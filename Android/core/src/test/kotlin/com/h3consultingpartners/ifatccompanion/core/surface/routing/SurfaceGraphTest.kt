package com.h3consultingpartners.ifatccompanion.core.surface.routing

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.surface.AirportSurfaceModel
import com.h3consultingpartners.ifatccompanion.core.surface.GeoCoordinate
import com.h3consultingpartners.ifatccompanion.core.surface.MockAirportSurface
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceConfidence
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceRunway
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceTaxiway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Graph generation: connected taxiway graph, intersections, runway intersections,
 * mapped + inferred holding positions, disconnected geometry, and source-id preservation.
 *
 * Ported from `IFATCCompanionTests/SurfaceGraphTests.swift`.
 */
class SurfaceGraphTest {

    private val ref = Coordinate(40.0, -75.0)

    private fun mockGraph(): Pair<AirportSurfaceModel, SurfaceGraph> {
        val m = MockAirportSurface.model(
            icao = "KTEST", reference = ref, primaryRunwayIdent = "36", gate = "A1", nowMillis = 0L,
        )
        return m to SurfaceGraphBuilder.build(m)
    }

    private fun g(dLat: Double, dLon: Double) =
        GeoCoordinate(ref.latitude + dLat, ref.longitude + dLon)

    @Test
    fun graphHasNodesAndEdges() {
        val (_, g) = mockGraph()
        assertTrue(g.nodes.size > 3)
        assertTrue(g.edges.size > 1)
    }

    @Test
    fun connectedTaxiwayGraph() {
        val (_, g) = mockGraph()
        // Gate, taxiway A, taxiway C, primary hold all connect → one component.
        assertEquals(1, g.componentCount, "the mock surface is fully connected")
    }

    @Test
    fun taxiwayIntersectionNode() {
        val (_, g) = mockGraph()
        // Taxiway A and C share a vertex → an intersection node exists there.
        assertTrue(g.nodes.any { it.kind == SurfaceNodeKind.INTERSECTION })
    }

    @Test
    fun runwayIntersectionDetectedAsCrossing() {
        val (_, g) = mockGraph()
        assertTrue(g.runwayCrossingEdges.isNotEmpty(), "taxiway A crosses the crossing runway")
        assertTrue(g.runwayCrossingEdges.all { it.crossingPoint != null })
        assertTrue(g.runwayCrossingEdges.all { it.runwayOccupancy })
    }

    @Test
    fun mappedHoldingPositionNode() {
        val (_, g) = mockGraph()
        assertTrue(
            g.nodes.any {
                it.kind == SurfaceNodeKind.HOLDING_POSITION && !it.inferred && it.runwayRef == "36"
            },
        )
    }

    @Test
    fun inferredHoldingPositionWhenNoneMapped() {
        // A runway with a taxiway reaching its threshold, but NO mapped hold.
        val runway = SurfaceRunway(
            osmID = "way/r", tags = mapOf("aeroway" to "runway", "ref" to "18/36"),
            idents = listOf("18", "36"),
            centerline = listOf(g(-0.0030, 0.0000), g(0.0030, 0.0000)),
            widthMeters = 45.0, widthInferred = false,
        )
        val twy = SurfaceTaxiway(
            osmID = "way/t", tags = mapOf("aeroway" to "taxiway", "ref" to "A"),
            isTaxilane = false, name = "A",
            geometry = listOf(g(-0.0028, -0.0006), g(-0.0028, 0.0000)),
            oneway = false, access = null, widthMeters = null,
        )
        val model = AirportSurfaceModel(
            icao = "KNOH", reference = GeoCoordinate(ref),
            runways = listOf(runway), runwayEnds = makeEnds(runway),
            taxiways = listOf(twy), holdingPositions = emptyList(), parkingPositions = emptyList(),
            aprons = emptyList(),
            source = testProvenance(ref, rawElementCount = 2),
            confidence = SurfaceConfidence.LOW,
        )
        val graph = SurfaceGraphBuilder.build(model)
        assertTrue(
            graph.nodes.any { it.kind == SurfaceNodeKind.HOLDING_POSITION && it.inferred },
            "a runway entry with no mapped hold should yield an inferred hold",
        )
    }

    @Test
    fun disconnectedGeometryReportsMultipleComponents() {
        // Two taxiways that do not share a vertex and are far apart.
        val a = SurfaceTaxiway(
            osmID = "way/a", tags = mapOf("aeroway" to "taxiway", "ref" to "A"), isTaxilane = false,
            name = "A", geometry = listOf(g(0.0, 0.0), g(0.001, 0.0)),
            oneway = false, access = null, widthMeters = null,
        )
        val b = SurfaceTaxiway(
            osmID = "way/b", tags = mapOf("aeroway" to "taxiway", "ref" to "B"), isTaxilane = false,
            name = "B", geometry = listOf(g(0.02, 0.02), g(0.021, 0.02)),
            oneway = false, access = null, widthMeters = null,
        )
        val runway = SurfaceRunway(
            osmID = "way/r", tags = mapOf("aeroway" to "runway", "ref" to "09/27"),
            idents = listOf("09", "27"),
            centerline = listOf(g(0.05, -0.05), g(0.05, 0.05)),
            widthMeters = 45.0, widthInferred = false,
        )
        val model = AirportSurfaceModel(
            icao = "KDIS", reference = GeoCoordinate(ref), runways = listOf(runway),
            runwayEnds = makeEnds(runway), taxiways = listOf(a, b), holdingPositions = emptyList(),
            parkingPositions = emptyList(), aprons = emptyList(),
            source = testProvenance(ref, rawElementCount = 3),
            confidence = SurfaceConfidence.LOW,
        )
        val graph = SurfaceGraphBuilder.build(model)
        assertTrue(graph.componentCount >= 2, "disconnected taxiways → multiple components")
    }

    @Test
    fun inferredConnectorForGate() {
        val (_, g) = mockGraph()
        assertTrue(g.inferredConnectorCount >= 1, "the gate connects via an inferred connector")
        assertTrue(g.nodes.any { it.kind == SurfaceNodeKind.GATE && it.name == "A1" })
    }

    @Test
    fun sourceIdentifiersPreservedOnEdges() {
        val (_, g) = mockGraph()
        val allOSMIDs = g.edges.flatMap { it.osmIDs }
        assertTrue(
            allOSMIDs.any { it.contains("mock-twy-A") },
            "graph edges retain their originating OSM feature ids",
        )
    }
}
