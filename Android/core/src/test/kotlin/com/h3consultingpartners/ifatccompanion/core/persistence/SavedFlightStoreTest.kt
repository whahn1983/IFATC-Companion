package com.h3consultingpartners.ifatccompanion.core.persistence

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.model.Waypoint
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.FileStore
import com.h3consultingpartners.ifatccompanion.core.platform.InMemoryFileStore
import com.h3consultingpartners.ifatccompanion.core.platform.InMemoryKeyValueStore
import com.h3consultingpartners.ifatccompanion.core.platform.KeyValueStore
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the saved-flight library: how flights are named, that a slot is updated
 * rather than duplicated, that deleting releases the active binding, and that the
 * library survives being read back from disk.
 *
 * Ported from `IFATCCompanionTests/SavedFlightStoreTests.swift`. The iOS test writes
 * into a temporary directory and an isolated `UserDefaults` suite; here the shared
 * [InMemoryFileStore] stands in for the directory (so a "reopened" store sees the same
 * bytes) and an [InMemoryKeyValueStore] for the suite.
 */
class SavedFlightStoreTest {

    private val now = 1_700_000_000_000L
    private val clock = MutableClock(now)

    /** The "directory": one file store shared by every store built in a test. */
    private val files: FileStore = InMemoryFileStore(clock)

    private fun makeStore(defaults: KeyValueStore = InMemoryKeyValueStore()) =
        SavedFlightStore(files, defaults, clock)

    private fun snapshot(
        departure: String = "KIAH",
        destination: String = "KORD",
        atcState: ATCState = ATCState.CRUISE,
        transcript: List<ATCTransmission> = emptyList(),
    ) = SessionSnapshot(
        atcState = atcState,
        stateMachineCurrent = atcState,
        currentFacility = ATCFacility.CENTER,
        phase = FlightPhase.CRUISE,
        assignedAltitude = 35000,
        hasDeparted = true,
        arrivalAnnounced = false,
        awaitingGateArrival = false,
        manualTuning = false,
        transcript = transcript,
        departure = departure,
        destination = destination,
        mockMode = false,
        savedAtMillis = now,
    )

    // region Naming

    /**
     * Flights are named for the route they fly, and a repeat of the same route gets a
     * numeric suffix rather than a second identical row.
     */
    @Test
    fun namesFlightsByRouteWithSuffixesForRepeats() {
        val store = makeStore()
        assertEquals("KIAH-KORD", store.save(snapshot()).name)
        assertEquals("KIAH-KORD-1", store.save(snapshot()).name)
        assertEquals("KIAH-KORD-2", store.save(snapshot()).name)
        assertEquals(
            "KSFO-KJFK",
            store.save(snapshot(departure = "KSFO", destination = "KJFK")).name,
        )
    }

    /** A gap left by a deleted flight is reused, so the suffixes don't climb forever. */
    @Test
    fun reusesAFreedName() {
        val store = makeStore()
        store.save(snapshot())
        val second = store.save(snapshot())
        assertEquals("KIAH-KORD-1", second.name)
        store.delete(second.id)
        assertEquals("KIAH-KORD-1", store.save(snapshot()).name)
    }

    /** A plan with no endpoints still gets a usable name rather than an empty row. */
    @Test
    fun namesAnEndpointlessFlight() {
        val store = makeStore()
        assertEquals("Flight", store.save(snapshot(departure = "", destination = "")).name)
        assertEquals("EGLL", store.save(snapshot(departure = "EGLL", destination = "")).name)
    }

    // endregion

    // region Slots

    /** Saving binds the new flight as the active one, so auto-save knows where to write. */
    @Test
    fun savingBindsTheActiveFlight() {
        val store = makeStore()
        val flight = store.save(snapshot())
        assertEquals(flight.id, store.activeFlightID.value)
        assertEquals("KIAH-KORD", store.activeFlight?.name)
    }

    /** Updating a slot keeps its identity and name — it must never fork into a second row. */
    @Test
    fun updateReplacesInPlace() {
        val store = makeStore()
        val flight = store.save(snapshot(atcState = ATCState.CRUISE))
        store.update(flight.id, snapshot(atcState = ATCState.DESCENT))

        assertEquals(1, store.flights.value.size)
        assertEquals(flight.id, store.flights.value.first().id)
        assertEquals("KIAH-KORD", store.flights.value.first().name)
        assertEquals(ATCState.DESCENT, store.flights.value.first().snapshot.atcState)
    }

