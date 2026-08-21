package com.h3consultingpartners.ifatccompanion.core.surface.routing

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.surface.AircraftSizeClass
import com.h3consultingpartners.ifatccompanion.core.surface.AirportSurfaceModel
import com.h3consultingpartners.ifatccompanion.core.surface.GeoCoordinate
import com.h3consultingpartners.ifatccompanion.core.surface.MockAirportSurface
import com.h3consultingpartners.ifatccompanion.core.surface.OSMSurfaceNormalizer
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceConfidence
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceHoldingPosition
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceParking
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceRunway
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceRunwayEnd
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceTaxiway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Routing: correct runway end, full-length preference, runway-crossing penalty, no
 * illegal disconnected jumps, no route through parking stands, low-confidence downgrade,
 * no-path fallback, and recalculation.
 *
 * Ported from `IFATCCompanionTests/TaxiRoutingTests.swift`.
 */
class TaxiRoutingTest {

    private val ref = Coordinate(40.0, -75.0)

    private fun mockEngine(runway: String = "36", gate: String = "A1"): TaxiRouteEngine {
        val m = MockAirportSurface.model(
            icao = "KTEST", reference = ref, primaryRunwayIdent = runway, gate = gate, nowMillis = 0L,
        )
        val g = SurfaceGraphBuilder.build(m)
        return TaxiRouteEngine(g, m)
    }

    private fun departureRoute(runway: String = "36", gate: String = "A1"): SurfaceTaxiRoute? =
        mockEngine(runway, gate).route(
            TaxiRouteEngine.Request(
                startCoordinate = MockAirportSurface.gateCoordinate(ref),
                startGateName = gate, isDeparture = true,
                assignedRunwayIdent = runway, arrivalGateName = null,
                aircraft = AircraftSizeClass.MEDIUM,
            ),
        )

    @Test
    fun routesToCorrectRunwayEnd() {
        val route = departureRoute("36")
        assertNotNull(route)
        assertEquals("36", route.holdShortRunway)
        assertEquals("runway 36", route.destinationLabel)
    }

    @Test
    fun taxiwaySequenceIsNamed() {
        val route = departureRoute()
        assertTrue(route?.taxiwaySequence?.contains("A") ?: false)
        assertTrue(route?.taxiwaySequence?.contains("C") ?: false)
    }

    @Test
    fun highConfidenceOnWellFormedSurface() {
        val route = departureRoute()
        assertEquals(SurfaceConfidence.HIGH, route?.confidence)
    }

    @Test
    fun exactlyOneCrossingAndNotOfTheDepartureRunway() {
        val route = departureRoute("36")
        assertEquals(1, route?.crossings?.size, "the route crosses exactly the one runway in the way")
        // It holds short of its own departure runway — it never crosses runway 36.
        assertFalse(route?.crossings?.any { it.runwayIdent == "36" } ?: true)
        assertEquals(MockAirportSurface.crossingIdent("36"), route?.crossings?.firstOrNull()?.runwayIdent)
    }

    @Test
    fun departureHoldsShortOfOwnRunwayInsteadOfCrossingToFarSideHold() {
        // Reproduces the reported bug: the only hold/entry tagged for the assigned runway sits on
        // the *far* side, so A* crossed the departure runway to reach it and placed the hold on
        // the other side. A departure must never cross its own runway — it must stop at the
        // near-side hold-short of that crossing and treat it as the departure hold.
        fun c(lat: Double, lon: Double) = GeoCoordinate(lat, lon)

        // Runway 09/27 east–west (09 threshold at the west end).
        val runway = SurfaceRunway(
            osmID = "way/r", tags = mapOf("aeroway" to "runway", "ref" to "09/27"),
            idents = listOf("09", "27"),
            centerline = listOf(c(40.0000, -75.0050), c(40.0000, -74.9950)),
            widthMeters = 45.0, widthInferred = false,
        )
        val ends = OSMSurfaceNormalizer.makeRunwayEnds(listOf(runway))
        // Taxiway X runs south → north across the runway (crossing it mid-length, ~256 m east of
        // the 09 threshold so it is a crossing, not an entry). Its south end is where the aircraft
        // must hold short; its north end is across the runway.
        val twyX = SurfaceTaxiway(
            osmID = "way/x", tags = mapOf("aeroway" to "taxiway", "ref" to "X"), isTaxilane = false,
            name = "X",
            geometry = listOf(c(39.9975, -75.0020), c(40.0000, -75.0020), c(40.0025, -75.0020)),
            oneway = false, access = null, widthMeters = null,
        )
        // The ONLY mapped hold for 09 is on the far (north) side, at taxiway X's north end.
        val hold = SurfaceHoldingPosition(
            osmID = "node/h", tags = mapOf("aeroway" to "holding_position", "ref" to "09"),
            coordinate = c(40.0025, -75.0020), runwayRef = "09", inferred = false,
        )
        // Gate just south of taxiway X's south end (attaches to the south node, no crossing).
        val gate = SurfaceParking(
            osmID = "node/g", tags = mapOf("aeroway" to "gate", "ref" to "G1"),
            kind = SurfaceParking.Kind.GATE, name = "G1", coordinate = c(39.9970, -75.0020),
        )
        val m = AirportSurfaceModel(
            icao = "KXRW", reference = c(40.0, -75.0), runways = listOf(runway),
            runwayEnds = ends, taxiways = listOf(twyX), holdingPositions = listOf(hold),
            parkingPositions = listOf(gate), aprons = emptyList(),
            source = testProvenance(ref, halfSpanDegrees = 0.05, rawElementCount = 4),
            confidence = SurfaceConfidence.MEDIUM,
        )
        val g = SurfaceGraphBuilder.build(m)
        val engine = TaxiRouteEngine(g, m)
        val route = engine.route(
            TaxiRouteEngine.Request(
                startCoordinate = c(39.9970, -75.0020).toCoordinate(),
                startGateName = "G1", isDeparture = true,
                assignedRunwayIdent = "09", arrivalGateName = null,
                aircraft = AircraftSizeClass.MEDIUM,
            ),
        )
        assertNotNull(route, "a departure whose only far-side hold requires a crossing must still route")
        assertEquals("09", route.holdShortRunway)
        // It never crosses its own runway, and no crossing of it is reported…
        assertFalse(
            route.crossings.any { it.runwayIdent == "09" || it.runwayIdent == "27" },
            "the departure runway is the hold, not a crossing",
        )
        // …and the whole route stays south of the runway centerline (never steps onto the far side).
        for (point in route.geometry) {
            assertTrue(
                point.latitude < 40.0000,
                "the route holds short on the near side and never crosses to the far side",
            )
        }
        // The route still ends near the near-side hold-short of the runway (just south of the crossing).
        val end = route.endCoordinate.toCoordinate()
        assertTrue(
            SurfaceGeometry.distanceMeters(end, c(40.0000, -75.0020).toCoordinate()) < 60,
            "the route ends holding short of the runway at the crossing point",
        )
        // A contiguous path survives the truncation (N nodes joined by N-1 edges).
        assertEquals(route.nodeIDs.size - 1, route.edgeIDs.size)
    }

