package com.h3consultingpartners.ifatccompanion.core.weather.radar

import com.h3consultingpartners.ifatccompanion.core.net.HttpFetching
import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink
import kotlin.math.roundToInt

/**
 * Selects the active precipitation overlay provider for a region and builds its image
 * URL. Provider preference order is **NOAA → EUMETNET OPERA → NASA GIBS**:
 *
 * 1. Inside NOAA radar coverage → NOAA/NWS radar precipitation.
 * 2. Else inside EUMETNET OPERA (Europe) coverage → OPERA radar precipitation **when it
 *    can render**. OPERA's ORD render is **currently disabled** (see the provider
 *    construction below), so today Europe falls through to case 3.
 * 3. Else → NASA global satellite precipitation *estimate* (never called radar).
 * 4. If none covers the region → no overlay ("Precipitation overlay unavailable for this
 *    region.").
 *
 * In Mock Mode the offline mock provider stands in. Only these providers ship — no paid
 * or unclear-commercial-use providers.
 *
 * Ported from `IFATCCompanion/Weather/PrecipitationOverlayService.swift`. One thing
 * differs: iOS renders the asynchronous (ORD OPERA) overlay into a temp PNG file and hands
 * SwiftUI's `AsyncImage` that file URL, deduping in-flight renders per region key. Android
 * draws its own map, so a provider that has no direct URL is fetched as **bytes** through
 * [overlayImage] instead of a file cache — the same call the sampler already needs. The
 * per-region [overlayKey] quantization and the failure-streak cooldown are both kept: a
 * provider whose endpoint is persistently unreachable must still fall through rather than
 * leave the map blank while claiming coverage.
 */
