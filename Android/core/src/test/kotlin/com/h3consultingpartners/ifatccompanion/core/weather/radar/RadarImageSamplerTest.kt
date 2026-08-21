package com.h3consultingpartners.ifatccompanion.core.weather.radar

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.platform.RgbaGrid
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.RadarBoundingBox
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.WeatherIntensity
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sampler decides where the deviation engine thinks the weather is, so a colour
 * misread or a flipped row puts the reroute on the wrong side of a storm. Everything
 * here is the pure half of the iOS type; the decode step is injected.
 */
class RadarImageSamplerTest {

    // region Colour classification

    @Test
    fun theReflectivityRampMapsToTheStandardBands() {
        fun classify(r: Int, g: Int, b: Int) = RadarImageSampler.intensity(r, g, b, 255)
        assertEquals(WeatherIntensity.LIGHT, classify(0, 220, 0))        // green
        assertEquals(WeatherIntensity.LIGHT, classify(0, 120, 240))      // blue
        assertEquals(WeatherIntensity.MODERATE, classify(240, 240, 0))   // yellow
        assertEquals(WeatherIntensity.HEAVY, classify(250, 150, 0))      // orange
        assertEquals(WeatherIntensity.EXTREME, classify(240, 0, 0))      // red
        assertEquals(WeatherIntensity.EXTREME, classify(200, 0, 200))    // magenta
    }

    /**
     * The satellite estimate averages over ~10 km and paints convective cores paler than
     * radar would, so its yellow-green band is promoted a step.
     */
    @Test
    fun theImergRampPromotesYellowGreen() {
        // Hue ~85°: green enough for the reflectivity ramp to call it light.
        val yellowGreen = Triple(128, 220, 0)
        assertEquals(
            WeatherIntensity.LIGHT,
            RadarImageSampler.intensity(yellowGreen.first, yellowGreen.second, yellowGreen.third, 255),
        )
        assertEquals(
            WeatherIntensity.MODERATE,
            RadarImageSampler.intensity(
                yellowGreen.first, yellowGreen.second, yellowGreen.third, 255,
                palette = RadarImageSampler.Palette.IMERG_RATE,
            ),
        )
    }

    /** Map furniture bleeding through the transparent overlay is not precipitation. */
    @Test
    fun transparentDarkAndGrayPixelsAreNotPrecipitation() {
        assertNull(RadarImageSampler.intensity(0, 220, 0, 10))       // transparent
        assertNull(RadarImageSampler.intensity(10, 10, 10, 255))     // near-black
        assertNull(RadarImageSampler.intensity(200, 198, 202, 255))  // washed-out gray
        assertNull(RadarImageSampler.intensity(255, 255, 255, 255))  // white
    }

    @Test
    fun hueIsTheStandardConversion() {
        assertEquals(0.0, RadarImageSampler.hueDegrees(255.0, 0.0, 0.0, 255.0, 0.0), 0.001)
        assertEquals(120.0, RadarImageSampler.hueDegrees(0.0, 255.0, 0.0, 255.0, 0.0), 0.001)
        assertEquals(240.0, RadarImageSampler.hueDegrees(0.0, 0.0, 255.0, 255.0, 0.0), 0.001)
        // Achromatic input has no hue.
        assertEquals(0.0, RadarImageSampler.hueDegrees(100.0, 100.0, 100.0, 100.0, 100.0), 0.001)
    }

    // endregion

    // region Sample resolution

    @Test
    fun theSampleGridHoldsItsResolutionBudgetAndBounds() {
        // ~2 NM per pixel in the middle of the range.
        assertEquals(RadarImageSampler.GridSize(250, 200), RadarImageSampler.sampleGrid(400.0, 500.0))
        // Below the floor on one axis only, the other axis keeps its budget.
        assertEquals(RadarImageSampler.GridSize(250, 160), RadarImageSampler.sampleGrid(300.0, 500.0))
        // A short route is floored, not over-sampled.
        assertEquals(RadarImageSampler.GridSize(160, 160), RadarImageSampler.sampleGrid(20.0, 20.0))
        // A transcon route is capped.
        assertEquals(RadarImageSampler.GridSize(640, 640), RadarImageSampler.sampleGrid(4000.0, 4000.0))
    }

