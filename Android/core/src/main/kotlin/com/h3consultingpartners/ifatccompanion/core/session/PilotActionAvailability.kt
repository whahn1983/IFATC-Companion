package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase

/**
 * Which pilot response buttons to surface right now.
 *
 * The response buttons are tied to the controller the pilot is currently working
 * ([Inputs.workingFacility]) and gated by the phase of flight, so only the calls that
 * make sense right now are shown — Clearance at the gate, push/start on Ramp, taxi on
 * Ground, takeoff on Tower, and the enroute/arrival requests on their respective
 * controllers.
 *
 * This is lifted out of the iOS `AppModel.availableActions` computed property into a
 * pure function, because it is exactly the sort of rule that is easy to get subtly wrong
 * and impossible to test while it is entangled with a view model.
 */
object PilotActionAvailability {

    /**
     * Everything the rule reads. Deliberately a value type: no engine references, so the
     * whole decision table can be exercised in a test.
     */
    data class Inputs(
        /**
         * The controller the pilot is currently dealing with for responses and check-ins:
         * the facility a hand-off has told them to contact if one is outstanding, otherwise
         * the frequency they're tuned to. Distinct from the tuned facility on purpose — the
         * radio does not tune to a new controller until the pilot reads the hand-off back
         * (or tunes by hand), yet the check-in / request buttons must already point at the
         * controller taking over.
         */
        val workingFacility: ATCFacility,
        val atcState: ATCState,
        val phase: FlightPhase,
        val aircraftState: AircraftState,
        /** True until the first departure. */
        val isPreDeparture: Boolean,
        val hasDeparted: Boolean,
        /** Whether the companion is deferring to a human controller right now. */
        val companionStandby: Boolean,
        /** Whether Ground handed the pilot to Tower to monitor before departure. */
        val monitoringTower: Boolean,
        /** Whether the pushback happens on Ground because the field has no ramp layer. */
        val pushbackOnGround: Boolean,
        /** Whether a ride report has surfaced a smoother cruise altitude to accept. */
        val hasSmootherAltitudeSuggestion: Boolean,
    )

    fun availableActions(inputs: Inputs): Set<PilotAction> {
        // Defer entirely to a human controller when one is staffing the position.
        if (inputs.companionStandby) return emptySet()

        if (inputs.isPreDeparture) return preDepartureActions(inputs)

        // Flight finished at the gate — nothing left to request.
        if (inputs.atcState == ATCState.PARKED) return emptySet()

        // Airborne / arrival — tie the requests to the controller currently working the
        // flight (the pending hand-off target, if any, else the tuned frequency).
        return when (inputs.workingFacility) {
            ATCFacility.DEPARTURE -> setOf(
                PilotAction.CHECK_IN,
                PilotAction.REQUEST_HIGHER,
                PilotAction.REQUEST_LOWER,
            )

            ATCFacility.CENTER -> buildSet {
                add(PilotAction.REQUEST_HIGHER)
                add(PilotAction.REQUEST_LOWER)
                add(PilotAction.RIDE_REPORT)
                add(PilotAction.DEST_WX)
                add(PilotAction.CHECK_IN)
                // Surface the accept button only while a ride report's smoother-altitude
                // suggestion is active.
                if (inputs.hasSmootherAltitudeSuggestion) add(PilotAction.ACCEPT_SMOOTHER_ALTITUDE)
            }

            ATCFacility.APPROACH -> setOf(
                PilotAction.CHECK_IN,
                PilotAction.VECTORS,
                PilotAction.APPROACH,
                PilotAction.REQUEST_LOWER,
                PilotAction.DEST_WX,
            )

            // Arrival Ramp: tuning in does not transmit, so the taxi-to-gate call is made
            // here with To Gate.
            ATCFacility.RAMP -> setOf(PilotAction.TO_GATE)

            // Tower (landing) and Ground (taxi-in) progress with a check-in; no enroute
            // requests apply. Inbound to land on Tower, the pilot can also break off the
            // approach with Go Around.
            ATCFacility.TOWER, ATCFacility.GROUND, ATCFacility.CLEARANCE -> buildSet {
                add(PilotAction.CHECK_IN)
                if (canGoAround(inputs)) add(PilotAction.GO_AROUND)
            }
        }
    }

