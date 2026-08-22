package com.h3consultingpartners.ifatccompanion.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Deterministic ATC interaction state machine states. Distinct from [FlightPhase]
 * (physical) — this tracks the conversational / procedural position in a normal
 * IFR flight.
 *
 * Ported from `IFATCCompanion/Models/ATCState.swift`.
 */
@Serializable
enum class ATCState(val rawValue: String) {
    @SerialName("notConnected") NOT_CONNECTED("notConnected"),
    @SerialName("connectedIdle") CONNECTED_IDLE("connectedIdle"),
    @SerialName("clearance") CLEARANCE("clearance"),
    @SerialName("pushback") PUSHBACK("pushback"),
    @SerialName("engineStart") ENGINE_START("engineStart"),
    @SerialName("pushbackTaxi") PUSHBACK_TAXI("pushbackTaxi"),
    @SerialName("groundTaxi") GROUND_TAXI("groundTaxi"),
    @SerialName("runwayCrossing") RUNWAY_CROSSING("runwayCrossing"),
    @SerialName("holdingShort") HOLDING_SHORT("holdingShort"),
    @SerialName("lineUpWait") LINE_UP_WAIT("lineUpWait"),
    @SerialName("towerDeparture") TOWER_DEPARTURE("towerDeparture"),
    @SerialName("initialClimb") INITIAL_CLIMB("initialClimb"),
    @SerialName("departure") DEPARTURE("departure"),
    @SerialName("climb") CLIMB("climb"),
    @SerialName("center") CENTER("center"),
    @SerialName("cruise") CRUISE("cruise"),
    @SerialName("topOfDescent") TOP_OF_DESCENT("topOfDescent"),
    @SerialName("descent") DESCENT("descent"),
    @SerialName("approach") APPROACH("approach"),
    @SerialName("final") FINAL("final"),
    @SerialName("landing") LANDING("landing"),
    @SerialName("runwayExit") RUNWAY_EXIT("runwayExit"),
    @SerialName("groundArrival") GROUND_ARRIVAL("groundArrival"),
    @SerialName("parked") PARKED("parked"),
    @SerialName("abnormal") ABNORMAL("abnormal"),
    ;

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            NOT_CONNECTED -> "Not Connected"
            CONNECTED_IDLE -> "Connected"
            CLEARANCE -> "Clearance"
            PUSHBACK -> "Pushback"
            ENGINE_START -> "Engine Start"
            PUSHBACK_TAXI -> "Pushback / Taxi"
            GROUND_TAXI -> "Ground Taxi"
            RUNWAY_CROSSING -> "Runway Crossing"
            HOLDING_SHORT -> "Holding Short"
            LINE_UP_WAIT -> "Line Up & Wait"
            TOWER_DEPARTURE -> "Tower"
            INITIAL_CLIMB -> "Initial Climb"
            DEPARTURE -> "Departure"
            CLIMB -> "Climb"
            CENTER -> "Center"
            CRUISE -> "Cruise"
            TOP_OF_DESCENT -> "Top of Descent"
            DESCENT -> "Descent"
            APPROACH -> "Approach"
            FINAL -> "Final"
            LANDING -> "Landing"
            RUNWAY_EXIT -> "Runway Exit"
            GROUND_ARRIVAL -> "Ground (Arrival)"
            PARKED -> "Parked"
            ABNORMAL -> "Off Route"
        }

    /** The controller facility that normally works this state. */
    val facility: ATCFacility
        get() = when (this) {
            NOT_CONNECTED, CONNECTED_IDLE, PARKED -> ATCFacility.GROUND
            CLEARANCE -> ATCFacility.CLEARANCE
            // Pushback and engine start are Ramp (local/company), not FAA Ground ATC.
            PUSHBACK, ENGINE_START -> ATCFacility.RAMP
            PUSHBACK_TAXI, GROUND_TAXI, RUNWAY_CROSSING, HOLDING_SHORT -> ATCFacility.GROUND
            LINE_UP_WAIT, TOWER_DEPARTURE, LANDING, FINAL, RUNWAY_EXIT -> ATCFacility.TOWER
            INITIAL_CLIMB, DEPARTURE -> ATCFacility.DEPARTURE
            CLIMB, CENTER, CRUISE, TOP_OF_DESCENT -> ATCFacility.CENTER
            DESCENT, APPROACH -> ATCFacility.APPROACH
            GROUND_ARRIVAL -> ATCFacility.GROUND
            ABNORMAL -> ATCFacility.CENTER
        }

    /**
     * Whether the controller call issued on entering this state carries an
     * instruction the pilot is expected to read back. Automatic (telemetry-driven)
     * flow holds on these until the pilot reads back, so calls never fire
     * back-to-back. Courtesy calls (radar contact at cruise, the arrival block-in)
     * and the non-speaking states do not expect a readback.
     */
    val expectsReadback: Boolean
        get() = when (this) {
            CLEARANCE, PUSHBACK, ENGINE_START, PUSHBACK_TAXI, GROUND_TAXI,
            RUNWAY_CROSSING, HOLDING_SHORT, LINE_UP_WAIT, TOWER_DEPARTURE,
            INITIAL_CLIMB, DEPARTURE, CLIMB, DESCENT, APPROACH, FINAL,
            LANDING, RUNWAY_EXIT, GROUND_ARRIVAL,
            -> true

            NOT_CONNECTED, CONNECTED_IDLE, CENTER, CRUISE, TOP_OF_DESCENT,
            PARKED, ABNORMAL,
            -> false
        }

    /**
     * The pilot-driven pre-departure ground sequence (clearance → pushback → engine
     * start → taxi → holding short → line up and wait). These steps are advanced
     * manually via the response buttons so the flow never skips a phase.
     */
    val isManualGroundFlow: Boolean
        get() = when (this) {
            CLEARANCE, PUSHBACK, ENGINE_START, PUSHBACK_TAXI, GROUND_TAXI,
            RUNWAY_CROSSING, HOLDING_SHORT, LINE_UP_WAIT,
            -> true

            else -> false
        }

    companion object {
        fun fromRawValue(raw: String): ATCState? = entries.firstOrNull { it.rawValue == raw }
    }
}
