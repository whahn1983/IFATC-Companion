package com.h3consultingpartners.ifatccompanion.ui.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.map.CoastlineData
import com.h3consultingpartners.ifatccompanion.core.map.MapGraticule
import com.h3consultingpartners.ifatccompanion.core.map.MapProjection

/**
 * What sits *under* the route: coastlines, a graticule and a scale bar, and optionally a
 * satellite raster.
 *
 * The route map has no base map — every hosted provider was rejected for wanting a key, a
 * bill or a backend, and `Docs/ANDROID_MAPPING.md` records each rejection. Until this
 * existed the consequence was that a flight plan drew on an empty canvas, with nothing to
 * place it against.
 *
 * Three layers, deliberately independent so the loss of one never costs the others:
 *
 *  - **Coastlines** — bundled, public-domain Natural Earth. No network.
 *  - **Graticule and scale bar** — arithmetic on the projection. No network.
 *  - **Satellite imagery** — fetched, and therefore the only one that can fail.
 *
 * When the imagery is unavailable — no signal at altitude, the service down, the fetch not
 * finished — the first two still draw. That degradation is the point of the arrangement
 * rather than a fallback bolted on: the map is legible with no connectivity at all, and
 * imagery is an enhancement on top.
 */

/** Everything the base map draws, resolved and ready. */
data class BaseMapModel(
    val showCoastlines: Boolean = true,
    val showGraticule: Boolean = true,
    val showScaleBar: Boolean = true,
    /**
     * Satellite imagery covering exactly [imageryBounds], or null when there is none —
     * which is the ordinary case offline, and never an error.
     */
    val imagery: ImageBitmap? = null,
    val imageryBounds: GeoBounds? = null,
)

/** A lat/lon window. Used to place fetched imagery on the canvas. */
data class GeoBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
)

/**
 * Draw the base map beneath every other layer.
 *
 * Order matters and is imagery → coastlines → graticule → scale bar: imagery is opaque so
 * it must go first, coastlines read over it, and the graticule reads over both.
 */
fun DrawScope.drawBaseMap(
    frame: MapFrame,
    model: BaseMapModel,
    coastlineColor: Color,
    graticuleColor: Color,
    labelColor: Color,
    textMeasurer: TextMeasurer?,
) {
    model.imagery?.let { image ->
        val bounds = model.imageryBounds
        if (bounds != null) drawImagery(frame, image, bounds)
    }
    if (model.showCoastlines) drawCoastlines(frame, coastlineColor)
    if (model.showGraticule) drawGraticule(frame, graticuleColor, labelColor, textMeasurer)
    if (model.showScaleBar) drawScaleBar(frame, labelColor, textMeasurer)
}

/**
 * Coastlines for the visible window only.
 *
 * The window filter is what keeps this cheap at taxi zoom: without it every frame walks
 * all ~5,000 points of every continent to draw the handful on screen.
 */
private fun DrawScope.drawCoastlines(frame: MapFrame, color: Color) {
    val topLeft = MapProjection.toCoordinate(
        MapProjection.UnitPoint(frame.viewport.minX, frame.viewport.minY),
    )
    val bottomRight = MapProjection.toCoordinate(
        MapProjection.UnitPoint(frame.viewport.maxX, frame.viewport.maxY),
    )
    // Padded so a polyline entering the window between two of its points still draws.
    val pad = 5.0
    val lines = CoastlineData.linesIn(
        south = minOf(topLeft.latitude, bottomRight.latitude) - pad,
        west = minOf(topLeft.longitude, bottomRight.longitude) - pad,
        north = maxOf(topLeft.latitude, bottomRight.latitude) + pad,
        east = maxOf(topLeft.longitude, bottomRight.longitude) + pad,
    )
    for (line in lines) {
        var previous: Offset? = null
        for (coordinate in line) {
            val point = frame.project(coordinate)
            previous?.let { drawLine(color, it, point, strokeWidth = COASTLINE_STROKE) }
            previous = point
        }
    }
}