    @Test
    fun departureHoldsShortAtCrossingRatherThanTaxiingAroundTheRunway() {
        // Reproduces the reported bug: the only mapped hold for the assigned end sits on the far
        // side of the runway but is reachable *around* the far end, so nothing forced a crossing
        // and the route taxied the length of the field and back — 2.5 km to reach a hold across
        // a runway the aircraft is 300 m from. With no hold on this side, the route must instead
        // roll up to the taxiway that crosses the runway toward the assigned end and hold short
        // at that crossing threshold.
        fun c(lat: Double, lon: Double) = GeoCoordinate(lat, lon)

        // Runway 09/27 east–west (09 threshold at the west end), ~850 m long.
        val runway = SurfaceRunway(
            osmID = "way/r", tags = mapOf("aeroway" to "runway", "ref" to "09/27"),
            idents = listOf("09", "27"),
            centerline = listOf(c(40.0000, -75.0050), c(40.0000, -74.9950)),
            widthMeters = 45.0, widthInferred = false,
        )
        val ends = OSMSurfaceNormalizer.makeRunwayEnds(listOf(runway))
        // Two taxiways cross the runway south → north: X ~170 m from the 09 (departure) threshold,
        // Y ~170 m from the 27 threshold — past midfield, so holding there would leave too little
        // runway to depart from.
        val twyX = SurfaceTaxiway(
            osmID = "way/x", tags = mapOf("aeroway" to "taxiway", "ref" to "X"), isTaxilane = false,
            name = "X", geometry = listOf(c(39.9970, -75.0030), c(40.0030, -75.0030)),
            oneway = false, access = null, widthMeters = null,
        )
        val twyY = SurfaceTaxiway(
            osmID = "way/y", tags = mapOf("aeroway" to "taxiway", "ref" to "Y"), isTaxilane = false,
            name = "Y", geometry = listOf(c(39.9970, -74.9970), c(40.0030, -74.9970)),
            oneway = false, access = null, widthMeters = null,
        )
        // Parallels either side, joined by E around the *east* (27) end — so the far-side hold is
        // reachable without ever crossing the runway. That long way around is the bug.
        val twyP = SurfaceTaxiway(
            osmID = "way/p", tags = mapOf("aeroway" to "taxiway", "ref" to "P"), isTaxilane = false,
            name = "P",
            geometry = listOf(c(39.9970, -75.0030), c(39.9970, -74.9970), c(39.9970, -74.9930)),
            oneway = false, access = null, widthMeters = null,
        )
        val twyE = SurfaceTaxiway(
            osmID = "way/e", tags = mapOf("aeroway" to "taxiway", "ref" to "E"), isTaxilane = false,
            name = "E", geometry = listOf(c(39.9970, -74.9930), c(40.0030, -74.9930)),
            oneway = false, access = null, widthMeters = null,
        )
        val twyN = SurfaceTaxiway(
            osmID = "way/n", tags = mapOf("aeroway" to "taxiway", "ref" to "N"), isTaxilane = false,
            name = "N",
            geometry = listOf(
                c(40.0030, -74.9930), c(40.0030, -74.9970),
                c(40.0030, -75.0030), c(40.0030, -75.0046),
            ),
            oneway = false, access = null, widthMeters = null,
        )
        // The ONLY mapped hold for 09 is on the far (north) side, at the north parallel's west end.
        val hold = SurfaceHoldingPosition(
            osmID = "node/h", tags = mapOf("aeroway" to "holding_position", "ref" to "09"),
            coordinate = c(40.0030, -75.0046), runwayRef = "09", inferred = false,
        )
        // Gate on the south side, just off the P/X junction.
        val gate = SurfaceParking(
            osmID = "node/g", tags = mapOf("aeroway" to "gate", "ref" to "G1"),
            kind = SurfaceParking.Kind.GATE, name = "G1", coordinate = c(39.9965, -75.0028),
        )
        val m = AirportSurfaceModel(
            icao = "KXRA", reference = c(40.0, -75.0), runways = listOf(runway),
            runwayEnds = ends, taxiways = listOf(twyX, twyY, twyP, twyE, twyN),
            holdingPositions = listOf(hold), parkingPositions = listOf(gate), aprons = emptyList(),
            source = testProvenance(ref, halfSpanDegrees = 0.05, rawElementCount = 8),
            confidence = SurfaceConfidence.MEDIUM,
        )
        val g = SurfaceGraphBuilder.build(m)
        val engine = TaxiRouteEngine(g, m)
        val route = engine.route(
            TaxiRouteEngine.Request(
                startCoordinate = c(39.9965, -75.0028).toCoordinate(),
                startGateName = "G1", isDeparture = true,
                assignedRunwayIdent = "09", arrivalGateName = null,
                aircraft = AircraftSizeClass.MEDIUM,
            ),
        )
        assertNotNull(route, "a departure whose only hold is across the runway must still route")
        assertEquals("09", route.holdShortRunway)
        // It stays on the aircraft's side of the runway throughout…
        for (point in route.geometry) {
            assertTrue(
                point.latitude < 40.0000,
                "the route holds short on the near side and never crosses to the far side",
            )
        }
        // …and holds short at taxiway X's crossing — the one toward the assigned (09) end, not Y
        // past midfield, and not the far-side hold reached the long way around.
        val end = route.endCoordinate.toCoordinate()
        assertTrue(
            SurfaceGeometry.distanceMeters(end, c(40.0000, -75.0030).toCoordinate()) < 40,
            "the route ends holding short where taxiway X crosses the runway",
        )
        assertTrue(
            route.distanceMeters < 800,
            "the route no longer taxis the length of the field and around the runway end",
        )
        assertTrue(route.taxiwaySequence.contains("X"), "the hold-short taxiway is named to the pilot")
        assertFalse(
            route.taxiwaySequence.contains("E"),
            "the taxiways of the long way around the runway are not taxied",
        )
        // The departure runway is the hold, never a reported crossing.
        assertFalse(route.crossings.any { it.runwayIdent == "09" || it.runwayIdent == "27" })
        // A contiguous path (N nodes joined by N-1 edges) — the crossing edge is not taxied.
        assertEquals(route.nodeIDs.size - 1, route.edgeIDs.size)
    }

