package com.h3consultingpartners.ifatccompanion.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The simulated controller sector currently working the aircraft.
 *
 * Ported from `IFATCCompanion/Models/ATCFacility.swift`. Raw values match the Swift
 * `String` raw values exactly so persisted sessions and saved flights use the same
 * vocabulary on both platforms.
 */
@Serializable
enum class ATCFacility(val rawValue: String) {
    @SerialName("clearance") CLEARANCE("clearance"),
    @SerialName("ramp") RAMP("ramp"),
    @SerialName("ground") GROUND("ground"),
    @SerialName("tower") TOWER("tower"),
    @SerialName("departure") DEPARTURE("departure"),
    @SerialName("center") CENTER("center"),
    @SerialName("approach") APPROACH("approach"),
    ;

    val id: String get() = rawValue

    /**
     * Whether this facility is FAA air traffic control. Ramp is a simulated
     * local/airline/company procedure, NOT FAA ATC, and is excluded.
     */
    val isFAAATC: Boolean
        get() = when (this) {
            CLEARANCE, GROUND, TOWER, DEPARTURE, CENTER, APPROACH -> true
            RAMP -> false
        }

    val title: String
        get() = when (this) {
            CLEARANCE -> "Clearance"
            RAMP -> "Ramp"
            GROUND -> "Ground"
            TOWER -> "Tower"
            DEPARTURE -> "Departure"
            CENTER -> "Center"
            APPROACH -> "Approach"
        }

    /** Spoken position name used in handoffs / call-ins. */
    val spokenName: String
        get() = when (this) {
            CLEARANCE -> "Clearance Delivery"
            RAMP -> "Ramp"
            GROUND -> "Ground"
            TOWER -> "Tower"
            DEPARTURE -> "Departure"
            CENTER -> "Center"
            APPROACH -> "Approach"
        }

    /**
     * Semantic icon key for the status chip. iOS names an SF Symbol here; Android
     * cannot use SF Symbols, so the engine stays platform-neutral and the Compose
     * layer maps each key to the closest Material Symbol. The mapping is recorded in
     * Docs/ANDROID_PARITY_MATRIX.md.
     */
    val iconKey: String
        get() = when (this) {
            CLEARANCE -> "description"      // iOS: doc.text
            RAMP -> "local_parking"         // iOS: parkingsign
            GROUND -> "directions_car"      // iOS: car
            TOWER -> "apartment"            // iOS: building.2
            DEPARTURE -> "flight_takeoff"   // iOS: airplane.departure
            CENTER -> "public"              // iOS: globe.americas
            APPROACH -> "flight_land"       // iOS: airplane.arrival
        }

    companion object {
        /**
         * Best-effort map from an Infinite Flight ATC facility name (e.g. "Ground",
         * "KSFO Tower", "Approach", "Clearance Delivery") to the matching facility.
         * Returns null for names that don't correspond to a gate-to-gate FAA position
         * (UNICOM, ATIS, …) or that can't be recognised. Matching is token-based and
         * case-insensitive, checking the more specific words first so "Clearance
         * Delivery" and "Ground Control" resolve unambiguously.
         */
        fun matching(name: String?): ATCFacility? {
            val raw = name?.uppercase()?.trim() ?: return null
            if (raw.isEmpty()) return null
            if (raw.contains("CLEARANCE") || raw.contains("DELIVERY")) return CLEARANCE
            if (raw.contains("GROUND")) return GROUND
            if (raw.contains("TOWER")) return TOWER
            if (raw.contains("DEPART")) return DEPARTURE
            if (raw.contains("APPROACH") || raw.contains("ARRIVAL")) return APPROACH
            if (raw.contains("CENTER") || raw.contains("CENTRE")) return CENTER
            return null
        }

        fun fromRawValue(raw: String): ATCFacility? = entries.firstOrNull { it.rawValue == raw }
    }
}
