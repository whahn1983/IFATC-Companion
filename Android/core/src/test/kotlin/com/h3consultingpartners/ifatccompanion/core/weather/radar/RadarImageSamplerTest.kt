package com.h3consultingpartners.ifatccompanion.core.weather.radar

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.RadarBoundingBox
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.WeatherIntensity
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [RadarImageSampler] — the live "radar image → moderate-or-greater precipitation
 * cell" sampling used to focus a convective SIGMET deviation on the precipitation core
 * instead of the whole advisory area. All logic here is deterministic and touches no
 * network.
 *
 * The sampler decides where the deviation engine thinks the weather is, so a colour misread
 * or a flipped row puts the reroute on the wrong side of a storm.
 *
 * Ported from `IFATCCompanionTests/RadarImageSamplerTests.swift`.
 */
class RadarImageSamplerTest {

    // region Colour → intensity

    @Test
    fun reflectivityColorsMapToIntensity() {
        // Green and blue are the lightest returns → below the moderate threshold.
        assertEquals(WeatherIntensity.LIGHT, RadarImageSampler.intensity(0, 255, 0, 255))
        assertEquals(WeatherIntensity.LIGHT, RadarImageSampler.intensity(0, 120, 255, 255))
        // Yellow → moderate, orange → heavy, red / magenta → extreme.
        assertEquals(WeatherIntensity.MODERATE, RadarImageSampler.intensity(255, 255, 0, 255))
        assertEquals(WeatherIntensity.HEAVY, RadarImageSampler.intensity(255, 150, 0, 255))
        assertEquals(WeatherIntensity.EXTREME, RadarImageSampler.intensity(255, 0, 0, 255))
        assertEquals(WeatherIntensity.EXTREME, RadarImageSampler.intensity(255, 0, 255, 255))
    }

    @Test
    fun imergRatePaletteMapsColorsToIntensity() {
        val imerg = RadarImageSampler.Palette.IMERG_RATE
        // Blue and green (the broad low-rate satellite wash) stay light so a stratiform field
        // doesn't blob the whole route into one giant deviation.
        assertEquals(WeatherIntensity.LIGHT, RadarImageSampler.intensity(0, 120, 255, 255, imerg))
        assertEquals(WeatherIntensity.LIGHT, RadarImageSampler.intensity(0, 255, 0, 255, imerg))
        // Yellow-green (chartreuse) is promoted to moderate — satellite averaging paints
        // convective cores paler than radar, so this band is where meaningful cells show.
        assertEquals(WeatherIntensity.MODERATE, RadarImageSampler.intensity(150, 255, 0, 255, imerg))
        // Yellow → moderate, orange → heavy, red / magenta → extreme, as with radar.
        assertEquals(WeatherIntensity.MODERATE, RadarImageSampler.intensity(255, 255, 0, 255, imerg))
        assertEquals(WeatherIntensity.HEAVY, RadarImageSampler.intensity(255, 150, 0, 255, imerg))
        assertEquals(WeatherIntensity.EXTREME, RadarImageSampler.intensity(255, 0, 0, 255, imerg))
        assertEquals(WeatherIntensity.EXTREME, RadarImageSampler.intensity(255, 0, 255, 255, imerg))
    }

    @Test
    fun chartreuseIsTheRampDifferentiator() {
        // The one band the two ramps disagree on: yellow-green reads light on the
        // reflectivity ramp (default) but moderate on the IMERG rate ramp.
        assertEquals(WeatherIntensity.LIGHT, RadarImageSampler.intensity(150, 255, 0, 255))
        assertEquals(
            WeatherIntensity.MODERATE,
            RadarImageSampler.intensity(150, 255, 0, 255, RadarImageSampler.Palette.IMERG_RATE),
        )
    }

    @Test
    fun transparentAndAchromaticPixelsAreNotPrecipitation() {
        assertNull(RadarImageSampler.intensity(255, 0, 0, 0), "transparent → no precip")
        assertNull(RadarImageSampler.intensity(128, 128, 128, 255), "gray → no precip")
        assertNull(RadarImageSampler.intensity(250, 250, 250, 255), "near-white → no precip")
        assertNull(RadarImageSampler.intensity(10, 10, 12, 255), "near-black → no precip")
    }

