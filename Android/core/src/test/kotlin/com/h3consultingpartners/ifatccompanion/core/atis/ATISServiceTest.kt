package com.h3consultingpartners.ifatccompanion.core.atis

import com.h3consultingpartners.ifatccompanion.core.net.HttpFetching
import com.h3consultingpartners.ifatccompanion.core.net.HttpResponse
import com.h3consultingpartners.ifatccompanion.core.net.HttpResult
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The D-ATIS client is the app's second direct-to-public-service client, and the rules
 * it has to keep are the ones a service operator cares about: don't re-ask a field that
 * answered 404, coalesce duplicate requests, back off after failures, and serve stale
 * data rather than failing hard.
 */
class ATISServiceTest {

    private class FakeHttp(var responder: suspend (String) -> HttpResult) : HttpFetching {
        val urls = mutableListOf<String>()
        var headers: Map<String, String> = emptyMap()
        var timeoutSeconds: Long = -1

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            timeoutSeconds: Long,
        ): HttpResult {
            urls += url
            this.headers = headers
            this.timeoutSeconds = timeoutSeconds
            return responder(url)
        }

        override suspend fun post(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String>,
            timeoutSeconds: Long,
        ): HttpResult = error("the ATIS client never POSTs")
    }

    private fun ok(body: String, status: Int = 200, headers: Map<String, String> = emptyMap()) =
        HttpResult.Success(HttpResponse(status, body.toByteArray(Charsets.UTF_8), headers))

    private val seattle =
        """[{"airport":"KSEA","type":"combined","code":"S","datis":"KSEA ATIS INFO S 2153Z."}]"""

    @Test
    fun theEndpointUrlIsTheIcaoAppendedToTheBase() = runTest {
        val http = FakeHttp { ok(seattle) }
        val service = ATISService(http, baseUrl = "https://datis.clowd.io/api", clock = MutableClock(0))
        service.atis("ksea")
        assertEquals(listOf("https://datis.clowd.io/api/KSEA"), http.urls)
        assertEquals(ATISService.TIMEOUT_SECONDS, http.timeoutSeconds)
        assertTrue(http.headers.getValue("User-Agent").startsWith("IFATCCompanion/"))
        assertEquals("application/json", http.headers["Accept"])
    }

    /** Anything that isn't a plausible ICAO never reaches the network. */
    @Test
    fun anImplausibleIdentifierIsNeverRequested() = runTest {
        val http = FakeHttp { ok(seattle) }
        val service = ATISService(http, clock = MutableClock(0))
        assertNull(service.atis("K"))
        assertNull(service.atis("K SEA"))
        assertTrue(http.urls.isEmpty())
    }

    @Test
    fun aFreshResultIsServedFromTheCacheWithinTheTtl() = runTest {
        val clock = MutableClock(0)
        val http = FakeHttp { ok(seattle) }
        val service = ATISService(http, clock = clock)

        assertNotNull(service.atis("KSEA"))
        clock.advance(ATISService.TTL_MILLIS - 1)
        assertNotNull(service.atis("KSEA"))
        assertEquals(1, http.urls.size)

        clock.advance(2)
        assertNotNull(service.atis("KSEA"))
        assertEquals(2, http.urls.size)
    }

    /** Tuning ATIS always pulls the latest, TTL or not. */
    @Test
    fun forceRefreshBypassesTheCache() = runTest {
        val http = FakeHttp { ok(seattle) }
        val service = ATISService(http, clock = MutableClock(0))
        service.atis("KSEA")
        service.atis("KSEA", forceRefresh = true)
        assertEquals(2, http.urls.size)
    }

    /**
     * A 404 means the field publishes no D-ATIS. That miss is cached, so the periodic
     * availability check doesn't hammer the endpoint for a field that will never answer.
     */
    @Test
    fun aMissingFieldIsCachedAsAMiss() = runTest {
        val http = FakeHttp { ok("""{"error":"not found"}""", status = 404) }
        val service = ATISService(http, clock = MutableClock(0))

        assertNull(service.atis("KXYZ"))
        assertNull(service.atis("KXYZ"))
        assertEquals(1, http.urls.size)
    }

    /**
     * A 200 that parses to nothing, for a field we already had an ATIS for, is a feed
     * hiccup rather than the field losing D-ATIS: keep serving the last good report and
     * leave its timestamp alone so the next call retries on the normal cadence.
     */
    @Test
    fun aMomentaryEmptyResponseKeepsTheLastGoodReport() = runTest {
        val clock = MutableClock(0)
        var body = seattle
        val http = FakeHttp { ok(body) }
        val service = ATISService(http, clock = clock)

        val first = service.atis("KSEA")
        assertEquals("S", first?.letter(arrival = true))

        body = "[]"
        val second = service.atis("KSEA", forceRefresh = true)
        assertEquals("S", second?.letter(arrival = true))

        // The cache entry kept its original timestamp, so the TTL has not been extended.
        clock.advance(ATISService.TTL_MILLIS + 1)
        body = seattle
        service.atis("KSEA")
        assertEquals(3, http.urls.size)
    }

    /** A retryable status backs off; the next call is served stale rather than failing. */
    @Test
    fun aRetryableFailureBacksOffAndServesStale() = runTest {
        val clock = MutableClock(0)
        var status = 200
        val http = FakeHttp { ok(seattle, status = status) }
        val service = ATISService(http, clock = clock)

        assertNotNull(service.atis("KSEA"))

        status = 503
        clock.advance(ATISService.TTL_MILLIS + 1)
        // The failed fetch falls back to the cached report rather than throwing.
        assertEquals("S", service.atis("KSEA")?.letter(arrival = true))

        val requestsSoFar = http.urls.size
        // Still inside the backoff window → served from cache, no new request.
        service.atis("KSEA")
        assertEquals(requestsSoFar, http.urls.size)
    }

    /** With nothing cached to fall back on, a backed-off request says so. */
    @Test
    fun backingOffWithNoCacheThrows() = runTest {
        val clock = MutableClock(0)
        val http = FakeHttp { ok("", status = 503) }
        val service = ATISService(http, clock = clock)

        assertFailsWith<ATISService.ATISError.Http> { service.atis("KSEA") }
        assertFailsWith<ATISService.ATISError> { service.atis("KSEA") }
        assertEquals(1, http.urls.size)
    }

    /** A transport failure with nothing cached surfaces, rather than inventing an ATIS. */
    @Test
    fun aTransportFailureWithNoCacheSurfaces() = runTest {
        val http = FakeHttp { HttpResult.Failure("offline") }
        val service = ATISService(http, clock = MutableClock(0))
        assertFailsWith<Throwable> { service.atis("KSEA") }
    }

    @Test
    fun theEndpointStatusIsPublishedForDiagnostics() = runTest {
        var status: String? = null
        val http = FakeHttp { ok(seattle) }
        val service = ATISService(http, clock = MutableClock(0))
        service.configure(diagnostics = null, endpointStatus = { status = it })
        service.atis("KSEA")
        assertEquals("HTTP 200 — KSEA", status)
    }

    @Test
    fun clearingTheCacheForcesTheNextFetch() = runTest {
        val http = FakeHttp { ok(seattle) }
        val service = ATISService(http, clock = MutableClock(0))
        service.atis("KSEA")
        service.clearCache()
        service.atis("KSEA")
        assertEquals(2, http.urls.size)
    }
}
