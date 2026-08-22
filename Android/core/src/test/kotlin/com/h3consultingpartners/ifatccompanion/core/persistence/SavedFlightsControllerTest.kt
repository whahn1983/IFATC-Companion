package com.h3consultingpartners.ifatccompanion.core.persistence

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.platform.InMemoryFileStore
import com.h3consultingpartners.ifatccompanion.core.platform.InMemoryKeyValueStore
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.session.FlightSessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Save, clear and load, against a real store.
 *
 * Every branch here can cost the pilot the flight they are on, so each test names the loss
 * it exists to prevent rather than the API it calls.
 */
class SavedFlightsControllerTest {

    private val now = 1_700_000_000_000L
    private val clock = MutableClock(now)
    private val files = InMemoryFileStore(clock)
    private val store = SavedFlightStore(files, InMemoryKeyValueStore(), clock)

    private var sessionState = FlightSessionState()
    private var autoSaveOn = true
    private var resets = 0
    private var restored: SessionSnapshot? = null
    private var resumableCleared = 0

    private fun snapshot(
        departure: String = "KIAH",
        destination: String = "KORD",
        atcState: ATCState = ATCState.CRUISE,
    ) = SessionSnapshot(
        atcState = atcState,
        stateMachineCurrent = atcState,
        currentFacility = ATCFacility.CENTER,
        phase = FlightPhase.CRUISE,
        assignedAltitude = 35000,
        hasDeparted = true,
        arrivalAnnounced = atcState == ATCState.PARKED,
        awaitingGateArrival = false,
        manualTuning = false,
        transcript = emptyList(),
        departure = departure,
        destination = destination,
        mockMode = false,
        savedAtMillis = now,
    )

    private val controller = SavedFlightsController(
        store = store,
        session = { sessionState },
        captureSnapshot = { snapshot(sessionState.flightPlan.departure, sessionState.flightPlan.destination) },
        resetSession = { resets++ },
        restoreSession = { restored = it },
        clearResumableSession = { resumableCleared++ },
        settings = { autoSaveOn },
    )

    private fun flying(
        departure: String = "KIAH",
        destination: String = "KORD",
        canSave: Boolean = true,
        ended: Boolean = false,
        mock: Boolean = false,
    ) {
        sessionState = FlightSessionState(
            flightPlan = FlightPlan.empty.copy(departure = departure, destination = destination),
            canSaveCurrentFlight = canSave,
            atcState = if (ended) ATCState.PARKED else ATCState.CRUISE,
            mockMode = mock,
        )
    }

    // region Saving

    @Test
    fun `saving twice updates one row rather than leaving two`() {
        flying()
        val first = controller.saveCurrentFlight()
        assertNotNull(first)
        store.setActive(first.id)

        clock.advance(60_000)
        val second = controller.saveCurrentFlight()
        assertNotNull(second)

        // The failure this prevents: "KIAH-KORD" and "KIAH-KORD-1" side by side, one of them
        // an hour stale, with no way for the pilot to tell which is which.
        assertEquals(1, store.flights.value.size, "saving twice duplicated the flight")
        assertEquals(first.id, second.id)
        assertTrue(second.savedAtMillis > first.savedAtMillis, "the row was not refreshed")
    }

    @Test
    fun `a session that cannot be saved is refused wherever the save came from`() {
        // The rule lives in the controller, not only in the disabled button — a dialog's
        // "Save & Clear" and an auto-save tick reach this by other routes.
        flying(canSave = false)
        assertNull(controller.saveCurrentFlight())
        assertTrue(store.flights.value.isEmpty())
    }

    // endregion

    // region Auto-save

    @Test
    fun `a mock flight never overwrites a real saved one`() {
        flying()
        val saved = controller.saveCurrentFlight()
        assertNotNull(saved)
        store.setActive(saved.id)
        val savedAt = saved.savedAtMillis

        // The pilot switches on Mock Mode with a real flight still bound. Without the guard
        // the scripted demo writes itself over an hour of real conversation.
        flying(mock = true)
        clock.advance(60_000)
        controller.autoSave()

        assertEquals(savedAt, store.flight(saved.id)?.savedAtMillis, "a mock session overwrote a real flight")
    }

