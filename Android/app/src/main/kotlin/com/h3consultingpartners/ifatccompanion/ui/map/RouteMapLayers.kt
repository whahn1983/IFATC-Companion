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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.weather.PIREP
import com.h3consultingpartners.ifatccompanion.core.weather.SIGMET
import com.h3consultingpartners.ifatccompanion.core.weather.TurbulenceSeverity
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.WeatherIntensity
import com.h3consultingpartners.ifatccompanion.core.weather.radar.RadarCell
import com.h3consultingpartners.ifatccompanion.ui.theme.IFATCTheme

/** Everything the route map draws, already resolved to coordinates. */
data class RouteMapModel(
    val route: List<Coordinate> = emptyList(),
    val departure: Coordinate? = null,
    val destination: Coordinate? = null,
    val nextWaypoint: Coordinate? = null,
    val nextWaypointName: String = "",
    val aircraft: AircraftState = AircraftState.empty,
    val pireps: List<PIREP> = emptyList(),
    val routeSigmets: List<SIGMET> = emptyList(),
    /** Mock Mode's hand-authored precipitation systems. */
    val radarCells: List<RadarCell> = emptyList(),
    /** Opt-in diagnostics: the live-sampled cells that actually drive the deviation math. */
    val sampledCells: List<RadarCell> = emptyList(),
    /** The reroute currently being flown or offered. */
    val deviationLine: List<Coordinate> = emptyList(),
    /** Faint previews of the deviations further along the route. */
    val deviationPreviews: List<List<Coordinate>> = emptyList(),
)

/**
 * The route-and-weather map on the Weather tab — a port of
 * `IFATCCompanion/Views/RouteMapView.swift`.
 *
 * Drawing order is the part that carries meaning and is preserved exactly: precipitation
 * at the very bottom, then the sampled-cell diagnostics, then advisory areas, then the
 * faint preview reroutes, then the active reroute, then the route line, and finally the
 * markers and the aircraft. Anything the pilot is meant to act on sits above everything
 * that is only context.
 *
 * Where iOS has MapKit, this draws on the app's own canvas — see [MapCanvas] and
 * Docs/ANDROID_MAPPING.md for why no mapping SDK is used. The precipitation raster the
 * iOS build overlays from the provider is **not** drawn here yet; the vector cells and the
 * advisory shading are. That gap is recorded in the parity matrix.
 */
