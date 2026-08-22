package com.h3consultingpartners.ifatccompanion.core.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Four buttons — Save, New Flight, Load, Delete — and every one either keeps or throws away
 * a flight the pilot has been talking through for an hour. These are the rules behind them.
 *
 * The wording is asserted as well as the logic: these strings are what the pilot reads a
 * moment before something becomes irreversible.
 */
class SavedFlightPolicyTest {

    // region Can it be saved

    @Test
    fun `a flight with a conversation under way can be saved`() {
        assertTrue(
            SavedFlightPolicy.canSaveCurrentFlight(
                mockMode = false, flightIsComplete = false, transcriptIsEmpty = false,
                hasDeparted = false, departure = "", destination = "",
            ),
        )
    }

    @Test
    fun `a flight planned but not yet flown can be saved`() {
        // Set up at the gate, put in the list, come back to it. Only one endpoint is needed.
        assertTrue(
            SavedFlightPolicy.canSaveCurrentFlight(
                mockMode = false, flightIsComplete = false, transcriptIsEmpty = true,
                hasDeparted = false, departure = "KIAH", destination = "",
            ),
        )
    }

    @Test
    fun `an empty session with nowhere to go cannot be saved`() {
        assertFalse(
            SavedFlightPolicy.canSaveCurrentFlight(
                mockMode = false, flightIsComplete = false, transcriptIsEmpty = true,
                hasDeparted = false, departure = "", destination = "",
            ),
        )
    }

    @Test
    fun `a finished flight cannot be saved`() {
        // Nothing to come back to once the aircraft has blocked in, and clearing retires it
        // from the list anyway — so offering to save one sets up the contradiction of
        // saving a flight the next tap deletes.
        assertFalse(
            SavedFlightPolicy.canSaveCurrentFlight(
                mockMode = false, flightIsComplete = true, transcriptIsEmpty = false,
                hasDeparted = true, departure = "KIAH", destination = "KORD",
            ),
        )
    }

    @Test
    fun `a mock flight cannot be saved`() {
        // A scripted demo that always starts at the gate is not a flight to come back to.
        assertFalse(
            SavedFlightPolicy.canSaveCurrentFlight(
                mockMode = true, flightIsComplete = false, transcriptIsEmpty = false,
                hasDeparted = true, departure = "KIAH", destination = "KORD",
            ),
        )
    }

    // endregion

    // region Would it be lost

    @Test
    fun `a flight with history and no bound slot would be lost`() {
        assertTrue(
            SavedFlightPolicy.hasUnsavedFlight(
                mockMode = false, flightIsComplete = false, transcriptIsEmpty = false,
                hasDeparted = false, autoSaveFlights = true, activeFlightStillInLibrary = false,
            ),
        )
    }

    @Test
    fun `a flight auto-saving into a slot that still exists would not be lost`() {
        assertFalse(
            SavedFlightPolicy.hasUnsavedFlight(
                mockMode = false, flightIsComplete = false, transcriptIsEmpty = false,
                hasDeparted = true, autoSaveFlights = true, activeFlightStillInLibrary = true,
            ),
        )
    }

    @Test
    fun `a bound flight with auto-save switched off would still be lost`() {
        // The binding is only a promise that auto-save keeps it current. Without auto-save
        // the slot holds whatever it held at the last manual save, so the rest is at risk.
        assertTrue(
            SavedFlightPolicy.hasUnsavedFlight(
                mockMode = false, flightIsComplete = false, transcriptIsEmpty = false,
                hasDeparted = true, autoSaveFlights = false, activeFlightStillInLibrary = true,
            ),
        )
    }

    @Test
    fun `a flight the pilot deleted while flying it would be lost again`() {
        // Deleting the flown flight unbinds the session rather than ending it, so it goes
        // back to being unsaved — and the next confirmation has to say so.
        assertTrue(
            SavedFlightPolicy.hasUnsavedFlight(
                mockMode = false, flightIsComplete = false, transcriptIsEmpty = false,
                hasDeparted = true, autoSaveFlights = true, activeFlightStillInLibrary = false,
            ),
        )
    }

