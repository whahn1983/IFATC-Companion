package com.h3consultingpartners.ifatccompanion.core.surface

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Raw OpenStreetMap elements as returned by the Overpass API `out geom;` form.
 *
 * Overpass returns a JSON document with a top-level `elements` array. Each element
 * is a node, way, or relation carrying its OSM `id`, `tags`, and — for ways queried
 * with `out geom;` — an inline `geometry` list so the app does not have to resolve
 * node references separately. The original OSM identifiers and tags are preserved
 * verbatim; normalization never discards them (required for ODbL traceability and
 * for the Airport Surface Diagnostics).
 *
 * Ported from `IFATCCompanion/AirportSurface/OSMElement.swift`.
 */
@Serializable
data class OverpassResponse(
    val elements: List<OSMElement> = emptyList(),
    /** Overpass echoes the query cost/timestamp in `osm3s`; kept only for diagnostics. */
    val generator: String? = null,
) {
    companion object {
        /** Decode an Overpass document. Throws when the body is not the JSON extract. */
        fun decode(text: String): OverpassResponse =
            SurfaceJson.decodeFromString(serializer(), text)

        /** Decode an Overpass document from the raw response body. */
        fun decode(data: ByteArray): OverpassResponse = decode(data.toString(Charsets.UTF_8))
    }
}

/** A single OSM element (node / way / relation) from an Overpass extract. */
@Serializable
data class OSMElement(
    val type: Kind,
    /** OSM element id — preserved through normalization for traceability. */
    val id: Long,
    /** Node coordinate (nodes only). */
    val lat: Double? = null,
    val lon: Double? = null,
    /** OSM tags (e.g. `aeroway=taxiway`, `ref=A`, `name=Alpha`). Preserved verbatim. */
    val tags: Map<String, String>? = null,
    /** Referenced node ids (ways/relations without inline geometry). */
    val nodes: List<Long>? = null,
    /** Inline way geometry, present when queried with `out geom;`. */
    val geometry: List<OSMGeoPoint>? = null,
) {
    @Serializable
    enum class Kind(val rawValue: String) {
        @SerialName("node") NODE("node"),
        @SerialName("way") WAY("way"),
        @SerialName("relation") RELATION("relation"),
    }

    /**
     * A stable, type-qualified identifier ("way/12345") so nodes and ways with the
     * same numeric id never collide in dictionaries or the graph.
     */
    val stableID: String get() = "${type.rawValue}/$id"

    fun tag(key: String): String? = tags?.get(key)

    /** Node coordinate, when this element is a located node. */
    val coordinate: Coordinate?
        get() {
            val la = lat ?: return null
            val lo = lon ?: return null
            val c = Coordinate(la, lo)
            return if (c.isValid) c else null
        }

    /** Way geometry as coordinates (empty for nodes or geometry-less ways). */
    val polyline: List<Coordinate>
        get() = (geometry ?: emptyList()).map { it.coordinate }.filter { it.isValid }

    /** Value of `aeroway`, the primary airport-surface classifier. */
    val aeroway: String? get() = tags?.get("aeroway")

    /**
     * Preferred human name/reference for a taxiway or runway: `ref` first (the
     * letter/number controllers use), then `name`. Empty when neither is tagged.
     */
    val refOrName: String
        get() = (tags?.get("ref") ?: tags?.get("name") ?: "").trim { it == ' ' || it == '\t' }
}

/** A single geometry vertex from an Overpass `out geom;` way. */
@Serializable
data class OSMGeoPoint(
    val lat: Double,
    val lon: Double,
) {
    val coordinate: Coordinate get() = Coordinate(lat, lon)
}