@Composable
fun RouteMap(
    model: RouteMapModel,
    modifier: Modifier = Modifier,
    showSampledCells: Boolean = false,
    height: androidx.compose.ui.unit.Dp = ROUTE_MAP_HEIGHT,
    /**
     * What sits under the route. Defaulted so the map is legible with no network at all:
     * bundled coastlines and a graticule need nothing fetched, and satellite imagery is an
     * enhancement the caller supplies when it has one.
     */
    baseMap: BaseMapModel = BaseMapModel(),
) {
    val state = rememberMapCanvasState()
    val semantic = IFATCTheme.semantic
    val textMeasurer = rememberTextMeasurer()

    // Re-fit when the route itself changes — not on every telemetry tick, which would
    // fight the pilot's own panning.
    val signature = remember(model.route, model.departure, model.destination) {
        model.route.joinToString { "${it.latitude},${it.longitude}" } +
            "|${model.departure}|${model.destination}"
    }
    LaunchedEffect(signature) {
        val content = buildList {
            addAll(model.route)
            model.departure?.let(::add)
            model.destination?.let(::add)
        }.filter { it.isValid }
        if (content.isNotEmpty()) state.fitTo(content)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(MAP_CORNER_RADIUS)),
    ) {
        // Only truly empty when there is nothing to place either: with a base map there
        // is now always something on screen, so this is about the route, not the canvas.
        if (model.route.isEmpty() && model.departure == null && model.aircraft.coordinate == null) {
            Text(
                text = "No route to draw yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
            return@Box
        }

        MapCanvas(state = state, modifier = Modifier.fillMaxSize()) { frame ->
            // 0. The base map — imagery if there is any, then coastlines, graticule and
            //    scale bar. Everything below reads over it.
            drawBaseMap(
                frame = frame,
                model = baseMap,
                coastlineColor = MAP_COASTLINE_COLOR,
                graticuleColor = MAP_GRATICULE_COLOR,
                labelColor = MAP_GRATICULE_LABEL_COLOR,
                textMeasurer = textMeasurer,
            )
            // 1. Precipitation (Mock Mode's vector systems).
            for (cell in model.radarCells) {
                drawPolygon(frame, cell.polygon, radarColor(cell.intensity, semantic), fillAlpha = 0.35f, strokeWidth = 1f)
            }
            // 2. The sampled cells the deviation math actually reads — a thicker outline
            //    and a lighter fill than the mock cells, so the two can be compared.
            if (showSampledCells) {
                for (cell in model.sampledCells) {
                    drawPolygon(frame, cell.polygon, radarColor(cell.intensity, semantic), fillAlpha = 0.18f, strokeWidth = 2.5f)
                }
            }
            // 3. Advisory areas, below the route so the line and the markers read on top.
            for (sigmet in model.routeSigmets) {
                drawPolygon(frame, sigmet.area, sigmetColor(sigmet, semantic), fillAlpha = 0.20f, strokeWidth = 2f)
            }
            // 4. Faint previews of the deviations further along the route.
            for (preview in model.deviationPreviews) {
                drawPolyline(
                    frame, preview, semantic.deviationLine.copy(alpha = 0.35f),
                    width = 2f, dash = floatArrayOf(3f, 6f),
                )
            }
            // 5. The reroute being flown or offered.
            drawPolyline(
                frame, model.deviationLine, semantic.deviationLine,
                width = 3f, dash = floatArrayOf(2f, 5f),
            )
            // 6. The filed route.
            drawPolyline(
                frame, model.route, semantic.routeLine,
                width = 3f, dash = floatArrayOf(7f, 4f),
            )
            // 7. Markers.
            model.nextWaypoint?.let { drawWaypoint(frame, it, semantic.routeLine) }
            model.departure?.let { drawAirport(frame, it, semantic.severityLight) }
            model.destination?.let { drawAirport(frame, it, semantic.severityExtreme) }
            for (pirep in model.pireps) {
                val coordinate = pirep.coordinate ?: continue
                if (!coordinate.isValid) continue
                drawPirep(frame, coordinate, severityColor(pirep.turbulence ?: TurbulenceSeverity.SMOOTH, semantic))
            }
            // 8. The aircraft, last, because it is never obscured.
            drawAircraft(frame, model.aircraft, semantic.aircraft)
        }
    }
}

// region Drawing primitives

private fun DrawScope.drawPolyline(
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

private fun DrawScope.drawPolygon(
    frame: MapFrame,
    coordinates: List<Coordinate>,
    color: Color,
    fillAlpha: Float,
    strokeWidth: Float,
) {
    val points = coordinates.filter { it.isValid }.map(frame::project)
    if (points.size < 3) return
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        for (point in points.drop(1)) lineTo(point.x, point.y)
        close()
    }
    drawPath(path, color.copy(alpha = fillAlpha))
    drawPath(path, color, style = Stroke(width = strokeWidth.dp.toPx()))
}

private fun DrawScope.drawAirport(frame: MapFrame, coordinate: Coordinate, color: Color) {
    if (!coordinate.isValid) return
    val point = frame.project(coordinate)
    if (!frame.isNearCanvas(point)) return
    drawCircle(color, radius = AIRPORT_MARKER_RADIUS.dp.toPx(), center = point)
    drawCircle(Color.White, radius = AIRPORT_MARKER_RADIUS.dp.toPx(), center = point, style = Stroke(1.5.dp.toPx()))
}

private fun DrawScope.drawWaypoint(frame: MapFrame, coordinate: Coordinate, color: Color) {
    if (!coordinate.isValid) return
    val point = frame.project(coordinate)
    if (!frame.isNearCanvas(point)) return
    // A diamond, matching the iOS `diamond.fill` annotation.
    val r = WAYPOINT_MARKER_RADIUS.dp.toPx()
    val diamond = Path().apply {
        moveTo(point.x, point.y - r)
        lineTo(point.x + r, point.y)
        lineTo(point.x, point.y + r)
        lineTo(point.x - r, point.y)
        close()
    }
    drawPath(diamond, color)
}