    @Test
    fun noIllegalDisconnectedJumps() {
        val route = departureRoute()
        assertNotNull(route)
        // A contiguous path: N nodes are joined by exactly N-1 edges.
        assertEquals(route.nodeIDs.size - 1, route.edgeIDs.size)
    }

    @Test
    fun doesNotRouteThroughParkingStands() {
        val m = MockAirportSurface.model(
            icao = "KTEST", reference = ref, primaryRunwayIdent = "36", gate = "A1", nowMillis = 0L,
        )
        val g = SurfaceGraphBuilder.build(m)
        val route = departureRoute()!!
        // Only the start node may be a gate/parking stand; none appear mid-route.
        for (nodeID in route.nodeIDs.drop(1)) {
            val node = g.node(nodeID)
            assertFalse(
                node?.kind == SurfaceNodeKind.GATE || node?.kind == SurfaceNodeKind.PARKING,
                "route must not pass through a parking stand",
            )
        }
    }

    @Test
    fun lowConfidenceDowngradeWhenUnnamedAndNoHolds() {
        var m = MockAirportSurface.model(
            icao = "KLOW", reference = ref, primaryRunwayIdent = "36", gate = "A1", nowMillis = 0L,
        )
        m = m.copy(
            taxiways = m.taxiways.map { it.copy(name = "") }, // strip names
            holdingPositions = emptyList(), // no mapped holds
        )
        m = m.copy(confidence = OSMSurfaceNormalizer.preliminaryConfidence(m))
        val g = SurfaceGraphBuilder.build(m)
        val engine = TaxiRouteEngine(g, m)
        val route = engine.route(
            TaxiRouteEngine.Request(
                startCoordinate = MockAirportSurface.gateCoordinate(ref),
                startGateName = "A1", isDeparture = true,
                assignedRunwayIdent = "36", arrivalGateName = null,
                aircraft = AircraftSizeClass.MEDIUM,
            ),
        )
        assertNotNull(route)
        assertTrue(
            route.confidence != SurfaceConfidence.HIGH,
            "unnamed geometry with no holds must not grade High",
        )
    }

    @Test
    fun noPathFallbackReturnsNil() {
        fun p(dLat: Double, dLon: Double) = GeoCoordinate(ref.latitude + dLat, ref.longitude + dLon)
        // A gate + a short taxiway near it, and a runway ~1 km away with nothing reaching it.
        val twy = SurfaceTaxiway(
            osmID = "way/t", tags = mapOf("aeroway" to "taxiway", "ref" to "A"), isTaxilane = false,
            name = "A", geometry = listOf(p(0.0, 0.0), p(0.0005, 0.0)),
            oneway = false, access = null, widthMeters = null,
        )
        val runway = SurfaceRunway(
            osmID = "way/r", tags = mapOf("aeroway" to "runway", "ref" to "18/36"),
            idents = listOf("18", "36"),
            centerline = listOf(p(0.01, 0.01), p(0.02, 0.01)), widthMeters = 45.0, widthInferred = false,
        )
        val gate = SurfaceParking(
            osmID = "node/g", tags = mapOf("aeroway" to "gate", "ref" to "A1"),
            kind = SurfaceParking.Kind.GATE, name = "A1", coordinate = p(0.0, 0.00005),
        )
        val m = AirportSurfaceModel(
            icao = "KNOP", reference = GeoCoordinate(ref), runways = listOf(runway),
            runwayEnds = emptyList(), taxiways = listOf(twy), holdingPositions = emptyList(),
            parkingPositions = listOf(gate), aprons = emptyList(),
            source = testProvenance(ref, rawElementCount = 3),
            confidence = SurfaceConfidence.LOW,
        )
        val g = SurfaceGraphBuilder.build(m)
        val engine = TaxiRouteEngine(g, m)
        val route = engine.route(
            TaxiRouteEngine.Request(
                startCoordinate = p(0.0, 0.0).toCoordinate(), startGateName = "A1", isDeparture = true,
                assignedRunwayIdent = "36", arrivalGateName = null, aircraft = AircraftSizeClass.MEDIUM,
            ),
        )
        assertNull(route, "no credible connected route → nil (Unavailable fallback)")
    }

    @Test
    fun departureRouteAnchorsAtStandWhileParked() {
        // Parked at the stand (start coordinate == the gate): the route begins at the gate.
        val route = departureRoute("36", "A1")
        assertNotNull(route)
        val start = route.startCoordinate.toCoordinate()
        assertTrue(
            SurfaceGeometry.distanceMeters(start, MockAirportSurface.gateCoordinate(ref)) < 30,
            "while parked at the stand the route still starts at the gate",
        )
    }

    @Test
    fun departureRouteStartsFromAircraftAfterPushback() {
        // After pushback the aircraft has moved off its stand onto taxiway A. The route
        // must no longer be anchored at the gate node (whose lead-in leg the aircraft has
        // already left, which is what read as "off route") — it starts on the taxiway,
        // and the post-pushback position tracks as on-route.
        val engine = mockEngine("36", "A1")
        val gate = MockAirportSurface.gateCoordinate(ref)
        val pushback = Coordinate(ref.latitude + 0.0010, ref.longitude + 0.0030)
        val route = engine.route(
            TaxiRouteEngine.Request(
                startCoordinate = pushback, startGateName = "A1", isDeparture = true,
                assignedRunwayIdent = "36", arrivalGateName = null, aircraft = AircraftSizeClass.MEDIUM,
            ),
        )
        assertNotNull(route)
        assertEquals("36", route.holdShortRunway, "still routes to the assigned runway")
        val start = route.startCoordinate.toCoordinate()
        assertTrue(
            SurfaceGeometry.distanceMeters(start, gate) > 40,
            "the route no longer starts at the gate node once pushed back",
        )
        // The post-pushback position tracks as on-route.
        val prog = RouteTracker().progress(pushback, route)
        assertTrue(prog.onRoute, "the post-pushback position is on the route")
    }

    @Test
    fun recalculationFromMidRouteStillRoutes() {
        val engine = mockEngine()
        // Start from a point partway along the route (near the crossing) rather than the gate.
        val mid = Coordinate(ref.latitude - 0.0010, ref.longitude + 0.0030)
        val route = engine.route(
            TaxiRouteEngine.Request(
                startCoordinate = mid, startGateName = null, isDeparture = true,
                assignedRunwayIdent = "36", arrivalGateName = null, aircraft = AircraftSizeClass.MEDIUM,
            ),
        )
        assertNotNull(route, "recalculation from the current position still reaches the runway")
        assertEquals("36", route.holdShortRunway)
    }

