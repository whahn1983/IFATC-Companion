import Foundation
import CoreLocation

/// Lightweight geospatial helpers used across weather analysis and flight logic.
/// Deterministic, dependency-free great-circle math (no external services).
enum Geo {

    static let earthRadiusNM = 3440.065

    /// Great-circle distance in nautical miles between two coordinates.
    static func distanceNM(from: CLLocationCoordinate2D, to: CLLocationCoordinate2D) -> Double {
        let lat1 = from.latitude * .pi / 180
        let lat2 = to.latitude * .pi / 180
        let dLat = (to.latitude - from.latitude) * .pi / 180
        let dLon = (to.longitude - from.longitude) * .pi / 180
        let a = sin(dLat / 2) * sin(dLat / 2)
            + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        let c = 2 * atan2(sqrt(a), sqrt(max(0, 1 - a)))
        return earthRadiusNM * c
    }

    /// Initial bearing (degrees true, 0–360) from one coordinate to another.
    static func bearing(from: CLLocationCoordinate2D, to: CLLocationCoordinate2D) -> Double {
        let lat1 = from.latitude * .pi / 180
        let lat2 = to.latitude * .pi / 180
        let dLon = (to.longitude - from.longitude) * .pi / 180
        let y = sin(dLon) * cos(lat2)
        let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        let brng = atan2(y, x) * 180 / .pi
        return (brng + 360).truncatingRemainder(dividingBy: 360)
    }

    /// Smallest absolute angular difference between two headings (0–180).
    static func headingDifference(_ a: Double, _ b: Double) -> Double {
        var diff = abs(a - b).truncatingRemainder(dividingBy: 360)
        if diff > 180 { diff = 360 - diff }
        return diff
    }

    /// Cross-track distance (NM) of a point from the great-circle path start->end.
    /// Positive/negative sign indicates side; callers typically use the magnitude.
    static func crossTrackDistanceNM(point: CLLocationCoordinate2D,
                                     pathStart: CLLocationCoordinate2D,
                                     pathEnd: CLLocationCoordinate2D) -> Double {
        let d13 = distanceNM(from: pathStart, to: point) / earthRadiusNM
        let brng13 = bearing(from: pathStart, to: point) * .pi / 180
        let brng12 = bearing(from: pathStart, to: pathEnd) * .pi / 180
        let xt = asin(sin(d13) * sin(brng13 - brng12))
        return xt * earthRadiusNM
    }

    /// Convert a compass bearing into a coarse clock/cardinal description.
    static func cardinal(_ bearing: Double) -> String {
        let dirs = ["north", "north-east", "east", "south-east",
                    "south", "south-west", "west", "north-west"]
        let idx = Int((bearing + 22.5).truncatingRemainder(dividingBy: 360) / 45)
        return dirs[max(0, min(dirs.count - 1, idx))]
    }
}

extension CLLocationCoordinate2D {
    var isValid: Bool {
        CLLocationCoordinate2DIsValid(self) && !(latitude == 0 && longitude == 0)
    }
}