    /** Hue is the standard HSV conversion; achromatic input has no hue. */
    @Test
    fun hueIsTheStandardConversion() {
        assertEquals(0.0, RadarImageSampler.hueDegrees(255.0, 0.0, 0.0, 255.0, 0.0), 0.001)
        assertEquals(120.0, RadarImageSampler.hueDegrees(0.0, 255.0, 0.0, 255.0, 0.0), 0.001)
        assertEquals(240.0, RadarImageSampler.hueDegrees(0.0, 0.0, 255.0, 255.0, 0.0), 0.001)
        assertEquals(0.0, RadarImageSampler.hueDegrees(100.0, 100.0, 100.0, 100.0, 100.0), 0.001)
    }

    // endregion

    // region Grid → cells

    private val unitBox = RadarBoundingBox(40.0, -100.0, 41.0, -99.0)

    @Test
    fun moderatePlusBlockBecomesOneCell() {
        // A 2×2 moderate-or-greater block inside a 4×4 grid (row 0 = north).
        val m = WeatherIntensity.MODERATE
        val h = WeatherIntensity.HEAVY
        val grid = listOf(
            listOf<WeatherIntensity?>(null, null, null, null),
            listOf<WeatherIntensity?>(null, m, h, null),
            listOf<WeatherIntensity?>(null, m, m, null),
            listOf<WeatherIntensity?>(null, null, null, null),
        )
        val cells = RadarImageSampler.cells(grid, unitBox)
        assertEquals(1, cells.size)
        val cell = cells.first()
        assertEquals(WeatherIntensity.HEAVY, cell.intensity, "cell intensity is the cluster peak")

        // rows 1–2 of 4 → lat 40.25…40.75; cols 1–2 of 4 → lon −99.75…−99.25.
        assertEquals(40.25, cell.polygon.minOf { it.latitude }, 1e-9)
        assertEquals(40.75, cell.polygon.maxOf { it.latitude }, 1e-9)
        assertEquals(-99.75, cell.polygon.minOf { it.longitude }, 1e-9)
        assertEquals(-99.25, cell.polygon.maxOf { it.longitude }, 1e-9)
    }

    @Test
    fun lightOnlyGridProducesNoCells() {
        val grid = List(4) { List<WeatherIntensity?>(4) { WeatherIntensity.LIGHT } }
        assertTrue(
            RadarImageSampler.cells(grid, unitBox).isEmpty(),
            "only moderate-or-greater returns become deviation cells",
        )
    }

    @Test
    fun tinyNoiseClusterIsDropped() {
        val h = WeatherIntensity.HEAVY
        val grid = listOf(
            listOf<WeatherIntensity?>(null, null, null, null),
            listOf<WeatherIntensity?>(null, h, null, null),
            listOf<WeatherIntensity?>(null, null, null, null),
            listOf<WeatherIntensity?>(null, null, null, null),
        )
        assertTrue(
            RadarImageSampler.cells(grid, unitBox, minCells = 3).isEmpty(),
            "sub-threshold speckle must not create a cell",
        )
    }

    @Test
    fun separateClustersBecomeSeparateCells() {
        val h = WeatherIntensity.HEAVY
        val grid = listOf(
            listOf<WeatherIntensity?>(h, h, null, null, null),
            listOf<WeatherIntensity?>(h, h, null, null, null),
            listOf<WeatherIntensity?>(null, null, null, null, null),
            listOf<WeatherIntensity?>(null, null, null, h, h),
            listOf<WeatherIntensity?>(null, null, null, h, h),
        )
        val box = RadarBoundingBox(40.0, -100.0, 45.0, -95.0)
        assertEquals(2, RadarImageSampler.cells(grid, box).size)
    }

    /** Diagonal neighbours are connected, so an L-shaped core is one cell, not two. */
    @Test
    fun diagonalNeighboursAreConnected() {
        val grid = intensityGrid(
            """
            X.....
            .X....
            ..X...
            ......
            """,
        )
        assertEquals(1, RadarImageSampler.cells(grid, unitBox, minCells = 3).size)
    }

