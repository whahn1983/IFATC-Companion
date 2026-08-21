package com.h3consultingpartners.ifatccompanion.core.weather.radar

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.RadarBoundingBox
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.WeatherIntensity
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * A map's visible extent: a centre and its latitude/longitude spans in degrees.
 *
 * Stands in for MapKit's `MKCoordinateRegion`, which `:core` cannot depend on and
 * Android has no equivalent of — the app draws its own map (see Docs/ANDROID_MAPPING.md),
 * so the region is just data. Every provider's coverage check takes one of these.
 */
data class MapRegion(
    val centerLatitude: Double,
    val centerLongitude: Double,
    val latitudeDelta: Double,
    val longitudeDelta: Double,
) {
    val center: Coordinate get() = Coordinate(centerLatitude, centerLongitude)

    /** The region as a bounding box, re-centred in Web Mercator (see [RadarBoundingBox.fromRegion]). */
    val boundingBox: RadarBoundingBox
        get() = RadarBoundingBox.fromRegion(
            centerLatitude = centerLatitude,
            centerLongitude = centerLongitude,
            latitudeDelta = latitudeDelta,
            longitudeDelta = longitudeDelta,
        )

    companion object {
        /** A padded region enclosing a set of coordinates (null if empty). */
        fun enclosing(coordinates: List<Coordinate>): MapRegion? {
            val first = coordinates.firstOrNull() ?: return null
            var minLat = first.latitude
            var maxLat = first.latitude
            var minLon = first.longitude
            var maxLon = first.longitude
            for (c in coordinates) {
                minLat = min(minLat, c.latitude); maxLat = max(maxLat, c.latitude)
                minLon = min(minLon, c.longitude); maxLon = max(maxLon, c.longitude)
            }
            return MapRegion(
                centerLatitude = (minLat + maxLat) / 2,
                centerLongitude = (minLon + maxLon) / 2,
                latitudeDelta = max(0.4, (maxLat - minLat) * 1.2),
                longitudeDelta = max(0.4, (maxLon - minLon) * 1.2),
            )
        }
    }
}

/** A pixel size for a rendered overlay request. Replaces `CGSize`. */
data class PixelSize(val width: Int, val height: Int) {
    val isValid: Boolean get() = width > 0 && height > 0
}

/**
 * Radar vs satellite estimate. Drives the user-facing layer label, so a satellite
 * estimate is never presented as radar.
 *
 * Ported from `IFATCCompanion/Weather/RadarPrecipitationProvider.swift`.
 */
enum class PrecipitationLayerType {
    /** NOAA/NWS, EUMETNET OPERA. */
    RADAR,

    /** NASA GPM IMERG / GIBS. */
    SATELLITE_ESTIMATE,
    ;

    /** The user-facing label for this layer type. */
    val uiLabel: String
        get() = when (this) {
            RADAR -> "Radar precipitation"
            SATELLITE_ESTIMATE -> "Satellite precipitation estimate"
        }

    val isRadar: Boolean get() = this == RADAR
}

/**
 * One radar time step. For NOAA base reflectivity / MRMS this is an observed frame
 * ([isForecast] false); the type carries a forecast flag only so a future provider that
 * offered nowcast frames could be added without a model change. This app never displays
 * forecast/model precipitation *as* radar.
 */
data class RadarFrame(
    val id: String,
    val timestampMillis: Long,
    val isForecast: Boolean = false,
    val label: String,
)

/**
 * A deterministic precipitation cell. Used for Mock Mode and tests (where we have an
 * exact polygon), and as the vector form radar raster sampling reduces to so the
 * route-conflict detector can treat both uniformly.
 *
 * iOS gives this identity-only equality (it carries non-Equatable coordinates); the same
 * is reproduced here so a mutated copy still equals its original, and [id] is last so
 * `copy()` carries it over.
 */
class RadarCell(
    val polygon: List<Coordinate>,
    val intensity: WeatherIntensity,
    val movementDirectionDegrees: Double? = null,
    val movementSpeedKnots: Double? = null,
    val id: String = UUID.randomUUID().toString(),
) {
    val center: Coordinate?
        get() {
            val valid = polygon.filter { it.isValid }
            if (valid.isEmpty()) return null
            return Coordinate(
                valid.sumOf { it.latitude } / valid.size,
                valid.sumOf { it.longitude } / valid.size,
            )
        }

    override fun equals(other: Any?): Boolean = other is RadarCell && id == other.id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "RadarCell(intensity=$intensity, vertices=${polygon.size})"
}

/**
 * The state the Weather screen renders for the radar precipitation layer. Purely
 * descriptive — the actual image fetch is done from the live provider, so this model
 * never holds stale imagery.
 *
 * Ported from `IFATCCompanion/Weather/RadarOverlayModel.swift`.
 */
data class RadarOverlayModel(
    /** Whether the user's setting enables the overlay (Auto where available). */
    val isEnabled: Boolean = false,
    /** Whether a provider (NOAA / OPERA / NASA) actually covers the current region. */
    val coverageAvailable: Boolean = false,
    val opacity: Double = 0.55,
    val lastUpdatedMillis: Long? = null,
    /** The active provider's display name (e.g. "NOAA/NWS radar precipitation"). */
    val sourceDescription: String = "NOAA/NWS radar precipitation",
    val attributionText: String? = "Radar precipitation data: NOAA/NWS",
    val coverageLabel: String = "Available in NOAA-covered radar regions",
    val unavailableMessage: String = "Precipitation overlay unavailable for this region.",
    /**
     * Radar vs satellite estimate — drives the user-facing layer label so a satellite
     * estimate is never presented as radar.
     */
    val layerType: PrecipitationLayerType = PrecipitationLayerType.RADAR,
    /** The user-facing layer label. */
    val layerLabel: String = "Radar precipitation",
    val frames: List<RadarFrame> = emptyList(),
    /**
     * Deterministic precipitation cells for Mock Mode / tests. Empty in live mode (live
     * precipitation is drawn from the provider's image overlay instead).
     */
    val mockCells: List<RadarCell> = emptyList(),
    /**
     * Moderate-or-greater precipitation cells sampled from the live radar image
     * (NOAA/OPERA base reflectivity). These are the sole input to the weather-deviation
     * flow, which threads the widest clear gap between them — never drawn on the map
     * (the radar image overlay already shows the precipitation). Empty in Mock Mode
     * (which uses [mockCells]) and wherever true-radar sampling is unavailable.
     */
    val sampledCells: List<RadarCell> = emptyList(),
) {
    /** Whether the active layer is a (lower-confidence) satellite estimate. */
    val isSatelliteEstimate: Boolean get() = layerType == PrecipitationLayerType.SATELLITE_ESTIMATE

    /** Whether the overlay should actually be shown on the map right now. */
    val shouldDisplay: Boolean get() = isEnabled && coverageAvailable
}