private fun DrawScope.drawPirep(frame: MapFrame, coordinate: Coordinate, color: Color) {
    val point = frame.project(coordinate)
    if (!frame.isNearCanvas(point)) return
    drawCircle(color, radius = PIREP_MARKER_RADIUS.dp.toPx(), center = point)
    drawCircle(Color.White, radius = PIREP_MARKER_RADIUS.dp.toPx(), center = point, style = Stroke(1.5.dp.toPx()))
}

private fun DrawScope.drawAircraft(frame: MapFrame, aircraft: AircraftState, color: Color) {
    val coordinate = aircraft.coordinate ?: return
    if (!coordinate.isValid) return
    val point = frame.project(coordinate)
    if (!frame.isNearCanvas(point)) return
    // The map is drawn true-north-up, so orient by the TRUE heading, falling back to
    // magnetic when the sim doesn't expose it. Using magnetic here would rotate the
    // symbol off by the local declination — negligible near the US/UK, 20°+ elsewhere.
    val heading = (aircraft.trueHeading ?: aircraft.heading ?: 0.0).toFloat()
    val size = AIRCRAFT_MARKER_SIZE.dp.toPx()
    rotate(degrees = heading, pivot = point) {
        val nose = Offset(point.x, point.y - size)
        val left = Offset(point.x - size * 0.6f, point.y + size * 0.7f)
        val right = Offset(point.x + size * 0.6f, point.y + size * 0.7f)
        val tail = Offset(point.x, point.y + size * 0.3f)
        val body = Path().apply {
            moveTo(nose.x, nose.y)
            lineTo(right.x, right.y)
            lineTo(tail.x, tail.y)
            lineTo(left.x, left.y)
            close()
        }
        drawPath(body, color)
        drawPath(body, Color.White, style = Stroke(1.dp.toPx()))
    }
}

// endregion

// region Colour ramps

/** Radar precipitation intensity, light → extreme. */
internal fun radarColor(
    intensity: WeatherIntensity,
    semantic: com.h3consultingpartners.ifatccompanion.ui.theme.IFATCSemanticColors,
): Color = when (intensity) {
    WeatherIntensity.LIGHT -> semantic.severityLight
    WeatherIntensity.MODERATE -> semantic.severityModerate
    WeatherIntensity.HEAVY -> semantic.severitySevere
    WeatherIntensity.EXTREME -> semantic.severityExtreme
    WeatherIntensity.UNKNOWN -> semantic.system
}

internal fun severityColor(
    severity: TurbulenceSeverity,
    semantic: com.h3consultingpartners.ifatccompanion.ui.theme.IFATCSemanticColors,
): Color = when (severity) {
    TurbulenceSeverity.SMOOTH, TurbulenceSeverity.LIGHT_CHOP -> semantic.severityLight
    TurbulenceSeverity.LIGHT -> semantic.severityModerate
    TurbulenceSeverity.MODERATE -> semantic.severitySevere
    TurbulenceSeverity.SEVERE -> semantic.severityExtreme
}

/** Advisories are coloured by what they warn about, not by a single alert colour. */
internal fun sigmetColor(
    sigmet: SIGMET,
    semantic: com.h3consultingpartners.ifatccompanion.ui.theme.IFATCSemanticColors,
): Color = when (sigmet.category) {
    SIGMET.Category.CONVECTIVE -> semantic.severityExtreme
    SIGMET.Category.TURBULENCE -> semantic.severitySevere
    SIGMET.Category.ICING_OR_MOUNTAIN_WAVE -> semantic.severityModerate
    SIGMET.Category.OTHER -> semantic.system
}

// endregion

private val ROUTE_MAP_HEIGHT = 280.dp
internal val MAP_CORNER_RADIUS = 12.dp
private const val AIRPORT_MARKER_RADIUS = 6f
private const val WAYPOINT_MARKER_RADIUS = 5f
private const val PIREP_MARKER_RADIUS = 7f
private const val AIRCRAFT_MARKER_SIZE = 9f

/** Base-map ink. Muted on purpose: context must never compete with the route. */
private val MAP_COASTLINE_COLOR = Color(0xFF5A6B78)
private val MAP_GRATICULE_COLOR = Color(0x33FFFFFF)
private val MAP_GRATICULE_LABEL_COLOR = Color(0x99FFFFFF)
