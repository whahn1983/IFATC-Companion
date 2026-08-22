package com.h3consultingpartners.ifatccompanion.service

import com.h3consultingpartners.ifatccompanion.core.liveupdate.LiveFlightAction
import com.h3consultingpartners.ifatccompanion.core.liveupdate.LiveFlightUpdate
import com.h3consultingpartners.ifatccompanion.core.liveupdate.LiveFlightUpdateProjection
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import com.h3consultingpartners.ifatccompanion.core.session.FlightSessionCoordinator
import com.h3consultingpartners.ifatccompanion.core.session.PilotAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Connects the flight session to the foreground service and its Live Flight Update.
 *
 * The service does not own the session — the session outlives every Activity and the
 * service alike. This only mirrors it: it projects each snapshot onto the card
 * ([LiveFlightUpdateProjection], which is where the rules live and where they are tested)
 * and routes a tapped notification action back to the same coordinator method the on-screen
 * button would have called, so a read-back from the lock screen is indistinguishable from
 * one tapped in the app — same gate, same standby guard, same transcript.
 */
class FlightSessionActiveFlightController(
    private val coordinator: FlightSessionCoordinator,
    scope: CoroutineScope,
    private val clock: Clock = Clock.system,
    private val onStopRequested: () -> Unit = {},
    /**
     * The live route-weather banner, if any. A flow rather than a value because the Live
     * Flight Update has to change when the weather does, not only when the flight does.
     */
    private val weatherAlert: StateFlow<String?> = MutableStateFlow(null),
) : ActiveFlightController {

    /**
     * A flight is "active" from the moment ATC communication starts until it ends —
     * either by reaching the arrival gate, or by being stopped.
     *
     * The `sessionEnded` half is load-bearing. Keyed on PARKED alone, a flight that never
     * reaches the gate — quit mid-cruise, diverted, link permanently lost, Mock Mode
     * switched off — stayed "active" forever, so the service never stopped. On Android 12
     * and below a foreground-service notification is not user-dismissible, so the pilot
     * was left with a frozen Live Flight Update card that only a force-stop could clear,
     * and a 1 Hz poll running behind it.
     */
    override val isSessionActive: StateFlow<Boolean> = coordinator.state
        .map { it.atcCommunicationStarted && !it.sessionEnded && it.atcState != ATCState.PARKED }
        .stateIn(scope, SharingStarted.Eagerly, false)

    override val liveUpdate: StateFlow<LiveFlightUpdate?> =
        combine(coordinator.state, weatherAlert) { session, alert ->
            LiveFlightUpdateProjection.from(session, clock.nowMillis(), weatherAlert = alert)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    override fun performLiveAction(action: LiveFlightAction) {
        when (action) {
            LiveFlightAction.READ_BACK -> coordinator.readBack()
            LiveFlightAction.CHECK_IN -> coordinator.performPilotAction(PilotAction.CHECK_IN)
        }
    }

    override fun stopSession() = onStopRequested()
}
