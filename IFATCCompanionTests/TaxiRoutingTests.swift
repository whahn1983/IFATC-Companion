import XCTest
import CoreLocation
@testable import IFATCCompanion

/// Routing: correct runway end, full-length preference, runway-crossing penalty, no
/// illegal disconnected jumps, no route through parking stands, low-confidence downgrade,
/// no-path fallback, and recalculation.
final class TaxiRoutingTests: XCTestCase {

    private let ref = CLLocationCoordinate2D(latitude: 40, longitude: -75)

    private func mockEngine(runway: String = "36", gate: String = "A1")
        -> (AirportSurfaceModel, SurfaceGraph, TaxiRouteEngine) {
        let m = MockAirportSurface.model(icao: "KTEST", reference: ref, primaryRunwayIdent: runway, gate: gate)
        let g = SurfaceGraphBuilder.build(from: m)
        return (m, g, TaxiRouteEngine(graph: g, model: m))
    }

    private func departureRoute(runway: String = "36", gate: String = "A1") -> SurfaceTaxiRoute? {
        let (_, _, engine) = mockEngine(runway: runway, gate: gate)
        return engine.route(.init(startCoordinate: MockAirportSurface.gateCoordinate(reference: ref),
                                  startGateName: gate, isDeparture: true,
                                  assignedRunwayIdent: runway, arrivalGateName: nil, aircraft: .medium))
    }

    func testRoutesToCorrectRunwayEnd() {
        let route = departureRoute(runway: "36")
        XCTAssertNotNil(route)
        XCTAssertEqual(route?.holdShortRunway, "36")
        XCTAssertEqual(route?.destinationLabel, "runway 36")
    }

    func testTaxiwaySequenceIsNamed() {
        let route = departureRoute()
        XCTAssertTrue(route?.taxiwaySequence.contains("A") ?? false)
        XCTAssertTrue(route?.taxiwaySequence.contains("C") ?? false)
    }

    func testHighConfidenceOnWellFormedSurface() {
        let route = departureRoute()
        XCTAssertEqual(route?.confidence, .high)
    }

    func testExactlyOneCrossingAndNotOfTheDepartureRunway() {
        let route = departureRoute(runway: "36")
        XCTAssertEqual(route?.crossings.count, 1, "the route crosses exactly the one runway in the way")
        // It holds short of its own departure runway — it never crosses runway 36.
        XCTAssertFalse(route?.crossings.contains { $0.runwayIdent == "36" } ?? true)
        XCTAssertEqual(route?.crossings.first?.runwayIdent, MockAirportSurface.crossingIdent(forPrimary: "36"))
    }

    func testNoIllegalDisconnectedJumps() {
        let route = departureRoute()
        XCTAssertNotNil(route)
        // A contiguous path: N nodes are joined by exactly N-1 edges.
        XCTAssertEqual(route?.edgeIDs.count, (route?.nodeIDs.count ?? 0) - 1)
    }

    func testDoesNotRouteThroughParkingStands() {
        let (_, g, _) = mockEngine()
        let route = departureRoute()!
        // Only the start node may be a gate/parking stand; none appear mid-route.
        for nodeID in route.nodeIDs.dropFirst() {
            let node = g.node(nodeID)
            XCTAssertFalse(node?.kind == .gate || node?.kind == .parking,
                           "route must not pass through a parking stand")
        }
    }

    func testLowConfidenceDowngradeWhenUnnamedAndNoHolds() {
        var m = MockAirportSurface.model(icao: "KLOW", reference: ref, primaryRunwayIdent: "36", gate: "A1")
        m.taxiways = m.taxiways.map { var t = $0; t.name = ""; return t }   // strip names
        m.holdingPositions = []                                             // no mapped holds
        m.confidence = OSMSurfaceNormalizer.preliminaryConfidence(m)
        let g = SurfaceGraphBuilder.build(from: m)
        let engine = TaxiRouteEngine(graph: g, model: m)
        let route = engine.route(.init(startCoordinate: MockAirportSurface.gateCoordinate(reference: ref),
                                       startGateName: "A1", isDeparture: true,
                                       assignedRunwayIdent: "36", arrivalGateName: nil, aircraft: .medium))
        XCTAssertNotNil(route)
        XCTAssertNotEqual(route?.confidence, .high, "unnamed geometry with no holds must not grade High")
    }

