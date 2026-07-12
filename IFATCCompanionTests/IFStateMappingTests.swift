import XCTest
@testable import IFATCCompanion

/// Guards how logical aircraft-state keys resolve against the live Connect manifest.
/// In particular the magnetic and true heading states must resolve to *distinct*
/// entries — the map orients the aircraft symbol by the true heading, while ATC
/// phraseology uses the magnetic heading, so a collision would silently reintroduce
/// the declination-sized rotation error the true-heading path fixes.
final class IFStateMappingTests: XCTestCase {

    /// A trimmed manifest carrying both heading states exactly as Infinite Flight
    /// names them (`id,type,name` per line; type 2 = float, 3 = double).
    private let manifest = """
    746,3,aircraft/0/latitude
    747,3,aircraft/0/longitude
    731,2,aircraft/0/heading_magnetic
    732,2,aircraft/0/heading_true
    744,2,aircraft/0/course
    716,2,aircraft/0/magnetic_variation
    """

    func testMagneticAndTrueHeadingResolveToDistinctStates() {
        let entries = IFManifestParser.parse(manifest)
        let store = IFStateMappingStore()
        store.resolve(from: entries)

        XCTAssertEqual(store.entry(for: .heading)?.name, "aircraft/0/heading_magnetic")
        XCTAssertEqual(store.entry(for: .trueHeading)?.name, "aircraft/0/heading_true")
        // They must not collapse onto the same manifest entry.
        XCTAssertNotEqual(store.entry(for: .heading)?.id, store.entry(for: .trueHeading)?.id)
    }

    /// When the sim only exposes a single magnetic heading, the true-heading key is
    /// left unresolved (the map then falls back to the magnetic heading rather than
    /// mis-binding to it).
    func testTrueHeadingUnresolvedWhenAbsent() {
        let entries = IFManifestParser.parse("""
        746,3,aircraft/0/latitude
        731,2,aircraft/0/heading_magnetic
        """)
        let store = IFStateMappingStore()
        store.resolve(from: entries)

        XCTAssertEqual(store.entry(for: .heading)?.name, "aircraft/0/heading_magnetic")
        XCTAssertNil(store.entry(for: .trueHeading))
    }
}