private fun DrawScope.drawGraticule(
    frame: MapFrame,
    lineColor: Color,
    labelColor: Color,
    textMeasurer: TextMeasurer?,
) {
    val width = frame.canvasSize.width
    val height = frame.canvasSize.height
    if (width <= 0f || height <= 0f) return

    val topLeft = MapProjection.toCoordinate(
        MapProjection.UnitPoint(frame.viewport.minX, frame.viewport.minY),
    )
    val bottomRight = MapProjection.toCoordinate(
        MapProjection.UnitPoint(frame.viewport.maxX, frame.viewport.maxY),
    )

    for (line in MapGraticule.linesFor(frame.viewport)) {
        if (line.isParallel) {
            // Project at both edges rather than assuming a horizontal row of pixels: in
            // Mercator a parallel is horizontal, but deriving it keeps this correct if the
            // projection ever changes.
            val start = frame.project(Coordinate(line.degrees, topLeft.longitude))
            val end = frame.project(Coordinate(line.degrees, bottomRight.longitude))
            drawLine(lineColor, start, end, strokeWidth = GRATICULE_STROKE)
            textMeasurer?.let { measurer ->
                drawGraticuleLabel(measurer, line.label, Offset(LABEL_INSET, start.y - LABEL_INSET * 2), labelColor)
            }
        } else {
            val start = frame.project(Coordinate(topLeft.latitude, line.degrees))
            val end = frame.project(Coordinate(bottomRight.latitude, line.degrees))
            drawLine(lineColor, start, end, strokeWidth = GRATICULE_STROKE)
            textMeasurer?.let { measurer ->
                drawGraticuleLabel(measurer, line.label, Offset(start.x + LABEL_INSET, LABEL_INSET), labelColor)
            }
        }
    }
}

private fun DrawScope.drawScaleBar(frame: MapFrame, color: Color, textMeasurer: TextMeasurer?) {
    val bar = MapGraticule.scaleBarFor(frame.viewport) ?: return
    val width = frame.canvasSize.width
    val height = frame.canvasSize.height
    if (width <= 0f || height <= 0f) return

    val barWidth = (bar.widthFraction * width).toFloat()
    if (barWidth <= 0f || barWidth > width) return

    val y = height - SCALE_BAR_INSET
    val left = SCALE_BAR_INSET
    drawLine(color, Offset(left, y), Offset(left + barWidth, y), strokeWidth = SCALE_BAR_STROKE)
    // End ticks, so the bar reads as a measurement rather than an underline.
    drawLine(color, Offset(left, y - SCALE_TICK), Offset(left, y + SCALE_TICK), strokeWidth = SCALE_BAR_STROKE)
    drawLine(
        color,
        Offset(left + barWidth, y - SCALE_TICK),
        Offset(left + barWidth, y + SCALE_TICK),
        strokeWidth = SCALE_BAR_STROKE,
    )
    textMeasurer?.let { measurer ->
        drawGraticuleLabel(measurer, bar.label, Offset(left, y - SCALE_TICK - LABEL_HEIGHT), color)
    }
}

/**
 * Place fetched imagery on the canvas by projecting the corners of the window it covers.
 *
 * The imagery is requested in Web Mercator — the same projection this map uses, and the
 * one NASA GIBS and NOAA both publish in — so placing it is a straight blit into the
 * projected rectangle, with no reprojection.
 */
private fun DrawScope.drawImagery(frame: MapFrame, image: ImageBitmap, bounds: GeoBounds) {
    val topLeft = frame.project(Coordinate(bounds.north, bounds.west))
    val bottomRight = frame.project(Coordinate(bounds.south, bounds.east))
    val targetWidth = bottomRight.x - topLeft.x
    val targetHeight = bottomRight.y - topLeft.y
    if (targetWidth <= 0f || targetHeight <= 0f) return
    if (image.width <= 0 || image.height <= 0) return

    // dstOffset/dstSize rather than a translate-and-scale pair: one call, no nested
    // transforms to leak into the layers drawn after this one.
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
        dstSize = IntSize(targetWidth.toInt().coerceAtLeast(1), targetHeight.toInt().coerceAtLeast(1)),
        alpha = IMAGERY_ALPHA,
    )
}

private fun DrawScope.drawGraticuleLabel(
    measurer: TextMeasurer,
    text: String,
    at: Offset,
    color: Color,
) {
    val layout = measurer.measure(text, TextStyle(fontSize = LABEL_FONT_SIZE, color = color))
    drawText(layout, topLeft = at)
}

private const val COASTLINE_STROKE = 1.4f
private const val GRATICULE_STROKE = 0.7f
private const val SCALE_BAR_STROKE = 2f
private const val SCALE_TICK = 5f
private const val SCALE_BAR_INSET = 14f
private const val LABEL_INSET = 4f
private const val LABEL_HEIGHT = 14f
private const val IMAGERY_ALPHA = 0.85f
private val LABEL_FONT_SIZE = 9.sp
