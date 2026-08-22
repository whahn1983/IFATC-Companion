package com.h3consultingpartners.ifatccompanion.core.surface.routing

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.surface.GeoCoordinate
import kotlin.math.max

/**
 * Pure aircraft-progress tracking against a calculated taxi route. No side effects —
 * the coordinator feeds it live telemetry each tick and acts on the result.
 *
 * Ported from `IFATCCompanion/AirportSurface/RouteTracker.swift`.
 */
class RouteTracker {

    companion object {
        /**
         * Perpendicular offset (m) beyond which the aircraft is considered off-route.
         * Generous on purpose: OSM taxiway centerlines rarely line up exactly with the
         * Infinite Flight scenery the aircraft is driving on, so a tight threshold flags
         * "off route" while the pilot is taxiing correctly. The coordinator adds tick
         * hysteresis on top of this before it ever surfaces the banner.
         */
        const val OFF_ROUTE_METERS = 55.0

        /** Distance (m) within which the destination (runway hold / gate) is "reached". */
        const val DESTINATION_REACHED_METERS = 30.0
    }

    data class Progress(
        /** Distance traveled along the route (m) to the aircraft's projected position. */
        val alongMeters: Double,
        val remainingMeters: Double,
        /** Perpendicular offset from the route centerline (m). */
        val crossTrackMeters: Double,
        val onRoute: Boolean,
        /** Index into `route.crossings` of the next crossing ahead (null = none). */
        val nextCrossingIndex: Int?,
        /** Distance (m) to the next crossing's centerline, when one is ahead. */
        val distanceToNextCrossingMeters: Double?,
        /** Distance (m) to the next crossing's hold-short point, when one is ahead. */
        val distanceToNextHoldMeters: Double?,
        val reachedDestination: Boolean,
        /** The projected point on the route (for the map marker snap). */
        val projectedPoint: GeoCoordinate,
    )

    /**
     * Compute progress for an aircraft position against a route. [minAlong] prevents
     * the projection from snapping backwards onto an earlier, geometrically-near part
     * of the route (e.g. parallel taxiways) — pass the last known along-distance.
     */
    fun progress(aircraft: Coordinate, route: SurfaceTaxiRoute, minAlong: Double = 0.0): Progress {
        val line = route.line
        if (line.size < 2) {
            return Progress(
                alongMeters = 0.0, remainingMeters = route.distanceMeters,
                crossTrackMeters = 0.0, onRoute = true, nextCrossingIndex = null,
                distanceToNextCrossingMeters = null, distanceToNextHoldMeters = null,
                reachedDestination = false, projectedPoint = route.startCoordinate,
            )
        }

        // Nearest point, but never allow a large backward jump past minAlong.
        val nearest = SurfaceGeometry.nearestPointOnPath(aircraft, line)
        var along = nearest?.alongMeters ?: 0.0
        var cross = nearest?.distanceMeters ?: 0.0
        var projected = nearest?.point ?: line[0]
        if (along < minAlong - 5) {
            // Re-project onto the forward portion only.
            val forward = forwardProjection(aircraft, line, minAlong)
            if (forward != null) {
                along = forward.along; cross = forward.cross; projected = forward.point
            }
        }

        val remaining = max(0.0, route.distanceMeters - along)
        val onRoute = cross <= OFF_ROUTE_METERS
        val reached = remaining <= DESTINATION_REACHED_METERS

        // Next crossing ahead (a little tolerance so one just underfoot still counts).
        var nextIdx: Int? = null
        var toCrossing: Double? = null
        var toHold: Double? = null
        for (c in route.crossings) {
            if (c.alongMeters <= along - 8) continue
            nextIdx = c.index
            toCrossing = max(0.0, c.alongMeters - along)
            val holdAlong =
                SurfaceGeometry.nearestPointOnPath(c.holdShortPoint.toCoordinate(), line)?.alongMeters
                    ?: c.alongMeters
            toHold = max(0.0, holdAlong - along)
            break
        }

        return Progress(
            alongMeters = along, remainingMeters = remaining, crossTrackMeters = cross,
            onRoute = onRoute, nextCrossingIndex = nextIdx,
            distanceToNextCrossingMeters = toCrossing, distanceToNextHoldMeters = toHold,
            reachedDestination = reached, projectedPoint = GeoCoordinate(projected),
        )
    }

    private data class ForwardHit(val along: Double, val cross: Double, val point: Coordinate)

    /** Project onto the polyline considering only the portion at/after [fromAlong]. */
    private fun forwardProjection(
        aircraft: Coordinate,
        line: List<Coordinate>,
        fromAlong: Double,
    ): ForwardHit? {
        if (line.size < 2) return null
        var cumulative = 0.0
        var best: ForwardHit? = null
        for (i in 1 until line.size) {
            val segStart = cumulative
            val seg = SurfaceGeometry.nearestPointOnSegment(aircraft, line[i - 1], line[i])
            val along = segStart + SurfaceGeometry.distanceMeters(line[i - 1], seg.point)
            if (along >= fromAlong - 5) {
                if (best == null || seg.distanceMeters < best!!.cross) {
                    best = ForwardHit(along, seg.distanceMeters, seg.point)
                }
            }
            cumulative += SurfaceGeometry.distanceMeters(line[i - 1], line[i])
        }
        return best
    }
}
