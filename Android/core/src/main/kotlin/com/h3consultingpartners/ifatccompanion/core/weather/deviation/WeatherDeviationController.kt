package com.h3consultingpartners.ifatccompanion.core.weather.deviation

import com.h3consultingpartners.ifatccompanion.core.airports.AirportDatabase
import com.h3consultingpartners.ifatccompanion.core.airports.ProcedureParser
import com.h3consultingpartners.ifatccompanion.core.atc.ApproachIntercept
import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.geo.WindEstimator
import com.h3consultingpartners.ifatccompanion.core.geo.validCoordinateOrNull
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.model.Waypoint
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyEngine
import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink
import com.h3consultingpartners.ifatccompanion.core.session.WeatherDeviationAction
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import com.h3consultingpartners.ifatccompanion.core.weather.SIGMET
import com.h3consultingpartners.ifatccompanion.core.weather.TurbulenceSeverity
import com.h3consultingpartners.ifatccompanion.core.weather.radar.RadarOverlayModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The simulated weather-deviation flow: find the storms on the route, draw the reroute
 * around them, and run the exchange with the controller that gets the aircraft around it.
 *
 * Ported from the weather-deviation half of `IFATCCompanion/App/AppModel.swift` —
 * `recomputeWeatherHazards` (:5229), `recomputeLockedDeviations` (:5455),
 * `computeDeviations` (:5504), `selectActiveLockedDeviation` (:5629),
 * `faintDeviationLines` (:5745), `buildWeatherHazards` (:5909), the advisory issuers
 * (:6653, :6676), the pilot actions (:6703 onward) and the turn issuers (:7265, :7919,
 * :7984).
 *
 * On iOS all of that is method soup on one 8,000-line `AppModel`. Here it is its own object
 * because it has one job and one piece of state — where the weather is and what the pilot
 * has been cleared to do about it — and because every decision in it is a pure function of
 * a route, a hazard set and an aircraft position, which is what makes it assertable without
 * a device.
 *
 * The heavy geometry is not here: [RouteWeatherConflictDetector] already solves the reroute
 * around a system, and [WeatherDeviationEngine] already composes every call. This is the
 * part that was missing — the thing that runs them.
 *
 * **Not yet ported**, and deliberately: the telemetry-discontinuity resync
 * (`resyncWeatherDeviation`), the off-path re-plan (`maybeReplanDeviationOffPath`), the
 * redraw when an entry point falls behind (`maybeRedrawDeviationPastEntry`), and the
 * re-vector onto a fresh line while already committed. Each is a recovery path for a
 * deviation already under way; without them a pilot who flies well off the drawn line keeps
 * the line they were given rather than being re-vectored onto a new one.
 */
