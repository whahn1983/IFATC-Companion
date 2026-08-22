package com.h3consultingpartners.ifatccompanion.core.weather.radar

import com.h3consultingpartners.ifatccompanion.core.config.AppConfig
import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.net.AppHttp
import com.h3consultingpartners.ifatccompanion.core.net.HttpFetching
import com.h3consultingpartners.ifatccompanion.core.net.HttpResult
import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.HazardConfidence
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.RadarBoundingBox

/**
 * A precipitation overlay provider for a map region.
 *
 * All fetches are keyless and free, and every implementation must be compatible with
 * commercial app inclusion, redistribution/display, and attribution-only terms.
 * Providers that would require a paid plan, user account, API-key billing, or
 * non-commercial-only terms are out of scope. Selection order is
 * NOAA → OPERA → NASA (see [PrecipitationOverlayService]).
 *
 * Ported from `IFATCCompanion/Weather/RadarPrecipitationProvider.swift`. The Swift
 * protocol's default-implementation extension becomes default interface methods.
 */
interface RadarPrecipitationProvider {
    val id: String
    val displayName: String
    val coverageDescription: String
    val attributionText: String?

    /**
     * Whether this provider serves *true observed radar* (vs a satellite estimate or a
     * mock stand-in). Never advertise satellite/mock data as true radar.
     */
    val supportsTrueRadar: Boolean

    /** Radar vs satellite-estimate — drives the UI label and confidence. */
    val layerType: PrecipitationLayerType

    /** Coarse confidence in this layer (radar high, satellite lower). */
    val confidence: HazardConfidence

    /** Whether the provider covers a region (synchronous coverage check — no I/O). */
    fun covers(region: MapRegion): Boolean

    /**
     * Whether the provider can actually **render an overlay** for the region right now —
     * a stricter check than [covers]. A provider may geographically cover a region yet be
     * unable to produce imagery there (e.g. no data endpoint is configured), in which case
     * it must not be selected as the active overlay and selection falls through to the
     * next provider. Defaults to [covers] — override to gate on a live capability.
     */
    fun canRenderOverlay(region: MapRegion): Boolean = covers(region)

    /** Whether the provider covers the region (suspending form; defaults to [covers]). */
    suspend fun isAvailable(region: MapRegion): Boolean = covers(region)

    /** The time steps available for the region. */
    suspend fun availableFrames(region: MapRegion): List<RadarFrame>

    /** XYZ tile URL for a frame, when the provider is tiled (else null). */
    suspend fun overlayTileUrl(z: Int, x: Int, y: Int, frame: RadarFrame): String? = null

    /** Rendered PNG bytes for a bounding box (fetches bytes). */
    suspend fun exportImage(bbox: RadarBoundingBox, size: PixelSize, frame: RadarFrame): ByteArray?

    /**
     * A synchronous URL for the rendered image, for the map to load directly. Null when
     * the provider cannot render an image for the region (fail gracefully).
     */
    fun exportImageUrl(bbox: RadarBoundingBox, size: PixelSize, frame: RadarFrame?): String? = null

    /** The user-facing layer label (radar vs satellite estimate). */
    val uiLayerLabel: String get() = layerType.uiLabel
}

/**
 * Shared image-fetch behaviour: every provider that renders server-side downloads its
 * PNG the same way — revalidating cache policy, 12-second timeout, and the app's
 * descriptive User-Agent. Empty bodies and non-2xx statuses degrade to null rather than
 * throwing, so a provider outage blanks the layer instead of failing the flight.
 */
internal suspend fun fetchOverlayPng(http: HttpFetching, url: String): ByteArray? {
    val result = http.get(
        url,
        headers = mapOf(
            "User-Agent" to AppHttp.userAgent,
            // The OkHttp equivalent of iOS's `.reloadRevalidatingCacheData`.
            "Cache-Control" to "max-age=0",
        ),
        timeoutSeconds = OVERLAY_IMAGE_TIMEOUT_SECONDS,
    )
    val response = (result as? HttpResult.Success)?.response ?: return null
    if (!response.isSuccess) return null
    return response.body.takeIf { it.isNotEmpty() }
}

internal const val OVERLAY_IMAGE_TIMEOUT_SECONDS = 12L

/**
 * Percent-encode a query parameter value. Every overlay URL is assembled by hand here
 * rather than with a URI builder, because `:core` is plain Kotlin/JVM and the exact
 * parameter order matters for the ArcGIS/WMS endpoints' own caches.
 */
