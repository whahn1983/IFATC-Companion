package com.h3consultingpartners.ifatccompanion.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink
import com.h3consultingpartners.ifatccompanion.core.weather.WeatherSessionController
import com.h3consultingpartners.ifatccompanion.core.weather.radar.PixelSize
import com.h3consultingpartners.ifatccompanion.core.weather.radar.PrecipitationLayerType
import com.h3consultingpartners.ifatccompanion.core.weather.radar.PrecipitationOverlayService
import com.h3consultingpartners.ifatccompanion.core.weather.radar.RadarImageSampler
import com.h3consultingpartners.ifatccompanion.core.weather.radar.RasterImage
import com.h3consultingpartners.ifatccompanion.core.weather.radar.RasterImageDecoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turn the live precipitation image into the cells the deviation flow routes around.
 *
 * `RadarImageSampler` — the colour classification, the connected-component clustering, the
 * pixel-to-coordinate projection — is ported in `:core` with its own tests and had no caller
 * anywhere. The only precipitation image path in the app fetched the *visible map region*
 * and decoded it straight to a bitmap for drawing, so no `RadarCell` was ever produced: the
 * Diagnostics "sampled cells" row always read zero, the sampled-cell map layer never drew,
 * and outside Mock Mode there was no cell data for any deviation to be computed from.
 *
 * The corridor sampled here is deliberately *not* the visible map region. It is the aircraft
 * plus the route still ahead, widened by the corridor pad — the same box the source-selection
 * label is chosen for — so panning the map never changes what the deviation flow sees.
 *
 * Ported from `AppModel.sampleLivePrecipitation()` (IFATCCompanion/App/AppModel.swift:6047)
 * and its staleness gate `maybeResamplePrecipitation` (:5955).
 */
class PrecipitationSampler(
    private val overlays: PrecipitationOverlayService,
    private val weather: WeatherSessionController,
    private val clock: Clock,
    private val diagnostics: DiagnosticsSink = DiagnosticsSink.noop,
    private val decodeContext: CoroutineDispatcher = Dispatchers.Default,
) {

    /** Whether a live sample has produced cells for the corridor currently in play. */
    var cellsReady: Boolean = false
        private set

    private var lastSampleAtMillis: Long? = null
    private var sampling = false

    /**
     * Sample if the last one is stale. Returns true when fresh cells were adopted.
     *
     * Single-flighted, so a slow fetch never overlaps the next telemetry tick's attempt. The
     * region barely changes as the aircraft flies — it is the whole route ahead, not a
     * window — so resampling is driven by staleness rather than by distance travelled.
     */
    suspend fun sampleIfStale(force: Boolean = false): Boolean {
        if (sampling) return false
        val now = clock.nowMillis()
        val last = lastSampleAtMillis
        if (!force && last != null && now - last < SAMPLE_INTERVAL_MILLIS) return false
        sampling = true
        try {
            return sample()
        } finally {
            sampling = false
        }
    }

    /** Forget the corridor's cells — a new route, or a flight put down. */
    fun reset() {
        cellsReady = false
        lastSampleAtMillis = null
    }

    private suspend fun sample(): Boolean {
        val region = weather.precipitationSampleRegion() ?: return false
        val bbox = region.boundingBox
        // Sized to the corridor's exact Web-Mercator aspect at roughly 2 NM per pixel, so a
        // whole-route sample still resolves individual storms near the aircraft — and so the
        // grid's rows and columns map back onto the box without shear.
        val grid = RadarImageSampler.mercatorSampleSize(bbox)
        val image = overlays.overlayImage(region, PixelSize(grid.columns, grid.rows)) ?: return false

        // The reflectivity ramp is the radar palette; the satellite estimate is a rain-rate
        // ramp and must be read with its own, or every pixel classifies as nothing.
        val palette = if (image.provider.layerType == PrecipitationLayerType.SATELLITE_ESTIMATE) {
            RadarImageSampler.Palette.IMERG_RATE
        } else {
            RadarImageSampler.Palette.REFLECTIVITY
        }
        val cells = withContext(decodeContext) {
            RadarImageSampler.cells(
                png = image.png,
                columns = grid.columns,
                rows = grid.rows,
                bbox = bbox,
                decoder = BitmapRasterDecoder,
                palette = palette,
                projection = RadarImageSampler.PixelProjection.WEB_MERCATOR,
            )
        }
        // A decode failure keeps the last good cells rather than blanking them: "the radar
        // hiccuped" and "the storms went away" must not look the same to the deviation flow.
        if (cells == null) {
            diagnostics.log(
                DiagnosticCategory.WEATHER,
                message = "Radar image did not decode — keeping the previous precipitation cells.",
            )
            return false
        }
        lastSampleAtMillis = clock.nowMillis()
        weather.noteSampledCells(cells)
        if (cells.isNotEmpty()) cellsReady = true
        diagnostics.log(
            DiagnosticCategory.WEATHER,
            message = "Sampled ${cells.size} precipitation cell(s) from ${image.provider.displayName}.",
        )
        return cells.isNotEmpty()
    }

    companion object {
        /** iOS resamples on a 60 s staleness check while airborne and in the foreground. */
        const val SAMPLE_INTERVAL_MILLIS = 60_000L
    }
}

/**
 * The Android half of the sampling stack: PNG bytes to pixels.
 *
 * Scaled with filtering **off** on purpose. The composites are classified rasters with
 * sentinel no-data values, so smooth interpolation averages sentinels into data and
 * fabricates reflectivity at every no-data boundary — iOS sets `interpolationQuality =
 * .none` for the same reason.
 */
object BitmapRasterDecoder : RasterImageDecoder {
    override fun decode(data: ByteArray, width: Int, height: Int): RasterImage? {
        if (data.isEmpty() || width <= 0 || height <= 0) return null
        val decoded = runCatching {
            BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }.getOrNull() ?: return null
        val scaled = runCatching {
            if (decoded.width == width && decoded.height == height) {
                decoded
            } else {
                decoded.scale(width, height, filter = false)
            }
        }.getOrNull() ?: return null
        val pixels = IntArray(width * height)
        runCatching { scaled.getPixels(pixels, 0, width, 0, 0, width, height) }
            .getOrElse { return null }
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        return ArrayRasterImage(width, height, pixels)
    }
}

/** A decoded image held as a flat ARGB array, row-major with row 0 at the top. */
private class ArrayRasterImage(
    override val width: Int,
    override val height: Int,
    private val pixels: IntArray,
) : RasterImage {
    override fun argbAt(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) return 0
        return pixels[y * width + x]
    }
}