    func testNoPathFallbackReturnsNil() {
        func p(_ dLat: Double, _ dLon: Double) -> GeoCoordinate {
            GeoCoordinate(latitude: ref.latitude + dLat, longitude: ref.longitude + dLon)
        }
        // A gate + a short taxiway near it, and a runway ~1 km away with nothing reaching it.
        let twy = SurfaceTaxiway(osmID: "way/t", tags: ["aeroway": "taxiway", "ref": "A"], isTaxilane: false,
                                 name: "A", geometry: [p(0, 0), p(0.0005, 0)], oneway: false, access: nil, widthMeters: nil)
        let runway = SurfaceRunway(osmID: "way/r", tags: ["aeroway": "runway", "ref": "18/36"], idents: ["18", "36"],
                                   centerline: [p(0.01, 0.01), p(0.02, 0.01)], widthMeters: 45, widthInferred: false)
        let gate = SurfaceParking(osmID: "node/g", tags: ["aeroway": "gate", "ref": "A1"], kind: .gate,
                                  name: "A1", coordinate: p(0, 0.00005))
        let bbox = OSMBoundingBox(center: ref, halfSpanDegrees: 0.04)
        let m = AirportSurfaceModel(icao: "KNOP", reference: GeoCoordinate(ref), runways: [runway],
                                    runwayEnds: [], taxiways: [twy], holdingPositions: [], parkingPositions: [gate],
                                    aprons: [], source: SurfaceProvenance(endpoint: "t", fetchDate: Date(), boundingBox: bbox, rawElementCount: 3),
                                    confidence: .low)
        let g = SurfaceGraphBuilder.build(from: m)
        let engine = TaxiRouteEngine(graph: g, model: m)
        let route = engine.route(.init(startCoordinate: p(0, 0).clLocation, startGateName: "A1", isDeparture: true,
                                       assignedRunwayIdent: "36", arrivalGateName: nil, aircraft: .medium))
        XCTAssertNil(route, "no credible connected route → nil (Unavailable fallback)")
    }

    func testDepartureRouteAnchorsAtStandWhileParked() {
        // Parked at the stand (start coordinate == the gate): the route begins at the gate.
        let route = departureRoute(runway: "36", gate: "A1")
        XCTAssertNotNil(route)
        let start = route!.startCoordinate.clLocation
        XCTAssertLessThan(SurfaceGeometry.distanceMeters(start, MockAirportSurface.gateCoordinate(reference: ref)), 30,
                          "while parked at the stand the route still starts at the gate")
    }

    func testDepartureRouteStartsFromAircraftAfterPushback() {
        // After pushback the aircraft has moved off its stand onto taxiway A. The route
        // must no longer be anchored at the gate node (whose lead-in leg the aircraft has
        // already left, which is what read as "off route") — it starts on the taxiway,
        // and the post-pushback position tracks as on-route.
        let (_, _, engine) = mockEngine(runway: "36", gate: "A1")
        let gate = MockAirportSurface.gateCoordinate(reference: ref)
        let pushback = CLLocationCoordinate2D(latitude: ref.latitude + 0.0010, longitude: ref.longitude + 0.0030)
        let route = engine.route(.init(startCoordinate: pushback, startGateName: "A1", isDeparture: true,
                                       assignedRunwayIdent: "36", arrivalGateName: nil, aircraft: .medium))
        XCTAssertNotNil(route)
        XCTAssertEqual(route?.holdShortRunway, "36", "still routes to the assigned runway")
        let start = route!.startCoordinate.clLocation
        XCTAssertGreaterThan(SurfaceGeometry.distanceMeters(start, gate), 40,
                             "the route no longer starts at the gate node once pushed back")
        // The post-pushback position tracks as on-route.
        let prog = RouteTracker().progress(aircraft: pushback, route: route!)
        XCTAssertTrue(prog.onRoute, "the post-pushback position is on the route")
    }

    func testRecalculationFromMidRouteStillRoutes() {
        let (_, _, engine) = mockEngine()
        // Start from a point partway along the route (near the crossing) rather than the gate.
        let mid = CLLocationCoordinate2D(latitude: ref.latitude - 0.0010, longitude: ref.longitude + 0.0030)
        let route = engine.route(.init(startCoordinate: mid, startGateName: nil, isDeparture: true,
                                       assignedRunwayIdent: "36", arrivalGateName: nil, aircraft: .medium))
        XCTAssertNotNil(route, "recalculation from the current position still reaches the runway")
        XCTAssertEqual(route?.holdShortRunway, "36")
    }

    func testArrivalRoutesToGate() {
        let (_, _, engine) = mockEngine()
        let route = engine.route(.init(startCoordinate: MockAirportSurface.runwayExitCoordinate(reference: ref),
                                       startGateName: nil, isDeparture: false,
                                       assignedRunwayIdent: nil, arrivalGateName: "A1", aircraft: .medium))
        XCTAssertNotNil(route)
        XCTAssertEqual(route?.arrivalGate, "A1")
        XCTAssertEqual(route?.destinationLabel, "gate A1")
    }

