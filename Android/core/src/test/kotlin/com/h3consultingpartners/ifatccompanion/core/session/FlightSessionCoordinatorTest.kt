package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.enroute.CenterSectorDatabase
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        taxiContext: () -> TaxiClearanceContext? = { null },
    ) = FlightSessionCoordinator(
        scope = scope,
        clock = MutableClock(0),
        settingsProvider = { settings },
        speak = { spoken += it },
        taxiContextProvider = taxiContext,
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

    // region Center sector

    @Test
    fun `an airborne fix names the Center sector working the flight`() = runTest {
        // Loaded synchronously here so the first fix already has data. In the app the
        // coordinator kicks the load off-thread on the first airborne fix and the generic
        // "Center" fallback holds until it lands.
        assertTrue(CenterSectorDatabase.shared.loadNow(), "the sector database should load")

        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        assertNull(coordinator.state.value.centerSectorName, "nothing is known before a fix")

        // 32.0N 95.0W — over east Texas, well inside a real Center sector.
        coordinator.ingestAircraftState(airborne(altitude = 35_000.0))
        advanceUntilIdle()

        // The database, the polygon lookup and the tracker were all ported and tested, and
        // nothing ever fed them a position — so this stayed null for every flight and
        // Center identified itself generically for the whole cruise.
        assertNotNull(
            coordinator.state.value.centerSectorName,
            "an airborne fix inside a sector should name it",
        )
    }

    @Test
    fun `a fix on the ground names no sector`() = runTest {
        assertTrue(CenterSectorDatabase.shared.loadNow())
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)

        coordinator.ingestAircraftState(
            AircraftState(
                latitude = 32.0,
                longitude = -95.0,
                altitudeMSL = 500.0,
                altitudeAGL = 0.0,
                groundSpeed = 12.0,
                verticalSpeed = 0.0,
                heading = 15.0,
                onGround = true,
            ),
        )
        advanceUntilIdle()

        // Taxiway fixes must not be fed to the tracker: a sector name at the gate would be
        // wrong, and worse, it would consume the first-fix adoption the tracker uses to
        // decide there is nothing to hand off from.
        assertNull(coordinator.state.value.centerSectorName)
    }

    // endregion

    // region Session snapshots

    @Test
    fun `a snapshot round-trips the conversation into a fresh coordinator`() = runTest {
        val original = coordinator(this)
        original.ingestFlightPlan(plan)
        val clearance = ATCTransmission(
            sender = ATCTransmission.Sender.ATC,
            facility = ATCFacility.CLEARANCE,
            displayText = "United 598, cleared to Minneapolis as filed, climb 5000.",
            spokenText = "United five niner eight, cleared to Minneapolis as filed, climb five thousand.",
            timestampMillis = 1_000,
        )
        original.post(clearance, speakIt = false)
        original.tuneTo(ATCFacility.GROUND)
        advanceUntilIdle()

        val snapshot = original.captureSnapshot()
        assertEquals(1, snapshot.transcript.size)
        assertEquals("KIAH", snapshot.departure)
        assertEquals("KMSP", snapshot.destination)

        // A fresh coordinator is what a relaunch actually gets — a new process, nothing
        // carried over in memory.
        val resumed = coordinator(this)
        resumed.restore(snapshot)
        advanceUntilIdle()

        val state = resumed.state.value
        assertEquals(1, state.transcript.size)
        assertEquals(clearance.displayText, state.transcript.first().displayText)
        assertEquals(snapshot.atcState, state.atcState)
        assertEquals(snapshot.currentFacility, state.currentFacility)
        assertEquals("KIAH", state.flightPlan.departure)
        assertFalse(state.sessionEnded, "a resumed session is not an ended one")
    }

    @Test
    fun `restoring re-establishes an outstanding read-back`() = runTest {
        val original = coordinator(this)
        original.ingestFlightPlan(plan)
        original.post(
            ATCTransmission(
                sender = ATCTransmission.Sender.ATC,
                facility = ATCFacility.CLEARANCE,
                displayText = "United 598, climb and maintain 5000.",
                spokenText = "United five niner eight, climb and maintain five thousand.",
                timestampMillis = 2_000,
                readback = ATCTransmission.Readback(
                    displayText = "Climb and maintain 5000, United 598.",
                    spokenText = "Climb and maintain five thousand, United five niner eight.",
                    facility = ATCFacility.CLEARANCE,
                ),
            ),
            speakIt = false,
        )
        advanceUntilIdle()

        val snapshot = original.captureSnapshot()
        val resumed = coordinator(this)
        resumed.restore(snapshot)
        advanceUntilIdle()

        // Dropping this on resume would put the pilot back into a conversation with an
        // instruction silently un-acknowledged — the controller waiting on a read-back
        // that the app has forgotten is owed.
        assertEquals(
            original.state.value.awaitingReadback,
            resumed.state.value.awaitingReadback,
        )
    }

    // endregion

    // region Manual overrides

    @Test
    fun `a manual override does not block the pilot's own next edit`() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan.copy(callsign = "UAL1", manualOverride = true))
        advanceUntilIdle()
        assertEquals("UAL1", coordinator.state.value.flightPlan.callsign)

        // The header commits a callsign, then a gate. The second commit used to be
        // discarded, because the guard rejected every plan once the flag was latched —
        // including the ones the pilot had just typed.
        coordinator.ingestFlightPlan(
            coordinator.state.value.flightPlan.copy(departureGate = "C12", manualOverride = true),
        )
        advanceUntilIdle()

        assertEquals("C12", coordinator.state.value.flightPlan.departureGate)
        assertEquals("UAL1", coordinator.state.value.flightPlan.callsign)
    }

    @Test
    fun `the simulator still cannot overwrite a manual override`() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan.copy(callsign = "UAL1", manualOverride = true))
        advanceUntilIdle()

        // Connect builds its plans with manualOverride defaulted false, so narrowing the
        // guard must not weaken this — it is the whole reason the flag exists.
        coordinator.ingestFlightPlan(plan.copy(callsign = "DAL9"))
        advanceUntilIdle()

        assertEquals("UAL1", coordinator.state.value.flightPlan.callsign)
    }

    @Test
    fun `clearing the override hands control back to the simulator`() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan.copy(callsign = "UAL1", manualOverride = true))
        advanceUntilIdle()

        coordinator.clearManualOverride()
        coordinator.ingestFlightPlan(plan.copy(callsign = "DAL9"))
        advanceUntilIdle()

        // Without the unlatch, "Clear Overrides" could not work at all: the empty plan it
        // ingests is unflagged, so the guard refused it and the override outlived the
        // button meant to remove it.
        assertEquals("DAL9", coordinator.state.value.flightPlan.callsign)
        assertFalse(coordinator.state.value.flightPlan.manualOverride)
    }

    // endregion

    // region Taxi clearance content

    @Test
    fun `a taxi clearance names the route when one is available`() = runTest {
        val coordinator = coordinator(
            this,
            taxiContext = {
                TaxiClearanceContext(
                    taxiways = "A, C, B",
                    crossingRunway = "27",
                    parkingTaxiway = "",
                )
            },
        )
        coordinator.ingestFlightPlan(plan)
        advanceUntilIdle()

        val context = coordinator.buildContext(ATCState.GROUND_TAXI)

        // buildContext hardcoded these three to empty, which is why every taxi clearance
        // this app has ever produced on Android said "taxi to runway 15L" and nothing
        // else — at every airport, for the whole of every flight.
        assertEquals("A, C, B", context.taxiway)
        assertEquals("27", context.crossingRunway)
    }

    @Test
    fun `a taxi clearance stays generic when no route is available`() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        advanceUntilIdle()

        // No OpenStreetMap coverage, an Overpass outage, or simply no route computed yet:
        // the clearance must degrade to the generic form rather than say something wrong.
        val context = coordinator.buildContext(ATCState.GROUND_TAXI)
        assertEquals("", context.taxiway)
        assertNull(context.crossingRunway)
    }

    // endregion

}
