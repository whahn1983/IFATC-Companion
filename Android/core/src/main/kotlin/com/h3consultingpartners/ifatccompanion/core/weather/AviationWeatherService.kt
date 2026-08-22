package com.h3consultingpartners.ifatccompanion.core.weather

import com.h3consultingpartners.ifatccompanion.core.config.AppConfig
import com.h3consultingpartners.ifatccompanion.core.net.AppHttp
import com.h3consultingpartners.ifatccompanion.core.net.HttpFetching
import com.h3consultingpartners.ifatccompanion.core.net.HttpResult
import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.Locale
import kotlin.math.max

/**
 * Where the weather client publishes the last endpoint status line ("HTTP 200 — metar")
 * that the Weather Diagnostics panel shows.
 *
 * CONTRACT for the diagnostics/app agent: on iOS this is
 * `DiagnosticsStore.weatherEndpointStatus`, an `@Published var` the panel reads;
 * :core's `DiagnosticsStore` has no such field yet, so the service takes this narrow
 * sink instead of depending on the whole store.
 */
fun interface WeatherEndpointStatusSink {
    fun onEndpointStatus(status: String)
}

/**
 * Fetches aviation weather from the NOAA Aviation Weather Center public JSON Data API
 * (`aviationweather.gov/api/data`). No API keys, no account. A **well-behaved
 * direct-to-public-service client** (the app has no backend, so every device calls
 * the service itself):
 *  - an **in-memory TTL cache** (5 min) fronts the network so repeated reads, the
 *    event-driven refreshes (connect / route change / manual pull-to-refresh), and the
 *    caller's slow periodic refresh (also 5 min, aligned to this TTL) don't re-fetch
 *    within a product's update window;
 *  - the shared [HttpFetching] client **revalidates conditionally** (ETag /
 *    Last-Modified) beyond the TTL, and carries a **descriptive User-Agent with
 *    contact info**;
 *  - concurrent identical requests are **coalesced** into one fetch;
 *  - on 429/503/5xx/timeout it **backs off exponentially** (honoring `Retry-After`)
 *    and **serves the last good cached data** rather than failing hard;
 *  - non-retryable errors (e.g. HTTP 400) don't trigger backoff.
 *
 * Note: this app uses the AWC Data API, **not** `api.weather.gov`, so it makes no
 * `/points`, forecast-office, gridpoint, or station-list metadata calls to cache.
 *
 * Ported from `IFATCCompanion/Weather/AviationWeatherService.swift`. The Swift `actor`
 * becomes a class guarded by a [Mutex]; `Task<Data, Error>` in-flight coalescing
 * becomes a [CompletableDeferred] per URL, completed by whichever caller owns the
 * fetch. The one behavioural difference from an unstructured Swift `Task`: cancelling
 * the owning coroutine cancels the joiners too, rather than letting an orphaned fetch
 * run on.
 */
