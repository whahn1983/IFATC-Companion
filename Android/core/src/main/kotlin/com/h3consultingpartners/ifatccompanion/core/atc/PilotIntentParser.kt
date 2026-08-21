package com.h3consultingpartners.ifatccompanion.core.atc

/**
 * A pilot intent recognised from spoken (or typed) input. Maps to an existing pilot
 * action in the flight session. Deterministic keyword matching — no AI/LLM.
 *
 * Ported from `IFATCCompanion/ATC/PilotIntentParser.swift`.
 */
enum class PilotIntent(val rawValue: String) {
    READBACK("readback"),
    SAY_AGAIN("sayAgain"),
    UNABLE("unable"),
    WILCO("wilco"),
    REQUEST_CLEARANCE("requestClearance"),
    REQUEST_PUSHBACK("requestPushback"),
    REQUEST_ENGINE_START("requestEngineStart"),
    REQUEST_TAXI("requestTaxi"),
    READY_FOR_DEPARTURE("readyForDeparture"),
    REQUEST_TAKEOFF("requestTakeoff"),
    REQUEST_HIGHER("requestHigher"),
    REQUEST_LOWER("requestLower"),
    REQUEST_VECTORS("requestVectors"),
    REQUEST_APPROACH("requestApproach"),
    RIDE_REPORT("rideReport"),
    DESTINATION_WEATHER("destinationWeather"),
    CHECK_IN("checkIn"),
    UNKNOWN("unknown"),
    ;

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            READBACK -> "Read Back"
            SAY_AGAIN -> "Say Again"
            UNABLE -> "Unable"
            WILCO -> "Wilco"
            REQUEST_CLEARANCE -> "Request Clearance"
            REQUEST_PUSHBACK -> "Request Pushback"
            REQUEST_ENGINE_START -> "Request Engine Start"
            REQUEST_TAXI -> "Request Taxi"
            READY_FOR_DEPARTURE -> "Ready for Departure"
            REQUEST_TAKEOFF -> "Request Takeoff"
            REQUEST_HIGHER -> "Request Higher"
            REQUEST_LOWER -> "Request Lower"
            REQUEST_VECTORS -> "Request Vectors"
            REQUEST_APPROACH -> "Request Approach"
            RIDE_REPORT -> "Ride Report"
            DESTINATION_WEATHER -> "Destination Weather"
            CHECK_IN -> "Check In"
            UNKNOWN -> "Unrecognized"
        }

    companion object {
        fun fromRawValue(raw: String): PilotIntent? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * Deterministically maps a recognised phrase to a [PilotIntent] using ordered keyword
 * rules. **Order matters**: more specific phrases are checked first, and the read-back
 * catch-all is last because it also matches words ("taxi", "runway") that appear in the
 * more specific requests above it.
 */
class PilotIntentParser {

    fun parse(text: String): PilotIntent {
        val t = " " + text.lowercase().trim().replace("-", " ") + " "

        fun has(vararg needles: String): Boolean =
            needles.any { t.contains(" $it ") || t.contains(" $it") }

        // Looser contains for multi-word phrases.
        fun contains(vararg phrases: String): Boolean = phrases.any { t.contains(it) }

        if (contains("say again", "repeat that", "repeat last")) return PilotIntent.SAY_AGAIN
        if (contains("unable")) return PilotIntent.UNABLE

        // Departure ground flow (checked before the readback catch-all, which also
        // matches "taxi"/"runway").
        if (contains("request pushback", "request push back", "ready for push", "pushback", "push back")) {
            return PilotIntent.REQUEST_PUSHBACK
        }
        if (contains("request start", "request engine start", "engine start", "start up", "startup", "ready to start")) {
            return PilotIntent.REQUEST_ENGINE_START
        }
        if (contains("request clearance", "ifr clearance", "request ifr")) {
            return PilotIntent.REQUEST_CLEARANCE
        }
        if (contains("request taxi", "ready to taxi", "ready for taxi")) {
            return PilotIntent.REQUEST_TAXI
        }
        if (contains("request takeoff", "request take off", "request departure")) {
            return PilotIntent.REQUEST_TAKEOFF
        }
        if (contains(
                "ready for departure", "ready for takeoff", "ready for take off",
                "holding short", "line up and wait", "lining up",
            )
        ) {
            return PilotIntent.READY_FOR_DEPARTURE
        }
        if (contains("ride report", "ride reports", "turbulence report", "any chop", "ride along")) {
            return PilotIntent.RIDE_REPORT
        }
        if (contains("destination weather", "field conditions", "weather at", "atis")) {
            return PilotIntent.DESTINATION_WEATHER
        }
        if (contains("vectors", "vector us", "vector me")) return PilotIntent.REQUEST_VECTORS
        if (contains(
                "request approach", "cleared approach", "the approach", "ils approach",
                "rnav approach", "visual approach",
            )
        ) {
            return PilotIntent.REQUEST_APPROACH
        }
        if (contains("request higher", "higher", "climb to", "request climb", "request flight level")) {
            return PilotIntent.REQUEST_HIGHER
        }
        if (contains("request lower", "lower", "descend to", "request descent", "down to")) {
            return PilotIntent.REQUEST_LOWER
        }
        if (contains("check in", "checking in", "with you", "good day")) return PilotIntent.CHECK_IN
        if (contains("wilco")) return PilotIntent.WILCO
        // A read-back is the catch-all for acknowledgements / clearances repeated.
        if (contains(
                "read back", "readback", "roger", "copy", "cleared", "maintain",
                "taxi", "runway", "squawk", "contact", "wind",
            ) || has("affirm", "affirmative")
        ) {
            return PilotIntent.READBACK
        }
        return PilotIntent.UNKNOWN
    }
}
