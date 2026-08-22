package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two places the ground and the airborne flow have to wait for the pilot.
 *
 * Both were missing. Ground never handed a departing aircraft to Tower to monitor, so
 * `monitoringTower` was false on every flight and the whole "number one for departure" /
 * "line up and wait" branch was unreachable. And once the pilot tuned a frequency by hand,
 * a facility change still played the hand-off and the new controller's instruction back to
 * back — the app told them to switch frequency and then talked to them on the frequency
 * they had not switched to yet.
 */
class GroundAndManualTuningTest {

    private val plan = FlightPlan.empty.copy(
        departure = "KIAH",
        destination = "KMSP",
        callsign = "United 598",
        cruiseAltitude = 35_000,
        runway = "26L",
    )

    private fun settings() = AppSettings(
        mockMode = false,
        voiceEnabled = false,
        traconCeilingFL = 180,
        initialClimbAltitudeFt = 5_000,
    )

    private fun coordinator(
        scope: TestScope,
        signals: () -> GroundHandoffSignals = { GroundHandoffSignals() },
        authorizeCrossing: () -> Unit = {},
    ) = FlightSessionCoordinator(
        scope = scope,
        clock = MutableClock(0),
        settingsProvider = ::settings,
        groundHandoffSignals = signals,
        authorizeCrossing = authorizeCrossing,
    )

    private fun taxiing() = AircraftState(
        latitude = 29.98,
        longitude = -95.34,
        altitudeMSL = 97.0,
        altitudeAGL = 0.0,
        groundSpeed = 14.0,
        // Deliberately not the runway heading: an aircraft pointing down 26L at taxi speed
        // reads as lined up, and the takeoff clearance would fire before the hand-off could
        // be observed.
        heading = 170.0,
        onGround = true,
    )

    private fun atc(c: FlightSessionCoordinator) =
        c.state.value.transcript.filter { it.sender == ATCTransmission.Sender.ATC }

    /** Taxi out to the point where Ground would hand the aircraft over. */
    private fun taxiingUnderGround(scope: TestScope, signals: () -> GroundHandoffSignals): FlightSessionCoordinator {
        val c = coordinator(scope, signals)
        c.ingestFlightPlan(plan)
        c.performPilotAction(PilotAction.CLEARANCE)
        c.readBack()
        c.performPilotAction(PilotAction.PUSHBACK)
        c.readBack()
        c.performPilotAction(PilotAction.ENGINE_START)
        c.readBack()
        // The pilot moves the radio to Ground themselves, which is what the Ramp hand-off
        // asked for and what clears the check-in owed to Ground.
        c.tuneTo(ATCFacility.GROUND)
        c.performPilotAction(PilotAction.TAXI)
        c.readBack()
        c.performPilotAction(PilotAction.TAXI)
        c.readBack()
        return c
    }

    private val approachingRunway = GroundHandoffSignals(
        isDepartureSurface = true,
        approachingRunwayHandoff = true,
    )

    // region Monitor Tower

    @Test
    fun groundHandsTheAircraftToTowerToMonitorNearTheRunway() = runTest {
        val c = taxiingUnderGround(this) { approachingRunway }
        assertEquals(ATCState.GROUND_TAXI, c.state.value.atcState)

        c.ingestAircraftState(taxiing())

        assertTrue(c.state.value.monitoringTower, "the monitor-Tower latch is what the whole branch keys on")
        assertTrue(
            atc(c).any { "monitor Tower" in it.displayText },
            "actual transcript: ${atc(c).map { it.displayText }}",
        )
    }

    /**
     * The hand-off must never cut across a clearance the pilot has not answered. Switching
     * frequency mid-instruction is exactly the failure a controller's own discipline exists
     * to prevent.
     */
    @Test
    fun theHandoffWaitsForAnOutstandingTaxiReadback() = runTest {
        val c = taxiingUnderGround(this) {
            approachingRunway.copy(awaitingTaxiReadback = true)
        }

        c.ingestAircraftState(taxiing())

        assertFalse(c.state.value.monitoringTower)
    }

    @Test
    fun theHandoffDoesNotFireOnTheArrivalSurface() = runTest {
        val c = taxiingUnderGround(this) {
            approachingRunway.copy(isDepartureSurface = false)
        }

        c.ingestAircraftState(taxiing())

        assertFalse(c.state.value.monitoringTower)
    }

    /**
     * A pilot who calls Tower anyway — typically well before the runway — is told they are
     * number one and nothing else. The takeoff clearance still comes only once they are
     * lined up.
     */
    @Test
    fun checkingInWhileMonitoringAnswersNumberOneAndDoesNotClearTheTakeoff() = runTest {
        val c = taxiingUnderGround(this) { approachingRunway }
        c.ingestAircraftState(taxiing())
        c.tuneTo(ATCFacility.TOWER)

        c.checkIn()

        assertTrue(
            atc(c).any { "number one" in it.displayText.lowercase() },
            "actual transcript: ${atc(c).map { it.displayText }}",
        )
        assertFalse(
            atc(c).any { "cleared for takeoff" in it.displayText },
            "a pilot short of the runway must not be cleared to go",
        )
    }

