package com.h3consultingpartners.ifatccompanion.core.map

import com.h3consultingpartners.ifatccompanion.core.config.AppConfig
import com.h3consultingpartners.ifatccompanion.core.net.AppHttp
import com.h3consultingpartners.ifatccompanion.core.net.HttpFetching
import com.h3consultingpartners.ifatccompanion.core.net.HttpResult
import com.h3consultingpartners.ifatccompanion.core.ui.LegalStrings
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.RadarBoundingBox
import com.h3consultingpartners.ifatccompanion.core.weather.radar.PixelSize
import com.h3consultingpartners.ifatccompanion.core.weather.radar.queryString

/**
 * Land and ocean imagery to sit under the route map.
 *
 * This is option C from `Docs/ANDROID_MAPPING.md`, and the reason it is worth having at
 * all is that it costs nothing new: NASA's Global Imagery Browse Services is **keyless,
 * public and needs no account**, and the app already calls it for the global precipitation
 * estimate. So there is no new provider, no API key, no billing account and no backend —
 * the three things that disqualified every commercial base map.
 *
 * **It is the only part of the base map that can fail**, and that is by design. The
 * coastlines are bundled and the graticule is arithmetic, so when this returns null — no
 * signal at altitude, GIBS unreachable, the request simply not finished — the map keeps
 * its coastlines, its grid and its scale bar. Losing imagery costs detail, never
 * legibility. Callers are expected to treat null as ordinary and not as an error worth
 * telling the pilot about.
 *
 * `BlueMarble_ShadedRelief_Bathymetry` is the layer because it is **static**: it carries
 * no TIME dimension, so a request cannot go stale and there is nothing to keep refreshing.
 * A weather layer would need currency; a picture of the ground does not.
 */
class BaseImageryService(
    private val http: HttpFetching,
    private val wmsBaseUrl: String = AppConfig.Endpoints.NASA_GIBS_WMS,
    private val layerIdentifier: String = DEFAULT_LAYER,
) {

    /**
     * Imagery covering [bbox] at [size], or null when it cannot be had.
     *
     * Null is the expected offline answer, not a failure to report.
     */
    suspend fun imagery(bbox: RadarBoundingBox, size: PixelSize): ByteArray? {
        val url = imageryUrl(bbox, size) ?: return null
        val result = http.get(
            url,
            headers = mapOf("User-Agent" to AppHttp.userAgent),
            timeoutSeconds = TIMEOUT_SECONDS,
        )
        val response = (result as? HttpResult.Success)?.response ?: return null
        // A WMS error is a 4xx/5xx carrying an XML ServiceException, which is a non-empty
        // body that is not an image. Rejecting it here keeps the decoder honest.
        if (!response.isSuccess) return null
        val body = response.body
        return if (body.isEmpty()) null else body
    }

    /**
     * The GIBS WMS 1.1.1 GetMap URL, in EPSG:3857 to match the map's own projection so the
     * result is a straight blit with no reprojection.
     *
     * Returns null for a degenerate size rather than building a request the service will
     * reject.
     */
    fun imageryUrl(bbox: RadarBoundingBox, size: PixelSize): String? {
        if (!size.isValid) return null
        val base = wmsBaseUrl.trim()
        if (base.isEmpty()) return null
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
                // No TIME: the layer is static, so GIBS serves the one image it has.
            ),
        )
        val separator = if (base.contains('?')) "&" else "?"
        return "$base$separator$query"
    }

    companion object {
        /**
         * Shaded relief with bathymetry: land and sea floor, no clouds, no date.
         * Static, so it never needs refreshing and cannot be out of date.
         */
        const val DEFAULT_LAYER = "BlueMarble_ShadedRelief_Bathymetry"

        /**
         * Short on purpose. Imagery is an enhancement; waiting on it would make the map
         * feel broken on a slow link when it has coastlines to draw immediately.
         */
        const val TIMEOUT_SECONDS = 12L

        /**
         * NASA asks that GIBS be credited wherever its imagery appears. Displayed with the
         * map, alongside the OpenStreetMap attribution the taxi map carries for different
         * and stricter reasons.
         */
        const val ATTRIBUTION = LegalStrings.BaseMap.IMAGERY_ATTRIBUTION_LONG
    }
}
