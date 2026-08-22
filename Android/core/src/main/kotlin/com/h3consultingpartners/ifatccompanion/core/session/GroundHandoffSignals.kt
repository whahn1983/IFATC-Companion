package com.h3consultingpartners.ifatccompanion.core.session

/**
 * What the airport surface knows about a departure taxi, reduced to the four facts the ATC
 * flow acts on.
 *
 * Written as its own type rather than four lambdas because they are read together and must
 * describe the same instant: a hand-off decided from an "approaching the runway" that is a
 * tick newer than the "waiting on a taxi read-back" beside it would cut across a clearance
 * the pilot has not answered.
 *
 * The flight session stays independent of the surface-routing subsystem — it asks for four
 * booleans and does not care where they came from.
 */
data class GroundHandoffSignals(
    /** Whether the surface currently loaded is the *departure* field's. */
    val isDepartureSurface: Boolean = false,
    /**
     * The aircraft is within the monitor-Tower lead distance of the departure runway — the
     * point at which Ground hands it to Tower to monitor, at the red sign short of the
     * runway.
     */
    val approachingRunwayHandoff: Boolean = false,
    /** Close enough to the hold-short that Tower would issue "line up and wait". */
    val approachingRunwayLineup: Boolean = false,
    /** A crossing or taxi clearance the pilot has not read back yet. */
    val awaitingCrossingReadback: Boolean = false,
    val awaitingTaxiReadback: Boolean = false,
)