    /**
     * Row 0 is the NORTH edge. The iOS regression this pins is a north↔south flip that turned
     * a southern storm into a northern cell.
     */
    @Test
    fun rowZeroIsTheNorthEdge() {
        val grid = intensityGrid(
            """
            XXX.
            XXX.
            ....
            ....
            """,
        )
        val bbox = RadarBoundingBox(30.0, -100.0, 40.0, -90.0)
        val cell = RadarImageSampler.cells(grid, bbox, minCells = 3).single()
        assertEquals(40.0, cell.polygon.maxOf { it.latitude }, 0.001)   // top row touches the north edge
        assertEquals(35.0, cell.polygon.minOf { it.latitude }, 0.001)   // two of four rows down
    }

    /**
     * A Web-Mercator image's rows are linear in Mercator y, not in latitude, so the midpoint
     * row of a tall box sits north of the box's midpoint latitude.
     */
    @Test
    fun theWebMercatorProjectionIsNotLinearInLatitude() {
        val grid = intensityGrid(
            """
            XXX.
            XXX.
            ....
            ....
            """,
        )
        val bbox = RadarBoundingBox(0.0, -10.0, 60.0, 10.0)
        val equirect = RadarImageSampler.cells(grid, bbox, minCells = 3).single()
        val mercator = RadarImageSampler.cells(
            grid,
            bbox,
            minCells = 3,
            projection = RadarImageSampler.PixelProjection.WEB_MERCATOR,
        ).single()

        assertEquals(30.0, equirect.polygon.minOf { it.latitude }, 0.001)
        assertTrue(
            mercator.polygon.minOf { it.latitude } > 35.0,
            "a Mercator row halfway down a 0–60° box is well north of 30°",
        )
    }

    /** The cell's centre is the mean of its valid vertices. */
    @Test
    fun aCellCentreIsItsPolygonMean() {
        val cell = RadarCell(
            polygon = listOf(
                Coordinate(30.0, -100.0), Coordinate(30.0, -90.0),
                Coordinate(40.0, -90.0), Coordinate(40.0, -100.0),
            ),
            intensity = WeatherIntensity.MODERATE,
        )
        assertEquals(35.0, cell.center!!.latitude, 0.001)
        assertEquals(-95.0, cell.center!!.longitude, 0.001)
    }

    // endregion

    // region SIGMET precipitation cores

    private fun square(latMin: Double, latMax: Double, lonMin: Double, lonMax: Double) = listOf(
        Coordinate(latMin, lonMin), Coordinate(latMin, lonMax),
        Coordinate(latMax, lonMax), Coordinate(latMax, lonMin),
    )

    @Test
    fun precipitationCoreReturnsOverlappingModeratePlusCell() {
        val sigmet = square(40.0, 44.0, -100.0, -96.0)   // big advisory
        val insideHeavy = RadarCell(square(41.0, 41.6, -99.0, -98.4), WeatherIntensity.HEAVY)
        val outside = RadarCell(square(30.0, 30.6, -80.0, -79.4), WeatherIntensity.HEAVY)
        val lightInside = RadarCell(square(42.0, 42.6, -99.0, -98.4), WeatherIntensity.LIGHT)

        val cores = RadarImageSampler.precipitationCores(sigmet, listOf(insideHeavy, outside, lightInside))
        assertEquals(1, cores.size, "only the moderate+ cell inside the advisory is a precip core")
        // The returned core is the cell's own (much smaller) polygon, not the SIGMET.
        assertEquals(41.0, cores.first().minOf { it.latitude }, 1e-9)
        assertEquals(41.6, cores.first().maxOf { it.latitude }, 1e-9)
    }

    @Test
    fun precipitationCoreEmptyWhenNoSignificantPrecipInArea() {
        val sigmet = square(40.0, 44.0, -100.0, -96.0)
        val farAway = RadarCell(square(10.0, 10.6, -50.0, -49.4), WeatherIntensity.EXTREME)
        assertTrue(
            RadarImageSampler.precipitationCores(sigmet, listOf(farAway)).isEmpty(),
            "with no precipitation in the advisory the caller falls back to the full area",
        )
    }

