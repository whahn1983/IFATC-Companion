import XCTest
@testable import IFATCCompanion

final class UNICOMTests: XCTestCase {

    func testBroadcastText() {
        XCTAssertEqual(UNICOMEvent.takingRunway.broadcast(ident: "Minneapolis", runway: "17R"),
                       "Traffic Minneapolis, taking runway 17R for departure.")
        XCTAssertEqual(UNICOMEvent.inbound.broadcast(ident: "Denver", runway: ""),
                       "Traffic Denver, inbound for landing.")
        XCTAssertEqual(UNICOMEvent.clearOfRunway.broadcast(ident: "", runway: "30L"),
                       "Traffic, clear of runway 30L.")
    }

    func testTrustedEventClassification() {
        XCTAssertTrue(UNICOMEvent.taxiingToRunway.isTrusted)
        XCTAssertTrue(UNICOMEvent.inbound.isTrusted)
        XCTAssertTrue(UNICOMEvent.clearOfRunway.isTrusted)
        XCTAssertFalse(UNICOMEvent.takingRunway.isTrusted)
        XCTAssertFalse(UNICOMEvent.crossingRunway.isTrusted)
        XCTAssertFalse(UNICOMEvent.departingRunway.isTrusted)
    }

    func testEveryEventHasCommandKeywords() {
        for event in UNICOMEvent.allCases {
            XCTAssertFalse(event.commandKeywords.isEmpty, "\(event) has no command keywords")
        }
    }

    func testManifestKeywordMatching() {
        let entries = [
            IFManifestEntry(id: 10, type: .int32, name: "commands.TakingRunway"),
            IFManifestEntry(id: 11, type: .int32, name: "commands.InboundForLanding"),
            IFManifestEntry(id: 12, type: .double, name: "aircraft/0/altitude_msl")
        ]
        let store = IFStateMappingStore()
        store.resolve(from: entries)
        XCTAssertNotNil(store.command(matchingAnyOf: UNICOMEvent.takingRunway.commandKeywords))
        XCTAssertNotNil(store.command(matchingAnyOf: UNICOMEvent.inbound.commandKeywords))
    }

    func testStateMappingResolvesAltitude() {
        let entries = [
            IFManifestEntry(id: 1, type: .double, name: "aircraft/0/altitude_msl"),
            IFManifestEntry(id: 2, type: .double, name: "aircraft/0/groundspeed"),
            IFManifestEntry(id: 3, type: .boolean, name: "aircraft/0/is_on_ground")
        ]
        let store = IFStateMappingStore()
        store.resolve(from: entries)
        XCTAssertEqual(store.entry(for: .altitudeMSL)?.id, 1)
        XCTAssertEqual(store.entry(for: .groundSpeed)?.id, 2)
        XCTAssertEqual(store.entry(for: .onGround)?.id, 3)
    }
}
