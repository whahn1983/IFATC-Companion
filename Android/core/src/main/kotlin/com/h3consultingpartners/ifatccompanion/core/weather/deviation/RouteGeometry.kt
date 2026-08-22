package com.h3consultingpartners.ifatccompanion.core.weather.deviation

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The filed route as a polyline, and the handful of measurements the weather-deviation
 * flow makes against it.
 *
 * Ported from the route helpers on `AppModel` — `fullFiledRoutePolyline`,
 * `upcomingRouteCoordinates`, `alongRouteNM`, `pointAlongRoute`,
 * `pointBeforeEndAlongRoute`, `routeTruncated`, `alongRouteDistanceFromEnd` and
 * `distanceToSegmentNM`. Pulled out as its own object because every one of them is a pure
 * function of a polyline and a point, and because "how far along the route is this?" is the
 * measurement the whole locked-deviation set is ordered by — worth being able to assert on
 * its own.
 *
 * The projection is planar (equirectangular, scaled to nautical miles at the point's own
 * latitude), which is the same frame [RouteWeatherConflictDetector] solves its geometry in.
 * At the scale a deviation is worked — tens of miles — that agrees with the great-circle
 * distance to well under the width of the corridor.
 */
object RouteGeometry {

    /** Distance in NM from [point] to the segment a→b, in the local planar frame. */
    fun distanceToSegmentNM(point: Coordinate, a: Coordinate, b: Coordinate): Double {
        val latScale = 60.0
        val lonScale = 60.0 * cos(point.latitude * PI / 180)
        val px = point.longitude * lonScale
        val py = point.latitude * latScale
        val ax = a.longitude * lonScale
        val ay = a.latitude * latScale
        val bx = b.longitude * lonScale
        val by = b.latitude * latScale
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared <= 0) 0.0 else {
            max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / lengthSquared))
        }
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /**
     * The route still ahead of [position]: the vertices past the segment the aircraft sits
     * abeam of, found by projecting onto the polyline.
     *
     * Projection rather than "farther from the departure than the aircraft", which silently
     * drops an upcoming fix on any route that jogs — and reshapes the detection corridor
     * away from the drawn route the moment telemetry arrives, so on-route storms stop being
     * detected exactly as the aircraft icon appears.
     */
    fun upcomingRouteCoordinates(route: List<Coordinate>, position: Coordinate): List<Coordinate> {
        val full = route.filter { it.isValid }
        if (full.size < 2) return full
        var bestSegment = 0
        var bestDistance = Double.MAX_VALUE
        for (i in 0 until full.size - 1) {
            val d = distanceToSegmentNM(position, full[i], full[i + 1])
            if (d < bestDistance) {
                bestDistance = d
                bestSegment = i
            }
        }
        val ahead = full.drop(bestSegment + 1)
        return ahead.ifEmpty { listOf(full.last()) }
    }

    /**
     * How far along the route (NM from its origin) the nearest point to [coordinate] lies.
     *
     * This is what orders the locked deviation set and tells which of them the aircraft has
     * already flown past — and it reads correctly whether the aircraft is tracking the
     * course or sitting well off to one side of it.
     */
    fun alongRouteNM(route: List<Coordinate>, coordinate: Coordinate): Double {
        val points = route.filter { it.isValid }
        if (points.size < 2) return 0.0
        val latScale = 60.0
        val lonScale = 60.0 * cos(coordinate.latitude * PI / 180)
        val px = coordinate.longitude * lonScale
        val py = coordinate.latitude * latScale
        var cumulative = 0.0
        var bestAlong = 0.0
        var bestDistance = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val segment = Geo.distanceNM(a, b)
            val ax = a.longitude * lonScale
            val ay = a.latitude * latScale
            val bx = b.longitude * lonScale
            val by = b.latitude * latScale
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared <= 0) 0.0 else {
                max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / lengthSquared))
            }
            val distance = hypot(px - (ax + t * dx), py - (ay + t * dy))
            if (distance < bestDistance) {
                bestDistance = distance
                bestAlong = cumulative + segment * t
            }
            cumulative += segment
        }
        return bestAlong
    }

    /** The point [targetNM] along the polyline (`start` then `ahead`), or null if it ends first. */
    fun pointAlongRoute(start: Coordinate, ahead: List<Coordinate>, targetNM: Double): Coordinate? {
        var previous = start
        var accumulated = 0.0
        for (point in ahead) {
            if (!point.isValid) continue
            val segment = Geo.distanceNM(previous, point)
            if (accumulated + segment >= targetNM) {
                return Geo.destination(previous, Geo.bearing(previous, point), targetNM - accumulated)
            }
            accumulated += segment
            previous = point
        }
        return null
    }

    /**
     * The point [targetNM] back from the route's final vertex, walking the legs in reverse.
     *
     * What holds a deviation's rejoin a fixed margin short of the field, on the flight path,
     * rather than letting a mint line terminate on top of the airport.
     */
    fun pointBeforeEndAlongRoute(route: List<Coordinate>, targetNM: Double): Coordinate? {
        val points = route.filter { it.isValid }
        val end = points.lastOrNull() ?: return null
        if (points.size < 2 || targetNM <= 0) return end
        var remaining = targetNM
        for (i in points.size - 1 downTo 1) {
            val a = points[i]
            val b = points[i - 1]
            val segment = Geo.distanceNM(a, b)
            if (segment >= remaining) return Geo.destination(a, Geo.bearing(a, b), remaining)
            remaining -= segment
        }
        return points.first()
    }

    /**
     * The polyline truncated at [cap]: the vertices up to the leg the cap projects onto,
     * then the cap itself. Returns the route unchanged when there is no cap.
     */
    fun routeTruncated(route: List<Coordinate>, cap: Coordinate?): List<Coordinate> {
        if (cap == null || !cap.isValid || route.size < 2) return route
        var bestSegment = 0
        var bestDistance = Double.MAX_VALUE
        for (i in 0 until route.size - 1) {
            val d = distanceToSegmentNM(cap, route[i], route[i + 1])
            if (d < bestDistance) {
                bestDistance = d
                bestSegment = i
            }
        }
        return route.take(bestSegment + 1) + cap
    }

    /**
     * How far before the route's final vertex (the airport) a point sits, measured along the
     * route. Projects onto the nearest leg first, so a fix a little off the drawn polyline
     * still measures sensibly.
     */
    fun alongRouteDistanceFromEnd(route: List<Coordinate>, target: Coordinate): Double {
        val points = route.filter { it.isValid }
        if (points.size < 2 || !target.isValid) return 0.0
        val total = points.zipWithNext { a, b -> Geo.distanceNM(a, b) }.sum()
        return max(0.0, total - alongRouteNM(points, target))
    }

    /**
     * The along-track distance of [point] from [a], measured along the leg a→b. Positive
     * once the point is past `a` on that leg.
     */
    fun alongLegNM(point: Coordinate, a: Coordinate, b: Coordinate): Double {
        val leg = Geo.bearing(a, b)
        val toPoint = Geo.bearing(a, point)
        return Geo.distanceNM(a, point) * cos((toPoint - leg) * PI / 180)
    }
}
