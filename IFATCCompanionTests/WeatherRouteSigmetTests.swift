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

    func testNearButNotThroughSigmetIsDropped() {
        // A box east of the KIAH→KMSP corridor: close enough that the old proximity
        // buffer kept it, but the route never actually enters the area. A SIGMET
        // covers a wide region, so only a genuine pass-through makes it applicable.
        let nearRoute = sigmet(box(CLLocationCoordinate2D(latitude: 37.4, longitude: -92.5)))
        let kept = analyzer.relevantSigmets([nearRoute], position: origin, routeEnd: dest)
        XCTAssertTrue(kept.isEmpty, "a SIGMET the route passes near but not through is not applicable")
    }

    func testRouteCrossingSigmetWithVerticesOffRouteIsKept() {
        // A wide box the route passes straight through, whose corners are all far
        // from the route line — an edge-crossing test catches it, a vertex-proximity
        // test would not.
        let wide = box(CLLocationCoordinate2D(latitude: 37.4, longitude: -94.3), half: 3.0)
        let kept = analyzer.relevantSigmets([sigmet(wide)], position: origin, routeEnd: dest)
        XCTAssertEqual(kept.count, 1, "the route crosses the area, so it is applicable")
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

    func testDegenerateGeometrySigmetIsDropped() {
        // A convective advisory whose "area" is only two points can't be drawn as a
        // polygon on the map — and so must not silently drive the ride index either.
        let line = [origin, dest]
        let kept = analyzer.relevantSigmets([sigmet(line, hazard: "CONVECTIVE")],
                                            position: origin, routeEnd: dest)
        XCTAssertTrue(kept.isEmpty, "a <3-point advisory has no drawable area")
    }

    func testOnRouteSigmetHasDrawableArea() {
        let onRoute = sigmet(box(CLLocationCoordinate2D(latitude: 37.4, longitude: -94.3)),
                             hazard: "CONVECTIVE")
        let kept = analyzer.relevantSigmets([onRoute], position: origin, routeEnd: dest)
        XCTAssertEqual(kept.count, 1)
        XCTAssertNotNil(kept.first?.drawableArea, "a kept advisory must be placeable on the map")
    }

    func testSigmetSeverityMapping() {
        XCTAssertEqual(sigmet([], hazard: "CONVECTIVE").turbulenceSeverity, .severe)
        XCTAssertEqual(sigmet([], hazard: "TURB").turbulenceSeverity, .moderate)
        let severeTurb = SIGMET(raw: "SEV TURB", hazard: "TURB", severity: "SEV", area: [])
        XCTAssertEqual(severeTurb.turbulenceSeverity, .severe,
                       "a severe-turbulence SIGMET must color and score as severe, not moderate")
        XCTAssertEqual(sigmet([], hazard: "ICE").turbulenceSeverity, .light)
    }

    func testSevereTurbSigmetDrivesSevereRide() {
        let model = TurbulenceModel()
        let onRoute = box(CLLocationCoordinate2D(latitude: 37.4, longitude: -94.3))
        let severeTurb = SIGMET(raw: "SEV TURB", hazard: "TURB", severity: "SEV", area: onRoute)
        let kept = analyzer.relevantSigmets([severeTurb], position: origin, routeEnd: dest)
        let assessment = model.assess(items: [], sigmets: kept, metar: nil, altitudeFt: 35000)
        XCTAssertEqual(assessment.severity, .severe)
    }

    func testLowSeveritySigmetIsStillRouteRelevant() {
        // An IFR advisory (maps to .smooth, doesn't raise the ride index) that the route
        // crosses is still returned — the map shows all route SIGMETs, not only rough
        // ones, now that SIGMETs don't drive a deviation.
        let ifr = sigmet(box(CLLocationCoordinate2D(latitude: 37.4, longitude: -94.3)), hazard: "IFR")
        XCTAssertEqual(ifr.turbulenceSeverity, .smooth)
        let kept = analyzer.relevantSigmets([ifr], position: origin, routeEnd: dest)
        XCTAssertEqual(kept.count, 1, "a low-severity on-route advisory is still route-relevant")
    }

    func testSigmetOnALaterLegIsCaughtByTheFullRoute() {
        // The route turns: east to a fix, then north. A box sitting on the northern leg
        // is missed by the straight aircraft→destination line but caught when the whole
        // route polyline is tested — "along the entire route."
        let f1 = CLLocationCoordinate2D(latitude: origin.latitude, longitude: origin.longitude + 3) // east
        let f2 = CLLocationCoordinate2D(latitude: origin.latitude + 4, longitude: f1.longitude)     // then north
        let onLeg = sigmet(box(CLLocationCoordinate2D(latitude: origin.latitude + 2, longitude: f1.longitude)))

        let straight = analyzer.relevantSigmets([onLeg], position: origin, routeEnd: f2)
        XCTAssertTrue(straight.isEmpty, "the straight line to the destination misses the later-leg advisory")

        let full = analyzer.relevantSigmets([onLeg], routePolyline: [origin, f1, f2])
        XCTAssertEqual(full.count, 1, "the full route polyline catches an advisory on a later leg")
    }

    func testPointInPolygon() {
        let square = box(CLLocationCoordinate2D(latitude: 40, longitude: -90), half: 1.0)
        XCTAssertTrue(WeatherRouteAnalyzer.pointInPolygon(
            CLLocationCoordinate2D(latitude: 40, longitude: -90), square))
        XCTAssertFalse(WeatherRouteAnalyzer.pointInPolygon(
            CLLocationCoordinate2D(latitude: 50, longitude: -90), square))
    }
}
