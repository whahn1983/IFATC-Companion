package com.h3consultingpartners.ifatccompanion.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceConfidence
import com.h3consultingpartners.ifatccompanion.ui.theme.IFATCTheme

/** One runway crossing on the route, and whether it is the one being worked. */
data class TaxiCrossingMarker(
    val point: Coordinate,
    val runwayIdent: String,
    val isActive: Boolean,
)

/** Everything the taxi map draws, already resolved to coordinates. */
data class TaxiMapModel(
    /** The assigned taxi route, in order. */
    val route: List<Coordinate> = emptyList(),
    val routeConfidence: SurfaceConfidence = SurfaceConfidence.HIGH,
    /** Only the runways the route crosses or ends at — see the note on [TaxiMap]. */
    val relevantRunways: List<List<Coordinate>> = emptyList(),
    val holdingPositions: List<Coordinate> = emptyList(),
    val crossings: List<TaxiCrossingMarker> = emptyList(),
    val isDeparture: Boolean = true,
    val departureGate: Coordinate? = null,
    val destination: Coordinate? = null,
    val destinationLabel: String = "",
    val aircraft: Coordinate? = null,
    val aircraftHeadingDegrees: Double = 0.0,
)

/**
 * The airport-surface map — a port of `IFATCCompanion/Views/TaxiMapView.swift`.
 *
 * **Only the geometry that is part of the assigned route is drawn**: the route itself and
 * the runways it crosses or ends at. That is not a simplification, it is the fix for a
 * real failure — drawing every runway and taxiway of a large field at fit-to-route zoom
 * overwhelmed MapKit's overlay layer on iOS and eventually crashed it. The same
 * restriction is kept here: this canvas would survive the volume, but a diagram showing
 * every stub taxiway at a field the size of DFW is unreadable at the zoom that matters,
 * and the pilot needs to see their route, not the airport.
 *
 * Surface geometry is OpenStreetMap data under the ODbL. The attribution shown beneath
 * the map is the licence condition, not decoration.
 */
@Composable
fun TaxiMap(
    model: TaxiMapModel,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    height: Dp = if (expanded) TAXI_MAP_HEIGHT_EXPANDED else TAXI_MAP_HEIGHT,
) {
    val state = rememberMapCanvasState()
    val semantic = IFATCTheme.semantic

    // Re-fit when the route changes — a new clearance always wins over the pilot's pan,
    // because the whole point of the map at that moment is the new route.
    val signature = remember(model.route, model.destination) {
        model.route.joinToString { "${it.latitude},${it.longitude}" } + "|${model.destination}"
    }
    LaunchedEffect(signature) {
        val content = buildList {
            addAll(model.route)
            model.relevantRunways.forEach(::addAll)
            model.destination?.let(::add)
            model.departureGate?.let(::add)
        }.filter { it.isValid }
        if (content.isNotEmpty()) state.fitTo(content)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(MAP_CORNER_RADIUS)),
    ) {
        if (model.route.isEmpty() && model.relevantRunways.isEmpty()) {
            Text(
                text = "Taxi route pending.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
            return@Box
        }

        MapCanvas(state = state, modifier = Modifier.fillMaxSize()) { frame ->
            for (runway in model.relevantRunways) {
                drawTaxiLine(
                    frame, runway, semantic.runway,
                    width = if (expanded) RUNWAY_WIDTH_EXPANDED else RUNWAY_WIDTH,
                )
            }

            // The assigned route. A low-confidence route is dashed, so a pilot can see at a
            // glance that the app is not certain of the surface data behind it rather than
            // being told a taxi route it drew from a thin extract.
            drawTaxiLine(
                frame,
                model.route,
                semantic.taxiRoute,
                width = if (expanded) ROUTE_WIDTH_EXPANDED else ROUTE_WIDTH,
                dash = if (model.routeConfidence <= SurfaceConfidence.LOW) floatArrayOf(8f, 5f) else null,
            )

            for (hold in model.holdingPositions) {
                drawHoldShortBar(frame, hold, semantic.holdShort, expanded)
            }

            for (crossing in model.crossings) {
                drawCrossing(
                    frame,
                    crossing.point,
                    if (crossing.isActive) semantic.severityExtreme else semantic.runwayCrossing,
                )
            }

            if (model.isDeparture) {
                model.departureGate?.let { drawStand(frame, it, semantic.gate) }
            }
            model.destination?.let {
                drawStand(frame, it, if (model.isDeparture) semantic.severityLight else semantic.gate)
            }
            model.aircraft?.let {
                drawTaxiingAircraft(frame, it, model.aircraftHeadingDegrees, semantic.aircraft, expanded)
            }
        }
    }
}

