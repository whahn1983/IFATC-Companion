package com.h3consultingpartners.ifatccompanion.core.weather.radar

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.weather.WeatherRouteAnalyzer
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.RadarBoundingBox
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.WeatherIntensity
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Derives coarse **moderate-or-greater** precipitation cells from a rendered radar image
 * (NOAA/EUMETNET OPERA base-reflectivity PNG) so the vector weather-deviation logic can
 * route around the actual precipitation core inside a SIGMET instead of the entire
 * advisory area.
 *
 * This is the live counterpart to Mock Mode's hand-authored [RadarCell]s. It is a
 * **best-effort sample of an already-approximate colorized radar image**, used for
 * simulation/training only — never real-world storm avoidance. The colour → intensity
 * mapping keys off the standard reflectivity ramp (green = light, yellow = moderate,
 * orange = heavy, red/magenta = extreme); only the moderate-and-warmer bands matter here,
 * so it is deliberately tolerant of shade variation between providers.
 *
 * Everything here is pure: the colour classification, the clustering and the geometry.
 * The one impure step on iOS — the PNG decode — is an injected [RasterImageDecoder], so
 * the whole sampler is unit-testable without an image codec.
 *
 * Ported from `IFATCCompanion/Weather/RadarImageSampler.swift`.
 */
object RadarImageSampler {

    // region Pixel → coordinate projection

    /**
     * How a sampled image's pixel rows map to latitude. The live radar/satellite overlays
     * are rendered in **Web Mercator (EPSG:3857)** — the projection the map draws in — so
     * a pixel row is linear in Mercator *y*, not in latitude; reading it as linear
     * latitude drifts the sampled cells (tens of NM at route scale, enough to push an
     * on-route core outside the deviation corridor). Synthetic test grids and any
     * equirectangular source stay [EQUIRECTANGULAR] (the default), where a row *is*
     * linear in latitude. Longitude is linear in both (Mercator *x* ∝ longitude).
     */
    enum class PixelProjection { EQUIRECTANGULAR, WEB_MERCATOR }

    /**
     * Web Mercator *y* (unitless; the Earth-radius factor cancels in the inverse) for a
     * latitude in degrees, and its inverse. Used to map pixel rows of a 3857 image back
     * to latitude non-linearly.
     */
    private fun mercatorY(latDegrees: Double): Double {
        val clamped = min(85.05112878, max(-85.05112878, latDegrees))
        return ln(tan(PI / 4 + clamped * PI / 180 / 2))
    }

    private fun inverseMercatorLatDegrees(y: Double): Double = (2 * atan(exp(y)) - PI / 2) * 180 / PI

    // endregion

    // region Colour → intensity

    /**
     * The colour ramp a rendered precipitation image uses, which determines how a pixel's
     * hue maps to an intensity band.
     */
    enum class Palette {
        /**
         * Standard radar base-reflectivity ramp (NOAA/NWS, EUMETNET OPERA): green/blue =
         * light, yellow = moderate, orange = heavy, red/magenta = extreme.
         */
        REFLECTIVITY,

        /**
         * NASA GPM IMERG / GIBS precipitation-*rate* ramp (mm/hr) — the global satellite
         * estimate. Structurally similar to the reflectivity ramp, but two things differ
         * and are handled here: its low end is a broad blue→green wash (kept as light so a
         * stratiform rain field doesn't blob the whole map into one giant deviation), and
         * satellite averaging (~10 km) *understates* convective cores, so the yellow-green
         * (chartreuse) band that reflectivity treats as light is promoted to moderate to
         * catch cells the estimate paints paler than radar would. **Best-effort: the exact
         * rate breakpoints are intended to be verified/tuned on device against live GIBS
         * IMERG tiles**, consistent with the other overlay colour scalings in this app.
         */
        IMERG_RATE,
    }

