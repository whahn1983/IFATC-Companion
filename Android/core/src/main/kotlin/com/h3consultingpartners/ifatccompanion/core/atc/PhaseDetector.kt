package com.h3consultingpartners.ifatccompanion.core.atc

import com.h3consultingpartners.ifatccompanion.core.airports.AirportDatabase
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import kotlin.math.abs

/**
 * Deterministic, conservative flight-phase detection from aircraft state. Exposes
 * intermediate reasoning via [Debug] for the Diagnostics screen.
 *
 * Ported from `IFATCCompanion/ATC/PhaseDetector.swift`.
 */
class PhaseDetector {

    data class Debug(
        val onGround: Boolean = false,
        val groundSpeed: Double = 0.0,
        val altitudeMSL: Double = 0.0,
        val verticalSpeed: Double = 0.0,
        val distanceToDestNM: Double? = null,
        val distanceToDepNM: Double? = null,
        val notes: List<String> = emptyList(),
    )

    data class Result(val phase: FlightPhase, val debug: Debug)

    /**
     * Detect the current phase. [previous] provides hysteresis so we don't oscillate
     * between adjacent phases on noisy data.
     */
    fun detect(
        state: AircraftState,
        plan: FlightPlan,
        airports: AirportDatabase,
        previous: FlightPhase,
    ): Result {
        val gs = state.groundSpeed ?: 0.0
        val alt = state.altitudeMSL ?: 0.0
        val vs = state.verticalSpeed ?: 0.0
        val notes = mutableListOf<String>()

        // Whether the aircraft is on the ground is *reported*, never assumed. Infinite
        // Flight answers each state read as its own request/response, so any one of them
        // can time out or be dropped while the rest of the snapshot arrives intact — most
        // often around a reconnect, including the forced one the app performs when it
        // returns from the background. A snapshot carrying a position and an altitude but
        // no on-ground flag is therefore routine, and reading that missing flag as
        // "airborne" is what put a taxiing aircraft into the climb: the airborne branch
        // below has no better answer for a slow, level fix than "climb", which is Center
        // on the radio while the aircraft is still on the taxiway.
        val reportedOnGround = state.onGround ?: state.altitudeAGL?.let { it < 10 }

        val coord = state.coordinate
        // Infinite Flight's reported field position is the source of truth; the built-in
        // hub table is only a last resort for a manually-entered ICAO IF isn't reporting.
        val depCoord = plan.departureCoordinate ?: airports.coordinate(plan.departure)
        val destCoord = plan.destinationCoordinate ?: airports.coordinate(plan.destination)
        val distanceToDepNM = if (coord != null && depCoord != null) {
            Geo.distanceNM(coord, depCoord)
        } else {
            null
        }
        val distanceToDestNM = if (coord != null && destCoord != null) {
            Geo.distanceNM(coord, destCoord)
        } else {
            null
        }

        fun debug(onGround: Boolean) = Debug(
            onGround = onGround,
            groundSpeed = gs,
            altitudeMSL = alt,
            verticalSpeed = vs,
            distanceToDestNM = distanceToDestNM,
            distanceToDepNM = distanceToDepNM,
            notes = notes.toList(),
        )

        // With no ground reference in this snapshot, hold the phase the flight is already
        // in rather than guess at it. Only a genuine vertical rate overrides the hold —
        // ground speed can't, since a takeoff roll is fast and firmly on the runway.
        val onGround: Boolean
        when {
            reportedOnGround != null -> onGround = reportedOnGround

            abs(vs) > UNREPORTED_GROUND_VERTICAL_RATE -> {
                notes += "On-ground state not reported — vertical rate shows airborne"
                onGround = false
            }

            else -> {
                notes += "On-ground state not reported — holding ${previous.title}"
                return Result(previous, debug(previous.isGround))
            }
        }

        // --- On the ground ---
        if (onGround) {
            if (gs < STOPPED_GROUND_SPEED) {
                // Distinguish pre-departure vs parked-after-arrival using prior phase.
                if (previous in ARRIVAL_PHASES) {
                    // Parked only once the parking brake is set (when the sim exposes it);
                    // a full stop with the brake released is still taxiing in (e.g. holding
                    // for traffic on the ramp), not parked at the gate.
                    if (state.parkingBrakeSet == false) {
                        notes += "Stopped on ground, brake released — still taxiing in"
                        return Result(FlightPhase.TAXI_IN, debug(true))
                    }
                    notes += "Stopped on ground after arrival"
                    return Result(FlightPhase.PARKED, debug(true))
                }
                notes += "Stopped on ground"
                return Result(
                    if (previous == FlightPhase.TAXI_IN) FlightPhase.PARKED else FlightPhase.PREFLIGHT,
                    debug(true),
                )
            }
            if (gs < TAXI_GROUND_SPEED) {
                // Taxi speed. Decide out vs in by proximity / prior phase.
                val arriving = previous in TAXI_IN_PHASES
                notes += "Taxi speed"
                return Result(
                    if (arriving) FlightPhase.TAXI_IN else FlightPhase.TAXI_OUT,
                    debug(true),
                )
            }
            // High ground speed -> takeoff roll or landing rollout.
            val rolloutContext = previous in ROLLOUT_PHASES
            notes += "High ground speed on ground"
            return Result(
                if (rolloutContext) FlightPhase.LANDING else FlightPhase.TAKEOFF,
                debug(true),
            )
        }

        // --- Airborne ---
        val cruise = plan.cruiseAltitude.toDouble()

        // Landing flare / very low and descending near destination.
        if (alt < LOW_ALTITUDE_MSL && distanceToDestNM != null &&
            distanceToDestNM < LOW_NEAR_DESTINATION_NM && vs < LOW_DESCENT_RATE
        ) {
            notes += "Low and close to destination"
            return Result(FlightPhase.APPROACH, debug(false))
        }

        if (vs > CLIMB_RATE) {
            // Climbing
            if (distanceToDepNM != null && distanceToDepNM < INITIAL_CLIMB_NM &&
                alt < INITIAL_CLIMB_ALTITUDE
            ) {
                notes += "Climbing near departure"
                return Result(FlightPhase.INITIAL_CLIMB, debug(false))
            }
            notes += "Climbing"
            return Result(FlightPhase.CLIMB, debug(false))
        }

        if (vs < -CLIMB_RATE) {
            // Descending
            if (distanceToDestNM != null && distanceToDestNM < TERMINAL_AREA_DESCENT_NM) {
                notes += "Descending in terminal area"
                return Result(FlightPhase.APPROACH, debug(false))
            }
            notes += "Descending"
            return Result(FlightPhase.DESCENT, debug(false))
        }

        // Roughly level.
        if (cruise > 0) {
            if (alt >= cruise - CRUISE_TOLERANCE) {
                notes += "Level at/near cruise"
                return Result(FlightPhase.CRUISE, debug(false))
            }
        } else if (alt > HIGH_LEVEL_ALTITUDE) {
            notes += "Level at high altitude"
            return Result(FlightPhase.CRUISE, debug(false))
        }

        if (distanceToDestNM != null && distanceToDestNM < TERMINAL_AREA_LEVEL_NM) {
            notes += "Level in terminal area"
            return Result(FlightPhase.APPROACH, debug(false))
        }

        // Default: maintain previous airborne phase if sensible, else climb.
        if (previous in STABLE_AIRBORNE_PHASES) {
            return Result(previous, debug(false))
        }
        notes += "Default airborne -> climb"
        return Result(FlightPhase.CLIMB, debug(false))
    }

