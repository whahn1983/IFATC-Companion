package com.h3consultingpartners.ifatccompanion.core.weather.radar

import com.h3consultingpartners.ifatccompanion.core.config.AppConfig
import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.net.HttpFetching
import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.HazardConfidence
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.RadarBoundingBox

/**
 * EUMETNET OPERA radar composite provider for Europe. True radar precipitation, sourced
 * from the **EUMETNET Open Radar Data (ORD)** 24-hour cache — the keyless, anonymous
 * public bucket that distributes the pan-European OPERA composite (produced by
 * **CIRRUS**, which replaced ODYSSEY in 2024). Honors **CC BY 4.0** attribution.
 * Coverage is best-effort over Europe — the app does **not** assume every country has
 * usable composite coverage, and fails gracefully (falling through to the NASA satellite
 * estimate) where the render can't be produced.
 *
 * **Rendering is off in shipping builds on both platforms.** iOS ships
 * `useORD: false` because decoding the raw scientific DBZH GeoTIFF with ImageIO
 * produces a garbled field — false clutter over clear ocean and little signal where
 * precipitation is heavy. The Android port therefore does not carry the ORD client or
 * the composite renderer either: reproducing a decode path that is disabled upstream
 * would ship a known-bad layer, and no keyless, rendered, cleanly licensed pan-European
 * radar source exists to swap in. Europe falls through to the clearly-labelled NASA
 * satellite estimate. The provider stays in place, and a WMS endpoint configured through
 * [wmsBaseUrl] re-enables it immediately — see Docs/ANDROID_DATA_SOURCES.md.
 *
 * Ported from `IFATCCompanion/Weather/PrecipitationProviders.swift`.
 */
class EUMETNETOPERARadarProvider(
    private val http: HttpFetching,
    private val clock: Clock = Clock.system,
    /**
     * Optional WMS GetMap endpoint for a compatible OPERA/ORD composite service. When
     * set it is the provider's only render path.
     */
    val wmsBaseUrl: String = "",
    /** The product to request (defaults to the top preference). */
    val product: Product = Product.MAXIMUM_REFLECTIVITY,
) : RadarPrecipitationProvider {

    /** OPERA composite products, in preference order. */
    enum class Product(val wmsLayerName: String, val ordProductCode: String) {
        MAXIMUM_REFLECTIVITY("opera_maximum_reflectivity", "DBZH"),
        INSTANTANEOUS_RAIN_RATE("opera_instantaneous_rain_rate", "RATE"),
        ONE_HOUR_ACCUMULATION("opera_1h_accumulation", "ACRR"),
    }

    override val id = "eumetnet-opera-radar"
    override val displayName = "EUMETNET OPERA radar precipitation"
    override val coverageDescription = "Available where EUMETNET OPERA radar data is provided (Europe)"
    override val attributionText: String? =
        "Radar precipitation data: EUMETNET OPERA / CIRRUS composite (CC BY 4.0)"
    override val supportsTrueRadar = true
    override val layerType = PrecipitationLayerType.RADAR
    override val confidence = HazardConfidence.HIGH

    override fun covers(region: MapRegion): Boolean = coverageBox.overlaps(region.boundingBox)

    /**
     * OPERA can render where it covers the region **and** it has a working source. With
     * the ORD path absent (see the class note), that means a configured WMS endpoint and
     * nothing else — so today the provider covers Europe but cannot draw it, and
     * selection falls through to the NASA satellite estimate rather than claiming
     * coverage it can't render.
     */
    override fun canRenderOverlay(region: MapRegion): Boolean =
        covers(region) && wmsBaseUrl.trim().isNotEmpty()

    override suspend fun availableFrames(region: MapRegion): List<RadarFrame> {
        if (!covers(region)) return emptyList()
        return listOf(
            RadarFrame(
                id = "opera-current",
                timestampMillis = clock.nowMillis(),
                isForecast = false,
                label = "Current (OPERA)",
            ),
        )
    }

    override suspend fun exportImage(bbox: RadarBoundingBox, size: PixelSize, frame: RadarFrame): ByteArray? {
        val url = exportImageUrl(bbox, size, frame) ?: return null
        return fetchOverlayPng(http, url)
    }

    /**
     * Build a WMS 1.1.1 GetMap for the configured OPERA/ORD service (EPSG:3857). Null
     * when no WMS endpoint is configured, which is the shipping default.
     */
    override fun exportImageUrl(bbox: RadarBoundingBox, size: PixelSize, frame: RadarFrame?): String? {
        val base = wmsBaseUrl.trim()
        if (base.isEmpty() || !size.isValid) return null
        val query = queryString(
            listOf(
                "SERVICE" to "WMS",
                "VERSION" to "1.1.1",
                "REQUEST" to "GetMap",
                "LAYERS" to product.wmsLayerName,
                "STYLES" to "",
                "SRS" to "EPSG:3857",
                "BBOX" to bbox.mercatorBBoxString,
                "WIDTH" to size.width.toString(),
                "HEIGHT" to size.height.toString(),
                "FORMAT" to "image/png",
                "TRANSPARENT" to "TRUE",
            ),
        )
        val separator = if (base.contains('?')) "&" else "?"
        return "$base$separator$query"
    }

    companion object {
        /** Preferred product order: max reflectivity → rain rate → 1-hour accumulation. */
        val preferredProducts: List<Product> = listOf(
            Product.MAXIMUM_REFLECTIVITY,
            Product.INSTANTANEOUS_RAIN_RATE,
            Product.ONE_HOUR_ACCUMULATION,
        )

        /** Preferred raster format for rendering (cloud-optimized GeoTIFF over HDF5). */
        val preferredFormats: List<String> = listOf("cog-geotiff", "geotiff", "odim-hdf5")

        /**
         * Best-effort OPERA coverage box over Europe. Deliberately conservative; not every
         * country inside the box necessarily has usable ORD coverage.
         */
        val coverageBox = RadarBoundingBox(34.0, -32.0, 72.0, 45.0)

        fun covers(coordinate: Coordinate): Boolean = coordinate in coverageBox
    }
}

