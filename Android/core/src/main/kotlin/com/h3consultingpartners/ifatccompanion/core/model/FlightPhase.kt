package com.h3consultingpartners.ifatccompanion.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Physical phase of flight inferred from aircraft state by `PhaseDetector`, and
 * produced directly by `MockSimulatorFeed`.
 *
 * Ported from `IFATCCompanion/Models/FlightPhase.swift`.
 */
@Serializable
enum class FlightPhase(val rawValue: String) {
    @SerialName("preflight") PREFLIGHT("preflight"),
    @SerialName("taxiOut") TAXI_OUT("taxiOut"),
    @SerialName("takeoff") TAKEOFF("takeoff"),
    @SerialName("initialClimb") INITIAL_CLIMB("initialClimb"),
    @SerialName("climb") CLIMB("climb"),
    @SerialName("cruise") CRUISE("cruise"),
    @SerialName("descent") DESCENT("descent"),
    @SerialName("approach") APPROACH("approach"),
    @SerialName("landing") LANDING("landing"),
    @SerialName("taxiIn") TAXI_IN("taxiIn"),
    @SerialName("parked") PARKED("parked"),
    @SerialName("unknown") UNKNOWN("unknown"),
    ;

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            PREFLIGHT -> "Preflight"
            TAXI_OUT -> "Taxi Out"
            TAKEOFF -> "Takeoff"
            INITIAL_CLIMB -> "Initial Climb"
            CLIMB -> "Climb"
            CRUISE -> "Cruise"
            DESCENT -> "Descent"
            APPROACH -> "Approach"
            LANDING -> "Landing"
            TAXI_IN -> "Taxi In"
            PARKED -> "Parked"
            UNKNOWN -> "Unknown"
        }

    /** Whether the aircraft is expected to be on the ground in this phase. */
    val isGround: Boolean
        get() = when (this) {
            PREFLIGHT, TAXI_OUT, TAXI_IN, PARKED -> true
            else -> false
        }

    companion object {
        /** Demo ordering used by the mock feed "advance phase" control. */
        val demoSequence: List<FlightPhase> = listOf(
            PREFLIGHT, TAXI_OUT, TAKEOFF, INITIAL_CLIMB, CLIMB,
            CRUISE, DESCENT, APPROACH, LANDING, TAXI_IN, PARKED,
        )

        fun fromRawValue(raw: String): FlightPhase? = entries.firstOrNull { it.rawValue == raw }
    }
}
