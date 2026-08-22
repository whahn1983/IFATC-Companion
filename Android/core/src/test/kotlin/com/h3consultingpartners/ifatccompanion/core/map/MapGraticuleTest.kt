package com.h3consultingpartners.ifatccompanion.core.map

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The graticule is the route map's only sense of where it is, and the scale bar its only
 * sense of how far. Both are arithmetic, so both are pinned here rather than judged by
 * looking at a screen.
 */
class MapGraticuleTest {

    private fun viewportAround(
        center: Coordinate,
        spanDegreesLongitude: Double,
    ): MapProjection.Viewport {
        val half = spanDegreesLongitude / 2
        val west = MapProjection.toUnit(Coordinate(center.latitude, center.longitude - half))
        val east = MapProjection.toUnit(Coordinate(center.latitude, center.longitude + half))
        val north = MapProjection.toUnit(Coordinate(center.latitude + half / 2, center.longitude))
        val south = MapProjection.toUnit(Coordinate(center.latitude - half / 2, center.longitude))
        return MapProjection.Viewport(
            minX = west.x,
            minY = north.y,
            maxX = east.x,
            maxY = south.y,
        )
    }

    // region Spacing

    @Test
    fun `spacing gets finer as the span shrinks`() {
        val wide = MapGraticule.spacingFor(120.0)
        val mid = MapGraticule.spacingFor(12.0)
        val tight = MapGraticule.spacingFor(0.4)
        assertNotNull(wide); assertNotNull(mid); assertNotNull(tight)
        assertTrue(wide > mid, "a wider span should use a coarser spacing")
        assertTrue(mid > tight, "a tighter span should use a finer spacing")
    }

    @Test
    fun `spacing keeps the grid readable rather than dense`() {
        // Whatever the span, the count across it should stay in single figures. A grid
        // that competes with the route for attention is worse than no grid.
        for (span in listOf(0.05, 0.5, 3.0, 12.0, 60.0, 180.0)) {
            val step = MapGraticule.spacingFor(span)
            assertNotNull(step, "span $span should yield a spacing")
            val count = span / step
            assertTrue(count <= 12.0, "span $span gave ${count.toInt()} lines at $step°")
        }
    }

    @Test
    fun `a degenerate span has no spacing`() {
        assertNull(MapGraticule.spacingFor(0.0))
        assertNull(MapGraticule.spacingFor(-5.0))
    }

    // endregion

    // region Lines

    @Test
    fun `lines fall inside the viewport and cover both axes`() {
        val viewport = viewportAround(Coordinate(35.0, -95.0), spanDegreesLongitude = 20.0)
        val lines = MapGraticule.linesFor(viewport)

        assertTrue(lines.any { it.isParallel }, "expected parallels")
        assertTrue(lines.any { !it.isParallel }, "expected meridians")

        val topLeft = MapProjection.toCoordinate(MapProjection.UnitPoint(viewport.minX, viewport.minY))
        val bottomRight = MapProjection.toCoordinate(MapProjection.UnitPoint(viewport.maxX, viewport.maxY))
        for (line in lines.filter { it.isParallel }) {
            assertTrue(
                line.degrees <= topLeft.latitude + 1e-6 && line.degrees >= bottomRight.latitude - 1e-6,
                "parallel ${line.degrees} outside the viewport",
            )
        }
    }

    // endregion

    // region Labels

    @Test
    fun `latitude labels carry a hemisphere`() {
        assertEquals("35°N", MapGraticule.latitudeLabel(35.0, step = 1.0))
        assertEquals("35°S", MapGraticule.latitudeLabel(-35.0, step = 1.0))
        assertEquals("0°N", MapGraticule.latitudeLabel(0.0, step = 1.0))
    }

    @Test
    fun `longitude labels are zero-padded to three digits like a chart`() {
        assertEquals("095°W", MapGraticule.longitudeLabel(-95.0, step = 1.0))
        assertEquals("005°E", MapGraticule.longitudeLabel(5.0, step = 1.0))
        assertEquals("180°E", MapGraticule.longitudeLabel(180.0, step = 1.0))
    }

    @Test
    fun `a longitude past the antimeridian is normalised, not printed as 190`() {
        // A viewport panned across the dateline produces meridians beyond ±180. Printing
        // "190°E" would be wrong on a chart and meaningless to a pilot.
        assertEquals("170°W", MapGraticule.longitudeLabel(190.0, step = 1.0))
        assertEquals("170°E", MapGraticule.longitudeLabel(-190.0, step = 1.0))
    }

    @Test
    fun `a fine spacing adds decimals`() {
        assertEquals("35.5°N", MapGraticule.latitudeLabel(35.5, step = 0.5))
        assertEquals("35°N", MapGraticule.latitudeLabel(35.0, step = 1.0))
    }

    // endregion

    // region Scale bar

    @Test
    fun `the scale bar rounds down so it always fits`() {
        val viewport = viewportAround(Coordinate(35.0, -95.0), spanDegreesLongitude = 20.0)
        val bar = MapGraticule.scaleBarFor(viewport, targetFraction = 0.25)
        assertNotNull(bar)
        assertTrue(
            bar.widthFraction <= 0.25 + 1e-9,
            "bar spans ${bar.widthFraction} of the canvas, wider than its target",
        )
        assertTrue(bar.widthFraction > 0.0)
        assertTrue(bar.label.endsWith("NM"), "unexpected label ${bar.label}")
    }

    @Test
    fun `the scale bar distance shrinks as the map zooms in`() {
        val wide = MapGraticule.scaleBarFor(viewportAround(Coordinate(35.0, -95.0), 40.0))
        val tight = MapGraticule.scaleBarFor(viewportAround(Coordinate(35.0, -95.0), 2.0))
        assertNotNull(wide); assertNotNull(tight)
        assertTrue(
            tight.distanceNM < wide.distanceNM,
            "zooming in should shorten the bar: ${tight.distanceNM} vs ${wide.distanceNM}",
        )
    }

    // endregion

    // region A viewport wider than the world

    @Test
    fun `a world-wide viewport draws each meridian once`() {
        // A fit padded to a wide canvas is wider than the world, so the same real meridian
        // falls inside it more than once. Only one world's worth of coastline is drawn, so
        // emitting both copies stacks gridlines on top of each other — each labelled with a
        // different longitude, which reads as a rendering fault rather than a maths one.
        val wide = MapProjection.fitting(
            MapProjection.WORLD_CORNERS, 1080f, 280f,
            MapProjection.DEFAULT_PADDING_FRACTION,
        )
        assertNotNull(wide)
        assertTrue(wide.width > 1.0, "this test is only meaningful for a viewport wider than the world")

        val meridians = MapGraticule.linesFor(wide).filter { !it.isParallel }
        val labels = meridians.map { it.label }
        assertEquals(labels.size, labels.toSet().size, "duplicate meridians: $labels")

        val places = meridians.map { MapProjection.toUnit(Coordinate(0.0, it.degrees)).x }
        for ((a, b) in places.zipWithNext()) {
            assertTrue(abs(a - b) > 1e-9, "two meridians projected to the same place: $places")
        }
    }

    @Test
    fun `an enormous span does not walk forever`() {
        // The spacing table bottoms out at 30 degrees, so without a stop a viewport far
        // wider than the world walks hundreds of steps inside a draw phase.
        val enormous = MapProjection.Viewport(minX = -40.0, minY = 0.0, maxX = 40.0, maxY = 1.0)
        val lines = MapGraticule.linesFor(enormous)
        assertTrue(lines.size < 200, "produced ${lines.size} lines for one frame")
    }

    // endregion
}
