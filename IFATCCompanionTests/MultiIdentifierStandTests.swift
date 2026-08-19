import XCTest
import CoreLocation
@testable import IFATCCompanion

/// Stands that OSM maps under **more than one identifier** on a single node — `A1;A2` at
/// Newark, `A54/A56` at Frankfurt, `C16/C16A + C16B` — which is how roughly 8–10% of the
/// stands at those fields are tagged.
///
/// Kept verbatim, such a `ref` is displayed and spoken as written, and a pilot who *types*
/// one of its identifiers matches nothing at all, because the stand is named `A1;A2` and
/// every lookup is exact. That hits manual entry as much as the automatic assignment.
final class MultiIdentifierStandTests: XCTestCase {

    private let ref = CLLocationCoordinate2D(latitude: 40, longitude: -75)

    // MARK: - Splitting the tag

    func testASingleIdentifierIsLeftExactlyAsTagged() {
        let parsed = StandIdentifier.parse("B44")
        XCTAssertEqual(parsed.name, "B44")
        XCTAssertTrue(parsed.aliases.isEmpty, "the ordinary stand gains nothing to match on")
    }

    func testSemicolonSeparatedIdentifiersSplit() {
        // The OSM multi-value separator; EWR's A1;A2.
        let parsed = StandIdentifier.parse("A1;A2")
        XCTAssertEqual(parsed.name, "A1", "the first identifier is the one a controller says")
        XCTAssertEqual(parsed.aliases, ["A2", "A1;A2"])
    }

    func testSlashAndPlusSeparatedIdentifiersSplit() {
        XCTAssertEqual(StandIdentifier.parse("A54/A56").name, "A54")
        XCTAssertEqual(StandIdentifier.parse("A54/A56").aliases, ["A56", "A54/A56"])

        let mixed = StandIdentifier.parse("C16/C16A + C16B")
        XCTAssertEqual(mixed.name, "C16")
        XCTAssertEqual(mixed.aliases, ["C16A", "C16B", "C16/C16A + C16B"])
    }

    func testABareNumberInheritsThePrecedingLetterPrefix() {
        // "A54/56" is A54 and A56, not A54 and 56.
        let parsed = StandIdentifier.parse("A54/56")
        XCTAssertEqual(parsed.name, "A54")
        XCTAssertEqual(parsed.aliases, ["A56", "A54/56"])
        // Only in that shape: nothing to inherit from, or a part that carries its own letters.
        XCTAssertEqual(StandIdentifier.parse("1/2").aliases.first, "2")
        XCTAssertEqual(StandIdentifier.parse("A1/B2").aliases.first, "B2")
    }

    func testRangesSpacedNamesAndStraySeparatorsAreNotSplit() {
        // A dash reads as a range, not a list, and a space is part of the identifier.
        XCTAssertEqual(StandIdentifier.parse("A1-A5").name, "A1-A5")
        XCTAssertTrue(StandIdentifier.parse("A1-A5").aliases.isEmpty)
        XCTAssertEqual(StandIdentifier.parse("Gate 12").name, "Gate 12")
        XCTAssertTrue(StandIdentifier.parse("Gate 12").aliases.isEmpty)
        // A trailing separator or a repeat leaves one identifier and no aliases worth keeping.
        XCTAssertEqual(StandIdentifier.parse("A1;").name, "A1")
        XCTAssertTrue(StandIdentifier.parse("A1;").aliases.isEmpty)
        XCTAssertEqual(StandIdentifier.parse("A1;A1").name, "A1")
        XCTAssertTrue(StandIdentifier.parse("A1;A1").aliases.isEmpty)
    }

    // MARK: - Through the normalizer and the lookup

    private func normalized(_ elements: String) -> AirportSurfaceModel {
        let json = "{\"version\":0.6,\"generator\":\"Overpass API\",\"elements\":[\(elements)]}"
        let response = try! JSONDecoder().decode(OverpassResponse.self, from: json.data(using: .utf8)!)
        let bbox = OSMBoundingBox(center: ref, halfSpanDegrees: 0.04)
        return OSMSurfaceNormalizer.normalize(response, icao: "KEWR", reference: ref,
                                              endpoint: "test", boundingBox: bbox, fetchDate: Date())
    }

    private func field() -> AirportSurfaceModel {
        normalized("""
        {"type":"node","id":1,"lat":40.0020,"lon":-75.0012,"tags":{"aeroway":"gate","ref":"A1;A2"}},
        {"type":"node","id":2,"lat":40.0021,"lon":-75.0013,"tags":{"aeroway":"gate","ref":"C16/C16A + C16B"}},
        {"type":"node","id":3,"lat":40.0022,"lon":-75.0014,"tags":{"aeroway":"gate","ref":"C16A"}},
        {"type":"node","id":4,"lat":40.0023,"lon":-75.0015,"tags":{"aeroway":"gate","ref":"B44"}}
        """)
    }

    func testTheStandIsNamedByItsFirstIdentifier() {
        let m = field()
        XCTAssertEqual(m.parkingPositions.first { $0.osmID == "node/1" }?.name, "A1",
                       "the map label and the clearance say A1, never \"A1;A2\"")
        XCTAssertEqual(m.parkingPositions.first { $0.osmID == "node/2" }?.name, "C16")
        // The tag itself is never discarded — provenance keeps it verbatim.
        XCTAssertEqual(m.parkingPositions.first { $0.osmID == "node/1" }?.tags["ref"], "A1;A2")
    }

    func testAPilotTypingEitherIdentifierFindsTheStand() {
        let m = field()
        XCTAssertEqual(m.parking(named: "A1")?.osmID, "node/1")
        XCTAssertEqual(m.parking(named: "A2")?.osmID, "node/1", "the second identifier matched too")
        XCTAssertEqual(m.parking(named: "a2")?.osmID, "node/1", "case-insensitively")
        XCTAssertEqual(m.parking(named: " A2 ")?.osmID, "node/1", "and ignoring stray spaces")
        XCTAssertEqual(m.parking(named: "A1;A2")?.osmID, "node/1", "as does the tag as written")
        XCTAssertEqual(m.parking(named: "C16B")?.osmID, "node/2")
    }

    func testAStandsOwnNameBeatsAnotherStandsAlias() {
        // C16A is both an alias of node/2 (C16/C16A + C16B) and the name of node/3.
        XCTAssertEqual(field().parking(named: "C16A")?.osmID, "node/3")
    }

    func testAnUnknownGateStillMatchesNothing() {
        XCTAssertNil(field().parking(named: "Z9"))
        XCTAssertNil(field().parking(named: ""))
    }

    // MARK: - Cached extracts

    func testACacheWrittenBeforeIdentifiersWereSplitStillDecodesAndIsRefetched() throws {
        // A v2 cache has no `aliases` key and holds the raw tag as the stand's name. It must
        // still decode (never crash a load), and its schema version must mark it for re-fetch.
        let legacy = """
        {"osmID":"node/1","tags":{"aeroway":"gate","ref":"A1;A2"},"kind":"gate","name":"A1;A2",
         "coordinate":{"latitude":40.002,"longitude":-75.0012}}
        """
        let stand = try JSONDecoder().decode(SurfaceParking.self, from: legacy.data(using: .utf8)!)
        XCTAssertEqual(stand.name, "A1;A2")
        XCTAssertTrue(stand.aliases.isEmpty)
        XCTAssertGreaterThan(OSMSurface.surfaceSchemaVersion, 2,
                             "the schema bump is what re-fetches those caches")
    }
}
