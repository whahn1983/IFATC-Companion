import XCTest
import CoreLocation
@testable import IFATCCompanion

/// Graph generation: connected taxiway graph, intersections, runway intersections,
/// mapped + inferred holding positions, disconnected geometry, and source-id preservation.
final class SurfaceGraphTests: XCTestCase {

    private let ref = CLLocationCoordinate2D(latitude: 40, longitude: -75)

    private func mockGraph() -> (AirportSurfaceModel, SurfaceGraph) {
        let m = MockAirportSurface.model(icao: "KTEST", reference: ref, primaryRunwayIdent: "36", gate: "A1")
        return (m, SurfaceGraphBuilder.build(from: m))
    }

    func testGraphHasNodesAndEdges() {
        let (_, g) = mockGraph()
        XCTAssertGreaterThan(g.nodes.count, 3)
        XCTAssertGreaterThan(g.edges.count, 1)
    }

    func testConnectedTaxiwayGraph() {
        let (_, g) = mockGraph()
        // Gate, taxiway A, taxiway C, primary hold all connect → one component.
        XCTAssertEqual(g.componentCount, 1, "the mock surface is fully connected")
    }

    func testTaxiwayIntersectionNode() {
        let (_, g) = mockGraph()
        // Taxiway A and C share a vertex → an intersection node exists there.
        XCTAssertTrue(g.nodes.contains { $0.kind == .intersection })
    }

    func testRunwayIntersectionDetectedAsCrossing() {
        let (_, g) = mockGraph()
        XCTAssertFalse(g.runwayCrossingEdges.isEmpty, "taxiway A crosses the crossing runway")
        XCTAssertTrue(g.runwayCrossingEdges.allSatisfy { $0.crossingPoint != nil })
        XCTAssertTrue(g.runwayCrossingEdges.allSatisfy { $0.runwayOccupancy })
    }

    func testMappedHoldingPositionNode() {
        let (_, g) = mockGraph()
        XCTAssertTrue(g.nodes.contains { $0.kind == .holdingPosition && $0.inferred == false && $0.runwayRef == "36" })
    }

    func testInferredHoldingPositionWhenNoneMapped() {
        // A runway with a taxiway reaching its threshold, but NO mapped hold.
        func g(_ dLat: Double, _ dLon: Double) -> GeoCoordinate {
            GeoCoordinate(latitude: ref.latitude + dLat, longitude: ref.longitude + dLon)
        }
        let runway = SurfaceRunway(osmID: "way/r", tags: ["aeroway": "runway", "ref": "18/36"],
                                   idents: ["18", "36"],
                                   centerline: [g(-0.0030, 0.0000), g(0.0030, 0.0000)],
                                   widthMeters: 45, widthInferred: false)
        let twy = SurfaceTaxiway(osmID: "way/t", tags: ["aeroway": "taxiway", "ref": "A"],
                                 isTaxilane: false, name: "A",
                                 geometry: [g(-0.0028, -0.0006), g(-0.0028, 0.0000)],
                                 oneway: false, access: nil, widthMeters: nil)
        let bbox = OSMBoundingBox(center: ref, halfSpanDegrees: 0.04)
        let model = AirportSurfaceModel(icao: "KNOH", reference: GeoCoordinate(ref),
                                        runways: [runway],
                                        runwayEnds: makeEnds(runway),
                                        taxiways: [twy], holdingPositions: [], parkingPositions: [],
                                        aprons: [], source: SurfaceProvenance(endpoint: "t", fetchDate: Date(),
                                                                              boundingBox: bbox, rawElementCount: 2),
                                        confidence: .low)
        let graph = SurfaceGraphBuilder.build(from: model)
        XCTAssertTrue(graph.nodes.contains { $0.kind == .holdingPosition && $0.inferred },
                      "a runway entry with no mapped hold should yield an inferred hold")
    }

    func testDisconnectedGeometryReportsMultipleComponents() {
        func g(_ dLat: Double, _ dLon: Double) -> GeoCoordinate {
            GeoCoordinate(latitude: ref.latitude + dLat, longitude: ref.longitude + dLon)
        }
        // Two taxiways that do not share a vertex and are far apart.
        let a = SurfaceTaxiway(osmID: "way/a", tags: ["aeroway": "taxiway", "ref": "A"], isTaxilane: false,
                               name: "A", geometry: [g(0, 0), g(0.001, 0)], oneway: false, access: nil, widthMeters: nil)
        let b = SurfaceTaxiway(osmID: "way/b", tags: ["aeroway": "taxiway", "ref": "B"], isTaxilane: false,
                               name: "B", geometry: [g(0.02, 0.02), g(0.021, 0.02)], oneway: false, access: nil, widthMeters: nil)
        let bbox = OSMBoundingBox(center: ref, halfSpanDegrees: 0.04)
        let runway = SurfaceRunway(osmID: "way/r", tags: ["aeroway": "runway", "ref": "09/27"], idents: ["09", "27"],
                                   centerline: [g(0.05, -0.05), g(0.05, 0.05)], widthMeters: 45, widthInferred: false)
        let model = AirportSurfaceModel(icao: "KDIS", reference: GeoCoordinate(ref), runways: [runway],
                                        runwayEnds: makeEnds(runway), taxiways: [a, b], holdingPositions: [],
                                        parkingPositions: [], aprons: [],
                                        source: SurfaceProvenance(endpoint: "t", fetchDate: Date(), boundingBox: bbox, rawElementCount: 3),
                                        confidence: .low)
        let graph = SurfaceGraphBuilder.build(from: model)
        XCTAssertGreaterThanOrEqual(graph.componentCount, 2, "disconnected taxiways → multiple components")
    }

    func testInferredConnectorForGate() {
        let (_, g) = mockGraph()
        XCTAssertGreaterThanOrEqual(g.inferredConnectorCount, 1, "the gate connects via an inferred connector")
        XCTAssertTrue(g.nodes.contains { $0.kind == .gate && $0.name == "A1" })
    }

    func testSourceIdentifiersPreservedOnEdges() {
        let (_, g) = mockGraph()
        let allOSMIDs = g.edges.flatMap { $0.osmIDs }
        XCTAssertTrue(allOSMIDs.contains { $0.contains("mock-twy-A") },
                      "graph edges retain their originating OSM feature ids")
    }

    // Mirror the normalizer's runway-end derivation for hand-built test models.
    private func makeEnds(_ r: SurfaceRunway) -> [SurfaceRunwayEnd] {
        guard let first = r.centerline.first?.clLocation, let last = r.centerline.last?.clLocation else { return [] }
        return r.idents.map { ident in
            let heading = OSMSurfaceNormalizer.runwayHeading(ident) ?? Geo.bearing(from: first, to: last)
            let bFL = Geo.bearing(from: first, to: last)
            let bLF = Geo.bearing(from: last, to: first)
            let near = Geo.headingDifference(bFL, heading) <= Geo.headingDifference(bLF, heading)
            return SurfaceRunwayEnd(ident: ident,
                                    threshold: GeoCoordinate(near ? first : last),
                                    oppositeThreshold: GeoCoordinate(near ? last : first),
                                    headingDegrees: heading, runwayOSMID: r.osmID, widthMeters: r.widthMeters)
        }
    }
}
