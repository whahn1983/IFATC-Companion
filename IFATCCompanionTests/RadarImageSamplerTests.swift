import XCTest
import CoreLocation
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
}
