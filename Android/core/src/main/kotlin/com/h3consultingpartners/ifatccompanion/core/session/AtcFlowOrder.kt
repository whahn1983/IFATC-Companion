package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState

/**
 * The canonical gate-to-gate order of the conversation, and the rules that keep it
 * moving forward.
 *
 * The phase detector flickers near the ground — a snapshot at 39 kt reads as taxi, the
 * next at 41 kt reads as a takeoff roll — and without an ordering the automatic flow
 * would bounce back to an earlier call each time it did. Lifted out of the iOS
 * `AppModel` so the ordering itself can be asserted.
 */
object AtcFlowOrder {

    val flowOrder: List<ATCState> = listOf(
        ATCState.CLEARANCE,
        ATCState.PUSHBACK,
        ATCState.ENGINE_START,
        ATCState.GROUND_TAXI,
        ATCState.LINE_UP_WAIT,
        ATCState.TOWER_DEPARTURE,
        ATCState.INITIAL_CLIMB,
        ATCState.DEPARTURE,
        ATCState.CLIMB,
        ATCState.CRUISE,
        ATCState.DESCENT,
        ATCState.APPROACH,
        ATCState.FINAL,
        ATCState.LANDING,
        ATCState.RUNWAY_EXIT,
        ATCState.GROUND_ARRIVAL,
        ATCState.PARKED,
    )

    /**
     * Index of a state in the canonical flow order, or null for states outside it
     * (NOT_CONNECTED, CONNECTED_IDLE, RUNWAY_CROSSING, HOLDING_SHORT, TOP_OF_DESCENT,
     * CENTER, ABNORMAL).
     */
    fun flowIndex(state: ATCState): Int? = flowOrder.indexOf(state).takeIf { it >= 0 }

    /**
     * Whether advancing from [current] to [target] would move the conversation forward
     * (or stay put). States outside the gate-to-gate order are treated as allowed.
     */
    fun isForward(target: ATCState, current: ATCState): Boolean {
        val ti = flowIndex(target) ?: return true
        val ci = flowIndex(current) ?: return true
        return ti >= ci
    }

    /**
     * The controller (facility) actually working a given ATC state. Used to drive
     * realistic "contact …" handoffs whenever control passes between facilities, and to
     * label the current facility in the UI.
     *
     * [fallback] answers for the states that have no controller of their own — the ones
     * before the first contact and the off-route state — where the iOS code returns
     * whatever facility is currently tuned.
     */
    fun controller(state: ATCState, fallback: ATCFacility): ATCFacility = when (state) {
        ATCState.CLEARANCE -> ATCFacility.CLEARANCE
        ATCState.PUSHBACK, ATCState.ENGINE_START -> ATCFacility.RAMP
        ATCState.PUSHBACK_TAXI, ATCState.GROUND_TAXI, ATCState.RUNWAY_CROSSING,
        ATCState.HOLDING_SHORT, ATCState.GROUND_ARRIVAL, ATCState.PARKED,
        -> ATCFacility.GROUND

        ATCState.LINE_UP_WAIT, ATCState.TOWER_DEPARTURE, ATCState.LANDING,
        ATCState.RUNWAY_EXIT,
        -> ATCFacility.TOWER

        ATCState.INITIAL_CLIMB, ATCState.DEPARTURE -> ATCFacility.DEPARTURE

        ATCState.CLIMB, ATCState.CENTER, ATCState.CRUISE, ATCState.TOP_OF_DESCENT,
        ATCState.DESCENT,
        -> ATCFacility.CENTER

        ATCState.APPROACH, ATCState.FINAL -> ATCFacility.APPROACH

        ATCState.NOT_CONNECTED, ATCState.CONNECTED_IDLE, ATCState.ABNORMAL -> fallback
    }

    /**
     * The next controller ahead of [current] that is not the one already tuned, so the
     * pilot can tune ahead for the upcoming hand-off without every facility cluttering
     * the page (Tower doesn't appear until the taxi is underway).
     */
    /**
     * The next state ahead of [current] that [facility] works, or the last one it works when
     * there is nothing ahead.
     *
     * This is what lets a check-in be *answered*. The pilot tunes a frequency and calls up;
     * the controller replies with whatever it has for them next. Falling back to the last
     * state that facility works is what makes a re-check-in on a frequency the flight has
     * already passed still find a sensible context to speak from, rather than returning null
     * and leaving the pilot talking to nobody.
     */
    fun nextStateWorkedBy(
        facility: ATCFacility,
        current: ATCState,
        fallback: ATCFacility,
    ): ATCState? {
        val start = flowIndex(current)?.plus(1) ?: 0
        flowOrder.drop(start).firstOrNull { controller(it, fallback) == facility }?.let { return it }
        return flowOrder.lastOrNull { controller(it, fallback) == facility }
    }

    fun nextDistinctFacility(current: ATCState, currentFacility: ATCFacility): ATCFacility? {
        val idx = flowIndex(current) ?: return null
        for (state in flowOrder.drop(idx + 1)) {
            val facility = controller(state, currentFacility)
            if (facility != currentFacility) return facility
        }
        return null
    }

    /**
     * The frequency buttons worth showing right now: the controller currently working the
     * flight plus where the pilot is headed — a hand-off they have been told to take but
     * have not tuned yet (so its button is there to tap), or, with none pending, the next
     * distinct controller ahead.
     */
    fun relevantFacilities(
        currentFacility: ATCFacility,
        pendingCheckInFacility: ATCFacility?,
        currentState: ATCState,
    ): Set<ATCFacility> = buildSet {
        add(currentFacility)
        if (pendingCheckInFacility != null) {
            add(pendingCheckInFacility)
        } else {
            nextDistinctFacility(currentState, currentFacility)?.let { add(it) }
        }
    }

    /**
     * The facilities the Tune Frequency grid can offer, in the order it shows them. Ramp
     * is handled separately because it is only live for the push and the gate.
     */
    val tunableFacilities: List<ATCFacility> = listOf(
        ATCFacility.CLEARANCE,
        ATCFacility.GROUND,
        ATCFacility.TOWER,
        ATCFacility.DEPARTURE,
        ATCFacility.CENTER,
        ATCFacility.APPROACH,
    )
}
