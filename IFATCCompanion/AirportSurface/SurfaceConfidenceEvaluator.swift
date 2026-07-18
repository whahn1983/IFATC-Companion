import Foundation

/// Grades an airport **dataset's** confidence by combining normalized-feature quality
/// with the connectivity of the derived surface graph. (Route confidence is graded
/// separately by `TaxiRouteEngine`, which also factors the aircraft snap and crossings.)
///
/// High confidence requires connected taxiway geometry, taxiway names/references, clear
/// runway geometry, and reliable holding positions. Medium tolerates some inferred
/// holds / missing names. Low means disconnected or largely unnamed geometry.
/// Unavailable means there is not enough to route on at all.
enum SurfaceConfidenceEvaluator {

    static func datasetConfidence(model: AirportSurfaceModel, graph: SurfaceGraph) -> SurfaceConfidence {
        guard model.hasUsableGeometry, !graph.edges.isEmpty else { return .unavailable }

        let namedFraction = graph.namedEdgeFraction
        let connected = graph.componentCount <= 1
        let hasMappedHolds = model.holdingPositions.contains { !$0.inferred }
        let hasRunways = !model.runways.isEmpty && !model.runwayEnds.isEmpty
        let hasParking = !model.parkingPositions.isEmpty

        if namedFraction >= 0.6 && connected && hasMappedHolds && hasRunways {
            return .high
        }
        if (namedFraction >= 0.3 || hasMappedHolds) && hasRunways && (connected || hasParking) {
            return .medium
        }
        return .low
    }
}
