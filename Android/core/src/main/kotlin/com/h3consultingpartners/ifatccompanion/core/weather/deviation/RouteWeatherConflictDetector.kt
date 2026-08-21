package com.h3consultingpartners.ifatccompanion.core.weather.deviation

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.Waypoint
import com.h3consultingpartners.ifatccompanion.core.weather.WeatherRouteAnalyzer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Pure, deterministic detection of route-weather conflicts. Builds a corridor
 * from the aircraft along its course, finds the precipitation cells that block
 * it, and — instead of hopping around a single cell — projects every nearby cell
 * onto the cross-track axis and **threads the widest clear gap** between them
 * (going around the near end of a solid line when no gap is usable), the way a
 * controller vectors a pilot between cells. When a line lies roughly along course,
 * it also offers **side-hug** reroutes down either edge.
 *
 * The reroute is deliberately conservative, matching how a real tactical deviation
 * works:
 * - **Only when the weather is genuinely upcoming.** A deviation is surfaced only
 *   once the blocking weather's near edge is within `deviationTriggerNM`; farther
 *   out the route ahead is still clear, so no line is drawn yet.
 * - **Tight to the storm, wide only as a last resort.** Candidates are ranked so the
 *   line hugs the weather: the shortest routine-width path clear of every cell, else
 *   the shortest that at least clears the intense (heavy/extreme) cores while skirting
 *   lighter precip, and only then — when nothing tight can dodge the cores — the
 *   shortest wide detour. It never swings far out of the way when a closer path exists.
 * - **Validated as drawn.** Every candidate is finalized (capped to the rejoin limit,
 *   bounded to the max off-course turn) *before* it is validated for clearance, so the
 *   line actually drawn is the one checked against the cells.
 * - **Ends at the first route intercept.** The drawn line rejoins the filed route once
 *   and stops; it never crosses the route and loops back to intercept a second time.
 * No AI, no I/O — fully unit-testable.
 *
 * Ported from `IFATCCompanion/Weather/RouteWeatherConflictDetector.swift`.
 */
class RouteWeatherConflictDetector {

    data class Config(
        /** Lookahead band for terminal/departure/arrival phases (NM). */
        var terminalLookahead: ClosedFloatingPointRange<Double> = 25.0..75.0,
        /** Lookahead band for the enroute phase (NM). */
        var enrouteLookahead: ClosedFloatingPointRange<Double> = 80.0..180.0,
        /**
         * How close the on-path weather's near edge must be (NM) before the conflict
         * is worked as a *tactical* deviation — i.e. the "contact ATC" banner is raised
         * and (in Mock Mode) the advisory is auto-issued (`withinTacticalRange` →
         * `shouldPrompt`). The **mint line itself is drawn as soon as on-path weather is
         * detected anywhere within the lookahead**, so a pilot sees the suggested
         * reroute far ahead; only the banner/ATC call holds off until the aircraft
         * closes to within this range. Real-world tactical convective deviations are
         * flown from close in — pilots avoid severe/extreme echoes by ~20 NM laterally
         * (FAA AC 00-24C) and typically start deviating ~20–40 NM out, with ATC
         * coordinating a little earlier — so the banner appearing 100–250 NM in advance
         * is neither realistic nor useful, while the advisory line ahead is.
         */
        var deviationTriggerNM: Double = 60.0,
        /**
         * How close the on-path weather's near edge must be (NM) before the mint reroute
         * line is actually **drawn** on the map. The conflict is still *detected* out to the
         * full lookahead (so Diagnostics can report far weather as "monitoring"), but the
         * drawn line is held until the weather is within this range. Drawing the line
         * for weather far ahead — often past one or more route bends — produced a long
         * straight line that cut across the near route legs toward distant weather (the
         * "crazy mint line"): the deviation is built as a straight-corridor offset aimed
         * at the blockage, which is only geometrically meaningful once the aircraft is
         * roughly committed toward it. Set just beyond `deviationTriggerNM` so the
         * reroute still appears a little before the "contact ATC" banner, but never at
         * the far edge of the enroute lookahead where it reads as a runaway line.
         */
        var mintLineDrawNM: Double = 75.0,
        /**
         * Half-width of the route corridor around the course line (NM) for **moderate**
         * precipitation. A cell only counts as a conflict (draws a mint line / can raise the
         * advisory) when it is genuinely **on the flight path** — within this half-width of the
         * course centerline, or crossed by it. Kept tight for moderate returns so weather merely
         * *near* the route (off to one side) doesn't trigger a deviation; a cell that actually
         * straddles the course is still caught by the centerline-through-polygon test regardless
         * of this value. Heavier returns widen it (below), since the reroute rounds them by a
         * wider berth — so a red core skirting the route is caught, not reported "no conflict".
         */
        var corridorHalfWidthNM: Double = 6.0,
        /**
         * Corridor half-width for **heavy** (orange) precipitation (NM). Wider than moderate:
         * a heavy cell the route passes close to is worth flagging even when its edge sits a
         * little off the centerline.
         */
        var corridorHalfWidthHeavyNM: Double = 12.0,
        /**
         * Corridor half-width for **extreme** (red) precipitation (NM). Widest, matching the
         * wide berth the reroute keeps from a convective core (~20 NM, FAA AC 00-24C) — a red
         * core skirting the route is caught here instead of slipping past the tight moderate
         * corridor and reporting "no conflict".
         */
        var corridorHalfWidthExtremeNM: Double = 18.0,
        /** Minutes of travel used for the time-based lookahead fallback. */
        var timeLookaheadMinutes: ClosedFloatingPointRange<Double> = 20.0..45.0,
        /** Light precipitation only prompts when this close and near-dead-ahead. */
        var lightImmediateNM: Double = 20.0,
        /** Max relative bearing (deg) for "directly ahead" (light-precip prompting). */
        var directlyAheadDegrees: Double = 25.0,
        /** How far beyond the weather a rejoin fix may sit (NM). */
        var rejoinReachBeyondNM: Double = 150.0,
        /** Minimum clearance past the far edge before a fix counts as "downstream". */
        var rejoinDownstreamMarginNM: Double = 10.0,

        // MARK: Gap threading
        /**
         * Lateral clearance kept on each side of a precipitation cell (NM). Cells
         * are padded by this before gaps are measured, so a threaded gap keeps this
         * much room from the actual precipitation on both sides. Kept just above the
         * validated floor (`pathClearanceNM`) so a genuinely flyable gap between two
         * moderate/heavy cores isn't padded shut — the reroute then threads the gap
         * with a slight jog rather than looping around the whole line. Red/extreme
         * cores are unaffected: they use the wider `severeBerthNM` via `berthNM`.
         */
        var lateralBufferNM: Double = 4.0,
        /**
         * Minimum *clear* lateral width (after the buffers) for a gap between two
         * cells to count as threadable (NM). With the buffers above, a gap needs
         * `2 * lateralBufferNM + minGapWidthNM` ≈ 11 NM of clear air to be flown —
         * low enough to use the breaks in a broken line instead of rounding it.
         */
        var minGapWidthNM: Double = 3.0,
        /**
         * How far off the course line a **routine** deviation may steer for a
         * threadable gap or an around-the-end bypass (NM). The reroute hugs the storm
         * within this bound; anything wider is a last resort (`maxDetourOffsetNM`),
         * used only when nothing within this bound can clear the intense cores. Keeps
         * the mint line tight to the weather instead of swinging far out of the way.
         */
        var searchHalfWidthNM: Double = 60.0,
        /**
         * The absolute maximum lateral offset for a **last-resort** detour (NM), taken
         * only when no path within `searchHalfWidthNM` can even avoid the heavy/extreme
         * cores. Bounds how far out of the way the line may ever go, so a broad system
         * never produces a runaway loop far from the route — it caps every drawn
         * candidate, the whole-system hull rescue included.
         *
         * The wide search that uses it relaxes in the **same two steps** as the routine
         * one: clear of every cell first, then clear of the intense cores alone. Demanding
         * an everywhere-clear path was what made a system broader than `searchHalfWidthNM`
         * fail outright — no routine candidate could be built, nothing wide was clear of
         * every last light return, and the solver fell through to the degenerate on-route
         * line ("no lateral deviation available" with a plainly flyable berth available).
         */
        var maxDetourOffsetNM: Double = 150.0,
        /**
         * Cells whose along-track position is within this margin of the blocking
         * band are treated as part of the same line for gap analysis (NM).
         */
        var clusterAlongMarginNM: Double = 30.0,
        /**
         * The along-route clear gap (NM) that separates two **distinct weather systems**.
         * Cells strung together by clear gaps *smaller* than this count as one system —
         * the reroute hugs past all of them and rejoins beyond the last (the packed-systems
         * case); a *larger* gap ends the system, so the reroute rejoins at its exit and the
         * next system is worked separately. This is the single tuned knob for "how packed
         * is one system": it governs both the along-track cluster window the hug parallels
         * and where the route-following rejoin lands, so the parallel leg and the rejoin
         * always agree on the system's extent (the closing leg can't cut through a system
         * the parallel leg stopped short of). ~30–50 NM is the realistic band; tune here.
         */
        var systemSeparationNM: Double = 40.0,
        /**
         * A candidate deviation path must stay at least this far from every
         * precipitation cell to be accepted, so a reroute never threads a gap in one
         * storm only to cut through another (NM). This is the base margin for
         * moderate/heavy returns.
         */
        var pathClearanceNM: Double = 3.0,
        /**
         * Lateral clearance kept from the most intense (red/extreme) cells (NM). A
         * wide berth around convective cores — used for both path validation and
         * gap/side-hug spacing — so the reroute rounds them well clear instead of
         * shaving past or threading a coarse-sampled gap straight through one. Set to
         * the ~20 NM real-world guidance for avoiding severe/extreme radar echoes
         * (FAA AC 00-24C); moderate/heavy returns keep the tighter `pathClearanceNM`.
         */
        var severeBerthNM: Double = 20.0,
        /**
         * The minimum lateral separation (NM) a **parallel side-hug** keeps between the
         * flight path and its parallel leg. A real weather deviation turns well off course
         * and parallels the weather with a wide berth — it never shaves a few miles past a
         * cell — so the offset a hug settles on is widened out to at least this distance
         * from the route whenever the wider leg still clears every cell. Where widening
         * would re-enter weather it is *not* forced: threading a genuine gap *between* two
         * cells (you cannot hold 20 NM off centerline and still fit inside a 20 NM gap) or a
         * boxed-in system keeps the tightest clearing offset instead. Applies to the
         * parallel legs the pilot sees drawn alongside a system; the single-apex
         * gap-threading dogleg — which flies *between* cells — is exempt. This is the fix
         * for "the deviation is only a few NM off the flight path": moderate/heavy hugs used
         * to sit at just `lateralBufferNM` + the cell's berth off course. Set to the ~20 NM
         * real-world lateral avoidance for severe echoes (FAA AC 00-24C).
         */
        var minParallelOffsetNM: Double = 20.0,
        /**
         * The most a deviation leg may turn away from the course (degrees). ATC never
         * turns an aircraft the long way around a storm, so the drawn mint line — and
         * therefore the assigned vector / rejoin turn derived from it — is bounded to
         * this off-course angle. Any leg that would point further back is pulled in to
         * this bound, so the line never reverses the aircraft.
         */
        var maxDeviationTurnDegrees: Double = 100.0,
        /**
         * The initial turn a deviation makes to establish its parallel offset (degrees).
         * Real weather deviations turn out ~20–30° and then parallel the weather, so the
         * hug reaches its offset over enough distance to make the first leg this angle
         * rather than a 90° sideways step. When the weather sits right at the aircraft a
         * steeper turn is used instead (the gentle start would cut back through the cell).
         * The same angle shapes the turn-out at the start of the drawn line and the
         * turn-back at the rejoin, so the mint line makes a nice ~30° dogleg out and back.
         */
        var initialDeviationTurnDegrees: Double = 30.0,
        /**
         * The minimum end-to-end extent of the drawn maneuver (NM). A deviation shorter
         * than this reads as a twitch on the map, so a compact cell's reroute is stretched
         * (its rejoin pushed forward, within the cap) to at least this length.
         */
        var minDeviationExtentNM: Double = 15.0,
        /**
         * The minimum **lateral** excursion (NM) the drawn maneuver must make from the
         * filed route to be worth showing as a deviation. `minDeviationExtentNM` bounds
         * the line's *length*; this bounds how far off the flight path it actually gets.
         * A reroute that never leaves the route is not a reroute — it draws a mint line
         * lying on top of the magenta one, telling the pilot to fly the course they are
         * already flying. Two constructions produce that shape: a threadable gap centered
         * on the course (the single-apex dogleg is exempt from `minParallelOffsetNM`, so
         * nothing widens it), and the degenerate zero-offset fallback taken when no
         * candidate could be built at all. The first is fixed at the source — a
         * gap-threading target within this floor is slid just clear of it, to the roomier
         * side of its gap (`nudgedOffRoute`), whenever the slid path still clears every
         * cell — and both are caught at draw time by `pathLeavesRoute`, which suppresses
         * the line. The conflict itself is untouched: the weather is still detected, the
         * banner still fires, and the pilot can still request vectors; only the misleading
         * line is withheld. Matches the excursion below which `previewApexHugsWeather`
         * stops looking for an apex at all ("barely leaves the route"), so the two guards
         * meet without a gap.
         */
        var minRouteExcursionNM: Double = 5.0,
        /**
         * When a run of back-to-back deviations sits this close end-to-start — the rejoin
         * of one within this distance of the next one's turn-out — and both hug the same
         * side, they are folded into one continuous parallel hug rather than drawn as a
         * string of little in-and-out jogs that each dip back to the route (and, in a
         * packed system, rejoin inside the next cell). A pilot threading a complex system
         * holds the offset through the gaps and flies one long parallel deviation; this is
         * the along-gap window for treating adjacent deviations as that one maneuver. See
         * [mergeAdjacentDeviations].
         */
        var mergeAdjacentGapNM: Double = 30.0,
    )

    var config: Config = Config()

    /**
     * One projected sample: along-track and (signed, +right) cross-track NM, and
     * the relative bearing from the course line.
     */
    private data class Sample(val along: Double, val cross: Double, val relBearing: Double)

    /**
     * A cell projected into the course-relative (along/cross) frame — the reduced
     * form the corridor and gap-threading logic reason about.
     */
    private data class Projection(
        val hazard: WeatherHazard,
        val polygon: List<Coordinate>?,
        val center: Coordinate?,
        val radiusBuffer: Double,
        /** Along-track extent over all samples. */
        val alongMin: Double,
        val alongMax: Double,
        /** Near/far edge along-track for the portion in front (clamped >= 0). */
        val nearAlong: Double,
        val farAlong: Double,
        /**
         * Cross-track extent (signed, +right) of the portion ahead, buffered by any
         * point-radius. Used to build the lateral gap intervals.
         */
        val crossLo: Double,
        val crossHi: Double,
        val leftEdgeRel: Double,
        val rightEdgeRel: Double,
        val centerRel: Double,
        val leftExtent: Double,
        val rightExtent: Double,
        /** Whether this cell actually intersects the route corridor (a blocker). */
        val blocks: Boolean,
    )