    @Test
    fun arrivalRoutesToGate() {
        val engine = mockEngine()
        val route = engine.route(
            TaxiRouteEngine.Request(
                startCoordinate = MockAirportSurface.runwayExitCoordinate(ref),
                startGateName = null, isDeparture = false,
                assignedRunwayIdent = null, arrivalGateName = "A1", aircraft = AircraftSizeClass.MEDIUM,
            ),
        )
        assertNotNull(route)
        assertEquals("A1", route.arrivalGate)
        assertEquals("gate A1", route.destinationLabel)
    }

    @Test
    fun departureMatchesZeroPaddedRunwayIdent() {
        // The app assigns a non-padded ident ("9") while OSM tags the runway end zero-padded
        // ("09") — they are the same physical end. The departure must still route; otherwise
        // KATL's east-flow runways (8L/9L, tagged 08L/09L in OSM) never resolve a goal and the
        // map is stuck on "route pending".
        val m = MockAirportSurface.model(
            icao = "KPAD", reference = ref, primaryRunwayIdent = "09", gate = "A1", nowMillis = 0L,
        )
        val g = SurfaceGraphBuilder.build(m)
        val engine = TaxiRouteEngine(g, m)
        val route = engine.route(
            TaxiRouteEngine.Request(
                startCoordinate = MockAirportSurface.gateCoordinate(ref),
                startGateName = "A1", isDeparture = true,
                assignedRunwayIdent = "9", arrivalGateName = null, aircraft = AircraftSizeClass.MEDIUM,
            ),
        )
        assertNotNull(route, "an assigned \"9\" must match the OSM-tagged \"09\" runway end")
        assertEquals("9", route.holdShortRunway, "the clearance still names the assigned runway")
    }

    @Test
    fun departureRoutesToCorrectEndWhenRunwayIsSplitAcrossWays() {
        // Reproduces the KLAX 06R/24L bug: the runway is two OSM ways — a main centerline and
        // a short stub at the west (06R) end — both tagged "06R/24L". Deriving ends per way
        // fabricated a "24L" end at the *west* extreme, planting a "24L" entry node at the
        // 06R end, so a 24L departure taxied to the wrong side. The route must reach the east
        // (24L) threshold, never the west one.
        fun c(lat: Double, lon: Double) = GeoCoordinate(lat, lon)

        // Runway 06R/24L as two ways (east–west): main + a ~90 m west-end stub, like KLAX.
        val rwyMain = SurfaceRunway(
            osmID = "way/rwy-main", tags = mapOf("aeroway" to "runway", "ref" to "06R/24L"),
            idents = listOf("06R", "24L"),
            centerline = listOf(c(40.0000, -75.0150), c(40.0000, -74.9850)),
            widthMeters = 45.0, widthInferred = false,
        )
        val rwyStub = SurfaceRunway(
            osmID = "way/rwy-stub", tags = mapOf("aeroway" to "runway", "ref" to "06R/24L"),
            idents = listOf("06R", "24L"),
            centerline = listOf(c(40.0000, -75.0160), c(40.0000, -75.0150)),
            widthMeters = 45.0, widthInferred = false,
        )
        val runways = listOf(rwyMain, rwyStub)
        val ends = OSMSurfaceNormalizer.makeRunwayEnds(runways)

        // A parallel taxiway just south of the runway, with a hold-short connector at each end.
        val twyA = SurfaceTaxiway(
            osmID = "way/A", tags = mapOf("aeroway" to "taxiway", "ref" to "A"), isTaxilane = false,
            name = "A", geometry = listOf(c(39.9990, -75.0150), c(39.9990, -74.9850)),
            oneway = false, access = null, widthMeters = null,
        )
        val twyW = SurfaceTaxiway(
            osmID = "way/W", tags = mapOf("aeroway" to "taxiway", "ref" to "W"), isTaxilane = false,
            name = "W", geometry = listOf(c(39.9990, -75.0150), c(39.9995, -75.0150)),
            oneway = false, access = null, widthMeters = null,
        )
        val twyE = SurfaceTaxiway(
            osmID = "way/E", tags = mapOf("aeroway" to "taxiway", "ref" to "E"), isTaxilane = false,
            name = "E", geometry = listOf(c(39.9990, -74.9850), c(39.9995, -74.9850)),
            oneway = false, access = null, widthMeters = null,
        )
        // Gate at the west end — the naive short route heads to the wrong (06R) end.
        val gate = SurfaceParking(
            osmID = "node/g", tags = mapOf("aeroway" to "gate", "ref" to "G1"),
            kind = SurfaceParking.Kind.GATE, name = "G1", coordinate = c(39.9970, -75.0150),
        )
        val m = AirportSurfaceModel(
            icao = "KSPL", reference = c(40.0, -75.0), runways = runways,
            runwayEnds = ends, taxiways = listOf(twyA, twyW, twyE), holdingPositions = emptyList(),
            parkingPositions = listOf(gate), aprons = emptyList(),
            source = testProvenance(ref, halfSpanDegrees = 0.05, rawElementCount = 6),
            confidence = SurfaceConfidence.MEDIUM,
        )
        val g = SurfaceGraphBuilder.build(m)
        val engine = TaxiRouteEngine(g, m)
        val route = engine.route(
            TaxiRouteEngine.Request(
                startCoordinate = c(39.9970, -75.0150).toCoordinate(),
                startGateName = "G1", isDeparture = true,
                assignedRunwayIdent = "24L", arrivalGateName = null, aircraft = AircraftSizeClass.MEDIUM,
            ),
        )
        assertNotNull(route, "a 24L departure over a split runway must still route")
        assertEquals("24L", route.holdShortRunway)

        val east = Coordinate(40.0000, -74.9850) // 24L threshold
        val west = Coordinate(40.0000, -75.0160) // 06R threshold
        val end = route.endCoordinate.toCoordinate()
        assertTrue(
            SurfaceGeometry.distanceMeters(end, east) < 200,
            "the route must reach the east (24L) threshold",
        )
        assertTrue(
            SurfaceGeometry.distanceMeters(end, west) > 2000,
            "the route must not end at the west (06R) end",
        )
    }

