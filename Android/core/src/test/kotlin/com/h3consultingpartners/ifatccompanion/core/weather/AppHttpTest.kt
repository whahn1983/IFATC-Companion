package com.h3consultingpartners.ifatccompanion.core.weather

import com.h3consultingpartners.ifatccompanion.core.net.AppHttp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure HTTP-behavior helpers shared by the app's direct-to-public-service
 * clients (User-Agent, Retry-After parsing, exponential backoff, retryable status).
 *
 * Ported from `IFATCCompanionTests/AppHTTPTests.swift`. `AppHTTP` itself is already
 * ported as `core.net.AppHttp`; the test lives here with the weather client that is
 * its main consumer, since :core has no `net` test package yet.
 */
class AppHttpTest {

    @Test
    fun userAgentIsDescriptiveWithContact() {
        val ua = AppHttp.userAgent
        assertTrue(ua.startsWith("IFATCCompanion/"))
        assertTrue(ua.contains("github.com/whahn1983/IFATC-Companion"))
    }

    @Test
    fun parseRetryAfterSeconds() {
        assertEquals(120.0, AppHttp.parseRetryAfter("120"))
        assertEquals(0.0, AppHttp.parseRetryAfter("0"))
        assertEquals(30.0, AppHttp.parseRetryAfter("  30 "))
        assertNull(AppHttp.parseRetryAfter(null))
        assertNull(AppHttp.parseRetryAfter(""))
        assertNull(AppHttp.parseRetryAfter("not-a-number-or-date"))
    }

    @Test
    fun parseRetryAfterHTTPDate() {
        val nowMillis = 1_700_000_000_000L
        val fmt = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            .withZone(ZoneId.of("GMT"))
        // A date 90s in the future → ~90s delay.
        val future = fmt.format(Instant.ofEpochMilli(nowMillis + 90_000))
        assertEquals(90.0, AppHttp.parseRetryAfter(future, nowMillis) ?: -1.0, 1.0)
        // A past date clamps to 0 (never negative).
        val past = fmt.format(Instant.ofEpochMilli(nowMillis - 120_000))
        assertEquals(0.0, AppHttp.parseRetryAfter(past, nowMillis) ?: -1.0, 1.0)
    }

    @Test
    fun backoffDelayIsExponentialAndCapped() {
        assertEquals(0.0, AppHttp.backoffDelaySeconds(failureCount = 0))
        assertEquals(30.0, AppHttp.backoffDelaySeconds(failureCount = 1))
        assertEquals(60.0, AppHttp.backoffDelaySeconds(failureCount = 2))
        assertEquals(120.0, AppHttp.backoffDelaySeconds(failureCount = 3))
        // Caps out and never overflows for very large counts.
        assertEquals(900.0, AppHttp.backoffDelaySeconds(failureCount = 100))
        // Custom base/cap.
        assertEquals(15.0, AppHttp.backoffDelaySeconds(failureCount = 1, base = 15.0, cap = 600.0))
        assertEquals(240.0, AppHttp.backoffDelaySeconds(failureCount = 5, base = 15.0, cap = 600.0))
        assertEquals(600.0, AppHttp.backoffDelaySeconds(failureCount = 10, base = 15.0, cap = 600.0))
    }

    @Test
    fun retryableStatus() {
        for (code in listOf(429, 502, 503, 504)) assertTrue(AppHttp.isRetryableStatus(code))
        for (code in listOf(200, 204, 304, 400, 401, 403, 404)) {
            assertFalse(AppHttp.isRetryableStatus(code))
        }
    }
}
