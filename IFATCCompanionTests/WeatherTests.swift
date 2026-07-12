import XCTest
import CoreLocation
@testable import IFATCCompanion

final class WeatherTests: XCTestCase {

    func testRawMETARParsing() {
        let m = METARParser.parseRaw("KMSP 281953Z 32012KT 10SM BKN025 18/11 A3012")
        XCTAssertNotNil(m)
        XCTAssertEqual(m?.icao, "KMSP")
        XCTAssertEqual(m?.windDirection, 320)
        XCTAssertEqual(m?.windSpeed, 12)
        XCTAssertEqual(m?.visibilitySM, 10)
        XCTAssertEqual(m?.ceilingFt, 2500)
        XCTAssertEqual(m?.temperatureC, 18)
        XCTAssertEqual(m?.dewpointC, 11)
        XCTAssertEqual(m?.altimeterInHg ?? 0, 30.12, accuracy: 0.001)
    }

    func testRawMETARGustParsing() {
        let m = METARParser.parseRaw("KDEN 281953Z 02015G24KT 10SM SCT080 24/06 A2998")
        XCTAssertEqual(m?.windDirection, 20)
        XCTAssertEqual(m?.windSpeed, 15)
        XCTAssertEqual(m?.windGust, 24)
    }

    func testRouteAnalyzerFiltersByCorridorAndAltitude() {
        var analyzer = WeatherRouteAnalyzer()
        analyzer.config.corridorNM = 100
        analyzer.config.altitudeBandFt = 5000

        let position = CLLocationCoordinate2D(latitude: 40, longitude: -95)
        let end = CLLocationCoordinate2D(latitude: 44, longitude: -93)

        let ahead = PIREP(raw: "ahead", coordinate: CLLocationCoordinate2D(latitude: 42, longitude: -94),
                          altitudeFt: 35000, turbulence: .moderate, icing: nil, time: nil, aircraftType: nil)
        let behind = PIREP(raw: "behind", coordinate: CLLocationCoordinate2D(latitude: 38, longitude: -96),
                           altitudeFt: 35000, turbulence: .moderate, icing: nil, time: nil, aircraftType: nil)
        let wrongAlt = PIREP(raw: "wrongAlt", coordinate: CLLocationCoordinate2D(latitude: 42, longitude: -94),
                             altitudeFt: 20000, turbulence: .light, icing: nil, time: nil, aircraftType: nil)
        let smooth = PIREP(raw: "smooth", coordinate: CLLocationCoordinate2D(latitude: 42, longitude: -94),
                           altitudeFt: 35000, turbulence: .smooth, icing: nil, time: nil, aircraftType: nil)

        let items = analyzer.relevantReports(pireps: [ahead, behind, wrongAlt, smooth],
                                             position: position, routeEnd: end, altitudeFt: 35000)
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items.first?.severity, .moderate)
        XCTAssertGreaterThan(items.first?.distanceAheadNM ?? 0, 0)
    }

    /// A PIREP at cruise altitude is relevant when evaluated against the planned
    /// cruise level, but not when evaluated against a much lower climb altitude —
    /// this is why the ride model keys route reports off the flight-plan cruise
    /// altitude (within tolerance) rather than the live altitude while climbing.
    func testReportsFilteredAgainstCruiseAltitudeWithinTolerance() {
        var analyzer = WeatherRouteAnalyzer()
        analyzer.config.corridorNM = 100
        analyzer.config.altitudeBandFt = 5000

        let position = CLLocationCoordinate2D(latitude: 40, longitude: -95)
        let end = CLLocationCoordinate2D(latitude: 44, longitude: -93)
        let atCruise = PIREP(raw: "cruise", coordinate: CLLocationCoordinate2D(latitude: 42, longitude: -94),
                             altitudeFt: 35000, turbulence: .moderate, icing: nil, time: nil, aircraftType: nil)

        // Referenced against the planned cruise level → kept (within ±5000).
        let atCruiseRef = analyzer.relevantReports(pireps: [atCruise], position: position,
                                                   routeEnd: end, altitudeFt: 35000)
        XCTAssertEqual(atCruiseRef.count, 1)

        // Referenced against a 12,000 ft climb altitude → dropped (outside ±5000).
        let atClimbRef = analyzer.relevantReports(pireps: [atCruise], position: position,
                                                  routeEnd: end, altitudeFt: 12000)
        XCTAssertTrue(atClimbRef.isEmpty)
    }

    func testEmptyPirepsProducesNoItems() {
        let analyzer = WeatherRouteAnalyzer()
        let items = analyzer.relevantReports(pireps: [],
                                             position: CLLocationCoordinate2D(latitude: 40, longitude: -95),
                                             routeEnd: CLLocationCoordinate2D(latitude: 44, longitude: -93),
                                             altitudeFt: 35000)
        XCTAssertTrue(items.isEmpty)
    }

    func testTurbulenceSeverityParsing() {
        XCTAssertEqual(TurbulenceSeverity.parse("MOD"), .moderate)
        XCTAssertEqual(TurbulenceSeverity.parse("SEV"), .severe)
        XCTAssertEqual(TurbulenceSeverity.parse("LGT CHOP"), .light)  // contains LGT
        XCTAssertEqual(TurbulenceSeverity.parse("CHOP"), .lightChop)
    }

    /// Real AWC `pirep?format=json` shape: flight level is `fltLvl` (camelCase) and
    /// turbulence is a code *string* in `tbInt1` (not an Int). Locks the parser to it.
    func testPIREPParserMatchesRealAWCJSON() {
        let json = """
        [
          {"obsTime":1783796520,"lat":38.04,"lon":-87.53,"fltLvl":0,"fltLvlType":"DURD",
           "tbInt1":"","acType":"E55P","rawOb":"EVV UA /OV EVV/TM 1902/FLDURD/TP E55P/SK BKN020"},
          {"obsTime":1783796460,"lat":27.03,"lon":-81.80,"fltLvl":190,"fltLvlType":"OTHER",
           "tbInt1":"NEG","acType":"E50P","rawOb":"RSW UA /OV RSW360030/TM 1901/FL190/TP E50P/SK SKC/TB NEG"},
          {"obsTime":1783796400,"lat":43.55,"lon":-116.19,"fltLvl":110,"fltLvlType":"OTHER",
           "tbInt1":"MOD","acType":"E75L","rawOb":"BOI UA /OV SPUUD4 STAR/TM 1900/FL110/TP E75L/TB MOD TURB 110-090 DURD"}
        ]
        """.data(using: .utf8)!

        let pireps = PIREPParser.parseJSON(json)
        XCTAssertEqual(pireps.count, 3)

        // fltLvl (camelCase) → feet; a 0 / during-descent level stays unknown (nil).
        XCTAssertNil(pireps[0].altitudeFt, "fltLvl 0 (DURD) is unknown, not sea level")
        XCTAssertEqual(pireps[1].altitudeFt, 19000)
        XCTAssertEqual(pireps[2].altitudeFt, 11000)

        // tbInt1 is a code string: NEG → smooth (filtered out), MOD → moderate.
        XCTAssertEqual(pireps[1].turbulence, .smooth)
        XCTAssertEqual(pireps[2].turbulence, .moderate)

        XCTAssertEqual(pireps[2].coordinate?.latitude ?? 0, 43.55, accuracy: 1e-6)
        XCTAssertNotNil(pireps[2].time, "obsTime epoch parses to a date")
    }

    func testRideReportEngineNoReports() {
        let engine = PhraseologyEngine(digitStyle: .individual)
        let ride = RideReportEngine(engine: engine)
        let cs = engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        let tx = ride.rideReport(items: [], callsign: cs)
        XCTAssertTrue(tx.displayText.contains("no significant ride reports"))
    }

    private func rideItem(_ sev: TurbulenceSeverity, altFt: Int, type: String = "B738") -> RideReportItem {
        RideReportItem(severity: sev, altitudeBand: nil, distanceAheadNM: 30, bearing: 0,
                       nearFix: nil, sourceRaw: "", reportedAltitudeFt: altFt, aircraftType: type)
    }

    func testSmootherAltitudePicksNearestSmootherLevelInBand() {
        let analyzer = WeatherRouteAnalyzer()
        // Moderate at FL350; a smooth report at FL390 (4000 ft away) and a light one at
        // FL330 (2000 ft away). The nearest smoother level wins even though FL390 is
        // smoother — least altitude change to reach a better ride.
        let items = [rideItem(.moderate, altFt: 35000),
                     rideItem(.smooth, altFt: 39000),
                     rideItem(.light, altFt: 33000)]
        let s = analyzer.smootherAltitude(items: items, referenceAltFt: 35000, currentSeverity: .moderate)
        XCTAssertEqual(s?.altitudeFt, 33000, "prefers the nearest smoother level")
        XCTAssertEqual(s?.higher, false)
    }

    func testSmootherAltitudeBreaksSeparationTieBySmootherRide() {
        let analyzer = WeatherRouteAnalyzer()
        // Two candidates equidistant from FL350: light at FL370 (+2000) and smooth at
        // FL330 (-2000). Equal altitude change → the smoother of the two wins.
        let items = [rideItem(.moderate, altFt: 35000),
                     rideItem(.light, altFt: 37000),
                     rideItem(.smooth, altFt: 33000)]
        let s = analyzer.smootherAltitude(items: items, referenceAltFt: 35000, currentSeverity: .moderate)
        XCTAssertEqual(s?.altitudeFt, 33000, "at equal separation, prefers the smoother ride")
        XCTAssertEqual(s?.severity, .smooth)
    }

    func testSmootherAltitudeIsDataDrivenAndBandBounded() {
        let analyzer = WeatherRouteAnalyzer()
        // Nothing smoother than the current level → no suggestion (never invented).
        XCTAssertNil(analyzer.smootherAltitude(items: [rideItem(.moderate, altFt: 35000)],
                                               referenceAltFt: 35000, currentSeverity: .moderate))
        // A smooth report above the cruise band (FL450) is out of range → not suggested.
        XCTAssertNil(analyzer.smootherAltitude(items: [rideItem(.smooth, altFt: 45000)],
                                               referenceAltFt: 35000, currentSeverity: .moderate))
        // Smooth at your own level isn't a level change.
        XCTAssertNil(analyzer.smootherAltitude(items: [rideItem(.smooth, altFt: 35000)],
                                               referenceAltFt: 35000, currentSeverity: .moderate))
    }

    func testRideReportRelaysPIREPAtAltitudeAndNamesSmootherLevel() {
        let engine = PhraseologyEngine(digitStyle: .individual)
        let ride = RideReportEngine(engine: engine)
        let cs = engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        let lead = RideReportItem(severity: .moderate, altitudeBand: 33000...37000, distanceAheadNM: 40,
                                  bearing: 0, nearFix: "DSM", sourceRaw: "", ageMinutes: 15,
                                  reportedAltitudeFt: 35000, aircraftType: "B738")
        let assessment = RideAssessment(index: 0.6, severity: .moderate, contributors: ["pilot reports"])
        let smoother = SmootherAltitude(altitudeFt: 39000, severity: .smooth, aircraftType: "A320", higher: true)
        let tx = ride.rideReport(assessment: assessment, items: [lead],
                                 referenceAltitudeFt: 35000, smoother: smoother, callsign: cs)
        XCTAssertTrue(tx.displayText.contains("moderate turbulence"))
        XCTAssertTrue(tx.displayText.contains("FL350"), "relays the report's own altitude")
        XCTAssertTrue(tx.displayText.contains("near DSM"))
        XCTAssertTrue(tx.displayText.contains("FL390"), "names the specific smoother level")
        XCTAssertTrue(tx.displayText.lowercased().contains("climb"))
    }

    func testRideReportFallsBackToGenericOfferWithoutSmootherData() {
        let engine = PhraseologyEngine(digitStyle: .individual)
        let ride = RideReportEngine(engine: engine)
        let cs = engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        let lead = RideReportItem(severity: .moderate, altitudeBand: nil, distanceAheadNM: 25,
                                  bearing: 0, nearFix: nil, sourceRaw: "", reportedAltitudeFt: 35000)
        let assessment = RideAssessment(index: 0.6, severity: .moderate, contributors: [])
        let tx = ride.rideReport(assessment: assessment, items: [lead],
                                 referenceAltitudeFt: 35000, smoother: nil, callsign: cs)
        XCTAssertTrue(tx.displayText.contains("higher or lower"), "generic offer when no level is supported")
    }

    /// Without a live aircraft fix the analysis falls back to the departure airport, so the
    /// along-track distance is origin-relative. It must be flagged and NOT presented as
    /// "… miles ahead" — that was the distance-from-origin bug on the ride-report response.
    func testRideReportOmitsDistanceWhenPositionIsNotLiveAircraft() {
        var analyzer = WeatherRouteAnalyzer()
        analyzer.config.corridorNM = 100
        analyzer.config.altitudeBandFt = 5000

        let departure = CLLocationCoordinate2D(latitude: 30, longitude: -95)
        let end = CLLocationCoordinate2D(latitude: 44, longitude: -93)
        let pirep = PIREP(raw: "sev", coordinate: CLLocationCoordinate2D(latitude: 37, longitude: -94),
                          altitudeFt: 36000, turbulence: .severe, icing: nil, time: nil, aircraftType: "A319")

        let items = analyzer.relevantReports(pireps: [pirep], position: departure, routeEnd: end,
                                             altitudeFt: 36000, positionIsLiveAircraft: false)
        XCTAssertEqual(items.count, 1)
        XCTAssertFalse(items.first?.distanceIsFromAircraft ?? true)
        // The distance is still computed (the turbulence model weights by it) — only the
        // presentation is suppressed.
        XCTAssertGreaterThan(items.first?.distanceAheadNM ?? 0, 0)

        let engine = PhraseologyEngine(digitStyle: .individual)
        let ride = RideReportEngine(engine: engine)
        let cs = engine.callsign(airline: "United", flightNumber: "1678", fallback: "")
        let assessment = RideAssessment(index: 0.9, severity: .severe, contributors: ["pilot reports"])
        let tx = ride.rideReport(assessment: assessment, items: items, referenceAltitudeFt: 36000,
                                 smoother: nil, callsign: cs)
        XCTAssertTrue(tx.displayText.contains("severe turbulence"))
        XCTAssertFalse(tx.displayText.contains("miles ahead"),
                       "no origin-relative distance is presented without a live aircraft fix")
        XCTAssertFalse(tx.spokenText.contains("miles ahead"))
    }

    /// With a live aircraft fix the distance is aircraft-relative and IS presented.
    func testRideReportShowsAircraftRelativeDistanceWithLivePosition() {
        var analyzer = WeatherRouteAnalyzer()
        analyzer.config.corridorNM = 100
        analyzer.config.altitudeBandFt = 5000

        let aircraft = CLLocationCoordinate2D(latitude: 40, longitude: -94.5)
        let end = CLLocationCoordinate2D(latitude: 44, longitude: -93)
        let pirep = PIREP(raw: "sev", coordinate: CLLocationCoordinate2D(latitude: 42, longitude: -94),
                          altitudeFt: 36000, turbulence: .severe, icing: nil, time: nil, aircraftType: "A319")

        // positionIsLiveAircraft defaults to true.
        let items = analyzer.relevantReports(pireps: [pirep], position: aircraft, routeEnd: end,
                                             altitudeFt: 36000)
        XCTAssertTrue(items.first?.distanceIsFromAircraft ?? false)

        let engine = PhraseologyEngine(digitStyle: .individual)
        let ride = RideReportEngine(engine: engine)
        let cs = engine.callsign(airline: "United", flightNumber: "1678", fallback: "")
        let assessment = RideAssessment(index: 0.9, severity: .severe, contributors: [])
        let tx = ride.rideReport(assessment: assessment, items: items, referenceAltitudeFt: 36000,
                                 smoother: nil, callsign: cs)
        XCTAssertTrue(tx.displayText.contains("miles ahead"),
                      "a live aircraft fix yields an aircraft-relative distance")
    }

    func testDestinationWeatherSpoken() {
        let engine = PhraseologyEngine(digitStyle: .individual)
        let ride = RideReportEngine(engine: engine)
        let cs = engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        let metar = METARParser.parseRaw("KMSP 281953Z 32012KT 10SM BKN025 18/11 A3012")
        let tx = ride.destinationWeather(metar: metar, callsign: cs, icao: "KMSP")
        XCTAssertTrue(tx.spokenText.contains("wind three two zero at one two"))
        XCTAssertTrue(tx.displayText.contains("Minneapolis"))
    }

    /// The pilot acknowledges an informational reply (ride report / destination
    /// weather) with a courtesy "Roger", addressed to the working controller.
    func testPilotRogerAcknowledgement() {
        let engine = PhraseologyEngine(digitStyle: .individual)
        let pilot = PilotResponseEngine(engine: engine)
        let tx = pilot.roger(context: TestSupport.context(), facility: .center)
        XCTAssertEqual(tx.sender, .pilot)
        XCTAssertEqual(tx.facility, .center)
        XCTAssertTrue(tx.displayText.contains("Roger"))
        XCTAssertTrue(tx.displayText.contains("United 598"))
    }
}
