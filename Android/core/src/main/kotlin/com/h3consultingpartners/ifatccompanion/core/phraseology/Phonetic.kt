package com.h3consultingpartners.ifatccompanion.core.phraseology

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure, deterministic aviation phonetics. No randomness, no AI.
 * These functions are unit-tested and used by [PhraseologyEngine].
 *
 * Every digit-bearing helper accepts an [icao] flag selecting the phraseology
 * pack. FAA (the default) keeps the familiar "three / four / five" digit words;
 * ICAO radiotelephony substitutes "tree / fower / fife" and uses "decimal"
 * instead of "point" for frequencies. Defaulting `icao` to `false` keeps every
 * existing caller and unit test on the FAA pack unchanged.
 *
 * Ported from `IFATCCompanion/Phraseology/Phonetic.swift`.
 */
object Phonetic {

    val digitWords: Map<Char, String> = mapOf(
        '0' to "zero", '1' to "one", '2' to "two", '3' to "three", '4' to "four",
        '5' to "five", '6' to "six", '7' to "seven", '8' to "eight", '9' to "niner",
    )

    /**
     * ICAO radiotelephony digit words (ICAO Annex 10): note "tree", "fower",
     * "fife", "niner". Other digits are spoken as in the FAA set.
     */
    val icaoDigitWords: Map<Char, String> = mapOf(
        '0' to "zero", '1' to "one", '2' to "two", '3' to "tree", '4' to "fower",
        '5' to "fife", '6' to "six", '7' to "seven", '8' to "eight", '9' to "niner",
    )

    fun digitMap(icao: Boolean): Map<Char, String> = if (icao) icaoDigitWords else digitWords

    val letterWords: Map<Char, String> = mapOf(
        'A' to "Alpha", 'B' to "Bravo", 'C' to "Charlie", 'D' to "Delta", 'E' to "Echo",
        'F' to "Foxtrot", 'G' to "Golf", 'H' to "Hotel", 'I' to "India", 'J' to "Juliett",
        'K' to "Kilo", 'L' to "Lima", 'M' to "Mike", 'N' to "November", 'O' to "Oscar",
        'P' to "Papa", 'Q' to "Quebec", 'R' to "Romeo", 'S' to "Sierra", 'T' to "Tango",
        'U' to "Uniform", 'V' to "Victor", 'W' to "Whiskey", 'X' to "X-ray",
        'Y' to "Yankee", 'Z' to "Zulu",
    )

    /**
     * Speak each digit individually: "4271" -> "four two seven one".
     *
     * Characters with no digit word are dropped silently (Swift `compactMap`), so
     * "1A2" reads "one two".
     */
    fun spellDigits(s: String, icao: Boolean = false): String {
        val map = digitMap(icao)
        return s.mapNotNull { map[it] }.joinToString(" ")
    }

    /** Spell a mixed token letter-by-letter / digit-by-digit (taxiway "A11" -> "Alpha one one"). */
    fun spellToken(s: String, icao: Boolean = false): String {
        val map = digitMap(icao)
        // Digits are looked up before letters, and anything that is neither (hyphens,
        // spaces, punctuation) is dropped entirely.
        return s.uppercase().mapNotNull { ch -> map[ch] ?: letterWords[ch] }.joinToString(" ")
    }

