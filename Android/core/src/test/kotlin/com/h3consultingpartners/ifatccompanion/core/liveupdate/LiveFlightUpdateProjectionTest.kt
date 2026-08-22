package com.h3consultingpartners.ifatccompanion.core.liveupdate

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.session.FlightSessionState
import com.h3consultingpartners.ifatccompanion.core.session.PilotAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the pilot is told while the phone is in their pocket. A wrong answer here is the
 * least visible kind, so the rules are pinned rather than left to the notification code.
 */
class LiveFlightUpdateProjectionTest {

    private val now = 1_700_000_000_000L

    private fun state(
        plan: FlightPlan = FlightPlan(callsign = "UAL598", departure = "KIAH", destination = "KMSP"),
        aircraft: AircraftState = AircraftState.empty,
        facility: ATCFacility = ATCFacility.CENTER,
        phase: FlightPhase = FlightPhase.CRUISE,
        awaitingReadback: Boolean = false,
        availableActions: Set<PilotAction> = emptySet(),
        pendingCheckInFacility: ATCFacility? = null,
        standby: Boolean = false,
    ) = FlightSessionState(
        flightPlan = plan,
        aircraftState = aircraft,
        phase = phase,
        currentFacility = facility,
        awaitingReadback = awaitingReadback,
        availableActions = availableActions,
        pendingCheckInFacility = pendingCheckInFacility,
        companionStandby = standby,
    )

    @Test
    fun theCardCarriesTheFlightAndItsTelemetry() {
        val update = LiveFlightUpdateProjection.from(
            state(
                aircraft = AircraftState.empty.copy(
                    altitudeMSL = 36_950.4,
                    heading = 12.6,
                    groundSpeed = 451.2,
                ),
            ),
            now,
        )
        assertEquals("IFATC Companion · UAL598", update.flightTitle)
        assertEquals("KIAH → KMSP", update.route)
        assertEquals("Cruise", update.phase)
        assertEquals("Center", update.facility)
        assertEquals(36_950, update.altitude)
        assertEquals(13, update.heading)
        assertEquals(451, update.speed)
        assertEquals(now, update.asOfMillis)
    }

    /** A heading of 360 is 0 — a card that says "heading 360" after a wrap reads as a bug. */
    @Test
    fun headingWrapsRatherThanReading360() {
        val update = LiveFlightUpdateProjection.from(
            state(aircraft = AircraftState.empty.copy(heading = 359.7)),
            now,
        )
        assertEquals(0, update.heading)
    }

    /** No telemetry yet is zeros, not a blank card. */
    @Test
    fun anEmptySnapshotStillRenders() {
        val update = LiveFlightUpdateProjection.from(state(plan = FlightPlan.empty), now)
        assertEquals(0, update.altitude)
        assertEquals("", update.route)
        assertEquals(LiveFlightUpdateProjection.DEFAULT_CALLSIGN, update.callsign)
    }

    @Test
    fun aHalfKnownRouteShowsTheHalfItKnows() {
        assertEquals("KIAH", LiveFlightUpdateProjection.route("KIAH", ""))
        assertEquals("KMSP", LiveFlightUpdateProjection.route("", "KMSP"))
        assertEquals("", LiveFlightUpdateProjection.route("", ""))
    }

    /** The callsign falls back to the airline and flight number before the generic label. */
    @Test
    fun theCallsignFallsBackToTheAirlineAndNumber() {
        val update = LiveFlightUpdateProjection.from(
            state(plan = FlightPlan(airline = "UAL", flightNumber = "598")),
            now,
        )
        assertEquals("UAL598", update.callsign)
    }

    // region Actions

    /** The buttons offered must be ones the engine will actually accept. */
    @Test
    fun readBackIsOfferedOnlyWhileTheGateIsClosed() {
        assertTrue(LiveFlightUpdateProjection.from(state(awaitingReadback = true), now).canReadBack)
        assertFalse(LiveFlightUpdateProjection.from(state(awaitingReadback = false), now).canReadBack)
    }

    @Test
    fun checkInMirrorsTheSessionsOwnAvailability() {
        val available = state(availableActions = setOf(PilotAction.CHECK_IN))
        assertTrue(LiveFlightUpdateProjection.from(available, now).canCheckIn)
        assertFalse(LiveFlightUpdateProjection.from(state(), now).canCheckIn)
    }

    /**
     * Standby is the one state where the companion must not talk. A notification action
     * would be the single remaining way it could still transmit over a human controller,
     * so both are suppressed regardless of what the session would otherwise allow.
     */
    @Test
    fun standbySuppressesEveryAction() {
        val update = LiveFlightUpdateProjection.from(
            state(
                awaitingReadback = true,
                availableActions = setOf(PilotAction.CHECK_IN),
                standby = true,
            ),
            now,
        )
        assertTrue(update.standby)
        assertFalse(update.canReadBack)
        assertFalse(update.canCheckIn)
        assertFalse(update.hasPendingResponse)
    }

    // endregion

    /** Only a genuine hand-off is named as "next" — the current facility never is. */
    @Test
    fun theNextFacilityIsOnlyAPendingHandoff() {
        assertEquals(
            "Approach",
            LiveFlightUpdateProjection.from(
                state(facility = ATCFacility.CENTER, pendingCheckInFacility = ATCFacility.APPROACH),
                now,
            ).nextFacility,
        )
        assertNull(
            LiveFlightUpdateProjection.from(
                state(facility = ATCFacility.CENTER, pendingCheckInFacility = ATCFacility.CENTER),
                now,
            ).nextFacility,
        )
        assertNull(LiveFlightUpdateProjection.from(state(), now).nextFacility)
    }

    /** The facility icon is a semantic key the notification maps — never an SF Symbol. */
    @Test
    fun theFacilityIconIsASemanticKey() {
        val update = LiveFlightUpdateProjection.from(state(facility = ATCFacility.TOWER), now)
        assertEquals(ATCFacility.TOWER.iconKey, update.facilityIconKey)
        assertFalse(update.facilityIconKey.contains("."), "an SF Symbol name would have dots in it")
    }
}
