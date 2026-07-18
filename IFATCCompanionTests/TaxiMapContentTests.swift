import XCTest
import CoreLocation
@testable import IFATCCompanion

/// The taxi map's on-map content selection. A dense field (e.g. KJFK) has hundreds of OSM
/// stands and dozens of hold points; MapKit for SwiftUI builds a hosting view per
/// `Annotation`, so drawing the full set at the fit-to-route zoom overwhelmed the map and
/// crashed the app. The map now draws only the single gate that matters — the departure
/// gate on taxi out (`AirportSurfaceModel.nearestParking`), the arrival gate being the
/// route destination — and caps holding positions to those nearest the route
/// (`TaxiMapContent.nearestToRoute`).
final class TaxiMapContentTests: XCTestCase {

    /// A short east–west route; candidates offset in latitude sit at a known perpendicular
    /// distance from it.
    private let route = [CLLocationCoordinate2D(latitude: 40, longitude: -75.0),
                         CLLocationCoordinate2D(latitude: 40, longitude: -74.99)]

    // MARK: nearestToRoute (holding-position cap)

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

    func testBoundsRealHoldingPositions() {
        // Exercise the closure over the real model type the view passes in.
        let holds = (0..<80).map {
            SurfaceHoldingPosition(osmID: "h\($0)", tags: [:],
                                   coordinate: GeoCoordinate(latitude: 40 + Double($0) * 0.0002, longitude: -74.995),
                                   runwayRef: "36", inferred: false)
        }
        let picked = TaxiMapContent.nearestToRoute(holds, route: route, limit: 16) { $0.coordinate.clLocation }
        XCTAssertEqual(picked.count, 16, "a dense field's hold points are bounded to the cap")
        XCTAssertEqual(picked.first?.osmID, "h0", "the hold on the route line is kept")
    }

    // MARK: nearestParking (departure gate)

    private func gateModel(_ stands: [SurfaceParking]) -> AirportSurfaceModel {
        let ref = CLLocationCoordinate2D(latitude: 40, longitude: -75)
        return AirportSurfaceModel(icao: "KGAT", reference: GeoCoordinate(ref),
                                   runways: [], runwayEnds: [], taxiways: [],
                                   holdingPositions: [], parkingPositions: stands, aprons: [],
                                   source: SurfaceProvenance(endpoint: "t", fetchDate: Date(),
                                                             boundingBox: OSMBoundingBox(center: ref, halfSpanDegrees: 0.04),
                                                             rawElementCount: stands.count),
                                   confidence: .low)
    }

    private func stand(_ name: String, _ lat: Double, _ lon: Double) -> SurfaceParking {
        SurfaceParking(osmID: "node/\(name)", tags: [:], kind: .gate, name: name,
                       coordinate: GeoCoordinate(latitude: lat, longitude: lon))
    }

    func testNearestParkingPicksClosestWithinRadius() {
        // A1 ~11 m from the query, B2 ~100 m, C3 ~1.1 km.
        let model = gateModel([stand("A1", 40.0000, -75.0),
                               stand("B2", 40.0010, -75.0),
                               stand("C3", 40.0100, -75.0)])
        let gate = model.nearestParking(to: CLLocationCoordinate2D(latitude: 40.0001, longitude: -75.0), within: 150)
        XCTAssertEqual(gate?.name, "A1", "the departure gate is the stand nearest the route start")
    }

    func testNearestParkingIsNilWhenNoneClose() {
        let model = gateModel([stand("C3", 40.0100, -75.0)])
        let gate = model.nearestParking(to: CLLocationCoordinate2D(latitude: 40.05, longitude: -75.0), within: 150)
        XCTAssertNil(gate, "no stand within the radius → no gate marker is drawn")
    }
}
