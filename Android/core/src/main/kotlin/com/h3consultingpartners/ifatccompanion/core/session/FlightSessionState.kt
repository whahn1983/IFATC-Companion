package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.atc.PhaseDetector
import com.h3consultingpartners.ifatccompanion.core.connect.IFConnectConnectionState
import com.h3consultingpartners.ifatccompanion.core.connect.LiveATCStatus
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan

/**
 * Everything the screens render, as one immutable value.
 *
 * The iOS `AppModel` publishes ~40 separate `@Published` properties and the SwiftUI
 * views observe the object. Compose works better the other way round: one `StateFlow` of
 * one immutable state, so a screen recomposes from a single coherent snapshot rather
 * than from forty independently-timed emissions. That also removes a whole class of bug
 * the iOS comments keep referring to — reading a property mid-update, while
 * `@Published`'s `willSet` has fired but the value has not landed.
 *
 * The flight-session coordinator is the only thing that produces this.
 */
data class FlightSessionState(
    // region Live telemetry and plan

    val aircraftState: AircraftState = AircraftState.empty,
    val flightPlan: FlightPlan = FlightPlan.empty,
    val phase: FlightPhase = FlightPhase.PREFLIGHT,
    val phaseDebug: PhaseDetector.Debug = PhaseDetector.Debug(),

    // endregion

    // region ATC conversation

    val atcState: ATCState = ATCState.NOT_CONNECTED,

    /**
     * The frequency the radio is actually tuned to. It changes only when the pilot tunes
     * by hand or reads a hand-off back (auto-tune), never the moment a controller issues
     * a hand-off. Clearance Delivery is the first controller a flight calls, so the radio
     * starts tuned there — not Ground — at the gate.
     */
    val currentFacility: ATCFacility = ATCFacility.CLEARANCE,

    /**
     * The controller a hand-off has told the pilot to contact but which they have not
     * tuned yet. Null when no check-in is outstanding.
     */
    val pendingCheckInFacility: ATCFacility? = null,

    val transcript: List<ATCTransmission> = emptyList(),
    val latestTransmission: ATCTransmission? = null,
    val assignedAltitude: Int = 0,

    /** True while the flow is holding for the pilot to read the last instruction back. */
    val awaitingReadback: Boolean = false,

    /** True once the pilot has started tuning frequencies by hand. */
    val manualTuning: Boolean = false,

    /** True once the aircraft has departed; drives the pre-departure / arrival split. */
    val hasDeparted: Boolean = false,

    /** True while Ground has handed the pilot to Tower to monitor before departure. */
    val monitoringTower: Boolean = false,

    /** The named en-route sector currently working the flight, when sectors are on. */
    val centerSectorName: String? = null,

    // endregion

    // region Human ATC

    val liveATC: LiveATCStatus = LiveATCStatus.none,

    /**
     * Whether the companion is deferring to a human controller right now. The guard is
     * per-frequency and location-aware: in live mode it applies only while the pilot's
     * tuned COM frequency is a staffed human controller, so tuning to UNICOM, ATIS, or an
     * unstaffed field lifts it and the companion resumes covering that sector.
     */
    val companionStandby: Boolean = false,

    /** Mock-mode demo toggle to exercise the "step aside" behaviour. */
    val simulateStaffedATC: Boolean = false,

    // endregion

    // region Connection

    val connectionState: IFConnectConnectionState = IFConnectConnectionState.Disconnected,
    val mockMode: Boolean = false,
    val hasLiveAccess: Boolean = false,

    // endregion

    // region Buttons

    val availableActions: Set<PilotAction> = emptySet(),
    val availableWeatherDeviationActions: Set<WeatherDeviationAction> = emptySet(),
    val relevantFacilities: Set<ATCFacility> = emptySet(),

    /** The accept button's label for an active smoother-altitude suggestion. */
    val smootherAltitudeLabel: String? = null,

    // endregion

    // region Saved flights

    val hasUnsavedFlight: Boolean = false,
    val canSaveCurrentFlight: Boolean = false,

    /**
     * The saved flight that clearing would retire, because this flight has finished.
     * Null when clearing retires nothing.
     */
    val savedFlightRetiredByClearing: String? = null,

    // endregion
) {

    /**
     * The controller the pilot is currently dealing with for responses and check-ins: the
     * facility a hand-off has told them to contact if one is outstanding, otherwise the
     * frequency they're tuned to. Distinct from [currentFacility] on purpose — the radio
     * does not tune to a new controller until the pilot reads the hand-off back (or tunes
     * by hand), yet the check-in / request buttons must already point at the controller
     * taking over.
     */
    val workingFacility: ATCFacility get() = pendingCheckInFacility ?: currentFacility

    /** Whether the pre-departure ground actions should be offered. */
    val isPreDeparture: Boolean get() = !hasDeparted

    /**
     * Whether the simulated arrival Ramp (taxi-to-gate) flow applies right now: the
     * aircraft has departed and is back on the ground arriving, not yet parked.
     */
    val isArrivalRamp: Boolean
        get() = PilotActionAvailability.isArrivalRamp(atcState, phase, hasDeparted)

    /**
     * Whether the "Ramp" frequency button should be live: pushback before departure, or
     * the taxi-to-gate hand-off on arrival — but never once parked.
     */
    val canContactRamp: Boolean
        get() = PilotActionAvailability.canContactRamp(atcState, phase, hasDeparted)

    /** Whether the flight has finished at the arrival gate. */
    val flightHasEnded: Boolean get() = atcState == ATCState.PARKED

    /** The most recent controller call, ignoring the pilot's own and ATIS broadcasts. */
    val lastControllerCall: ATCTransmission?
        get() = transcript.lastOrNull {
            it.sender == ATCTransmission.Sender.ATC && !it.isATISLine
        }

    /** Whether any ATC communication has happened yet — the chatter's start gate. */
    val atcCommunicationStarted: Boolean
        get() = transcript.any { it.isControllerExchange }
}