    @Test
    fun departureFallsThroughToReachableGoalWhenEntryStranded() {
        // Reproduces the KATL 26L failure: the surface loads and the aircraft snaps onto the
        // graph, but the runway-entry node for the assigned end is stranded in a disconnected
        // patch of the OSM graph, so A* to it finds no path. A holding position for the same
        // runway sits on the connected taxi network, so the route must fall through to it
        // instead of returning nil (which showed as "route pending" + a generic clearance).
        fun p(dLat: Double, dLon: Double) = GeoCoordinate(ref.latitude + dLat, ref.longitude + dLon)
        // Connected network: gate → taxiway A → taxiway B (which ends at the mapped hold).
        val twyA = SurfaceTaxiway(
            osmID = "way/a", tags = mapOf("aeroway" to "taxiway", "ref" to "A"), isTaxilane = false,
            name = "A", geometry = listOf(p(0.0002, 0.0), p(0.0030, 0.0)),
            oneway = false, access = null, widthMeters = null,
        )
        val twyB = SurfaceTaxiway(
            osmID = "way/b", tags = mapOf("aeroway" to "taxiway", "ref" to "B"), isTaxilane = false,
            name = "B", geometry = listOf(p(0.0030, 0.0), p(0.0030, 0.0010)),
            oneway = false, access = null, widthMeters = null,
        )
        // A tiny isolated stub next to the 36 threshold — nearest the threshold, so it becomes
        // the runway-entry node, but it is wired to nothing (44 m from taxiway B, far past the
        // ~1 m node-merge grid).
        val stub = SurfaceTaxiway(
            osmID = "way/stub", tags = mapOf("aeroway" to "taxiway", "ref" to "S"), isTaxilane = false,
            name = "S", geometry = listOf(p(0.00345, 0.0010), p(0.00355, 0.0010)),
            oneway = false, access = null, widthMeters = null,
        )
        val runway = SurfaceRunway(
            osmID = "way/r", tags = mapOf("aeroway" to "runway", "ref" to "18/36"),
            idents = listOf("18", "36"),
            centerline = listOf(p(0.0034, 0.0010), p(0.0090, 0.0010)),
            widthMeters = 45.0, widthInferred = false,
        )
        val end36 = SurfaceRunwayEnd(
            ident = "36", threshold = p(0.0034, 0.0010), oppositeThreshold = p(0.0090, 0.0010),
            headingDegrees = 360.0, runwayOSMID = "way/r", widthMeters = 45.0,
        )
        val end18 = SurfaceRunwayEnd(
            ident = "18", threshold = p(0.0090, 0.0010), oppositeThreshold = p(0.0034, 0.0010),
            headingDegrees = 180.0, runwayOSMID = "way/r", widthMeters = 45.0,
        )
        // Mapped hold for 36, coincident with taxiway B's end → reachable from the gate.
        val hold = SurfaceHoldingPosition(
            osmID = "node/h", tags = mapOf("aeroway" to "holding_position", "ref" to "36"),
            coordinate = p(0.0030, 0.0010), runwayRef = "36", inferred = false,
        )
        val gate = SurfaceParking(
            osmID = "node/g", tags = mapOf("aeroway" to "gate", "ref" to "A1"),
            kind = SurfaceParking.Kind.GATE, name = "A1", coordinate = p(0.0, 0.0),
        )
        val m = AirportSurfaceModel(
            icao = "KSTR", reference = GeoCoordinate(ref), runways = listOf(runway),
            runwayEnds = listOf(end36, end18), taxiways = listOf(twyA, twyB, stub),
            holdingPositions = listOf(hold), parkingPositions = listOf(gate), aprons = emptyList(),
            source = testProvenance(ref, rawElementCount = 6),
            confidence = SurfaceConfidence.MEDIUM,
        )
        val g = SurfaceGraphBuilder.build(m)
        val engine = TaxiRouteEngine(g, m)
        val route = engine.route(
            TaxiRouteEngine.Request(
                startCoordinate = p(0.0, 0.0).toCoordinate(), startGateName = "A1", isDeparture = true,
                assignedRunwayIdent = "36", arrivalGateName = null, aircraft = AircraftSizeClass.MEDIUM,
            ),
        )
        assertNotNull(
            route,
            "a stranded runway-entry must fall through to the reachable hold, not fail the route",
        )
        assertEquals("36", route.holdShortRunway)
    }

    // MARK: - Aircraft-location sensitivity (diagonal high-speed exits)

    /**
     * Reproduces the reported bug: after landing, with diagonal high-speed exits off the
     * runway, the taxi route began "about one taxiway away" — a plain node snap jumped the
     * start to the exit's far end out on the parallel taxiway (the nearest *node* to an
     * aircraft partway down the diagonal), and recalculating from a nearby point resolved to
     * the same node, so the route never moved. Snapping onto the nearest *edge* must begin the
     * route under the aircraft and track it as it rolls.
     */
    private fun diagonalExitSurface(): AirportSurfaceModel {
        fun p(dLat: Double, dLon: Double) = GeoCoordinate(ref.latitude + dLat, ref.longitude + dLon)
        // Runway 18/36 north–south (aircraft lands on 36, rolling out northbound).
        val runway = SurfaceRunway(
            osmID = "way/r", tags = mapOf("aeroway" to "runway", "ref" to "18/36"),
            idents = listOf("18", "36"), centerline = listOf(p(-0.0060, 0.0), p(0.0060, 0.0)),
            widthMeters = 45.0, widthInferred = false,
        )
        val ends = OSMSurfaceNormalizer.makeRunwayEnds(listOf(runway))
        // Parallel taxiway P east of the runway; each diagonal exit's far end is a P vertex
        // (a shared junction), so the exits wire into the taxi network there.
        val twyP = SurfaceTaxiway(
            osmID = "way/p", tags = mapOf("aeroway" to "taxiway", "ref" to "P"), isTaxilane = false,
            name = "P",
            geometry = listOf(
                p(-0.0060, 0.0010), p(-0.0012, 0.0010),
                p(0.0008, 0.0010), p(0.0028, 0.0010), p(0.0060, 0.0010),
            ),
            oneway = false, access = null, widthMeters = null,
        )
        // Three diagonal high-speed exits, angled forward (northeast) toward P.
        val e1 = SurfaceTaxiway(
            osmID = "way/e1", tags = mapOf("aeroway" to "taxiway", "ref" to "E1"), isTaxilane = false,
            name = "E1", geometry = listOf(p(-0.0020, 0.0), p(-0.0012, 0.0010)),
            oneway = false, access = null, widthMeters = null,
        )
        val e2 = SurfaceTaxiway(
            osmID = "way/e2", tags = mapOf("aeroway" to "taxiway", "ref" to "E2"), isTaxilane = false,
            name = "E2", geometry = listOf(p(0.0000, 0.0), p(0.0008, 0.0010)),
            oneway = false, access = null, widthMeters = null,
        )
        val e3 = SurfaceTaxiway(
            osmID = "way/e3", tags = mapOf("aeroway" to "taxiway", "ref" to "E3"), isTaxilane = false,
            name = "E3", geometry = listOf(p(0.0020, 0.0), p(0.0028, 0.0010)),
            oneway = false, access = null, widthMeters = null,
        )
        val gate = SurfaceParking(
            osmID = "node/g", tags = mapOf("aeroway" to "gate", "ref" to "G1"),
            kind = SurfaceParking.Kind.GATE, name = "G1", coordinate = p(0.0040, 0.0025),
        )
        return AirportSurfaceModel(
            icao = "KDIA", reference = GeoCoordinate(ref), runways = listOf(runway),
            runwayEnds = ends, taxiways = listOf(twyP, e1, e2, e3), holdingPositions = emptyList(),
            parkingPositions = listOf(gate), aprons = emptyList(),
            source = testProvenance(ref, halfSpanDegrees = 0.05, rawElementCount = 6),
            confidence = SurfaceConfidence.MEDIUM,
        )
    }