    /**
     * The regression this exists for: if the requested image's aspect ratio doesn't match
     * the bbox's Mercator aspect, the source returns a different extent than we asked for
     * and every sampled cell drifts. So the returned size must hold the Mercator aspect.
     */
    @Test
    fun theMercatorSampleSizeHoldsTheBboxAspect() {
        val bbox = RadarBoundingBox(30.0, -100.0, 45.0, -75.0)
        val size = RadarImageSampler.mercatorSampleSize(bbox)

        val mercWidth = (bbox.maxLongitude - bbox.minLongitude) * Math.PI / 180
        fun mercY(lat: Double) = kotlin.math.ln(kotlin.math.tan(Math.PI / 4 + lat * Math.PI / 180 / 2))
        val mercHeight = mercY(bbox.maxLatitude) - mercY(bbox.minLatitude)

        val imageAspect = size.columns.toDouble() / size.rows
        val bboxAspect = mercWidth / mercHeight
        assertTrue(
            abs(imageAspect - bboxAspect) / bboxAspect < 0.02,
            "image aspect $imageAspect should match bbox Mercator aspect $bboxAspect",
        )
    }

    // endregion

    // region Clustering

    /** Two separated blobs cluster into two cells; the peak intensity of each survives. */
    @Test
    fun separatedBlobsClusterSeparately() {
        val grid = intensityGrid(
            """
            ..........
            .XX....YY.
            .XX....YY.
            ..........
            """,
        )
        val cells = RadarImageSampler.cells(grid, unitBox, minCells = 3)
        assertEquals(2, cells.size)
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

    /** A speck smaller than the floor is noise, not a storm. */
    @Test
    fun clustersBelowTheFloorAreDropped() {
        val grid = intensityGrid(
            """
            ......
            .XX...
            ......
            """,
        )
        assertTrue(RadarImageSampler.cells(grid, unitBox, minCells = 3).isEmpty())
        assertEquals(1, RadarImageSampler.cells(grid, unitBox, minCells = 2).size)
    }

    /** Light returns never become cells — only moderate and above are routed around. */
    @Test
    fun lightReturnsAreNotCells() {
        val grid = List(4) { List(6) { WeatherIntensity.LIGHT } }
        assertTrue(RadarImageSampler.cells(grid, unitBox).isEmpty())
    }

    /** A cluster's cell takes the strongest intensity inside it. */
    @Test
    fun aCellCarriesItsPeakIntensity() {
        val grid = listOf(
            listOf(WeatherIntensity.MODERATE, WeatherIntensity.EXTREME, WeatherIntensity.MODERATE),
            listOf(WeatherIntensity.MODERATE, WeatherIntensity.MODERATE, null),
            listOf(null, null, null),
        )
        val cells = RadarImageSampler.cells(grid, unitBox, minCells = 3)
        assertEquals(1, cells.size)
        assertEquals(WeatherIntensity.EXTREME, cells[0].intensity)
    }

    /**
     * Row 0 is the NORTH edge. The iOS regression this pins is a north↔south flip that
     * turned a southern storm into a northern cell.
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
        val northmost = cell.polygon.maxOf { it.latitude }
        val southmost = cell.polygon.minOf { it.latitude }
        assertEquals(40.0, northmost, 0.001)          // top row touches the box's north edge
        assertEquals(35.0, southmost, 0.001)          // two of four rows down
    }

    /**
     * A Web-Mercator image's rows are linear in Mercator y, not in latitude, so the
     * midpoint row of a tall box sits north of the box's midpoint latitude.
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
            grid, bbox, minCells = 3, projection = RadarImageSampler.PixelProjection.WEB_MERCATOR,
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

    // region Decode

    /** The injected decoder is the only impure step; a failure degrades to null. */
    @Test
    fun aDecodeFailureYieldsNull() {
        val decoder = com.h3consultingpartners.ifatccompanion.core.platform.ImageDecoding { _, _, _ -> null }
        assertNull(RadarImageSampler.grid(ByteArray(4), 8, 8, decoder))
        assertNull(RadarImageSampler.cells(ByteArray(4), 8, 8, unitBox, decoder))
    }

    @Test
    fun aDecodedGridClassifiesTopRowFirst() {
        // A 2x2 image: top-left red (extreme), everything else transparent.
        val pixels = ByteArray(2 * 2 * 4)
        pixels[0] = 255.toByte(); pixels[1] = 0; pixels[2] = 0; pixels[3] = 255.toByte()
        val decoder = com.h3consultingpartners.ifatccompanion.core.platform.ImageDecoding { _, w, h ->
            RgbaGrid(w, h, pixels)
        }
        val grid = assertNotNull(RadarImageSampler.grid(ByteArray(4), 2, 2, decoder))
        assertEquals(WeatherIntensity.EXTREME, grid[0][0])
        assertNull(grid[1][1])
    }

    // endregion

    // region SIGMET cores

    @Test
    fun onlyCellsOverlappingTheAdvisoryAreCores() {
        val advisory = listOf(
            Coordinate(30.0, -100.0), Coordinate(30.0, -95.0),
            Coordinate(35.0, -95.0), Coordinate(35.0, -100.0),
        )
        val inside = RadarCell(
            polygon = listOf(
                Coordinate(31.0, -99.0), Coordinate(31.0, -98.0),
                Coordinate(32.0, -98.0), Coordinate(32.0, -99.0),
            ),
            intensity = WeatherIntensity.HEAVY,
        )
        val outside = RadarCell(
            polygon = listOf(
                Coordinate(50.0, -70.0), Coordinate(50.0, -69.0),
                Coordinate(51.0, -69.0), Coordinate(51.0, -70.0),
            ),
            intensity = WeatherIntensity.HEAVY,
        )
        val cores = RadarImageSampler.precipitationCores(advisory, listOf(inside, outside))
        assertEquals(1, cores.size)
        assertEquals(inside.polygon, cores[0])
    }

    /** A light cell inside an advisory isn't a core to route around. */
    @Test
    fun aLightCellIsNotACore() {
        val advisory = listOf(
            Coordinate(30.0, -100.0), Coordinate(30.0, -95.0),
            Coordinate(35.0, -95.0), Coordinate(35.0, -100.0),
        )
        val light = RadarCell(
            polygon = listOf(
                Coordinate(31.0, -99.0), Coordinate(31.0, -98.0),
                Coordinate(32.0, -98.0), Coordinate(32.0, -99.0),
            ),
            intensity = WeatherIntensity.LIGHT,
        )
        assertTrue(RadarImageSampler.precipitationCores(advisory, listOf(light)).isEmpty())
    }

    /** Crossing edges count as overlap even when no vertex is inside the other. */
    @Test
    fun crossingPolygonsOverlap() {
        val horizontal = listOf(
            Coordinate(34.0, -100.0), Coordinate(34.0, -90.0),
            Coordinate(36.0, -90.0), Coordinate(36.0, -100.0),
        )
        val vertical = listOf(
            Coordinate(30.0, -96.0), Coordinate(30.0, -94.0),
            Coordinate(40.0, -94.0), Coordinate(40.0, -96.0),
        )
        assertTrue(RadarImageSampler.polygonsOverlap(horizontal, vertical))
    }

    // endregion

    // region Helpers

    private val unitBox = RadarBoundingBox(0.0, 0.0, 1.0, 1.0)

    /** Build an intensity grid from an ASCII picture — 'X' is moderate, '.' is nothing. */
    private fun intensityGrid(picture: String): List<List<WeatherIntensity?>> =
        picture.trimIndent().lines().filter { it.isNotBlank() }.map { line ->
            line.map { if (it == '.') null else WeatherIntensity.MODERATE }
        }

    // endregion
}
