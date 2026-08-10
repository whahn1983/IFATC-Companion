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

    // MARK: - Environment wind states

    /// The sim's own wind states resolve — and the steady wind is never read off the **gust**
    /// state sitting next to it in the same group.
    func testEnvironmentWindStatesResolveAndNeverMatchTheGust() {
        let entries = IFManifestParser.parse("""
        901,2,environment/turbulence_factor
        902,2,environment/temperature
        903,2,environment/wind_gust_velocity
        904,2,environment/wind_velocity
        905,2,environment/wind_direction_true
        906,2,environment/surface_temperature
        """)
        let store = IFStateMappingStore()
        store.resolve(from: entries)

        XCTAssertEqual(store.entry(for: .windVelocity)?.name, "environment/wind_velocity")
        XCTAssertEqual(store.entry(for: .windDirectionTrue)?.name, "environment/wind_direction_true")
        XCTAssertNotEqual(store.entry(for: .windVelocity)?.name, "environment/wind_gust_velocity",
                          "the steady wind must not resolve onto the gust state")
    }

    /// A version that exposes neither leaves both unresolved, so the reader simply reports no
    /// sim wind and the solved wind triangle carries on as before.
    func testWindStatesUnresolvedWhenAbsent() {
        let entries = IFManifestParser.parse("746,3,aircraft/0/latitude")
        let store = IFStateMappingStore()
        store.resolve(from: entries)

        XCTAssertNil(store.entry(for: .windVelocity))
        XCTAssertNil(store.entry(for: .windDirectionTrue))
    }

    /// The reported-vs-solved delta is what settles whether the sim's `wind_direction_true`
    /// is the meteorological "from" or the direction the wind blows "toward" — the two differ
    /// by exactly 180°, and the state name doesn't say which.
    func testReportedWindDeltaNamesTheConvention() {
        var d = WeatherProviderDiagnostics.empty
        d.solvedWindFromDegrees = 220
        d.reportedWindKnots = 14

        d.reportedWindDirectionTrue = 221
        XCTAssertEqual(d.reportedWindDeltaText, "1° — sim reports “from”")

        d.reportedWindDirectionTrue = 41
        XCTAssertEqual(d.reportedWindDeltaText, "-179° — sim reports “toward”")

        // Neither: the two disagree in a way no convention explains.
        d.reportedWindDirectionTrue = 310
        XCTAssertEqual(d.reportedWindDeltaText, "90° — inconsistent")

        // Nothing to compare against until the triangle has solved.
        d.solvedWindFromDegrees = nil
        XCTAssertNil(d.reportedWindDeltaText)
    }

    // MARK: - Heading units (radians vs degrees)

    /// Radians are converted; degrees are taken at face value. The units are settled once
    /// per state snapshot, because a single reading can't tell them apart.
    func testHeadingAnglesAreNormalizedByTheSnapshotsUnits() {
        // Radians in: π/2 is 090°, and 4 rad is 229°.
        XCTAssertEqual(IFConnectStateReader.normalizeAngle(.pi / 2, alreadyDegrees: false), 90, accuracy: 0.001)
        XCTAssertEqual(IFConnectStateReader.normalizeAngle(4, alreadyDegrees: false), 229.183, accuracy: 0.01)
        // Degrees in: unchanged, and wrapped to 0–360.
        XCTAssertEqual(IFConnectStateReader.normalizeAngle(4, alreadyDegrees: true), 4, accuracy: 0.001)
        XCTAssertEqual(IFConnectStateReader.normalizeAngle(-10, alreadyDegrees: true), 350, accuracy: 0.001)
    }

    /// Regression: judging each value on its own mangles a heading near north on a build that
    /// reports degrees — 004° and 4 rad are the same number on the wire, and the old per-value
    /// guess read every heading below ~6° as radians, turning 004° into 229°. That fed the
    /// wind triangle (`HeadingSolver.wind`) and every weather vector solved from it. The
    /// snapshot decides: any one angle too large to be radians makes them all degrees.
    func testNearNorthHeadingIsNotMistakenForRadiansWhenTheSnapshotIsInDegrees() {
        // A snapshot in degrees: nose on 004°, tracking 007°, magnetic 003°.
        let snapshot: [Double] = [4, 7, 3]
        let inDegrees = snapshot.contains { IFConnectStateReader.exceedsFullCircleInRadians($0) }
        XCTAssertTrue(inDegrees, "a reading of 7 cannot be radians, so the snapshot is in degrees")
        XCTAssertEqual(IFConnectStateReader.normalizeAngle(4, alreadyDegrees: inDegrees), 4, accuracy: 0.001)

        // The same snapshot in radians stays radians and converts.
        let radians: [Double] = [0.07, 0.12, 0.05]
        let radiansInDegrees = radians.contains { IFConnectStateReader.exceedsFullCircleInRadians($0) }
        XCTAssertFalse(radiansInDegrees)
        XCTAssertEqual(IFConnectStateReader.normalizeAngle(0.07, alreadyDegrees: radiansInDegrees),
                       4.011, accuracy: 0.01)
    }
}
