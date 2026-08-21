package com.h3consultingpartners.ifatccompanion.core.weather.radar

import com.h3consultingpartners.ifatccompanion.core.net.HttpFetching
import com.h3consultingpartners.ifatccompanion.core.net.HttpResponse
import com.h3consultingpartners.ifatccompanion.core.net.HttpResult
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.RadarBoundingBox
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.WeatherIntensity
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic coverage for the anonymous EUMETNET ORD client and the OPERA composite
 * renderer. The network fetch, image decode, and LAEA georeferencing against real
 * composites are verified on device (the ORD host isn't reachable from CI); these exercise
 * the deterministic URL/key parsing, projection, and classification.
 *
 * Ported from `IFATCCompanionTests/OPERACompositeTests.swift`.
 */
class EUMETNETORDClientTest {

    private fun utcMillis(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun client() = EUMETNETORDClient(NeverCalledHttp)

    @Test
    fun compositePrefixIsUTCDatePath() {
        assertEquals("2026/06/04/OPERA/COMP/", EUMETNETORDClient.compositePrefix(utcMillis(2026, 6, 4, 2, 20)))
        // Just before UTC midnight still resolves to that UTC day, not the next.
        assertEquals("2026/01/09/OPERA/COMP/", EUMETNETORDClient.compositePrefix(utcMillis(2026, 1, 9, 23, 59)))
    }

    @Test
    fun listURLIsAnonymousListObjectsV2() {
        val s = client().listUrl("2026/06/04/OPERA/COMP/")
        assertTrue(s.contains("s3.waw3-1.cloudferro.com"))
        assertTrue(s.contains("openradar-24h"))
        assertTrue(s.contains("list-type=2"))
        assertTrue(s.contains("prefix="))
        // Anonymous: no signing/credential query items.
        assertFalse(s.lowercase().contains("x-amz-signature"))
        assertFalse(s.lowercase().contains("awsaccesskey"))
    }

    @Test
    fun objectURLBuildsKeylessPath() {
        val key = "2026/06/04/OPERA/COMP/OPERA@20260604T0220@0@DBZH.tif"
        val s = client().objectUrl(key)
        assertTrue(s.startsWith("https://s3.waw3-1.cloudferro.com/openradar-24h/"))
        assertTrue(s.endsWith("OPERA@20260604T0220@0@DBZH.tif"))
    }

    @Test
    fun parseKeysFromListXML() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ListBucketResult>
              <Contents><Key>2026/06/04/OPERA/COMP/OPERA@20260604T0220@0@DBZH.tif</Key><Size>1</Size></Contents>
              <Contents><Key>2026/06/04/OPERA/COMP/OPERA@20260604T0220@0@DBZH.h5</Key></Contents>
            </ListBucketResult>
        """.trimIndent()
        val keys = EUMETNETORDClient.parseKeys(xml)
        assertEquals(2, keys.size)
        assertTrue(keys.contains("2026/06/04/OPERA/COMP/OPERA@20260604T0220@0@DBZH.tif"))
    }

    @Test
    fun compositeTimestampParse() {
        val millis = EUMETNETORDClient.compositeTimestamp(
            "2026/06/04/OPERA/COMP/OPERA@20260604T0220@0@DBZH.tif",
        )
        assertEquals(utcMillis(2026, 6, 4, 2, 20), assertNotNull(millis))
        assertNull(EUMETNETORDClient.compositeTimestamp("not-a-composite.tif"))
    }

    @Test
    fun isGeoTIFFCompositeMatchesProductAndExtension() {
        val dbzhTif = "…/OPERA@20260604T0220@0@DBZH.tif"
        assertTrue(EUMETNETORDClient.isGeoTIFFComposite(dbzhTif, EUMETNETORDClient.Product.MAXIMUM_REFLECTIVITY))
        // ODIM HDF5 is not a renderable GeoTIFF.
        assertFalse(
            EUMETNETORDClient.isGeoTIFFComposite(
                "…/OPERA@20260604T0220@0@DBZH.h5",
                EUMETNETORDClient.Product.MAXIMUM_REFLECTIVITY,
            ),
        )
        // Wrong product code.
        assertFalse(
            EUMETNETORDClient.isGeoTIFFComposite(
                "…/OPERA@20260604T0215@0@RATE.tif",
                EUMETNETORDClient.Product.MAXIMUM_REFLECTIVITY,
            ),
        )
        assertTrue(
            EUMETNETORDClient.isGeoTIFFComposite(
                "…/OPERA@20260604T0215@0@RATE.tiff",
                EUMETNETORDClient.Product.INSTANTANEOUS_RAIN_RATE,
            ),
        )
    }

    @Test
    fun latestGeoTIFFKeyPicksNewestMatchingProduct() {
        val keys = listOf(
            "2026/06/04/OPERA/COMP/OPERA@20260604T0200@0@DBZH.tif",
            "2026/06/04/OPERA/COMP/OPERA@20260604T0220@0@DBZH.tif",   // newest DBZH .tif
            "2026/06/04/OPERA/COMP/OPERA@20260604T0230@0@DBZH.h5",    // newer but HDF5 → excluded
            "2026/06/04/OPERA/COMP/OPERA@20260604T0225@0@RATE.tif",   // wrong product
        )
        assertEquals(
            "2026/06/04/OPERA/COMP/OPERA@20260604T0220@0@DBZH.tif",
            EUMETNETORDClient.latestGeoTIFFKey(keys, EUMETNETORDClient.Product.MAXIMUM_REFLECTIVITY),
        )
        assertNull(EUMETNETORDClient.latestGeoTIFFKey(keys, EUMETNETORDClient.Product.ONE_HOUR_ACCUMULATION))
    }

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

/** The OPERA LAEA grid projection and composite classification/reprojection. */
class OPERACompositeRendererTest {

    @Test
    fun projectionOriginMapsToZero() {
        val p = OPERALambertGrid.project(OPERALambertGrid.LAT0, OPERALambertGrid.LON0)
        assertEquals(0.0, p.x, 1.0)
        assertEquals(0.0, p.y, 1.0)
    }

    @Test
    fun normalizedInsideAndOutsideGrid() {
        val grid = OPERALambertGrid()
        // Central Europe (the projection origin) sits inside the grid.
        val center = assertNotNull(grid.normalized(55.0, 10.0))
        assertTrue(center.u > 0 && center.u < 1)
        assertTrue(center.v > 0 && center.v < 1)
        // North Scotland (the user's scenario) is inside the composite.
        assertNotNull(grid.normalized(57.8, -4.0))
        // Well outside Europe → null (no fabricated coverage).
        assertNull(grid.normalized(0.0, 0.0))       // equatorial Atlantic/Africa
        assertNull(grid.normalized(39.0, -98.0))    // Kansas, U.S.
    }

    @Test
    fun normalizedRowOrderNorthIsAbove() {
        val grid = OPERALambertGrid()
        val north = assertNotNull(grid.normalized(65.0, -4.0), "both points should be inside the grid")
        val south = assertNotNull(grid.normalized(45.0, -4.0), "both points should be inside the grid")
        // v increases north→south (row order), so a more-northern point has smaller v.
        assertTrue(north.v < south.v)
    }

    @Test
    fun classifyColoredReflectivityRamp() {
        assertEquals(WeatherIntensity.LIGHT, OPERACompositeRenderer.classify(0, 180, 60, 255))       // green
        assertEquals(WeatherIntensity.MODERATE, OPERACompositeRenderer.classify(235, 220, 40, 255))  // yellow
        assertEquals(WeatherIntensity.HEAVY, OPERACompositeRenderer.classify(245, 140, 20, 255))     // orange
        assertEquals(WeatherIntensity.EXTREME, OPERACompositeRenderer.classify(220, 30, 30, 255))    // red
        // Fully transparent → no precipitation.
        assertNull(OPERACompositeRenderer.classify(220, 30, 30, 0))
    }

    @Test
    fun classifyGrayDBZHScaling() {
        // Near-gray single-band DBZH via ODIM gain 0.5 / offset −32:
        //  DN 150 → 43 dBZ (heavy), DN 100 → 18 dBZ (below moderate → ignored).
        assertEquals(WeatherIntensity.HEAVY, OPERACompositeRenderer.classify(150, 150, 150, 255))
        assertNull(OPERACompositeRenderer.classify(100, 100, 100, 255))
        // Sentinels 0 and 255 are treated as no-data.
        assertNull(OPERACompositeRenderer.classify(0, 0, 0, 255))
        assertNull(OPERACompositeRenderer.classify(255, 255, 255, 255))
    }

    @Test
    fun overlayColorsRoundTripThroughClassifier() {
        // A colorized overlay pixel must classify back to the same intensity, so the display
        // render and the sampler agree.
        for (intensity in listOf(
            WeatherIntensity.LIGHT,
            WeatherIntensity.MODERATE,
            WeatherIntensity.HEAVY,
            WeatherIntensity.EXTREME,
        )) {
            val c = OPERACompositeRenderer.color(intensity)
            assertEquals(intensity, OPERACompositeRenderer.classify(c.r, c.g, c.b, c.a))
        }
    }

    @Test
    fun inverseMercatorRoundTrip() {
        val origin = OPERACompositeRenderer.inverseMercator(0.0, 0.0)
        assertEquals(0.0, origin.lat, 1e-6)
        assertEquals(0.0, origin.lon, 1e-6)
        // Half the mercator world width in x is +90° longitude.
        val east = OPERACompositeRenderer.inverseMercator(20037508.342789244 / 2, 0.0)
        assertEquals(90.0, east.lon, 1e-3)
    }

    @Test
    fun intensityGridResamplesInsideBBox() {
        // A raster that is uniformly extreme, sampled over a bbox fully inside the grid,
        // yields an all-extreme output grid.
        val raster = OPERARaster(4, 4, List(16) { WeatherIntensity.EXTREME })
        val bbox = RadarBoundingBox(48.0, 5.0, 52.0, 15.0)
        val grid = OPERACompositeRenderer.intensityGrid(raster, bbox, columns = 8, rows = 8)
        assertEquals(8, grid.size)
        assertEquals(8, grid[0].size)
        assertEquals(WeatherIntensity.EXTREME, grid[4][4])
        assertEquals(WeatherIntensity.EXTREME, grid[0][0])
    }

    @Test
    fun renderMercatorPNGProducesPNGData() {
        val raster = OPERARaster(8, 8, List(64) { WeatherIntensity.HEAVY })
        val bbox = RadarBoundingBox(50.0, -6.0, 60.0, 2.0)
        val data = assertNotNull(OPERACompositeRenderer.renderMercatorPNG(raster, bbox, width = 32, height = 32))
        assertTrue(data.size >= 8)
        // PNG magic number.
        assertEquals(
            listOf(0x89, 0x50, 0x4E, 0x47),
            data.take(4).map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun denoiseRemovesSpeckleAndKeepsCoherentClusters() {
        val w = 10
        val h = 10
        val cells = arrayOfNulls<WeatherIntensity>(w * h)
        // Isolated single-pixel speckle (raw-composite clutter) → should be removed.
        cells[1 * w + 1] = WeatherIntensity.EXTREME
        // A tiny 2-pixel cluster below the threshold → also removed.
        cells[1 * w + 5] = WeatherIntensity.HEAVY
        cells[1 * w + 6] = WeatherIntensity.HEAVY
        // A coherent 3×3 block (9 cells ≥ 6) → real precipitation, kept intact.
        for (r in 5..7) for (c in 5..7) cells[r * w + c] = WeatherIntensity.MODERATE

        val cleaned = OPERACompositeRenderer.denoise(
            OPERARaster(w, h, cells.asList()),
            minClusterCells = 6,
        )
        assertNull(cleaned.intensity[1 * w + 1])   // isolated speckle gone
        assertNull(cleaned.intensity[1 * w + 5])   // 2-px cluster gone
        assertNull(cleaned.intensity[1 * w + 6])
        for (r in 5..7) for (c in 5..7) {
            assertEquals(WeatherIntensity.MODERATE, cleaned.intensity[r * w + c])   // coherent block kept
        }
    }

    @Test
    fun denoiseTreatsDiagonalNeighborsAsConnected() {
        val w = 8
        val h = 8
        // Six cells connected only diagonally still form one 8-connected cluster → kept.
        val big = arrayOfNulls<WeatherIntensity>(w * h)
        for (i in 0 until 6) big[i * w + i] = WeatherIntensity.HEAVY
        val kept = OPERACompositeRenderer.denoise(OPERARaster(w, h, big.asList()), minClusterCells = 6)
        for (i in 0 until 6) assertEquals(WeatherIntensity.HEAVY, kept.intensity[i * w + i])

        // A diagonal run of only 3 is below the threshold → removed.
        val small = arrayOfNulls<WeatherIntensity>(w * h)
        for (i in 0 until 3) small[i * w + i] = WeatherIntensity.HEAVY
        val removed = OPERACompositeRenderer.denoise(OPERARaster(w, h, small.asList()), minClusterCells = 6)
        for (i in 0 until 3) assertNull(removed.intensity[i * w + i])
    }
}
