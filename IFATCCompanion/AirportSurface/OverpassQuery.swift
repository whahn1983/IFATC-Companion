import Foundation
import CoreLocation

/// A geographic bounding box (WGS84 degrees), Overpass order-friendly. Used to size
/// an airport-specific extract and stored in the cache metadata so a cached extract's
/// coverage is auditable.
struct OSMBoundingBox: Codable, Equatable {
    var south: Double
    var west: Double
    var north: Double
    var east: Double

    /// A square-ish box of `halfSpan` degrees latitude around a center, widened in
    /// longitude by the local cos(lat) so the ground footprint is roughly square.
    init(center: CLLocationCoordinate2D, halfSpanDegrees halfSpan: Double) {
        let cosLat = max(0.2, cos(center.latitude * .pi / 180))
        let lonHalf = halfSpan / cosLat
        south = center.latitude - halfSpan
        north = center.latitude + halfSpan
        west = center.longitude - lonHalf
        east = center.longitude + lonHalf
    }

    /// Whether a coordinate lies inside the box.
    func contains(_ c: CLLocationCoordinate2D) -> Bool {
        c.latitude >= south && c.latitude <= north && c.longitude >= west && c.longitude <= east
    }

    /// Overpass bbox clause order: south,west,north,east.
    var overpassClause: String {
        String(format: "%.6f,%.6f,%.6f,%.6f", south, west, north, east)
    }
}

/// Builds the Overpass QL request for a single airport's movement surface.
///
/// Only the airport area is requested (never a region or the whole planet). Two feature
/// families are pulled: `aeroway`-tagged movement surfaces — runways, taxiways,
/// taxilanes, holding positions, parking positions, gates, aprons, terminals — and
/// `building` footprints. The buildings/terminals are not routable; they are used to keep
/// synthesized gate lead-ins from being drawn straight through a concourse to a stand on
/// the far side. `out geom tags;` returns inline way geometry and all tags in one
/// round-trip, so the app never has to resolve node references or make a second call
/// during taxi.
struct OverpassQuery {
    let icao: String
    let center: CLLocationCoordinate2D
    let halfSpanDegrees: Double

    init(icao: String, center: CLLocationCoordinate2D,
         halfSpanDegrees: Double = OSMSurface.bboxHalfSpanDegrees) {
        self.icao = icao.uppercased()
        self.center = center
        self.halfSpanDegrees = halfSpanDegrees
    }

    var boundingBox: OSMBoundingBox {
        OSMBoundingBox(center: center, halfSpanDegrees: halfSpanDegrees)
    }

    /// The Overpass QL query text. Small, airport-scoped, JSON output.
    var queryText: String {
        let box = boundingBox.overpassClause
        return """
        [out:json][timeout:30];
        (
          way["aeroway"](\(box));
          node["aeroway"](\(box));
          relation["aeroway"](\(box));
          way["building"](\(box));
          relation["building"](\(box));
        );
        out geom tags qt;
        """
    }

    /// URL-form body ("data=<query>") posted to the Overpass interpreter.
    var httpBody: Data? {
        var components = URLComponents()
        components.queryItems = [URLQueryItem(name: "data", value: queryText)]
        return components.percentEncodedQuery?.data(using: .utf8)
    }
}