    private fun preDepartureActions(inputs: Inputs): Set<PilotAction> = when (inputs.workingFacility) {
        ATCFacility.CLEARANCE -> {
            // Offer the IFR clearance until it's issued. The push is NOT offered here —
            // the clearance ends by telling the pilot to contact Ramp (or Ground) for the
            // pushback, so the Pushback button appears only after they tune that
            // frequency, never under Clearance.
            val beforeClearance = inputs.atcState == ATCState.NOT_CONNECTED ||
                inputs.atcState == ATCState.CONNECTED_IDLE
            if (beforeClearance) setOf(PilotAction.CLEARANCE) else emptySet()
        }

        ATCFacility.RAMP -> {
            // Tuning to Ramp does not transmit, so the push must be requested here. Before
            // the push: offer Pushback. After it: engine start, then a taxi request hands
            // off to Ground.
            if (inputs.atcState in PRE_PUSH_STATES) {
                setOf(PilotAction.PUSHBACK)
            } else {
                setOf(PilotAction.ENGINE_START, PilotAction.TAXI)
            }
        }

        ATCFacility.GROUND -> {
            // On Ground only the taxi is requested. "Ready for departure" is a Tower call
            // (it addresses Tower while holding short), so it is offered only once the
            // pilot has tuned Tower — never on Ground. The exception is a no-ramp airport,
            // where the push happens on Ground: offer it there (and only before it has
            // been done).
            val prePush = inputs.atcState in PRE_PUSH_STATES
            if (prePush && inputs.pushbackOnGround) {
                setOf(PilotAction.PUSHBACK, PilotAction.TAXI)
            } else {
                setOf(PilotAction.TAXI)
            }
        }

        ATCFacility.TOWER -> {
            // After a "monitor Tower" hand-off no check-in is required, but offer it —
            // checking in gets a "number one for departure" acknowledgement — alongside
            // the report-ready ("line up and wait") and takeoff requests. Otherwise the
            // usual report-ready / takeoff pair.
            if (inputs.monitoringTower) {
                setOf(PilotAction.READY, PilotAction.CHECK_IN, PilotAction.TAKEOFF)
            } else {
                setOf(PilotAction.READY, PilotAction.TAKEOFF)
            }
        }

        else -> setOf(PilotAction.CLEARANCE)
    }

    /**
     * Whether the "Go Around" button applies right now: airborne, inbound to land on the
     * Tower frequency for the ILS/GPS/visual approach — either cleared the approach /
     * contacting Tower (FINAL) or cleared to land (LANDING). Hidden on the ground and
     * throughout the departure.
     */
    fun canGoAround(inputs: Inputs): Boolean {
        if (inputs.workingFacility != ATCFacility.TOWER) return false
        if (!inputs.hasDeparted) return false
        if (inputs.aircraftState.onGround == true) return false
        return inputs.atcState == ATCState.FINAL || inputs.atcState == ATCState.LANDING
    }

    /**
     * Whether the simulated arrival Ramp (taxi-to-gate) flow applies right now: the
     * aircraft has departed and is back on the ground arriving, not yet parked.
     */
    fun isArrivalRamp(atcState: ATCState, phase: FlightPhase, hasDeparted: Boolean): Boolean {
        if (!hasDeparted) return false
        if (atcState == ATCState.PARKED) return false
        return phase in ARRIVAL_PHASES ||
            atcState == ATCState.RUNWAY_EXIT ||
            atcState == ATCState.GROUND_ARRIVAL
    }

    /**
     * Whether the "Ramp" frequency button should be live: pushback before departure, or
     * the taxi-to-gate hand-off on arrival — but never once parked.
     */
    fun canContactRamp(atcState: ATCState, phase: FlightPhase, hasDeparted: Boolean): Boolean {
        if (atcState == ATCState.PARKED) return false
        return !hasDeparted || isArrivalRamp(atcState, phase, hasDeparted)
    }

    private val PRE_PUSH_STATES = setOf(
        ATCState.NOT_CONNECTED,
        ATCState.CONNECTED_IDLE,
        ATCState.CLEARANCE,
    )

    private val ARRIVAL_PHASES = setOf(
        FlightPhase.LANDING,
        FlightPhase.TAXI_IN,
        FlightPhase.PARKED,
    )
}
