import XCTest
import CoreLocation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
@testable import IFATCCompanion

/// Tests for `RadarImageSampler` — the live "radar image → moderate-or-greater
/// precipitation cell" sampling used to focus a convective SIGMET deviation on the
/// precipitation core instead of the whole advisory area. All logic here is
/// deterministic and touches no network.
final class RadarImageSamplerTests: XCTestCase {

    // MARK: - Color → intensity

    func testReflectivityColorsMapToIntensity() {
        // Green and blue are the lightest returns → below the moderate threshold.
        XCTAssertEqual(RadarImageSampler.intensity(r: 0, g: 255, b: 0, a: 255), .light)
        XCTAssertEqual(RadarImageSampler.intensity(r: 0, g: 120, b: 255, a: 255), .light)
        // Yellow → moderate, orange → heavy, red / magenta → extreme.
        XCTAssertEqual(RadarImageSampler.intensity(r: 255, g: 255, b: 0, a: 255), .moderate)
        XCTAssertEqual(RadarImageSampler.intensity(r: 255, g: 150, b: 0, a: 255), .heavy)
        XCTAssertEqual(RadarImageSampler.intensity(r: 255, g: 0, b: 0, a: 255), .extreme)
        XCTAssertEqual(RadarImageSampler.intensity(r: 255, g: 0, b: 255, a: 255), .extreme)
    }

    func testIMERGRatePaletteMapsColorsToIntensity() {
        let imerg = RadarImageSampler.Palette.imergRate
        // Blue and green (the broad low-rate satellite wash) stay light so a stratiform
        // field doesn't blob the whole route into one giant deviation.
        XCTAssertEqual(RadarImageSampler.intensity(r: 0, g: 120, b: 255, a: 255, palette: imerg), .light)
        XCTAssertEqual(RadarImageSampler.intensity(r: 0, g: 255, b: 0, a: 255, palette: imerg), .light)
        // Yellow-green (chartreuse) is promoted to moderate — satellite averaging paints
        // convective cores paler than radar, so this band is where meaningful cells show.
        XCTAssertEqual(RadarImageSampler.intensity(r: 150, g: 255, b: 0, a: 255, palette: imerg), .moderate)
        // Yellow → moderate, orange → heavy, red / magenta → extreme, as with radar.
        XCTAssertEqual(RadarImageSampler.intensity(r: 255, g: 255, b: 0, a: 255, palette: imerg), .moderate)
        XCTAssertEqual(RadarImageSampler.intensity(r: 255, g: 150, b: 0, a: 255, palette: imerg), .heavy)
        XCTAssertEqual(RadarImageSampler.intensity(r: 255, g: 0, b: 0, a: 255, palette: imerg), .extreme)
        XCTAssertEqual(RadarImageSampler.intensity(r: 255, g: 0, b: 255, a: 255, palette: imerg), .extreme)
    }

    func testChartreuseIsTheRampDifferentiator() {
        // The one band the two ramps disagree on: yellow-green reads light on the
        // reflectivity ramp (default) but moderate on the IMERG rate ramp.
        XCTAssertEqual(RadarImageSampler.intensity(r: 150, g: 255, b: 0, a: 255), .light)
        XCTAssertEqual(RadarImageSampler.intensity(r: 150, g: 255, b: 0, a: 255, palette: .imergRate), .moderate)
    }

    func testTransparentAndAchromaticPixelsAreNotPrecipitation() {
        XCTAssertNil(RadarImageSampler.intensity(r: 255, g: 0, b: 0, a: 0), "transparent → no precip")
        XCTAssertNil(RadarImageSampler.intensity(r: 128, g: 128, b: 128, a: 255), "gray → no precip")
        XCTAssertNil(RadarImageSampler.intensity(r: 250, g: 250, b: 250, a: 255), "near-white → no precip")
        XCTAssertNil(RadarImageSampler.intensity(r: 10, g: 10, b: 12, a: 255), "near-black → no precip")
    }

    // MARK: - Grid → cells

    private let unitBox = RadarBoundingBox(minLatitude: 40, minLongitude: -100,
                                           maxLatitude: 41, maxLongitude: -99)

