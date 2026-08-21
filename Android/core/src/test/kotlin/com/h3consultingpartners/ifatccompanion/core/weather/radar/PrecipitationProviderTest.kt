package com.h3consultingpartners.ifatccompanion.core.weather.radar

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.net.HttpFetching
import com.h3consultingpartners.ifatccompanion.core.net.HttpResponse
import com.h3consultingpartners.ifatccompanion.core.net.HttpResult
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.HazardConfidence
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.RadarBoundingBox
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage, labelling, and attribution for the three precipitation providers (NOAA radar,
 * EUMETNET OPERA radar, NASA GIBS satellite estimate), plus the selection order between
 * them and the Web-Mercator registration of the bounding box they are requested for.
 *
 * Ported from `IFATCCompanionTests/PrecipitationProviderTests.swift`. The
 * `PrecipitationSourceFollowsRouteTests` case there drives `AppModel` end to end; the
 * region it selects from belongs to the flight coordinator, not this package, so those
 * three tests live with it.
 */
class PrecipitationProviderTest {

    private val http: HttpFetching = NeverCalledHttp
    private val clock = MutableClock(0)

    private fun region(lat: Double, lon: Double) =
        MapRegion(centerLatitude = lat, centerLongitude = lon, latitudeDelta = 2.0, longitudeDelta = 2.0)

    // region Provider metadata

    @Test
    fun noaaProviderMetadata() {
        val p = NOAARadarPrecipitationProvider(http, clock)
        assertTrue(p.supportsTrueRadar)
        assertEquals(PrecipitationLayerType.RADAR, p.layerType)
        assertEquals("Radar precipitation", p.uiLayerLabel)
        assertNotNull(p.attributionText)
    }

    @Test
    fun operaProviderMetadata() {
        val p = EUMETNETOPERARadarProvider(http, clock)
        assertTrue(p.supportsTrueRadar)
        assertEquals(PrecipitationLayerType.RADAR, p.layerType)
        assertEquals("Radar precipitation", p.uiLayerLabel)
        // CC BY 4.0 attribution is honoured, and credits the CIRRUS composite.
        assertTrue(p.attributionText?.contains("CC BY 4.0") ?: false)
        assertTrue(p.attributionText?.contains("CIRRUS") ?: false)
        // Product preference order: max reflectivity → rain rate → 1h accumulation.
        assertEquals(
            EUMETNETOPERARadarProvider.Product.MAXIMUM_REFLECTIVITY,
            EUMETNETOPERARadarProvider.preferredProducts.first(),
        )
        // Cloud-optimized GeoTIFF is preferred over HDF5.
        assertTrue(EUMETNETOPERARadarProvider.preferredFormats.first().contains("geotiff"))
        assertTrue(EUMETNETOPERARadarProvider.preferredFormats.contains("odim-hdf5"))
    }

    @Test
    fun operaCanRenderReflectsAvailableSource() {
        val paris = region(48.85, 2.35)
        // The type default renders from the anonymous ORD composite.
        assertTrue(EUMETNETOPERARadarProvider(http, clock).canRenderOverlay(paris))
        // A configured WMS endpoint also counts as a renderable source.
        assertTrue(
            EUMETNETOPERARadarProvider(http, clock, wmsBaseUrl = "https://example.org/wms", useORD = false)
                .canRenderOverlay(paris),
        )
        // With neither ORD nor a WMS endpoint it can't render → must not claim coverage.
        assertFalse(EUMETNETOPERARadarProvider(http, clock, useORD = false).canRenderOverlay(paris))
        // Even with a source, it never claims to render outside its coverage box (U.S.).
        assertFalse(EUMETNETOPERARadarProvider(http, clock).canRenderOverlay(region(39.0, -98.0)))
    }

    @Test
    fun operaCoverageIsEuropeNotUS() {
        assertTrue(EUMETNETOPERARadarProvider.covers(Coordinate(48.85, 2.35)))
        assertFalse(
            EUMETNETOPERARadarProvider.covers(Coordinate(39.0, -98.0)),
            "OPERA must not claim U.S. coverage",
        )
    }

    @Test
    fun operaHasNoSynchronousWMSURLWithoutEndpoint() {
        // The direct-URL path is WMS-only. Without a configured WMS endpoint it returns null
        // (the ORD composite is rendered asynchronously via `exportImage` instead) — never a
        // wrong or fabricated raster URL.
        val p = EUMETNETOPERARadarProvider(http, clock)   // empty wmsBaseUrl, ORD render path
        val bbox = RadarBoundingBox(45.0, 0.0, 50.0, 8.0)
        assertNull(p.exportImageUrl(bbox, PixelSize(400, 300), null))
    }