    // endregion

    // region Crossing read-back

    /**
     * One tap, both effects: the words go out on the frequency *and* the crossing is
     * authorized. The port had split them so each path dropped the other half.
     */
    @Test
    fun readingBackAPendingCrossingBothTransmitsAndAuthorizes() = runTest {
        var authorized = 0
        val c = coordinator(
            this,
            signals = { GroundHandoffSignals(isDepartureSurface = true, awaitingCrossingReadback = true) },
            authorizeCrossing = { authorized += 1 },
        )
        c.ingestFlightPlan(plan)
        c.post(
            ATCTransmission.create(
                sender = ATCTransmission.Sender.ATC,
                facility = ATCFacility.GROUND,
                displayText = "United 598, cross runway 27, continue taxi.",
                spokenText = "United 598, cross runway 27, continue taxi.",
                timestampMillis = 0,
                readback = ATCTransmission.Readback(
                    displayText = "Cross runway 27, United 598.",
                    spokenText = "Cross runway 27, United 598.",
                    facility = ATCFacility.GROUND,
                ),
            ),
        )

        c.readBack()

        assertEquals(1, authorized, "the crossing must be authorized by the read-back, and only by it")
        assertTrue(
            c.state.value.transcript.any {
                it.sender == ATCTransmission.Sender.PILOT && "Cross runway 27" in it.displayText
            },
            "the read-back is a transmission — it belongs on the frequency and in the transcript",
        )
    }

    // endregion

    // region Manual tuning

    private fun airborne(altitude: Double) = AircraftState(
        latitude = 32.0,
        longitude = -95.0,
        altitudeMSL = altitude,
        altitudeAGL = altitude,
        groundSpeed = 380.0,
        verticalSpeed = 2_000.0,
        heading = 10.0,
        onGround = false,
    )

    /** Descending into the destination terminal area, where Center hands over to Approach. */
    private fun descending(altitude: Double) = AircraftState(
        latitude = 44.60,
        longitude = -93.40,
        altitudeMSL = altitude,
        altitudeAGL = altitude - 841,
        groundSpeed = 280.0,
        verticalSpeed = -1_800.0,
        heading = 300.0,
        onGround = false,
    )

    /** Airborne and enroute, with the radio moved by hand at least once. */
    private fun enrouteWithTheRadioMovedByHand(scope: TestScope): FlightSessionCoordinator {
        val c = coordinator(scope)
        c.ingestFlightPlan(plan)
        c.ingestAircraftState(airborne(3_000.0))
        c.readBack()
        c.ingestAircraftState(airborne(35_000.0))
        c.readBack()
        // The pilot moves the radio themselves. From here the app must stop putting a new
        // controller on the air before they have arrived on its frequency.
        c.tuneTo(c.state.value.currentFacility, manual = true)
        return c
    }

    @Test
    fun aFacilityChangeUnderManualTuningOffersOnlyTheHandoff() = runTest {
        val c = enrouteWithTheRadioMovedByHand(this)
        val before = atc(c).size

        // Into the destination terminal area: Center hands the flight to Approach here.
        c.ingestAircraftState(descending(24_000.0))

        val posted = atc(c).drop(before)
        assertTrue(posted.isNotEmpty(), "the hand-off itself must still be issued")
        assertTrue(
            posted.all { "contact" in it.displayText },
            "only the hand-off may play: ${posted.map { it.displayText }}",
        )
        assertEquals(
            ATCFacility.APPROACH,
            c.state.value.pendingCheckInFacility,
            "the new controller is held until the pilot checks in",
        )
    }

    /** And a further fix does not re-issue it, or stack a second controller on top. */
    @Test
    fun theHeldControllerStaysHeldWhileTheFlightContinues() = runTest {
        val c = enrouteWithTheRadioMovedByHand(this)
        c.ingestAircraftState(descending(24_000.0))
        val after = atc(c).size

        c.ingestAircraftState(descending(12_000.0))
        c.ingestAircraftState(descending(9_000.0))

        assertEquals(after, atc(c).size, "nothing may play while the pilot has not checked in")
    }

    @Test
    fun theNewControllerSpeaksOnceThePilotChecksIn() = runTest {
        val c = enrouteWithTheRadioMovedByHand(this)
        c.ingestAircraftState(descending(24_000.0))
        val before = atc(c).size

        c.tuneTo(ATCFacility.APPROACH)
        c.checkIn()

        assertTrue(atc(c).size > before, "checking in is what releases the held instruction")
        assertEquals(null, c.state.value.pendingCheckInFacility)
    }

    // endregion
}
