package com.h3consultingpartners.ifatccompanion.core.map

import com.h3consultingpartners.ifatccompanion.core.net.HttpFetching
import com.h3consultingpartners.ifatccompanion.core.net.HttpResponse
import com.h3consultingpartners.ifatccompanion.core.net.HttpResult
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.RadarBoundingBox
import com.h3consultingpartners.ifatccompanion.core.weather.radar.PixelSize
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The imagery underlay is the only part of the base map that touches the network, so these
 * cover the two things that matter: that the request is well formed, and that every way it
 * can fail is reported as a result rather than thrown — because it is the result that
 * leaves the coastlines and graticule on screen.
 *
 * The distinction between [ImageryResult.Unavailable] and [ImageryResult.Rejected] is
 * pinned deliberately. Collapsing the two is what makes a layer identifier that stopped
 * existing indistinguishable from being offline, on a device, forever.
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

    // region Every failure is a result, never an exception

    @Test
    fun `a transport failure is unavailable, not a rejection`() = runTest {
        // Offline. Nothing is wrong with the request, so nothing should be recorded and
        // the caller should try again later.
        val service = BaseImageryService(StubHttp(HttpResult.Failure("no network", null)))
        assertEquals(ImageryResult.Unavailable, service.imagery(bbox, size))
    }

    @Test
    fun `a WMS ServiceException is a rejection carrying its status`() = runTest {
        // GIBS answers a bad request with 400 and an XML ServiceException. That body is
        // not empty, so only the status distinguishes it from an image — and a 400 here
        // means a layer identifier or a parameter that will be wrong on every future
        // request too, which is exactly what must not be reported as "offline".
        val xml = "<ServiceExceptionReport/>".encodeToByteArray()
        val service = BaseImageryService(StubHttp(success(xml, status = 400)))
        val result = service.imagery(bbox, size)
        assertTrue(result is ImageryResult.Rejected, "expected a rejection, got $result")
        assertEquals(400, result.status)
        assertFalse(result.worthRetrying, "a 400 will be a 400 next time too")
    }

    @Test
    fun `a server error is a rejection that is worth retrying`() = runTest {
        val service = BaseImageryService(StubHttp(success(ByteArray(1), status = 503)))
        val result = service.imagery(bbox, size)
        assertTrue(result is ImageryResult.Rejected)
        assertTrue(result.worthRetrying, "a 503 is transient")
    }

    @Test
    fun `an empty body is a rejection rather than a zero-byte image`() = runTest {
        val service = BaseImageryService(StubHttp(success(ByteArray(0))))
        // A zero-byte "image" would reach the decoder and fail there instead, which is a
        // worse place to find out.
        val result = service.imagery(bbox, size)
        assertTrue(result is ImageryResult.Rejected, "expected a rejection, got $result")
        assertEquals("empty body", result.detail)
    }

    @Test
    fun `a degenerate size never reaches the network`() = runTest {
        val http = StubHttp(success(byteArrayOf(1, 2, 3)))
        assertEquals(ImageryResult.Unavailable, BaseImageryService(http).imagery(bbox, PixelSize(0, 0)))
        assertNull(http.lastUrl, "should not have issued a request")
    }

    @Test
    fun `a good response returns its bytes`() = runTest {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val service = BaseImageryService(StubHttp(success(bytes)))
        val result = service.imagery(bbox, size)
        assertTrue(result is ImageryResult.Image, "expected an image, got $result")
        assertEquals(bytes.toList(), result.bytes.toList())
    }

    // endregion
}
