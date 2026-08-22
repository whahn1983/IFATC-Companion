package com.h3consultingpartners.ifatccompanion.core.map

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.RadarBoundingBox
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One image is fetched per route, so the window it covers and the shape it is requested in
 * are the two decisions that decide whether the underlay looks right. Both are arithmetic,
 * so both are pinned here.
 */
class BaseMapWindowTest {

    private val kordToKlax = listOf(
        Coordinate(41.9786, -87.9048),
        Coordinate(39.0, -100.0),
        Coordinate(33.9425, -118.4081),
    )

    // region Coverage

    @Test
    fun `the window contains the route with room to pan`() {
        val box = BaseMapWindow.coverage(kordToKlax)
        assertNotNull(box)
        for (point in kordToKlax) {
            assertTrue(point in box, "$point fell outside the window")
        }
        val routeSpan = 118.4081 - 87.9048
        assertTrue(
            box.maxLongitude - box.minLongitude > routeSpan,
            "the window is no wider than the route, so any pan runs off it",
        )
    }

    @Test
    fun `nothing to cover yields no window`() {
        assertNull(BaseMapWindow.coverage(emptyList()))
        // Coordinate.isValid rejects the 0,0 sentinel a missing position decodes to, so a
        // list of those is the same as no list at all.
        assertNull(BaseMapWindow.coverage(listOf(Coordinate(Double.NaN, Double.NaN))))
    }

    @Test
    fun `a single position gets a window big enough to be worth fetching`() {
        // No plan filed, just an aircraft. A point has a location even with no extent, and
        // the window has to be sized by the floor rather than by the (zero) extent — a
        // mile-wide satellite image behind a route map would be useless and would need
        // refetching the moment the aircraft moved.
        val here = Coordinate(41.9786, -87.9048)
        val box = BaseMapWindow.coverage(listOf(here))
        assertNotNull(box)
        assertTrue(here in box)
        assertTrue(
            box.maxLatitude - box.minLatitude >= BaseMapWindow.MIN_SPAN_DEGREES,
            "a point produced a ${box.maxLatitude - box.minLatitude}° window",
        )
    }

    @Test
    fun `a very short hop is still given a usable window`() {
        // KSFO to KOAK is about a tenth of a degree apart. Sizing to the route would ask
        // for a picture of the bay and nothing around it.
        val box = BaseMapWindow.coverage(
            listOf(Coordinate(37.6188, -122.3750), Coordinate(37.7213, -122.2211)),
        )
        assertNotNull(box)
        assertTrue(box.maxLongitude - box.minLongitude >= BaseMapWindow.MIN_SPAN_DEGREES)
    }

    @Test
    fun `rounding a position to the nearest degree keeps it inside the window`() {
        // Free flight refetches only when the aircraft crosses a whole degree, so the
        // window is centred up to half a degree away from where the aircraft actually is.
        // If that could push the aircraft outside its own imagery the dedup would be a bug.
        for ((actual, rounded) in listOf(
            Coordinate(41.49, -87.51) to Coordinate(41.0, -88.0),
            Coordinate(41.51, -87.49) to Coordinate(42.0, -87.0),
            Coordinate(-0.49, 179.51) to Coordinate(0.0, 180.0),
        )) {
            val box = BaseMapWindow.coverage(listOf(rounded))
            assertNotNull(box)
            assertTrue(actual in box, "$actual fell outside the window centred on $rounded")
        }
    }

    @Test
    fun `the window never leaves the world`() {
        // A polar route padded outward would otherwise run past the Mercator limit, where
        // the projection goes to infinity.
        val box = BaseMapWindow.coverage(
            listOf(Coordinate(84.0, -170.0), Coordinate(84.5, 170.0)),
        )
        assertNotNull(box)
        assertTrue(box.maxLatitude <= MapProjection.MAX_LATITUDE)
        assertTrue(box.minLatitude >= -MapProjection.MAX_LATITUDE)
        assertTrue(box.maxLongitude <= 180.0 && box.minLongitude >= -180.0)
    }

    @Test
    fun `a nonsense padding factor is ignored rather than obeyed`() {
        val sane = BaseMapWindow.coverage(kordToKlax, paddingFactor = 1.0)
        assertNotNull(sane)
        for (bad in listOf(0.0, -3.0, Double.NaN)) {
            val box = BaseMapWindow.coverage(kordToKlax, paddingFactor = bad)
            assertNotNull(box, "padding $bad should still yield a window")
            assertEquals(sane.minLatitude, box.minLatitude, 1e-9, "padding $bad shrank the window")
            assertEquals(sane.maxLongitude, box.maxLongitude, 1e-9, "padding $bad shrank the window")
        }
    }

