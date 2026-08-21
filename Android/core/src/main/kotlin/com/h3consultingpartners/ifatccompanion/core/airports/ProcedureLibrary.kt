package com.h3consultingpartners.ifatccompanion.core.airports

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The kind of published procedure.
 *
 * Ported from `IFATCCompanion/ATC/ProcedureLibrary.swift`. Raw values match the Swift
 * `String` raw values exactly so persisted plans decode the same on both platforms.
 */
@Serializable
enum class ProcedureKind(val rawValue: String) {
    /** Standard Instrument Departure. */
    @SerialName("sid") SID("sid"),

    /** Standard Terminal Arrival. */
    @SerialName("star") STAR("star"),

    @SerialName("approach") APPROACH("approach"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): ProcedureKind? =
            entries.firstOrNull { it.rawValue == rawValue }
    }
}

/** Instrument approach types, with display + spoken forms. */
@Serializable
enum class ApproachType(val rawValue: String) {
    @SerialName("ils") ILS("ils"),
    @SerialName("loc") LOC("loc"),
    @SerialName("rnav") RNAV("rnav"),
    @SerialName("rnavGPS") RNAV_GPS("rnavGPS"),
    @SerialName("vor") VOR("vor"),
    @SerialName("ndb") NDB("ndb"),
    @SerialName("gps") GPS("gps"),
    @SerialName("visual") VISUAL("visual"),
    ;

    val display: String
        get() = when (this) {
            ILS -> "ILS"
            LOC -> "LOC"
            RNAV -> "RNAV"
            RNAV_GPS -> "RNAV (GPS)"
            VOR -> "VOR"
            NDB -> "NDB"
            GPS -> "GPS"
            VISUAL -> "Visual"
        }

    /** Spelled for the speech synthesizer (letters separated so they're read out). */
    val spoken: String
        get() = when (this) {
            ILS -> "I L S"
            LOC -> "localizer"
            RNAV -> "R NAV"
            RNAV_GPS -> "R NAV G P S"
            VOR -> "V O R"
            NDB -> "N D B"
            GPS -> "G P S"
            VISUAL -> "visual"
        }

    companion object {
        fun fromRawValue(rawValue: String): ApproachType? =
            entries.firstOrNull { it.rawValue == rawValue }

        fun parse(text: String): ApproachType? {
            val t = text.uppercase()
            if (t.contains("RNAV") && t.contains("GPS")) return RNAV_GPS
            if (t.contains("RNAV")) return RNAV
            if (t.contains("ILS")) return ILS
            if (t.contains("LOC")) return LOC
            if (t.contains("VOR")) return VOR
            if (t.contains("NDB")) return NDB
            if (t.contains("GPS")) return GPS
            if (t.contains("VIS")) return VISUAL
            return null
        }
    }
}

/**
 * The phraseology spelling a [Procedure] needs to speak itself.
 *
 * CONTRACT: on iOS these are `Phonetic.runway`, `Phonetic.spellDigits` and
 * `Phonetic.spellToken`. `Phonetic` belongs to the phraseology package, so this
 * package declares only the three calls it makes and takes the speller as a
 * parameter — wire the real `Phonetic` in as the implementation.
 */
interface ProcedureSpeller {
    /** Runway: "17R" -> "one seven right", "04L" -> "zero four left", "09" -> "zero niner". */
    fun runway(raw: String, icao: Boolean): String

    /** Speak each digit individually: "4271" -> "four two seven one". */
    fun spellDigits(s: String, icao: Boolean): String

    /** Spell a mixed token letter-by-letter / digit-by-digit ("A11" -> "Alpha one one"). */
    fun spellToken(s: String, icao: Boolean): String
}

/**
 * A parsed published procedure (SID, STAR, or approach). Deterministically
 * derived from the procedure name string the pilot enters, optionally enriched
 * with known fixes from the built-in [ProcedureLibrary].
 */
