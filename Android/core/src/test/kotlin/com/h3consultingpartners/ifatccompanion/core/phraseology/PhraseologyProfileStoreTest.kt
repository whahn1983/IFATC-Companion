package com.h3consultingpartners.ifatccompanion.core.phraseology

import com.h3consultingpartners.ifatccompanion.core.platform.InMemoryFileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Persistence and CRUD for user phraseology profiles.
 *
 * Ported from the store half of `IFATCCompanionTests/PhraseologyPackTests.swift`
 * (`testProfileRoundTripsThroughJSON`) and the behaviour
 * `IFATCCompanion/Phraseology/PhraseologyProfileStore.swift` documents. iOS keeps
 * these blobs in `UserDefaults`; here they are two entries in a [FileStore]
 * namespace, so what is on trial is that a profile written by one store instance is
 * still there — and still active — for the next one.
 */
class PhraseologyProfileStoreTest {

    @Test
    fun testProfilesAndActiveSelectionSurviveAReload() {
        val files = InMemoryFileStore()
        val store = PhraseologyProfileStore(files)
        val profile = store.createNew()
        store.activeProfileID = profile.id

        val reloaded = PhraseologyProfileStore(files)
        assertEquals(listOf("New Profile"), reloaded.profiles.map { it.name })
        assertEquals(profile.id, reloaded.activeProfileID)
        assertEquals(profile.id, reloaded.activeProfile?.id)
    }

    /** The de-duplication counter starts at 2: "New Profile", "New Profile 2", … */
    @Test
    fun testCreateNewGeneratesAUniqueName() {
        val store = PhraseologyProfileStore(InMemoryFileStore())
        assertEquals("New Profile", store.createNew().name)
        assertEquals("New Profile 2", store.createNew().name)
        assertEquals("New Profile 3", store.createNew().name)
    }

    @Test
    fun testUpdateReplacesTheProfileWithTheSameID() {
        val files = InMemoryFileStore()
        val store = PhraseologyProfileStore(files)
        val profile = store.createNew()
        store.update(profile.copy(airlineCallSets = mapOf("BAW" to "Speedbird")))

        assertEquals(1, store.profiles.size)
        assertEquals("Speedbird", PhraseologyProfileStore(files).profiles.first().airlineCallSets["BAW"])
    }

    /** Deleting the active profile clears the selection so nothing points at a ghost. */
    @Test
    fun testDeletingTheActiveProfileClearsTheSelection() {
        val files = InMemoryFileStore()
        val store = PhraseologyProfileStore(files)
        val profile = store.createNew()
        store.activeProfileID = profile.id

        store.delete(profile)

        assertTrue(store.profiles.isEmpty())
        assertNull(store.activeProfileID)
        assertNull(PhraseologyProfileStore(files).activeProfileID)
    }

    /**
     * An imported profile gets a fresh id so it never clobbers an existing one, and a
     * colliding name is marked so the two are still tellable apart in the list.
     */
    @Test
    fun testImportAssignsANewIDAndDeduplicatesTheName() {
        val store = PhraseologyProfileStore(InMemoryFileStore())
        val original = store.createNew(named = "Shared")
        val imported = store.importJSON(store.exportJSON(original))

        assertNotNull(imported)
        assertNotEquals(original.id, imported.id)
        assertEquals("Shared (Imported)", imported.name)
        assertEquals(2, store.profiles.size)
    }

    @Test
    fun testImportRejectsMalformedJSON() {
        val store = PhraseologyProfileStore(InMemoryFileStore())
        assertNull(store.importJSON("not json"))
        assertTrue(store.profiles.isEmpty())
    }

    /**
     * The exported JSON carries the same keys the Swift `Codable` synthesis writes, so a
     * profile shared from an iPhone imports here and back again.
     */
    @Test
    fun testExportedJSONUsesTheSwiftKeys() {
        val store = PhraseologyProfileStore(InMemoryFileStore())
        val json = store.exportJSON(PhraseologyProfile.example())
        for (key in listOf("\"id\"", "\"name\"", "\"templates\"", "\"airlineCallSets\"")) {
            assertTrue(json.contains(key), "missing $key in exported profile: $json")
        }
        assertTrue(json.contains("\"takeoff\""))
        assertTrue(json.contains("\"Lufthansa\""))
    }
}
