package com.h3consultingpartners.ifatccompanion.core.phraseology

import com.h3consultingpartners.ifatccompanion.core.platform.FileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * The observable state of [PhraseologyProfileStore]: iOS publishes `profiles` and
 * `activeProfileID` separately; the Kotlin port aggregates them into one immutable
 * value so a screen re-renders once per change.
 */
data class PhraseologyProfilesState(
    val profiles: List<PhraseologyProfile> = emptyList(),
    val activeProfileID: String? = null,
) {
    /** The active profile, if any is selected and still exists. */
    val activeProfile: PhraseologyProfile?
        get() = activeProfileID?.let { id -> profiles.firstOrNull { it.id == id } }
}

/**
 * Persists user-created phraseology profiles and tracks the active one.
 * Profiles are stored as JSON; the active selection is a stored UUID string.
 * Fully local — no accounts, no network. Profiles can be exported/imported as
 * plain JSON text for sharing.
 *
 * Ported from `IFATCCompanion/Phraseology/PhraseologyProfileStore.swift`. iOS keeps
 * both blobs in `UserDefaults`; here they are two entries in the [FileStore]
 * namespace [NAMESPACE], named with the same keys the Swift uses so the meaning of
 * the persisted data is identical on both platforms.
 */
class PhraseologyProfileStore(private val files: FileStore) {

    private val _state = MutableStateFlow(PhraseologyProfilesState())
    val state: StateFlow<PhraseologyProfilesState> = _state.asStateFlow()

    val profiles: List<PhraseologyProfile> get() = _state.value.profiles
    var activeProfileID: String?
        get() = _state.value.activeProfileID
        set(value) {
            _state.value = _state.value.copy(activeProfileID = value)
            persistActiveID()
        }

    /** The active profile, if any is selected and still exists. */
    val activeProfile: PhraseologyProfile? get() = _state.value.activeProfile

    init {
        load()
    }

    // MARK: - CRUD

    fun add(profile: PhraseologyProfile) {
        _state.value = _state.value.copy(profiles = _state.value.profiles + profile)
        persistProfiles()
    }

    fun update(profile: PhraseologyProfile) {
        val current = _state.value.profiles
        val idx = current.indexOfFirst { it.id == profile.id }
        if (idx < 0) return
        _state.value = _state.value.copy(
            profiles = current.toMutableList().also { it[idx] = profile },
        )
        persistProfiles()
    }

    fun delete(profile: PhraseologyProfile) {
        var next = _state.value.copy(profiles = _state.value.profiles.filterNot { it.id == profile.id })
        if (next.activeProfileID == profile.id) {
            next = next.copy(activeProfileID = null)
            _state.value = next
            // Mirrors the Swift `didSet` on `activeProfileID`.
            persistActiveID()
        } else {
            _state.value = next
        }
        persistProfiles()
    }

    /**
     * Create a fresh, empty profile with a unique default name and return it.
     * The de-duplication counter starts at 2: "New Profile", "New Profile 2", …
     */
    fun createNew(named: String? = null): PhraseologyProfile {
        val base = named ?: "New Profile"
        var candidate = base
        var n = 2
        while (_state.value.profiles.any { it.name == candidate }) {
            candidate = "$base $n"
            n += 1
        }
        val profile = PhraseologyProfile(name = candidate)
        add(profile)
        return profile
    }

    // MARK: - Import / Export

    /** Export a profile as pretty-printed JSON for sharing. */
    fun exportJSON(profile: PhraseologyProfile): String =
        runCatching { prettyJson.encodeToString(PhraseologyProfile.serializer(), profile) }
            .getOrDefault("")

    /**
     * Import a profile from JSON text. A new id is assigned to avoid clobbering
     * an existing profile; the name is de-duplicated. Returns the imported
     * profile, or null if the JSON could not be decoded.
     */
    fun importJSON(json: String): PhraseologyProfile? {
        var profile = runCatching { lenientJson.decodeFromString(PhraseologyProfile.serializer(), json) }
            .getOrNull() ?: return null
        profile = profile.copy(id = PhraseologyProfile.newID())
        if (_state.value.profiles.any { it.name == profile.name }) {
            profile = profile.copy(name = profile.name + " (Imported)")
        }
        add(profile)
        return profile
    }

    // MARK: - Persistence

    private fun load() {
        val data = files.read(NAMESPACE, PROFILES_KEY)
        val decoded = data?.let {
            runCatching {
                lenientJson.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(PhraseologyProfile.serializer()),
                    it.decodeToString(),
                )
            }.getOrNull()
        }
        val activeID = files.read(NAMESPACE, ACTIVE_KEY)?.decodeToString()?.takeIf { it.isNotEmpty() }
        _state.value = PhraseologyProfilesState(
            profiles = decoded ?: emptyList(),
            activeProfileID = activeID,
        )
    }

    private fun persistProfiles() {
        val encoded = runCatching {
            lenientJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(PhraseologyProfile.serializer()),
                _state.value.profiles,
            )
        }.getOrNull() ?: return
        files.write(NAMESPACE, PROFILES_KEY, encoded.encodeToByteArray())
    }

    private fun persistActiveID() {
        val id = _state.value.activeProfileID
        if (id != null) {
            files.write(NAMESPACE, ACTIVE_KEY, id.encodeToByteArray())
        } else {
            files.delete(NAMESPACE, ACTIVE_KEY)
        }
    }

    companion object {
        /** Blob namespace holding both entries. */
        const val NAMESPACE = "phraseology_profiles"

        /** Same key the iOS build uses in `UserDefaults`. */
        const val PROFILES_KEY = "phraseologyProfiles"

        /** Same key the iOS build uses in `UserDefaults`. */
        const val ACTIVE_KEY = "phraseologyActiveProfileID"

        private val lenientJson = Json { ignoreUnknownKeys = true }

        // iOS exports with `.prettyPrinted, .sortedKeys`; kotlinx has no sorted-keys
        // option, so exported key order follows the declaration order instead. The
        // bytes differ from iOS's, the content does not.
        private val prettyJson = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    }
}
