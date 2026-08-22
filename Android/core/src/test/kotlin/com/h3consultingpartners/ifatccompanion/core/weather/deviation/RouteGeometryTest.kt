package com.h3consultingpartners.ifatccompanion.core.weather.deviation

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * How far along the route a point is, and what of the route is still ahead.
 *
 * These are the measurements the locked deviation set is ordered by — which reroute is
 * next, which has been flown past, where a rejoin may not go — so getting one of them wrong
 * shows up as a mint line drawn behind the aircraft rather than as anything obvious.
 */
class RouteGeometryTest {

    private val origin = Coordinate(40.0, -95.0)

    /** Due north from the origin, so along-route distance and northing are the same thing. */
    private fun north(nm: Double): Coordinate = Geo.destination(origin, 0.0, nm)

    private val route = listOf(origin, north(100.0), north(200.0), north(300.0))

    @Test
    fun alongRouteMeasuresDistanceFromTheOrigin() {
        assertEquals(0.0, RouteGeometry.alongRouteNM(route, origin), 1.0)
        assertEquals(150.0, RouteGeometry.alongRouteNM(route, north(150.0)), 1.0)
        assertEquals(300.0, RouteGeometry.alongRouteNM(route, north(300.0)), 1.0)
    }

    /**
     * Measured by projection, so it reads correctly for an aircraft sitting well off to one
     * side of the course — the "missed the entry point" case as much as the "flew past it"
     * one.
     */
    @Test
    fun alongRouteProjectsAPointOffToTheSide() {
        val offset = Geo.destination(north(150.0), 90.0, 30.0)

        assertEquals(150.0, RouteGeometry.alongRouteNM(route, offset), 2.0)
    }

    @Test
    fun upcomingRouteReturnsTheVerticesStillAhead() {
        val ahead = RouteGeometry.upcomingRouteCoordinates(route, north(150.0))

        assertEquals(2, ahead.size, "past the 100 NM fix, two remain")
        assertTrue(Geo.distanceNM(ahead.first(), north(200.0)) < 1)
    }

    /** At the gate the whole route is still ahead. */
    @Test
    fun upcomingRouteAtTheOriginIsTheWholeRoute() {
        val ahead = RouteGeometry.upcomingRouteCoordinates(route, origin)

        assertEquals(3, ahead.size)
    }

    /** Never empty: an aircraft past the last fix still has the destination in front of it. */
    @Test
    fun upcomingRouteNeverGoesEmpty() {
        val ahead = RouteGeometry.upcomingRouteCoordinates(route, north(400.0))

        assertEquals(1, ahead.size)
        assertTrue(Geo.distanceNM(ahead.first(), north(300.0)) < 1)
    }

    @Test
    fun pointAlongRouteWalksTheLegs() {
        val point = RouteGeometry.pointAlongRoute(origin, route.drop(1), 250.0)

        assertNotNull(point)
        assertEquals(250.0, Geo.distanceNM(origin, point), 2.0)
    }

    @Test
    fun pointAlongRouteReturnsNothingPastTheEnd() {
        assertEquals(null, RouteGeometry.pointAlongRoute(origin, route.drop(1), 1_000.0))
    }

    /**
     * What holds a rejoin a fixed margin short of the field, so a mint line always
     * terminates on the flight path rather than on top of the airport.
     */
    @Test
    fun pointBeforeEndHoldsTheMarginShortOfTheField() {
        val cap = RouteGeometry.pointBeforeEndAlongRoute(route, 20.0)

        assertNotNull(cap)
        assertEquals(20.0, Geo.distanceNM(cap, north(300.0)), 1.0)
    }

    @Test
    fun routeTruncatedEndsAtTheCap() {
        val cap = north(250.0)

        val truncated = RouteGeometry.routeTruncated(route, cap)

        assertTrue(Geo.distanceNM(truncated.last(), cap) < 1)
        assertTrue(truncated.size < route.size + 1)
    }

    @Test
    fun alongRouteDistanceFromEndMeasuresBackFromTheField() {
        assertEquals(100.0, RouteGeometry.alongRouteDistanceFromEnd(route, north(200.0)), 2.0)
    }

    /** Positive once the point is past `a` on the leg a→b, negative before it. */
    @Test
    fun alongLegIsSignedByDirectionOfTravel() {
        val ahead = RouteGeometry.alongLegNM(north(150.0), origin, north(300.0))
        val behind = RouteGeometry.alongLegNM(Geo.destination(origin, 180.0, 20.0), origin, north(300.0))

        assertEquals(150.0, ahead, 2.0)
        assertTrue(behind < 0, "a point behind the leg's start must read negative, was $behind")
        assertTrue(abs(behind + 20.0) < 2.0)
    }
}
