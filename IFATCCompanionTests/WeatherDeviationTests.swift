import XCTest
import CoreLocation
import MapKit
@testable import IFATCCompanion

/// Tests for the NOAA radar precipitation + simulated weather-deviation feature.
/// All logic under test is deterministic; nothing here touches the network.
final class WeatherDeviationTests: XCTestCase {

    private let detector = RouteWeatherConflictDetector()

    // A point in the central U.S. and a course due north.
    private let usPosition = CLLocationCoordinate2D(latitude: 40, longitude: -95)
    private let course = 0.0

    // MARK: - Geometry helpers

    /// A course-aligned box centered `alongNM` ahead and `crossNM` to the side
    /// (positive = right of course), sized ±`halfAlong`/±`halfCross` NM.
    private func cell(alongNM: Double, crossNM: Double,
                      halfAlong: Double = 10, halfCross: Double = 10,
                      course: Double = 0,
                      from position: CLLocationCoordinate2D) -> [CLLocationCoordinate2D] {
        let onCourse = Geo.destination(from: position, bearingDegrees: course, distanceNM: alongNM)
        let center = Geo.destination(from: onCourse, bearingDegrees: course + 90, distanceNM: crossNM)
        func pt(_ a: Double, _ c: Double) -> CLLocationCoordinate2D {
            let p = Geo.destination(from: center, bearingDegrees: course, distanceNM: a)
            return Geo.destination(from: p, bearingDegrees: course + 90, distanceNM: c)
        }
        return [pt(-halfAlong, -halfCross), pt(halfAlong, -halfCross),
                pt(halfAlong, halfCross), pt(-halfAlong, halfCross)]
    }

    private func radarHazard(_ polygon: [CLLocationCoordinate2D],
                             intensity: WeatherIntensity = .heavy,
                             move: (Double, Double)? = (90, 20)) -> WeatherHazard {
        WeatherHazard(source: .noaaRadar, phenomenon: .precipitation, intensity: intensity,
                      geometry: .polygon(polygon), confidence: .high,
                      movementDirectionDegrees: move?.0, movementSpeedKnots: move?.1)
    }

    // MARK: - NOAA coverage

    func testNOAARadarCoverageAvailablePath() {
        // Central U.S. is covered.
        XCTAssertTrue(NOAARadarPrecipitationProvider.covers(coordinate: usPosition))
        let region = MKCoordinateRegion(center: usPosition,
                                        span: MKCoordinateSpan(latitudeDelta: 2, longitudeDelta: 2))
        XCTAssertTrue(NOAARadarPrecipitationProvider.covers(region: region))
    }

    func testNOAARadarUnavailableRegionPath() {
        // Central Europe is outside NOAA radar coverage — no global assumption.
        let paris = CLLocationCoordinate2D(latitude: 48.85, longitude: 2.35)
        XCTAssertFalse(NOAARadarPrecipitationProvider.covers(coordinate: paris))
        let region = MKCoordinateRegion(center: paris,
                                        span: MKCoordinateSpan(latitudeDelta: 2, longitudeDelta: 2))
        XCTAssertFalse(NOAARadarPrecipitationProvider.covers(region: region))
    }

    // MARK: - Conflict detection

    func testRouteCorridorIntersectsPrecipitationHazard() {
        let hazard = radarHazard(cell(alongNM: 40, crossNM: 0, from: usPosition))
        let conflict = detector.detectConflict(position: usPosition, course: course,
                                               groundspeedKnots: 450, phase: .cruise,
                                               hazards: [hazard], waypoints: [])
        XCTAssertNotNil(conflict, "a precipitation cell across the corridor must be detected")
        XCTAssertEqual(conflict?.severity, .heavy)
        XCTAssertEqual(conflict?.source, .noaaRadar)
    }

    func testNoHazardsMeansNoConflict() {
        // Missing reports never fabricate a conflict.
        let conflict = detector.detectConflict(position: usPosition, course: course,
                                               groundspeedKnots: 450, phase: .cruise,
                                               hazards: [], waypoints: [])
        XCTAssertNil(conflict)
    }

