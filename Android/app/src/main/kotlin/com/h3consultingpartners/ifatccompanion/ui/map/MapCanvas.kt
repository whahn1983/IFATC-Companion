package com.h3consultingpartners.ifatccompanion.ui.map

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.map.MapProjection

/**
 * The drawing surface both maps share.
 *
 * The iOS app draws its route and taxi maps on MapKit. No Android mapping SDK reaches
 * this app's bar — free of API keys, free of a billing account, free of a recurring
 * tile bill, and licensed for commercial use with OSM-derived overlays — so the Android
 * build renders the same content itself. See Docs/ANDROID_MAPPING.md for what was
 * considered and why.
 *
 * That trade is less painful than it sounds, because neither of this app's maps is a
 * *place* map. The taxi map draws an airport surface diagram from OpenStreetMap
 * geometry; the weather map draws a route, an aircraft, hazard shapes and a
 * precipitation raster. Both are the app's own data over a coordinate frame, and none
 * of it needs a street-level base map underneath. What it does need — a correct
 * projection, pan, pinch-zoom, and a fit-to-content — is here, and unlike MapKit's
 * transform it is unit tested (see MapProjectionTest).
 */
@Composable
fun MapCanvas(
    state: MapCanvasState,
    modifier: Modifier = Modifier,
    onTap: ((Coordinate) -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
    draw: DrawScope.(MapFrame) -> Unit,
) {
    Box(
        modifier = modifier
            .onSizeChanged { state.onSizeChanged(it) }
            .pointerInput(state.interactive) {
                if (!state.interactive) return@pointerInput
                detectTransformGestures { centroid, pan, zoom, _ ->
                    state.onGesture(centroid, pan, zoom)
                }
            }
            .pointerInput(onTap, state.interactive) {
                if (onTap == null) return@pointerInput
                detectTapGestures { offset -> state.coordinateAt(offset)?.let(onTap) }
            },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val viewport = state.viewport ?: return@Canvas
            draw(MapFrame(viewport, size))
        }
        overlay()
    }
}

/**
 * One frame's projection context, handed to every layer so they all agree on where a
 * coordinate lands.
 */
data class MapFrame(
    val viewport: MapProjection.Viewport,
    val canvasSize: Size,
) {
    /** Project a coordinate to canvas pixels. */
    fun project(coordinate: Coordinate): Offset {
        val point = viewport.toScreen(
            MapProjection.toUnit(coordinate),
            canvasSize.width,
            canvasSize.height,
        )
        return Offset(point.x, point.y)
    }

    /** How many pixels one nautical mile covers at the viewport's centre. */
    val pixelsPerNM: Float
        get() {
            val widthNM = viewport.widthNM()
            return if (widthNM <= 0.0) 0f else (canvasSize.width / widthNM).toFloat()
        }

    /**
     * Whether a projected point is near enough to the canvas to be worth drawing. Layers
     * cull with this rather than trusting the clip, because a route leg can project
     * millions of pixels off-screen and some draw calls degrade badly on those.
     */
    fun isNearCanvas(point: Offset, marginPixels: Float = 2_000f): Boolean =
        point.x > -marginPixels && point.x < canvasSize.width + marginPixels &&
            point.y > -marginPixels && point.y < canvasSize.height + marginPixels
}

/**
 * The map's viewport and gesture state.
 *
 * [fitTo] is what the taxi map calls when a taxi route is issued and what the weather
 * map calls when the route loads. Once the pilot pans or pinches, [hasUserAdjusted]
 * latches so a routine data refresh does not yank the view back — but an explicit
 * re-fit (the recentre button, or a new taxi clearance) always wins.
 */
