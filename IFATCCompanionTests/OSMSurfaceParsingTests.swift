import XCTest
import CoreLocation
@testable import IFATCCompanion

/// Data parsing: runways, taxiways, taxilanes, holding positions, gates, parking,
/// apron geometry, and taxiway names/references are extracted from an Overpass extract,
/// and OSM identifiers/tags are preserved.
final class OSMSurfaceParsingTests: XCTestCase {

    private let json = """
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
    """

    private func normalized() -> AirportSurfaceModel {
        let data = json.data(using: .utf8)!
        let response = try! JSONDecoder().decode(OverpassResponse.self, from: data)
        let ref = CLLocationCoordinate2D(latitude: 40, longitude: -75)
        let bbox = OSMBoundingBox(center: ref, halfSpanDegrees: 0.04)
        return OSMSurfaceNormalizer.normalize(response, icao: "KTST", reference: ref,
                                              endpoint: "test", boundingBox: bbox, fetchDate: Date())
    }

    func testOverpassJSONDecodes() {
        let data = json.data(using: .utf8)!
        let response = try! JSONDecoder().decode(OverpassResponse.self, from: data)
        XCTAssertEqual(response.elements.count, 9)
        XCTAssertEqual(response.elements.first?.type, .way)
    }

    func testRunwayParsing() {
        let m = normalized()
        XCTAssertEqual(m.runways.count, 1)
        let rwy = m.runways[0]
        XCTAssertEqual(rwy.idents, ["09", "27"])
        XCTAssertEqual(rwy.widthMeters, 45, accuracy: 0.5)
        XCTAssertFalse(rwy.widthInferred)
        XCTAssertEqual(rwy.osmID, "way/1")
        XCTAssertEqual(rwy.tags["aeroway"], "runway")
        // Two directional ends derived.
        XCTAssertEqual(m.runwayEnds.count, 2)
        XCTAssertNotNil(m.runwayEnd(ident: "09"))
        XCTAssertNotNil(m.runwayEnd(ident: "27"))
    }

    func testTaxiwayAndTaxilaneParsing() {
        let m = normalized()
        // A, Bravo, C are taxiways; id 4 is a taxilane.
        XCTAssertEqual(m.taxiwaysOnly.count, 3)
        XCTAssertEqual(m.taxilanes.count, 1)
        XCTAssertTrue(m.taxiwaysOnly.contains { $0.name == "A" })
        XCTAssertTrue(m.taxiwaysOnly.contains { $0.name == "Bravo" && $0.oneway })
        // access=no marks a closed taxiway.
        XCTAssertTrue(m.taxiwaysOnly.contains { $0.name == "C" && $0.isClosed })
    }

    func testTaxiwayNamesAndReferences() {
        let m = normalized()
        // ref preferred over name; name used when ref absent.
        XCTAssertTrue(m.taxiwaysOnly.contains { $0.name == "A" && $0.hasName })
        XCTAssertTrue(m.taxiwaysOnly.contains { $0.name == "Bravo" && $0.hasName })
        XCTAssertTrue(m.taxilanes.allSatisfy { !$0.hasName })   // taxilane had no ref/name
    }

    func testHoldingPositionParsing() {
        let m = normalized()
        XCTAssertEqual(m.holdingPositions.count, 1)
        XCTAssertEqual(m.holdingPositions[0].runwayRef, "09")
        XCTAssertFalse(m.holdingPositions[0].inferred)
        XCTAssertEqual(m.holdingPositions[0].osmID, "node/5")
    }

    func testGatesAndParkingParsing() {
        let m = normalized()
        XCTAssertEqual(m.gates.count, 1)
        XCTAssertEqual(m.gates.first?.name, "B44")
        XCTAssertEqual(m.parkingPositions.count, 2)   // gate + parking_position
        XCTAssertTrue(m.parkingPositions.contains { $0.kind == .parkingPosition && $0.name == "P1" })
        XCTAssertNotNil(m.parking(named: "B44"))
    }

