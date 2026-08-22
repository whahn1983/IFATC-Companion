package com.h3consultingpartners.ifatccompanion.core.surface

/**
 * Splits the identifier tagged on an aircraft stand into the one a controller says and
 * every other identifier the same stand answers to.
 *
 * OSM maps a physical stand that can be worked under more than one number as a *single*
 * node carrying all of them in its `ref`: `A1;A2` at Newark, `A54/A56` at Frankfurt,
 * `C16/C16A + C16B`. Around 8–10% of the stands at those two fields are tagged this way,
 * so it is not an edge case. Stored verbatim, such a value is displayed and spoken back
 * as written ("gate A1;A2"), and — worse — a pilot who *types* `A1` matches nothing at
 * all, because the stand is named `A1;A2` and the lookup is an exact one. That affects
 * manual entry, not just the automatic assignment.
 *
 * So the tag is split: the first identifier becomes the stand's name — what the clearance
 * says and what the map labels — and the rest, plus the raw tag value itself, become
 * aliases the lookup also accepts. Nothing is lost: the original `ref` stays verbatim in
 * [SurfaceParking.tags], as every OSM tag does.
 *
 * Ported from `IFATCCompanion/AirportSurface/StandIdentifier.swift`.
 */
object StandIdentifier {

    /** The name a controller says plus the other identifiers the stand answers to. */
    data class Parsed(val name: String, val aliases: List<String>)

    /**
     * Characters mappers use to join several identifiers into one `ref`. `;` is the OSM
     * multi-value separator; the rest are what mappers actually write in the wild.
     * Deliberately excludes `-`, which reads as a range ("A1-A5") rather than a list, and
     * whitespace, which is part of identifiers like "Gate 12".
     */
    private val SEPARATORS = charArrayOf(';', '/', '+', ',', '&')

    /**
     * Split a stand's `ref`/`name` into the name to use and the aliases it also answers to.
     * A single-identifier tag — the common case — comes back unchanged with no aliases.
     */
    fun parse(raw: String): Parsed {
        val trimmed = raw.trim()
        if (trimmed.none { it in SEPARATORS }) return Parsed(trimmed, emptyList())

        val identifiers = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (piece in trimmed.split(*SEPARATORS)) {
            val part = piece.trim()
            if (part.isEmpty()) continue
            val identifier = inheritingPrefix(part, identifiers.lastOrNull())
            if (!seen.add(identifier.uppercase())) continue
            identifiers.add(identifier)
        }
        val name = identifiers.firstOrNull() ?: return Parsed(trimmed, emptyList())
        // Only one identifier survived the split — a stray separator, or the same one twice.
        // There is nothing extra to match on, so the raw value isn't worth carrying.
        if (identifiers.size <= 1) return Parsed(name, emptyList())
        // The tag exactly as written is an alias too, so a pilot who copies it in still matches.
        return Parsed(name, identifiers.drop(1) + trimmed)
    }

    /**
     * `A54/56` means A54 *and* A56: a bare number following a lettered identifier inherits
     * its letters. Applied only in that exact shape — an all-digit part after a part that
     * starts with letters — so `1/2` and `A1/B2` are left exactly as tagged.
     */
    private fun inheritingPrefix(part: String, previous: String?): String {
        if (previous == null || part.isEmpty() || !part.all { it.isDigit() }) return part
        val letters = previous.takeWhile { it.isLetter() }
        if (letters.isEmpty()) return part
        return letters + part
    }
}
