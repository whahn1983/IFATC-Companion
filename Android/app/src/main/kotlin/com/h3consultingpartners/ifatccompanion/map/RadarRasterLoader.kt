package com.h3consultingpartners.ifatccompanion.map

import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticLevel
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink
import com.h3consultingpartners.ifatccompanion.core.weather.radar.MapRegion
import com.h3consultingpartners.ifatccompanion.core.weather.radar.PixelSize
import com.h3consultingpartners.ifatccompanion.core.weather.radar.PrecipitationOverlayService
import com.h3consultingpartners.ifatccompanion.ui.map.GeoBounds
import com.h3consultingpartners.ifatccompanion.ui.map.RadarRaster
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Fetches and decodes the precipitation raster the route map draws over everything else.
 *
 * `PrecipitationOverlayService` already does all the hard parts — choosing a provider that
 * covers the region, building the export URL, fetching, and backing off from one that keeps
 * failing. Its KDoc says outright that "the image itself is fetched by the app"; until now
 * nothing did, so a pipeline with a provider ladder, a sampler and a failure cooldown put no
 * pixels anywhere. The vector cells and the advisory shading drew, and the raster the README
 * advertises did not.
 *
 * The service returns the bounding box it actually rendered, so placement is exact rather
 * than aligned-by-eye. iOS overlays its image on the visible region and its own comment
 * calls that "intentionally approximate"; this does better for free, because the Android
 * map projects its own coordinates.
 */
class RadarRasterLoader(
    private val overlays: PrecipitationOverlayService,
    private val diagnostics: DiagnosticsSink = DiagnosticsSink.noop,
    private val decodeContext: CoroutineDispatcher = Dispatchers.Default,
) {

    /**
     * The raster for [region] at [size], or null when there is none to draw.
     *
     * Null is ordinary: no provider covers the region, the provider is in its failure
     * cooldown, there is no signal, or the fetch simply has not finished. In every one of
     * those the map keeps its base layers, its vector cells and its advisory shading.
     */
    suspend fun load(region: MapRegion, size: PixelSize): RadarRaster? {
        if (!size.isValid) return null
        val overlay = overlays.overlayImage(region, size) ?: return null
        val image = decodeRaster(overlay.png, decodeContext)
        if (image == null) {
            // Bytes arrived and were not an image. Worth recording, unlike the no-coverage
            // and offline cases: it means a provider is answering with something other than
            // what it promised, which is otherwise indistinguishable from having no radar.
            diagnostics.log(
                category = DiagnosticCategory.WEATHER,
                level = DiagnosticLevel.WARNING,
                message = "Precipitation raster from ${overlay.provider.id} did not decode " +
                    "(${overlay.png.size} bytes)",
            )
            return null
        }
        return RadarRaster(
            image = image,
            bounds = GeoBounds(
                south = overlay.bbox.minLatitude,
                west = overlay.bbox.minLongitude,
                north = overlay.bbox.maxLatitude,
                east = overlay.bbox.maxLongitude,
            ),
        )
    }
}