class PrecipitationOverlayService(
    private val providers: List<RadarPrecipitationProvider>,
    private val mockProvider: RadarPrecipitationProvider,
    private val clock: Clock = Clock.system,
) {

    constructor(
        http: HttpFetching,
        clock: Clock = Clock.system,
    ) : this(
        providers = listOf(
            NOAARadarPrecipitationProvider(http, clock),
            // OPERA ORD rendering is disabled in shipping builds. Decoding the raw
            // scientific DBZH GeoTIFF produces a garbled field — false clutter speckle over
            // clear ocean AND little/no signal where real precipitation is heavy — because
            // a general-purpose image decoder can't faithfully read/scale the single-band
            // sample values. There is no keyless, rendered, cleanly licensed pan-European
            // radar source to swap in: LibreWXR is close (keyless, RainViewer-compatible
            // tiles, includes OPERA) but its European composite carries a CC-BY-SA
            // **share-alike** obligation via DPC Italy and offers no production reliability.
            // Until a validated source exists, OPERA still *covers* Europe but *cannot
            // render*, so selection falls through to the NASA satellite estimate (clearly
            // labelled, not called radar). The provider and its whole ORD/renderer/store
            // stack stay in place — flip `useORD = true` (or configure a WMS endpoint) to
            // re-enable.
            EUMETNETOPERARadarProvider(http, clock, useORD = false),
            NASAGIBSPrecipitationProvider(http, clock),
        ),
        mockProvider = MockRadarPrecipitationProvider(clock),
        clock = clock,
    )

    private var useMock = false
    private var diagnostics: DiagnosticsSink? = null

    var lastUpdateMillis: Long? = null
        private set

    var lastError: String? = null
        private set

    /**
     * Consecutive render failures per provider id, and a cooldown after too many, so a
     * provider whose live source is persistently unreachable stops winning selection and
     * falls through to the next (e.g. NASA) instead of leaving the map blank while
     * claiming coverage — self-recovering once the cooldown ends.
     */
    private val renderFailureStreak = mutableMapOf<String, Int>()
    private val renderCooldownUntil = mutableMapOf<String, Long>()

    fun configure(diagnostics: DiagnosticsSink?) {
        this.diagnostics = diagnostics
    }

    /** Use the mock provider (Mock Mode) instead of the live selection. */
    fun useMockProvider(on: Boolean) {
        useMock = on
    }

    // region Selection

    /**
     * The first provider (NOAA → OPERA → NASA) that both **covers** the region and can
     * actually **render** an overlay there, or null. The stricter render check is what
     * keeps a provider that geographically covers Europe but has no working data source
     * from winning selection and blanking the map while falsely reporting coverage —
     * selection falls through to the next provider that can render (the NASA satellite
     * estimate) instead.
     */
    fun selectedProvider(region: MapRegion): RadarPrecipitationProvider? {
        if (useMock) return mockProvider
        return providers.firstOrNull {
            it.covers(region) && it.canRenderOverlay(region) && !inRenderCooldown(it.id)
        }
    }

    /** Whether a provider is currently cooling down after repeated render failures. */
    private fun inRenderCooldown(id: String): Boolean {
        val until = renderCooldownUntil[id] ?: return false
        if (until > clock.nowMillis()) return true
        renderCooldownUntil.remove(id)   // expired → allow a retry
        return false
    }

    // There is deliberately no `selectedProvider(for positions:)` convenience that boxes
    // an arbitrary set of coordinates. Coverage is a bounding-box *overlap*, so folding a
    // fixed point (a filed departure) into the box pins the selection there for a whole
    // flight: KIAH→EGLL stayed on NOAA gate to gate and labelled the NASA satellite
    // estimate over England as radar. Callers build the region they actually mean — one
    // region, from which both the sampler and the Source/Layer labels select.

    // endregion

    // region Rendering

    /**
     * The overlay image URL for the map's visible region, from the selected provider.
     * Null when nothing covers the region, or the provider renders vector cells (mock).
     */
    fun imageUrl(region: MapRegion, size: PixelSize): String? {
        val provider = selectedProvider(region) ?: return null
        val bbox = region.boundingBox
        val frame = RadarFrame(id = "current", timestampMillis = clock.nowMillis(), label = "Current")
        val url = provider.exportImageUrl(bbox, size, frame)
        if (url != null) {
            lastUpdateMillis = clock.nowMillis()
            return url
        }
        return null
    }

    /**
     * Fetch the rendered overlay bytes for a region, for the sampler (which needs the
     * pixels, not a URL). Records success or failure against the provider's streak, so a
     * persistently failing provider eventually falls through.
     */
    suspend fun overlayImage(region: MapRegion, size: PixelSize): OverlayImage? {
        val provider = selectedProvider(region) ?: return null
        val bbox = region.boundingBox
        val frame = RadarFrame(id = "current", timestampMillis = clock.nowMillis(), label = "Current")
        val bytes = runCatching { provider.exportImage(bbox, size, frame) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            noteRenderFailure(provider.id)
            return null
        }
        lastUpdateMillis = clock.nowMillis()
        lastError = null
        renderFailureStreak[provider.id] = 0
        renderCooldownUntil.remove(provider.id)
        return OverlayImage(png = bytes, provider = provider, bbox = bbox, size = size)
    }

    /**
     * Record a render failure; after [RENDER_FAILURE_THRESHOLD] in a row put the provider
     * in a cooldown so selection falls through to the next one.
     */
    private fun noteRenderFailure(id: String) {
        // iOS words this "OPERA composite unavailable" because its async-render path is only
        // ever reached by the ORD OPERA provider. Here every provider's bytes come through
        // the same call, so the message names the layer rather than one source.
        lastError = "Precipitation overlay unavailable"
        val streak = (renderFailureStreak[id] ?: 0) + 1
        renderFailureStreak[id] = streak
        if (streak >= RENDER_FAILURE_THRESHOLD) {
            renderCooldownUntil[id] = clock.nowMillis() + RENDER_COOLDOWN_MILLIS
            renderFailureStreak[id] = 0
        }
    }

    /** The rendered bytes plus the provider and extent they belong to. */
    data class OverlayImage(
        val png: ByteArray,
        val provider: RadarPrecipitationProvider,
        val bbox: com.h3consultingpartners.ifatccompanion.core.weather.deviation.RadarBoundingBox,
        val size: PixelSize,
    ) {
        override fun equals(other: Any?): Boolean =
            other is OverlayImage && png.contentEquals(other.png) &&
                provider.id == other.provider.id && bbox == other.bbox && size == other.size

        override fun hashCode(): Int =
            ((png.contentHashCode() * 31 + provider.id.hashCode()) * 31 + bbox.hashCode()) * 31 + size.hashCode()
    }

    // endregion

    companion object {
        const val RENDER_FAILURE_THRESHOLD = 3
        const val RENDER_COOLDOWN_MILLIS = 120_000L

        /** A coarse region+size cache key (quantized so small pans reuse a render). */
        fun overlayKey(region: MapRegion, size: PixelSize): String {
            fun q(value: Double, precision: Double): Int = (value / precision).roundToInt()
            return listOf(
                q(region.centerLatitude, 0.25),
                q(region.centerLongitude, 0.25),
                q(region.latitudeDelta, 0.25),
                q(region.longitudeDelta, 0.25),
                size.width,
                size.height,
            ).joinToString("_")
        }
    }
}