// region Drawing primitives

private fun DrawScope.drawTaxiLine(
    frame: MapFrame,
    coordinates: List<Coordinate>,
    color: Color,
    width: Float,
    dash: FloatArray? = null,
) {
    val points = coordinates.filter { it.isValid }.map(frame::project)
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        for (point in points.drop(1)) lineTo(point.x, point.y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = width.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = dash?.let {
                PathEffect.dashPathEffect(floatArrayOf(it[0].dp.toPx(), it[1].dp.toPx()))
            },
        ),
    )
}

/** A hold-short bar, drawn across the taxiway the way the painted marking runs. */
private fun DrawScope.drawHoldShortBar(frame: MapFrame, coordinate: Coordinate, color: Color, expanded: Boolean) {
    if (!coordinate.isValid) return
    val point = frame.project(coordinate)
    if (!frame.isNearCanvas(point)) return
    val half = (if (expanded) HOLD_BAR_HALF_EXPANDED else HOLD_BAR_HALF).dp.toPx()
    drawLine(
        color = color,
        start = Offset(point.x - half, point.y),
        end = Offset(point.x + half, point.y),
        strokeWidth = HOLD_BAR_THICKNESS.dp.toPx(),
        cap = StrokeCap.Round,
    )
}

/** A runway crossing on the route: an ✕, red once it is the one being worked. */
private fun DrawScope.drawCrossing(frame: MapFrame, coordinate: Coordinate, color: Color) {
    if (!coordinate.isValid) return
    val point = frame.project(coordinate)
    if (!frame.isNearCanvas(point)) return
    val r = CROSSING_RADIUS.dp.toPx()
    drawCircle(Color.White.copy(alpha = 0.6f), radius = r, center = point)
    drawLine(color, Offset(point.x - r * 0.6f, point.y - r * 0.6f), Offset(point.x + r * 0.6f, point.y + r * 0.6f), CROSSING_STROKE.dp.toPx(), StrokeCap.Round)
    drawLine(color, Offset(point.x - r * 0.6f, point.y + r * 0.6f), Offset(point.x + r * 0.6f, point.y - r * 0.6f), CROSSING_STROKE.dp.toPx(), StrokeCap.Round)
    drawCircle(color, radius = r, center = point, style = Stroke(1.5.dp.toPx()))
}

private fun DrawScope.drawStand(frame: MapFrame, coordinate: Coordinate, color: Color) {
    if (!coordinate.isValid) return
    val point = frame.project(coordinate)
    if (!frame.isNearCanvas(point)) return
    val r = STAND_MARKER_RADIUS.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(point.x - r, point.y - r),
        size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.4f),
    )
}

private fun DrawScope.drawTaxiingAircraft(
    frame: MapFrame,
    coordinate: Coordinate,
    headingDegrees: Double,
    color: Color,
    expanded: Boolean,
) {
    if (!coordinate.isValid) return
    val point = frame.project(coordinate)
    if (!frame.isNearCanvas(point)) return
    val size = (if (expanded) AIRCRAFT_SIZE_EXPANDED else AIRCRAFT_SIZE).dp.toPx()
    rotate(degrees = headingDegrees.toFloat(), pivot = point) {
        val body = Path().apply {
            moveTo(point.x, point.y - size)
            lineTo(point.x + size * 0.6f, point.y + size * 0.7f)
            lineTo(point.x, point.y + size * 0.3f)
            lineTo(point.x - size * 0.6f, point.y + size * 0.7f)
            close()
        }
        drawPath(body, color)
        drawPath(body, Color.White, style = Stroke(1.dp.toPx()))
    }
}

// endregion

private val TAXI_MAP_HEIGHT = 220.dp
private val TAXI_MAP_HEIGHT_EXPANDED = 460.dp
private const val RUNWAY_WIDTH = 6f
private const val RUNWAY_WIDTH_EXPANDED = 10f
private const val ROUTE_WIDTH = 5f
private const val ROUTE_WIDTH_EXPANDED = 7f
private const val HOLD_BAR_HALF = 6f
private const val HOLD_BAR_HALF_EXPANDED = 9f
private const val HOLD_BAR_THICKNESS = 3f
private const val CROSSING_RADIUS = 8f
private const val CROSSING_STROKE = 2f
private const val STAND_MARKER_RADIUS = 5f
private const val AIRCRAFT_SIZE = 8f
private const val AIRCRAFT_SIZE_EXPANDED = 11f