    companion object {
        /** Feet per minute that override a missing on-ground flag. */
        const val UNREPORTED_GROUND_VERTICAL_RATE = 500.0

        /** Knots below which the aircraft counts as stopped. */
        const val STOPPED_GROUND_SPEED = 1.0

        /** Knots below which ground movement counts as taxiing rather than rolling. */
        const val TAXI_GROUND_SPEED = 40.0

        /** Feet MSL below which a descending aircraft near the field is on approach. */
        const val LOW_ALTITUDE_MSL = 2_000.0
        const val LOW_NEAR_DESTINATION_NM = 8.0
        const val LOW_DESCENT_RATE = -100.0

        /** Feet per minute that count as a genuine climb or descent. */
        const val CLIMB_RATE = 500.0

        const val INITIAL_CLIMB_NM = 15.0
        const val INITIAL_CLIMB_ALTITUDE = 8_000.0

        const val TERMINAL_AREA_DESCENT_NM = 40.0
        const val TERMINAL_AREA_LEVEL_NM = 30.0

        /** Feet below the filed cruise that still counts as being at cruise. */
        const val CRUISE_TOLERANCE = 1_500.0

        /** Feet MSL above which level flight is cruise when no cruise altitude is filed. */
        const val HIGH_LEVEL_ALTITUDE = 17_000.0

        private val ARRIVAL_PHASES = setOf(
            FlightPhase.DESCENT, FlightPhase.APPROACH, FlightPhase.LANDING, FlightPhase.TAXI_IN,
        )
        private val TAXI_IN_PHASES = setOf(
            FlightPhase.LANDING, FlightPhase.TAXI_IN, FlightPhase.DESCENT, FlightPhase.APPROACH,
        )
        private val ROLLOUT_PHASES = setOf(
            FlightPhase.APPROACH, FlightPhase.LANDING, FlightPhase.DESCENT,
        )
        private val STABLE_AIRBORNE_PHASES = setOf(
            FlightPhase.CRUISE, FlightPhase.CLIMB, FlightPhase.DESCENT, FlightPhase.APPROACH,
        )
    }
}