    func testApronGeometryParsing() {
        let m = normalized()
        XCTAssertEqual(m.aprons.count, 1)
        XCTAssertGreaterThanOrEqual(m.aprons[0].polygon.count, 3)
        XCTAssertEqual(m.aprons[0].osmID, "way/8")
    }

    func testInferredWidthFlaggedWhenUntagged() {
        // Taxiway A has no width tag; runway has one.
        let m = normalized()
        XCTAssertNil(m.taxiwaysOnly.first { $0.name == "A" }?.widthMeters)
        XCTAssertFalse(m.runways[0].widthInferred)
    }

    // MARK: - Buildings / terminals

    /// `building=*` ways and `aeroway=terminal` become building footprints; a movement
    /// surface with a stray `building` tag is not misclassified; `building=no` is ignored.
    private let buildingJSON = """
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
    """

    func testBuildingAndTerminalParsing() {
        let data = buildingJSON.data(using: .utf8)!
        let response = try! JSONDecoder().decode(OverpassResponse.self, from: data)
        let ref = CLLocationCoordinate2D(latitude: 40, longitude: -75)
        let bbox = OSMBoundingBox(center: ref, halfSpanDegrees: 0.04)
        let m = OSMSurfaceNormalizer.normalize(response, icao: "KTST", reference: ref,
                                               endpoint: "test", boundingBox: bbox, fetchDate: Date())
        // building=yes way + aeroway=terminal → two footprints.
        XCTAssertEqual(m.buildings.count, 2)
        XCTAssertTrue(m.buildings.contains { $0.osmID == "way/11" })
        XCTAssertTrue(m.buildings.contains { $0.osmID == "way/12" })   // terminal
        // The taxiway is not a building; the apron (building=no) is not a building.
        XCTAssertFalse(m.buildings.contains { $0.osmID == "way/10" })
        XCTAssertFalse(m.buildings.contains { $0.osmID == "way/13" })
        XCTAssertEqual(m.aprons.count, 1)
        // Fresh normalization stamps the current schema version.
        XCTAssertEqual(m.source.schemaVersion, OSMSurface.surfaceSchemaVersion)
        XCTAssertFalse(m.source.isOutdatedSchema)
    }

    // MARK: - Overpass query scoping

    /// The `building` features are scoped to a strictly tighter box than the movement
    /// surfaces, so a hub embedded in a dense metro (e.g. KMSP) doesn't pull the whole city's
    /// buildings and time the extract out — while the runways/taxiways/gates still use the
    /// full airport box.
    func testBuildingExtractIsScopedTighterThanMovementSurfaces() {
        let ref = CLLocationCoordinate2D(latitude: 44.8848, longitude: -93.2223)  // KMSP
        let query = OverpassQuery(icao: "KMSP", center: ref)

        // The building box is a strict subset of the full movement-surface box.
        let full = query.boundingBox
        let bld = query.buildingBoundingBox
        XCTAssertGreaterThan(bld.south, full.south)
        XCTAssertLessThan(bld.north, full.north)
        XCTAssertGreaterThan(bld.west, full.west)
        XCTAssertLessThan(bld.east, full.east)

        // The query text pulls aeroway features on the full box and buildings on the tighter
        // box (never the other way round).
        let text = query.queryText
        XCTAssertTrue(text.contains("way[\"aeroway\"](\(full.overpassClause))"))
        XCTAssertTrue(text.contains("way[\"building\"](\(bld.overpassClause))"))
        XCTAssertFalse(text.contains("way[\"building\"](\(full.overpassClause))"),
                       "buildings must not be pulled on the full box")
    }

    /// Guardrail: even if a caller passes a building span larger than the movement span, the
    /// building box is clamped so it can never exceed the movement-surface box.
    func testBuildingBoxNeverExceedsMovementBox() {
        let ref = CLLocationCoordinate2D(latitude: 44.8848, longitude: -93.2223)
        let query = OverpassQuery(icao: "KMSP", center: ref,
                                  halfSpanDegrees: 0.02, buildingHalfSpanDegrees: 0.09)
        XCTAssertEqual(query.boundingBox, query.buildingBoundingBox,
                       "an oversized building span is clamped to the movement-surface box")
    }
}
