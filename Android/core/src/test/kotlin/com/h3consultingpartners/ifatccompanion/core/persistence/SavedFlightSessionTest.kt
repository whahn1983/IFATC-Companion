package com.h3consultingpartners.ifatccompanion.core.persistence

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.model.Waypoint
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticLevel
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticRecord
import com.h3consultingpartners.ifatccompanion.core.platform.InMemoryFileStore
import com.h3consultingpartners.ifatccompanion.core.platform.InMemoryKeyValueStore
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies saving, loading and swapping whole flights: that a saved flight captures the
 * entire session, that a slot is updated rather than duplicated, and that a flight
 * already finished at the gate is not one the library will take.
 *
 * Ported from `IFATCCompanionTests/SavedFlightSessionTests.swift`. The iOS suite drives
 * `AppModel` end to end; the parts of it that belong to the session coordinator (the
 * reset-then-apply load, the auto-save, the forced reconnect on a swap, Mock Mode's
 * refusal to save) are left for the coordinator's own suite — see this package's summary.
 * What is tested here is the half that lives in the store and the snapshot: naming,
 * update-in-place, the completed-flight rule the Save button is defined by, the endpoint
 * label both sides of the mismatch warning are built from, and the diagnostics log
 * travelling with the flight.
 */
class SavedFlightSessionTest {

    private val now = 1_700_000_000_000L
    private val clock = MutableClock(now)
    private val files = InMemoryFileStore(clock)

    private fun makeStore() = SavedFlightStore(files, InMemoryKeyValueStore(), clock)

    /** A session parked at the gate, as it would be saved before pushback. */
    private fun gateSnapshot(): SessionSnapshot {
        val plan = FlightPlan(
            departure = "KIAH",
            destination = "KMSP",
            cruiseAltitude = 28000,
            waypoints = listOf(Waypoint(name = "DOOBI", latitude = 30.1, longitude = -95.2)),
        )
        return SessionSnapshot(
            atcState = ATCState.CLEARANCE,
            stateMachineCurrent = ATCState.CLEARANCE,
            currentFacility = ATCFacility.CLEARANCE,
            phase = FlightPhase.PREFLIGHT,
            assignedAltitude = 5000,
            hasDeparted = false,
            arrivalAnnounced = false,
            awaitingGateArrival = false,
            manualTuning = false,
            transcript = listOf(
                ATCTransmission(
                    sender = ATCTransmission.Sender.ATC,
                    facility = ATCFacility.CLEARANCE,
                    displayText = "United 598, cleared to Minneapolis as filed.",
                    spokenText = "United 598, cleared to Minneapolis as filed.",
                    timestampMillis = now,
                ),
            ),
            departure = "KIAH",
            destination = "KMSP",
            mockMode = false,
            savedAtMillis = now,
            flightPlan = plan,
            overrides = FlightOverrides(
                callsign = "UAL598", airline = "United", flightNumber = "598",
                departure = "KIAH", destination = "KMSP",
                departureGate = "C12", arrivalGate = "B44",
            ),
        )
    }

    /** A session finished at the destination gate — blocked in, arrival announced. */
    private fun completedSnapshot(): SessionSnapshot = gateSnapshot().copy(
        atcState = ATCState.PARKED,
        stateMachineCurrent = ATCState.PARKED,
        currentFacility = ATCFacility.GROUND,
        phase = FlightPhase.PARKED,
        hasDeparted = true,
        arrivalAnnounced = true,
    )

    // region Saving

    /**
     * Saving captures the whole session — the plan, the pilot's own fields, the radio and
     * the transcript — under a name taken from the route.
     */
    @Test
    fun savingCapturesTheWholeSession() {
        val store = makeStore()
        val saved = store.save(gateSnapshot().copy(tunedFacility = ATCFacility.GROUND))

        assertEquals("KIAH-KMSP", saved.name)
        assertEquals(1, store.flights.value.size)
        assertEquals(
            saved.id, store.activeFlightID.value,
            "saving binds the flight so auto-save knows where to write",
        )
        val snapshot = store.flights.value.first().snapshot
        assertEquals("UAL598", snapshot.overrides?.callsign)
        assertEquals("B44", snapshot.overrides?.arrivalGate)
        assertEquals(
            ATCFacility.GROUND, snapshot.tunedFacility,
            "the frequency the pilot is actually on is part of the flight",
        )
        assertEquals("KIAH", snapshot.flightPlan?.departure)
        assertFalse(snapshot.transcript.isEmpty())
    }

