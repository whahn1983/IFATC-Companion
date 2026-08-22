package com.h3consultingpartners.ifatccompanion.core.weather.radar

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.map.MapProjection
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The precipitation raster is requested for the region the pilot is looking at, so this
 * conversion decides what gets fetched. Getting it wrong is invisible: a slightly wrong
 * region still returns an image, just of somewhere else.
 */
class MapRegionShowingTest {

    private fun viewportOver(coordinates: List<Coordinate>, canvas: Pair<Float, Float>) =
        MapProjection.fitting(coordinates, canvas.first, canvas.second)

    @Test
    fun `the region covers what the viewport is showing`() {
        val route = listOf(Coordinate(41.9786, -87.9048), Coordinate(33.9425, -118.4081))
        val viewport = viewportOver(route, 1080f to 280f)
        assertNotNull(viewport)

        val region = MapRegion.showing(viewport)
        assertNotNull(region)
        for (point in route) {
            assertTrue(
                point in region.boundingBox,
                "$point is on screen but outside the region that would be requested",
            )
        }
    }

    @Test
    fun `the region's centre is the viewport's centre`() {
        val viewport = viewportOver(
            listOf(Coordinate(51.4706, -0.4619), Coordinate(52.3105, 4.7683)),
            1080f to 400f,
        )
        assertNotNull(viewport)
        val region = MapRegion.showing(viewport)
        assertNotNull(region)

        val centre = MapProjection.toCoordinate(
            MapProjection.UnitPoint(viewport.centerX, viewport.centerY),
        )
        assertTrue(abs(region.centerLatitude - centre.latitude) < 0.01)
        assertTrue(abs(region.centerLongitude - centre.longitude) < 0.01)
    }

    @Test
    fun `a viewport wider than the world does not ask for more than the world`() {
        // A fit padded to a wide, short card spans several world-widths. No provider can
        // serve a request wider than the planet, and one would be a nonsense bounding box.
        val world = MapProjection.fitting(
            MapProjection.WORLD_CORNERS, 1080f, 280f, MapProjection.DEFAULT_PADDING_FRACTION,
        )
        assertNotNull(world)
        assertTrue(world.width > 1.0, "this test needs a viewport wider than the world")

        val region = MapRegion.showing(world)
        assertNotNull(region)
        assertTrue(
            region.longitudeDelta <= 360.0,
            "asked for ${region.longitudeDelta}° of longitude",
        )
    }

    @Test
    fun `a degenerate viewport asks for nothing`() {
        assertNull(MapRegion.showing(MapProjection.Viewport(0.5, 0.5, 0.5, 0.5)))
        assertNull(MapRegion.showing(MapProjection.Viewport(0.6, 0.6, 0.4, 0.4)))
    }

    @Test
    fun `zooming in narrows what is requested`() {
        // The whole point of tracking the viewport rather than the route: a pilot who zooms
        // into the weather ahead should get that weather, not a scaled-down picture of the
        // entire leg.
        val route = listOf(Coordinate(41.9786, -87.9048), Coordinate(33.9425, -118.4081))
        val wide = MapRegion.showing(viewportOver(route, 1080f to 400f)!!)
        val tight = MapRegion.showing(
            viewportOver(listOf(Coordinate(38.0, -103.0), Coordinate(38.5, -102.5)), 1080f to 400f)!!,
        )
        assertNotNull(wide); assertNotNull(tight)
        assertTrue(
            tight.longitudeDelta < wide.longitudeDelta,
            "zoomed in but still requesting ${tight.longitudeDelta}° vs ${wide.longitudeDelta}°",
        )
    }
}

/**
 * Mock Mode draws its own precipitation as polygons. Whether the fetched raster is also
 * drawn is a one-line decision that would put two different weathers on the same map.
 */
class RadarRasterGateTest {

    private val covered = RadarOverlayModel(isEnabled = true, coverageAvailable = true)

    @Test
    fun `the raster draws in live mode where a provider covers the route`() {
        assertTrue(covered.shouldDisplayRaster(mockMode = false))
    }

    @Test
    fun `the raster never draws in Mock Mode, whatever the coverage says`() {
        // The trap: useMockProvider puts a covers-everywhere provider in front of the
        // ladder, so coverageAvailable — and therefore shouldDisplay — is true in Mock
        // Mode. Gating on shouldDisplay alone would draw the mock cells and a raster.
        assertFalse(covered.shouldDisplayRaster(mockMode = true))
    }

    @Test
    fun `the raster does not draw with the overlay switched off or no coverage`() {
        assertFalse(covered.copy(isEnabled = false).shouldDisplayRaster(mockMode = false))
        assertFalse(covered.copy(coverageAvailable = false).shouldDisplayRaster(mockMode = false))
    }
}
