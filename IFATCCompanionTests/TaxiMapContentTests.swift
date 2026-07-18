import XCTest
import CoreLocation
@testable import IFATCCompanion

/// The taxi map's context-annotation bounding. A dense field (e.g. KJFK) has hundreds of
/// OSM stands and dozens of hold points; MapKit for SwiftUI builds a hosting view per
/// `Annotation`, so drawing the full set at the fit-to-route zoom overwhelmed the map and
/// crashed the app. `TaxiMapContent.nearestToRoute` caps what is drawn to the features
/// nearest the assigned route.
final class TaxiMapContentTests: XCTestCase {

    /// A short east–west route; candidates offset in latitude sit at a known perpendicular
    /// distance from it.
    private let route = [CLLocationCoordinate2D(latitude: 40, longitude: -75.0),
                         CLLocationCoordinate2D(latitude: 40, longitude: -74.99)]

    func testCapsToLimitAndKeepsClosestToRoute() {
        let near     = CLLocationCoordinate2D(latitude: 40.0001, longitude: -74.995)
        let mid      = CLLocationCoordinate2D(latitude: 40.0003, longitude: -74.995)
        let far      = CLLocationCoordinate2D(latitude: 40.0007, longitude: -74.995)
        let farthest = CLLocationCoordinate2D(latitude: 40.0012, longitude: -74.995)

        let picked = TaxiMapContent.nearestToRoute([farthest, near, far, mid], route: route, limit: 2) { $0 }

        XCTAssertEqual(picked.count, 2, "capped to the limit")
        XCTAssertEqual(picked[0].latitude, near.latitude, accuracy: 1e-9, "closest first")
        XCTAssertEqual(picked[1].latitude, mid.latitude, accuracy: 1e-9, "then the next closest")
    }

    func testReturnsAllWhenAlreadyUnderLimit() {
        let items = [CLLocationCoordinate2D(latitude: 40.0001, longitude: -74.995),
                     CLLocationCoordinate2D(latitude: 40.0002, longitude: -74.995)]
        let out = TaxiMapContent.nearestToRoute(items, route: route, limit: 14) { $0 }
        XCTAssertEqual(out.count, 2, "no filtering needed when the set already fits")
    }

    func testStillBoundedWithoutAUsableRoute() {
        // A single-point (or empty) route can't measure distance — the result must still be
        // capped so the map is never flooded.
        let items = (0..<50).map { CLLocationCoordinate2D(latitude: 40 + Double($0) * 0.001, longitude: -75) }
        XCTAssertEqual(TaxiMapContent.nearestToRoute(items, route: [], limit: 14) { $0 }.count, 14)
        XCTAssertEqual(TaxiMapContent.nearestToRoute(items, route: [route[0]], limit: 14) { $0 }.count, 14)
    }

    func testZeroLimitDrawsNothing() {
        let items = [CLLocationCoordinate2D(latitude: 40, longitude: -75)]
        XCTAssertTrue(TaxiMapContent.nearestToRoute(items, route: route, limit: 0) { $0 }.isEmpty)
    }

    func testBoundsRealParkingFeatures() {
        // Exercise the closure over the real model type the view passes in.
        let stands = (0..<200).map {
            SurfaceParking(osmID: "p\($0)", tags: [:], kind: .gate, name: "G\($0)",
                           coordinate: GeoCoordinate(latitude: 40 + Double($0) * 0.0002, longitude: -74.995))
        }
        let picked = TaxiMapContent.nearestToRoute(stands, route: route, limit: 14) { $0.coordinate.clLocation }
        XCTAssertEqual(picked.count, 14, "200 OSM stands are bounded to the cap")
        XCTAssertEqual(picked.first?.osmID, "p0", "the stand on the route line is kept")
    }
}