    /**
     * Classify one precipitation pixel into an intensity for the given colour ramp, or
     * null when the pixel is transparent / gray / below the moderate threshold we care
     * about. For both ramps green and blue map to light (ignored by the moderate-plus
     * filter); the ramps differ only in where the moderate band begins (see [Palette]).
     */
    fun intensity(r: Int, g: Int, b: Int, a: Int, palette: Palette = Palette.REFLECTIVITY): WeatherIntensity? {
        // Transparent background of the overlay → no precipitation here.
        if (a < 40) return null
        val rf = r.toDouble()
        val gf = g.toDouble()
        val bf = b.toDouble()
        val maxc = max(rf, max(gf, bf))
        val minc = min(rf, min(gf, bf))
        val value = maxc / 255.0
        val saturation = if (maxc <= 0) 0.0 else (maxc - minc) / maxc
        // Near-black, near-white, or washed-out gray pixels are map furniture / borders
        // bleeding through, not a coloured precipitation return.
        if (value < 0.25 || saturation < 0.30) return null

        val hue = hueDegrees(rf, gf, bf, maxc, minc)
        return when (palette) {
            Palette.REFLECTIVITY -> when {
                hue >= 78 && hue <= 175 -> WeatherIntensity.LIGHT      // green / green-cyan
                hue > 175 && hue < 290 -> WeatherIntensity.LIGHT       // blue / cyan (lightest returns)
                hue >= 46 && hue < 78 -> WeatherIntensity.MODERATE     // yellow
                hue >= 20 && hue < 46 -> WeatherIntensity.HEAVY        // orange
                hue >= 290 && hue < 330 -> WeatherIntensity.EXTREME    // magenta / violet (very heavy)
                else -> WeatherIntensity.EXTREME                       // red (hue < 20 or >= 330)
            }
            Palette.IMERG_RATE -> when {
                hue >= 100 && hue <= 175 -> WeatherIntensity.LIGHT     // green (low-moderate estimated rate)
                hue > 175 && hue < 290 -> WeatherIntensity.LIGHT       // blue / cyan (lightest estimate)
                hue >= 70 && hue < 100 -> WeatherIntensity.MODERATE    // yellow-green — promoted
                hue >= 46 && hue < 70 -> WeatherIntensity.MODERATE     // yellow
                hue >= 20 && hue < 46 -> WeatherIntensity.HEAVY        // orange
                hue >= 290 && hue < 330 -> WeatherIntensity.EXTREME    // magenta / violet (very heavy)
                else -> WeatherIntensity.EXTREME                       // red (hue < 20 or >= 330)
            }
        }
    }

    /**
     * Hue in degrees (0–360) for an RGB triple, given its precomputed max/min channels.
     * Returns 0 for achromatic input (callers gate on saturation first).
     */
    fun hueDegrees(r: Double, g: Double, b: Double, maxc: Double, minc: Double): Double {
        val delta = maxc - minc
        if (delta <= 0) return 0.0
        val hue = when (maxc) {
            r -> 60 * (((g - b) / delta) % 6)
            g -> 60 * (((b - r) / delta) + 2)
            else -> 60 * (((r - g) / delta) + 4)
        }
        return if (hue < 0) hue + 360 else hue
    }

    // endregion

    // region Sample resolution

    /** A sample grid size in pixels. */
    data class GridSize(val columns: Int, val rows: Int)

    /**
     * The `columns × rows` sample grid for a radar image covering a bbox of the given span
     * (NM), sized to hold roughly [targetNMPerPixel] NM per pixel on each axis so a
     * **whole-flight-plan** sample still resolves individual storms near the aircraft
     * (finer for short routes, capped for very long ones). Bounded to `[minDim, maxDim]`
     * per axis — the floor keeps a short route from over-sampling a tiny image, the cap
     * keeps a transcon route from requesting a giant one.
     */
    fun sampleGrid(
        latSpanNM: Double,
        lonSpanNM: Double,
        targetNMPerPixel: Double = 2.0,
        minDim: Int = 160,
        maxDim: Int = 640,
    ): GridSize {
        fun dim(nm: Double): Int {
            val n = if (nm.isFinite()) (nm / max(0.1, targetNMPerPixel)).roundToInt() else minDim
            return min(maxDim, max(minDim, n))
        }
        return GridSize(columns = dim(lonSpanNM), rows = dim(latSpanNM))
    }