class MapCanvasState(
    val interactive: Boolean = true,
    /**
     * What to frame when the map has nothing of its own to frame.
     *
     * Without this the viewport stays null until [fitTo] is given a non-empty list, and
     * `MapCanvas` skips its entire draw lambda — so a map whose content has not resolved
     * draws *nothing at all*, base layers included. That is fine for the taxi map, which
     * has no content until a route exists, and wrong for the route map, which has bundled
     * coastlines and a graticule it could be showing meanwhile.
     */
    private val defaultFit: List<Coordinate> = emptyList(),
) {
    var viewport: MapProjection.Viewport? by mutableStateOf(null)
        private set

    var hasUserAdjusted: Boolean by mutableStateOf(false)
        private set

    private var canvasSize: IntSize = IntSize.Zero
    private var pendingFit: List<Coordinate> = emptyList()
    private var pendingPadding: Double = MapProjection.DEFAULT_PADDING_FRACTION

    internal fun onSizeChanged(size: IntSize) {
        canvasSize = size

        // The pilot has put the map somewhere of their own choosing, so a resize must not
        // take it back. `fitTo` returns before recording a pending fit once that latches,
        // so re-fitting here would restore whatever was pending *before* they moved —
        // which, since the default fit is promoted below, is the whole world. The activity
        // handles orientation itself, so this state survives a rotation and that snap-back
        // would be the visible result of one.
        if (hasUserAdjusted) {
            viewport = viewport?.withCanvasAspect(size.width.toFloat(), size.height.toFloat())
            return
        }

        // Nothing has asked for a frame yet, so adopt the default one. Promoting it to the
        // pending fit rather than applying it once is what keeps the aspect right across a
        // resize; [fitTo] replaces it as soon as there is real content to frame.
        if (pendingFit.isEmpty() && defaultFit.isNotEmpty()) {
            pendingFit = defaultFit
            pendingPadding = MapProjection.DEFAULT_PADDING_FRACTION
        }
        // A fit cannot be applied before the canvas has a size, so the first layout pass is
        // where it actually takes effect.
        if (pendingFit.isNotEmpty()) applyFit(pendingFit, pendingPadding)
    }

    /**
     * Frame [coordinates]. Ignored while the pilot has the map somewhere of their own
     * choosing unless [force] is set.
     */
    fun fitTo(
        coordinates: List<Coordinate>,
        padding: Double = MapProjection.DEFAULT_PADDING_FRACTION,
        force: Boolean = false,
    ) {
        if (coordinates.isEmpty()) return
        if (hasUserAdjusted && !force) return
        if (force) hasUserAdjusted = false
        pendingFit = coordinates
        pendingPadding = padding
        applyFit(coordinates, padding)
    }

    private fun applyFit(coordinates: List<Coordinate>, padding: Double) {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return
        MapProjection.fitting(
            coordinates = coordinates,
            canvasWidth = canvasSize.width.toFloat(),
            canvasHeight = canvasSize.height.toFloat(),
            paddingFraction = padding,
        )?.let { viewport = it }
    }

    internal fun onGesture(centroid: Offset, pan: Offset, zoom: Float) {
        val current = viewport ?: return
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return
        val width = canvasSize.width.toFloat()
        val height = canvasSize.height.toFloat()

        var next = current
        if (zoom != 1f && zoom > 0f) {
            next = next.zoomed(zoom.toDouble(), centroid.x, centroid.y, width, height)
        }
        if (pan != Offset.Zero) {
            next = next.panned(pan.x, pan.y, width, height)
        }
        if (next != current) {
            viewport = next
            hasUserAdjusted = true
        }
    }

    internal fun coordinateAt(offset: Offset): Coordinate? {
        val current = viewport ?: return null
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return null
        return MapProjection.toCoordinate(
            current.toUnit(
                MapProjection.ScreenPoint(offset.x, offset.y),
                canvasSize.width.toFloat(),
                canvasSize.height.toFloat(),
            ),
        )
    }

    /** Drop the "the pilot moved it" latch, so the next data-driven fit takes effect. */
    fun releaseUserAdjustment() {
        hasUserAdjusted = false
    }
}

@Composable
fun rememberMapCanvasState(
    interactive: Boolean = true,
    defaultFit: List<Coordinate> = emptyList(),
): MapCanvasState = remember(interactive, defaultFit) { MapCanvasState(interactive, defaultFit) }