    func testModeratePlusBlockBecomesOneCell() {
        // A 2×2 moderate-or-greater block inside a 4×4 grid (row 0 = north).
        let m: WeatherIntensity? = .moderate
        let h: WeatherIntensity? = .heavy
        let grid: [[WeatherIntensity?]] = [
            [nil, nil, nil, nil],
            [nil, m,   h,   nil],
            [nil, m,   m,   nil],
            [nil, nil, nil, nil]
        ]
        let cells = RadarImageSampler.cells(from: grid, bbox: unitBox)
        XCTAssertEqual(cells.count, 1)
        let cell = try? XCTUnwrap(cells.first)
        XCTAssertEqual(cell?.intensity, .heavy, "cell intensity is the cluster peak")

        // rows 1–2 of 4 → lat 40.25…40.75; cols 1–2 of 4 → lon −99.75…−99.25.
        let lats = cell?.polygon.map { $0.latitude } ?? []
        let lons = cell?.polygon.map { $0.longitude } ?? []
        XCTAssertEqual(lats.min() ?? 0, 40.25, accuracy: 1e-9)
        XCTAssertEqual(lats.max() ?? 0, 40.75, accuracy: 1e-9)
        XCTAssertEqual(lons.min() ?? 0, -99.75, accuracy: 1e-9)
        XCTAssertEqual(lons.max() ?? 0, -99.25, accuracy: 1e-9)
    }

    func testLightOnlyGridProducesNoCells() {
        let l: WeatherIntensity? = .light
        let grid: [[WeatherIntensity?]] = Array(repeating: Array(repeating: l, count: 4), count: 4)
        XCTAssertTrue(RadarImageSampler.cells(from: grid, bbox: unitBox).isEmpty,
                      "only moderate-or-greater returns become deviation cells")
    }

    func testTinyNoiseClusterIsDropped() {
        let h: WeatherIntensity? = .heavy
        let grid: [[WeatherIntensity?]] = [
            [nil, nil, nil, nil],
            [nil, h,   nil, nil],
            [nil, nil, nil, nil],
            [nil, nil, nil, nil]
        ]
        XCTAssertTrue(RadarImageSampler.cells(from: grid, bbox: unitBox, minCells: 3).isEmpty,
                      "sub-threshold speckle must not create a cell")
    }

    func testSeparateClustersBecomeSeparateCells() {
        let h: WeatherIntensity? = .heavy
        let grid: [[WeatherIntensity?]] = [
            [h,   h,   nil, nil, nil],
            [h,   h,   nil, nil, nil],
            [nil, nil, nil, nil, nil],
            [nil, nil, nil, h,   h  ],
            [nil, nil, nil, h,   h  ]
        ]
        let box = RadarBoundingBox(minLatitude: 40, minLongitude: -100, maxLatitude: 45, maxLongitude: -95)
        XCTAssertEqual(RadarImageSampler.cells(from: grid, bbox: box).count, 2)
    }

    // MARK: - SIGMET precipitation cores

    private func square(latMin: Double, latMax: Double, lonMin: Double, lonMax: Double) -> [CLLocationCoordinate2D] {
        [CLLocationCoordinate2D(latitude: latMin, longitude: lonMin),
         CLLocationCoordinate2D(latitude: latMin, longitude: lonMax),
         CLLocationCoordinate2D(latitude: latMax, longitude: lonMax),
         CLLocationCoordinate2D(latitude: latMax, longitude: lonMin)]
    }

    func testPrecipitationCoreReturnsOverlappingModeratePlusCell() {
        let sigmet = square(latMin: 40, latMax: 44, lonMin: -100, lonMax: -96)  // big advisory
        let insideHeavy = RadarCell(polygon: square(latMin: 41, latMax: 41.6, lonMin: -99, lonMax: -98.4),
                                    intensity: .heavy)
        let outside = RadarCell(polygon: square(latMin: 30, latMax: 30.6, lonMin: -80, lonMax: -79.4),
                                intensity: .heavy)
        let lightInside = RadarCell(polygon: square(latMin: 42, latMax: 42.6, lonMin: -99, lonMax: -98.4),
                                    intensity: .light)

        let cores = RadarImageSampler.precipitationCores(in: sigmet,
                                                         cells: [insideHeavy, outside, lightInside])
        XCTAssertEqual(cores.count, 1, "only the moderate+ cell inside the advisory is a precip core")
        // The returned core is the cell's own (much smaller) polygon, not the SIGMET.
        let coreLats = cores.first?.map { $0.latitude } ?? []
        XCTAssertEqual(coreLats.min() ?? 0, 41, accuracy: 1e-9)
        XCTAssertEqual(coreLats.max() ?? 0, 41.6, accuracy: 1e-9)
    }