    func testDepartureMatchesZeroPaddedRunwayIdent() {
        // The app assigns a non-padded ident ("9") while OSM tags the runway end zero-padded
        // ("09") — they are the same physical end. The departure must still route; otherwise
        // KATL's east-flow runways (8L/9L, tagged 08L/09L in OSM) never resolve a goal and the
        // map is stuck on "route pending".
        let m = MockAirportSurface.model(icao: "KPAD", reference: ref, primaryRunwayIdent: "09", gate: "A1")
        let g = SurfaceGraphBuilder.build(from: m)
        let engine = TaxiRouteEngine(graph: g, model: m)
        let route = engine.route(.init(startCoordinate: MockAirportSurface.gateCoordinate(reference: ref),
                                       startGateName: "A1", isDeparture: true,
                                       assignedRunwayIdent: "9", arrivalGateName: nil, aircraft: .medium))
        XCTAssertNotNil(route, "an assigned \"9\" must match the OSM-tagged \"09\" runway end")
        XCTAssertEqual(route?.holdShortRunway, "9", "the clearance still names the assigned runway")
    }

    func testDepartureFallsThroughToReachableGoalWhenEntryStranded() {
        // Reproduces the KATL 26L failure: the surface loads and the aircraft snaps onto the
        // graph, but the runway-entry node for the assigned end is stranded in a disconnected
        // patch of the OSM graph, so A* to it finds no path. A holding position for the same
        // runway sits on the connected taxi network, so the route must fall through to it
        // instead of returning nil (which showed as "route pending" + a generic clearance).
        func p(_ dLat: Double, _ dLon: Double) -> GeoCoordinate {
            GeoCoordinate(latitude: ref.latitude + dLat, longitude: ref.longitude + dLon)
        }
        // Connected network: gate → taxiway A → taxiway B (which ends at the mapped hold).
        let twyA = SurfaceTaxiway(osmID: "way/a", tags: ["aeroway": "taxiway", "ref": "A"], isTaxilane: false,
                                  name: "A", geometry: [p(0.0002, 0), p(0.0030, 0)], oneway: false, access: nil, widthMeters: nil)
        let twyB = SurfaceTaxiway(osmID: "way/b", tags: ["aeroway": "taxiway", "ref": "B"], isTaxilane: false,
                                  name: "B", geometry: [p(0.0030, 0), p(0.0030, 0.0010)], oneway: false, access: nil, widthMeters: nil)
        // A tiny isolated stub next to the 36 threshold — nearest the threshold, so it becomes
        // the runway-entry node, but it is wired to nothing (44 m from taxiway B, far past the
        // ~1 m node-merge grid).
        let stub = SurfaceTaxiway(osmID: "way/stub", tags: ["aeroway": "taxiway", "ref": "S"], isTaxilane: false,
                                  name: "S", geometry: [p(0.00345, 0.0010), p(0.00355, 0.0010)], oneway: false, access: nil, widthMeters: nil)
        let runway = SurfaceRunway(osmID: "way/r", tags: ["aeroway": "runway", "ref": "18/36"], idents: ["18", "36"],
                                   centerline: [p(0.0034, 0.0010), p(0.0090, 0.0010)], widthMeters: 45, widthInferred: false)
        let end36 = SurfaceRunwayEnd(ident: "36", threshold: p(0.0034, 0.0010), oppositeThreshold: p(0.0090, 0.0010),
                                     headingDegrees: 360, runwayOSMID: "way/r", widthMeters: 45)
        let end18 = SurfaceRunwayEnd(ident: "18", threshold: p(0.0090, 0.0010), oppositeThreshold: p(0.0034, 0.0010),
                                     headingDegrees: 180, runwayOSMID: "way/r", widthMeters: 45)
        // Mapped hold for 36, coincident with taxiway B's end → reachable from the gate.
        let hold = SurfaceHoldingPosition(osmID: "node/h", tags: ["aeroway": "holding_position", "ref": "36"],
                                          coordinate: p(0.0030, 0.0010), runwayRef: "36", inferred: false)
        let gate = SurfaceParking(osmID: "node/g", tags: ["aeroway": "gate", "ref": "A1"], kind: .gate,
                                  name: "A1", coordinate: p(0, 0))
        let bbox = OSMBoundingBox(center: ref, halfSpanDegrees: 0.04)
        let m = AirportSurfaceModel(icao: "KSTR", reference: GeoCoordinate(ref), runways: [runway],
                                    runwayEnds: [end36, end18], taxiways: [twyA, twyB, stub], holdingPositions: [hold],
                                    parkingPositions: [gate], aprons: [],
                                    source: SurfaceProvenance(endpoint: "t", fetchDate: Date(), boundingBox: bbox, rawElementCount: 6),
                                    confidence: .medium)
        let g = SurfaceGraphBuilder.build(from: m)
        let engine = TaxiRouteEngine(graph: g, model: m)
        let route = engine.route(.init(startCoordinate: p(0, 0).clLocation, startGateName: "A1", isDeparture: true,
                                       assignedRunwayIdent: "36", arrivalGateName: nil, aircraft: .medium))
        XCTAssertNotNil(route, "a stranded runway-entry must fall through to the reachable hold, not fail the route")
        XCTAssertEqual(route?.holdShortRunway, "36")
    }
}
