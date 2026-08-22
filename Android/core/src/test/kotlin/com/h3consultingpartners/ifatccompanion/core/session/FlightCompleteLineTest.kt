package com.h3consultingpartners.ifatccompanion.core.session

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
 * The line that ends the flight.
 *
 * iOS posts "United 598 parked at B44. Flight complete." into the transcript the moment the
 * aircraft is actually stopped at the stand. On Android the transcript simply stopped at the
 * taxi-in clearance, so nothing in the app ever said the flight was over — the pilot had to
 * infer it from the buttons going away.
 */
class FlightCompleteLineTest {

    private val plan = FlightPlan.empty.copy(
        departure = "KIAH",
        destination = "KMSP",
        airline = "United",
        flightNumber = "598",
        callsign = "United 598",
        arrivalGate = "B44",
        cruiseAltitude = 35_000,
    )

    private val spoken = mutableListOf<ATCTransmission>()

    private fun coordinator(scope: TestScope) = FlightSessionCoordinator(
        scope = scope,
        clock = MutableClock(0),
        // Not mock mode: the demo feed would drive the aircraft itself, and this is about
        // what the coordinator does with the telemetry it is handed.
        settingsProvider = { AppSettings(mockMode = false, voiceEnabled = true) },
        speak = { spoken += it },
    )

    private fun onStand(groundSpeed: Double, braked: Boolean) = AircraftState(
        latitude = 44.88,
        longitude = -93.22,
        altitudeMSL = 841.0,
        altitudeAGL = 0.0,
        groundSpeed = groundSpeed,
        verticalSpeed = 0.0,
        heading = 90.0,
        onGround = true,
        parkingBrakeSet = braked,
    )

    /** Taxi in, slow to the stand, then stop with the brake set. */
    private fun blockIn(scope: TestScope): FlightSessionCoordinator {
        val coordinator = coordinator(scope)
        coordinator.ingestFlightPlan(plan)
        coordinator.arriveAtGate()
        coordinator.ingestAircraftState(onStand(groundSpeed = 4.0, braked = false))
        coordinator.ingestAircraftState(onStand(groundSpeed = 0.0, braked = true))
        return coordinator
    }

    private fun completionLines(c: FlightSessionCoordinator) =
        c.state.value.transcript.filter { it.displayText.contains("Flight complete") }

    @Test
    fun `blocking in ends the transcript with a flight-complete line`() = runTest {
        val coordinator = blockIn(this)

        val line = completionLines(coordinator).singleOrNull()
        assertTrue(
            line != null,
            "no completion line in ${coordinator.state.value.transcript.map { it.displayText }}",
        )
        assertEquals("United 598 parked at B44. Flight complete.", line.displayText)
        assertEquals(ATCTransmission.Sender.SYSTEM, line.sender)
    }

    @Test
    fun `the completion line is not spoken`() = runTest {
        // It is not a radio call — nobody transmits "flight complete" on the ramp
        // frequency — so it goes in the transcript and not over the air.
        val coordinator = blockIn(this)
        assertTrue(
            spoken.none { it.displayText.contains("Flight complete") },
            "the completion line was read out on the radio",
        )
    }

    @Test
    fun `it is posted once, not on every telemetry frame after parking`() = runTest {
        val coordinator = blockIn(this)
        repeat(5) { coordinator.ingestAircraftState(onStand(groundSpeed = 0.0, braked = true)) }

        assertEquals(1, completionLines(coordinator).size)
    }

    @Test
    fun `a flight with no gate still gets a line`() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan.copy(arrivalGate = ""))
        coordinator.arriveAtGate()
        coordinator.ingestAircraftState(onStand(groundSpeed = 4.0, braked = false))
        coordinator.ingestAircraftState(onStand(groundSpeed = 0.0, braked = true))

        assertEquals(
            "United 598 parked at the gate. Flight complete.",
            completionLines(coordinator).single().displayText,
        )
    }

    @Test
    fun `a new flight gets its own completion line`() = runTest {
        val coordinator = blockIn(this)
        coordinator.resetForNewFlight()
        coordinator.ingestFlightPlan(plan)
        coordinator.arriveAtGate()
        coordinator.ingestAircraftState(onStand(groundSpeed = 4.0, braked = false))
        coordinator.ingestAircraftState(onStand(groundSpeed = 0.0, braked = true))

        assertEquals(1, completionLines(coordinator).size, "the second flight never announced")
    }
}
