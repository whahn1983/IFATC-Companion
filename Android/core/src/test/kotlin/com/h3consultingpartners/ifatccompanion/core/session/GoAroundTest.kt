package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.atc.GoAroundPattern
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * The missed approach.
 *
 * Before this existed the go-around button posted the pilot's call and nothing answered it:
 * the aircraft climbed away from a runway it was still cleared to land on, and the automatic
 * flow carried on as though the approach were proceeding. The failure mode is the worst kind
 * — the app keeps talking, and everything it says is about a landing that is not happening.
 */
class GoAroundTest {

    private val plan = FlightPlan.empty.copy(
        departure = "KIAH",
        destination = "KORD",
        callsign = "United 598",
        runway = "09",
    )

    private fun coordinator(
        scope: TestScope,
        spoken: MutableList<ATCTransmission> = mutableListOf(),
    ) = FlightSessionCoordinator(
        scope = scope,
        clock = MutableClock(0),
        settingsProvider = { AppSettings(mockMode = false, voiceEnabled = false) },
        speak = { spoken += it },
    )

    private fun onApproach(altitude: Double) = AircraftState(
        latitude = 41.9,
        longitude = -87.9,
        altitudeMSL = altitude,
        altitudeAGL = altitude,
        groundSpeed = 160.0,
        verticalSpeed = -600.0,
        heading = 90.0,
        onGround = false,
    )

    private fun atcLines(state: FlightSessionState) =
        state.transcript.filter { it.sender == ATCTransmission.Sender.ATC }.map { it.displayText }

    // region The crosswind vector

    @Test
    fun `a left-hand pattern turns ninety degrees left off the runway`() {
        // Wrong by 180° still sounds like a plausible instruction on the radio. Checking the
        // number is the only way this is ever caught.
        assertEquals(270, GoAroundPattern.crosswindHeading(360, leftTraffic = true))
        assertEquals(90, GoAroundPattern.crosswindHeading(180, leftTraffic = true))
        assertEquals(0, GoAroundPattern.crosswindHeading(90, leftTraffic = true))
    }

    @Test
    fun `a right-hand pattern turns the other way`() {
        assertEquals(90, GoAroundPattern.crosswindHeading(360, leftTraffic = false))
        assertEquals(180, GoAroundPattern.crosswindHeading(90, leftTraffic = false))
    }

    @Test
    fun `a westerly runway does not produce a negative heading`() {
        // 270 − 90 is 180, but 90 − 90 is 0 and 0 − 90 would be −90: a vector the
        // phraseology would read out as "turn heading minus ninety".
        for (heading in 0..359) {
            val result = GoAroundPattern.crosswindHeading(heading, leftTraffic = true)
            assertTrue(result in 0..359, "heading $heading produced $result")
        }
    }

    // endregion

    // region The exchange

    @Test
    fun `a go-around puts Tower's pattern instruction on the radio, not just the pilot's call`() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.ingestAircraftState(onApproach(2_500.0))

        coordinator.performPilotAction(PilotAction.GO_AROUND)

        val state = coordinator.state.value
        val pilot = state.transcript.filter { it.sender == ATCTransmission.Sender.PILOT }
        assertTrue(pilot.isNotEmpty(), "the pilot's go-around call was not posted")

        // Tower's instruction, in full. Asserted element by element because a go-around
        // missing any one of them is an instruction the pilot cannot fly: the vector, the
        // altitude to climb to, the pattern direction, and where to go next.
        val tower = atcLines(state).lastOrNull { it.contains("go around", ignoreCase = true) }
        assertNotNull(tower, "Tower said nothing back: ${atcLines(state)}")
        assertTrue(tower.contains("turn left heading"), tower)
        assertTrue(tower.contains("climb and maintain"), tower)
        assertTrue(tower.contains("make left traffic runway"), tower)
        assertTrue(tower.contains("contact Approach on"), tower)

