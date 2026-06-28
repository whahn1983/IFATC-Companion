import XCTest
@testable import IFATCCompanion

final class TurbulenceModelTests: XCTestCase {

    private let model = TurbulenceModel()

    private func item(_ severity: TurbulenceSeverity, distance: Double, age: Double? = nil) -> RideReportItem {
        RideReportItem(severity: severity, altitudeBand: nil, distanceAheadNM: distance,
                       bearing: nil, nearFix: nil, sourceRaw: "test", ageMinutes: age)
    }

    func testNoSignalsIsSmooth() {
        let a = model.assess(items: [], sigmets: [], metar: nil, altitudeFt: 35000)
        XCTAssertEqual(a.severity, .smooth)
        XCTAssertEqual(a.index, 0, accuracy: 0.0001)
    }

    func testCloseModeratePirepRaisesIndex() {
        let a = model.assess(items: [item(.moderate, distance: 0)], altitudeFt: 35000)
        XCTAssertGreaterThan(a.index, 0.5)
        XCTAssertTrue(a.contributors.contains("pilot reports"))
    }

    func testDistantReportIsWeightedDown() {
        let near = model.weightedScore(for: item(.moderate, distance: 0))
        let far = model.weightedScore(for: item(.moderate, distance: 240))
        XCTAssertGreaterThan(near, far)
    }

    func testOldReportIsWeightedDown() {
        let fresh = model.weightedScore(for: item(.severe, distance: 10, age: 0))
        let stale = model.weightedScore(for: item(.severe, distance: 10, age: 180))
        XCTAssertGreaterThan(fresh, stale)
    }

    func testConvectiveSigmetDominates() {
        let sigmet = SIGMET(raw: "CONVECTIVE SIGMET", hazard: "CONVECTIVE", severity: nil, area: [])
        let a = model.assess(items: [], sigmets: [sigmet], metar: nil, altitudeFt: 35000)
        XCTAssertGreaterThanOrEqual(a.severity.rawValue, TurbulenceSeverity.moderate.rawValue)
        XCTAssertTrue(a.contributors.contains("convective SIGMET"))
    }

    func testLowLevelWindShearAddsAtLowAltitude() {
        var metar = METAR(icao: "KMSP", raw: "")
        metar.windSpeed = 18
        metar.windGust = 30
        let low = model.assess(items: [], metar: metar, altitudeFt: 3000)
        let high = model.assess(items: [], metar: metar, altitudeFt: 35000)
        XCTAssertGreaterThan(low.index, high.index)
        XCTAssertTrue(low.contributors.contains("surface wind shear"))
    }

    func testSeverityThresholds() {
        XCTAssertEqual(model.severity(for: 0.0), .smooth)
        XCTAssertEqual(model.severity(for: 0.25), .lightChop)
        XCTAssertEqual(model.severity(for: 0.45), .light)
        XCTAssertEqual(model.severity(for: 0.7), .moderate)
        XCTAssertEqual(model.severity(for: 0.95), .severe)
    }
}
