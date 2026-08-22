package com.h3consultingpartners.ifatccompanion.core.map

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Web Mercator (EPSG:3857) projection and viewport maths for the app's own map
 * renderer.
 *
 * The iOS app draws its route and taxi maps on MapKit. Android has no free equivalent
 * that does not require an API key, a billing account or a tile bill (see
 * Docs/ANDROID_MAPPING.md for why each candidate was rejected), so the Android build
 * renders the same content itself onto a Compose canvas. That makes the projection the
 * app's own responsibility — and, usefully, a pure function that can be unit tested,
 * which is not something MapKit's own transform ever was.
 *
 * Web Mercator is the projection every tile service and every piece of weather imagery
 * the app already fetches is published in (NOAA's radar export and NASA GIBS both serve
 * EPSG:3857), so choosing it means the precipitation overlay lands on the map without
 * being reprojected.
 *
 * The unit space is the standard normalised one: x and y each run 0…1 across the whole
 * world, with (0, 0) at the north-west corner (180° W, ~85.051° N).
 */
object MapProjection {

    /**
     * The latitude limit of Web Mercator. Beyond it the projection runs to infinity, so
     * every latitude is clamped here — the same limit the tile services themselves use.
     */
    const val MAX_LATITUDE = 85.05112877980659

    /**
     * The whole world, as the two corners a fit can be built from.
     *
     * Used when a map has nothing of its own to frame yet. Going through [fitting] rather
     * than hard-coding the unit square is what applies the canvas aspect correction, so a
     * wide, short map card shows the world in proportion instead of stretched.
     */
    val WORLD_CORNERS: List<Coordinate> = listOf(
        Coordinate(MAX_LATITUDE, -180.0),
        Coordinate(-MAX_LATITUDE, 180.0),
    )

