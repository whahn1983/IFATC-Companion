package com.h3consultingpartners.ifatccompanion.core.net

/**
 * The network port the engine depends on. Every public-service client in :core goes
 * through this rather than touching OkHttp directly, so tests can serve canned
 * payloads (and canned failures) without a socket, and so the caching/User-Agent
 * policy lives in exactly one place.
 */
interface HttpFetching {
    /**
     * Perform a GET. Never throws for a non-2xx status — that is reported in
     * [HttpResponse.status] so callers can apply the retry/backoff rules in [AppHttp].
     * Transport failures are returned as [HttpResult.Failure].
     */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = AppHttp.DEFAULT_TIMEOUT_SECONDS,
    ): HttpResult

    /** POST a body — used only by the Overpass API, which takes its query as a form body. */
    suspend fun post(
        url: String,
        body: String,
        contentType: String = "application/x-www-form-urlencoded",
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = AppHttp.DEFAULT_TIMEOUT_SECONDS,
    ): HttpResult
}

data class HttpResponse(
    val status: Int,
    val body: ByteArray,
    val headers: Map<String, String>,
    /** True when the response was served from the local cache without a network hit. */
    val fromCache: Boolean = false,
) {
    val bodyText: String get() = body.toString(Charsets.UTF_8)
    val isSuccess: Boolean get() = status in 200..299
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    override fun equals(other: Any?): Boolean =
        other is HttpResponse && status == other.status &&
            body.contentEquals(other.body) && headers == other.headers &&
            fromCache == other.fromCache

    override fun hashCode(): Int =
        (((status * 31) + body.contentHashCode()) * 31 + headers.hashCode()) * 31 + fromCache.hashCode()
}

sealed interface HttpResult {
    data class Success(val response: HttpResponse) : HttpResult
    /** Transport-level failure: no response reached us (DNS, TLS, timeout, offline). */
    data class Failure(val message: String, val cause: Throwable? = null) : HttpResult

    val successOrNull: HttpResponse? get() = (this as? Success)?.response
}
