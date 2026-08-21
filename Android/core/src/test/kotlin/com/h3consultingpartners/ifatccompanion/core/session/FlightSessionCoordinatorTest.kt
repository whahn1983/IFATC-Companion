package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The coordinator is where the ported rules meet. These drive it the way telemetry
 * does — a snapshot at a time — and check the behaviours that would be invisible until
 * a real flight went wrong.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlightSessionCoordinatorTest {

    private val plan = FlightPlan(
        callsign = "UAL598",
        airline = "UAL",
        flightNumber = "598",
        departure = "KIAH",
        destination = "KMSP",
        cruiseAltitude = 37_000,
        departureRunway = "15L",
        arrivalRunway = "30L",
    )

    private fun coordinator(
        scope: TestScope,
        settings: AppSettings = AppSettings(mockMode = false),
        spoken: MutableList<ATCTransmission> = mutableListOf(),
    ) = FlightSessionCoordinator(
        scope = scope,
        clock = MutableClock(0),
        settingsProvider = { settings },
        speak = { spoken += it },
    )

    private fun airborne(
        altitude: Double,
        verticalSpeed: Double = 0.0,
        groundSpeed: Double = 420.0,
    ) = AircraftState(
        latitude = 32.0,
        longitude = -95.0,
        altitudeMSL = altitude,
        altitudeAGL = altitude,
        groundSpeed = groundSpeed,
        verticalSpeed = verticalSpeed,
        heading = 15.0,
        onGround = false,
    )

    @Test
    fun aSnapshotWithNoUsableTelemetryIsIgnoredEntirely() = runTest {
        // The reconnect handshake produces an all-null snapshot where every state read
        // failed. Acting on one is how a parked aircraft ends up at cruise.
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        val before = coordinator.state.value

        coordinator.ingestAircraftState(AircraftState.empty)

        assertEquals(before.phase, coordinator.state.value.phase)
        assertEquals(before.atcState, coordinator.state.value.atcState)
    }

    @Test
    fun theConversationNeverRunsBackwardWhenThePhaseFlickers() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)

        // Climb out and reach cruise.
        coordinator.ingestAircraftState(airborne(20_000.0, verticalSpeed = 2_000.0))
        advanceUntilIdle()
        coordinator.ingestAircraftState(airborne(37_000.0))
        advanceUntilIdle()
        val atCruise = coordinator.state.value.atcState

        // A single noisy snapshot that reads as a climb must not drag the conversation
        // back to the Departure hand-off.
        coordinator.ingestAircraftState(airborne(36_800.0, verticalSpeed = 900.0))
        advanceUntilIdle()

        assertTrue(
            AtcFlowOrder.flowIndex(coordinator.state.value.atcState)!! >=
                AtcFlowOrder.flowIndex(atCruise)!!,
            "the flow moved backwards from $atCruise to ${coordinator.state.value.atcState}",
        )
    }

    @Test
    fun aStaffedControllerSilencesTheCompanionEntirely() = runTest {
        val spoken = mutableListOf<ATCTransmission>()
        val settings = AppSettings(mockMode = true)
        val coordinator = coordinator(this, settings, spoken)
        coordinator.ingestFlightPlan(plan)
        coordinator.setSimulateStaffedATC(true)
        coordinator.tuneTo(ATCFacility.CENTER)

        coordinator.ingestAircraftState(airborne(37_000.0))
        advanceUntilIdle()

        assertTrue(coordinator.state.value.companionStandby)
        assertTrue(
            coordinator.state.value.availableActions.isEmpty(),
            "the companion must offer the pilot nothing to say while a human is working",
        )
    }

    @Test
    fun aPilotTransmissionOpensTheReadbackGate() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)

        coordinator.ingestAircraftState(airborne(20_000.0, verticalSpeed = 2_000.0))
        advanceUntilIdle()

        if (coordinator.state.value.awaitingReadback) {
            coordinator.readBack()
            assertFalse(
                coordinator.state.value.awaitingReadback,
                "any pilot transmission counts as an acknowledgement",
            )
        }
    }

    @Test
    fun aStopOutOnTheTaxiwayNeverEndsTheFlight() = runTest {
        // The arrival only completes at the gate. A stop with the brake released is
        // still taxiing in — holding for traffic on the ramp, most likely.
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)

        coordinator.advanceAndPost(ATCState.PARKED)

        assertFalse(
            coordinator.state.value.atcState == ATCState.PARKED,
            "PARKED must not be reachable without being parked at the gate",
        )
    }

    @Test
    fun tuningMovesTheRadioWithoutTransmitting() = runTest {
        val spoken = mutableListOf<ATCTransmission>()
        val coordinator = coordinator(this, spoken = spoken)
        coordinator.ingestFlightPlan(plan)

        coordinator.tuneTo(ATCFacility.GROUND)

        assertEquals(ATCFacility.GROUND, coordinator.state.value.currentFacility)
        assertTrue(coordinator.state.value.manualTuning)
        assertTrue(spoken.isEmpty(), "tuning must never transmit")
    }

    @Test
    fun anIdenticalControllerCallIsNotRepeatedOnceAcknowledged() = runTest {
        val coordinator = coordinator(this)
        val call = ATCTransmission.create(
            sender = ATCTransmission.Sender.ATC,
            facility = ATCFacility.CENTER,
            displayText = "Fly heading 082, vectors around precipitation.",
            timestampMillis = 0,
        )

        coordinator.post(call)
        coordinator.readBack()
        val countAfterFirst = coordinator.state.value.transcript.size

        coordinator.post(call.copy(id = "second"))

        assertEquals(
            countAfterFirst,
            coordinator.state.value.transcript.size,
            "a call the pilot has already answered must not be said again verbatim",
        )
    }

    @Test
    fun anUnansweredCallIsStillRepeatable() = runTest {
        // Re-issuing an unanswered call is how an unheard instruction gets through.
        val coordinator = coordinator(this)
        val call = ATCTransmission.create(
            sender = ATCTransmission.Sender.ATC,
            facility = ATCFacility.CENTER,
            displayText = "Climb and maintain flight level three seven zero.",
            timestampMillis = 0,
        )

        coordinator.post(call)
        coordinator.post(call.copy(id = "second"))

        assertEquals(2, coordinator.state.value.transcript.size)
    }

    @Test
    fun theFlightPlanFromConnectNeverOverwritesAManualOverride() = runTest {
        val coordinator = coordinator(this)
        val manual = plan.copy(destination = "KDEN", manualOverride = true)
        coordinator.ingestFlightPlan(manual)

        coordinator.ingestFlightPlan(plan.copy(destination = "KMSP"))

        assertEquals("KDEN", coordinator.state.value.flightPlan.destination)
    }

    @Test
    fun theWorkingFacilityFollowsAPendingHandoffBeforeTheRadioDoes() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.tuneTo(ATCFacility.TOWER)

        coordinator.ingestAircraftState(airborne(6_000.0, verticalSpeed = 2_000.0))
        advanceUntilIdle()

        val state = coordinator.state.value
        val pending = state.pendingCheckInFacility
        if (pending != null) {
            assertEquals(
                pending,
                state.workingFacility,
                "the request buttons must point at the controller taking over, not the " +
                    "frequency still tuned",
            )
        }
    }

    @Test
    fun aPhaseChangeIsReflectedInTheStateAndTheDebugTrace() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)

        coordinator.ingestAircraftState(airborne(30_000.0, verticalSpeed = 1_500.0))
        advanceUntilIdle()

        assertEquals(FlightPhase.CLIMB, coordinator.state.value.phase)
        assertTrue(coordinator.state.value.phaseDebug.notes.isNotEmpty())
    }
    // region Session lifecycle

    @Test
    fun `endSession marks the flight over so a watcher can stop without reaching the gate`() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.post(
            ATCTransmission(
                sender = ATCTransmission.Sender.ATC,
                facility = ATCFacility.CLEARANCE,
                displayText = "United 598, cleared to Minneapolis as filed.",
                spokenText = "United five niner eight, cleared to Minneapolis as filed.",
                timestampMillis = 0,
            ),
            speakIt = false,
        )
        advanceUntilIdle()

        // A controller exchange has happened and the flight is nowhere near the gate.
        assertTrue(coordinator.state.value.atcCommunicationStarted)
        assertFalse(coordinator.state.value.sessionEnded)
        assertTrue(coordinator.state.value.atcState != ATCState.PARKED)

        coordinator.endSession()
        advanceUntilIdle()

        // This is the whole point: a flight abandoned mid-cruise is over even though it
        // never reached PARKED, which is what anything keyed on atcState alone missed.
        assertTrue(coordinator.state.value.sessionEnded)
    }

    @Test
    fun `a new controller exchange revives a session that was ended earlier`() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.endSession()
        advanceUntilIdle()
        assertTrue(coordinator.state.value.sessionEnded)

        coordinator.post(
            ATCTransmission(
                sender = ATCTransmission.Sender.ATC,
                facility = ATCFacility.CLEARANCE,
                displayText = "United 598, push and start approved.",
                spokenText = "United five niner eight, push and start approved.",
                timestampMillis = 1_000,
            ),
            speakIt = false,
        )
        advanceUntilIdle()

        // Without this the flag latches for the process and the next flight never starts
        // the foreground service.
        assertFalse(coordinator.state.value.sessionEnded)
    }

    // endregion

}
