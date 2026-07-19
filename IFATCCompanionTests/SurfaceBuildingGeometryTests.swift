import XCTest
import CoreLocation
@testable import IFATCCompanion

/// Building-geometry awareness in taxi routing: gate lead-ins avoid being drawn through a
/// concourse to a stand on the far side, and a cache written before building footprints
/// existed is recognized as an outdated schema (so it is re-fetched).
final class SurfaceBuildingGeometryTests: XCTestCase {

    private let ref = CLLocationCoordinate2D(latitude: 40, longitude: -75)

    private func g(_ dLat: Double, _ dLon: Double) -> GeoCoordinate {
        GeoCoordinate(latitude: ref.latitude + dLat, longitude: ref.longitude + dLon)
    }

    /// A thin concourse laid out E–W with a stand on its south face. The geometrically
    /// nearest taxi node is on the *far* (north) side of the building; a slightly farther
    /// node is clear on the south side.
    private func thinConcourseModel(withBuilding: Bool) -> AirportSurfaceModel {
        // Taxiway on the north side of the concourse — nearest to the gate, but its lead-in
        // would cut through the building.
        let twyNorth = SurfaceTaxiway(osmID: "way/twy-north", tags: ["aeroway": "taxiway", "ref": "N"],
                                      isTaxilane: false, name: "N",
                                      geometry: [g(0.0003, 0.0000), g(0.0003, 0.0010)],
                                      oneway: false, access: nil, widthMeters: nil)
        // Taxiway on the south side — a little farther, but clear of the building.
        let twySouth = SurfaceTaxiway(osmID: "way/twy-south", tags: ["aeroway": "taxiway", "ref": "S"],
                                      isTaxilane: false, name: "S",
                                      geometry: [g(-0.0010, 0.0000), g(-0.0010, 0.0010)],
                                      oneway: false, access: nil, widthMeters: nil)
        // Runway placed well clear so it doesn't attach a runway-entry node near the stand.
        let runway = SurfaceRunway(osmID: "way/rwy", tags: ["aeroway": "runway", "ref": "09/27"],
                                   idents: ["09", "27"],
                                   centerline: [g(0.0100, -0.0050), g(0.0100, 0.0050)],
                                   widthMeters: 45, widthInferred: false)
        let gate = SurfaceParking(osmID: "node/gate", tags: ["aeroway": "gate", "ref": "G1"],
                                  kind: .gate, name: "G1", coordinate: g(-0.00025, 0.0000))
        // Thin E–W building between the stand and the north taxiway.
        let building = SurfaceBuilding(osmID: "way/concourse",
                                       tags: ["aeroway": "terminal"],
                                       polygon: [g(0.0002, -0.0004), g(0.0002, 0.0004),
                                                 g(-0.0002, 0.0004), g(-0.0002, -0.0004)])
        let bbox = OSMBoundingBox(center: ref, halfSpanDegrees: 0.04)
        return AirportSurfaceModel(icao: "KBLD", reference: GeoCoordinate(ref),
                                   runways: [runway], runwayEnds: makeEnds(runway),
                                   taxiways: [twyNorth, twySouth], holdingPositions: [],
                                   parkingPositions: [gate], aprons: [],
                                   buildings: withBuilding ? [building] : [],
                                   source: SurfaceProvenance(endpoint: "t", fetchDate: Date(),
                                                             boundingBox: bbox, rawElementCount: 4),
                                   confidence: .low)
    }

    private func gateConnector(in graph: SurfaceGraph) -> (connector: SurfaceEdge, otherNode: SurfaceNode)? {
        guard let gate = graph.nodes.first(where: { $0.kind == .gate }) else { return nil }
        guard let connector = graph.edges.first(where: {
            $0.inferred && ($0.from == gate.id || $0.to == gate.id) }) else { return nil }
        let otherID = connector.from == gate.id ? connector.to : connector.from
        return (connector, graph.nodes[otherID])
    }

    func testGateConnectorAvoidsBuildingCrossing() {
        let graph = SurfaceGraphBuilder.build(from: thinConcourseModel(withBuilding: true))
        guard let gate = graph.nodes.first(where: { $0.kind == .gate }),
              let (connector, other) = gateConnector(in: graph) else {
            return XCTFail("expected a gate node with an inferred connector")
        }
        // Chose the clear stand on the south side, not the nearer node across the concourse.
        XCTAssertLessThan(other.coordinate.latitude, gate.coordinate.latitude,
                          "connector should attach to the south (clear) taxiway, not across the building")
        XCTAssertFalse(connector.crossesBuilding, "chosen connector must not cross the concourse")
        XCTAssertFalse(graph.edges.contains { $0.inferred && $0.crossesBuilding },
                       "no inferred connector should cut through a building when a clear node exists")
    }

