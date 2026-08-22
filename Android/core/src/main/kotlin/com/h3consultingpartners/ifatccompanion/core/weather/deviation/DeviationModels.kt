package com.h3consultingpartners.ifatccompanion.core.weather.deviation

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.model.Waypoint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

// The deviation-flow half of `IFATCCompanion/Weather/RadarOverlayModel.swift`. The
// radar-overlay half of that file (RadarFrame / RadarCell / RadarOverlayModel) belongs
// to the radar-overlay package and is deliberately not duplicated here.

/** Which side of course a deviation is requested / approved on. */
@Serializable
enum class DeviationDirection(val rawValue: String) {
    @SerialName("left") LEFT("left"),
    @SerialName("right") RIGHT("right"),
    ;

    val opposite: DeviationDirection get() = if (this == LEFT) RIGHT else LEFT
    val word: String get() = rawValue
}

/**
 * Simulated ATC weather-deviation flow state. Mirrors the request → approval →
 * clear-of-weather → rejoin lifecycle. [RADAR_UNAVAILABLE_FOR_REGION] is a terminal
 * informational state used outside NOAA coverage with no advisory data.
 */
@Serializable
enum class WeatherDeviationState(val rawValue: String) {
    @SerialName("none") NONE("none"),
    @SerialName("weatherAheadDetected") WEATHER_AHEAD_DETECTED("weatherAheadDetected"),
    @SerialName("advisoryIssued") ADVISORY_ISSUED("advisoryIssued"),
    @SerialName("awaitingPilotIntentions") AWAITING_PILOT_INTENTIONS("awaitingPilotIntentions"),
    @SerialName("deviationRequested") DEVIATION_REQUESTED("deviationRequested"),
    @SerialName("deviationApproved") DEVIATION_APPROVED("deviationApproved"),
    @SerialName("vectoringAroundWeather") VECTORING_AROUND_WEATHER("vectoringAroundWeather"),
    @SerialName("deviatingAroundWeather") DEVIATING_AROUND_WEATHER("deviatingAroundWeather"),
    @SerialName("clearOfWeather") CLEAR_OF_WEATHER("clearOfWeather"),
    @SerialName("rejoinClearanceIssued") REJOIN_CLEARANCE_ISSUED("rejoinClearanceIssued"),
    @SerialName("resumedOwnNavigation") RESUMED_OWN_NAVIGATION("resumedOwnNavigation"),
    @SerialName("radarUnavailableForRegion") RADAR_UNAVAILABLE_FOR_REGION("radarUnavailableForRegion"),
    ;

    /**
     * Whether the aircraft is currently off its filed course for weather (so the
     * telemetry loop should watch for "clear of weather").
     */
    val isDeviating: Boolean
        get() = this == DEVIATION_APPROVED || this == DEVIATING_AROUND_WEATHER ||
            this == VECTORING_AROUND_WEATHER

    /**
     * Whether the pilot has committed to a controller-approved reroute — a lateral
     * deviation or a vector is being flown. While committed the mint line is
     * **locked** to the path the pilot is following: a fresh radar sample no longer
     * moves or re-proposes it, and confirm-clear hysteresis never tears it down.
     * The lock releases only on clear-of-weather (or a fresh reroute request).
     */
    val isCommittedDeviation: Boolean
        get() = when (this) {
            DEVIATION_APPROVED, VECTORING_AROUND_WEATHER, DEVIATING_AROUND_WEATHER, CLEAR_OF_WEATHER -> true
            else -> false
        }

    /**
     * Whether the flow is holding for the pilot's answer to an advisory: the response
     * card is already on screen with its request buttons (deviate left/right, vectors,
     * higher/lower, continue) and nothing has been approved yet.
     */
    val isAwaitingPilotDecision: Boolean
        get() = when (this) {
            ADVISORY_ISSUED, AWAITING_PILOT_INTENTIONS, DEVIATION_REQUESTED -> true
            else -> false
        }

