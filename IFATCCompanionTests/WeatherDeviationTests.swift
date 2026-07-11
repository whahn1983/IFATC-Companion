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

    func testGivesRedCellsAWiderBerthThanLighterCells() throws {
        // The same cell straddling the course, once heavy and once red/extreme. The
        // red core must be rounded with a noticeably wider berth than the heavy cell.
        let poly = cell(alongNM: 40, crossNM: 10, halfCross: 12, from: usPosition)
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
}
