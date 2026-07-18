import Foundation
import CoreLocation

/// A runway crossing along a calculated taxi route.
struct RouteCrossing: Identifiable, Equatable {
    /// 0-based order along the route.
    var index: Int
    /// Phraseology ident of the runway being crossed ("16L").
    var runwayIdent: String
    /// Display name of the crossed runway ("16L/34R").
    var runwayName: String
    /// The runway-centerline crossing point.
    var point: GeoCoordinate
    /// A hold-short point a short distance before the crossing along the route.
    var holdShortPoint: GeoCoordinate
    /// Along-route distance (meters) from the route start to the crossing.
    var alongMeters: Double
    /// The graph edge carrying the crossing.
    var edgeID: Int
    /// Confidence in this specific crossing's geometry.
    var confidence: SurfaceConfidence

    var id: Int { index }
}

/// A best-effort calculated taxi route over the airport surface graph.
struct SurfaceTaxiRoute: Equatable {
    var isDeparture: Bool
    var nodeIDs: [Int]
    var edgeIDs: [Int]
    /// Full route polyline for rendering / progress tracking.
    var geometry: [GeoCoordinate]
    var distanceMeters: Double
    /// Ordered, de-duplicated named taxiway sequence spoken to the pilot.
    var taxiwaySequence: [String]
    var crossings: [RouteCrossing]
    var confidence: SurfaceConfidence
    var confidenceScore: Double
    /// "runway 16L" (departure) or "gate B44" / "parking" (arrival).
    var destinationLabel: String
    /// Assigned runway (departure) — the hold-short runway at the end of the route.
    var holdShortRunway: String?
    /// Gate/parking name (arrival).
    var arrivalGate: String?
    var startCoordinate: GeoCoordinate
    var endCoordinate: GeoCoordinate
    /// Whether any inferred connector was used mid-route (not at the gate endpoints).
    var usedInferredConnectorMidRoute: Bool
    var unnamedSegmentCount: Int
    var notes: [String]

    var clGeometry: [CLLocationCoordinate2D] { geometry.clLocations }
    var distanceNM: Double { distanceMeters / SurfaceGeometry.metersPerNM }

    /// The taxiway sequence rendered for phraseology/display ("A, C, B").
    var taxiwaysText: String {
        taxiwaySequence.isEmpty ? "available taxiways" : taxiwaySequence.joined(separator: ", ")
    }
}
