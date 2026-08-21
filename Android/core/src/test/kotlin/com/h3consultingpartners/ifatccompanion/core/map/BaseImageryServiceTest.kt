package com.h3consultingpartners.ifatccompanion.core.map

import com.h3consultingpartners.ifatccompanion.core.net.HttpFetching
import com.h3consultingpartners.ifatccompanion.core.net.HttpResponse
import com.h3consultingpartners.ifatccompanion.core.net.HttpResult
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.RadarBoundingBox
import com.h3consultingpartners.ifatccompanion.core.weather.radar.PixelSize
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The imagery underlay is the only part of the base map that touches the network, so these
 * cover the two things that matter: that the request is well formed, and that every way it
 * can fail produces null rather than an exception — because null is what leaves the
 * coastlines and graticule on screen.
 */
class BaseImageryServiceTest {

    private val bbox = RadarBoundingBox(
        minLatitude = 30.0,
        minLongitude = -100.0,
        maxLatitude = 40.0,
        maxLongitude = -90.0,
    )
    private val size = PixelSize(width = 512, height = 512)

    private class StubHttp(private val result: HttpResult) : HttpFetching {
        var lastUrl: String? = null
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            timeoutSeconds: Long,
        ): HttpResult {
            lastUrl = url
            return result
        }

        override suspend fun post(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String>,
            timeoutSeconds: Long,
        ): HttpResult = throw UnsupportedOperationException("imagery is GET only")
    }

    private fun success(body: ByteArray, status: Int = 200) = HttpResult.Success(
        HttpResponse(status = status, body = body, headers = emptyMap()),
    )

    // region URL

    @Test
    fun `the request is a WMS GetMap in the map's own projection`() {
        val url = BaseImageryService(StubHttp(success(ByteArray(0)))).imageryUrl(bbox, size)
        assertNotNull(url)
        for (expected in listOf(
            "SERVICE=WMS",
            "VERSION=1.1.1",
            "REQUEST=GetMap",
            "SRS=EPSG%3A3857",
            "WIDTH=512",
            "HEIGHT=512",
            "FORMAT=image%2Fpng",
            "LAYERS=BlueMarble_ShadedRelief_Bathymetry",
        )) {
            assertTrue(expected in url, "missing $expected in $url")
        }
    }

    @Test
    fun `the layer is static so no TIME is sent`() {
        val url = BaseImageryService(StubHttp(success(ByteArray(0)))).imageryUrl(bbox, size)
        assertNotNull(url)
        // A TIME on a static layer is at best ignored and at worst a 400. It is also the
        // signal that someone swapped in a temporal layer without noticing.
        assertTrue("TIME=" !in url, "unexpected TIME parameter in $url")
    }

    @Test
    fun `a degenerate size builds no request at all`() {
        val service = BaseImageryService(StubHttp(success(ByteArray(0))))
        assertNull(service.imageryUrl(bbox, PixelSize(0, 512)))
        assertNull(service.imageryUrl(bbox, PixelSize(512, 0)))
        assertNull(service.imageryUrl(bbox, PixelSize(-1, -1)))
    }

    // endregion

    // region Failure is null, never an exception

    @Test
    fun `a transport failure yields null`() = runTest {
        val service = BaseImageryService(StubHttp(HttpResult.Failure("no network", null)))
        assertNull(service.imagery(bbox, size))
    }

    @Test
    fun `a WMS ServiceException yields null even though it has a body`() = runTest {
        // GIBS answers a bad request with 400 and an XML ServiceException. That body is
        // not empty, so only the status distinguishes it from an image.
        val xml = "<ServiceExceptionReport/>".encodeToByteArray()
        val service = BaseImageryService(StubHttp(success(xml, status = 400)))
        assertNull(service.imagery(bbox, size))
    }

    @Test
    fun `an empty body yields null rather than a zero-byte image`() = runTest {
        val service = BaseImageryService(StubHttp(success(ByteArray(0))))
        // A zero-byte "image" would reach the decoder and fail there instead, which is a
        // worse place to find out.
        assertNull(service.imagery(bbox, size))
    }

    @Test
    fun `a degenerate size never reaches the network`() = runTest {
        val http = StubHttp(success(byteArrayOf(1, 2, 3)))
        assertNull(BaseImageryService(http).imagery(bbox, PixelSize(0, 0)))
        assertNull(http.lastUrl, "should not have issued a request")
    }

    @Test
    fun `a good response returns its bytes`() = runTest {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val service = BaseImageryService(StubHttp(success(bytes)))
        assertEquals(bytes.toList(), service.imagery(bbox, size)?.toList())
    }

    // endregion
}