    @Test
    fun arrivalStartsUnderAircraftOnDiagonalExit() {
        fun p(dLat: Double, dLon: Double) = Coordinate(ref.latitude + dLat, ref.longitude + dLon)
        val m = diagonalExitSurface()
        val g = SurfaceGraphBuilder.build(m)
        val engine = TaxiRouteEngine(g, m)

        // The aircraft has turned onto exit E2 and is 45% of the way down it — nearer the exit's
        // far end (out on parallel taxiway P) than to any other node.
        val onExit = p(0.00036, 0.00045)
        val exitFarEnd = p(0.0008, 0.0010)
        val route = engine.route(
            TaxiRouteEngine.Request(
                startCoordinate = onExit, startGateName = null, isDeparture = false,
                assignedRunwayIdent = null, arrivalGateName = "G1", aircraft = AircraftSizeClass.MEDIUM,
            ),
        )
        assertNotNull(route)
        assertEquals("G1", route.arrivalGate, "the arrival still routes to the entered gate")
        val start = route.startCoordinate.toCoordinate()
        assertTrue(
            SurfaceGeometry.distanceMeters(start, onExit) < 20,
            "the route begins under the aircraft on the exit, not at a distant node",
        )
        assertTrue(
            SurfaceGeometry.distanceMeters(start, exitFarEnd) > 40,
            "the start is NOT jumped a taxiway over to the exit's far end on P",
        )
    }

    @Test
    fun arrivalRouteStartTracksAircraftAcrossRecalculations() {
        fun p(dLat: Double, dLon: Double) = Coordinate(ref.latitude + dLat, ref.longitude + dLon)
        val m = diagonalExitSurface()
        val g = SurfaceGraphBuilder.build(m)
        val engine = TaxiRouteEngine(g, m)

        // Two recalculations from successive points down exit E2. The old node snap resolved
        // both to the same far-end node (identical route); the edge snap must move the start
        // with the aircraft.
        val early = p(0.00036, 0.00045) // 45% down E2
        val later = p(0.00056, 0.00070) // 70% down E2
        fun startFor(c: Coordinate): Coordinate? = engine.route(
            TaxiRouteEngine.Request(
                startCoordinate = c, startGateName = null, isDeparture = false,
                assignedRunwayIdent = null, arrivalGateName = "G1", aircraft = AircraftSizeClass.MEDIUM,
            ),
        )?.startCoordinate?.toCoordinate()

        val s1 = startFor(early)
        val s2 = startFor(later)
        assertNotNull(s1, "both recalculations produce a route")
        assertNotNull(s2, "both recalculations produce a route")
        assertTrue(SurfaceGeometry.distanceMeters(s1, early) < 20)
        assertTrue(SurfaceGeometry.distanceMeters(s2, later) < 20)
        assertTrue(
            SurfaceGeometry.distanceMeters(s1, s2) > 10,
            "recalculating from a new position moves the route start (it no longer sticks)",
        )
    }

    @Test
    fun midTaxiDepartureRecalcKeepsRunwayCrossingAhead() {
        // Recalculate a departure from a point partway along taxiway A, north of the crossing
        // runway and with no gate name (so it is not anchored at the stand). The start snaps
        // mid-edge, so the still-to-be-taxied first leg — which crosses the crossing runway —
        // becomes the lead-in; the route must still surface that crossing (its hold-short),
        // never silently drop it.
        val engine = mockEngine("36", "A1")
        val northOfCrossing = Coordinate(ref.latitude + 0.0010, ref.longitude + 0.0030)
        val route = engine.route(
            TaxiRouteEngine.Request(
                startCoordinate = northOfCrossing, startGateName = null, isDeparture = true,
                assignedRunwayIdent = "36", arrivalGateName = null, aircraft = AircraftSizeClass.MEDIUM,
            ),
        )
        assertNotNull(route)
        assertEquals("36", route.holdShortRunway)
        assertEquals(
            1, route.crossings.size,
            "a mid-edge start keeps the runway crossing that lies ahead on the lead-in",
        )
        val start = route.startCoordinate.toCoordinate()
        assertTrue(
            SurfaceGeometry.distanceMeters(start, northOfCrossing) < 20,
            "the route starts under the aircraft, not at a node a taxiway away",
        )
    }

    @Test
    fun arrivalFallsThroughToReachableStandWhenEnteredGateStranded() {
        // Reproduces the mock KMSP arrival failure: the real surface loads and the rollout
        // start snaps onto the connected taxi network, but the *entered* stand ("A1") attaches
        // to a disconnected patch of the OSM graph, so A* to it finds no path. Another stand on
        // the same concourse ("A2") sits on the connected network, so the arrival must fall
        // through to it instead of returning nil — which (in the mock demo) reverts the map to
        // the bundled synthetic field. Arrival previously probed only one goal candidate.
        fun p(dLat: Double, dLon: Double) = GeoCoordinate(ref.latitude + dLat, ref.longitude + dLon)
        // Connected network: taxiway A runs west→east; the rollout start snaps to its west end,
        // and stand A2 attaches to its east end.
        val twyA = SurfaceTaxiway(
            osmID = "way/a", tags = mapOf("aeroway" to "taxiway", "ref" to "A"), isTaxilane = false,
            name = "A", geometry = listOf(p(0.0, 0.0), p(0.0, 0.0030)),
            oneway = false, access = null, widthMeters = null,
        )
        // A tiny isolated stub far to the east, wired to nothing — stand A1 attaches only to it.
        val stub = SurfaceTaxiway(
            osmID = "way/stub", tags = mapOf("aeroway" to "taxiway", "ref" to "S"), isTaxilane = false,
            name = "S", geometry = listOf(p(0.0, 0.0100), p(0.0, 0.0102)),
            oneway = false, access = null, widthMeters = null,
        )
        // A runway (unconnected to the taxi net here) so the surface has usable geometry.
        val runway = SurfaceRunway(
            osmID = "way/r", tags = mapOf("aeroway" to "runway", "ref" to "18/36"),
            idents = listOf("18", "36"), centerline = listOf(p(0.0050, 0.0), p(0.0110, 0.0)),
            widthMeters = 45.0, widthInferred = false,
        )
        // A1 sits by the isolated stub (disconnected); A2 sits by taxiway A's east end (reachable).
        val a1 = SurfaceParking(
            osmID = "node/a1", tags = mapOf("aeroway" to "gate", "ref" to "A1"),
            kind = SurfaceParking.Kind.GATE, name = "A1", coordinate = p(0.0002, 0.0100),
        )
        val a2 = SurfaceParking(
            osmID = "node/a2", tags = mapOf("aeroway" to "gate", "ref" to "A2"),
            kind = SurfaceParking.Kind.GATE, name = "A2", coordinate = p(0.0002, 0.0030),
        )
        val m = AirportSurfaceModel(
            icao = "KHUB", reference = GeoCoordinate(ref), runways = listOf(runway),
            runwayEnds = emptyList(), taxiways = listOf(twyA, stub), holdingPositions = emptyList(),
            parkingPositions = listOf(a1, a2), aprons = emptyList(),
            source = testProvenance(ref, rawElementCount = 5),
            confidence = SurfaceConfidence.MEDIUM,
        )
        val g = SurfaceGraphBuilder.build(m)
        val engine = TaxiRouteEngine(g, m)
        val route = engine.route(
            TaxiRouteEngine.Request(
                startCoordinate = p(0.0, 0.0).toCoordinate(), startGateName = null, isDeparture = false,
                assignedRunwayIdent = null, arrivalGateName = "A1", aircraft = AircraftSizeClass.MEDIUM,
            ),
        )
        assertNotNull(
            route,
            "a stranded entered stand must fall through to a reachable one, not fail the route",
        )
        assertEquals("A2", route.arrivalGate, "the arrival lands at the reachable same-concourse stand")
    }