    @Test
    fun nasaProviderIsSatelliteEstimateNeverRadar() {
        val p = NASAGIBSPrecipitationProvider(http, clock)
        assertFalse(p.supportsTrueRadar, "NASA IMERG is a satellite estimate, not radar")
        assertEquals(PrecipitationLayerType.SATELLITE_ESTIMATE, p.layerType)
        assertEquals("Satellite precipitation estimate", p.uiLayerLabel)
        assertFalse(p.uiLayerLabel.lowercase().contains("radar"))
        assertEquals(HazardConfidence.LOW, p.confidence, "satellite estimate is lower confidence than radar")
        // Required NASA acknowledgement.
        assertTrue(p.attributionText?.contains("NASA Global Imagery Browse Services (GIBS)") ?: false)
        assertTrue(p.attributionText?.contains("GPM IMERG") ?: false)
    }

    @Test
    fun nasaCoverageIsNearGlobalNotPolar() {
        assertTrue(Coordinate(0.0, 0.0) in NASAGIBSPrecipitationProvider.coverageBox)
        assertFalse(
            Coordinate(75.0, 100.0) in NASAGIBSPrecipitationProvider.coverageBox,
            "IMERG does not cover the poles; the app never implies global radar",
        )
    }

    @Test
    fun nasaExportURLIsWellFormedAndKeyless() {
        val p = NASAGIBSPrecipitationProvider(http, clock)
        val bbox = RadarBoundingBox(-5.0, -35.0, 5.0, -25.0)
        val s = p.exportImageUrl(bbox, PixelSize(500, 500), null) ?: ""
        assertTrue(s.contains("GetMap"))
        assertTrue(s.contains("IMERG_Precipitation_Rate"))
        assertFalse(s.lowercase().contains("apikey"))
        assertFalse(s.lowercase().contains("token"))
    }

    // endregion

    // region Selection order (NOAA → OPERA → NASA → none)

    private fun shippingService() = PrecipitationOverlayService(http, clock)

    @Test
    fun selectionOrder() {
        val service = shippingService()

        // Inside NOAA coverage → NOAA.
        assertEquals("noaa-nws-radar", service.selectedProvider(region(40.0, -95.0))?.id)
        // Europe: OPERA covers it but its ORD render is disabled in shipping builds, so
        // selection falls through to the NASA satellite estimate.
        assertEquals("nasa-gibs-imerg", service.selectedProvider(region(48.85, 2.35))?.id)
        // Elsewhere within ±60° → NASA satellite estimate.
        assertEquals("nasa-gibs-imerg", service.selectedProvider(region(0.0, -30.0))?.id)
        // High latitude outside all coverage → none.
        assertNull(service.selectedProvider(region(75.0, 100.0)))
    }

    @Test
    fun selectedProviderLayerLabels() {
        val service = shippingService()
        assertEquals("Radar precipitation", service.selectedProvider(region(40.0, -95.0))?.uiLayerLabel)
        // OPERA disabled → Europe shows the satellite estimate label, never "radar".
        assertEquals(
            "Satellite precipitation estimate",
            service.selectedProvider(region(48.85, 2.35))?.uiLayerLabel,
        )
        assertEquals(
            "Satellite precipitation estimate",
            service.selectedProvider(region(0.0, -30.0))?.uiLayerLabel,
        )
    }

    @Test
    fun mockModeSelectsMockProvider() {
        val service = shippingService()
        service.useMockProvider(true)
        assertEquals("mock-radar", service.selectedProvider(region(48.85, 2.35))?.id)
    }

    @Test
    fun europeSelectsOPERAWhenExplicitlyEnabled() {
        // The selection logic still prefers OPERA over NASA in Europe when OPERA has a
        // working source — this guards the re-enable path (flip `useORD = true`, or wire a
        // WMS endpoint, and OPERA wins again). The shipping default keeps it disabled.
        val service = PrecipitationOverlayService(
            providers = listOf(
                NOAARadarPrecipitationProvider(http, clock),
                EUMETNETOPERARadarProvider(http, clock, useORD = true),   // explicitly enabled
                NASAGIBSPrecipitationProvider(http, clock),
            ),
            mockProvider = MockRadarPrecipitationProvider(clock),
            clock = clock,
        )
        assertEquals("eumetnet-opera-radar", service.selectedProvider(region(48.85, 2.35))?.id)
    }

    @Test
    fun shippingDefaultDisablesOPERAInEurope() {
        // Regression guard for the shipping decision: OPERA's ORD render is disabled by
        // default, so Europe resolves to the NASA satellite estimate, not OPERA.
        assertEquals("nasa-gibs-imerg", shippingService().selectedProvider(region(48.85, 2.35))?.id)
    }

