package com.h3consultingpartners.ifatccompanion.core.ui

import com.h3consultingpartners.ifatccompanion.core.map.BaseImageryService
import com.h3consultingpartners.ifatccompanion.core.map.CoastlineData
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
    fun theBaseMapCreditsNameBothSourcesAndNeitherClaimsTheOther() {
        // Two sources with two different obligations. Natural Earth is public domain, so
        // its line is courtesy; NASA asks that GIBS be credited wherever its imagery
        // appears. Conflating them — or crediting NASA for the coastlines — would be wrong
        // in a way nobody would notice by looking at the map.
        assertTrue(LegalStrings.BaseMap.COASTLINE_ATTRIBUTION.contains("Natural Earth"))
        assertTrue(
            LegalStrings.BaseMap.COASTLINE_ATTRIBUTION.contains("public domain", ignoreCase = true),
        )
        assertTrue(!LegalStrings.BaseMap.COASTLINE_ATTRIBUTION.contains("NASA"))

        assertTrue(LegalStrings.BaseMap.IMAGERY_ATTRIBUTION.contains("NASA"))
        assertTrue(LegalStrings.BaseMap.IMAGERY_ATTRIBUTION_LONG.contains("NASA"))
        assertTrue(!LegalStrings.BaseMap.IMAGERY_ATTRIBUTION.contains("Natural Earth"))
    }

    @Test
    fun theCreditsShownOnTheMapAreTheOnesTheDataLayersCarry() {
        // The map draws from CoastlineData and BaseImageryService; Settings and this test
        // read LegalStrings. If those ever diverge the app would credit one source on the
        // map and a different wording everywhere else.
        assertEquals(LegalStrings.BaseMap.COASTLINE_ATTRIBUTION, CoastlineData.CREDIT)
        assertEquals(LegalStrings.BaseMap.IMAGERY_ATTRIBUTION_LONG, BaseImageryService.ATTRIBUTION)
    }

    @Test
    fun theDataSourcesSummaryNamesEverySourceItClaimsToCover() {
        // This one string is the app's own answer to "where does all this come from", so
        // adding a source without adding it here makes the summary quietly incomplete.
        val summary = LegalStrings.dataSourcesSummary()
        for (source in listOf(
            LegalStrings.OpenStreetMap.LICENSE_NAME,
            LegalStrings.CenterSectors.PROVIDER_NAME,
            LegalStrings.BaseMap.COASTLINE_PROVIDER,
            LegalStrings.BaseMap.IMAGERY_PROVIDER,
        )) {
            assertTrue(summary.contains(source), "the Data Sources summary never mentions $source")
        }
    }

    @Test
    fun losingTheImageryIsDescribedAsNormalRatherThanAsAFailure() {
        // The whole arrangement is that coastlines and the grid survive with no network.
        // If this text ever reads like an error, the arrangement has been misunderstood.
        val note = LegalStrings.BaseMap.OFFLINE_NOTE
        assertTrue(note.contains("no connection") || note.contains("need no connection"))
        for (alarming in listOf("error", "failed", "failure", "unavailable")) {
            assertTrue(
                !note.contains(alarming, ignoreCase = true),
                "the offline note calls a normal state a \"$alarming\"",
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