    func testDistanceToPrecipitation() {
        // Cell centered 40 NM ahead, ±10 NM along-track → near edge ≈ 30 NM.
        let hazard = radarHazard(cell(alongNM: 40, crossNM: 0, halfAlong: 10, from: usPosition))
        let conflict = detector.detectConflict(position: usPosition, course: course,
                                               groundspeedKnots: 450, phase: .cruise,
                                               hazards: [hazard], waypoints: [])
        let distance = try? XCTUnwrap(conflict?.distanceAheadNM)
        XCTAssertNotNil(distance)
        XCTAssertGreaterThan(distance ?? 0, 15)
        XCTAssertLessThan(distance ?? 999, 45)
    }

    func testClockPositionFormatting() {
        XCTAssertEqual(RouteWeatherConflictDetector.clockPosition(relBearing: 0), 12)
        XCTAssertEqual(RouteWeatherConflictDetector.clockPosition(relBearing: 90), 3)
        XCTAssertEqual(RouteWeatherConflictDetector.clockPosition(relBearing: -90), 9)
        XCTAssertEqual(RouteWeatherConflictDetector.clockPosition(relBearing: 180), 6)
        XCTAssertEqual(RouteWeatherConflictDetector.clockPosition(relBearing: 30), 1)
        XCTAssertEqual(RouteWeatherConflictDetector.clockPosition(relBearing: -30), 11)
        XCTAssertEqual(RouteWeatherConflictDetector.clockPosition(relBearing: 60), 2)
    }

    func testLeftRightDeviationScoring() {
        // Cell biased to the RIGHT of course → the cleaner bypass is LEFT.
        let rightCell = radarHazard(cell(alongNM: 40, crossNM: 8, from: usPosition))
        let rightConflict = detector.detectConflict(position: usPosition, course: course,
                                                    groundspeedKnots: 450, phase: .cruise,
                                                    hazards: [rightCell], waypoints: [])
        XCTAssertEqual(rightConflict?.recommendedDirection, .left,
                       "a cell to the right should be bypassed on the left")

        // Cell biased to the LEFT of course → bypass RIGHT.
        let leftCell = radarHazard(cell(alongNM: 40, crossNM: -8, from: usPosition))
        let leftConflict = detector.detectConflict(position: usPosition, course: course,
                                                   groundspeedKnots: 450, phase: .cruise,
                                                   hazards: [leftCell], waypoints: [])
        XCTAssertEqual(leftConflict?.recommendedDirection, .right)
    }

    func testRejoinFixSelection() {
        let hazard = radarHazard(cell(alongNM: 40, crossNM: 0, from: usPosition))
        // A filed fix 100 NM ahead, downstream of the weather.
        let downstream = Geo.destination(from: usPosition, bearingDegrees: course, distanceNM: 100)
        let wp = Waypoint(name: "FODAK", latitude: downstream.latitude, longitude: downstream.longitude)
        let conflict = detector.detectConflict(position: usPosition, course: course,
                                               groundspeedKnots: 450, phase: .cruise,
                                               hazards: [hazard], waypoints: [wp])
        XCTAssertEqual(conflict?.rejoinFix?.name, "FODAK")
    }

    func testNoRejoinFixFallback() {
        let hazard = radarHazard(cell(alongNM: 40, crossNM: 0, from: usPosition))
        let conflict = detector.detectConflict(position: usPosition, course: course,
                                               groundspeedKnots: 450, phase: .cruise,
                                               hazards: [hazard], waypoints: [])
        XCTAssertNil(conflict?.rejoinFix, "no downstream fix means no rejoin fix")

        // The phraseology then falls back to "advise clear of weather".
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        let phr = WeatherDeviationPhraseology(engine: engine)
        let cs = engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        let tx = phr.approvalNoRejoin(cs: cs, direction: .right, degrees: 20, maintainAltitude: 37000)
        XCTAssertTrue(tx.displayText.contains("advise clear of weather"))
        XCTAssertFalse(tx.displayText.contains("proceed direct"))
    }

    // MARK: - STAR handling

