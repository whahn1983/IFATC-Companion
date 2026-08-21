package com.h3consultingpartners.ifatccompanion.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.h3consultingpartners.ifatccompanion.AppGraph
import com.h3consultingpartners.ifatccompanion.core.billing.EntitlementState
import com.h3consultingpartners.ifatccompanion.core.config.AppConfig
import com.h3consultingpartners.ifatccompanion.core.billing.SubscriptionProduct
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyProfile
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticRecord
import com.h3consultingpartners.ifatccompanion.core.session.FlightSessionCoordinator
import com.h3consultingpartners.ifatccompanion.core.session.FlightSessionState
import com.h3consultingpartners.ifatccompanion.core.session.PilotAction
import com.h3consultingpartners.ifatccompanion.core.session.PilotActionPresentation
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import com.h3consultingpartners.ifatccompanion.core.settings.NOAARadarOverlayMode
import com.h3consultingpartners.ifatccompanion.core.settings.SettingsRepository
import com.h3consultingpartners.ifatccompanion.core.surface.routing.AirportSurfaceState
import com.h3consultingpartners.ifatccompanion.core.surface.routing.TaxiMapAction
import com.h3consultingpartners.ifatccompanion.core.weather.WeatherSessionState
import com.h3consultingpartners.ifatccompanion.ui.screens.FlightOverrides
import com.h3consultingpartners.ifatccompanion.ui.screens.VoiceOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
                }
        }
    }

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
                val taxiing = state.atcState == ATCState.GROUND_TAXI ||
                    state.atcState == ATCState.PUSHBACK_TAXI
                val plan = state.flightPlan

                if (taxiing && startedFor != state.atcState) {
                    startedFor = state.atcState
                    sawReadback = false
                    val reference = plan.departureCoordinate ?: state.aircraftState.coordinate
                    val start = state.aircraftState.coordinate ?: reference
                    if (reference != null && start != null && plan.departure.length >= 3) {
                        graph.surfaceRouting.beginDeparture(
                            icao = plan.departure,
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

    /** Acknowledge a runway-crossing clearance — the release for the wedge above. */
    fun onCrossingReadback() = graph.surfaceRouting.crossingReadbackReceived()

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
        settingsRepository.replace(settings)
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