    /** A precipitation cell paired with the clearance (NM) a reroute must keep from it. */
    data class CellBerth(val polygon: List<Coordinate>, val clearance: Double)

    /** A point in the course-relative (along = x, outboard cross = y) plane. */
    data class HullPoint(val x: Double, val y: Double)

    /** A candidate reroute: the drawn path, the lateral offset it steers for, and its shape. */
    private data class Candidate(val path: List<Coordinate>, val target: Double, val parallel: Boolean)

    /** A hug path plus the lateral offset it settled on. */
    private data class OffsetPath(val path: List<Coordinate>, val target: Double)

    /** The nearest moderate-or-greater hazard on a route, and how far along it begins. */
    data class RouteHazard(val hazard: WeatherHazard, val distanceNM: Double)

    // MARK: - Public API

    /**
     * Detect the most significant route-weather conflict ahead, if any, and the
     * recommended deviation around it — the shortest gap-threading or side-hug path
     * that stays clear of every cell.
     *
     * @param position current aircraft position.
     * @param course course/heading to fly (deg true) — bearing to the next fix or
     *   the aircraft heading. Used as the corridor direction unless [routeAhead]
     *   reveals weather on a later leg (see below).
     * @param groundspeedKnots for the time-based lookahead + ETA (null → phase band).
     * @param phase current flight phase (selects the lookahead band).
     * @param hazards normalized weather hazards to test (moderate-or-greater
     *   precipitation cells — SIGMET polygons are not fed here).
     * @param waypoints filed route fixes, for rejoin-fix selection.
     * @param routeAhead the upcoming route as ordered coordinates (fixes still ahead →
     *   destination). When supplied, the corridor follows the route's bends into
     *   the weather instead of a straight band along [course], so a storm sitting
     *   on a leg *after* a turn is still caught. Empty → straight-course detection.
     * @param rejoinCap the deepest point the reroute may rejoin the route (never past
     *   it). Used to keep the mint line from routing past the destination / into
     *   the approach — the caller passes the first approach fix (else the
     *   destination). Null → uncapped.
     */
    fun detectConflict(
        position: Coordinate,
        course: Double,
        groundspeedKnots: Double?,
        phase: FlightPhase,
        hazards: List<WeatherHazard>,
        waypoints: List<Waypoint>,
        routeAhead: List<Coordinate> = emptyList(),
        rejoinCap: Coordinate? = null,
    ): RouteWeatherConflict? {
        if (!position.isValid || hazards.isEmpty()) return null
        val lookahead = lookaheadNM(phase, groundspeedKnots)
        val flyCourse = routeAwareCourse(
            position = position,
            fallback = course,
            routeAhead = routeAhead,
            hazards = hazards,
            lookahead = lookahead,
        )
        // Rejoin on the route where it exits the weather — so when the route turns
        // (e.g. south) past the storm, the intercept is measured to that turn and a
        // deviation onto the shorter side wins. Null when no route is supplied.
        val routeRejoin = routeRejoinCoord(
            position = position,
            routeAhead = routeAhead,
            hazards = hazards,
            lookahead = lookahead,
        )
        val conflict = detect(
            position = position,
            course = flyCourse,
            groundspeedKnots = groundspeedKnots,
            phase = phase,
            hazards = hazards,
            waypoints = waypoints,
            rejoinOverride = routeRejoin,
            rejoinCap = rejoinCap,
        ) ?: return null
        // End the drawn line exactly where it first rejoins the filed route. The
        // deviation begins on the route, turns off it, and must come back to intercept it
        // **once** — it can't cross the route and loop back to intercept a second time.
        // So truncate it at the first point (past the departure) where it re-crosses the
        // upcoming route polyline. Truncation only ever shortens the path, so a candidate
        // validated clear stays clear. When it never re-crosses (it ends alongside the
        // route), fall back to snapping the final vertex onto the route so the line still
        // ends exactly on the flight plan.
        val routePoly = (listOf(position) + routeAhead).filter { it.isValid }
        if (routePoly.size >= 2 && conflict.deviationPath.size >= 2) {
            val truncated = truncatedAtFirstRouteIntercept(conflict.deviationPath, routePoly)
            if (truncated != null && truncated.size >= 2) {
                conflict.deviationPath = truncated
            } else {
                val last = conflict.deviationPath.last()
                val onRoute = nearestPointOnPolyline(last, routePoly)
                if (onRoute != null) {
                    val mutated = conflict.deviationPath.toMutableList()
                    mutated[mutated.size - 1] = onRoute
                    conflict.deviationPath = mutated
                }
            }
        }
        // Final safety net: after the route-intercept truncation / on-route snapping above (and
        // the earlier rejoin-cap clamp), reshape any remaining ~90°+ opening or closing leg into
        // a gentle ~30° intercept, so the mint line never enters or rejoins the flight path with
        // a square sideways jog or a backwards intercept.
        conflict.deviationPath = gentleInterceptAngles(
            conflict.deviationPath,
            position = position,
            course = flyCourse,
            cores = intenseCoreBerths(hazards),
        )
        return conflict
    }

    /**
     * Aim the detection corridor along the route rather than a straight bearing to
     * the next fix. The narrow corridor otherwise misses weather on the route
     * *after* a turn — the sampler still finds the cells (its window is far wider),
     * but the straight band slides past them, so hazards are seen with "no conflict".
     * Walks the upcoming route polyline (within the lookahead), finds the nearest
     * point on it that lies within a corridor half-width of a cell, and aims the
     * course from the aircraft at that blockage. Returns [fallback] unchanged when no
     * route is supplied or nothing on it is blocked — straight-ahead detection is
     * then unaffected.
     */
    private fun routeAwareCourse(
        position: Coordinate,
        fallback: Double,
        routeAhead: List<Coordinate>,
        hazards: List<WeatherHazard>,
        lookahead: Double,
    ): Double {
        val ahead = routeAhead.filter { it.isValid }
        if (ahead.isEmpty()) return fallback
        val route = listOf(position) + ahead
        if (route.size < 2) return fallback

        // The nearest point on the upcoming route (within the lookahead) that sits
        // within a corridor half-width of a cell — the first place the route flies
        // into weather, wherever it bends.
        var bestAlong = Double.MAX_VALUE
        var bestPoint: Coordinate? = null
        var cumulative = 0.0
        for (i in 0 until route.size - 1) {
            if (cumulative > lookahead) break
            val a = route[i]
            val b = route[i + 1]
            val segLen = Geo.distanceNM(a, b)
            val steps = max(1, (segLen / 5).toInt())
            for (s in 0..steps) {
                val t = s.toDouble() / steps
                val along = cumulative + segLen * t
                if (along > lookahead) break
                val p = interpolate(a, b, t)
                for (hazard in hazards) {
                    val half = corridorHalfWidth(hazard.intensity) + pointRadiusBuffer(hazard)
                    if (distanceHazardToPointNM(hazard, p) <= half && along < bestAlong) {
                        bestAlong = along
                        bestPoint = p
                    }
                }
            }
            cumulative += segLen
        }

        // Aim the course at the blockage. Too close for a stable bearing → keep the
        // filed course (the aircraft is essentially already at the weather).
        val point = bestPoint ?: return fallback
        if (Geo.distanceNM(position, point) <= 5) return fallback
        return Geo.bearing(position, point)
    }

    /**
     * The point on the route where the deviation should rejoin: just past the far
     * extent of the **first weather system** along the route (a contiguous run of cells,
     * merged across clear gaps smaller than `systemSeparationNM`) — *not* the farthest
     * weather anywhere on the route, which would stretch the line to a distant rejoin near
     * a downstream system. Because it follows the route's bends, a route that turns (say
     * south) past the storm puts the rejoin on that turn — so the reroute's length is
     * measured to the real intercept and the shorter-side deviation wins. Null when no
     * route is supplied or nothing on it is within the corridor (detection then rejoins
     * straight ahead, as before).
     */
    private fun routeRejoinCoord(
        position: Coordinate,
        routeAhead: List<Coordinate>,
        hazards: List<WeatherHazard>,
        lookahead: Double,
    ): Coordinate? {
        val ahead = routeAhead.filter { it.isValid }
        if (ahead.isEmpty()) return null
        val route = listOf(position) + ahead
        if (route.size < 2) return null

        // Walk the route and find the far edge of the **first system** — the first
        // contiguous run of weather, where cells separated by a clear gap smaller than
        // `systemSeparationNM` count as one system and a larger gap ends it. Rejoining at
        // the first system's exit — rather than the farthest weather anywhere on the route —
        // keeps the drawn line compact around that system instead of stretching it to a
        // distant rejoin near a downstream system or the destination (the "line drawn past
        // the weather, ending near the airport" failure). Systems beyond the gap are worked
        // separately (the preview walker steps to each in turn).
        var firstSystemFarAlong: Double? = null
        var cumulative = 0.0
        walk@ for (i in 0 until route.size - 1) {
            if (cumulative > lookahead) break
            val a = route[i]
            val b = route[i + 1]
            val segLen = Geo.distanceNM(a, b)
            val steps = max(1, (segLen / 5).toInt())
            for (s in 0..steps) {
                val t = s.toDouble() / steps
                val along = cumulative + segLen * t
                if (along > lookahead) break
                val p = interpolate(a, b, t)
                var inWeather = false
                for (hazard in hazards) {
                    val half = corridorHalfWidth(hazard.intensity) + pointRadiusBuffer(hazard)
                    if (distanceHazardToPointNM(hazard, p) <= half) {
                        inWeather = true
                        break
                    }
                }
                if (inWeather) {
                    firstSystemFarAlong = along
                } else {
                    val far = firstSystemFarAlong
                    // A full clear gap — the first system has ended; rejoin here.
                    if (far != null && along - far >= config.systemSeparationNM) break@walk
                }
            }
            cumulative += segLen
        }
        val far = firstSystemFarAlong ?: return null
        return pointOnRoute(route, far + 20)
    }

    /** The coordinate a given distance along a route polyline (clamped to its end). */
    private fun pointOnRoute(route: List<Coordinate>, target: Double): Coordinate {
        var cumulative = 0.0
        for (i in 0 until route.size - 1) {
            val a = route[i]
            val b = route[i + 1]
            val segLen = Geo.distanceNM(a, b)
            if (cumulative + segLen >= target) {
                val t = if (segLen <= 0) 0.0 else (target - cumulative) / segLen
                return interpolate(a, b, min(1.0, max(0.0, t)))
            }
            cumulative += segLen
        }
        return route.lastOrNull() ?: route[0]
    }

    /**
     * Distance (NM) from a hazard's shape to a point: 0 inside a polygon, else the
     * nearest edge / sample point.
     */
    private fun distanceHazardToPointNM(hazard: WeatherHazard, p: Coordinate): Double {
        val poly = hazard.geometry.polygonPoints
        if (poly != null && poly.size >= 3) return distanceToPolygonNM(p, poly)
        val pts = samplePoints(hazard)
        return pts.minOfOrNull { Geo.distanceNM(p, it) } ?: Double.MAX_VALUE
    }

    /**
     * Linear interpolation between two coordinates (planar; adequate at corridor
     * scale and consistent with the detector's other planar helpers).
     */
    private fun interpolate(a: Coordinate, b: Coordinate, t: Double): Coordinate =
        Coordinate(
            a.latitude + (b.latitude - a.latitude) * t,
            a.longitude + (b.longitude - a.longitude) * t,
        )

