package com.h3consultingpartners.ifatccompanion.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.h3consultingpartners.ifatccompanion.AppGraph
import com.h3consultingpartners.ifatccompanion.core.airports.AirportDatabase
import com.h3consultingpartners.ifatccompanion.core.billing.EntitlementState
import com.h3consultingpartners.ifatccompanion.core.billing.SubscriptionProduct
import com.h3consultingpartners.ifatccompanion.core.config.AppConfig
import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.persistence.SavedFlight
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyProfile
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticLevel
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticRecord
import com.h3consultingpartners.ifatccompanion.core.review.ReviewRequestManager
import com.h3consultingpartners.ifatccompanion.core.session.FlightSessionCoordinator
import com.h3consultingpartners.ifatccompanion.core.session.FlightSessionState
import com.h3consultingpartners.ifatccompanion.core.session.PilotAction
import com.h3consultingpartners.ifatccompanion.core.session.PilotActionPresentation
import com.h3consultingpartners.ifatccompanion.core.session.WeatherDeviationAction
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import com.h3consultingpartners.ifatccompanion.core.settings.NOAARadarOverlayMode
import com.h3consultingpartners.ifatccompanion.core.settings.SettingsRepository
import com.h3consultingpartners.ifatccompanion.core.surface.routing.AirportSurfaceState
import com.h3consultingpartners.ifatccompanion.core.surface.routing.TaxiMapAction
import com.h3consultingpartners.ifatccompanion.core.weather.WeatherSessionState
import com.h3consultingpartners.ifatccompanion.core.weather.radar.MapRegion
import com.h3consultingpartners.ifatccompanion.core.weather.radar.PixelSize
import com.h3consultingpartners.ifatccompanion.map.BaseMapImageryLoader
import com.h3consultingpartners.ifatccompanion.ui.map.BaseMapModel
import com.h3consultingpartners.ifatccompanion.ui.map.RadarRaster
import com.h3consultingpartners.ifatccompanion.ui.screens.FlightOverrides
import com.h3consultingpartners.ifatccompanion.ui.screens.VoiceOption
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The bridge between the flight session and Compose.
 *
 * It deliberately holds no logic of its own: the engine decides, and this exposes what it
 * decided and forwards what the pilot did. Anything that looks like a rule belongs in
 * `:core`, where it can be tested — that is the whole reason the engine is a separate
 * module. What does live here is *draft* state: the text a pilot is part-way through
 * typing, which is not a fact about the flight until they commit it.
 */
