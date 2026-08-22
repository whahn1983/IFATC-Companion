package com.h3consultingpartners.ifatccompanion.core.persistence

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.platform.InMemoryFileStore
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Verifies the session-state persistence used to resume an in-progress flight after a
 * disconnect/reconnect, instead of re-deriving the conversation (which would jump a
 * parked aircraft to cruise).
 *
 * Ported from `IFATCCompanionTests/SessionStateStoreTests.swift`. iOS isolates the test
 * with its own `UserDefaults` suite; here each test gets a fresh [InMemoryFileStore].
 */
class SessionStateStoreTest {

    private val now = 1_700_000_000_000L

    private fun makeStore(clock: MutableClock = MutableClock(now)) =
        SessionStateStore(InMemoryFileStore(clock), clock)

    private fun snapshot(
        atcState: ATCState = ATCState.CLIMB,
        arrivalAnnounced: Boolean = false,
        mockMode: Boolean = false,
        savedAtMillis: Long = now,
        transcript: List<ATCTransmission> = emptyList(),
    ) = SessionSnapshot(
        atcState = atcState,
        stateMachineCurrent = atcState,
        currentFacility = ATCFacility.CENTER,
        phase = FlightPhase.CLIMB,
        assignedAltitude = 28000,
        hasDeparted = true,
        arrivalAnnounced = arrivalAnnounced,
        awaitingGateArrival = false,
        manualTuning = false,
        transcript = transcript,
        departure = "KIAH",
        destination = "KMSP",
        mockMode = mockMode,
        savedAtMillis = savedAtMillis,
    )

    @Test
    fun roundTripsThroughDefaults() {
        val store = makeStore()
        val tx = ATCTransmission(
            sender = ATCTransmission.Sender.ATC,
            facility = ATCFacility.CENTER,
            displayText = "Climb and maintain flight level two eight zero.",
            spokenText = "Climb and maintain flight level two eight zero.",
            timestampMillis = now,
        )
        store.save(snapshot(transcript = listOf(tx)))

        val loaded = store.load()
        assertEquals(ATCState.CLIMB, loaded?.atcState)
        assertEquals(28000, loaded?.assignedAltitude)
        assertEquals(1, loaded?.transcript?.size)
        assertEquals(
            "Climb and maintain flight level two eight zero.",
            loaded?.transcript?.first()?.displayText,
        )
    }

    @Test
    fun resumableReturnsRecentInProgressSession() {
        val store = makeStore()
        store.save(snapshot(atcState = ATCState.CRUISE))
        assertNotNull(store.loadResumable(), "a recent in-progress session should resume")
    }

    @Test
    fun resumableRejectsStaleSession() {
        val store = makeStore()
        store.maxAgeMillis = 3600_000L
        store.save(snapshot(savedAtMillis = now - 7200_000L))
        assertNull(store.loadResumable(), "a session older than maxAge must not resume")
    }

    @Test
    fun resumableRejectsCompletedFlight() {
        val store = makeStore()
        store.save(snapshot(atcState = ATCState.PARKED, arrivalAnnounced = true))
        assertNull(
            store.loadResumable(),
            "a finished gate-to-gate flight has nothing to resume",
        )
    }

    @Test
    fun clearRemovesSnapshot() {
        val store = makeStore()
        store.save(snapshot())
        store.clear()
        assertNull(store.load())
        assertNull(store.loadResumable())
    }

    /**
     * Not in the iOS suite, which gets this for free from Swift's `Codable` optionals:
     * a session written before a field existed must still load. Decoding is deliberately
     * tolerant of both a missing key and one this build has never heard of.
     */
    @Test
    fun aSnapshotMissingLaterFieldsStillLoads() {
        val files = InMemoryFileStore(MutableClock(now))
        val store = SessionStateStore(files, MutableClock(now))
        // The first-release key set, plus a key from a build that is newer than this one.
        val legacy = """
            {"atcState":"climb","stateMachineCurrent":"climb","currentFacility":"center",
             "phase":"climb","assignedAltitude":28000,"hasDeparted":true,
             "arrivalAnnounced":false,"awaitingGateArrival":false,"manualTuning":false,
             "transcript":[],"departure":"KIAH","destination":"KMSP","mockMode":false,
             "savedAtMillis":$now,"somethingFromTheFuture":42}
        """.trimIndent()
        files.write(
            SessionStateStore.NAMESPACE,
            SessionStateStore.SNAPSHOT_NAME,
            legacy.encodeToByteArray(),
        )

        val loaded = store.load()
        assertEquals(ATCState.CLIMB, loaded?.atcState)
        assertNull(loaded?.monitoringTower, "a field that did not exist yet reads as absent")
        assertNull(loaded?.departureATIS)
        assertNull(loaded?.weatherDeviation)
    }
}
