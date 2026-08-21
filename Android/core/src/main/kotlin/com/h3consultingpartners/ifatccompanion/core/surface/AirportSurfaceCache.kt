package com.h3consultingpartners.ifatccompanion.core.surface

import com.h3consultingpartners.ifatccompanion.core.platform.FileStore

/**
 * On-disk cache of normalized airport surfaces, one JSON blob per ICAO.
 *
 * Requirements met here:
 *  - caches only airports actually used (entries are written on demand);
 *  - stores the source identifier, fetch date, cache age, ODbL metadata and
 *    attribution (all inside the cached [AirportSurfaceModel.source] provenance);
 *  - retains original OSM identifiers and tags (they are part of the model);
 *  - supports deletion (single airport or all) for Settings;
 *  - never bundles a global OSM database in the binary.
 *
 * The refresh interval itself (60–90 days) is enforced by the provider, which treats
 * a cached model older than [OSMSurface.CACHE_REFRESH_INTERVAL_SECONDS] as stale.
 *
 * iOS writes files into a directory under `Caches` named
 * [OSMSurface.CACHE_DIRECTORY_NAME]; here the same blobs live in the [FileStore]
 * port under the [OSMSurface.CACHE_NAMESPACE] namespace, so `:core` stays free of
 * `java.io.File` and a test can back the cache with an in-memory store. The
 * `namespace` argument plays the role of the Swift `directoryName:` — the iOS tests
 * pass a unique one per test to keep caches isolated, and so do these.
 *
 * Ported from `IFATCCompanion/AirportSurface/AirportSurfaceCache.swift`.
 */
class AirportSurfaceCache(
    private val store: FileStore,
    private val namespace: String = OSMSurface.CACHE_NAMESPACE,
) {

    private fun fileName(icao: String): String = "${icao.uppercase()}.json"

    /** Load a cached surface, or null when none / undecodable. */
    fun load(icao: String): AirportSurfaceModel? {
        val bytes = store.read(namespace, fileName(icao)) ?: return null
        return runCatching { AirportSurfaceModel.decode(bytes.toString(Charsets.UTF_8)) }.getOrNull()
    }

    /**
     * Persist a normalized surface. Errors are swallowed — a failed cache write only
     * costs a re-fetch later.
     */
    fun save(model: AirportSurfaceModel): Boolean = runCatching {
        val text = SurfaceJson.encodeToString(AirportSurfaceModel.serializer(), model)
        store.write(namespace, fileName(model.icao), text.toByteArray(Charsets.UTF_8))
        true
    }.getOrDefault(false)

    /** Delete a single airport's cached surface. */
    fun delete(icao: String) {
        runCatching { store.delete(namespace, fileName(icao)) }
    }

    /** Delete every cached airport surface (Settings → clear cache). */
    fun deleteAll() {
        val names = runCatching { store.list(namespace) }.getOrDefault(emptyList())
        for (name in names) {
            if (name.endsWith(".json")) runCatching { store.delete(namespace, name) }
        }
    }

    /** ICAO codes with a cached surface on disk. */
    fun cachedICAOs(): List<String> =
        runCatching { store.list(namespace) }.getOrDefault(emptyList())
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json").uppercase() }
            .sorted()

    /** Total bytes used by the cache (for the Settings display). */
    fun totalSizeBytes(): Int =
        runCatching { store.list(namespace) }.getOrDefault(emptyList())
            .sumOf { store.read(namespace, it)?.size ?: 0 }
}
