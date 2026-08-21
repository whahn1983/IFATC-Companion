package com.h3consultingpartners.ifatccompanion.core.weather.deviation

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.Waypoint
import kotlin.math.abs
import kotlin.test.Test

class ScratchProbeTest {

    private val detector = RouteWeatherConflictDetector()
    private val usPosition = Coordinate(40.0, -95.0)

    private fun cell(
        alongNM: Double,
        crossNM: Double,
        halfAlong: Double = 10.0,
        halfCross: Double = 10.0,
        course: Double = 0.0,
        from: Coordinate,
    ): List<Coordinate> {
        val onCourse = Geo.destination(from, course, alongNM)
        val center = Geo.destination(onCourse, course + 90, crossNM)
        fun pt(a: Double, c: Double): Coordinate {
            val p = Geo.destination(center, course, a)
            return Geo.destination(p, course + 90, c)
        }
        return listOf(
            pt(-halfAlong, -halfCross), pt(halfAlong, -halfCross),
            pt(halfAlong, halfCross), pt(-halfAlong, halfCross),
        )
    }

    private fun radarHazard(polygon: List<Coordinate>): WeatherHazard = WeatherHazard(
        source = WeatherHazardSource.NOAA_RADAR,
        phenomenon = WeatherPhenomenon.PRECIPITATION,
        intensity = WeatherIntensity.HEAVY,
        geometry = HazardGeometry.Polygon(polygon),
        confidence = HazardConfidence.HIGH,
        movementDirectionDegrees = 90.0,
        movementSpeedKnots = 20.0,
    )

    private fun segIx(a: Coordinate, b: Coordinate, c: Coordinate, d: Coordinate): Coordinate? {
        val x1 = a.longitude; val y1 = a.latitude
        val x2 = b.longitude; val y2 = b.latitude
        val x3 = c.longitude; val y3 = c.latitude
        val x4 = d.longitude; val y4 = d.latitude
        val denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        if (abs(denom) <= 1e-12) return null
        val t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / denom
        val u = ((x1 - x3) * (y1 - y2) - (y1 - y3) * (x1 - x2)) / denom
        if (t < 0 || t > 1 || u < 0 || u > 1) return null
        return Coordinate(y1 + t * (y2 - y1), x1 + t * (x2 - x1))
    }

    @Test
    fun probe() {
        val f1 = Geo.destination(usPosition, 0.0, 60.0)
        val f2 = Geo.destination(f1, 45.0, 90.0)
        val storm = radarHazard(cell(alongNM = 40.0, crossNM = 0.0, halfCross = 14.0, from = usPosition))
        val wps = listOf(
            Waypoint(name = "F1", latitude = f1.latitude, longitude = f1.longitude),
            Waypoint(name = "F2", latitude = f2.latitude, longitude = f2.longitude),
        )
        val conflict = detector.detectConflict(
            position = usPosition, course = 0.0, groundspeedKnots = 450.0,
            phase = FlightPhase.CRUISE, hazards = listOf(storm), waypoints = wps,
            routeAhead = listOf(f1, f2),
        )!!
        val route = listOf(usPosition, f1, f2)
        val path = conflict.deviationPath
        println("PATH:")
        for (p in path) {
            val d = Geo.distanceNM(usPosition, p)
            val brg = Geo.bearing(usPosition, p)
            println("  ${p.latitude},${p.longitude}  dist=$d brg=$brg")
        }
        println("ROUTE:")
        for (p in route) println("  ${p.latitude},${p.longitude}")
        val hits = mutableListOf<Coordinate>()
        for (i in 0 until path.size - 1) {
            for (r in 0 until route.size - 1) {
                val x = segIx(path[i], path[i + 1], route[r], route[r + 1]) ?: continue
                println("  crossing leg=$i routeSeg=$r at ${x.latitude},${x.longitude} distFromStart=${Geo.distanceNM(path.first(), x)}")
                if (Geo.distanceNM(path.first(), x) <= 3) continue
                if (hits.none { Geo.distanceNM(it, x) < 1 }) hits.add(x)
            }
        }
        println("HITS=${hits.size}")
    }
}
