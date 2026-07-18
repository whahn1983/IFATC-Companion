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
}
