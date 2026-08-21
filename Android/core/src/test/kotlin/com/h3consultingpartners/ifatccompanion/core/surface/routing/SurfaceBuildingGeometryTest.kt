package com.h3consultingpartners.ifatccompanion.core.surface.routing

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.surface.AirportSurfaceModel
import com.h3consultingpartners.ifatccompanion.core.surface.GeoCoordinate
import com.h3consultingpartners.ifatccompanion.core.surface.MockAirportSurface
import com.h3consultingpartners.ifatccompanion.core.surface.OSMSurface
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceBuilding
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceConfidence
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceJson
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceParking
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceRunway
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceTaxiway
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Building-geometry awareness in taxi routing: gate lead-ins avoid being drawn through a
 * concourse to a stand on the far side, and a cache written before building footprints
 * existed is recognized as an outdated schema (so it is re-fetched).
 *
 * Ported from `IFATCCompanionTests/SurfaceBuildingGeometryTests.swift`.
 */
class SurfaceBuildingGeometryTest {

    private val ref = Coordinate(40.0, -75.0)

    private fun g(dLat: Double, dLon: Double) =
        GeoCoordinate(ref.latitude + dLat, ref.longitude + dLon)

    /**
     * A thin concourse laid out E–W with a stand on its south face. The geometrically
     * nearest taxi node is on the *far* (north) side of the building; a slightly farther
     * node is clear on the south side.
     */
    private fun thinConcourseModel(withBuilding: Boolean): AirportSurfaceModel {
        // Taxiway on the north side of the concourse — nearest to the gate, but its lead-in
        // would cut through the building.
        val twyNorth = SurfaceTaxiway(
            osmID = "way/twy-north", tags = mapOf("aeroway" to "taxiway", "ref" to "N"),
            isTaxilane = false, name = "N",
            geometry = listOf(g(0.0003, 0.0000), g(0.0003, 0.0010)),
            oneway = false, access = null, widthMeters = null,
        )
        // Taxiway on the south side — a little farther, but clear of the building.
        val twySouth = SurfaceTaxiway(
            osmID = "way/twy-south", tags = mapOf("aeroway" to "taxiway", "ref" to "S"),
            isTaxilane = false, name = "S",
            geometry = listOf(g(-0.0010, 0.0000), g(-0.0010, 0.0010)),
            oneway = false, access = null, widthMeters = null,
        )
        // Runway placed well clear so it doesn't attach a runway-entry node near the stand.
        val runway = SurfaceRunway(
            osmID = "way/rwy", tags = mapOf("aeroway" to "runway", "ref" to "09/27"),
            idents = listOf("09", "27"),
            centerline = listOf(g(0.0100, -0.0050), g(0.0100, 0.0050)),
            widthMeters = 45.0, widthInferred = false,
        )
        val gate = SurfaceParking(
            osmID = "node/gate", tags = mapOf("aeroway" to "gate", "ref" to "G1"),
            kind = SurfaceParking.Kind.GATE, name = "G1", coordinate = g(-0.00025, 0.0000),
        )
        // Thin E–W building between the stand and the north taxiway.
        val building = SurfaceBuilding(
            osmID = "way/concourse",
            tags = mapOf("aeroway" to "terminal"),
            polygon = listOf(
                g(0.0002, -0.0004), g(0.0002, 0.0004),
                g(-0.0002, 0.0004), g(-0.0002, -0.0004),
            ),
        )
        return AirportSurfaceModel(
            icao = "KBLD", reference = GeoCoordinate(ref),
            runways = listOf(runway), runwayEnds = makeEnds(runway),
            taxiways = listOf(twyNorth, twySouth), holdingPositions = emptyList(),
            parkingPositions = listOf(gate), aprons = emptyList(),
            buildings = if (withBuilding) listOf(building) else emptyList(),
            source = testProvenance(ref, rawElementCount = 4),
            confidence = SurfaceConfidence.LOW,
        )
    }

    private data class GateConnector(val connector: SurfaceEdge, val otherNode: SurfaceNode)

    private fun gateConnector(graph: SurfaceGraph): GateConnector? {
        val gate = graph.nodes.firstOrNull { it.kind == SurfaceNodeKind.GATE } ?: return null
        val connector = graph.edges.firstOrNull {
            it.inferred && (it.from == gate.id || it.to == gate.id)
        } ?: return null
        val otherID = if (connector.from == gate.id) connector.to else connector.from
        return GateConnector(connector, graph.nodes[otherID])
    }

    @Test
    fun gateConnectorAvoidsBuildingCrossing() {
        val graph = SurfaceGraphBuilder.build(thinConcourseModel(withBuilding = true))
        val gate = graph.nodes.firstOrNull { it.kind == SurfaceNodeKind.GATE }
        assertNotNull(gate, "expected a gate node with an inferred connector")
        val hit = gateConnector(graph)
        assertNotNull(hit, "expected a gate node with an inferred connector")
        // Chose the clear stand on the south side, not the nearer node across the concourse.
        assertTrue(
            hit.otherNode.coordinate.latitude < gate.coordinate.latitude,
            "connector should attach to the south (clear) taxiway, not across the building",
        )
        assertFalse(hit.connector.crossesBuilding, "chosen connector must not cross the concourse")
        assertFalse(
            graph.edges.any { it.inferred && it.crossesBuilding },
            "no inferred connector should cut through a building when a clear node exists",
        )
    }

