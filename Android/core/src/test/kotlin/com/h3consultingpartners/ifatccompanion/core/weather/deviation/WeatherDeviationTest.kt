package com.h3consultingpartners.ifatccompanion.core.weather.deviation

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.Waypoint
import com.h3consultingpartners.ifatccompanion.core.phraseology.CallsignDigitStyle
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyEngine
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyMode
import com.h3consultingpartners.ifatccompanion.core.weather.WeatherRouteAnalyzer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the NOAA radar precipitation + simulated weather-deviation feature.
 * All logic under test is deterministic; nothing here touches the network.
 *
 * Ported from `IFATCCompanionTests/WeatherDeviationTests.swift`. The provider cases in
 * that file (`testNOAARadarCoverageAvailablePath`, `testNOAARadarUnavailableRegionPath`,
 * `testApprovedFreeProvidersOnly`, `testNOAAExportURLIsWellFormedAndKeyless`,
 * `testMockProviderRendersNoImage`) exercise the radar *providers*, which live in the
 * radar-overlay package; the coverage half of the two global-handling cases is dropped
 * with them and only the hazard/phraseology half is kept here.
 */
class WeatherDeviationTest {

    private val detector = RouteWeatherConflictDetector()

    // A point in the central U.S. and a course due north.
    private val usPosition = Coordinate(40.0, -95.0)
    private val course = 0.0

    // MARK: - Geometry helpers

    /**
     * A course-aligned box centered [alongNM] ahead and [crossNM] to the side
     * (positive = right of course), sized ±[halfAlong]/±[halfCross] NM.
     */
    private fun cell(
        alongNM: Double,
        crossNM: Double,
        halfAlong: Double = 10.0,
        halfCross: Double = 10.0,
        course: Double = 0.0,
        from: Coordinate,
    ): List<Coordinate> {
        val onCourse = Geo.destination(from, course, alongNM)
        val center = Geo.destination(onCourse, course + 90, crossNM)
        fun pt(a: Double, c: Double): Coordinate {
            val p = Geo.destination(center, course, a)
            return Geo.destination(p, course + 90, c)
        }
        return listOf(
            pt(-halfAlong, -halfCross),
            pt(halfAlong, -halfCross),
            pt(halfAlong, halfCross),
            pt(-halfAlong, halfCross),
        )
    }

    private fun radarHazard(
        polygon: List<Coordinate>,
        intensity: WeatherIntensity = WeatherIntensity.HEAVY,
        move: Pair<Double, Double>? = Pair(90.0, 20.0),
    ): WeatherHazard = WeatherHazard(
        source = WeatherHazardSource.NOAA_RADAR,
        phenomenon = WeatherPhenomenon.PRECIPITATION,
        intensity = intensity,
        geometry = HazardGeometry.Polygon(polygon),
        confidence = HazardConfidence.HIGH,
        movementDirectionDegrees = move?.first,
        movementSpeedKnots = move?.second,
    )

    private fun faaEngine(): PhraseologyEngine =
        PhraseologyEngine(digitStyle = CallsignDigitStyle.INDIVIDUAL, mode = PhraseologyMode.FAA)

    // MARK: - Conflict detection

    @Test
    fun routeCorridorIntersectsPrecipitationHazard() {
        val hazard = radarHazard(cell(alongNM = 40.0, crossNM = 0.0, from = usPosition))
        val conflict = detector.detectConflict(
            position = usPosition, course = course, groundspeedKnots = 450.0,
            phase = FlightPhase.CRUISE, hazards = listOf(hazard), waypoints = emptyList(),
        )
        assertNotNull(conflict, "a precipitation cell across the corridor must be detected")
        assertEquals(WeatherIntensity.HEAVY, conflict.severity)
        assertEquals(WeatherHazardSource.NOAA_RADAR, conflict.source)
    }

    @Test
    fun noHazardsMeansNoConflict() {
        // Missing reports never fabricate a conflict.
        val conflict = detector.detectConflict(
            position = usPosition, course = course, groundspeedKnots = 450.0,
            phase = FlightPhase.CRUISE, hazards = emptyList(), waypoints = emptyList(),
        )
        assertNull(conflict)
    }

    @Test
    fun distanceToPrecipitation() {
        // Cell centered 40 NM ahead, ±10 NM along-track → near edge ≈ 30 NM.
        val hazard = radarHazard(cell(alongNM = 40.0, crossNM = 0.0, halfAlong = 10.0, from = usPosition))
        val conflict = detector.detectConflict(
            position = usPosition, course = course, groundspeedKnots = 450.0,
            phase = FlightPhase.CRUISE, hazards = listOf(hazard), waypoints = emptyList(),
        )
        val distance = assertNotNull(conflict).distanceAheadNM
        assertTrue(distance > 15)
        assertTrue(distance < 45)
    }

    @Test
    fun clockPositionFormatting() {
        assertEquals(12, RouteWeatherConflictDetector.clockPosition(0.0))
        assertEquals(3, RouteWeatherConflictDetector.clockPosition(90.0))
        assertEquals(9, RouteWeatherConflictDetector.clockPosition(-90.0))
        assertEquals(6, RouteWeatherConflictDetector.clockPosition(180.0))
        assertEquals(1, RouteWeatherConflictDetector.clockPosition(30.0))
        assertEquals(11, RouteWeatherConflictDetector.clockPosition(-30.0))
        assertEquals(2, RouteWeatherConflictDetector.clockPosition(60.0))
    }

    @Test
    fun leftRightDeviationScoring() {
        // Cell biased to the RIGHT of course → the cleaner bypass is LEFT.
        val rightCell = radarHazard(cell(alongNM = 40.0, crossNM = 8.0, from = usPosition))
        val rightConflict = detector.detectConflict(
            position = usPosition, course = course, groundspeedKnots = 450.0,
            phase = FlightPhase.CRUISE, hazards = listOf(rightCell), waypoints = emptyList(),
        )
        assertEquals(
            DeviationDirection.LEFT,
            rightConflict?.recommendedDirection,
            "a cell to the right should be bypassed on the left",
        )

        // Cell biased to the LEFT of course → bypass RIGHT.
        val leftCell = radarHazard(cell(alongNM = 40.0, crossNM = -8.0, from = usPosition))
        val leftConflict = detector.detectConflict(
            position = usPosition, course = course, groundspeedKnots = 450.0,
            phase = FlightPhase.CRUISE, hazards = listOf(leftCell), waypoints = emptyList(),
        )
        assertEquals(DeviationDirection.RIGHT, leftConflict?.recommendedDirection)
    }

    // MARK: - Gap threading

    /**
     * The signed cross-track offset (+right) of a coordinate from the northbound
     * course line out of [usPosition].
     */
    private fun offsetFromCourse(point: Coordinate): Double {
        val end = Geo.destination(usPosition, course, 200.0)
        return Geo.crossTrackDistanceNM(point = point, pathStart = usPosition, pathEnd = end)
    }