    /**
     * Group integer below 100 into natural English ("34" -> "thirty four", "8" -> "eight").
     *
     * As in the Swift there is no bounds checking: callers guarantee 0…99, and a value
     * outside that range indexes past the end of the word tables and throws.
     */
    fun twoDigitGroup(n: Int, icao: Boolean = false): String {
        val ones = if (icao) {
            listOf("zero", "one", "two", "tree", "fower", "fife", "six", "seven", "eight", "niner")
        } else {
            listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "niner")
        }
        val teens = listOf(
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
            "sixteen", "seventeen", "eighteen", "nineteen",
        )
        val tens = listOf(
            "", "", "twenty", "thirty", "forty", "fifty",
            "sixty", "seventy", "eighty", "ninety",
        )
        if (n < 10) return ones[n]
        if (n < 20) return teens[n - 10]
        val t = n / 10
        val o = n % 10
        return if (o == 0) tens[t] else "${tens[t]} ${ones[o]}"
    }

    /**
     * Pronounce an altitude in feet per ATC convention.
     * 10,000 -> "one zero thousand", 37,000 -> "flight level three seven zero",
     * 2,500 -> "two thousand five hundred".
     *
     * [transitionAltitude] is in feet and defaults to 18,000 — the US transition
     * altitude. No caller in the phraseology area overrides it.
     */
    fun altitude(feet: Int, transitionAltitude: Int = 18000, icao: Boolean = false): String {
        if (feet <= 0) return "field elevation"
        if (feet >= transitionAltitude) {
            val fl = feet / 100
            return "flight level " + spellDigits(fl.toString(), icao)
        }
        val thousands = feet / 1000
        val hundreds = (feet % 1000) / 100
        val parts = mutableListOf<String>()
        if (thousands > 0) {
            // ATC spells the thousands digits: 11,000 -> "one one thousand"
            parts.add(spellDigits(thousands.toString(), icao) + " thousand")
        }
        if (hundreds > 0) {
            parts.add(spellDigits(hundreds.toString(), icao) + " hundred")
        }
        if (parts.isEmpty()) {
            // sub-100 ft, e.g. pattern altitude rounding
            return spellDigits(feet.toString(), icao)
        }
        return parts.joinToString(" ")
    }

    /** Heading: 270 -> "two seven zero" (always 3 digits). */
    fun heading(deg: Int, icao: Boolean = false): String {
        val normalized = ((deg % 360) + 360) % 360
        val padded = String.format(Locale.US, "%03d", normalized)
        return spellDigits(padded, icao)
    }

    /**
     * Frequency: 118.300 -> "one one eight point three" (FAA) /
     * "one one eight decimal three" (ICAO).
     *
     * The `%.3f` format is pinned to [Locale.US]: a comma decimal separator would
     * break the split and silently drop the fractional part.
     */
    fun frequency(mhz: Double, icao: Boolean = false): String {
        // Format to up to 3 decimal places, then trim trailing zeros (keep at least one).
        var s = String.format(Locale.US, "%.3f", mhz)
        while (s.endsWith("0") && !s.endsWith(".0")) s = s.dropLast(1)
        // Swift's `split(separator:)` omits empty subsequences.
        val parts = s.split(".").filter { it.isNotEmpty() }
        val whole = spellDigits(parts[0], icao)
        if (parts.size <= 1) return whole
        val frac = spellDigits(parts[1], icao)
        val separator = if (icao) "decimal" else "point"
        return "$whole $separator $frac"
    }

    /** Runway: "17R" -> "one seven right", "04L" -> "zero four left", "09" -> "zero niner". */
    fun runway(raw: String, icao: Boolean = false): String {
        val upper = raw.uppercase().trim()
        var digits = ""
        var suffix = ""
        for (ch in upper) {
            if (ch.isDigit()) digits += ch
            else if (ch == 'L' || ch == 'R' || ch == 'C') suffix = ch.toString()
        }
        // Note: the *original* string comes back, untrimmed and in its original case.
        if (digits.isEmpty()) return raw
        // Pad single-digit runways to two digits per convention (9 -> 09).
        if (digits.length == 1) digits = "0$digits"
        var result = spellDigits(digits, icao)
        when (suffix) {
            "L" -> result += " left"
            "R" -> result += " right"
            "C" -> result += " center"
        }
        return result
    }

    // MARK: - Runway direction pairs

    /**
     * Split a runway ident into its number (1…36) and side suffix. "24L" -> (24, "L"),
     * "06R" -> (6, "R"), "36" -> (36, ""). The number is null when the ident carries no
     * usable runway number.
     */
    private fun runwayComponents(raw: String): Pair<Int?, String> {
        val upper = raw.uppercase().trim()
        var digits = ""
        var suffix = ""
        for (ch in upper) {
            if (ch.isDigit()) digits += ch
            else if (ch == 'L' || ch == 'R' || ch == 'C') suffix = ch.toString()
        }
        val n = digits.toIntOrNull()
        if (n == null || n < 1 || n > 36) return null to suffix
        return n to suffix
    }

    /**
     * The reciprocal runway ident — the opposite end of the same physical runway:
     * "24L" -> "6R", "06R" -> "24L", "36" -> "18", "09" -> "27", "13C" -> "31C".
     * Returns null when [raw] carries no usable runway number.
     */
    fun reciprocalRunway(raw: String): String? {
        val (number, suffix) = runwayComponents(raw)
        if (number == null) return null
        val reciprocalNumber = if (number <= 18) number + 18 else number - 18
        val reciprocalSuffix = when (suffix) {
            "L" -> "R"
            "R" -> "L"
            // "C" stays center; a bare number stays bare
            else -> suffix
        }
        return "$reciprocalNumber$reciprocalSuffix"
    }

    /**
     * Both physical ends of a runway, ordered lower-number-first: "24L" -> ("6R", "24L"),
     * "36" -> ("18", "36"). Null when no reciprocal can be derived.
     */
    private fun orderedRunwayEnds(raw: String): Pair<String, String>? {
        val (number, suffix) = runwayComponents(raw)
        val reciprocal = reciprocalRunway(raw)
        if (number == null || reciprocal == null) return null
        // Note: not zero-padded — "06R" becomes "6R".
        val end = "$number$suffix"
        val reciprocalNumber = if (number <= 18) number + 18 else number - 18
        return if (number <= reciprocalNumber) end to reciprocal else reciprocal to end
    }

    /**
     * Both physical directions of a runway as a written designation, lower number first:
     * "24L" -> "6R-24L", "06R" -> "6R-24L", "36" -> "18-36", "09" -> "9-27". Falls back to
     * the trimmed single ident when no reciprocal can be derived.
     */
    fun runwayPairDisplay(raw: String): String {
        val ends = orderedRunwayEnds(raw) ?: return raw.uppercase().trim()
        return "${ends.first}-${ends.second}"
    }

    /**
     * Speak a single runway end without the two-digit padding [runway] applies, so both
     * ends of a pair read naturally: "6R" -> "six right", "24L" -> "two four left".
     */
    private fun spokenRunwayEnd(end: String, icao: Boolean): String {
        val (number, suffix) = runwayComponents(end)
        if (number == null) return runway(end, icao)
        var result = spellDigits(number.toString(), icao)
        when (suffix) {
            "L" -> result += " left"
            "R" -> result += " right"
            "C" -> result += " center"
        }
        return result
    }

    /**
     * Both physical directions of a runway spoken end-to-end, lower number first:
     * "24L" -> "six right two four left", "36" -> "one eight three six". Falls back to the
     * single-runway phonetics when no reciprocal can be derived.
     */
    fun runwayPairSpoken(raw: String, icao: Boolean = false): String {
        val ends = orderedRunwayEnds(raw) ?: return runway(raw, icao)
        return "${spokenRunwayEnd(ends.first, icao)} ${spokenRunwayEnd(ends.second, icao)}"
    }

    /** Wind: dir 330 / speed 12 -> "wind three three zero at one two". */
    fun wind(direction: Int, speed: Int, gust: Int? = null, icao: Boolean = false): String {
        if (direction == 0 && speed == 0) return "wind calm"
        val dir = if (direction == 0) {
            "variable"
        } else {
            spellDigits(String.format(Locale.US, "%03d", ((direction % 360) + 360) % 360), icao)
        }
        var s = "wind $dir at ${spellDigits(speed.toString(), icao)}"
        // The gust clause only appears when the gust is strictly greater than the steady speed.
        if (gust != null && gust > speed) {
            s += " gusting ${spellDigits(gust.toString(), icao)}"
        }
        return s
    }

    /**
     * Squawk: 4271 -> "squawk four two seven one". The keyword is part of the returned
     * value — a template must not prepend it a second time.
     */
    fun squawk(code: String, icao: Boolean = false): String =
        "squawk " + spellDigits(code, icao)

    /** Visibility in statute miles: 10 -> "one zero", 3 -> "three". */
    fun visibility(sm: Int, icao: Boolean = false): String = spellDigits(sm.toString(), icao)

    /**
     * Altimeter: FAA reports inHg ("altimeter three zero one two"); ICAO reports
     * QNH in whole hectopascals ("QNH one zero one three"). Use [altimeterSetting]
     * for the leading keyword + value combined.
     */
    fun altimeter(inHg: Double, icao: Boolean = false): String {
        // Swift `.rounded()` rounds half away from zero; altimeter settings are always
        // positive, where `roundToInt()` (half toward +infinity) agrees exactly.
        val scaled = (inHg * 100).roundToInt()
        return spellDigits(scaled.toString(), icao)
    }

    /** Full altimeter/QNH phrase including the keyword, selected by pack. */
    fun altimeterSetting(inHg: Double, icao: Boolean = false): String {
        if (icao) {
            // 33.8638866667 hPa per inHg — do not round this constant.
            val hpa = (inHg * 33.8638866667).roundToInt()
            return "QNH " + spellDigits(hpa.toString(), icao)
        }
        return "altimeter " + altimeter(inHg, icao)
    }
}
