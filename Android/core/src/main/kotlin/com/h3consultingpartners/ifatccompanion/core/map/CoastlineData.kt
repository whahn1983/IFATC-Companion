package com.h3consultingpartners.ifatccompanion.core.map

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.ui.LegalStrings
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * World coastlines, bundled with the app.
 *
 * The route map has no base map — see `Docs/ANDROID_MAPPING.md` for why every hosted
 * option was rejected — so without this a flight plan is a line on an empty canvas.
 * Coastlines are the cheapest thing that makes it legible: they place the route on the
 * planet, and unlike a tile provider they need no key, no billing account, no backend and
 * **no network**, which is the case that matters here. A pilot with the app open at
 * altitude may have no signal at all, and the coastline still draws.
 *
 * **Source and licence.** Natural Earth 1:110m physical coastline, which is in the
 * **public domain** — no attribution is required, though Natural Earth is credited in
 * `Docs/ANDROID_DATA_SOURCES.md` because saying where data came from is worth doing
 * whether or not a licence compels it. Note this is a different obligation from the
 * OpenStreetMap surface data, which is ODbL and *does* require the attribution the taxi
 * map displays.
 *
 * **Resolution.** 1:110m is the coarsest Natural Earth set: continents and major islands,
 * not inlets. That is the right level for a map whose job is orientation at route scale,
 * and it keeps the asset to about 75 KB. Coordinates are stored to two decimal places —
 * roughly a kilometre, finer than the source detail justifies.
 */
object CoastlineData {

    const val RESOURCE_NAME = "coastlines.json"

    /**
     * Natural Earth is public domain; this is a credit, not a licence requirement. It
     * delegates so there is exactly one wording, shared with Settings and the map itself.
     */
    const val CREDIT = LegalStrings.BaseMap.COASTLINE_ATTRIBUTION

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: List<List<Coordinate>>? = null

    /**
     * The coastline polylines, loaded once and kept.
     *
     * Returns an empty list rather than throwing when the resource is missing or
     * malformed: a map without coastlines is degraded, and a map that crashes is broken.
     * The route, the aircraft and the weather all still draw.
     */
    fun lines(): List<List<Coordinate>> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val loaded = runCatching { load() }.getOrDefault(emptyList())
            cached = loaded
            return loaded
        }
    }

    private fun load(): List<List<Coordinate>> {
        val text = CoastlineData::class.java.getResourceAsStream("/$RESOURCE_NAME")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: return emptyList()

        // [[[lon,lat],[lon,lat],…],…] — GeoJSON order, stripped of the wrapper and the
        // properties, which are all dead weight for drawing a line.
        val raw = json.decodeFromString(
            ListSerializer(ListSerializer(ListSerializer(Double.serializer()))),
            text,
        )
        return raw.mapNotNull { line ->
            val points = line.mapNotNull { pair ->
                if (pair.size < 2) null else Coordinate(latitude = pair[1], longitude = pair[0])
            }
            points.takeIf { it.size >= 2 }
        }
    }

    /**
     * Only the polylines that intersect the given latitude/longitude window, so a map
     * zoomed into one airport does not walk every segment of every continent each frame.
     *
     * Whole-polyline rather than per-segment: a line is kept if any of its points is in
     * the window, which over-includes slightly and costs far less than clipping. The
     * expensive case this exists to avoid is drawing all 5,000 points at taxi zoom.
     */
    fun linesIn(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
    ): List<List<Coordinate>> = lines().filter { line ->
        line.any { it.latitude in south..north && it.longitude in west..east }
    }
}
