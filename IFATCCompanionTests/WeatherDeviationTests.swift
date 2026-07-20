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

    // MARK: - Gap threading

    /// The signed cross-track offset (+right) of a coordinate from the northbound
    /// course line out of `usPosition`.
    private func offsetFromCourse(_ point: CLLocationCoordinate2D) -> Double {
        let end = Geo.destination(from: usPosition, bearingDegrees: course, distanceNM: 200)
        return Geo.crossTrackDistanceNM(point: point, pathStart: usPosition, pathEnd: end)
    }

    /// Assert a reroute path stays clear of every cell along its whole length
    /// (sampling each leg), ignoring the immediate vicinity of the aircraft.
    private func assertPathClear(_ path: [CLLocationCoordinate2D], of polys: [[CLLocationCoordinate2D]],
                                 file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertGreaterThanOrEqual(path.count, 2, "no reroute path was produced", file: file, line: line)
        for i in 0..<(path.count - 1) {
            let a = path[i], b = path[i + 1]
            for s in 0...40 {
                let f = Double(s) / 40
                let p = CLLocationCoordinate2D(latitude: a.latitude + (b.latitude - a.latitude) * f,
                                               longitude: a.longitude + (b.longitude - a.longitude) * f)
                guard Geo.distanceNM(from: usPosition, to: p) > 8 else { continue }
                for poly in polys {
                    XCTAssertFalse(WeatherRouteAnalyzer.pointInPolygon(p, poly),
                                   "reroute enters a precipitation cell", file: file, line: line)
                }
            }
        }
    }

    func testThreadsGapBetweenAdjacentCells() throws {
        // A line of two cells ~40 NM ahead: a large cell that just crosses the course
        // to the left, and a cell to the right — leaving a clear gap on the right.
        let leftCell = radarHazard(cell(alongNM: 40, crossNM: -24, halfCross: 26, from: usPosition))
        let rightCell = radarHazard(cell(alongNM: 40, crossNM: 36, halfCross: 14, from: usPosition))
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: [leftCell, rightCell], waypoints: []))

        XCTAssertEqual(conflict.recommendedDirection, .right, "the clear gap is on the right")
        // The apex should thread the gap (a modest offset), not fly around the whole
        // line (which would be a much larger offset).
        let offset = offsetFromCourse(conflict.deviationPath[1])
        XCTAssertGreaterThan(offset, 4, "apex should sit right of course, inside the gap")
        XCTAssertLessThan(offset, 30, "apex should thread the gap, not round the whole line")
    }

    func testGoesAroundNearEndOfSolidLine() throws {
        // A single wide cell with no gap, biased left of course → the shorter way
        // around is the right end.
        let wide = radarHazard(cell(alongNM: 40, crossNM: -10, halfCross: 40, from: usPosition))
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: [wide], waypoints: []))

        XCTAssertEqual(conflict.recommendedDirection, .right)
        let offset = offsetFromCourse(conflict.deviationPath[1])
        XCTAssertGreaterThan(offset, 25, "no gap → route around the near (right) end, a large offset")
        // The reroute around the wide cell must actually stay clear of it — a single
        // dogleg to the shared rejoin clips the near corner, so a side-hug is used.
        assertPathClear(conflict.deviationPath, of: [wide.geometry.polygonPoints ?? []])
    }

    func testReroutePathStaysClearAcrossADiagonalLine() throws {
        // A line of cells angling across course (near end left of course, far end well
        // right of it): the classic case where a single dogleg to the shared rejoin
        // cuts back through the line. The reroute must still stay clear of every cell
        // along its whole length — a side-hug down one edge of the line.
        let polys = [
            cell(alongNM: 30,  crossNM: -30, halfCross: 12, from: usPosition),
            cell(alongNM: 55,  crossNM: -10, halfCross: 12, from: usPosition),
            cell(alongNM: 80,  crossNM: 10,  halfCross: 12, from: usPosition),
            cell(alongNM: 105, crossNM: 30,  halfCross: 12, from: usPosition),
        ]
        let downstream = Geo.destination(from: usPosition, bearingDegrees: course, distanceNM: 160)
        let wp = Waypoint(name: "RJOIN", latitude: downstream.latitude, longitude: downstream.longitude)
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: polys.map { radarHazard($0) }, waypoints: [wp]))

        assertPathClear(conflict.deviationPath, of: polys)
    }

    func testTakesShorterSideAroundLineLeaningOneWay() throws {
        // A line that just touches the course at its near end and then leans hard to
        // the right. The shorter reroute is a small jog LEFT past the near cell, not a
        // long loop around the far right end. The path must take the left side and
        // stay clear — never swinging out to the far (right) edge of the line.
        let polys = [
            cell(alongNM: 40,  crossNM: 0,  halfCross: 12, from: usPosition),
            cell(alongNM: 80,  crossNM: 25, halfCross: 12, from: usPosition),
            cell(alongNM: 120, crossNM: 50, halfCross: 12, from: usPosition),
            cell(alongNM: 160, crossNM: 75, halfCross: 12, from: usPosition),
        ]
        let downstream = Geo.destination(from: usPosition, bearingDegrees: course, distanceNM: 220)
        let wp = Waypoint(name: "RJOIN", latitude: downstream.latitude, longitude: downstream.longitude)
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: polys.map { radarHazard($0) }, waypoints: [wp]))

        XCTAssertEqual(conflict.recommendedDirection, .left, "the shorter way is left of the near cell")
        assertPathClear(conflict.deviationPath, of: polys)
        // It must not loop around the far right edge (~68 NM out); the left hug stays
        // within a modest offset of course the whole way.
        for point in conflict.deviationPath {
            XCTAssertLessThan(offsetFromCourse(point), 25,
                              "reroute must not swing out to the far right edge of the line")
        }
    }

    // MARK: - Turn bound (never reverse the aircraft)

    /// Every leg of the drawn mint line stays within the configured off-course turn
    /// bound, so the reroute never turns the aircraft the long way around. Uses a
    /// tight bound to force the clamp to engage on a path that would otherwise swing
    /// out to a large offset.
    func testDeviationPathRespectsTurnBound() throws {
        var tight = RouteWeatherConflictDetector()
        tight.config.maxDeviationTurnDegrees = 20
        let wide = radarHazard(cell(alongNM: 40, crossNM: -10, halfCross: 40, from: usPosition))
        let conflict = try XCTUnwrap(tight.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: [wide], waypoints: []))
        let path = conflict.deviationPath
        XCTAssertGreaterThanOrEqual(path.count, 2)
        for i in 0..<(path.count - 1) {
            let brg = Geo.bearing(from: path[i], to: path[i + 1])
            XCTAssertLessThanOrEqual(Geo.headingDifference(brg, course), 20 + 0.5,
                                     "leg \(i) turns beyond the deviation bound")
        }
    }

    /// At the default bound the mint line is never reversed: no leg exceeds ~100° off
    /// course, even for a diagonal line whose rejoin sits well downrange.
    func testDeviationPathNeverReversesAtDefaultBound() throws {
        let polys = [
            cell(alongNM: 30,  crossNM: -30, halfCross: 12, from: usPosition),
            cell(alongNM: 55,  crossNM: -10, halfCross: 12, from: usPosition),
            cell(alongNM: 80,  crossNM: 10,  halfCross: 12, from: usPosition),
            cell(alongNM: 105, crossNM: 30,  halfCross: 12, from: usPosition),
        ]
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: polys.map { radarHazard($0) }, waypoints: []))
        for i in 0..<(conflict.deviationPath.count - 1) {
            let brg = Geo.bearing(from: conflict.deviationPath[i], to: conflict.deviationPath[i + 1])
            XCTAssertLessThanOrEqual(Geo.headingDifference(brg, course), 100 + 0.5,
                                     "the mint line must never turn the aircraft around")
        }
    }

    // MARK: - Turn-back symmetry (gradual rejoin, not a 90° squeeze)

    /// A wide wall of precipitation squarely on course forces a side-hug down one edge.
    /// The closing leg back onto course must be a gradual (~30°) turn-back, not a ~90°
    /// sideways jog — the compressed-rejoin bug. The turn-out and parallel legs are
    /// unaffected; only the rejoin is pushed forward far enough to intercept gently.
    func testTurnBackIsGradualNotNinetyDegrees() throws {
        let wall = radarHazard(cell(alongNM: 60, crossNM: 0, halfAlong: 30, halfCross: 25, from: usPosition))
        let downstream = Geo.destination(from: usPosition, bearingDegrees: course, distanceNM: 260)
        let wp = Waypoint(name: "RJOIN", latitude: downstream.latitude, longitude: downstream.longitude)
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: [wall], waypoints: [wp]))
        let path = conflict.deviationPath
        XCTAssertGreaterThanOrEqual(path.count, 3, "a wall on course must produce a hug")
        let n = path.count
        let lastLeg = Geo.headingDifference(Geo.bearing(from: path[n - 2], to: path[n - 1]), course)
        XCTAssertLessThanOrEqual(lastLeg, 45, "the turn-back onto course must be gradual, not a ~90° squeeze")
        assertPathClear(path, of: [wall.geometry.polygonPoints ?? []])
    }

    // MARK: - Gentle-intercept safety net (no ~90° / backwards entries or exits)

    /// A course-aligned point `alongNM` ahead and `crossNM` to the side (+ = right).
    private func coursePoint(along: Double, cross: Double,
                             from position: CLLocationCoordinate2D, course: Double) -> CLLocationCoordinate2D {
        let onC = Geo.destination(from: position, bearingDegrees: course, distanceNM: along)
        return Geo.destination(from: onC, bearingDegrees: course + (cross >= 0 ? 90 : -90), distanceNM: abs(cross))
    }

    /// A hug whose closing leg jogs ~90° sideways back onto course — the squeeze that
    /// truncation / on-route snapping / a tight rejoin cap can leave — must be reshaped into
    /// a gentle ~30° turn-back, with the rejoin point itself left on the route.
    func testGentleInterceptReshapesASteepClosingLeg() {
        let p = usPosition
        // turn-out(0,0) → parallel-in(35,20) → parallel-out(80,20) → rejoin(82,0): the last leg
        // is a near-90° sideways jog from a 20 NM offset back to course in just 2 NM.
        let steep = [coursePoint(along: 0, cross: 0, from: p, course: course),
                     coursePoint(along: 35, cross: 20, from: p, course: course),
                     coursePoint(along: 80, cross: 20, from: p, course: course),
                     coursePoint(along: 82, cross: 0, from: p, course: course)]
        let out = detector.gentleInterceptAngles(steep, position: p, course: course, cores: [])
        let n = out.count
        let closing = Geo.headingDifference(Geo.bearing(from: out[n - 2], to: out[n - 1]), course)
        XCTAssertLessThanOrEqual(closing, 45, "the closing leg is reshaped to a gentle intercept, not a ~90° jog")
        XCTAssertEqual(out[n - 1].latitude, steep[3].latitude, accuracy: 1e-6, "the rejoin point stays on the route")
        XCTAssertEqual(out[n - 1].longitude, steep[3].longitude, accuracy: 1e-6)
    }

    /// A hug whose opening leg steps out ~90° sideways onto the offset must be reshaped into a
    /// gentle ~30° turn-out, with the turn-out point itself left on the route.
    func testGentleInterceptReshapesASteepOpeningLeg() {
        let p = usPosition
        // turn-out(40,0) → parallel-in(42,20): a near-90° step-out; the exit is already gentle.
        let steep = [coursePoint(along: 40, cross: 0, from: p, course: course),
                     coursePoint(along: 42, cross: 20, from: p, course: course),
                     coursePoint(along: 90, cross: 20, from: p, course: course),
                     coursePoint(along: 140, cross: 0, from: p, course: course)]
        let out = detector.gentleInterceptAngles(steep, position: p, course: course, cores: [])
        let opening = Geo.headingDifference(Geo.bearing(from: out[0], to: out[1]), course)
        XCTAssertLessThanOrEqual(opening, 45, "the opening leg is reshaped to a gentle turn-out, not a ~90° step")
        XCTAssertEqual(out[0].latitude, steep[0].latitude, accuracy: 1e-6, "the turn-out point stays on the route")
        XCTAssertEqual(out[0].longitude, steep[0].longitude, accuracy: 1e-6)
    }

    /// Best-effort: when the only way to gentle the intercept would drag the closing leg through
    /// an intense core, the reshape is declined and the original (steep-but-clear) leg is kept —
    /// a valid reroute is never bent into weather.
    func testGentleInterceptKeepsSteepLegRatherThanCutACore() {
        let p = usPosition
        let steep = [coursePoint(along: 0, cross: 0, from: p, course: course),
                     coursePoint(along: 35, cross: 20, from: p, course: course),
                     coursePoint(along: 80, cross: 20, from: p, course: course),
                     coursePoint(along: 82, cross: 0, from: p, course: course)]
        // A heavy core sitting where the pulled-back closing leg would descend through it.
        let core = cell(alongNM: 65, crossNM: 5, halfAlong: 12, halfCross: 10, from: p)
        let out = detector.gentleInterceptAngles(steep, position: p, course: course,
                                                 cores: [(polygon: core, clearance: 3.0)])
        let n = out.count
        let closing = Geo.headingDifference(Geo.bearing(from: out[n - 2], to: out[n - 1]), course)
        XCTAssertGreaterThan(closing, 50, "the steep leg is kept rather than reshaping it through a core")
    }

    // MARK: - Whole-path clearance (the return leg too)

    /// The entire drawn reroute — every leg, the return included — must clear every cell,
    /// not just the ones the initial candidate happened to rejoin past. A staggered line
    /// whose tail sits where a naive return leg would cut back through it is held on its
    /// offset longer (and widened if needed) until the whole path is clear.
    func testWholePathIncludingReturnLegClearsEveryCell() throws {
        let polys = [
            cell(alongNM: 35, crossNM: -18, halfCross: 14, from: usPosition),
            cell(alongNM: 55, crossNM: 0,   halfCross: 14, from: usPosition),
            cell(alongNM: 78, crossNM: 20,  halfCross: 14, from: usPosition),
        ]
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: polys.map { radarHazard($0) }, waypoints: []))
        assertPathClear(conflict.deviationPath, of: polys)
    }

    // MARK: - Engages-weather protection (no mint line in clear air)

    /// A reroute that runs entirely in clear air, far from every cell, must be recognized
    /// as *not* engaging the weather, so callers can suppress drawing it. A path that hugs
    /// the storm does engage. This is the guard against a mint line with no weather near it.
    func testPathEngagesWeatherDistinguishesClearAirFromAHug() throws {
        let storm = radarHazard(cell(alongNM: 40, crossNM: 0, from: usPosition))
        let east0 = Geo.destination(from: usPosition, bearingDegrees: 90, distanceNM: 200)
        let east1 = Geo.destination(from: usPosition, bearingDegrees: 90, distanceNM: 300)
        XCTAssertFalse(detector.pathEngagesWeather([east0, east1], hazards: [storm]),
                       "a line far from every cell does not engage the weather")
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: [storm], waypoints: []))
        XCTAssertTrue(detector.pathEngagesWeather(conflict.deviationPath, hazards: [storm]),
                      "the drawn reroute around the storm engages it")
    }

    // MARK: - Strategic-preview apex hug (no clear-air spike near a route bend)

    /// A genuine reroute that rounds the cell has its apex right beside the weather, so the
    /// stricter preview guard accepts it.
    func testPreviewApexHugsWeatherAcceptsAGenuineHug() throws {
        let storm = radarHazard(cell(alongNM: 40, crossNM: 0, from: usPosition))
        let route = [usPosition, Geo.destination(from: usPosition, bearingDegrees: course, distanceNM: 120)]
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: [storm], waypoints: [], routeAhead: route))
        XCTAssertTrue(detector.previewApexHugsWeather(conflict.deviationPath, route: route, hazards: [storm]),
                      "a reroute that rounds the cell has its apex beside the weather")
    }

    /// The reported anomaly: a "sharp angle out and back" whose *base* sits near a cell but
    /// whose *apex* bulges off into clear air, far downrange from any weather. The loose
    /// `pathEngagesWeather` guard is fooled (the base is near the cell), but the stricter
    /// apex-hug guard the strategic preview uses rejects it, so the faint line is suppressed.
    func testPreviewApexHugsWeatherRejectsAClearAirSpike() {
        let storm = radarHazard(cell(alongNM: 40, crossNM: 0, from: usPosition), intensity: .heavy)
        let route = [usPosition, Geo.destination(from: usPosition, bearingDegrees: course, distanceNM: 120)]
        // Base near the cell's near edge; apex 40 NM off the route, 90 NM downrange, in
        // clear air; then back to the route — the truncated cross-bend stub.
        let base = Geo.destination(from: usPosition, bearingDegrees: course, distanceNM: 30)
        let onCourse90 = Geo.destination(from: usPosition, bearingDegrees: course, distanceNM: 90)
        let apex = Geo.destination(from: onCourse90, bearingDegrees: course + 90, distanceNM: 40)
        let end = Geo.destination(from: usPosition, bearingDegrees: course, distanceNM: 100)
        let spike = [base, apex, end]
        XCTAssertTrue(detector.pathEngagesWeather(spike, hazards: [storm]),
                      "the loose guard is fooled — the spike's base sits inside the cell")
        XCTAssertFalse(detector.previewApexHugsWeather(spike, route: route, hazards: [storm]),
                       "the apex bulges into clear air far from the cell, so the preview drops it")
    }

    // MARK: - Rejoin cap (never route past the destination / approach)

    /// The along-course component (NM) of a point relative to the northbound course.
    private func alongFromCourse(_ point: CLLocationCoordinate2D) -> Double {
        let d = Geo.distanceNM(from: usPosition, to: point)
        let delta = (Geo.bearing(from: usPosition, to: point) - course) * .pi / 180
        return d * cos(delta)
    }

    /// Weather sitting well downrange (and, implicitly, the destination beyond it)
    /// must not pull the mint line past the rejoin cap: every vertex intercepts the
    /// route at or before the cap (here a fix 90 NM ahead, e.g. the first ILS fix).
    func testMintLineNeverRoutesPastTheRejoinCap() throws {
        let storm = radarHazard(cell(alongNM: 60, crossNM: 0, halfAlong: 20, halfCross: 20, from: usPosition))
        let cap = Geo.destination(from: usPosition, bearingDegrees: course, distanceNM: 50)
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: [storm], waypoints: [], rejoinCap: cap))
        for point in conflict.deviationPath {
            XCTAssertLessThanOrEqual(alongFromCourse(point), 50 + 1,
                                     "the mint line must intercept the route no deeper than the rejoin cap")
        }
    }

    // MARK: - On-path gate + tactical-range gating

    func testFarOnPathWeatherIsMonitoredButNotDrawn() throws {
        // On-path weather well beyond the draw range is still *detected* (so Diagnostics
        // can report it as "monitoring"), but its mint line is held: drawing a straight
        // reroute aimed across the route's bends at distant weather produced the runaway
        // "crazy" line. withinDrawRange (and withinTacticalRange / shouldPrompt) stay
        // false until the aircraft closes in.
        let farCell = radarHazard(cell(alongNM: 140, crossNM: 0, from: usPosition))
        let far = try XCTUnwrap(detector.detectConflict(position: usPosition, course: course,
                                                        groundspeedKnots: 450, phase: .cruise,
                                                        hazards: [farCell], waypoints: []),
                                "far on-path weather should still produce a conflict")
        XCTAssertFalse(far.withinDrawRange, "far weather must not draw a mint line yet")
        XCTAssertFalse(far.withinTacticalRange, "far weather is out of tactical range")
        XCTAssertFalse(far.shouldPrompt, "the banner / advisory must not fire for far weather")

        // The same cell up close is within draw + tactical range and prompts.
        let nearCell = radarHazard(cell(alongNM: 45, crossNM: 0, from: usPosition))
        let near = try XCTUnwrap(detector.detectConflict(position: usPosition, course: course,
                                                         groundspeedKnots: 450, phase: .cruise,
                                                         hazards: [nearCell], waypoints: []))
        XCTAssertGreaterThanOrEqual(near.deviationPath.count, 2, "a near conflict draws the mint line")
        XCTAssertTrue(near.withinDrawRange, "near weather draws the mint line")
        XCTAssertTrue(near.withinTacticalRange, "near weather is within tactical range")
        XCTAssertTrue(near.shouldPrompt, "near weather raises the banner / advisory")
    }

    func testMintLineDrawsAheadOfTheBannerButNotAtTheHorizon() throws {
        // The draw range sits between the tactical (banner) trigger and the far horizon,
        // so the reroute appears a little before the "contact ATC" banner, but weather at
        // the edge of the enroute lookahead is monitored only — never drawn as a line
        // that shoots across the map.
        // Just past the tactical trigger (60 NM) but within the draw range (75 NM): the
        // mint line is drawn as advance notice, yet the banner still holds. The cell is
        // centered 70 NM ahead (near edge ~60–65 NM), inside the draw range.
        let advance = radarHazard(cell(alongNM: 70, crossNM: 0, halfAlong: 8, from: usPosition))
        let adv = try XCTUnwrap(detector.detectConflict(position: usPosition, course: course,
                                                        groundspeedKnots: 450, phase: .cruise,
                                                        hazards: [advance], waypoints: []))
        XCTAssertTrue(adv.withinDrawRange, "weather inside the draw range shows the reroute ahead")
        XCTAssertFalse(adv.withinTacticalRange, "but the banner holds until the tactical range")
        XCTAssertGreaterThanOrEqual(adv.deviationPath.count, 2)

        // Beyond the draw range: detected and monitored, but the line is held.
        let horizon = radarHazard(cell(alongNM: 120, crossNM: 0, from: usPosition))
        let hz = try XCTUnwrap(detector.detectConflict(position: usPosition, course: course,
                                                       groundspeedKnots: 450, phase: .cruise,
                                                       hazards: [horizon], waypoints: []))
        XCTAssertFalse(hz.withinDrawRange, "weather at the horizon is monitored, not drawn")
    }

    // MARK: - Drawn-ahead geometry (turn-out before the weather, 30° turns, min extent)

    func testMintLineStartsAheadWithAThirtyDegreeTurnOut() throws {
        // A moderate cell ~60 NM ahead on course. The drawn line must not drift shallowly
        // from the aircraft: it starts at a turn-out point ahead (on the course line) and
        // makes a ~30° turn onto the offset there.
        let cellPoly = cell(alongNM: 70, crossNM: 0, halfAlong: 10, halfCross: 8, from: usPosition)
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: [radarHazard(cellPoly, intensity: .moderate)], waypoints: []))
        let path = conflict.deviationPath
        XCTAssertGreaterThanOrEqual(path.count, 2)
        // The start is ahead of the aircraft, on the course line (not a drift from the nose).
        XCTAssertGreaterThan(alongFromCourse(path[0]), 10,
                             "the mint line must start ahead of the aircraft, before the weather")
        XCTAssertLessThan(abs(offsetFromCourse(path[0])), 3,
                          "the turn-out point sits on the route, not off to one side")
        // The first leg is a real ~30° turn onto the offset, never a shallow drift.
        let turnOut = Geo.headingDifference(Geo.bearing(from: path[0], to: path[1]), course)
        XCTAssertGreaterThanOrEqual(turnOut, 22, "the turn-out must be a genuine turn (~30°), not a drift")
        XCTAssertLessThanOrEqual(turnOut, 50, "the turn-out must not overshoot a normal deviation turn")
    }

    func testMintLineSpansAtLeastTheMinimumExtent() throws {
        // Even a compact cell must produce a maneuver at least the minimum extent long,
        // so the mint line never renders as a twitch on the map.
        let cellPoly = cell(alongNM: 55, crossNM: 0, halfAlong: 5, halfCross: 5, from: usPosition)
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: [radarHazard(cellPoly, intensity: .moderate)], waypoints: []))
        let path = conflict.deviationPath
        let start = try XCTUnwrap(path.first)
        let end = try XCTUnwrap(path.last)
        XCTAssertGreaterThanOrEqual(Geo.distanceNM(from: start, to: end), 15 - 0.5,
                                    "the drawn deviation must span at least the minimum extent")
    }

    /// A wide red/extreme core well ahead — one needing the wide (~20 NM) berth — must still be
    /// entered and left with gradual ~30° legs, not a square 90° step. The turn-out is pulled
    /// earlier and the turn-back pushed out into clear air (rather than collapsing to a square
    /// when the ideal transition would clip the bermed core), while the whole line stays clear.
    func testWideCoreStillGetsGradualTurnOutAndTurnBack() throws {
        // Extreme wall ~95 NM ahead on course (near edge well beyond the ~30° turn-out lead, so
        // a gradual turn onto the offset fits ahead of it — not the close-aboard exception).
        let wall = radarHazard(cell(alongNM: 120, crossNM: 0, halfAlong: 25, halfCross: 20, from: usPosition),
                               intensity: .extreme)
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: [wall], waypoints: []))
        let path = conflict.deviationPath
        XCTAssertGreaterThanOrEqual(path.count, 4, "a wide core on course must produce a parallel hug")
        let n = path.count
        let turnOut = Geo.headingDifference(Geo.bearing(from: path[0], to: path[1]), course)
        XCTAssertLessThanOrEqual(turnOut, 55, "the turn-out onto the offset must be gradual, not a ~90° step")
        XCTAssertGreaterThanOrEqual(turnOut, 20, "the turn-out is still a genuine deviation turn")
        let turnBack = Geo.headingDifference(Geo.bearing(from: path[n - 2], to: path[n - 1]), course)
        XCTAssertLessThanOrEqual(turnBack, 55, "the turn-back onto course must be gradual, not a ~90° squeeze")
        assertPathClear(path, of: [wall.geometry.polygonPoints ?? []])
    }

    func testWeatherOffToTheSideDoesNotDrawADeviation() {
        // A moderate cell ~16 NM to the side of course, not crossing the centerline:
        // "nearby but not on top of the route" → no conflict, no mint line, no banner.
        let sideCell = radarHazard(cell(alongNM: 45, crossNM: 16, halfCross: 6, from: usPosition),
                                   intensity: .moderate)
        XCTAssertNil(detector.detectConflict(position: usPosition, course: course,
                                             groundspeedKnots: 450, phase: .cruise,
                                             hazards: [sideCell], waypoints: []),
                     "weather off to the side of the route must not draw a deviation")
    }

    func testWeatherStraddlingTheCourseStillDraws() throws {
        // The same cell moved onto the flight path (its near edge crosses the centerline)
        // must still be caught — tightening the corridor only excludes off-to-the-side cells.
        let onPath = radarHazard(cell(alongNM: 45, crossNM: 4, halfCross: 6, from: usPosition),
                                 intensity: .moderate)
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: [onPath], waypoints: []),
            "a cell straddling the course is on the flight path and must draw a deviation")
        XCTAssertTrue(conflict.shouldPrompt)
    }

    /// The detection corridor scales with intensity: a red/orange core skirting the route
    /// (edge a little off the centerline, not crossed by it) is flagged, while moderate
    /// precip at the same offset still isn't — so a live "clear hazard on the route, but
    /// diagnostics say no conflict" for an intense core near the path is caught, without
    /// re-opening the moderate off-to-the-side false positive.
    func testDetectionCorridorScalesWithIntensity() {
        func conflict(at crossNM: Double, _ intensity: WeatherIntensity) -> RouteWeatherConflict? {
            // A compact cell whose nearest edge sits `crossNM - 6` NM off the centerline,
            // never crossing it (so only the corridor half-width decides the outcome).
            let poly = cell(alongNM: 45, crossNM: crossNM, halfCross: 6, from: usPosition)
            return detector.detectConflict(position: usPosition, course: course,
                                           groundspeedKnots: 450, phase: .cruise,
                                           hazards: [radarHazard(poly, intensity: intensity)], waypoints: [])
        }
        // Edge ~10 NM off course: moderate ignores it (±6), heavy and extreme catch it.
        XCTAssertNil(conflict(at: 16, .moderate), "moderate precip 10 NM off the path stays off-path")
        XCTAssertNotNil(conflict(at: 16, .heavy), "a heavy core 10 NM off the path is now flagged")
        XCTAssertNotNil(conflict(at: 16, .extreme), "a red core 10 NM off the path is now flagged")
        // Edge ~14 NM off course: past the heavy corridor but within the extreme one.
        XCTAssertNil(conflict(at: 20, .heavy), "a heavy core 14 NM off the path is beyond its corridor")
        XCTAssertNotNil(conflict(at: 20, .extreme), "a red core 14 NM off the path is within its wide corridor")
    }

    // MARK: - Minimum lateral offset (parallel legs stay ≥ 20 NM off the flight path)

    /// A single cell straddling the course must be paralleled with the whole parallel leg at
    /// least the configured minimum (20 NM) off the flight path — never shaved a few NM past
    /// the weather. This is the fix for "the deviation is only a few NM off the flight path":
    /// the tight hug that used to sit at the cell edge plus a small buffer is opened up to the
    /// minimum lateral separation whenever the wider leg still clears.
    func testParallelHugKeepsAtLeastTheMinimumLateralOffset() throws {
        // A compact moderate cell on course — its natural (edge + 3 NM) hug would be ~11 NM
        // off, well inside the 20 NM minimum, so it must be widened out to 20 NM.
        let cellPoly = cell(alongNM: 50, crossNM: 0, halfCross: 8, from: usPosition)
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: [radarHazard(cellPoly, intensity: .moderate)], waypoints: []))
        let path = conflict.deviationPath
        XCTAssertGreaterThanOrEqual(path.count, 4, "an on-course cell forces a parallel hug")
        // The parallel leg is the widest-offset run of the drawn line.
        let maxOffset = path.map { abs(offsetFromCourse($0)) }.max() ?? 0
        XCTAssertGreaterThanOrEqual(maxOffset, 20 - 0.5,
                                    "the parallel leg must sit at least the 20 NM minimum off the flight path")
        assertPathClear(path, of: [cellPoly])
    }

    /// The minimum offset scales with the config knob: raising it widens the drawn parallel
    /// leg accordingly (proving it is the knob, not an incidental berth, that governs the leg).
    func testMinimumLateralOffsetTracksTheConfiguredValue() throws {
        var wide = RouteWeatherConflictDetector()
        wide.config.minParallelOffsetNM = 35
        let cellPoly = cell(alongNM: 50, crossNM: 0, halfCross: 8, from: usPosition)
        let conflict = try XCTUnwrap(wide.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: [radarHazard(cellPoly, intensity: .moderate)], waypoints: []))
        let maxOffset = conflict.deviationPath.map { abs(offsetFromCourse($0)) }.max() ?? 0
        XCTAssertGreaterThanOrEqual(maxOffset, 35 - 0.5,
                                    "a larger configured minimum widens the parallel leg to match")
    }

    /// Exemption: threading a genuine gap *between* two cells is not forced out to the 20 NM
    /// minimum — you cannot hold 20 NM off centerline while flying through a ~20 NM gap. The
    /// reroute keeps the tight threading offset rather than looping around the whole line.
    func testGapThreadIsExemptFromTheMinimumLateralOffset() throws {
        // Two cells ~40 NM ahead with a clear gap on the right (left cell crosses the course,
        // right cell out to its right) — the same geometry as the gap-threading test.
        let leftCell = radarHazard(cell(alongNM: 40, crossNM: -24, halfCross: 26, from: usPosition))
        let rightCell = radarHazard(cell(alongNM: 40, crossNM: 36, halfCross: 14, from: usPosition))
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: [leftCell, rightCell], waypoints: []))
        // The drawn line threads the gap at a tight offset — it must NOT be pushed out to the
        // minimum (which would put the leg inside the right cell) or looped around the line.
        let maxOffset = conflict.deviationPath.map { abs(offsetFromCourse($0)) }.max() ?? 0
        XCTAssertLessThan(maxOffset, 20,
                          "a gap-thread stays tight — the 20 NM minimum can't be held inside the gap")
        assertPathClear(conflict.deviationPath, of: [leftCell.geometry.polygonPoints ?? [],
                                                     rightCell.geometry.polygonPoints ?? []])
    }

    // MARK: - Prefer the parallel hug over a single-apex triangle

    /// A cell biased to one side of course can be dodged either by a single-apex
    /// triangle (2 legs / 3 points: turn out to an apex, turn straight back) or by a
    /// parallel side-hug (3 legs / 4 points: turn out ~30°, run alongside the weather,
    /// turn ~30° back). The triangle is a touch shorter, but real weather deviations
    /// parallel the weather — so the drawn mint line must be the parallel hug, not the
    /// single-turn triangle.
    func testPrefersParallelHugOverSingleApexTriangle() throws {
        // A heavy cell just right of course (near edge ~2 NM right), where a shallow
        // dogleg around the near (left) end clears the cell — the case that used to be
        // drawn as a 3-point triangle.
        let cellPoly = cell(alongNM: 45, crossNM: 10, halfCross: 8, from: usPosition)
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: [radarHazard(cellPoly, intensity: .heavy)], waypoints: []))
        let path = conflict.deviationPath

        XCTAssertEqual(conflict.recommendedDirection, .left, "the shorter side of a right-biased cell is left")
        // A parallel hug has at least four points (start, turn-out, turn-back, rejoin);
        // a single-apex triangle has only three.
        XCTAssertGreaterThanOrEqual(path.count, 4,
                                    "the reroute must be a parallel hug (4+ points), not a 3-point triangle")
        // It has an interior leg that runs roughly parallel to course — the alongside
        // leg a triangle lacks (both of a triangle's legs angle away from the course).
        var hasParallelLeg = false
        for i in 0..<(path.count - 1) where Geo.headingDifference(Geo.bearing(from: path[i], to: path[i + 1]), course) < 15 {
            hasParallelLeg = true
        }
        XCTAssertTrue(hasParallelLeg, "the hug must include a leg parallel to course")
        // Both the turn-out onto the parallel leg and the turn-back off it are realistic
        // ~30° turns, not a single wide apex.
        let turnOut = Geo.headingDifference(Geo.bearing(from: path[0], to: path[1]), course)
        XCTAssertGreaterThanOrEqual(turnOut, 22, "turn-out onto the parallel leg is a genuine ~30° turn")
        XCTAssertLessThanOrEqual(turnOut, 50, "the turn-out must not overshoot a normal deviation turn")
        let n = path.count
        let turnBack = Geo.headingDifference(Geo.bearing(from: path[n - 2], to: path[n - 1]), course)
        XCTAssertLessThanOrEqual(turnBack, 45, "the turn-back onto course is gradual, not a wide single turn")
        assertPathClear(path, of: [cellPoly])
    }

    // MARK: - Tight to the storm (no giant last-resort detours)

    func testKeepsDeviationTightAroundACore() throws {
        // A heavy core blocking the corridor. The reroute hugs close to it — it must stay
        // within the routine offset bound, never swinging out to a huge last-resort loop.
        let core = cell(alongNM: 45, crossNM: 0, halfCross: 10, from: usPosition)
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: [radarHazard(core, intensity: .heavy)], waypoints: []))
        for point in conflict.deviationPath {
            XCTAssertLessThanOrEqual(abs(offsetFromCourse(point)), 60 + 1,
                                     "a routine deviation must stay tight, never a huge detour")
        }
        assertPathClear(conflict.deviationPath, of: [core])
    }

    func testDeviationNeverExceedsTheMaxDetourOffset() throws {
        // Even a broad wall of precipitation across the corridor must never produce a
        // reroute wider than the absolute last-resort detour bound.
        let wall = stride(from: -60.0, through: 60.0, by: 15.0).map {
            radarHazard(cell(alongNM: 45, crossNM: $0, halfCross: 10, from: usPosition), intensity: .moderate)
        }
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: wall, waypoints: []))
        for point in conflict.deviationPath {
            XCTAssertLessThanOrEqual(abs(offsetFromCourse(point)), 150 + 1,
                                     "the mint line must never exceed the maximum last-resort detour")
        }
    }

    func testNeverCutsAnIntenseCoreToStayTight() throws {
        // A wall of moderate precip too wide to clear tightly, with an extreme core off to
        // one side. The reroute may skirt the moderate wall, but it must never cut through
        // the extreme core — the intense cores are always avoided.
        var hazards = stride(from: -55.0, through: 55.0, by: 11.0).map {
            radarHazard(cell(alongNM: 45, crossNM: $0, halfCross: 7, from: usPosition), intensity: .moderate)
        }
        let corePoly = cell(alongNM: 45, crossNM: 33, halfCross: 8, from: usPosition)
        hazards.append(radarHazard(corePoly, intensity: .extreme))
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: hazards, waypoints: []))
        let path = conflict.deviationPath
        for i in 0..<(path.count - 1) {
            let a = path[i], b = path[i + 1]
            for s in 0...30 {
                let f = Double(s) / 30
                let p = CLLocationCoordinate2D(latitude: a.latitude + (b.latitude - a.latitude) * f,
                                               longitude: a.longitude + (b.longitude - a.longitude) * f)
                guard Geo.distanceNM(from: usPosition, to: p) > 8 else { continue }
                XCTAssertFalse(WeatherRouteAnalyzer.pointInPolygon(p, corePoly),
                               "the mint line must never cut through the extreme core")
            }
        }
    }

    // MARK: - Final drawn geometry is what gets validated

    func testFinalDrawnPathClearsCoreUnderRejoinCap() throws {
        // An extreme core straddling the course with a rejoin cap just past it. The
        // capped, turn-bounded line that is actually drawn must still clear the core — the
        // clearance check governs the final geometry, not a pre-cap candidate shape.
        let corePoly = cell(alongNM: 45, crossNM: 0, halfAlong: 12, halfCross: 12, from: usPosition)
        let cap = Geo.destination(from: usPosition, bearingDegrees: course, distanceNM: 75)
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: [radarHazard(corePoly, intensity: .extreme)], waypoints: [], rejoinCap: cap))
        assertPathClear(conflict.deviationPath, of: [corePoly])
    }

    // MARK: - Ends at the first route intercept (no double-cross)

    func testDeviationEndsAtFirstRouteInterceptNoDoubleCross() throws {
        // The route runs north, then bends north-east just past the weather. A reroute
        // aimed at a straight-ahead rejoin would cross the bent route and come back down
        // to intercept it a second time. The drawn line must instead end at the first
        // intercept — crossing the route exactly once.
        let f1 = Geo.destination(from: usPosition, bearingDegrees: 0, distanceNM: 60)   // north
        let f2 = Geo.destination(from: f1, bearingDegrees: 45, distanceNM: 90)          // then NE
        let storm = radarHazard(cell(alongNM: 40, crossNM: 0, halfCross: 14, from: usPosition))
        let wps = [Waypoint(name: "F1", latitude: f1.latitude, longitude: f1.longitude),
                   Waypoint(name: "F2", latitude: f2.latitude, longitude: f2.longitude)]
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: 0, groundspeedKnots: 450, phase: .cruise,
            hazards: [storm], waypoints: wps, routeAhead: [f1, f2]))

        let route = [usPosition, f1, f2]
        let path = conflict.deviationPath
        // The line ends on the filed route.
        let end = try XCTUnwrap(path.last)
        XCTAssertLessThan(minDistanceToPolyline(end, route), 1.0,
                          "the deviation must end on the filed route")
        // The line now begins at its turn-out point, which sits on the route ahead of the
        // aircraft; crossings within the departure skip of that start are the shared
        // origin, not a re-intercept. It must then intercept the route exactly once (its
        // endpoint) — never crossing it and looping back to intercept a second time.
        let start = try XCTUnwrap(path.first)
        var hits: [CLLocationCoordinate2D] = []
        for i in 0..<(path.count - 1) {
            for r in 0..<(route.count - 1) {
                guard let x = segmentIntersectionPoint(path[i], path[i + 1], route[r], route[r + 1]),
                      Geo.distanceNM(from: start, to: x) > 3 else { continue }
                if !hits.contains(where: { Geo.distanceNM(from: $0, to: x) < 1 }) { hits.append(x) }
            }
        }
        XCTAssertEqual(hits.count, 1, "the deviation must intercept the route exactly once and end there")
    }

    /// Minimum distance (NM) from a point to a polyline (test helper).
    private func minDistanceToPolyline(_ p: CLLocationCoordinate2D, _ line: [CLLocationCoordinate2D]) -> Double {
        guard line.count >= 2 else { return .greatestFiniteMagnitude }
        var best = Double.greatestFiniteMagnitude
        for i in 0..<(line.count - 1) {
            let a = line[i], b = line[i + 1]
            for s in 0...50 {
                let f = Double(s) / 50
                let q = CLLocationCoordinate2D(latitude: a.latitude + (b.latitude - a.latitude) * f,
                                               longitude: a.longitude + (b.longitude - a.longitude) * f)
                best = min(best, Geo.distanceNM(from: p, to: q))
            }
        }
        return best
    }

    /// Planar segment-intersection point (test helper mirroring the detector's geometry).
    private func segmentIntersectionPoint(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D,
                                          _ c: CLLocationCoordinate2D, _ d: CLLocationCoordinate2D)
        -> CLLocationCoordinate2D? {
        let x1 = a.longitude, y1 = a.latitude, x2 = b.longitude, y2 = b.latitude
        let x3 = c.longitude, y3 = c.latitude, x4 = d.longitude, y4 = d.latitude
        let denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        guard abs(denom) > 1e-12 else { return nil }
        let t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / denom
        let u = ((x1 - x3) * (y1 - y2) - (y1 - y3) * (x1 - x2)) / denom
        guard t >= 0, t <= 1, u >= 0, u <= 1 else { return nil }
        return CLLocationCoordinate2D(latitude: y1 + t * (y2 - y1), longitude: x1 + t * (x2 - x1))
    }

    func testDetectsWeatherOnALegAfterATurn() throws {
        // The route turns at a nearby fix and then flies into weather on the *next*
        // leg. A straight corridor aimed at the near fix slides past the storm (the
        // failure the user hit: cells detected, but "No conflict"); following the
        // route polyline turns the corridor down-route and catches it.
        let f1 = Geo.destination(from: usPosition, bearingDegrees: 90, distanceNM: 15)   // close, due east
        let f2 = Geo.destination(from: f1, bearingDegrees: 0, distanceNM: 80)            // then north
        let storm = radarHazard(cell(alongNM: 40, crossNM: 0, halfAlong: 12, halfCross: 12,
                                     course: 0, from: f1))
        let wps = [Waypoint(name: "F1", latitude: f1.latitude, longitude: f1.longitude),
                   Waypoint(name: "F2", latitude: f2.latitude, longitude: f2.longitude)]
        let courseToNext = Geo.bearing(from: usPosition, to: f1)

        // Straight corridor along the bearing to the next fix misses it.
        let straight = detector.detectConflict(position: usPosition, course: courseToNext,
                                               groundspeedKnots: 450, phase: .cruise,
                                               hazards: [storm], waypoints: wps)
        XCTAssertNil(straight, "a straight corridor to the next fix misses weather on the next leg")

        // Following the upcoming route polyline detects it.
        let routed = detector.detectConflict(position: usPosition, course: courseToNext,
                                             groundspeedKnots: 450, phase: .cruise,
                                             hazards: [storm], waypoints: wps,
                                             routeAhead: [f1, f2])
        XCTAssertNotNil(routed, "following the route polyline detects weather on the next leg")
        XCTAssertEqual(routed?.severity, .heavy)
    }

    func testDeviationRejoinsPromptlyNotAtADistantFix() throws {
        // A cell dead ahead with the next filed fix far beyond it. The drawn deviation
        // must return to course just past the weather (a compact reroute) rather than
        // stretch all the way to that distant fix — chasing the far fix is what forced
        // a short side deviation to swing back across the storm and get rejected, so
        // the reroute took the long way (or drove straight through when boxed in). The
        // fix is still named for the rejoin clearance; it simply lies on ahead.
        let hazard = radarHazard(cell(alongNM: 40, crossNM: 0, from: usPosition))
        let far = Geo.destination(from: usPosition, bearingDegrees: course, distanceNM: 150)
        let wp = Waypoint(name: "FODAK", latitude: far.latitude, longitude: far.longitude)
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: [hazard], waypoints: [wp]))

        XCTAssertEqual(conflict.rejoinFix?.name, "FODAK", "the downstream fix is still named for the rejoin")
        let end = try XCTUnwrap(conflict.deviationPath.last)
        let endDist = Geo.distanceNM(from: usPosition, to: end)
        XCTAssertLessThan(endDist, 90,
                          "the drawn deviation rejoins course just past the weather, not at the 150 NM fix")
    }

    func testRejoinFollowsTheRouteSouthThroughATurn() throws {
        // The route runs east, then turns south just past the weather. The intercept
        // back onto the route is therefore to the south — so the deviation length must
        // be measured to that southward turn (not a straight-ahead point), which makes
        // the southern deviation the shortest. Verify the drawn line rejoins on the
        // route's southward leg: its endpoint is well south of the aircraft.
        let f1 = Geo.destination(from: usPosition, bearingDegrees: 90, distanceNM: 50)   // east
        let f2 = Geo.destination(from: f1, bearingDegrees: 180, distanceNM: 80)          // then south
        let storm = radarHazard(cell(alongNM: 45, crossNM: 0, halfCross: 12, course: 90, from: usPosition))
        let wps = [Waypoint(name: "F1", latitude: f1.latitude, longitude: f1.longitude),
                   Waypoint(name: "F2", latitude: f2.latitude, longitude: f2.longitude)]
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: Geo.bearing(from: usPosition, to: f1),
            groundspeedKnots: 450, phase: .cruise, hazards: [storm], waypoints: wps,
            routeAhead: [f1, f2]))

        let end = try XCTUnwrap(conflict.deviationPath.last)
        XCTAssertLessThan(end.latitude, usPosition.latitude - 0.3,
                          "the deviation should rejoin on the route's southward leg, not straight ahead")
    }

    func testRejoinsAtFirstSystemNotStretchedToADistantSecondSystem() throws {
        // Two systems on a northbound route: one ~40 NM ahead, another ~150 NM ahead with
        // a wide clear gap between them. The drawn line must rejoin just past the FIRST
        // system — compact around it — not stretch all the way to the second system near
        // the destination (the mislocated "line past the weather, ending near the airport").
        let storm1 = radarHazard(cell(alongNM: 40, crossNM: 0, halfCross: 12, from: usPosition))
        let storm2 = radarHazard(cell(alongNM: 150, crossNM: 0, halfCross: 12, from: usPosition))
        let f1 = Geo.destination(from: usPosition, bearingDegrees: 0, distanceNM: 100)
        let f2 = Geo.destination(from: usPosition, bearingDegrees: 0, distanceNM: 200)
        let wps = [Waypoint(name: "F1", latitude: f1.latitude, longitude: f1.longitude),
                   Waypoint(name: "F2", latitude: f2.latitude, longitude: f2.longitude)]
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: 0, groundspeedKnots: 450, phase: .cruise,
            hazards: [storm1, storm2], waypoints: wps, routeAhead: [f1, f2]))
        let end = try XCTUnwrap(conflict.deviationPath.last)
        XCTAssertLessThan(alongFromCourse(end), 120,
                          "the line rejoins past the first system, not stretched to the second ~150 NM away")
        assertPathClear(conflict.deviationPath, of: [storm1.geometry.polygonPoints ?? [],
                                                     storm2.geometry.polygonPoints ?? []])
    }

    // MARK: - Complex shapes (variable-offset, multi-leg hug)

    func testUpperHullTracesOutboardEnvelope() {
        // A staggered set of points: the hull keeps the outward-bulging envelope and drops
        // interior points that lie below it.
        let pts: [(x: Double, y: Double)] = [(0, 0), (1, 5), (2, 3), (3, 8), (4, 2), (5, 0)]
        let hull = detector.upperHull(pts)
        XCTAssertEqual(hull.first?.x, 0, "the leftmost point is always on the hull")
        XCTAssertEqual(hull.last?.x, 5, "the rightmost point is always on the hull")
        for i in 1..<hull.count {
            XCTAssertGreaterThan(hull[i].x, hull[i - 1].x, "the hull is monotonic in x")
        }
        XCTAssertTrue(hull.contains { $0.x == 3 && $0.y == 8 }, "the outward peak is kept")
        XCTAssertFalse(hull.contains { $0.x == 2 }, "an interior point below the envelope is dropped")
    }

    func testComplexStaggeredLineStaysTightAndClear() throws {
        // A line that straddles course near the aircraft and bulges hard to the right
        // downrange — a shape a single fixed-offset parallel would have to swing wide for.
        // The reroute must stay clear of every cell and take the tight (left) side rather
        // than loop around the far-right bulge.
        let polys = [
            cell(alongNM: 35,  crossNM: 0,  halfCross: 14, from: usPosition),   // straddles course
            cell(alongNM: 70,  crossNM: 20, halfCross: 12, from: usPosition),   // right
            cell(alongNM: 105, crossNM: 45, halfCross: 12, from: usPosition),   // far right
        ]
        let downstream = Geo.destination(from: usPosition, bearingDegrees: course, distanceNM: 220)
        let wp = Waypoint(name: "RJOIN", latitude: downstream.latitude, longitude: downstream.longitude)
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: polys.map { radarHazard($0) }, waypoints: [wp]))
        assertPathClear(conflict.deviationPath, of: polys)
        for p in conflict.deviationPath {
            XCTAssertLessThan(abs(offsetFromCourse(p)), 40,
                              "the reroute hugs the near/left edge, never loops around the far-right bulge")
        }
    }

    func testGivesRedCellsAWiderBerthThanLighterCells() throws {
        // The same cell straddling the course, once heavy and once red/extreme. The
        // red core must be rounded with a noticeably wider berth than the heavy cell.
        // The cell is wide enough that even the heavy hug's natural berth exceeds the
        // 20 NM minimum lateral offset, so the red core's extra berth stays visible in the
        // offset rather than both being floored to the same minimum separation.
        let poly = cell(alongNM: 40, crossNM: 10, halfCross: 20, from: usPosition)
        func bypassOffset(_ intensity: WeatherIntensity) throws -> Double {
            let conflict = try XCTUnwrap(detector.detectConflict(
                position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
                hazards: [radarHazard(poly, intensity: intensity)], waypoints: []))
            return abs(offsetFromCourse(conflict.deviationPath[1]))
        }
        let heavy = try bypassOffset(.heavy)
        let extreme = try bypassOffset(.extreme)
        XCTAssertGreaterThan(extreme, heavy + 5,
                             "a red/extreme core must be rounded with a wider berth than a heavy cell")
    }

    func testTerminalWeatherJustAfterDeparture() throws {
        // A cell 30 NM off the departure end, on course, is caught by the terminal
        // lookahead band (25–75 NM) while still on the ground / departing.
        let hazard = radarHazard(cell(alongNM: 30, crossNM: 0, halfCross: 10, from: usPosition))
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 0, phase: .takeoff,
            hazards: [hazard], waypoints: []))
        XCTAssertEqual(conflict.severity, .heavy)
        XCTAssertTrue(conflict.shouldPrompt)
    }

    func testDeviationPathStaysClearOfCells() throws {
        // A recommended reroute must not pass through a cell anywhere along its
        // length — not just at the abeam point — so it never avoids one storm and
        // routes into another.
        let leftPoly = cell(alongNM: 40, crossNM: -24, halfCross: 26, from: usPosition)
        let rightPoly = cell(alongNM: 40, crossNM: 36, halfCross: 14, from: usPosition)
        let conflict = try XCTUnwrap(detector.detectConflict(
            position: usPosition, course: course, groundspeedKnots: 450, phase: .cruise,
            hazards: [radarHazard(leftPoly), radarHazard(rightPoly)], waypoints: []))

        let path = conflict.deviationPath
        for i in 0..<(path.count - 1) {
            let a = path[i], b = path[i + 1]
            for s in 0...20 {
                let f = Double(s) / 20
                let p = CLLocationCoordinate2D(latitude: a.latitude + (b.latitude - a.latitude) * f,
                                               longitude: a.longitude + (b.longitude - a.longitude) * f)
                guard Geo.distanceNM(from: usPosition, to: p) > 8 else { continue }
                XCTAssertFalse(WeatherRouteAnalyzer.pointInPolygon(p, leftPoly), "path enters the left cell")
                XCTAssertFalse(WeatherRouteAnalyzer.pointInPolygon(p, rightPoly), "path enters the right cell")
            }
        }
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

    // MARK: - Deferred deviation (reroute drawn ahead: hold the turn, then issue it)

    func testDeferDeviationApprovesButHoldsTheTurn() {
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        let phr = WeatherDeviationPhraseology(engine: engine)
        let dev = WeatherDeviationEngine(phraseology: phr)
        let cs = engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        let inputs = WeatherDeviationEngine.Inputs(maintainAltitude: 37000, heading: 90)
        let result = dev.deferDeviation(cs: cs, conflict: nil, direction: .right, distanceNM: 30,
                                        inputs: inputs, context: WeatherDeviationContext(), facility: .center)
        XCTAssertEqual(result.context.state, .deviationApproved, "the deviation is approved…")
        XCTAssertNil(result.context.assignedHeading, "…but the turn is held — no heading assigned yet")
        XCTAssertNotNil(result.pilot, "the pilot's request is posted")
        let atc = result.atc.first?.displayText ?? ""
        XCTAssertTrue(atc.contains("deviation right of course approved"), atc)
        XCTAssertTrue(atc.contains("continue present heading"), atc)
        XCTAssertTrue(atc.contains("expect the turn"), atc)
    }

    func testBeginDeviationTurnVectorsOntoTheReroute() {
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        let phr = WeatherDeviationPhraseology(engine: engine)
        let dev = WeatherDeviationEngine(phraseology: phr)
        let cs = engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        var ctx = WeatherDeviationContext()
        ctx.state = .deviationApproved
        ctx.deviationStartLatitude = 40
        ctx.deviationStartLongitude = -95
        ctx.deviationStartHeading = 100
        let result = dev.beginDeviationTurn(cs: cs, heading: 110, maintainAltitude: 37000,
                                            context: ctx, facility: .center)
        XCTAssertEqual(result.context.state, .vectoringAroundWeather, "reaching the turn-out begins the vector")
        XCTAssertEqual(result.context.assignedHeading, 110)
        XCTAssertNil(result.context.deviationStartLatitude, "the held turn is consumed once issued")
        XCTAssertTrue(result.atc.first?.displayText.contains("fly heading 110") ?? false,
                      result.atc.first?.displayText ?? "")
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
        for source in [WeatherHazardSource.noaaRadar, .satelliteEstimate, .metar, .taf] {
            XCTAssertFalse(source.supportsTurbulenceWording, "\(source) must not imply turbulence")
        }
    }

    func testSatelliteEstimateSourceIsLabeledAsEstimateNotRadar() {
        // The satellite-estimate deviation source must read as an estimate, never as
        // radar, wherever the label surfaces (diagnostics / data-source captions).
        let label = WeatherHazardSource.satelliteEstimate.label
        XCTAssertTrue(label.lowercased().contains("estimate"))
        XCTAssertFalse(label.lowercased().contains("radar"))
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

    // MARK: - Turbulence / icing ride advisory (altitude, not lateral)

    func testSigmetRideAdvisoryTurbulenceOffersSmootherAir() {
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        let phr = WeatherDeviationPhraseology(engine: engine)
        let cs = engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        let tx = phr.sigmetRideAdvisory(cs: cs, hazardLabel: "severe turbulence", icing: false)
        XCTAssertTrue(tx.displayText.contains("severe turbulence"))
        XCTAssertTrue(tx.displayText.contains("smoother air"))
        XCTAssertTrue(tx.displayText.contains("Say intentions"))
        // A turbulence advisory is resolved with altitude, never a lateral deviation.
        XCTAssertFalse(tx.displayText.lowercased().contains("deviation"))
        XCTAssertFalse(tx.displayText.lowercased().contains("vector"))
    }

    func testSigmetRideAdvisoryIcingFramesExit() {
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        let phr = WeatherDeviationPhraseology(engine: engine)
        let cs = engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        let tx = phr.sigmetRideAdvisory(cs: cs, hazardLabel: "icing", icing: true)
        XCTAssertTrue(tx.displayText.contains("icing"))
        XCTAssertTrue(tx.displayText.contains("exit the icing"))
    }

    func testRideSigmetSituationAwaitsIntentions() {
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        let phr = WeatherDeviationPhraseology(engine: engine)
        let dev = WeatherDeviationEngine(phraseology: phr)
        let cs = phr.engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        let result = dev.issueAdvisory(cs: cs, situation: .rideSigmet(label: "severe turbulence", icing: false),
                                       context: WeatherDeviationContext(), facility: .center)
        XCTAssertEqual(result.context.state, .awaitingPilotIntentions)
        XCTAssertTrue(result.atc.first?.displayText.contains("smoother air") ?? false)
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

    func testApprovedFreeProvidersOnly() {
        // The shipped providers are the approved free/keyless sources: NOAA radar,
        // EUMETNET OPERA radar, NASA GIBS satellite estimate, and the mock stand-in.
        XCTAssertEqual(NOAARadarPrecipitationProvider().id, "noaa-nws-radar")
        XCTAssertTrue(NOAARadarPrecipitationProvider().supportsTrueRadar)
        XCTAssertEqual(EUMETNETOPERARadarProvider().id, "eumetnet-opera-radar")
        XCTAssertTrue(EUMETNETOPERARadarProvider().supportsTrueRadar)
        XCTAssertEqual(NASAGIBSPrecipitationProvider().id, "nasa-gibs-imerg")
        XCTAssertFalse(NASAGIBSPrecipitationProvider().supportsTrueRadar,
                       "NASA IMERG is a satellite estimate, not radar")
        XCTAssertFalse(MockRadarPrecipitationProvider().supportsTrueRadar,
                       "the mock provider must not advertise true radar")
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

    // MARK: - Merging adjacent deviations

    /// A point `alongNM` up the northbound course.
    private func onCourse(_ alongNM: Double) -> CLLocationCoordinate2D {
        Geo.destination(from: usPosition, bearingDegrees: course, distanceNM: alongNM)
    }

    /// A point `alongNM` up the course and `crossNM` to the side (+ = right of course).
    private func offCourse(_ alongNM: Double, _ crossNM: Double) -> CLLocationCoordinate2D {
        Geo.destination(from: onCourse(alongNM), bearingDegrees: course + 90, distanceNM: crossNM)
    }

    /// A minimal conflict carrying a hand-built deviation path, for the merge geometry.
    private func makeConflict(path: [CLLocationCoordinate2D], direction: DeviationDirection,
                              severity: WeatherIntensity = .heavy, hazard: WeatherHazard) -> RouteWeatherConflict {
        RouteWeatherConflict(
            hazard: hazard, distanceAheadNM: 30, relativeBearingDegrees: 0,
            leftClock: 11, centerClock: 12, rightClock: 1, estimatedTimeMinutes: nil,
            severity: severity, leftBypassScore: 0, rightBypassScore: 0,
            recommendedDirection: direction, recommendedDeviationDegrees: 20,
            rejoinFix: nil, originalSegment: nil, shouldPrompt: true,
            intersectionArea: [], deviationPath: path)
    }

    /// Two same-side deviations whose rejoin/turn-out sit within the merge window fold into
    /// one continuous parallel hug: the first turn-out, the last rejoin, and no dip back to
    /// the route in the gap between them.
    func testAdjacentSameSideDeviationsMergeIntoOneParallelHug() throws {
        let cellA = radarHazard(cell(alongNM: 40, crossNM: 0, from: usPosition))    // along 30–50
        let cellB = radarHazard(cell(alongNM: 100, crossNM: 0, from: usPosition))   // along 90–110
        let devA = makeConflict(path: [onCourse(25), offCourse(35, -22), offCourse(55, -22), onCourse(65)],
                                direction: .left, hazard: cellA)
        let devB = makeConflict(path: [onCourse(85), offCourse(95, -22), offCourse(115, -22), onCourse(125)],
                                direction: .left, hazard: cellB)
        let route = [onCourse(0), onCourse(200)]

        let merged = detector.mergeAdjacentDeviations([devA, devB], hazards: [cellA, cellB], route: route)

        XCTAssertEqual(merged.count, 1, "the two adjacent same-side deviations fold into one")
        let path = merged[0].deviationPath
        XCTAssertLessThan(Geo.distanceNM(from: path.first!, to: onCourse(25)), 2,
                          "the folded line keeps the first deviation's turn-out")
        XCTAssertLessThan(Geo.distanceNM(from: path.last!, to: onCourse(125)), 2,
                          "the folded line rejoins only at the last deviation's rejoin")
        assertPathClear(path, of: [cellA.geometry.polygonPoints ?? [], cellB.geometry.polygonPoints ?? []])
        // Every interior vertex stays out on the offset — the line runs parallel through the
        // gap instead of dipping back to the course between the two cells.
        for v in path.dropFirst().dropLast() {
            XCTAssertGreaterThan(abs(offsetFromCourse(v)), 15, "the hug holds its offset across the gap")
        }
    }

    /// A clear gap wider than the merge window leaves the two deviations separate — they are
    /// distinct systems, each with its own in-and-out maneuver.
    func testDeviationsSeparatedByAWideGapAreNotMerged() {
        let cellA = radarHazard(cell(alongNM: 40, crossNM: 0, from: usPosition))
        let cellB = radarHazard(cell(alongNM: 200, crossNM: 0, from: usPosition))
        let devA = makeConflict(path: [onCourse(25), offCourse(35, -22), offCourse(55, -22), onCourse(65)],
                                direction: .left, hazard: cellA)
        let devB = makeConflict(path: [onCourse(185), offCourse(195, -22), offCourse(215, -22), onCourse(225)],
                                direction: .left, hazard: cellB)
        let route = [onCourse(0), onCourse(300)]

        let merged = detector.mergeAdjacentDeviations([devA, devB], hazards: [cellA, cellB], route: route)
        XCTAssertEqual(merged.count, 2, "a wide clear gap keeps the two systems separate")
    }

    /// Deviations hugging opposite sides are never joined into one parallel run — connecting
    /// their offsets would cross the route (and the weather) — even when they sit close.
    func testOppositeSideDeviationsAreNotMerged() {
        let cellA = radarHazard(cell(alongNM: 40, crossNM: 0, from: usPosition))
        let cellB = radarHazard(cell(alongNM: 100, crossNM: 0, from: usPosition))
        let devA = makeConflict(path: [onCourse(25), offCourse(35, -22), offCourse(55, -22), onCourse(65)],
                                direction: .left, hazard: cellA)
        let devB = makeConflict(path: [onCourse(85), offCourse(95, 22), offCourse(115, 22), onCourse(125)],
                                direction: .right, hazard: cellB)
        let route = [onCourse(0), onCourse(200)]

        let merged = detector.mergeAdjacentDeviations([devA, devB], hazards: [cellA, cellB], route: route)
        XCTAssertEqual(merged.count, 2, "opposite-side hugs are left split")
    }

    /// When the folded line would otherwise rejoin *inside* a cell (the packed-system case
    /// the user flagged), the rejoin is slid forward along the route until it clears the
    /// weather, so the merged deviation no longer terminates in a hazard.
    func testMergedRejoinIsPushedClearWhenItLandsInAHazard() throws {
        let cellA = radarHazard(cell(alongNM: 40, crossNM: 0, from: usPosition))               // along 30–50
        let cellB = radarHazard(cell(alongNM: 115, crossNM: 0, halfAlong: 25, from: usPosition)) // along 90–140
        let devA = makeConflict(path: [onCourse(25), offCourse(35, -22), offCourse(55, -22), onCourse(65)],
                                direction: .left, hazard: cellA)
        // devB's own rejoin lands at along 120 — inside cellB.
        let devB = makeConflict(path: [onCourse(85), offCourse(95, -22), offCourse(135, -22), onCourse(120)],
                                direction: .left, hazard: cellB)
        let route = [onCourse(0), onCourse(220)]

        let merged = detector.mergeAdjacentDeviations([devA, devB], hazards: [cellA, cellB], route: route)
        XCTAssertEqual(merged.count, 1, "the packed cells still fold into one hug")
        let path = merged[0].deviationPath
        let polyB = try XCTUnwrap(cellB.geometry.polygonPoints)
        XCTAssertFalse(WeatherRouteAnalyzer.pointInPolygon(path.last!, polyB),
                       "the folded line no longer terminates inside the hazard")
        XCTAssertGreaterThan(alongFromCourse(path.last!), 140,
                             "the rejoin is slid past the cell's far edge to clear air")
        assertPathClear(path, of: [polyB])
    }

    /// A single deviation (nothing adjacent) passes through the merge untouched.
    func testSingleDeviationIsUnchangedByMerge() {
        let cellA = radarHazard(cell(alongNM: 40, crossNM: 0, from: usPosition))
        let devA = makeConflict(path: [onCourse(25), offCourse(35, -22), offCourse(55, -22), onCourse(65)],
                                direction: .left, hazard: cellA)
        let merged = detector.mergeAdjacentDeviations([devA], hazards: [cellA], route: [onCourse(0), onCourse(120)])
        XCTAssertEqual(merged.count, 1)
        XCTAssertEqual(merged[0].deviationPath.count, devA.deviationPath.count, "an isolated deviation is left as-is")
    }
}
