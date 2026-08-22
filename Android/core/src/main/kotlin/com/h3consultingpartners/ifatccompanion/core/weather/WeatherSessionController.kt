package com.h3consultingpartners.ifatccompanion.core.weather

import com.h3consultingpartners.ifatccompanion.core.airports.AirportDatabase
import com.h3consultingpartners.ifatccompanion.core.atis.AirportATIS
import com.h3consultingpartners.ifatccompanion.core.atis.ATISDiagnostics
import com.h3consultingpartners.ifatccompanion.core.atis.ATISService
import com.h3consultingpartners.ifatccompanion.core.atis.ATISPhraseology
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyEngine
import com.h3consultingpartners.ifatccompanion.core.session.WeatherAnswering
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.RideReportEngine
import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.mock.MockSimulatorFeed
import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import com.h3consultingpartners.ifatccompanion.core.settings.NOAARadarOverlayMode
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.RideAssessment
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.TurbulenceModel
import com.h3consultingpartners.ifatccompanion.core.weather.radar.MapRegion
import com.h3consultingpartners.ifatccompanion.core.weather.radar.PrecipitationOverlayService
import com.h3consultingpartners.ifatccompanion.core.weather.radar.RadarCell
import com.h3consultingpartners.ifatccompanion.core.weather.radar.RadarOverlayModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Everything the Weather tab and the weather-aware ATC calls read.
 *
 * On iOS this is roughly forty `@Published` properties on `AppModel`; here it is one
 * immutable snapshot behind a [StateFlow], the same shape the flight session uses.
 */
data class WeatherSessionState(
    val departureMetar: METAR? = null,
    val destinationMetar: METAR? = null,
    val alternateMetar: METAR? = null,
    val destinationTaf: TAF? = null,
    val pireps: List<PIREP> = emptyList(),
    val sigmets: List<SIGMET> = emptyList(),
    /** Advisories whose area lies along the route — the only ones that raise the ride index. */
    val routeSigmets: List<SIGMET> = emptyList(),
    val rideReportItems: List<RideReportItem> = emptyList(),
    val rideAssessment: RideAssessment = RideAssessment.smooth,
    /** A reachable level with a smoother reported ride, or null when no report supports one. */
    val suggestedSmootherAltitude: SmootherAltitude? = null,
    val radarOverlay: RadarOverlayModel = RadarOverlayModel(),
    val departureAtis: AirportATIS? = null,
    val arrivalAtis: AirportATIS? = null,
    val atisDiagnostics: ATISDiagnostics = ATISDiagnostics(),
    val status: String = "Not loaded",
    val lastUpdateMillis: Long? = null,
)

/**
 * Owns the weather half of the iOS `AppModel`: the aviation-weather fetch, the ride-report
 * and turbulence recomputation, the D-ATIS refresh, and the descriptive radar-overlay
 * state the Weather tab renders.
 *
 * Ported from the weather sections of `IFATCCompanion/App/AppModel.swift`
 * (`refreshWeather`, `recomputeRideItems`, `refreshATIS`, `pirepBoundingBox` and the
 * radar-overlay descriptor). It is deliberately a separate object from
 * `FlightSessionCoordinator` rather than one god-object: the ATC state machine and the
 * weather feed share almost nothing but the flight plan, and splitting them is what makes
 * each testable on its own.
 *
 * The route-conflict/deviation solver is **not** driven from here yet — see
 * Docs/ANDROID_PARITY_MATRIX.md.
 */