    func testStarDeviationAssignsMaintainAltitude() {
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        let phr = WeatherDeviationPhraseology(engine: engine)
        let cs = engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        let tx = phr.starDeviationApproval(cs: cs, direction: .right, degrees: nil,
                                           maintainAltitude: 11000, starDisplay: "MUSCL TWO",
                                           starSpoken: "MUSCL TWO", rejoinFix: "GEP")
        XCTAssertTrue(tx.displayText.contains("maintain 11,000"),
                      "off-procedure deviation must preserve the altitude restriction")
        XCTAssertTrue(tx.displayText.contains("expect to rejoin the MUSCL TWO arrival at GEP"))
    }

    func testRejoinStarPhraseology() {
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        let phr = WeatherDeviationPhraseology(engine: engine)
        let cs = engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        let tx = phr.rejoinStar(cs: cs, rejoinFix: "GEP", starDisplay: "MUSCL TWO", starSpoken: "MUSCL TWO")
        XCTAssertTrue(tx.displayText.contains("cleared direct GEP"))
        XCTAssertTrue(tx.displayText.contains("descend via the MUSCL TWO arrival"))
    }

    // MARK: - Terminology

    func testPrecipitationWordingForRadarDerivedWeather() {
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        let phr = WeatherDeviationPhraseology(engine: engine)
        let cs = engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        let hazard = radarHazard([usPosition, usPosition, usPosition], intensity: .heavy)
        let conflict = RouteWeatherConflict(
            hazard: hazard, distanceAheadNM: 30, relativeBearingDegrees: 0,
            leftClock: 12, centerClock: 12, rightClock: 12, estimatedTimeMinutes: nil,
            severity: .heavy, leftBypassScore: 0, rightBypassScore: 0,
            recommendedDirection: .right, recommendedDeviationDegrees: 20,
            rejoinFix: nil, originalSegment: nil, shouldPrompt: true,
            intersectionArea: [], deviationPath: [])
        let tx = phr.radarAdvisory(cs: cs, conflict: conflict)
        XCTAssertTrue(tx.displayText.contains("precipitation"),
                      "radar-derived weather must be spoken as precipitation")
        XCTAssertFalse(tx.displayText.lowercased().contains("turbulence"),
                       "radar colors must never be called turbulence")
    }

    func testTurbulenceWordingOnlyFromTurbulenceSpecificSources() {
        // Turbulence-capable sources.
        for source in [WeatherHazardSource.pirep, .sigmet, .cwa, .gairmet] {
            XCTAssertTrue(source.supportsTurbulenceWording, "\(source) should support turbulence wording")
        }
        // Precipitation / surface-only sources.
        for source in [WeatherHazardSource.noaaRadar, .metar, .taf] {
            XCTAssertFalse(source.supportsTurbulenceWording, "\(source) must not imply turbulence")
        }
    }

    func testIntensityUnknownWording() {
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        let phr = WeatherDeviationPhraseology(engine: engine)
        let cs = engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        let hazard = radarHazard([usPosition, usPosition, usPosition], intensity: .unknown, move: nil)
        let conflict = RouteWeatherConflict(
            hazard: hazard, distanceAheadNM: 30, relativeBearingDegrees: 0,
            leftClock: 12, centerClock: 12, rightClock: 12, estimatedTimeMinutes: nil,
            severity: .unknown, leftBypassScore: 0, rightBypassScore: 0,
            recommendedDirection: .right, recommendedDeviationDegrees: 20,
            rejoinFix: nil, originalSegment: nil, shouldPrompt: true,
            intersectionArea: [], deviationPath: [])
        let tx = phr.radarAdvisory(cs: cs, conflict: conflict)
        XCTAssertTrue(tx.displayText.contains("intensity unknown"))
    }

    func testMovementUnknownWording() {
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        let phr = WeatherDeviationPhraseology(engine: engine)
        let cs = engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        let hazard = radarHazard([usPosition, usPosition, usPosition], intensity: .heavy, move: nil)
        let conflict = RouteWeatherConflict(
            hazard: hazard, distanceAheadNM: 30, relativeBearingDegrees: 0,
            leftClock: 12, centerClock: 12, rightClock: 12, estimatedTimeMinutes: nil,
            severity: .heavy, leftBypassScore: 0, rightBypassScore: 0,
            recommendedDirection: .right, recommendedDeviationDegrees: 20,
            rejoinFix: nil, originalSegment: nil, shouldPrompt: true,
            intersectionArea: [], deviationPath: [])
        let tx = phr.radarAdvisory(cs: cs, conflict: conflict)
        XCTAssertTrue(tx.displayText.contains("movement unknown"))
    }