    @Test
    fun connectorPicksNearestWhenNoBuildings() {
        // Same geometry, buildings removed: the nearest (north) node wins, proving the
        // building footprint — not some other bias — changed the attachment.
        val graph = SurfaceGraphBuilder.build(thinConcourseModel(withBuilding = false))
        val gate = graph.nodes.firstOrNull { it.kind == SurfaceNodeKind.GATE }
        assertNotNull(gate, "expected a gate node with an inferred connector")
        val hit = gateConnector(graph)
        assertNotNull(hit, "expected a gate node with an inferred connector")
        assertTrue(
            hit.otherNode.coordinate.latitude > gate.coordinate.latitude,
            "without buildings the geometrically nearest (north) node is chosen",
        )
    }

    @Test
    fun routeThroughBuildingConnectorLowersConfidence() {
        // A stand whose only reachable taxi node is across a building: the connector is
        // still made (routing shouldn't fail) but flagged as crossing a building.
        val twyNorth = SurfaceTaxiway(
            osmID = "way/twy-north", tags = mapOf("aeroway" to "taxiway", "ref" to "N"),
            isTaxilane = false, name = "N",
            geometry = listOf(g(0.0003, 0.0000), g(0.0003, 0.0010)),
            oneway = false, access = null, widthMeters = null,
        )
        val gate = SurfaceParking(
            osmID = "node/gate", tags = mapOf("aeroway" to "gate", "ref" to "G1"),
            kind = SurfaceParking.Kind.GATE, name = "G1", coordinate = g(-0.00025, 0.0000),
        )
        val building = SurfaceBuilding(
            osmID = "way/concourse", tags = mapOf("aeroway" to "terminal"),
            polygon = listOf(
                g(0.0002, -0.0004), g(0.0002, 0.0004),
                g(-0.0002, 0.0004), g(-0.0002, -0.0004),
            ),
        )
        val runway = SurfaceRunway(
            osmID = "way/rwy", tags = mapOf("aeroway" to "runway", "ref" to "09/27"),
            idents = listOf("09", "27"),
            centerline = listOf(g(0.0100, -0.0050), g(0.0100, 0.0050)),
            widthMeters = 45.0, widthInferred = false,
        )
        val model = AirportSurfaceModel(
            icao = "KBLD", reference = GeoCoordinate(ref),
            runways = listOf(runway), runwayEnds = makeEnds(runway),
            taxiways = listOf(twyNorth), holdingPositions = emptyList(),
            parkingPositions = listOf(gate), aprons = emptyList(), buildings = listOf(building),
            source = testProvenance(ref, rawElementCount = 3),
            confidence = SurfaceConfidence.LOW,
        )
        val graph = SurfaceGraphBuilder.build(model)
        val hit = gateConnector(graph)
        assertNotNull(
            hit,
            "expected an inferred connector even when the only node is across the building",
        )
        assertTrue(
            hit.connector.crossesBuilding,
            "the only reachable connector crosses the building and should be flagged",
        )
    }

    // MARK: - Cache schema versioning

    @Test
    fun freshModelStampsCurrentSchemaVersion() {
        val m = MockAirportSurface.model(
            icao = "KTST", reference = ref, primaryRunwayIdent = "36", gate = "A1", nowMillis = 0L,
        )
        assertEquals(OSMSurface.SURFACE_SCHEMA_VERSION, m.source.schemaVersion)
        assertFalse(m.source.isOutdatedSchema)
    }

    @Test
    fun legacyCacheDecodesAsOutdatedSchema() {
        // Encode a current model, then strip the fields a pre-v2 cache would not have.
        val m = MockAirportSurface.model(
            icao = "KTST", reference = ref, primaryRunwayIdent = "36", gate = "A1", nowMillis = 0L,
        )
        val encoded = SurfaceJson.encodeToString(AirportSurfaceModel.serializer(), m)
        val obj = Json.parseToJsonElement(encoded).jsonObject.toMutableMap()
        obj.remove("buildings")
        val source = (obj["source"] as JsonObject).toMutableMap()
        source.remove("schemaVersion")
        obj["source"] = JsonObject(source)

        val legacy = JsonObject(obj).toString()
        val decoded = AirportSurfaceModel.decode(legacy)

        assertTrue(decoded.buildings.isEmpty(), "missing buildings decode to empty, not a failure")
        assertEquals(1, decoded.source.schemaVersion, "a missing schemaVersion decodes to legacy v1")
        assertTrue(decoded.source.isOutdatedSchema, "a v1 cache is flagged for re-fetch")
    }

    @Test
    fun currentCacheRoundTripsWithoutRefetch() {
        val m = MockAirportSurface.model(
            icao = "KTST", reference = ref, primaryRunwayIdent = "36", gate = "A1", nowMillis = 0L,
        )
        val encoded = SurfaceJson.encodeToString(AirportSurfaceModel.serializer(), m)
        val decoded = AirportSurfaceModel.decode(encoded)
        assertEquals(OSMSurface.SURFACE_SCHEMA_VERSION, decoded.source.schemaVersion)
        assertFalse(decoded.source.isOutdatedSchema)
    }
}
