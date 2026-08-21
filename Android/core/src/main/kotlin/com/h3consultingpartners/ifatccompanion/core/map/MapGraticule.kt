package com.h3consultingpartners.ifatccompanion.core.map

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The graticule and scale bar: the map's coordinate frame.
 *
 * The route map draws a flight plan, an aircraft and weather onto an otherwise empty
 * canvas. Without a base map there is nothing to place any of it against — a line between
 * two airports looks the same whether it crosses Texas or the Tasman Sea. MapKit gave iOS
 * coastlines for free; this gives Android the part of that which is pure arithmetic, needs
 * no network, no key and no tile provider, and works with no signal.
 *
 * Kept in `:core` so the spacing choice and the scale-bar rounding are unit tested rather
 * than eyeballed on a device.
 */
object MapGraticule {

    /** A parallel or meridian to draw, with the label to put on it. */
    data class Line(
        /** Degrees — latitude for a parallel, longitude for a meridian. */
        val degrees: Double,
        val label: String,
        /** True when this is a parallel (constant latitude). */
        val isParallel: Boolean,
    )

    /** The scale bar: a round distance and the fraction of the canvas width it spans. */
    data class ScaleBar(
        val distanceNM: Double,
        val label: String,
        /** 0…1 of the canvas width. Callers multiply by their own pixel width. */
        val widthFraction: Double,
    )

    /**
     * Candidate spacings in degrees, coarse to fine.
     *
     * Deliberately the values a chart uses rather than a computed power of ten: a pilot
     * reads 1°, 5°, 10° without translating, and 2.5° or 7.5° gridlines would be a small
     * constant tax on every glance.
     */
    private val SPACINGS_DEGREES = doubleArrayOf(
        30.0, 20.0, 10.0, 5.0, 2.0, 1.0, 0.5, 0.25, 0.1, 0.05, 0.02, 0.01,
    )

    /** Round distances for the scale bar, in nautical miles. */
    private val SCALE_STEPS_NM = doubleArrayOf(
        2000.0, 1000.0, 500.0, 200.0, 100.0, 50.0, 20.0, 10.0, 5.0, 2.0, 1.0, 0.5, 0.2,
    )

    /**
     * How many gridlines to aim for across the shorter axis. Enough to orient by, few
     * enough that they never compete with the route for attention.
     */
    private const val TARGET_LINES = 4

    /**
     * The graticule for a viewport: the parallels and meridians that fall inside it, at a
     * spacing chosen from the span.
     *
     * Latitude and longitude get their own spacing. Away from the equator a Mercator
     * viewport covers far more longitude than latitude, and forcing one spacing on both
     * gives either a cramped grid or an empty one.
     */
    fun linesFor(viewport: MapProjection.Viewport): List<Line> {
        val topLeft = MapProjection.toCoordinate(MapProjection.UnitPoint(viewport.minX, viewport.minY))
        val bottomRight = MapProjection.toCoordinate(MapProjection.UnitPoint(viewport.maxX, viewport.maxY))

        val north = maxOf(topLeft.latitude, bottomRight.latitude)
        val south = minOf(topLeft.latitude, bottomRight.latitude)
        val west = minOf(topLeft.longitude, bottomRight.longitude)
        val east = maxOf(topLeft.longitude, bottomRight.longitude)

        val lines = mutableListOf<Line>()
        spacingFor(north - south)?.let { step ->
            var lat = ceil(south / step) * step
            while (lat <= north) {
                lines += Line(lat, latitudeLabel(lat, step), isParallel = true)
                lat += step
            }
        }
        spacingFor(east - west)?.let { step ->
            var lon = ceil(west / step) * step
            while (lon <= east) {
                lines += Line(lon, longitudeLabel(lon, step), isParallel = false)
                lon += step
            }
        }
        return lines
    }

    /**
     * The largest spacing that still puts roughly [TARGET_LINES] lines across [span]
     * degrees, or null when the span is degenerate.
     */
    fun spacingFor(span: Double): Double? {
        if (span <= 0.0 || span.isNaN()) return null
        val ideal = span / TARGET_LINES
        // Coarse to fine: take the first that is no larger than ideal, so the grid errs
        // toward fewer lines rather than a thicket.
        return SPACINGS_DEGREES.firstOrNull { it <= ideal } ?: SPACINGS_DEGREES.last()
    }

    /**
     * A scale bar for a viewport, targeting about a quarter of the canvas width and
     * rounded down to a distance worth reading.
     *
     * Rounded *down* on purpose: a bar that is shorter than its target still fits, while
     * one rounded up can run off the edge of a narrow canvas.
     */
    fun scaleBarFor(viewport: MapProjection.Viewport, targetFraction: Double = 0.25): ScaleBar? {
        val spanNM = viewport.widthNM()
        if (spanNM <= 0.0 || spanNM.isNaN() || spanNM.isInfinite()) return null
        val target = spanNM * targetFraction
        val distance = SCALE_STEPS_NM.firstOrNull { it <= target } ?: return null
        return ScaleBar(
            distanceNM = distance,
            label = if (distance >= 1.0) "${distance.roundToInt()} NM" else "$distance NM",
            widthFraction = distance / spanNM,
        )
    }

    /** "35°N", or "35.5°N" when the spacing is finer than a degree. */
    fun latitudeLabel(degrees: Double, step: Double): String =
        format(abs(degrees), step) + if (degrees < 0) "°S" else "°N"

    /** "095°W", zero-padded the way a chart writes longitude. */
    fun longitudeLabel(degrees: Double, step: Double): String {
        // Normalize into -180…180 so a viewport panned across the antimeridian still
        // labels its meridians rather than reading "190°E".
        var d = degrees
        while (d > 180.0) d -= 360.0
        while (d < -180.0) d += 360.0
        return format(abs(d), step, padTo = 3) + if (d < 0) "°W" else "°E"
    }

    private fun format(value: Double, step: Double, padTo: Int = 1): String {
        val decimals = when {
            step >= 1.0 -> 0
            step >= 0.1 -> 1
            else -> 2
        }
        val whole = floor(value).toInt()
        return if (decimals == 0) {
            whole.toString().padStart(padTo, '0')
        } else {
            val scale = if (decimals == 1) 10 else 100
            val frac = ((value - whole) * scale).roundToInt()
            // A fraction that rounds up to a whole unit belongs on the next degree.
            if (frac >= scale) {
                (whole + 1).toString().padStart(padTo, '0')
            } else {
                whole.toString().padStart(padTo, '0') + "." + frac.toString().padStart(decimals, '0')
            }
        }
    }

    /** The coordinate a parallel or meridian passes through at a given cross-axis value. */
    fun pointOn(line: Line, crossDegrees: Double): Coordinate =
        if (line.isParallel) {
            Coordinate(latitude = line.degrees, longitude = crossDegrees)
        } else {
            Coordinate(latitude = crossDegrees, longitude = line.degrees)
        }
}
