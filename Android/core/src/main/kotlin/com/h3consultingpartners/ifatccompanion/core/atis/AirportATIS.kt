package com.h3consultingpartners.ifatccompanion.core.atis

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A real-world ATIS (Automatic Terminal Information Service) broadcast for an
 * airport, as published by the FAA Digital ATIS (D-ATIS) feed.
 *
 * **Source.** D-ATIS is the FAA's own digital text of the spoken ATIS. This app
 * reads it from the free, public, keyless community endpoint at `datis.clowd.io`
 * (built and maintained by the vATIS project, sourced from the FAA SWIM system) —
 * the same "direct-to-public-service" pattern the app already uses for NOAA aviation
 * weather. Coverage is the set of US airports that publish D-ATIS (major fields); an
 * airport with no D-ATIS simply returns nothing, and the whole ATIS feature then
 * quietly disappears for that field (no button, no code appended anywhere). Nothing
 * here is ever fabricated — a missing airport means a missing feature, never an
 * invented ATIS.
 *
 * A field may publish a single **combined** ATIS or **separate** arrival and departure
 * ATIS, each with its own information letter, so a report holds one or more [Part]s.
 *
 * Ported from `IFATCCompanion/ATIS/AirportATIS.swift`.
 *
 * Serializable because a saved flight carries the reports already fetched, so the ATIS
 * card is populated on load rather than blank until the next refresh cycle. The [Kind]
 * raw values are the Swift's, so a persisted session means the same thing on both
 * platforms.
 */
@Serializable
data class AirportATIS(
    /** ICAO the ATIS is for (e.g. "KLAX"). */
    val airport: String,
    /** The published parts (one combined, or arrival + departure). */
    val parts: List<Part>,
    /** When the app fetched this report, in epoch milliseconds. */
    val fetchedAtMillis: Long,
) {

    /** Which operation an ATIS part applies to. */
    @Serializable
    enum class Kind(val rawValue: String) {
        @SerialName("combined") COMBINED("combined"),
        @SerialName("arrival") ARRIVAL("arrival"),
        @SerialName("departure") DEPARTURE("departure"),
        ;

        companion object {
            /** Map the D-ATIS `type` field ("arr" / "dep" / "combined") onto a [Kind]. */
            fun fromApiType(type: String): Kind = when (type.lowercase()) {
                "arr", "arrival" -> ARRIVAL
                "dep", "departure" -> DEPARTURE
                else -> COMBINED
            }
        }
    }

    /** A single ATIS part (a combined ATIS, or one of arrival / departure). */
    @Serializable
    data class Part(
        val kind: Kind,
        /**
         * The ATIS information code letter, uppercased single character ("A"…"Z"), or
         * empty when the feed didn't supply a recognizable code.
         */
        val letter: String,
        /** The raw D-ATIS text exactly as published (kept verbatim for display). */
        val text: String,
    )

    // region Access

    /**
     * The ATIS part relevant to a phase of flight: the arrival part on arrival, the
     * departure part on departure — each falling back to a combined ATIS, then any
     * part, so a field that publishes only one still resolves.
     */
    fun part(arrival: Boolean): Part? {
        val preferred = if (arrival) Kind.ARRIVAL else Kind.DEPARTURE
        return parts.firstOrNull { it.kind == preferred }
            ?: parts.firstOrNull { it.kind == Kind.COMBINED }
            ?: parts.firstOrNull()
    }

    /**
     * The information code letter for a phase, uppercased ("A"), or null when the
     * relevant part carries no recognizable single-letter code.
     */
    fun letter(arrival: Boolean): String? {
        val raw = part(arrival)?.letter?.trim() ?: return null
        if (raw.length != 1 || !raw[0].isLetter()) return null
        return raw.uppercase()
    }

    // endregion
}

/**
 * A read-only snapshot of ATIS state for the Diagnostics tab: which fields ATIS was
 * requested for, whether it was received, and the information code carried / reported.
 */
data class ATISDiagnostics(
    val departureAirport: String = "",
    val departureReceived: Boolean = false,
    val departureLetter: String? = null,
    val arrivalAirport: String = "",
    val arrivalReceived: Boolean = false,
    val arrivalLetter: String? = null,
    /** Whether the aircraft is within the arrival-ATIS range (100 NM of destination). */
    val withinArrivalRange: Boolean = false,
    /**
     * The information code the pilot has actually received (by tuning) and will report
     * to ATC, per phase. Null until the pilot tunes ATIS for that phase.
     */
    val reportedDeparture: String? = null,
    val reportedArrival: String? = null,
)
