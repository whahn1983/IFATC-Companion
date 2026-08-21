package com.h3consultingpartners.ifatccompanion.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Attribution that drifts is attribution that fails. OpenStreetMap's ODbL and VATSpy's
 * ShareAlike are obligations, not courtesies, so the wording is asserted rather than
 * trusted — these are the strings the iOS build ships, character for character.
 */
class LegalStringsTest {

    @Test
    fun openStreetMapAttributionIsExact() {
        assertEquals(
            "Surface data © OpenStreetMap contributors",
            LegalStrings.OpenStreetMap.ATTRIBUTION_TEXT,
        )
    }

    @Test
    fun openStreetMapIsLicensedUnderOdblNotCreativeCommons() {
        assertEquals(
            "Open Database License (ODbL) 1.0",
            LegalStrings.OpenStreetMap.LICENSE_NAME,
        )
        assertEquals("ODbL 1.0", LegalStrings.OpenStreetMap.LICENSE_SHORT_NAME)
        // The single most common mislabelling of OSM data, and the one the iOS source
        // calls out by name. It must never appear anywhere near the surface data.
        val surfaceText = listOf(
            LegalStrings.OpenStreetMap.LICENSE_NAME,
            LegalStrings.OpenStreetMap.LICENSE_SHORT_NAME,
            LegalStrings.OpenStreetMap.ATTRIBUTION_TEXT,
            LegalStrings.OpenStreetMap.NO_ENDORSEMENT,
            LegalStrings.dataSourcesSummary(),
            LegalStrings.DIAGNOSTICS_SURFACE_ATTRIBUTION,
        ).joinToString(" ")
        assertFalse(
            surfaceText.contains("CC BY 4.0"),
            "OpenStreetMap data must never be described as CC BY 4.0",
        )
    }

    @Test
    fun theAttributionLinksToOpenStreetMapsOwnCopyrightPage() {
        assertEquals(
            "https://www.openstreetmap.org/copyright",
            LegalStrings.OpenStreetMap.COPYRIGHT_URL,
        )
        assertEquals(
            "https://opendatacommons.org/licenses/odbl/1-0/",
            LegalStrings.OpenStreetMap.LICENSE_URL,
        )
    }

    @Test
    fun sectorDataCarriesItsShareAlikeAttribution() {
        assertEquals(
            "Sector boundaries © VATSIM VATSpy Data Project",
            LegalStrings.CenterSectors.ATTRIBUTION_TEXT,
        )
        assertEquals("CC BY-SA 4.0", LegalStrings.CenterSectors.LICENSE_SHORT_NAME)
        assertTrue(
            LegalStrings.dataSourcesSummary().contains(LegalStrings.CenterSectors.PROVIDER_NAME),
        )
    }

    @Test
    fun sectorFrequenciesAreDisclosedAsSimulated() {
        assertEquals(
            "Sector frequencies are simulated — real ARTCC/FIR sector frequencies are not " +
                "published as open data.",
            LegalStrings.CenterSectors.FREQUENCY_DISCLAIMER,
        )
    }

    @Test
    fun everySurfaceDisclaimerSaysSimulationOnly() {
        for (text in listOf(
            LegalStrings.OpenStreetMap.SIMULATION_DISCLAIMER,
            LegalStrings.TAXI_MAP_NOTE,
            LegalStrings.DIAGNOSTICS_SURFACE_ATTRIBUTION,
            LegalStrings.WEATHER_SIMULATION_ONLY,
        )) {
            assertTrue(
                text.contains("simulation", ignoreCase = true),
                "\"$text\" must say it is for simulation only",
            )
        }
    }

    @Test
    fun theNonAffiliationDisclaimerNamesTheDeveloperAndInfiniteFlight() {
        val text = LegalStrings.INFINITE_FLIGHT_DISCLAIMER
        assertTrue(text.contains("H3 Consulting Partners"))
        assertTrue(text.contains("not affiliated with"))
        assertTrue(text.contains("Infinite Flight"))
    }
}