    func testPrecipitationCoreEmptyWhenNoSignificantPrecipInArea() {
        let sigmet = square(latMin: 40, latMax: 44, lonMin: -100, lonMax: -96)
        let farAway = RadarCell(polygon: square(latMin: 10, latMax: 10.6, lonMin: -50, lonMax: -49.4),
                                intensity: .extreme)
        XCTAssertTrue(RadarImageSampler.precipitationCores(in: sigmet, cells: [farAway]).isEmpty,
                      "with no precipitation in the advisory the caller falls back to the full area")
    }

    func testPolygonsOverlapDetectsEdgeCrossingWithoutContainedVertex() {
        // A plus-sign crossing: neither square contains a vertex of the other, but
        // their edges cross, so they overlap.
        let horizontal = square(latMin: 40.4, latMax: 40.6, lonMin: -100, lonMax: -96)
        let vertical = square(latMin: 39, latMax: 42, lonMin: -98.1, lonMax: -97.9)
        XCTAssertTrue(RadarImageSampler.polygonsOverlap(horizontal, vertical))
    }

    // MARK: - Sample resolution (whole-flight-plan sampling)

    func testSampleGridScalesWithSpanAndClamps() {
        // A short route floors at the minimum grid (no over-sampling a tiny image).
        let small = RadarImageSampler.sampleGrid(latSpanNM: 40, lonSpanNM: 40)
        XCTAssertEqual(small.rows, 160)
        XCTAssertEqual(small.columns, 160)

        // In the scaling band the grid holds ~2 NM per pixel on each axis, so an
        // elongated route stays fine on its long axis while the short axis floors.
        let mid = RadarImageSampler.sampleGrid(latSpanNM: 600, lonSpanNM: 200)
        XCTAssertEqual(mid.rows, 300, "600 NM / 2 NM per pixel")
        XCTAssertEqual(mid.columns, 160, "200 NM / 2 → 100, floored to the minimum")

        let scaled = RadarImageSampler.sampleGrid(latSpanNM: 900, lonSpanNM: 900)
        XCTAssertEqual(scaled.rows, 450)
        XCTAssertEqual(scaled.columns, 450)

        // A transcon route caps the grid rather than requesting a giant image.
        let big = RadarImageSampler.sampleGrid(latSpanNM: 4000, lonSpanNM: 4000)
        XCTAssertEqual(big.rows, 640)
        XCTAssertEqual(big.columns, 640)
    }

    // MARK: - Mercator-aspect sample size (live NOAA/NASA export)

    /// The exact Web-Mercator width:height of a box, the aspect the 3857 render is
    /// registered to. Mirrors `mercatorSampleSize`'s own span math.
    private func mercatorAspect(_ box: RadarBoundingBox) -> Double {
        func y(_ lat: Double) -> Double {
            let c = min(85.05112878, max(-85.05112878, lat))
            return log(tan(.pi / 4 + c * .pi / 180 / 2))
        }
        let w = (box.maxLongitude - box.minLongitude) * .pi / 180
        let h = y(box.maxLatitude) - y(box.minLatitude)
        return w / h
    }

    func testMercatorSampleSizeMatchesBboxMercatorAspect() {
        // A wide, mid-latitude corridor: sizing from lat/lon NM with an independent
        // `[160, 640]` clamp per axis would floor the short (lat) axis and break the
        // aspect, so the ImageServer would adjust the returned extent and drift the cells.
        // The size must instead hold the bbox's exact Web-Mercator aspect ratio.
        let wide = RadarBoundingBox(minLatitude: 33, minLongitude: -102,
                                    maxLatitude: 37, maxLongitude: -94)
        let s = RadarImageSampler.mercatorSampleSize(bbox: wide)
        XCTAssertEqual(Double(s.columns) / Double(s.rows), mercatorAspect(wide), accuracy: 0.02,
                       "sample size aspect must match the bbox's Web-Mercator aspect")
        // The longer (Mercator) axis keeps the ~2 NM/pixel resolution budget; the shorter
        // axis follows from the aspect (here below the old 160 floor — which is the point).
        XCTAssertEqual(s.columns, 197, "longer axis holds the sampleGrid resolution/cap")
        XCTAssertLessThan(s.rows, 160, "shorter axis follows the aspect, not the per-axis floor")
    }

