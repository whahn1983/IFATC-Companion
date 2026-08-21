package com.h3consultingpartners.ifatccompanion.core.surface

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
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
import kotlin.math.max

/**
 * Fetches, normalizes, and caches airport surfaces from OpenStreetMap via a public
 * Overpass endpoint. A well-behaved direct-to-public-service client, mirroring
 * `AviationWeatherService`:
 *  - requests only a **small airport-specific bounding box** (never a region/planet);
 *  - **caches** successful extracts on disk with a long (75-day) refresh interval, so
 *    there is no network activity during taxi once an airport is loaded;
 *  - **coalesces** concurrent identical requests and never runs parallel queries for
 *    the same airport;
 *  - a descriptive **User-Agent** identifying IFATC Companion / H3 Consulting Partners;
 *  - **fails over** across the configured public endpoints and **backs off** politely
 *    on 429/5xx, serving stale cached data rather than hammering a shared server;
 *  - lets the user **manually refresh** (`forceRefresh`).
 *
 * Free access to OSM data does not guarantee unlimited access to any particular public
 * Overpass server — hence the failover, backoff, dedup, and stale-serve behavior.
 *
 * Ported from `IFATCCompanion/AirportSurface/AirportSurfaceProvider.swift`. The Swift
 * `actor` becomes a class guarded by a [Mutex]; the per-airport `Task` used for request
 * coalescing becomes a [CompletableDeferred] per ICAO, completed by whichever caller
 * owns the fetch. The one behavioural difference from an unstructured Swift `Task`:
 * cancelling the owning coroutine cancels the joiners too, rather than letting an
 * orphaned fetch run on.
 */
