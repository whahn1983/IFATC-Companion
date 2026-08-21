package com.h3consultingpartners.ifatccompanion.core.atis

import com.h3consultingpartners.ifatccompanion.core.config.AppConfig
import com.h3consultingpartners.ifatccompanion.core.net.AppHttp
import com.h3consultingpartners.ifatccompanion.core.net.HttpFetching
import com.h3consultingpartners.ifatccompanion.core.net.HttpResult
import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.math.max

/** Where the ATIS client publishes its last endpoint status line for Diagnostics. */
fun interface ATISEndpointStatusSink {
    fun onEndpointStatus(status: String)
}

/**
 * Fetches real-world FAA D-ATIS from the free, public, keyless `datis.clowd.io`
 * endpoint. A **well-behaved direct-to-public-service client**, mirroring
 * `AviationWeatherService`: the app has no backend, so every device calls the service
 * itself.
 *  - a short in-memory **TTL cache** (2 min) fronts the network so the periodic
 *    availability checks and the telemetry-driven range checks don't re-fetch within a
 *    product update window; tuning ATIS passes `forceRefresh` to always pull the latest;
 *  - the shared client **revalidates conditionally** (ETag / Last-Modified) and carries
 *    a **descriptive User-Agent with contact info**;
 *  - concurrent identical requests are **coalesced** into one fetch;
 *  - on 429/5xx/timeout it **backs off exponentially** and **serves the last good
 *    cached data** rather than failing hard;
 *  - a **404 (or other 4xx)** means the field simply has no D-ATIS: it is cached as a
 *    null miss so the app hides the feature without hammering the endpoint.
 *
 * A successful fetch can legitimately return null (airport has no D-ATIS). The method
 * only *throws* on a transient network/server failure with no cached fallback.
 *
 * Ported from `IFATCCompanion/ATIS/ATISService.swift`. The Swift `actor` becomes a
 * class guarded by a [Mutex]; the `Task<AirportATIS?, Error>` in-flight map becomes a
 * [CompletableDeferred] per ICAO, completed by whichever caller owns the fetch.
 */
