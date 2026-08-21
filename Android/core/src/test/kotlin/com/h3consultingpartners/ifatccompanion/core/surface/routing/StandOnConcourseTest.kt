package com.h3consultingpartners.ifatccompanion.core.surface.routing

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.surface.AirportSurfaceModel
import com.h3consultingpartners.ifatccompanion.core.surface.GeoCoordinate
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceBuilding
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceConfidence
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceParking
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceRunway
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceTaxiway
import com.h3consultingpartners.ifatccompanion.core.surface.toCoordinates
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stands a field maps **on the concourse itself**, and the same stand mapped twice.
 *
 * KIAD tags both: `gate` C24 is a node that is literally a *vertex of the Concourse C/D
 * outline* (`way/43194367`), while `parking_position` C24 is the aircraft stand 75 m south
 * of it on the apron. Nine of the C-row gate nodes are members of that way. Two things went
 * wrong there. Whichever feature the extract listed first became the taxi target, so a route
 * to C24 could end at a point inside the terminal; and from a stand sitting *on* a footprint
 * every candidate lead-in touches that footprint, so the flat concourse-crossing penalty
 * applied to all of them equally and the attachment fell back to nearest-overall — which
 * across a 33 m concourse is as easily the far side as the stand's own.
 *
 * Ported from `IFATCCompanionTests/StandOnConcourseTests.swift`.
 */
class StandOnConcourseTest {

    // Real KIAD geometry, so the numbers here are the ones the field actually has.
    private val gateOnOutline = Coordinate(38.9452671, -77.4464376)
    private val standOnApron = Coordinate(38.9445917, -77.4465230)
    private val southWall = 38.9452671 // the even-numbered gate nodes sit on it
    private val northWall = 38.9455500
    private val lonWest = -77.4480
    private val lonEast = -77.4450

    private fun c(lat: Double, lon: Double) = GeoCoordinate(lat, lon)

    private fun concourse(): SurfaceBuilding = SurfaceBuilding(
        osmID = "way/43194367",
        tags = mapOf(
            "building" to "airport_terminal", "aeroway" to "terminal",
            "name" to "Concourses C & D",
        ),
        polygon = listOf(
            c(southWall, lonWest), c(northWall, lonWest),
            c(northWall, lonEast), c(southWall, lonEast),
        ),
    )

    /** An apron taxilane running E–W at [lat], noded every ~17 m. */
    private fun lane(osmID: String, lat: Double): SurfaceTaxiway = SurfaceTaxiway(
        osmID = osmID, tags = mapOf("aeroway" to "taxilane"), isTaxilane = true, name = "",
        geometry = (0 until 15).map { c(lat, lonWest + 0.0002 * it.toDouble()) },
        oneway = false, access = null, widthMeters = null,
    )

    private fun runway(): SurfaceRunway = SurfaceRunway(
        osmID = "way/rwy", tags = mapOf("aeroway" to "runway", "ref" to "01C/19C"),
        idents = listOf("01C", "19C"),
        centerline = listOf(c(38.9200, -77.4600), c(38.9300, -77.4600)),
        widthMeters = 45.0, widthInferred = false,
    )

    private fun field(
        stands: List<SurfaceParking>,
        northLaneLatitude: Double = 38.9465,
    ): AirportSurfaceModel {
        val r = runway()
        return AirportSurfaceModel(
            icao = "KIAD", reference = GeoCoordinate(gateOnOutline),
            runways = listOf(r), runwayEnds = makeEnds(r),
            taxiways = listOf(
                lane("way/north", lat = northLaneLatitude),
                lane("way/south", lat = 38.9441500),
            ),
            holdingPositions = emptyList(), parkingPositions = stands, aprons = emptyList(),
            buildings = listOf(concourse()),
            source = testProvenance(gateOnOutline, rawElementCount = 5),
            confidence = SurfaceConfidence.LOW,
        )
    }

