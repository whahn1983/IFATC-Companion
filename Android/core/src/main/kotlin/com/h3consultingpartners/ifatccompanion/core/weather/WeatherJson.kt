package com.h3consultingpartners.ifatccompanion.core.weather

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Lenient accessors for the loosely-typed JSON the Aviation Weather Center API
 * returns (fields can be numbers, strings, or absent). Keeps parsing resilient.
 *
 * Ported from `IFATCCompanion/Weather/WeatherJSON.swift`. `JSONSerialization` +
 * `Any?` become `kotlinx.serialization`'s [JsonElement] tree; the "is it an Int, a
 * Double or a String" cascade is preserved clause for clause, because the AWC feed
 * really does move fields between those shapes between products.
 */
object JsonLenient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The payload as a list of objects. Mirrors Swift's two casts exactly: an array
     * is only usable when **every** element is an object (`as? [[String: Any]]` fails
     * wholesale otherwise), and a bare object is wrapped in a single-element list.
     */
    fun array(data: ByteArray): List<JsonObject> {
        val element = runCatching { json.parseToJsonElement(data.toString(Charsets.UTF_8)) }
            .getOrNull() ?: return emptyList()
        objectArray(element)?.let { return it }
        if (element is JsonObject) return listOf(element)
        return emptyList()
    }

    /**
     * A nested array of objects (`obj["clouds"] as? [[String: Any]]`), or null when the
     * value is absent, not an array, or holds anything that is not an object — the
     * all-or-nothing semantics of the Swift cast, which leaves the field empty rather
     * than silently dropping the bad entries.
     */
    fun objectArray(value: JsonElement?): List<JsonObject>? {
        val array = value as? JsonArray ?: return null
        if (!array.all { it is JsonObject }) return null
        return array.map { it as JsonObject }
    }

    /**
     * Integer value. Note this is a 32-bit `Int` where Swift's `Int` is 64-bit, so an
     * epoch value beyond 2038-01-19 parses on iOS and returns null here; every field
     * that uses it (wind, flight level, cloud base) is far inside the range.
     */
    fun int(value: JsonElement?): Int? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString) return intFromString(primitive.content)
        primitive.content.toIntOrNull()?.let { return it }
        // Swift's `case let d as Double: return Int(d)` — truncation toward zero.
        return primitive.content.toDoubleOrNull()?.toInt()
    }

    /** The `case let s as String` branch: keep digits and a sign, then parse. */
    private fun intFromString(s: String): Int? =
        s.filter { it.isDigit() || it == '-' }.toIntOrNull()

    fun double(value: JsonElement?): Double? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString) return double(primitive.content)
        return primitive.content.toDoubleOrNull()
    }

    /**
     * The `case let s as String` branch, also used directly by the raw-METAR parser
     * for visibility tokens. Handles "10+" and "1/2".
     */
    fun double(s: String?): Double? {
        if (s == null) return null
        if (s.contains("/")) {
            val parts = s.split("/")
            if (parts.size == 2) {
                val a = parts[0].toDoubleOrNull()
                val b = parts[1].toDoubleOrNull()
                if (a != null && b != null && b != 0.0) return a / b
            }
        }
        return s.filter { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull()
    }

    fun string(value: JsonElement?): String? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString) return primitive.content
        // Swift bridges a JSON number to NSNumber, which casts to Int when it is
        // exactly representable and to Double otherwise.
        primitive.content.toIntOrNull()?.let { return it.toString() }
        primitive.content.toDoubleOrNull()?.let { return it.toString() }
        return null
    }

    /**
     * AWC report times come as ISO-ish strings or epoch seconds. Returned as epoch
     * **milliseconds** — :core has no Foundation `Date` and keeps every timestamp as
     * `Long` millis.
     */
    fun date(value: JsonElement?): Long? {
        val epoch = int(value)
        if (epoch != null && epoch > 1_000_000_000) return epoch.toLong() * 1000L
        val s = string(value) ?: return null
        // `ISO8601DateFormatter` (internet date time): a zone designator is required.
        runCatching { Instant.parse(s) }.getOrNull()?.let { return it.toEpochMilli() }
        runCatching { OffsetDateTime.parse(s) }.getOrNull()?.let { return it.toInstant().toEpochMilli() }
        // Then the explicit "yyyy-MM-dd'T'HH:mm:ss'Z'" fallback, read as UTC.
        runCatching { LocalDateTime.parse(s, LITERAL_Z_FORMATTER) }.getOrNull()
            ?.let { return it.toInstant(ZoneOffset.UTC).toEpochMilli() }
        return null
    }

    private val LITERAL_Z_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
}