class FlightViewModel(
    private val graph: AppGraph,
    private val coordinator: FlightSessionCoordinator,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val session: StateFlow<FlightSessionState> = coordinator.state

    val settings: StateFlow<AppSettings> = settingsRepository.state

    val weatherState: StateFlow<WeatherSessionState> = graph.weather.state

    val phraseologyProfilesState = graph.phraseologyProfiles.state

    val diagnosticsLog: StateFlow<List<DiagnosticRecord>> = graph.diagnostics.records

    /**
     * Everything on screen that is *not* a fact about the flight: text a pilot is part-way
     * through typing, which sheet is open, whether the microphone is listening.
     *
     * One snapshot rather than a dozen separate flows, so a screen collects once and every
     * field it reads recomposes together. The engine's state stays where it belongs — in
     * [session] — and never mixes with this.
     */
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    data class UiState(
        val draftCallsign: String = "",
        val draftDepartureGate: String = "",
        val draftArrivalGate: String = "",
        val overrides: FlightOverrides = FlightOverrides(),
        val editingProfile: PhraseologyProfile? = null,
        val profileImportFailed: Boolean = false,
        val showSampledRadarCells: Boolean = false,
        /** Set when the ATC tab's subscribe banner is tapped; the shell consumes it. */
        val subscriptionRequested: Boolean = false,
        val microphoneDenied: Boolean = false,
        val isListening: Boolean = false,
        val speechPartial: String = "",
        val lastSpokenText: String = "",
        val lastSpokenIntentTitle: String? = null,
    )

    private inline fun updateUi(transform: (UiState) -> UiState) {
        _ui.value = transform(_ui.value)
    }

    init {
        // Keep the weather engine's view of the flight current. It only recomputes derived
        // ride state on each tick — the network fetch is on its own cadence — so this is
        // cheap enough to run on every telemetry update.
        viewModelScope.launch {
            session.collect { state ->
                graph.weather.updateFlightContext(state.flightPlan, state.aircraftState, state.phase)
            }
        }

        observeTaxi()

        observeBaseMapImagery()

        observeRadarRaster()

        observePrecipitationSampling()

        observeAutoSave()

        observeReviewMoments()

        // Load the airport surface whenever the flight's endpoints change.
        //
        // Until now SurfaceSessionController.refresh had exactly one caller in the whole
        // app — the "Refresh airport data" row in Settings. A pilot who never opened
        // Settings never loaded any surface data, so the taxi map read "Taxi route
        // pending." from launch to landing. Keyed on the pair so a 1 Hz telemetry tick
        // does not re-trigger it; the provider caches and de-duplicates in flight.
        viewModelScope.launch {
            session
                .map { it.flightPlan.departure to it.flightPlan.destination }
                .distinctUntilChanged()
                .collect { (departure, destination) ->
                    if (departure.isBlank() && destination.isBlank()) return@collect
                    graph.surface.refresh(session.value.flightPlan)
                    // Weather had the same problem and worse: refresh() was reachable only
                    // from a control that was never built, so the whole weather engine —
                    // METARs, TAF, ride assessment, SIGMETs — was never given any data.
                    graph.weather.refresh()
                    graph.weather.recomputeRideItems()
                    // Both fields' stands are now knowable, so fill any blank gate from the
                    // airport's own stand data — and drop one assigned at the airport this
                    // flight has just stopped flying to.
                    graph.autoGates.assignIfNeeded()
                }
        }

        // The moment the aircraft comes to rest is the moment its stand can be read off its
        // position rather than guessed, and it is also where a failed extract read gets its
        // retry. Cheap: everything below the transition is a map lookup.
        viewModelScope.launch {
            session
                .map { it.aircraftState }
                .distinctUntilChanged()
                .collect { graph.autoGates.onTelemetry(it) }
        }
    }

    // region Base map

    private val _baseMap = MutableStateFlow(BaseMapModel())

    /**
     * What sits under the route line: bundled coastlines and a graticule always, plus
     * satellite imagery when it can be fetched.
     *
     * The two halves are deliberately unequal. Coastlines are packaged with the app and
     * the graticule is arithmetic, so they are on from the first frame with no network at
     * all — which is the case that matters, because a pilot with the app open at altitude
     * often has no signal. Imagery is an enhancement, and its absence is the ordinary
     * offline state rather than an error the pilot is told about.
     */
    val baseMap: StateFlow<BaseMapModel> = _baseMap.asStateFlow()

    /**
     * Fetch one image per route rather than one per pan.
     *
     * The window is padded well beyond the route (see `BaseMapWindow`), so ordinary
     * panning and zooming stay inside what was already fetched, and the layer is a static
     * Blue Marble image with no time dimension, so there is nothing to keep current. That
     * makes a single request per route the right cadence rather than a compromise —
     * chasing the camera would put the map on the network for the whole flight.
     *
     * "One image per route" is not the same as "one attempt per route". A pilot who opens
     * the app at the gate on a hotspot that has not associated yet, or in the seconds
     * before mobile data attaches, gets a transport failure — and because the upstream is
     * deduplicated on the route itself, nothing would ever ask again for a plan that does
     * not change for three hours. So an unavailable answer is retried on a widening delay
     * and then given up on; a refused one is not retried at all, because a request the
     * service rejects will be rejected again.
     */
    private fun observeBaseMapImagery() {
        viewModelScope.launch {
            session
                .map(::baseMapCoverage)
                // Dropped rather than collected as an empty list. With no plan filed the
                // coverage is the aircraft position, and a single failed telemetry read
                // makes that null — which under collectLatest would cancel an attempt that
                // was mid-retry and hand the next one a fresh budget, so the bound below
                // would never actually be reached on a flaky link.
                .filter { it.isNotEmpty() }
                .distinctUntilChanged()
                .collectLatest(::loadBaseMapImagery)
        }
    }

    /**
     * Attempt imagery for [coordinates] until it lands, is refused, or the attempts run out.
     *
     * Cancellation is the point of running this inside `collectLatest`: a new route
     * cancels a pending retry mid-delay rather than racing the fetch for the new one.
     */
    private suspend fun loadBaseMapImagery(coordinates: List<Coordinate>) {
        var attempt = 0
        while (true) {
            when (val outcome = graph.baseMapImagery.load(coordinates)) {
                is BaseMapImageryLoader.Outcome.Loaded -> {
                    _baseMap.value = _baseMap.value.copy(
                        imagery = outcome.imagery.image,
                        imageryBounds = outcome.imagery.bounds,
                    )
                    return
                }

                is BaseMapImageryLoader.Outcome.Rejected ->
                    if (!outcome.retryable) return

                BaseMapImageryLoader.Outcome.Unavailable -> Unit
            }

            attempt++
            if (attempt >= BASE_MAP_IMAGERY_ATTEMPTS) {
                // One line, once, at the end. Enough that a pilot who wonders why the map
                // has no imagery can find out, without narrating an ordinary offline flight.
                graph.diagnostics.log(
                    category = DiagnosticCategory.WEATHER,
                    level = DiagnosticLevel.INFO,
                    message = "Base map imagery unavailable after $attempt attempts; " +
                        "coastlines and grid are unaffected",
                )
                return
            }
            delay(BASE_MAP_IMAGERY_RETRY_DELAYS_SECONDS[attempt - 1].seconds)
        }
    }

    /**
     * The coordinates the imagery has to cover, coarse enough that a 1 Hz telemetry tick
     * does not re-request an identical image.
     *
     * The filed route is preferred because it does not move. Only when nothing is filed
     * does this fall back to the aircraft itself, and then rounded to a whole degree — the
     * window is at least two degrees across, so the aircraft cannot land outside its own
     * imagery, and free flight refetches every degree crossed rather than every second.
     */
    private fun baseMapCoverage(state: FlightSessionState): List<Coordinate> {
        val plan = state.flightPlan
        val planned = buildList {
            AirportDatabase.coordinate(plan.departure)?.let(::add)
            addAll(plan.waypoints.mapNotNull { it.coordinate?.takeIf(Coordinate::isValid) })
            AirportDatabase.coordinate(plan.destination)?.let(::add)
        }
        if (planned.isNotEmpty()) return planned
        val here = state.aircraftState.coordinate?.takeIf(Coordinate::isValid) ?: return emptyList()
        val rounded = Coordinate(
            latitude = here.latitude.roundToInt().toDouble(),
            longitude = here.longitude.roundToInt().toDouble(),
        )
        // Rounding a position in the Gulf of Guinea can land on exactly (0, 0), which
        // `Coordinate.isValid` treats as "no fix" — so the rounded point would be discarded
        // and a perfectly good position would produce no imagery at all. In that one square
        // the position is used unrounded, which costs a few extra fetches over open ocean.
        return listOf(if (rounded.isValid) rounded else here)
    }

    // endregion

    // region Precipitation raster

    private val _radarRaster = MutableStateFlow<RadarRaster?>(null)

    /**
     * The fetched precipitation image the route map draws over everything else.
     *
     * Null until a region has been looked at and a provider has answered — and null for the
     * whole flight where no provider covers the route, which is most of the world. The map
     * is complete without it: the advisory shading, the sampled cells and, in Mock Mode, the
     * hand-authored precipitation polygons all draw regardless.
     */
    val radarRaster: StateFlow<RadarRaster?> = _radarRaster.asStateFlow()

    /** The region the map last settled on. Null until the pilot has looked at the map. */
    private val radarRegion = MutableStateFlow<MapRegion?>(null)

    /**
     * The route map has settled on a region; precipitation is fetched for what is on screen.
     *
     * The visible region rather than the route, which is what iOS does — a pilot who zooms
     * into the weather ahead should get that weather at full resolution and not a
     * scaled-down picture of the whole leg.
     */
    fun onRouteMapRegionSettled(region: MapRegion) {
        radarRegion.value = region
    }

    /**
     * Keep the precipitation raster current for whatever region the map is showing.
     *
     * Unlike the satellite underlay this cannot be fetched once and kept: every provider
     * serves "latest available" with no time parameter in the URL, so currency comes from
     * re-requesting and from nothing else. A pilot who sets the map down and flies for an
     * hour would otherwise be reading hour-old weather that looks exactly like fresh
     * weather. iOS re-samples on a 60-second staleness check for the same reason.
     *
     * `collectLatest` cancels an in-flight fetch when the pilot moves the map, because only
     * the region they are looking at now is worth waiting for.
     */
    /**
     * Keep the live precipitation cells current while the app is on screen.
     *
     * Gated on the foreground rather than run from the session scope, because these are the
     * megabyte-scale composite fetches and iOS gates them the same way — a flight running in
     * the background on the foreground service keeps its last good cells rather than fetching
     * a new picture nobody is looking at. Mock Mode has its own cells and never samples.
     */
    private fun observePrecipitationSampling() {
        viewModelScope.launch {
            while (true) {
                val overlay = graph.weather.state.value.radarOverlay
                val airborne = session.value.aircraftState.onGround == false
                if (!settings.value.mockMode && overlay.shouldDisplay && airborne) {
                    val sampled = runCatching { graph.precipitationSampler.sampleIfStale() }.getOrDefault(false)
                    // Fresh cells change what the reroute has to round, so the locked set is
                    // re-solved against them rather than held from the previous sample.
                    if (sampled) graph.weatherDeviation.invalidateLockedDeviations()
                }
                delay(PRECIPITATION_SAMPLE_CHECK_SECONDS.seconds)
            }
        }
    }

    private fun observeRadarRaster() {
        viewModelScope.launch {
            radarRegion.filterNotNull().collectLatest { region ->
                while (true) {
                    refreshRadarRaster(region)
                    delay(RADAR_REFRESH_SECONDS.seconds)
                }
            }
        }
    }

    /**
     * One attempt, keeping the last good image on failure.
     *
     * Blanking the map on a transient error is worse than showing weather a minute old: the
     * pilot reads "no precipitation" from an empty overlay, which is a different and more
     * dangerous statement than "the last picture we had". iOS makes the same choice in
     * `sampleLivePrecipitation` — "keep the last good cells so the deviation line doesn't
     * blink out on a transient error".
     */
    private suspend fun refreshRadarRaster(region: MapRegion) {
        if (!graph.weather.state.value.radarOverlay.shouldDisplayRaster(settings.value.mockMode)) {
            // Switched off, out of coverage, or Mock Mode — which draws its own
            // precipitation, so a stale raster underneath it would be a second weather.
            _radarRaster.value = null
            return
        }
        graph.radarRaster.load(region, RADAR_RASTER_SIZE)?.let { _radarRaster.value = it }
    }

    // endregion

    // region Saved flights

    /** The pilot's library, newest first — whatever the store currently holds. */
    val savedFlights: StateFlow<List<SavedFlight>> = graph.savedFlightStore.flights

    /** The slot the live session is bound to, so the list can mark which flight is flying. */
    val activeSavedFlightID: StateFlow<String?> = graph.savedFlightStore.activeFlightID

    /**
     * Save the flight in progress.
     *
     * The controller refuses whatever the disabled button refuses, so a save that arrives
     * from a confirmation dialog is held to the same rule.
     */
    fun onSaveCurrentFlight() {
        graph.savedFlights.saveCurrentFlight()
        coordinator.refreshSavedFlightState()
    }

    /** Put this flight down and start again from the gate, keeping the plan and settings. */
    fun onStartNewFlight() {
        graph.savedFlights.startNewFlight()
        coordinator.refreshSavedFlightState()
    }

    /** Carry on a previous flight exactly where it was left. */
    fun onLoadSavedFlight(flight: SavedFlight) {
        graph.savedFlights.loadSavedFlight(flight)
        coordinator.refreshSavedFlightState()
    }

    /**
     * Remove a saved flight. Deleting the one being flown unbinds it rather than ending it —
     * the flight carries on and simply stops being saved anywhere, which is why the session
     * has to be told to re-read the library afterwards.
     */
    fun onDeleteSavedFlight(flight: SavedFlight) {
        graph.savedFlights.deleteSavedFlight(flight)
        coordinator.refreshSavedFlightState()
    }

    /**
     * The clock, for the list's "saved 3 min ago".
     *
     * Read through the graph rather than `System.currentTimeMillis()` so the whole app has
     * one notion of now — the same `Clock` every engine and every test is built against.
     */
    fun nowMillis(): Long = graph.clock.nowMillis()

    /** The route warning shown before loading, or null when the routes agree. */
    fun endpointMismatch(flight: SavedFlight): String? = graph.savedFlights.endpointMismatch(flight)

    /**
     * Keep a bound slot current as the flight progresses.
     *
     * Hung off the transcript growing rather than a timer, for the same reason the crash
     * snapshot is: transmissions are seconds apart at their densest, and the transcript is
     * the part a reload cannot reconstruct from the next telemetry fix.
     */
    private fun observeAutoSave() {
        viewModelScope.launch {
            session
                .map { it.transcript.size }
                .distinctUntilChanged()
                .collect { size ->
                    if (size == 0) return@collect
                    graph.savedFlights.autoSave()
                }
        }
    }

    // endregion

    // region Rating prompt

    /**
     * Ask the pilot to rate the app, at the two moments the product allows and never
     * otherwise.
     *
     * Both are calm: connected and idle at the gate before the first ATC call of a session,
     * and arrived and parked with the flight finished. Never mid-flight — a pilot asked to
     * rate the app while being vectored is being interrupted at exactly the moment the app
     * is supposed to be useful. `ReviewRequestManager` applies every other gate; this only
     * decides *when the moment has arrived*.
     */
    private fun observeReviewMoments() {
        viewModelScope.launch {
            var countedThisFlight = false
            var askedBeforeFirstCall = false
            session.collect { state ->
                if (state.flightHasEnded) {
                    // Exactly once per completed flight: the engagement gate is what keeps a
                    // brand-new pilot out of the prompt, and double-counting brings the first
                    // ask forward past the point the product rule chose.
                    if (!countedThisFlight) {
                        countedThisFlight = true
                        graph.reviewDecision.recordFlightCompleted()
                        graph.reviewLauncher.requestIfAppropriate(
                            ReviewRequestManager.Trigger.AFTER_FLIGHT_COMPLETE,
                        )
                    }
                    return@collect
                }
                countedThisFlight = false

                // Connected, at the gate, nothing said yet. The transcript emptying is what
                // makes this "before the first call" rather than "any time on the ground".
                val atTheGateBeforeAnyCall = state.atcState == ATCState.CONNECTED_IDLE &&
                    state.transcript.isEmpty() &&
                    !state.hasDeparted
                if (atTheGateBeforeAnyCall && !askedBeforeFirstCall) {
                    askedBeforeFirstCall = true
                    graph.reviewLauncher.requestIfAppropriate(
                        ReviewRequestManager.Trigger.BEFORE_FIRST_CALL,
                    )
                } else if (!atTheGateBeforeAnyCall) {
                    askedBeforeFirstCall = false
                }
            }
        }
    }

    // endregion

    // region Pilot actions

    fun onPilotAction(action: PilotAction) {
        when (action) {
            PilotAction.CHECK_IN -> coordinator.checkIn()
            // The remaining actions post a pilot request and let the controller answer.
            // They route through the coordinator so the read-back gate and the standby
            // guard apply to every one of them, without the UI having to know that.
            else -> coordinator.performPilotAction(action)
        }
    }

    fun onAcknowledgement(ack: PilotActionPresentation.Acknowledgement) {
        when (ack) {
            PilotActionPresentation.Acknowledgement.READ_BACK -> coordinator.readBack()
            PilotActionPresentation.Acknowledgement.SAY_AGAIN -> coordinator.sayAgain()
            PilotActionPresentation.Acknowledgement.UNABLE -> coordinator.unable()
        }
    }

    fun onTune(facility: ATCFacility) = coordinator.tuneTo(facility)

    /**
     * Tuning ATIS is what makes the pilot *have* the information code — it is only from
     * here on that any call reports it. Pulls the freshest broadcast rather than serving
     * the cached one, because a pilot tuning ATIS is asking for the current letter.
     *
     * It deliberately does **not** retune the controller: ATIS is a one-way broadcast, not
     * a facility, so listening to it must not take the pilot off the frequency they are
     * working.
     */
    fun onTuneAtis() {
        val arrival = session.value.hasDeparted
        viewModelScope.launch {
            graph.weather.refreshAtis()
            graph.weather.noteAtisTuned(arrival)
            val atis = if (arrival) {
                graph.weather.state.value.arrivalAtis
            } else {
                graph.weather.state.value.departureAtis
            }
            atis?.part(arrival)?.let { part ->
                graph.speech.speakAtis(part, graph.clock.nowMillis())
            }
        }
    }

    fun onReplayLastCall() {
        session.value.latestTransmission?.let(graph.speech::speak)
    }

    /**
     * The subscribe banner on the ATC tab. Navigation belongs to [AppNavHost], which owns
     * the destination state, so this raises a one-shot request rather than navigating
     * itself — a no-op here would leave the banner dead, which is how it shipped before.
     */
    fun onSubscribe() {
        updateUi { it.copy(subscriptionRequested = true) }
    }

    /** Consumed by the shell once it has switched to the paywall. */
    fun onSubscriptionRequestHandled() {
        updateUi { it.copy(subscriptionRequested = false) }
    }

    fun onContactAtcAboutWeather() = coordinator.performPilotAction(PilotAction.RIDE_REPORT)

    // endregion

    // region Taxi routing

    /**
     * The computed taxi route, its crossings and the aircraft's progress along it.
     *
     * This is what the taxi map draws. Until now nothing constructed the coordinator
     * behind it, so the map had no route to show at any point in any flight.
     */
    val surfaceRouting: StateFlow<AirportSurfaceState> = graph.surfaceRouting.state

    /**
     * Drive the taxi coordinator from the flight's own state.
     *
     * The coordinator has a strict order — begin, clearance issued, read-back complete —
     * and it draws nothing until the read-back lands: `revealIfReady` returns early
     * without it and `updateLive` no-ops while the map is hidden. Rather than reach into
     * the ATC state machine, this watches the states it already publishes, which keeps
     * `:core`'s flow ignorant of the surface subsystem.
     */
    private fun observeTaxi() {
        viewModelScope.launch {
            var startedFor: ATCState? = null
            var sawReadback = false
            session.collect { state ->
                val departing = state.atcState == ATCState.GROUND_TAXI ||
                    state.atcState == ATCState.PUSHBACK_TAXI
                // The arrival half never ran: only the two departure states were handled,
                // always with the departure airport and always calling beginDeparture. So
                // `route.isDeparture` was true for the whole flight, the taxi-in clearance
                // could never be gate-routed, no arrival taxi map was ever drawn, and the
                // arrival runway crossings were never issued.
                val arriving = state.atcState == ATCState.GROUND_ARRIVAL ||
                    state.atcState == ATCState.RUNWAY_EXIT
                val taxiing = departing || arriving
                val plan = state.flightPlan

                if (taxiing && startedFor != state.atcState) {
                    val previous = startedFor
                    startedFor = state.atcState
                    sawReadback = false
                    val icao = if (arriving) plan.destination else plan.departure
                    val reference = (if (arriving) plan.destinationCoordinate else plan.departureCoordinate)
                        ?: state.aircraftState.coordinate
                    val start = state.aircraftState.coordinate ?: reference
                    when {
                        reference == null || start == null || icao.length < 3 -> Unit
                        // Already taxiing in — the runway-exit call started the arrival, and
                        // reaching the taxi-in only re-anchors the route to where the
                        // aircraft has actually rolled out to.
                        arriving && previous == ATCState.RUNWAY_EXIT -> {
                            graph.surfaceRouting.updateTaxiStart(start)
                        }
                        arriving -> {
                            graph.surfaceRouting.beginArrival(
                                icao = icao,
                                reference = reference,
                                aircraftName = state.aircraftState.aircraftName,
                                gate = plan.arrivalGate,
                                startCoordinate = start,
                                mock = state.mockMode,
                                arrivalRunway = plan.arrivalRunway.ifEmpty { plan.runway },
                            )
                            graph.surfaceRouting.taxiClearanceIssued(supersedeWhenRouteReady = true)
                        }
                        else -> {
                            graph.surfaceRouting.beginDeparture(
                                icao = icao,
                                reference = reference,
                                // The aircraft type comes from telemetry, not the plan — it is
                                // what sizes the taxi route's turn radii.
                                aircraftName = state.aircraftState.aircraftName,
                                runway = plan.departureRunway.ifEmpty { plan.runway },
                                gate = plan.departureGate,
                                startCoordinate = start,
                                mock = state.mockMode,
                            )
                            graph.surfaceRouting.taxiClearanceIssued(supersedeWhenRouteReady = true)
                        }
                    }
                }

                // The read-back is what reveals the map. Detected as the gate closing
                // again after a taxi clearance, which is the same moment the pilot's
                // acknowledgement reaches the transcript.
                if (taxiing && !sawReadback && !state.awaitingReadback && startedFor != null) {
                    sawReadback = true
                    graph.surfaceRouting.taxiReadBackComplete()
                }

                if (!taxiing && startedFor != null && state.atcState == ATCState.PARKED) {
                    startedFor = null
                    graph.surfaceRouting.hideTaxiMap()
                }

                graph.surfaceRouting.updateLive(
                    coordinate = state.aircraftState.coordinate,
                    heading = state.aircraftState.heading,
                    onGround = state.aircraftState.onGround,
                    groundSpeed = state.aircraftState.groundSpeed,
                )
            }
        }
    }

    /**
     * A taxi-map action the pilot tapped.
     *
     * Every one of these had no caller. That matters most for REQUEST_CROSSING: with
     * `autoCrossingCalls` on by default the coordinator issues a crossing clearance and
     * then waits in AWAITING_PILOT_READBACK for an acknowledgement no button could send,
     * so a taxi that met a runway would have stopped there for good.
     */
    fun onTaxiAction(action: TaxiMapAction) {
        when (action) {
            TaxiMapAction.REQUEST_CROSSING -> graph.surfaceRouting.requestCrossing()
            TaxiMapAction.HOLD_POSITION -> graph.surfaceRouting.holdPosition()
            TaxiMapAction.REQUEST_ALTERNATE_ROUTE -> graph.surfaceRouting.requestAlternateRoute()
            TaxiMapAction.RECALCULATE -> graph.surfaceRouting.recalculateRoute()
            TaxiMapAction.CONTINUE_ORIGINAL_ROUTE -> graph.surfaceRouting.continueOriginalRoute()
            TaxiMapAction.REQUEST_NEW_TAXI -> graph.surfaceRouting.requestNewTaxiInstructions()
        }
    }

    /**
     * Acknowledge a runway-crossing clearance — the release for the wedge above.
     *
     * Routed through the session's own `readBack()` rather than straight to the surface
     * coordinator, because reading back is a transmission: it has to appear in the
     * transcript and go out over the radio, and only then authorize the crossing. Calling
     * `crossingReadbackReceived()` here authorized it silently.
     */
    fun onCrossingReadback() = coordinator.readBack()

    /** Open the taxi map full screen, and close it again. */
    fun onExpandTaxiMap() {
        graph.surfaceRouting.mapExpanded = true
    }

    fun onCollapseTaxiMap() {
        graph.surfaceRouting.mapExpanded = false
    }

    /**
     * Read back the last controller call, from the taxi map.
     *
     * The same method the Responses grid's button calls, so a taxi or crossing clearance
     * can be acknowledged without scrolling back up to it.
     */
    fun onReadBack() = coordinator.readBack()

    /**
     * What the weather-deviation flow currently says: the mint line, the faint previews,
     * the response card's buttons and its status line.
     */
    val weatherDeviation = graph.weatherDeviation.state

    /** A tap on one of the weather response card's buttons. */
    fun onWeatherDeviationAction(action: WeatherDeviationAction) =
        coordinator.performWeatherDeviationAction(action)

    // endregion

    // region Push to talk

    fun onPushToTalkStart() {
        updateUi { it.copy(isListening = true, speechPartial = "") }
        // Cut the ambient bed before the recogniser opens the mic. On a device speaker the
        // static and the background voices are picked straight back up, which either
        // defeats recognition outright or salts the transcript with the chatter's own
        // callsigns. :core has had pauseForPTT/resumeAfterPTT since the port — ready,
        // tested, and until now called by nothing.
        graph.chatter.pauseForPTT()
        graph.pushToTalk.start(
            onPartial = { partial -> updateUi { it.copy(speechPartial = partial) } },
            onFinal = { text -> onSpeechRecognized(text) },
            onError = { message ->
                // Say why, rather than leaving a dead button: no offline model, no
                // permission and "didn't catch that" are different problems for the pilot.
                updateUi { it.copy(isListening = false, speechPartial = "", lastSpokenIntentTitle = message) }
                // An error ends the press just as much as a release does. Without this the
                // bed stays paused for the rest of the flight after one failed attempt.
                graph.chatter.resumeAfterPTT()
            },
        )
    }

    fun onPushToTalkEnd() {
        updateUi { it.copy(isListening = false) }
        graph.pushToTalk.stop()
        graph.chatter.resumeAfterPTT()
    }

    private fun onSpeechRecognized(text: String) {
        graph.chatter.resumeAfterPTT()
        // The parse is the engine's call, not the UI's — the UI only reports what came back.
        val intent = coordinator.handleSpokenPilotText(text)
        updateUi {
            it.copy(
                isListening = false,
                speechPartial = "",
                lastSpokenText = text,
                lastSpokenIntentTitle = intent,
            )
        }
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        updateUi { it.copy(microphoneDenied = !granted) }
    }

    // endregion

    // region ATC screen fields

    fun onCallsignChange(value: String) = updateUi { it.copy(draftCallsign = value) }

    fun onCallsignCommit() {
        val plan = session.value.flightPlan
        coordinator.ingestFlightPlan(
            plan.copy(callsign = _ui.value.draftCallsign.trim(), manualOverride = true),
        )
    }

    fun onDepartureGateChange(value: String) = updateUi { it.copy(draftDepartureGate = value) }

    fun onArrivalGateChange(value: String) = updateUi { it.copy(draftArrivalGate = value) }

    fun onGatesCommit() {
        val plan = session.value.flightPlan
        coordinator.ingestFlightPlan(
            plan.copy(
                departureGate = _ui.value.draftDepartureGate.trim(),
                arrivalGate = _ui.value.draftArrivalGate.trim(),
                manualOverride = true,
            ),
        )
        // A gate field the pilot has just *cleared* is blank again, so it is the automatic
        // assignment's to fill. Everything else about the edit leaves the assignment a
        // no-op, so this is safe to run on every commit.
        viewModelScope.launch { graph.autoGates.assignIfNeeded() }
    }

    /** A misfiled pair is common enough on a turn-around to deserve one tap. */
    fun onSwapGates() {
        updateUi { it.copy(draftDepartureGate = it.draftArrivalGate, draftArrivalGate = it.draftDepartureGate) }
        onGatesCommit()
    }

    // endregion

    // region Flight screen

    fun onOverridesChange(value: FlightOverrides) {
        updateUi { it.copy(overrides = value) }
    }

    fun onApplyOverrides() {
        val o = _ui.value.overrides
        val plan = session.value.flightPlan
        coordinator.ingestFlightPlan(
            plan.copy(
                callsign = o.callsign.ifBlank { plan.callsign },
                airline = o.airline.ifBlank { plan.airline },
                flightNumber = o.flightNumber.ifBlank { plan.flightNumber },
                departure = o.departure.ifBlank { plan.departure },
                destination = o.destination.ifBlank { plan.destination },
                alternate = o.alternate.ifBlank { plan.alternate },
                cruiseAltitude = o.cruiseAltitude.toIntOrNull() ?: plan.cruiseAltitude,
                runway = o.runway.ifBlank { plan.runway },
                approach = o.approach.ifBlank { plan.approach },
                sid = o.sid.ifBlank { plan.sid },
                star = o.star.ifBlank { plan.star },
                departureGate = o.departureGate.ifBlank { plan.departureGate },
                arrivalGate = o.arrivalGate.ifBlank { plan.arrivalGate },
                manualOverride = true,
            ),
        )
    }

    /** Clearing hands the flight plan back to Infinite Flight, which is the point. */
    fun onClearOverrides() {
        updateUi {
            it.copy(
                overrides = FlightOverrides(),
                // Leaving these populated would show header values the plan no longer holds.
                draftCallsign = "",
                draftDepartureGate = "",
                draftArrivalGate = "",
            )
        }
        // Unlatch first: the empty plan below is itself unflagged, so the guard would
        // refuse it while the override is still set and the button would do nothing.
        coordinator.clearManualOverride()
        coordinator.ingestFlightPlan(FlightPlan.empty)
    }

    fun onRefreshFlightPlan() {
        viewModelScope.launch { graph.connect.refreshFlightPlan() }
    }

    /**
     * SimBrief opens in a Custom Tab — their site, their branding, their session. The app
     * neither scrapes it nor injects anything into it.
     */
    fun onOpenSimBrief() = graph.openLink(AppConfig.Links.SIMBRIEF)

    // endregion

    // region Weather screen

    fun onToggleRadarOverlay(enabled: Boolean) {
        settingsRepository.setNoaaRadarOverlay(
            if (enabled) NOAARadarOverlayMode.AUTO_WHERE_AVAILABLE else NOAARadarOverlayMode.OFF,
        )
        graph.weather.refreshOverlayDescriptor()
    }

    fun onRadarOpacityChange(value: Float) {
        settingsRepository.setRadarOpacity(value.toDouble())
        graph.weather.refreshOverlayDescriptor()
    }

    /**
     * iOS refreshes weather by pull-to-refresh and then recomputes every deviation against
     * what landed. Same order here: one fetch feeds both, and because this is a manual
     * refresh it re-solves even mid-deviation.
     */
    fun onRefreshWeather() {
        viewModelScope.launch {
            graph.weather.refresh()
            graph.weather.recomputeRideItems()
        }
    }

    // endregion

    // region Settings

    fun updateSettings(settings: AppSettings) {
        // A mode change is not a settings write, it is a change of data source: the demo
        // feed and the Infinite Flight link have to be started and torn down in a
        // particular order, and the entitlement decides whether leaving Mock Mode is
        // allowed at all. Compared before the write, because the controller persists the
        // mode itself and reads the previous value to decide.
        val previous = settingsRepository.state.value
        val modeChanged = previous.mockMode != settings.mockMode
        val autoGatesChanged = previous.autoAssignGates != settings.autoAssignGates
        settingsRepository.replace(settings)
        // Switching gate assignment on assigns straight away; switching it off gives the
        // fields back — a gate the app filled in is the app's to withdraw, while one the
        // pilot typed always stays.
        if (autoGatesChanged) viewModelScope.launch { graph.autoGates.applySettingChange() }
        if (modeChanged) {
            graph.flightSource.toggleMockMode(
                on = settings.mockMode,
                hasLiveAccess = graph.entitlements.state.value.hasLiveAccess,
            )
        }
        // Chatter reads density, volume and the on/off toggle straight from settings, so
        // it is re-configured here rather than only when those specific fields change.
        graph.chatter.configure(settings)
        // Phraseology mode and digit style feed the engine, so it is rebuilt whenever
        // settings change rather than only when those two do — it is cheap, and missing the
        // rebuild would leave the pilot hearing the pack they just switched away from.
        coordinator.applyEngineConfig()
    }

    /**
     * The device's installed voices, enumerated once per engine-ready transition.
     *
     * This used to be a plain function called from settingsModel(), which is rebuilt on
     * every recomposition — and during a live flight the 1 Hz telemetry tick recomposes
     * the whole nav host, so the Settings tab was making a TextToSpeech binder round trip
     * and sorting the entire voice set on the UI thread once a second.
     *
     * Keyed on isReady because the engine returns nothing before initialisation completes,
     * so a value cached any earlier would be permanently empty.
     */
    val availableVoices: StateFlow<List<VoiceOption>> = graph.speech.isReady
        .map { ready ->
            if (!ready) emptyList()
            else graph.speech.voiceOptions().map { (id, title) -> VoiceOption(id, title) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun onPreviewVoice(voiceId: String) = graph.speech.previewVoice(voiceId)

    /**
     * The Settings "Connect" / "Reconnect" control.
     *
     * In Mock Mode it restarts the demo; in Live Connected Mode it drops the link and
     * brings it back up, which is what recovers a session after the tablet changed
     * network or Infinite Flight was restarted.
     */
    fun onReconnect() = graph.flightSource.reconnect()

    fun onRefreshAirportData() {
        viewModelScope.launch { graph.surface.refresh(session.value.flightPlan) }
    }

    fun onClearAirportCache() {
        viewModelScope.launch { graph.surface.clearCache() }
    }

    /** Everything the app stores is local, so a reset is a genuine factory reset. */
    fun onResetAppData() {
        settingsRepository.resetAll()
        viewModelScope.launch { graph.surface.clearCache() }
        graph.diagnostics.clear()
        coordinator.applyEngineConfig()
    }

    fun onOpenLink(url: String) = graph.openLink(url)

    val surfaceState = graph.surface.state

    fun surfaceDiagnosticRows(): List<Pair<String, String>> = graph.surface.diagnosticRows()

    // endregion

    // region Subscription

    val entitlements: StateFlow<EntitlementState> = graph.entitlements.state

    /**
     * Play Billing's purchase flow needs the Activity it will be shown over. If there
     * isn't one — the process is in the background — there is nothing to launch onto, so
     * the tap is dropped rather than crashing.
     */
    fun onPurchase(product: SubscriptionProduct) {
        val activity = graph.activityOrNull() ?: return
        graph.entitlements.purchase(activity, product)
    }

    fun onRestorePurchases() {
        viewModelScope.launch { graph.entitlements.restorePurchases() }
    }

    /**
     * Entitlements are otherwise loaded exactly once per process, in Application.onCreate.
     * If Play was not bindable at that moment — mid self-update, or the common cold-boot
     * race — the product list stayed empty and every Buy button stayed dead for the life
     * of the process. Refreshing when the paywall appears is what recovers from that.
     */
    fun onSubscriptionScreenShown() {
        viewModelScope.launch { graph.entitlements.onPaywallShown() }
    }

    // endregion

    // region Phraseology profiles

    fun onSelectActiveProfile(id: String?) {
        graph.phraseologyProfiles.activeProfileID = id
        coordinator.applyEngineConfig()
    }

    fun onEditProfile(profile: PhraseologyProfile) {
        updateUi { it.copy(editingProfile = profile) }
    }

    fun onCloseProfileEditor() {
        updateUi { it.copy(editingProfile = null) }
    }

    /**
     * iOS commits the draft when the editor disappears. Compose has no equally reliable
     * moment — a process death or a configuration change would lose it — so every edit
     * writes straight through. Same result, no window where the work is only in view state.
     */
    fun onSaveProfileDraft(profile: PhraseologyProfile) {
        graph.phraseologyProfiles.update(profile)
        updateUi { it.copy(editingProfile = profile) }
        coordinator.applyEngineConfig()
    }

    fun onDeleteProfile(profile: PhraseologyProfile) {
        graph.phraseologyProfiles.delete(profile)
        if (_ui.value.editingProfile?.id == profile.id) updateUi { it.copy(editingProfile = null) }
        coordinator.applyEngineConfig()
    }

    fun onCreateProfile() {
        val profile = graph.phraseologyProfiles.createNew()
        graph.phraseologyProfiles.activeProfileID = profile.id
        updateUi { it.copy(editingProfile = profile) }
        coordinator.applyEngineConfig()
    }

    fun onAddExampleProfile() {
        graph.phraseologyProfiles.add(PhraseologyProfile.example())
        coordinator.applyEngineConfig()
    }

    fun onImportProfileJson(json: String) {
        val imported = graph.phraseologyProfiles.importJSON(json)
        updateUi { it.copy(profileImportFailed = imported == null) }
        if (imported != null) coordinator.applyEngineConfig()
    }

    fun onShareProfile(profile: PhraseologyProfile) {
        graph.shareText(
            subject = profile.name,
            text = graph.phraseologyProfiles.exportJSON(profile),
        )
    }

    fun onDismissProfileImportError() {
        updateUi { it.copy(profileImportFailed = false) }
    }

    // endregion

    // region Diagnostics

    fun onToggleMockMode(enabled: Boolean) {
        settingsRepository.setMockMode(enabled)
        graph.setMockMode(enabled)
    }

    fun onAdvanceMockPhase() = graph.mockFeed.advancePhase()

    fun onToggleSimulateStaffedATC(enabled: Boolean) = coordinator.setSimulateStaffedATC(enabled)

    fun onToggleSampledRadarCells(enabled: Boolean) {
        updateUi { it.copy(showSampledRadarCells = enabled) }
    }

    fun onClearDiagnosticsLog() = graph.diagnostics.clear()

    fun onExportSurfaceDiagnostics() {
        graph.shareText(subject = "Airport surface diagnostics", text = graph.surface.exportText())
        graph.diagnostics.log(DiagnosticCategory.SURFACE, message = "Surface diagnostics exported")
    }

    fun mockRouteText(): String = with(graph.mockFeed.route) { "$departure → $destination" }

    /** How many of the readings the app needs the field's manifest actually resolved. */
    fun resolvedMappingCount(): Int = graph.connect.mappingStore.resolvedCount

    /** The live Connect snapshot — manifest, link state, last error. */
    val connectState = graph.connect.state

    // endregion

    fun frequencyForFacility(facility: ATCFacility): Double = coordinator.frequencyForFacility(facility)

    override fun onCleared() {
        graph.pushToTalk.release()
        super.onCleared()
    }

    companion object {
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                FlightViewModel(graph, graph.flightSessionCoordinator, graph.settingsRepository)
            }
        }
    }
}