    /**
     * Assert a reroute path stays clear of every cell along its whole length
     * (sampling each leg), ignoring the immediate vicinity of the aircraft.
     */
    private fun assertPathClear(path: List<Coordinate>, polys: List<List<Coordinate>>) {
        assertTrue(path.size >= 2, "no reroute path was produced")
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            for (s in 0..40) {
                val f = s.toDouble() / 40
                val p = Coordinate(
                    a.latitude + (b.latitude - a.latitude) * f,
                    a.longitude + (b.longitude - a.longitude) * f,
                )
                if (Geo.distanceNM(usPosition, p) <= 8) continue
                for (poly in polys) {
                    assertFalse(
                        WeatherRouteAnalyzer.pointInPolygon(p, poly),
                        "reroute enters a precipitation cell",
                    )
                }
            }
        }
    }

    @Test
    fun threadsGapBetweenAdjacentCells() {
        // A line of two cells ~40 NM ahead: a large cell that just crosses the course
        // to the left, and a cell to the right — leaving a clear gap on the right.
        val leftCell = radarHazard(cell(alongNM = 40.0, crossNM = -24.0, halfCross = 26.0, from = usPosition))
        val rightCell = radarHazard(cell(alongNM = 40.0, crossNM = 36.0, halfCross = 14.0, from = usPosition))
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = listOf(leftCell, rightCell), waypoints = emptyList(),
            ),
        )

        assertEquals(DeviationDirection.RIGHT, conflict.recommendedDirection, "the clear gap is on the right")
        // The apex should thread the gap (a modest offset), not fly around the whole
        // line (which would be a much larger offset).
        val offset = offsetFromCourse(conflict.deviationPath[1])
        assertTrue(offset > 4, "apex should sit right of course, inside the gap")
        assertTrue(offset < 30, "apex should thread the gap, not round the whole line")
    }

    @Test
    fun goesAroundNearEndOfSolidLine() {
        // A single wide cell with no gap, biased left of course → the shorter way
        // around is the right end.
        val wide = radarHazard(cell(alongNM = 40.0, crossNM = -10.0, halfCross = 40.0, from = usPosition))
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = listOf(wide), waypoints = emptyList(),
            ),
        )

        assertEquals(DeviationDirection.RIGHT, conflict.recommendedDirection)
        val offset = offsetFromCourse(conflict.deviationPath[1])
        assertTrue(offset > 25, "no gap → route around the near (right) end, a large offset")
        // The reroute around the wide cell must actually stay clear of it — a single
        // dogleg to the shared rejoin clips the near corner, so a side-hug is used.
        assertPathClear(conflict.deviationPath, listOf(wide.geometry.polygonPoints ?: emptyList()))
    }

    @Test
    fun reroutePathStaysClearAcrossADiagonalLine() {
        // A line of cells angling across course (near end left of course, far end well
        // right of it): the classic case where a single dogleg to the shared rejoin
        // cuts back through the line. The reroute must still stay clear of every cell
        // along its whole length — a side-hug down one edge of the line.
        val polys = listOf(
            cell(alongNM = 30.0, crossNM = -30.0, halfCross = 12.0, from = usPosition),
            cell(alongNM = 55.0, crossNM = -10.0, halfCross = 12.0, from = usPosition),
            cell(alongNM = 80.0, crossNM = 10.0, halfCross = 12.0, from = usPosition),
            cell(alongNM = 105.0, crossNM = 30.0, halfCross = 12.0, from = usPosition),
        )
        val downstream = Geo.destination(usPosition, course, 160.0)
        val wp = Waypoint(name = "RJOIN", latitude = downstream.latitude, longitude = downstream.longitude)
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = polys.map { radarHazard(it) }, waypoints = listOf(wp),
            ),
        )

        assertPathClear(conflict.deviationPath, polys)
    }

    @Test
    fun takesShorterSideAroundLineLeaningOneWay() {
        // A line that just touches the course at its near end and then leans hard to
        // the right. The shorter reroute is a small jog LEFT past the near cell, not a
        // long loop around the far right end. The path must take the left side and
        // stay clear — never swinging out to the far (right) edge of the line.
        val polys = listOf(
            cell(alongNM = 40.0, crossNM = 0.0, halfCross = 12.0, from = usPosition),
            cell(alongNM = 80.0, crossNM = 25.0, halfCross = 12.0, from = usPosition),
            cell(alongNM = 120.0, crossNM = 50.0, halfCross = 12.0, from = usPosition),
            cell(alongNM = 160.0, crossNM = 75.0, halfCross = 12.0, from = usPosition),
        )
        val downstream = Geo.destination(usPosition, course, 220.0)
        val wp = Waypoint(name = "RJOIN", latitude = downstream.latitude, longitude = downstream.longitude)
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = polys.map { radarHazard(it) }, waypoints = listOf(wp),
            ),
        )

        assertEquals(DeviationDirection.LEFT, conflict.recommendedDirection, "the shorter way is left of the near cell")
        assertPathClear(conflict.deviationPath, polys)
        // It must not loop around the far right edge (~68 NM out); the left hug stays
        // within a modest offset of course the whole way.
        for (point in conflict.deviationPath) {
            assertTrue(
                offsetFromCourse(point) < 25,
                "reroute must not swing out to the far right edge of the line",
            )
        }
    }

    // MARK: - Turn bound (never reverse the aircraft)

    /**
     * Every leg of the drawn mint line stays within the configured off-course turn
     * bound, so the reroute never turns the aircraft the long way around. Uses a
     * tight bound to force the clamp to engage on a path that would otherwise swing
     * out to a large offset.
     */
    @Test
    fun deviationPathRespectsTurnBound() {
        val tight = RouteWeatherConflictDetector()
        tight.config.maxDeviationTurnDegrees = 20.0
        val wide = radarHazard(cell(alongNM = 40.0, crossNM = -10.0, halfCross = 40.0, from = usPosition))
        val conflict = assertNotNull(
            tight.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = listOf(wide), waypoints = emptyList(),
            ),
        )
        val path = conflict.deviationPath
        assertTrue(path.size >= 2)
        for (i in 0 until path.size - 1) {
            val brg = Geo.bearing(path[i], path[i + 1])
            assertTrue(
                Geo.headingDifference(brg, course) <= 20 + 0.5,
                "leg $i turns beyond the deviation bound",
            )
        }
    }

    /**
     * At the default bound the mint line is never reversed: no leg exceeds ~100° off
     * course, even for a diagonal line whose rejoin sits well downrange.
     */
    @Test
    fun deviationPathNeverReversesAtDefaultBound() {
        val polys = listOf(
            cell(alongNM = 30.0, crossNM = -30.0, halfCross = 12.0, from = usPosition),
            cell(alongNM = 55.0, crossNM = -10.0, halfCross = 12.0, from = usPosition),
            cell(alongNM = 80.0, crossNM = 10.0, halfCross = 12.0, from = usPosition),
            cell(alongNM = 105.0, crossNM = 30.0, halfCross = 12.0, from = usPosition),
        )
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = polys.map { radarHazard(it) }, waypoints = emptyList(),
            ),
        )
        for (i in 0 until conflict.deviationPath.size - 1) {
            val brg = Geo.bearing(conflict.deviationPath[i], conflict.deviationPath[i + 1])
            assertTrue(
                Geo.headingDifference(brg, course) <= 100 + 0.5,
                "the mint line must never turn the aircraft around",
            )
        }
    }

    // MARK: - Turn-back symmetry (gradual rejoin, not a 90° squeeze)

    /**
     * A wide wall of precipitation squarely on course forces a side-hug down one edge.
     * The closing leg back onto course must be a gradual (~30°) turn-back, not a ~90°
     * sideways jog — the compressed-rejoin bug. The turn-out and parallel legs are
     * unaffected; only the rejoin is pushed forward far enough to intercept gently.
     */
    @Test
    fun turnBackIsGradualNotNinetyDegrees() {
        val wall = radarHazard(
            cell(alongNM = 60.0, crossNM = 0.0, halfAlong = 30.0, halfCross = 25.0, from = usPosition),
        )
        val downstream = Geo.destination(usPosition, course, 260.0)
        val wp = Waypoint(name = "RJOIN", latitude = downstream.latitude, longitude = downstream.longitude)
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = listOf(wall), waypoints = listOf(wp),
            ),
        )
        val path = conflict.deviationPath
        assertTrue(path.size >= 3, "a wall on course must produce a hug")
        val n = path.size
        val lastLeg = Geo.headingDifference(Geo.bearing(path[n - 2], path[n - 1]), course)
        assertTrue(lastLeg <= 45, "the turn-back onto course must be gradual, not a ~90° squeeze")
        assertPathClear(path, listOf(wall.geometry.polygonPoints ?: emptyList()))
    }

    // MARK: - Gentle-intercept safety net (no ~90° / backwards entries or exits)

    /** A course-aligned point [along] NM ahead and [cross] NM to the side (+ = right). */
    private fun coursePoint(along: Double, cross: Double, from: Coordinate, course: Double): Coordinate {
        val onC = Geo.destination(from, course, along)
        return Geo.destination(onC, course + (if (cross >= 0) 90 else -90), abs(cross))
    }

    /**
     * A hug whose closing leg jogs ~90° sideways back onto course — the squeeze that
     * truncation / on-route snapping / a tight rejoin cap can leave — must be reshaped into
     * a gentle ~30° turn-back, with the rejoin point itself left on the route.
     */
    @Test
    fun gentleInterceptReshapesASteepClosingLeg() {
        val p = usPosition
        // turn-out(0,0) → parallel-in(35,20) → parallel-out(80,20) → rejoin(82,0): the last leg
        // is a near-90° sideways jog from a 20 NM offset back to course in just 2 NM.
        val steep = listOf(
            coursePoint(0.0, 0.0, p, course),
            coursePoint(35.0, 20.0, p, course),
            coursePoint(80.0, 20.0, p, course),
            coursePoint(82.0, 0.0, p, course),
        )
        val out = detector.gentleInterceptAngles(steep, position = p, course = course, cores = emptyList())
        val n = out.size
        val closing = Geo.headingDifference(Geo.bearing(out[n - 2], out[n - 1]), course)
        assertTrue(closing <= 45, "the closing leg is reshaped to a gentle intercept, not a ~90° jog")
        assertTrue(abs(out[n - 1].latitude - steep[3].latitude) < 1e-6, "the rejoin point stays on the route")
        assertTrue(abs(out[n - 1].longitude - steep[3].longitude) < 1e-6)
    }

    /**
     * A hug whose opening leg steps out ~90° sideways onto the offset must be reshaped into a
     * gentle ~30° turn-out, with the turn-out point itself left on the route.
     */
    @Test
    fun gentleInterceptReshapesASteepOpeningLeg() {
        val p = usPosition
        // turn-out(40,0) → parallel-in(42,20): a near-90° step-out; the exit is already gentle.
        val steep = listOf(
            coursePoint(40.0, 0.0, p, course),
            coursePoint(42.0, 20.0, p, course),
            coursePoint(90.0, 20.0, p, course),
            coursePoint(140.0, 0.0, p, course),
        )
        val out = detector.gentleInterceptAngles(steep, position = p, course = course, cores = emptyList())
        val opening = Geo.headingDifference(Geo.bearing(out[0], out[1]), course)
        assertTrue(opening <= 45, "the opening leg is reshaped to a gentle turn-out, not a ~90° step")
        assertTrue(abs(out[0].latitude - steep[0].latitude) < 1e-6, "the turn-out point stays on the route")
        assertTrue(abs(out[0].longitude - steep[0].longitude) < 1e-6)
    }

    /**
     * Best-effort: when the only way to gentle the intercept would drag the closing leg through
     * an intense core, the reshape is declined and the original (steep-but-clear) leg is kept —
     * a valid reroute is never bent into weather.
     */
    @Test
    fun gentleInterceptKeepsSteepLegRatherThanCutACore() {
        val p = usPosition
        val steep = listOf(
            coursePoint(0.0, 0.0, p, course),
            coursePoint(35.0, 20.0, p, course),
            coursePoint(80.0, 20.0, p, course),
            coursePoint(82.0, 0.0, p, course),
        )
        // A heavy core sitting where the pulled-back closing leg would descend through it.
        val core = cell(alongNM = 65.0, crossNM = 5.0, halfAlong = 12.0, halfCross = 10.0, from = p)
        val out = detector.gentleInterceptAngles(
            steep,
            position = p,
            course = course,
            cores = listOf(RouteWeatherConflictDetector.CellBerth(core, 3.0)),
        )
        val n = out.size
        val closing = Geo.headingDifference(Geo.bearing(out[n - 2], out[n - 1]), course)
        assertTrue(closing > 50, "the steep leg is kept rather than reshaping it through a core")
    }

    // MARK: - Whole-path clearance (the return leg too)

    /**
     * The entire drawn reroute — every leg, the return included — must clear every cell,
     * not just the ones the initial candidate happened to rejoin past. A staggered line
     * whose tail sits where a naive return leg would cut back through it is held on its
     * offset longer (and widened if needed) until the whole path is clear.
     */
    @Test
    fun wholePathIncludingReturnLegClearsEveryCell() {
        val polys = listOf(
            cell(alongNM = 35.0, crossNM = -18.0, halfCross = 14.0, from = usPosition),
            cell(alongNM = 55.0, crossNM = 0.0, halfCross = 14.0, from = usPosition),
            cell(alongNM = 78.0, crossNM = 20.0, halfCross = 14.0, from = usPosition),
        )
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = polys.map { radarHazard(it) }, waypoints = emptyList(),
            ),
        )
        assertPathClear(conflict.deviationPath, polys)
    }

    // MARK: - Engages-weather protection (no mint line in clear air)

    /**
     * A reroute that runs entirely in clear air, far from every cell, must be recognized
     * as *not* engaging the weather, so callers can suppress drawing it. A path that hugs
     * the storm does engage. This is the guard against a mint line with no weather near it.
     */
    @Test
    fun pathEngagesWeatherDistinguishesClearAirFromAHug() {
        val storm = radarHazard(cell(alongNM = 40.0, crossNM = 0.0, from = usPosition))
        val east0 = Geo.destination(usPosition, 90.0, 200.0)
        val east1 = Geo.destination(usPosition, 90.0, 300.0)
        assertFalse(
            detector.pathEngagesWeather(listOf(east0, east1), hazards = listOf(storm)),
            "a line far from every cell does not engage the weather",
        )
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = listOf(storm), waypoints = emptyList(),
            ),
        )
        assertTrue(
            detector.pathEngagesWeather(conflict.deviationPath, hazards = listOf(storm)),
            "the drawn reroute around the storm engages it",
        )
    }

    // MARK: - Strategic-preview apex hug (no clear-air spike near a route bend)

    /**
     * A genuine reroute that rounds the cell has its apex right beside the weather, so the
     * stricter preview guard accepts it.
     */
    @Test
    fun previewApexHugsWeatherAcceptsAGenuineHug() {
        val storm = radarHazard(cell(alongNM = 40.0, crossNM = 0.0, from = usPosition))
        val route = listOf(usPosition, Geo.destination(usPosition, course, 120.0))
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = listOf(storm), waypoints = emptyList(), routeAhead = route,
            ),
        )
        assertTrue(
            detector.previewApexHugsWeather(conflict.deviationPath, route = route, hazards = listOf(storm)),
            "a reroute that rounds the cell has its apex beside the weather",
        )
    }

    /**
     * The reported anomaly: a "sharp angle out and back" whose *base* sits near a cell but
     * whose *apex* bulges off into clear air, far downrange from any weather. The loose
     * [RouteWeatherConflictDetector.pathEngagesWeather] guard is fooled (the base is near
     * the cell), but the stricter apex-hug guard the strategic preview uses rejects it, so
     * the faint line is suppressed.
     */
    @Test
    fun previewApexHugsWeatherRejectsAClearAirSpike() {
        val storm = radarHazard(
            cell(alongNM = 40.0, crossNM = 0.0, from = usPosition),
            intensity = WeatherIntensity.HEAVY,
        )
        val route = listOf(usPosition, Geo.destination(usPosition, course, 120.0))
        // Base near the cell's near edge; apex 40 NM off the route, 90 NM downrange, in
        // clear air; then back to the route — the truncated cross-bend stub.
        val base = Geo.destination(usPosition, course, 30.0)
        val onCourse90 = Geo.destination(usPosition, course, 90.0)
        val apex = Geo.destination(onCourse90, course + 90, 40.0)
        val end = Geo.destination(usPosition, course, 100.0)
        val spike = listOf(base, apex, end)
        assertTrue(
            detector.pathEngagesWeather(spike, hazards = listOf(storm)),
            "the loose guard is fooled — the spike's base sits inside the cell",
        )
        assertFalse(
            detector.previewApexHugsWeather(spike, route = route, hazards = listOf(storm)),
            "the apex bulges into clear air far from the cell, so the preview drops it",
        )
    }

    // MARK: - Leaves-the-route protection (no mint line on top of the flight path)

    /**
     * The reported anomaly: a mint "deviation" drawn right on top of the magenta flight
     * path. Neither engagement guard catches it — a line lying on the route passes
     * `pathEngagesWeather` trivially (the route runs into the cell) and
     * `previewApexHugsWeather` returns true for anything that barely leaves the route —
     * so the excursion is measured on its own: a line that goes nowhere is not a reroute.
     */
    @Test
    fun pathLeavesRouteRejectsALineDrawnOnTheFlightPath() {
        val storm = radarHazard(cell(alongNM = 40.0, crossNM = 0.0, from = usPosition))
        val route = listOf(usPosition, Geo.destination(usPosition, course, 160.0))
        // A "deviation" straight down the course line — what a gap centered on the route
        // (or the degenerate zero-offset fallback) draws.
        val onRoute = listOf(
            usPosition,
            Geo.destination(usPosition, course, 60.0),
            Geo.destination(usPosition, course, 120.0),
        )
        assertTrue(
            detector.routeExcursionNM(onRoute, route = route) < 1,
            "a line along the course never leaves the route",
        )
        assertFalse(
            detector.pathLeavesRoute(onRoute, route = route),
            "a line that deviates nowhere must not be drawn as a deviation",
        )
        // Both existing guards are happy with it — which is why this one is needed.
        assertTrue(
            detector.pathEngagesWeather(onRoute, hazards = listOf(storm)),
            "the on-route line passes the engagement guard: the route runs into the cell",
        )
        assertTrue(
            detector.previewApexHugsWeather(onRoute, route = route, hazards = listOf(storm)),
            "the apex guard passes it too — there is no apex to test",
        )

        // A real reroute around the same cell does leave the route.
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = listOf(storm), waypoints = emptyList(), routeAhead = route,
            ),
        )
        assertTrue(
            detector.pathLeavesRoute(conflict.deviationPath, route = route),
            "a reroute that rounds the cell leaves the flight path",
        )
    }

    /**
     * No route to measure against → the excursion is unmeasurable, not zero. A line must
     * never be suppressed because nobody could tell how far off course it goes.
     */
    @Test
    fun routeExcursionIsUnmeasurableWithoutARoute() {
        val path = listOf(usPosition, Geo.destination(usPosition, course, 60.0))
        assertEquals(Double.MAX_VALUE, detector.routeExcursionNM(path, route = emptyList()))
        assertTrue(
            detector.pathLeavesRoute(path, route = listOf(usPosition)),
            "a single-point route cannot bound anything — draw the line",
        )
    }

    /**
     * A gap wide enough to fly that straddles the course used to be threaded straight down
     * the flight path: the gap's midpoint is ~0 cross-track, and the single-apex dogleg is
     * exempt from `minParallelOffsetNM`, so nothing widened it. The thread is now slid to
     * one side of its gap, so the drawn line actually leaves the route.
     */
    @Test
    fun gapStraddlingTheCourseIsNotThreadedDownTheFlightPath() {
        // Two cells ~40 NM ahead: the left one crosses the course corridor (inner edge 6 NM
        // left of course, so it genuinely blocks), the right one sits out at 14 NM. After
        // the 4 NM lateral buffers the clear gap runs from 2 NM left of course to 10 NM
        // right of it — a gap wide enough to fly whose midpoint (4 NM right) lands within
        // the excursion floor, i.e. a thread drawn essentially down the flight path.
        val polys = listOf(
            cell(alongNM = 40.0, crossNM = -20.0, halfCross = 14.0, from = usPosition),
            cell(alongNM = 40.0, crossNM = 26.0, halfCross = 12.0, from = usPosition),
        )
        val route = listOf(usPosition, Geo.destination(usPosition, course, 200.0))
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = polys.map { radarHazard(it) },
                waypoints = emptyList(), routeAhead = route,
            ),
        )

        assertTrue(
            detector.pathLeavesRoute(conflict.deviationPath, route = route),
            "the drawn deviation must leave the flight path, not run down it",
        )
        // Sliding the thread must not push it into either cell.
        assertPathClear(conflict.deviationPath, polys)
    }

    // MARK: - Rejoin cap (never route past the destination / approach)

    /** The along-course component (NM) of a point relative to the northbound course. */
    private fun alongFromCourse(point: Coordinate): Double {
        val d = Geo.distanceNM(usPosition, point)
        val delta = (Geo.bearing(usPosition, point) - course) * PI / 180
        return d * cos(delta)
    }

    /**
     * Weather sitting well downrange (and, implicitly, the destination beyond it)
     * must not pull the mint line past the rejoin cap: every vertex intercepts the
     * route at or before the cap (here a fix 90 NM ahead, e.g. the first ILS fix).
     */
    @Test
    fun mintLineNeverRoutesPastTheRejoinCap() {
        val storm = radarHazard(
            cell(alongNM = 60.0, crossNM = 0.0, halfAlong = 20.0, halfCross = 20.0, from = usPosition),
        )
        val cap = Geo.destination(usPosition, course, 50.0)
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = listOf(storm), waypoints = emptyList(), rejoinCap = cap,
            ),
        )
        for (point in conflict.deviationPath) {
            assertTrue(
                alongFromCourse(point) <= 50 + 1,
                "the mint line must intercept the route no deeper than the rejoin cap",
            )
        }
    }

    // MARK: - On-path gate + tactical-range gating

    @Test
    fun farOnPathWeatherIsMonitoredButNotDrawn() {
        // On-path weather well beyond the draw range is still *detected* (so Diagnostics
        // can report it as "monitoring"), but its mint line is held: drawing a straight
        // reroute aimed across the route's bends at distant weather produced the runaway
        // "crazy" line. withinDrawRange (and withinTacticalRange / shouldPrompt) stay
        // false until the aircraft closes in.
        val farCell = radarHazard(cell(alongNM = 140.0, crossNM = 0.0, from = usPosition))
        val far = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = listOf(farCell), waypoints = emptyList(),
            ),
            "far on-path weather should still produce a conflict",
        )
        assertFalse(far.withinDrawRange, "far weather must not draw a mint line yet")
        assertFalse(far.withinTacticalRange, "far weather is out of tactical range")
        assertFalse(far.shouldPrompt, "the banner / advisory must not fire for far weather")

        // The same cell up close is within draw + tactical range and prompts.
        val nearCell = radarHazard(cell(alongNM = 45.0, crossNM = 0.0, from = usPosition))
        val near = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = listOf(nearCell), waypoints = emptyList(),
            ),
        )
        assertTrue(near.deviationPath.size >= 2, "a near conflict draws the mint line")
        assertTrue(near.withinDrawRange, "near weather draws the mint line")
        assertTrue(near.withinTacticalRange, "near weather is within tactical range")
        assertTrue(near.shouldPrompt, "near weather raises the banner / advisory")
    }

    @Test
    fun mintLineDrawsAheadOfTheBannerButNotAtTheHorizon() {
        // The draw range sits between the tactical (banner) trigger and the far horizon,
        // so the reroute appears a little before the "contact ATC" banner, but weather at
        // the edge of the enroute lookahead is monitored only — never drawn as a line
        // that shoots across the map.
        // Just past the tactical trigger (60 NM) but within the draw range (75 NM): the
        // mint line is drawn as advance notice, yet the banner still holds. The cell is
        // centered 70 NM ahead (near edge ~60–65 NM), inside the draw range.
        val advance = radarHazard(cell(alongNM = 70.0, crossNM = 0.0, halfAlong = 8.0, from = usPosition))
        val adv = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = listOf(advance), waypoints = emptyList(),
            ),
        )
        assertTrue(adv.withinDrawRange, "weather inside the draw range shows the reroute ahead")
        assertFalse(adv.withinTacticalRange, "but the banner holds until the tactical range")
        assertTrue(adv.deviationPath.size >= 2)

        // Beyond the draw range: detected and monitored, but the line is held.
        val horizon = radarHazard(cell(alongNM = 120.0, crossNM = 0.0, from = usPosition))
        val hz = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = listOf(horizon), waypoints = emptyList(),
            ),
        )
        assertFalse(hz.withinDrawRange, "weather at the horizon is monitored, not drawn")
    }

    // MARK: - Drawn-ahead geometry (turn-out before the weather, 30° turns, min extent)

    @Test
    fun mintLineStartsAheadWithAThirtyDegreeTurnOut() {
        // A moderate cell ~60 NM ahead on course. The drawn line must not drift shallowly
        // from the aircraft: it starts at a turn-out point ahead (on the course line) and
        // makes a ~30° turn onto the offset there.
        val cellPoly = cell(alongNM = 70.0, crossNM = 0.0, halfAlong = 10.0, halfCross = 8.0, from = usPosition)
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE,
                hazards = listOf(radarHazard(cellPoly, intensity = WeatherIntensity.MODERATE)),
                waypoints = emptyList(),
            ),
        )
        val path = conflict.deviationPath
        assertTrue(path.size >= 2)
        // The start is ahead of the aircraft, on the course line (not a drift from the nose).
        assertTrue(
            alongFromCourse(path[0]) > 10,
            "the mint line must start ahead of the aircraft, before the weather",
        )
        assertTrue(
            abs(offsetFromCourse(path[0])) < 3,
            "the turn-out point sits on the route, not off to one side",
        )
        // The first leg is a real ~30° turn onto the offset, never a shallow drift.
        val turnOut = Geo.headingDifference(Geo.bearing(path[0], path[1]), course)
        assertTrue(turnOut >= 22, "the turn-out must be a genuine turn (~30°), not a drift")
        assertTrue(turnOut <= 50, "the turn-out must not overshoot a normal deviation turn")
    }

    @Test
    fun mintLineSpansAtLeastTheMinimumExtent() {
        // Even a compact cell must produce a maneuver at least the minimum extent long,
        // so the mint line never renders as a twitch on the map.
        val cellPoly = cell(alongNM = 55.0, crossNM = 0.0, halfAlong = 5.0, halfCross = 5.0, from = usPosition)
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE,
                hazards = listOf(radarHazard(cellPoly, intensity = WeatherIntensity.MODERATE)),
                waypoints = emptyList(),
            ),
        )
        val path = conflict.deviationPath
        val start = assertNotNull(path.firstOrNull())
        val end = assertNotNull(path.lastOrNull())
        assertTrue(
            Geo.distanceNM(start, end) >= 15 - 0.5,
            "the drawn deviation must span at least the minimum extent",
        )
    }

    /**
     * A wide red/extreme core well ahead — one needing the wide (~20 NM) berth — must still be
     * entered and left with gradual ~30° legs, not a square 90° step. The turn-out is pulled
     * earlier and the turn-back pushed out into clear air (rather than collapsing to a square
     * when the ideal transition would clip the bermed core), while the whole line stays clear.
     */
    @Test
    fun wideCoreStillGetsGradualTurnOutAndTurnBack() {
        // Extreme wall ~95 NM ahead on course (near edge well beyond the ~30° turn-out lead, so
        // a gradual turn onto the offset fits ahead of it — not the close-aboard exception).
        val wall = radarHazard(
            cell(alongNM = 120.0, crossNM = 0.0, halfAlong = 25.0, halfCross = 20.0, from = usPosition),
            intensity = WeatherIntensity.EXTREME,
        )
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = listOf(wall), waypoints = emptyList(),
            ),
        )
        val path = conflict.deviationPath
        assertTrue(path.size >= 4, "a wide core on course must produce a parallel hug")
        val n = path.size
        val turnOut = Geo.headingDifference(Geo.bearing(path[0], path[1]), course)
        assertTrue(turnOut <= 55, "the turn-out onto the offset must be gradual, not a ~90° step")
        assertTrue(turnOut >= 20, "the turn-out is still a genuine deviation turn")
        val turnBack = Geo.headingDifference(Geo.bearing(path[n - 2], path[n - 1]), course)
        assertTrue(turnBack <= 55, "the turn-back onto course must be gradual, not a ~90° squeeze")
        assertPathClear(path, listOf(wall.geometry.polygonPoints ?: emptyList()))
    }

    @Test
    fun weatherOffToTheSideDoesNotDrawADeviation() {
        // A moderate cell ~16 NM to the side of course, not crossing the centerline:
        // "nearby but not on top of the route" → no conflict, no mint line, no banner.
        val sideCell = radarHazard(
            cell(alongNM = 45.0, crossNM = 16.0, halfCross = 6.0, from = usPosition),
            intensity = WeatherIntensity.MODERATE,
        )
        assertNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = listOf(sideCell), waypoints = emptyList(),
            ),
            "weather off to the side of the route must not draw a deviation",
        )
    }

    @Test
    fun weatherStraddlingTheCourseStillDraws() {
        // The same cell moved onto the flight path (its near edge crosses the centerline)
        // must still be caught — tightening the corridor only excludes off-to-the-side cells.
        val onPath = radarHazard(
            cell(alongNM = 45.0, crossNM = 4.0, halfCross = 6.0, from = usPosition),
            intensity = WeatherIntensity.MODERATE,
        )
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = listOf(onPath), waypoints = emptyList(),
            ),
            "a cell straddling the course is on the flight path and must draw a deviation",
        )
        assertTrue(conflict.shouldPrompt)
    }

    /**
     * The detection corridor scales with intensity: a red/orange core skirting the route
     * (edge a little off the centerline, not crossed by it) is flagged, while moderate
     * precip at the same offset still isn't — so a live "clear hazard on the route, but
     * diagnostics say no conflict" for an intense core near the path is caught, without
     * re-opening the moderate off-to-the-side false positive.
     */
    @Test
    fun detectionCorridorScalesWithIntensity() {
        // A compact cell whose nearest edge sits `crossNM - 6` NM off the centerline,
        // never crossing it (so only the corridor half-width decides the outcome).
        fun conflict(crossNM: Double, intensity: WeatherIntensity): RouteWeatherConflict? {
            val poly = cell(alongNM = 45.0, crossNM = crossNM, halfCross = 6.0, from = usPosition)
            return detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE,
                hazards = listOf(radarHazard(poly, intensity = intensity)), waypoints = emptyList(),
            )
        }
        // Edge ~10 NM off course: moderate ignores it (±6), heavy and extreme catch it.
        assertNull(conflict(16.0, WeatherIntensity.MODERATE), "moderate precip 10 NM off the path stays off-path")
        assertNotNull(conflict(16.0, WeatherIntensity.HEAVY), "a heavy core 10 NM off the path is now flagged")
        assertNotNull(conflict(16.0, WeatherIntensity.EXTREME), "a red core 10 NM off the path is now flagged")
        // Edge ~14 NM off course: past the heavy corridor but within the extreme one.
        assertNull(conflict(20.0, WeatherIntensity.HEAVY), "a heavy core 14 NM off the path is beyond its corridor")
        assertNotNull(
            conflict(20.0, WeatherIntensity.EXTREME),
            "a red core 14 NM off the path is within its wide corridor",
        )
    }

    // MARK: - Minimum lateral offset (parallel legs stay >= 20 NM off the flight path)

    /**
     * A single cell straddling the course must be paralleled with the whole parallel leg at
     * least the configured minimum (20 NM) off the flight path — never shaved a few NM past
     * the weather. This is the fix for "the deviation is only a few NM off the flight path":
     * the tight hug that used to sit at the cell edge plus a small buffer is opened up to the
     * minimum lateral separation whenever the wider leg still clears.
     */
    @Test
    fun parallelHugKeepsAtLeastTheMinimumLateralOffset() {
        // A compact moderate cell on course — its natural (edge + 3 NM) hug would be ~11 NM
        // off, well inside the 20 NM minimum, so it must be widened out to 20 NM.
        val cellPoly = cell(alongNM = 50.0, crossNM = 0.0, halfCross = 8.0, from = usPosition)
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE,
                hazards = listOf(radarHazard(cellPoly, intensity = WeatherIntensity.MODERATE)),
                waypoints = emptyList(),
            ),
        )
        val path = conflict.deviationPath
        assertTrue(path.size >= 4, "an on-course cell forces a parallel hug")
        // The parallel leg is the widest-offset run of the drawn line.
        val maxOffset = path.maxOfOrNull { abs(offsetFromCourse(it)) } ?: 0.0
        assertTrue(
            maxOffset >= 20 - 0.5,
            "the parallel leg must sit at least the 20 NM minimum off the flight path",
        )
        assertPathClear(path, listOf(cellPoly))
    }

    /**
     * The minimum offset scales with the config knob: raising it widens the drawn parallel
     * leg accordingly (proving it is the knob, not an incidental berth, that governs the leg).
     */
    @Test
    fun minimumLateralOffsetTracksTheConfiguredValue() {
        val wide = RouteWeatherConflictDetector()
        wide.config.minParallelOffsetNM = 35.0
        val cellPoly = cell(alongNM = 50.0, crossNM = 0.0, halfCross = 8.0, from = usPosition)
        val conflict = assertNotNull(
            wide.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE,
                hazards = listOf(radarHazard(cellPoly, intensity = WeatherIntensity.MODERATE)),
                waypoints = emptyList(),
            ),
        )
        val maxOffset = conflict.deviationPath.maxOfOrNull { abs(offsetFromCourse(it)) } ?: 0.0
        assertTrue(maxOffset >= 35 - 0.5, "a larger configured minimum widens the parallel leg to match")
    }

    /**
     * Exemption: threading a genuine gap *between* two cells is not forced out to the 20 NM
     * minimum — you cannot hold 20 NM off centerline while flying through a ~20 NM gap. The
     * reroute keeps the tight threading offset rather than looping around the whole line.
     */
    @Test
    fun gapThreadIsExemptFromTheMinimumLateralOffset() {
        // Two cells ~40 NM ahead with a clear gap on the right (left cell crosses the course,
        // right cell out to its right) — the same geometry as the gap-threading test.
        val leftCell = radarHazard(cell(alongNM = 40.0, crossNM = -24.0, halfCross = 26.0, from = usPosition))
        val rightCell = radarHazard(cell(alongNM = 40.0, crossNM = 36.0, halfCross = 14.0, from = usPosition))
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = listOf(leftCell, rightCell), waypoints = emptyList(),
            ),
        )
        // The drawn line threads the gap at a tight offset — it must NOT be pushed out to the
        // minimum (which would put the leg inside the right cell) or looped around the line.
        val maxOffset = conflict.deviationPath.maxOfOrNull { abs(offsetFromCourse(it)) } ?: 0.0
        assertTrue(
            maxOffset < 20,
            "a gap-thread stays tight — the 20 NM minimum can't be held inside the gap",
        )
        assertPathClear(
            conflict.deviationPath,
            listOf(
                leftCell.geometry.polygonPoints ?: emptyList(),
                rightCell.geometry.polygonPoints ?: emptyList(),
            ),
        )
    }

    // MARK: - Widening the search rather than giving up

    /**
     * The reported anomaly: Diagnostics said *"no lateral deviation available"* and no mint
     * line was drawn, for weather that could plainly be flown around with a wider berth.
     * The routine search is bounded to `searchHalfWidthNM` (60 NM), so a system broader than
     * that produces no routine candidate at all; the wide last-resort pass then demanded a
     * path clear of **every** cell, which lighter precip scattered outboard denied — and the
     * solver fell through to the degenerate zero-offset line, drawn on top of the route and
     * therefore suppressed. The wide pass now relaxes in the same two steps as the routine
     * one, so it goes wider instead of giving up.
     */
    @Test
    fun widensBeyondTheRoutineBoundInsteadOfGivingUp() {
        // A heavy core 40 NM ahead spanning ±74 NM of course: clearing it needs ~77 NM of
        // offset, well beyond the 60 NM routine bound, so nothing routine-width is built.
        val core = cell(alongNM = 40.0, crossNM = 0.0, halfCross = 74.0, from = usPosition)
        // Moderate precip abutting each end and running out to ~200 NM — past the 150 NM wide
        // bound — so no reachable path is clear of *every* cell, only of the core.
        val moderateRight = cell(alongNM = 40.0, crossNM = 138.0, halfCross = 62.0, from = usPosition)
        val moderateLeft = cell(alongNM = 40.0, crossNM = -138.0, halfCross = 62.0, from = usPosition)
        val route = listOf(usPosition, Geo.destination(usPosition, course, 300.0))
        val hazards = listOf(
            radarHazard(core, intensity = WeatherIntensity.HEAVY),
            radarHazard(moderateRight, intensity = WeatherIntensity.MODERATE),
            radarHazard(moderateLeft, intensity = WeatherIntensity.MODERATE),
        )
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = hazards, waypoints = emptyList(), routeAhead = route,
            ),
        )

        assertTrue(
            detector.pathLeavesRoute(conflict.deviationPath, route = route),
            "a wider berth is flyable, so a line must be drawn — not the on-route fallback",
        )
        // Wide, but still bounded: it rounds the core rather than looping out past the
        // moderate returns (which would exceed the last-resort maximum).
        val maxOffset = conflict.deviationPath.maxOfOrNull { abs(offsetFromCourse(it)) } ?: 0.0
        assertTrue(maxOffset > 60, "clearing the core requires more than the routine bound")
        assertTrue(
            maxOffset <= detector.config.maxDetourOffsetNM,
            "the widened search stays inside the last-resort maximum",
        )
        // The intense core is what must never be cut; the lighter precip may be skirted.
        assertPathClear(conflict.deviationPath, listOf(core))
    }

    /**
     * The route merely *skirts* a line — the weather's edge sits a mile or two off course,
     * not across it. The tightest clearing hug is then only a few NM off the flight path:
     * the shortest clear path there is, and too tight to be drawn as a deviation at all
     * (`minRouteExcursionNM`), so it used to win the ranking and then be suppressed —
     * "no lateral deviation available" with open air a short turn away. The hug is now
     * opened up to clear the floor whenever the wider leg still clears.
     */
    @Test
    fun skirtedLineIsStillGivenADrawableTurnOut() {
        // Left cell whose right edge sits 6 NM left of course, right cell out at 14 NM: the
        // clear slot runs from ~3 NM left of course to ~11 NM right of it, so the tightest
        // clearing hug lands ~4 NM right — inside the excursion floor.
        val left = cell(alongNM = 40.0, crossNM = -20.0, halfCross = 14.0, from = usPosition)
        val right = cell(alongNM = 40.0, crossNM = 26.0, halfCross = 12.0, from = usPosition)
        val route = listOf(usPosition, Geo.destination(usPosition, course, 200.0))
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = listOf(radarHazard(left), radarHazard(right)),
                waypoints = emptyList(), routeAhead = route,
            ),
        )

        val maxOffset = conflict.deviationPath.maxOfOrNull { abs(offsetFromCourse(it)) } ?: 0.0
        assertTrue(
            maxOffset >= detector.config.minRouteExcursionNM,
            "the turn-out must clear the excursion floor, not hug the route",
        )
        assertTrue(
            detector.pathLeavesRoute(conflict.deviationPath, route = route),
            "so the deviation is actually drawn",
        )
        // Opening it up must not push it into either cell — the slot is what bounds it.
        assertPathClear(conflict.deviationPath, listOf(left, right))
    }

    /** The tight line still wins when one exists — widening is a last resort, not a default. */
    @Test
    fun staysTightWhenARoutineWidthPathClears() {
        val cellPoly = cell(alongNM = 50.0, crossNM = 0.0, halfCross = 8.0, from = usPosition)
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE,
                hazards = listOf(radarHazard(cellPoly, intensity = WeatherIntensity.MODERATE)),
                waypoints = emptyList(),
            ),
        )
        val maxOffset = conflict.deviationPath.maxOfOrNull { abs(offsetFromCourse(it)) } ?: 0.0
        assertTrue(
            maxOffset <= detector.config.searchHalfWidthNM,
            "a compact cell is still hugged close, never widened out",
        )
    }

    // MARK: - Prefer the parallel hug over a single-apex triangle

    /**
     * A cell biased to one side of course can be dodged either by a single-apex
     * triangle (2 legs / 3 points: turn out to an apex, turn straight back) or by a
     * parallel side-hug (3 legs / 4 points: turn out ~30°, run alongside the weather,
     * turn ~30° back). The triangle is a touch shorter, but real weather deviations
     * parallel the weather — so the drawn mint line must be the parallel hug, not the
     * single-turn triangle.
     */
    @Test
    fun prefersParallelHugOverSingleApexTriangle() {
        // A heavy cell just right of course (near edge ~2 NM right), where a shallow
        // dogleg around the near (left) end clears the cell — the case that used to be
        // drawn as a 3-point triangle.
        val cellPoly = cell(alongNM = 45.0, crossNM = 10.0, halfCross = 8.0, from = usPosition)
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE,
                hazards = listOf(radarHazard(cellPoly, intensity = WeatherIntensity.HEAVY)),
                waypoints = emptyList(),
            ),
        )
        val path = conflict.deviationPath

        assertEquals(
            DeviationDirection.LEFT,
            conflict.recommendedDirection,
            "the shorter side of a right-biased cell is left",
        )
        // A parallel hug has at least four points (start, turn-out, turn-back, rejoin);
        // a single-apex triangle has only three.
        assertTrue(
            path.size >= 4,
            "the reroute must be a parallel hug (4+ points), not a 3-point triangle",
        )
        // It has an interior leg that runs roughly parallel to course — the alongside
        // leg a triangle lacks (both of a triangle's legs angle away from the course).
        var hasParallelLeg = false
        for (i in 0 until path.size - 1) {
            if (Geo.headingDifference(Geo.bearing(path[i], path[i + 1]), course) < 15) hasParallelLeg = true
        }
        assertTrue(hasParallelLeg, "the hug must include a leg parallel to course")
        // Both the turn-out onto the parallel leg and the turn-back off it are realistic
        // ~30° turns, not a single wide apex.
        val turnOut = Geo.headingDifference(Geo.bearing(path[0], path[1]), course)
        assertTrue(turnOut >= 22, "turn-out onto the parallel leg is a genuine ~30° turn")
        assertTrue(turnOut <= 50, "the turn-out must not overshoot a normal deviation turn")
        val n = path.size
        val turnBack = Geo.headingDifference(Geo.bearing(path[n - 2], path[n - 1]), course)
        assertTrue(turnBack <= 45, "the turn-back onto course is gradual, not a wide single turn")
        assertPathClear(path, listOf(cellPoly))
    }

    // MARK: - Tight to the storm (no giant last-resort detours)

    @Test
    fun keepsDeviationTightAroundACore() {
        // A heavy core blocking the corridor. The reroute hugs close to it — it must stay
        // within the routine offset bound, never swinging out to a huge last-resort loop.
        val core = cell(alongNM = 45.0, crossNM = 0.0, halfCross = 10.0, from = usPosition)
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE,
                hazards = listOf(radarHazard(core, intensity = WeatherIntensity.HEAVY)),
                waypoints = emptyList(),
            ),
        )
        for (point in conflict.deviationPath) {
            assertTrue(
                abs(offsetFromCourse(point)) <= 60 + 1,
                "a routine deviation must stay tight, never a huge detour",
            )
        }
        assertPathClear(conflict.deviationPath, listOf(core))
    }

    @Test
    fun deviationNeverExceedsTheMaxDetourOffset() {
        // Even a broad wall of precipitation across the corridor must never produce a
        // reroute wider than the absolute last-resort detour bound.
        val wall = generateSequence(-60.0) { it + 15.0 }.takeWhile { it <= 60.0 }.map {
            radarHazard(
                cell(alongNM = 45.0, crossNM = it, halfCross = 10.0, from = usPosition),
                intensity = WeatherIntensity.MODERATE,
            )
        }.toList()
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = wall, waypoints = emptyList(),
            ),
        )
        for (point in conflict.deviationPath) {
            assertTrue(
                abs(offsetFromCourse(point)) <= 150 + 1,
                "the mint line must never exceed the maximum last-resort detour",
            )
        }
    }

    @Test
    fun neverCutsAnIntenseCoreToStayTight() {
        // A wall of moderate precip too wide to clear tightly, with an extreme core off to
        // one side. The reroute may skirt the moderate wall, but it must never cut through
        // the extreme core — the intense cores are always avoided.
        val hazards = generateSequence(-55.0) { it + 11.0 }.takeWhile { it <= 55.0 }.map {
            radarHazard(
                cell(alongNM = 45.0, crossNM = it, halfCross = 7.0, from = usPosition),
                intensity = WeatherIntensity.MODERATE,
            )
        }.toMutableList()
        val corePoly = cell(alongNM = 45.0, crossNM = 33.0, halfCross = 8.0, from = usPosition)
        hazards.add(radarHazard(corePoly, intensity = WeatherIntensity.EXTREME))
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = hazards, waypoints = emptyList(),
            ),
        )
        val path = conflict.deviationPath
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            for (s in 0..30) {
                val f = s.toDouble() / 30
                val p = Coordinate(
                    a.latitude + (b.latitude - a.latitude) * f,
                    a.longitude + (b.longitude - a.longitude) * f,
                )
                if (Geo.distanceNM(usPosition, p) <= 8) continue
                assertFalse(
                    WeatherRouteAnalyzer.pointInPolygon(p, corePoly),
                    "the mint line must never cut through the extreme core",
                )
            }
        }
    }

    // MARK: - Final drawn geometry is what gets validated

    @Test
    fun finalDrawnPathClearsCoreUnderRejoinCap() {
        // An extreme core straddling the course with a rejoin cap just past it. The
        // capped, turn-bounded line that is actually drawn must still clear the core — the
        // clearance check governs the final geometry, not a pre-cap candidate shape.
        val corePoly = cell(alongNM = 45.0, crossNM = 0.0, halfAlong = 12.0, halfCross = 12.0, from = usPosition)
        val cap = Geo.destination(usPosition, course, 75.0)
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE,
                hazards = listOf(radarHazard(corePoly, intensity = WeatherIntensity.EXTREME)),
                waypoints = emptyList(), rejoinCap = cap,
            ),
        )
        assertPathClear(conflict.deviationPath, listOf(corePoly))
    }

    // MARK: - Ends at the first route intercept (no double-cross)

    @Test
    fun deviationEndsAtFirstRouteInterceptNoDoubleCross() {
        // The route runs north, then bends north-east just past the weather. A reroute
        // aimed at a straight-ahead rejoin would cross the bent route and come back down
        // to intercept it a second time. The drawn line must instead end at the first
        // intercept — crossing the route exactly once.
        val f1 = Geo.destination(usPosition, 0.0, 60.0) // north
        val f2 = Geo.destination(f1, 45.0, 90.0) // then NE
        val storm = radarHazard(cell(alongNM = 40.0, crossNM = 0.0, halfCross = 14.0, from = usPosition))
        val wps = listOf(
            Waypoint(name = "F1", latitude = f1.latitude, longitude = f1.longitude),
            Waypoint(name = "F2", latitude = f2.latitude, longitude = f2.longitude),
        )
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = 0.0, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = listOf(storm), waypoints = wps,
                routeAhead = listOf(f1, f2),
            ),
        )

        val route = listOf(usPosition, f1, f2)
        val path = conflict.deviationPath
        // The line ends on the filed route.
        val end = assertNotNull(path.lastOrNull())
        assertTrue(minDistanceToPolyline(end, route) < 1.0, "the deviation must end on the filed route")
        // The line now begins at its turn-out point, which sits on the route ahead of the
        // aircraft; crossings within the departure skip of that start are the shared
        // origin, not a re-intercept. It must then intercept the route exactly once (its
        // endpoint) — never crossing it and looping back to intercept a second time.
        val start = assertNotNull(path.firstOrNull())
        val hits = mutableListOf<Coordinate>()
        for (i in 0 until path.size - 1) {
            for (r in 0 until route.size - 1) {
                val x = segmentIntersectionPoint(path[i], path[i + 1], route[r], route[r + 1]) ?: continue
                if (Geo.distanceNM(start, x) <= 3) continue
                if (hits.none { Geo.distanceNM(it, x) < 1 }) hits.add(x)
            }
        }
        assertEquals(1, hits.size, "the deviation must intercept the route exactly once and end there")
    }

    /** Minimum distance (NM) from a point to a polyline (test helper). */
    private fun minDistanceToPolyline(p: Coordinate, line: List<Coordinate>): Double {
        if (line.size < 2) return Double.MAX_VALUE
        var best = Double.MAX_VALUE
        for (i in 0 until line.size - 1) {
            val a = line[i]
            val b = line[i + 1]
            for (s in 0..50) {
                val f = s.toDouble() / 50
                val q = Coordinate(
                    a.latitude + (b.latitude - a.latitude) * f,
                    a.longitude + (b.longitude - a.longitude) * f,
                )
                best = minOf(best, Geo.distanceNM(p, q))
            }
        }
        return best
    }

    /** Planar segment-intersection point (test helper mirroring the detector's geometry). */
    private fun segmentIntersectionPoint(
        a: Coordinate,
        b: Coordinate,
        c: Coordinate,
        d: Coordinate,
    ): Coordinate? {
        val x1 = a.longitude
        val y1 = a.latitude
        val x2 = b.longitude
        val y2 = b.latitude
        val x3 = c.longitude
        val y3 = c.latitude
        val x4 = d.longitude
        val y4 = d.latitude
        val denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        if (abs(denom) <= 1e-12) return null
        val t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / denom
        val u = ((x1 - x3) * (y1 - y2) - (y1 - y3) * (x1 - x2)) / denom
        if (t < 0 || t > 1 || u < 0 || u > 1) return null
        return Coordinate(y1 + t * (y2 - y1), x1 + t * (x2 - x1))
    }

    @Test
    fun detectsWeatherOnALegAfterATurn() {
        // The route turns at a nearby fix and then flies into weather on the *next*
        // leg. A straight corridor aimed at the near fix slides past the storm (the
        // failure the user hit: cells detected, but "No conflict"); following the
        // route polyline turns the corridor down-route and catches it.
        val f1 = Geo.destination(usPosition, 90.0, 15.0) // close, due east
        val f2 = Geo.destination(f1, 0.0, 80.0) // then north
        val storm = radarHazard(
            cell(alongNM = 40.0, crossNM = 0.0, halfAlong = 12.0, halfCross = 12.0, course = 0.0, from = f1),
        )
        val wps = listOf(
            Waypoint(name = "F1", latitude = f1.latitude, longitude = f1.longitude),
            Waypoint(name = "F2", latitude = f2.latitude, longitude = f2.longitude),
        )
        val courseToNext = Geo.bearing(usPosition, f1)

        // Straight corridor along the bearing to the next fix misses it.
        val straight = detector.detectConflict(
            position = usPosition, course = courseToNext, groundspeedKnots = 450.0,
            phase = FlightPhase.CRUISE, hazards = listOf(storm), waypoints = wps,
        )
        assertNull(straight, "a straight corridor to the next fix misses weather on the next leg")

        // Following the upcoming route polyline detects it.
        val routed = detector.detectConflict(
            position = usPosition, course = courseToNext, groundspeedKnots = 450.0,
            phase = FlightPhase.CRUISE, hazards = listOf(storm), waypoints = wps,
            routeAhead = listOf(f1, f2),
        )
        assertNotNull(routed, "following the route polyline detects weather on the next leg")
        assertEquals(WeatherIntensity.HEAVY, routed.severity)
    }

    @Test
    fun deviationRejoinsPromptlyNotAtADistantFix() {
        // A cell dead ahead with the next filed fix far beyond it. The drawn deviation
        // must return to course just past the weather (a compact reroute) rather than
        // stretch all the way to that distant fix — chasing the far fix is what forced
        // a short side deviation to swing back across the storm and get rejected, so
        // the reroute took the long way (or drove straight through when boxed in). The
        // fix is still named for the rejoin clearance; it simply lies on ahead.
        val hazard = radarHazard(cell(alongNM = 40.0, crossNM = 0.0, from = usPosition))
        val far = Geo.destination(usPosition, course, 150.0)
        val wp = Waypoint(name = "FODAK", latitude = far.latitude, longitude = far.longitude)
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = listOf(hazard), waypoints = listOf(wp),
            ),
        )

        assertEquals("FODAK", conflict.rejoinFix?.name, "the downstream fix is still named for the rejoin")
        val end = assertNotNull(conflict.deviationPath.lastOrNull())
        val endDist = Geo.distanceNM(usPosition, end)
        assertTrue(
            endDist < 90,
            "the drawn deviation rejoins course just past the weather, not at the 150 NM fix",
        )
    }

    @Test
    fun rejoinFollowsTheRouteSouthThroughATurn() {
        // The route runs east, then turns south just past the weather. The intercept
        // back onto the route is therefore to the south — so the deviation length must
        // be measured to that southward turn (not a straight-ahead point), which makes
        // the southern deviation the shortest. Verify the drawn line rejoins on the
        // route's southward leg: its endpoint is well south of the aircraft.
        val f1 = Geo.destination(usPosition, 90.0, 50.0) // east
        val f2 = Geo.destination(f1, 180.0, 80.0) // then south
        val storm = radarHazard(
            cell(alongNM = 45.0, crossNM = 0.0, halfCross = 12.0, course = 90.0, from = usPosition),
        )
        val wps = listOf(
            Waypoint(name = "F1", latitude = f1.latitude, longitude = f1.longitude),
            Waypoint(name = "F2", latitude = f2.latitude, longitude = f2.longitude),
        )
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = Geo.bearing(usPosition, f1), groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = listOf(storm), waypoints = wps,
                routeAhead = listOf(f1, f2),
            ),
        )

        val end = assertNotNull(conflict.deviationPath.lastOrNull())
        assertTrue(
            end.latitude < usPosition.latitude - 0.3,
            "the deviation should rejoin on the route's southward leg, not straight ahead",
        )
    }

    @Test
    fun rejoinsAtFirstSystemNotStretchedToADistantSecondSystem() {
        // Two systems on a northbound route: one ~40 NM ahead, another ~150 NM ahead with
        // a wide clear gap between them. The drawn line must rejoin just past the FIRST
        // system — compact around it — not stretch all the way to the second system near
        // the destination (the mislocated "line past the weather, ending near the airport").
        val storm1 = radarHazard(cell(alongNM = 40.0, crossNM = 0.0, halfCross = 12.0, from = usPosition))
        val storm2 = radarHazard(cell(alongNM = 150.0, crossNM = 0.0, halfCross = 12.0, from = usPosition))
        val f1 = Geo.destination(usPosition, 0.0, 100.0)
        val f2 = Geo.destination(usPosition, 0.0, 200.0)
        val wps = listOf(
            Waypoint(name = "F1", latitude = f1.latitude, longitude = f1.longitude),
            Waypoint(name = "F2", latitude = f2.latitude, longitude = f2.longitude),
        )
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = 0.0, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = listOf(storm1, storm2), waypoints = wps,
                routeAhead = listOf(f1, f2),
            ),
        )
        val end = assertNotNull(conflict.deviationPath.lastOrNull())
        assertTrue(
            alongFromCourse(end) < 120,
            "the line rejoins past the first system, not stretched to the second ~150 NM away",
        )
        assertPathClear(
            conflict.deviationPath,
            listOf(
                storm1.geometry.polygonPoints ?: emptyList(),
                storm2.geometry.polygonPoints ?: emptyList(),
            ),
        )
    }

    // MARK: - Complex shapes (variable-offset, multi-leg hug)

    @Test
    fun upperHullTracesOutboardEnvelope() {
        // A staggered set of points: the hull keeps the outward-bulging envelope and drops
        // interior points that lie below it.
        val pts = listOf(
            RouteWeatherConflictDetector.HullPoint(0.0, 0.0),
            RouteWeatherConflictDetector.HullPoint(1.0, 5.0),
            RouteWeatherConflictDetector.HullPoint(2.0, 3.0),
            RouteWeatherConflictDetector.HullPoint(3.0, 8.0),
            RouteWeatherConflictDetector.HullPoint(4.0, 2.0),
            RouteWeatherConflictDetector.HullPoint(5.0, 0.0),
        )
        val hull = detector.upperHull(pts)
        assertEquals(0.0, hull.first().x, "the leftmost point is always on the hull")
        assertEquals(5.0, hull.last().x, "the rightmost point is always on the hull")
        for (i in 1 until hull.size) {
            assertTrue(hull[i].x > hull[i - 1].x, "the hull is monotonic in x")
        }
        assertTrue(hull.any { it.x == 3.0 && it.y == 8.0 }, "the outward peak is kept")
        assertFalse(hull.any { it.x == 2.0 }, "an interior point below the envelope is dropped")
    }

    @Test
    fun complexStaggeredLineStaysTightAndClear() {
        // A line that straddles course near the aircraft and bulges hard to the right
        // downrange — a shape a single fixed-offset parallel would have to swing wide for.
        // The reroute must stay clear of every cell and take the tight (left) side rather
        // than loop around the far-right bulge.
        val polys = listOf(
            cell(alongNM = 35.0, crossNM = 0.0, halfCross = 14.0, from = usPosition), // straddles course
            cell(alongNM = 70.0, crossNM = 20.0, halfCross = 12.0, from = usPosition), // right
            cell(alongNM = 105.0, crossNM = 45.0, halfCross = 12.0, from = usPosition), // far right
        )
        val downstream = Geo.destination(usPosition, course, 220.0)
        val wp = Waypoint(name = "RJOIN", latitude = downstream.latitude, longitude = downstream.longitude)
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE, hazards = polys.map { radarHazard(it) }, waypoints = listOf(wp),
            ),
        )
        assertPathClear(conflict.deviationPath, polys)
        for (p in conflict.deviationPath) {
            assertTrue(
                abs(offsetFromCourse(p)) < 40,
                "the reroute hugs the near/left edge, never loops around the far-right bulge",
            )
        }
    }

    @Test
    fun givesRedCellsAWiderBerthThanLighterCells() {
        // The same cell straddling the course, once heavy and once red/extreme. The
        // red core must be rounded with a noticeably wider berth than the heavy cell.
        // The cell is wide enough that even the heavy hug's natural berth exceeds the
        // 20 NM minimum lateral offset, so the red core's extra berth stays visible in the
        // offset rather than both being floored to the same minimum separation.
        val poly = cell(alongNM = 40.0, crossNM = 10.0, halfCross = 20.0, from = usPosition)
        fun bypassOffset(intensity: WeatherIntensity): Double {
            val conflict = assertNotNull(
                detector.detectConflict(
                    position = usPosition, course = course, groundspeedKnots = 450.0,
                    phase = FlightPhase.CRUISE,
                    hazards = listOf(radarHazard(poly, intensity = intensity)), waypoints = emptyList(),
                ),
            )
            return abs(offsetFromCourse(conflict.deviationPath[1]))
        }
        val heavy = bypassOffset(WeatherIntensity.HEAVY)
        val extreme = bypassOffset(WeatherIntensity.EXTREME)
        assertTrue(
            extreme > heavy + 5,
            "a red/extreme core must be rounded with a wider berth than a heavy cell",
        )
    }

    @Test
    fun terminalWeatherJustAfterDeparture() {
        // A cell 30 NM off the departure end, on course, is caught by the terminal
        // lookahead band (25–75 NM) while still on the ground / departing.
        val hazard = radarHazard(cell(alongNM = 30.0, crossNM = 0.0, halfCross = 10.0, from = usPosition))
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 0.0,
                phase = FlightPhase.TAKEOFF, hazards = listOf(hazard), waypoints = emptyList(),
            ),
        )
        assertEquals(WeatherIntensity.HEAVY, conflict.severity)
        assertTrue(conflict.shouldPrompt)
    }

    @Test
    fun deviationPathStaysClearOfCells() {
        // A recommended reroute must not pass through a cell anywhere along its
        // length — not just at the abeam point — so it never avoids one storm and
        // routes into another.
        val leftPoly = cell(alongNM = 40.0, crossNM = -24.0, halfCross = 26.0, from = usPosition)
        val rightPoly = cell(alongNM = 40.0, crossNM = 36.0, halfCross = 14.0, from = usPosition)
        val conflict = assertNotNull(
            detector.detectConflict(
                position = usPosition, course = course, groundspeedKnots = 450.0,
                phase = FlightPhase.CRUISE,
                hazards = listOf(radarHazard(leftPoly), radarHazard(rightPoly)), waypoints = emptyList(),
            ),
        )

        val path = conflict.deviationPath
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            for (s in 0..20) {
                val f = s.toDouble() / 20
                val p = Coordinate(
                    a.latitude + (b.latitude - a.latitude) * f,
                    a.longitude + (b.longitude - a.longitude) * f,
                )
                if (Geo.distanceNM(usPosition, p) <= 8) continue
                assertFalse(WeatherRouteAnalyzer.pointInPolygon(p, leftPoly), "path enters the left cell")
                assertFalse(WeatherRouteAnalyzer.pointInPolygon(p, rightPoly), "path enters the right cell")
            }
        }
    }

    @Test
    fun rejoinFixSelection() {
        val hazard = radarHazard(cell(alongNM = 40.0, crossNM = 0.0, from = usPosition))
        // A filed fix 100 NM ahead, downstream of the weather.
        val downstream = Geo.destination(usPosition, course, 100.0)
        val wp = Waypoint(name = "FODAK", latitude = downstream.latitude, longitude = downstream.longitude)
        val conflict = detector.detectConflict(
            position = usPosition, course = course, groundspeedKnots = 450.0,
            phase = FlightPhase.CRUISE, hazards = listOf(hazard), waypoints = listOf(wp),
        )
        assertEquals("FODAK", conflict?.rejoinFix?.name)
    }

    @Test
    fun noRejoinFixFallback() {
        val hazard = radarHazard(cell(alongNM = 40.0, crossNM = 0.0, from = usPosition))
        val conflict = detector.detectConflict(
            position = usPosition, course = course, groundspeedKnots = 450.0,
            phase = FlightPhase.CRUISE, hazards = listOf(hazard), waypoints = emptyList(),
        )
        assertNull(conflict?.rejoinFix, "no downstream fix means no rejoin fix")

        // The phraseology then falls back to "advise clear of weather".
        val engine = faaEngine()
        val phr = WeatherDeviationPhraseology(engine)
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val tx = phr.approvalNoRejoin(
            cs = cs, direction = DeviationDirection.RIGHT, degrees = 20, maintainAltitude = 37000,
        )
        assertTrue(tx.displayText.contains("advise clear of weather"))
        assertFalse(tx.displayText.contains("proceed direct"))
    }

    // MARK: - Deferred deviation (reroute drawn ahead: hold the turn, then issue it)

    @Test
    fun deferDeviationApprovesButHoldsTheTurn() {
        val engine = faaEngine()
        val phr = WeatherDeviationPhraseology(engine)
        val dev = WeatherDeviationEngine(phr)
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val inputs = WeatherDeviationEngine.Inputs(maintainAltitude = 37000, heading = 90)
        val result = dev.deferDeviation(
            cs = cs, conflict = null, direction = DeviationDirection.RIGHT, distanceNM = 30,
            inputs = inputs, context = WeatherDeviationContext(), facility = ATCFacility.CENTER,
        )
        assertEquals(
            WeatherDeviationState.DEVIATION_APPROVED,
            result.context.state,
            "the deviation is approved…",
        )
        assertNull(result.context.assignedHeading, "…but the turn is held — no heading assigned yet")
        assertNotNull(result.pilot, "the pilot's request is posted")
        val atc = result.atc.firstOrNull()?.displayText ?: ""
        assertTrue(atc.contains("deviation right of course approved"), atc)
        assertTrue(atc.contains("continue present heading"), atc)
        assertTrue(atc.contains("expect the turn"), atc)
    }

    /**
     * "Expect the turn in X miles" speaks the distance that was measured, rounded to fives.
     *
     * The caller already rounds the turn-out distance to the nearest 5 NM; rounding that
     * again to the nearest 10 in the phraseology inflated it — a turn-out 13 NM ahead became
     * 15, and 15 rounds *up* to "20 miles". So the same turn the advisory had just described
     * as "10 miles" was announced as a turn 20 miles ahead, and the pilot flew past it
     * waiting for a call at a distance that never came.
     */
    @Test
    fun expectDeviationSpeaksTheMeasuredTurnDistanceNotATensRounding() {
        val engine = faaEngine()
        val phr = WeatherDeviationPhraseology(engine)
        val dev = WeatherDeviationEngine(phr)
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val inputs = WeatherDeviationEngine.Inputs(maintainAltitude = 37000, heading = 90)

        fun turnPhrase(nm: Int): String = dev.deferDeviation(
            cs = cs, conflict = null, direction = DeviationDirection.RIGHT, distanceNM = nm,
            inputs = inputs, context = WeatherDeviationContext(), facility = ATCFacility.CENTER,
        ).atc.firstOrNull()?.displayText ?: ""

        val fifteen = turnPhrase(15)
        assertTrue(fifteen.contains("expect the turn in 15 miles"), fifteen)
        assertFalse(fifteen.contains("20 miles"), "15 NM must not round up to 20")

        val ten = turnPhrase(10)
        assertTrue(ten.contains("expect the turn in 10 miles"), ten)

        val twentyFive = turnPhrase(25)
        assertTrue(twentyFive.contains("expect the turn in 25 miles"), twentyFive)
    }

    // MARK: - Reroute redrawn ahead (entry point fell behind the aircraft)

    /**
     * The drawn reroute's entry point fell behind the aircraft, so the deviation was
     * redrawn ahead of it. The controller advises the revised deviation — and the call is
     * purely informational: the lifecycle state is untouched, so a pending pilot decision
     * (and the advisory still to come) stands exactly as it was.
     */
    @Test
    fun advisePathRedrawnIsInformationalAndKeepsTheLifecycle() {
        val engine = faaEngine()
        val phr = WeatherDeviationPhraseology(engine)
        val dev = WeatherDeviationEngine(phr)
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val ctx = WeatherDeviationContext(state = WeatherDeviationState.AWAITING_PILOT_INTENTIONS)
        val result = dev.advisePathRedrawn(
            cs = cs, distanceNM = 20, context = ctx, facility = ATCFacility.CENTER,
        )
        assertEquals(
            WeatherDeviationState.AWAITING_PILOT_INTENTIONS,
            result.context.state,
            "advising the redraw must not move the deviation lifecycle",
        )
        assertNull(result.pilot, "the controller initiates it — there is no pilot call")
        val atc = result.atc.firstOrNull()?.displayText ?: ""
        assertTrue(atc.contains("weather deviation updated"), atc)
        assertTrue(atc.contains("20 miles ahead"), atc)
    }

    /**
     * Nothing is assigned, but the call still carries its own read-back: the courtesy
     * "Roger". Without it the Read Back button falls through to a read-back re-derived
     * from the conversational state — a stale echo of whatever was said before.
     */
    @Test
    fun advisePathRedrawnReadsBackRoger() {
        val engine = faaEngine()
        val phr = WeatherDeviationPhraseology(engine)
        val dev = WeatherDeviationEngine(phr)
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val ctx = WeatherDeviationContext(state = WeatherDeviationState.AWAITING_PILOT_INTENTIONS)
        val result = dev.advisePathRedrawn(
            cs = cs, distanceNM = 20, context = ctx, facility = ATCFacility.CENTER,
        )
        val rb = result.atc.firstOrNull()?.readback
        assertNotNull(rb, "the advisory must carry its own read-back")
        assertEquals("Roger, ${cs.display}.", rb.displayText)
        assertEquals("Roger, ${cs.spoken}.", rb.spokenText)
        assertEquals(ATCFacility.CENTER, rb.facility)
    }

    /**
     * The pilot never answered the first advisory (or elected to continue, which resets the
     * lifecycle), so nothing is pending when the line is redrawn. The update then **opens
     * the decision** — awaiting-intentions is what puts the response card and its request
     * buttons on screen — and seeds the context from the redrawn line, so a deviation
     * requested off this call rejoins where the new line rejoins.
     */
    @Test
    fun advisePathRedrawnOpensTheDecisionWhenNothingIsPending() {
        val engine = faaEngine()
        val phr = WeatherDeviationPhraseology(engine)
        val dev = WeatherDeviationEngine(phr)
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val hazard = radarHazard(listOf(usPosition, usPosition, usPosition))
        val conflict = RouteWeatherConflict(
            hazard = hazard, distanceAheadNM = 20.0, relativeBearingDegrees = 0.0,
            leftClock = 12, centerClock = 12, rightClock = 12, estimatedTimeMinutes = null,
            severity = WeatherIntensity.HEAVY, leftBypassScore = 0.0, rightBypassScore = 0.0,
            recommendedDirection = DeviationDirection.LEFT, recommendedDeviationDegrees = 20,
            rejoinFix = Waypoint(name = "HOBTT", latitude = 41.0, longitude = -95.0),
            originalSegment = null, shouldPrompt = true,
            intersectionArea = emptyList(), deviationPath = emptyList(),
        )

        for (start in listOf(
            WeatherDeviationState.NONE,
            WeatherDeviationState.WEATHER_AHEAD_DETECTED,
            WeatherDeviationState.RESUMED_OWN_NAVIGATION,
        )) {
            val ctx = WeatherDeviationContext(state = start)
            val result = dev.advisePathRedrawn(
                cs = cs, distanceNM = 20, conflict = conflict, context = ctx, facility = ATCFacility.CENTER,
            )
            assertEquals(
                WeatherDeviationState.AWAITING_PILOT_INTENTIONS,
                result.context.state,
                "from $start the update must open the decision so the card comes up",
            )
            assertEquals("HOBTT", result.context.rejoinFix)
            assertEquals(DeviationDirection.LEFT, result.context.requestedDeviationDirection)
            assertEquals(hazard.id, result.context.activeHazardID)
        }
    }

    /**
     * A pilot already flying an approved deviation has nothing to activate, so the update
     * leaves the committed lifecycle alone. (The caller never redraws a committed line in
     * the first place — this is the engine holding the same line.)
     */
    @Test
    fun advisePathRedrawnLeavesACommittedDeviationAlone() {
        val engine = faaEngine()
        val phr = WeatherDeviationPhraseology(engine)
        val dev = WeatherDeviationEngine(phr)
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        for (start in listOf(
            WeatherDeviationState.DEVIATION_APPROVED,
            WeatherDeviationState.VECTORING_AROUND_WEATHER,
            WeatherDeviationState.DEVIATING_AROUND_WEATHER,
            WeatherDeviationState.CLEAR_OF_WEATHER,
        )) {
            val ctx = WeatherDeviationContext(state = start)
            val result = dev.advisePathRedrawn(
                cs = cs, distanceNM = 20, context = ctx, facility = ATCFacility.CENTER,
            )
            assertEquals(start, result.context.state, "an activated deviation must not be reopened by the update")
        }
    }

    @Test
    fun beginDeviationTurnVectorsOntoTheReroute() {
        val engine = faaEngine()
        val phr = WeatherDeviationPhraseology(engine)
        val dev = WeatherDeviationEngine(phr)
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val ctx = WeatherDeviationContext(
            state = WeatherDeviationState.DEVIATION_APPROVED,
            deviationStartLatitude = 40.0,
            deviationStartLongitude = -95.0,
            deviationStartHeading = 100,
        )
        val result = dev.beginDeviationTurn(
            cs = cs, heading = 110, maintainAltitude = 37000, context = ctx, facility = ATCFacility.CENTER,
        )
        assertEquals(
            WeatherDeviationState.VECTORING_AROUND_WEATHER,
            result.context.state,
            "reaching the turn-out begins the vector",
        )
        assertEquals(110, result.context.assignedHeading)
        assertNull(result.context.deviationStartLatitude, "the held turn is consumed once issued")
        assertTrue(
            result.atc.firstOrNull()?.displayText?.contains("fly heading 110") ?: false,
            result.atc.firstOrNull()?.displayText ?: "",
        )
    }

    // MARK: - Drifted off the reroute being flown

    /**
     * Re-vectoring an aircraft that has drifted off the reroute states the reason, assigns a
     * fresh heading with the maintain altitude, and clears the armed turn — it indexed the
     * geometry just replaced, and the caller re-arms against the re-anchored line.
     */
    @Test
    fun revectorOffPathAssignsAFreshHeadingAndClearsTheArmedTurn() {
        val engine = faaEngine()
        val phr = WeatherDeviationPhraseology(engine)
        val dev = WeatherDeviationEngine(phr)
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val ctx = WeatherDeviationContext(
            state = WeatherDeviationState.VECTORING_AROUND_WEATHER,
            assignedHeading = 40,
            pendingTurnIndex = 2,
            pendingRejoinHeading = 60,
            vectorApexLatitude = 40.0,
            vectorApexLongitude = -95.0,
            vectorLegBearing = 35.0,
        )
        val result = dev.revectorOffPath(
            cs = cs, heading = 75, maintainAltitude = 37000, context = ctx, facility = ATCFacility.CENTER,
        )
        assertEquals(WeatherDeviationState.VECTORING_AROUND_WEATHER, result.context.state)
        assertEquals(75, result.context.assignedHeading)
        assertNull(result.context.pendingTurnIndex, "the stale armed turn is cleared")
        assertNull(result.context.vectorApexLatitude)
        assertNull(result.pilot, "the controller initiates it — there is no pilot call")
        val atc = result.atc.firstOrNull()?.displayText ?: ""
        assertTrue(atc.contains("off the assigned deviation"), atc)
        assertTrue(atc.contains("fly heading 075"), atc)
        assertTrue(atc.contains("advise clear of weather"), atc)
        assertTrue(
            result.atc.firstOrNull()?.readback?.displayText?.contains("Heading 075") ?: false,
            "the heading and maintain altitude are read back",
        )
    }

    // MARK: - STAR handling

    @Test
    fun starDeviationAssignsMaintainAltitude() {
        val engine = faaEngine()
        val phr = WeatherDeviationPhraseology(engine)
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val tx = phr.starDeviationApproval(
            cs = cs, direction = DeviationDirection.RIGHT, degrees = null,
            maintainAltitude = 11000, starDisplay = "MUSCL TWO",
            starSpoken = "MUSCL TWO", rejoinFix = "GEP",
        )
        assertTrue(
            tx.displayText.contains("maintain 11,000"),
            "off-procedure deviation must preserve the altitude restriction",
        )
        assertTrue(tx.displayText.contains("expect to rejoin the MUSCL TWO arrival at GEP"))
    }

    @Test
    fun rejoinStarPhraseology() {
        val engine = faaEngine()
        val phr = WeatherDeviationPhraseology(engine)
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val tx = phr.rejoinStar(cs = cs, rejoinFix = "GEP", starDisplay = "MUSCL TWO", starSpoken = "MUSCL TWO")
        assertTrue(tx.displayText.contains("cleared direct GEP"))
        assertTrue(tx.displayText.contains("descend via the MUSCL TWO arrival"))
    }

    // MARK: - Terminology

    @Test
    fun precipitationWordingForRadarDerivedWeather() {
        val engine = faaEngine()
        val phr = WeatherDeviationPhraseology(engine)
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val hazard = radarHazard(
            listOf(usPosition, usPosition, usPosition),
            intensity = WeatherIntensity.HEAVY,
        )
        val conflict = RouteWeatherConflict(
            hazard = hazard, distanceAheadNM = 30.0, relativeBearingDegrees = 0.0,
            leftClock = 12, centerClock = 12, rightClock = 12, estimatedTimeMinutes = null,
            severity = WeatherIntensity.HEAVY, leftBypassScore = 0.0, rightBypassScore = 0.0,
            recommendedDirection = DeviationDirection.RIGHT, recommendedDeviationDegrees = 20,
            rejoinFix = null, originalSegment = null, shouldPrompt = true,
            intersectionArea = emptyList(), deviationPath = emptyList(),
        )
        val tx = phr.radarAdvisory(cs = cs, conflict = conflict)
        assertTrue(
            tx.displayText.contains("precipitation"),
            "radar-derived weather must be spoken as precipitation",
        )
        assertFalse(
            tx.displayText.lowercase().contains("turbulence"),
            "radar colors must never be called turbulence",
        )
    }

    @Test
    fun turbulenceWordingOnlyFromTurbulenceSpecificSources() {
        // Turbulence-capable sources.
        for (source in listOf(
            WeatherHazardSource.PIREP,
            WeatherHazardSource.SIGMET,
            WeatherHazardSource.CWA,
            WeatherHazardSource.GAIRMET,
        )) {
            assertTrue(source.supportsTurbulenceWording, "$source should support turbulence wording")
        }
        // Precipitation / surface-only sources.
        for (source in listOf(
            WeatherHazardSource.NOAA_RADAR,
            WeatherHazardSource.SATELLITE_ESTIMATE,
            WeatherHazardSource.METAR,
            WeatherHazardSource.TAF,
        )) {
            assertFalse(source.supportsTurbulenceWording, "$source must not imply turbulence")
        }
    }

    @Test
    fun satelliteEstimateSourceIsLabeledAsEstimateNotRadar() {
        // The satellite-estimate deviation source must read as an estimate, never as
        // radar, wherever the label surfaces (diagnostics / data-source captions).
        val label = WeatherHazardSource.SATELLITE_ESTIMATE.label
        assertTrue(label.lowercase().contains("estimate"))
        assertFalse(label.lowercase().contains("radar"))
    }

    @Test
    fun intensityUnknownWording() {
        val engine = faaEngine()
        val phr = WeatherDeviationPhraseology(engine)
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val hazard = radarHazard(
            listOf(usPosition, usPosition, usPosition),
            intensity = WeatherIntensity.UNKNOWN,
            move = null,
        )
        val conflict = RouteWeatherConflict(
            hazard = hazard, distanceAheadNM = 30.0, relativeBearingDegrees = 0.0,
            leftClock = 12, centerClock = 12, rightClock = 12, estimatedTimeMinutes = null,
            severity = WeatherIntensity.UNKNOWN, leftBypassScore = 0.0, rightBypassScore = 0.0,
            recommendedDirection = DeviationDirection.RIGHT, recommendedDeviationDegrees = 20,
            rejoinFix = null, originalSegment = null, shouldPrompt = true,
            intersectionArea = emptyList(), deviationPath = emptyList(),
        )
        val tx = phr.radarAdvisory(cs = cs, conflict = conflict)
        assertTrue(tx.displayText.contains("intensity unknown"))
    }

    @Test
    fun movementUnknownWording() {
        val engine = faaEngine()
        val phr = WeatherDeviationPhraseology(engine)
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val hazard = radarHazard(
            listOf(usPosition, usPosition, usPosition),
            intensity = WeatherIntensity.HEAVY,
            move = null,
        )
        val conflict = RouteWeatherConflict(
            hazard = hazard, distanceAheadNM = 30.0, relativeBearingDegrees = 0.0,
            leftClock = 12, centerClock = 12, rightClock = 12, estimatedTimeMinutes = null,
            severity = WeatherIntensity.HEAVY, leftBypassScore = 0.0, rightBypassScore = 0.0,
            recommendedDirection = DeviationDirection.RIGHT, recommendedDeviationDegrees = 20,
            rejoinFix = null, originalSegment = null, shouldPrompt = true,
            intersectionArea = emptyList(), deviationPath = emptyList(),
        )
        val tx = phr.radarAdvisory(cs = cs, conflict = conflict)
        assertTrue(tx.displayText.contains("movement unknown"))
    }

    // MARK: - Global / non-U.S. handling

    @Test
    fun globalSigmetHandlingOutsideRadarCoverage() {
        // A SIGMET along a European route is still handled even though NOAA radar
        // does not cover the region.
        val paris = Coordinate(48.85, 2.35)
        val polygon = cell(alongNM = 40.0, crossNM = 0.0, from = paris)
        val sigmet = WeatherHazard(
            source = WeatherHazardSource.SIGMET,
            phenomenon = WeatherPhenomenon.THUNDERSTORM,
            intensity = WeatherIntensity.EXTREME,
            geometry = HazardGeometry.Polygon(polygon),
            confidence = HazardConfidence.MEDIUM,
        )
        val conflict = detector.detectConflict(
            position = paris, course = course, groundspeedKnots = 450.0,
            phase = FlightPhase.CRUISE, hazards = listOf(sigmet), waypoints = emptyList(),
        )
        assertNotNull(conflict, "a SIGMET on the route is applicable globally")
        assertEquals(WeatherHazardSource.SIGMET, conflict.source)
        assertTrue(conflict.isConvectiveSigmet)
    }

    @Test
    fun noGAirmetGlobalAssumption() {
        // G-AIRMET is a contiguous-U.S. concept; the app never treats NOAA-tied
        // data as globally available.
        assertEquals("G-AIRMET", WeatherHazardSource.GAIRMET.label)
    }

    // MARK: - Turbulence / icing ride advisory (altitude, not lateral)

    @Test
    fun sigmetRideAdvisoryTurbulenceOffersSmootherAir() {
        val engine = faaEngine()
        val phr = WeatherDeviationPhraseology(engine)
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val tx = phr.sigmetRideAdvisory(cs = cs, hazardLabel = "severe turbulence", icing = false)
        assertTrue(tx.displayText.contains("severe turbulence"))
        assertTrue(tx.displayText.contains("smoother air"))
        assertTrue(tx.displayText.contains("Say intentions"))
        // A turbulence advisory is resolved with altitude, never a lateral deviation.
        assertFalse(tx.displayText.lowercase().contains("deviation"))
        assertFalse(tx.displayText.lowercase().contains("vector"))
    }

    @Test
    fun sigmetRideAdvisoryIcingFramesExit() {
        val engine = faaEngine()
        val phr = WeatherDeviationPhraseology(engine)
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val tx = phr.sigmetRideAdvisory(cs = cs, hazardLabel = "icing", icing = true)
        assertTrue(tx.displayText.contains("icing"))
        assertTrue(tx.displayText.contains("exit the icing"))
    }

    @Test
    fun rideSigmetSituationAwaitsIntentions() {
        val engine = faaEngine()
        val phr = WeatherDeviationPhraseology(engine)
        val dev = WeatherDeviationEngine(phr)
        val cs = phr.engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val result = dev.issueAdvisory(
            cs = cs,
            situation = WeatherDeviationEngine.Situation.RideSigmet(label = "severe turbulence", icing = false),
            context = WeatherDeviationContext(), facility = ATCFacility.CENTER,
        )
        assertEquals(WeatherDeviationState.AWAITING_PILOT_INTENTIONS, result.context.state)
        assertTrue(result.atc.firstOrNull()?.displayText?.contains("smoother air") ?: false)
    }

    // MARK: - Radar unavailable fallback

    @Test
    fun radarUnavailableGracefulFallback() {
        val engine = faaEngine()
        val phr = WeatherDeviationPhraseology(engine)
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val tx = phr.noRadarNoAdvisory(cs = cs)
        assertTrue(tx.displayText.contains("radar precipitation is not available for this region"))
        assertTrue(tx.displayText.contains("No significant aviation weather advisories are available"))
    }

    // MARK: - Merging adjacent deviations

    /** A point [alongNM] up the northbound course. */
    private fun onCourse(alongNM: Double): Coordinate = Geo.destination(usPosition, course, alongNM)

    /** A point [alongNM] up the course and [crossNM] to the side (+ = right of course). */
    private fun offCourse(alongNM: Double, crossNM: Double): Coordinate =
        Geo.destination(onCourse(alongNM), course + 90, crossNM)

    /** A minimal conflict carrying a hand-built deviation path, for the merge geometry. */
    private fun makeConflict(
        path: List<Coordinate>,
        direction: DeviationDirection,
        severity: WeatherIntensity = WeatherIntensity.HEAVY,
        hazard: WeatherHazard,
    ): RouteWeatherConflict = RouteWeatherConflict(
        hazard = hazard, distanceAheadNM = 30.0, relativeBearingDegrees = 0.0,
        leftClock = 11, centerClock = 12, rightClock = 1, estimatedTimeMinutes = null,
        severity = severity, leftBypassScore = 0.0, rightBypassScore = 0.0,
        recommendedDirection = direction, recommendedDeviationDegrees = 20,
        rejoinFix = null, originalSegment = null, shouldPrompt = true,
        intersectionArea = emptyList(), deviationPath = path,
    )

    /**
     * Two same-side deviations whose rejoin/turn-out sit within the merge window fold into
     * one continuous parallel hug: the first turn-out, the last rejoin, and no dip back to
     * the route in the gap between them.
     */
    @Test
    fun adjacentSameSideDeviationsMergeIntoOneParallelHug() {
        val cellA = radarHazard(cell(alongNM = 40.0, crossNM = 0.0, from = usPosition)) // along 30–50
        val cellB = radarHazard(cell(alongNM = 100.0, crossNM = 0.0, from = usPosition)) // along 90–110
        val devA = makeConflict(
            path = listOf(onCourse(25.0), offCourse(35.0, -22.0), offCourse(55.0, -22.0), onCourse(65.0)),
            direction = DeviationDirection.LEFT, hazard = cellA,
        )
        val devB = makeConflict(
            path = listOf(onCourse(85.0), offCourse(95.0, -22.0), offCourse(115.0, -22.0), onCourse(125.0)),
            direction = DeviationDirection.LEFT, hazard = cellB,
        )
        val route = listOf(onCourse(0.0), onCourse(200.0))

        val merged = detector.mergeAdjacentDeviations(listOf(devA, devB), listOf(cellA, cellB), route)

        assertEquals(1, merged.size, "the two adjacent same-side deviations fold into one")
        val path = merged[0].deviationPath
        assertTrue(
            Geo.distanceNM(path.first(), onCourse(25.0)) < 2,
            "the folded line keeps the first deviation's turn-out",
        )
        assertTrue(
            Geo.distanceNM(path.last(), onCourse(125.0)) < 2,
            "the folded line rejoins only at the last deviation's rejoin",
        )
        assertPathClear(
            path,
            listOf(
                cellA.geometry.polygonPoints ?: emptyList(),
                cellB.geometry.polygonPoints ?: emptyList(),
            ),
        )
        // Every interior vertex stays out on the offset — the line runs parallel through the
        // gap instead of dipping back to the course between the two cells.
        for (v in path.drop(1).dropLast(1)) {
            assertTrue(abs(offsetFromCourse(v)) > 15, "the hug holds its offset across the gap")
        }
    }

    /**
     * A clear gap wider than the merge window leaves the two deviations separate — they are
     * distinct systems, each with its own in-and-out maneuver.
     */
    @Test
    fun deviationsSeparatedByAWideGapAreNotMerged() {
        val cellA = radarHazard(cell(alongNM = 40.0, crossNM = 0.0, from = usPosition))
        val cellB = radarHazard(cell(alongNM = 200.0, crossNM = 0.0, from = usPosition))
        val devA = makeConflict(
            path = listOf(onCourse(25.0), offCourse(35.0, -22.0), offCourse(55.0, -22.0), onCourse(65.0)),
            direction = DeviationDirection.LEFT, hazard = cellA,
        )
        val devB = makeConflict(
            path = listOf(onCourse(185.0), offCourse(195.0, -22.0), offCourse(215.0, -22.0), onCourse(225.0)),
            direction = DeviationDirection.LEFT, hazard = cellB,
        )
        val route = listOf(onCourse(0.0), onCourse(300.0))

        val merged = detector.mergeAdjacentDeviations(listOf(devA, devB), listOf(cellA, cellB), route)
        assertEquals(2, merged.size, "a wide clear gap keeps the two systems separate")
    }

    /**
     * Deviations hugging opposite sides are never joined into one parallel run — connecting
     * their offsets would cross the route (and the weather) — even when they sit close.
     */
    @Test
    fun oppositeSideDeviationsAreNotMerged() {
        val cellA = radarHazard(cell(alongNM = 40.0, crossNM = 0.0, from = usPosition))
        val cellB = radarHazard(cell(alongNM = 100.0, crossNM = 0.0, from = usPosition))
        val devA = makeConflict(
            path = listOf(onCourse(25.0), offCourse(35.0, -22.0), offCourse(55.0, -22.0), onCourse(65.0)),
            direction = DeviationDirection.LEFT, hazard = cellA,
        )
        val devB = makeConflict(
            path = listOf(onCourse(85.0), offCourse(95.0, 22.0), offCourse(115.0, 22.0), onCourse(125.0)),
            direction = DeviationDirection.RIGHT, hazard = cellB,
        )
        val route = listOf(onCourse(0.0), onCourse(200.0))

        val merged = detector.mergeAdjacentDeviations(listOf(devA, devB), listOf(cellA, cellB), route)
        assertEquals(2, merged.size, "opposite-side hugs are left split")
    }

    /**
     * When the folded line would otherwise rejoin *inside* a cell (the packed-system case
     * the user flagged), the rejoin is slid forward along the route until it clears the
     * weather, so the merged deviation no longer terminates in a hazard.
     */
    @Test
    fun mergedRejoinIsPushedClearWhenItLandsInAHazard() {
        // along 30–50
        val cellA = radarHazard(cell(alongNM = 40.0, crossNM = 0.0, from = usPosition))
        // along 90–140
        val cellB = radarHazard(cell(alongNM = 115.0, crossNM = 0.0, halfAlong = 25.0, from = usPosition))
        val devA = makeConflict(
            path = listOf(onCourse(25.0), offCourse(35.0, -22.0), offCourse(55.0, -22.0), onCourse(65.0)),
            direction = DeviationDirection.LEFT, hazard = cellA,
        )
        // devB's own rejoin lands at along 120 — inside cellB.
        val devB = makeConflict(
            path = listOf(onCourse(85.0), offCourse(95.0, -22.0), offCourse(135.0, -22.0), onCourse(120.0)),
            direction = DeviationDirection.LEFT, hazard = cellB,
        )
        val route = listOf(onCourse(0.0), onCourse(220.0))

        val merged = detector.mergeAdjacentDeviations(listOf(devA, devB), listOf(cellA, cellB), route)
        assertEquals(1, merged.size, "the packed cells still fold into one hug")
        val path = merged[0].deviationPath
        val polyB = assertNotNull(cellB.geometry.polygonPoints)
        assertFalse(
            WeatherRouteAnalyzer.pointInPolygon(path.last(), polyB),
            "the folded line no longer terminates inside the hazard",
        )
        assertTrue(
            alongFromCourse(path.last()) > 140,
            "the rejoin is slid past the cell's far edge to clear air",
        )
        assertPathClear(path, listOf(polyB))
    }

    /** A single deviation (nothing adjacent) passes through the merge untouched. */
    @Test
    fun singleDeviationIsUnchangedByMerge() {
        val cellA = radarHazard(cell(alongNM = 40.0, crossNM = 0.0, from = usPosition))
        val devA = makeConflict(
            path = listOf(onCourse(25.0), offCourse(35.0, -22.0), offCourse(55.0, -22.0), onCourse(65.0)),
            direction = DeviationDirection.LEFT, hazard = cellA,
        )
        val merged = detector.mergeAdjacentDeviations(
            listOf(devA), listOf(cellA), listOf(onCourse(0.0), onCourse(120.0)),
        )
        assertEquals(1, merged.size)
        assertEquals(
            devA.deviationPath.size,
            merged[0].deviationPath.size,
            "an isolated deviation is left as-is",
        )
    }
}
