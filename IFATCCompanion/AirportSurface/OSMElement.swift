import Foundation
import CoreLocation

/// Raw OpenStreetMap elements as returned by the Overpass API `out geom;` form.
///
/// Overpass returns a JSON document with a top-level `elements` array. Each element
/// is a node, way, or relation carrying its OSM `id`, `tags`, and — for ways queried
/// with `out geom;` — an inline `geometry` list so the app does not have to resolve
/// node references separately. The original OSM identifiers and tags are preserved
/// verbatim; normalization never discards them (required for ODbL traceability and
/// for the Airport Surface Diagnostics).
struct OverpassResponse: Codable, Equatable {
    var elements: [OSMElement] = []
    /// Overpass echoes the query cost/timestamp in `osm3s`; kept only for diagnostics.
    var generator: String?
}

/// A single OSM element (node / way / relation) from an Overpass extract.
struct OSMElement: Codable, Equatable, Identifiable {
    enum Kind: String, Codable {
        case node
        case way
        case relation
    }

    var type: Kind
    /// OSM element id — preserved through normalization for traceability.
    var id: Int
    /// Node coordinate (nodes only).
    var lat: Double?
    var lon: Double?
    /// OSM tags (e.g. `aeroway=taxiway`, `ref=A`, `name=Alpha`). Preserved verbatim.
    var tags: [String: String]?
    /// Referenced node ids (ways/relations without inline geometry).
    var nodes: [Int]?
    /// Inline way geometry, present when queried with `out geom;`.
    var geometry: [OSMGeoPoint]?

    /// A stable, type-qualified identifier ("way/12345") so nodes and ways with the
    /// same numeric id never collide in dictionaries or the graph.
    var stableID: String { "\(type.rawValue)/\(id)" }

    var tag: (String) -> String? { { key in tags?[key] } }

    /// Node coordinate, when this element is a located node.
    var coordinate: CLLocationCoordinate2D? {
        guard let lat, let lon else { return nil }
        let c = CLLocationCoordinate2D(latitude: lat, longitude: lon)
        return c.isValid ? c : nil
    }

    /// Way geometry as CoreLocation coordinates (empty for nodes or geometry-less ways).
    var polyline: [CLLocationCoordinate2D] {
        (geometry ?? []).map { $0.clLocation }.filter { $0.isValid }
    }

    /// Value of `aeroway`, the primary airport-surface classifier.
    var aeroway: String? { tags?["aeroway"] }

    /// Preferred human name/reference for a taxiway or runway: `ref` first (the
    /// letter/number controllers use), then `name`. Empty when neither is tagged.
    var refOrName: String {
        (tags?["ref"] ?? tags?["name"] ?? "").trimmingCharacters(in: .whitespaces)
    }
}

/// A single geometry vertex from an Overpass `out geom;` way.
struct OSMGeoPoint: Codable, Equatable {
    var lat: Double
    var lon: Double

    var clLocation: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }
}
