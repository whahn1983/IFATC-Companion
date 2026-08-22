package com.h3consultingpartners.ifatccompanion.core.phraseology

/**
 * Which radiotelephony pack the engine speaks.
 *
 * Ported from `IFATCCompanion/Settings/AppSettings.swift`. Swift declares this
 * next to the other user preferences, but it is a phraseology concept and every
 * consumer is in this package, so the Kotlin port keeps it here. The settings
 * layer should persist [rawValue] and reuse this type rather than redeclaring it.
 */
enum class PhraseologyMode(val rawValue: String) {
    FAA("faa"),
    ICAO("icao"),
    ;

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            FAA -> "FAA / US"
            ICAO -> "ICAO"
        }

    /** Short description of the pack's distinguishing conventions. */
    val detail: String
        get() = when (this) {
            FAA -> "US digits, \"point\" frequencies, inHg altimeter."
            ICAO -> "\"tree/fower/fife\" digits, \"decimal\" frequencies, QNH in hPa."
        }

    companion object {
        fun fromRawValue(raw: String): PhraseologyMode? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * How a flight number is spoken: 1234 as "twelve thirty four" or "one two three four".
 *
 * Ported from `IFATCCompanion/Settings/AppSettings.swift` — see the note on
 * [PhraseologyMode] about where this type lives.
 */
enum class CallsignDigitStyle(val rawValue: String) {
    /** 1234 -> "twelve thirty four" */
    GROUPED("grouped"),

    /** 1234 -> "one two three four" */
    INDIVIDUAL("individual"),
    ;

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            GROUPED -> "Grouped (twelve thirty four)"
            INDIVIDUAL -> "Individual (one two three four)"
        }

    companion object {
        fun fromRawValue(raw: String): CallsignDigitStyle? = entries.firstOrNull { it.rawValue == raw }
    }
}
