package com.h3consultingpartners.ifatccompanion.core.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lightweight geospatial helpers used across weather analysis and flight logic.
 * Deterministic, dependency-free great-circle math (no external services).
 *
 * A direct port of `IFATCCompanion/Utils/Geo.swift`. Every constant and formula is
 * carried across unchanged so distances, bearings and corridor geometry agree with
 * iOS to the last decimal.
 */
object Geo {

    const val EARTH_RADIUS_NM = 3440.065

    /** Great-circle distance in nautical miles between two coordinates. */
    fun distanceNM(from: Coordinate, to: Coordinate): Double {
        val lat1 = from.latitude * PI / 180
        val lat2 = to.latitude * PI / 180
        val dLat = (to.latitude - from.latitude) * PI / 180
        val dLon = (to.longitude - from.longitude) * PI / 180
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(max(0.0, 1 - a)))
        return EARTH_RADIUS_NM * c
    }

    /** Initial bearing (degrees true, 0–360) from one coordinate to another. */
    fun bearing(from: Coordinate, to: Coordinate): Double {
        val lat1 = from.latitude * PI / 180
        val lat2 = to.latitude * PI / 180
        val dLon = (to.longitude - from.longitude) * PI / 180
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val brng = atan2(y, x) * 180 / PI
        return (brng + 360).mod(360.0)
    }

    /** Smallest absolute angular difference between two headings (0–180). */
    fun headingDifference(a: Double, b: Double): Double {
        var diff = abs(a - b) % 360
        if (diff > 180) diff = 360 - diff
        return diff
    }

    /**
     * Cross-track distance (NM) of a point from the great-circle path start->end.
     * Positive/negative sign indicates side; callers typically use the magnitude.
     */
    fun crossTrackDistanceNM(
        point: Coordinate,
        pathStart: Coordinate,
        pathEnd: Coordinate,
    ): Double {
        val d13 = distanceNM(pathStart, point) / EARTH_RADIUS_NM
        val brng13 = bearing(pathStart, point) * PI / 180
        val brng12 = bearing(pathStart, pathEnd) * PI / 180
        val xt = asin(sin(d13) * sin(brng13 - brng12))
        return xt * EARTH_RADIUS_NM
    }

    /**
     * Whether the two planar segments p1–p2 and p3–p4 intersect, treating
     * latitude/longitude as a flat plane. Adequate at the scale of a SIGMET area and
     * consistent with the polygon test, which uses the same planar approximation.
     * Uses the standard orientation test and handles the collinear-overlap edge case.
     */
    fun segmentsIntersect(
        p1: Coordinate,
        p2: Coordinate,
        p3: Coordinate,
        p4: Coordinate,
    ): Boolean {
        val o1 = orientation(p1, p2, p3)
        val o2 = orientation(p1, p2, p4)
        val o3 = orientation(p3, p4, p1)
        val o4 = orientation(p3, p4, p2)
        if (o1 != o2 && o3 != o4) return true
        if (o1 == 0 && onSegment(p1, p2, p3)) return true
        if (o2 == 0 && onSegment(p1, p2, p4)) return true
        if (o3 == 0 && onSegment(p3, p4, p1)) return true
        if (o4 == 0 && onSegment(p3, p4, p2)) return true
        return false
    }

    /** Sign of the cross product (b-a)×(c-a): 0 collinear, 1 CCW, 2 CW. */
    private fun orientation(a: Coordinate, b: Coordinate, c: Coordinate): Int {
        val value = (b.longitude - a.longitude) * (c.latitude - a.latitude) -
            (b.latitude - a.latitude) * (c.longitude - a.longitude)
        if (abs(value) < 1e-12) return 0
        return if (value > 0) 1 else 2
    }

    /** Whether collinear point c lies within the bounding box of segment a–b. */
    private fun onSegment(a: Coordinate, b: Coordinate, c: Coordinate): Boolean =
        min(a.longitude, b.longitude) <= c.longitude && c.longitude <= max(a.longitude, b.longitude) &&
            min(a.latitude, b.latitude) <= c.latitude && c.latitude <= max(a.latitude, b.latitude)

    /**
     * The coordinate reached by travelling [distanceNM] along [bearingDegrees] (true)
     * from [origin], on a great circle. Used to build weather-deviation corridors and
     * recommended deviation paths.
     */
    fun destination(
        origin: Coordinate,
        bearingDegrees: Double,
        distanceNM: Double,
    ): Coordinate {
        val angular = distanceNM / EARTH_RADIUS_NM
        val brng = bearingDegrees * PI / 180
        val lat1 = origin.latitude * PI / 180
        val lon1 = origin.longitude * PI / 180
        val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(brng))
        val lon2 = lon1 + atan2(
            sin(brng) * sin(angular) * cos(lat1),
            cos(angular) - sin(lat1) * sin(lat2),
        )
        return Coordinate(lat2 * 180 / PI, lon2 * 180 / PI)
    }

    /** Convert a compass bearing into a coarse clock/cardinal description. */
    fun cardinal(bearing: Double): String {
        val dirs = listOf(
            "north", "north-east", "east", "south-east",
            "south", "south-west", "west", "north-west",
        )
        val idx = ((bearing + 22.5).mod(360.0) / 45).toInt()
        return dirs[max(0, min(dirs.size - 1, idx))]
    }
}