class ATISService(
    private val http: HttpFetching,
    baseUrl: String = AppConfig.Endpoints.DATIS_BASE,
    private val clock: Clock = Clock.system,
    private var diagnostics: DiagnosticsSink? = null,
    private var endpointStatus: ATISEndpointStatusSink? = null,
) {

    /** The errors the service surfaces, with the iOS `errorDescription` strings verbatim. */
    sealed class ATISError(override val message: String) : Exception(message) {
        object BadURL : ATISError("Invalid ATIS endpoint URL.")
        data class Http(val code: Int) : ATISError("ATIS server returned HTTP $code.")
        object Throttled : ATISError("ATIS requests are backing off after repeated errors.")
    }

    /**
     * A cached report. [atis] is deliberately nullable: a cached *miss* (the field
     * publishes no D-ATIS) is as much a result as a hit, and caching it is what stops
     * the app re-asking a field that will never answer.
     */
    private class CacheEntry(val atis: AirportATIS?, val timestampMillis: Long)

    private val lock = Mutex()
    private val cache = mutableMapOf<String, CacheEntry>()
    private val inFlight = mutableMapOf<String, CompletableDeferred<AirportATIS?>>()

    private var failureCount = 0
    private var nextRetryAtMillis: Long? = null

    private var baseUrl: String = baseUrl

    fun configure(
        baseUrl: String? = null,
        diagnostics: DiagnosticsSink?,
        endpointStatus: ATISEndpointStatusSink? = null,
    ) {
        if (!baseUrl.isNullOrEmpty()) this.baseUrl = baseUrl
        this.diagnostics = diagnostics
        this.endpointStatus = endpointStatus
    }

    // region Fetch

    /**
     * Fetch the current ATIS for an airport. Returns null when the field has no
     * published D-ATIS (a normal condition — the feature then quietly disappears).
     * Throws only on a network/transient failure with no cached fallback.
     */
    suspend fun atis(icao: String, forceRefresh: Boolean = false): AirportATIS? {
        val id = icao.uppercase().trim()
        if (id.length < 3 || !id.all { it.isLetter() || it.isDigit() }) return null
        val key = id

        val slot: Pair<CompletableDeferred<AirportATIS?>, Boolean> = lock.withLock {
            val cached = cache[key]
            // Fresh within the TTL → no network (unless the caller forces a pull, e.g. tune).
            if (!forceRefresh && cached != null &&
                clock.nowMillis() - cached.timestampMillis < TTL_MILLIS
            ) {
                return cached.atis
            }
            // Backing off after repeated failures → serve stale if we have it, else fail.
            val retry = nextRetryAtMillis
            if (!forceRefresh && retry != null && clock.nowMillis() < retry) {
                if (cached != null) return cached.atis
                throw ATISError.Throttled
            }
            // Coalesce concurrent identical requests into a single fetch.
            val existing = inFlight[key]
            if (existing != null) {
                existing to false
            } else {
                val fresh = CompletableDeferred<AirportATIS?>()
                inFlight[key] = fresh
                fresh to true
            }
        }
        val owned = slot.first
        val isOwner = slot.second

        if (isOwner) {
            try {
                owned.complete(performFetch(id = id, key = key))
            } catch (cancellation: CancellationException) {
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
     * Await a (possibly shared) fetch; on failure prefer stale cached data over the
     * error. Only the fetch's owner clears the in-flight slot.
     */
    private suspend fun joinOrStale(
        task: CompletableDeferred<AirportATIS?>,
        key: String,
        isOwner: Boolean,
    ): AirportATIS? {
        try {
            return task.await()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val stale = lock.withLock { cache[key] } ?: throw error
            return stale.atis
        } finally {
            if (isOwner) lock.withLock { inFlight.remove(key) }
        }
    }

    private suspend fun performFetch(id: String, key: String): AirportATIS? {
        val base = baseUrl.trimEnd('/')
        if (base.isEmpty()) throw ATISError.BadURL
        val url = "$base/$id"
        diagnostics?.log(DiagnosticCategory.ATIS, message = "ATIS GET $id")

        val headers = mapOf(
            "User-Agent" to AppHttp.userAgent,
            "Accept" to "application/json",
            // The OkHttp equivalent of iOS's `.reloadRevalidatingCacheData`: always ask
            // the origin, but let it answer 304 from ETag / Last-Modified.
            "Cache-Control" to "max-age=0",
        )

        val result = http.get(url, headers = headers, timeoutSeconds = TIMEOUT_SECONDS)
        val response = when (result) {
            is HttpResult.Failure -> {
                registerFailure(retryAfterSeconds = null)
                throw IOException(result.message, result.cause)
            }
            is HttpResult.Success -> result.response
        }

        endpointStatus?.onEndpointStatus("HTTP ${response.status} — $id")

        if (AppHttp.isRetryableStatus(response.status)) {
            registerFailure(AppHttp.parseRetryAfter(response.header("Retry-After"), clock.nowMillis()))
            throw ATISError.Http(response.status)
        }
        // A 4xx (typically 404) means this field has no D-ATIS. Cache the miss so we
        // hide the feature without re-hammering the endpoint.
        if (response.status in 400..499) {
            lock.withLock {
                cache[key] = CacheEntry(null, clock.nowMillis())
                clearBackoffLocked()
            }
            return null
        }
        if (!response.isSuccess) throw ATISError.Http(response.status)

        val atis = ATISParser.parse(response.body, airport = id, nowMillis = clock.nowMillis())
        // A 200 that yields no parseable ATIS for a field we *did* have one for is almost
        // always a momentary feed hiccup, not the field losing D-ATIS. Keep serving the
        // last good report and leave its cache entry (and timestamp) intact, so we retry
        // on the normal cadence instead of latching "no ATIS" for the whole TTL. (A
        // genuine "no D-ATIS" arrives as a 404, cached above.)
        return lock.withLock {
            val prior = cache[key]
            if (atis == null && prior?.atis != null) {
                clearBackoffLocked()
                return@withLock prior.atis
            }
            cache[key] = CacheEntry(atis, clock.nowMillis())
            clearBackoffLocked()
            atis
        }
    }

    // endregion

    // region Backoff

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

    // endregion

    suspend fun clearCache() {
        lock.withLock { cache.clear() }
    }

    companion object {
        /** 2 minutes, matching the iOS `ttl`. */
        const val TTL_MILLIS = 120_000L
        const val TIMEOUT_SECONDS = 12L

        /** iOS: `AppHTTP.backoffDelay(failureCount:base:cap:)` with base 15, cap 600. */
        const val BACKOFF_BASE_SECONDS = 15.0
        const val BACKOFF_CAP_SECONDS = 600.0
    }
}
