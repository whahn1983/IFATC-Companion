package com.h3consultingpartners.ifatccompanion.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The production [HttpFetching], backed by OkHttp with a bounded on-disk cache so
 * conditional revalidation (ETag/If-None-Match, Last-Modified/If-Modified-Since) and
 * any `Cache-Control` are honoured by the loader — the Android counterpart of the
 * iOS `URLCache`-backed `URLSession`.
 *
 * [cacheDirectory] is supplied by the app layer (the app's cache dir). Passing null
 * disables the disk cache, which is what the JVM tests do.
 */
class OkHttpFetcher(
    cacheDirectory: File? = null,
    cacheDiskMb: Int = AppHttp.DEFAULT_CACHE_DISK_MB,
    private val userAgentProvider: () -> String = { AppHttp.userAgent },
    baseClient: OkHttpClient = OkHttpClient(),
) : HttpFetching {

    private val client: OkHttpClient = baseClient.newBuilder()
        .apply {
            if (cacheDirectory != null) {
                cache(Cache(cacheDirectory, cacheDiskMb.toLong() * 1024L * 1024L))
            }
        }
        .connectTimeout(AppHttp.DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(AppHttp.DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutSeconds: Long,
    ): HttpResult = execute(
        Request.Builder().url(url).get().applyHeaders(headers).build(),
        timeoutSeconds,
    )

    override suspend fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String>,
        timeoutSeconds: Long,
    ): HttpResult = execute(
        Request.Builder()
            .url(url)
            .post(body.toRequestBody(contentType.toMediaType()))
            .applyHeaders(headers)
            .build(),
        timeoutSeconds,
    )

    private fun Request.Builder.applyHeaders(headers: Map<String, String>): Request.Builder {
        header("User-Agent", userAgentProvider())
        headers.forEach { (name, value) -> header(name, value) }
        return this
    }

    private suspend fun execute(request: Request, timeoutSeconds: Long): HttpResult =
        withContext(Dispatchers.IO) {
            val call = client.newBuilder()
                .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build()
                .newCall(request)
            try {
                call.execute().use { response ->
                    HttpResult.Success(
                        HttpResponse(
                            status = response.code,
                            body = response.body?.bytes() ?: ByteArray(0),
                            headers = response.headers.toMultimap()
                                .mapValues { it.value.firstOrNull().orEmpty() },
                            fromCache = response.networkResponse == null &&
                                response.cacheResponse != null,
                        ),
                    )
                }
            } catch (io: IOException) {
                HttpResult.Failure(io.message ?: io::class.simpleName ?: "network error", io)
            } catch (illegal: IllegalStateException) {
                HttpResult.Failure(illegal.message ?: "request failed", illegal)
            }
        }
}
