package com.h3consultingpartners.ifatccompanion.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.h3consultingpartners.ifatccompanion.AppGraph
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.session.FlightSessionCoordinator
import com.h3consultingpartners.ifatccompanion.core.session.FlightSessionState
import com.h3consultingpartners.ifatccompanion.core.session.PilotAction
import com.h3consultingpartners.ifatccompanion.core.session.PilotActionPresentation
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import com.h3consultingpartners.ifatccompanion.core.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The bridge between the flight session and Compose.
 *
 * It deliberately holds no logic of its own: the engine decides, and this exposes what
 * it decided and forwards what the pilot did. Anything that looks like a rule belongs in
 * `:core`, where it can be tested — that is the whole reason the engine is a separate
 * module.
 */
class FlightViewModel(
    private val graph: AppGraph,
    private val coordinator: FlightSessionCoordinator,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val session: StateFlow<FlightSessionState> = coordinator.state

    val settings: StateFlow<AppSettings> = settingsRepository.state

    private val _microphoneDenied = MutableStateFlow(false)
    val microphoneDenied: StateFlow<Boolean> = _microphoneDenied.asStateFlow()

    private val _speechPartial = MutableStateFlow("")
    val speechPartial: StateFlow<String> = _speechPartial.asStateFlow()

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

    fun onReplayLastCall() {
        session.value.latestTransmission?.let(graph.speech::speak)
    }

    // endregion

    // region Settings

    fun updateSettings(settings: AppSettings) {
        settingsRepository.replace(settings)
        // Phraseology mode and digit style feed the engine, so it is rebuilt whenever
        // settings change rather than only when those two do — it is cheap, and missing
        // the rebuild would leave the pilot hearing the pack they just switched away from.
        coordinator.applyEngineConfig()
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        _microphoneDenied.value = !granted
    }

    // endregion

    companion object {
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val coordinator = graph.flightSessionCoordinator
                FlightViewModel(graph, coordinator, graph.settingsRepository)
            }
        }
    }
}
