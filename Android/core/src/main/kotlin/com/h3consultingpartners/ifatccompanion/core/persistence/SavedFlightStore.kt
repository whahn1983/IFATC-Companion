package com.h3consultingpartners.ifatccompanion.core.persistence

import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import com.h3consultingpartners.ifatccompanion.core.platform.FileStore
import com.h3consultingpartners.ifatccompanion.core.platform.InMemoryKeyValueStore
import com.h3consultingpartners.ifatccompanion.core.platform.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * One saved flight: a whole-session [SessionSnapshot] under a name the pilot can pick
 * from a list.
 *
 * This is the deliberate flight *library*, separate from the single auto-resume
 * snapshot [SessionStateStore] keeps. The auto-resume snapshot answers "the app was
 * killed, put me back where I was"; a saved flight answers "I have three flights on the
 * go — give me that one". Both use the same snapshot type, so anything the reconnect
 * path restores, a saved flight restores too.
 */
@OptIn(ExperimentalUuidApi::class)
@Serializable
data class SavedFlight(
    val id: String = Uuid.random().toString(),
    /**
     * Display name, `DEP-DEST` with a `-1`, `-2` suffix when the same route is saved
     * more than once (see [SavedFlightStore.makeName]).
     */
    val name: String,
    /**
     * When this slot was last written, epoch millis — the explicit save, or the most
     * recent auto-save while the flight was loaded. Orders the list, newest first.
     */
    val savedAtMillis: Long,
    val snapshot: SessionSnapshot,
) {
    /** Where the flight had got to, for the list's subtitle ("Cruise", "On approach"). */
    val stateTitle: String get() = snapshot.atcState.title

    /** The controller being worked when the flight was put away. */
    val facilityTitle: String get() = snapshot.currentFacility.title
}

/**
 * Stores the pilot's saved flights as a single JSON file, and remembers which one (if
 * any) the live session is currently flying.
 *
 * Ported from `IFATCCompanion/App/SavedFlightStore.swift`. The file goes through the
 * [FileStore] port (namespace "saved_flights") rather than the key-value store, for the
 * reason iOS puts it in Application Support rather than `UserDefaults`: a flight carries
 * its transcript and diagnostics log, and several of them together are far past what
 * belongs in a preferences store read into memory wholesale at launch. Only the active
 * binding — one identifier — goes to the [KeyValueStore], under the Swift's own key.
 */
class SavedFlightStore(
    private val files: FileStore,
    private val defaults: KeyValueStore = InMemoryKeyValueStore(),
    private val clock: Clock = Clock.system,
) {

    private val _flights = MutableStateFlow<List<SavedFlight>>(emptyList())

    /** Saved flights, newest first. */
    val flights: StateFlow<List<SavedFlight>> = _flights.asStateFlow()

    private val _activeFlightID = MutableStateFlow<String?>(null)

    /**
     * The saved flight the live session is currently flying, if it was loaded from (or
     * saved into) the library. Auto-save writes here.
     */
    val activeFlightID: StateFlow<String?> = _activeFlightID.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)

    /**
     * Set when the library could not be read or written, so the app can surface it in
     * Diagnostics rather than failing silently.
     */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** The on-disk envelope, versioned so a future format change can migrate rather than discard. */
    @Serializable
    private data class Library(
        val version: Int = 1,
        val flights: List<SavedFlight> = emptyList(),
    )

    init {
        _activeFlightID.value = defaults.getString(ACTIVE_KEY)
        load()
    }

    // region Reading

    val activeFlight: SavedFlight?
        get() {
            val id = _activeFlightID.value ?: return null
            return _flights.value.firstOrNull { it.id == id }
        }

    fun flight(id: String): SavedFlight? = _flights.value.firstOrNull { it.id == id }

    /**
     * Load the library from disk. A file that cannot be decoded is left untouched — the
     * list simply comes up empty and [lastError] explains why, rather than the next save
     * overwriting flights that might still be recoverable by hand.
     */
    private fun load() {
        val bytes = files.read(NAMESPACE, FILE_NAME) ?: return
        try {
            val library = json.decodeFromString(Library.serializer(), bytes.decodeToString())
            _flights.value = library.flights.sortedByDescending { it.savedAtMillis }
        } catch (e: Exception) {
            _lastError.value = "Could not read saved flights: ${e.message}"
        }
    }

    // endregion

    // region Writing

    /**
     * A name for a new slot: `DEP-DEST`, with `-1`, `-2`… appended when that route is
     * already saved, so flying the same route twice never collapses into one entry.
     */
    fun makeName(snapshot: SessionSnapshot): String {
        val base = snapshot.routeName
        val taken = _flights.value.map { it.name }.toSet()
        if (base !in taken) return base
        var suffix = 1
        while ("$base-$suffix" in taken) suffix += 1
        return "$base-$suffix"
    }

    /**
     * Save a snapshot as a new flight and make it the active one, so subsequent
     * auto-saves keep it current.
     */
    fun save(snapshot: SessionSnapshot, name: String? = null): SavedFlight {
        val flight = SavedFlight(
            name = name ?: makeName(snapshot),
            savedAtMillis = clock.nowMillis(),
            snapshot = snapshot,
        )
        _flights.value = listOf(flight) + _flights.value
        setActive(flight.id)
        persist()
        return flight
    }

    /**
     * Overwrite an existing slot in place, keeping its name and identity. Used by the
     * auto-save so the loaded flight stays current as it is flown. No-op when the slot
     * has since been deleted — a deleted flight must not resurrect itself.
     */
    fun update(id: String, snapshot: SessionSnapshot) {
        val current = _flights.value
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return
        val updated = current[index].copy(snapshot = snapshot, savedAtMillis = clock.nowMillis())
        // Keep the list ordered by most recently flown.
        val rest = current.toMutableList().also { it.removeAt(index) }
        _flights.value = listOf(updated) + rest
        persist()
    }

    fun delete(id: String) {
        _flights.value = _flights.value.filterNot { it.id == id }
        if (_activeFlightID.value == id) setActive(null)
        persist()
    }

    /**
     * Bind (or unbind) the live session to a slot. Persisted so the binding survives a
     * relaunch, which is what lets auto-save keep writing into the right flight after the
     * app is killed and reopened.
     */
    fun setActive(id: String?) {
        _activeFlightID.value = id
        if (id != null) defaults.putString(ACTIVE_KEY, id) else defaults.remove(ACTIVE_KEY)
    }

    private fun persist() {
        try {
            val data = json.encodeToString(Library.serializer(), Library(flights = _flights.value))
            files.write(NAMESPACE, FILE_NAME, data.encodeToByteArray())
            _lastError.value = null
        } catch (e: Exception) {
            _lastError.value = "Could not save flights: ${e.message}"
        }
    }

    // endregion

    companion object {
        /** The [FileStore] namespace the library lives in. */
        const val NAMESPACE = "saved_flights"

        /** The Swift file name, kept verbatim. */
        const val FILE_NAME = "SavedFlights.json"

        /** The Swift `UserDefaults` key for the active binding, kept verbatim. */
        const val ACTIVE_KEY = "savedFlights.activeID"

        /** Same tolerant decoding as the auto-resume snapshot — see [SessionStateStore]. */
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}
