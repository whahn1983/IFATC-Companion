package com.h3consultingpartners.ifatccompanion.core.ui

import com.h3consultingpartners.ifatccompanion.core.config.AppConfig

/**
 * The legal, attribution and disclaimer text the app is obliged to show, verbatim from
 * the shipping iOS build.
 *
 * These live in Kotlin rather than `strings.xml` for one reason: a test can assert them
 * character for character (see `LegalStringsTest`). Attribution that drifts is
 * attribution that fails, and OpenStreetMap's ODbL and VATSpy's ShareAlike are both
 * obligations rather than courtesies — so the wording is pinned, not merely copied. The
 * app ships a single locale (en), matching iOS, so nothing is lost by not translating.
 */
object LegalStrings {

    /** Non-affiliation with Infinite Flight. */
    const val INFINITE_FLIGHT_DISCLAIMER =
        "IFATC Companion is an independent companion app developed by H3 Consulting " +
            "Partners and is not affiliated with, endorsed by, sponsored by, or approved " +
            "by Infinite Flight LLC."

    const val INFINITE_FLIGHT_REQUIRED =
        "Infinite Flight is sold separately and is required for Live Connected Mode."

    const val SIMULATION_ONLY =
        "All ATC, weather, taxi, navigation, and aviation information in IFATC Companion " +
            "is for flight simulation and entertainment only and must not be used for " +
            "real-world aviation."

    /** Shown on the subscription screen, above the products. */
    const val LIVE_CONNECTED_REQUIREMENT =
        "Live Connected Mode requires Infinite Flight, sold separately, running on " +
            "another device on the same local Wi-Fi network."

    /** Multiplayer etiquette, shown in Settings. */
    const val NOT_STAFFED_ATC =
        "IFATC Companion is **not** staffed ATC and must not impersonate live " +
            "controllers. Always yield to real controllers when a frequency is staffed."

    /** Shown under the ATC transcript. */
    const val ATC_SIMULATION_NOTE =
        "Radar-aware ATC simulation — training and entertainment only."

    // region OpenStreetMap — ODbL 1.0

    object OpenStreetMap {
        const val PROVIDER_NAME = "OpenStreetMap contributors"

        /** The licence OSM data is distributed under. It is **not** CC BY 4.0. */
        const val LICENSE_NAME = "Open Database License (ODbL) 1.0"
        const val LICENSE_SHORT_NAME = "ODbL 1.0"

        /**
         * The exact wording shown directly on the taxi map, in Settings, and in
         * diagnostics. Identical everywhere so attribution reads consistently, and
         * tappable wherever it appears — it links to [COPYRIGHT_URL].
         */
        const val ATTRIBUTION_TEXT = "Surface data © OpenStreetMap contributors"

        /**
         * The bare copyright line some compact contexts use. Prefer [ATTRIBUTION_TEXT]
         * where space allows.
         */
        const val ATTRIBUTION_SHORT = "© OpenStreetMap contributors"

        const val SIMULATION_DISCLAIMER =
            "Airport surface maps, taxi routes, and runway-crossing instructions are for " +
                "flight simulation only and must not be used for real-world aviation."

        const val NO_ENDORSEMENT =
            "OpenStreetMap® is open data licensed under the ODbL by the OpenStreetMap " +
                "Foundation. IFATC Companion is not endorsed by or affiliated with " +
                "OpenStreetMap or any Overpass operator."

        const val COPYRIGHT_URL = AppConfig.Links.OPENSTREETMAP_COPYRIGHT
        const val LICENSE_URL = AppConfig.Links.ODBL_LICENSE
        const val DOCUMENTATION_URL = AppConfig.Links.OPENSTREETMAP_LICENSING_DOC
    }

    // endregion

    // region En-route sectors — VATSpy, CC BY-SA 4.0

    object CenterSectors {
        const val PROVIDER_NAME = "VATSIM VATSpy Data Project"
        const val LICENSE_NAME = "Creative Commons Attribution-ShareAlike 4.0 International"
        const val LICENSE_SHORT_NAME = "CC BY-SA 4.0"
        const val ATTRIBUTION_TEXT = "Sector boundaries © VATSIM VATSpy Data Project"

        const val FREQUENCY_DISCLAIMER =
            "Sector frequencies are simulated — real ARTCC/FIR sector frequencies are not " +
                "published as open data."

        const val LICENSE_URL = AppConfig.Links.CC_BY_SA_4_0
        const val SOURCE_URL = AppConfig.Links.VATSPY_DATA_PROJECT
        const val DOCUMENTATION_URL = AppConfig.Links.CENTER_SECTORS_DOC
    }

    // endregion

    // region Weather

    const val WEATHER_SIMULATION_ONLY =
        "Radar, precipitation, and deviation logic are for simulation only and must not " +
            "be used for real-world aviation."

    const val WEATHER_NO_SUBSCRIPTION =
        "Training and entertainment use only. No paid weather subscription, API key, or " +
            "account is required."

    const val PRECIPITATION_SOURCES =
        "Precipitation overlay uses free sources: NOAA/NWS radar (U.S.), then a NASA " +
            "global satellite estimate everywhere else — including Europe — which is not " +
            "radar. No global radar coverage is implied. Simulation only — not for " +
            "real-world aviation. No paid subscription, API key, or account required."

    // endregion

    /** Shown under the taxi map. */
    const val TAXI_MAP_NOTE =
        "Simulation only — not for real-world aviation. OSM data may not match Infinite " +
            "Flight scenery."

    /** Shown in Diagnostics beneath the surface-data section. */
    const val DIAGNOSTICS_SURFACE_ATTRIBUTION =
        "Surface data © OpenStreetMap contributors — ODbL 1.0. Simulation only."

    /**
     * The Data Sources section's summary. Assembled the way the iOS Settings screen
     * assembles it, from the same constants, so the two cannot drift apart.
     */
    fun dataSourcesSummary(): String =
        "Airport surface geometry and taxi routes are derived from OpenStreetMap " +
            "(${OpenStreetMap.LICENSE_NAME}) via a public Overpass API. Community-sourced " +
            "and best-effort — cached locally (~75 days) and refreshed on demand. Not " +
            "authoritative and not guaranteed to match Infinite Flight scenery. En-route " +
            "Center sector boundaries are adapted from the ${CenterSectors.PROVIDER_NAME} " +
            "(${CenterSectors.LICENSE_SHORT_NAME}) and bundled with the app. " +
            "${CenterSectors.FREQUENCY_DISCLAIMER} Simulation only."

    /** The Data Sources section's legal footer, assembled as the iOS screen assembles it. */
    fun openStreetMapLegal(): String =
        "${OpenStreetMap.SIMULATION_DISCLAIMER}\n\n${OpenStreetMap.NO_ENDORSEMENT}"
}