    @Test
    fun polygonsOverlapDetectsEdgeCrossingWithoutContainedVertex() {
        // A plus-sign crossing: neither square contains a vertex of the other, but their edges
        // cross, so they overlap.
        val horizontal = square(40.4, 40.6, -100.0, -96.0)
        val vertical = square(39.0, 42.0, -98.1, -97.9)
        assertTrue(RadarImageSampler.polygonsOverlap(horizontal, vertical))
    }

    // endregion

    // region Sample resolution (whole-flight-plan sampling)

    @Test
    fun sampleGridScalesWithSpanAndClamps() {
        // A short route floors at the minimum grid (no over-sampling a tiny image).
        val small = RadarImageSampler.sampleGrid(latSpanNM = 40.0, lonSpanNM = 40.0)
        assertEquals(160, small.rows)
        assertEquals(160, small.columns)

        // In the scaling band the grid holds ~2 NM per pixel on each axis, so an elongated
        // route stays fine on its long axis while the short axis floors.
        val mid = RadarImageSampler.sampleGrid(latSpanNM = 600.0, lonSpanNM = 200.0)
        assertEquals(300, mid.rows, "600 NM / 2 NM per pixel")
        assertEquals(160, mid.columns, "200 NM / 2 → 100, floored to the minimum")

        val scaled = RadarImageSampler.sampleGrid(latSpanNM = 900.0, lonSpanNM = 900.0)
        assertEquals(450, scaled.rows)
        assertEquals(450, scaled.columns)

        // A transcon route caps the grid rather than requesting a giant image.
        val big = RadarImageSampler.sampleGrid(latSpanNM = 4000.0, lonSpanNM = 4000.0)
        assertEquals(640, big.rows)
        assertEquals(640, big.columns)
    }

    // endregion

    // region Mercator-aspect sample size (live NOAA/NASA export)

    /**
     * The exact Web-Mercator width:height of a box, the aspect the 3857 render is registered
     * to. Mirrors `mercatorSampleSize`'s own span math.
     */
    private fun mercatorAspect(box: RadarBoundingBox): Double {
        fun y(lat: Double): Double {
            val c = min(85.05112878, max(-85.05112878, lat))
            return ln(tan(PI / 4 + c * PI / 180 / 2))
        }
        val w = (box.maxLongitude - box.minLongitude) * PI / 180
        val h = y(box.maxLatitude) - y(box.minLatitude)
        return w / h
    }

    @Test
    fun mercatorSampleSizeMatchesBboxMercatorAspect() {
        // A wide, mid-latitude corridor: sizing from lat/lon NM with an independent
        // `[160, 640]` clamp per axis would floor the short (lat) axis and break the aspect,
        // so the ImageServer would adjust the returned extent and drift the cells. The size
        // must instead hold the bbox's exact Web-Mercator aspect ratio.
        val wide = RadarBoundingBox(33.0, -102.0, 37.0, -94.0)
        val s = RadarImageSampler.mercatorSampleSize(wide)
        assertEquals(
            mercatorAspect(wide),
            s.columns.toDouble() / s.rows,
            0.02,
            "sample size aspect must match the bbox's Web-Mercator aspect",
        )
        // The longer (Mercator) axis keeps the ~2 NM/pixel resolution budget; the shorter axis
        // follows from the aspect (here below the old 160 floor — which is the point).
        assertEquals(197, s.columns, "longer axis holds the sampleGrid resolution/cap")
        assertTrue(s.rows < 160, "shorter axis follows the aspect, not the per-axis floor")
    }

    @Test
    fun mercatorSampleSizeTallCorridorKeepsAspect() {
        // A tall, narrow corridor: the latitude axis is the longer Mercator axis and keeps the
        // resolution budget; longitude follows from the aspect.
        val tall = RadarBoundingBox(30.0, -98.0, 42.0, -96.0)
        val s = RadarImageSampler.mercatorSampleSize(tall)
        assertEquals(mercatorAspect(tall), s.columns.toDouble() / s.rows, 0.02)
        assertTrue(s.rows > s.columns, "a tall corridor samples more rows than columns")
    }

