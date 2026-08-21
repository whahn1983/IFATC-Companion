package com.h3consultingpartners.ifatccompanion.core.surface

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Data parsing: runways, taxiways, taxilanes, holding positions, gates, parking,
 * apron geometry, and taxiway names/references are extracted from an Overpass extract,
 * and OSM identifiers/tags are preserved.
 *
 * Ported from `IFATCCompanionTests/OSMSurfaceParsingTests.swift`.
 */
class OSMSurfaceParsingTest {

    private val json = """
    {
      "version": 0.6,
      "generator": "Overpass API",
      "elements": [
        {"type":"way","id":1,"tags":{"aeroway":"runway","ref":"09/27","width":"45"},
         "geometry":[{"lat":40.0000,"lon":-75.0050},{"lat":40.0000,"lon":-74.9950}]},
        {"type":"way","id":2,"tags":{"aeroway":"taxiway","ref":"A"},
         "geometry":[{"lat":40.0010,"lon":-75.0000},{"lat":39.9990,"lon":-75.0000}]},
        {"type":"way","id":3,"tags":{"aeroway":"taxiway","name":"Bravo","oneway":"yes"},
         "geometry":[{"lat":40.0010,"lon":-75.0000},{"lat":40.0010,"lon":-74.9980}]},
        {"type":"way","id":4,"tags":{"aeroway":"taxilane"},
         "geometry":[{"lat":40.0020,"lon":-75.0010},{"lat":40.0020,"lon":-74.9990}]},
        {"type":"node","id":5,"lat":40.0005,"lon":-75.0000,"tags":{"aeroway":"holding_position","ref":"09"}},
        {"type":"node","id":6,"lat":40.0020,"lon":-75.0012,"tags":{"aeroway":"gate","ref":"B44"}},
        {"type":"node","id":7,"lat":40.0020,"lon":-74.9988,"tags":{"aeroway":"parking_position","ref":"P1"}},
        {"type":"way","id":8,"tags":{"aeroway":"apron"},
         "geometry":[{"lat":40.0021,"lon":-75.0011},{"lat":40.0021,"lon":-74.9989},{"lat":40.0025,"lon":-75.0000}]},
        {"type":"way","id":9,"tags":{"aeroway":"taxiway","ref":"C","access":"no"},
         "geometry":[{"lat":39.9990,"lon":-75.0000},{"lat":39.9990,"lon":-74.9980}]}
      ]
    }
    """.trimIndent()

    private fun normalized(): AirportSurfaceModel {
        val response = OverpassResponse.decode(json)
        val ref = Coordinate(40.0, -75.0)
        val bbox = OSMBoundingBox(center = ref, halfSpanDegrees = 0.04)
        return OSMSurfaceNormalizer.normalize(
            response, icao = "KTST", reference = ref,
            endpoint = "test", boundingBox = bbox, fetchDateMillis = FETCHED_AT,
        )
    }

    @Test
    fun overpassJSONDecodes() {
        val response = OverpassResponse.decode(json)
        assertEquals(9, response.elements.size)
        assertEquals(OSMElement.Kind.WAY, response.elements.first().type)
    }

    @Test
    fun runwayParsing() {
        val m = normalized()
        assertEquals(1, m.runways.size)
        val rwy = m.runways[0]
        assertEquals(listOf("09", "27"), rwy.idents)
        assertEquals(45.0, rwy.widthMeters, 0.5)
        assertFalse(rwy.widthInferred)
        assertEquals("way/1", rwy.osmID)
        assertEquals("runway", rwy.tags["aeroway"])
        // Two directional ends derived.
        assertEquals(2, m.runwayEnds.size)
        assertNotNull(m.runwayEnd("09"))
        assertNotNull(m.runwayEnd("27"))
    }

    @Test
    fun taxiwayAndTaxilaneParsing() {
        val m = normalized()
        // A, Bravo, C are taxiways; id 4 is a taxilane.
        assertEquals(3, m.taxiwaysOnly.size)
        assertEquals(1, m.taxilanes.size)
        assertTrue(m.taxiwaysOnly.any { it.name == "A" })
        assertTrue(m.taxiwaysOnly.any { it.name == "Bravo" && it.oneway })
        // access=no marks a closed taxiway.
        assertTrue(m.taxiwaysOnly.any { it.name == "C" && it.isClosed })
    }

    @Test
    fun taxiwayNamesAndReferences() {
        val m = normalized()
        // ref preferred over name; name used when ref absent.
        assertTrue(m.taxiwaysOnly.any { it.name == "A" && it.hasName })
        assertTrue(m.taxiwaysOnly.any { it.name == "Bravo" && it.hasName })
        assertTrue(m.taxilanes.all { !it.hasName })   // taxilane had no ref/name
    }