data class Procedure(
    val kind: ProcedureKind,
    /** Designator root, e.g. "WAGON". */
    val name: String,
    /** Trailing revision number, e.g. 5. */
    val revision: Int? = null,
    /** Text after a "." separator, e.g. "HOBTT". */
    val transition: String? = null,
    /** For approaches / runway-specific procedures. */
    val runway: String? = null,
    val approachType: ApproachType? = null,
    /** Ordered fixes, when known. */
    val fixes: List<String> = emptyList(),
) {

    /** Transcript form, e.g. "WAGON5", "WAGON5.HOBTT", "ILS RWY 30L". */
    val displayName: String
        get() = when (kind) {
            ProcedureKind.APPROACH -> {
                val type = approachType?.display ?: "Approach"
                val rwy = runway
                if (rwy != null && rwy.isNotEmpty()) "$type RWY $rwy" else type
            }
            ProcedureKind.SID, ProcedureKind.STAR -> {
                var base = name
                revision?.let { base += "$it" }
                val t = transition
                if (t != null && t.isNotEmpty()) base += ".$t"
                base
            }
        }

    /** Spoken form for the synthesizer. */
    fun spokenName(speller: ProcedureSpeller, icao: Boolean): String =
        when (kind) {
            ProcedureKind.APPROACH -> {
                val type = approachType?.spoken ?: "approach"
                val rwy = runway
                if (rwy != null && rwy.isNotEmpty()) {
                    "$type runway ${speller.runway(rwy, icao)}"
                } else {
                    type
                }
            }
            ProcedureKind.SID, ProcedureKind.STAR -> {
                // Speak the name word as-is (the synthesizer pronounces it), plus the
                // revision number spelled out, plus an optional transition.
                val parts = mutableListOf(capitalizedWords(name))
                revision?.let { parts.add(speller.spellDigits(it.toString(), icao)) }
                var s = parts.joinToString(" ")
                val t = transition
                if (t != null && t.isNotEmpty()) s += ", ${speller.spellToken(t, icao)} transition"
                s
            }
        }

    private companion object {
        /**
         * Swift's `String.capitalized`: the first character of each word uppercased, the
         * rest lowercased ("WAGON" -> "Wagon", "X-RAY" -> "X-Ray"). A word continues for
         * as long as the characters stay alphanumeric.
         */
        fun capitalizedWords(text: String): String {
            val out = StringBuilder(text.length)
            var previousWasWordCharacter = false
            for (ch in text) {
                if (ch.isLetterOrDigit()) {
                    out.append(if (previousWasWordCharacter) ch.lowercaseChar() else ch.uppercaseChar())
                    previousWasWordCharacter = true
                } else {
                    out.append(ch)
                    previousWasWordCharacter = false
                }
            }
            return out.toString()
        }
    }
}

/**
 * Parses procedure name strings and supplies a small built-in library of known
 * procedures (fixes) for the demo/mock airports. Best-effort and deterministic.
 */
object ProcedureParser {

    /** Parse a SID name string, e.g. "WAGON5", "WAGmm", "WAGON5.HOBTT". */
    fun parseSID(raw: String, icao: String? = null): Procedure? =
        parseDesignator(raw, kind = ProcedureKind.SID, icao = icao)

    /** Parse a STAR name string, e.g. "KKILR3", "BDF.BDF7". */
    fun parseSTAR(raw: String, icao: String? = null): Procedure? =
        parseDesignator(raw, kind = ProcedureKind.STAR, icao = icao)