        // And the read-back has to move the radio, or the pilot is left calling Tower about
        // an approach Tower is no longer running.
        val readback = state.transcript.last { it.sender == ATCTransmission.Sender.ATC }.readback
        assertNotNull(readback, "no read-back to tune the radio with")
        assertEquals(ATCFacility.APPROACH, readback.tuneTo)
    }

    @Test
    fun `the pattern altitude becomes the assigned altitude`() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.ingestAircraftState(onApproach(2_500.0))

        coordinator.performPilotAction(PilotAction.GO_AROUND)

        // The pattern is 3,000 ft above the field, in MSL, rounded up to the next thousand.
        // Whatever the field elevation, it has to clear the ground it is flown over — the
        // aircraft is being sent round, not into the terrain.
        val assigned = coordinator.state.value.assignedAltitude
        assertTrue(assigned >= 3_000, "pattern altitude $assigned is below the terminal minimum")
        assertEquals(0, assigned % 1_000, "pattern altitude $assigned is not a round thousand")

        // And Tower has to have said the number it assigned, or the read-back is a lie.
        val tower = atcLines(coordinator.state.value).last { it.contains("go around", ignoreCase = true) }
        assertTrue(
            tower.contains("$assigned") || tower.contains("${assigned / 1_000}"),
            "Tower assigned $assigned but said: $tower",
        )
    }

    @Test
    fun `the automatic flow holds while the aircraft is in the pattern`() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.ingestAircraftState(onApproach(2_500.0))
        coordinator.performPilotAction(PilotAction.GO_AROUND)
        val afterGoAround = coordinator.state.value.transcript.size

        // The missed-approach climb. Without the hold this is read as "descending toward
        // the approach" and the aircraft is cleared to land on the runway it just left.
        repeat(5) { coordinator.ingestAircraftState(onApproach(3_000.0).copy(verticalSpeed = 1_200.0)) }

        assertEquals(
            afterGoAround,
            coordinator.state.value.transcript.size,
            "the automatic flow kept talking through the go-around: " +
                atcLines(coordinator.state.value).takeLast(3),
        )
    }

    // endregion

    // region Coming back round

    @Test
    fun `checking in with Approach clears the aircraft to continue inbound`() = runTest {
        // Driven through the check-in button, the way the pilot reaches it and the way iOS
        // drives its own test — not by calling the private resume directly, which would
        // pass while the button that leads to it was wired to nothing.
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.ingestAircraftState(onApproach(2_500.0))
        coordinator.performPilotAction(PilotAction.GO_AROUND)

        coordinator.tuneTo(ATCFacility.APPROACH)
        coordinator.performPilotAction(PilotAction.CHECK_IN)

        val state = coordinator.state.value
        assertEquals(ATCState.APPROACH, state.atcState, "the conversation was not rewound to the approach")
        assertEquals(ATCFacility.APPROACH, state.currentFacility)
        assertTrue(
            atcLines(state).any { it.contains("continue", ignoreCase = true) },
            "Approach never cleared the aircraft to continue inbound: ${atcLines(state)}",
        )
    }

    @Test
    fun `the automatic flow resumes once the pilot has re-established`() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.ingestAircraftState(onApproach(2_500.0))
        coordinator.performPilotAction(PilotAction.GO_AROUND)
        coordinator.tuneTo(ATCFacility.APPROACH)
        coordinator.performPilotAction(PilotAction.CHECK_IN)
        val afterResume = coordinator.state.value.transcript.size

        // The second approach. The whole point of the rewind is that this replays; a hold
        // that never lifted would leave the pilot in the pattern with nobody to talk to.
        repeat(6) { coordinator.ingestAircraftState(onApproach(1_200.0)) }

        assertTrue(
            coordinator.state.value.transcript.size >= afterResume,
            "the flow did not resume after the pilot re-established",
        )
        assertFalse(
            coordinator.state.value.companionStandby,
            "the session was left in standby",
        )
    }

    @Test
    fun `a go-around survives a relaunch`() = runTest {
        // The snapshot field has existed since the port began and was written by nothing, so
        // a relaunch mid-pattern came back with the flow unheld — and immediately cleared
        // the aircraft to land on the runway it had gone around from.
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.ingestAircraftState(onApproach(2_500.0))
        coordinator.performPilotAction(PilotAction.GO_AROUND)

        val snapshot = coordinator.captureSnapshot()
        assertEquals(true, snapshot.goAroundInProgress, "the go-around was not captured")

        val relaunched = coordinator(this)
        relaunched.ingestFlightPlan(plan)
        relaunched.restore(snapshot)
        relaunched.ingestAircraftState(onApproach(2_500.0))
        val afterRestore = relaunched.state.value.transcript.size

        repeat(5) { relaunched.ingestAircraftState(onApproach(3_000.0).copy(verticalSpeed = 1_200.0)) }
        assertEquals(
            afterRestore,
            relaunched.state.value.transcript.size,
            "the restored session did not hold the flow",
        )
    }

    @Test
    fun `starting a new flight forgets the go-around`() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.ingestAircraftState(onApproach(2_500.0))
        coordinator.performPilotAction(PilotAction.GO_AROUND)

        coordinator.resetForNewFlight()
        assertEquals(false, coordinator.captureSnapshot().goAroundInProgress)
    }

    // endregion
}
