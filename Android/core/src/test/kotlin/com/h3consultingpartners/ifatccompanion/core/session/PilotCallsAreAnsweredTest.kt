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
import kotlin.test.assertTrue

/**
 * Every pilot transmission gets an answer.
 *
 * A parity audit found three buttons that put the pilot's call in the transcript and then
 * left the frequency silent: Check In, Say Again and Unable. That is the worst way for this
 * app to fail — the pilot has done the right thing, heard nothing back, and has no way to
 * tell whether the app is broken or they missed the reply. These pin the answers.
 */
class PilotCallsAreAnsweredTest {

    private val plan = FlightPlan.empty.copy(
        departure = "KIAH",
        destination = "KORD",
        callsign = "United 598",
        cruiseAltitude = 35_000,
    )

    private fun coordinator(scope: TestScope) = FlightSessionCoordinator(
        scope = scope,
        clock = MutableClock(0),
        settingsProvider = { AppSettings(mockMode = true, voiceEnabled = false) },
    )

    private fun airborne(altitude: Double = 20_000.0) = AircraftState(
        latitude = 35.0,
        longitude = -95.0,
        altitudeMSL = altitude,
        altitudeAGL = altitude,
        groundSpeed = 420.0,
        verticalSpeed = 0.0,
        heading = 15.0,
        onGround = false,
    )

    private fun atc(coordinator: FlightSessionCoordinator) =
        coordinator.state.value.transcript.filter { it.sender == ATCTransmission.Sender.ATC }

    private fun flying(scope: TestScope): FlightSessionCoordinator {
        val c = coordinator(scope)
        c.ingestFlightPlan(plan)
        c.ingestAircraftState(airborne())
        return c
    }

    // region Check in

    @Test
    fun `checking in is always answered`() = runTest {
        val coordinator = flying(this)
        val before = atc(coordinator).size

        coordinator.tuneTo(ATCFacility.CENTER)
        coordinator.performPilotAction(PilotAction.CHECK_IN)

        val pilot = coordinator.state.value.transcript.filter { it.sender == ATCTransmission.Sender.PILOT }
        assertTrue(pilot.isNotEmpty(), "the pilot's check-in was not posted")
        assertTrue(
            atc(coordinator).size > before,
            "the pilot checked in and the frequency stayed silent",
        )
    }

    @Test
    fun `a second check-in is answered too`() = runTest {
        // The pilot taps twice, or calls up again after a gap. Each call gets a reply: when
        // the state machine has something for that frequency the conversation moves on,
        // and when it does not the controller acknowledges with radar contact rather than
        // leaving the second call hanging.
        val coordinator = flying(this)
        coordinator.tuneTo(ATCFacility.CENTER)
        coordinator.performPilotAction(PilotAction.CHECK_IN)
        val afterFirst = atc(coordinator).size

        coordinator.performPilotAction(PilotAction.CHECK_IN)

        assertTrue(atc(coordinator).size > afterFirst, "the second check-in was not answered")
    }

    @Test
    fun `a check-in the flow has nothing for is answered with radar contact`() = runTest {
        // Parked at the gate with the conversation already past Clearance. There is nothing
        // ahead for that frequency, so the reply is an acknowledgement — not silence, and
        // not the next clearance in the flow.
        val coordinator = flying(this)
        coordinator.tuneTo(ATCFacility.CENTER)
        coordinator.performPilotAction(PilotAction.CHECK_IN)
        coordinator.performPilotAction(PilotAction.CHECK_IN)
        coordinator.performPilotAction(PilotAction.CHECK_IN)

        assertTrue(
            atc(coordinator).any { it.displayText.contains("radar contact", ignoreCase = true) },
            "no acknowledgement anywhere in ${atc(coordinator).map { it.displayText }}",
        )
    }

    // endregion

    // region Say again

    @Test
    fun `say again makes the controller repeat the last call`() = runTest {
        val coordinator = flying(this)
        coordinator.tuneTo(ATCFacility.CENTER)
        coordinator.performPilotAction(PilotAction.CHECK_IN)
        val lastBefore = atc(coordinator).last().displayText

        coordinator.sayAgain()

        val lines = atc(coordinator)
        assertEquals(
            lastBefore,
            lines.last().displayText,
            "the controller did not repeat itself: ${lines.takeLast(2).map { it.displayText }}",
        )
        assertTrue(
            lines.count { it.displayText == lastBefore } >= 2,
            "the repeat replaced the original instead of following it",
        )
    }

    @Test
    fun `say again with nothing to repeat does not invent a call`() = runTest {
        // Before any controller has spoken there is nothing to say again, and making
        // something up would be worse than the silence.
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.sayAgain()
        assertTrue(atc(coordinator).isEmpty(), "a repeat was invented: ${atc(coordinator)}")
    }

    // endregion

    // region Unable

    @Test
    fun `unable is answered with an alternative the pilot can fly`() = runTest {
        // Silence after "unable" leaves the aircraft with a clearance it has just refused
        // and no replacement — the one state the app must never leave a pilot in.
        val coordinator = flying(this)
        val before = atc(coordinator).size

        coordinator.unable()

        val reply = atc(coordinator).drop(before).lastOrNull()
        assertTrue(reply != null, "\"unable\" went unanswered")
        assertTrue(reply.displayText.contains("maintain", ignoreCase = true), reply.displayText)
        assertTrue(
            reply.displayText.contains("advise able to comply", ignoreCase = true),
            reply.displayText,
        )
    }

    @Test
    fun `the unable reply carries a read-back so the gate can close on it`() = runTest {
        val coordinator = flying(this)
        coordinator.unable()

        val reply = atc(coordinator).last()
        val readback = reply.readback
        assertTrue(readback != null, "the alternative clearance had no read-back to answer")
        assertTrue(readback.displayText.contains("Maintain"), readback.displayText)
    }

    @Test
    fun `unable never offers an altitude below the initial climb`() = runTest {
        // The alternative is the higher of the current assignment and the initial climb, so
        // refusing a climb cannot be answered with a descent into terrain.
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.ingestAircraftState(airborne(altitude = 3_000.0))
        coordinator.unable()

        val reply = atc(coordinator).last().displayText
        assertTrue(
            !reply.contains("maintain 0", ignoreCase = true) &&
                !reply.contains("field elevation", ignoreCase = true),
            "unable was answered with an unflyable altitude: $reply",
        )
    }

    // endregion

    @Test
    fun `a standby session answers nothing at all`() = runTest {
        // A staffed human controller is on frequency. The app must stay off the radio, and
        // that has to hold for the replies just added as much as for the calls.
        val coordinator = flying(this)
        coordinator.tuneTo(ATCFacility.CENTER)
        coordinator.setSimulateStaffedATC(true)
        assertTrue(coordinator.state.value.companionStandby, "the session did not stand by")
        val before = coordinator.state.value.transcript.size

        coordinator.performPilotAction(PilotAction.CHECK_IN)
        coordinator.sayAgain()
        coordinator.unable()

        assertEquals(
            before,
            coordinator.state.value.transcript.size,
            "the app talked over a staffed controller",
        )
    }
}
