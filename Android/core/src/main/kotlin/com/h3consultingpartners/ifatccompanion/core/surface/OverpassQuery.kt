package com.h3consultingpartners.ifatccompanion.core.surface

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * A geographic bounding box (WGS84 degrees), Overpass order-friendly. Used to size
 * an airport-specific extract and stored in the cache metadata so a cached extract's
 * coverage is auditable.
 *
 * Ported from `IFATCCompanion/AirportSurface/OverpassQuery.swift`.
 */
@Serializable
data class OSMBoundingBox(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    /** Whether a coordinate lies inside the box. */
    fun contains(c: Coordinate): Boolean =
        c.latitude >= south && c.latitude <= north && c.longitude >= west && c.longitude <= east

    /** Overpass bbox clause order: south,west,north,east. */
    val overpassClause: String
        get() = String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", south, west, north, east)

    companion object {
        /**
         * A square-ish box of [halfSpanDegrees] degrees latitude around a center, widened
         * in longitude by the local cos(lat) so the ground footprint is roughly square.
         */
        operator fun invoke(center: Coordinate, halfSpanDegrees: Double): OSMBoundingBox {
            val cosLat = max(0.2, cos(center.latitude * PI / 180))
            val lonHalf = halfSpanDegrees / cosLat
            return OSMBoundingBox(
                south = center.latitude - halfSpanDegrees,
                north = center.latitude + halfSpanDegrees,
                west = center.longitude - lonHalf,
                east = center.longitude + lonHalf,
            )
        }
    }
}

/**
 * Builds the Overpass QL request for a single airport's movement surface.
 *
 * Only the airport area is requested (never a region or the whole planet). Two feature
 * families are pulled: `aeroway`-tagged movement surfaces — runways, taxiways,
 * taxilanes, holding positions, parking positions, gates, aprons, terminals — and
 * `building` footprints. The buildings/terminals are not routable; they are used to keep
 * synthesized gate lead-ins from being drawn straight through a concourse to a stand on
 * the far side. `out geom tags;` returns inline way geometry and all tags in one
 * round-trip, so the app never has to resolve node references or make a second call
 * during taxi.
 *
 * The two families use **different** bounding boxes: the movement surfaces need the full
 * airport box (a big airport's runways span it), but `building=*` is one of the densest
 * tags in OSM, so pulling every building in the full box at a hub embedded in a dense metro
 * makes the extract time out (the airport then never caches). Buildings are therefore
 * scoped to a tighter box around the terminal core — enough to cover the concourses that
 * matter for gate lead-ins while excluding the surrounding city.
 */
class OverpassQuery(
    icao: String,
    val center: Coordinate,
    val halfSpanDegrees: Double = OSMSurface.BBOX_HALF_SPAN_DEGREES,
    buildingHalfSpanDegrees: Double = OSMSurface.BUILDING_BBOX_HALF_SPAN_DEGREES,
) {
    val icao: String = icao.uppercase()

    /** Never let the building box exceed the movement-surface box. */
    val buildingHalfSpanDegrees: Double = min(buildingHalfSpanDegrees, halfSpanDegrees)

    val boundingBox: OSMBoundingBox
        get() = OSMBoundingBox(center = center, halfSpanDegrees = halfSpanDegrees)

    /** The tighter box the `building` features are scoped to (a subset of [boundingBox]). */
    val buildingBoundingBox: OSMBoundingBox
        get() = OSMBoundingBox(center = center, halfSpanDegrees = buildingHalfSpanDegrees)

    /** The Overpass QL query text. Small, airport-scoped, JSON output. */
    val queryText: String
        get() {
            val box = boundingBox.overpassClause
            val buildingBox = buildingBoundingBox.overpassClause
            return """
                [out:json][timeout:${OSMSurface.OVERPASS_QUERY_TIMEOUT_SECONDS}];
                (
                  way["aeroway"]($box);
                  node["aeroway"]($box);
                  relation["aeroway"]($box);
                  way["building"]($buildingBox);
                  relation["building"]($buildingBox);
                );
                out geom tags qt;
            """.trimIndent()
        }

    /**
     * URL-form body ("data=<query>") posted to the Overpass interpreter. iOS builds it
     * with `URLComponents.percentEncodedQuery`; the JVM's form encoder writes spaces as
     * `+`, which is what `application/x-www-form-urlencoded` means and what Overpass
     * decodes.
     */
    val httpBody: String
        get() = "data=" + URLEncoder.encode(queryText, "UTF-8")
}
