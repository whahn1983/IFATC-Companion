import Foundation
import CoreLocation

/// Filters weather reports to those relevant to the current route corridor and
/// altitude band, sorted by distance ahead. Pure, deterministic, unit-tested.
struct WeatherRouteAnalyzer {

    struct Config {
        var corridorNM: Double = 100
        var altitudeBandFt: Double = 5000
    }

    var config = Config()

    /// Returns relevant PIREPs as `RideReportItem`s ahead of the aircraft along
    /// the path toward `routeEnd`.
    func relevantReports(pireps: [PIREP],
                         position: CLLocationCoordinate2D,
                         routeEnd: CLLocationCoordinate2D?,
                         altitudeFt: Double,
                         nearestFix: String? = nil,
                         now: Date = Date()) -> [RideReportItem] {

        let courseTo = routeEnd.map { Geo.bearing(from: position, to: $0) }

        var items: [RideReportItem] = []
        for pirep in pireps {
            guard let severity = pirep.turbulence, severity > .smooth else { continue }
            guard let coord = pirep.coordinate, coord.isValid else { continue }

            // Altitude band filter (unknown altitude is included conservatively).
            if let alt = pirep.altitudeFt {
                if abs(Double(alt) - altitudeFt) > config.altitudeBandFt { continue }
            }

            let distance = Geo.distanceNM(from: position, to: coord)
            let bearingToPirep = Geo.bearing(from: position, to: coord)

            // Determine "ahead" relative to course (if we have one).
            var distanceAhead = distance
            if let course = courseTo {
                let angle = Geo.headingDifference(course, bearingToPirep) * .pi / 180
                let alongTrack = distance * cos(angle)
                let crossTrack = abs(distance * sin(angle))
                if alongTrack < -10 { continue }            // clearly behind
                if crossTrack > config.corridorNM { continue } // outside corridor
                distanceAhead = max(0, alongTrack)
            } else {
                if distance > config.corridorNM { continue }
            }

            let band: ClosedRange<Int>? = pirep.altitudeFt.map { alt in
                let lo = max(0, alt - 2000)
                let hi = alt + 2000
                return lo...hi
            }

            let age = pirep.time.map { max(0, now.timeIntervalSince($0) / 60) }
            items.append(RideReportItem(severity: severity,
                                        altitudeBand: band,
                                        distanceAheadNM: distanceAhead,
                                        bearing: bearingToPirep,
                                        nearFix: nearestFix,
                                        sourceRaw: pirep.raw,
                                        ageMinutes: age))
        }

        return items.sorted { ($0.distanceAheadNM ?? .greatestFiniteMagnitude) < ($1.distanceAheadNM ?? .greatestFiniteMagnitude) }
    }

    /// Filter SIGMET/AIRMET advisories to those the route actually passes through.
    /// Unlike a PIREP — a point report we buffer by the route corridor — a SIGMET
    /// covers a wide area, so it is only applicable when the route line genuinely
    /// crosses (or starts/ends inside) its polygon; being merely *near* the area
    /// does not count. Advisories with no usable geometry are excluded — they can't
    /// be placed on the route, and the nationwide AIR/SIGMET feed otherwise makes a
    /// distant turbulence advisory look like it's on every flight. Pure and
    /// deterministic.
    func relevantSigmets(_ sigmets: [SIGMET],
                         position: CLLocationCoordinate2D,
                         routeEnd: CLLocationCoordinate2D?) -> [SIGMET] {
        sigmets.filter { sigmet in
            // Require a drawable polygon (≥3 valid vertices): an advisory that can't
            // be placed on the map must not silently drive the ride index either.
            guard let area = sigmet.drawableArea else { return false }
            return routePassesThroughPolygon(area, position: position, routeEnd: routeEnd)
        }
    }

    /// Whether the route actually passes through the advisory polygon: either an
    /// endpoint lies inside the area, or the route line crosses one of its edges.
    /// With no destination yet, only the current position being inside the area
    /// counts — a lone point isn't a route, so proximity alone is not applicability.
    private func routePassesThroughPolygon(_ polygon: [CLLocationCoordinate2D],
                                           position: CLLocationCoordinate2D,
                                           routeEnd: CLLocationCoordinate2D?) -> Bool {
        if Self.pointInPolygon(position, polygon) { return true }
        guard let routeEnd else { return false }
        if Self.pointInPolygon(routeEnd, polygon) { return true }

        // The route line enters and leaves the area by crossing its boundary, so a
        // pass-through with both endpoints outside is caught by an edge crossing.
        var j = polygon.count - 1
        for i in polygon.indices {
            if Geo.segmentsIntersect(position, routeEnd, polygon[j], polygon[i]) { return true }
            j = i
        }
        return false
    }

    /// Ray-casting point-in-polygon test on lat/lon (adequate at the scale of a
    /// SIGMET area; the route corridor check above covers near-edge cases).
    static func pointInPolygon(_ point: CLLocationCoordinate2D, _ polygon: [CLLocationCoordinate2D]) -> Bool {
        guard polygon.count >= 3 else { return false }
        var inside = false
        var j = polygon.count - 1
        for i in polygon.indices {
            let pi = polygon[i], pj = polygon[j]
            if (pi.latitude > point.latitude) != (pj.latitude > point.latitude) {
                let slope = (point.latitude - pi.latitude) / (pj.latitude - pi.latitude)
                let intersectLon = pi.longitude + slope * (pj.longitude - pi.longitude)
                if point.longitude < intersectLon { inside.toggle() }
            }
            j = i
        }
        return inside
    }
}