    /** Parse an approach string, e.g. "ILS 30L", "RNAV (GPS) 27", "VOR 09". */
    fun parseApproach(raw: String): Procedure? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val type = ApproachType.parse(trimmed)
        val runway = extractRunway(trimmed)
        // If neither a type nor a runway is present it's not a usable approach.
        if (type == null && runway == null) return null
        return Procedure(
            kind = ProcedureKind.APPROACH,
            name = type?.display ?: "Approach",
            revision = null,
            transition = null,
            runway = runway,
            // Ported as-is: a runway with no recognisable type is assumed to be an ILS.
            approachType = type ?: ApproachType.ILS,
        )
    }

    // region Internals

    private fun parseDesignator(raw: String, kind: ProcedureKind, icao: String?): Procedure? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // Split off an optional ".TRANSITION".
        val dotParts = splitOnFirstSeparator(trimmed, '.')
        val designator = dotParts[0]
        val transition = if (dotParts.size > 1) dotParts[1] else null

        // Separate a trailing revision number from the alphabetic root.
        var root = designator
        var revision: Int? = null
        if (designator.lastOrNull()?.isDigit() == true) {
            var digits = ""
            while (root.lastOrNull()?.isDigit() == true) {
                digits = root.last() + digits
                root = root.dropLast(1)
            }
            revision = digits.toIntOrNull()
        }
        if (root.isEmpty()) return null

        var procedure = Procedure(
            kind = kind,
            name = root.uppercase(),
            revision = revision,
            transition = transition?.uppercase(),
            runway = null,
            approachType = null,
        )
        if (icao != null) procedure = ProcedureLibrary.enrich(procedure, icao)
        return procedure
    }

    /**
     * Swift's `split(separator:maxSplits:1)`, which omits empty subsequences: ".ABC"
     * yields ["ABC"] (one part, no transition), "A.B.C" yields ["A", "B.C"]. Kotlin's
     * own `split(limit = 2)` keeps the empty leading piece, which would turn ".ABC"
     * into an empty designator.
     */
    private fun splitOnFirstSeparator(text: String, separator: Char): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var index = 0
        while (index < text.length) {
            if (text[index] == separator) {
                var didAppend = false
                if (start != index) {
                    result.add(text.substring(start, index))
                    didAppend = true
                }
                index += 1
                start = index
                if (didAppend && result.size == 1) break
                continue
            }
            index += 1
        }
        if (start != text.length) result.add(text.substring(start))
        return result
    }

    /** Extract a runway identifier (e.g. "30L", "09", "16R") from free text. */
    fun extractRunway(text: String): String? {
        val upper = text.uppercase()
        var current = ""
        var best: String? = null
        fun flush() {
            if (current.isNotEmpty()) {
                // A runway is 1-2 digits optionally followed by L/R/C.
                val digits = current.takeWhile { it.isDigit() }
                val n = digits.toIntOrNull()
                if (digits.length in 1..2 && n != null && n in 1..36) {
                    best = current
                }
            }
            current = ""
        }
        for (ch in upper) {
            if (ch.isDigit()) {
                current += ch
            } else if ((ch == 'L' || ch == 'R' || ch == 'C') && current.isNotEmpty() &&
                current.all { it.isDigit() }
            ) {
                current += ch
                flush()
            } else {
                flush()
            }
        }
        flush()
        return best
    }

    // endregion
}

/**
 * A tiny built-in library of published procedures (with fixes) for the demo
 * airports. Not exhaustive — used to enrich parsed procedures with realistic
 * fixes so procedure-aware instructions sound natural offline.
 */
object ProcedureLibrary {

    data class Entry(val designator: String, val runways: List<String>, val fixes: List<String>)

    val sids: Map<String, List<Entry>> = mapOf(
        "KIAH" to listOf(
            Entry(designator = "WAGON", runways = listOf("15L", "15R"), fixes = listOf("WAGON", "HOBTT", "DAS")),
        ),
        "KMSP" to listOf(
            Entry(designator = "ZALES", runways = listOf("30L", "30R"), fixes = listOf("ZALES", "KKILR")),
        ),
        "KDEN" to listOf(
            Entry(designator = "FLATI", runways = listOf("34L", "34R"), fixes = listOf("FLATI", "AKO")),
        ),
    )

    val stars: Map<String, List<Entry>> = mapOf(
        "KMSP" to listOf(
            Entry(designator = "KKILR", runways = listOf("30L", "30R"), fixes = listOf("FGT", "KKILR", "GOPHR")),
        ),
        "KIAH" to listOf(
            Entry(designator = "DOOBI", runways = listOf("26L", "26R"), fixes = listOf("DOOBI", "GUMBYS")),
        ),
        "KDEN" to listOf(
            Entry(designator = "QUAIL", runways = listOf("16L", "16R"), fixes = listOf("QUAIL", "BAACK")),
        ),
    )

    /** Attach known fixes / runways to a parsed SID/STAR if the designator matches. */
    fun enrich(procedure: Procedure, icao: String): Procedure {
        val table = when (procedure.kind) {
            ProcedureKind.SID -> sids
            ProcedureKind.STAR -> stars
            ProcedureKind.APPROACH -> emptyMap()
        }
        val entries = table[icao.uppercase()] ?: return procedure
        val match = entries.firstOrNull { it.designator == procedure.name } ?: return procedure
        return procedure.copy(
            fixes = match.fixes,
            runway = procedure.runway ?: match.runways.firstOrNull(),
        )
    }
}
