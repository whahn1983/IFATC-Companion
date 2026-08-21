package com.h3consultingpartners.ifatccompanion.core.net

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Shared HTTP conventions for the app's direct-to-public-service clients (NOAA
 * aviation weather, NOAA/NWS radar, EUMETNET OPERA ORD, Overpass, D-ATIS). The app
 * has no backend, so every device talks to these public services itself — this
 * centralizes the "well-behaved public client" bits: a descriptive User-Agent with
 * contact info, a shared revalidating HTTP cache, and pure `Retry-After` /
 * exponential-backoff math.
 *
 * Ported from `IFATCCompanion/Weather/AppHTTP.swift`. `URLSession` + `URLCache`
 * become OkHttp + its `Cache`, which honours ETag/If-None-Match and
 * Last-Modified/If-Modified-Since the same way.
 */
object AppHttp {

    /**
     * Contact/identity URL included in the User-Agent so service operators can reach
     * the project (NWS asks clients to identify themselves; a public repo is a stable,
     * non-personal contact point).
     */
    const val CONTACT_URL = "https://github.com/whahn1983/IFATC-Companion"

    /**
     * A descriptive User-Agent: app name + version + contact, e.g.
     * `IFATCCompanion/1.4 (+https://github.com/whahn1983/IFATC-Companion)`.
     *
     * The version is injected once at start-up from `BuildConfig.VERSION_NAME`
     * (iOS reads `CFBundleShortVersionString`); until then it reads "dev", matching
     * the iOS fallback.
     */
    @Volatile
    var appVersion: String = "dev"

    val userAgent: String
        get() = "IFATCCompanion/$appVersion (+$CONTACT_URL)"

    /** Default per-request timeout, matching the iOS `timeoutIntervalForRequest`. */
    const val DEFAULT_TIMEOUT_SECONDS: Long = 20

    /** Overlay-image cache sizing, matching the iOS `overlay-img-cache` session. */
    const val IMAGE_CACHE_NAME = "overlay-img-cache"
    const val IMAGE_CACHE_DISK_MB = 64

    /** Default JSON/text cache sizing, matching the iOS `app-http-cache` session. */
    const val DEFAULT_CACHE_NAME = "app-http-cache"
    const val DEFAULT_CACHE_DISK_MB = 256

    private val httpDateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)

    /**
     * Parse an HTTP `Retry-After` header value, which is either an integer number of
     * seconds or an HTTP-date. Returns the delay in **seconds** (clamped ≥ 0), or null
     * when absent/unparseable.
     */
    fun parseRetryAfter(value: String?, nowMillis: Long = System.currentTimeMillis()): Double? {
        val trimmed = value?.trim()
        if (trimmed.isNullOrEmpty()) return null
        trimmed.toDoubleOrNull()?.let { return max(0.0, it) }
        val date = runCatching { ZonedDateTime.parse(trimmed, httpDateFormatter) }.getOrNull()
            ?: return null
        return max(0.0, (date.toInstant().toEpochMilli() - nowMillis) / 1000.0)
    }

    /**
     * Exponential backoff delay for the Nth consecutive failure (1-based): capped
     * `base · 2^(failureCount−1)`. Callers add jitter / honour a larger `Retry-After`.
     */
    fun backoffDelaySeconds(
        failureCount: Int,
        base: Double = 30.0,
        cap: Double = 900.0,
    ): Double {
        if (failureCount <= 0) return 0.0
        // Cap the exponent so 2^n can't overflow before the min() clamps it.
        val exponent = min(failureCount - 1, 20)
        return min(cap, base * 2.0.pow(exponent.toDouble()))
    }

    /**
     * Whether an HTTP status code is worth retrying with backoff (throttling /
     * transient server errors), per NWS/ORD guidance.
     */
    fun isRetryableStatus(code: Int): Boolean =
        code == 429 || code == 503 || code == 502 || code == 504
}