    /**
     * The single-course detection body: builds the corridor from [position] along
     * [course], finds the blocking cells, threads/hugs the shortest clear reroute,
     * and picks a downstream rejoin fix.
     */
    private fun detect(
        position: Coordinate,
        course: Double,
        groundspeedKnots: Double?,
        phase: FlightPhase,
        hazards: List<WeatherHazard>,
        waypoints: List<Waypoint>,
        rejoinOverride: Coordinate? = null,
        rejoinCap: Coordinate? = null,
    ): RouteWeatherConflict? {
        if (!position.isValid || hazards.isEmpty()) return null
        val lookahead = lookaheadNM(phase, groundspeedKnots)
        val corridorEnd = Geo.destination(position, course, lookahead)

        // Project every hazard; keep those that sit ahead and near the route.
        val projections = hazards.mapNotNull {
            projectHazard(it, position = position, course = course, lookahead = lookahead, corridorEnd = corridorEnd)
        }
        val blockers = projections.filter { it.blocks }
        if (blockers.isEmpty()) return null

        // The most significant blocker anchors severity, distance, clock and rejoin.
        val primary = blockers.drop(1).fold(blockers[0]) { best, cand ->
            if (cand.hazard.intensity > best.hazard.intensity) {
                cand
            } else if (cand.hazard.intensity == best.hazard.intensity && cand.nearAlong < best.nearAlong) {
                cand
            } else {
                best
            }
        }

        // The blocking "line" the aircraft is about to cross (all blockers, by
        // along-track extent), plus any nearby non-blocking cells that form part of
        // the same line — these are what the gap solver threads between.
        val bandNear = blockers.minOfOrNull { it.nearAlong } ?: primary.nearAlong
        val bandFar = blockers.maxOfOrNull { it.farAlong } ?: primary.farAlong

        // On-path weather is detected across the whole lookahead — the conflict is
        // returned regardless of distance — but two range gates govern what the pilot
        // sees. `withinTacticalRange` gates working it *now*: the banner / ATC advisory
        // (via `shouldPrompt`) hold off until the near edge is within the tactical
        // deviation range.
        val withinTacticalRange = bandNear <= config.deviationTriggerNM
        // `withinDrawRange` gates *drawing* the mint line. Far on-path weather is still
        // detected (for the "monitoring" diagnostics) but its line is held until the
        // weather is close enough that a straight tactical deviation is meaningful —
        // otherwise the straight-corridor line, aimed across the route's bends at distant
        // weather, shoots off across the map ("crazy mint line").
        val withinDrawRange = bandNear <= config.mintLineDrawNM
        // Cluster by the system-separation gap (not the tighter `clusterAlongMarginNM`),
        // so the line the hug parallels spans exactly the cells the route-following rejoin
        // treats as one system — the parallel leg then always reaches the rejoin.
        val lineCells = projections.filter {
            it.alongMax >= bandNear - config.systemSeparationNM &&
                it.alongMin <= bandFar + config.systemSeparationNM
        }
        // Candidate reroutes around the line come in two shapes:
        //   • single-apex doglegs (position → abeam-the-middle apex → rejoin) at each
        //     threadable gap and around either end, least-deviation first — a 2-leg /
        //     3-point triangle; and
        //   • side-hug paths (position → step out just before the near edge → hold
        //     that offset past the far edge → rejoin) down the left and right edges — a
        //     3-leg / 4-point parallel route that turns out ~30°, parallels the weather,
        //     then turns ~30° back onto course.
        // The hug is what lets a long line lying roughly along course be passed on the
        // genuinely shorter side — a single dogleg to the shared rejoin would cut back
        // across the line and be rejected. Every candidate is validated end-to-end. Among
        // the ones that stay clear the **parallel hug is preferred over the triangle** —
        // real weather deviations parallel the weather rather than cut a single wide turn
        // around it — with the triangle used only when no hug clears (e.g. threading a gap
        // that no straight parallel offset fits).
        val lineSet = if (lineCells.isEmpty()) blockers else lineCells
        val solution = threadSolution(lineSet)
        val midAlong = max(0.0, (bandNear + bandFar) / 2)
        val onCourse = Geo.destination(position, course, midAlong)

        // The full along-track span of the whole line — its non-blocking cells can
        // reach past the blocking band — so a side-hug runs parallel far enough to
        // clear the entire line before closing back to the rejoin.
        val lineNear = lineSet.minOfOrNull { it.nearAlong } ?: bandNear
        val lineFar = max(lineNear, lineSet.maxOfOrNull { it.farAlong } ?: bandFar)

        // The named downstream fix drives the ATC rejoin call ("proceed direct …").
        val rejoin = rejoinFix(
            waypoints = waypoints,
            position = position,
            course = course,
            farAlong = bandFar,
            lookahead = lookahead,
            polygon = primary.polygon,
        )
        // The drawn deviation, however, rejoins **just past the weather** — never at a
        // distant downstream fix. Chasing a far fix forces every candidate to swing
        // back across the storms to reach it, so a short one-side deviation gets
        // rejected and the reroute takes the long way round. Prefer the point where
        // the route itself exits the weather (so a route that turns past the storm
        // puts the intercept on that turn and the shorter side wins); otherwise return
        // to course right past the far edge. Either keeps each candidate compact.
        // The deepest along-course distance the reroute may rejoin the route — the
        // cap (first approach fix / destination) projected onto course. The mint line
        // must never route past it, even for weather sitting on the destination.
        val capAlong = rejoinCap?.let { project(it, position, course).along }?.let { if (it > 0) it else null }
        val rawRejoin = rejoinOverride ?: Geo.destination(position, course, lineFar + 20)
        val rejoinCoord = cappedToAlong(rawRejoin, capAlong, position, course)

        // Per-cell required clearance (berth) for every precipitation cell — a wide
        // berth for red/extreme cores, the base margin for lighter returns. Used both
        // to validate a whole candidate path and to search for the tightest clear hug.
        val cellBerths: List<CellBerth> = hazards.mapNotNull {
            val poly = it.geometry.polygonPoints
            if (poly == null || poly.size < 3) null else CellBerth(poly, berthNM(it.intensity))
        }
        // The intense cores (heavy + extreme — the orange/red returns) that a deviation
        // must *always* clear, each by its berth. Lighter (moderate) precipitation may be
        // skirted to keep the line tight to the storm when clearing everything would force
        // a wide detour; the intense cores never are.
        val intenseBerths = intenseCoreBerths(hazards)

        // A single dogleg abeam the middle of the line at a lateral offset.
        fun apexPath(target: Double): List<Coordinate> {
            val sideBearing = course + (if (target >= 0) 90 else -90)
            val apex = Geo.destination(onCourse, sideBearing, abs(target))
            return listOf(position, apex, rejoinCoord)
        }

        // A path that hugs one side of the line: turn out to `offset`, hold it parallel,
        // then close to the rejoin. Two refinements keep it realistic:
        //   • `minLead` is how far along course the turn-out reaches the offset, so the
        //     first leg is a ~30° deviation rather than a 90° sideways step. When the
        //     weather sits right at the aircraft the forward-angled start clips a cell
        //     and validation drops it, leaving the steeper `minLead: 0` variant.
        //   • the far turn-back is capped at the rejoin's along-distance, so the parallel
        //     leg never runs past the intercept and doubles back when off-route cells
        //     sit beyond where the route exits the weather.
        fun hugPath(offset: Double, minLead: Double): List<Coordinate> {
            val sideBearing = course + (if (offset >= 0) 90 else -90)
            val margin = config.lateralBufferNM
            val rejoinAlong = project(rejoinCoord, position, course).along
            val nearAlong = max(max(0.0, lineNear - margin), minLead)
            val farAlong = max(nearAlong, min(lineFar + margin, rejoinAlong))
            val nearOn = Geo.destination(position, course, nearAlong)
            val farOn = Geo.destination(position, course, farAlong)
            val pNear = Geo.destination(nearOn, sideBearing, abs(offset))
            val pFar = Geo.destination(farOn, sideBearing, abs(offset))
            return listOf(position, pNear, pFar, rejoinCoord)
        }

        // Along-course distance to reach a lateral offset at the target initial-turn
        // angle — the lead that keeps the first leg a realistic deviation, not 90°.
        fun gentleLead(offset: Double): Double =
            abs(offset) / tan(config.initialDeviationTurnDegrees * PI / 180)

        // A variable-offset hug that follows the **outboard silhouette** of the clustered
        // line on one side (+1 right / −1 left): the convex upper hull of every cell's
        // projected corners in the (along, cross) frame, offset outboard by the berth.
        // Unlike the fixed-offset hugs, this traces a staggered / complex edge with **as
        // many legs as the shape needs** — turn out to the near offset just before the
        // weather, follow the edge in/out, then rejoin — so a line whose near cells sit
        // close to course and far cells bulge wide is hugged tightly instead of paralleled
        // at the single widest offset. Being convex it never zig-zags inboard, so it always
        // stays outboard of every (convex) cell; `pathIsClear` still validates it, and the
        // shortest-clear selector adopts it only when it beats the fixed-offset hugs.
        fun hullHugPath(side: Double): OffsetPath? {
            val rejoinAlong = project(rejoinCoord, position, course).along
            val margin = config.lateralBufferNM
            var berth = config.pathClearanceNM
            val pts = mutableListOf<HullPoint>() // x = along, y = outboard cross on `side`
            for (cell in lineSet) {
                val poly = cell.polygon ?: continue
                berth = max(berth, berthNM(cell.hazard.intensity))
                for (v in poly) {
                    val s = project(v, position, course)
                    if (s.along <= 0 || s.along > rejoinAlong) continue
                    pts.add(HullPoint(s.along, side * s.cross))
                }
            }
            if (pts.size < 2) return null
            val hull = upperHull(pts)
            val first = hull.firstOrNull() ?: return null
            fun offsetFor(y: Double): Double = max(config.minParallelOffsetNM, y + berth)
            fun point(along: Double, offset: Double): Coordinate {
                val onC = Geo.destination(position, course, max(0.0, along))
                return Geo.destination(onC, course + side * 90, offset)
            }
            val path = mutableListOf(position)
            var maxOff = offsetFor(first.y)
            // Reach the near offset just *before* the first hull vertex so the turn-out
            // doesn't clip the near cell, then trace each hull vertex.
            path.add(point(max(0.0, first.x - margin), maxOff))
            for (h in hull) {
                val off = offsetFor(h.y)
                maxOff = max(maxOff, off)
                path.add(point(h.x, off))
            }
            path.add(rejoinCoord)
            return OffsetPath(path, side * maxOff)
        }

        // A **multi-leg** route around the ENTIRE storm on one side (+1 right / −1 left),
        // for when the storm wraps the route through several turns and no fixed-offset or
        // single-jog hug can thread it (the "no line drawn — can't resolve" case). It traces
        // the outboard convex silhouette of **every** cell within reach on that side — the
        // upper hull of all their corners, offset out by the berth — so it rounds the whole
        // system with as many legs as the shape needs (turn out ~30°, follow the edge in and
        // out, turn back ~30° past the far side), then rejoins the route past all of it.
        // Being the convex upper envelope it never cuts inboard, so it stays clear of every
        // (box) cell it encloses; `pathIsClear` still validates the whole thing. Unlike
        // `hullHugPath` it is not limited to the clustered `lineSet` or the fixed rejoin, so
        // it can route over the top of a storm that sits across the route's bends.
        fun wideHullHug(side: Double): List<Coordinate>? {
            val margin = config.lateralBufferNM
            var berth = config.pathClearanceNM
            val pts = mutableListOf<HullPoint>() // x = along, y = outboard cross on `side`
            for (cell in cellBerths) {
                var onSide = false
                for (v in cell.polygon) {
                    val s = project(v, position, course)
                    if (s.along <= 0) continue
                    if (capAlong != null && s.along > capAlong) continue
                    // Keep corners on this side or straddling the route; a cell wholly on the
                    // far side doesn't shape this side's route (and `pathIsClear` still guards).
                    if (side * s.cross <= -(config.corridorHalfWidthExtremeNM + margin)) continue
                    pts.add(HullPoint(s.along, side * s.cross))
                    onSide = true
                }
                if (onSide) berth = max(berth, cell.clearance)
            }
            if (pts.size < 2) return null
            val hull = upperHull(pts)
            val first = hull.firstOrNull() ?: return null
            val last = hull.lastOrNull() ?: return null
            fun offsetFor(y: Double): Double = max(config.minParallelOffsetNM, y + berth)
            fun point(along: Double, offset: Double): Coordinate {
                val onC = Geo.destination(position, course, max(0.0, along))
                return Geo.destination(onC, course + side * 90, offset)
            }
            val path = mutableListOf(position)
            // Turn out to the first (nearest) hull vertex's offset a ~30° lead before it.
            val firstOff = offsetFor(first.y)
            path.add(point(max(0.0, first.x - turnOutLead(side * firstOff)), firstOff))
            for (h in hull) path.add(point(h.x, offsetFor(h.y)))
            // Turn back onto the route a ~30° lead past the last (farthest) hull vertex, capped.
            val lastOff = offsetFor(last.y)
            var backAlong = last.x + turnOutLead(side * lastOff)
            if (capAlong != null) backAlong = min(backAlong, capAlong)
            path.add(Geo.destination(position, course, max(last.x + 1, backAlong)))
            return path
        }

        // The finally-drawn geometry of a candidate: capped to the rejoin limit (never
        // past the destination / approach) and bounded to the max off-course turn (never
        // reversing the aircraft). Validation and selection run on THIS — the line
        // actually drawn — so the clearance guard can't be defeated by a cap or bound
        // that bends an already-validated candidate back into a cell.
        fun finalize(path: List<Coordinate>): List<Coordinate> =
            boundedToCourse(clampPathToAlong(path, capAlong, position, course), course)

        // Widen a cleared parallel-hug offset out to at least `minParallelOffsetNM` from the
        // flight path when the wider leg still clears `cells`; otherwise keep the tight
        // offset. A real weather deviation turns well off course and parallels the weather
        // with a wide berth, so a hug that clears only a few miles off the route is opened up
        // to the minimum separation the user asked for — unless doing so would re-enter
        // weather (threading a genuine gap between cells, or boxed in), where the minimum
        // simply cannot be held and the tightest clearing offset is kept.
        fun atLeastMinOffset(tightTarget: Double, gentle: Boolean, cells: List<CellBerth>): OffsetPath {
            val tightPath = finalize(hugPath(tightTarget, if (gentle) gentleLead(tightTarget) else 0.0))
            if (abs(tightTarget) >= config.minParallelOffsetNM) return OffsetPath(tightPath, tightTarget)
            val sign = if (tightTarget < 0) -1.0 else 1.0
            // The offset to try, widest first: the full minimum separation, and — when even
            // that can't be held — at least clear of the excursion floor. The second step is
            // what saves a hug that clears at a couple of miles off course, which happens
            // wherever the route merely *skirts* a line: tight enough to be the shortest
            // clear path, too tight to be drawn as a deviation at all, so without it the
            // maneuver either vanished from the map or jumped to a needless detour down the
            // far side. A hug already clear of the floor is left where it is.
            val steps = mutableListOf(config.minParallelOffsetNM)
            if (abs(tightTarget) < config.minRouteExcursionNM) steps.add(config.minRouteExcursionNM * 1.5)
            for (widened in steps) {
                if (abs(tightTarget) >= widened) continue
                val target = sign * widened
                val path = finalize(hugPath(target, if (gentle) gentleLead(target) else 0.0))
                if (pathIsClear(path, cells, position)) return OffsetPath(path, target)
            }
            return OffsetPath(tightPath, tightTarget)
        }

        // The tightest parallel-offset hug on one side (+1 right / −1 left) that stays
        // clear of `cells`: the smallest offset (out to `maxOffset`) whose whole drawn
        // path clears them. Mirrors the real weather-deviation maneuver — turn out just
        // enough to parallel the weather's edge, hold the offset, then rejoin when clear.
        // Being the *minimum* clearing offset it stays close to the flight plan, so the
        // shortest-path selector picks it early rather than diving wide. `gentle` bounds
        // the initial turn; a steep variant is offered for when the gentle start would
        // clip weather sitting right at the aircraft.
        fun tightHug(side: Double, gentle: Boolean, cells: List<CellBerth>, maxOffset: Double): OffsetPath? {
            var offset = config.lateralBufferNM
            while (offset <= maxOffset) {
                val target = side * offset
                val path = finalize(hugPath(target, if (gentle) gentleLead(target) else 0.0))
                if (pathIsClear(path, cells, position)) {
                    // Found the tightest clearing offset — widen it out to at least the
                    // minimum lateral separation from the flight path when the wider leg
                    // still clears (a real deviation parallels the weather with a wide
                    // berth). A gap-thread / boxed-in case, where widening re-enters
                    // weather, keeps the tight offset.
                    return atLeastMinOffset(target, gentle, cells)
                }
                offset += 2
            }
            return null
        }

        // Routine-width candidates (offset bounded to `searchHalfWidthNM`): the gap /
        // around-the-end doglegs, the capped side-edge hugs, and the tightest clearing
        // hug on each side. Each is finalized up front so it is validated and ranked as
        // the line actually drawn. Offsets beyond the bound are dropped here, so a broad
        // line can never emit a runaway around-the-end candidate that loops far out.
        // Each candidate carries a `parallel` flag: true for the 3-leg / 4-point side-hug
        // (turn out ~30°, parallel the weather, turn ~30° back) and false for the 2-leg /
        // 3-point single-apex triangle. The selector prefers a clear parallel hug over a
        // clear triangle, so the mint line parallels the weather instead of cutting one
        // wide turn around it.
        val candidates = mutableListOf<Candidate>()
        for (t in solution.targets) {
            if (abs(t.center) > config.searchHalfWidthNM) continue
            candidates.add(Candidate(finalize(apexPath(t.center)), t.center, false))
            // A target within the excursion floor threads straight down the flight path — a
            // mint line drawn on top of the magenta one. That happens to a gap straddling the
            // course, and to the outboard edge of a line the route merely *skirts*: the
            // padded edge sits a mile or two off course, so the least-deviation candidate is
            // a jog that deviates nowhere. Offer the same thread slid clear of the floor as
            // well, and let the selection tiers judge both — the slid one is preferred
            // wherever it clears (`leavesRoute`), and the centered one is still available as
            // the last clear resort. Offering it (rather than substituting it only when it
            // clears every cell, as before) is what keeps a drawable thread on the table when
            // the slid line is merely clear of the intense cores — scattered lighter precip
            // used to delete it outright, leaving nothing drawable at all.
            val nudged = nudgedOffRoute(t)
            if (nudged != null) {
                candidates.add(Candidate(finalize(apexPath(nudged)), nudged, false))
            }
        }
        for (edge in listOf(solution.leftEdge, solution.rightEdge)) {
            if (abs(edge) > config.searchHalfWidthNM) continue
            val hug = atLeastMinOffset(edge, gentle = false, cells = cellBerths)
            candidates.add(Candidate(hug.path, hug.target, true))
        }
        for (gentle in listOf(true, false)) {
            for (side in listOf(1.0, -1.0)) {
                // The tightest hug that clears EVERY cell (the ideal, fully-clear option).
                tightHug(side, gentle, cellBerths, config.searchHalfWidthNM)?.let {
                    candidates.add(Candidate(it.path, it.target, true))
                }
                // The tightest hug that clears the INTENSE cores but may skirt lighter
                // precip — the close-in option that keeps the line tight to a broad area
                // of moderate returns instead of forcing a wide detour around all of it.
                tightHug(side, gentle, intenseBerths, config.searchHalfWidthNM)?.let {
                    candidates.add(Candidate(it.path, it.target, true))
                }
            }
        }
        // Variable-offset edge-following hugs (the multi-leg path for staggered / complex
        // shapes). Added on top of the fixed-offset hugs; the shortest-clear selector picks
        // whichever is shorter, so this only wins where following the edge genuinely beats
        // paralleling at the widest offset.
        for (side in listOf(1.0, -1.0)) {
            val hull = hullHugPath(side)
            if (hull != null && abs(hull.target) <= config.searchHalfWidthNM) {
                candidates.add(Candidate(finalize(hull.path), hull.target, true))
            }
        }

        // The shortest of a candidate set (by total drawn length), or null when empty.
        fun shortest(set: List<Candidate>): Candidate? =
            set.minWithOrNull(compareBy { pathLengthNM(it.path) })
        // The shortest clear reroute, **preferring the 3-leg parallel hug over the
        // single-apex triangle**: take the shortest parallel hug when one is present, and
        // only fall back to the shortest triangle when no hug is (e.g. a gap-threading
        // dogleg that no straight parallel offset fits). This is what makes the drawn line
        // parallel the weather rather than cut one wide turn around it — even when a
        // triangle to the shared rejoin would be a shade shorter.
        fun shortestPreferringParallel(set: List<Candidate>): Candidate? =
            shortest(set.filter { it.parallel }) ?: shortest(set)

        // Last resort: the same candidate shapes searched **wide** — out to
        // `maxDetourOffsetNM` instead of the routine `searchHalfWidthNM` bound. Built only
        // when nothing routine-width clears, so the tight line is always preferred; when
        // the storm is simply broader than the routine bound this is what keeps a reroute
        // available instead of collapsing to the degenerate on-route fallback. It is the
        // routine set widened, not a different set: the gap / around-the-end doglegs and
        // side-edge hugs that were dropped for sitting beyond the bound, the tightest
        // clearing hug on each side searched all the way out, and the edge-following hulls
        // (single-system and whole-system) at whatever offset their shape needs.
        fun wideCandidates(): List<Candidate> {
            val wide = mutableListOf<Candidate>()
            // Beyond the routine bound but within the wide one — the offsets the routine
            // pass dropped for being too far off course.
            fun isWide(offset: Double): Boolean =
                abs(offset) > config.searchHalfWidthNM && abs(offset) <= config.maxDetourOffsetNM

            for (t in solution.targets) {
                if (!isWide(t.center)) continue
                wide.add(Candidate(finalize(apexPath(t.center)), t.center, false))
            }
            for (edge in listOf(solution.leftEdge, solution.rightEdge)) {
                if (!isWide(edge)) continue
                val hug = atLeastMinOffset(edge, gentle = false, cells = cellBerths)
                wide.add(Candidate(hug.path, hug.target, true))
            }
            // The tightest clearing hug on each side, searched out to the wide bound —
            // against every cell (the ideal) and against the intense cores alone (skirting
            // lighter precip), mirroring the routine tier's two options.
            for (gentle in listOf(true, false)) {
                for (side in listOf(1.0, -1.0)) {
                    for (cells in listOf(cellBerths, intenseBerths)) {
                        tightHug(side, gentle, cells, config.maxDetourOffsetNM)?.let {
                            wide.add(Candidate(it.path, it.target, true))
                        }
                    }
                }
            }
            for (side in listOf(1.0, -1.0)) {
                val hull = hullHugPath(side)
                if (hull != null && abs(hull.target) <= config.maxDetourOffsetNM) {
                    wide.add(Candidate(finalize(hull.path), hull.target, true))
                }
                // The whole-system silhouette — not tied to the clustered line or the fixed
                // rejoin, so it can round a storm that wraps the route through several turns.
                val multi = wideHullHug(side)
                if (multi != null && multi.size >= 2) {
                    val path = finalize(multi)
                    val offset = path.maxOfOrNull { abs(project(it, position, course).cross) } ?: 0.0
                    if (offset <= config.maxDetourOffsetNM) {
                        wide.add(Candidate(path, side * offset, true))
                    }
                }
            }
            return wide
        }

        // A candidate that goes somewhere. One steered to within the excursion floor of the
        // course — a gap centered on the route whose slot was too tight to slide out of — is
        // drawn on top of the filed route and therefore suppressed, so preferring one that
        // leaves the route is what turns "no lateral deviation available" into a line the
        // pilot can fly. It is a *preference*, not a filter: an on-route thread is still
        // taken below if nothing else, tight or wide, clears at all.
        fun leavesRoute(c: Candidate): Boolean = abs(c.target) >= config.minRouteExcursionNM

        // Choose the reroute the pilot actually flies, in priority order that keeps it
        // tight to the storm, prefers a parallel hug over a single-turn triangle, and only
        // ever swings wide as an absolute last resort:
        //   1. the shortest routine-width path clear of EVERY cell — a parallel hug when
        //      one clears, else a triangle;
        //   2. else the shortest routine-width path that clears the intense (heavy /
        //      extreme) cores — again preferring the parallel hug — skirting lighter
        //      precip to stay close, not looping;
        //   3. else, last resort, widen the search to `maxDetourOffsetNM` and take the
        //      shortest *wide* path clear of every cell, then the shortest wide path that
        //      clears the intense cores — the same two-step relaxation as the routine tier,
        //      so a storm broader than the routine bound still yields a reroute instead of
        //      failing outright the moment nothing wide is clear of every last light cell;
        //   4. else a clear routine path that doesn't leave the route (an on-route thread,
        //      drawn nowhere but still the closest thing to a solution);
        //   5. else (genuinely boxed in) the path — routine or wide — that keeps the most
        //      room from the intense cores, never the straight-through least-deviation one.
        // Tiers 1–3 additionally require the path to *go somewhere* (`leavesRoute`), so a
        // clear-but-undrawable line never pre-empts a wider one that can actually be flown.
        // Only a total failure to build any candidate at all falls through to the
        // degenerate zero-offset line, which `pathLeavesRoute` then declines to draw.
        val clearAll = candidates.filter { pathIsClear(it.path, cellBerths, position) }
        val clearIntense = candidates.filter { pathIsClear(it.path, intenseBerths, position) }
        var picked = shortestPreferringParallel(clearAll.filter { leavesRoute(it) })
            ?: shortestPreferringParallel(clearIntense.filter { leavesRoute(it) })
        if (picked == null) {
            // Nothing routine-width both clears and goes anywhere — widen the search rather
            // than give up, and only then settle for a clear-but-undrawable tight thread.
            val wide = wideCandidates()
            val wideAll = wide.filter { pathIsClear(it.path, cellBerths, position) }
            val wideIntense = wide.filter { pathIsClear(it.path, intenseBerths, position) }
            picked = shortestPreferringParallel(wideAll.filter { leavesRoute(it) })
                ?: shortestPreferringParallel(wideIntense.filter { leavesRoute(it) })
                ?: shortestPreferringParallel(clearAll)
                ?: shortestPreferringParallel(clearIntense)
                ?: (candidates + wide).maxWithOrNull(
                    compareBy { pathBerthMarginNM(it.path, intenseBerths, position) },
                )
        }
        val chosenPath: List<Coordinate>
        val chosenTarget: Double
        if (picked != null) {
            chosenPath = picked.path
            chosenTarget = picked.target
        } else {
            chosenPath = finalize(apexPath(0.0))
            chosenTarget = 0.0
        }
        val target = chosenTarget
        val direction = if (target >= 0) DeviationDirection.RIGHT else DeviationDirection.LEFT
        // The chosen path is already finalized (capped + turn-bounded) and validated for
        // clearance. Draw the maneuver starting at the turn-out point — a ~30° lead
        // before the weather — and rejoining with a ~30° turn-back, rather than a long
        // shallow drift from the aircraft. Both only reshape the lead-in / lead-out on the
        // course line ahead of / behind the (already-clear) offset legs, and both leave a
        // reroute close aboard starting at the aircraft. They are given the intense cores so
        // that when the ideal ~30° transition would clip a wide-berth (red/extreme) core they
        // pull the turn-out earlier / push the turn-back later into clear air rather than
        // collapsing back to a square 90° step. Then guarantee the minimum extent.
        var deviationPath = startAtTurnOut(chosenPath, position, course, intenseBerths)
        deviationPath = endAtTurnBack(deviationPath, position, course, capAlong, intenseBerths)
        deviationPath = enforceMinExtent(deviationPath, position, course, capAlong)
        // Safety: the reshaped lead-in / lead-out must still clear the intense cores. If a
        // smooth ~30° transition could not be fitted clear of them (weather close aboard, or
        // no room before the cap), keep the validated original path instead.
        if (!pathIsClear(deviationPath, intenseBerths, position)) {
            deviationPath = chosenPath
        }
        // Guarantee the WHOLE drawn path — the return leg included — clears every cell. When
        // the fixed rejoin left the return leg cutting through weather (nothing there was
        // fully clear), hold the offset longer and turn back farther past it, widening only
        // if needed, until the entire path is clear. A path already clear is untouched.
        deviationPath = extendedToClear(deviationPath, position, course, cellBerths, capAlong)
        // Still cutting weather? The storm wraps the route through several turns and no
        // single-jog hug can thread it. Route around the WHOLE system with a multi-leg hug
        // down each side and take the shortest that actually clears every cell — so a line
        // is drawn (over the top / around the end) instead of none at all. Bounded by
        // `maxDetourOffsetNM` like every other candidate: rounding a continent-wide area of
        // returns is not a deviation any controller would issue, and the tiered selection
        // above has already found the best line available within that bound.
        if (!pathIsClear(deviationPath, cellBerths, position)) {
            val multi = listOf(1.0, -1.0).mapNotNull { wideHullHug(it) }
                .filter { it.size >= 2 && pathIsClear(it, cellBerths, position) }
                .filter { path ->
                    path.all { abs(project(it, position, course).cross) <= config.maxDetourOffsetNM }
                }
                .minWithOrNull(compareBy { pathLengthNM(it) })
            if (multi != null) deviationPath = multi
        }
        val throughPoint = if (deviationPath.size >= 2) deviationPath[1] else chosenPath[1]

        // Speak the deviation the drawn line actually flies: the initial turn from the
        // turn-out point to the through-point, rounded to 5°, with a severity-based floor.
        val degrees = deviationDegrees(
            position = deviationPath.firstOrNull() ?: position,
            course = course,
            throughPoint = throughPoint,
            severity = primary.hazard.intensity,
        )

        val segment = originalSegment(
            waypoints = waypoints,
            position = position,
            course = course,
            nearAlong = primary.nearAlong,
            rejoin = rejoin,
        )
        val distance = primary.nearAlong
        val eta = groundspeedKnots?.let { if (it > 30) distance / it * 60 else null }
        val area = primary.polygon ?: boxAround(primary.center ?: corridorEnd)
        // The banner / advisory only fire for on-path weather that is also close enough
        // to work now; farther out, the mint line is drawn but no prompt is raised.
        val prompt = withinTacticalRange &&
            shouldPrompt(
                severity = primary.hazard.intensity,
                convective = primary.hazard.isConvectiveSigmet,
                distance = distance,
                centerRel = primary.centerRel,
            )

        return RouteWeatherConflict(
            hazard = primary.hazard,
            distanceAheadNM = distance,
            relativeBearingDegrees = primary.centerRel,
            leftClock = clockPosition(primary.leftEdgeRel),
            centerClock = clockPosition(primary.centerRel),
            rightClock = clockPosition(primary.rightEdgeRel),
            estimatedTimeMinutes = eta,
            severity = primary.hazard.intensity,
            leftBypassScore = primary.leftExtent,
            rightBypassScore = primary.rightExtent,
            recommendedDirection = direction,
            recommendedDeviationDegrees = degrees,
            rejoinFix = rejoin,
            originalSegment = segment,
            shouldPrompt = prompt,
            withinTacticalRange = withinTacticalRange,
            withinDrawRange = withinDrawRange,
            intersectionArea = area,
            deviationPath = deviationPath,
        )
    }

