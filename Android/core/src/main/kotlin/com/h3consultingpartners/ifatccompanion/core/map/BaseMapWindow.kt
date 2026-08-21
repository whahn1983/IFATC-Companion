package com.h3consultingpartners.ifatccompanion.core.map

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.RadarBoundingBox
import com.h3consultingpartners.ifatccompanion.core.weather.radar.PixelSize
import kotlin.math.roundToInt

/**
 * Decides *what* the imagery underlay should cover and at what size, given a route.
 *
 * The alternative — refetching whenever the pilot pans or pinches — was rejected. It puts
 * the map on the network during a flight, on a link that is often a phone hotspot, and it
 * makes panning feel like it is loading rather than moving. Instead one image is fetched
 * per route, over a window deliberately wider than the route itself, so ordinary panning
 * and zooming stay inside what was already fetched. The layer is a static Blue Marble
 * image with no time dimension, so there is nothing to keep current and one fetch is not a
 * compromise.
 *
 * Everything here is arithmetic on the projection, so it is pinned by tests rather than by
 * looking at a screen.
 */
object BaseMapWindow {

    /**
     * How much wider than the route to fetch. 2.0 doubles each span, which is roughly a
     * screen's worth of pan in every direction — enough that a pilot following the
     * aircraft along the route never runs off the imagery, without quadrupling the ground
     * resolution spent on empty ocean.
     */
    const val DEFAULT_PADDING_FACTOR = 2.0

    /**
     * The longest edge of the requested image. GIBS serves far larger, but this is an
     * underlay behind a route line on a phone-sized canvas: past about a thousand pixels
     * the extra detail is invisible and the download is not.
     */
    const val DEFAULT_MAX_DIMENSION = 1024

    /**
     * The smallest window worth fetching, before padding. Roughly 60 NM.
     *
     * A departure-only plan, or an aircraft flying with nothing filed, has no extent at
     * all, and a short hop like KSFO–KOAK has almost none. Sizing the request to those
     * would ask for a window a mile across — a taxi-scale picture behind a route map, and
     * a fetch that has to be redone the moment the aircraft moves. A floor of a degree
     * makes the imagery useful in exactly the cases where the route cannot size it.
     */
    const val MIN_SPAN_DEGREES = 1.0

    /**
     * A window covering [coordinates], padded by [paddingFactor], or null when there is
     * nothing to cover.
     *
     * A single point — an aircraft with no plan filed — still yields a window, because a
     * point has a location even though it has no extent; it is given [MIN_SPAN_DEGREES]
     * before padding so there is something to project.
     */
    fun coverage(
        coordinates: List<Coordinate>,
        paddingFactor: Double = DEFAULT_PADDING_FACTOR,
    ): RadarBoundingBox? {
        val valid = coordinates.filter { it.isValid }
        if (valid.isEmpty()) return null

        val minLatitude = valid.minOf { it.latitude }
        val maxLatitude = valid.maxOf { it.latitude }
        val minLongitude = valid.minOf { it.longitude }
        val maxLongitude = valid.maxOf { it.longitude }

        val factor = if (paddingFactor.isFinite() && paddingFactor >= 1.0) paddingFactor else 1.0
        val latitudeSpan = maxOf(maxLatitude - minLatitude, MIN_SPAN_DEGREES) * factor
        val longitudeSpan = maxOf(maxLongitude - minLongitude, MIN_SPAN_DEGREES) * factor
        val centreLatitude = (minLatitude + maxLatitude) / 2
        val centreLongitude = (minLongitude + maxLongitude) / 2

        // Latitude clamps at the Mercator limit; longitude clamps at the antimeridian
        // rather than wrapping, because a WMS bbox cannot express a window that crosses
        // it. A route that does gets the widest window this side of the seam, which still
        // covers most of it.
        return RadarBoundingBox(
            minLatitude = (centreLatitude - latitudeSpan / 2)
                .coerceIn(-MapProjection.MAX_LATITUDE, MapProjection.MAX_LATITUDE),
            maxLatitude = (centreLatitude + latitudeSpan / 2)
                .coerceIn(-MapProjection.MAX_LATITUDE, MapProjection.MAX_LATITUDE),
            minLongitude = (centreLongitude - longitudeSpan / 2).coerceIn(-180.0, 180.0),
            maxLongitude = (centreLongitude + longitudeSpan / 2).coerceIn(-180.0, 180.0),
        )
    }

    /**
     * The pixel size to request for [box], with the longer edge at [maxDimension].
     *
     * The aspect ratio is taken in **Web Mercator**, not in degrees. A ten-degree-tall box
     * at 60° N is far shorter on a Mercator map than a ten-degree-wide one, so sizing from
     * the lat/lon spans would stretch the imagery vertically — subtly at the equator and
     * badly at high latitude, which is where a lot of flying happens.
     */
    fun pixelSize(box: RadarBoundingBox, maxDimension: Int = DEFAULT_MAX_DIMENSION): PixelSize {
        if (maxDimension <= 0) return PixelSize(0, 0)
        val northWest = MapProjection.toUnit(Coordinate(box.maxLatitude, box.minLongitude))
        val southEast = MapProjection.toUnit(Coordinate(box.minLatitude, box.maxLongitude))
        val width = southEast.x - northWest.x
        val height = southEast.y - northWest.y
        if (width <= 0.0 || height <= 0.0) return PixelSize(0, 0)

        return if (width >= height) {
            PixelSize(maxDimension, (maxDimension * height / width).roundToInt().coerceAtLeast(1))
        } else {
            PixelSize((maxDimension * width / height).roundToInt().coerceAtLeast(1), maxDimension)
        }
    }
}
