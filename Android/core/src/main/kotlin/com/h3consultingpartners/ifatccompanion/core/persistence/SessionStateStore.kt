package com.h3consultingpartners.ifatccompanion.core.persistence

import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import com.h3consultingpartners.ifatccompanion.core.platform.FileStore
import kotlinx.serialization.json.Json

/**
 * Persists the latest [SessionSnapshot] so a disconnect/reconnect (or an app relaunch)
 * resumes the flight in progress.
 *
 * Ported from `IFATCCompanion/App/SessionStateStore.swift`, which keeps the snapshot in
 * `UserDefaults`. Here it is a single blob in the [FileStore] "session" namespace: the
 * snapshot carries a whole transcript (and, for a saved flight, a diagnostics log),
 * which is far past what belongs in a preferences store read into memory wholesale at
 * launch — the same reasoning the iOS `SavedFlightStore` gives for using Application
 * Support. The entry name is the Swift `UserDefaults` key, unchanged.
 */
class SessionStateStore(
    private val files: FileStore,
    private val clock: Clock = Clock.system,
) {

    /**
     * Snapshots older than this are treated as a previous flight, not a reconnect, and
     * are not restored. The active session re-stamps `savedAtMillis` periodically while
     * connected, so this only fires when the app was genuinely away a long time (e.g.
     * reopened the next day). Six hours, in milliseconds.
     */
    var maxAgeMillis: Long = 6L * 3600L * 1000L

    fun save(snapshot: SessionSnapshot) {
        val encoded = try {
            json.encodeToString(SessionSnapshot.serializer(), snapshot)
        } catch (_: Exception) {
            // Swift's `try? JSONEncoder().encode(...)` — an un-encodable snapshot is
            // dropped rather than crashing the flight in progress.
            return
        }
        files.write(NAMESPACE, SNAPSHOT_NAME, encoded.encodeToByteArray())
    }

    fun load(): SessionSnapshot? {
        val bytes = files.read(NAMESPACE, SNAPSHOT_NAME) ?: return null
        return try {
            json.decodeFromString(SessionSnapshot.serializer(), bytes.decodeToString())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Load a snapshot only if it is recent enough and represents an in-progress flight
     * worth resuming (not a completed gate-to-gate flight).
     */
    fun loadResumable(nowMillis: Long = clock.nowMillis()): SessionSnapshot? {
        val snapshot = load() ?: return null
        if (snapshot.isCompleted) return null
        if (nowMillis - snapshot.savedAtMillis > maxAgeMillis) return null
        return snapshot
    }

    fun clear() = files.delete(NAMESPACE, SNAPSHOT_NAME)

    companion object {
        /** The [FileStore] namespace the auto-resume snapshot lives in. */
        const val NAMESPACE = "session"

        /** The Swift `UserDefaults` key, kept verbatim as the entry name. */
        const val SNAPSHOT_NAME = "session.snapshot.v1"

        /**
         * Tolerant decoding: a snapshot written before a field existed is missing that
         * key (the field's `null`/default stands in), and a snapshot written by a newer
         * build carries keys this one doesn't know — neither may cost the pilot their
         * flight, so unknown keys are ignored rather than thrown on.
         */
        internal val json = Json {
            ignoreUnknownKeys = true
            // Swift's synthesized `Codable` writes every non-optional property and
            // omits a `nil` one (`encodeIfPresent`). These two flags reproduce that
            // exactly, so the JSON carries the same key set on both platforms.
            encodeDefaults = true
            explicitNulls = false
        }
    }
}
