package com.h3consultingpartners.ifatccompanion.core.atc

import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyEngine
import com.h3consultingpartners.ifatccompanion.core.phraseology.RampPhraseologyEngine
import kotlin.math.max

/**
 * Deterministic ATC interaction state machine. Maps physical [FlightPhase] to
 * [ATCState], and emits the appropriate controller transmission when the state
 * advances.
 *
 * Ported from `IFATCCompanion/ATC/ATCStateMachine.swift`.
 */
class ATCStateMachine(private val engine: PhraseologyEngine) {

    private val ramp = RampPhraseologyEngine(engine)

    var current: ATCState = ATCState.NOT_CONNECTED
        private set

    fun reset() {
        current = ATCState.NOT_CONNECTED
    }

    /**
     * Restore the machine to a previously saved state (after a disconnect, so the
     * conversation resumes where it left off instead of re-deriving from telemetry).
     */
    fun restore(state: ATCState) {
        current = state
    }

    fun setConnected() {
        if (current == ATCState.NOT_CONNECTED) current = ATCState.CONNECTED_IDLE
    }

    /**
     * Map a detected physical phase to the appropriate ATC state, honouring the natural
     * one-directional flow of a flight.
     */
    fun mappedState(phase: FlightPhase): ATCState = when (phase) {
        FlightPhase.PREFLIGHT -> ATCState.CLEARANCE
        FlightPhase.TAXI_OUT -> ATCState.GROUND_TAXI
        FlightPhase.TAKEOFF -> ATCState.TOWER_DEPARTURE
        FlightPhase.INITIAL_CLIMB -> ATCState.INITIAL_CLIMB
        FlightPhase.CLIMB -> ATCState.CLIMB
        FlightPhase.CRUISE -> ATCState.CRUISE
        FlightPhase.DESCENT -> ATCState.DESCENT
        FlightPhase.APPROACH -> ATCState.APPROACH
        FlightPhase.LANDING -> ATCState.LANDING
        FlightPhase.TAXI_IN -> ATCState.GROUND_ARRIVAL
        FlightPhase.PARKED -> ATCState.PARKED
        FlightPhase.UNKNOWN ->
            if (current == ATCState.NOT_CONNECTED) ATCState.CONNECTED_IDLE else current
    }

    /**
     * Advance to a new state if warranted and, if so, return the controller transmission
     * that accompanies the change. Returns null when no change.
     */
    fun advance(target: ATCState, context: ATCContext): ATCTransmission? {
        if (target == current) return null
        val previous = current
        current = target
        return transmission(target, previous, context)
    }