    // endregion

    // region Pixel size

    @Test
    fun `the requested shape matches the window's shape in Mercator, not in degrees`() {
        // Ten degrees square at 60 N. In degrees that is 1:1, so sizing from degrees would
        // request a square. Mercator stretches north-south by roughly sec(latitude), so at
        // 60 N the same window is about twice as tall as it is wide — and a square request
        // would squash it by half.
        val box = RadarBoundingBox(
            minLatitude = 55.0,
            maxLatitude = 65.0,
            minLongitude = -10.0,
            maxLongitude = 0.0,
        )
        val size = BaseMapWindow.pixelSize(box, maxDimension = 1000)
        assertEquals(1000, size.height)
        assertTrue(
            size.width in 400..600,
            "width ${size.width} does not match the Mercator aspect of this window",
        )
    }

    @Test
    fun `a tall window puts the long edge on the vertical`() {
        val box = RadarBoundingBox(
            minLatitude = 0.0,
            maxLatitude = 40.0,
            minLongitude = -2.0,
            maxLongitude = 2.0,
        )
        val size = BaseMapWindow.pixelSize(box, maxDimension = 800)
        assertEquals(800, size.height)
        assertTrue(size.width in 1 until 800, "unexpected width ${size.width}")
    }

    @Test
    fun `the aspect is preserved to within a pixel of rounding`() {
        val box = BaseMapWindow.coverage(kordToKlax)
        assertNotNull(box)
        val size = BaseMapWindow.pixelSize(box, maxDimension = 1024)
        val northWest = MapProjection.toUnit(Coordinate(box.maxLatitude, box.minLongitude))
        val southEast = MapProjection.toUnit(Coordinate(box.minLatitude, box.maxLongitude))
        val expected = (southEast.y - northWest.y) / (southEast.x - northWest.x)
        val actual = size.height.toDouble() / size.width
        assertTrue(
            abs(expected - actual) < 0.01,
            "aspect drifted: wanted $expected, got $actual (${size.width}x${size.height})",
        )
    }

    @Test
    fun `a degenerate request produces an invalid size rather than a bad URL`() {
        val box = BaseMapWindow.coverage(kordToKlax)
        assertNotNull(box)
        assertTrue(!BaseMapWindow.pixelSize(box, maxDimension = 0).isValid)
        assertTrue(!BaseMapWindow.pixelSize(box, maxDimension = -1).isValid)
    }

    @Test
    fun `every window a route produces is requestable`() {
        // The two halves have to agree: a window coverage() will return must always give
        // pixelSize() something it can size, or the fetch silently never happens.
        val routes = listOf(
            listOf(Coordinate(41.9786, -87.9048)),
            kordToKlax,
            listOf(Coordinate(-33.9461, 151.1772), Coordinate(1.3502, 103.9944)),
            listOf(Coordinate(84.0, -170.0), Coordinate(84.5, 170.0)),
            // Above the Mercator limit once padded: clamping both edges independently
            // collapsed this to a zero-height window, which is silently unrequestable —
            // a polar flight simply never got imagery and nothing said why.
            listOf(Coordinate(86.5, 10.0), Coordinate(86.7, 12.0)),
            listOf(Coordinate(-87.0, 0.0)),
            listOf(Coordinate(0.0, 179.8), Coordinate(0.2, 179.9)),
            listOf(Coordinate(0.0, 0.1), Coordinate(0.001, 0.101)),
        )
        for (route in routes) {
            val box = BaseMapWindow.coverage(route) ?: continue
            assertTrue(
                BaseMapWindow.pixelSize(box).isValid,
                "no requestable size for $route",
            )
        }
    }

    // endregion

    @Test
    fun `a window pressed against a limit keeps its span instead of collapsing`() {
        // Each edge is derived from the other rather than clamped independently: two
        // independent clamps land both edges on the same limit and the window has no
        // extent left at all.
        for (route in listOf(
            listOf(Coordinate(86.5, 10.0), Coordinate(86.7, 12.0)),
            listOf(Coordinate(-86.9, 10.0)),
            listOf(Coordinate(10.0, 179.9), Coordinate(10.2, -179.9)),
        )) {
            val box = BaseMapWindow.coverage(route)
            assertNotNull(box, "no window for $route")
            assertTrue(box.maxLatitude > box.minLatitude, "zero-height window for $route")
            assertTrue(box.maxLongitude > box.minLongitude, "zero-width window for $route")
            assertTrue(BaseMapWindow.pixelSize(box).isValid, "unrequestable window for $route")
        }
    }
}