    func testConnectorPicksNearestWhenNoBuildings() {
        // Same geometry, buildings removed: the nearest (north) node wins, proving the
        // building footprint — not some other bias — changed the attachment.
        let graph = SurfaceGraphBuilder.build(from: thinConcourseModel(withBuilding: false))
        guard let gate = graph.nodes.first(where: { $0.kind == .gate }),
              let (_, other) = gateConnector(in: graph) else {
            return XCTFail("expected a gate node with an inferred connector")
        }
        XCTAssertGreaterThan(other.coordinate.latitude, gate.coordinate.latitude,
                             "without buildings the geometrically nearest (north) node is chosen")
    }

    func testRouteThroughBuildingConnectorLowersConfidence() {
        // A stand whose only reachable taxi node is across a building: the connector is
        // still made (routing shouldn't fail) but flagged as crossing a building.
        let twyNorth = SurfaceTaxiway(osmID: "way/twy-north", tags: ["aeroway": "taxiway", "ref": "N"],
                                      isTaxilane: false, name: "N",
                                      geometry: [g(0.0003, 0.0000), g(0.0003, 0.0010)],
                                      oneway: false, access: nil, widthMeters: nil)
        let gate = SurfaceParking(osmID: "node/gate", tags: ["aeroway": "gate", "ref": "G1"],
                                  kind: .gate, name: "G1", coordinate: g(-0.00025, 0.0000))
        let building = SurfaceBuilding(osmID: "way/concourse", tags: ["aeroway": "terminal"],
                                       polygon: [g(0.0002, -0.0004), g(0.0002, 0.0004),
                                                 g(-0.0002, 0.0004), g(-0.0002, -0.0004)])
        let runway = SurfaceRunway(osmID: "way/rwy", tags: ["aeroway": "runway", "ref": "09/27"],
                                   idents: ["09", "27"],
                                   centerline: [g(0.0100, -0.0050), g(0.0100, 0.0050)],
                                   widthMeters: 45, widthInferred: false)
        let bbox = OSMBoundingBox(center: ref, halfSpanDegrees: 0.04)
        let model = AirportSurfaceModel(icao: "KBLD", reference: GeoCoordinate(ref),
                                        runways: [runway], runwayEnds: makeEnds(runway),
                                        taxiways: [twyNorth], holdingPositions: [],
                                        parkingPositions: [gate], aprons: [], buildings: [building],
                                        source: SurfaceProvenance(endpoint: "t", fetchDate: Date(),
                                                                  boundingBox: bbox, rawElementCount: 3),
                                        confidence: .low)
        let graph = SurfaceGraphBuilder.build(from: model)
        guard let (connector, _) = gateConnector(in: graph) else {
            return XCTFail("expected an inferred connector even when the only node is across the building")
        }
        XCTAssertTrue(connector.crossesBuilding,
                      "the only reachable connector crosses the building and should be flagged")
    }

    // MARK: - Cache schema versioning

    func testFreshModelStampsCurrentSchemaVersion() {
        let m = MockAirportSurface.model(icao: "KTST", reference: ref, primaryRunwayIdent: "36", gate: "A1")
        XCTAssertEqual(m.source.schemaVersion, OSMSurface.surfaceSchemaVersion)
        XCTAssertFalse(m.source.isOutdatedSchema)
    }

    func testLegacyCacheDecodesAsOutdatedSchema() throws {
        // Encode a current model, then strip the fields a pre-v2 cache would not have.
        let m = MockAirportSurface.model(icao: "KTST", reference: ref, primaryRunwayIdent: "36", gate: "A1")
        let data = try JSONEncoder().encode(m)
        var obj = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        obj.removeValue(forKey: "buildings")
        var source = try XCTUnwrap(obj["source"] as? [String: Any])
        source.removeValue(forKey: "schemaVersion")
        obj["source"] = source

        let legacyData = try JSONSerialization.data(withJSONObject: obj)
        let decoded = try JSONDecoder().decode(AirportSurfaceModel.self, from: legacyData)

        XCTAssertTrue(decoded.buildings.isEmpty, "missing buildings decode to empty, not a failure")
        XCTAssertEqual(decoded.source.schemaVersion, 1, "a missing schemaVersion decodes to legacy v1")
        XCTAssertTrue(decoded.source.isOutdatedSchema, "a v1 cache is flagged for re-fetch")
    }

    func testCurrentCacheRoundTripsWithoutRefetch() throws {
        let m = MockAirportSurface.model(icao: "KTST", reference: ref, primaryRunwayIdent: "36", gate: "A1")
        let data = try JSONEncoder().encode(m)
        let decoded = try JSONDecoder().decode(AirportSurfaceModel.self, from: data)
        XCTAssertEqual(decoded.source.schemaVersion, OSMSurface.surfaceSchemaVersion)
        XCTAssertFalse(decoded.source.isOutdatedSchema)
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
