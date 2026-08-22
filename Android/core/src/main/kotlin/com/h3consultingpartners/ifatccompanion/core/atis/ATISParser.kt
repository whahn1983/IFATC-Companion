package com.h3consultingpartners.ifatccompanion.core.atis

import com.h3consultingpartners.ifatccompanion.core.phraseology.Phonetic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses the FAA D-ATIS JSON payload (`datis.clowd.io/api/{ICAO}`) into an
 * [AirportATIS]. Deterministic and defensive — any shape it doesn't recognize (an
 * error object, an empty array, malformed JSON) yields null, which the app treats as
 * "no ATIS for this field" and hides the feature. No data is invented.
 *
 * The feed returns a JSON array; each element is:
 * ```json
 * { "airport": "KLAX", "type": "arr" | "dep" | "combined",
 *   "code": "A", "datis": "…ADVISE YOU HAVE INFORMATION ALPHA." }
 * ```
 *
 * Ported from `IFATCCompanion/ATIS/ATISParser.swift`. Swift's `JSONDecoder` into a
 * `[DATISElement]` becomes a hand-walked [JsonArray]: the feed answers an unknown
 * field with a bare error *object* rather than an array, and a strict decode of that
 * shape would throw where the Swift `try?` merely returned nil.
 */
object ATISParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parse a D-ATIS response body. Returns null when the airport has no usable
     * D-ATIS (error object, empty array, or no non-empty text).
     */
    fun parse(data: ByteArray, airport: String, nowMillis: Long): AirportATIS? =
        parse(data.toString(Charsets.UTF_8), airport, nowMillis)

    fun parse(body: String, airport: String, nowMillis: Long): AirportATIS? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        val elements = (root as? JsonArray)?.filterIsInstance<JsonObject>() ?: return null
        if (elements.isEmpty()) return null

        val parts = mutableListOf<AirportATIS.Part>()
        for (element in elements) {
            val text = element.string("datis").orEmpty().trim()
            if (text.isEmpty()) continue
            val kind = AirportATIS.Kind.fromApiType(element.string("type").orEmpty())
            val letter = infoLetter(element.string("code"), text) ?: ""
            parts.add(AirportATIS.Part(kind = kind, letter = letter, text = text))
        }
        if (parts.isEmpty()) return null

        val icao = (elements.first().string("airport") ?: airport).uppercase().trim()
        return AirportATIS(
            airport = icao.ifEmpty { airport.uppercase() },
            parts = parts,
            fetchedAtMillis = nowMillis,
        )
    }

    // region Information code

    /** Reverse phonetic map, e.g. "ALPHA" -> "A", built from [Phonetic.letterWords]. */
    private val wordToLetter: Map<String, String> =
        Phonetic.letterWords.entries.associate { (ch, word) -> word.uppercase() to ch.toString() }

    /**
     * Resolve the ATIS information letter, preferring the feed's explicit `code` field
     * and falling back to the "…INFORMATION <letter>" phrase in the text (the D-ATIS
     * closes with "advise you have information X"). Returns an uppercase single letter,
     * or null.
     */
    fun infoLetter(code: String?, text: String): String? {
        val trimmed = code?.trim()
        if (!trimmed.isNullOrEmpty()) {
            letterFromToken(trimmed)?.let { return it }
        }
        val upper = text.uppercase()
        return letterAfterKeyword("INFORMATION", upper) ?: letterAfterKeyword("INFO", upper)
    }

    /**
     * Interpret a code token that may be a single letter ("A"), a phonetic word
     * ("ALPHA"), or a decorated form ("INFO A").
     */
    private fun letterFromToken(token: String): String? {
        val t = token.uppercase().trim()
        if (t.length == 1 && t[0].isLetter()) return t
        wordToLetter[t]?.let { return it }
        val letters = t.filter { it.isLetter() }
        if (letters.length == 1) return letters
        return wordToLetter[letters]
    }

    /**
     * The information letter following the last occurrence of [keyword] in the
     * (uppercased) text — the closing "advise you have information X" wins over any
     * earlier "…INFORMATION X" mention.
     */
    private fun letterAfterKeyword(keyword: String, upper: String): String? {
        val words = NON_LETTER_RUN.split(upper).filter { it.isNotEmpty() }
        val index = words.lastIndexOf(keyword)
        if (index < 0 || index + 1 >= words.size) return null
        val next = words[index + 1]
        wordToLetter[next]?.let { return it }
        if (next.length == 1 && next[0].isLetter()) return next
        return null
    }

    /**
     * Swift splits on `{ !$0.isLetter }`, which no Kotlin `split` overload expresses
     * directly; a character-class regex is the equivalent, and is built once.
     */
    private val NON_LETTER_RUN = Regex("[^A-Za-z]+")

    // endregion

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
}