class AviationWeatherService(
    private val http: HttpFetching,
    baseUrl: String = AppConfig.Endpoints.AVIATION_WEATHER_BASE,
    private val clock: Clock = Clock.system,
    private var diagnostics: DiagnosticsSink? = null,
    private var endpointStatus: WeatherEndpointStatusSink? = null,
) {

    /** The errors the service surfaces, with the iOS `errorDescription` strings verbatim. */
    sealed class WeatherError(override val message: String) : Exception(message) {
        object BadURL : WeatherError("Invalid weather endpoint URL.")
        data class Http(val code: Int) : WeatherError("Weather server returned HTTP $code.")
        object NoData : WeatherError("No weather data returned.")
        object Throttled : WeatherError("Weather requests are backing off after repeated errors.")
    }

    private class CacheEntry(val data: ByteArray, val timestampMillis: Long)

    private val lock = Mutex()
    private val cache = mutableMapOf<String, CacheEntry>()

    /** In-flight fetches keyed by URL, so concurrent identical requests share one call. */
    private val inFlight = mutableMapOf<String, CompletableDeferred<ByteArray>>()

    /** Exponential-backoff state after throttling / transient failures. */
    private var failureCount = 0
    private var nextRetryAtMillis: Long? = null

    private var baseUrl: String = baseUrl

    fun configure(
        baseUrl: String,
        diagnostics: DiagnosticsSink?,
        endpointStatus: WeatherEndpointStatusSink? = null,
    ) {
        this.baseUrl = if (baseUrl.isEmpty()) this.baseUrl else baseUrl
        this.diagnostics = diagnostics
        this.endpointStatus = endpointStatus
    }

    // MARK: - Public fetches

    suspend fun metars(icaos: List<String>): List<METAR> {
        val ids = sanitized(icaos)
        if (ids.isEmpty()) return emptyList()
        val data = get(
            path = "metar",
            query = linkedMapOf("ids" to ids.joinToString(","), "format" to "json"),
        )
        return MetarParser.parseJson(data)
    }

    suspend fun taf(icao: String): TAF? {
        val id = sanitized(listOf(icao)).firstOrNull() ?: return null
        val data = get(path = "taf", query = linkedMapOf("ids" to id, "format" to "json"))
        return TafParser.parseJson(data).firstOrNull()
    }

    /**
     * Fetch PIREPs within a bounding box. The AWC `pirep` endpoint **requires** a
     * `bbox` (or a station id + radial distance) and returns HTTP 400 without one, so
     * callers must pass the route/area box; an empty box yields no request.
     * [bbox] is `minLat,minLon,maxLat,maxLon`.
     */
    suspend fun pireps(bbox: String, ageHours: Int = DEFAULT_PIREP_AGE_HOURS): List<PIREP> {
        val box = bbox.trim { it == ' ' || it == '\t' }
        if (box.isEmpty()) return emptyList()
        val data = get(
            path = "pirep",
            query = linkedMapOf("format" to "json", "age" to ageHours.toString(), "bbox" to box),
        )
        return PirepParser.parseJson(data)
    }

    suspend fun airSigmets(): List<SIGMET> {
        val data = get(path = "airsigmet", query = linkedMapOf("format" to "json"))
        return SigmetParser.parseJson(data)
    }

    suspend fun clearCache() {
        lock.withLock { cache.clear() }
    }

    // MARK: - Networking

    private suspend fun get(path: String, query: Map<String, String>): ByteArray {
        val url = buildUrl(path, query) ?: throw WeatherError.BadURL
        val key = url

        val slot: Pair<CompletableDeferred<ByteArray>, Boolean> = lock.withLock {
            // Fresh within the TTL → no network.
            val cached = cache[key]
            if (cached != null && clock.nowMillis() - cached.timestampMillis < TTL_MILLIS) {
                return cached.data
            }
            // Backing off after repeated failures → serve stale if we have it, else fail.
            val retry = nextRetryAtMillis
            if (retry != null && clock.nowMillis() < retry) {
                if (cached != null) return cached.data
                throw WeatherError.Throttled
            }
            // Coalesce concurrent identical requests into a single fetch.
            val existing = inFlight[key]
            if (existing != null) {
                existing to false
            } else {
                val fresh = CompletableDeferred<ByteArray>()
                inFlight[key] = fresh
                fresh to true
            }
        }
        val owned = slot.first
        val isOwner = slot.second

        if (isOwner) {
            try {
                owned.complete(performFetch(url = url, path = path, key = key))
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                owned.cancel(cancellation)
                lock.withLock { inFlight.remove(key) }
                throw cancellation
            } catch (error: Throwable) {
                owned.completeExceptionally(error)
            }
        }
        return joinOrStale(owned, key = key, isOwner = isOwner)
    }

    /**
     * Await a (possibly shared) fetch; on failure prefer stale cached data over
     * surfacing the error. Only the fetch's owner clears the in-flight slot.
     */
    private suspend fun joinOrStale(
        task: CompletableDeferred<ByteArray>,
        key: String,
        isOwner: Boolean,
    ): ByteArray {
        try {
            return task.await()
        } catch (error: Throwable) {
            val stale = lock.withLock { cache[key] }
            if (stale != null) return stale.data      // stale-but-usable fallback
            throw error
        } finally {
            if (isOwner) lock.withLock { inFlight.remove(key) }
        }
    }

    private suspend fun performFetch(url: String, path: String, key: String): ByteArray {
        diagnostics?.log(DiagnosticCategory.WEATHER, message = "GET $path")
        val headers = mapOf(
            "User-Agent" to AppHttp.userAgent,
            // The OkHttp equivalent of iOS's `.reloadRevalidatingCacheData`: always ask
            // the origin, but let it answer 304 from ETag / Last-Modified.
            "Cache-Control" to "max-age=0",
        )

        val result = http.get(url, headers = headers, timeoutSeconds = TIMEOUT_SECONDS)
        val response = when (result) {
            is HttpResult.Failure -> {
                // Network / timeout (non-HTTP) errors are transient → back off.
                registerFailure(retryAfterSeconds = null)
                throw IOException(result.message, result.cause)
            }
            is HttpResult.Success -> result.response
        }

        setEndpointStatus("HTTP ${response.status} — $path")
        if (AppHttp.isRetryableStatus(response.status)) {
            registerFailure(
                retryAfterSeconds = AppHttp.parseRetryAfter(
                    response.header("Retry-After"),
                    nowMillis = clock.nowMillis(),
                ),
            )
            throw WeatherError.Http(response.status)
        }
        if (!response.isSuccess) {
            // e.g. 400 — not a throttle; don't back off.
            throw WeatherError.Http(response.status)
        }
        if (response.body.isEmpty()) throw WeatherError.NoData
        lock.withLock {
            cache[key] = CacheEntry(response.body, clock.nowMillis())
            clearBackoffLocked()
        }
        return response.body
    }

    private suspend fun registerFailure(retryAfterSeconds: Double?) {
        lock.withLock {
            failureCount += 1
            val backoff = AppHttp.backoffDelaySeconds(
                failureCount = failureCount,
                base = BACKOFF_BASE_SECONDS,
                cap = BACKOFF_CAP_SECONDS,
            )
            val delay = max(backoff, retryAfterSeconds ?: 0.0)
            nextRetryAtMillis = clock.nowMillis() + (delay * 1000).toLong()
        }
    }

    private fun clearBackoffLocked() {
        failureCount = 0
        nextRetryAtMillis = null
    }

    private fun setEndpointStatus(status: String) {
        endpointStatus?.onEndpointStatus(status)
    }

    private fun sanitized(icaos: List<String>): List<String> =
        icaos.map { it.uppercase().trim { c -> c == ' ' || c == '\t' } }
            .filter { it.length >= 3 && it.all { c -> c.isLetter() || c.isDigit() } }

    /**
     * `"$baseUrl/$path?k=v&…"`. The query is built from an ordered map so the URL —
     * and therefore the cache key — is deterministic, where Swift's
     * `URLComponents.queryItems` inherits the dictionary's arbitrary order.
     */
    private fun buildUrl(path: String, query: Map<String, String>): String? {
        if (baseUrl.isEmpty()) return null
        val q = query.entries.joinToString("&") { "${it.key}=${percentEncodeQuery(it.value)}" }
        return if (q.isEmpty()) "$baseUrl/$path" else "$baseUrl/$path?$q"
    }

    companion object {
        /** In-memory cache TTL: 5 minutes, aligned with the caller's periodic refresh. */
        const val TTL_SECONDS: Long = 300
        private const val TTL_MILLIS: Long = TTL_SECONDS * 1000

        /** Per-request timeout (seconds), matching the iOS `request.timeoutInterval`. */
        const val TIMEOUT_SECONDS: Long = 12

        /** Backoff base/cap (seconds) this client passes to [AppHttp.backoffDelaySeconds]. */
        const val BACKOFF_BASE_SECONDS: Double = 15.0
        const val BACKOFF_CAP_SECONDS: Double = 600.0

        /** Default PIREP look-back window, in hours. */
        const val DEFAULT_PIREP_AGE_HOURS: Int = 3

        /**
         * The iOS session's `URLCache` sizing for this client
         * (`AppHTTP.makeCachingSession(cacheName:memoryMB:diskMB:timeout:)`). The
         * Android disk cache is configured on the shared `OkHttpFetcher`, so these are
         * kept here as the recorded values rather than applied per client.
         */
        const val CACHE_NAME = "avwx-http-cache"
        const val CACHE_MEMORY_MB = 8
        const val CACHE_DISK_MB = 32

        /**
         * `CharacterSet.urlQueryAllowed` — what `URLComponents` leaves unescaped in a
         * query value. Notably a comma stays a comma, so `ids=KMSP,KDEN` and
         * `bbox=29,-96,45,-92` go out exactly as the iOS build sends them.
         */
        private const val URL_QUERY_ALLOWED = "-._~!$&'()*+,;=:@/?"

        internal fun percentEncodeQuery(value: String): String = buildString {
            for (raw in value.toByteArray(Charsets.UTF_8)) {
                val b = raw.toInt() and 0xFF
                val c = b.toChar()
                val allowed = b < 0x80 &&
                    ((c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') ||
                        URL_QUERY_ALLOWED.contains(c))
                if (allowed) append(c) else append(String.format(Locale.US, "%%%02X", b))
            }
        }
    }
}
