package com.h3consultingpartners.ifatccompanion.core.weather

import com.h3consultingpartners.ifatccompanion.core.net.AppHttp
import com.h3consultingpartners.ifatccompanion.core.net.HttpFetching
import com.h3consultingpartners.ifatccompanion.core.net.HttpResponse
import com.h3consultingpartners.ifatccompanion.core.net.HttpResult
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Behaviour tests for the AWC client itself. The iOS suite has no direct equivalent —
 * `AviationWeatherService` is an `actor` exercised through `AppModel` there — so these
 * lock the parts of the port that would otherwise only be checked in flight: the exact
 * endpoint URLs and query parameters, the 5-minute TTL, request coalescing, and the
 * back-off/serve-stale rules from `docs/Weather.md`.
 */
class AviationWeatherServiceTest {

    private class FakeHttp(
        var responder: suspend (String) -> HttpResult,
    ) : HttpFetching {
        val urls = mutableListOf<String>()
        val headerSets = mutableListOf<Map<String, String>>()
        var timeoutSeconds: Long = -1

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            timeoutSeconds: Long,
        ): HttpResult {
            urls += url
            headerSets += headers
            this.timeoutSeconds = timeoutSeconds
            return responder(url)
        }

        override suspend fun post(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String>,
            timeoutSeconds: Long,
        ): HttpResult = error("the weather client never POSTs")
    }

    private fun ok(
        body: String,
        status: Int = 200,
        headers: Map<String, String> = emptyMap(),
    ) = HttpResult.Success(HttpResponse(status, body.toByteArray(Charsets.UTF_8), headers))

    private val metarBody = """[{"icaoId":"KMSP","rawOb":"KMSP 281953Z 32012KT 10SM BKN025 18/11 A3012","wdir":320,"wspd":12}]"""

    /**
     * Every aviationweather.gov path and query parameter, verbatim. A silently renamed
     * parameter returns HTTP 400 from AWC, which the client then reports as a hard
     * failure rather than backing off — so this is the guard that keeps the feed alive.
     */
    @Test
    fun endpointUrlsAndQueryParametersAreVerbatim() = runTest {
        val http = FakeHttp { ok("[]") }
        val service = AviationWeatherService(http, clock = MutableClock(0))

        service.metars(listOf("kmsp", " kden "))
        service.taf("KMSP")
        service.pireps(bbox = "29,-96,45,-92")
        service.airSigmets()

        assertEquals(
            listOf(
                "https://aviationweather.gov/api/data/metar?ids=KMSP,KDEN&format=json",
                "https://aviationweather.gov/api/data/taf?ids=KMSP&format=json",
                "https://aviationweather.gov/api/data/pirep?format=json&age=3&bbox=29,-96,45,-92",
                "https://aviationweather.gov/api/data/airsigmet?format=json",
            ),
            http.urls,
        )
        assertEquals(AviationWeatherService.TIMEOUT_SECONDS, http.timeoutSeconds)
        assertEquals(AppHttp.userAgent, http.headerSets.first()["User-Agent"])
    }

    /** An empty bbox yields no request at all — AWC answers 400 without one. */
    @Test
    fun emptyBboxMakesNoRequest() = runTest {
        val http = FakeHttp { ok("[]") }
        val service = AviationWeatherService(http, clock = MutableClock(0))
        assertTrue(service.pireps(bbox = "   ").isEmpty())
        assertTrue(service.metars(listOf("XX", "")).isEmpty())
        assertTrue(http.urls.isEmpty())
    }

    /** Repeated reads inside the 5-minute TTL never touch the network. */
    @Test
    fun cachedResponseIsReusedWithinTheTtl() = runTest {
        val clock = MutableClock(0)
        val http = FakeHttp { ok(metarBody) }
        val service = AviationWeatherService(http, clock = clock)

        assertEquals("KMSP", service.metars(listOf("KMSP")).first().icao)
        clock.advance(AviationWeatherService.TTL_SECONDS * 1000 - 1)
        service.metars(listOf("KMSP"))
        assertEquals(1, http.urls.size)

        clock.advance(1)
        service.metars(listOf("KMSP"))
        assertEquals(2, http.urls.size)
    }

    /** Concurrent identical requests are coalesced into a single fetch. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun concurrentIdenticalRequestsShareOneFetch() = runTest {
        val gate = CompletableDeferred<Unit>()
        val http = FakeHttp {
            gate.await()
            ok("[]")
        }
        val service = AviationWeatherService(http, clock = MutableClock(0))

        val first = async { service.airSigmets() }
        val second = async { service.airSigmets() }
        runCurrent()
        gate.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, http.urls.size)
    }

    /**
     * On 429/503/5xx the client backs off and serves the last good cached data rather
     * than failing hard — and while backing off it does not touch the network at all.
     */
    @Test
    fun retryableStatusBacksOffAndServesStaleData() = runTest {
        val clock = MutableClock(0)
        val http = FakeHttp { ok(metarBody) }
        val service = AviationWeatherService(http, clock = clock)
        assertEquals("KMSP", service.metars(listOf("KMSP")).first().icao)

        clock.advance(AviationWeatherService.TTL_SECONDS * 1000)
        http.responder = { ok("", status = 503) }
        // Stale-but-usable rather than an error.
        assertEquals("KMSP", service.metars(listOf("KMSP")).first().icao)
        assertEquals(2, http.urls.size)

        // Still inside the 15 s base backoff → no further network call.
        clock.advance(1000)
        assertEquals("KMSP", service.metars(listOf("KMSP")).first().icao)
        assertEquals(2, http.urls.size)
    }

    /** A non-retryable status (400) surfaces as an error and never arms the backoff. */
    @Test
    fun nonRetryableStatusDoesNotBackOff() = runTest {
        val clock = MutableClock(0)
        val http = FakeHttp { ok("", status = 400) }
        val service = AviationWeatherService(http, clock = clock)

        val error = assertFailsWith<AviationWeatherService.WeatherError.Http> {
            service.airSigmets()
        }
        assertEquals(400, error.code)
        assertEquals("Weather server returned HTTP 400.", error.message)

        // No backoff was armed, so the next call goes back out immediately.
        assertFailsWith<AviationWeatherService.WeatherError.Http> { service.airSigmets() }
        assertEquals(2, http.urls.size)
    }

    /** The endpoint status line the Weather Diagnostics panel prints, verbatim. */
    @Test
    fun endpointStatusIsReported() = runTest {
        val seen = mutableListOf<String>()
        val http = FakeHttp { ok("[]") }
        val service = AviationWeatherService(
            http,
            clock = MutableClock(0),
            endpointStatus = { seen += it },
        )
        service.airSigmets()
        assertEquals(listOf("HTTP 200 — airsigmet"), seen)
    }
}
