package com.h3consultingpartners.ifatccompanion.core.surface

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A conservative aircraft size classification used to bias taxi routing away from
 * paths unsuitable for the aircraft (narrow taxilanes, tight turns) when OSM tags
 * carry enough information. Infinite Flight aircraft info is used when available;
 * otherwise the aircraft is classified conservatively by size, defaulting to [MEDIUM].
 *
 * Ported from `IFATCCompanion/AirportSurface/AircraftSizeClass.swift`.
 */
@Serializable
enum class AircraftSizeClass(val rawValue: String) {
    @SerialName("light") LIGHT("light"),      // GA singles/twins
    @SerialName("small") SMALL("small"),      // regional jets, turboprops
    @SerialName("medium") MEDIUM("medium"),   // A320/737 family
    @SerialName("large") LARGE("large"),      // 757/767/A330
    @SerialName("heavy") HEAVY("heavy"),      // 777/747/A350/A380
    ;

    val title: String
        get() = when (this) {
            LIGHT -> "Light"
            SMALL -> "Small"
            MEDIUM -> "Medium"
            LARGE -> "Large"
            HEAVY -> "Heavy"
        }

    /**
     * Ordering rank, smallest airframe to largest. Used where one class has to be compared
     * with another — e.g. deciding whether a stand sized for an A320 can take the aircraft.
     */
    val rank: Int
        get() = when (this) {
            LIGHT -> 0
            SMALL -> 1
            MEDIUM -> 2
            LARGE -> 3
            HEAVY -> 4
        }

    /**
     * Approximate minimum taxiway width (meters) the class is comfortable on. Used
     * only when OSM tags a taxiway/taxilane width; unknown widths never penalize.
     */
    val minComfortableTaxiwayWidthMeters: Double
        get() = when (this) {
            LIGHT -> 7.5
            SMALL -> 15.0
            MEDIUM -> 18.0
            LARGE -> 23.0
            HEAVY -> 30.0
        }

    /** Whether taxilanes (apron lead-in lanes) are generally acceptable for the class. */
    val acceptsTaxilanes: Boolean
        get() = when (this) {
            LIGHT, SMALL, MEDIUM -> true
            LARGE, HEAVY -> false
        }

    companion object {
        /**
         * Best-effort classification from an Infinite Flight aircraft name. Conservative:
         * anything unrecognised is [MEDIUM].
         */
        fun classify(aircraftName: String?): AircraftSizeClass =
            classifyStrict(aircraftName) ?: MEDIUM

        /**
         * The same classification as [classify], but it reports "not recognised" instead of
         * defaulting to [MEDIUM]. Used where an unknown must stay unknown — an OSM
         * `aircraft:type` token that matches no airframe says nothing about how big the stand
         * is, and reading it as a 737 stand would invent data that isn't in the extract.
         */
        fun classifyStrict(aircraftName: String?): AircraftSizeClass? {
            val raw = aircraftName?.uppercase()
            if (raw.isNullOrEmpty()) return null
            val n = raw.replace("-", "").replace(" ", "")

            // Heavy widebodies.
            for (token in listOf("747", "748", "777", "77W", "A380", "A388", "A350", "A359", "A35", "MD11", "AN12", "AN22", "C17", "C5")) {
                if (n.contains(token)) return HEAVY
            }
            // Large widebodies.
            for (token in listOf("767", "757", "A330", "A339", "A340", "A300", "A310", "787", "78", "DC10", "L101")) {
                if (n.contains(token)) return LARGE
            }
            // Medium narrowbodies.
            for (token in listOf("737", "738", "739", "73", "A320", "A319", "A321", "A318", "A32", "757200", "MD80", "MD90", "717", "727", "B52")) {
                if (n.contains(token)) return MEDIUM
            }
            // Small regionals / turboprops.
            for (token in listOf("CRJ", "E170", "E175", "E190", "E195", "EMB", "ERJ", "DASH", "DH8", "Q400", "ATR", "SF34", "SAAB", "A220", "BCS", "F50", "F70", "F100")) {
                if (n.contains(token)) return SMALL
            }
            // Light GA.
            for (token in listOf("C172", "C152", "CESSNA", "SR22", "TBM", "PIPER", "PA28", "DA40", "DA42", "SPITFIRE", "XCUB", "CUB")) {
                if (n.contains(token)) return LIGHT
            }
            return null
        }
    }
}