    @Test
    fun standBesideLongNodelessTaxiwayAttachesAndRoutes() {
        // Reproduces the reported KDEN gate-B20 failure: a stand sits ~140 m from a taxiway
        // whose OSM geometry is one long segment with no intermediate node, so its nearest
        // *node* is ~290 m away — beyond the 240 m gate-attach radius. Attaching stands to nodes
        // only left it orphaned (no connector → unroutable), and the arrival silently fell
        // through to a different stand. Projecting onto the taxiway edge (splitting it to insert
        // the junction) must wire the stand in so the arrival routes all the way to it.
        fun p(dLat: Double, dLon: Double) = GeoCoordinate(ref.latitude + dLat, ref.longitude + dLon)
        // "Green": a single ~510 m E–W segment — two vertices only, so there is no node anywhere
        // near its middle (mirrors OSM's node-less apron taxilane past Concourse B).
        val green = SurfaceTaxiway(
            osmID = "way/green", tags = mapOf("aeroway" to "taxiway", "ref" to "Green"),
            isTaxilane = false, name = "Green",
            geometry = listOf(p(0.0, -0.003), p(0.0, 0.003)),
            oneway = false, access = null, widthMeters = null,
        )
        // The stand sits ~140 m south of Green's middle — but ~290 m from either endpoint, so no
        // graph node is within the 240 m attach radius.
        val gate = SurfaceParking(
            osmID = "node/b20", tags = mapOf("aeroway" to "gate", "ref" to "B20"),
            kind = SurfaceParking.Kind.GATE, name = "B20", coordinate = p(-0.00125, 0.0),
        )
        // A runway placed well clear (so the model has usable geometry and no runway-entry node
        // lands near the stand).
        val runway = SurfaceRunway(
            osmID = "way/r", tags = mapOf("aeroway" to "runway", "ref" to "18/36"),
            idents = listOf("18", "36"), centerline = listOf(p(0.0200, 0.0200), p(0.0300, 0.0200)),
            widthMeters = 45.0, widthInferred = false,
        )
        val m = AirportSurfaceModel(
            icao = "KLNG", reference = GeoCoordinate(ref), runways = listOf(runway),
            runwayEnds = emptyList(), taxiways = listOf(green), holdingPositions = emptyList(),
            parkingPositions = listOf(gate), aprons = emptyList(),
            source = testProvenance(ref, rawElementCount = 3),
            confidence = SurfaceConfidence.LOW,
        )
        val g = SurfaceGraphBuilder.build(m)

        // The stand attached (an inferred connector was created) despite no node within 240 m,
        // and it joins Green into one connected component.
        assertTrue(
            g.inferredConnectorCount >= 1,
            "a stand beside a long node-less taxiway must still attach via edge projection",
        )
        assertTrue(g.nodes.any { it.kind == SurfaceNodeKind.GATE && it.name == "B20" })
        assertEquals(1, g.componentCount, "the edge-attached stand is wired into the taxiway network")

        // And an arrival rolling in from Green's west end routes all the way to the stand.
        val engine = TaxiRouteEngine(g, m)
        val route = engine.route(
            TaxiRouteEngine.Request(
                startCoordinate = p(0.0, -0.003).toCoordinate(), startGateName = null,
                isDeparture = false, assignedRunwayIdent = null,
                arrivalGateName = "B20", aircraft = AircraftSizeClass.MEDIUM,
            ),
        )
        assertNotNull(route, "the arrival must reach the edge-attached stand, not fall short of it")
        assertEquals("B20", route.arrivalGate, "the route ends at the entered gate")
    }

    // MARK: - Heading-aware start (no 180° U-turn in place)

    /**
     * A single straight east–west taxiway with a gate at its west end, so an aircraft sitting
     * mid-taxiway has the gate *behind* it. Used to prove the route sets off in the aircraft's
     * heading and turns around later, rather than pivoting 180° where it sits.
     */
    private fun straightTaxiwaySurface(): AirportSurfaceModel {
        fun p(dLat: Double, dLon: Double) = GeoCoordinate(ref.latitude + dLat, ref.longitude + dLon)
        // Taxiway T runs west (W) → east (E) through the reference longitude.
        val twyT = SurfaceTaxiway(
            osmID = "way/t", tags = mapOf("aeroway" to "taxiway", "ref" to "T"), isTaxilane = false,
            name = "T", geometry = listOf(p(0.0, -0.003), p(0.0, 0.003)),
            oneway = false, access = null, widthMeters = null,
        )
        // Gate just south of the west end, so it attaches to the W node.
        val gate = SurfaceParking(
            osmID = "node/g", tags = mapOf("aeroway" to "gate", "ref" to "G"),
            kind = SurfaceParking.Kind.GATE, name = "G", coordinate = p(-0.0002, -0.003),
        )
        // A runway placed well clear so the model has usable geometry and nothing snaps near T.
        val runway = SurfaceRunway(
            osmID = "way/r", tags = mapOf("aeroway" to "runway", "ref" to "18/36"),
            idents = listOf("18", "36"), centerline = listOf(p(0.02, 0.02), p(0.03, 0.02)),
            widthMeters = 45.0, widthInferred = false,
        )
        return AirportSurfaceModel(
            icao = "KHDG", reference = GeoCoordinate(ref), runways = listOf(runway),
            runwayEnds = emptyList(), taxiways = listOf(twyT), holdingPositions = emptyList(),
            parkingPositions = listOf(gate), aprons = emptyList(),
            source = testProvenance(ref, halfSpanDegrees = 0.05, rawElementCount = 3),
            confidence = SurfaceConfidence.MEDIUM,
        )
    }

