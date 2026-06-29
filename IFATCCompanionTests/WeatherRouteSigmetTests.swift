import XCTest
import CoreLocation
@testable import IFATCCompanion

/// The ride assessment must only be raised by SIGMETs whose advisory area actually
/// lies along the route — the nationwide AIR/SIGMET feed otherwise made a distant
/// turbulence advisory read as "severe" on every flight.
final class WeatherRouteSigmetTests: XCTestCase {

    private let analyzer = WeatherRouteAnalyzer()

    // KIAH → KMSP, roughly a south-to-north line up the middle of the US.
    private let origin = CLLocationCoordinate2D(latitude: 29.98, longitude: -95.34)
    private let dest = CLLocationCoordinate2D(latitude: 44.88, longitude: -93.22)

    /// A small box polygon centered on `center` (±`half` degrees).
    private func box(_ center: CLLocationCoordinate2D, half: Double = 0.5) -> [CLLocationCoordinate2D] {
        [CLLocationCoordinate2D(latitude: center.latitude - half, longitude: center.longitude - half),
         CLLocationCoordinate2D(latitude: center.latitude - half, longitude: center.longitude + half),
         CLLocationCoordinate2D(latitude: center.latitude + half, longitude: center.longitude + half),
         CLLocationCoordinate2D(latitude: center.latitude + half, longitude: center.longitude - half)]
    }

    private func sigmet(_ area: [CLLocationCoordinate2D], hazard: String = "TURB") -> SIGMET {
        SIGMET(raw: "\(hazard) SIGMET", hazard: hazard, severity: nil, area: area)
    }

    func testOnRouteSigmetIsKept() {
        // Box near the route midpoint (~37.4N, 94.3W).
        let onRoute = sigmet(box(CLLocationCoordinate2D(latitude: 37.4, longitude: -94.3)))
        let kept = analyzer.relevantSigmets([onRoute], position: origin, routeEnd: dest)
        XCTAssertEqual(kept.count, 1)
    }

    func testOffRouteSigmetIsDropped() {
        // Box over the US west coast — far from the KIAH→KMSP corridor.
        let offRoute = sigmet(box(CLLocationCoordinate2D(latitude: 37.0, longitude: -120.0)))
        let kept = analyzer.relevantSigmets([offRoute], position: origin, routeEnd: dest)
        XCTAssertTrue(kept.isEmpty)
    }

    func testGeometrylessSigmetIsDropped() {
        let kept = analyzer.relevantSigmets([sigmet([])], position: origin, routeEnd: dest)
        XCTAssertTrue(kept.isEmpty, "an advisory with no area can't be placed on the route")
    }

    func testSigmetContainingPositionIsKept() {
        let around = sigmet(box(origin, half: 1.0))
        let kept = analyzer.relevantSigmets([around], position: origin, routeEnd: dest)
        XCTAssertEqual(kept.count, 1)
    }

    func testOffRouteSigmetDoesNotRaiseRideIndex() {
        let model = TurbulenceModel()
        let offRoute = sigmet(box(CLLocationCoordinate2D(latitude: 37.0, longitude: -120.0)))
        let kept = analyzer.relevantSigmets([offRoute], position: origin, routeEnd: dest)
        let assessment = model.assess(items: [], sigmets: kept, metar: nil, altitudeFt: 35000)
        XCTAssertEqual(assessment.severity, .smooth,
                       "an off-route turbulence SIGMET must not drive the ride to severe")
    }

    func testPointInPolygon() {
        let square = box(CLLocationCoordinate2D(latitude: 40, longitude: -90), half: 1.0)
        XCTAssertTrue(WeatherRouteAnalyzer.pointInPolygon(
            CLLocationCoordinate2D(latitude: 40, longitude: -90), square))
        XCTAssertFalse(WeatherRouteAnalyzer.pointInPolygon(
            CLLocationCoordinate2D(latitude: 50, longitude: -90), square))
    }
}
