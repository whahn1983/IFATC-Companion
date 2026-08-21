package com.h3consultingpartners.ifatccompanion.core.weather.deviation

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import java.util.UUID
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

// MARK: - Hazard taxonomy
//
// A normalized, source-tagged weather hazard used by the route-conflict detector
// and the simulated weather-deviation flow. Deliberately kept separate from the
// existing ride-report / SIGMET types: [WeatherHazard] unifies NOAA radar
// precipitation, SIGMETs, PIREPs and the other aviation advisories behind one
// shape so the deviation logic can reason about "weather ahead" without caring
// where it came from — while the `source` tag preserves *what kind* of report it
// is so phraseology never, for example, calls radar colors "turbulence".
//
// Ported from `IFATCCompanion/Weather/WeatherHazard.swift`.

/**
 * Where a hazard came from. Coverage limitations differ by source (see
 * `Docs/Weather.md`): NOAA radar is NOAA-covered-regions only; PIREPs/AIREPs are
 * primarily U.S. + North Atlantic; G-AIRMET is contiguous-U.S. only.
 */
enum class WeatherHazardSource(val rawValue: String) {
    NOAA_RADAR("noaaRadar"),

    /**
     * NASA GPM IMERG / GIBS global satellite precipitation *estimate* (not radar).
     * Only ever drives a deviation when the user opts in via the satellite-estimate
     * deviation setting; kept as its own source so diagnostics and phraseology never
     * present the estimate as radar-grade.
     */
    SATELLITE_ESTIMATE("satelliteEstimate"),
    SIGMET("sigmet"),
    PIREP("pirep"),
    METAR("metar"),
    TAF("taf"),
    CWA("cwa"),
    GAIRMET("gairmet"),
    UNKNOWN("unknown"),
    ;

    /** Short human label used in diagnostics / data-source captions. */
    val label: String
        get() = when (this) {
            NOAA_RADAR -> "NOAA/NWS radar precipitation"
            SATELLITE_ESTIMATE -> "NASA satellite precipitation estimate"
            SIGMET -> "SIGMET"
            PIREP -> "PIREP"
            METAR -> "METAR"
            TAF -> "TAF"
            CWA -> "CWA"
            GAIRMET -> "G-AIRMET"
            UNKNOWN -> "Unknown"
        }

    /**
     * Whether a "turbulence" characterization is ever valid for this source.
     * Radar reflectivity is precipitation intensity only — it must never be
     * spoken as turbulence. Turbulence wording is reserved for the report types
     * that actually measure or forecast it.
     */
    val supportsTurbulenceWording: Boolean
        get() = when (this) {
            PIREP, SIGMET, CWA, GAIRMET -> true
            NOAA_RADAR, SATELLITE_ESTIMATE, METAR, TAF, UNKNOWN -> false
        }
}

/** The phenomenon a hazard represents. */
enum class WeatherPhenomenon(val rawValue: String) {
    PRECIPITATION("precipitation"),
    THUNDERSTORM("thunderstorm"),
    TURBULENCE("turbulence"),
    ICING("icing"),
    WIND_SHEAR("windShear"),
    LOW_CEILING("lowCeiling"),
    LOW_VISIBILITY("lowVisibility"),
    UNKNOWN("unknown"),
}

/**
 * A coarse intensity scale shared by radar precipitation and hazard severity.
 * [UNKNOWN] sorts below [LIGHT] so "intensity unknown" never outranks a graded
 * cell in severity comparisons — the Swift enum is `Int`-raw-valued and
 * `Comparable` on that raw value, and the declaration order below is that same
 * order, so Kotlin's ordinal comparison agrees with it exactly.
 */
enum class WeatherIntensity(val rawValue: Int) {
    UNKNOWN(-1),
    LIGHT(0),
    MODERATE(1),
    HEAVY(2),
    EXTREME(3),
    ;

    /**
     * Precipitation phrasing for radar-derived hazards. Always says
     * "precipitation" — never "turbulence" — because radar shows precipitation.
     */
    val spokenPrecipitation: String
        get() = when (this) {
            LIGHT -> "light precipitation"
            MODERATE -> "moderate precipitation"
            HEAVY -> "heavy precipitation"
            EXTREME -> "extreme precipitation"
            UNKNOWN -> "precipitation"
        }

    /** Short display label for legends / diagnostics. */
    val displayLabel: String
        get() = when (this) {
            LIGHT -> "Light"
            MODERATE -> "Moderate"
            HEAVY -> "Heavy"
            EXTREME -> "Extreme"
            UNKNOWN -> "Unknown"
        }
}

