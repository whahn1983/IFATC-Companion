package com.h3consultingpartners.ifatccompanion.core.airports

import com.h3consultingpartners.ifatccompanion.core.geo.Geo

/**
 * Real-world runway inventory for airports. Used to pick a realistic active
 * runway from the surface wind — the same way an ATIS/controller chooses the
 * active runway — instead of inventing a runway number that does not exist at
 * the field (e.g. "runway 14" at Newark, which has none).
 *
 * Each airport lists its runway idents with the commonly-active end first so
 * the wind-based pick is stable for calm/ambiguous winds. A runway's magnetic
 * heading is derived from its number (×10), which is accurate to within a few
 * degrees — more than enough to choose the best-aligned runway.
 *
 * Ported from `IFATCCompanion/ATC/RunwayDatabase.swift`. The Swift `shared`
 * singleton becomes a Kotlin `object`.
 */
object RunwayDatabase {

    /** ICAO (4-letter) -> ordered runway idents (e.g. ["22R", "22L", "4L", "4R", "11", "29"]). */
    val airports: Map<String, List<String>> = mapOf(
        // New York / New Jersey
        "KEWR" to listOf("22R", "22L", "4L", "4R", "11", "29"),
        "KJFK" to listOf("31L", "31R", "13L", "13R", "4L", "4R", "22L", "22R"),
        "KLGA" to listOf("22", "4", "13", "31"),
        // Major US hubs (mirrors AirportDatabase coverage)
        "KIAH" to listOf("26L", "26R", "8L", "8R", "9", "27", "15L", "15R", "33L", "33R"),
        "KMSP" to listOf("30L", "30R", "12L", "12R", "4", "22"),
        "KDEN" to listOf("34L", "34R", "16L", "16R", "17L", "17R", "35L", "35R", "7", "25", "8", "26"),
        "KORD" to listOf(
            "28R", "28C", "28L", "27R", "27C", "27L", "22L", "22R",
            "10L", "10C", "10R", "9R", "9C", "9L", "4L", "4R",
        ),
        "KATL" to listOf("26R", "26L", "27R", "27L", "28", "8L", "8R", "9L", "9R", "10"),
        "KLAX" to listOf("25R", "25L", "24R", "24L", "6L", "6R", "7L", "7R"),
        "KSFO" to listOf("28L", "28R", "1L", "1R", "10L", "10R", "19L", "19R"),
        "KSEA" to listOf("16L", "16C", "16R", "34L", "34C", "34R"),
        "KDFW" to listOf(
            "35L", "35C", "35R", "36L", "36R", "17L", "17C", "17R",
            "18L", "18R", "13L", "13R", "31L", "31R",
        ),
        "KBOS" to listOf("4L", "4R", "22L", "22R", "9", "27", "14", "32", "15R", "15L", "33L", "33R"),
        "KMIA" to listOf("8L", "8R", "26L", "26R", "9", "27", "12", "30"),
        "KLAS" to listOf("26L", "26R", "8L", "8R", "1L", "1R", "19L", "19R"),
        "KPHX" to listOf("25L", "25R", "26", "7L", "7R", "8"),
        "KDCA" to listOf("1", "19", "15", "33", "4", "22"),
        "KMCI" to listOf("19L", "19R", "1L", "1R", "9", "27"),
        "KSTL" to listOf("30L", "30R", "12L", "12R", "6", "24", "11", "29"),
        "KOMA" to listOf("32R", "32L", "14L", "14R", "18", "36"),
        "KDSM" to listOf("31", "13", "5", "23"),
    )

    /**
     * Look up an airport's runways. Accepts 4-letter ICAO ("KEWR") or 3-letter
     * US codes ("EWR", resolved as "K"+code).
     */
    fun runways(code: String): List<String> {
        val normalized = code.uppercase().trim()
        airports[normalized]?.let { return it }
        if (normalized.length == 3) {
            airports["K$normalized"]?.let { return it }
        }
        return emptyList()
    }

    /**
     * The active runway best aligned into the wind (the direction the wind is
     * coming *from*, in degrees). Returns null when the airport is unknown, so
     * the caller can fall back to a wind-derived guess.
     *
     * Calm/variable wind (≤ 3 kt) keeps the field's primary runway (first in the
     * list) for a stable, realistic default rather than chasing noise.
     */
    fun activeRunway(code: String, windDirection: Int, windSpeed: Int): String? =
        activeRunwayAmong(runways(code), windDirection = windDirection, windSpeed = windSpeed)

    /**
     * The same pick, made among a runway list supplied by the caller — the field's own
     * runway-end idents parsed from its loaded airport surface. That source is
     * airport-agnostic, so it reaches the many fields the curated table above does not, and
     * choosing *among real runways* is the whole point: the alternative last resort invents a
     * runway number from the wind, and that number is read back as a heading by the takeoff
     * clearance and the line-up detector. The curated table is still consulted first, because
     * it is ordered with each field's commonly-active end first and so gives a stable,
     * realistic answer in calm wind; a parsed surface has no such ordering.
     */
    fun activeRunwayAmong(idents: List<String>, windDirection: Int, windSpeed: Int): String? {
        val primary = idents.firstOrNull() ?: return null
        if (!(windSpeed > 3 && windDirection > 0)) return primary

        val wind = windDirection.toDouble()
        // Most into-wind runway: smallest angular difference between the runway's
        // heading and the wind direction. Stable on ties (keeps list order).
        val best = idents.minByOrNull { Geo.headingDifference(headingOf(it), wind) }
        return best ?: primary
    }

    /**
     * Magnetic heading implied by a runway ident's leading number (×10).
     * "22R" -> 220, "4L" -> 40, "36" -> 360.
     */
    private fun headingOf(ident: String): Double = headingForRunway(ident) ?: 360.0

    /**
     * Magnetic heading implied by a runway ident's leading number (×10), or null
     * when the ident carries no usable runway number. "22R" -> 220, "4L" -> 40,
     * "36" -> 360. Accurate to within a few degrees of the true runway heading.
     */
    fun headingForRunway(ident: String): Double? {
        val digits = ident.takeWhile { it.isDigit() }
        val number = digits.toIntOrNull() ?: return null
        if (number <= 0 || number > 36) return null
        return (number * 10).toDouble()
    }
}