    @Test
    fun routeStartsInAircraftHeadingInsteadOfPivotingInPlace() {
        val m = straightTaxiwaySurface()
        val g = SurfaceGraphBuilder.build(m)
        val engine = TaxiRouteEngine(g, m)
        // Aircraft sits mid-taxiway (over the reference) heading due EAST; the gate is behind it
        // at the west end.
        val mid = Coordinate(ref.latitude, ref.longitude)

        // With a known east heading the route must set off east — its first step increases
        // longitude — taxiing forward and turning around farther along rather than reversing
        // in place onto the gate directly behind it.
        val withHeading = engine.route(
            TaxiRouteEngine.Request(
                startCoordinate = mid, startGateName = null,
                isDeparture = false, assignedRunwayIdent = null,
                arrivalGateName = "G", aircraft = AircraftSizeClass.MEDIUM,
                aircraftHeadingDegrees = 90.0,
            ),
        )
        assertNotNull(withHeading)
        assertEquals("G", withHeading.arrivalGate, "still routes to the entered gate")
        val geo = withHeading.geometry
        assertTrue(geo.size >= 2)
        assertTrue(
            SurfaceGeometry.distanceMeters(geo[0].toCoordinate(), mid) < 20,
            "the route begins under the aircraft, not at a distant node",
        )
        assertTrue(
            geo[1].longitude > geo[0].longitude,
            "the first step is eastbound — the way the aircraft is pointing, not a 180° pivot",
        )

        // Without a heading the router is free to reverse straight onto the nearer gate behind
        // the aircraft: its first step is westbound. This is the behavior the heading suppresses.
        val noHeading = engine.route(
            TaxiRouteEngine.Request(
                startCoordinate = mid, startGateName = null,
                isDeparture = false, assignedRunwayIdent = null,
                arrivalGateName = "G", aircraft = AircraftSizeClass.MEDIUM,
            ),
        )
        assertNotNull(noHeading)
        val geo2 = noHeading.geometry
        assertTrue(geo2.size >= 2)
        assertTrue(
            geo2[1].longitude < geo2[0].longitude,
            "with no heading the route reverses straight back to the gate (westbound)",
        )
    }

    // MARK: - Turn minimization (prefer fewer larger turns to many small ones)

    /**
     * Two disjoint corridors from a start node S to a runway hold T: a short "staircase" that
     * steps down through four 90° turns, and a slightly longer "dogleg" that reaches the same
     * point with just two turns. The router must prefer the dogleg — trading a little distance
     * for far fewer turns — instead of stepping down through the many-small-turns staircase.
     */
    @Test
    fun prefersFewerTurnsOverSteppingStaircase() {
        fun p(dLat: Double, dLon: Double) = GeoCoordinate(ref.latitude + dLat, ref.longitude + dLon)
        fun seg(id: String, pts: List<GeoCoordinate>) = SurfaceTaxiway(
            osmID = "way/$id", tags = mapOf("aeroway" to "taxiway", "ref" to id.uppercase()),
            isTaxilane = false, name = id.uppercase(), geometry = pts,
            oneway = false, access = null, widthMeters = null,
        )
        // Shared endpoints S (start) and T (hold). Each straight leg is its own way so the bends
        // between legs are graph junctions where turns are actually counted.
        val s = p(0.0, 0.0)
        val t = p(0.0, 0.005)
        // Staircase (4 turns), the shorter path — small up/over/down/over steps near the S–T line.
        val sc1 = seg("s1", listOf(s, p(0.0, 0.001)))
        val sc2 = seg("s2", listOf(p(0.0, 0.001), p(0.0002, 0.001)))
        val sc3 = seg("s3", listOf(p(0.0002, 0.001), p(0.0002, 0.004)))
        val sc4 = seg("s4", listOf(p(0.0002, 0.004), p(0.0, 0.004)))
        val sc5 = seg("s5", listOf(p(0.0, 0.004), t))
        // Dogleg (2 turns), a little longer — one leg north, one long leg east, one leg south to T.
        val dg1 = seg("d1", listOf(s, p(0.001, 0.0)))
        val dg2 = seg("d2", listOf(p(0.001, 0.0), p(0.001, 0.005)))
        val dg3 = seg("d3", listOf(p(0.001, 0.005), t))
        // A runway just east of T so T becomes its "09" hold-short (the routing goal). Its
        // centerline stays clear of every taxiway, so no leg is a crossing.
        val runway = SurfaceRunway(
            osmID = "way/r", tags = mapOf("aeroway" to "runway", "ref" to "09/27"),
            idents = listOf("09", "27"),
            centerline = listOf(p(0.0001, 0.0052), p(0.0001, 0.0110)),
            widthMeters = 45.0, widthInferred = false,
        )
        val ends = OSMSurfaceNormalizer.makeRunwayEnds(listOf(runway))
        val m = AirportSurfaceModel(
            icao = "KTRN", reference = GeoCoordinate(ref), runways = listOf(runway),
            runwayEnds = ends, taxiways = listOf(sc1, sc2, sc3, sc4, sc5, dg1, dg2, dg3),
            holdingPositions = emptyList(), parkingPositions = emptyList(), aprons = emptyList(),
            source = testProvenance(ref, halfSpanDegrees = 0.05, rawElementCount = 9),
            confidence = SurfaceConfidence.MEDIUM,
        )
        val g = SurfaceGraphBuilder.build(m)
        val engine = TaxiRouteEngine(g, m)
        val route = engine.route(
            TaxiRouteEngine.Request(
                startCoordinate = s.toCoordinate(), startGateName = null,
                isDeparture = true, assignedRunwayIdent = "09",
                arrivalGateName = null, aircraft = AircraftSizeClass.MEDIUM,
            ),
        )
        assertNotNull(route, "a departure to runway 09 must route")
        assertEquals("09", route.holdShortRunway)
        // The dogleg swings north to latitude +0.001; the staircase never exceeds +0.0002. A
        // route whose geometry reaches that far north took the two-turn dogleg — even though the
        // four-turn staircase is the shorter path.
        val maxLat = route.geometry.maxOfOrNull { it.latitude } ?: ref.latitude
        assertTrue(
            maxLat > ref.latitude + 0.0005,
            "router takes the two-turn dogleg, not the shorter four-turn staircase",
        )
    }
}