// MARK: - Geometry

/**
 * A lat/lon bounding box (used for radar coverage checks and image export).
 *
 * The constructor normalizes the corners the way the Swift initialiser does, so a
 * box built from swapped edges still has min ≤ max.
 */
class RadarBoundingBox(
    minLatitude: Double,
    minLongitude: Double,
    maxLatitude: Double,
    maxLongitude: Double,
) {
    val minLatitude: Double
    val maxLatitude: Double
    val minLongitude: Double
    val maxLongitude: Double

    init {
        this.minLatitude = min(minLatitude, maxLatitude)
        this.maxLatitude = max(minLatitude, maxLatitude)
        this.minLongitude = min(minLongitude, maxLongitude)
        this.maxLongitude = max(minLongitude, maxLongitude)
    }

    val center: Coordinate
        get() = Coordinate(
            (minLatitude + maxLatitude) / 2,
            (minLongitude + maxLongitude) / 2,
        )

    /** The four corners as a closed-ish polygon (SW, SE, NE, NW). */
    val corners: List<Coordinate>
        get() = listOf(
            Coordinate(minLatitude, minLongitude),
            Coordinate(minLatitude, maxLongitude),
            Coordinate(maxLatitude, maxLongitude),
            Coordinate(maxLatitude, minLongitude),
        )

    operator fun contains(c: Coordinate): Boolean =
        c.latitude >= minLatitude && c.latitude <= maxLatitude &&
            c.longitude >= minLongitude && c.longitude <= maxLongitude

    /** Whether this box overlaps another (axis-aligned). */
    fun overlaps(other: RadarBoundingBox): Boolean =
        minLatitude <= other.maxLatitude && maxLatitude >= other.minLatitude &&
            minLongitude <= other.maxLongitude && maxLongitude >= other.minLongitude

    /**
     * The bbox in Web Mercator (EPSG:3857) meters as "xmin,ymin,xmax,ymax", for
     * WMS providers that render in 3857 (aligns with the map's projection).
     */
    val mercatorBBoxString: String
        get() {
            fun x(lon: Double): Double = lon * 20037508.342789244 / 180
            fun y(lat: Double): Double {
                val clamped = min(85.05112878, max(-85.05112878, lat))
                val rad = clamped * PI / 180
                return ln(tan(PI / 4 + rad / 2)) * 6378137.0
            }
            return "${x(minLongitude)},${y(minLatitude)},${x(maxLongitude)},${y(maxLatitude)}"
        }

    override fun equals(other: Any?): Boolean =
        other is RadarBoundingBox &&
            minLatitude == other.minLatitude && minLongitude == other.minLongitude &&
            maxLatitude == other.maxLatitude && maxLongitude == other.maxLongitude

    override fun hashCode(): Int {
        var result = minLatitude.hashCode()
        result = 31 * result + minLongitude.hashCode()
        result = 31 * result + maxLatitude.hashCode()
        result = 31 * result + maxLongitude.hashCode()
        return result
    }

    companion object {
        /**
         * Build a box from a map coordinate region (the map's visible extent), given its
         * centre and its latitude/longitude spans in degrees.
         *
         * Longitude is linear in Web Mercator, so the east/west edges are just
         * `center ± longitudeDelta/2`. Latitude is **not**: the map is drawn in Web
         * Mercator (EPSG:3857) with the region centre at the view's centre, so
         * reconstructing the north/south edges as `center ± latitudeDelta/2` in *degrees*
         * yields a box that is off-centre in Mercator — and the offset grows with the
         * span. An overlay requested for that box (the NASA GIBS / OPERA WMS layers all
         * export in 3857) therefore drifts vertically as the map is zoomed instead of
         * simply scaling. Placing the edges symmetrically about the centre *in Mercator*
         * keeps the overlay registered at every zoom level.
         */
        fun fromRegion(
            centerLatitude: Double,
            centerLongitude: Double,
            latitudeDelta: Double,
            longitudeDelta: Double,
        ): RadarBoundingBox {
            val lonHalf = longitudeDelta / 2
            val latHalf = latitudeDelta / 2

            // Normalized (Earth-radius-free) Web-Mercator y and its inverse; the radius
            // cancels because we only use y to re-centre the latitude span symmetrically.
            fun mercatorY(lat: Double): Double {
                val clamped = min(85.05112878, max(-85.05112878, lat))
                return ln(tan(PI / 4 + clamped * PI / 180 / 2))
            }
            fun latitudeFromMercatorY(y: Double): Double = (2 * atan(exp(y)) - PI / 2) * 180 / PI

            val yCenter = mercatorY(centerLatitude)
            val yHalf = (mercatorY(centerLatitude + latHalf) - mercatorY(centerLatitude - latHalf)) / 2
            return RadarBoundingBox(
                minLatitude = latitudeFromMercatorY(yCenter - yHalf),
                minLongitude = centerLongitude - lonHalf,
                maxLatitude = latitudeFromMercatorY(yCenter + yHalf),
                maxLongitude = centerLongitude + lonHalf,
            )
        }
    }
}