    // MARK: - Gap threading

    /**
     * A candidate lateral offset to steer for, with the clear interval it may slide
     * within. [center] is the offset the solution prefers (the middle of a gap between
     * two cells, or the outboard edge of the whole line); [lo]/[hi] bound how far it can
     * be moved sideways and still sit in air the flanking cells' buffers leave clear.
     * The interval is what lets a gap-thread that lands on the flight path be slid to one
     * side of its gap ([nudgedOffRoute]) instead of drawing a deviation along the route.
     */
    data class ThreadTarget(var center: Double, var lo: Double, var hi: Double)

    /** The gap-threading solution: the ranked targets plus the whole line's outboard edges. */
    private data class ThreadSolution(
        val targets: List<ThreadTarget>,
        val leftEdge: Double,
        val rightEdge: Double,
    )

    /** One offer from the gap solver: the offset to steer for and how wide its gap is. */
    private data class GapCandidate(val target: ThreadTarget, val width: Double)

    /**
     * Slide a gap-threading target off the flight path to at least `minRouteExcursionNM`,
     * or null when it is already clear of the route or its gap cannot hold the floor.
     *
     * A gap that straddles the course centers its target on ~0, so the dogleg through it
     * is drawn straight down the route — a "deviation" that deviates nowhere. Slide it to
     * whichever side of the gap has more room left over, keeping it inside the clear
     * interval so it still threads. Where the gap is too narrow to hold the floor on
     * either side (genuinely threading a tight slot), nothing is returned and the caller
     * keeps the centered thread — the honest answer, which the draw-time guard then
     * declines to paint as a reroute.
     */
    private fun nudgedOffRoute(t: ThreadTarget): Double? {
        if (abs(t.center) >= config.minRouteExcursionNM) return null
        // Just clear of the floor — half the floor again as margin, so the turn-out /
        // rejoin reshaping that follows can move a vertex a little without dropping the
        // line back under it. Deliberately no farther: rounding the end of a line whose
        // edge sits a mile off course is a small jog, not a wide detour.
        val slid = config.minRouteExcursionNM * 1.5
        val rightRoom = t.hi - slid // >= 0 when +slid still sits inside the clear gap
        val leftRoom = -slid - t.lo // >= 0 when −slid does
        if (max(rightRoom, leftRoom) < 0) return null
        if (rightRoom >= 0 && leftRoom >= 0) return if (rightRoom >= leftRoom) slid else -slid
        return if (rightRoom >= 0) slid else -slid
    }