    @Test
    fun mercatorSampleSizeSmallRegionFloorsLongerAxisAndKeepsAspect() {
        // A small region floors the longer Mercator axis to the minimum. A degrees-square box
        // at mid-latitude is taller than wide in Mercator (latitude is stretched by
        // 1/cos(lat)), so latitude is the longer axis and floors to 160; longitude follows.
        val small = RadarBoundingBox(34.8, -97.6, 35.2, -97.2)
        val s = RadarImageSampler.mercatorSampleSize(small)
        assertEquals(160, max(s.columns, s.rows), "longer axis floors to the minimum dimension")
        assertEquals(160, s.rows, "latitude is the longer Mercator axis at mid-latitude")
        assertEquals(mercatorAspect(small), s.columns.toDouble() / s.rows, 0.02)
    }

    // endregion

    // region Decode

    /** The injected decoder is the only impure step; a failure degrades to null. */
    @Test
    fun aDecodeFailureYieldsNull() {
        val decoder = FakeDecoder { _, _ -> null }
        assertNull(RadarImageSampler.grid(ByteArray(4), 8, 8, decoder))
        assertNull(RadarImageSampler.cells(ByteArray(4), 8, 8, unitBox, decoder))
    }

    @Test
    fun aDecodedGridClassifiesTopRowFirst() {
        // A 2×2 image: top-left red (extreme), everything else transparent.
        val decoder = FakeDecoder { w, h ->
            InMemoryRasterImage.build(w, h) { x, y ->
                if (x == 0 && y == 0) packArgb(255, 255, 0, 0) else 0
            }
        }
        val grid = assertNotNull(RadarImageSampler.grid(ByteArray(4), 2, 2, decoder))
        assertEquals(WeatherIntensity.EXTREME, grid[0][0])
        assertNull(grid[1][1])
    }

    /**
     * End-to-end guard on the decode's vertical orientation: precipitation in the **north**
     * (top) half of the image must sample to a cell in the **north** half of the bbox. A
     * flipped decode (the earlier `rows - 1 - row`) mirrors it into the south, which put
     * southern storms' sampled cells hundreds of NM north on the map.
     */
    @Test
    fun pngDecodeKeepsNorthPrecipInNorthHalf() {
        val side = 16
        // Opaque yellow (moderate) across the top half, transparent below.
        val decoder = FakeDecoder { w, h ->
            InMemoryRasterImage.build(w, h) { _, y ->
                if (y < h / 2) packArgb(255, 255, 255, 0) else 0
            }
        }
        val bbox = RadarBoundingBox(40.0, -100.0, 44.0, -96.0)
        val cells = assertNotNull(RadarImageSampler.cells(ByteArray(4), side, side, bbox, decoder))
        val cell = assertNotNull(cells.firstOrNull(), "the yellow (moderate) top half must produce a cell")
        val midLat = (bbox.minLatitude + bbox.maxLatitude) / 2
        assertTrue(
            cell.center!!.latitude > midLat,
            "north-half precipitation must decode to a north-half cell (not vertically flipped)",
        )
        // The cluster fills exactly the top half of the image, so the cell's southern edge sits
        // at the bbox mid-latitude and its northern edge at the top.
        assertEquals(midLat, cell.polygon.minOf { it.latitude }, 1e-9)
        assertEquals(bbox.maxLatitude, cell.polygon.maxOf { it.latitude }, 1e-9)
    }

    /** A decoder stand-in: `:app` supplies the BitmapFactory-backed one. */
    private class FakeDecoder(
        private val image: (width: Int, height: Int) -> RasterImage?,
    ) : RasterImageDecoder {
        override fun decode(data: ByteArray, width: Int, height: Int): RasterImage? = image(width, height)
        override fun decodeScaled(data: ByteArray, maxDimension: Int): RasterImage? =
            image(maxDimension, maxDimension)
    }

    // endregion

    // region Helpers

    /** Build an intensity grid from an ASCII picture — 'X' is moderate, '.' is nothing. */
    private fun intensityGrid(picture: String): List<List<WeatherIntensity?>> =
        picture.trimIndent().lines().filter { it.isNotBlank() }.map { line ->
            line.map { if (it == '.') null else WeatherIntensity.MODERATE }
        }

    // endregion
}