internal fun encodeQuery(value: String): String = buildString {
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val ch = byte.toInt().toChar()
        when {
            ch.isLetterOrDigit() && ch.code < 128 -> append(ch)
            ch == '-' || ch == '_' || ch == '.' || ch == '~' -> append(ch)
            else -> append('%').append("%02X".format(byte.toInt() and 0xFF))
        }
    }
}

internal fun queryString(items: List<Pair<String, String>>): String =
    items.joinToString("&") { (name, value) -> "$name=${encodeQuery(value)}" }

/**
 * NOAA/NWS radar base reflectivity (MRMS) overlay provider. Public, keyless NWS ArcGIS
 * radar ImageServer. Coverage is limited to NOAA-covered regions (contiguous U.S. plus
 * Alaska/Hawaii/Puerto Rico approximations).
 */
class NOAARadarPrecipitationProvider(
    private val http: HttpFetching,
    private val clock: Clock = Clock.system,
    private val baseUrl: String = AppConfig.Endpoints.NWS_RADAR_IMAGE_SERVER,
) : RadarPrecipitationProvider {

    override val id = "noaa-nws-radar"
    override val displayName = "NOAA/NWS radar precipitation"
    override val coverageDescription = "Available in NOAA-covered radar regions"
    override val attributionText: String? = "Radar precipitation data: NOAA/NWS"
    override val supportsTrueRadar = true
    override val layerType = PrecipitationLayerType.RADAR
    override val confidence = HazardConfidence.HIGH

    override fun covers(region: MapRegion): Boolean = Companion.covers(region)

    override suspend fun availableFrames(region: MapRegion): List<RadarFrame> {
        if (!covers(region)) return emptyList()
        return listOf(
            RadarFrame(id = "current", timestampMillis = clock.nowMillis(), isForecast = false, label = "Current"),
        )
    }

    override suspend fun exportImage(bbox: RadarBoundingBox, size: PixelSize, frame: RadarFrame): ByteArray? {
        val url = exportImageUrl(bbox, size, frame) ?: return null
        return fetchOverlayPng(http, url)
    }

    override fun exportImageUrl(bbox: RadarBoundingBox, size: PixelSize, frame: RadarFrame?): String? {
        if (!size.isValid) return null
        // 4326 bbox, rendered in Web Mercator (3857) to align with the map's projection.
        val bboxValue =
            "${bbox.minLongitude},${bbox.minLatitude},${bbox.maxLongitude},${bbox.maxLatitude}"
        val query = queryString(
            listOf(
                "bbox" to bboxValue,
                "bboxSR" to "4326",
                "imageSR" to "3857",
                "size" to "${size.width},${size.height}",
                "format" to "png",
                "transparent" to "true",
                "f" to "image",
            ),
        )
        return "$baseUrl/exportImage?$query"
    }

    companion object {
        /**
         * Approximate NOAA radar coverage boxes (conservative; NOAA-covered regions only
         * — never implying global coverage).
         */
        val coverageBoxes: List<RadarBoundingBox> = listOf(
            RadarBoundingBox(20.0, -130.0, 52.0, -60.0),    // CONUS
            RadarBoundingBox(50.0, -180.0, 72.0, -129.0),   // Alaska
            RadarBoundingBox(18.0, -161.0, 23.0, -154.0),   // Hawaii
            RadarBoundingBox(16.0, -68.0, 20.0, -64.0),     // Puerto Rico
        )

        fun covers(region: MapRegion): Boolean {
            val box = region.boundingBox
            return coverageBoxes.any { it.overlaps(box) }
        }

        fun covers(coordinate: Coordinate): Boolean = coverageBoxes.any { coordinate in it }
    }
}

/**
 * A deterministic, offline radar stand-in for Mock Mode and tests. Advertises itself as
 * NOT true radar and serves precipitation as vector cells (drawn as polygons), never as
 * an image claiming to be observed radar.
 */
class MockRadarPrecipitationProvider(
    private val clock: Clock = Clock.system,
) : RadarPrecipitationProvider {

    override val id = "mock-radar"
    override val displayName = "Simulated radar (Mock Mode)"
    override val coverageDescription = "Simulated coverage for the mock flight"
    override val attributionText: String? = "Simulated precipitation — Mock Mode"
    override val supportsTrueRadar = false
    override val layerType = PrecipitationLayerType.RADAR
    override val confidence = HazardConfidence.HIGH

    override fun covers(region: MapRegion): Boolean = true

    override suspend fun availableFrames(region: MapRegion): List<RadarFrame> = listOf(
        RadarFrame(
            id = "mock-current",
            timestampMillis = clock.nowMillis(),
            isForecast = false,
            label = "Current (mock)",
        ),
    )

    override suspend fun exportImage(bbox: RadarBoundingBox, size: PixelSize, frame: RadarFrame): ByteArray? = null
}
