package com.h3consultingpartners.ifatccompanion.core.weather

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The ride assessment must only be raised by SIGMETs whose advisory area actually
 * lies along the route — the nationwide AIR/SIGMET feed otherwise made a distant
 * turbulence advisory read as "severe" on every flight.
 *
 * Ported from `IFATCCompanionTests/WeatherRouteSigmetTests.swift`. The two cases that
 * finish in `TurbulenceModel.assess` keep their analyzer half here; the assessment
 * assertion belongs with the turbulence/ride-report package.
 */
class WeatherRouteSigmetTest {

    private val analyzer = WeatherRouteAnalyzer()

    // KIAH → KMSP, roughly a south-to-north line up the middle of the US.
    private val origin = Coordinate(29.98, -95.34)
    private val dest = Coordinate(44.88, -93.22)

    /** A small box polygon centered on [center] (±[half] degrees). */
    private fun box(center: Coordinate, half: Double = 0.5): List<Coordinate> = listOf(
        Coordinate(center.latitude - half, center.longitude - half),
        Coordinate(center.latitude - half, center.longitude + half),
        Coordinate(center.latitude + half, center.longitude + half),
        Coordinate(center.latitude + half, center.longitude - half),
    )

    private fun sigmet(area: List<Coordinate>, hazard: String = "TURB"): SIGMET =
        SIGMET(raw = "$hazard SIGMET", hazard = hazard, severity = null, area = area)

    @Test
    fun onRouteSigmetIsKept() {
        // Box near the route midpoint (~37.4N, 94.3W).
        val onRoute = sigmet(box(Coordinate(37.4, -94.3)))
        val kept = analyzer.relevantSigmets(listOf(onRoute), position = origin, routeEnd = dest)
        assertEquals(1, kept.size)
    }

    @Test
    fun nearButNotThroughSigmetIsDropped() {
        // A box east of the KIAH→KMSP corridor: close enough that the old proximity
        // buffer kept it, but the route never actually enters the area. A SIGMET
        // covers a wide region, so only a genuine pass-through makes it applicable.
        val nearRoute = sigmet(box(Coordinate(37.4, -92.5)))
        val kept = analyzer.relevantSigmets(listOf(nearRoute), position = origin, routeEnd = dest)
        assertTrue(kept.isEmpty(), "a SIGMET the route passes near but not through is not applicable")
    }

    @Test
    fun routeCrossingSigmetWithVerticesOffRouteIsKept() {
        // A wide box the route passes straight through, whose corners are all far
        // from the route line — an edge-crossing test catches it, a vertex-proximity
        // test would not.
        val wide = box(Coordinate(37.4, -94.3), half = 3.0)
        val kept = analyzer.relevantSigmets(listOf(sigmet(wide)), position = origin, routeEnd = dest)
        assertEquals(1, kept.size, "the route crosses the area, so it is applicable")
    }

    @Test
    fun offRouteSigmetIsDropped() {
        // Box over the US west coast — far from the KIAH→KMSP corridor.
        val offRoute = sigmet(box(Coordinate(37.0, -120.0)))
        val kept = analyzer.relevantSigmets(listOf(offRoute), position = origin, routeEnd = dest)
        assertTrue(kept.isEmpty())
    }

    @Test
    fun geometrylessSigmetIsDropped() {
        val kept = analyzer.relevantSigmets(
            listOf(sigmet(emptyList())), position = origin, routeEnd = dest,
        )
        assertTrue(kept.isEmpty(), "an advisory with no area can't be placed on the route")
    }

    @Test
    fun sigmetContainingPositionIsKept() {
        val around = sigmet(box(origin, half = 1.0))
        val kept = analyzer.relevantSigmets(listOf(around), position = origin, routeEnd = dest)
        assertEquals(1, kept.size)
    }

    /**
     * The `TurbulenceModel.assess` half of this guard lives with the turbulence/ride-report
     * package; here we lock the analyzer input it depends on — an off-route advisory never
     * reaches the model at all.
     */
    @Test
    fun offRouteSigmetDoesNotRaiseRideIndex() {
        val offRoute = sigmet(box(Coordinate(37.0, -120.0)))
        val kept = analyzer.relevantSigmets(listOf(offRoute), position = origin, routeEnd = dest)
        assertTrue(
            kept.isEmpty(),
            "an off-route turbulence SIGMET must not drive the ride to severe",
        )
    }