    @Test
    fun europeFallsThroughToNASAWhenOPERACannotRender() {
        // An OPERA provider with no working source must not win selection and blank the map
        // while claiming coverage — selection falls through to the NASA estimate.
        val service = PrecipitationOverlayService(
            providers = listOf(
                NOAARadarPrecipitationProvider(http, clock),
                EUMETNETOPERARadarProvider(http, clock, useORD = false),   // no ORD, no WMS
                NASAGIBSPrecipitationProvider(http, clock),
            ),
            mockProvider = MockRadarPrecipitationProvider(clock),
            clock = clock,
        )
        assertEquals("nasa-gibs-imerg", service.selectedProvider(region(48.85, 2.35))?.id)
        assertEquals(
            "Satellite precipitation estimate",
            service.selectedProvider(region(48.85, 2.35))?.uiLayerLabel,
        )
    }

    // endregion

    // region Web-Mercator registration of the visible-region box
    //
    // The map projects in Web Mercator (EPSG:3857) with the region centre at the view's
    // centre, so the box's north/south edges have to be symmetric about the centre *in
    // Mercator*, not in raw degrees. Getting this wrong leaves the 3857 NASA GIBS / OPERA
    // WMS overlay off-centre by an amount that grows with the span — so it appears to *move*
    // (not just scale) as the map is zoomed.

    /** Normalized (Earth-radius-free) Web-Mercator y, matching `RadarBoundingBox`. */
    private fun mercatorY(lat: Double): Double {
        val clamped = min(85.05112878, max(-85.05112878, lat))
        return ln(tan(PI / 4 + clamped * PI / 180 / 2))
    }

    @Test
    fun regionBoxIsMercatorSymmetricAboutCenter() {
        // A mid-latitude region where Mercator's latitude non-linearity is pronounced.
        val box = MapRegion(55.0, 10.0, latitudeDelta = 20.0, longitudeDelta = 20.0).boundingBox

        val yCenter = mercatorY(55.0)
        val northHalf = mercatorY(box.maxLatitude) - yCenter
        val southHalf = yCenter - mercatorY(box.minLatitude)
        // North and south Mercator half-spans match → the box is centred on the map centre.
        assertEquals(northHalf, southHalf, 1e-9)
        // The degree edges are therefore *asymmetric* about the centre (that is correct): the
        // northern edge is nearer the centre in degrees than the southern one.
        assertTrue(box.maxLatitude - 55.0 < 55.0 - box.minLatitude)
        // Longitude is linear in Mercator, so it stays a plain symmetric ± half-span.
        assertEquals(0.0, box.minLongitude, 1e-9)
        assertEquals(20.0, box.maxLongitude, 1e-9)
    }

    @Test
    fun overlayCentreStaysPinnedAcrossZoomLevels() {
        // Same centre, several zoom levels: a correctly-registered overlay keeps its Mercator
        // centre pinned to the map centre at every zoom — it scales, never moves.
        val yCenter = mercatorY(45.0)
        for (delta in listOf(1.0, 8.0, 30.0, 60.0)) {
            val box = MapRegion(45.0, -100.0, latitudeDelta = delta, longitudeDelta = delta).boundingBox
            val boxYCenter = (mercatorY(box.minLatitude) + mercatorY(box.maxLatitude)) / 2
            assertEquals(yCenter, boxYCenter, 1e-9, "overlay centre drifted at zoom span $delta")
        }
    }

    @Test
    fun mercatorBBoxStringCentresOnRegionCenter() {
        // End-to-end for the NASA/OPERA WMS request: the exported 3857 BBOX must be
        // vertically centred on the region centre so GIBS returns the on-screen extent.
        val box = MapRegion(30.0, 0.0, latitudeDelta = 40.0, longitudeDelta = 40.0).boundingBox
        val parts = box.mercatorBBoxString.split(",").mapNotNull { it.toDoubleOrNull() }
        assertEquals(4, parts.size)
        val yMin = parts[1]
        val yMax = parts[3]
        // `mercatorBBoxString` applies the Earth radius; fold it in for the comparison.
        assertEquals(6_378_137.0 * mercatorY(30.0), (yMin + yMax) / 2, 1e-3)
    }

    // endregion

    /** Selection and URL building never touch the network. */
    private object NeverCalledHttp : HttpFetching {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSeconds: Long): HttpResult =
            HttpResult.Success(HttpResponse(status = 200, body = ByteArray(0), headers = emptyMap()))

        override suspend fun post(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String>,
            timeoutSeconds: Long,
        ): HttpResult = HttpResult.Failure("not used")
    }
}