    @Test
    fun `a finished flight is not unsaved`() {
        // It is done and cannot be saved, so warning that it will be lost would offer the
        // pilot a rescue that is not there.
        assertFalse(
            SavedFlightPolicy.hasUnsavedFlight(
                mockMode = false, flightIsComplete = true, transcriptIsEmpty = false,
                hasDeparted = true, autoSaveFlights = false, activeFlightStillInLibrary = false,
            ),
        )
    }

    @Test
    fun `a session with no history yet has nothing to lose`() {
        assertFalse(
            SavedFlightPolicy.hasUnsavedFlight(
                mockMode = false, flightIsComplete = false, transcriptIsEmpty = true,
                hasDeparted = false, autoSaveFlights = false, activeFlightStillInLibrary = false,
            ),
        )
    }

    // endregion

    // region Retiring and mismatches

    @Test
    fun `only a finished flight is retired by clearing`() {
        assertEquals("KIAH-KORD", SavedFlightPolicy.retiredByClearing(true, "KIAH-KORD"))
        assertNull(SavedFlightPolicy.retiredByClearing(false, "KIAH-KORD"))
        assertNull(SavedFlightPolicy.retiredByClearing(true, null))
    }

    @Test
    fun `a different route in the simulator is worth warning about`() {
        val warning = SavedFlightPolicy.endpointMismatch("KIAH-KORD", "KSFO-KJFK")
        assertEquals(
            "Infinite Flight is reporting KIAH-KORD, but this saved flight is KSFO-KJFK.",
            warning,
        )
    }

    @Test
    fun `two unnamed routes are not a mismatch`() {
        // routeLabel falls back to "Flight" when neither endpoint is known. Two flights that
        // are both merely "Flight" tell the pilot nothing, and a warning that fires on every
        // load before the plan arrives is a warning they learn to dismiss.
        assertNull(SavedFlightPolicy.endpointMismatch("Flight", "KSFO-KJFK"))
        assertNull(SavedFlightPolicy.endpointMismatch("KIAH-KORD", "Flight"))
        assertNull(SavedFlightPolicy.endpointMismatch("KIAH-KORD", "KIAH-KORD"))
    }

    // endregion

    // region What the pilot reads

    @Test
    fun `the confirmation leads with the mismatch, then the stakes, then the action`() {
        val message = SavedFlightPolicy.confirmationMessage(
            endpointMismatch = "Infinite Flight is reporting KIAH-KORD, but this saved flight is KSFO-KJFK.",
            retiredName = null,
            hasUnsavedFlight = true,
            isNewFlight = false,
        )
        val mismatchAt = message.indexOf("Infinite Flight is reporting")
        val stakesAt = message.indexOf("hasn't been saved")
        val actionAt = message.indexOf("Loading brings back")
        assertTrue(mismatchAt in 0 until stakesAt, "the mismatch must lead: $message")
        assertTrue(stakesAt < actionAt, "the stakes must come before the explanation: $message")
    }

    @Test
    fun `a finished flight's confirmation names what will be removed instead of what is lost`() {
        val message = SavedFlightPolicy.confirmationMessage(
            endpointMismatch = null,
            retiredName = "KIAH-KORD",
            hasUnsavedFlight = false,
            isNewFlight = true,
        )
        assertTrue(message.contains("KIAH-KORD"), message)
        assertTrue(message.contains("will be removed"), message)
        assertFalse(
            message.contains("will be lost"),
            "a finished flight is not lost, it is retired: $message",
        )
    }

    @Test
    fun `a confirmation with nothing at stake still says what the action does`() {
        val message = SavedFlightPolicy.confirmationMessage(null, null, false, isNewFlight = true)
        assertEquals(SavedFlightPolicy.NEW_FLIGHT_EXPLANATION, message)
    }

    // endregion
}