    @Test
    fun degenerateGeometrySigmetIsDropped() {
        // A convective advisory whose "area" is only two points can't be drawn as a
        // polygon on the map — and so must not silently drive the ride index either.
        val line = listOf(origin, dest)
        val kept = analyzer.relevantSigmets(
            listOf(sigmet(line, hazard = "CONVECTIVE")), position = origin, routeEnd = dest,
        )
        assertTrue(kept.isEmpty(), "a <3-point advisory has no drawable area")
    }

    @Test
    fun onRouteSigmetHasDrawableArea() {
        val onRoute = sigmet(box(Coordinate(37.4, -94.3)), hazard = "CONVECTIVE")
        val kept = analyzer.relevantSigmets(listOf(onRoute), position = origin, routeEnd = dest)
        assertEquals(1, kept.size)
        assertNotNull(kept.first().drawableArea, "a kept advisory must be placeable on the map")
    }

    @Test
    fun sigmetSeverityMapping() {
        assertEquals(
            TurbulenceSeverity.SEVERE,
            sigmet(emptyList(), hazard = "CONVECTIVE").turbulenceSeverity,
        )
        assertEquals(
            TurbulenceSeverity.MODERATE,
            sigmet(emptyList(), hazard = "TURB").turbulenceSeverity,
        )
        val severeTurb = SIGMET(raw = "SEV TURB", hazard = "TURB", severity = "SEV", area = emptyList())
        assertEquals(
            TurbulenceSeverity.SEVERE, severeTurb.turbulenceSeverity,
            "a severe-turbulence SIGMET must color and score as severe, not moderate",
        )
        assertEquals(TurbulenceSeverity.LIGHT, sigmet(emptyList(), hazard = "ICE").turbulenceSeverity)
    }

    /**
     * The `TurbulenceModel.assess` half of this guard lives with the turbulence/ride-report
     * package; here we lock the analyzer input — the advisory is route-relevant and carries
     * the severe severity the model then scores.
     */
    @Test
    fun severeTurbSigmetDrivesSevereRide() {
        val onRoute = box(Coordinate(37.4, -94.3))
        val severeTurb = SIGMET(raw = "SEV TURB", hazard = "TURB", severity = "SEV", area = onRoute)
        val kept = analyzer.relevantSigmets(listOf(severeTurb), position = origin, routeEnd = dest)
        assertEquals(1, kept.size)
        assertEquals(TurbulenceSeverity.SEVERE, kept.first().turbulenceSeverity)
    }

    @Test
    fun lowSeveritySigmetIsStillRouteRelevant() {
        // An IFR advisory (maps to smooth, doesn't raise the ride index) that the route
        // crosses is still returned — the map shows all route SIGMETs, not only rough
        // ones, now that SIGMETs don't drive a deviation.
        val ifr = sigmet(box(Coordinate(37.4, -94.3)), hazard = "IFR")
        assertEquals(TurbulenceSeverity.SMOOTH, ifr.turbulenceSeverity)
        val kept = analyzer.relevantSigmets(listOf(ifr), position = origin, routeEnd = dest)
        assertEquals(1, kept.size, "a low-severity on-route advisory is still route-relevant")
    }

    @Test
    fun sigmetOnALaterLegIsCaughtByTheFullRoute() {
        // The route turns: east to a fix, then north. A box sitting on the northern leg
        // is missed by the straight aircraft→destination line but caught when the whole
        // route polyline is tested — "along the entire route."
        val f1 = Coordinate(origin.latitude, origin.longitude + 3) // east
        val f2 = Coordinate(origin.latitude + 4, f1.longitude)     // then north
        val onLeg = sigmet(box(Coordinate(origin.latitude + 2, f1.longitude)))

        val straight = analyzer.relevantSigmets(listOf(onLeg), position = origin, routeEnd = f2)
        assertTrue(
            straight.isEmpty(),
            "the straight line to the destination misses the later-leg advisory",
        )

        val full = analyzer.relevantSigmets(listOf(onLeg), routePolyline = listOf(origin, f1, f2))
        assertEquals(1, full.size, "the full route polyline catches an advisory on a later leg")
    }

    @Test
    fun pointInPolygon() {
        val square = box(Coordinate(40.0, -90.0), half = 1.0)
        assertTrue(WeatherRouteAnalyzer.pointInPolygon(Coordinate(40.0, -90.0), square))
        assertFalse(WeatherRouteAnalyzer.pointInPolygon(Coordinate(50.0, -90.0), square))
    }
}
