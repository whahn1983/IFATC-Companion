import XCTest
import CoreLocation
@testable import IFATCCompanion

/// Licensing & attribution: OpenStreetMap must be identified as ODbL 1.0 (never CC BY
/// 4.0), attribution must be present and linked, cached data must retain source/license
/// metadata, and no unsupported data source may be used.
@MainActor
final class AirportSurfaceLicensingTests: XCTestCase {

    func testOSMIsIdentifiedAsODbLNotCCBY() {
        XCTAssertTrue(OSMSurface.licenseName.contains("ODbL"))
        XCTAssertTrue(OSMSurface.licenseShortName.contains("ODbL"))
        XCTAssertFalse(OSMSurface.licenseName.uppercased().contains("CC BY"),
                       "OSM data is ODbL, not CC BY 4.0")
        XCTAssertFalse(OSMSurface.licenseName.uppercased().contains("CREATIVE COMMONS"))
    }

    func testVisibleAttributionWording() {
        XCTAssertEqual(OSMSurface.attributionText, "Surface data © OpenStreetMap contributors")
        XCTAssertTrue(OSMSurface.attributionShort.contains("OpenStreetMap contributors"))
        XCTAssertEqual(OSMSurface.providerName, "OpenStreetMap contributors")
    }

    func testAttributionLinkIsTheOSMCopyrightPage() {
        XCTAssertEqual(OSMSurface.copyrightURL.absoluteString, "https://www.openstreetmap.org/copyright")
        XCTAssertEqual(OSMSurface.copyrightURL.scheme, "https")
        XCTAssertEqual(OSMSurface.odblLicenseURL.host, "opendatacommons.org")
        XCTAssertEqual(OSMSurface.publicDocumentationURL.scheme, "https")
    }

    func testUserAgentIdentifiesAppAndPublisher() {
        XCTAssertTrue(OSMSurface.userAgent.contains("IFATCCompanion"))
        XCTAssertTrue(OSMSurface.userAgent.contains("H3 Consulting Partners"))
    }

    func testOnlyOverpassOSMEndpointsAreUsed() {
        XCTAssertFalse(OSMSurface.overpassEndpoints.isEmpty)
        for endpoint in OSMSurface.overpassEndpoints {
            XCTAssertTrue(endpoint.contains("overpass"),
                          "the only airport-surface data service is OSM/Overpass: \(endpoint)")
        }
        XCTAssertTrue(OSMSurface.primaryOverpassEndpoint.contains("overpass"))
    }

    func testNormalizedSurfaceRetainsSourceAndLicenseMetadata() {
        let model = MockAirportSurface.model(icao: "KTST",
                                             reference: CLLocationCoordinate2D(latitude: 40, longitude: -75),
                                             primaryRunwayIdent: "36", gate: "A1")
        XCTAssertEqual(model.source.provider, OSMSurface.providerName)
        XCTAssertEqual(model.source.license, OSMSurface.licenseName)
        XCTAssertEqual(model.source.attribution, OSMSurface.attributionText)
        // Original OSM identifiers and tags are retained through normalization.
        XCTAssertTrue(model.runways.first?.osmID.contains("mock-rwy") ?? false)
        XCTAssertFalse(model.runways.first?.tags.isEmpty ?? true)
        XCTAssertEqual(model.runways.first?.tags["aeroway"], "runway")
    }

    func testCacheRoundTripPreservesLicenseAndOSMTags() {
        let cache = AirportSurfaceCache(directoryName: "osm-test-cache-\(UUID().uuidString)")
        defer { cache.deleteAll() }
        let model = MockAirportSurface.model(icao: "ZZZZ",
                                             reference: CLLocationCoordinate2D(latitude: 40, longitude: -75),
                                             primaryRunwayIdent: "36", gate: "A1")
        XCTAssertTrue(cache.save(model))
        let loaded = cache.load(icao: "ZZZZ")
        XCTAssertNotNil(loaded)
        XCTAssertEqual(loaded?.source.license, OSMSurface.licenseName)
        XCTAssertEqual(loaded?.source.attribution, OSMSurface.attributionText)
        XCTAssertEqual(loaded?.runways.first?.tags["aeroway"], "runway")
        XCTAssertEqual(loaded?.icao, "ZZZZ")
        XCTAssertTrue(cache.cachedICAOs().contains("ZZZZ"))
    }

    func testDiagnosticsSnapshotCarriesAttributionAndLicense() {
        let coord = AirportSurfaceCoordinator()
        coord.beginMockTaxiForTesting(kind: .departure,
                                      reference: CLLocationCoordinate2D(latitude: 40, longitude: -75),
                                      runway: "36", gate: "A1")
        let d = coord.diagnosticsSnapshot()
        XCTAssertEqual(d.sourceProvider, OSMSurface.providerName)
        XCTAssertTrue(d.license.contains("ODbL"))
        XCTAssertEqual(d.attribution, OSMSurface.attributionText)
        XCTAssertTrue(d.exportText().contains("OpenStreetMap contributors"))
        XCTAssertTrue(d.exportText().contains("ODbL"))
    }
}
