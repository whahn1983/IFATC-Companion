import Foundation
import CoreLocation

/// Planar (lat/lon-as-plane) geometry helpers for the airport-surface layer. At the
/// scale of a single airport this flat approximation is more than accurate enough for
/// intersection, snapping, and progress math, and it is consistent with `Geo`'s planar
/// `segmentsIntersect`. Distances still use `Geo`'s great-circle math for correctness.
enum SurfaceGeometry {

    static let metersPerNM = 1852.0

    static func distanceMeters(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D) -> Double {
        Geo.distanceNM(from: a, to: b) * metersPerNM
    }

    /// Total length (meters) of a polyline.
    static func pathLengthMeters(_ path: [CLLocationCoordinate2D]) -> Double {
        guard path.count >= 2 else { return 0 }
        var total = 0.0
        for i in 1..<path.count { total += distanceMeters(path[i - 1], path[i]) }
        return total
    }

    /// Intersection point of segments p1–p2 and p3–p4 (planar), or nil if they don't
    /// properly cross. Endpoint-touch is treated as an intersection.
    static func segmentIntersection(_ p1: CLLocationCoordinate2D, _ p2: CLLocationCoordinate2D,
                                    _ p3: CLLocationCoordinate2D, _ p4: CLLocationCoordinate2D) -> CLLocationCoordinate2D? {
        // Use longitude as x, latitude as y.
        let x1 = p1.longitude, y1 = p1.latitude
        let x2 = p2.longitude, y2 = p2.latitude
        let x3 = p3.longitude, y3 = p3.latitude
        let x4 = p4.longitude, y4 = p4.latitude
        let denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        guard abs(denom) > 1e-15 else { return nil }   // parallel / degenerate
        let t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / denom
        let u = ((x1 - x3) * (y1 - y2) - (y1 - y3) * (x1 - x2)) / denom
        guard t >= 0, t <= 1, u >= 0, u <= 1 else { return nil }
        return CLLocationCoordinate2D(latitude: y1 + t * (y2 - y1),
                                      longitude: x1 + t * (x2 - x1))
    }

    /// The point on segment a–b nearest to `p`, plus the along-segment fraction (0…1)
    /// and the perpendicular distance in meters.
    static func nearestPointOnSegment(_ p: CLLocationCoordinate2D,
                                      _ a: CLLocationCoordinate2D,
                                      _ b: CLLocationCoordinate2D) -> (point: CLLocationCoordinate2D, t: Double, distanceMeters: Double) {
        // cos(lat) correction so longitude degrees are scaled to match latitude on the plane.
        let cosLat = max(0.2, cos(a.latitude * .pi / 180))
        let ax = a.longitude * cosLat, ay = a.latitude
        let bx = b.longitude * cosLat, by = b.latitude
        let px = p.longitude * cosLat, py = p.latitude
        let dx = bx - ax, dy = by - ay
        let lenSq = dx * dx + dy * dy
        var t = 0.0
        if lenSq > 1e-18 {
            t = ((px - ax) * dx + (py - ay) * dy) / lenSq
            t = max(0, min(1, t))
        }
        let proj = CLLocationCoordinate2D(latitude: ay + t * dy,
                                          longitude: (ax + t * dx) / cosLat)
        return (proj, t, distanceMeters(p, proj))
    }

    /// The nearest point on a polyline to `p`, with the perpendicular distance and the
    /// cumulative along-path distance (meters) to that point.
    static func nearestPointOnPath(_ p: CLLocationCoordinate2D,
                                   _ path: [CLLocationCoordinate2D]) -> (point: CLLocationCoordinate2D, distanceMeters: Double, alongMeters: Double)? {
        guard path.count >= 2 else {
            if let only = path.first { return (only, distanceMeters(p, only), 0) }
            return nil
        }
        var best: (point: CLLocationCoordinate2D, distanceMeters: Double, alongMeters: Double)?
        var cumulative = 0.0
        for i in 1..<path.count {
            let seg = nearestPointOnSegment(p, path[i - 1], path[i])
            let along = cumulative + distanceMeters(path[i - 1], seg.point)
            if best == nil || seg.distanceMeters < best!.distanceMeters {
                best = (seg.point, seg.distanceMeters, along)
            }
            cumulative += distanceMeters(path[i - 1], path[i])
        }
        return best
    }

