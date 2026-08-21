package com.h3consultingpartners.ifatccompanion.core.atis

/**
 * Extracts the **active departure and arrival runways** from a D-ATIS report, so the
 * simulated background chatter can reference the runways actually in use at a field
 * (e.g. departing 25R, landing 24R) rather than any runway on the map.
 *
 * It is a pure, deterministic scan over the published D-ATIS text — no network,
 * nothing invented. The coded runway groups are read the way a controller would:
 * keywords such as `DEPG` / `TKOF` mark a departure runway, `LDG` / `ILS` / `APCH`
 * mark an arrival runway, and a combined phrase (`LDG AND DEPG RWY 13`) marks both. A
 * field that publishes separate arrival and departure ATIS resolves each part by its
 * own [AirportATIS.Kind]; a text the scanner doesn't recognize simply yields nothing
 * (the caller then falls back to the field's full runway set).
 *
 * Ported from `IFATCCompanion/ATIS/ATISRunwayParser.swift`.
 */
object ATISRunwayParser {

    /** The active runways parsed from a report, split by operation. */
    data class Runways(
        val departures: List<String> = emptyList(),
        val arrivals: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean get() = departures.isEmpty() && arrivals.isEmpty()
    }

    /**
     * Active runways across every part of a report — a single combined ATIS, or
     * separate arrival and departure ATIS — de-duplicated in first-seen order.
     */
    fun activeRunways(atis: AirportATIS): Runways {
        val departures = LinkedHashSet<String>()
        val arrivals = LinkedHashSet<String>()
        for (part in atis.parts) {
            val parsed = parse(part.text, part.kind)
            departures.addAll(parsed.departures)
            arrivals.addAll(parsed.arrivals)
        }
        return Runways(departures.toList(), arrivals.toList())
    }

    /**
     * Canonical runway ident for comparison against other sources (e.g. the OSM map):
     * uppercased with the leading zero dropped, so "04L" and "4L" and "4l" all compare
     * equal; "09" -> "9". A token that isn't a runway is returned uppercased and trimmed.
     */
    fun canonical(ident: String): String = runwayToken(ident) ?: ident.uppercase().trim()

    // region Parsing

    /** Words that put the scanner into a *departure* runway context. */
    private val departureKeywords = setOf(
        "DEPG", "DEPTG", "DPTG", "DEPARTING", "DEPARTURE", "DEPARTURES", "DEP", "DEPS",
        "DEPART", "DEPARTS", "TKOF", "TKOFF", "TAKEOFF", "DEPU",
    )

    /**
     * Words that put the scanner into an *arrival* runway context (landing and every
     * kind of approach clearance, which is always an arrival).
     */
    private val arrivalKeywords = setOf(
        "LDG", "LNDG", "LANDING", "ARR", "ARRS", "ARRIVAL", "ARRIVALS", "ARRIVING",
        "APCH", "APCHS", "APPCH", "APPCHS", "APPROACH", "APPROACHES", "APP", "APPS",
        "ILS", "RNAV", "RNP", "LOC", "VOR", "LDA", "SDF", "GLS", "VISUAL", "VIS", "VA",
    )

    /** Words that introduce a run of runway idents. */
    private val runwayKeywords = setOf("RWY", "RWYS", "RUNWAY", "RUNWAYS", "RY", "RYS")

    /** Tokens that join runway idents inside one group ("24R AND 25L", "27L, 27R"). */
    private val connectorTokens = setOf("AND", "&")

    /**
     * Words that mark a runway mention as a NAVAID-outage, closure, or surface-condition
     * report rather than a runway in use: "…OTS" (out of service), "COND" (condition
     * code), "CLSD"/"CLOSED", and the "unavailable" spellings. A runway named only in
     * one of these contexts (e.g. "RWY 22R LOC OTS", "RWY 9L PAPI OTS", "RWY 22L COND
     * CODE 5 5 5 …") is not the active runway, so it must not inherit the combined-ATIS
     * "both operations" default.
     */
    private val statusTokens = setOf(
        "OTS", "COND", "CLSD", "CLOSED", "UNAVBL", "UNAVAIL", "UNAVAILABLE",
    )

    private val TOKEN_DELIMITERS = charArrayOf(' ', '\n', '\t', '/')

