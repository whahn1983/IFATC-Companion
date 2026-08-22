package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * When Tower clears the aircraft for take-off.
 *
 * Android issued the clearance the instant the nose swung onto the centreline — mid-turn,
 * before the aircraft had settled. iOS waits five seconds with the aircraft lined up *and
 * stopped*, re-checks, and only then clears; an aircraft already rolling is cleared at once,
 * and one that lines up and then taxis clear is not cleared at all.
 */
class TakeoffClearanceTimingTest {

    private val plan = FlightPlan.empty.copy(
        departure = "KIAH",
        destination = "KMSP",
        airline = "United",
        flightNumber = "598",
        callsign = "United 598",
        departureRunway = "27",
        cruiseAltitude = 35_000,
    )

    private val clock = MutableClock(1_700_000_000_000L)

    private fun coordinator(scope: TestScope) = FlightSessionCoordinator(
        scope = scope,
        clock = clock,
        settingsProvider = { AppSettings(mockMode = true, voiceEnabled = false) },
    )

    /** On runway 27: heading 270, on the ground. */
    private fun onRunway(groundSpeed: Double) = AircraftState(
        latitude = 29.98,
        longitude = -95.34,
        altitudeMSL = 97.0,
        altitudeAGL = 0.0,
        groundSpeed = groundSpeed,
        verticalSpeed = 0.0,
        heading = 270.0,
        onGround = true,
    )

    /** Taxiing, nowhere near aligned with 27. */
    private fun onTaxiway() = onRunway(groundSpeed = 12.0).copy(heading = 20.0)

    private fun atLineUp(scope: TestScope): FlightSessionCoordinator {
        val coordinator = coordinator(scope)
        coordinator.ingestFlightPlan(plan)
        coordinator.tuneTo(ATCFacility.TOWER)
        coordinator.advanceAndPost(
            com.h3consultingpartners.ifatccompanion.core.model.ATCState.LINE_UP_WAIT,
            announceHandoff = false,
        )
        return coordinator
    }

    private fun cleared(c: FlightSessionCoordinator) =
        c.state.value.transcript.any { it.displayText.contains("cleared for takeoff", ignoreCase = true) }

    @Test
    fun `an aircraft that has just lined up is not cleared yet`() = runTest {
        val coordinator = atLineUp(this)

        coordinator.ingestAircraftState(onRunway(groundSpeed = 0.0))

        assertTrue(!cleared(coordinator), "cleared the instant it lined up")
    }

    @Test
    fun `it is cleared once it has held for the delay`() = runTest {
        val coordinator = atLineUp(this)
        coordinator.ingestAircraftState(onRunway(groundSpeed = 0.0))

        clock.advance(FlightSessionCoordinator.TAKEOFF_CLEARANCE_DELAY_MILLIS)
        coordinator.ingestAircraftState(onRunway(groundSpeed = 0.0))

        assertTrue(cleared(coordinator), coordinator.state.value.transcript.map { it.displayText }.toString())
    }

    @Test
    fun `an aircraft already rolling is cleared at once`() = runTest {
        // The take-off run has begun, so the clearance is overdue, not early.
        val coordinator = atLineUp(this)

        coordinator.ingestAircraftState(onRunway(groundSpeed = 60.0))

        assertTrue(cleared(coordinator), "an aircraft on its roll was left uncleared")
    }

    @Test
    fun `lining up and then taxiing clear cancels the clearance`() = runTest {
        val coordinator = atLineUp(this)
        coordinator.ingestAircraftState(onRunway(groundSpeed = 0.0))

        // Thought better of it and vacated.
        clock.advance(3_000L)
        coordinator.ingestAircraftState(onTaxiway())
        clock.advance(FlightSessionCoordinator.TAKEOFF_CLEARANCE_DELAY_MILLIS)
        coordinator.ingestAircraftState(onTaxiway())

        assertTrue(!cleared(coordinator), "cleared an aircraft that had left the runway")
    }

    @Test
    fun `the countdown restarts after leaving and re-entering the runway`() = runTest {
        val coordinator = atLineUp(this)
        coordinator.ingestAircraftState(onRunway(groundSpeed = 0.0))
        clock.advance(4_000L)
        coordinator.ingestAircraftState(onTaxiway())

        // Back on the centreline: the earlier four seconds must not count toward this hold.
        clock.advance(2_000L)
        coordinator.ingestAircraftState(onRunway(groundSpeed = 0.0))
        assertTrue(!cleared(coordinator), "the old countdown carried over")

        clock.advance(FlightSessionCoordinator.TAKEOFF_CLEARANCE_DELAY_MILLIS)
        coordinator.ingestAircraftState(onRunway(groundSpeed = 0.0))
        assertTrue(cleared(coordinator), "the restarted countdown never fired")
    }
}