    /**
     * The `columns × rows` sample size for a radar export of [bbox], sized so its aspect
     * ratio matches the bbox's exact **Web-Mercator** width:height.
     *
     * The live NOAA/NASA overlays render in EPSG:3857 (the projection the map draws, and
     * the one the pixel→lat inversion in `boundingPolygon` assumes). If the requested
     * image's aspect ratio differs from the bbox's Mercator aspect, the source does not
     * give back the extent we asked for: an ArcGIS ImageServer **expands the returned
     * extent** to fit the image, and a WMS **stretches** the render — either way the image
     * covers a different area than the [bbox] the sampler uses to place pixels, so every
     * sampled cell drifts (tens of NM, pulled toward the corridor's centre) from the
     * displayed radar. Sizing from [sampleGrid] alone did this: it clamps each axis to
     * `[minDim, maxDim]` independently and sizes from lat/lon NM (whose single-`cos(lat)`
     * aspect also diverges over a tall corridor), so the two axes rarely held the Mercator
     * aspect.
     *
     * This keeps [sampleGrid]'s ~[targetNMPerPixel] resolution/cap on the **longer**
     * Mercator axis, then derives the shorter axis from the exact Mercator aspect so the
     * image is registered to [bbox] and the inverse-Mercator cell coordinates land on the
     * real returns.
     */
    fun mercatorSampleSize(
        bbox: RadarBoundingBox,
        targetNMPerPixel: Double = 2.0,
        minDim: Int = 160,
        maxDim: Int = 640,
    ): GridSize {
        val midLat = (bbox.minLatitude + bbox.maxLatitude) / 2
        val latSpanNM = (bbox.maxLatitude - bbox.minLatitude) * 60
        val lonSpanNM = (bbox.maxLongitude - bbox.minLongitude) * 60 * max(0.2, cos(midLat * PI / 180))
        val base = sampleGrid(latSpanNM, lonSpanNM, targetNMPerPixel, minDim, maxDim)

        // Exact Web-Mercator span (radius omitted — it cancels in the aspect). x ∝
        // longitude in radians so it matches the units of mercatorY; y is the Mercator
        // projection of latitude, so a row is linear in it.
        val mercWidth = (bbox.maxLongitude - bbox.minLongitude) * PI / 180
        val mercHeight = mercatorY(bbox.maxLatitude) - mercatorY(bbox.minLatitude)
        if (mercWidth <= 0 || mercHeight <= 0) return base

        // Keep the resolution budget on the longer axis; derive the shorter from the exact
        // Mercator aspect so columns / rows == mercWidth / mercHeight.
        return if (mercWidth >= mercHeight) {
            val columns = base.columns
            GridSize(columns, max(8, (columns.toDouble() * mercHeight / mercWidth).roundToInt()))
        } else {
            val rows = base.rows
            GridSize(max(8, (rows.toDouble() * mercWidth / mercHeight).roundToInt()), rows)
        }
    }

    // endregion

    // region Grid → cells

    /**
     * Cluster a grid of per-pixel intensities into moderate-or-greater precipitation
     * cells, each an axis-aligned lat/lon box covering its cluster. `grid[row][col]` is
     * row-major with row 0 at the **top** (max latitude) of [bbox]. Clusters smaller than
     * [minCells] pixels are dropped as noise.
     */
    fun cells(
        grid: List<List<WeatherIntensity?>>,
        bbox: RadarBoundingBox,
        minCells: Int = 3,
        projection: PixelProjection = PixelProjection.EQUIRECTANGULAR,
    ): List<RadarCell> {
        val rows = grid.size
        if (rows == 0) return emptyList()
        val cols = grid[0].size
        if (cols == 0) return emptyList()

        fun significant(row: Int, col: Int): Boolean {
            if (row < 0 || row >= rows || col < 0 || col >= cols) return false
            val intensity = grid[row].getOrNull(col) ?: return false
            return intensity >= WeatherIntensity.MODERATE
        }

        val visited = Array(rows) { BooleanArray(cols) }
        val neighbours = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1, -1 to -1, -1 to 1, 1 to -1, 1 to 1)
        val out = mutableListOf<RadarCell>()