    private fun gateC24(): SurfaceParking = SurfaceParking(
        osmID = "node/3413155764", tags = mapOf("aeroway" to "gate", "ref" to "C24"),
        kind = SurfaceParking.Kind.GATE, name = "C24", coordinate = GeoCoordinate(gateOnOutline),
    )

    private fun standC24(): SurfaceParking = SurfaceParking(
        osmID = "way/1008778924", tags = mapOf("aeroway" to "parking_position", "ref" to "C24"),
        kind = SurfaceParking.Kind.PARKING_POSITION, name = "C24",
        coordinate = GeoCoordinate(standOnApron),
    )

    private fun standNodes(graph: SurfaceGraph, name: String): List<SurfaceNode> =
        graph.nodes.filter {
            (it.kind == SurfaceNodeKind.GATE || it.kind == SurfaceNodeKind.PARKING) && it.name == name
        }

    private data class ConnectorHit(val edge: SurfaceEdge, val other: SurfaceNode)

    private fun connector(graph: SurfaceGraph, stand: SurfaceNode): ConnectorHit? {
        val e = graph.edges.firstOrNull {
            it.inferred && (it.from == stand.id || it.to == stand.id)
        } ?: return null
        return ConnectorHit(e, graph.nodes[if (e.from == stand.id) e.to else e.from])
    }

    // MARK: - One stand mapped twice

    @Test
    fun theParkingPositionIsTheStandNodeNotTheGateOnTheConcourse() {
        // Gate listed first, as an extract sorted nodes-before-ways delivers it.
        val graph = SurfaceGraphBuilder.build(field(listOf(gateC24(), standC24())))
        val nodes = standNodes(graph, "C24")
        assertEquals(1, nodes.size, "one physical stand contributes one routable node")
        val node = nodes.firstOrNull()
        assertNotNull(node, "expected a C24 stand node")
        assertEquals(SurfaceNodeKind.PARKING, node.kind, "the aircraft parks on the parking_position")
        assertEquals("way/1008778924", node.osmID)
        assertTrue(
            abs(node.coordinate.latitude - standOnApron.latitude) < 1e-6,
            "the route must end on the apron, not on the terminal outline",
        )
    }

    @Test
    fun theOrderTheExtractListsThemInDoesNotDecide() {
        val gateFirst = SurfaceGraphBuilder.build(field(listOf(gateC24(), standC24())))
        val standFirst = SurfaceGraphBuilder.build(field(listOf(standC24(), gateC24())))
        assertEquals(
            standNodes(gateFirst, "C24").firstOrNull()?.osmID,
            standNodes(standFirst, "C24").firstOrNull()?.osmID,
            "whichever way round the extract lists them, the stand is the target",
        )
    }

    @Test
    fun bothFeaturesStayInTheModel() {
        val model = field(listOf(gateC24(), standC24()))
        assertEquals(2, model.parkingPositions.size, "no OSM feature is discarded")
        assertEquals(1, model.routableStands.size, "but only one of them is a taxi target")
        assertEquals(
            "way/1008778924", model.parking("C24")?.osmID,
            "the lookup resolves to the stand an aircraft can park on",
        )
    }

    @Test
    fun aGateIsOnlySupersededByAStandCloseEnoughToBeTheSameOne() {
        // A same-named stand right across the field is a different stand, not this one.
        val distant = SurfaceParking(
            osmID = "way/elsewhere",
            tags = mapOf("aeroway" to "parking_position", "ref" to "C24"),
            kind = SurfaceParking.Kind.PARKING_POSITION, name = "C24",
            coordinate = c(38.9600, -77.4464376),
        )
        val model = field(listOf(gateC24(), distant))
        val metres = SurfaceGeometry
            .distanceMeters(gateOnOutline, distant.coordinate.toCoordinate()).toInt()
        assertEquals(
            2, model.routableStands.size,
            "a stand $metres m away is not the same stand",
        )
    }