class AirportSurfaceProvider(
    private val http: HttpFetching,
    private val cache: AirportSurfaceCache,
    private val endpoints: List<String> = OSMSurface.OVERPASS_ENDPOINTS,
    private val clock: Clock = Clock.system,
    private var diagnostics: DiagnosticsSink? = null,
) {

    /** The errors the provider surfaces, with the iOS `errorDescription` strings verbatim. */
    sealed class SurfaceError(override val message: String) : Exception(message) {
        object BadURL : SurfaceError("Invalid Overpass endpoint URL.")

        data class Http(val code: Int) : SurfaceError("Overpass returned HTTP $code.")

        object Throttled :
            SurfaceError("Overpass requests are backing off after repeated errors.")

        object EmptyExtract :
            SurfaceError("OpenStreetMap returned no airport surface features for this area.")

        /**
         * Overpass answered `200 OK` with one of its own error pages — "too busy", rate
         * limited, query timed out — instead of the extract. Distinct from [EmptyExtract]
         * on purpose: one says the server could not answer, the other says the airport
         * has nothing mapped, and telling a pilot the second when the first is true sends
         * them hunting the wrong problem.
         */
        data class ServerBusy(val reason: String?) : SurfaceError(describe(reason)) {
            companion object {
                private fun describe(reason: String?): String {
                    val detail = if (reason != null) " — $reason." else "."
                    return "The OpenStreetMap Overpass servers could not answer right now" +
                        "$detail Airport data will be retried automatically."
                }
            }
        }

        object Decoding : SurfaceError("Could not decode the Overpass response.")

        object Unreachable :
            SurfaceError("The airport surface data service is temporarily unavailable.")
    }

    /** What Settings shows for the cache: which airports are held, and how much space. */
    data class CacheInfo(val icaos: List<String>, val bytes: Int)

    private val lock = Mutex()

    /** In-memory hot cache so repeated same-session reads never touch disk/network. */
    private val memory = mutableMapOf<String, AirportSurfaceModel>()
    private val inFlight = mutableMapOf<String, CompletableDeferred<AirportSurfaceModel>>()

    /**
     * Backoff is tracked **per airport**. It used to be one counter for the whole provider,
     * which meant one field failing all its endpoints denied every *other* uncached airport
     * for the next 60–900 s — so a slow monster on one end of the flight could starve the
     * other end of its data entirely.
     */
    private val failureCounts = mutableMapOf<String, Int>()
    private val nextRetryAt = mutableMapOf<String, Long>()

    @Volatile
    var lastErrorMessage: String? = null
        private set

    fun configure(diagnostics: DiagnosticsSink?) {
        this.diagnostics = diagnostics
    }

    // MARK: - Cache-only access (no network)

    /** The best cached surface for an airport (memory then disk), without any network. */
    suspend fun cachedSurface(icao: String): AirportSurfaceModel? =
        lock.withLock { cachedSurfaceLocked(icao.uppercase()) }

    private fun cachedSurfaceLocked(key: String): AirportSurfaceModel? {
        memory[key]?.let { return it }
        val disk = cache.load(key) ?: return null
        memory[key] = disk
        return disk
    }

    suspend fun clearCache() = lock.withLock {
        memory.clear()
        cache.deleteAll()
        failureCounts.clear()
        nextRetryAt.clear()
    }

    suspend fun deleteCache(icao: String) = lock.withLock {
        val key = icao.uppercase()
        memory.remove(key)
        cache.delete(key)
        // Deleting an airport's cache is a deliberate "try again" — don't leave it serving a
        // throttled error from a failure that happened before the pilot asked for a re-fetch.
        clearBackoffLocked(key)
    }

    suspend fun cacheInfo(): CacheInfo =
        lock.withLock { CacheInfo(cache.cachedICAOs(), cache.totalSizeBytes()) }

    // MARK: - Fetch / normalize / cache

    /**
     * Get the normalized surface for an airport. Returns a cached model when fresh
     * (or when offline/backing off and a cached copy exists), otherwise fetches a new
     * airport-sized extract from Overpass, normalizes and caches it.
     */
    suspend fun surface(
        icao: String,
        reference: Coordinate,
        forceRefresh: Boolean = false,
    ): AirportSurfaceModel {
        val key = icao.uppercase().trim { it == ' ' || it == '\t' }
        if (key.length < 3) throw SurfaceError.BadURL
        if (!reference.isValid) throw SurfaceError.BadURL

        val slot: Pair<CompletableDeferred<AirportSurfaceModel>, Boolean> = lock.withLock {
            // Fresh cache (memory or disk) → no network. A cache written by an older model
            // schema (e.g. before building footprints existed) is treated as not-fresh so it
            // is re-fetched now, even though its fetch date may be well within the refresh
            // interval — otherwise a stand behind a concourse keeps routing through it.
            if (!forceRefresh) {
                val cached = cachedSurfaceLocked(key)
                if (cached != null &&
                    !cached.source.isStale(clock.nowMillis()) &&
                    !cached.source.isOutdatedSchema
                ) {
                    return cached
                }
                // Backing off for *this airport* → serve stale if we have it, else fail.
                val retry = nextRetryAt[key]
                if (retry != null && clock.nowMillis() < retry) {
                    if (cached != null) return cached
                    throw SurfaceError.Throttled
                }
            }
            // Coalesce concurrent identical requests.
            val existing = inFlight[key]
            if (existing != null) {
                existing to false
            } else {
                val fresh = CompletableDeferred<AirportSurfaceModel>()
                inFlight[key] = fresh
                fresh to true
            }
        }

        val owned = slot.first
        val isOwner = slot.second
        if (isOwner) {
            try {
                owned.complete(performFetch(icao = key, reference = reference))
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

    private suspend fun joinOrStale(
        task: CompletableDeferred<AirportSurfaceModel>,
        key: String,
        isOwner: Boolean,
    ): AirportSurfaceModel {
        try {
            return task.await()
        } catch (error: Throwable) {
            val cached = lock.withLock { cachedSurfaceLocked(key) }
            if (cached != null) return cached
            throw error
        } finally {
            if (isOwner) lock.withLock { inFlight.remove(key) }
        }
    }

    private suspend fun performFetch(icao: String, reference: Coordinate): AirportSurfaceModel {
        val query = OverpassQuery(icao = icao, center = reference)
        val body = query.httpBody
        diagnostics?.log(
            DiagnosticCategory.SURFACE,
            message = "OSM Overpass GET $icao bbox ${query.boundingBox.overpassClause}",
        )

        var lastStatus: Int? = null
        // What each endpoint actually said, kept apart so the failure reported at the end is
        // the true one. An empty extract is a real answer from a working server; an Overpass
        // error page served with HTTP 200 is not.
        var sawEmptyExtract = false
        var sawUndecodableBody = false
        var sawRateLimit = false
        var errorPage: OverpassErrorPage? = null

        for (endpoint in endpoints) {
            if (endpoint.isEmpty()) continue
            try {
                val result = http.post(
                    url = endpoint,
                    body = body,
                    contentType = "application/x-www-form-urlencoded",
                    headers = mapOf(
                        "User-Agent" to OSMSurface.userAgent,
                        "Accept" to "application/json",
                    ),
                    timeoutSeconds = OSMSurface.OVERPASS_REQUEST_TIMEOUT_SECONDS,
                )
                val response = when (result) {
                    is HttpResult.Failure -> throw java.io.IOException(result.message, result.cause)
                    is HttpResult.Success -> result.response
                }
                lastStatus = response.status
                if (AppHttp.isRetryableStatus(response.status)) {
                    // Try the next endpoint before giving up. A 429 is the same "we are
                    // busy" answer the error page gives, just said with a status code.
                    if (response.status == 429) sawRateLimit = true
                    continue
                }
                if (!response.isSuccess) throw SurfaceError.Http(response.status)

                val decoded = runCatching { OverpassResponse.decode(response.body) }.getOrNull()
                if (decoded == null) {
                    // Overpass reports overload as an HTML page served with HTTP 200, so a
                    // body that isn't the JSON extract is the *server* answering, never an
                    // airport with nothing mapped.
                    val page = OverpassErrorPage.detect(response.body)
                    if (page != null) {
                        if (errorPage == null) errorPage = page
                        diagnostics?.log(
                            DiagnosticCategory.SURFACE,
                            message = "OSM $icao: $endpoint returned an Overpass error page — ${page.summary}",
                        )
                        throw SurfaceError.ServerBusy(page.reason)
                    }
                    sawUndecodableBody = true
                    throw SurfaceError.Decoding
                }
                if (decoded.elements.isEmpty()) {
                    // No aeroway features here; a real "no data" answer — try the next
                    // endpoint in case it's a transient partial, else surface empty.
                    sawEmptyExtract = true
                    lastStatus = 200
                    continue
                }
                val model = OSMSurfaceNormalizer.normalize(
                    response = decoded,
                    icao = icao,
                    reference = reference,
                    endpoint = endpoint,
                    boundingBox = query.boundingBox,
                    fetchDateMillis = clock.nowMillis(),
                )
                lock.withLock {
                    memory[icao] = model
                    cache.save(model)
                    clearBackoffLocked(icao)
                }
                lastErrorMessage = null
                diagnostics?.log(
                    DiagnosticCategory.SURFACE,
                    message = "OSM $icao: ${decoded.elements.size} elements → " +
                        "${model.runways.size} rwy, ${model.taxiways.size} twy, " +
                        "${model.confidence.title} confidence",
                )
                return model
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                lastErrorMessage = error.message ?: error.toString()
                continue
            }
        }

        // Every endpoint failed or returned empty. Report what actually happened, most
        // specific first: a server that answered "nothing here" is the only one of these
        // that is really about the airport.
        registerFailure(icao = icao, retryAfterSeconds = null)
        if (sawEmptyExtract) {
            diagnostics?.log(
                DiagnosticCategory.SURFACE,
                message = "OSM $icao: no airport surface features returned",
            )
            throw SurfaceError.EmptyExtract
        }
        errorPage?.let { page ->
            diagnostics?.log(
                DiagnosticCategory.SURFACE,
                message = "OSM $icao: every Overpass endpoint answered with an error page " +
                    "(${page.reason ?: "unclassified"})",
            )
            throw SurfaceError.ServerBusy(page.reason)
        }
        if (sawUndecodableBody) {
            diagnostics?.log(
                DiagnosticCategory.SURFACE,
                message = "OSM $icao: Overpass response could not be decoded",
            )
            throw SurfaceError.Decoding
        }
        if (sawRateLimit) {
            diagnostics?.log(
                DiagnosticCategory.SURFACE,
                message = "OSM $icao: every Overpass endpoint rate-limited the request (HTTP 429)",
            )
            throw SurfaceError.ServerBusy("the request rate limit is in force")
        }
        diagnostics?.log(
            DiagnosticCategory.SURFACE,
            message = "OSM $icao: all Overpass endpoints unavailable " +
                "(last HTTP ${lastStatus?.toString() ?: "—"})",
        )
        throw SurfaceError.Unreachable
    }

    // MARK: - Backoff

    /**
     * Per-airport exponential backoff: 60 s doubling to a 900 s cap, or the server's own
     * `Retry-After` when that is longer.
     */
    private suspend fun registerFailure(icao: String, retryAfterSeconds: Double?) {
        lock.withLock {
            val count = (failureCounts[icao] ?: 0) + 1
            failureCounts[icao] = count
            val backoff = AppHttp.backoffDelaySeconds(
                failureCount = count,
                base = BACKOFF_BASE_SECONDS,
                cap = BACKOFF_CAP_SECONDS,
            )
            val delay = max(backoff, retryAfterSeconds ?: 0.0)
            nextRetryAt[icao] = clock.nowMillis() + (delay * 1000).toLong()
        }
    }

    private fun clearBackoffLocked(icao: String) {
        failureCounts.remove(icao)
        nextRetryAt.remove(icao)
    }

    companion object {
        /** Backoff base/cap (seconds) this client passes to [AppHttp.backoffDelaySeconds]. */
        const val BACKOFF_BASE_SECONDS: Double = 60.0
        const val BACKOFF_CAP_SECONDS: Double = 900.0
    }
}
