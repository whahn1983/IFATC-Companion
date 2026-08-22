package com.h3consultingpartners.ifatccompanion.map

import androidx.compose.ui.graphics.ImageBitmap
import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.map.BaseImageryService
import com.h3consultingpartners.ifatccompanion.core.map.BaseMapWindow
import com.h3consultingpartners.ifatccompanion.core.map.ImageryResult
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticLevel
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink
import com.h3consultingpartners.ifatccompanion.ui.map.GeoBounds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Fetches and decodes the route map's satellite underlay.
 *
 * This lives outside `ui/map` on purpose. Everything in that package is pure Compose so it
 * can be type-checked against JetBrains Compose without the Android SDK (see
 * `settings-uicheck.gradle.kts`), and `BitmapFactory` is Android-only. So the decode is
 * here and only the resulting [ImageBitmap] crosses into the drawing layer.
 *
 * **No failure throws, and no failure reaches the pilot's flight UI.** Bundled coastlines
 * and an arithmetic graticule are what make the map legible; imagery is detail on top, so
 * losing it costs detail and never legibility.
 *
 * It does distinguish between the two ways of losing it, because they need opposite
 * handling. [Outcome.Unavailable] is being offline — retry later, record nothing.
 * [Outcome.Rejected] is the service answering and refusing, or bytes that are not an image:
 * a wrong layer identifier or a moved endpoint fails that way on every request forever, and
 * without a Diagnostics line it would be indistinguishable from no signal for the life of
 * the app.
 */
class BaseMapImageryLoader(
    private val service: BaseImageryService,
    private val diagnostics: DiagnosticsSink = DiagnosticsSink.noop,
    private val decodeContext: CoroutineDispatcher = Dispatchers.Default,
) {

    /** A decoded image and the exact window it covers, ready for `BaseMapModel`. */
    data class Imagery(val image: ImageBitmap, val bounds: GeoBounds)

    /** What one attempt produced. */
    sealed interface Outcome {
        data class Loaded(val imagery: Imagery) : Outcome

        /** Nothing arrived. Ordinary offline: worth another attempt, not worth a word. */
        data object Unavailable : Outcome

        /**
         * Something arrived and was not usable. Recorded in Diagnostics; retrying is
         * pointless unless the status says otherwise.
         */
        data class Rejected(val retryable: Boolean) : Outcome
    }

    /**
     * One attempt at imagery covering [coordinates], with room to pan around them.
     *
     * One request per route rather than one per viewport change: see [BaseMapWindow] for
     * why the window is padded instead of tracking the camera.
     */
    suspend fun load(coordinates: List<Coordinate>): Outcome {
        val box = BaseMapWindow.coverage(coordinates) ?: return Outcome.Unavailable
        val size = BaseMapWindow.pixelSize(box)
        if (!size.isValid) return Outcome.Unavailable

        val bytes = when (val result = service.imagery(box, size)) {
            is ImageryResult.Image -> result.bytes
            ImageryResult.Unavailable -> return Outcome.Unavailable
            is ImageryResult.Rejected -> {
                diagnostics.log(
                    category = DiagnosticCategory.WEATHER,
                    level = DiagnosticLevel.WARNING,
                    message = "Base map imagery refused: ${result.detail}",
                )
                return Outcome.Rejected(retryable = result.worthRetrying)
            }
        }

        val image = decodeRaster(bytes, decodeContext)
        if (image == null) {
            // Bytes that arrived and did not decode mean the response was not the image it
            // claimed to be. Retryable because a truncated body is the likeliest cause.
            diagnostics.log(
                category = DiagnosticCategory.WEATHER,
                level = DiagnosticLevel.WARNING,
                message = "Base map imagery did not decode (${bytes.size} bytes)",
            )
            return Outcome.Rejected(retryable = true)
        }

        return Outcome.Loaded(
            Imagery(
                image = image,
                bounds = GeoBounds(
                    south = box.minLatitude,
                    west = box.minLongitude,
                    north = box.maxLatitude,
                    east = box.maxLongitude,
                ),
            ),
        )
    }
}
