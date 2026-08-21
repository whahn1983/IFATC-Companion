package com.h3consultingpartners.ifatccompanion.map

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.map.BaseImageryService
import com.h3consultingpartners.ifatccompanion.core.map.BaseMapWindow
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticLevel
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink
import com.h3consultingpartners.ifatccompanion.ui.map.GeoBounds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetches and decodes the route map's satellite underlay.
 *
 * This lives outside `ui/map` on purpose. Everything in that package is pure Compose so it
 * can be type-checked against JetBrains Compose without the Android SDK (see
 * `settings-uicheck.gradle.kts`), and `BitmapFactory` is Android-only. So the decode is
 * here and only the resulting [ImageBitmap] crosses into the drawing layer.
 *
 * **Every failure returns null**, and null is not an error. Bundled coastlines and an
 * arithmetic graticule are what actually make the map legible; imagery is detail on top.
 * A pilot at altitude with no signal loses the picture of the ground and keeps the map.
 */
class BaseMapImageryLoader(
    private val service: BaseImageryService,
    private val diagnostics: DiagnosticsSink = DiagnosticsSink.noop,
    private val decodeContext: CoroutineDispatcher = Dispatchers.Default,
) {

    /** A decoded image and the exact window it covers, ready for `BaseMapModel`. */
    data class Imagery(val image: ImageBitmap, val bounds: GeoBounds)

    /**
     * Imagery covering [coordinates] with room to pan around them, or null.
     *
     * One request per route rather than one per viewport change: see [BaseMapWindow] for
     * why the window is padded instead of tracking the camera.
     */
    suspend fun load(coordinates: List<Coordinate>): Imagery? {
        val box = BaseMapWindow.coverage(coordinates) ?: return null
        val size = BaseMapWindow.pixelSize(box)
        if (!size.isValid) return null

        val bytes = service.imagery(box, size) ?: return null

        // Decoding is measured in tens of milliseconds for an image this size — small, but
        // not something to do on whichever thread the caller happened to be on.
        val image = withContext(decodeContext) {
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
        if (image == null) {
            // Worth a diagnostic line, unlike the offline case: bytes that arrived and did
            // not decode mean the response was not the image it claimed to be.
            diagnostics.log(
                category = DiagnosticCategory.WEATHER,
                level = DiagnosticLevel.WARNING,
                message = "Base map imagery did not decode (${bytes.size} bytes)",
            )
            return null
        }

        return Imagery(
            image = image,
            bounds = GeoBounds(
                south = box.minLatitude,
                west = box.minLongitude,
                north = box.maxLatitude,
                east = box.maxLongitude,
            ),
        )
    }
}
