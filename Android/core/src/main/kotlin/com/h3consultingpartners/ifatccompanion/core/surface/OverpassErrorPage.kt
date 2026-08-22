package com.h3consultingpartners.ifatccompanion.core.surface

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Recognizes the error pages a public Overpass endpoint serves **with HTTP 200**.
 *
 * Overpass does not report overload with a status code. When a server is loaded, rate
 * limited, or the query outruns its budget, it answers `200 OK` with an HTML page whose
 * body reads "The server is probably too busy…" or "runtime error: Query timed out…"
 * instead of the JSON extract. Both of the two big fields tried in one recent sample
 * (KATL, EHAM) came back exactly that way.
 *
 * Left undetected, that page fails JSON decoding and the fetch falls through to the
 * "empty extract" path, which tells the pilot there are *no airport surface features for
 * this area* — sending them to hunt a data problem at their airport when the truth is
 * that a shared public server was busy for a minute. This type is what tells the two
 * apart.
 *
 * Ported from `IFATCCompanion/AirportSurface/OverpassErrorPage.swift`.
 */
data class OverpassErrorPage(
    /**
     * A short, recognized reason, when the page says something classifiable. Null for an
     * error page whose text matches none of the known markers — still an error page, just
     * not one worth naming to the pilot.
     */
    val reason: String?,
    /**
     * The page's text, tags stripped and whitespace collapsed, capped for a log line.
     * Diagnostics only — never shown in the interface.
     */
    val summary: String,
) {
    companion object {
        /**
         * How much of the body to inspect. Overpass's error pages are tiny; a real extract
         * can be megabytes, and only its first non-whitespace character is needed to rule it
         * out.
         */
        private const val INSPECTED_BYTES = 8_192

        /**
         * The error page in a response body, or null when the body is (or begins as) JSON.
         *
         * Deliberately shape-based rather than marker-based: any body that does not start as
         * a JSON document is the server talking, not airport data. Call it only after JSON
         * decoding has already failed — a valid extract never reaches here.
         */
        fun detect(data: ByteArray): OverpassErrorPage? {
            val head = data.copyOfRange(0, minOf(INSPECTED_BYTES, data.size))
            val text = decodeText(head) ?: return null
            val first = text.firstOrNull { !it.isWhitespace() } ?: return null
            if (first == '{' || first == '[') return null
            val plain = plainText(text)
            if (plain.isEmpty()) return null
            return OverpassErrorPage(reason = classify(plain), summary = plain.take(240))
        }

        /**
         * UTF-8 first, ISO-8859-1 as the fallback — the same two encodings iOS tries.
         * The 8 KB window can slice a multi-byte character in half, so a strict UTF-8
         * decode is what makes the Latin-1 fallback meaningful.
         */
        private fun decodeText(bytes: ByteArray): String? {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
                .getOrElse { runCatching { String(bytes, Charsets.ISO_8859_1) }.getOrNull() }
        }

        /**
         * The phrases Overpass puts in its error pages, mapped to how the app says it. Matched
         * on the tag-stripped text, so markup between the words never hides one.
         */
        private val markers: List<Pair<String, String>> = listOf(
            "too busy" to "the server is too busy",
            "load too high" to "the server load is too high",
            "slot available after" to "the request rate limit is in force",
            "too many requests" to "the request rate limit is in force",
            "rate_limited" to "the request rate limit is in force",
            "timed out" to "the query outran the server's time budget",
            "out of memory" to "the query outran the server's memory budget",
        )

        private fun classify(plain: String): String? {
            val haystack = plain.lowercase()
            return markers.firstOrNull { haystack.contains(it.first) }?.second
        }

        /** Tag-stripped, whitespace-collapsed text of an HTML (or plain-text) error page. */
        private fun plainText(html: String): String {
            val out = StringBuilder()
            var insideTag = false
            for (character in html) {
                when (character) {
                    '<' -> { insideTag = true; out.append(' ') }   // a tag also separates words
                    '>' -> insideTag = false
                    else -> if (!insideTag) out.append(character)
                }
            }
            // Split on any Unicode whitespace and rejoin with single spaces, as Swift's
            // `split(whereSeparator: { $0.isWhitespace })` does.
            val words = ArrayList<String>()
            val word = StringBuilder()
            for (character in out) {
                if (character.isWhitespace()) {
                    if (word.isNotEmpty()) { words.add(word.toString()); word.clear() }
                } else {
                    word.append(character)
                }
            }
            if (word.isNotEmpty()) words.add(word.toString())
            return words.joinToString(" ")
        }
    }
}
