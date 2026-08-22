package com.h3consultingpartners.ifatccompanion.core.airports

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How an airport's ramp/apron area is controlled. Ramp control is NOT FAA ATC —
 * it is a local airport, airline, or company procedure. These styles only change
 * how the *simulated* ramp conversation reads; none of them grant runway,
 * movement-area, route, altitude, heading, or approach authority.
 *
 * Ported from `IFATCCompanion/ATC/RampProfile.swift`. Raw values match the Swift
 * `String` raw values exactly.
 */
@Serializable
enum class RampType(val rawValue: String) {
    /** A dedicated ramp controller (e.g. ATL, ORD non-movement ramp towers). */
    @SerialName("rampControl") RAMP_CONTROL("rampControl"),

    /** European-style apron control. */
    @SerialName("apronControl") APRON_CONTROL("apronControl"),

    /** Airline/company ramp coordinator (most US hubs). */
    @SerialName("companyRamp") COMPANY_RAMP("companyRamp"),

    /** Unstaffed — advisory/CTAF-style ramp self-announce only. */
    @SerialName("advisoryOnly") ADVISORY_ONLY("advisoryOnly"),

    /** No ramp layer; the pilot contacts Ground directly. */
    @SerialName("none") NONE("none"),
    ;

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            RAMP_CONTROL -> "Ramp Control"
            APRON_CONTROL -> "Apron Control"
            COMPANY_RAMP -> "Company Ramp"
            ADVISORY_ONLY -> "Advisory Only"
            NONE -> "No Ramp"
        }

    /** Whether this style speaks "face <dir>" instead of "tail <dir>" for pushes. */
    val usesFaceDirection: Boolean get() = this == APRON_CONTROL

    companion object {
        fun fromRawValue(rawValue: String): RampType? =
            entries.firstOrNull { it.rawValue == rawValue }
    }
}

/**
 * Per-airport ramp behavior so the simulated ramp conversation can vary without
 * code changes. When no airport profile exists, [RampProfile.generic] is used.
 *
 * IMPORTANT: every string here is *local/simulated ramp phraseology*, documented
 * as non-FAA. Ramp must never issue runway, takeoff, landing, crossing, IFR
 * route, altitude, heading, SID, STAR, or approach instructions.
 */
@Serializable
data class RampProfile(
    /** "" for the generic profile. */
    val airportICAO: String,
    /** Spoken position name, e.g. "Ramp". */
    val rampName: String,
    /** Simulated ramp frequency (MHz). */
    val rampFrequency: Double,
    val rampType: RampType,
    val requiresPushApproval: Boolean,
    val requiresEngineStartCoordination: Boolean,
    val usesSpots: Boolean,
    val defaultSpotNames: List<String>,
    /** "west", "east", "north", "south". */
    val defaultPushDirections: List<String>,
    /** Free-text note, e.g. "letter+number (B44)". */
    val defaultGateNamingStyle: String,
    /** Template for the Ramp→Ground handoff. `{freq}`/`{spot}` placeholders allowed. */
    val handoffToGroundPhrase: String,
    /** Template for the arrival ramp entry. `{gate}`/`{alley}` placeholders allowed. */
    val arrivalRampEntryPhrase: String,
    val notes: String,
    val reviewStatus: String,
) {

    val id: String get() = if (airportICAO.isEmpty()) "generic" else airportICAO

    companion object {

        /**
         * Generic US airline ramp profile used when no airport-specific profile is
         * known. Conservative: requires push approval, uses tail directions, hands
         * off to Ground at a generic spot/movement-area boundary.
         */
        val generic = RampProfile(
            airportICAO = "",
            rampName = "Ramp",
            rampFrequency = 131.0,
            rampType = RampType.COMPANY_RAMP,
            requiresPushApproval = true,
            requiresEngineStartCoordination = false,
            usesSpots = true,
            defaultSpotNames = emptyList(),
            defaultPushDirections = emptyList(),
            defaultGateNamingStyle = "as entered",
            handoffToGroundPhrase = "contact Ground {freq}",
            arrivalRampEntryPhrase = "proceed to the gate via the ramp",
            notes = "Generic simulated airline ramp. Not FAA ATC. No precise spots are " +
                "invented unless an airport-specific profile is supplied.",
            reviewStatus = "simulated",
        )

        /**
         * Built-in airport ramp profiles. Intentionally small — most airports use the
         * generic profile. Entries here are documented as simulated/best-effort and
         * flagged for airport-specific validation.
         */
        val known: Map<String, RampProfile> = mapOf(
            // KATL — dedicated ramp towers, spots, tail directions. Simulated.
            "KATL" to RampProfile(
                airportICAO = "KATL",
                rampName = "Ramp",
                rampFrequency = 129.625,
                rampType = RampType.RAMP_CONTROL,
                requiresPushApproval = true,
                requiresEngineStartCoordination = false,
                usesSpots = true,
                defaultSpotNames = listOf("1", "2", "3", "4", "5"),
                defaultPushDirections = listOf("north", "south"),
                defaultGateNamingStyle = "concourse+number (T1, A12)",
                handoffToGroundPhrase = "monitor Ground {freq} at spot {spot}",
                arrivalRampEntryPhrase = "proceed to the gate via the ramp",
                notes = "ATL uses ramp towers and spots. Spot numbers/frequencies are " +
                    "illustrative only.",
                reviewStatus = "airportSpecific-needsReview",
            ),
            // KORD — apron-style alleys and spots. Simulated.
            "KORD" to RampProfile(
                airportICAO = "KORD",
                rampName = "Ramp",
                rampFrequency = 129.6,
                rampType = RampType.RAMP_CONTROL,
                requiresPushApproval = true,
                requiresEngineStartCoordination = false,
                usesSpots = true,
                defaultSpotNames = listOf("5", "7", "9"),
                defaultPushDirections = listOf("east", "west"),
                defaultGateNamingStyle = "concourse+number (B12)",
                handoffToGroundPhrase = "contact Ground {freq} at spot {spot}",
                arrivalRampEntryPhrase = "proceed to the gate via the inner alley",
                notes = "ORD ramp/alley layout. Spots/frequency illustrative only.",
                reviewStatus = "airportSpecific-needsReview",
            ),
        )

        /** Resolve the ramp profile for an airport, falling back to the generic one. */
        fun profile(icao: String): RampProfile = known[icao.uppercase()] ?: generic
    }
}