    func testMercatorSampleSizeTallCorridorKeepsAspect() {
        // A tall, narrow corridor: the latitude axis is the longer Mercator axis and keeps
        // the resolution budget; longitude follows from the aspect.
        let tall = RadarBoundingBox(minLatitude: 30, minLongitude: -98,
                                    maxLatitude: 42, maxLongitude: -96)
        let s = RadarImageSampler.mercatorSampleSize(bbox: tall)
        XCTAssertEqual(Double(s.columns) / Double(s.rows), mercatorAspect(tall), accuracy: 0.02)
        XCTAssertGreaterThan(s.rows, s.columns, "a tall corridor samples more rows than columns")
    }

    func testMercatorSampleSizeSmallRegionFloorsLongerAxisAndKeepsAspect() {
        // A small region floors the longer Mercator axis to the minimum. A degrees-square
        // box at mid-latitude is taller than wide in Mercator (latitude is stretched by
        // 1/cos(lat)), so latitude is the longer axis and floors to 160; longitude follows.
        let small = RadarBoundingBox(minLatitude: 34.8, minLongitude: -97.6,
                                     maxLatitude: 35.2, maxLongitude: -97.2)
        let s = RadarImageSampler.mercatorSampleSize(bbox: small)
        XCTAssertEqual(max(s.columns, s.rows), 160, "longer axis floors to the minimum dimension")
        XCTAssertEqual(s.rows, 160, "latitude is the longer Mercator axis at mid-latitude")
        XCTAssertEqual(Double(s.columns) / Double(s.rows), mercatorAspect(small), accuracy: 0.02)
    }

    // MARK: - PNG decode orientation (north stays north)

    /// A PNG whose rows are laid out **top-first** (row 0 = north), built from an explicit
    /// `CGImage` over raw bytes so the source orientation is unambiguous — independent of
    /// any `CGContext` row convention. `top…` rows are opaque yellow (moderate) and the
    /// rest transparent.
    private func makePNG(width: Int, height: Int, opaqueYellowTopRows top: Int) -> Data? {
        var px = [UInt8](repeating: 0, count: 4 * width * height)
        for row in 0..<height where row < top {
            for col in 0..<width {
                let i = (row * width + col) * 4
                px[i] = 255; px[i + 1] = 255; px[i + 2] = 0; px[i + 3] = 255   // opaque yellow → moderate
            }
        }
        let bitmapInfo = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue)
        guard let provider = CGDataProvider(data: Data(px) as CFData),
              let image = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
                                  bytesPerRow: 4 * width, space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: bitmapInfo, provider: provider, decode: nil,
                                  shouldInterpolate: false, intent: .defaultIntent) else { return nil }
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, UTType.png.identifier as CFString, 1, nil)
        else { return nil }
        CGImageDestinationAddImage(dest, image, nil)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return out as Data
    }

    /// End-to-end guard on the PNG decode's vertical orientation: precipitation in the
    /// **north** (top) half of the image must decode to a cell in the **north** half of the
    /// bbox. A flipped decode (the earlier `rows - 1 - row`) mirrors it into the south,
    /// which put southern storms' sampled cells hundreds of NM north on the map.
    func testPNGDecodeKeepsNorthPrecipInNorthHalf() throws {
        let side = 16
        let data = try XCTUnwrap(makePNG(width: side, height: side, opaqueYellowTopRows: side / 2))
        let bbox = RadarBoundingBox(minLatitude: 40, minLongitude: -100, maxLatitude: 44, maxLongitude: -96)
        let cells = try XCTUnwrap(
            RadarImageSampler.cells(fromPNG: data, columns: side, rows: side, bbox: bbox))
        let cell = try XCTUnwrap(cells.first, "the yellow (moderate) top half must produce a cell")
        let centerLat = try XCTUnwrap(cell.center?.latitude)
        let midLat = (bbox.minLatitude + bbox.maxLatitude) / 2
        XCTAssertGreaterThan(centerLat, midLat,
                             "north-half precipitation must decode to a north-half cell (not vertically flipped)")
        // The cluster fills exactly the top half of the image, so the cell's southern edge
        // sits at the bbox mid-latitude and its northern edge at the top.
        XCTAssertEqual(cell.polygon.map(\.latitude).min() ?? 0, midLat, accuracy: 1e-9)
        XCTAssertEqual(cell.polygon.map(\.latitude).max() ?? 0, bbox.maxLatitude, accuracy: 1e-9)
    }
}
