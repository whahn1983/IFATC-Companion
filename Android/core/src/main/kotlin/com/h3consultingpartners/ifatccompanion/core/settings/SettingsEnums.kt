package com.h3consultingpartners.ifatccompanion.core.settings

/**
 * How the simulated weather-deviation alerts behave. Purely a UI/prompting
 * preference — it never changes the underlying data sources.
 *
 * Ported from `IFATCCompanion/Settings/AppSettings.swift`.
 */
enum class WeatherDeviationAlertMode(val rawValue: String) {
    OFF("off"),
    ADVISORY_ONLY("advisoryOnly"),
    ADVISORY_PLUS_DEVIATION("advisoryPlusDeviation"),
    ;

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            OFF -> "Off"
            ADVISORY_ONLY -> "Advisory only"
            ADVISORY_PLUS_DEVIATION -> "Advisory + suggested deviation"
        }

    /** Whether any weather advisory/banner should be surfaced at all. */
    val alertsEnabled: Boolean get() = this != OFF

    /** Whether a suggested deviation (degrees/side) should accompany the advisory. */
    val suggestsDeviation: Boolean get() = this == ADVISORY_PLUS_DEVIATION

    companion object {
        fun fromRawValue(raw: String): WeatherDeviationAlertMode? =
            entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * The NOAA radar overlay preference: shown automatically where NOAA provides
 * coverage, or off. No third option — this app never selects a global/commercial
 * radar provider.
 *
 * Ported from `IFATCCompanion/Settings/AppSettings.swift`.
 */
enum class NOAARadarOverlayMode(val rawValue: String) {
    AUTO_WHERE_AVAILABLE("autoWhereAvailable"),
    OFF("off"),
    ;

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            AUTO_WHERE_AVAILABLE -> "Auto where available"
            OFF -> "Off"
        }

    companion object {
        fun fromRawValue(raw: String): NOAARadarOverlayMode? =
            entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * How busy the ambient background radio-chatter frequency sounds. Controls the
 * gap between simulated transmissions (shorter gaps = busier sector).
 *
 * Ported from `IFATCCompanion/Settings/AppSettings.swift`.
 */
enum class ChatterDensity(val rawValue: String) {
    LIGHT("light"),
    MODERATE("moderate"),
    BUSY("busy"),
    ;

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            LIGHT -> "Light"
            MODERATE -> "Moderate"
            BUSY -> "Busy"
        }

    /**
     * Random gap (seconds) between the end of one transmission and the start of the
     * next, as a closed range sampled uniformly. Swift expresses this as a
     * `ClosedRange<Double>`; Kotlin's `ClosedFloatingPointRange<Double>` is the same
     * inclusive interval.
     */
    val gapRange: ClosedFloatingPointRange<Double>
        get() = when (this) {
            LIGHT -> 9.0..22.0
            MODERATE -> 5.0..14.0
            BUSY -> 2.0..7.0
        }

    companion object {
        fun fromRawValue(raw: String): ChatterDensity? = entries.firstOrNull { it.rawValue == raw }
    }
}
