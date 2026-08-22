package com.h3consultingpartners.ifatccompanion.core.surface

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.platform.InMemoryFileStore
import java.net.URI
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Licensing & attribution: OpenStreetMap must be identified as ODbL 1.0 (never CC BY
 * 4.0), attribution must be present and linked, cached data must retain source/license
 * metadata, and no unsupported data source may be used.
 *
 * Ported from `IFATCCompanionTests/AirportSurfaceLicensingTests.swift`.
 */
class AirportSurfaceLicensingTest {

    @Test
    fun osmIsIdentifiedAsODbLNotCCBY() {
        assertTrue(OSMSurface.LICENSE_NAME.contains("ODbL"))
        assertTrue(OSMSurface.LICENSE_SHORT_NAME.contains("ODbL"))
        assertFalse(
            OSMSurface.LICENSE_NAME.uppercase().contains("CC BY"),
            "OSM data is ODbL, not CC BY 4.0",
        )
        assertFalse(OSMSurface.LICENSE_NAME.uppercase().contains("CREATIVE COMMONS"))
    }

    @Test
    fun visibleAttributionWording() {
        assertEquals("Surface data © OpenStreetMap contributors", OSMSurface.ATTRIBUTION_TEXT)
        assertTrue(OSMSurface.ATTRIBUTION_SHORT.contains("OpenStreetMap contributors"))
        assertEquals("OpenStreetMap contributors", OSMSurface.PROVIDER_NAME)
    }

    @Test
    fun attributionLinkIsTheOSMCopyrightPage() {
        assertEquals("https://www.openstreetmap.org/copyright", OSMSurface.COPYRIGHT_URL)
        assertEquals("https", URI(OSMSurface.COPYRIGHT_URL).scheme)
        assertEquals("opendatacommons.org", URI(OSMSurface.ODBL_LICENSE_URL).host)
        assertEquals("https", URI(OSMSurface.PUBLIC_DOCUMENTATION_URL).scheme)
    }

    @Test
    fun userAgentIdentifiesAppAndPublisher() {
        assertTrue(OSMSurface.userAgent.contains("IFATCCompanion"))
        assertTrue(OSMSurface.userAgent.contains("H3 Consulting Partners"))
    }

    @Test
    fun onlyOverpassOSMEndpointsAreUsed() {
        assertFalse(OSMSurface.OVERPASS_ENDPOINTS.isEmpty())
        for (endpoint in OSMSurface.OVERPASS_ENDPOINTS) {
            assertTrue(
                endpoint.contains("overpass"),
                "the only airport-surface data service is OSM/Overpass: $endpoint",
            )
        }
        assertTrue(OSMSurface.PRIMARY_OVERPASS_ENDPOINT.contains("overpass"))
    }

    @Test
    fun normalizedSurfaceRetainsSourceAndLicenseMetadata() {
        val model = MockAirportSurface.model(
            icao = "KTST",
            reference = Coordinate(40.0, -75.0),
            primaryRunwayIdent = "36",
            gate = "A1",
        )
        assertEquals(OSMSurface.PROVIDER_NAME, model.source.provider)
        assertEquals(OSMSurface.LICENSE_NAME, model.source.license)
        assertEquals(OSMSurface.ATTRIBUTION_TEXT, model.source.attribution)
        // Original OSM identifiers and tags are retained through normalization.
        assertTrue(model.runways.firstOrNull()?.osmID?.contains("mock-rwy") ?: false)
        assertFalse(model.runways.firstOrNull()?.tags?.isEmpty() ?: true)
        assertEquals("runway", model.runways.firstOrNull()?.tags?.get("aeroway"))
    }

    @Test
    fun cacheRoundTripPreservesLicenseAndOSMTags() {
        val cache = AirportSurfaceCache(
            InMemoryFileStore(),
            namespace = "osm-test-cache-${UUID.randomUUID()}",
        )
        try {
            val model = MockAirportSurface.model(
                icao = "ZZZZ",
                reference = Coordinate(40.0, -75.0),
                primaryRunwayIdent = "36",
                gate = "A1",
            )
            assertTrue(cache.save(model))
            val loaded = cache.load("ZZZZ")
            assertNotNull(loaded)
            assertEquals(OSMSurface.LICENSE_NAME, loaded.source.license)
            assertEquals(OSMSurface.ATTRIBUTION_TEXT, loaded.source.attribution)
            assertEquals("runway", loaded.runways.firstOrNull()?.tags?.get("aeroway"))
            assertEquals("ZZZZ", loaded.icao)
            assertTrue(cache.cachedICAOs().contains("ZZZZ"))
        } finally {
            cache.deleteAll()
        }
    }

    /**
     * PARITY NOTE: iOS drives this through `AirportSurfaceCoordinator.beginMockTaxiForTesting`
     * and `diagnosticsSnapshot()`. The coordinator belongs to the taxi agent, so the snapshot is
     * built here directly from the same mock surface — what the test actually guards is that the
     * snapshot always carries the ODbL attribution, whoever assembled it.
     */
    @Test
    fun diagnosticsSnapshotCarriesAttributionAndLicense() {
        val surface = MockAirportSurface.model(
            icao = "KTST",
            reference = Coordinate(40.0, -75.0),
            primaryRunwayIdent = "36",
            gate = "A1",
        )
        val d = AirportSurfaceDiagnostics.from(
            surface = surface,
            graph = null,
            route = null,
            statusText = AirportSurfaceStatusText.READY,
            datasetConfidence = surface.confidence,
            routeConfidence = SurfaceConfidence.UNAVAILABLE,
            crossingStateTitle = "No crossing pending",
            crossingStateAuthorized = false,
            activeCrossing = null,
            awaitingCrossingReadback = false,
            authorizedCrossingIndex = null,
            snappedSegment = "—",
            lastError = null,
        )
        assertEquals(OSMSurface.PROVIDER_NAME, d.sourceProvider)
        assertTrue(d.license.contains("ODbL"))
        assertEquals(OSMSurface.ATTRIBUTION_TEXT, d.attribution)
        assertTrue(d.exportText().contains("OpenStreetMap contributors"))
        assertTrue(d.exportText().contains("ODbL"))
    }
}
