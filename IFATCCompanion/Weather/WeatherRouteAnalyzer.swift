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
                         nearestFix: String? = nil) -> [RideReportItem] {

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

            items.append(RideReportItem(severity: severity,
                                        altitudeBand: band,
                                        distanceAheadNM: distanceAhead,
                                        bearing: bearingToPirep,
                                        nearFix: nearestFix,
                                        sourceRaw: pirep.raw))
        }

        return items.sorted { ($0.distanceAheadNM ?? .greatestFiniteMagnitude) < ($1.distanceAheadNM ?? .greatestFiniteMagnitude) }
    }
}