    @Test
    fun holdingPositionParsing() {
        val m = normalized()
        assertEquals(1, m.holdingPositions.size)
        assertEquals("09", m.holdingPositions[0].runwayRef)
        assertFalse(m.holdingPositions[0].inferred)
        assertEquals("node/5", m.holdingPositions[0].osmID)
    }

    @Test
    fun gatesAndParkingParsing() {
        val m = normalized()
        assertEquals(1, m.gates.size)
        assertEquals("B44", m.gates.firstOrNull()?.name)
        assertEquals(2, m.parkingPositions.size)   // gate + parking_position
        assertTrue(m.parkingPositions.any { it.kind == SurfaceParking.Kind.PARKING_POSITION && it.name == "P1" })
        assertNotNull(m.parking("B44"))
    }

    @Test
    fun apronGeometryParsing() {
        val m = normalized()
        assertEquals(1, m.aprons.size)
        assertTrue(m.aprons[0].polygon.size >= 3)
        assertEquals("way/8", m.aprons[0].osmID)
    }

    @Test
    fun inferredWidthFlaggedWhenUntagged() {
        // Taxiway A has no width tag; runway has one.
        val m = normalized()
        assertNull(m.taxiwaysOnly.first { it.name == "A" }.widthMeters)
        assertFalse(m.runways[0].widthInferred)
    }

    // MARK: - Split runways (multi-way OSM runways)

    /**
     * KLAX (and other large fields) tag one physical runway as several OSM ways — a main
     * centerline plus short stubs at the thresholds. Deriving ends per way fabricated a
     * duplicate far-ident end at the wrong extreme: the west stub of `06R/24L`, tagged with
     * both idents, produced a `24L` end whose threshold sat at the *west* (06R) end, which
     * sent 24L departures to the wrong side of the runway. Ends must be merged so there is
     * exactly one end per ident, at the runway's true extremes. Coordinates are the real
     * KLAX 06R/24L ways.
     */
    @Test
    fun splitRunwayMergesToOneEndPerIdentAtTrueExtremes() {
        val json = """
        {
          "version": 0.6,
          "elements": [
            {"type":"way","id":100,"tags":{"aeroway":"runway","ref":"06R/24L"},
             "geometry":[{"lat":33.94700,"lon":-118.43292},{"lat":33.95047,"lon":-118.39906}]},
            {"type":"way","id":101,"tags":{"aeroway":"runway","ref":"06R/24L"},
             "geometry":[{"lat":33.94682,"lon":-118.43469},{"lat":33.94700,"lon":-118.43292}]}
          ]
        }
        """.trimIndent()
        val response = OverpassResponse.decode(json)
        val ref = Coordinate(33.9425, -118.4081)   // KLAX
        val bbox = OSMBoundingBox(center = ref, halfSpanDegrees = 0.04)
        val m = OSMSurfaceNormalizer.normalize(
            response, icao = "KLAX", reference = ref,
            endpoint = "test", boundingBox = bbox, fetchDateMillis = FETCHED_AT,
        )

        // Two ways describe one physical runway → exactly one end per ident, not four.
        assertEquals(2, m.runwayEnds.size, "a two-way runway must yield two ends, not a phantom pair")
        assertEquals(1, m.runwayEnds.count { it.ident == "24L" })
        assertEquals(1, m.runwayEnds.count { it.ident == "06R" })

        // 24L threshold is at the east extreme; its opposite (06R) is at the west extreme.
        val east = Coordinate(33.95047, -118.39906)
        val west = Coordinate(33.94682, -118.43469)
        val end24L = assertNotNull(m.runwayEnd("24L"))
        assertTrue(
            distanceMeters(end24L.threshold.toCoordinate(), east) < 20,
            "24L threshold must be the east end, not the west (06R) end",
        )
        assertTrue(distanceMeters(end24L.oppositeThreshold.toCoordinate(), west) < 20)
        // And 06R is the mirror image.
        val end06R = assertNotNull(m.runwayEnd("06R"))
        assertTrue(distanceMeters(end06R.threshold.toCoordinate(), west) < 20)
        assertTrue(distanceMeters(end06R.oppositeThreshold.toCoordinate(), east) < 20)
    }

    // MARK: - Buildings / terminals

