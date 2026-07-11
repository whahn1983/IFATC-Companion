import XCTest
import CoreLocation
import CoreGraphics
@testable import IFATCCompanion

/// Pure-logic coverage for the anonymous EUMETNET ORD client and the OPERA composite
/// renderer. The network fetch, ImageIO decode, and LAEA georeferencing against real
/// composites are verified on device (the ORD S3 host isn't reachable from CI); these
/// exercise the deterministic URL/key parsing, projection, and classification.
final class EUMETNETORDClientTests: XCTestCase {

    private func utcDate(_ y: Int, _ mo: Int, _ d: Int, _ h: Int = 0, _ mi: Int = 0) -> Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        return cal.date(from: DateComponents(year: y, month: mo, day: d, hour: h, minute: mi))!
    }

    func testCompositePrefixIsUTCDatePath() {
        XCTAssertEqual(EUMETNETORDClient.compositePrefix(for: utcDate(2026, 6, 4, 2, 20)),
                       "2026/06/04/OPERA/COMP/")
        // Just before UTC midnight still resolves to that UTC day, not the next.
        XCTAssertEqual(EUMETNETORDClient.compositePrefix(for: utcDate(2026, 1, 9, 23, 59)),
                       "2026/01/09/OPERA/COMP/")
    }

    func testListURLIsAnonymousListObjectsV2() {
        let client = EUMETNETORDClient()
        let url = client.listURL(prefix: "2026/06/04/OPERA/COMP/")
        let s = url?.absoluteString ?? ""
        XCTAssertTrue(s.contains("s3.waw3-1.cloudferro.com"))
        XCTAssertTrue(s.contains("openradar-24h"))
        XCTAssertTrue(s.contains("list-type=2"))
        XCTAssertTrue(s.contains("prefix="))
        // Anonymous: no signing/credential query items.
        XCTAssertFalse(s.lowercased().contains("x-amz-signature"))
        XCTAssertFalse(s.lowercased().contains("awsaccesskey"))
    }

    func testObjectURLBuildsKeylessPath() {
        let client = EUMETNETORDClient()
        let key = "2026/06/04/OPERA/COMP/OPERA@20260604T0220@0@DBZH.tif"
        let s = client.objectURL(key: key)?.absoluteString ?? ""
        XCTAssertTrue(s.hasPrefix("https://s3.waw3-1.cloudferro.com/openradar-24h/"))
        XCTAssertTrue(s.hasSuffix("OPERA@20260604T0220@0@DBZH.tif"))
    }

    func testParseKeysFromListXML() {
        let xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <ListBucketResult>
          <Contents><Key>2026/06/04/OPERA/COMP/OPERA@20260604T0220@0@DBZH.tif</Key><Size>1</Size></Contents>
          <Contents><Key>2026/06/04/OPERA/COMP/OPERA@20260604T0220@0@DBZH.h5</Key></Contents>
        </ListBucketResult>
        """
        let keys = EUMETNETORDClient.parseKeys(fromListXML: xml)
        XCTAssertEqual(keys.count, 2)
        XCTAssertTrue(keys.contains("2026/06/04/OPERA/COMP/OPERA@20260604T0220@0@DBZH.tif"))
    }

    func testCompositeTimestampParse() {
        let d = EUMETNETORDClient.compositeTimestamp(
            fromKey: "2026/06/04/OPERA/COMP/OPERA@20260604T0220@0@DBZH.tif")
        XCTAssertNotNil(d)
        var cal = Calendar(identifier: .gregorian); cal.timeZone = TimeZone(identifier: "UTC")!
        let c = cal.dateComponents([.year, .month, .day, .hour, .minute], from: d!)
        XCTAssertEqual(c.year, 2026); XCTAssertEqual(c.month, 6); XCTAssertEqual(c.day, 4)
        XCTAssertEqual(c.hour, 2); XCTAssertEqual(c.minute, 20)
        XCTAssertNil(EUMETNETORDClient.compositeTimestamp(fromKey: "not-a-composite.tif"))
    }

    func testIsGeoTIFFCompositeMatchesProductAndExtension() {
        let dbzhTif = "…/OPERA@20260604T0220@0@DBZH.tif"
        XCTAssertTrue(EUMETNETORDClient.isGeoTIFFComposite(dbzhTif, product: .maximumReflectivity))
        // ODIM HDF5 is not a renderable GeoTIFF.
        XCTAssertFalse(EUMETNETORDClient.isGeoTIFFComposite("…/OPERA@20260604T0220@0@DBZH.h5",
                                                            product: .maximumReflectivity))
        // Wrong product code.
        XCTAssertFalse(EUMETNETORDClient.isGeoTIFFComposite("…/OPERA@20260604T0215@0@RATE.tif",
                                                            product: .maximumReflectivity))
        XCTAssertTrue(EUMETNETORDClient.isGeoTIFFComposite("…/OPERA@20260604T0215@0@RATE.tiff",
                                                           product: .instantaneousRainRate))
    }

    func testLatestGeoTIFFKeyPicksNewestMatchingProduct() {
        let keys = [
            "2026/06/04/OPERA/COMP/OPERA@20260604T0200@0@DBZH.tif",
            "2026/06/04/OPERA/COMP/OPERA@20260604T0220@0@DBZH.tif",   // newest DBZH .tif
            "2026/06/04/OPERA/COMP/OPERA@20260604T0230@0@DBZH.h5",    // newer but HDF5 → excluded
            "2026/06/04/OPERA/COMP/OPERA@20260604T0225@0@RATE.tif"    // wrong product
        ]
        XCTAssertEqual(EUMETNETORDClient.latestGeoTIFFKey(from: keys, product: .maximumReflectivity),
                       "2026/06/04/OPERA/COMP/OPERA@20260604T0220@0@DBZH.tif")
        XCTAssertNil(EUMETNETORDClient.latestGeoTIFFKey(from: keys, product: .oneHourAccumulation))
    }
}

/// The OPERA LAEA grid projection and composite classification/reprojection.
final class OPERACompositeRendererTests: XCTestCase {

    func testProjectionOriginMapsToZero() {
        let p = OPERALambertGrid.project(lat: OPERALambertGrid.lat0, lon: OPERALambertGrid.lon0)
        XCTAssertEqual(p.x, 0, accuracy: 1)
        XCTAssertEqual(p.y, 0, accuracy: 1)
    }

    func testNormalizedInsideAndOutsideGrid() {
        let grid = OPERALambertGrid()
        // Central Europe (the projection origin) sits inside the grid.
        let center = grid.normalized(lat: 55, lon: 10)
        XCTAssertNotNil(center)
        if let c = center {
            XCTAssertTrue(c.u > 0 && c.u < 1)
            XCTAssertTrue(c.v > 0 && c.v < 1)
        }
        // North Scotland (the user's scenario) is inside the composite.
        XCTAssertNotNil(grid.normalized(lat: 57.8, lon: -4.0))
        // Well outside Europe → nil (no fabricated coverage).
        XCTAssertNil(grid.normalized(lat: 0, lon: 0))       // equatorial Atlantic/Africa
        XCTAssertNil(grid.normalized(lat: 39, lon: -98))    // Kansas, U.S.
    }

    func testNormalizedRowOrderNorthIsAbove() {
        let grid = OPERALambertGrid()
        guard let north = grid.normalized(lat: 65, lon: -4),
              let south = grid.normalized(lat: 45, lon: -4) else {
            return XCTFail("both points should be inside the grid")
        }
        // v increases north→south (row order), so a more-northern point has smaller v.
        XCTAssertLessThan(north.v, south.v)
    }

    func testClassifyColoredReflectivityRamp() {
        XCTAssertEqual(OPERACompositeRenderer.classify(r: 0, g: 180, b: 60, a: 255), .light)     // green
        XCTAssertEqual(OPERACompositeRenderer.classify(r: 235, g: 220, b: 40, a: 255), .moderate) // yellow
        XCTAssertEqual(OPERACompositeRenderer.classify(r: 245, g: 140, b: 20, a: 255), .heavy)    // orange
        XCTAssertEqual(OPERACompositeRenderer.classify(r: 220, g: 30, b: 30, a: 255), .extreme)   // red
        // Fully transparent → no precipitation.
        XCTAssertNil(OPERACompositeRenderer.classify(r: 220, g: 30, b: 30, a: 0))
    }

    func testClassifyGrayDBZHScaling() {
        // Near-gray single-band DBZH via ODIM gain 0.5 / offset −32:
        //  DN 150 → 43 dBZ (heavy), DN 100 → 18 dBZ (below moderate → ignored).
        XCTAssertEqual(OPERACompositeRenderer.classify(r: 150, g: 150, b: 150, a: 255), .heavy)
        XCTAssertNil(OPERACompositeRenderer.classify(r: 100, g: 100, b: 100, a: 255))
        // Sentinels 0 and 255 are treated as no-data.
        XCTAssertNil(OPERACompositeRenderer.classify(r: 0, g: 0, b: 0, a: 255))
        XCTAssertNil(OPERACompositeRenderer.classify(r: 255, g: 255, b: 255, a: 255))
    }

    func testOverlayColorsRoundTripThroughClassifier() {
        // A colorized overlay pixel must classify back to the same intensity, so the
        // display render and the sampler agree.
        for intensity in [WeatherIntensity.light, .moderate, .heavy, .extreme] {
            let c = OPERACompositeRenderer.color(for: intensity)
            XCTAssertEqual(OPERACompositeRenderer.classify(r: c.r, g: c.g, b: c.b, a: c.a), intensity)
        }
    }

    func testInverseMercatorRoundTrip() {
        let origin = OPERACompositeRenderer.inverseMercator(x: 0, y: 0)
        XCTAssertEqual(origin.lat, 0, accuracy: 1e-6)
        XCTAssertEqual(origin.lon, 0, accuracy: 1e-6)
        // Half the mercator world width in x is +90° longitude.
        let east = OPERACompositeRenderer.inverseMercator(x: 20037508.342789244 / 2, y: 0)
        XCTAssertEqual(east.lon, 90, accuracy: 1e-3)
    }

    func testIntensityGridResamplesInsideBBox() {
        // A raster that is uniformly extreme, sampled over a bbox fully inside the grid,
        // yields an all-extreme output grid.
        let raster = OPERARaster(width: 4, height: 4,
                                 intensity: Array(repeating: .extreme, count: 16))
        let bbox = RadarBoundingBox(minLatitude: 48, minLongitude: 5, maxLatitude: 52, maxLongitude: 15)
        let grid = OPERACompositeRenderer.intensityGrid(from: raster, bbox: bbox, columns: 8, rows: 8)
        XCTAssertEqual(grid.count, 8)
        XCTAssertEqual(grid[0].count, 8)
        XCTAssertEqual(grid[4][4], .extreme)
        XCTAssertEqual(grid[0][0], .extreme)
    }

    func testRenderMercatorPNGProducesPNGData() {
        let raster = OPERARaster(width: 8, height: 8,
                                 intensity: Array(repeating: .heavy, count: 64))
        let bbox = RadarBoundingBox(minLatitude: 50, minLongitude: -6, maxLatitude: 60, maxLongitude: 2)
        let data = OPERACompositeRenderer.renderMercatorPNG(from: raster, bbox: bbox,
                                                            width: 32, height: 32)
        XCTAssertNotNil(data)
        // PNG magic number.
        if let d = data, d.count >= 8 {
            XCTAssertEqual(Array(d.prefix(4)), [0x89, 0x50, 0x4E, 0x47])
        }
    }
}