class WeatherDeviationController(
    private val clock: Clock,
    private val diagnostics: DiagnosticsSink = DiagnosticsSink.noop,
    private val settingsProvider: () -> AppSettings = { AppSettings() },
    private val engineProvider: () -> PhraseologyEngine = { PhraseologyEngine() },
    private val detector: RouteWeatherConflictDetector = RouteWeatherConflictDetector(),
) {

    /** Everything the flow needs to know about the flight right now. */
    data class Inputs(
        val plan: FlightPlan,
        val aircraft: AircraftState,
        val phase: FlightPhase,
        val atcState: ATCState,
        val currentFacility: ATCFacility,
        val hasDeparted: Boolean,
        val companionStandby: Boolean,
        val assignedAltitude: Int,
        val overlay: RadarOverlayModel,
        val routeSigmets: List<SIGMET> = emptyList(),
        /**
         * True once a live radar sample has actually produced cells for this route. The
         * locked set is never frozen before then: the first recompute of a flight routinely
         * runs before the cells are in place, and freezing that empty result is what leaves
         * the mint lines missing until a manual refresh. Always true in Mock Mode, where the
         * cells are set synchronously.
         */
        val radarCellsReady: Boolean = true,
        /**
         * Turns a leg's **true** course into the heading the pilot dials in: crabbed into
         * the wind so the aircraft's *track* lies along the drawn line, then converted into
         * the magnetic frame the heading bug reads.
         *
         * Passed in rather than solved here because the estimate is smoothed across
         * telemetry ticks and the flight session is what sees them. Null in tests and
         * wherever the sim exposes too little to solve either correction, in which case
         * every heading below is the rounded true bearing — exactly the old behaviour.
         */
        val headings: WindEstimator? = null,
    )

    /** The rejoin fix marker drawn at the end of the mint line. */
    data class RejoinMarker(val name: String, val coordinate: Coordinate)

    /** What the map, the banner and the response card read. */
    data class State(
        val hazards: List<WeatherHazard> = emptyList(),
        val conflict: RouteWeatherConflict? = null,
        /** The solid mint line: the reroute the pilot is flying, or the one being offered. */
        val deviationLine: List<Coordinate> = emptyList(),
        /** Every other upcoming reroute on the plan, drawn faint. */
        val previews: List<List<Coordinate>> = emptyList(),
        val rejoinMarker: RejoinMarker? = null,
        val context: WeatherDeviationContext = WeatherDeviationContext(),
        val actions: List<WeatherDeviationAction> = emptyList(),
        val statusLine: String = "",
        /** The ATC tab's "weather ahead — contact ATC" banner, or null. */
        val bannerText: String? = null,
    ) {
        val isActive: Boolean get() = conflict != null || context.state != WeatherDeviationState.NONE
    }

    /**
     * Transmissions to put on the frequency, in order.
     *
     * Returned rather than posted, because the transcript belongs to the flight session and
     * a second writer into it is how two callers end up disagreeing about what was said.
     * [controllerInitiated] marks a call ATC makes on its own — an auto-issued advisory, a
     * turn fired by the aircraft's own progress — with no pilot request waiting on an
     * answer. Those are the ones that can come out verbatim-identical back to back, so the
     * caller holds one that would only repeat a call the pilot already acknowledged.
     */
    data class Emission(
        val transmissions: List<ATCTransmission>,
        val controllerInitiated: Boolean = false,
    ) {
        companion object {
            val none = Emission(emptyList())
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val settings: AppSettings get() = settingsProvider()
    private val engine: WeatherDeviationEngine get() = WeatherDeviationEngine(WeatherDeviationPhraseology(engineProvider()))

    // region Held across ticks

    private var context = WeatherDeviationContext()
    private var hazards: List<WeatherHazard> = emptyList()
    private var lockedDeviations: List<RouteWeatherConflict> = emptyList()
    private var deviationsLocked = false
    private var lockedRouteKey: String? = null
    private var activeConflict: RouteWeatherConflict? = null
    private var lastConflictSeenAtMillis: Long? = null

    /** Whether the pilot has engaged this advisory at all, so it is offered exactly once. */
    private var weatherHandled = false
    private var advisoryIssued = false

    // endregion

    /**
     * One telemetry tick. Recomputes hazards, the locked reroute set and the active
     * conflict, then returns whatever the controller says off its own bat — the auto-issued
     * advisory, a turn as the aircraft reaches a vertex, the auto-resume at the rejoin.
     */
    fun update(inputs: Inputs): Emission {
        hazards = buildHazards(inputs)
        val position = aircraftOrDeparture(inputs)
        // Off the radar network and not opted in: the overlay image still shows, but no
        // reroute is drawn from it. The NASA estimate is ~10 km, hours latent and cannot
        // grade severity, so a deviation built on it would be a confident-looking line
        // around weather nobody has actually observed. "Deviations from satellite
        // estimate" is the pilot's opt-in, and it was read by nothing.
        if (position == null || deviationsAreSuppressed(inputs)) {
            activeConflict = null
            publish(inputs)
            return Emission.none
        }

        ensureLockedDeviations(inputs, position)
        val detected = selectActiveLockedDeviation(inputs, position)
        activeConflict = resolveWithHysteresis(detected)

        // The weather ahead has cleared: forget the "engaged" flag and roll back a
        // not-yet-committed lifecycle so a new conflict prompts afresh. A committed
        // deviation is never torn down here — the pilot is flying it.
        if (activeConflict == null && activeRideSigmet(inputs) == null) {
            weatherHandled = false
            advisoryIssued = false
            if (context.state.isAwaitingPilotDecision ||
                context.state == WeatherDeviationState.WEATHER_AHEAD_DETECTED
            ) {
                context = context.reset()
            }
        }

        val emission = autoCalls(inputs, position)
        publish(inputs)
        return emission
    }

    /**
     * Whether the current precipitation source may drive a deviation at all.
     *
     * True only for a satellite estimate the pilot has not opted into — true observed
     * radar always may, and Mock Mode's deterministic cells are not an estimate.
     */
    private fun deviationsAreSuppressed(inputs: Inputs): Boolean =
        inputs.overlay.isSatelliteEstimate && !settingsProvider().satelliteDeviationsEnabled

    /** A pilot tap on one of the response card's buttons. */
    fun perform(action: WeatherDeviationAction, inputs: Inputs): Emission {
        if (inputs.companionStandby) return Emission.none
        val emission = when (action) {
            WeatherDeviationAction.ASK_CENTER -> askAboutWeather(inputs)
            WeatherDeviationAction.REQUEST_RIGHT_DEVIATION -> requestDeviation(inputs, DeviationDirection.RIGHT)
            WeatherDeviationAction.REQUEST_LEFT_DEVIATION -> requestDeviation(inputs, DeviationDirection.LEFT)
            WeatherDeviationAction.REQUEST_VECTOR -> requestVectors(inputs)
            WeatherDeviationAction.REQUEST_HIGHER -> requestAltitude(inputs, higher = true)
            WeatherDeviationAction.REQUEST_LOWER -> requestAltitude(inputs, higher = false)
            WeatherDeviationAction.CLEAR_OF_WEATHER -> reportClearOfWeather(inputs)
            WeatherDeviationAction.CONTINUE_ON_COURSE -> continueOnCourse(inputs)
            WeatherDeviationAction.SAY_AGAIN -> sayAgain(inputs)
        }
        publish(inputs)
        return emission
    }

    /** Put the flow down entirely — a new flight, or a flight cleared. */
    fun reset() {
        context = WeatherDeviationContext()
        hazards = emptyList()
        lockedDeviations = emptyList()
        deviationsLocked = false
        lockedRouteKey = null
        activeConflict = null
        lastConflictSeenAtMillis = null
        weatherHandled = false
        advisoryIssued = false
        _state.value = State()
    }

    /** Restore an in-progress deviation from a session snapshot. */
    fun restore(restored: WeatherDeviationContext) {
        context = restored
    }

    /** Force the whole-route walk to run again — a pull-to-refresh, or fresh radar. */
    fun invalidateLockedDeviations() {
        deviationsLocked = false
        lastConflictSeenAtMillis = null
    }

    // region Hazards

    /**
     * Normalize the current weather into the hazards the detector routes around.
     *
     * The only driver of the deviation flow is **moderate-or-greater precipitation**: the
     * hand-authored cells in Mock Mode, or the cells sampled from the live radar image
     * otherwise. SIGMET polygons are deliberately not fed here — a SIGMET is a coarse,
     * often huge advisory box rather than a precipitation shape, and routing around one
     * produces reroutes that ignore where the storms actually are. They still raise their
     * own advisory, through [Situation.Sigmet].
     */
    private fun buildHazards(inputs: Inputs): List<WeatherHazard> {
        val overlay = inputs.overlay
        if (!overlay.isEnabled || !overlay.coverageAvailable) return emptyList()
        val source = if (overlay.isSatelliteEstimate) {
            WeatherHazardSource.SATELLITE_ESTIMATE
        } else {
            WeatherHazardSource.NOAA_RADAR
        }
        val cells = if (settings.mockMode) overlay.mockCells else overlay.sampledCells
        return cells
            .filter { it.intensity >= WeatherIntensity.MODERATE }
            .map { cell ->
                WeatherHazard(
                    source = source,
                    phenomenon = WeatherPhenomenon.PRECIPITATION,
                    intensity = cell.intensity,
                    geometry = HazardGeometry.Polygon(cell.polygon),
                    confidence = HazardConfidence.HIGH,
                    movementDirectionDegrees = cell.movementDirectionDegrees,
                    movementSpeedKnots = cell.movementSpeedKnots,
                    notes = overlay.layerLabel,
                )
            }
    }

    // endregion

    // region The locked whole-route walk

    /**
     * A fingerprint of the filed route, so a new or edited plan discards the old locked
     * deviations and computes a fresh set.
     */
    private fun routeFingerprint(plan: FlightPlan): String = buildString {
        append(plan.departure).append('|').append(plan.destination)
        plan.waypoints.forEach { waypoint ->
            append('|').append(waypoint.name)
            waypoint.coordinate?.let {
                append(':').append((it.latitude * 100).toInt())
                append(':').append((it.longitude * 100).toInt())
            }
        }
    }

    /**
     * Recompute the locked deviation set once per route + radar sample, then hold it.
     *
     * A telemetry tick never re-solves the geometry, which is what stops the mint lines
     * shifting and flickering from one second to the next. Only a **non-empty** result is
     * frozen: an empty solve is left unlocked so the next recompute re-solves it once the
     * cells are actually in place.
     */
    private fun ensureLockedDeviations(inputs: Inputs, position: Coordinate) {
        val key = routeFingerprint(inputs.plan)
        if (key != lockedRouteKey) {
            lockedRouteKey = key
            deviationsLocked = false
        }
        if (deviationsLocked) return
        if (!settings.mockMode && !inputs.radarCellsReady) return

        lockedDeviations = recomputeLockedDeviations(inputs, position)
        deviationsLocked = lockedDeviations.isNotEmpty()
    }

    private fun recomputeLockedDeviations(inputs: Inputs, position: Coordinate): List<RouteWeatherConflict> {
        if (!inputs.overlay.isEnabled || !inputs.overlay.coverageAvailable || hazards.isEmpty()) {
            return emptyList()
        }
        val route = fullRoutePolyline(inputs.plan)
        val origin = departureCoordinate(inputs.plan) ?: position
        if (!origin.isValid) return emptyList()

        // Begin the walk at least the airport margin past the departure end of the route, so
        // no drawn line starts within that distance of the field — weather on the immediate
        // climb-out is handled by departure vectors, not by an enroute reroute.
        val walkStart = departureCoordinate(inputs.plan)?.let { departure ->
            RouteGeometry.pointAlongRoute(
                start = departure,
                ahead = RouteGeometry.upcomingRouteCoordinates(route, departure),
                targetNM = REJOIN_AIRPORT_MARGIN_NM,
            )
        } ?: origin

        return computeDeviations(inputs, route, walkStart, rejoinCap(inputs.plan, route))
    }

    /**
     * Walk the filed route from [origin] to the destination and produce the deviation around
     * each qualifying system — the full search for every one, in a single pass.
     *
     * Every deviation is then measured for how far it actually leaves the route, and one
     * that deviates nowhere is **discarded** rather than left undrawn. The set is what the
     * aircraft works from: a line nobody can fly would still be selected as the active
     * conflict, raise the banner, issue the advisory and put up the response card for a
     * maneuver with no line to turn onto — and, being selected, would stand in front of a
     * perfectly good line further on.
     */
    private fun computeDeviations(
        inputs: Inputs,
        route: List<Coordinate>,
        origin: Coordinate,
        cap: Coordinate?,
    ): List<RouteWeatherConflict> {
        val results = mutableListOf<RouteWeatherConflict>()
        var startPoint = origin
        var steps = 0
        while (results.size < MAX_PREVIEW_SYSTEMS && steps < MAX_PREVIEW_SYSTEMS * 4) {
            steps += 1
            val aheadFromStart = RouteGeometry.upcomingRouteCoordinates(route, startPoint)
            if (aheadFromStart.isEmpty()) break
            val onRoute = detector.nearestRouteHazard(aheadFromStart, startPoint, hazards) ?: break

            // Solve from a modest lead *before* the entry, where the route is locally
            // straight — not from startPoint, which may be hundreds of miles back across the
            // route's bends and would render as a runaway line past the weather.
            val lead = min(onRoute.distanceNM, DEVIATION_SOLVE_LEAD_NM)
            val detectAlong = max(0.0, onRoute.distanceNM - lead)
            val detectPosition = if (detectAlong <= 1) {
                startPoint
            } else {
                RouteGeometry.pointAlongRoute(startPoint, aheadFromStart, detectAlong) ?: startPoint
            }
            val ahead = RouteGeometry.upcomingRouteCoordinates(route, detectPosition)
            if (ahead.isEmpty()) break
            val course = ahead.firstOrNull { Geo.distanceNM(detectPosition, it) > 1 }
                ?.let { Geo.bearing(detectPosition, it) }
                ?: currentCourse(inputs, detectPosition)

            val conflict = detector.detectConflict(
                position = detectPosition,
                course = course,
                groundspeedKnots = inputs.aircraft.groundSpeed,
                phase = FlightPhase.CRUISE,
                hazards = hazards,
                waypoints = inputs.plan.waypoints,
                routeAhead = ahead,
                rejoinCap = cap,
            )
            val end = conflict?.deviationPath?.lastOrNull()
            val usable = conflict != null && end != null && conflict.deviationPath.size >= 2 &&
                // Only a line that actually rounds weather, never a degenerate line drawn out
                // in clear air, and whose apex sits alongside the system rather than bulging
                // off across a route bend.
                detector.pathEngagesWeather(conflict.deviationPath, hazards) &&
                detector.previewApexHugsWeather(conflict.deviationPath, listOf(detectPosition) + ahead, hazards) &&
                end.isValid && Geo.distanceNM(detectPosition, end) > 1

            if (usable) {
                results += conflict
                startPoint = end
                continue
            }
            val next = RouteGeometry.pointAlongRoute(
                startPoint,
                aheadFromStart,
                onRoute.distanceNM + PREVIEW_SCAN_STEP_NM,
            )
            if (next != null && Geo.distanceNM(startPoint, next) > 1) startPoint = next else break
        }

        // Fold runs of short back-to-back deviations into one continuous parallel hug, so a
        // complex multi-cell system draws a single long line rather than a string of little
        // in-and-out jogs that each rejoin inside the next cell.
        val mergeRoute = RouteGeometry.routeTruncated(
            RouteGeometry.upcomingRouteCoordinates(route, origin),
            cap,
        )
        val merged = detector.mergeAdjacentDeviations(results, hazards, mergeRoute)

        // Soften any hard turn back onto the flight plan by rejoining at a fix farther down
        // the route — bounded by the next deviation's turn-out, so a softened rejoin can
        // never run into the following reroute.
        val softened = merged.mapIndexed { index, deviation ->
            val nextTurnOutAlong = merged.getOrNull(index + 1)
                ?.deviationPath?.firstOrNull()
                ?.let { RouteGeometry.alongRouteNM(route, it) - MERGE_REJOIN_MARGIN_NM }
            withGentleRejoin(
                deviation = deviation,
                plan = inputs.plan,
                route = route,
                cap = cap,
                limitAlong = nextTurnOutAlong ?: Double.MAX_VALUE,
            )
        }

        val filedRoute = (listOf(origin) + RouteGeometry.upcomingRouteCoordinates(route, origin))
            .filter { it.isValid }
        return softened.mapNotNull { deviation ->
            deviation.maxRouteExcursionNM = detector.routeExcursionNM(deviation.deviationPath, filedRoute)
            deviation.takeIf { it.maxRouteExcursionNM >= detector.config.minRouteExcursionNM }
        }
    }

    /**
     * Soften the final turn back onto the route by rejoining at a fix farther down it.
     *
     * Only when the current closing turn is sharper than [MAX_REJOIN_TURN_DEGREES], and only
     * to a fix whose closing leg does not take the aircraft back through the weather the
     * deviation exists to avoid.
     */
    private fun withGentleRejoin(
        deviation: RouteWeatherConflict,
        plan: FlightPlan,
        route: List<Coordinate>,
        cap: Coordinate?,
        limitAlong: Double,
    ): RouteWeatherConflict {
        val points = deviation.deviationPath.filter { it.isValid }
        if (points.size < 3) return deviation
        val rejoin = points.last()
        val turnVertex = points[points.size - 2]
        val inbound = Geo.bearing(points[points.size - 3], turnVertex)
        fun turnDegrees(to: Coordinate) = courseChangeDegrees(inbound, Geo.bearing(turnVertex, to))
        val currentTurn = turnDegrees(rejoin)
        if (currentTurn <= MAX_REJOIN_TURN_DEGREES) return deviation

        var capAlong = limitAlong
        cap?.takeIf { it.isValid }?.let { capAlong = min(capAlong, RouteGeometry.alongRouteNM(route, it)) }
        val rejoinAlong = RouteGeometry.alongRouteNM(route, rejoin)

        var best: Triple<Waypoint, Coordinate, Double>? = null
        val candidates = plan.waypoints
            .mapNotNull { waypoint ->
                val c = waypoint.coordinate?.takeIf { it.isValid } ?: return@mapNotNull null
                val along = RouteGeometry.alongRouteNM(route, c)
                if (along <= rejoinAlong + 1 || along > capAlong) null else Triple(waypoint, c, along)
            }
            .sortedBy { it.third }
        for ((waypoint, coordinate, _) in candidates) {
            val softened = points.dropLast(1) + coordinate
            if (!detector.committedPathStillClear(softened, hazards)) continue
            val turn = turnDegrees(coordinate)
            if (turn <= MAX_REJOIN_TURN_DEGREES) {
                best = Triple(waypoint, coordinate, turn)
                break
            }
            if (best == null || turn < best.third) best = Triple(waypoint, coordinate, turn)
        }
        val chosen = best?.takeIf { it.third < currentTurn } ?: return deviation
        deviation.deviationPath = points.dropLast(1) + chosen.second
        deviation.rejoinFix = chosen.first
        return deviation
    }

    /**
     * The deviation the aircraft is currently working, chosen from the locked set by how far
     * along the route each one's rejoin sits — with its proximity flags refreshed live from
     * the aircraft's actual position.
     */
    private fun selectActiveLockedDeviation(inputs: Inputs, position: Coordinate): RouteWeatherConflict? {
        if (lockedDeviations.isEmpty()) return null
        val route = fullRoutePolyline(inputs.plan)
        val aircraftAlong = RouteGeometry.alongRouteNM(route, position)
        var best: RouteWeatherConflict? = null
        var bestAlong = Double.MAX_VALUE
        for (deviation in lockedDeviations) {
            val end = deviation.deviationPath.lastOrNull()?.takeIf { it.isValid } ?: continue
            val endAlong = RouteGeometry.alongRouteNM(route, end)
            if (endAlong <= aircraftAlong - 2) continue // already flown past its rejoin
            if (endAlong < bestAlong) {
                bestAlong = endAlong
                best = deviation
            }
        }
        val deviation = best ?: return null
        val start = deviation.deviationPath.firstOrNull() ?: return null
        val distance = Geo.distanceNM(position, start)
        deviation.distanceAheadNM = distance
        deviation.withinTacticalRange = distance <= detector.config.deviationTriggerNM
        deviation.withinDrawRange = distance <= detector.config.mintLineDrawNM
        deviation.shouldPrompt = deviation.withinTacticalRange &&
            (deviation.isConvectiveSigmet || deviation.severity >= WeatherIntensity.MODERATE)
        return deviation
    }

    /**
     * Hold a shown conflict through a noisy resample.
     *
     * Live radar sampling loses and regains a storm that is really still ahead, and read
     * straight through that blinks the mint line and the banner on and off at the resample
     * cadence. So once a conflict is shown it keeps being returned until the route has
     * tested continuously clear for [CLEAR_CONFIRM_WINDOW_MILLIS]. A committed deviation is
     * never torn down here — the pilot is already flying the line.
     */
    private fun resolveWithHysteresis(detected: RouteWeatherConflict?): RouteWeatherConflict? {
        if (detected != null) {
            lastConflictSeenAtMillis = clock.nowMillis()
            return detected
        }
        val held = activeConflict ?: run {
            lastConflictSeenAtMillis = null
            return null
        }
        if (context.state.isCommittedDeviation) return held
        val since = lastConflictSeenAtMillis
        if (since != null && clock.nowMillis() - since < CLEAR_CONFIRM_WINDOW_MILLIS) return held
        lastConflictSeenAtMillis = null
        return null
    }

    // endregion

    // region Calls the controller makes on its own

    private fun autoCalls(inputs: Inputs, position: Coordinate): Emission {
        if (!settings.weatherDeviationAlerts.alertsEnabled) return Emission.none
        if (!weatherFlowAllowed(inputs) || inputs.companionStandby) return Emission.none

        maybeAutoIssueAdvisory(inputs, position)?.let { return it }
        // At most one turn fires per tick, most imminent first, so they never race.
        maybeIssueDeviationStartTurn(inputs, position)?.let { return it }
        maybeIssueRejoinTurn(inputs, position)?.let { return it }
        maybeAutoResumeAtRouteIntercept(inputs, position)?.let { return it }
        return Emission.none
    }

    /**
     * Issue the advisory once for a conflict the pilot has not engaged.
     *
     * In Mock Mode as soon as the conflict is in tactical range, so the offline demo plays
     * out on its own; in every mode as a safety net once the aircraft closes to within
     * [DEVIATION_AUTO_CALL_NM] of the drawn turn-out, so a reroute cannot be silently flown
     * past with no call.
     */
    private fun maybeAutoIssueAdvisory(inputs: Inputs, position: Coordinate): Emission? {
        if (advisoryIssued || weatherHandled) return null
        if (context.state != WeatherDeviationState.NONE) return null
        if (establishedOnFinal(inputs)) return null
        val conflict = flyableConflict() ?: return null
        if (!conflict.shouldPrompt) return null

        val turnOut = conflict.deviationPath.firstOrNull()?.takeIf { it.isValid }
        val closeEnough = settings.mockMode || (
            turnOut != null && aheadAlongTrackNM(inputs, turnOut, position)
                .let { it > 0 && it <= DEVIATION_AUTO_CALL_NM }
            )
        if (!closeEnough) return null

        val situation = currentSituation(inputs) ?: return null
        advisoryIssued = true
        weatherHandled = true
        val result = engine.issueAdvisory(
            cs = callsign(inputs),
            situation = situation,
            context = context,
            facility = weatherFacility(inputs),
        )
        context = result.context
        diagnostics.log(
            DiagnosticCategory.WEATHER,
            message = "Weather advisory issued: ${result.atc.firstOrNull()?.displayText.orEmpty()}",
        )
        return Emission(result.atc, controllerInitiated = true)
    }

    /**
     * While a deviation is approved with its turn held — the reroute drawn ahead — issue the
     * beginning turn once the aircraft reaches the turn-out.
     *
     * Called a lead distance *before* the vertex so the aircraft rolls onto the reroute
     * through it rather than overshooting, and fired equally by passing abeam it, so flying
     * wide of the point still triggers the turn.
     */
    private fun maybeIssueDeviationStartTurn(inputs: Inputs, position: Coordinate): Emission? {
        if (context.state != WeatherDeviationState.DEVIATION_APPROVED) return null
        val latitude = context.deviationStartLatitude ?: return null
        val longitude = context.deviationStartLongitude ?: return null
        val heading = context.deviationStartHeading ?: return null
        val turnOut = Coordinate(latitude, longitude)

        val turnDegrees = context.deviationStartLegBearing
            ?.let { courseChangeDegrees(it, heading.toDouble()) } ?: 30.0
        // The turn-out is flown in from the filed course rather than a prior mint-line
        // vertex, so the full anticipation lead applies uncapped.
        val lead = turnLeadNM(inputs, turnDegrees, inboundLegNM = null)
        if (!hasReached(turnOut, position, context.deviationStartLegBearing, lead)) return null
        if (wouldReverseAircraft(inputs, position, heading)) return null

        val result = engine.beginDeviationTurn(
            cs = callsign(inputs),
            heading = assignedHeading(inputs, heading.toDouble()),
            maintainAltitude = maintainAltitude(inputs),
            context = context,
            facility = weatherFacility(inputs),
        )
        context = result.context.copy(
            deviationStartLatitude = null,
            deviationStartLongitude = null,
            deviationStartHeading = null,
            deviationStartLegBearing = null,
        )
        armRejoinTurn(index = 1, path = committedMintLine())
        return Emission(result.atc, controllerInitiated = true)
    }

    /** Each interior turn of the committed line, as the aircraft reaches its vertex. */
    private fun maybeIssueRejoinTurn(inputs: Inputs, position: Coordinate): Emission? {
        if (context.state != WeatherDeviationState.VECTORING_AROUND_WEATHER) return null
        val index = context.pendingTurnIndex ?: return null
        val heading = context.pendingRejoinHeading ?: return null
        val apexLatitude = context.vectorApexLatitude ?: return null
        val apexLongitude = context.vectorApexLongitude ?: return null
        val apex = Coordinate(apexLatitude, apexLongitude)
        val path = committedMintLine()

        val inboundLeg = path.getOrNull(index - 1)
            ?.takeIf { it.isValid }
            ?.let { Geo.distanceNM(it, apex) }
        val turnDegrees = context.vectorLegBearing
            ?.let { courseChangeDegrees(it, heading.toDouble()) } ?: 30.0
        val lead = turnLeadNM(inputs, turnDegrees, inboundLeg)
        if (!hasReached(apex, position, context.vectorLegBearing, lead)) return null
        // Every drawn leg is bounded to 100° off course, so a turn this far off the current
        // track means the aircraft has left the drawn geometry behind. Skip it.
        if (wouldReverseAircraft(inputs, position, heading)) return null

        val isFinalTurn = index >= path.size - 2
        val result = engine.rejoinTurn(
            cs = callsign(inputs),
            heading = assignedHeading(inputs, heading.toDouble()),
            rejoinFix = context.rejoinFix,
            finalTurn = isFinalTurn,
            context = context,
            facility = weatherFacility(inputs),
        )
        context = result.context
        if (!isFinalTurn) armRejoinTurn(index + 1, path)
        return Emission(result.atc, controllerInitiated = true)
    }

    /**
     * The aircraft flew the mint line all the way to the flight-plan intercept without the
     * pilot reporting clear of weather, so the controller resumes own navigation itself.
     *
     * Guarded to the final leg — at or beyond the last turn, and within
     * [AUTO_RESUME_INTERCEPT_NM] of the intercept — so it cannot trip on the outbound or
     * parallel legs.
     */
    private fun maybeAutoResumeAtRouteIntercept(inputs: Inputs, position: Coordinate): Emission? {
        when (context.state) {
            WeatherDeviationState.DEVIATION_APPROVED, WeatherDeviationState.VECTORING_AROUND_WEATHER -> Unit
            else -> return null
        }
        val line = deviationLine()
        if (line.size < 2) return null
        val end = line.last().takeIf { it.isValid } ?: return null
        val lastTurn = line[line.size - 2].takeIf { it.isValid } ?: return null

        val legBearing = Geo.bearing(lastTurn, end)
        val toAircraft = Geo.bearing(lastTurn, position)
        val along = Geo.distanceNM(lastTurn, position) * cos((toAircraft - legBearing) * PI / 180)
        if (along < 0 || Geo.distanceNM(position, end) > AUTO_RESUME_INTERCEPT_NM) return null

        val result = engine.autoResumeOwnNavigation(
            cs = callsign(inputs),
            context = context,
            facility = weatherFacility(inputs),
        )
        context = result.context
        settleAfterDeviation()
        return Emission(result.atc, controllerInitiated = true)
    }

    // endregion

    // region Pilot actions

    /** Pilot taps "Contact ATC": the controller volunteers the weather advisory. */
    private fun askAboutWeather(inputs: Inputs): Emission {
        weatherHandled = true
        val cs = callsign(inputs)
        val facility = weatherFacility(inputs)
        val situation = currentSituation(inputs)
        val spokenName = engineProvider().spokenName(facility)
        if (situation == null) {
            return Emission(
                listOf(
                    atc(
                        facility,
                        "${cs.display}, no significant precipitation along your route at this time.",
                        "${cs.spoken}, no significant precipitation along your route at this time.",
                    ),
                ),
            )
        }
        val query = pilot(
            facility,
            "$spokenName, ${cs.display}, weather ahead, requesting advisory.",
            "$spokenName, ${cs.spoken}, weather ahead, requesting advisory.",
        )
        advisoryIssued = true
        val result = engine.issueAdvisory(cs = cs, situation = situation, context = context, facility = facility)
        context = result.context
        return Emission(listOf(query) + result.atc)
    }

    /**
     * Pilot requests a left or right deviation; the controller approves.
     *
     * When the reroute is still drawn ahead the controller approves but **holds the turn**:
     * the pilot continues on course and is told to expect it in so many miles, and the
     * beginning turn fires on its own once the aircraft reaches the turn-out. Close aboard,
     * the turn is worked immediately.
     */
    private fun requestDeviation(inputs: Inputs, direction: DeviationDirection): Emission {
        if (!weatherFlowAllowed(inputs) || establishedOnFinal(inputs)) return Emission.none
        weatherHandled = true
        val cs = callsign(inputs)
        val facility = weatherFacility(inputs)
        val turnOut = turnOutStillAhead(inputs)
        val result = if (turnOut != null) {
            engine.deferDeviation(
                cs = cs,
                conflict = activeConflict,
                direction = direction,
                distanceNM = turnOut.second,
                inputs = deviationInputs(inputs, direction),
                context = context,
                facility = facility,
            )
        } else {
            engine.requestDeviation(
                cs = cs,
                conflict = activeConflict,
                requested = direction,
                inputs = deviationInputs(inputs, direction),
                context = context,
                facility = facility,
            )
        }
        context = result.context
        freezeCommittedPath()
        if (turnOut != null) armDeviationStart(inputs) else armRejoinTurn(1, committedMintLine())
        return Emission(listOfNotNull(result.pilot) + result.atc)
    }

    /** Pilot requests a vector around the weather; the controller assigns a heading. */
    private fun requestVectors(inputs: Inputs): Emission {
        if (!weatherFlowAllowed(inputs) || establishedOnFinal(inputs)) return Emission.none
        // Already flying a committed deviation and asking again: the reroute ahead is what
        // matters. Re-planning it from here is not ported yet, so the controller has the
        // pilot continue on the deviation they are on rather than inventing a second one.
        val held = context.deviationStartLatitude != null
        if (context.state.isCommittedDeviation && !held) {
            val result = engine.continueCurrentDeviation(
                cs = callsign(inputs),
                context = context,
                facility = weatherFacility(inputs),
            )
            context = result.context
            return Emission(listOfNotNull(result.pilot) + result.atc)
        }
        weatherHandled = true
        val side = activeConflict?.recommendedDirection ?: DeviationDirection.RIGHT
        val result = engine.requestVectors(
            cs = callsign(inputs),
            inputs = deviationInputs(inputs, side),
            context = context,
            facility = weatherFacility(inputs),
        )
        context = result.context
        freezeCommittedPath()
        armRejoinTurn(1, committedMintLine())
        return Emission(listOfNotNull(result.pilot) + result.atc)
    }

    /** Pilot requests higher or lower for weather; the controller assigns the altitude. */
    private fun requestAltitude(inputs: Inputs, higher: Boolean): Emission {
        if (!weatherFlowAllowed(inputs)) return Emission.none
        weatherHandled = true
        val current = max(inputs.assignedAltitude, inputs.aircraft.altitudeMSL?.toInt() ?: 0)
        val step = if (higher) ALTITUDE_STEP_FT else -ALTITUDE_STEP_FT
        val base = when {
            current > 0 -> current
            inputs.plan.cruiseAltitude > 0 -> inputs.plan.cruiseAltitude
            else -> DEFAULT_CRUISE_FT
        }
        val target = max(MINIMUM_ALTITUDE_FT, base + step)
        val result = engine.requestAltitude(
            cs = callsign(inputs),
            higher = higher,
            targetAltitude = target,
            context = context,
            facility = weatherFacility(inputs),
        )
        context = result.context
        return Emission(listOfNotNull(result.pilot) + result.atc)
    }

    /** Pilot reports clear of weather; the controller clears them back to the route. */
    private fun reportClearOfWeather(inputs: Inputs): Emission {
        val result = engine.reportClearOfWeather(
            cs = callsign(inputs),
            inputs = deviationInputs(inputs, context.requestedDeviationDirection ?: DeviationDirection.RIGHT),
            context = context,
            facility = weatherFacility(inputs),
        )
        context = result.context
        settleAfterDeviation()
        return Emission(listOfNotNull(result.pilot) + result.atc)
    }

    /** Pilot elects to continue on course through the advisory. */
    private fun continueOnCourse(inputs: Inputs): Emission {
        weatherHandled = true
        val cs = callsign(inputs)
        val facility = weatherFacility(inputs)
        context = context.reset()
        // Continuing resolves the prompt, so drop the confirm-clear hold: a genuinely clear
        // route then removes the banner promptly, and weather still ahead re-arms it on the
        // next detected tick.
        lastConflictSeenAtMillis = null
        return Emission(
            listOf(
                pilot(facility, "${cs.display}, continuing on course.", "${cs.spoken}, continuing on course."),
                atc(
                    facility,
                    "${cs.display}, roger, advise if you need to deviate.",
                    "${cs.spoken}, roger, advise if you need to deviate.",
                ),
            ),
        )
    }

    /** Re-issue the last weather advisory or instruction. */
    private fun sayAgain(inputs: Inputs): Emission {
        val cs = callsign(inputs)
        val facility = weatherFacility(inputs)
        val request = pilot(facility, "Say again for ${cs.display}.", "Say again for ${cs.spoken}.")
        val last = context.lastATCWeatherCall
            ?: return Emission(listOf(request))
        return Emission(listOf(request, atc(facility, last, last)))
    }

    // endregion

    // region Deviation bookkeeping

    /**
     * Freeze the recommended reroute as the path the pilot has now committed to fly, so it
     * stops being re-proposed and stops shifting with each radar resample.
     *
     * A reroute that was never drawn — it deviates nowhere — is never frozen either:
     * committing it would put the withheld line back on the map as a frozen path, which is
     * drawn ahead of every guard.
     */
    private fun freezeCommittedPath() {
        val conflict = activeConflict
        if (conflict == null || conflict.deviationPath.size < 2 || !leavesRoute(conflict)) {
            context.committedDeviationPath = null
            return
        }
        context.committedDeviationPath = conflict.deviationPath.map { WeatherDeviationContext.PathPoint(it) }
    }

    /** Hold the beginning turn at the turn-out, to fire once the aircraft reaches it. */
    private fun armDeviationStart(inputs: Inputs) {
        val path = committedMintLine()
        val position = validCoordinateOrNull(inputs.aircraft.latitude, inputs.aircraft.longitude)
        val first = path.getOrNull(0)?.takeIf { it.isValid }
        val second = path.getOrNull(1)?.takeIf { it.isValid }
        if (first == null || second == null || position == null) {
            context.deviationStartLatitude = null
            context.deviationStartLongitude = null
            context.deviationStartHeading = null
            context.deviationStartLegBearing = null
            return
        }
        context.deviationStartLatitude = first.latitude
        context.deviationStartLongitude = first.longitude
        // Stored **true**, like every other piece of turn geometry here: it is compared
        // against `deviationStartLegBearing` for the turn size and against the aircraft's
        // track by the never-reverse guard, and a crabbed magnetic heading in either
        // comparison mixes frames. The correction goes on where the number is spoken.
        context.deviationStartHeading = ApproachIntercept.normalizedHeading(Geo.bearing(first, second))
        context.deviationStartLegBearing = Geo.bearing(position, first)
    }

    /**
     * Arm the interior turn at [index] of the committed line, so it fires as the aircraft
     * reaches that vertex. A single dogleg has one; a side-hug has two, and each firing arms
     * the next.
     */
    private fun armRejoinTurn(index: Int, path: List<Coordinate>) {
        val apex = path.getOrNull(index)?.takeIf { it.isValid }
        val previous = path.getOrNull(index - 1)?.takeIf { it.isValid }
        val next = path.getOrNull(index + 1)?.takeIf { it.isValid }
        if (index < 1 || apex == null || previous == null || next == null) {
            context.pendingTurnIndex = null
            context.pendingRejoinHeading = null
            context.vectorApexLatitude = null
            context.vectorApexLongitude = null
            context.vectorLegBearing = null
            return
        }
        context.pendingTurnIndex = index
        context.vectorApexLatitude = apex.latitude
        context.vectorApexLongitude = apex.longitude
        context.vectorLegBearing = Geo.bearing(previous, apex)
        // True, for the same reason as `deviationStartHeading`: `vectorLegBearing` and the
        // never-reverse guard are both true-frame comparisons.
        context.pendingRejoinHeading = ApproachIntercept.normalizedHeading(Geo.bearing(apex, next))
    }

    /** The deviation is over: forget it and let a fresh conflict prompt afresh. */
    private fun settleAfterDeviation() {
        activeConflict = null
        context = context.reset()
        weatherHandled = false
        advisoryIssued = false
        lastConflictSeenAtMillis = null
    }

    // endregion

    // region What the map and the card read

    /** The committed line, else the live recommendation — whatever the map is drawing. */
    private fun committedMintLine(): List<Coordinate> {
        context.committedDeviationPath?.let { frozen -> return frozen.map { it.coordinate } }
        val conflict = activeConflict ?: return emptyList()
        // Only a line the map actually drew can be tracked along: an undrawn reroute has no
        // turns for the pilot to be vectored onto.
        if (!leavesRoute(conflict)) return emptyList()
        return conflict.deviationPath
    }

    private fun deviationLine(): List<Coordinate> {
        context.committedDeviationPath?.let { frozen ->
            if (frozen.size >= 2) return frozen.map { it.coordinate }
        }
        val conflict = activeConflict ?: return emptyList()
        // Far on-path weather is still detected and monitored, but its straight-corridor
        // reroute — aimed across the route's bends — would render as a runaway line, so the
        // line is held until the aircraft closes in.
        if (!conflict.withinDrawRange || conflict.deviationPath.size < 2) return emptyList()
        if (!leavesRoute(conflict)) return emptyList()
        return conflict.deviationPath
    }

    /**
     * Every other upcoming reroute on the plan, drawn faint. Deviations already flown past,
     * and the one currently drawn solid, are dropped.
     */
    private fun previews(inputs: Inputs, solid: List<Coordinate>): List<List<Coordinate>> {
        val route = fullRoutePolyline(inputs.plan)
        val solidStart = solid.firstOrNull()
        val aircraftAlong = validCoordinateOrNull(inputs.aircraft.latitude, inputs.aircraft.longitude)
            ?.let { RouteGeometry.alongRouteNM(route, it) }
        return lockedDeviations.mapNotNull { deviation ->
            val path = deviation.deviationPath
            val first = path.firstOrNull() ?: return@mapNotNull null
            if (path.size < 2 || !leavesRoute(deviation)) return@mapNotNull null
            if (solidStart != null && Geo.distanceNM(solidStart, first) < 2) return@mapNotNull null
            val end = path.last()
            if (aircraftAlong != null && end.isValid &&
                RouteGeometry.alongRouteNM(route, end) <= aircraftAlong - 2
            ) {
                return@mapNotNull null
            }
            path
        }
    }

    private fun rejoinMarker(line: List<Coordinate>): RejoinMarker? {
        val conflict = activeConflict
        if (conflict != null && conflict.withinDrawRange && leavesRoute(conflict)) {
            val fix = conflict.rejoinFix
            val coordinate = fix?.coordinate?.takeIf { it.isValid }
            if (fix != null && coordinate != null) {
                return RejoinMarker(fix.name.ifEmpty { "Rejoin" }, coordinate)
            }
        }
        val end = line.lastOrNull()?.takeIf { it.isValid } ?: return null
        if (context.committedDeviationPath == null) return null
        return RejoinMarker(context.rejoinFix ?: "Rejoin", end)
    }

    /**
     * The response buttons to surface, keyed off the lifecycle. A turbulence or icing
     * advisory offers only altitude changes — there is nothing to laterally route around.
     */
    private fun actions(inputs: Inputs): List<WeatherDeviationAction> = when (context.state) {
        WeatherDeviationState.ADVISORY_ISSUED,
        WeatherDeviationState.AWAITING_PILOT_INTENTIONS,
        WeatherDeviationState.DEVIATION_REQUESTED,
        -> when {
            establishedOnFinal(inputs) -> listOf(WeatherDeviationAction.SAY_AGAIN)
            advisoryIsAltitudeOnly(inputs) -> listOf(
                WeatherDeviationAction.REQUEST_HIGHER,
                WeatherDeviationAction.REQUEST_LOWER,
                WeatherDeviationAction.CONTINUE_ON_COURSE,
                WeatherDeviationAction.SAY_AGAIN,
            )
            else -> listOf(
                WeatherDeviationAction.REQUEST_RIGHT_DEVIATION,
                WeatherDeviationAction.REQUEST_LEFT_DEVIATION,
                WeatherDeviationAction.REQUEST_VECTOR,
                WeatherDeviationAction.REQUEST_HIGHER,
                WeatherDeviationAction.REQUEST_LOWER,
                WeatherDeviationAction.CONTINUE_ON_COURSE,
                WeatherDeviationAction.SAY_AGAIN,
            )
        }

        WeatherDeviationState.DEVIATION_APPROVED,
        WeatherDeviationState.VECTORING_AROUND_WEATHER,
        WeatherDeviationState.DEVIATING_AROUND_WEATHER,
        WeatherDeviationState.CLEAR_OF_WEATHER,
        -> {
            val flyingLateral = context.committedDeviationPath != null || activeConflict != null
            when {
                establishedOnFinal(inputs) -> listOf(
                    WeatherDeviationAction.CLEAR_OF_WEATHER,
                    WeatherDeviationAction.SAY_AGAIN,
                )
                advisoryIsAltitudeOnly(inputs) || !flyingLateral -> listOf(
                    WeatherDeviationAction.CLEAR_OF_WEATHER,
                    WeatherDeviationAction.SAY_AGAIN,
                )
                else -> listOf(
                    WeatherDeviationAction.REQUEST_VECTOR,
                    WeatherDeviationAction.CLEAR_OF_WEATHER,
                    WeatherDeviationAction.SAY_AGAIN,
                )
            }
        }

        else -> emptyList()
    }

    private fun statusLine(inputs: Inputs): String {
        val conflict = activeConflict
        if (conflict != null) {
            val distance = conflict.distanceAheadNM.roundToInt()
            return "${conflict.severity.displayLabel} precipitation, $distance NM ahead. Say intentions."
        }
        return when (context.state) {
            WeatherDeviationState.DEVIATION_APPROVED,
            WeatherDeviationState.VECTORING_AROUND_WEATHER,
            WeatherDeviationState.DEVIATING_AROUND_WEATHER,
            -> "Deviating for weather — report clear of weather when able."

            else -> {
                val ride = activeRideSigmet(inputs)
                if (ride != null) {
                    "${rideAdvisoryWord(ride)} along your route — a different altitude may " +
                        "give a smoother ride. Say intentions."
                } else {
                    "Say intentions."
                }
            }
        }
    }

    /**
     * The ATC tab's banner. Raised only for weather the pilot can act on now, and only
     * while they have not yet engaged it — once the exchange is under way the response card
     * itself is the prompt.
     */
    private fun bannerText(inputs: Inputs): String? {
        if (!settings.weatherDeviationAlerts.alertsEnabled) return null
        if (!weatherFlowAllowed(inputs)) return null
        if (context.state != WeatherDeviationState.NONE) return null
        val conflict = flyableConflict()
        if (conflict != null && conflict.shouldPrompt) {
            val distance = conflict.distanceAheadNM.roundToInt()
            return "${conflict.severity.displayLabel} precipitation $distance NM ahead — contact ATC."
        }
        val ride = activeRideSigmet(inputs) ?: return null
        return "${rideAdvisoryWord(ride)} along your route — contact ATC."
    }

    private fun publish(inputs: Inputs) {
        val line = deviationLine()
        _state.value = State(
            hazards = hazards,
            conflict = activeConflict,
            deviationLine = line,
            previews = previews(inputs, line),
            rejoinMarker = rejoinMarker(line),
            context = context,
            actions = actions(inputs),
            statusLine = statusLine(inputs),
            bannerText = bannerText(inputs),
        )
    }

    // endregion

    // region Situation

    /** The weather situation to advise on, or null when nothing significant applies. */
    private fun currentSituation(inputs: Inputs): WeatherDeviationEngine.Situation? {
        val conflict = flyableConflict()
        if (conflict != null) {
            return if (conflict.source == WeatherHazardSource.SIGMET) {
                WeatherDeviationEngine.Situation.Sigmet(
                    label = conflict.hazard.notes ?: "significant weather",
                    convective = conflict.isConvectiveSigmet,
                )
            } else {
                WeatherDeviationEngine.Situation.RadarConflict(conflict)
            }
        }
        activeRideSigmet(inputs)?.let { return rideSigmetSituation(it) }
        if (!inputs.overlay.coverageAvailable && inputs.routeSigmets.isEmpty()) {
            return WeatherDeviationEngine.Situation.NoRadarNoAdvisory
        }
        return null
    }

    /**
     * The active precipitation conflict the pilot can actually act on: one whose solved line
     * leaves the flight path and is therefore drawn. A conflict whose line lies on the route
     * has nothing to turn onto, so prompting for it would offer a deviation that cannot be
     * flown.
     */
    private fun flyableConflict(): RouteWeatherConflict? = activeConflict?.takeIf { leavesRoute(it) }

    private fun leavesRoute(deviation: RouteWeatherConflict): Boolean =
        deviation.maxRouteExcursionNM >= detector.config.minRouteExcursionNM

    /**
     * The most significant turbulence or icing SIGMET along the route, when there is no
     * precipitation conflict to thread. There is nothing to laterally route around, so it is
     * worked with an altitude change instead.
     */
    private fun activeRideSigmet(inputs: Inputs): SIGMET? {
        if (flyableConflict() != null) return null
        return inputs.routeSigmets
            .filter {
                it.category == SIGMET.Category.TURBULENCE ||
                    it.category == SIGMET.Category.ICING_OR_MOUNTAIN_WAVE
            }
            .maxByOrNull { it.turbulenceSeverity.rawValue }
    }

    private fun rideSigmetSituation(sigmet: SIGMET): WeatherDeviationEngine.Situation {
        val text = (sigmet.hazard ?: sigmet.raw).uppercase()
        if (text.contains("ICE")) {
            return WeatherDeviationEngine.Situation.RideSigmet(label = "icing", icing = true)
        }
        if (text.contains("MTW")) {
            return WeatherDeviationEngine.Situation.RideSigmet(
                label = "mountain wave turbulence",
                icing = false,
            )
        }
        val label = if (sigmet.turbulenceSeverity == TurbulenceSeverity.SEVERE) {
            "severe turbulence"
        } else {
            "turbulence"
        }
        return WeatherDeviationEngine.Situation.RideSigmet(label = label, icing = false)
    }

    private fun rideAdvisoryWord(sigmet: SIGMET): String {
        val text = (sigmet.hazard ?: sigmet.raw).uppercase()
        return when {
            text.contains("ICE") -> "Icing"
            sigmet.turbulenceSeverity == TurbulenceSeverity.SEVERE -> "Severe turbulence"
            else -> "Turbulence"
        }
    }

    private fun advisoryIsAltitudeOnly(inputs: Inputs): Boolean =
        flyableConflict() == null && activeRideSigmet(inputs) != null

    // endregion

    // region Context and geometry helpers

    /**
     * The controller working the weather deviation: whichever radar position is tuned —
     * Departure on climb, Approach on arrival, Center enroute — falling back to the phase of
     * flight when the tuned facility is not a radar position.
     */
    private fun weatherFacility(inputs: Inputs): ATCFacility = when (inputs.currentFacility) {
        ATCFacility.DEPARTURE, ATCFacility.CENTER, ATCFacility.APPROACH -> inputs.currentFacility
        else -> when (inputs.phase) {
            FlightPhase.APPROACH, FlightPhase.DESCENT -> ATCFacility.APPROACH
            FlightPhase.INITIAL_CLIMB, FlightPhase.CLIMB -> ATCFacility.DEPARTURE
            else -> ATCFacility.CENTER
        }
    }

    /** The flow runs only in flight, and never over a human controller. */
    private fun weatherFlowAllowed(inputs: Inputs): Boolean {
        if (!inputs.hasDeparted || inputs.companionStandby) return false
        return when (inputs.atcState) {
            ATCState.NOT_CONNECTED, ATCState.CONNECTED_IDLE, ATCState.CLEARANCE,
            ATCState.PUSHBACK, ATCState.ENGINE_START, ATCState.PUSHBACK_TAXI,
            ATCState.GROUND_TAXI, ATCState.RUNWAY_CROSSING, ATCState.HOLDING_SHORT,
            ATCState.LINE_UP_WAIT, ATCState.TOWER_DEPARTURE, ATCState.LANDING,
            ATCState.RUNWAY_EXIT, ATCState.GROUND_ARRIVAL, ATCState.PARKED,
            -> false

            else -> true
        }
    }

    private fun establishedOnFinal(inputs: Inputs): Boolean =
        inputs.atcState == ATCState.FINAL || inputs.atcState == ATCState.LANDING

    private fun isOnStar(inputs: Inputs): Boolean {
        if (inputs.plan.star.isEmpty()) return false
        return inputs.phase == FlightPhase.DESCENT || inputs.phase == FlightPhase.APPROACH ||
            inputs.atcState == ATCState.DESCENT || inputs.atcState == ATCState.APPROACH ||
            inputs.atcState == ATCState.FINAL
    }

    private fun starDisplaySpoken(inputs: Inputs): Pair<String, String> {
        if (inputs.plan.star.isEmpty()) return "" to ""
        val parsed = ProcedureParser.parseSTAR(inputs.plan.star, inputs.plan.destination)
        val display = parsed?.displayName ?: inputs.plan.star
        return display to display
    }

    private fun deviationInputs(
        inputs: Inputs,
        direction: DeviationDirection,
        unableSide: Boolean = false,
    ): WeatherDeviationEngine.Inputs {
        val (starDisplay, starSpoken) = starDisplaySpoken(inputs)
        return WeatherDeviationEngine.Inputs(
            maintainAltitude = maintainAltitude(inputs),
            heading = deviationHeading(inputs, direction),
            onSTAR = isOnStar(inputs),
            starDisplay = starDisplay,
            starSpoken = starSpoken,
            nearRoute = false,
            unableRequestedSide = unableSide,
        )
    }

    private fun maintainAltitude(inputs: Inputs): Int = when {
        inputs.assignedAltitude > 0 -> inputs.assignedAltitude
        inputs.plan.cruiseAltitude > 0 -> inputs.plan.cruiseAltitude
        else -> DEFAULT_CRUISE_FT
    }

    /**
     * A leg's true course as the heading to hand the pilot.
     *
     * The mint line is great-circle geometry, so its legs are *true* courses, and it asks
     * the aircraft to follow a drawn path rather than merely point somewhere — so the
     * number spoken has to be the heading that makes the aircraft's **track** lie along the
     * leg. Without the crab the aircraft is flown on the leg's bearing and the wind walks
     * it off the line for the leg's whole length; without the declination it is flown on a
     * true bearing dialled into a magnetic bug.
     *
     * Falls back to the rounded true bearing when no estimator is attached, which is what
     * every one of these three sites did before.
     */
    private fun assignedHeading(inputs: Inputs, trueCourse: Double): Int =
        inputs.headings?.assignedHeading(trueCourse)
            ?: ApproachIntercept.normalizedHeading(trueCourse)

    /**
     * The heading to fly for a vector around weather.
     *
     * Preferring the bearing to the reroute's first turn vertex keeps the assigned heading
     * consistent with the line on the map and anchored to where the aircraft is *now* — so a
     * second vector, requested while already deviating, turns toward the drawn reroute
     * rather than stacking another offset on top of the current heading.
     */
    private fun deviationHeading(inputs: Inputs, direction: DeviationDirection): Int {
        val position = aircraftOrDeparture(inputs)
        val apex = activeConflict?.deviationPath?.getOrNull(1)?.takeIf { it.isValid }
        if (position != null && apex != null) {
            return assignedHeading(inputs, Geo.bearing(position, apex))
        }
        val base = inputs.aircraft.heading ?: position?.let { currentCourse(inputs, it) } ?: 0.0
        val degrees = activeConflict?.recommendedDeviationDegrees ?: 20
        val signed = base.roundToInt() + if (direction == DeviationDirection.RIGHT) degrees else -degrees
        return ((signed % 360) + 360) % 360
    }

    /**
     * The turn-out at the start of the drawn line and its distance ahead, rounded to five
     * miles, when it sits meaningfully ahead of the aircraft — so the beginning turn is held
     * until the aircraft reaches it. Null when the aircraft is already at or past it.
     */
    private fun turnOutStillAhead(inputs: Inputs): Pair<Coordinate, Int>? {
        val position = validCoordinateOrNull(inputs.aircraft.latitude, inputs.aircraft.longitude) ?: return null
        val path = activeConflict?.deviationPath ?: return null
        if (path.size < 2) return null
        val first = path[0].takeIf { it.isValid } ?: return null
        if (!path[1].isValid) return null
        val ahead = max(
            aheadAlongTrackNM(inputs, first, position),
            RouteGeometry.alongRouteNM(fullRoutePolyline(inputs.plan), first) -
                RouteGeometry.alongRouteNM(fullRoutePolyline(inputs.plan), position),
        )
        if (ahead <= DEVIATION_TURN_HOLD_NM) return null
        return first to max(5, (ahead / 5).roundToInt() * 5)
    }

    /**
     * How far ahead a point lies along the aircraft's current track — negative once the
     * aircraft has passed abeam it.
     *
     * Not the same as the straight-line distance, and that difference matters: a turn-out
     * the aircraft has already flown past is still miles away as the crow flies, and read as
     * a distance it looks like a turn comfortably ahead.
     */
    private fun aheadAlongTrackNM(inputs: Inputs, target: Coordinate, position: Coordinate): Double {
        val track = aircraftTrack(inputs, position)
        val bearing = Geo.bearing(position, target)
        return Geo.distanceNM(position, target) * cos((bearing - track) * PI / 180)
    }

    private fun aircraftTrack(inputs: Inputs, position: Coordinate): Double =
        inputs.aircraft.track ?: inputs.aircraft.trueHeading ?: inputs.aircraft.heading
            ?: currentCourse(inputs, position)

    /**
     * Whether the aircraft is close enough to a turn vertex to be turned: within the
     * anticipation lead of it, or already past abeam it along the leg into it.
     */
    private fun hasReached(vertex: Coordinate, position: Coordinate, legBearing: Double?, leadNM: Double): Boolean {
        val distance = Geo.distanceNM(position, vertex)
        if (distance <= leadNM) return true
        if (legBearing == null) return false
        val vertexToAircraft = Geo.bearing(vertex, position)
        return distance * cos((vertexToAircraft - legBearing) * PI / 180) >= -leadNM
    }

    /**
     * How far ahead of a turn vertex the turn is issued, so the aircraft rolls out on the
     * next leg *through* it rather than overshooting: the base capture reach (~30 s of
     * travel) plus the turn-anticipation distance for this vertex's course change.
     *
     * Down a mint-line leg the added lead is capped at 60% of that leg, so a turn is never
     * called while the aircraft is still most of a leg away — sitting at the previous vertex,
     * say. The beginning turn is flown in from the filed course rather than a prior vertex,
     * so it applies the full lead uncapped.
     */
    private fun turnLeadNM(inputs: Inputs, turnDegrees: Double, inboundLegNM: Double?): Double {
        val groundSpeed = inputs.aircraft.groundSpeed ?: 300.0
        val base = max(2.0, groundSpeed / 120)
        val anticipation = turnAnticipationNM(groundSpeed, turnDegrees)
        if (inboundLegNM == null) return base + anticipation
        val room = max(0.0, inboundLegNM * 0.6 - base)
        return base + min(anticipation, room)
    }

    /** The geometric roll-in distance for a turn, plus half a reaction lead. */
    private fun turnAnticipationNM(groundSpeed: Double, turnDegrees: Double): Double {
        val half = (min(abs(turnDegrees), 170.0) / 2) * PI / 180
        val geometric = turnRadiusNM(groundSpeed) * tan(half)
        val reaction = max(0.5, groundSpeed / 360)
        return geometric + reaction * 0.5
    }

    /** R = V² / (g · tan bank), at a typical 25° bank. Faster aircraft carve a wider arc. */
    private fun turnRadiusNM(groundSpeed: Double): Double {
        val speed = max(60.0, groundSpeed)
        val feetPerSecond = speed * 1.68781
        val radiusFeet = (feetPerSecond * feetPerSecond) / (32.174 * tan(25.0 * PI / 180))
        return radiusFeet / 6076.12
    }

    private fun wouldReverseAircraft(inputs: Inputs, position: Coordinate, heading: Int): Boolean =
        courseChangeDegrees(aircraftTrack(inputs, position), heading.toDouble()) > MAX_VECTOR_TURN_DEGREES

    private fun courseChangeDegrees(inbound: Double, outbound: Double): Double {
        var delta = abs(outbound - inbound) % 360
        if (delta > 180) delta = 360 - delta
        return delta
    }

    private fun aircraftOrDeparture(inputs: Inputs): Coordinate? =
        validCoordinateOrNull(inputs.aircraft.latitude, inputs.aircraft.longitude)
            ?: departureCoordinate(inputs.plan)

    private fun departureCoordinate(plan: FlightPlan): Coordinate? =
        plan.departureCoordinate?.takeIf { it.isValid }
            ?: AirportDatabase.coordinate(plan.departure)?.takeIf { it.isValid }

    private fun destinationCoordinate(plan: FlightPlan): Coordinate? =
        plan.destinationCoordinate?.takeIf { it.isValid }
            ?: AirportDatabase.coordinate(plan.destination)?.takeIf { it.isValid }

    /** Departure, located enroute fixes, destination — exactly the line drawn on the map. */
    private fun fullRoutePolyline(plan: FlightPlan): List<Coordinate> = buildList {
        departureCoordinate(plan)?.let { add(it) }
        addAll(plan.waypoints.mapNotNull { it.coordinate }.filter { it.isValid })
        destinationCoordinate(plan)?.let { add(it) }
    }

    /** Bearing to the next un-passed fix, else to the destination, else the heading. */
    private fun currentCourse(inputs: Inputs, position: Coordinate): Double {
        val origin = departureCoordinate(inputs.plan)
        inputs.plan.nextUnpassedWaypoint(position, origin)?.coordinate?.let {
            return Geo.bearing(position, it)
        }
        destinationCoordinate(inputs.plan)?.let { return Geo.bearing(position, it) }
        return inputs.aircraft.heading ?: 0.0
    }

    /**
     * The deepest point a deviation may rejoin the route: at least the airport margin before
     * the field, and never past the first fix of the filed approach when that sits farther
     * out — so a mint line always terminates on the flight path short of the field, and never
     * routes into the approach.
     */
    private fun rejoinCap(plan: FlightPlan, route: List<Coordinate>): Coordinate? {
        if (route.size < 2) return destinationCoordinate(plan)
        var margin = REJOIN_AIRPORT_MARGIN_NM
        plan.approachStartCoordinate?.takeIf { it.isValid }?.let {
            margin = max(margin, RouteGeometry.alongRouteDistanceFromEnd(route, it))
        }
        return RouteGeometry.pointBeforeEndAlongRoute(route, margin) ?: route.last()
    }

    private fun callsign(inputs: Inputs): PhraseologyEngine.Callsign = engineProvider().callsign(
        airline = inputs.plan.airline,
        flightNumber = inputs.plan.flightNumber,
        fallback = inputs.plan.callsign,
    )

    private fun pilot(facility: ATCFacility, display: String, spoken: String) = ATCTransmission.create(
        sender = ATCTransmission.Sender.PILOT,
        facility = facility,
        displayText = display,
        spokenText = spoken,
        timestampMillis = clock.nowMillis(),
    )

    private fun atc(facility: ATCFacility, display: String, spoken: String) = ATCTransmission.create(
        sender = ATCTransmission.Sender.ATC,
        facility = facility,
        displayText = display,
        spokenText = spoken,
        timestampMillis = clock.nowMillis(),
    )

    // endregion

    companion object {
        /** How long the route must test continuously clear before a shown conflict is dropped. */
        const val CLEAR_CONFIRM_WINDOW_MILLIS = 90_000L

        /** Within this of the drawn turn-out, ATC issues the advisory whether asked or not. */
        const val DEVIATION_AUTO_CALL_NM = 15.0

        /** A turn-out farther ahead than this holds the beginning turn rather than working it now. */
        const val DEVIATION_TURN_HOLD_NM = 6.0

        /** Within this of the rejoin, the deviation auto-resumes own navigation. */
        const val AUTO_RESUME_INTERCEPT_NM = 15.0

        /** No mint line begins within this of either airport. */
        const val REJOIN_AIRPORT_MARGIN_NM = 20.0

        /** A closing turn sharper than this is softened by rejoining farther down the route. */
        const val MAX_REJOIN_TURN_DEGREES = 60.0

        /** A vector this far off the current track means the geometry is behind the aircraft. */
        const val MAX_VECTOR_TURN_DEGREES = 135.0

        /** The lead ahead of a system the reroute is solved from, where the route is straight. */
        const val DEVIATION_SOLVE_LEAD_NM = 70.0

        /** How far past an unsolvable system the route walk steps before looking again. */
        const val PREVIEW_SCAN_STEP_NM = 150.0

        /** How far before the next reroute's turn-out a softened rejoin must stop. */
        const val MERGE_REJOIN_MARGIN_NM = 5.0

        /** The walk gives up after this many systems, so it can never run away. */
        const val MAX_PREVIEW_SYSTEMS = 6

        const val ALTITUDE_STEP_FT = 2_000
        const val MINIMUM_ALTITUDE_FT = 4_000
        const val DEFAULT_CRUISE_FT = 37_000
    }
}
