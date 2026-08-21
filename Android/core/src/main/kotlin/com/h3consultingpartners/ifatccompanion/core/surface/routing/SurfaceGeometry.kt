package com.h3consultingpartners.ifatccompanion.core.surface.routing

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.surface.GeoCoordinate
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Planar (lat/lon-as-plane) geometry helpers for the airport-surface layer. At the
 * scale of a single airport this flat approximation is more than accurate enough for
 * intersection, snapping, and progress math, and it is consistent with [Geo]'s planar
 * `segmentsIntersect`. Distances still use [Geo]'s great-circle math for correctness.
 *
 * Ported from `IFATCCompanion/AirportSurface/SurfaceGeometry.swift`.
 */
object SurfaceGeometry {

    const val METERS_PER_NM = 1852.0

    fun distanceMeters(a: Coordinate, b: Coordinate): Double =
        Geo.distanceNM(a, b) * METERS_PER_NM

    /** Total length (meters) of a polyline. */
    fun pathLengthMeters(path: List<Coordinate>): Double {
        if (path.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until path.size) total += distanceMeters(path[i - 1], path[i])
        return total
    }

    /**
     * Intersection point of segments p1–p2 and p3–p4 (planar), or null if they don't
     * properly cross. Endpoint-touch is treated as an intersection.
     */
    fun segmentIntersection(
        p1: Coordinate,
        p2: Coordinate,
        p3: Coordinate,
        p4: Coordinate,
    ): Coordinate? {
        // Use longitude as x, latitude as y.
        val x1 = p1.longitude; val y1 = p1.latitude
        val x2 = p2.longitude; val y2 = p2.latitude
        val x3 = p3.longitude; val y3 = p3.latitude
        val x4 = p4.longitude; val y4 = p4.latitude
        val denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        if (abs(denom) <= 1e-15) return null // parallel / degenerate
        val t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / denom
        val u = ((x1 - x3) * (y1 - y2) - (y1 - y3) * (x1 - x2)) / denom
        if (t < 0 || t > 1 || u < 0 || u > 1) return null
        return Coordinate(latitude = y1 + t * (y2 - y1), longitude = x1 + t * (x2 - x1))
    }

    /**
     * The point on segment a–b nearest to [p], plus the along-segment fraction (0…1)
     * and the perpendicular distance in meters.
     */
    data class NearestOnSegment(val point: Coordinate, val t: Double, val distanceMeters: Double)

    fun nearestPointOnSegment(p: Coordinate, a: Coordinate, b: Coordinate): NearestOnSegment {
        // cos(lat) correction so longitude degrees are scaled to match latitude on the plane.
        val cosLat = max(0.2, cos(a.latitude * PI / 180))
        val ax = a.longitude * cosLat; val ay = a.latitude
        val bx = b.longitude * cosLat; val by = b.latitude
        val px = p.longitude * cosLat; val py = p.latitude
        val dx = bx - ax; val dy = by - ay
        val lenSq = dx * dx + dy * dy
        var t = 0.0
        if (lenSq > 1e-18) {
            t = ((px - ax) * dx + (py - ay) * dy) / lenSq
            t = max(0.0, min(1.0, t))
        }
        val proj = Coordinate(latitude = ay + t * dy, longitude = (ax + t * dx) / cosLat)
        return NearestOnSegment(proj, t, distanceMeters(p, proj))
    }

    /**
     * The nearest point on a polyline to [p], with the perpendicular distance and the
     * cumulative along-path distance (meters) to that point.
     */
    data class NearestOnPath(val point: Coordinate, val distanceMeters: Double, val alongMeters: Double)

    fun nearestPointOnPath(p: Coordinate, path: List<Coordinate>): NearestOnPath? {
        if (path.size < 2) {
            val only = path.firstOrNull() ?: return null
            return NearestOnPath(only, distanceMeters(p, only), 0.0)
        }
        var best: NearestOnPath? = null
        var cumulative = 0.0
        for (i in 1 until path.size) {
            val seg = nearestPointOnSegment(p, path[i - 1], path[i])
            val along = cumulative + distanceMeters(path[i - 1], seg.point)
            if (best == null || seg.distanceMeters < best!!.distanceMeters) {
                best = NearestOnPath(seg.point, seg.distanceMeters, along)
            }
            cumulative += distanceMeters(path[i - 1], path[i])
        }
        return best
    }

    /**
     * The coordinate reached by travelling [meters] along a polyline from its start.
     * Clamped to the endpoints.
     */
    fun pointAlong(path: List<Coordinate>, meters: Double): Coordinate? {
        val first = path.firstOrNull() ?: return null
        if (meters <= 0 || path.size < 2) return first
        var remaining = meters
        for (i in 1 until path.size) {
            val segLen = distanceMeters(path[i - 1], path[i])
            if (remaining <= segLen) {
                val f = if (segLen > 0) remaining / segLen else 0.0
                return Coordinate(
                    latitude = path[i - 1].latitude + (path[i].latitude - path[i - 1].latitude) * f,
                    longitude = path[i - 1].longitude + (path[i].longitude - path[i - 1].longitude) * f,
                )
            }
            remaining -= segLen
        }
        return path.last()
    }

    /** A ~1.1 m snap key for merging coincident OSM vertices into shared graph nodes. */
    fun snapKey(c: GeoCoordinate): String =
        String.format(Locale.US, "%.5f,%.5f", c.latitude, c.longitude)

    // MARK: - Polygons

    data class BoundingBox(
        val minLat: Double,
        val minLon: Double,
        val maxLat: Double,
        val maxLon: Double,
    )

