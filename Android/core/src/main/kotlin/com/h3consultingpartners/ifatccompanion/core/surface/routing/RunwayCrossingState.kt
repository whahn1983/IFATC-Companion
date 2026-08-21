package com.h3consultingpartners.ifatccompanion.core.surface.routing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The runway-crossing workflow states. Extends the ATC interaction beyond the coarse
 * `ATCState.RUNWAY_CROSSING` / `.HOLDING_SHORT` with the fine-grained lifecycle the
 * simulated Ground crossing sequence needs.
 *
 * Ported from `IFATCCompanion/AirportSurface/RunwayCrossingState.swift`.
 */
@Serializable
enum class RunwayCrossingState(val rawValue: String) {
    @SerialName("noCrossingPending") NO_CROSSING_PENDING("noCrossingPending"),
    @SerialName("crossingDetectedAhead") CROSSING_DETECTED_AHEAD("crossingDetectedAhead"),
    @SerialName("approachingHoldingPosition") APPROACHING_HOLDING_POSITION("approachingHoldingPosition"),
    @SerialName("holdShortInstructionIssued") HOLD_SHORT_INSTRUCTION_ISSUED("holdShortInstructionIssued"),
    @SerialName("holdingShort") HOLDING_SHORT("holdingShort"),
    @SerialName("crossingClearanceReady") CROSSING_CLEARANCE_READY("crossingClearanceReady"),
    @SerialName("crossingClearanceIssued") CROSSING_CLEARANCE_ISSUED("crossingClearanceIssued"),
    @SerialName("awaitingPilotReadback") AWAITING_PILOT_READBACK("awaitingPilotReadback"),
    @SerialName("crossingAuthorized") CROSSING_AUTHORIZED("crossingAuthorized"),
    @SerialName("crossingInProgress") CROSSING_IN_PROGRESS("crossingInProgress"),
    @SerialName("runwayCenterlineCrossed") RUNWAY_CENTERLINE_CROSSED("runwayCenterlineCrossed"),
    @SerialName("runwayVacated") RUNWAY_VACATED("runwayVacated"),
    @SerialName("taxiResumed") TAXI_RESUMED("taxiResumed"),
    @SerialName("unauthorizedCrossingDetected") UNAUTHORIZED_CROSSING_DETECTED("unauthorizedCrossingDetected"),
    @SerialName("lowConfidenceCrossingData") LOW_CONFIDENCE_CROSSING_DATA("lowConfidenceCrossingData"),
    ;

    val title: String
        get() = when (this) {
            NO_CROSSING_PENDING -> "No crossing pending"
            CROSSING_DETECTED_AHEAD -> "Crossing detected ahead"
            APPROACHING_HOLDING_POSITION -> "Approaching hold short"
            HOLD_SHORT_INSTRUCTION_ISSUED -> "Hold short instruction issued"
            HOLDING_SHORT -> "Holding short"
            CROSSING_CLEARANCE_READY -> "Crossing clearance ready"
            CROSSING_CLEARANCE_ISSUED -> "Crossing clearance issued"
            AWAITING_PILOT_READBACK -> "Awaiting read back"
            CROSSING_AUTHORIZED -> "Crossing authorized"
            CROSSING_IN_PROGRESS -> "Crossing in progress"
            RUNWAY_CENTERLINE_CROSSED -> "Runway centerline crossed"
            RUNWAY_VACATED -> "Runway vacated"
            TAXI_RESUMED -> "Taxi resumed"
            UNAUTHORIZED_CROSSING_DETECTED -> "Unauthorized crossing detected"
            LOW_CONFIDENCE_CROSSING_DATA -> "Low-confidence crossing data"
        }

    /** Whether the aircraft is currently authorized to be on/entering the runway. */
    val isAuthorized: Boolean
        get() = when (this) {
            CROSSING_AUTHORIZED, CROSSING_IN_PROGRESS, RUNWAY_CENTERLINE_CROSSED,
            RUNWAY_VACATED, TAXI_RESUMED,
            -> true
            else -> false
        }

    /** Whether a pilot read-back is outstanding before the crossing can be authorized. */
    val awaitingReadback: Boolean
        get() = this == AWAITING_PILOT_READBACK || this == CROSSING_CLEARANCE_ISSUED

    /** Whether this state represents an active crossing sequence (map highlights it). */
    val isActiveSequence: Boolean get() = this != NO_CROSSING_PENDING && this != TAXI_RESUMED
}