    /** The transmission a controller issues upon entering [state]. */
    fun transmission(
        state: ATCState,
        @Suppress("UNUSED_PARAMETER") previous: ATCState,
        c: ATCContext,
    ): ATCTransmission? = when (state) {
        ATCState.CLEARANCE -> {
            val cleared = engine.clearance(
                cs = c.callsign,
                destination = c.plan.destination,
                cruise = c.cruiseAltitude,
                sid = c.plan.sid,
                initialAlt = c.initialClimbAltitude,
                departureFreq = c.departureFrequency,
                squawk = c.squawk,
                sidProcedure = c.sidProcedure,
            )
            // End the clearance with the pushback hand-off so the pilot knows which
            // facility/frequency to tune for the push (Ramp or Ground).
            engine.appendingPushbackHandoff(cleared, c.pushbackFacility, c.pushbackFrequency)
        }

        // Ramp (simulated local/company), not FAA ATC. Includes tail/face direction when
        // known, else "advise ready to taxi".
        ATCState.PUSHBACK -> ramp.pushbackApproved(
            cs = c.callsign,
            direction = c.pushDirection,
            usesFaceDirection = c.rampProfile.rampType.usesFaceDirection,
        )

        ATCState.ENGINE_START -> ramp.startApproved(c.callsign)

        ATCState.GROUND_TAXI, ATCState.PUSHBACK_TAXI -> engine.taxiToRunway(
            cs = c.callsign,
            runway = c.runway,
            via = c.taxiway,
            crossing = c.crossingRunway,
        )

        ATCState.LINE_UP_WAIT -> engine.lineUpAndWait(c.callsign, c.runway)

        // When a departure heading is known, the takeoff clearance also issues the
        // initial heading + climb (real-world style); otherwise the simpler "cleared for
        // takeoff" form is used.
        ATCState.TOWER_DEPARTURE -> if (c.departureHeading > 0) {
            engine.clearedForTakeoff(
                cs = c.callsign,
                runway = c.runway,
                windDir = c.windDirection,
                windSpeed = c.windSpeed,
                departureHeading = c.departureHeading,
                initialAltitude = c.initialClimbAltitude,
                runwayIsKnown = c.runwayIsKnown,
            )
        } else {
            engine.clearedForTakeoff(
                cs = c.callsign,
                runway = c.runway,
                windDir = c.windDirection,
                windSpeed = c.windSpeed,
            )
        }

        // Departure works the climb up to the TRACON ceiling (default FL180), joining the
        // filed route.
        ATCState.INITIAL_CLIMB, ATCState.DEPARTURE -> {
            val top = if (c.traconCeiling > 0) {
                c.traconCeiling
            } else {
                max(c.assignedAltitude, c.initialClimbAltitude)
            }
            engine.departureClimb(c.callsign, top, c.firstFixName)
        }

        // Center's first call after the Departure hand-off: radar contact, then the
        // clearance up to the cruising altitude.
        ATCState.CLIMB -> engine.centerRadarContactClimb(c.callsign, c.cruiseAltitude)

        // A filed STAR yields "descend via the <STAR> arrival"; otherwise a plain "descend
        // and maintain <alt>". The target is an intermediate altitude clearly below cruise
        // (not the cruise level), so it is never contradictory.
        ATCState.DESCENT -> {
            val alt = descentTargetAltitude(c)
            val star = c.starProcedure
            if (star != null) {
                engine.descendViaArrival(c.callsign, star, alt)
            } else {
                engine.descendMaintain(c.callsign, alt)
            }
        }

        // Approach descends to the terminal intercept altitude and tells the pilot which
        // approach to expect — independent of the higher altitude Center assigned during
        // the enroute descent. The intercept altitude is the first altitude in the
        // approach section of the flight plan when known, otherwise the elevation-aware
        // default (3,000 ft above the field, in MSL) so it never descends the aircraft
        // below the surface.
        ATCState.APPROACH -> {
            val interceptAlt = if (c.approachInterceptAltitude > 0) {
                c.approachInterceptAltitude
            } else {
                c.approachDefaultAltitude
            }
            val approach = c.approachProcedure
            if (approach != null) {
                engine.descendExpectApproach(c.callsign, interceptAlt, approach, c.runway)
            } else {
                engine.descendExpectApproach(c.callsign, interceptAlt, c.approachName, c.runway)
            }
        }

        ATCState.FINAL -> {
            val approach = c.approachProcedure
            if (approach != null) {
                engine.clearedApproach(c.callsign, approach, c.runway)
            } else {
                engine.clearedApproach(c.callsign, c.approachName, c.runway)
            }
        }

        ATCState.LANDING -> engine.clearedToLand(
            cs = c.callsign,
            runway = c.runway,
            windDir = c.windDirection,
            windSpeed = c.windSpeed,
        )

        // Tower instructs the aircraft to clear the runway and switch to Ground.
        ATCState.RUNWAY_EXIT -> engine.exitRunwayContactGround(c.callsign, c.groundFrequency)

        ATCState.GROUND_ARRIVAL -> engine.taxiToParking(c.callsign, c.gate, c.parkingTaxiway)

        // No call on reaching cruise: Center already established radar contact and cleared
        // the climb to the cruising altitude during the climb (at the TRACON-ceiling
        // check-in), so a second "radar contact" here is redundant.
        ATCState.CRUISE -> null

        ATCState.NOT_CONNECTED, ATCState.CONNECTED_IDLE, ATCState.HOLDING_SHORT,
        ATCState.RUNWAY_CROSSING, ATCState.TOP_OF_DESCENT, ATCState.PARKED,
        ATCState.ABNORMAL, ATCState.CENTER,
        -> null
    }

    companion object {
        /**
         * Intermediate altitude (ft MSL) Center assigns at top of descent — clearly below
         * cruise (so "descend and maintain …" is never contradictory) and above the
         * terminal/approach altitude that Approach later assigns.
         */
        fun descentTargetAltitude(c: ATCContext): Int {
            val cruise = if (c.cruiseAltitude > 0) c.cruiseAltitude else DEFAULT_CRUISE_ALTITUDE
            return if (cruise > HIGH_CRUISE_THRESHOLD) {
                HIGH_DESCENT_TARGET
            } else {
                max(LOW_DESCENT_FLOOR, cruise - LOW_DESCENT_STEP)
            }
        }

        const val DEFAULT_CRUISE_ALTITUDE = 37_000
        const val HIGH_CRUISE_THRESHOLD = 15_000
        const val HIGH_DESCENT_TARGET = 11_000
        const val LOW_DESCENT_FLOOR = 4_000
        const val LOW_DESCENT_STEP = 4_000
    }
}