    /**
     * Axis-aligned bounding box of a polygon (min/max latitude & longitude), or null when
     * empty. Used as a cheap first-pass reject before the full segment/point tests.
     */
    fun boundingBox(polygon: List<Coordinate>): BoundingBox? {
        val first = polygon.firstOrNull() ?: return null
        var minLat = first.latitude; var maxLat = first.latitude
        var minLon = first.longitude; var maxLon = first.longitude
        for (p in polygon.drop(1)) {
            minLat = min(minLat, p.latitude); maxLat = max(maxLat, p.latitude)
            minLon = min(minLon, p.longitude); maxLon = max(maxLon, p.longitude)
        }
        return BoundingBox(minLat, minLon, maxLat, maxLon)
    }

    /**
     * Ray-casting point-in-polygon test (planar, longitude as x / latitude as y). The
     * polygon is treated as implicitly closed; winding direction does not matter.
     */
    fun polygonContains(p: Coordinate, polygon: List<Coordinate>): Boolean {
        if (polygon.size < 3) return false
        val x = p.longitude; val y = p.latitude
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i].longitude; val yi = polygon[i].latitude
            val xj = polygon[j].longitude; val yj = polygon[j].latitude
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * Whether segment a–b intersects the closed polygon — either crossing one of its
     * edges or lying entirely inside it (tested via the segment midpoint). Endpoints that
     * merely touch the boundary count as an intersection (consistent with
     * [segmentIntersection]).
     */
    fun segmentIntersectsPolygon(a: Coordinate, b: Coordinate, polygon: List<Coordinate>): Boolean {
        if (polygon.size < 3) return false
        val n = polygon.size
        for (i in 0 until n) {
            val p = polygon[i]; val q = polygon[(i + 1) % n]
            if (segmentIntersection(a, b, p, q) != null) return true
        }
        // No boundary crossing: the segment is either wholly inside or wholly outside;
        // its midpoint decides which.
        val mid = Coordinate(
            latitude = (a.latitude + b.latitude) / 2,
            longitude = (a.longitude + b.longitude) / 2,
        )
        return polygonContains(mid, polygon)
    }

    /**
     * How much of segment a–b lies **inside** [polygon], in meters.
     *
     * [segmentIntersectsPolygon] answers only whether the two meet, which cannot separate a
     * lead-in that merely starts on the outline from one that runs the building's whole
     * width: a stand mapped *as a vertex of the concourse* — how KIAD tags every gate node,
     * each one a member of the Concourse C/D way — touches the boundary in every direction,
     * so a boolean test flags all of them equally and whatever penalty it carries stops
     * discriminating. The intruded length is what tells those cases apart: it is ~0 for a
     * lead-in leaving the building and the full span for one crossing to the far side.
     */
    fun segmentIntrusionMeters(a: Coordinate, b: Coordinate, polygon: List<Coordinate>): Double {
        if (polygon.size < 3) return 0.0
        val total = distanceMeters(a, b)
        if (total <= 0) return 0.0
        // Split a–b at every boundary crossing; each resulting span is wholly in or wholly
        // out, so its midpoint classifies it.
        val cuts = mutableListOf(0.0, 1.0)
        val n = polygon.size
        for (i in 0 until n) {
            val hit = segmentIntersection(a, b, polygon[i], polygon[(i + 1) % n]) ?: continue
            cuts.add(min(1.0, max(0.0, parameter(hit, a, b))))
        }
        cuts.sort()
        var inside = 0.0
        for (i in 1 until cuts.size) {
            val span = cuts[i] - cuts[i - 1]
            if (span <= 1e-9) continue
            val t = (cuts[i] + cuts[i - 1]) / 2
            val mid = Coordinate(
                latitude = a.latitude + (b.latitude - a.latitude) * t,
                longitude = a.longitude + (b.longitude - a.longitude) * t,
            )
            if (polygonContains(mid, polygon)) inside += span * total
        }
        return inside
    }

    /**
     * Fractional position of [p] along a–b (planar, longitude scaled by cos(lat) to match
     * [nearestPointOnSegment]). Used to order boundary crossings along a segment.
     */
    private fun parameter(p: Coordinate, a: Coordinate, b: Coordinate): Double {
        val cosLat = max(0.2, cos(a.latitude * PI / 180))
        val dx = (b.longitude - a.longitude) * cosLat
        val dy = b.latitude - a.latitude
        val lenSq = dx * dx + dy * dy
        if (lenSq <= 1e-18) return 0.0
        return ((p.longitude - a.longitude) * cosLat * dx + (p.latitude - a.latitude) * dy) / lenSq
    }

    /**
     * Sub-sample a polyline into segments no longer than [maxMeters], so a long
     * straight taxiway/runway segment is still tested finely for crossings.
     */
    fun densify(path: List<Coordinate>, maxMeters: Double = 40.0): List<Coordinate> {
        if (path.size < 2) return path
        val out = mutableListOf(path[0])
        for (i in 1 until path.size) {
            val a = path[i - 1]; val b = path[i]
            val d = distanceMeters(a, b)
            if (d > maxMeters) {
                val steps = ceil(d / maxMeters).toInt()
                for (s in 1 until steps) {
                    val f = s.toDouble() / steps.toDouble()
                    out.add(
                        Coordinate(
                            latitude = a.latitude + (b.latitude - a.latitude) * f,
                            longitude = a.longitude + (b.longitude - a.longitude) * f,
                        ),
                    )
                }
            }
            out.add(b)
        }
        return out
    }
}