    @Test
    fun aFieldMappingOnlyGateNodesIsUnchanged() {
        val model = field(listOf(gateC24()))
        assertEquals(
            listOf("node/3413155764"), model.routableStands.map { it.osmID },
            "with no parking_position to supersede it the gate is still the stand",
        )
    }

    // MARK: - Lead-ins from a stand on the outline

    @Test
    fun aLeadInFromAStandOnTheOutlineTakesTheShallowestCrossing() {
        // Gate only, so the target really is the node on the concourse. The north lane is
        // nearer, but reaching it means crossing the building; the south lane does not.
        val graph = SurfaceGraphBuilder.build(
            field(listOf(gateC24()), northLaneLatitude = 38.9456500),
        )
        val stand = standNodes(graph, "C24").firstOrNull()
        assertNotNull(stand, "expected a C24 stand node with an inferred connector")
        val hit = connector(graph, stand)
        assertNotNull(hit, "expected a C24 stand node with an inferred connector")
        assertTrue(
            hit.other.coordinate.latitude < stand.coordinate.latitude,
            "the lead-in leaves the concourse on the stand's own side",
        )
        assertFalse(
            hit.edge.crossesBuilding,
            "a lead-in that only starts on the outline is not cutting through it",
        )
    }

    @Test
    fun theAttachmentDoesNotFlipWhenTheFarLaneEdgesCloser() {
        // Before the intrusion-aware penalty, every candidate scored alike and a few metres
        // of taxilane geometry decided the side. The stand's own side must win either way.
        for (northLane in listOf(38.9465000, 38.9463800, 38.9458000)) {
            val graph = SurfaceGraphBuilder.build(
                field(listOf(gateC24(), standC24()), northLaneLatitude = northLane),
            )
            val stand = standNodes(graph, "C24").firstOrNull()
            assertNotNull(stand, "expected a connector with the north lane at $northLane")
            val hit = connector(graph, stand)
            assertNotNull(hit, "expected a connector with the north lane at $northLane")
            assertTrue(
                hit.other.coordinate.latitude < southWall,
                "north lane at $northLane: attached across the concourse",
            )
        }
    }

    // MARK: - The geometry primitive

    @Test
    fun intrusionIsZeroForALeadInLeavingTheBuilding() {
        val poly = concourse().polygon.toCoordinates()
        val outward = Coordinate(southWall - 0.0010, -77.4464376)
        assertTrue(
            abs(SurfaceGeometry.segmentIntrusionMeters(gateOnOutline, outward, poly) - 0.0) <= 0.5,
            "a segment starting on the boundary and heading away is outside",
        )
        assertTrue(
            SurfaceGeometry.segmentIntersectsPolygon(gateOnOutline, outward, poly),
            "the boolean test cannot tell that apart — which is the bug it caused",
        )
    }

    @Test
    fun intrusionMeasuresTheSpanInsideForACrossing() {
        val poly = concourse().polygon.toCoordinates()
        val across = Coordinate(northWall + 0.0010, -77.4464376)
        val width = SurfaceGeometry.distanceMeters(
            Coordinate(southWall, -77.4464376),
            Coordinate(northWall, -77.4464376),
        )
        assertTrue(
            abs(SurfaceGeometry.segmentIntrusionMeters(gateOnOutline, across, poly) - width) <= 1.0,
            "a crossing is charged the concourse's full width",
        )
    }

    @Test
    fun intrusionCountsOnlyTheInsidePortion() {
        val poly = concourse().polygon.toCoordinates()
        // Starts south of the building, ends north of it: only the middle span is inside.
        val from = Coordinate(southWall - 0.0020, -77.4464376)
        val to = Coordinate(northWall + 0.0020, -77.4464376)
        val total = SurfaceGeometry.distanceMeters(from, to)
        val inside = SurfaceGeometry.segmentIntrusionMeters(from, to, poly)
        assertTrue(inside < total / 2, "the approach and departure legs are outside")
        assertTrue(inside > 25, "but the concourse's own width is inside")
    }
}