    // MARK: - Global / non-U.S. handling

    func testGlobalSigmetHandlingOutsideRadarCoverage() {
        // A SIGMET along a European route is still handled even though NOAA radar
        // does not cover the region.
        let paris = CLLocationCoordinate2D(latitude: 48.85, longitude: 2.35)
        XCTAssertFalse(NOAARadarPrecipitationProvider.covers(coordinate: paris))
        let polygon = cell(alongNM: 40, crossNM: 0, from: paris)
        let sigmet = WeatherHazard(source: .sigmet, phenomenon: .thunderstorm, intensity: .extreme,
                                   geometry: .polygon(polygon), confidence: .medium)
        let conflict = detector.detectConflict(position: paris, course: course,
                                               groundspeedKnots: 450, phase: .cruise,
                                               hazards: [sigmet], waypoints: [])
        XCTAssertNotNil(conflict, "a SIGMET on the route is applicable globally")
        XCTAssertEqual(conflict?.source, .sigmet)
        XCTAssertTrue(conflict?.isConvectiveSigmet ?? false)
    }

    func testNoGAirmetGlobalAssumption() {
        // G-AIRMET is a contiguous-U.S. concept; the app never treats NOAA-tied
        // data as globally available.
        let tokyo = CLLocationCoordinate2D(latitude: 35.68, longitude: 139.77)
        XCTAssertFalse(NOAARadarPrecipitationProvider.covers(coordinate: tokyo))
        XCTAssertEqual(WeatherHazardSource.gairmet.label, "G-AIRMET")
    }

    // MARK: - Radar unavailable fallback

    func testRadarUnavailableGracefulFallback() {
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        let phr = WeatherDeviationPhraseology(engine: engine)
        let cs = engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        let tx = phr.noRadarNoAdvisory(cs: cs)
        XCTAssertTrue(tx.displayText.contains("radar precipitation is not available for this region"))
        XCTAssertTrue(tx.displayText.contains("No significant aviation weather advisories are available"))
    }

    // MARK: - Provider architecture

    func testOnlyNOAAAndMockProvidersPresent() {
        let noaa = NOAARadarPrecipitationProvider()
        XCTAssertEqual(noaa.id, "noaa-nws-radar")
        XCTAssertTrue(noaa.supportsTrueRadar)
        let mock = MockRadarPrecipitationProvider()
        XCTAssertFalse(mock.supportsTrueRadar, "the mock provider must not advertise true radar")
    }

    func testNOAAExportURLIsWellFormedAndKeyless() {
        let noaa = NOAARadarPrecipitationProvider()
        let bbox = RadarBoundingBox(minLatitude: 38, minLongitude: -97, maxLatitude: 42, maxLongitude: -93)
        let url = noaa.exportImageURL(for: bbox, size: CGSize(width: 600, height: 400), frame: nil)
        let s = try? XCTUnwrap(url?.absoluteString)
        XCTAssertNotNil(s)
        XCTAssertTrue(s?.contains("exportImage") ?? false)
        XCTAssertFalse(s?.lowercased().contains("apikey") ?? true, "NOAA export must not carry an API key")
        XCTAssertFalse(s?.lowercased().contains("token") ?? true)
    }

    func testMockProviderRendersNoImage() async throws {
        let mock = MockRadarPrecipitationProvider()
        let bbox = RadarBoundingBox(minLatitude: 38, minLongitude: -97, maxLatitude: 42, maxLongitude: -93)
        let frame = RadarFrame(id: "m", timestamp: Date(), label: "m")
        let data = try await mock.exportImage(for: bbox, size: CGSize(width: 10, height: 10), frame: frame)
        XCTAssertNil(data, "mock precipitation is drawn as polygons, never a radar image")
        XCTAssertNil(mock.exportImageURL(for: bbox, size: CGSize(width: 10, height: 10), frame: frame))
    }
}