/**
 * How long to wait before each retry of the base map's satellite underlay.
 *
 * Four attempts over about a minute and a half. Long enough to cover a phone that has not
 * found a network yet at the gate; bounded because imagery is an enhancement and a
 * genuinely offline flight must not keep waking the radio for it.
 */
private val BASE_MAP_IMAGERY_RETRY_DELAYS_SECONDS = listOf(5L, 20L, 60L)

/**
 * The pixel size the precipitation raster is requested at.
 *
 * Fixed rather than measured from the canvas. The map card is a known 280dp tall and this is
 * a translucent overlay read for shape and colour, not for detail; asking for the device's
 * true pixel dimensions would triple the download on a high-density phone to draw the same
 * blob. iOS multiplies by `displayScale` and gets a bigger image for no more information.
 */
private val RADAR_RASTER_SIZE = PixelSize(width = 1024, height = 512)

/**
 * How often the precipitation raster is re-requested for an unchanged region.
 *
 * iOS's steady-state re-sample interval. No provider puts a time parameter in its URL —
 * they all serve "latest available" — so this interval is the only thing that makes the
 * displayed weather current, and a longer one is indistinguishable on screen from a frozen
 * one.
 */
private const val RADAR_REFRESH_SECONDS = 60L

/**
 * How often the sampler is *asked*. It samples only when its own staleness gate says
 * so, so this is the polling cadence, not the fetch cadence.
 */
private const val PRECIPITATION_SAMPLE_CHECK_SECONDS = 20L

/** One more than the number of waits: the first attempt is not preceded by one. */
private val BASE_MAP_IMAGERY_ATTEMPTS = BASE_MAP_IMAGERY_RETRY_DELAYS_SECONDS.size + 1
