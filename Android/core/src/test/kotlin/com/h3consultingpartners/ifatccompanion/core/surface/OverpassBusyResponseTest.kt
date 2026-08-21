package com.h3consultingpartners.ifatccompanion.core.surface

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.net.HttpFetching
import com.h3consultingpartners.ifatccompanion.core.net.HttpResponse
import com.h3consultingpartners.ifatccompanion.core.net.HttpResult
import com.h3consultingpartners.ifatccompanion.core.platform.InMemoryFileStore
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Overpass reports overload **with HTTP 200**: the body is its own HTML error page
 * ("the server is probably too busy…", "runtime error: Query timed out…") rather than the
 * JSON extract. Both large fields in one recent sample (KATL, EHAM) answered exactly that
 * way.
 *
 * That page fails JSON decoding, and the fetch used to fall through to the "empty extract"
 * path on the strength of the 200 alone — telling the pilot there are *no airport surface
 * features for this area*, which sends them hunting a data problem at their airport when a
 * shared public server was simply busy.
 *
 * Ported from `IFATCCompanionTests/OverpassBusyResponseTests.swift`.
 */
class OverpassBusyResponseTest {

    private val ref = Coordinate(33.6407, -84.4277)

    // MARK: - Recognizing the page

    @Test
    fun aBusyServerPageIsRecognizedAndNamed() {
        val page = OverpassErrorPage.detect(busyPage.toByteArray(Charsets.UTF_8))
        assertEquals("the server is too busy", page?.reason)
        assertTrue(page?.summary?.contains("too busy") ?: false, "the log line carries the page text")
        assertFalse(page?.summary?.contains("<") ?: true, "with its markup stripped")
    }

    @Test
    fun aQueryTimeoutPageIsRecognizedSeparately() {
        assertEquals(
            "the query outran the server's time budget",
            OverpassErrorPage.detect(timedOutPage.toByteArray(Charsets.UTF_8))?.reason,
        )
    }

    @Test
    fun anUnclassifiablePageIsStillAnErrorPage() {
        val page = OverpassErrorPage.detect("<html><body>Bad Gateway</body></html>".toByteArray(Charsets.UTF_8))
        assertNotNull(page, "any body that isn't JSON is the server talking, not airport data")
        assertNull(page.reason, "but nothing worth naming to the pilot")
    }

    @Test
    fun aRealExtractIsNotMistakenForAnErrorPage() {
        assertNull(OverpassErrorPage.detect("\n  {\"elements\":[]}".toByteArray(Charsets.UTF_8)))
        assertNull(OverpassErrorPage.detect("[]".toByteArray(Charsets.UTF_8)))
    }

    // MARK: - What the pilot is told

    @Test
    fun aBusyServerIsReportedAsBusyAndNotAsAnEmptyAirport() = runTest {
        val provider = makeProvider(serving = busyPage.toByteArray(Charsets.UTF_8))
        val error = readError(provider, "KATL")
        if (error !is AirportSurfaceProvider.SurfaceError.ServerBusy) {
            fail("a busy Overpass server must not be reported as an empty extract")
        }
        assertEquals("the server is too busy", error.reason)
        val message = AirportSurfaceProvider.SurfaceError.ServerBusy(error.reason).message
        assertTrue(message.contains("Overpass"), "the message names the server, not the airport")
        assertFalse(message.contains("no airport surface features"))
    }

    @Test
    fun anAirportThatGenuinelyHasNoFeaturesIsStillReportedAsEmpty() = runTest {
        val provider = makeProvider(serving = "{\"version\":0.6,\"elements\":[]}".toByteArray(Charsets.UTF_8))
        if (readError(provider, "KATL") !is AirportSurfaceProvider.SurfaceError.EmptyExtract) {
            fail("a real empty answer from a working server is still an empty extract")
        }
    }

    @Test
    fun aRateLimitedRequestIsReportedAsBusyToo() = runTest {
        val provider = makeProvider(serving = "Too Many Requests".toByteArray(Charsets.UTF_8), status = 429)
        if (readError(provider, "KATL") !is AirportSurfaceProvider.SurfaceError.ServerBusy) {
            fail("HTTP 429 is the same \"we are busy\" answer, said with a status code")
        }
    }

    // MARK: - Harness

    /**
     * Serves one canned response to every request, so the provider's own behavior — not a
     * public Overpass server — is what the tests above measure.
     */
    private class StubOverpassHttp(val body: ByteArray, val status: Int) : HttpFetching {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSeconds: Long) =
            respond()

        override suspend fun post(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String>,
            timeoutSeconds: Long,
        ): HttpResult = respond()

        private fun respond(): HttpResult = HttpResult.Success(
            HttpResponse(
                status = status,
                body = body,
                headers = mapOf("Content-Type" to "text/html"),
            ),
        )
    }

    private fun makeProvider(serving: ByteArray, status: Int = 200): AirportSurfaceProvider =
        AirportSurfaceProvider(
            http = StubOverpassHttp(serving, status),
            cache = AirportSurfaceCache(InMemoryFileStore(), namespace = "test-overpass-${UUID.randomUUID()}"),
            endpoints = listOf("https://overpass.test/api/interpreter"),
        )

    private suspend fun readError(
        provider: AirportSurfaceProvider,
        icao: String,
    ): AirportSurfaceProvider.SurfaceError? = try {
        provider.surface(icao = icao, reference = ref)
        null
    } catch (error: AirportSurfaceProvider.SurfaceError) {
        error
    }

    companion object {
        /** The shape of the page overpass-api.de serves when it is shedding load. */
        private val busyPage = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <html><head><title>OSM3S Response</title></head><body>
            <p>The data included in this document is from www.openstreetmap.org.</p>
            <p><strong style="color:#FF0000">Error</strong>: runtime error: open64: 0 Success /osm3s_v0.7.57_osm_base
            Dispatcher_Client::request_read_and_idx::rate_limited. The server is probably too busy to handle your request.</p>
            </body></html>
        """.trimIndent()

        private val timedOutPage = """
            <html><body><p><strong style="color:#FF0000">Error</strong>: runtime error:
            Query timed out in "query" at line 3 after 90 seconds.</p></body></html>
        """.trimIndent()
    }
}
