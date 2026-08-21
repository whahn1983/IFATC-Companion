package com.h3consultingpartners.ifatccompanion.core.map

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app renders its own maps, so the projection is the app's own responsibility
 * rather than MapKit's — which means it can be, and is, tested.
 */
class MapProjectionTest {

    @Test
    fun nullIslandProjectsToTheCentreOfTheWorldSquare() {
        val point = MapProjection.toUnit(Coordinate(0.0, 0.0))
        assertEquals(0.5, point.x, 1e-12)
        assertEquals(0.5, point.y, 1e-12)
    }

    @Test
    fun northWestCornerProjectsToTheOrigin() {
        val point = MapProjection.toUnit(Coordinate(MapProjection.MAX_LATITUDE, -180.0))
        assertEquals(0.0, point.x, 1e-9)
        assertEquals(0.0, point.y, 1e-9)
    }

    @Test
    fun projectionRoundTripsForAirportsAcrossTheWorld() {
        val places = listOf(
            Coordinate(29.9844, -95.3414), // KIAH
            Coordinate(51.4700, -0.4543), // EGLL
            Coordinate(-33.9461, 151.1772), // YSSY
            Coordinate(64.1300, -21.9406), // BIRK
            Coordinate(-53.7783, -67.7494), // SAWE, deep southern
        )
        for (place in places) {
            val back = MapProjection.toCoordinate(MapProjection.toUnit(place))
            assertEquals(place.latitude, back.latitude, 1e-9, "latitude for $place")
            assertEquals(place.longitude, back.longitude, 1e-9, "longitude for $place")
        }
    }

    @Test
    fun latitudesBeyondTheMercatorLimitAreClampedRatherThanRunningToInfinity() {
        val point = MapProjection.toUnit(Coordinate(89.9, 0.0))
        assertTrue(point.y.isFinite())
        assertTrue(point.y >= 0.0, "a clamped north pole must stay inside the world square")
    }

    @Test
    fun screenRoundTripIsExactForAViewport() {
        val viewport = MapProjection.Viewport(0.25, 0.30, 0.35, 0.40)
        val original = MapProjection.UnitPoint(0.28, 0.33)
        val screen = viewport.toScreen(original, 400f, 400f)
        val back = viewport.toUnit(screen, 400f, 400f)
        assertEquals(original.x, back.x, 1e-6)
        assertEquals(original.y, back.y, 1e-6)
    }

    @Test
    fun fittingFramesEveryCoordinateWithMargin() {
        val route = listOf(
            Coordinate(29.9844, -95.3414), // KIAH
            Coordinate(35.0, -93.0),
            Coordinate(44.8848, -93.2223), // KMSP
        )
        val viewport = assertNotNull(MapProjection.fitting(route, 800f, 1200f))
        for (coordinate in route) {
            assertTrue(
                MapProjection.isVisible(coordinate, viewport),
                "$coordinate must be inside the fitted viewport",
            )
        }
    }

    @Test
    fun fittingCorrectsForTheCanvasAspectSoTheMapIsNotStretched() {
        val route = listOf(Coordinate(30.0, -100.0), Coordinate(30.0, -90.0))
        val canvasWidth = 400f
        val canvasHeight = 800f
        val viewport = assertNotNull(MapProjection.fitting(route, canvasWidth, canvasHeight))
        // The viewport's aspect must equal the canvas's, otherwise a circle drawn on the
        // map would come out an ellipse.
        assertEquals(
            (canvasWidth / canvasHeight).toDouble(),
            viewport.width / viewport.height,
            1e-6,
        )
    }

    @Test
    fun fittingASinglePointStillFramesUsefulGround() {
        // A taxi map fitted the moment a route is issued can be handed one stand. It must
        // not zoom to a pixel.
        val viewport = assertNotNull(
            MapProjection.fitting(listOf(Coordinate(29.9844, -95.3414)), 600f, 600f),
        )
        val spanNM = viewport.widthNM()
        assertTrue(
            spanNM >= MapProjection.DEFAULT_MINIMUM_SPAN_NM,
            "a single point was framed at ${spanNM}NM, tighter than the minimum span",
        )
    }

    @Test
    fun fittingNothingReturnsNothing() {
        assertNull(MapProjection.fitting(emptyList(), 600f, 600f))
        assertNull(MapProjection.fitting(listOf(Coordinate(0.0, 1.0)), 0f, 600f))
    }

    @Test
    fun zoomKeepsTheFocalPointUnderTheFingers() {
        val viewport = MapProjection.Viewport(0.2, 0.2, 0.4, 0.4)
        val focusX = 150f
        val focusY = 90f
        val before = viewport.toUnit(MapProjection.ScreenPoint(focusX, focusY), 300f, 300f)
        val zoomed = viewport.zoomed(2.0, focusX, focusY, 300f, 300f)
        val after = zoomed.toUnit(MapProjection.ScreenPoint(focusX, focusY), 300f, 300f)
        assertEquals(before.x, after.x, 1e-9)
        assertEquals(before.y, after.y, 1e-9)
        assertTrue(zoomed.width < viewport.width, "zooming in must narrow the viewport")
    }

    @Test
    fun panMovesTheMapWithTheFinger() {
        val viewport = MapProjection.Viewport(0.2, 0.2, 0.4, 0.4)
        // Dragging the map to the right shows ground further west, so minX decreases.
        val panned = viewport.panned(60f, 0f, 300f, 300f)
        assertTrue(panned.minX < viewport.minX)
        assertEquals(viewport.width, panned.width, 1e-12)
    }

    @Test
    fun panningNorthCannotLeaveTheWorldSquare() {
        val viewport = MapProjection.Viewport(0.2, 0.0, 0.4, 0.2)
        val panned = viewport.panned(0f, 400f, 300f, 300f)
        assertTrue(panned.minY >= 0.0, "the map must not pan past the top of the world")
    }

    @Test
    fun widthInNauticalMilesMatchesTheGreatCircleAcrossTheViewport() {
        val viewport = assertNotNull(
            MapProjection.fitting(
                listOf(Coordinate(30.0, -100.0), Coordinate(30.0, -90.0)),
                600f,
                600f,
            ),
        )
        val west = MapProjection.toCoordinate(
            MapProjection.UnitPoint(viewport.minX, viewport.centerY),
        )
        val east = MapProjection.toCoordinate(
            MapProjection.UnitPoint(viewport.maxX, viewport.centerY),
        )
        val measured = Geo.distanceNM(west, east)
        // Within a couple of percent: widthNM uses the centre latitude's cosine, while the
        // great circle follows the true path.
        assertTrue(
            abs(measured - viewport.widthNM()) / measured < 0.02,
            "widthNM ${viewport.widthNM()} disagrees with the measured $measured",
        )
    }

    @Test
    fun tileZoomGrowsAsTheViewportNarrows() {
        val wide = MapProjection.Viewport(0.0, 0.4, 1.0, 0.6)
        val tight = MapProjection.Viewport(0.4990, 0.4990, 0.5010, 0.5010)
        assertTrue(MapProjection.tileZoom(tight, 1080f) > MapProjection.tileZoom(wide, 1080f))
        assertTrue(MapProjection.tileZoom(tight, 1080f) <= MapProjection.MAX_TILE_ZOOM)
    }
}