    /**
     * Parse a single D-ATIS text into its active departure/arrival runways. [kind]
     * supplies the default operation for a runway named with no explicit keyword: a
     * departure-only or arrival-only ATIS defaults accordingly; a combined ATIS
     * defaults to both.
     */
    fun parse(text: String, kind: AirportATIS.Kind): Runways {
        val departures = mutableListOf<String>()
        val arrivals = mutableListOf<String>()
        // Clauses are delimited by '.', runway lists by commas — normalize both to
        // whitespace-ish boundaries, then scan each clause with its own keyword context.
        val normalized = text.uppercase().replace(",", " ")
        for (clause in normalized.split('.')) {
            val tokens = clause.split(*TOKEN_DELIMITERS)
                .filter { it.isNotEmpty() }
                .flatMap { splitFlushRunwayKeyword(it) }
            parseClause(tokens, kind, departures, arrivals)
        }
        return Runways(dedup(departures), dedup(arrivals))
    }

    private fun parseClause(
        tokens: List<String>,
        kind: AirportATIS.Kind,
        departures: MutableList<String>,
        arrivals: MutableList<String>,
    ) {
        var pendingDep = false
        var pendingArr = false
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token in departureKeywords) { pendingDep = true; i++; continue }
            if (token in arrivalKeywords) { pendingArr = true; i++; continue }
            if (token !in runwayKeywords) { i++; continue }

            // Collect the runway idents that follow, skipping connectors and any repeated
            // "RWY"/"RWYS" ("RWY 24R AND RWY 25L"); stop at the first non-runway word.
            var j = i + 1
            val collected = mutableListOf<String>()
            while (j < tokens.size) {
                val next = tokens[j]
                if (next in connectorTokens || next in runwayKeywords) { j++; continue }
                val runway = runwayToken(next) ?: break
                collected.add(runway)
                j++
            }
            if (collected.isNotEmpty()) {
                val hasContext = pendingDep || pendingArr
                // A runway named with no arrival/departure keyword is only "in use" when
                // it isn't a NAVAID-outage, closure, or surface-condition report. Without
                // this, the combined-ATIS default would flag every "RWY x LOC OTS" / "RWY
                // x COND CODE …" runway as an active arrival+departure runway. An
                // explicitly keyworded group (ILS/APCH/DEPG/…) is always trusted.
                val suppressed = !hasContext && groupIsStatusReport(tokens, j)
                if (!suppressed) {
                    val toDep = pendingDep || (!hasContext && kind != AirportATIS.Kind.ARRIVAL)
                    val toArr = pendingArr || (!hasContext && kind != AirportATIS.Kind.DEPARTURE)
                    for (runway in collected) {
                        if (toDep) departures.add(runway)
                        if (toArr) arrivals.add(runway)
                    }
                }
                // Consume the context so a later group in the same clause re-derives its own.
                pendingDep = false
                pendingArr = false
            }
            i = j
        }
    }

    /**
     * Whether the tokens trailing a runway group — scanned up to the next
     * runway/operation keyword or the clause end — describe a component outage,
     * closure, or surface condition, meaning the runway was named for a status report
     * rather than because it is in use.
     */
    private fun groupIsStatusReport(tokens: List<String>, start: Int): Boolean {
        var k = start
        while (k < tokens.size) {
            val t = tokens[k]
            // A following runway or operation keyword starts a new group — the current
            // group's trailing context has ended without a status token.
            if (t in runwayKeywords || t in departureKeywords || t in arrivalKeywords) return false
            if (t in statusTokens) return true
            k++
        }
        return false
    }

    /**
     * Split a runway keyword written flush against its designator ("RY8R", "RWY22L")
     * into the two tokens the spaced form produces, so the scanner sees the runway
     * either way. Only a token whose letters are exactly a runway keyword *and* whose
     * tail is a valid ident is split — anything else (a fix name, a NOTAM word) is left
     * as it was.
     */
    private fun splitFlushRunwayKeyword(token: String): List<String> {
        val firstDigit = token.indexOfFirst { it.isDigit() }
        if (firstDigit <= 0) return listOf(token)
        val keyword = token.substring(0, firstDigit)
        val ident = token.substring(firstDigit)
        if (keyword !in runwayKeywords || runwayToken(ident) == null) return listOf(token)
        return listOf(keyword, ident)
    }

    /**
     * A single runway token ("24R", "8", "04L") in canonical form (leading zero
     * dropped), or null when the token isn't a runway ident in the 1…36 range.
     */
    private fun runwayToken(token: String): String? {
        val digits = StringBuilder()
        var suffix = ""
        for (ch in token.uppercase().trim()) {
            when {
                ch.isDigit() -> digits.append(ch)
                ch == 'L' || ch == 'R' || ch == 'C' -> suffix = ch.toString()
                else -> return null
            }
        }
        val n = digits.toString().toIntOrNull() ?: return null
        if (n !in 1..36) return null
        return "$n$suffix"
    }

    private fun dedup(xs: List<String>): List<String> = LinkedHashSet(xs).toList()

    // endregion
}