    /// The coordinate reached by travelling `meters` along a polyline from its start.
    /// Clamped to the endpoints.
    static func pointAlong(_ path: [CLLocationCoordinate2D], meters: Double) -> CLLocationCoordinate2D? {
        guard let first = path.first else { return nil }
        if meters <= 0 || path.count < 2 { return first }
        var remaining = meters
        for i in 1..<path.count {
            let segLen = distanceMeters(path[i - 1], path[i])
            if remaining <= segLen {
                let f = segLen > 0 ? remaining / segLen : 0
                return CLLocationCoordinate2D(latitude: path[i - 1].latitude + (path[i].latitude - path[i - 1].latitude) * f,
                                              longitude: path[i - 1].longitude + (path[i].longitude - path[i - 1].longitude) * f)
            }
            remaining -= segLen
        }
        return path.last
    }

    /// A ~1.1 m snap key for merging coincident OSM vertices into shared graph nodes.
    static func snapKey(_ c: GeoCoordinate) -> String {
        String(format: "%.5f,%.5f", c.latitude, c.longitude)
    }

    // MARK: - Polygons

    /// Axis-aligned bounding box of a polygon (min/max latitude & longitude), or nil when
    /// empty. Used as a cheap first-pass reject before the full segment/point tests.
    static func boundingBox(of polygon: [CLLocationCoordinate2D])
        -> (minLat: Double, minLon: Double, maxLat: Double, maxLon: Double)? {
        guard let first = polygon.first else { return nil }
        var minLat = first.latitude, maxLat = first.latitude
        var minLon = first.longitude, maxLon = first.longitude
        for p in polygon.dropFirst() {
            minLat = min(minLat, p.latitude); maxLat = max(maxLat, p.latitude)
            minLon = min(minLon, p.longitude); maxLon = max(maxLon, p.longitude)
        }
        return (minLat, minLon, maxLat, maxLon)
    }

    /// Ray-casting point-in-polygon test (planar, longitude as x / latitude as y). The
    /// polygon is treated as implicitly closed; winding direction does not matter.
    static func polygonContains(_ p: CLLocationCoordinate2D, _ polygon: [CLLocationCoordinate2D]) -> Bool {
        guard polygon.count >= 3 else { return false }
        let x = p.longitude, y = p.latitude
        var inside = false
        var j = polygon.count - 1
        for i in 0..<polygon.count {
            let xi = polygon[i].longitude, yi = polygon[i].latitude
            let xj = polygon[j].longitude, yj = polygon[j].latitude
            if (yi > y) != (yj > y),
               x < (xj - xi) * (y - yi) / (yj - yi) + xi {
                inside.toggle()
            }
            j = i
        }
        return inside
    }

    /// Whether segment a–b intersects the closed polygon — either crossing one of its
    /// edges or lying entirely inside it (tested via the segment midpoint). Endpoints that
    /// merely touch the boundary count as an intersection (consistent with
    /// `segmentIntersection`).
    static func segmentIntersectsPolygon(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D,
                                         _ polygon: [CLLocationCoordinate2D]) -> Bool {
        guard polygon.count >= 3 else { return false }
        let n = polygon.count
        for i in 0..<n {
            let p = polygon[i], q = polygon[(i + 1) % n]
            if segmentIntersection(a, b, p, q) != nil { return true }
        }
        // No boundary crossing: the segment is either wholly inside or wholly outside;
        // its midpoint decides which.
        let mid = CLLocationCoordinate2D(latitude: (a.latitude + b.latitude) / 2,
                                         longitude: (a.longitude + b.longitude) / 2)
        return polygonContains(mid, polygon)
    }

    /// Sub-sample a polyline into segments no longer than `maxMeters`, so a long
    /// straight taxiway/runway segment is still tested finely for crossings.
    static func densify(_ path: [CLLocationCoordinate2D], maxMeters: Double = 40) -> [CLLocationCoordinate2D] {
        guard path.count >= 2 else { return path }
        var out: [CLLocationCoordinate2D] = [path[0]]
        for i in 1..<path.count {
            let a = path[i - 1], b = path[i]
            let d = distanceMeters(a, b)
            if d > maxMeters {
                let steps = Int((d / maxMeters).rounded(.up))
                for s in 1..<steps {
                    let f = Double(s) / Double(steps)
                    out.append(CLLocationCoordinate2D(latitude: a.latitude + (b.latitude - a.latitude) * f,
                                                      longitude: a.longitude + (b.longitude - a.longitude) * f))
                }
            }
            out.append(b)
        }
        return out
    }
}