    companion object {
        fun fromRawValue(raw: String): WeatherDeviationState? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * A reference to the filed route segment a deviation departs from (by fix name),
 * so the rejoin clearance can name where the aircraft left course.
 */
@Serializable
data class RouteSegmentRef(var from: String, var to: String)

/**
 * Mutable storage for the active weather-deviation interaction. Held by the app
 * coordinator; the phraseology/engine read and update it. Serializable so an
 * in-progress deviation can be captured in the session snapshot and restored on
 * reconnect (otherwise the deviation card and its "clear of weather" button
 * vanish when the Infinite Flight link drops and comes back mid-diversion).
 *
 * The Swift is a `struct`, so every engine step works on its own copy. The engines
 * here therefore call `context.copy()` before mutating, which reproduces that value
 * semantics exactly while keeping the field-by-field mutability the coordinator uses.
 */
@Serializable
data class WeatherDeviationContext(
    var state: WeatherDeviationState = WeatherDeviationState.NONE,
    var activeHazardID: String? = null,
    /**
     * The mint deviation line the pilot has committed to fly, frozen when the
     * controller approves the vector/deviation. While set, the map draws this fixed
     * path instead of the live per-sample recommendation, so the mint line stops
     * shifting and blinking once the pilot is following it. Cleared on clear-of-
     * weather / reset; replaced only when the pilot requests another reroute.
     */
    var committedDeviationPath: List<PathPoint>? = null,
    var requestedDeviationDirection: DeviationDirection? = null,
    var approvedDeviationDegrees: Int? = null,
    /**
     * The heading last spoken to the pilot — **magnetic**, and already crabbed into the
     * wind, since it is what the pilot dials into the heading bug (see `HeadingSolver`).
     * The armed-turn fields below are the opposite: they are the drawn line's own **true**
     * courses, kept in that frame because they are compared against the map geometry.
     */
    var assignedHeading: Int? = null,
    var maintainAltitude: Int? = null,
    var rejoinFix: String? = null,
    /**
     * The next turn point in the committed mint line (the vertex the aircraft is
     * flying toward) and the **true** course out of it toward the following vertex,
     * so the telemetry loop can auto-issue the turn once the aircraft reaches it. A
     * side-hug line (`[start, turnOut, turnBack, rejoin]`) has **two** such turns —
     * out onto the parallel leg, then back down to the route — so these advance from
     * one interior vertex to the next each time a turn fires, rather than clearing
     * after a single turn. [pendingTurnIndex] is the index (into the committed line)
     * of the vertex being turned at; all are cleared when the final turn fires.
     */
    var pendingTurnIndex: Int? = null,
    var vectorApexLatitude: Double? = null,
    var vectorApexLongitude: Double? = null,
    var pendingRejoinHeading: Int? = null,
    /**
     * Bearing of the leg leading into the pending turn vertex (previous → apex), so
     * the loop can detect the aircraft passing abeam/past the vertex even if it flies
     * wide of it.
     */
    var vectorLegBearing: Double? = null,
    /**
     * A deviation approved while the mint line is still drawn ahead: the turn-out
     * (start of the mint line) the aircraft is flying toward, the **true** course out of
     * it onto the reroute, and the bearing of the leg into it (to detect passing abeam).
     * While these are set the controller has approved the deviation but is **holding the
     * turn** — the pilot continues on course until reaching the turn-out, then the
     * beginning turn is issued. Cleared once the turn fires (or on reset).
     */
    var deviationStartLatitude: Double? = null,
    var deviationStartLongitude: Double? = null,
    var deviationStartHeading: Int? = null,
    var deviationStartLegBearing: Double? = null,
    var originalRouteSegment: RouteSegmentRef? = null,
    /** Epoch millis; iOS keeps a `Date`. */
    var timeDeviationStartedMillis: Long? = null,
    var lastATCWeatherCall: String? = null,
    var radarCoverageAvailable: Boolean = false,
    var radarSourceDescription: String = "NOAA/NWS radar precipitation",
) {
    /**
     * A serializable lat/lon pair. The frozen mint line is stored as these, mirroring
     * how the vector apex is stored as separate `Double`s so an in-progress deviation
     * survives a reconnect.
     */
    @Serializable
    data class PathPoint(var latitude: Double, var longitude: Double) {
        constructor(c: Coordinate) : this(c.latitude, c.longitude)

        val coordinate: Coordinate get() = Coordinate(latitude, longitude)
    }

    /**
     * Reset back to the idle state, keeping the radar coverage/source facts so the
     * diagnostics panel still reflects the last known provider status. Swift mutates in
     * place; here the fresh context is returned.
     */
    fun reset(): WeatherDeviationContext = WeatherDeviationContext(
        radarCoverageAvailable = radarCoverageAvailable,
        radarSourceDescription = radarSourceDescription,
    )

    companion object {
        val none = WeatherDeviationContext()
    }
}

// MARK: - Conflict

/**
 * A detected route-weather conflict, produced by [RouteWeatherConflictDetector].
 * Identity-only equality (it carries coordinate geometry).
 */
data class RouteWeatherConflict(
    var hazard: WeatherHazard,
    /** Distance from the aircraft to the near edge of the weather (NM). */
    var distanceAheadNM: Double,
    /** Bearing to the weather relative to the aircraft's course (−180…180; + is right). */
    var relativeBearingDegrees: Double,
    /** Clock positions (1…12) for the left edge, center, and right edge of the cell. */
    var leftClock: Int,
    var centerClock: Int,
    var rightClock: Int,
    var estimatedTimeMinutes: Double?,
    var severity: WeatherIntensity,
    var leftBypassScore: Double,
    var rightBypassScore: Double,
    var recommendedDirection: DeviationDirection,
    var recommendedDeviationDegrees: Int,
    var rejoinFix: Waypoint?,
    var originalSegment: RouteSegmentRef?,
    /**
     * Whether to raise the "contact ATC" banner and (in Mock Mode) auto-issue the
     * advisory. True only for genuinely on-path weather that is also within the
     * tactical deviation range ([withinTacticalRange]); far-ahead weather is detected
     * and monitored without yet triggering the banner.
     */
    var shouldPrompt: Boolean,
    /**
     * Whether the weather is close enough (near edge within `deviationTriggerNM`) to
     * work the deviation now. The banner / ATC advisory hold off until this is true.
     */
    var withinTacticalRange: Boolean = true,
    /**
     * Whether the weather is close enough (near edge within `mintLineDrawNM`) to draw
     * the recommended reroute line on the map. A conflict is still detected out to the
     * full lookahead — so Diagnostics can report far weather as "monitoring" — but the
     * mint line is held until this is true, so a straight-corridor deviation aimed at
     * distant weather (across the route's bends) doesn't render as a runaway line.
     */
    var withinDrawRange: Boolean = true,
    /** The polygon the route passes through, for shading on the map. */
    var intersectionArea: List<Coordinate>,
    /**
     * A recommended deviation path for drawing on the map: `position → turn(s) →
     * rejoin`. A single-apex dogleg has three points; a side-hug down one edge of a
     * long line has four (step-out, run parallel, then close to the rejoin). A run of
     * adjacent hugs folded into one parallel line down a complex multi-cell system
     * ([RouteWeatherConflictDetector.mergeAdjacentDeviations]) carries more: one
     * turn-out, every offset vertex, one rejoin — the interior turns are still walked
     * generically at each vertex.
     */
    var deviationPath: List<Coordinate>,
    /**
     * How far the finally-drawn line gets from the filed route at its farthest point
     * (NM) — measured by the coordinator once the path is fully built, *after* the
     * adjacent-deviation merge and the gentle-rejoin softening, both of which reshape
     * it. A line below `minRouteExcursionNM` deviates nowhere and is not drawn (see
     * [RouteWeatherConflictDetector.pathLeavesRoute]); the conflict is still detected
     * and still drives the banner, advisory, and Diagnostics. Defaults to "unmeasured",
     * so a directly-constructed conflict is drawn as before until someone measures it.
     */
    var maxRouteExcursionNM: Double = Double.MAX_VALUE,
    val id: String = UUID.randomUUID().toString(),
) {
    override fun equals(other: Any?): Boolean = other is RouteWeatherConflict && id == other.id
    override fun hashCode(): Int = id.hashCode()

    val isConvectiveSigmet: Boolean get() = hazard.isConvectiveSigmet
    val source: WeatherHazardSource get() = hazard.source
}