class WeatherSessionController(
    private val weatherService: AviationWeatherService,
    private val atisService: ATISService,
    private val overlayService: PrecipitationOverlayService,
    private val airports: AirportDatabase = AirportDatabase,
    private val clock: Clock = Clock.system,
    private val diagnostics: DiagnosticsSink = DiagnosticsSink.noop,
    private val mock: MockSimulatorFeed? = null,
    private val settingsProvider: () -> AppSettings = { AppSettings() },
    /**
     * The phraseology in force, read per call so the ride read-out follows the pilot's
     * digit style and pack like every other line rather than a snapshot taken at
     * construction.
     */
    private val phraseologyProvider: () -> PhraseologyEngine = { PhraseologyEngine() },
) : WeatherAnswering {

    private val _state = MutableStateFlow(WeatherSessionState())
    val state: StateFlow<WeatherSessionState> = _state.asStateFlow()

    private val routeAnalyzer = WeatherRouteAnalyzer(clock)
    private val turbulenceModel = TurbulenceModel()

    private val settings: AppSettings get() = settingsProvider()

    /** The last flight context handed in, so a recompute can run without a fresh fetch. */
    private var flightPlan: FlightPlan = FlightPlan.empty
    private var aircraftState: AircraftState = AircraftState.empty
    private var phase: FlightPhase = FlightPhase.PREFLIGHT

    /**
     * Hand in the current flight context. Cheap and idempotent — call it on every
     * telemetry tick; it only recomputes the derived ride state, never fetches.
     */
    fun updateFlightContext(plan: FlightPlan, aircraft: AircraftState, phase: FlightPhase) {
        val routeChanged = plan.departure != flightPlan.departure ||
            plan.destination != flightPlan.destination ||
            plan.alternate != flightPlan.alternate
        flightPlan = plan
        aircraftState = aircraft
        this.phase = phase
        recomputeRideItems()
        if (routeChanged) refreshOverlayDescriptor()
    }

    // region Refresh

    /**
     * Reload every weather product, then recompute the derived ride state against it.
     *
     * ATIS is refreshed on the same cadence but is **independent** of the weather fetch:
     * a failed METAR pull must not also silence the ATIS a pilot is about to tune.
     */
    suspend fun refresh() {
        if (settings.mockMode) {
            refreshMock()
            return
        }

        // Live mode: no vector precipitation cells (radar is the provider's image overlay).
        _state.update { it.copy(radarOverlay = it.radarOverlay.copy(mockCells = emptyList())) }

        try {
            val ids = buildList {
                for (id in listOf(flightPlan.departure, flightPlan.destination, flightPlan.alternate)) {
                    if (id.isNotEmpty()) add(id)
                }
                aircraftState.nearestAirport?.takeIf { it.isNotEmpty() }?.let { add(it) }
            }
            val metars = weatherService.metars(ids)
            val taf = runCatching { weatherService.taf(flightPlan.destination) }.getOrNull()
            // The AWC pirep endpoint requires a bounding box (else HTTP 400), so query the
            // route/area box and let `relevantReports` narrow it to the corridor afterwards.
            val pireps = pirepBoundingBox()
                ?.let { runCatching { weatherService.pireps(it) }.getOrNull() }
                ?: emptyList()
            val sigmets = runCatching { weatherService.airSigmets() }.getOrNull() ?: emptyList()

            _state.update {
                it.copy(
                    departureMetar = metars.firstOrNull { m -> m.icao == flightPlan.departure },
                    destinationMetar = metars.firstOrNull { m -> m.icao == flightPlan.destination },
                    alternateMetar = metars.firstOrNull { m -> m.icao == flightPlan.alternate },
                    destinationTaf = taf,
                    pireps = pireps,
                    sigmets = sigmets,
                    lastUpdateMillis = clock.nowMillis(),
                    status = "Loaded ${metars.size} METARs, ${pireps.size} PIREPs, ${sigmets.size} SIGMETs.",
                )
            }
            recomputeRideItems()
            refreshOverlayDescriptor()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val message = error.message ?: error.toString()
            _state.update { it.copy(status = "Weather unavailable: $message") }
            diagnostics.log(DiagnosticCategory.WEATHER, message = "Weather fetch failed: $message")
            refreshOverlayDescriptor()
        }
        refreshAtis()
    }

    private suspend fun refreshMock() {
        val feed = mock
        if (feed == null) {
            _state.update { it.copy(status = "Mock weather unavailable — no mock feed.") }
            return
        }
        val metars = feed.sampleMETARs()
        val pireps = feed.samplePIREPs()
        _state.update {
            it.copy(
                departureMetar = metars.firstOrNull { m -> m.icao == flightPlan.departure } ?: metars.firstOrNull(),
                destinationMetar = metars.firstOrNull { m -> m.icao == flightPlan.destination }
                    ?: metars.drop(1).firstOrNull(),
                destinationTaf = feed.sampleTAF(),
                pireps = pireps,
                // Mock precipitation cells (crossing the route ahead) drive the offline
                // deviation demo. Live radar sampling is Mock-Mode-off.
                radarOverlay = it.radarOverlay.copy(
                    mockCells = feed.sampleRadarCells(),
                    sampledCells = emptyList(),
                ),
                lastUpdateMillis = clock.nowMillis(),
                status = "Mock weather loaded (${metars.size} METARs, ${pireps.size} PIREPs).",
            )
        }
        recomputeRideItems()
        refreshOverlayDescriptor()
        refreshAtis()
    }

    // endregion

    // region Ride reports

    /**
     * Recompute the ride reports, the route advisories and the ride index from whatever
     * weather is currently loaded. Pure with respect to the network — safe to call on
     * every telemetry tick.
     */
    fun recomputeRideItems() {
        routeAnalyzer.config.corridorNM = settings.routeCorridorNM
        routeAnalyzer.config.altitudeBandFt = settings.altitudeBandFt

        // Distance ahead must be measured from the live aircraft fix. Fall back to the
        // departure only so PIREPs can still be filtered to the route corridor — in that
        // case the distance would be origin-relative, so it is flagged and not shown.
        val liveAircraft = aircraftState.coordinate
        val position = liveAircraft ?: resolvedDepartureCoordinate()
        if (position == null) {
            _state.update { it.copy(rideReportItems = emptyList(), routeSigmets = emptyList()) }
            return
        }

        val end = resolvedDestinationCoordinate() ?: flightPlan.nextWaypoint(position)?.coordinate
        val liveAltitude = aircraftState.altitudeMSL ?: flightPlan.cruiseAltitude.toDouble()
        // Ride reports describe the cruise portion of the route ahead, so evaluate a
        // PIREP's altitude relevance against the planned cruise level (within the
        // ±tolerance band) rather than the live altitude — otherwise en-route turbulence
        // at cruise is filtered out while the aircraft is still climbing. Fall back to the
        // live altitude before a cruise level is set.
        val referenceAltitude =
            if (flightPlan.cruiseAltitude > 0) flightPlan.cruiseAltitude.toDouble() else liveAltitude

        val current = _state.value
        val items = routeAnalyzer.relevantReports(
            pireps = current.pireps,
            position = position,
            routeEnd = end,
            altitudeFt = referenceAltitude,
            routeFixes = routeNamedFixes(),
            positionIsLiveAircraft = liveAircraft != null,
        )

        val arrivalPhase = phase in ARRIVAL_PHASES
        val nearMetar = if (arrivalPhase) {
            current.destinationMetar ?: current.departureMetar
        } else {
            current.departureMetar ?: current.destinationMetar
        }

        // Only SIGMETs whose area lies along the route may raise the ride index — a
        // nationwide turbulence advisory far from the route must not read as "severe".
        // Test the full route ahead (aircraft → remaining fixes → destination) so an
        // advisory on a later leg past a turn is caught, not just one on the straight line
        // to the destination.
        val routePolyline = listOf(position) + upcomingRouteCoordinates(position)
        val routeSigmets = routeAnalyzer.relevantSigmets(current.sigmets, routePolyline)

        // Wind shear is a low-level, surface-driven effect, so it keys off the live
        // altitude; the PIREP altitude band above keys off the cruise reference.
        val assessment = turbulenceModel.assess(
            items = items,
            sigmets = routeSigmets,
            metar = nearMetar,
            altitudeFt = liveAltitude,
        )

        _state.update {
            it.copy(
                rideReportItems = items,
                routeSigmets = routeSigmets,
                rideAssessment = assessment,
            )
        }
    }

    /**
     * The altitude ride reports are evaluated against: the filed cruise level, or the live
     * altitude before one is set (matching [recomputeRideItems]).
     */
    fun rideReferenceAltitudeFt(): Int =
        if (flightPlan.cruiseAltitude > 0) {
            flightPlan.cruiseAltitude
        } else {
            aircraftState.altitudeMSL?.toInt() ?: 0
        }

    /**
     * A data-backed smoother altitude from PIREPs at *other* levels along the route,
     * bounded to the commercial cruise band. Null when nothing supports one — it never
     * invents a smooth level with no report behind it.
     *
     * Computed on demand rather than on every recompute, matching iOS: the suggestion is a
     * one-shot hint attached to a ride report the pilot asked for, and recomputing it every
     * telemetry tick would make the accept button appear and vanish on its own.
     */
    fun computeSmootherAltitude(referenceAltFt: Int = rideReferenceAltitudeFt()): SmootherAltitude? {
        val liveAircraft = aircraftState.coordinate
        val position = liveAircraft ?: resolvedDepartureCoordinate() ?: return null
        val end = resolvedDestinationCoordinate() ?: flightPlan.nextWaypoint(position)?.coordinate
        // All route-corridor PIREPs regardless of altitude — the ±band filter would hide
        // the very levels a smoother-ride suggestion is drawn from. This path reads only
        // each item's altitude/severity, so no route-fix labelling is needed.
        val allItems = routeAnalyzer.relevantReports(
            pireps = _state.value.pireps,
            position = position,
            routeEnd = end,
            altitudeFt = referenceAltFt.toDouble(),
            ignoreAltitudeBand = true,
            positionIsLiveAircraft = liveAircraft != null,
        )
        val currentSeverity =
            _state.value.rideReportItems.maxOfOrNull { it.severity } ?: TurbulenceSeverity.SMOOTH
        return routeAnalyzer.smootherAltitude(
            items = allItems,
            referenceAltFt = referenceAltFt,
            currentSeverity = currentSeverity,
        )
    }

    /** Remember a suggestion so the accept button appears and the next request targets it. */
    fun noteSmootherAltitude(suggestion: SmootherAltitude?) {
        _state.update { it.copy(suggestedSmootherAltitude = suggestion) }
    }

    /** Clear the one-shot hint from the last ride report. */
    override fun clearSmootherAltitude() = noteSmootherAltitude(null)

    // endregion

    // region Answering the controller

    /**
     * Center's read-out of the ride along the route, composed from freshly-fetched reports.
     *
     * The whole sequence is here rather than in the flight session because every input is
     * this object's: the PIREP pool, the corridor analysis, the reference altitude and the
     * smoother-level search. The session asks and posts; this decides what the answer says.
     */
    override suspend fun rideReport(callsign: PhraseologyEngine.Callsign): ATCTransmission {
        refresh()
        recomputeRideItems()
        val reference = rideReferenceAltitudeFt()
        // Remembered, not just returned: the suggestion is what makes the accept button
        // appear and what the next plain higher/lower request is aimed at.
        val smoother = computeSmootherAltitude(reference)
        noteSmootherAltitude(smoother)
        val snapshot = _state.value
        return RideReportEngine(phraseologyProvider()).rideReport(
            assessment = snapshot.rideAssessment,
            items = snapshot.rideReportItems,
            referenceAltitudeFt = reference,
            smoother = smoother,
            callsign = callsign,
        )
    }

    override suspend fun destinationWeather(
        callsign: PhraseologyEngine.Callsign,
        icao: String,
    ): ATCTransmission {
        refresh()
        return RideReportEngine(phraseologyProvider()).destinationWeather(
            metar = _state.value.destinationMetar,
            callsign = callsign,
            icaoCode = icao,
        )
    }

    override fun smootherAltitude(): SmootherAltitude? = _state.value.suggestedSmootherAltitude

    override fun metar(arriving: Boolean): METAR? =
        if (arriving) _state.value.destinationMetar else _state.value.departureMetar

    override fun radarOverlay(): RadarOverlayModel = _state.value.radarOverlay

    /**
     * Adopt the precipitation cells a live radar sample produced.
     *
     * The one writer of `sampledCells`, which until now was only ever set to the empty
     * list — so the whole deviation flow had no cells to run on outside Mock Mode, the
     * Diagnostics "Sampled cells" row always read zero, and the sampled-cell map layer never
     * drew anything. An empty result is ignored rather than adopted: a fetch or decode
     * failure must not blank a good sample, which is the difference between "the radar
     * hiccuped" and "the storms went away".
     */
    fun noteSampledCells(cells: List<RadarCell>) {
        if (cells.isEmpty()) return
        _state.update { it.copy(radarOverlay = it.radarOverlay.copy(sampledCells = cells)) }
    }

    /**
     * The corridor a live radar sample covers: the aircraft and the route still ahead,
     * widened so weather whose body sits off the centreline — but whose edge crosses the
     * route — is still captured.
     */
    fun precipitationSampleRegion(padNM: Double = CORRIDOR_PAD_NM): MapRegion? {
        val region = precipitationRegion() ?: return null
        val padLatitude = padNM / 60.0
        val padLongitude = padNM / (60.0 * max(0.2, cos(region.centerLatitude * PI / 180)))
        return region.copy(
            latitudeDelta = region.latitudeDelta + 2 * padLatitude,
            longitudeDelta = region.longitudeDelta + 2 * padLongitude,
        )
    }

    override fun routeSigmets(): List<SIGMET> = _state.value.routeSigmets

    /**
     * A reported ride of moderate or worse covering the target band is what makes a
     * controller refuse a climb into it — the one case where "unable higher" is the more
     * useful answer than granting the request.
     */
    override fun altitudeIsBlockedByRideReports(altitudeFt: Int): Boolean =
        _state.value.rideReportItems.any {
            it.severity >= TurbulenceSeverity.MODERATE && it.altitudeBand?.contains(altitudeFt) == true
        }

    // endregion

    // region ATIS

    /**
     * Refresh D-ATIS for the departure and — once within range — the arrival field.
     *
     * A field with no published D-ATIS simply returns nothing and the feature disappears
     * for it: no button, no information code appended to any call. Nothing is fabricated.
     */
    suspend fun refreshAtis() {
        val departureId = flightPlan.departure.trim().uppercase()
        val arrivalId = flightPlan.destination.trim().uppercase()
        val withinArrivalRange = withinArrivalAtisRange()

        val departure = departureId.takeIf { it.isNotEmpty() }
            ?.let { runCatching { atisService.atis(it) }.getOrNull() }
        val arrival = arrivalId.takeIf { it.isNotEmpty() && withinArrivalRange }
            ?.let { runCatching { atisService.atis(it) }.getOrNull() }

        _state.update { current ->
            current.copy(
                departureAtis = departure ?: current.departureAtis.takeIf { departureId.isNotEmpty() },
                arrivalAtis = arrival ?: current.arrivalAtis.takeIf { arrivalId.isNotEmpty() },
                atisDiagnostics = current.atisDiagnostics.copy(
                    departureAirport = departureId,
                    departureReceived = departure != null,
                    departureLetter = departure?.letter(arrival = false),
                    arrivalAirport = arrivalId,
                    arrivalReceived = arrival != null,
                    arrivalLetter = arrival?.letter(arrival = true),
                    withinArrivalRange = withinArrivalRange,
                ),
            )
        }
    }

    /**
     * Record the information code the pilot has actually received by tuning ATIS, per
     * phase. Until they tune, no code is reported to ATC — the app never claims the pilot
     * has information it only fetched in the background.
     */
    fun noteAtisTuned(arrival: Boolean) {
        val atis = if (arrival) _state.value.arrivalAtis else _state.value.departureAtis
        val letter = atis?.letter(arrival) ?: return
        // Re-tuning re-arms the report: the letter may have changed, and a pilot who tunes
        // again before calling has the newer one to give.
        if (arrival) arrivalInfoReported = false else departureInfoReported = false
        _state.update {
            it.copy(
                atisDiagnostics = if (arrival) {
                    it.atisDiagnostics.copy(reportedArrival = letter)
                } else {
                    it.atisDiagnostics.copy(reportedDeparture = letter)
                },
            )
        }
    }

    /**
     * Whether the code for each leg has already gone out on the radio. Not part of the
     * published state: nothing renders it, and it is the answer to "have I said this yet",
     * which only [atisInfoWord] asks.
     */
    private var departureInfoReported = false
    private var arrivalInfoReported = false

    /**
     * The phonetic information word for this leg — once.
     *
     * Only a code the pilot actually *tuned* is ever reported; a report fetched in the
     * background is not information the pilot has. Marks the leg reported, so the second
     * taxi request or check-in is bare rather than claiming the code twice.
     */
    override fun atisInfoWord(arriving: Boolean): String? {
        if (arriving) {
            if (arrivalInfoReported) return null
            val letter = _state.value.atisDiagnostics.reportedArrival ?: return null
            arrivalInfoReported = true
            return ATISPhraseology.phoneticLetter(letter)
        }
        if (departureInfoReported) return null
        val letter = _state.value.atisDiagnostics.reportedDeparture ?: return null
        departureInfoReported = true
        return ATISPhraseology.phoneticLetter(letter)
    }

    /** Within [ARRIVAL_ATIS_RANGE_NM] of the destination, the arrival ATIS becomes relevant. */
    private fun withinArrivalAtisRange(): Boolean {
        val position = aircraftState.coordinate ?: return false
        val destination = resolvedDestinationCoordinate() ?: return false
        return Geo.distanceNM(position, destination) <= ARRIVAL_ATIS_RANGE_NM
    }

    // endregion

    // region Radar overlay

    /**
     * Recompute the descriptive overlay state for the current route: which provider covers
     * it, what to label the layer, and whether to say so at all. The image itself is
     * fetched by the app from [PrecipitationOverlayService]; this only describes it.
     */
    fun refreshOverlayDescriptor() {
        val enabled = settings.noaaRadarOverlay == NOAARadarOverlayMode.AUTO_WHERE_AVAILABLE
        overlayService.useMockProvider(settings.mockMode)
        val region = precipitationRegion()
        val provider = region?.let { overlayService.selectedProvider(it) }

        _state.update { current ->
            val previous = current.radarOverlay
            current.copy(
                radarOverlay = if (provider == null) {
                    previous.copy(
                        isEnabled = enabled,
                        coverageAvailable = false,
                        opacity = settings.radarOpacity,
                        lastUpdatedMillis = overlayService.lastUpdateMillis,
                    )
                } else {
                    previous.copy(
                        isEnabled = enabled,
                        coverageAvailable = true,
                        opacity = settings.radarOpacity,
                        lastUpdatedMillis = overlayService.lastUpdateMillis,
                        sourceDescription = provider.displayName,
                        attributionText = provider.attributionText,
                        coverageLabel = provider.coverageDescription,
                        layerType = provider.layerType,
                        layerLabel = provider.uiLayerLabel,
                    )
                },
            )
        }
    }

    /**
     * The single region both the sampler and the Source/Layer labels select from.
     *
     * It is deliberately built from the coordinates that actually matter *now* — the
     * aircraft and the remaining route — rather than everything the flight plan mentions.
     * Coverage is a bounding-box overlap, so folding a fixed point (the filed departure)
     * into the box pins the selection there for a whole flight: KIAH→EGLL stayed on NOAA
     * gate to gate and labelled the NASA satellite estimate over England as radar.
     */
    fun precipitationRegion(): MapRegion? {
        val position = aircraftState.coordinate ?: resolvedDepartureCoordinate()
        val coordinates = buildList {
            position?.let { add(it) }
            position?.let { addAll(upcomingRouteCoordinates(it)) }
            if (isEmpty()) {
                resolvedDepartureCoordinate()?.let { add(it) }
                resolvedDestinationCoordinate()?.let { add(it) }
            }
        }.filter { it.isValid }
        return MapRegion.enclosing(coordinates)
    }

    // endregion

    // region Route geometry

    private fun resolvedDepartureCoordinate(): Coordinate? =
        flightPlan.waypoints.firstOrNull { it.name.equals(flightPlan.departure, ignoreCase = true) }?.coordinate
            ?: airports.coordinate(flightPlan.departure)

    private fun resolvedDestinationCoordinate(): Coordinate? =
        flightPlan.waypoints.lastOrNull { it.name.equals(flightPlan.destination, ignoreCase = true) }?.coordinate
            ?: airports.coordinate(flightPlan.destination)

    /** Route fixes with known positions, for labelling each PIREP with its nearest fix. */
    private fun routeNamedFixes(): List<WeatherRouteAnalyzer.NamedFix> =
        flightPlan.waypoints.mapNotNull { waypoint ->
            val coordinate = waypoint.coordinate ?: return@mapNotNull null
            if (!coordinate.isValid) return@mapNotNull null
            WeatherRouteAnalyzer.NamedFix(waypoint.name, coordinate)
        }

    /**
     * The remaining route from [position] to the destination, so an advisory on a later
     * leg past a turn is caught rather than only one on the straight line ahead.
     */
    private fun upcomingRouteCoordinates(position: Coordinate): List<Coordinate> {
        val fixes = flightPlan.waypoints.mapNotNull { it.coordinate?.takeIf { c -> c.isValid } }
        if (fixes.isEmpty()) return listOfNotNull(resolvedDestinationCoordinate())
        // Start from the fix nearest ahead of the aircraft: the plan's own order is the
        // route order, so everything from that index on is what is still to be flown.
        val nextIndex = fixes.indices.minByOrNull { Geo.distanceNM(position, fixes[it]) } ?: 0
        val remaining = fixes.drop(nextIndex)
        val destination = resolvedDestinationCoordinate()
        return if (destination != null && remaining.lastOrNull() != destination) {
            remaining + destination
        } else {
            remaining
        }
    }

    /**
     * A padded bounding box enclosing the aircraft, the route endpoints and the nearest
     * field, as `minLat,minLon,maxLat,maxLon` — the AWC PIREP endpoint requires one.
     */
    fun pirepBoundingBox(padDegrees: Double = PIREP_BBOX_PAD_DEGREES): String? {
        val coordinates = buildList {
            aircraftState.coordinate?.takeIf { it.isValid }?.let { add(it) }
            for (coordinate in listOf(
                resolvedDepartureCoordinate(),
                resolvedDestinationCoordinate(),
                airports.coordinate(flightPlan.alternate),
            )) {
                if (coordinate != null && coordinate.isValid) add(coordinate)
            }
            aircraftState.nearestAirport
                ?.let { airports.coordinate(it) }
                ?.takeIf { it.isValid }
                ?.let { add(it) }
        }
        if (coordinates.isEmpty()) return null
        val minLat = max(-90.0, coordinates.minOf { it.latitude } - padDegrees)
        val maxLat = min(90.0, coordinates.maxOf { it.latitude } + padDegrees)
        val minLon = max(-180.0, coordinates.minOf { it.longitude } - padDegrees)
        val maxLon = min(180.0, coordinates.maxOf { it.longitude } + padDegrees)
        return "%.3f,%.3f,%.3f,%.3f".format(minLat, minLon, maxLat, maxLon)
    }

    // endregion

    companion object {
        /**
         * How far past the route the sampled corridor reaches, so weather whose body sits
         * off the centreline but whose edge crosses the route is still captured.
         */
        const val CORRIDOR_PAD_NM = 60.0

        /** Beyond this the arrival field's ATIS is not yet relevant, so it isn't fetched. */
        const val ARRIVAL_ATIS_RANGE_NM = 100.0

        const val PIREP_BBOX_PAD_DEGREES = 2.0

        private val ARRIVAL_PHASES = setOf(
            FlightPhase.DESCENT,
            FlightPhase.APPROACH,
            FlightPhase.LANDING,
            FlightPhase.TAXI_IN,
            FlightPhase.PARKED,
        )
    }
}
