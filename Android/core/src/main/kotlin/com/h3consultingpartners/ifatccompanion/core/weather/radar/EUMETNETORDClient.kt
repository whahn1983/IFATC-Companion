package com.h3consultingpartners.ifatccompanion.core.weather.radar

import com.h3consultingpartners.ifatccompanion.core.config.AppConfig
import com.h3consultingpartners.ifatccompanion.core.net.AppHttp
import com.h3consultingpartners.ifatccompanion.core.net.HttpFetching
import com.h3consultingpartners.ifatccompanion.core.net.HttpResult
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Anonymous client for the **EUMETNET Open Radar Data (ORD)** 24-hour cache — the keyless,
 * public S3 bucket that distributes the pan-European OPERA radar composite (produced by
 * **CIRRUS**, which replaced ODYSSEY in 2024). This is the live data source behind the
 * app's EUMETNET OPERA radar overlay.
 *
 * **Access is anonymous.** The bucket is public and requires no credentials, account, or
 * API key — the AWS CLI equivalent is `--no-sign-request`. Over plain HTTPS that simply
 * means we issue unsigned `GET` requests (no `Authorization` header, no query signing)
 * against the CloudFerro path-style S3 endpoint.
 *
 * The composite products are licensed **CC BY 4.0** ("Radar precipitation data: EUMETNET
 * OPERA (CC BY 4.0), CIRRUS composite"). Only the confirmed CC BY 4.0 composite products
 * are requested: maximum reflectivity (`DBZH`), instantaneous rain rate (`RATE`), and
 * 1-hour accumulation (`ACRR`).
 *
 * Object layout (observed from the ORD documentation):
 * `s3://openradar-24h/YYYY/MM/DD/OPERA/COMP/OPERA@<yyyyMMdd'T'HHmm>@0@<PROD>.<ext>`
 * e.g. `OPERA@20260604T0220@0@DBZH.h5` (ODIM HDF5) and the cloud-optimized GeoTIFF sibling
 * `OPERA@20260604T0220@0@DBZH.tif`. The app prefers the GeoTIFF over ODIM HDF5.
 *
 * The pure pieces (URL building, key/timestamp parsing, latest-object selection) are
 * unit-tested; only the two fetches touch the network, and every failure is surfaced as
 * null so the caller falls back gracefully.
 *
 * Ported from `IFATCCompanion/Weather/EUMETNETORDClient.swift`.
 */
class EUMETNETORDClient(
    private val http: HttpFetching,
    /** CloudFerro path-style S3 endpoint host (scheme + host, no trailing slash). */
    endpoint: String = AppConfig.Endpoints.EUMETNET_OPERA_S3_BASE,
    /** The public 24-hour-cache bucket name. */
    val bucket: String = "openradar-24h",
) {

    val endpoint: String = if (endpoint.endsWith("/")) endpoint.dropLast(1) else endpoint

    /** The CIRRUS/OPERA composite products, keyed by their ORD product code. */
    enum class Product(val rawValue: String) {
        MAXIMUM_REFLECTIVITY("DBZH"),
        INSTANTANEOUS_RAIN_RATE("RATE"),
        ONE_HOUR_ACCUMULATION("ACRR"),
    }

    // region Pure URL / key helpers (unit-tested)

    /** An anonymous S3 ListObjectsV2 URL for the given key prefix (path-style, keyless — no signing). */
    fun listUrl(prefix: String, maxKeys: Int = 1000): String {
        val query = queryString(
            listOf(
                "list-type" to "2",
                "prefix" to prefix,
                "max-keys" to maxKeys.toString(),
            ),
        )
        return "$endpoint/$bucket/?$query"
    }

    /** The anonymous object URL for a bucket key (path-style, keyless). */
    fun objectUrl(key: String): String {
        val escaped = key.split("/").joinToString("/") { encodePathSegment(it) }
        return "$endpoint/$bucket/$escaped"
    }

    // endregion

    // region Network (thin, defensive; well-behaved public client)

    /**
     * Outcome of an object fetch, distinguishing a retryable throttle/transient error
     * (429/503/5xx/network — back off) from a non-retryable "gone" (keep last good).
     */
    sealed interface ObjectOutcome {
        data class Success(val data: ByteArray) : ObjectOutcome {
            override fun equals(other: Any?): Boolean =
                other is Success && data.contentEquals(other.data)

            override fun hashCode(): Int = data.contentHashCode()
        }

        /** Throttled / transient — back off, honouring any `Retry-After` (seconds). */
        data class Retry(val afterSeconds: Double?) : ObjectOutcome

        /** The object is gone or refused — keep the last good data, no aggressive retry. */
        data object Unavailable : ObjectOutcome
    }

    /** The latest composite object URL plus the product timestamp embedded in its key. */
    data class LatestComposite(val url: String, val timestampMillis: Long)

    /**
     * The latest cloud-optimized GeoTIFF composite (object URL + its product timestamp) for
     * [product], scanning today's and yesterday's UTC prefixes (the 24-hour cache straddles
     * the day boundary). Returns the timestamp too so the caller can **skip the expensive
     * download when the product hasn't changed**. Null on a listing failure / no match, so
     * the caller keeps its last-good data.
     */
    suspend fun latestComposite(product: Product, nowMillis: Long): LatestComposite? {
        val prefixes = listOf(
            compositePrefix(nowMillis),
            compositePrefix(nowMillis - DAY_MILLIS),
        )
        val keys = mutableListOf<String>()
        for (prefix in prefixes) {
            val xml = fetchText(listUrl(prefix)) ?: continue
            keys.addAll(parseKeys(xml))
        }
        val key = latestGeoTIFFKey(keys, product) ?: return null
        val timestamp = compositeTimestamp(key) ?: return null
        return LatestComposite(objectUrl(key), timestamp)
    }

    /**
     * GET the composite bytes with **conditional revalidation** — the HTTP layer sends
     * `If-None-Match` / `If-Modified-Since` from the stored validators and serves the cached
     * body on `304 Not Modified`. Throttling / transient errors are reported as
     * [ObjectOutcome.Retry] (with any `Retry-After`) so the caller backs off; other 4xx are
     * [ObjectOutcome.Unavailable] (keep last good, no aggressive retry).
     */
    suspend fun fetchObject(url: String): ObjectOutcome {
        val result = http.get(
            url,
            headers = mapOf(
                "User-Agent" to AppHttp.userAgent,
                // The OkHttp equivalent of iOS's `.reloadRevalidatingCacheData`.
                "Cache-Control" to "max-age=0",
            ),
            timeoutSeconds = OBJECT_TIMEOUT_SECONDS,
        )
        val response = when (result) {
            is HttpResult.Success -> result.response
            // Network / timeout → back off.
            is HttpResult.Failure -> return ObjectOutcome.Retry(null)
        }
        if (AppHttp.isRetryableStatus(response.status)) {
            return ObjectOutcome.Retry(AppHttp.parseRetryAfter(response.header("Retry-After")))
        }
        if (!response.isSuccess) return ObjectOutcome.Unavailable
        return if (response.body.isEmpty()) ObjectOutcome.Unavailable else ObjectOutcome.Success(response.body)
    }

    /** GET a text (XML) body, decoded as UTF-8, with the app User-Agent. Null on any HTTP error. */
    private suspend fun fetchText(url: String): String? {
        val result = http.get(
            url,
            headers = mapOf("User-Agent" to AppHttp.userAgent),
            timeoutSeconds = LIST_TIMEOUT_SECONDS,
        )
        val response = (result as? HttpResult.Success)?.response ?: return null
        if (!response.isSuccess) return null
        return response.bodyText
    }

    // endregion

    companion object {
        /** iOS `request.timeoutInterval = 20` on the composite download. */
        const val OBJECT_TIMEOUT_SECONDS: Long = 20

        /** iOS `request.timeoutInterval = 15` on the bucket listing. */
        const val LIST_TIMEOUT_SECONDS: Long = 15

        private const val DAY_MILLIS: Long = 86_400_000L

        /** Cloud-optimized GeoTIFF extensions, most-preferred first. */
        val geotiffExtensions: List<String> = listOf("tif", "tiff")

        private val prefixFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.US).withZone(ZoneOffset.UTC)

        private val stampFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm", Locale.US).withZone(ZoneOffset.UTC)

        /** The `YYYY/MM/DD/OPERA/COMP/` object-key prefix for a UTC day. */
        fun compositePrefix(epochMillis: Long): String =
            prefixFormatter.format(Instant.ofEpochMilli(epochMillis)) + "/OPERA/COMP/"

        /**
         * Extract `<Key>…</Key>` object keys from an S3 ListObjectsV2 XML response. A
         * deliberately small, dependency-free scan (the response is machine-generated and
         * flat), so it stays pure and testable without an XML parser.
         */
        fun parseKeys(listXml: String): List<String> {
            val keys = mutableListOf<String>()
            val open = "<Key>"
            val close = "</Key>"
            var search = 0
            while (true) {
                val o = listXml.indexOf(open, search)
                if (o < 0) break
                val c = listXml.indexOf(close, o + open.length)
                if (c < 0) break
                keys.add(xmlUnescape(listXml.substring(o + open.length, c)))
                search = c + close.length
            }
            return keys
        }

        /** Minimal XML entity unescape for object keys (they can contain `&`). */
        fun xmlUnescape(s: String): String =
            s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")

        /**
         * Parse the composite timestamp from an object key or filename of the form
         * `…/OPERA@20260604T0220@0@DBZH.tif` → the UTC epoch millis of `20260604T0220`.
         * Returns null when the key isn't a recognizable OPERA composite name.
         */
        fun compositeTimestamp(key: String): Long? {
            val file = key.split("/").lastOrNull() ?: return null
            // Token between the first two '@' is the timestamp: OPERA@<ts>@0@PROD.ext
            val parts = file.split("@")
            if (parts.size < 2) return null
            val stamp = parts[1]   // e.g. 20260604T0220
            return runCatching {
                ZonedDateTime.parse(stamp, stampFormatter).toInstant().toEpochMilli()
            }.getOrNull()
        }

        /**
         * Whether a key names a cloud-optimized GeoTIFF composite for [product]
         * (`…@<PROD>.tif`/`.tiff`, case-insensitive on the extension).
         */
        fun isGeoTIFFComposite(key: String, product: Product): Boolean {
            val file = key.split("/").lastOrNull() ?: return false
            val lower = file.lowercase()
            if (geotiffExtensions.none { lower.endsWith(".$it") }) return false
            // Product code appears as the last '@'-delimited token before the extension.
            return lower.contains("@${product.rawValue.lowercase()}.")
        }

        /**
         * The most recent GeoTIFF composite key for [product] among [keys] (by embedded
         * timestamp), or null when none match.
         */
        fun latestGeoTIFFKey(keys: List<String>, product: Product): String? =
            keys.filter { isGeoTIFFComposite(it, product) }
                .mapNotNull { key -> compositeTimestamp(key)?.let { key to it } }
                .maxByOrNull { it.second }
                ?.first

        /**
         * Percent-encode one path segment, leaving the characters iOS's `.urlPathAllowed`
         * set leaves alone — which is why an `OPERA@…@0@DBZH.tif` key passes through
         * verbatim rather than arriving as `%40`-mangled and 404ing.
         */
        internal fun encodePathSegment(segment: String): String = buildString {
            for (byte in segment.toByteArray(Charsets.UTF_8)) {
                val ch = byte.toInt().toChar()
                val allowed = (ch in 'A'..'Z') || (ch in 'a'..'z') || (ch in '0'..'9') ||
                    ch in "-._~!$&'()*+,;=:@"
                if (allowed) append(ch) else append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
    }
}
