package com.h3consultingpartners.ifatccompanion.core.geo

import kotlin.math.abs

/**
 * A WGS-84 latitude/longitude pair.
 *
 * Ported from `CLLocationCoordinate2D` usage in the iOS app. Android's
 * `android.location.Location` is a heavyweight framework class, so the engine
 * carries its own value type — that keeps every geospatial routine (weather
 * analysis, taxi routing, deviation corridors) unit-testable on a plain JVM.
 */
data class Coordinate(val latitude: Double, val longitude: Double) {

    /**
     * Mirrors `CLLocationCoordinate2DIsValid` plus the iOS app's own extra rule that
     * exactly (0, 0) — Null Island — is treated as "no fix", because that is what the
     * Connect link reports when a state read fails.
     */
    val isValid: Boolean
        get() = latitude.isFinite() && longitude.isFinite() &&
            abs(latitude) <= 90.0 && abs(longitude) <= 180.0 &&
            !(latitude == 0.0 && longitude == 0.0)

    companion object {
        val zero = Coordinate(0.0, 0.0)
    }
}

/** Null when either component is missing, matching the optional-coordinate idiom on iOS. */
fun coordinateOrNull(latitude: Double?, longitude: Double?): Coordinate? {
    if (latitude == null || longitude == null) return null
    return Coordinate(latitude, longitude)
}

/** Null unless the coordinate is both present and valid. */
fun validCoordinateOrNull(latitude: Double?, longitude: Double?): Coordinate? =
    coordinateOrNull(latitude, longitude)?.takeIf { it.isValid }
