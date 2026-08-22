package com.h3consultingpartners.ifatccompanion.core.atc

import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyEngine

/**
 * Deterministic detection of "lined up on the departure runway" from telemetry, used by
 * the automatic ATC flow to issue the takeoff clearance once the aircraft has entered
 * and aligned with the runway. Without a per-runway position model we use a robust
 * proxy: on the ground, low ground speed, and heading aligned with the assigned
 * runway's magnetic heading.
 *
 * Ported from `IFATCCompanion/ATC/RunwayLineupDetector.swift`.
 */
data class RunwayLineupDetector(
    /** Heading alignment tolerance (degrees) for "lined up". */
    val headingToleranceDeg: Double = DEFAULT_HEADING_TOLERANCE_DEG,
    /** Maximum ground speed (kts) still considered "lining up / holding". */
    val maxLineupGroundSpeed: Double = DEFAULT_MAX_LINEUP_GROUND_SPEED,
    /**
     * Ground speed (kts) above which the aircraft is considered to be on its takeoff roll
     * while aligned with the runway.
     */
    val rollGroundSpeed: Double = DEFAULT_ROLL_GROUND_SPEED,
) {

    /**
     * True when the aircraft appears established on the runway centreline at low speed
     * (entered the runway and aligned).
     */
    fun isLinedUp(state: AircraftState, runway: String): Boolean {
        if (!(state.onGround ?: true)) return false
        if (headingAligned(state, runway) != true) return false
        val gs = state.groundSpeed ?: 0.0
        return gs <= maxLineupGroundSpeed
    }

    /** True when aligned with the runway and accelerating down it (takeoff roll). */
    fun isDepartingRoll(state: AircraftState, runway: String): Boolean {
        if (headingAligned(state, runway) != true) return false
        val gs = state.groundSpeed ?: 0.0
        return gs > rollGroundSpeed
    }

    /**
     * True when airborne, descending, low, and aligned with the landing runway — i.e.
     * established on final. Used to issue the approach clearance even when the autopilot
     * approach mode (APPR) is not exposed by Infinite Flight.
     */
    fun isOnFinalApproach(state: AircraftState, runway: String): Boolean {
        if (state.onGround == true) return false
        if (headingAligned(state, runway) != true) return false
        val vs = state.verticalSpeed ?: 0.0
        val agl = state.altitudeAGL ?: (state.altitudeMSL ?: 0.0)
        return vs < FINAL_DESCENT_RATE && agl < FINAL_MAX_AGL
    }

    /**
     * Whether the aircraft heading is within tolerance of the runway heading. Returns
     * null when the runway/heading can't be determined.
     */
    private fun headingAligned(state: AircraftState, runway: String): Boolean? {
        val rwy = PhraseologyEngine.runwayHeading(runway) ?: return null
        val hdg = state.heading ?: return null
        return PhraseologyEngine.angularDiff(hdg, rwy.toDouble()) <= headingToleranceDeg
    }

    companion object {
        const val DEFAULT_HEADING_TOLERANCE_DEG = 18.0
        const val DEFAULT_MAX_LINEUP_GROUND_SPEED = 45.0
        const val DEFAULT_ROLL_GROUND_SPEED = 30.0

        /** Feet per minute below which a low, aligned aircraft is descending on final. */
        const val FINAL_DESCENT_RATE = -100.0

        /** Feet AGL below which an aligned, descending aircraft is established on final. */
        const val FINAL_MAX_AGL = 4_000.0
    }
}