    /** Tapping Save twice updates the one flight rather than leaving a duplicate behind. */
    @Test
    fun savingTwiceUpdatesTheSameFlight() {
        val store = makeStore()
        val first = store.save(gateSnapshot())
        // The app's Save re-saves into the bound slot rather than making a second one.
        store.update(first.id, gateSnapshot())
        val second = store.activeFlight

        assertEquals(1, store.flights.value.size)
        assertEquals(first.id, second?.id)
        assertEquals("KIAH-KMSP", store.flights.value.first().name)
    }

    /**
     * The Diagnostics log belongs to the flight: it is saved with it and comes back with
     * it, rather than showing whatever session the pilot switched away from.
     */
    @Test
    fun diagnosticsLogTravelsWithTheFlight() {
        val store = makeStore()
        val snap = gateSnapshot().copy(
            diagnostics = DiagnosticsSnapshot.from(
                listOf(
                    DiagnosticRecord(
                        now, DiagnosticCategory.ATC, DiagnosticLevel.INFO,
                        "Cleared to Minneapolis as filed.",
                    ),
                ),
            ),
        )
        val saved = store.save(snap)

        val reopened = makeStore()
        val restored = reopened.flight(saved.id)?.snapshot?.diagnostics
        assertNotNull(restored)
        assertTrue(
            restored.entries.any { it.message.contains("Cleared to Minneapolis") },
            "the saved flight's log comes back with it",
        )
        assertEquals(DiagnosticCategory.ATC, restored.entries.first().category)
    }

    // endregion

    // region Retiring a finished flight

    /**
     * A finished flight cannot be saved: there is nothing to come back to, and clearing
     * retires it anyway, so saving one would only set up a flight the next tap deletes.
     *
     * `AppModel.canSaveCurrentFlight` and `flightIsComplete` are both defined by this
     * one rule on the snapshot — deliberately, so what the library calls a finished
     * flight and what the session calls one can never disagree.
     */
    @Test
    fun aFinishedFlightCannotBeSaved() {
        assertTrue(completedSnapshot().isCompleted, "parked with the arrival announced")
        assertFalse(gateSnapshot().isCompleted, "still at the gate, still worth keeping")
    }

    // endregion

    // region Warnings

    /**
     * The endpoint check warns only when the saved flight really is a different route
     * from the one Infinite Flight is reporting. Both sides of that comparison are built
     * from the same label, so the two can never disagree about what a route is called.
     */
    @Test
    fun endpointMismatchWarnsOnlyOnADifferentRoute() {
        val liveRoute = SessionSnapshot.routeLabel(departure = "KIAH", destination = "KMSP")
        val sameRoute = gateSnapshot()
        val differentRoute = gateSnapshot().copy(departure = "EGLL", destination = "KBOS")

        assertEquals(liveRoute, sameRoute.routeName)
        assertEquals("EGLL-KBOS", differentRoute.routeName)
        assertEquals("KIAH-KMSP", liveRoute)
        // A plan that names neither endpoint is "Flight", which the warning treats as
        // "route unknown" rather than as a route that disagrees.
        assertEquals("Flight", SessionSnapshot.routeLabel(departure = "", destination = ""))
    }

    // endregion

    /**
     * The pilot's flight fields travel with the flight; the device's own preferences do
     * not. Loading a flight from last week must never change the audio setup or the
     * Infinite Flight host/port.
     */
    @Test
    fun overridesCarryTheFlightFieldsAndNothingElse() {
        val device = AppSettings(host = "192.168.1.20", port = 10111, callsign = "SWA1")
        val overrides = FlightOverrides(
            callsign = "UAL598", airline = "United", flightNumber = "598",
            departure = "KIAH", destination = "KMSP", arrivalGate = "B44",
        )

        val applied = overrides.applyTo(device)
        assertEquals("UAL598", applied.callsign)
        assertEquals("B44", applied.arrivalGate)
        assertEquals("192.168.1.20", applied.host, "the connection is the device's, not the flight's")
        assertEquals(10111, applied.port)

        val captured = FlightOverrides.from(applied)
        assertEquals(overrides.callsign, captured.callsign)
        assertEquals(overrides.arrivalGate, captured.arrivalGate)
    }

    /** A store over an empty file store comes up empty rather than erroring. */
    @Test
    fun anEmptyLibraryIsNotAnError() {
        val store = makeStore()
        assertTrue(store.flights.value.isEmpty())
        assertNull(store.lastError.value)
        assertNull(store.activeFlight)
    }
}