    /** A deleted flight must not come back the next time the auto-save fires. */
    @Test
    fun updateIgnoresADeletedFlight() {
        val store = makeStore()
        val flight = store.save(snapshot())
        store.delete(flight.id)
        store.update(flight.id, snapshot(atcState = ATCState.DESCENT))
        assertTrue(store.flights.value.isEmpty())
    }

    /**
     * Deleting the flight being flown releases the binding, so the session in progress
     * stops auto-saving instead of writing into a slot that no longer exists.
     */
    @Test
    fun deletingTheActiveFlightUnbindsIt() {
        val store = makeStore()
        val flight = store.save(snapshot())
        store.delete(flight.id)
        assertNull(store.activeFlightID.value)
    }

    // endregion

    // region Persistence

    /**
     * The library is on disk, not just in memory: a second store over the same directory
     * sees the same flights, newest first.
     */
    @Test
    fun librarySurvivesReload() {
        val store = makeStore()
        store.save(snapshot(departure = "KIAH", destination = "KORD"))
        clock.advance(1000)
        store.save(snapshot(departure = "KSFO", destination = "KJFK"))

        val reopened = makeStore()
        assertEquals(2, reopened.flights.value.size)
        assertEquals("KSFO-KJFK", reopened.flights.value.first().name, "newest first")
        assertEquals("KIAH-KORD", reopened.flights.value.last().name)
    }

    /**
     * The active binding is remembered too, so a relaunch keeps auto-saving into the
     * flight that was being flown.
     */
    @Test
    fun activeBindingSurvivesReload() {
        val defaults = InMemoryKeyValueStore()

        val store = SavedFlightStore(files, defaults, clock)
        val flight = store.save(snapshot())

        val reopened = SavedFlightStore(files, defaults, clock)
        assertEquals(flight.id, reopened.activeFlightID.value)
    }

    /**
     * The whole session round-trips through JSON — the fields a saved flight adds are no
     * use if they don't survive the encoder.
     */
    @Test
    fun wholeSessionRoundTripsThroughDisk() {
        val store = makeStore()
        val plan = FlightPlan(
            departure = "KIAH",
            destination = "KORD",
            waypoints = listOf(Waypoint(name = "DOOBI", latitude = 30.1, longitude = -95.2)),
        )
        val snap = snapshot(
            transcript = listOf(
                ATCTransmission(
                    sender = ATCTransmission.Sender.ATC,
                    facility = ATCFacility.CENTER,
                    displayText = "Descend and maintain one one thousand.",
                    spokenText = "Descend and maintain one one thousand.",
                    timestampMillis = now,
                ),
            ),
        ).copy(
            flightPlan = plan,
            overrides = FlightOverrides(
                callsign = "UAL598", airline = "United", flightNumber = "598",
                departureGate = "C12", arrivalGate = "B44",
            ),
            tunedFacility = ATCFacility.APPROACH,
            awaitingReadback = true,
            pendingReadbackTx = ATCTransmission(
                sender = ATCTransmission.Sender.ATC,
                facility = ATCFacility.CENTER,
                displayText = "Turn left heading two seven zero.",
                spokenText = "Turn left heading two seven zero.",
                timestampMillis = now,
            ),
            readbackPrompts = 2,
            arrivalGateLatitude = 41.9,
            arrivalGateLongitude = -87.9,
            diagnostics = DiagnosticsSnapshot(
                entries = listOf(
                    DiagnosticsSnapshot.Entry(
                        timestampMillis = now,
                        category = DiagnosticCategory.ATC,
                        message = "Cleared direct DOOBI.",
                    ),
                ),
            ),
        )
        store.save(snap)

        val restored = makeStore().flights.value.firstOrNull()?.snapshot
        assertEquals("DOOBI", restored?.flightPlan?.waypoints?.first()?.name)
        assertEquals("UAL598", restored?.overrides?.callsign)
        assertEquals("B44", restored?.overrides?.arrivalGate)
        assertEquals(ATCFacility.APPROACH, restored?.tunedFacility)
        assertEquals(true, restored?.awaitingReadback)
        assertEquals("Turn left heading two seven zero.", restored?.pendingReadbackTx?.displayText)
        assertEquals(2, restored?.readbackPrompts)
        assertNotNull(restored?.arrivalGateCoordinate)
        assertEquals(41.9, restored!!.arrivalGateCoordinate!!.latitude, 0.0001)
        assertEquals("Cleared direct DOOBI.", restored.diagnostics?.entries?.first()?.message)
        assertEquals(1, restored.transcript.size)
    }

    // endregion
}