    /**
     * The candidate lateral offsets to steer for (signed cross-track NM, +right),
     * ordered best-first, plus the line's outboard `leftEdge`/`rightEdge` used to
     * build the side-hug paths. Projects the cells onto the cross-track axis, pads
     * each by the lateral buffer, merges overlaps, and offers the interior gaps
     * between adjacent cells (wide enough to fly) plus going around either end.
     * `targets` is ordered by least deviation, then wider gap, then to the right (the
     * conventional first offer); the caller validates each candidate's full path.
     */
    private fun threadSolution(cells: List<Projection>): ThreadSolution {
        val intervals = cells.map {
            // Pad each cell by the lateral buffer — or the cell's wider berth, for a
            // red/extreme core — so the gaps and side-hug edges keep that much room.
            val buf = max(config.lateralBufferNM, berthNM(it.hazard.intensity)) + it.radiusBuffer
            Pair(it.crossLo - buf, it.crossHi + buf)
        }.sortedBy { it.first }

        val merged = mutableListOf<Pair<Double, Double>>()
        for (iv in intervals) {
            val last = merged.lastOrNull()
            if (last != null && iv.first <= last.second) {
                merged[merged.size - 1] = Pair(last.first, max(last.second, iv.second))
            } else {
                merged.add(iv)
            }
        }
        val first = merged.firstOrNull()
        val last = merged.lastOrNull()
        if (first == null || last == null) {
            return ThreadSolution(listOf(ThreadTarget(0.0, 0.0, 0.0)), 0.0, 0.0)
        }

        val candidates = mutableListOf<GapCandidate>()
        // Interior gaps between adjacent cells — only if wide enough to be flown. The gap's
        // own edges bound how far the thread may be slid off the route.
        for (i in 0 until merged.size - 1) {
            val gapLo = merged[i].second
            val gapHi = merged[i + 1].first
            val width = gapHi - gapLo
            if (width >= config.minGapWidthNM) {
                candidates.add(
                    GapCandidate(ThreadTarget(center = (gapLo + gapHi) / 2, lo = gapLo, hi = gapHi), width),
                )
            }
        }
        // Around either end of the whole line (open air outboard of the edges), so the
        // slide interval runs from the edge outboard to the search bound.
        candidates.add(
            GapCandidate(
                ThreadTarget(
                    center = first.first,
                    lo = first.first - config.searchHalfWidthNM,
                    hi = first.first,
                ),
                config.searchHalfWidthNM,
            ),
        )
        candidates.add(
            GapCandidate(
                ThreadTarget(
                    center = last.second,
                    lo = last.second,
                    hi = last.second + config.searchHalfWidthNM,
                ),
                config.searchHalfWidthNM,
            ),
        )

        val reachable = candidates.filter { abs(it.target.center) <= config.searchHalfWidthNM }
        val pool = if (reachable.isEmpty()) candidates else reachable

        val targets = pool.sortedWith { a, b ->
            val ca = a.target.center
            val cb = b.target.center
            if (abs(ca) != abs(cb)) {
                if (abs(ca) < abs(cb)) -1 else 1
            } else if (a.width != b.width) {
                if (a.width > b.width) -1 else 1
            } else if (ca >= 0 && cb < 0) {
                // Prefer the right side on an exact tie.
                -1
            } else if (cb >= 0 && ca < 0) {
                1
            } else {
                // Swift's `sorted(by:)` returns false both ways here (no preference); the
                // comparator reports "equal" so Kotlin's stable sort keeps the input order.
                0
            }
        }.map { it.target }

        return ThreadSolution(targets, first.first, last.second)
    }

    // MARK: - Path clearance

    /**
     * The clearance (NM) a reroute keeps from a cell of the given intensity: a wide
     * berth for red/extreme cores, the base margin for everything lighter. Applied to
     * both path validation and the gap/side-hug spacing so the whole reroute — not
     * just its abeam point — gives convective cores real room.
     */
    private fun berthNM(intensity: WeatherIntensity): Double =
        if (intensity == WeatherIntensity.EXTREME) config.severeBerthNM else config.pathClearanceNM

    /**
     * The corridor half-width (NM) for a cell of the given intensity — how far off the
     * course centerline the cell may sit and still count as **on the flight path**. Scaled
     * by intensity (moderate tight, heavy wider, extreme widest) so a red/orange core the
     * route skirts is flagged rather than slipping past the tight moderate corridor, while
     * moderate precip off to the side still doesn't trigger a deviation.
     */
    private fun corridorHalfWidth(intensity: WeatherIntensity): Double = when (intensity) {
        WeatherIntensity.EXTREME -> config.corridorHalfWidthExtremeNM
        WeatherIntensity.HEAVY -> config.corridorHalfWidthHeavyNM
        else -> config.corridorHalfWidthNM
    }

