package com.h3consultingpartners.ifatccompanion.core.mock

import com.h3consultingpartners.ifatccompanion.core.airports.AirportDatabase
import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.Waypoint
import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import com.h3consultingpartners.ifatccompanion.core.weather.METAR
import com.h3consultingpartners.ifatccompanion.core.weather.MetarParser
import com.h3consultingpartners.ifatccompanion.core.weather.PIREP
import com.h3consultingpartners.ifatccompanion.core.weather.TAF
import com.h3consultingpartners.ifatccompanion.core.weather.TurbulenceSeverity
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.WeatherIntensity
import com.h3consultingpartners.ifatccompanion.core.weather.radar.RadarCell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic mock flight-data feed so the app is fully demoable without Infinite
 * Flight. Drives a simulated route through each flight phase and ships sample
 * weather + PIREP data.
 *
 * Ported from `IFATCCompanion/Mock/MockSimulatorFeed.swift`, which is a `@MainActor`
 * `ObservableObject` publishing `phase` / `running` and pushing states through an
 * `onState` closure. Here the published values are [StateFlow]s, the 1 Hz tick runs
 * on an injected [CoroutineScope], and the snapshot timestamp comes from an injected
 * [Clock] so a test can drive the whole demo without wall time.
 */
class MockSimulatorFeed(
    val route: Route = defaultRoute(),
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.system,
) {

    /** The scripted demo route: endpoints, cruise level, fixes and the two stands. */
    data class Route(
        val departure: String,
        val destination: String,
        val depCoord: Coordinate,
        val destCoord: Coordinate,
        val cruiseAltitude: Int,
        val waypoints: List<Waypoint>,
        /**
         * Realistic default gate at the origin/destination (a United stand at the mock
         * route's hubs), so the demo taxis from/to a plausible gate. Overridden by any
         * gate the pilot enters.
         */
        val departureGate: String = "",
        val arrivalGate: String = "",
    )

    private val _phase = MutableStateFlow(FlightPhase.PREFLIGHT)
    val phase: StateFlow<FlightPhase> = _phase.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * The latest synthesized snapshot. Seeded with the pre-flight state the Swift's
     * first `emit()` pushes, so a collector attaching before [start] sees the aircraft
     * at the gate rather than nothing.
     */
    private val _state = MutableStateFlow(stateFor(FlightPhase.PREFLIGHT))
    val state: StateFlow<AircraftState> = _state.asStateFlow()

    /**
     * Pushed states — the same closure shape `IFConnectManager.onState` has, so the
     * session coordinator subscribes to the mock feed exactly as it does to the live
     * link.
     */
    var onState: ((AircraftState) -> Unit)? = null

    private var job: Job? = null

    /** 0…1 within the current phase emission. */
    private var phaseProgress: Double = 0.0

    // region Control

    fun start() {
        if (_running.value) return
        _running.value = true
        emit() // push immediately
        job = scope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MILLIS)
                if (!_running.value) break
                phaseProgress = min(1.0, phaseProgress + PHASE_PROGRESS_STEP)
                emit()
            }
        }
    }

    fun stop() {
        _running.value = false
        job?.cancel()
        job = null
    }

    fun setPhase(newPhase: FlightPhase) {
        _phase.value = newPhase
        phaseProgress = 0.0
        emit()
    }

    fun advancePhase() {
        val seq = FlightPhase.demoSequence
        val idx = seq.indexOf(_phase.value)
        if (idx >= 0 && idx + 1 < seq.size) {
            setPhase(seq[idx + 1])
        } else {
            setPhase(seq.firstOrNull() ?: FlightPhase.PREFLIGHT)
        }
    }

    // endregion

    // region State synthesis

    private fun emit() {
        val next = stateFor(_phase.value)
        _state.value = next
        onState?.invoke(next)
    }

    /** Build a plausible aircraft state for a given phase. */
    fun stateFor(phase: FlightPhase): AircraftState {
        val course = Geo.bearing(route.depCoord, route.destCoord)

        fun along(fraction: Double): Coordinate = Coordinate(
            route.depCoord.latitude + (route.destCoord.latitude - route.depCoord.latitude) * fraction,
            route.depCoord.longitude + (route.destCoord.longitude - route.depCoord.longitude) * fraction,
        )

        // Base profile per phase: (fraction-along, altitude, gs, vs, onGround).
        val profile: Profile = when (phase) {
            FlightPhase.PREFLIGHT -> Profile(0.00, 0.0, 0.0, 0.0, true)
            FlightPhase.TAXI_OUT -> Profile(0.00, 0.0, 16.0, 0.0, true)
            FlightPhase.TAKEOFF -> Profile(0.01, 50.0, 150.0, 200.0, true)
            FlightPhase.INITIAL_CLIMB -> Profile(0.04, 4500.0, 240.0, 2600.0, false)
            FlightPhase.CLIMB -> Profile(0.18, 21000.0, 390.0, 1900.0, false)
            FlightPhase.CRUISE -> Profile(0.50, route.cruiseAltitude.toDouble(), 460.0, 0.0, false)
            FlightPhase.DESCENT -> Profile(0.80, 17000.0, 410.0, -1900.0, false)
            FlightPhase.APPROACH -> Profile(0.97, 4000.0, 240.0, -1100.0, false)
            FlightPhase.LANDING -> Profile(1.00, 0.0, 130.0, -300.0, true)
            FlightPhase.TAXI_IN -> Profile(1.00, 0.0, 16.0, 0.0, true)
            FlightPhase.PARKED -> Profile(1.00, 0.0, 0.0, 0.0, true)
            FlightPhase.UNKNOWN -> Profile(0.50, route.cruiseAltitude.toDouble(), 450.0, 0.0, false)
        }

        // Nudge fraction forward slightly across in-phase ticks for liveliness.
        val frac = min(1.0, profile.frac + phaseProgress * IN_PHASE_NUDGE)
        val coord = along(frac)
        return AircraftState(
            latitude = coord.latitude,
            longitude = coord.longitude,
            altitudeMSL = profile.alt,
            altitudeAGL = if (profile.ground) 0.0 else max(0.0, profile.alt - 800),
            groundSpeed = profile.gs,
            indicatedAirspeed = if (profile.ground) profile.gs else max(0.0, profile.gs - 60),
            trueAirspeed = profile.gs,
            heading = course,
            // The mock course is a true bearing, so the true heading matches it (no
            // synthetic magnetic declination in the demo feed).
            trueHeading = course,
            track = course,
            verticalSpeed = profile.vs,
            onGround = profile.ground,
            // Simulate the autopilot approach mode (APPR) being engaged once on the
            // approach, so the companion can issue the "cleared … approach" call.
            approachModeEngaged = (phase == FlightPhase.APPROACH || phase == FlightPhase.LANDING),
            // Parking brake is set at the gate (pre-departure) and once parked after
            // arrival; released any time the aircraft is moving or airborne.
            parkingBrakeSet = (phase == FlightPhase.PREFLIGHT || phase == FlightPhase.PARKED),
            nearestAirport = if (frac < 0.5) route.departure else route.destination,
            nearestAirportDistanceNM = Geo.distanceNM(
                coord,
                if (frac < 0.5) route.depCoord else route.destCoord,
            ),
            aircraftName = "Boeing 737-800",
            liveryName = "United",
            lastUpdateMillis = clock.nowMillis(),
        )
    }

    private data class Profile(
        val frac: Double,
        val alt: Double,
        val gs: Double,
        val vs: Double,
        val ground: Boolean,
    )

    // endregion

    // region Sample weather

    fun sampleMETARs(): List<METAR> = listOfNotNull(
        MetarParser.parseRaw("KIAH 281953Z 16008KT 10SM FEW250 31/21 A3001"),
        MetarParser.parseRaw("KMSP 281953Z 32012KT 10SM BKN025 18/11 A3012"),
        MetarParser.parseRaw("KDEN 281953Z 02015G24KT 10SM SCT080 24/06 A2998"),
    )

    fun sampleTAF(): TAF = TAF(
        icao = route.destination,
        raw = "KMSP 281720Z 2818/2924 32012KT P6SM BKN025 FM290200 31008KT P6SM SCT040",
        issueTimeMillis = null,
        periods = emptyList(),
    )

    /**
     * Sample PIREPs placed along the route at cruise altitude with light/moderate
     * turbulence, so the ride-report feature is demoable offline.
     */
    fun samplePIREPs(): List<PIREP> {
        fun point(fraction: Double) = Coordinate(
            route.depCoord.latitude + (route.destCoord.latitude - route.depCoord.latitude) * fraction,
            route.depCoord.longitude + (route.destCoord.longitude - route.depCoord.longitude) * fraction,
        )
        val now = clock.nowMillis()
        return listOf(
            PIREP(
                raw = "UA /OV KMCI090040 /TM 1945 /FL350 /TP B738 /TB LGT-MOD",
                coordinate = point(0.62), altitudeFt = 35000,
                turbulence = TurbulenceSeverity.MODERATE,
                icing = null, timeMillis = now, aircraftType = "B738",
            ),
            PIREP(
                raw = "UA /OV KOMA /TM 1950 /FL330 /TP A320 /TB LGT CHOP",
                coordinate = point(0.70), altitudeFt = 33000,
                turbulence = TurbulenceSeverity.LIGHT_CHOP,
                icing = null, timeMillis = now, aircraftType = "A320",
            ),
            PIREP(
                raw = "UA /OV KDSM /TM 1955 /FL370 /TP B739 /TB LGT",
                coordinate = point(0.78), altitudeFt = 37000,
                turbulence = TurbulenceSeverity.LIGHT,
                icing = null, timeMillis = now, aircraftType = "B739",
            ),
        )
    }

    /**
     * Deterministic mock precipitation systems along the filed route, so the deviation
     * demo has a conflict to work **and** the strategic preview has several distinct
     * systems to draw. Three systems, all centered on (or crossing) the course line:
     *  - a moderate system early on (~20% along the route),
     *  - the primary heavy system ~40 NM ahead of the cruise point — the one the demo
     *    works as an active deviation (unchanged, so the mock flow stays stable), and
     *  - a heavy system near the arrival (~82% along the route).
     *
     * They're spaced well over a single lookahead apart, so each is a separate deviation
     * (a distinct preview line) rather than one merged reroute — and at cruise only the
     * primary is in range, so the worked-deviation flow is unaffected. Moving east.
     */
    fun sampleRadarCells(): List<RadarCell> {
        val course = Geo.bearing(route.depCoord, route.destCoord)

        fun along(fraction: Double) = Coordinate(
            route.depCoord.latitude + (route.destCoord.latitude - route.depCoord.latitude) * fraction,
            route.depCoord.longitude + (route.destCoord.longitude - route.depCoord.longitude) * fraction,
        )

        fun box(c: Coordinate, half: Double): List<Coordinate> = listOf(
            Coordinate(c.latitude - half, c.longitude - half),
            Coordinate(c.latitude - half, c.longitude + half),
            Coordinate(c.latitude + half, c.longitude + half),
            Coordinate(c.latitude + half, c.longitude - half),
        )

        // Primary system — unchanged from the original single-cell demo (~40 NM ahead of
        // the cruise point, heavy, wide, symmetric about course).
        val primary = Geo.destination(along(0.50), course, 40.0)
        return listOf(
            RadarCell(
                polygon = box(along(0.20), 0.35), intensity = WeatherIntensity.MODERATE,
                movementDirectionDegrees = 90.0, movementSpeedKnots = 15.0,
            ),
            RadarCell(
                polygon = box(primary, 0.55), intensity = WeatherIntensity.HEAVY,
                movementDirectionDegrees = 90.0, movementSpeedKnots = 20.0,
            ),
            RadarCell(
                polygon = box(along(0.82), 0.4), intensity = WeatherIntensity.HEAVY,
                movementDirectionDegrees = 90.0, movementSpeedKnots = 25.0,
            ),
        )
    }

    // endregion

    companion object {

        /** The demo pushes a fresh snapshot once a second, as the live Connect poll does. */
        const val TICK_INTERVAL_MILLIS = 1_000L

        /** Progress added per in-phase tick; ten ticks saturate the phase. */
        const val PHASE_PROGRESS_STEP = 0.1

        /** How much of the route one saturated phase's progress adds (2%). */
        const val IN_PHASE_NUDGE = 0.02

        // region Routes

        fun defaultRoute(): Route {
            val dep = AirportDatabase.coordinate("KIAH") ?: Coordinate(29.98, -95.34)
            val dest = AirportDatabase.coordinate("KMSP") ?: Coordinate(44.88, -93.22)
            return Route(
                departure = "KIAH", destination = "KMSP",
                depCoord = dep, destCoord = dest, cruiseAltitude = 37000,
                waypoints = synthWaypoints(
                    dep, dest,
                    listOf("TBONE", "KMCI", "KOMA", "KDSM", "FARGO"),
                ),
                // United hubs from a United stand: Terminal C at Houston, Concourse C
                // (Terminal 1) at Minneapolis.
                departureGate = "C24", arrivalGate = "C6",
            )
        }

        fun denverRoute(): Route {
            val dep = AirportDatabase.coordinate("KDEN") ?: Coordinate(39.85, -104.67)
            val dest = AirportDatabase.coordinate("KMSP") ?: Coordinate(44.88, -93.22)
            return Route(
                departure = "KDEN", destination = "KMSP",
                depCoord = dep, destCoord = dest, cruiseAltitude = 35000,
                waypoints = synthWaypoints(dep, dest, listOf("AKO", "ONL", "FSD", "REDWG")),
                // United hub concourse B at Denver, Concourse C at Minneapolis.
                departureGate = "B32", arrivalGate = "C6",
            )
        }

        private fun synthWaypoints(
            dep: Coordinate,
            dest: Coordinate,
            names: List<String>,
        ): List<Waypoint> = names.mapIndexed { idx, name ->
            val f = (idx + 1).toDouble() / (names.size + 1).toDouble()
            Waypoint(
                name = name,
                latitude = dep.latitude + (dest.latitude - dep.latitude) * f,
                longitude = dep.longitude + (dest.longitude - dep.longitude) * f,
                altitude = null,
            )
        }

        // endregion
    }
}
