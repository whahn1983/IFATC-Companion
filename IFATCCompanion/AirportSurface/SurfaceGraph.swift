import Foundation
import CoreLocation

/// The kind of point a graph node represents.
enum SurfaceNodeKind: String {
    case taxiwayEndpoint
    case intersection
    case holdingPosition
    case runwayEntry
    case runwayCrossing
    case gate
    case parking
    case apronConnector
}

/// A node in the airport surface graph.
struct SurfaceNode: Identifiable, Equatable {
    let id: Int
    var coordinate: GeoCoordinate
    var kind: SurfaceNodeKind
    /// Runway ident this node serves (holding position / runway entry / crossing).
    var runwayRef: String?
    /// Gate / parking / taxiway name where applicable.
    var name: String?
    /// Original OSM feature id, when the node came from a mapped feature.
    var osmID: String?
    var inferred: Bool

    var clLocation: CLLocationCoordinate2D { coordinate.clLocation }
}

/// An edge in the airport surface graph — a routable segment of taxiway/taxilane
/// geometry between two nodes. Tracks everything routing and phraseology need.
struct SurfaceEdge: Identifiable, Equatable {
    let id: Int
    var from: Int
    var to: Int
    var geometry: [GeoCoordinate]
    var distanceMeters: Double
    /// Taxiway `ref`/name ("A", "Alpha"), or "" when unnamed.
    var taxiwayName: String
    var hasName: Bool
    var isTaxilane: Bool
    /// Ident of a runway crossed mid-edge (phraseology form, e.g. "16L"), else nil.
    var runwayCrossing: String?
    /// Display name of the crossed runway ("16L/34R"), else nil.
    var runwayCrossingName: String?
    /// Location of the runway-centerline crossing point, when `runwayCrossing != nil`.
    var crossingPoint: GeoCoordinate?
    /// Whether traversing this edge means occupying/entering a runway surface.
    var runwayOccupancy: Bool
    /// `oneway`: traversable only from→to.
    var oneway: Bool
    /// Non-operational (closed) segment.
    var closed: Bool
    /// Inferred connector (gate lead-in, apron connector) rather than mapped geometry.
    var inferred: Bool
    /// Per-edge confidence 0…1 (names/closed/inferred lower it).
    var confidence: Double
    /// Original OSM feature ids that contributed to this edge.
    var osmIDs: [String]
    var widthMeters: Double?

    var clGeometry: [CLLocationCoordinate2D] { geometry.clLocations }
}

/// The connected airport surface graph derived from an `AirportSurfaceModel`.
struct SurfaceGraph {
    var nodes: [SurfaceNode]
    var edges: [SurfaceEdge]
    /// nodeID → indices into `edges` incident on that node.
    var adjacency: [Int: [Int]]
    /// Number of disconnected components (1 = fully connected).
    var componentCount: Int
    /// Whether any inferred connectors were added (gate lead-ins, etc.).
    var inferredConnectorCount: Int

    func node(_ id: Int) -> SurfaceNode? { nodes.first { $0.id == id } }

    func edgesIncident(to nodeID: Int) -> [SurfaceEdge] {
        (adjacency[nodeID] ?? []).compactMap { idx in edges.indices.contains(idx) ? edges[idx] : nil }
    }

    /// Nearest node to a coordinate (used to snap the aircraft/gate/runway onto the graph).
    func nearestNode(to coord: CLLocationCoordinate2D, maxMeters: Double = .greatestFiniteMagnitude) -> (node: SurfaceNode, distanceMeters: Double)? {
        var best: (SurfaceNode, Double)?
        for n in nodes {
            let d = SurfaceGeometry.distanceMeters(coord, n.clLocation)
            if d <= maxMeters, best == nil || d < best!.1 { best = (n, d) }
        }
        return best.map { (node: $0.0, distanceMeters: $0.1) }
    }

    /// Nearest node of a given kind matching an optional runway ident.
    func nearestNode(kind: SurfaceNodeKind, runwayRef: String? = nil,
                     to coord: CLLocationCoordinate2D) -> SurfaceNode? {
        let candidates = nodes.filter { n in
            guard n.kind == kind else { return false }
            if let runwayRef, let nodeRef = n.runwayRef {
                return nodeRef.uppercased() == runwayRef.uppercased()
            }
            return runwayRef == nil
        }
        return candidates.min { SurfaceGeometry.distanceMeters(coord, $0.clLocation) < SurfaceGeometry.distanceMeters(coord, $1.clLocation) }
    }

    var runwayCrossingEdges: [SurfaceEdge] { edges.filter { $0.runwayCrossing != nil } }
    var namedEdgeFraction: Double {
        let routable = edges.filter { !$0.inferred }
        guard !routable.isEmpty else { return 0 }
        return Double(routable.filter { $0.hasName }.count) / Double(routable.count)
    }
}