/**
 * NASA global satellite precipitation estimate via NASA Global Imagery Browse Services
 * (GIBS), part of NASA Earth Science Data and Information System, using GPM IMERG where
 * applicable. This is a **satellite precipitation estimate — not radar** — and is always
 * labelled as such and treated as lower confidence than NOAA/OPERA radar. Used as the
 * global fallback outside NOAA and OPERA coverage.
 */
class NASAGIBSPrecipitationProvider(
    private val http: HttpFetching,
    private val clock: Clock = Clock.system,
    /** GIBS WMS endpoint (keyless). EPSG:3857 to align with the map's projection. */
    private val wmsBaseUrl: String = AppConfig.Endpoints.NASA_GIBS_WMS,
    /** GIBS layer identifier for the IMERG precipitation rate. */
    private val layerIdentifier: String = "IMERG_Precipitation_Rate",
) : RadarPrecipitationProvider {

    override val id = "nasa-gibs-imerg"
    override val displayName = "NASA global satellite precipitation estimate"
    override val coverageDescription = "Global satellite precipitation estimate (approx. ±60° latitude)"
    override val attributionText: String? =
        "Imagery/data provided by NASA Global Imagery Browse Services (GIBS), part of " +
            "NASA Earth Science Data and Information System, and NASA GPM IMERG where applicable."
    override val supportsTrueRadar = false
    override val layerType = PrecipitationLayerType.SATELLITE_ESTIMATE
    override val confidence = HazardConfidence.LOW

    override fun covers(region: MapRegion): Boolean = coverageBox.overlaps(region.boundingBox)

    override suspend fun availableFrames(region: MapRegion): List<RadarFrame> {
        if (!covers(region)) return emptyList()
        return listOf(
            RadarFrame(
                id = "imerg-current",
                timestampMillis = clock.nowMillis(),
                isForecast = false,
                label = "Latest satellite estimate",
            ),
        )
    }

    override suspend fun exportImage(bbox: RadarBoundingBox, size: PixelSize, frame: RadarFrame): ByteArray? {
        val url = exportImageUrl(bbox, size, frame) ?: return null
        return fetchOverlayPng(http, url)
    }

    /**
     * GIBS WMS 1.1.1 GetMap in EPSG:3857. TIME is omitted so GIBS serves the layer's
     * default (latest available) estimate.
     */
    override fun exportImageUrl(bbox: RadarBoundingBox, size: PixelSize, frame: RadarFrame?): String? {
        if (!size.isValid) return null
        val query = queryString(
            listOf(
                "SERVICE" to "WMS",
                "VERSION" to "1.1.1",
                "REQUEST" to "GetMap",
                "LAYERS" to layerIdentifier,
                "STYLES" to "",
                "SRS" to "EPSG:3857",
                "BBOX" to bbox.mercatorBBoxString,
                "WIDTH" to size.width.toString(),
                "HEIGHT" to size.height.toString(),
                "FORMAT" to "image/png",
                "TRANSPARENT" to "TRUE",
            ),
        )
        return "$wmsBaseUrl?$query"
    }

    companion object {
        /** IMERG is near-global but not polar; coverage is ~60°S–60°N. */
        val coverageBox = RadarBoundingBox(-60.0, -180.0, 60.0, 180.0)
    }
}
