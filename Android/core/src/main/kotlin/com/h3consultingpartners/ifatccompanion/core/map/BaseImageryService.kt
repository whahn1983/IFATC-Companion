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
 * coastlines are bundled and the graticule is arithmetic, so when this cannot deliver —
 * no signal at altitude, GIBS unreachable, the request simply not finished — the map keeps
 * its coastlines, its grid and its scale bar. Losing imagery costs detail, never
 * legibility.
 *
 * It reports **why** it failed rather than only that it did, because the two cases need
 * opposite handling and look identical from outside. [ImageryResult.Unavailable] is the
 * ordinary offline answer: say nothing, try again later. [ImageryResult.Rejected] means the
 * service answered and refused — a layer identifier that no longer exists, an endpoint that
 * moved — and that would otherwise be indistinguishable from being offline for the life of
 * the app, on a device, with nothing recorded anywhere.
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

    /** Imagery covering [bbox] at [size], or why it could not be had. */
    suspend fun imagery(bbox: RadarBoundingBox, size: PixelSize): ImageryResult {
        val url = imageryUrl(bbox, size) ?: return ImageryResult.Unavailable
        val result = http.get(
            url,
            headers = mapOf("User-Agent" to AppHttp.userAgent),
            timeoutSeconds = TIMEOUT_SECONDS,
        )
        val response = when (result) {
            is HttpResult.Success -> result.response
            // No response reached us at all: offline, DNS, TLS, timeout. Ordinary.
            is HttpResult.Failure -> return ImageryResult.Unavailable
        }
        // A WMS error is a 4xx/5xx carrying an XML ServiceException — a non-empty body that
        // is not an image. Catching it here keeps it out of the decoder, and naming the
        // status is what makes a wrong layer identifier findable instead of permanent.
        if (!response.isSuccess) {
            return ImageryResult.Rejected(response.status, "HTTP ${response.status}")
        }
        val body = response.body
        // A 200 with nothing in it is the service refusing in the politest possible way.
        if (body.isEmpty()) return ImageryResult.Rejected(response.status, "empty body")
        return ImageryResult.Image(body)
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

/**
 * What [BaseImageryService.imagery] came back with.
 *
 * Three cases rather than a nullable, because "no signal" and "the service refused" call
 * for opposite behaviour and are otherwise indistinguishable: one should be retried and
 * never mentioned, the other should be recorded and never retried.
 */
sealed interface ImageryResult {

    /** The encoded image, ready to decode. */
    data class Image(val bytes: ByteArray) : ImageryResult {
        override fun equals(other: Any?): Boolean =
            other is Image && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /**
     * Nothing to request, or no response reached us. The expected answer at altitude with
     * no signal — worth retrying later, never worth telling the pilot about.
     */
    data object Unavailable : ImageryResult

    /**
     * The service answered and refused.
     *
     * This is the case that must not be silent. A layer identifier that stopped existing
     * fails this way on every request forever, and looks exactly like being offline.
     */
    data class Rejected(val status: Int, val detail: String) : ImageryResult {
        /** A throttle or a 5xx may pass; a 400 on a malformed request never will. */
        val worthRetrying: Boolean get() = AppHttp.isRetryableStatus(status)
    }
}