        for (startRow in 0 until rows) {
            for (startCol in 0 until cols) {
                if (!significant(startRow, startCol) || visited[startRow][startCol]) continue
                val stack = ArrayDeque<Pair<Int, Int>>()
                stack.addLast(startRow to startCol)
                visited[startRow][startCol] = true
                var minRow = startRow
                var maxRow = startRow
                var minCol = startCol
                var maxCol = startCol
                var peak = grid[startRow][startCol] ?: WeatherIntensity.MODERATE
                var count = 0

                while (stack.isNotEmpty()) {
                    val (row, col) = stack.removeLast()
                    count += 1
                    minRow = min(minRow, row); maxRow = max(maxRow, row)
                    minCol = min(minCol, col); maxCol = max(maxCol, col)
                    val intensity = grid[row].getOrNull(col)
                    if (intensity != null && intensity > peak) peak = intensity
                    for ((dr, dc) in neighbours) {
                        val nr = row + dr
                        val nc = col + dc
                        if (significant(nr, nc) && !visited[nr][nc]) {
                            visited[nr][nc] = true
                            stack.addLast(nr to nc)
                        }
                    }
                }

                if (count < minCells) continue
                val polygon = boundingPolygon(minRow, maxRow, minCol, maxCol, rows, cols, bbox, projection)
                out.add(RadarCell(polygon = polygon, intensity = peak))
            }
        }
        return out
    }

    /**
     * The lat/lon corners of the grid-cell block `minRow…maxRow` × `minCol…maxCol`. Row 0
     * is the top (max latitude); the block spans whole cells, so its edges run from
     * [minRow] to `maxRow + 1` and [minCol] to `maxCol + 1`.
     */
    private fun boundingPolygon(
        minRow: Int,
        maxRow: Int,
        minCol: Int,
        maxCol: Int,
        rows: Int,
        cols: Int,
        bbox: RadarBoundingBox,
        projection: PixelProjection,
    ): List<Coordinate> {
        val latSpan = bbox.maxLatitude - bbox.minLatitude
        val lonSpan = bbox.maxLongitude - bbox.minLongitude

        // Longitude is linear in both projections (Mercator x ∝ longitude).
        fun lonAtColEdge(edge: Int): Double = bbox.minLongitude + (edge.toDouble() / cols) * lonSpan

        // Latitude: linear for an equirectangular grid; for a Web Mercator image the row is
        // linear in Mercator y, so interpolate in y and invert the projection.
        fun latAtRowEdge(edge: Int): Double = when (projection) {
            PixelProjection.EQUIRECTANGULAR -> bbox.maxLatitude - (edge.toDouble() / rows) * latSpan
            PixelProjection.WEB_MERCATOR -> {
                val f = edge.toDouble() / rows   // 0 at top (maxLat) → 1 at bottom (minLat)
                val yTop = mercatorY(bbox.maxLatitude)
                val yBottom = mercatorY(bbox.minLatitude)
                inverseMercatorLatDegrees(yTop - f * (yTop - yBottom))
            }
        }

        val north = latAtRowEdge(minRow)
        val south = latAtRowEdge(maxRow + 1)
        val west = lonAtColEdge(minCol)
        val east = lonAtColEdge(maxCol + 1)
        return listOf(
            Coordinate(south, west),
            Coordinate(south, east),
            Coordinate(north, east),
            Coordinate(north, west),
        )
    }

    // endregion

    // region Image decode

    /**
     * Classify a decoded raster into a `rows × cols` intensity grid.
     *
     * The decoded image reads top-row-first: row 0 is the image's TOP (north), matching
     * the grid convention (row 0 = north / `bbox.maxLatitude`, as `boundingPolygon` maps
     * it). So read it straight through — a `rows - 1 - row` flip here would mirror every
     * sampled cell north↔south about the corridor's centre latitude, turning a southern
     * storm into a northern cell.
     */
    fun grid(image: RasterImage, palette: Palette = Palette.REFLECTIVITY): List<List<WeatherIntensity?>> =
        List(image.height) { row ->
            List(image.width) { col ->
                val argb = image.argbAt(col, row)
                intensity(
                    r = argbRed(argb),
                    g = argbGreen(argb),
                    b = argbBlue(argb),
                    a = argbAlpha(argb),
                    palette = palette,
                )
            }
        }

    /**
     * Decode a radar PNG into a `rows × cols` intensity grid via the injected decoder.
     * Returns null when the bytes can't be decoded.
     */
    fun grid(
        png: ByteArray,
        columns: Int,
        rows: Int,
        decoder: RasterImageDecoder,
        palette: Palette = Palette.REFLECTIVITY,
    ): List<List<WeatherIntensity?>>? {
        if (columns <= 0 || rows <= 0) return null
        val image = decoder.decode(png, columns, rows) ?: return null
        return grid(image, palette)
    }

    /**
     * Decode [png] and cluster it into moderate-or-greater precipitation cells for the
     * region [bbox], at the given sample resolution and colour ramp. Returns null on
     * decode failure so the caller can fall back to the full SIGMET area.
     */
    fun cells(
        png: ByteArray,
        columns: Int,
        rows: Int,
        bbox: RadarBoundingBox,
        decoder: RasterImageDecoder,
        palette: Palette = Palette.REFLECTIVITY,
        projection: PixelProjection = PixelProjection.EQUIRECTANGULAR,
    ): List<RadarCell>? {
        val grid = grid(png, columns, rows, decoder, palette) ?: return null
        return cells(grid, bbox, projection = projection)
    }

    // endregion

    // region SIGMET precipitation cores

    /**
     * The moderate-or-greater precipitation cells that lie within (or overlap) a SIGMET's
     * advisory [area], as the geometry to route around instead of the whole polygon.
     * Returns each overlapping cell's polygon; an empty result means no significant
     * precipitation was found in the area, and the caller should fall back to the full
     * advisory.
     */
    fun precipitationCores(area: List<Coordinate>, cells: List<RadarCell>): List<List<Coordinate>> {
        val advisory = area.filter { it.isValid }
        if (advisory.size < 3) return emptyList()
        return cells.mapNotNull { cell ->
            if (cell.intensity < WeatherIntensity.MODERATE) return@mapNotNull null
            val polygon = cell.polygon.filter { it.isValid }
            if (polygon.size < 3) return@mapNotNull null
            if (polygonsOverlap(advisory, polygon)) polygon else null
        }
    }

    /**
     * Whether two lat/lon polygons overlap: a vertex of one inside the other, or a pair of
     * edges crossing. Planar test, consistent with the rest of the route-conflict geometry
     * (adequate at SIGMET / precipitation-cell scale).
     */
    fun polygonsOverlap(a: List<Coordinate>, b: List<Coordinate>): Boolean {
        if (a.any { WeatherRouteAnalyzer.pointInPolygon(it, b) }) return true
        if (b.any { WeatherRouteAnalyzer.pointInPolygon(it, a) }) return true
        var j = a.size - 1
        for (i in a.indices) {
            var l = b.size - 1
            for (k in b.indices) {
                if (Geo.segmentsIntersect(a[j], a[i], b[l], b[k])) return true
                l = k
            }
            j = i
        }
        return false
    }

    // endregion
}
