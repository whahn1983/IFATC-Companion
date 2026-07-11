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
    /// the path toward `routeEnd`. Pass `ignoreAltitudeBand: true` to keep reports at
    /// **all** levels (for the smoother-altitude search, which needs the other levels the
    /// ±band filter would otherwise hide).
    func relevantReports(pireps: [PIREP],
                         position: CLLocationCoordinate2D,
                         routeEnd: CLLocationCoordinate2D?,
                         altitudeFt: Double,
                         nearestFix: String? = nil,
                         ignoreAltitudeBand: Bool = false,
                         now: Date = Date()) -> [RideReportItem] {

        let courseTo = routeEnd.map { Geo.bearing(from: position, to: $0) }

        var items: [RideReportItem] = []
        for pirep in pireps {
            guard let severity = pirep.turbulence, severity > .smooth else { continue }
            guard let coord = pirep.coordinate, coord.isValid else { continue }

            // Altitude band filter (unknown altitude is included conservatively).
            if !ignoreAltitudeBand, let alt = pirep.altitudeFt {
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
                                        ageMinutes: age,
                                        reportedAltitudeFt: pirep.altitudeFt,
                                        aircraftType: pirep.aircraftType))
        }

        return items.sorted { ($0.distanceAheadNM ?? .greatestFiniteMagnitude) < ($1.distanceAheadNM ?? .greatestFiniteMagnitude) }
    }

    /// Cruise band (ft) a smoother-altitude suggestion is bounded to — commercial jets
    /// including regional and business jets (FL240–FL430). Suggestions never fall outside it.
    static let cruiseBandFt = 24_000...43_000

    /// A specific smoother altitude to suggest, drawn from PIREPs at *other* levels along
    /// the route, or nil when none supports one (the caller then keeps the generic "advise
    /// higher or lower"). Considers reports within `band` that are strictly smoother than
    /// `currentSeverity` and at a level at least `minSeparationFt` from `referenceAltFt`;
    /// prefers the smoothest, then the nearest such level, snapped to 1000 ft. **Data-driven
    /// only** — it never invents a smooth level with no report behind it. Pure/testable.
    func smootherAltitude(items: [RideReportItem],
                          referenceAltFt: Int,
                          currentSeverity: TurbulenceSeverity,
                          band: ClosedRange<Int> = cruiseBandFt,
                          minSeparationFt: Int = 1500) -> SmootherAltitude? {
        guard currentSeverity > .smooth else { return nil }
        let candidates: [(alt: Int, item: RideReportItem)] = items.compactMap { item in
            guard let raw = item.reportedAltitudeFt else { return nil }
            let alt = Int((Double(raw) / 1000).rounded()) * 1000
            guard band.contains(alt),
                  item.severity < currentSeverity,
                  abs(alt - referenceAltFt) >= minSeparationFt else { return nil }
            return (alt, item)
        }
        let best = candidates.min { a, b in
            a.item.severity != b.item.severity
                ? a.item.severity < b.item.severity
                : abs(a.alt - referenceAltFt) < abs(b.alt - referenceAltFt)
        }
        guard let best else { return nil }
        return SmootherAltitude(altitudeFt: best.alt, severity: best.item.severity,
                                aircraftType: best.item.aircraftType, higher: best.alt > referenceAltFt)
    }

    /// Filter SIGMET/AIRMET advisories to those the route actually passes through.
    /// Unlike a PIREP — a point report we buffer by the route corridor — a SIGMET
    /// covers a wide area, so it is only applicable when the route line genuinely
    /// crosses (or starts/ends inside) its polygon; being merely *near* the area
    /// does not count. Advisories with no usable geometry are excluded — they can't
    /// be placed on the route, and the nationwide AIR/SIGMET feed otherwise makes a
    /// distant turbulence advisory look like it's on every flight. Pure and
    /// deterministic.
    /// Evaluate against the full route polyline (aircraft → remaining fixes →
    /// destination), so an advisory on a leg *after* a turn is caught, not just one on
    /// the straight line to the destination — "along the entire route."
    func relevantSigmets(_ sigmets: [SIGMET],
                         routePolyline: [CLLocationCoordinate2D]) -> [SIGMET] {
        let route = routePolyline.filter { $0.isValid }
        return sigmets.filter { sigmet in
            // Require a drawable polygon (≥3 valid vertices): an advisory that can't
            // be placed on the map must not silently drive the ride index either.
            guard let area = sigmet.drawableArea else { return false }
            return routePassesThroughPolygon(area, polyline: route)
        }
    }

    /// Convenience for a single straight leg (aircraft → route end). With no route end
    /// only the current position being inside the area counts — a lone point isn't a
    /// route, so proximity alone is not applicability.
    func relevantSigmets(_ sigmets: [SIGMET],
                         position: CLLocationCoordinate2D,
                         routeEnd: CLLocationCoordinate2D?) -> [SIGMET] {
        relevantSigmets(sigmets, routePolyline: [position] + (routeEnd.map { [$0] } ?? []))
    }

    /// Whether the route actually passes through the advisory polygon: either a
    /// polyline vertex lies inside the area, or one of its legs crosses an edge. A lone
    /// point (no legs) is applicable only if it sits inside — proximity alone is not.
    private func routePassesThroughPolygon(_ polygon: [CLLocationCoordinate2D],
                                           polyline: [CLLocationCoordinate2D]) -> Bool {
        guard !polyline.isEmpty else { return false }
        for p in polyline where Self.pointInPolygon(p, polygon) { return true }
        guard polyline.count >= 2 else { return false }

        // Each leg enters and leaves the area by crossing its boundary, so a
        // pass-through with both endpoints outside is caught by an edge crossing.
        for k in 0..<(polyline.count - 1) {
            let a = polyline[k], b = polyline[k + 1]
            var j = polygon.count - 1
            for i in polygon.indices {
                if Geo.segmentsIntersect(a, b, polygon[j], polygon[i]) { return true }
                j = i
            }
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