    /**
     * `building=*` ways and `aeroway=terminal` become building footprints; a movement
     * surface with a stray `building` tag is not misclassified; `building=no` is ignored.
     */
    private val buildingJSON = """
    {
      "version": 0.6,
      "elements": [
        {"type":"way","id":10,"tags":{"aeroway":"taxiway","ref":"A"},
         "geometry":[{"lat":40.0010,"lon":-75.0000},{"lat":39.9990,"lon":-75.0000}]},
        {"type":"way","id":11,"tags":{"building":"yes"},
         "geometry":[{"lat":40.0002,"lon":-75.0004},{"lat":40.0002,"lon":-74.9996},{"lat":39.9998,"lon":-74.9996},{"lat":39.9998,"lon":-75.0004}]},
        {"type":"way","id":12,"tags":{"aeroway":"terminal","name":"Concourse C"},
         "geometry":[{"lat":40.0006,"lon":-75.0004},{"lat":40.0006,"lon":-74.9996},{"lat":40.0004,"lon":-74.9996},{"lat":40.0004,"lon":-75.0004}]},
        {"type":"way","id":13,"tags":{"aeroway":"apron","building":"no"},
         "geometry":[{"lat":40.0009,"lon":-75.0004},{"lat":40.0009,"lon":-74.9996},{"lat":40.0007,"lon":-75.0000}]}
      ]
    }
    """.trimIndent()

    @Test
    fun buildingAndTerminalParsing() {
        val response = OverpassResponse.decode(buildingJSON)
        val ref = Coordinate(40.0, -75.0)
        val bbox = OSMBoundingBox(center = ref, halfSpanDegrees = 0.04)
        val m = OSMSurfaceNormalizer.normalize(
            response, icao = "KTST", reference = ref,
            endpoint = "test", boundingBox = bbox, fetchDateMillis = FETCHED_AT,
        )
        // building=yes way + aeroway=terminal → two footprints.
        assertEquals(2, m.buildings.size)
        assertTrue(m.buildings.any { it.osmID == "way/11" })
        assertTrue(m.buildings.any { it.osmID == "way/12" })   // terminal
        // The taxiway is not a building; the apron (building=no) is not a building.
        assertFalse(m.buildings.any { it.osmID == "way/10" })
        assertFalse(m.buildings.any { it.osmID == "way/13" })
        assertEquals(1, m.aprons.size)
        // Fresh normalization stamps the current schema version.
        assertEquals(OSMSurface.SURFACE_SCHEMA_VERSION, m.source.schemaVersion)
        assertFalse(m.source.isOutdatedSchema)
    }

    // MARK: - Overpass query scoping

    /**
     * The `building` features are scoped to a strictly tighter box than the movement
     * surfaces, so a hub embedded in a dense metro (e.g. KMSP) doesn't pull the whole city's
     * buildings and time the extract out — while the runways/taxiways/gates still use the
     * full airport box.
     */
    @Test
    fun buildingExtractIsScopedTighterThanMovementSurfaces() {
        val ref = Coordinate(44.8848, -93.2223)  // KMSP
        val query = OverpassQuery(icao = "KMSP", center = ref)

        // The building box is a strict subset of the full movement-surface box.
        val full = query.boundingBox
        val bld = query.buildingBoundingBox
        assertTrue(bld.south > full.south)
        assertTrue(bld.north < full.north)
        assertTrue(bld.west > full.west)
        assertTrue(bld.east < full.east)

        // The query text pulls aeroway features on the full box and buildings on the tighter
        // box (never the other way round).
        val text = query.queryText
        assertTrue(text.contains("way[\"aeroway\"](${full.overpassClause})"))
        assertTrue(text.contains("way[\"building\"](${bld.overpassClause})"))
        assertFalse(
            text.contains("way[\"building\"](${full.overpassClause})"),
            "buildings must not be pulled on the full box",
        )
    }

    /**
     * Guardrail: even if a caller passes a building span larger than the movement span, the
     * building box is clamped so it can never exceed the movement-surface box.
     */
    @Test
    fun buildingBoxNeverExceedsMovementBox() {
        val ref = Coordinate(44.8848, -93.2223)
        val query = OverpassQuery(
            icao = "KMSP", center = ref,
            halfSpanDegrees = 0.02, buildingHalfSpanDegrees = 0.09,
        )
        assertEquals(
            query.boundingBox, query.buildingBoundingBox,
            "an oversized building span is clamped to the movement-surface box",
        )
    }

    companion object {
        /** A fixed fetch instant, standing in for the Swift's `Date()`. */
        private const val FETCHED_AT = 1_700_000_000_000L

        /**
         * `SurfaceGeometry.distanceMeters` on iOS; that type belongs to the graph/routing
         * package, so the two lines of arithmetic are inlined here.
         */
        fun distanceMeters(a: Coordinate, b: Coordinate): Double = Geo.distanceNM(a, b) * 1852.0
    }
}