/**
 * The shape a hazard occupies. Kept as a sealed hierarchy so point reports (PIREPs),
 * advisory polygons (SIGMETs), radar boxes and computed route intersections all
 * reduce to a drawable/testable geometry.
 */
sealed interface HazardGeometry {
    data class PointRadius(val center: Coordinate, val radiusNM: Double) : HazardGeometry
    data class Polygon(val points: List<Coordinate>) : HazardGeometry
    data class BoundingBoxGeometry(val box: RadarBoundingBox) : HazardGeometry
    data class RouteSegmentIntersection(val entry: Coordinate, val exit: Coordinate) : HazardGeometry

    /**
     * A drawable polygon (>= 3 valid vertices) when one exists, for the map + the
     * point-in-polygon / edge-crossing route tests. Point/segment geometries
     * return null (they are handled by distance/segment tests instead).
     */
    val polygonPoints: List<Coordinate>?
        get() = when (this) {
            is Polygon -> {
                val valid = points.filter { it.isValid }
                if (valid.size >= 3) valid else null
            }
            is BoundingBoxGeometry -> box.corners
            is PointRadius, is RouteSegmentIntersection -> null
        }

    /**
     * A single representative coordinate (polygon centroid, point center, or the
     * midpoint of a route intersection).
     */
    val representativeCenter: Coordinate?
        get() = when (this) {
            is PointRadius -> if (center.isValid) center else null
            is BoundingBoxGeometry -> box.center
            is Polygon -> {
                val valid = points.filter { it.isValid }
                if (valid.isEmpty()) {
                    null
                } else {
                    Coordinate(
                        valid.sumOf { it.latitude } / valid.size,
                        valid.sumOf { it.longitude } / valid.size,
                    )
                }
            }
            is RouteSegmentIntersection -> Coordinate(
                (entry.latitude + exit.latitude) / 2,
                (entry.longitude + exit.longitude) / 2,
            )
        }
}

// MARK: - Hazard

/** Coarse confidence in a hazard, used to weight prompting decisions. */
enum class HazardConfidence(val rawValue: String) {
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low"),
}

/**
 * A normalized weather hazard. Equality is identity ([id]) only — it carries
 * geometry rather than a plain value, following the same pattern as the existing
 * `SIGMET` model. [id] is the last constructor parameter so `copy()` carries it
 * across the way a Swift `var` mutation does.
 *
 * Times are epoch milliseconds rather than a `Date`, matching the rest of :core.
 */
data class WeatherHazard(
    var source: WeatherHazardSource,
    var providerID: String? = null,
    var phenomenon: WeatherPhenomenon,
    var intensity: WeatherIntensity,
    var geometry: HazardGeometry,
    var confidence: HazardConfidence = HazardConfidence.MEDIUM,
    var validFromMillis: Long? = null,
    var validUntilMillis: Long? = null,
    var movementDirectionDegrees: Double? = null,
    var movementSpeedKnots: Double? = null,
    var distanceAheadNM: Double? = null,
    var estimatedTimeToHazardMinutes: Double? = null,
    var altitudeLower: Int? = null,
    var altitudeUpper: Int? = null,
    var notes: String? = null,
    val id: String = UUID.randomUUID().toString(),
) {
    override fun equals(other: Any?): Boolean = other is WeatherHazard && id == other.id
    override fun hashCode(): Int = id.hashCode()

    /**
     * Whether the movement vector is known well enough to voice ("moving east at
     * two zero knots"). Both a direction and a non-trivial speed are required.
     */
    val hasKnownMovement: Boolean
        get() {
            val dir = movementDirectionDegrees ?: return false
            if (dir < 0) return false
            val spd = movementSpeedKnots ?: return false
            return spd >= 1
        }

    /**
     * Whether this hazard is a convective SIGMET (thunderstorm activity), which
     * warrants the stronger "convective weather / thunderstorms" phrasing.
     */
    val isConvectiveSigmet: Boolean
        get() = source == WeatherHazardSource.SIGMET && phenomenon == WeatherPhenomenon.THUNDERSTORM
}
