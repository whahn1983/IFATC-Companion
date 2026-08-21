package com.h3consultingpartners.ifatccompanion.core.enroute

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import kotlin.math.abs

/**
 * The bundled enroute sector map: ~450 ARTCC / FIR boundaries covering the globe,
 * loaded once off the main thread and then queried by position.
 *
 * Loading is lazy and asynchronous because the dataset is ~0.5 MB of JSON and there
 * is no reason to pay for it on a flight that never leaves the pattern. [prepare] is
 * called when a flight is set up; every query before the load finishes simply
 * returns null, which the hand-off tracker reads as "no crossing yet".
 *
 * Ported from `IFATCCompanion/Enroute/CenterSectorDatabase.swift`. iOS reads the file
 * from the app bundle on a utility `DispatchQueue`; here it is a classpath resource
 * read on an injected dispatcher, which works unchanged in a plain-JVM unit test and
 * in the APK.
 */
class CenterSectorDatabase(
    /**
     * Opens the packaged dataset. Injected so a test can feed a different document
     * (or none) without an Android context — the Kotlin stand-in for iOS's `bundle`.
     */
    private val openResource: () -> InputStream? = DEFAULT_RESOURCE_OPENER,
) {

    /**
     * Sectors ordered smallest-area first (the generator sorts them). Where two
     * boundaries overlap — an upper sector stacked on a lower one, a national FIR
     * wrapping the ACCs inside it — [sector] therefore returns the most specific
     * one, deterministically, instead of whichever happened to decode first.
     */
    private var sectors: List<CenterSector> = emptyList()
    private var loadState: LoadState = LoadState.Idle
    private var loadedProvenance: Provenance? = null
    private val lock = Any()

    sealed interface LoadState {
        data object Idle : LoadState
        data object Loading : LoadState
        data object Ready : LoadState
        data class Failed(val message: String) : LoadState
    }

    /**
     * Build a database from sectors already in memory, skipping the resource entirely.
     * The test seam: a handful of known boundaries makes hand-off behavior assertable
     * in a way the shipped global dataset does not. Pass them smallest-area first, the
     * order the generator writes, so overlapping boundaries resolve the same way here
     * as they do in the app.
     */
    constructor(sectors: List<CenterSector>) : this() {
        this.sectors = deconflictFrequencies(sectors)
        this.loadState = LoadState.Ready
        this.loadedProvenance = Provenance(
            generated = "in-memory",
            source = "test",
            license = CenterSectorData.LICENSE_SHORT_NAME,
            sectorCount = sectors.size,
        )
    }

    // MARK: - Loading

    /**
     * Kick off the background load if it hasn't started. Idempotent and cheap to call
     * from anywhere; returns immediately.
     */
    fun prepare(scope: CoroutineScope, dispatcher: CoroutineDispatcher = Dispatchers.IO) {
        synchronized(lock) {
            if (loadState != LoadState.Idle) return
            loadState = LoadState.Loading
        }
        scope.launch(dispatcher) { load() }
    }

    /**
     * Load synchronously. Used by the background worker and by tests, which need the
     * data in hand before they can assert on it. Callers on a UI thread should wrap it
     * in `withContext(Dispatchers.IO)` — or use [prepare].
     */
    fun loadNow(): Boolean {
        val alreadyReady = synchronized(lock) {
            val ready = loadState == LoadState.Ready
            if (!ready) loadState = LoadState.Loading
            ready
        }
        if (alreadyReady) return true
        return load()
    }

    private fun load(): Boolean {
        try {
            val text = openResource()?.use { it.readBytes().decodeToString() }
            if (text == null) {
                finish(LoadState.Failed("${CenterSectorData.RESOURCE_FILENAME} is not on the classpath"))
                return false
            }
            val document = json.decodeFromString(Document.serializer(), text)
            if (document.schemaVersion > CenterSectorData.SUPPORTED_SCHEMA_VERSION) {
                finish(
                    LoadState.Failed(
                        "sector data schema ${document.schemaVersion} is newer than this build",
                    ),
                )
                return false
            }
            synchronized(lock) {
                sectors = deconflictFrequencies(document.sectors)
                loadedProvenance = Provenance(
                    generated = document.generated,
                    source = document.source,
                    license = document.license,
                    sectorCount = document.sectors.size,
                )
                loadState = LoadState.Ready
            }
            return true
        } catch (error: Exception) {
            finish(LoadState.Failed(error.message ?: error.toString()))
            return false
        }
    }

    private fun finish(state: LoadState) {
        synchronized(lock) { loadState = state }
    }

    // MARK: - Queries

    val state: LoadState get() = synchronized(lock) { loadState }

    val isReady: Boolean get() = state == LoadState.Ready

    val count: Int get() = synchronized(lock) { sectors.size }

    /**
     * The sector working the given position, or null when the data isn't loaded yet or
     * the position falls in a gap between boundaries (the source data does have a few,
     * mostly over open ocean). A gap is deliberately *not* an error: the caller keeps
     * working the sector it already has rather than inventing a hand-off.
     */
    fun sector(at: Coordinate): CenterSector? = synchronized(lock) {
        if (loadState != LoadState.Ready) return null
        sectors.firstOrNull { it.contains(at) }
    }

    fun sector(id: String): CenterSector? = synchronized(lock) {
        sectors.firstOrNull { it.id == id }
    }

    /** Provenance of the loaded dataset, for diagnostics. */
    val provenance: Provenance? get() = synchronized(lock) { loadedProvenance }

    data class Provenance(
        val generated: String,
        val source: String,
        val license: String,
        val sectorCount: Int,
    )

    // MARK: - File format

    @Serializable
    private data class Document(
        val schemaVersion: Int,
        val generated: String,
        val source: String,
        val license: String,
        val sectors: List<CenterSector>,
    )

    companion object {

        /** The process-wide instance the app shares, matching iOS's `shared`. */
        val shared: CenterSectorDatabase by lazy { CenterSectorDatabase() }

        private val DEFAULT_RESOURCE_OPENER: () -> InputStream? = {
            CenterSectorDatabase::class.java
                .getResourceAsStream("/${CenterSectorData.RESOURCE_FILENAME}")
        }

        /**
         * The header carries `sourceURL`, `licenseURL` and `note` alongside the fields
         * the loader reads, so unknown keys are ignored rather than fatal — and a future
         * additive header change stays readable by this build.
         */
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Keep neighbouring sectors off the same frequency.
         *
         * Most sectors' frequencies are synthesized from a hash of the sector id (real
         * sector frequencies are not published as open data), and there are more sectors
         * than 25 kHz slots in the enroute band, so two of them landing on the same
         * frequency is inevitable. Between distant sectors that is harmless; between two
         * that share a boundary it is not — the hand-off would tell the pilot to switch to
         * the frequency they are already on. Sectors whose bounding boxes are close enough
         * to be worked back to back are therefore stepped up the band until they differ.
         *
         * Real frequencies the source publishes are never moved: two adjacent Australian
         * sectors sharing one is a fact about that airspace, not a collision to fix. The
         * pass runs in file order, which the generator fixes, so the result is identical on
         * every device and every launch.
         */
        fun deconflictFrequencies(sectors: List<CenterSector>): List<CenterSector> {
            val result = sectors.toMutableList()
            // Bounded so a pathological input can't spin: the band holds 160 slots, and a
            // sector with more same-frequency neighbours than that is not worth chasing.
            val maximumSteps = 160
            for (index in result.indices) {
                if (result[index].publishedFrequency != null) continue
                var frequency = result[index].frequency
                var steps = 0
                while (steps < maximumSteps &&
                    result.subList(0, index).any { earlier ->
                        earlier.isNeighbour(result[index]) &&
                            abs(earlier.frequency - frequency) < 0.0005
                    }
                ) {
                    frequency = CenterSector.nextFrequency(after = frequency)
                    steps += 1
                }
                result[index] = result[index].copy(assignedFrequency = frequency)
            }
            return result
        }
    }
}