    /**
     * Whether a deviation path stays clear of every cell along its whole length,
     * each by that cell's own required clearance — the guard that stops a reroute
     * from threading one storm and cutting into another, and that keeps a wide berth
     * from red/extreme cores. Samples each leg and ignores the immediate vicinity of
     * [origin] (the aircraft may already be in light precipitation; we only care that
     * the path ahead stays clear).
     */
    private fun pathIsClear(path: List<Coordinate>, cells: List<CellBerth>, origin: Coordinate): Boolean {
        if (path.size < 2 || cells.isEmpty()) return true
        val startSkip = 8.0
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val steps = max(1, (Geo.distanceNM(a, b) / 4).toInt())
            for (s in 0..steps) {
                val f = s.toDouble() / steps
                val p = interpolate(a, b, f)
                if (Geo.distanceNM(origin, p) < startSkip) continue
                for (cell in cells) {
                    if (distanceToPolygonNM(p, cell.polygon) < cell.clearance) return false
                }
            }
        }
        return true
    }

    /**
     * Total great-circle length (NM) of a multi-leg path — the objective the
     * selector minimizes over the candidate reroutes that stay clear.
     */
    private fun pathLengthNM(path: List<Coordinate>): Double {
        if (path.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until path.size - 1) total += Geo.distanceNM(path[i], path[i + 1])
        return total
    }

    /**
     * How well a path respects every cell's required berth: the smallest value of
     * (distance to the cell − that cell's clearance) along the whole path, ignoring
     * the immediate vicinity of the origin. Positive means it keeps the full berth
     * everywhere; more negative means it cuts deeper past a berth. Used only as the
     * boxed-in tie-breaker: with no fully-clear candidate, fly the one that intrudes
     * least — which keeps the widest room from a red core — rather than the one that
     * happens to need the smallest turn (which can drive straight through it).
     */
    private fun pathBerthMarginNM(path: List<Coordinate>, cells: List<CellBerth>, origin: Coordinate): Double {
        if (path.size < 2 || cells.isEmpty()) return Double.MAX_VALUE
        val startSkip = 8.0
        var worst = Double.MAX_VALUE
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val steps = max(1, (Geo.distanceNM(a, b) / 4).toInt())
            for (s in 0..steps) {
                val f = s.toDouble() / steps
                val p = interpolate(a, b, f)
                if (Geo.distanceNM(origin, p) < startSkip) continue
                for (cell in cells) {
                    worst = min(worst, distanceToPolygonNM(p, cell.polygon) - cell.clearance)
                }
            }
        }
        return worst
    }

    /**
     * Whether a drawn deviation actually **engages** the weather it claims to route
     * around: some interior point comes within [maxDistanceNM] of a moderate-or-greater
     * precipitation cell. A line that stays far from every cell — a degenerate reroute
     * drawn out in clear air (e.g. one stretched past the storm toward a distant rejoin,
     * which validates as "clear" precisely because it is nowhere near the weather) — does
     * **not** engage, so callers suppress drawing it rather than show a mint line with no
     * weather anywhere near it. Ignores the immediate vicinity of the start (the aircraft
     * may sit in light precip) and only the moderate+ cells that actually drive a
     * deviation. This is the guard that catches a line that isn't surrounding any weather.
     */
    fun pathEngagesWeather(
        path: List<Coordinate>,
        hazards: List<WeatherHazard>,
        maxDistanceNM: Double = 45.0,
    ): Boolean {
        if (path.size < 2) return false
        val start = path.first()
        val polys = hazards.mapNotNull { h ->
            if (h.intensity < WeatherIntensity.MODERATE) return@mapNotNull null
            val p = h.geometry.polygonPoints
            if (p == null || p.size < 3) null else p
        }
        if (polys.isEmpty()) return false
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val steps = max(1, (Geo.distanceNM(a, b) / 5).toInt())
            for (s in 0..steps) {
                val p = interpolate(a, b, s.toDouble() / steps)
                if (Geo.distanceNM(start, p) < 5) continue
                for (poly in polys) {
                    if (distanceToPolygonNM(p, poly) <= maxDistanceNM) return true
                }
            }
        }
        return false
    }

    /**
     * Whether an already-committed deviation path still clears every current
     * precipitation cell by that cell's required berth — the test for "the reroute the
     * pilot is flying is still good." Unlike [detectConflict] (whose wide detection
     * corridor re-flags the very storm the line hugs as a fresh conflict), this asks the
     * narrower question a re-vector needs: has any moderate-or-greater cell **encroached
     * onto the committed line** within its clearance? Returns true when the path stays
     * clear (ATC can have the pilot continue on the current deviation); false when new
     * weather now sits on it (re-vector from the current position). The path's first
     * point is the origin (the current aircraft position on a re-vector); its immediate
     * vicinity is ignored, matching the construction-time validation ([pathIsClear]).
     */
    fun committedPathStillClear(path: List<Coordinate>, hazards: List<WeatherHazard>): Boolean {
        if (path.size < 2) return true
        val origin = path.first()
        val cells = hazards.mapNotNull {
            if (it.intensity < WeatherIntensity.MODERATE) return@mapNotNull null
            val poly = it.geometry.polygonPoints
            if (poly == null || poly.size < 3) null else CellBerth(poly, berthNM(it.intensity))
        }
        if (cells.isEmpty()) return true
        return pathIsClear(path, cells, origin)
    }

    /**
     * The nearest moderate-or-greater hazard that sits **on the route corridor** anywhere
     * along the given polyline (`[position] + route`), and how far along the route it
     * begins (NM). Walks the whole route — no lookahead limit — and tests each sampled
     * point against every hazard's intensity-scaled corridor half-width, exactly the
     * "on the flight path" test the deviation detector uses. Independent of whether a
     * drawable reroute was produced, so Diagnostics can report on-path weather ("moderate
     * precipitation on route, 210 NM — monitoring") even when no mint line was drawn for
     * it, instead of falsely saying "no conflict". Null when nothing qualifying lies on the
     * route. No AI, no I/O.
     */
    fun nearestRouteHazard(
        route: List<Coordinate>,
        from: Coordinate,
        hazards: List<WeatherHazard>,
    ): RouteHazard? {
        val pts = (listOf(from) + route).filter { it.isValid }
        if (pts.size < 2 || hazards.isEmpty()) return null
        var best: RouteHazard? = null
        var cumulative = 0.0
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val segLen = Geo.distanceNM(a, b)
            val steps = max(1, (segLen / 5).toInt())
            for (s in 0..steps) {
                val t = s.toDouble() / steps
                val p = interpolate(a, b, t)
                for (h in hazards) {
                    if (h.intensity < WeatherIntensity.MODERATE) continue
                    val half = corridorHalfWidth(h.intensity) + pointRadiusBuffer(h)
                    if (distanceHazardToPointNM(h, p) <= half) {
                        val along = cumulative + segLen * t
                        val current = best
                        if (current == null || along < current.distanceNM) best = RouteHazard(h, along)
                    }
                }
            }
            cumulative += segLen
        }
        return best
    }

    // MARK: - Merging adjacent deviations

    /**
     * Fold a run of short, back-to-back deviations into one continuous parallel hug.
     *
     * A complex, multi-cell system makes the whole-route walk emit a *separate* deviation
     * around each cell — turn out, parallel it, turn back to the route — and because the
     * cells are packed each turn-back lands right where the next cell begins (often inside
     * it). The map then shows a string of little in-and-out jogs, each terminating in a
     * hazard. When the rejoin of one deviation sits within `mergeAdjacentGapNM` of the next
     * one's turn-out and both hug the same side, a pilot would simply hold the offset
     * through the gap. This folds such a run into a single deviation: keep the first
     * turn-out, thread every interior (offset) vertex of the run together, drop the
     * intermediate dips back to the route, and rejoin only at the last deviation's rejoin —
     * one parallel line down the whole system, its in-between in-hazard rejoins gone. A run
     * whose connecting leg would cut an intense core, or that hugs the opposite side, is
     * left split. [conflicts] must be in along-route order (as the route walker produces
     * them); [route] is the filed polyline ahead, used to push a final rejoin that still
     * lands in a cell forward to clear air. Pure geometry — no AI, no I/O.
     */
    fun mergeAdjacentDeviations(
        conflicts: List<RouteWeatherConflict>,
        hazards: List<WeatherHazard>,
        route: List<Coordinate>,
    ): List<RouteWeatherConflict> {
        if (conflicts.size < 2) return conflicts
        // The intense cores (heavy + extreme) a merged run must never cut. These are the
        // same cells each constituent hug already clears, so reusing their offset vertices
        // stays valid — only the new connector legs between deviations need checking. Lighter
        // (moderate) precip may be skirted (as the individual hugs do), so it isn't a blocker.
        val cores = hazards.mapNotNull {
            if (it.intensity < WeatherIntensity.HEAVY) return@mapNotNull null
            val poly = it.geometry.polygonPoints
            if (poly == null || poly.size < 3) null else CellBerth(poly, berthNM(it.intensity))
        }
        val out = mutableListOf<RouteWeatherConflict>()
        var run = mutableListOf(conflicts[0])
        for (next in conflicts.drop(1)) {
            if (canExtendRun(run, next, cores)) {
                run.add(next)
            } else {
                out.add(collapseRun(run, hazards, cores, route))
                run = mutableListOf(next)
            }
        }
        out.add(collapseRun(run, hazards, cores, route))
        return out
    }

    /**
     * Whether [next] continues the parallel run [run] closely enough to fold in: both are
     * real hugs (>= 3 points), hug the same side, and the previous rejoin sits within
     * `mergeAdjacentGapNM` of [next]'s turn-out. The only *new* geometry the fold adds is
     * the connector leg that holds the offset across the gap — from the previous hug's last
     * offset vertex to the next hug's first — so that is all that is checked here: it must
     * clear every intense core. Each constituent hug's own legs were already validated
     * against all cores on construction, and [next]'s closing leg back to the route is
     * handled in [collapseRun] (slid clear if it lands in weather) — so a deviation whose
     * own rejoin sits inside the next cell still merges instead of being kept as the very
     * in-and-out jog this is meant to remove.
     */
    private fun canExtendRun(
        run: List<RouteWeatherConflict>,
        next: RouteWeatherConflict,
        cores: List<CellBerth>,
    ): Boolean {
        val prev = run.lastOrNull() ?: return false
        if (prev.deviationPath.size < 3 || next.deviationPath.size < 3) return false
        if (prev.recommendedDirection != next.recommendedDirection) return false
        val prevEnd = prev.deviationPath.last()
        val nextStart = next.deviationPath.first()
        if (!prevEnd.isValid || !nextStart.isValid) return false
        if (Geo.distanceNM(prevEnd, nextStart) > config.mergeAdjacentGapNM) return false
        // The connector runs from the previous hug's last offset vertex (second-to-last
        // point, before its rejoin) to the next hug's first offset vertex (second point,
        // after its turn-out) — the leg that keeps the aircraft offset across the gap.
        val prevOffset = prev.deviationPath.dropLast(1).lastOrNull() ?: return false
        val nextOffset = next.deviationPath.drop(1).firstOrNull() ?: return false
        if (!prevOffset.isValid || !nextOffset.isValid) return false
        return segmentClearsCores(prevOffset, nextOffset, cores)
    }

    /**
     * The parallel line through a run of deviations: the first turn-out, then every
     * deviation's interior (offset) vertices in order — the dips back to the route between
     * them dropped — closing at the last deviation's rejoin. Near-coincident vertices are
     * collapsed so the folded line has no zero-length legs.
     */
    private fun combinedCoordinates(run: List<RouteWeatherConflict>): List<Coordinate> {
        val first = run.firstOrNull() ?: return emptyList()
        if (run.size <= 1) return first.deviationPath
        val pts = mutableListOf<Coordinate>()
        first.deviationPath.firstOrNull()?.let { if (it.isValid) pts.add(it) }
        for (dev in run) {
            if (dev.deviationPath.size < 3) continue
            pts.addAll(dev.deviationPath.drop(1).dropLast(1).filter { it.isValid })
        }
        val end = run.last().deviationPath.lastOrNull()
        if (end == null || !end.isValid) return pts
        // Collapse near-coincident interior vertices, but always keep the true rejoin.
        val deduped = mutableListOf<Coordinate>()
        for (p in pts) {
            val last = deduped.lastOrNull()
            if (last == null || Geo.distanceNM(last, p) >= 1) deduped.add(p)
        }
        val last = deduped.lastOrNull()
        if (last == null || Geo.distanceNM(last, end) >= 1) {
            deduped.add(end)
        } else {
            deduped[deduped.size - 1] = end
        }
        return deduped
    }

    /**
     * Turn a run into a single conflict. A run of one is returned unchanged; a longer run
     * becomes the first deviation re-shaped to the folded parallel line, carrying the last
     * deviation's rejoin fix and the run's worst severity, with a final rejoin that still
     * terminates in a cell nudged forward along the route to clear air.
     */
    private fun collapseRun(
        run: List<RouteWeatherConflict>,
        hazards: List<WeatherHazard>,
        cores: List<CellBerth>,
        route: List<Coordinate>,
    ): RouteWeatherConflict {
        if (run.size <= 1) return run[0]
        val base = run.first()
        val merged = base.copy()
        val path = rejoinClearOfHazards(combinedCoordinates(run), hazards, cores, route)
        merged.deviationPath = path
        merged.rejoinFix = run.last().rejoinFix ?: base.rejoinFix
        merged.severity = run.maxOfOrNull { it.severity } ?: base.severity
        return merged
    }

    /**
     * If the folded line still rejoins inside (or right at the edge of) a precipitation
     * cell — the "terminates in a hazard" case a packed system produces — slide the rejoin
     * forward along the filed route to the first point clear of every cell, but only when
     * the closing leg to it also clears the intense cores. Best-effort: if no clear route
     * point ahead has a clean closing leg, the path is returned unchanged (never made
     * worse). [route] is the filed polyline ahead of the maneuver.
     */
    private fun rejoinClearOfHazards(
        path: List<Coordinate>,
        hazards: List<WeatherHazard>,
        cores: List<CellBerth>,
        route: List<Coordinate>,
    ): List<Coordinate> {
        if (path.size < 3 || route.size < 2) return path
        val end = path.last()
        if (!end.isValid) return path
        val polys = hazards.mapNotNull { h ->
            if (h.intensity < WeatherIntensity.MODERATE) return@mapNotNull null
            val p = h.geometry.polygonPoints
            if (p == null || p.size < 3) null else p
        }
        if (polys.isEmpty()) return path
        // Already clear of every cell — nothing to do.
        if (polys.all { distanceToPolygonNM(end, it) > config.pathClearanceNM }) return path
        val lastApex = path[path.size - 2]
        // Densify the route so a rejoin can land between filed fixes, then walk forward from
        // the sample nearest the current rejoin to the first one clear of every cell.
        val samples = densifiedRoute(route, 5.0)
        if (samples.isEmpty()) return path
        var startIdx = 0
        var bestD = Double.MAX_VALUE
        for ((i, s) in samples.withIndex()) {
            val d = Geo.distanceNM(s, end)
            if (d < bestD) {
                bestD = d
                startIdx = i
            }
        }
        for (i in startIdx until samples.size) {
            val cand = samples[i]
            if (!polys.all { distanceToPolygonNM(cand, it) > config.pathClearanceNM }) continue
            if (Geo.distanceNM(lastApex, cand) <= 1) continue
            if (segmentClearsCores(lastApex, cand, cores)) {
                return path.dropLast(1) + listOf(cand)
            }
        }
        return path
    }

    /**
     * Whether the straight leg `a → b` keeps every intense core's berth along its whole
     * length. Unlike [pathIsClear] this samples from the very start of the leg (no
     * origin-vicinity skip), since the closing leg it validates is short and its near end
     * still must not clip a core.
     */
    private fun segmentClearsCores(a: Coordinate, b: Coordinate, cores: List<CellBerth>): Boolean {
        if (cores.isEmpty()) return true
        val steps = max(1, (Geo.distanceNM(a, b) / 4).toInt())
        for (s in 0..steps) {
            val p = interpolate(a, b, s.toDouble() / steps)
            for (core in cores) {
                if (distanceToPolygonNM(p, core.polygon) < core.clearance) return false
            }
        }
        return true
    }

    /**
     * A polyline resampled to roughly [stepNM]-spaced points, so a rejoin can be slid to a
     * clear point between the filed fixes rather than only landing on a vertex.
     */
    private fun densifiedRoute(route: List<Coordinate>, stepNM: Double): List<Coordinate> {
        val pts = route.filter { it.isValid }
        if (pts.size < 2) return pts
        val out = mutableListOf(pts[0])
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val steps = max(1, roundHalfAwayFromZero(Geo.distanceNM(a, b) / stepNM).toInt())
            for (s in 1..steps) out.add(interpolate(a, b, s.toDouble() / steps))
        }
        return out
    }

    /**
     * A stricter engagement test than [pathEngagesWeather], for the faint **strategic
     * previews**. [pathEngagesWeather] only asks whether *some* point of the drawn line
     * comes near weather — which a "sharp angle out and back" spike still satisfies when
     * its base sits beside a cell (or merely within 45 NM of one) while its **apex bulges
     * off into clear air**. That degenerate shape is exactly what a preview draws when a
     * straight-corridor reroute is aimed across a route bend (e.g. the arrival turn toward
     * the destination) and then truncated at the bend: the kept stub juts out toward — but
     * never reaches — weather that is really further along, past the turn. The solid line
     * never shows it because it is held until the weather is within `mintLineDrawNM` (where
     * the route to it is essentially straight); the preview has no such gate.
     *
     * A genuine reroute *hugs* the weather: the point where it bulges farthest off the
     * filed route (its apex) sits right alongside the cell it is rounding, within a berth
     * of it. So require that — the apex vertex must come within [maxDistanceNM] of a
     * moderate-or-greater cell; a line whose apex is out in clear air, nowhere near any
     * precipitation, is not hugging anything and is suppressed. [maxDistanceNM] is set just
     * past the widest a real hug's apex can sit from the cell it rounds: a parallel leg now
     * holds at least `minParallelOffsetNM` (20 NM) off course, and when it hugs the *far*
     * side of a cell whose near edge lies at the corridor's outer limit
     * (`corridorHalfWidthExtremeNM`), the apex-to-cell distance approaches those summed. So
     * real hugs — even the wide, minimum-offset ones — pass, while a clear-air spike (apex
     * tens of NM beyond any cell) does not. A line that barely leaves the route (no real
     * apex) is left to [pathEngagesWeather]. [route] is the filed route the preview should
     * be hugging (`[anchor] + upcoming fixes`).
     */
    fun previewApexHugsWeather(
        path: List<Coordinate>,
        route: List<Coordinate>,
        hazards: List<WeatherHazard>,
        maxDistanceNM: Double = 40.0,
    ): Boolean {
        if (path.size < 2) return false
        val polys = hazards.mapNotNull { h ->
            if (h.intensity < WeatherIntensity.MODERATE) return@mapNotNull null
            val p = h.geometry.polygonPoints
            if (p == null || p.size < 3) null else p
        }
        if (polys.isEmpty() || route.size < 2) return false
        // The apex: the drawn vertex that bulges farthest off the filed route.
        var apex: Coordinate? = null
        var apexExcursion = 0.0
        for (v in path) {
            if (!v.isValid) continue
            val d = distanceToPolylineNM(v, route)
            if (d > apexExcursion) {
                apexExcursion = d
                apex = v
            }
        }
        // Barely leaves the route — not the out-and-back spike this guards against.
        val a = apex ?: return true
        if (apexExcursion < 5) return true
        return polys.any { distanceToPolygonNM(a, it) <= maxDistanceNM }
    }

    /**
     * How far (NM) a drawn deviation gets from the filed route at its farthest vertex —
     * the maneuver's lateral excursion, the companion to the along-track extent
     * [enforceMinExtent] guarantees. [Double.MAX_VALUE] when there is no route to
     * measure against, so an unmeasurable line is never mistaken for an on-route one.
     */
    fun routeExcursionNM(path: List<Coordinate>, route: List<Coordinate>): Double {
        val line = route.filter { it.isValid }
        val pts = path.filter { it.isValid }
        if (line.size < 2 || pts.size < 2) return Double.MAX_VALUE
        return pts.maxOfOrNull { distanceToPolylineNM(it, line) } ?: Double.MAX_VALUE
    }

    /**
     * Whether a drawn deviation leaves the filed route far enough to be worth showing:
     * its excursion reaches `minRouteExcursionNM`. This is the guard against a mint line
     * lying on top of the flight path — a reroute that recommends the course already
     * being flown. Unlike [pathEngagesWeather] (which such a line passes trivially,
     * because the route runs into the cell) and [previewApexHugsWeather] (which returns
     * true for anything that barely leaves the route, having no apex to test), this asks
     * the one question neither does: does the line go anywhere?
     */
    fun pathLeavesRoute(path: List<Coordinate>, route: List<Coordinate>): Boolean =
        routeExcursionNM(path, route) >= config.minRouteExcursionNM

    /** Distance (NM) from a point to a polyline — the nearest of its segments. */
    private fun distanceToPolylineNM(p: Coordinate, line: List<Coordinate>): Double {
        if (line.size < 2) {
            return line.firstOrNull()?.let { Geo.distanceNM(p, it) } ?: Double.MAX_VALUE
        }
        var best = Double.MAX_VALUE
        for (i in 0 until line.size - 1) best = min(best, pointToSegmentNM(p, line[i], line[i + 1]))
        return best
    }

    /**
     * The upper convex hull (the maximal-`y` envelope) of points in an (x, y) plane,
     * left to right — used to trace the outboard silhouette of a clustered weather line
     * so a hug can follow a staggered edge with as many legs as the shape needs. Standard
     * monotone chain: sort by x (ties: higher y first), then keep only right turns so the
     * kept vertices bulge upward (outboard). Internal for direct unit testing.
     */
    fun upperHull(input: List<HullPoint>): List<HullPoint> {
        val sorted = input.sortedWith { a, b ->
            if (a.x != b.x) {
                if (a.x < b.x) -1 else 1
            } else if (a.y != b.y) {
                if (a.y > b.y) -1 else 1
            } else {
                0
            }
        }
        val hull = mutableListOf<HullPoint>()
        for (p in sorted) {
            while (hull.size >= 2) {
                val a = hull[hull.size - 2]
                val b = hull[hull.size - 1]
                val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
                // Drop left turns / collinear.
                if (cross >= 0) hull.removeAt(hull.size - 1) else break
            }
            hull.add(p)
        }
        return hull
    }

    /** Distance (NM) from a point to a polygon: 0 inside, else the nearest edge. */
    private fun distanceToPolygonNM(p: Coordinate, poly: List<Coordinate>): Double {
        if (poly.size < 3) return Double.MAX_VALUE
        if (WeatherRouteAnalyzer.pointInPolygon(p, poly)) return 0.0
        var best = Double.MAX_VALUE
        var j = poly.size - 1
        for (i in poly.indices) {
            best = min(best, pointToSegmentNM(p, poly[j], poly[i]))
            j = i
        }
        return best
    }

    /** Point-to-segment distance (NM) using a local equirectangular NM plane. */
    private fun pointToSegmentNM(p: Coordinate, a: Coordinate, b: Coordinate): Double {
        val latScale = 60.0
        val lonScale = 60.0 * cos(p.latitude * PI / 180)
        val px = p.longitude * lonScale
        val py = p.latitude * latScale
        val ax = a.longitude * lonScale
        val ay = a.latitude * latScale
        val bx = b.longitude * lonScale
        val by = b.latitude * latScale
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq <= 0) 0.0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / lenSq))
        val cx = ax + t * dx
        val cy = ay + t * dy
        return hypot(px - cx, py - cy)
    }

    /**
     * The point on a polyline nearest to [p] — used to snap the drawn deviation's final
     * vertex exactly onto the filed route so the mint line always ends on the flight plan.
     */
    private fun nearestPointOnPolyline(p: Coordinate, line: List<Coordinate>): Coordinate? {
        if (line.size < 2) return line.firstOrNull()
        var best: Coordinate? = null
        var bestD = Double.MAX_VALUE
        for (i in 0 until line.size - 1) {
            val c = closestPointOnSegment(p, line[i], line[i + 1])
            val d = Geo.distanceNM(p, c)
            if (d < bestD) {
                bestD = d
                best = c
            }
        }
        return best
    }

    /**
     * Truncate a deviation path so it ends exactly where it first re-intercepts the
     * filed route — the mint line rejoins the route once and stops, never crossing it
     * and looping back to intercept a second time. The path starts on the route (at the
     * aircraft), so crossings within `departureSkipNM` of the start are ignored as the
     * shared departure; the first crossing beyond that cuts the path, keeping the
     * vertices up to that leg and ending precisely at the intercept. Returns null when
     * the path never re-crosses the route (the caller then snaps the last vertex on).
     */
    private fun truncatedAtFirstRouteIntercept(
        path: List<Coordinate>,
        route: List<Coordinate>,
    ): List<Coordinate>? {
        if (path.size < 2 || route.size < 2) return null
        val start = path.first()
        val departureSkipNM = 3.0
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            var bestPoint: Coordinate? = null
            var bestAlong = Double.MAX_VALUE
            for (r in 0 until route.size - 1) {
                val x = segmentIntersectionPoint(a, b, route[r], route[r + 1]) ?: continue
                if (Geo.distanceNM(start, x) <= departureSkipNM) continue
                // The crossing nearest this leg's start is the earliest along the path.
                val along = Geo.distanceNM(a, x)
                if (along < bestAlong) {
                    bestAlong = along
                    bestPoint = x
                }
            }
            val x = bestPoint
            if (x != null) return path.subList(0, i + 1).toList() + listOf(x)
        }
        return null
    }

    /**
     * The intersection point of two planar segments a–b and c–d (lat/lon treated as a
     * flat plane, matching the detector's other planar geometry), or null when they do
     * not cross within both segments (including the parallel / collinear case).
     */
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

    /**
     * The closest point on segment a→b to [p], in the same local NM plane as
     * [pointToSegmentNM] (which returns only the distance).
     */
    private fun closestPointOnSegment(p: Coordinate, a: Coordinate, b: Coordinate): Coordinate {
        val latScale = 60.0
        val lonScale = 60.0 * cos(p.latitude * PI / 180)
        val ax = a.longitude * lonScale
        val ay = a.latitude * latScale
        val bx = b.longitude * lonScale
        val by = b.latitude * latScale
        val px = p.longitude * lonScale
        val py = p.latitude * latScale
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq <= 0) 0.0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / lenSq))
        val cx = ax + t * dx
        val cy = ay + t * dy
        return Coordinate(cy / latScale, cx / lonScale)
    }

    // MARK: - Turn-out / turn-back shaping

    /**
     * The along-course lead for a turn of `initialDeviationTurnDegrees` (capped by the
     * max off-course bound) to reach a lateral [offset] — how far ahead the turn must
     * begin so the leg onto the offset is that angle rather than a shallow drift.
     */
    private fun turnOutLead(offset: Double): Double {
        val angle = min(config.initialDeviationTurnDegrees, config.maxDeviationTurnDegrees)
        return abs(offset) / tan(max(1.0, angle) * PI / 180)
    }

    /**
     * Start the drawn maneuver at its **turn-out point** — a lead-in just before the
     * weather that makes the first leg a ~`initialDeviationTurnDegrees`° turn onto the
     * offset — instead of a long shallow drift all the way from the aircraft. For
     * weather drawn far ahead this moves the mint line's start up to just before the
     * weather; for weather close aboard the lead collapses and the line still starts at
     * the aircraft (a necessarily steeper turn).
     *
     * The turn-out completes where the parallel leg already begins (`path[1]`) — the offset
     * reached right at the weather's near edge. But a wide-berth (red/extreme) core needs
     * more room than the tight lateral buffer that vertex sits at, so the ~30° diagonal onto
     * the offset can clip the core a few miles before the near edge. When it would, reach the
     * offset **earlier** — slide the turn-out (and the parallel-leg start) back along course
     * into clear air ahead of the weather, holding the same ~30° angle and extending the
     * parallel leg back to meet it — until the turn-out leg is clear. Stop once the turn-out
     * would begin behind the aircraft: the weather is then close aboard and a steeper turn is
     * unavoidable (the "pilot turned late" exception), so keep the original. A triangle's
     * single apex can't be slid without changing the detour, so it only gets the lead-in.
     */
    private fun startAtTurnOut(
        path: List<Coordinate>,
        position: Coordinate,
        course: Double,
        cores: List<CellBerth> = emptyList(),
    ): List<Coordinate> {
        if (path.size < 2) return path
        val first = path.first()
        val s1 = project(path[1], position, course)
        if (abs(s1.cross) <= 0.5) return path // no offset to turn onto
        val lead = turnOutLead(s1.cross)
        val curStart = project(first, position, course).along
        val sideBearing = course + (if (s1.cross >= 0) 90 else -90)
        // A hug's parallel leg can start earlier (the leg from the moved near-point to the far
        // point stays on the offset); a triangle's apex can't move without reshaping the detour.
        val canSlideNear = path.size >= 4
        var offsetAlong = s1.along
        while (true) {
            val startAlong = offsetAlong - lead
            // Only pull the start forward (ahead of the aircraft); keep it at the aircraft
            // when the weather is close enough that a 30° turn-out would begin behind it.
            if (startAlong <= max(2.0, curStart + 2)) return path
            val v0 = Geo.destination(position, course, startAlong)
            val nearOn = Geo.destination(position, course, offsetAlong)
            val pNear = if (canSlideNear) {
                Geo.destination(nearOn, sideBearing, abs(s1.cross))
            } else {
                path[1]
            }
            if (pathIsClear(listOf(v0, pNear), cores, position)) {
                return if (canSlideNear) {
                    listOf(v0, pNear) + path.drop(2)
                } else {
                    listOf(v0) + path.drop(1)
                }
            }
            if (!canSlideNear) return path // apex can't slide — keep the original
            offsetAlong -= 5 // reach the offset earlier, in clear air before the weather
        }
    }

    /**
     * Rejoin with a ~`initialDeviationTurnDegrees`° **turn-back** — symmetric with the
     * turn-out, so the closing leg is neither a long shallow intercept nor a ~90°
     * sideways jog back onto the route. Applied only when the rejoin sits essentially on
     * the course line (a straight route); a bent-route rejoin is left to the
     * route-intercept truncation. Only the rejoin (last) vertex is moved, so the turn-out
     * and parallel legs are untouched:
     *  • **Too shallow** → pull the rejoin *back* so the closing leg steepens to the
     *    target angle (only shortens the maneuver).
     *  • **Too steep** (the ~90° squeeze) → push the rejoin *forward*, a matching lead
     *    beyond the parallel-leg end, so the closing leg has the along-distance to
     *    intercept at the target angle — bounded by [capAlong] (never past the
     *    destination / approach). Where the cap leaves no room the step stays steep; a
     *    downstream system in the way is the packed-systems case, handled by extending
     *    the parallel leg past it.
     */
    private fun endAtTurnBack(
        path: List<Coordinate>,
        position: Coordinate,
        course: Double,
        capAlong: Double?,
        cores: List<CellBerth> = emptyList(),
    ): List<Coordinate> {
        if (path.size < 3) return path
        val last = path.last()
        val sLast = project(last, position, course)
        if (abs(sLast.cross) >= 3) return path // bent-route rejoin — leave to truncation
        val sFar = project(path[path.size - 2], position, course)
        if (abs(sFar.cross) <= 0.5) return path
        val lead = turnOutLead(sFar.cross)
        val closingAlong = sLast.along - sFar.along
        fun rejoinAt(along: Double): Coordinate = Geo.destination(position, course, along)
        // Whether the closing leg from the parallel-leg end to a rejoin at `along` stays clear
        // of the intense cores. Pushing the rejoin further out only shallows the leg and holds
        // the offset longer, so it moves the turn-back into clear air past the weather.
        fun closingClears(along: Double): Boolean =
            pathIsClear(listOf(path[path.size - 2], rejoinAt(along)), cores, position)

        if (closingAlong > lead + 2) {
            // Too shallow → steepen toward the target by pulling the rejoin back, but never so
            // far it re-enters the weather (keep the closing leg clear of the cores).
            val along = sFar.along + lead
            if (!closingClears(along)) return path
            return path.dropLast(1) + listOf(rejoinAt(along))
        }
        if (closingAlong < lead - 2) {
            // Too steep → give the closing leg room by rejoining a matching lead beyond the
            // parallel-leg end, pushing it further out if a wide-berth core would still be
            // clipped, all bounded by the rejoin cap (never past the destination / approach).
            var along = sFar.along + lead
            if (capAlong != null) along = min(along, capAlong)
            var steps = 0
            while (!closingClears(along) && steps < 40) {
                along += 5
                steps += 1
                // No room within the cap — forced steep.
                if (capAlong != null && along > capAlong) return path
            }
            if (along <= sLast.along + 2) return path // cap leaves no room
            return path.dropLast(1) + listOf(rejoinAt(along))
        }
        return path
    }

    /**
     * Guarantee the drawn maneuver spans at least `minDeviationExtentNM` from start to
     * end. In practice construction already exceeds it (the rejoin sits well past the
     * turn-out), but a compact cell can fall short — then the rejoin end is pushed
     * forward along course (within the cap), which only lengthens the final leg.
     */
    private fun enforceMinExtent(
        path: List<Coordinate>,
        position: Coordinate,
        course: Double,
        capAlong: Double?,
    ): List<Coordinate> {
        if (path.size < 2) return path
        val a0 = project(path.first(), position, course).along
        val sLast = project(path.last(), position, course)
        if (abs(sLast.cross) >= 3 || sLast.along - a0 >= config.minDeviationExtentNM) return path
        var target = a0 + config.minDeviationExtentNM
        if (capAlong != null) target = min(target, capAlong)
        if (target <= sLast.along) return path
        val v = Geo.destination(position, course, target)
        return path.dropLast(1) + listOf(v)
    }

    /**
     * Deterministically make the **whole** drawn hug — the return leg included — clear
     * every cell by its berth. The candidate search rejoins at a fixed point just past the
     * weather, so when the route re-enters weather there (or off to the side of the return
     * leg) nothing at that rejoin is fully clear and the selector falls back to a path that
     * cuts through it. This repair fixes that the way a pilot would: hold the offset
     * *longer* — extend the parallel leg and turn back later, past the weather the return
     * would otherwise cross — and, only if holding longer still can't clear it, step the
     * offset *wider*, re-checking the entire path each time. It is a pure try → check →
     * adjust → re-check loop, bounded by the rejoin cap and `maxDetourOffsetNM`.
     *
     * Applies to the 4-point parallel hug (`[turn-out, parallel-in, parallel-out, rejoin]`)
     * — the shape whose return leg can strand in weather; a triangle/degenerate path is
     * left as-is. A path that already clears every cell is returned unchanged, so a good
     * reroute is never disturbed. Best-effort: if nothing within the bounds clears, the
     * original (least-bad) path is kept rather than looping forever.
     */
    private fun extendedToClear(
        path: List<Coordinate>,
        position: Coordinate,
        course: Double,
        cells: List<CellBerth>,
        capAlong: Double?,
    ): List<Coordinate> {
        if (path.size != 4 || cells.isEmpty()) return path
        if (pathIsClear(path, cells, position)) return path
        val sOut = project(path[1], position, course) // turn-out onto the offset
        val sPar = project(path[2], position, course) // parallel-leg far end
        if (abs(sPar.cross) <= 0.5) return path
        val side = if (sPar.cross >= 0) 1.0 else -1.0
        val sideBearing = course + (if (side >= 0) 90 else -90)
        val nearAlong = max(0.0, sOut.along) // reach the offset by the near edge
        var offset = max(config.minParallelOffsetNM, max(abs(sOut.cross), abs(sPar.cross)))
        while (offset <= config.maxDetourOffsetNM) {
            // A ~30° turn-out onto (and off) the offset, re-established for each width so a
            // wider hug starts its turn earlier instead of jutting sideways.
            val lead = turnOutLead(side * offset)
            val startAlong = max(0.0, nearAlong - lead)
            val v0 = Geo.destination(position, course, startAlong)
            val nearOn = Geo.destination(position, course, nearAlong)
            val pNear = Geo.destination(nearOn, sideBearing, offset)
            var farAlong = max(nearAlong + 1, sPar.along)
            var steps = 0
            while (steps < 80) {
                steps += 1
                if (capAlong != null) farAlong = min(farAlong, capAlong) // never past the cap
                val farOn = Geo.destination(position, course, farAlong)
                val pFar = Geo.destination(farOn, sideBearing, offset)
                var rejoinAlong = farAlong + lead
                if (capAlong != null) rejoinAlong = min(rejoinAlong, capAlong)
                val rejoin = Geo.destination(position, course, rejoinAlong)
                val candidate = listOf(v0, pNear, pFar, rejoin)
                if (pathIsClear(candidate, cells, position)) return candidate
                if (capAlong != null && farAlong >= capAlong) break // no room to hold longer
                farAlong += 8 // hold the offset longer; turn back farther past the weather
            }
            offset += 5 // widen and try again
        }
        return path // nothing within the bounds cleared — keep the best-effort original
    }

    /**
     * The intense cores (heavy + extreme returns) a reroute must **always** clear, each
     * paired with the wide berth its intensity demands. Lighter (moderate) precipitation is
     * deliberately excluded — it may be skirted to keep the line tight to the storm — so this
     * is the cell set used to validate turn-out / turn-back reshaping (which may trim the
     * parallel leg) without collapsing a tight hug into a wide detour.
     */
    private fun intenseCoreBerths(hazards: List<WeatherHazard>): List<CellBerth> =
        hazards.mapNotNull {
            if (it.intensity < WeatherIntensity.HEAVY) return@mapNotNull null
            val poly = it.geometry.polygonPoints
            if (poly == null || poly.size < 3) null else CellBerth(poly, berthNM(it.intensity))
        }

    /**
     * Reshape the opening (turn-out) and closing (turn-back) legs of a drawn hug so each
     * meets the flight path at no more than a gentle intercept angle — never the ~90° sideways
     * jog or backwards intercept that route-intercept truncation, on-route snapping, or a
     * tight rejoin cap can leave once the earlier per-candidate shaping has run. Only the two
     * end legs are touched, by sliding the single adjacent interior vertex (the parallel-leg
     * end) along the course so the leg intercepts at ~`initialDeviationTurnDegrees`: the far
     * vertex is pulled *back* for the closing leg, the near vertex pushed *forward* for the
     * opening leg. Each reshape is kept only when the whole path still clears the intense
     * cores, so a valid reroute is never bent into a convective core (best-effort — otherwise
     * the original leg stands; near weather packed against the rejoin cap there is genuinely
     * no room and the steep leg is left). Applies to parallel hugs (>= 4 points); a triangle /
     * gap-thread dogleg, whose single apex can't move without changing the detour, is left
     * as-is. Pure geometry. Internal for direct unit testing.
     */
    fun gentleInterceptAngles(
        path: List<Coordinate>,
        position: Coordinate,
        course: Double,
        cores: List<CellBerth>,
    ): List<Coordinate> {
        if (path.size < 4) return path
        // Reshape only a clearly-steep intercept — a normal ~30–45° deviation turn is left be.
        val maxIntercept = config.initialDeviationTurnDegrees + 20 // ~50°
        var result = path

        fun angleOffCourse(a: Coordinate, b: Coordinate): Double =
            abs(normalizedSigned(Geo.bearing(a, b) - course))

        fun offsetPoint(along: Double, cross: Double): Coordinate {
            val onC = Geo.destination(position, course, max(0.0, along))
            return Geo.destination(onC, course + (if (cross >= 0) 90 else -90), abs(cross))
        }

        // Closing leg: keep the intercept R fixed; slide the parallel-far vertex B back along
        // course so B→R intercepts at ~the target angle (holds the offset, then a gentle turn).
        val n = result.size
        val r = result[n - 1]
        val b = result[n - 2]
        val sR = project(r, position, course)
        val sB = project(b, position, course)
        if (angleOffCourse(b, r) > maxIntercept && abs(sB.cross) > 0.5 && sR.along > 2) {
            val lead = turnOutLead(sB.cross)
            val newAlong = sR.along - lead
            val prevAlong = project(result[n - 3], position, course).along
            if (newAlong > prevAlong + 1 && newAlong < sB.along) {
                val candidate = result.toMutableList()
                candidate[n - 2] = offsetPoint(newAlong, sB.cross)
                if (pathIsClear(candidate, cores, position)) result = candidate
            }
        }

        // Opening leg: keep the turn-out A fixed; slide the parallel-near vertex C forward along
        // course so A→C leaves the route at ~the target angle instead of a square step-out.
        // Skipped when the turn-out sits essentially at the aircraft (along ≈ 0): that is the
        // "pilot turned late" close-aboard case where a steep step-out is genuinely unavoidable,
        // and pushing the offset later would only drag the entry leg through the near weather.
        val a = result[0]
        val c = result[1]
        val sA = project(a, position, course)
        val sC = project(c, position, course)
        if (angleOffCourse(a, c) > maxIntercept && abs(sC.cross) > 0.5 && sA.along > 5) {
            val lead = turnOutLead(sC.cross)
            val newAlong = sA.along + lead
            val nextAlong = project(result[2], position, course).along
            if (newAlong < nextAlong - 1 && newAlong > sC.along) {
                val candidate = result.toMutableList()
                candidate[1] = offsetPoint(newAlong, sC.cross)
                if (pathIsClear(candidate, cores, position)) result = candidate
            }
        }
        return result
    }

    /**
     * The spoken deviation amount: the initial turn from the turn-out point to the
     * through-point, rounded to 5° and clamped, with a severity-based floor so heavier
     * precipitation is never given a token offset.
     */
    private fun deviationDegrees(
        position: Coordinate,
        course: Double,
        throughPoint: Coordinate,
        severity: WeatherIntensity,
    ): Int {
        val turn = abs(normalizedSigned(Geo.bearing(position, throughPoint) - course))
        var degrees = roundHalfAwayFromZero(turn / 5).toInt() * 5
        // Severity-based floor: a real weather deviation turns out ~20–30° to establish
        // a parallel offset, so moderate-or-heavy precip is never given a token nudge
        // and a convective core gets the larger initial turn.
        when (severity) {
            WeatherIntensity.EXTREME -> degrees = max(degrees, 30)
            WeatherIntensity.HEAVY, WeatherIntensity.MODERATE -> degrees = max(degrees, 20)
            WeatherIntensity.LIGHT, WeatherIntensity.UNKNOWN -> Unit
        }
        return min(45, max(10, degrees))
    }

    /**
     * Pull a rejoin point back to the cap's along-course distance when it lies past
     * it (keeping any cross-track offset), so the reroute intercepts the route no
     * deeper than the cap. Uncapped (null) or not-yet-past → returned unchanged.
     */
    private fun cappedToAlong(
        point: Coordinate,
        capAlong: Double?,
        position: Coordinate,
        course: Double,
    ): Coordinate {
        if (capAlong == null) return point
        val s = project(point, position, course)
        if (s.along <= capAlong) return point
        val onCourse = Geo.destination(position, course, capAlong)
        if (abs(s.cross) <= 0.01) return onCourse
        return Geo.destination(onCourse, course + (if (s.cross >= 0) 90 else -90), abs(s.cross))
    }

    /**
     * Cap every vertex of a deviation path (past the start) at the rejoin limit, so
     * no part of the drawn line runs past the destination / into the approach.
     */
    private fun clampPathToAlong(
        path: List<Coordinate>,
        capAlong: Double?,
        position: Coordinate,
        course: Double,
    ): List<Coordinate> {
        if (capAlong == null) return path
        return path.mapIndexed { idx, pt ->
            if (idx == 0) pt else cappedToAlong(pt, capAlong, position, course)
        }
    }

    /**
     * Clamp a deviation path so no leg turns more than `maxDeviationTurnDegrees` off
     * the course — ATC never turns an aircraft the long way around weather, so the
     * drawn line (and the vector / rejoin turn derived from it) is prevented from
     * reversing. Each successive vertex whose leg bearing exceeds the bound is pulled
     * back onto the bound at the same leg length, keeping the path moving downrange.
     * The starting position is never moved.
     */
    private fun boundedToCourse(path: List<Coordinate>, course: Double): List<Coordinate> {
        if (path.size < 2) return path
        val maxTurn = config.maxDeviationTurnDegrees
        val result = mutableListOf(path[0])
        var prev = path[0]
        for (i in 1 until path.size) {
            val pt = path[i]
            if (!pt.isValid) continue
            val delta = normalizedSigned(Geo.bearing(prev, pt) - course)
            prev = if (abs(delta) > maxTurn) {
                val clamped = course + (if (delta > 0) maxTurn else -maxTurn)
                val dist = Geo.distanceNM(prev, pt)
                Geo.destination(prev, clamped, dist)
            } else {
                pt
            }
            result.add(prev)
        }
        return result
    }

    // MARK: - Lookahead

    /**
     * The lookahead distance (NM) for the corridor, from the phase band clamped by
     * a groundspeed-based time window.
     */
    fun lookaheadNM(phase: FlightPhase, groundspeed: Double?): Double {
        val band = if (isTerminal(phase)) config.terminalLookahead else config.enrouteLookahead
        if (groundspeed == null || groundspeed <= 30) return band.endInclusive
        // Distance covered in the middle of the time window (~60 min ≈ gs NM),
        // clamped to the phase band and the time-window bounds.
        val nominal = groundspeed * 1.0
        val timeLower = groundspeed * config.timeLookaheadMinutes.start / 60
        val timeUpper = groundspeed * config.timeLookaheadMinutes.endInclusive / 60
        val clampedToTime = min(max(nominal, timeLower), timeUpper)
        return min(band.endInclusive, max(band.start, clampedToTime))
    }

    private fun isTerminal(phase: FlightPhase): Boolean = when (phase) {
        FlightPhase.PREFLIGHT, FlightPhase.TAXI_OUT, FlightPhase.TAKEOFF, FlightPhase.INITIAL_CLIMB,
        FlightPhase.APPROACH, FlightPhase.LANDING, FlightPhase.TAXI_IN, FlightPhase.PARKED,
        -> true
        FlightPhase.CLIMB, FlightPhase.CRUISE, FlightPhase.DESCENT, FlightPhase.UNKNOWN -> false
    }

    // MARK: - Projection

    /**
     * Project a hazard into the course-relative frame, or null when it has no valid
     * geometry or sits entirely behind / beyond the searched band.
     */
    private fun projectHazard(
        hazard: WeatherHazard,
        position: Coordinate,
        course: Double,
        lookahead: Double,
        corridorEnd: Coordinate,
    ): Projection? {
        val poly = hazard.geometry.polygonPoints
        val radiusBuffer = pointRadiusBuffer(hazard)
        val points = samplePoints(hazard)
        if (points.isEmpty()) return null
        val samples = points.map { project(it, position, course) }

        val alongMin = samples.minOfOrNull { it.along } ?: 0.0
        val alongMax = samples.maxOfOrNull { it.along } ?: 0.0
        // Drop cells fully behind, or beyond the searched band.
        if (alongMax <= -5 || alongMin >= lookahead + config.clusterAlongMarginNM) return null

        // Corridor blocking test: a sample inside the corridor, or (for a wide cell
        // whose vertices all straddle it) the route line passing through the polygon. The
        // corridor half-width scales with intensity, so a red/orange core skirting the route
        // is caught while moderate precip off to the side is not.
        val corridorHalf = corridorHalfWidth(hazard.intensity) + radiusBuffer
        val inCorridor = samples.filter { it.along >= -5 && it.along <= lookahead && abs(it.cross) <= corridorHalf }
        val lineThrough = poly?.let { routeLinePasses(it, position, corridorEnd) } ?: false
        val blocks = inCorridor.isNotEmpty() || lineThrough

        // Near/far edge measured from the in-corridor portion (or the whole cell).
        val relevant = if (inCorridor.isEmpty()) samples else inCorridor
        val nearAlong = max(0.0, (relevant.minOfOrNull { it.along } ?: 0.0) - radiusBuffer)
        val farAlong = max(nearAlong, (relevant.maxOfOrNull { it.along } ?: nearAlong) + radiusBuffer)

        // Clock span + cross extents come from the whole cell's forward portion.
        val ahead = samples.filter { it.along > -5 }
        val spanSamples = if (ahead.isEmpty()) samples else ahead
        val crossLo = spanSamples.minOfOrNull { it.cross } ?: 0.0
        val crossHi = spanSamples.maxOfOrNull { it.cross } ?: 0.0
        val leftEdgeRel = spanSamples.minOfOrNull { it.relBearing } ?: 0.0
        val rightEdgeRel = spanSamples.maxOfOrNull { it.relBearing } ?: 0.0
        val centerCoord = hazard.geometry.representativeCenter
        val centerRel = centerCoord?.let { project(it, position, course).relBearing }
            ?: ((leftEdgeRel + rightEdgeRel) / 2)
        val rightExtent = spanSamples.maxOfOrNull { max(0.0, it.cross) } ?: 0.0
        val leftExtent = spanSamples.maxOfOrNull { max(0.0, -it.cross) } ?: 0.0

        return Projection(
            hazard = hazard,
            polygon = poly,
            center = centerCoord,
            radiusBuffer = radiusBuffer,
            alongMin = alongMin,
            alongMax = alongMax,
            nearAlong = nearAlong,
            farAlong = farAlong,
            crossLo = crossLo,
            crossHi = crossHi,
            leftEdgeRel = leftEdgeRel,
            rightEdgeRel = rightEdgeRel,
            centerRel = centerRel,
            leftExtent = leftExtent,
            rightExtent = rightExtent,
            blocks = blocks,
        )
    }

    // MARK: - Severity → prompting

    private fun shouldPrompt(
        severity: WeatherIntensity,
        convective: Boolean,
        distance: Double,
        centerRel: Double,
    ): Boolean {
        if (convective) return true
        return when (severity) {
            // Light precipitation: only prompt when directly ahead and close.
            WeatherIntensity.LIGHT, WeatherIntensity.UNKNOWN ->
                distance <= config.lightImmediateNM && abs(centerRel) <= config.directlyAheadDegrees
            WeatherIntensity.MODERATE, WeatherIntensity.HEAVY, WeatherIntensity.EXTREME -> true
        }
    }

    // MARK: - Rejoin selection

    /**
     * Pick a downstream filed fix to rejoin: past the far edge of the weather,
     * not inside the hazard, within reasonable reach, and roughly on course.
     */
    private fun rejoinFix(
        waypoints: List<Waypoint>,
        position: Coordinate,
        course: Double,
        farAlong: Double,
        lookahead: Double,
        polygon: List<Coordinate>?,
    ): Waypoint? {
        val located = waypoints.filter { it.coordinate?.isValid ?: false }
        val candidates = located.mapNotNull { wp ->
            val c = wp.coordinate ?: return@mapNotNull null
            val s = project(c, position, course)
            Triple(wp, s.along, s.cross)
        }
        val minAlong = farAlong + config.rejoinDownstreamMarginNM
        val maxAlong = lookahead + config.rejoinReachBeyondNM
        val onCourse = config.corridorHalfWidthNM * 3
        return candidates
            .filter { it.second >= minAlong && it.second <= maxAlong && abs(it.third) <= onCourse }
            .filter { cand ->
                val poly = polygon ?: return@filter true
                val c = cand.first.coordinate ?: return@filter true
                !WeatherRouteAnalyzer.pointInPolygon(c, poly)
            }
            .sortedBy { it.second }
            .firstOrNull()?.first
    }

    /** The route segment the aircraft is leaving (fix before the weather → rejoin). */
    private fun originalSegment(
        waypoints: List<Waypoint>,
        position: Coordinate,
        course: Double,
        nearAlong: Double,
        rejoin: Waypoint?,
    ): RouteSegmentRef? {
        if (rejoin == null) return null
        val located = waypoints.filter { it.coordinate?.isValid ?: false }
        // The last fix still behind the near edge of the weather.
        val before = located
            .mapNotNull { wp ->
                val c = wp.coordinate ?: return@mapNotNull null
                Pair(wp, project(c, position, course).along)
            }
            .filter { it.second < nearAlong }
            .maxWithOrNull(compareBy { it.second })?.first
            ?: return null
        return RouteSegmentRef(from = before.name, to = rejoin.name)
    }

    // MARK: - Geometry helpers

    /** Project a coordinate onto the course line from [position]. */
    private fun project(point: Coordinate, position: Coordinate, course: Double): Sample {
        val d = Geo.distanceNM(position, point)
        val b = Geo.bearing(position, point)
        val deltaDeg = normalizedSigned(b - course)
        val delta = deltaDeg * PI / 180
        val along = d * cos(delta)
        val cross = d * sin(delta)
        val relBearing = atan2(cross, along) * 180 / PI
        return Sample(along, cross, relBearing)
    }

    /** Sample coordinates representing a hazard's shape. */
    private fun samplePoints(hazard: WeatherHazard): List<Coordinate> = when (val g = hazard.geometry) {
        is HazardGeometry.Polygon -> g.points.filter { it.isValid }
        is HazardGeometry.BoundingBoxGeometry -> g.box.corners + listOf(g.box.center)
        is HazardGeometry.PointRadius -> if (g.center.isValid) listOf(g.center) else emptyList()
        is HazardGeometry.RouteSegmentIntersection -> listOf(g.entry, g.exit).filter { it.isValid }
    }

    private fun pointRadiusBuffer(hazard: WeatherHazard): Double {
        val g = hazard.geometry
        if (g is HazardGeometry.PointRadius) return max(0.0, g.radiusNM)
        return 0.0
    }

    /**
     * Whether the corridor line passes through the polygon (endpoint inside, or an
     * edge crossing). Mirrors `WeatherRouteAnalyzer`.
     */
    private fun routeLinePasses(polygon: List<Coordinate>, a: Coordinate, b: Coordinate): Boolean {
        if (WeatherRouteAnalyzer.pointInPolygon(a, polygon)) return true
        if (WeatherRouteAnalyzer.pointInPolygon(b, polygon)) return true
        var j = polygon.size - 1
        for (i in polygon.indices) {
            if (Geo.segmentsIntersect(a, b, polygon[j], polygon[i])) return true
            j = i
        }
        return false
    }

    /**
     * Build a small square polygon around a coordinate (fallback intersection area
     * for point/segment hazards).
     */
    private fun boxAround(c: Coordinate, half: Double = 0.25): List<Coordinate> = listOf(
        Coordinate(c.latitude - half, c.longitude - half),
        Coordinate(c.latitude - half, c.longitude + half),
        Coordinate(c.latitude + half, c.longitude + half),
        Coordinate(c.latitude + half, c.longitude - half),
    )

    /** Normalize an angle to (−180, 180]. */
    private fun normalizedSigned(deg: Double): Double {
        var d = deg % 360
        if (d > 180) d -= 360
        if (d <= -180) d += 360
        return d
    }

    companion object {
        /**
         * Convert a relative bearing (0 = straight ahead, + = right) to a clock
         * position 1…12. 0° → 12 o'clock, +90° → 3 o'clock, −90° → 9 o'clock.
         */
        fun clockPosition(relBearing: Double): Int {
            val steps = roundHalfAwayFromZero(relBearing / 30).toInt()
            val mod = ((steps % 12) + 12) % 12
            return if (mod == 0) 12 else mod
        }
    }
}