    @Test
    fun `auto-save does nothing when the pilot switched it off`() {
        flying()
        val saved = controller.saveCurrentFlight()
        assertNotNull(saved)
        store.setActive(saved.id)

        autoSaveOn = false
        clock.advance(60_000)
        controller.autoSave()
        assertEquals(saved.savedAtMillis, store.flight(saved.id)?.savedAtMillis)
    }

    @Test
    fun `auto-save keeps a bound slot current`() {
        flying()
        val saved = controller.saveCurrentFlight()
        assertNotNull(saved)
        store.setActive(saved.id)

        clock.advance(60_000)
        controller.autoSave()
        assertTrue((store.flight(saved.id)?.savedAtMillis ?: 0) > saved.savedAtMillis)
    }

    // endregion

    // region Starting again

    @Test
    fun `starting a new flight drops the resume snapshot as well as the session`() {
        // Without this the flight the pilot just cleared is what the next launch comes back
        // to, because the crash-resume snapshot is written separately from the library.
        flying()
        controller.startNewFlight()
        assertEquals(1, resets)
        assertEquals(1, resumableCleared, "the cleared flight would return on relaunch")
        assertNull(store.activeFlightID.value, "auto-save would keep writing to the old slot")
    }

    @Test
    fun `starting a new flight retires a finished one from the list`() {
        flying()
        val saved = controller.saveCurrentFlight()
        assertNotNull(saved)
        store.setActive(saved.id)

        flying(ended = true)
        controller.startNewFlight()
        assertTrue(store.flights.value.isEmpty(), "a completed flight was left in the list")
    }

    @Test
    fun `starting a new flight keeps an unfinished one in the list`() {
        // Clearing is how the pilot switches flights. Deleting the one they are leaving
        // would make switching the same thing as losing it.
        flying()
        val saved = controller.saveCurrentFlight()
        assertNotNull(saved)
        store.setActive(saved.id)

        flying()
        controller.startNewFlight()
        assertEquals(1, store.flights.value.size)
        assertNull(store.activeFlightID.value, "the session should be unbound, not the flight deleted")
    }

    // endregion

    // region Loading

    @Test
    fun `loading resets before restoring, never layers over the live session`() {
        flying()
        val saved = controller.saveCurrentFlight()
        assertNotNull(saved)

        flying(departure = "KSFO", destination = "KJFK")
        assertTrue(controller.loadSavedFlight(saved))

        assertEquals(1, resets, "the session was not reset before the snapshot was applied")
        assertEquals(saved.snapshot, restored)
        assertEquals(saved.id, store.activeFlightID.value, "the loaded flight was not bound")
    }

    @Test
    fun `Mock Mode has nowhere to load a flight into`() {
        flying()
        val saved = controller.saveCurrentFlight()
        assertNotNull(saved)

        flying(mock = true)
        assertFalse(controller.loadSavedFlight(saved))
        assertEquals(0, resets, "Mock Mode reset a session it cannot load into")
    }

    // endregion

    // region The binding the session reads

    @Test
    fun `deleting the flight being flown unbinds it rather than ending the session`() {
        flying()
        val saved = controller.saveCurrentFlight()
        assertNotNull(saved)
        store.setActive(saved.id)
        assertTrue(controller.binding().activeFlightStillInLibrary)

        controller.deleteSavedFlight(saved)
        // The session carries on; it simply stops auto-saving anywhere, and becomes unsaved
        // again — which is what the next confirmation has to warn about.
        assertFalse(controller.binding().activeFlightStillInLibrary)
        assertNull(controller.binding().activeFlightName)
    }

    @Test
    fun `a mismatched route is reported against the live plan`() {
        flying(departure = "KSFO", destination = "KJFK")
        val saved = SavedFlight(name = "KIAH-KORD", savedAtMillis = now, snapshot = snapshot())
        assertEquals(
            "Infinite Flight is reporting KSFO-KJFK, but this saved flight is KIAH-KORD.",
            controller.endpointMismatch(saved),
        )
    }

    // endregion
}
