import Foundation
import CoreLocation

/// Pure aircraft-progress tracking against a calculated taxi route. No side effects —
/// the coordinator feeds it live telemetry each tick and acts on the result.
struct RouteTracker {

    /// Perpendicular offset (m) beyond which the aircraft is considered off-route.
    static let offRouteMeters = 35.0
    /// Distance (m) within which the destination (runway hold / gate) is "reached".
    static let destinationReachedMeters = 30.0

    struct Progress: Equatable {
        /// Distance traveled along the route (m) to the aircraft's projected position.
        var alongMeters: Double
        var remainingMeters: Double
        /// Perpendicular offset from the route centerline (m).
        var crossTrackMeters: Double
        var onRoute: Bool
        /// Index into `route.crossings` of the next crossing ahead (nil = none).
        var nextCrossingIndex: Int?
        /// Distance (m) to the next crossing's centerline, when one is ahead.
        var distanceToNextCrossingMeters: Double?
        /// Distance (m) to the next crossing's hold-short point, when one is ahead.
        var distanceToNextHoldMeters: Double?
        var reachedDestination: Bool
        /// The projected point on the route (for the map marker snap).
        var projectedPoint: GeoCoordinate
    }

    /// Compute progress for an aircraft position against a route. `minAlong` prevents
    /// the projection from snapping backwards onto an earlier, geometrically-near part
    /// of the route (e.g. parallel taxiways) — pass the last known along-distance.
    func progress(aircraft: CLLocationCoordinate2D, route: SurfaceTaxiRoute, minAlong: Double = 0) -> Progress {
        let line = route.clGeometry
        guard line.count >= 2 else {
            return Progress(alongMeters: 0, remainingMeters: route.distanceMeters,
                            crossTrackMeters: 0, onRoute: true, nextCrossingIndex: nil,
                            distanceToNextCrossingMeters: nil, distanceToNextHoldMeters: nil,
                            reachedDestination: false, projectedPoint: route.startCoordinate)
        }

        // Nearest point, but never allow a large backward jump past minAlong.
        let nearest = SurfaceGeometry.nearestPointOnPath(aircraft, line)
        var along = nearest?.alongMeters ?? 0
        var cross = nearest?.distanceMeters ?? 0
        var projected = nearest?.point ?? line[0]
        if along < minAlong - 5 {
            // Re-project onto the forward portion only.
            if let forward = forwardProjection(aircraft: aircraft, line: line, fromAlong: minAlong) {
                along = forward.along; cross = forward.cross; projected = forward.point
            }
        }

        let remaining = max(0, route.distanceMeters - along)
        let onRoute = cross <= Self.offRouteMeters
        let reached = remaining <= Self.destinationReachedMeters

        // Next crossing ahead (a little tolerance so one just underfoot still counts).
        var nextIdx: Int?
        var toCrossing: Double?
        var toHold: Double?
        for c in route.crossings where c.alongMeters > along - 8 {
            nextIdx = c.index
            toCrossing = max(0, c.alongMeters - along)
            let holdAlong = SurfaceGeometry.nearestPointOnPath(c.holdShortPoint.clLocation, line)?.alongMeters ?? c.alongMeters
            toHold = max(0, holdAlong - along)
            break
        }

        return Progress(alongMeters: along, remainingMeters: remaining, crossTrackMeters: cross,
                        onRoute: onRoute, nextCrossingIndex: nextIdx,
                        distanceToNextCrossingMeters: toCrossing, distanceToNextHoldMeters: toHold,
                        reachedDestination: reached, projectedPoint: GeoCoordinate(projected))
    }

    /// Project onto the polyline considering only the portion at/after `fromAlong`.
    private func forwardProjection(aircraft: CLLocationCoordinate2D, line: [CLLocationCoordinate2D],
                                   fromAlong: Double) -> (along: Double, cross: Double, point: CLLocationCoordinate2D)? {
        guard line.count >= 2 else { return nil }
        var cumulative = 0.0
        var best: (Double, Double, CLLocationCoordinate2D)?
        for i in 1..<line.count {
            let segStart = cumulative
            let seg = SurfaceGeometry.nearestPointOnSegment(aircraft, line[i - 1], line[i])
            let along = segStart + SurfaceGeometry.distanceMeters(line[i - 1], seg.point)
            if along >= fromAlong - 5 {
                if best == nil || seg.distanceMeters < best!.1 {
                    best = (along, seg.distanceMeters, seg.point)
                }
            }
            cumulative += SurfaceGeometry.distanceMeters(line[i - 1], line[i])
        }
        return best.map { (along: $0.0, cross: $0.1, point: $0.2) }
    }
}
