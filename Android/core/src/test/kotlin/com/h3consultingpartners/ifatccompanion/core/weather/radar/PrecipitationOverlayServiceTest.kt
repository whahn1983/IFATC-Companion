package com.h3consultingpartners.ifatccompanion.core.weather.radar

import com.h3consultingpartners.ifatccompanion.core.net.HttpFetching
import com.h3consultingpartners.ifatccompanion.core.net.HttpResponse
import com.h3consultingpartners.ifatccompanion.core.net.HttpResult
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Provider selection is where the app's most important weather promise is kept: a
 * satellite estimate must never be presented as radar, and a provider that covers a
 * region but cannot draw it must not win and blank the map.
 */
class PrecipitationOverlayServiceTest {

    private class FakeHttp(var responder: suspend (String) -> HttpResult) : HttpFetching {
        val urls = mutableListOf<String>()
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSeconds: Long): HttpResult {
            urls += url
            return responder(url)
        }
        override suspend fun post(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String>,
            timeoutSeconds: Long,
        ): HttpResult = error("overlay providers never POST")
    }

    private fun png(bytes: Int = 64, status: Int = 200) =
        HttpResult.Success(HttpResponse(status, ByteArray(bytes) { 1 }, emptyMap()))

    private fun service(http: HttpFetching, clock: MutableClock = MutableClock(0)) =
        PrecipitationOverlayService(http, clock)

    private val kansas = MapRegion(38.0, -97.0, 4.0, 6.0)
    private val england = MapRegion(52.0, -1.0, 4.0, 6.0)
    private val southernOcean = MapRegion(-75.0, 0.0, 4.0, 6.0)

    // region Selection order

    @Test
    fun noaaWinsInsideItsCoverage() {
        val provider = assertNotNull(service(FakeHttp { png() }).selectedProvider(kansas))
        assertEquals("noaa-nws-radar", provider.id)
        assertTrue(provider.supportsTrueRadar)
        assertEquals(PrecipitationLayerType.RADAR, provider.layerType)
    }

    /**
     * OPERA geographically covers Europe but has no render path in shipping builds, so
     * selection must fall through to the clearly-labelled satellite estimate rather than
     * claiming radar coverage it can't draw.
     */
    @Test
    fun europeFallsThroughToTheSatelliteEstimate() {
        val provider = assertNotNull(service(FakeHttp { png() }).selectedProvider(england))
        assertEquals("nasa-gibs-imerg", provider.id)
        assertFalse(provider.supportsTrueRadar)
        assertEquals(PrecipitationLayerType.SATELLITE_ESTIMATE, provider.layerType)
        assertEquals("Satellite precipitation estimate", provider.uiLayerLabel)
    }

    /** Outside every provider's coverage there is simply no overlay. */
    @Test
    fun aRegionNoProviderCoversHasNoOverlay() {
        assertNull(service(FakeHttp { png() }).selectedProvider(southernOcean))
        assertNull(service(FakeHttp { png() }).imageUrl(southernOcean, PixelSize(256, 256)))
    }

    /** Mock Mode stands in offline, and never claims to be true radar. */
    @Test
    fun mockModeUsesTheOfflineProvider() {
        val service = service(FakeHttp { png() })
        service.useMockProvider(true)
        val provider = assertNotNull(service.selectedProvider(southernOcean))
        assertEquals("mock-radar", provider.id)
        assertFalse(provider.supportsTrueRadar)
        // The mock draws vector cells, so it offers no image URL.
        assertNull(service.imageUrl(southernOcean, PixelSize(256, 256)))
    }

    /** A configured OPERA WMS endpoint restores it ahead of the satellite estimate. */
    @Test
    fun aConfiguredOperaEndpointWinsInEurope() {
        val http = FakeHttp { png() }
        val clock = MutableClock(0)
        val service = PrecipitationOverlayService(
            providers = listOf(
                NOAARadarPrecipitationProvider(http, clock),
                EUMETNETOPERARadarProvider(http, clock, wmsBaseUrl = "https://example.invalid/wms"),
                NASAGIBSPrecipitationProvider(http, clock),
            ),
            mockProvider = MockRadarPrecipitationProvider(clock),
            clock = clock,
        )
        assertEquals("eumetnet-opera-radar", service.selectedProvider(england)?.id)
    }

    // endregion

    // region URLs

    @Test
    fun theNoaaUrlIsAnArcgisExportImageInWebMercator() {
        val url = assertNotNull(service(FakeHttp { png() }).imageUrl(kansas, PixelSize(512, 256)))
        assertTrue(url.startsWith("https://mapservices.weather.noaa.gov/"))
        assertTrue(url.contains("/exportImage?"))
        assertTrue(url.contains("bboxSR=4326"))
        assertTrue(url.contains("imageSR=3857"))
        assertTrue(url.contains("size=512%2C256"))
        assertTrue(url.contains("format=png"))
        assertTrue(url.contains("transparent=true"))
    }

    @Test
    fun theGibsUrlIsAWms111GetMapInWebMercator() {
        val url = assertNotNull(service(FakeHttp { png() }).imageUrl(england, PixelSize(512, 256)))
        assertTrue(url.startsWith("https://gibs.earthdata.nasa.gov/"))
        assertTrue(url.contains("SERVICE=WMS"))
        assertTrue(url.contains("VERSION=1.1.1"))
        assertTrue(url.contains("REQUEST=GetMap"))
        assertTrue(url.contains("LAYERS=IMERG_Precipitation_Rate"))
        assertTrue(url.contains("SRS=EPSG%3A3857"))
        assertTrue(url.contains("WIDTH=512"))
        assertTrue(url.contains("HEIGHT=256"))
    }

    /** A zero-size request can't be rendered and must not produce a URL. */
    @Test
    fun aZeroSizedRequestHasNoUrl() {
        assertNull(service(FakeHttp { png() }).imageUrl(kansas, PixelSize(0, 0)))
    }

    // endregion

    // region Failure handling

    /**
     * A provider whose endpoint keeps failing must eventually fall through rather than
     * leave the map blank while claiming coverage — and recover once the cooldown ends.
     */
    @Test
    fun aPersistentlyFailingProviderCoolsDownAndFallsThrough() = runTest {
        val clock = MutableClock(0)
        val service = service(FakeHttp { HttpResult.Failure("offline") }, clock)
        val size = PixelSize(64, 64)

        repeat(PrecipitationOverlayService.RENDER_FAILURE_THRESHOLD) {
            assertNull(service.overlayImage(kansas, size))
        }
        // NOAA is cooling down, so Kansas now selects the satellite estimate.
        assertEquals("nasa-gibs-imerg", service.selectedProvider(kansas)?.id)

        clock.advance(PrecipitationOverlayService.RENDER_COOLDOWN_MILLIS + 1)
        assertEquals("noaa-nws-radar", service.selectedProvider(kansas)?.id)
    }

    /** A successful fetch clears the streak and stamps the update time. */
    @Test
    fun asuccessfulFetchResetsTheStreak() = runTest {
        val clock = MutableClock(1_000)
        var fail = true
        val service = service(FakeHttp { if (fail) HttpResult.Failure("offline") else png() }, clock)
        val size = PixelSize(64, 64)

        assertNull(service.overlayImage(kansas, size))
        fail = false
        val image = assertNotNull(service.overlayImage(kansas, size))
        assertEquals("noaa-nws-radar", image.provider.id)
        assertEquals(1_000L, service.lastUpdateMillis)
        assertNull(service.lastError)

        // Two more failures would have tripped the threshold had the streak survived.
        fail = true
        repeat(2) { service.overlayImage(kansas, size) }
        assertEquals("noaa-nws-radar", service.selectedProvider(kansas)?.id)
    }

    /** An empty body is a failure, not an empty overlay. */
    @Test
    fun anEmptyBodyIsAFailure() = runTest {
        val service = service(FakeHttp { png(bytes = 0) })
        assertNull(service.overlayImage(kansas, PixelSize(64, 64)))
    }

    /** A non-2xx response never becomes overlay pixels. */
    @Test
    fun anErrorStatusIsNotAnOverlay() = runTest {
        val service = service(FakeHttp { png(status = 500) })
        assertNull(service.overlayImage(kansas, PixelSize(64, 64)))
    }

    // endregion

    /** Small pans reuse a render; a real move does not. */
    @Test
    fun theOverlayKeyIsQuantized() {
        val size = PixelSize(256, 256)
        val a = PrecipitationOverlayService.overlayKey(MapRegion(38.00, -97.00, 4.0, 6.0), size)
        val b = PrecipitationOverlayService.overlayKey(MapRegion(38.05, -97.05, 4.0, 6.0), size)
        val c = PrecipitationOverlayService.overlayKey(MapRegion(41.00, -97.00, 4.0, 6.0), size)
        assertEquals(a, b)
        assertTrue(a != c)
    }
}
