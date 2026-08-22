package com.h3consultingpartners.ifatccompanion.core.persistence

import com.h3consultingpartners.ifatccompanion.core.diagnostics.DiagnosticsStore
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.InMemoryFileStore
import com.h3consultingpartners.ifatccompanion.core.platform.InMemoryKeyValueStore
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.session.FlightSessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A saved flight carries its Diagnostics log.
 *
 * Saving a flight is a deliberate act, and the log is what makes it inspectable afterwards
 * — which is the whole reason `SessionSnapshot` has carried a `DiagnosticsSnapshot` field
 * since the port began. Nothing ever wrote one, and `DiagnosticsStore` had no `restore`, so
 * the field had no producer and no consumer.
 *
 * Deliberately only for saved flights: the auto-resume snapshot is written continuously and
 * its job is to get the conversation back, so carrying five hundred log lines through every
 * write would cost far more there than it is worth.
 */
class SavedFlightDiagnosticsTest {

    private val now = 1_700_000_000_000L
    private val clock = MutableClock(now)
    private val store = SavedFlightStore(InMemoryFileStore(clock), InMemoryKeyValueStore(), clock)
    private val log = DiagnosticsStore(clock = clock)

    private var sessionState = FlightSessionState(
        flightPlan = FlightPlan.empty.copy(departure = "KIAH", destination = "KMSP"),
        canSaveCurrentFlight = true,
        atcState = ATCState.CRUISE,
    )

    private var restored: SessionSnapshot? = null

    private val controller = SavedFlightsController(
        store = store,
        session = { sessionState },
        captureSnapshot = {
            SessionSnapshot(
                atcState = ATCState.CRUISE,
                stateMachineCurrent = ATCState.CRUISE,
                currentFacility = ATCFacility.CENTER,
                phase = FlightPhase.CRUISE,
                assignedAltitude = 35_000,
                hasDeparted = true,
                arrivalAnnounced = false,
                awaitingGateArrival = false,
                manualTuning = false,
                transcript = emptyList(),
                departure = "KIAH",
                destination = "KMSP",
                mockMode = false,
                savedAtMillis = now,
                flightPlan = sessionState.flightPlan,
            )
        },
        resetSession = {},
        restoreSession = { restored = it },
        clearResumableSession = {},
        settings = { false },
        diagnosticsLog = { log.records.value },
        restoreDiagnostics = { log.restore(it) },
    )

    private fun somethingHappened() {
        log.log(DiagnosticCategory.CONNECTION, message = "Connected to Infinite Flight")
        log.log(DiagnosticCategory.ATC, message = "Phase Cruise")
    }

    @Test
    fun `saving a flight attaches the log`() {
        somethingHappened()

        val saved = controller.saveCurrentFlight()

        val entries = saved?.snapshot?.diagnostics?.entries.orEmpty()
        assertEquals(2, entries.size, "the log was not attached")
        assertTrue(entries.any { it.message.contains("Connected to Infinite Flight") })
    }

    @Test
    fun `loading it back restores the log`() {
        somethingHappened()
        val saved = controller.saveCurrentFlight()!!
        log.clear()
        assertTrue(log.records.value.isEmpty())

        controller.loadSavedFlight(saved)

        assertEquals(2, log.records.value.size, "the flight's history did not come back")
        assertEquals("Phase Cruise", log.records.value.last().message)
    }

    @Test
    fun `a line logged after loading appends to the restored log`() {
        // Restoring has to go through the store's own buffer: filling only the published
        // list would publish the saved log once and then have the next line replace it.
        somethingHappened()
        val saved = controller.saveCurrentFlight()!!
        log.clear()
        controller.loadSavedFlight(saved)

        log.log(DiagnosticCategory.SESSION, message = "Resumed")

        assertEquals(3, log.records.value.size, log.records.value.map { it.message }.toString())
    }

    @Test
    fun `a flight saved with an empty log restores to an empty log`() {
        val saved = controller.saveCurrentFlight()!!
        log.log(DiagnosticCategory.SESSION, message = "Something since")

        controller.loadSavedFlight(saved)

        assertTrue(log.records.value.isEmpty(), log.records.value.map { it.message }.toString())
    }
}