    /** Project a coordinate into the 0…1 unit square. */
    fun toUnit(coordinate: Coordinate): UnitPoint {
        val latitude = coordinate.latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val x = (coordinate.longitude + 180.0) / 360.0
        val sinLat = tan(PI / 4 + latitude * PI / 360)
        val y = 0.5 - ln(sinLat) / (2 * PI)
        // At exactly the Mercator limit the arithmetic lands a hair either side of the
        // edge, so the result is pinned to the unit square rather than left to a rounding
        // error a renderer would have to defend against.
        return UnitPoint(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0))
    }

    /** Invert [toUnit]. */
    fun toCoordinate(point: UnitPoint): Coordinate {
        val longitude = point.x * 360.0 - 180.0
        val n = PI * (1 - 2 * point.y)
        val latitude = 180.0 / PI * atan(sinh(n))
        return Coordinate(latitude, longitude)
    }

    /** A point in the normalised 0…1 world square. */
    data class UnitPoint(val x: Double, val y: Double)

    /**
     * What the map is currently showing: the unit-space rectangle mapped onto the
     * canvas. Kept as a rectangle rather than a centre-and-zoom because every operation
     * the renderer needs — fitting a route, drawing a polyline, hit-testing a tap — is
     * simpler against a rectangle, and because the aspect correction has to be applied
     * somewhere and doing it once here keeps it out of every layer.
     */
    data class Viewport(
        val minX: Double,
        val minY: Double,
        val maxX: Double,
        val maxY: Double,
    ) {
        val width: Double get() = maxX - minX
        val height: Double get() = maxY - minY
        val centerX: Double get() = (minX + maxX) / 2
        val centerY: Double get() = (minY + maxY) / 2

        /** Project a unit point onto a canvas of [canvasWidth] × [canvasHeight] pixels. */
        fun toScreen(point: UnitPoint, canvasWidth: Float, canvasHeight: Float): ScreenPoint =
            ScreenPoint(
                x = (((point.x - minX) / width) * canvasWidth).toFloat(),
                y = (((point.y - minY) / height) * canvasHeight).toFloat(),
            )

        /** Invert [toScreen]. */
        fun toUnit(point: ScreenPoint, canvasWidth: Float, canvasHeight: Float): UnitPoint =
            UnitPoint(
                x = minX + (point.x / canvasWidth) * width,
                y = minY + (point.y / canvasHeight) * height,
            )

        /** Pan by a screen-space delta, in pixels. */
        fun panned(dxPixels: Float, dyPixels: Float, canvasWidth: Float, canvasHeight: Float): Viewport {
            if (canvasWidth <= 0f || canvasHeight <= 0f) return this
            val dx = -(dxPixels / canvasWidth) * width
            val dy = -(dyPixels / canvasHeight) * height
            return Viewport(minX + dx, minY + dy, maxX + dx, maxY + dy).clampedToWorld()
        }

        /**
         * Zoom by [factor] about a focal point in screen space, so a pinch keeps the
         * point under the fingers fixed.
         */
        fun zoomed(
            factor: Double,
            focusX: Float,
            focusY: Float,
            canvasWidth: Float,
            canvasHeight: Float,
        ): Viewport {
            if (canvasWidth <= 0f || canvasHeight <= 0f || factor <= 0.0) return this
            val focus = toUnit(ScreenPoint(focusX, focusY), canvasWidth, canvasHeight)
            val newWidth = (width / factor).coerceIn(MIN_SPAN, 1.0)
            val newHeight = (height / factor).coerceIn(MIN_SPAN, 1.0)
            // Keep the focal point at the same fractional position in the new rectangle.
            val fx = if (width == 0.0) 0.5 else (focus.x - minX) / width
            val fy = if (height == 0.0) 0.5 else (focus.y - minY) / height
            val newMinX = focus.x - fx * newWidth
            val newMinY = focus.y - fy * newHeight
            return Viewport(newMinX, newMinY, newMinX + newWidth, newMinY + newHeight)
                .clampedToWorld()
        }

        /**
         * Keep the viewport inside the world square vertically and no larger than it.
         * Longitude is left free to wrap so a route crossing the antimeridian still draws
         * as one line rather than snapping back across the whole world.
         */
        fun clampedToWorld(): Viewport {
            var h = height.coerceIn(MIN_SPAN, 1.0)
            var top = minY
            if (top < 0.0) top = 0.0
            if (top + h > 1.0) top = max(0.0, 1.0 - h)
            if (h > 1.0) h = 1.0
            return Viewport(minX, top, minX + width, top + h)
        }

        /** The span of this viewport in nautical miles at its centre latitude. */
        fun widthNM(): Double {
            val center = MapProjection.toCoordinate(UnitPoint(centerX, centerY))
            return width * WORLD_CIRCUMFERENCE_NM * cos(center.latitude * PI / 180)
        }
    }

    /** A point in canvas pixels. */
    data class ScreenPoint(val x: Float, val y: Float)

    /**
     * The smallest viewport span allowed, ~1 metre of world at the equator. Small enough
     * for a stand marker on a taxi map, large enough that the arithmetic stays well away
     * from floating-point trouble.
     */
    const val MIN_SPAN = 1.0 / (1 shl 25)

    /** Earth's circumference at the equator, in nautical miles. */
    const val WORLD_CIRCUMFERENCE_NM = 21_638.8

    /**
     * The viewport that fits every one of [coordinates] with [paddingFraction] of the
     * span left as margin, corrected so the map is not stretched on a non-square canvas.
     *
     * This is what the taxi map does when a taxi route is issued ("automatically fit the
     * assigned taxi route") and what the weather map does when the route loads. Returns
     * null when there is nothing to fit.
     */
    fun fitting(
        coordinates: List<Coordinate>,
        canvasWidth: Float,
        canvasHeight: Float,
        paddingFraction: Double = DEFAULT_PADDING_FRACTION,
        minimumSpanNM: Double = DEFAULT_MINIMUM_SPAN_NM,
    ): Viewport? {
        if (coordinates.isEmpty() || canvasWidth <= 0f || canvasHeight <= 0f) return null

        val points = coordinates.map(::toUnit)
        var minX = points.first().x
        var maxX = points.first().x
        var minY = points.first().y
        var maxY = points.first().y
        for (point in points) {
            minX = min(minX, point.x)
            maxX = max(maxX, point.x)
            minY = min(minY, point.y)
            maxY = max(maxY, point.y)
        }

        // A single point, or a perfectly straight north–south or east–west line, has zero
        // extent in one axis; give it the minimum span so it is still framed sensibly
        // rather than zoomed to a pixel.
        val centerLatitude = toCoordinate(UnitPoint((minX + maxX) / 2, (minY + maxY) / 2)).latitude
        val minimumSpan = minimumSpanNM /
            (WORLD_CIRCUMFERENCE_NM * max(0.05, cos(centerLatitude * PI / 180)))

        var spanX = max(maxX - minX, minimumSpan)
        var spanY = max(maxY - minY, minimumSpan)

        spanX *= (1 + 2 * paddingFraction)
        spanY *= (1 + 2 * paddingFraction)

        // Correct for the canvas aspect so a wide route on a tall screen is not squashed:
        // whichever axis needs more room sets the scale, and the other is widened to match.
        val canvasAspect = canvasWidth / canvasHeight
        val spanAspect = spanX / spanY
        if (spanAspect < canvasAspect) {
            spanX = spanY * canvasAspect
        } else {
            spanY = spanX / canvasAspect
        }

        val centerX = (minX + maxX) / 2
        val centerY = (minY + maxY) / 2
        return Viewport(
            minX = centerX - spanX / 2,
            minY = centerY - spanY / 2,
            maxX = centerX + spanX / 2,
            maxY = centerY + spanY / 2,
        ).clampedToWorld()
    }

    /** Leave an eighth of the span as margin on each side. */
    const val DEFAULT_PADDING_FRACTION = 0.125

    /**
     * The tightest the map ever zooms when fitting: roughly a large airport's width, so
     * fitting a taxi route that is only a few hundred feet long still shows enough of the
     * field around it to be legible.
     */
    const val DEFAULT_MINIMUM_SPAN_NM = 0.6

    /**
     * The heading, in screen terms, for a true heading. Screen y grows downward while
     * bearings grow clockwise from north, and Web Mercator is north-up and conformal, so
     * a true bearing is already the on-screen rotation — but going through this function
     * keeps every layer from re-deriving that and getting it wrong.
     */
    fun screenRotationDegrees(trueHeadingDegrees: Double): Float =
        ((trueHeadingDegrees % 360) + 360).mod(360.0).toFloat()

    /** Whether [coordinate] currently falls inside [viewport], with no margin. */
    fun isVisible(coordinate: Coordinate, viewport: Viewport): Boolean {
        val point = toUnit(coordinate)
        return point.x in viewport.minX..viewport.maxX && point.y in viewport.minY..viewport.maxY
    }

    /**
     * The zoom level whose tiles best match [viewport] on a canvas [canvasWidth] pixels
     * wide, for fetching precipitation imagery at a sensible resolution.
     */
    fun tileZoom(viewport: Viewport, canvasWidth: Float, tileSize: Int = 256): Int {
        if (viewport.width <= 0 || canvasWidth <= 0f) return 0
        val tilesAcross = canvasWidth / tileSize
        val worldTiles = tilesAcross / viewport.width
        val zoom = ln(max(1.0, worldTiles)) / ln(2.0)
        return zoom.toInt().coerceIn(0, MAX_TILE_ZOOM)
    }

    const val MAX_TILE_ZOOM = 20

    /** Guard against a NaN or infinity reaching the canvas and blanking the whole map. */
    internal fun Double.finiteOr(fallback: Double): Double =
        if (isFinite() && abs(this) < 1e12) this else fallback

    /** Exposed for tests: the inverse Gudermannian used by [toCoordinate]. */
    internal fun inverseGudermannian(y: Double): Double = 2 * atan(exp(y)) - PI / 2
}
