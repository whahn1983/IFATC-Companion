package com.h3consultingpartners.ifatccompanion.core.weather.radar

import com.h3consultingpartners.ifatccompanion.core.net.AppHttp
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.RadarBoundingBox
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.WeatherIntensity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

// The EUMETNET OPERA / CIRRUS pan-European composite is gridded in a **Lambert Azimuthal
// Equal Area** projection centered near (55° N, 10° E), covering roughly 3,800 × 4,400 km
// with documented geographic corners:
//   NW (70° N, 30° W), NE (70° N, 50° E), SW (32° N, 15° W), SE (32° N, 30° E).
//
// We derive the projected extent by forward-projecting those four documented corners
// (spherical LAEA, R = 6,378,137 m) and taking their bounding rectangle — so the grid
// mapping is self-consistent with the published corners without hard-coding
// false-easting/northing constants we can't verify. This is a simulation overlay: a few-km
// alignment error from the "approximate" corners is acceptable, and far better than
// treating the composite as an equirectangular image (which would badly misplace
// precipitation at UK latitudes).

/**
 * The OPERA composite's LAEA grid: forward projection plus normalized-coordinate mapping
 * used to resample the composite into a lat/lon or Web-Mercator output.
 *
 * Ported from `IFATCCompanion/Weather/OPERACompositeRenderer.swift`.
 */
class OPERALambertGrid {

    val xmin: Double
    val xmax: Double
    val ymin: Double
    val ymax: Double

    init {
        val nw = project(cornerNWLat, cornerNWLon)
        val ne = project(cornerNELat, cornerNELon)
        val sw = project(cornerSWLat, cornerSWLon)
        val se = project(cornerSELat, cornerSELon)
        xmin = min(nw.x, sw.x)
        xmax = max(ne.x, se.x)
        ymax = max(nw.y, ne.y)
        ymin = min(sw.y, se.y)
    }

    /** A point in LAEA projected metres (x east, y north). */
    data class ProjectedPoint(val x: Double, val y: Double)

    /** Normalized source coordinates: u west→east, v north→south (image-row order). */
    data class NormalizedPoint(val u: Double, val v: Double)

    /**
     * Normalized source coordinates for a geographic point: [NormalizedPoint.u] in 0…1
     * west→east, [NormalizedPoint.v] in 0…1 north→south (image-row order, top = north).
     * Returns null when the point lies outside the composite grid.
     */
    fun normalized(lat: Double, lon: Double): NormalizedPoint? {
        if (xmax <= xmin || ymax <= ymin) return null
        val p = project(lat, lon)
        val u = (p.x - xmin) / (xmax - xmin)
        val v = (ymax - p.y) / (ymax - ymin)
        if (u < 0 || u > 1 || v < 0 || v > 1) return null
        return NormalizedPoint(u, v)
    }

    companion object {
        /** Earth radius used for the spherical LAEA projection (WGS84 semi-major axis), metres. */
        const val RADIUS = 6_378_137.0

        /** Projection origin, degrees. */
        const val LAT0 = 55.0
        const val LON0 = 10.0

        /** Documented grid corners (lat, lon), degrees. */
        const val cornerNWLat = 70.0
        const val cornerNWLon = -30.0
        const val cornerNELat = 70.0
        const val cornerNELon = 50.0
        const val cornerSWLat = 32.0
        const val cornerSWLon = -15.0
        const val cornerSELat = 32.0
        const val cornerSELon = 30.0

        /**
         * Spherical Lambert Azimuthal Equal Area forward projection about ([LAT0], [LON0]).
         * Returns projected metres (x east, y north).
         */
        fun project(lat: Double, lon: Double): ProjectedPoint {
            val d = PI / 180
            val phi = lat * d
            val lam = lon * d
            val phi0 = LAT0 * d
            val lam0 = LON0 * d
            val dLam = lam - lam0
            val denom = 1 + sin(phi0) * sin(phi) + cos(phi0) * cos(phi) * cos(dLam)
            // Guard the antipode (denom → 0); the OPERA area never approaches it.
            val kPrime = if (denom > 1e-12) sqrt(2 / denom) else 0.0
            val x = RADIUS * kPrime * cos(phi) * sin(dLam)
            val y = RADIUS * kPrime * (cos(phi0) * sin(phi) - sin(phi0) * cos(phi) * cos(dLam))
            return ProjectedPoint(x, y)
        }
    }
}

/**
 * A decoded OPERA composite as a grid of classified precipitation intensities at a
 * manageable resolution. Row 0 is the north edge of the grid (image top).
 */
class OPERARaster(
    val width: Int,
    val height: Int,
    /** Row-major, length `width * height`; null = no precipitation / no data. */
    val intensity: List<WeatherIntensity?>,
) {
    fun at(u: Double, v: Double): WeatherIntensity? {
        if (width <= 0 || height <= 0) return null
        val col = min(width - 1, max(0, (u * width).toInt()))
        val row = min(height - 1, max(0, (v * height).toInt()))
        return intensity[row * width + col]
    }
}

/**
 * Decodes an OPERA/CIRRUS composite GeoTIFF and resamples it, through the LAEA grid, into
 * either the app's precipitation-cell sampling grid (lat/lon-linear) or a colorized
 * Web-Mercator PNG overlay for the map. Pure resampling/classification is unit-tested;
 * only the raster decode touches platform imaging (an injected [RasterImageDecoder]), and
 * it fails to null so the caller falls back gracefully.
 *
 * Classification is intentionally conservative so the overlay never *invents* precipitation
 * from ambiguous data: clearly colored composite pixels are read via the standard
 * reflectivity color ramp (as with the NOAA/NASA image overlays), while near-gray
 * single-band `DBZH` data pixels are mapped through the common ODIM reflectivity scaling
 * (gain 0.5, offset −32 dBZ) with sentinel `0`/`255` treated as "no data". These scaling
 * assumptions are **best-effort and meant to be verified/tuned on device** against real
 * ORD composites.
 *
 * Ported from `IFATCCompanion/Weather/OPERACompositeRenderer.swift`.
 */
object OPERACompositeRenderer {

    /**
     * Cap on the decoded source resolution (longest side, px). Keeps memory bounded while
     * preserving enough detail for the route-corridor sampling window.
     */
    const val MAX_SOURCE_DIMENSION = 2200

    // region Classification

    /** Classify one decoded composite pixel into a precipitation intensity. */
    fun classify(r: Int, g: Int, b: Int, a: Int): WeatherIntensity? {
        if (a < 40) return null
        val rf = r.toDouble()
        val gf = g.toDouble()
        val bf = b.toDouble()
        val maxc = max(rf, max(gf, bf))
        val minc = min(rf, min(gf, bf))
        val value = maxc / 255.0
        val sat = if (maxc <= 0) 0.0 else (maxc - minc) / maxc

        // Clearly colored → standard reflectivity color ramp (shared with the other image
        // overlays), so a colorized composite reads exactly like NOAA/NASA.
        if (value >= 0.25 && sat >= 0.30) {
            return RadarImageSampler.intensity(r, g, b, a)
        }

        // Near-gray → treat as single-band DBZH data via ODIM scaling.
        // DN 0 and 255 are common no-data / undetect sentinels.
        val dn = maxc.roundToInt()
        if (dn <= 0 || dn >= 255) return null
        val dbz = 0.5 * dn - 32.0        // ODIM gain/offset
        return when {
            dbz < 30 -> null                     // below moderate rain → ignore (like light)
            dbz < 40 -> WeatherIntensity.MODERATE
            dbz < 50 -> WeatherIntensity.HEAVY
            else -> WeatherIntensity.EXTREME
        }
    }

    // endregion

    // region Decode

    /**
     * Decode composite image bytes (cloud-optimized GeoTIFF, or any format the platform
     * decoder handles) into a classified [OPERARaster], downsampled so the longest side is
     * at most [MAX_SOURCE_DIMENSION]. Returns null when the bytes can't be decoded.
     *
     * The decoder must downsample **without smoothing**: the composite is a *classified*
     * raster with sentinel no-data/undetect values, so linear interpolation would average
     * sentinels into data and fabricate reflectivity at every no-data boundary (iOS sets
     * `interpolationQuality = .none` here for the same reason).
     */
    fun decodeRaster(data: ByteArray, decoder: RasterImageDecoder?): OPERARaster? {
        val image = decoder?.decodeScaled(data, MAX_SOURCE_DIMENSION) ?: return null
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0) return null

        // The decoded image reads top-row-first: row 0 is the image top (north), matching
        // this raster's convention (row 0 = north; see `at(v:)` and `intensityGrid`). Read
        // straight through — an `h - 1 - row` flip here would invert the raster north↔south,
        // the same latitude flip guarded in the NOAA/NASA sampler.
        val out = arrayOfNulls<WeatherIntensity>(w * h)
        for (row in 0 until h) {
            for (col in 0 until w) {
                val argb = image.argbAt(col, row)
                out[row * w + col] = classify(
                    r = argbRed(argb),
                    g = argbGreen(argb),
                    b = argbBlue(argb),
                    a = argbAlpha(argb),
                )
            }
        }
        return denoise(OPERARaster(w, h, out.asList()))
    }

    // endregion

    // region Denoise (clutter / speckle suppression)

    /**
     * Suppress isolated speckle in a classified raster: a classified cell survives only if
     * it belongs to an 8-connected cluster of at least [minClusterCells] classified cells.
     *
     * The raw OPERA *maximum-reflectivity* composite carries substantial
     * non-meteorological echo — ground/sea clutter, anomalous propagation, interference
     * "spokes", bioscatter, and coverage-edge artifacts — that the public *rendered*
     * products quality-control away. Without this the overlay paints every clutter pixel as
     * precipitation, speckling clear ocean (visible as scattered dots over the sea where
     * the reference composite is empty). This is the display/sampling counterpart to
     * [RadarImageSampler.cells]' `minCells` cluster filter, applied once at full raster
     * resolution so both the map overlay and the route-corridor sampler consume
     * clutter-suppressed data.
     */
    fun denoise(raster: OPERARaster, minClusterCells: Int = 6): OPERARaster {
        val w = raster.width
        val h = raster.height
        if (w <= 0 || h <= 0 || minClusterCells <= 1) return raster
        val source = raster.intensity
        val out = source.toMutableList()
        val visited = BooleanArray(w * h)
        val neighbours = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1, -1 to -1, -1 to 1, 1 to -1, 1 to 1)
        val stack = ArrayDeque<Int>()

        for (start in 0 until (w * h)) {
            if (source[start] == null || visited[start]) continue
            visited[start] = true
            stack.clear()
            stack.addLast(start)
            // Only the cells of a *small* cluster need erasing, so cap the recorded list at
            // the threshold — large (real) clusters stop growing it early.
            val clusterCells = mutableListOf(start)
            var count = 1
            while (stack.isNotEmpty()) {
                val idx = stack.removeLast()
                val row = idx / w
                val col = idx % w
                for ((dr, dc) in neighbours) {
                    val nr = row + dr
                    val nc = col + dc
                    if (nr < 0 || nr >= h || nc < 0 || nc >= w) continue
                    val n = nr * w + nc
                    if (source[n] != null && !visited[n]) {
                        visited[n] = true
                        stack.addLast(n)
                        count += 1
                        if (clusterCells.size < minClusterCells) clusterCells.add(n)
                    }
                }
            }
            if (count < minClusterCells) {
                for (idx in clusterCells) out[idx] = null
            }
        }
        return OPERARaster(w, h, out)
    }

    // endregion

    // region Resample → sampling grid (lat/lon-linear)

    /**
     * Resample a decoded composite into a `rows × columns` intensity grid over [bbox], laid
     * out linearly in lat/lon (row 0 = max latitude), matching what
     * [RadarImageSampler.cells] expects.
     */
    fun intensityGrid(
        raster: OPERARaster,
        bbox: RadarBoundingBox,
        columns: Int,
        rows: Int,
        grid: OPERALambertGrid = OPERALambertGrid(),
    ): List<List<WeatherIntensity?>> {
        if (columns <= 0 || rows <= 0) return List(max(0, rows)) { List(max(0, columns)) { null } }
        val latSpan = bbox.maxLatitude - bbox.minLatitude
        val lonSpan = bbox.maxLongitude - bbox.minLongitude
        return List(rows) { row ->
            val lat = bbox.maxLatitude - (row + 0.5) / rows * latSpan
            List(columns) { col ->
                val lon = bbox.minLongitude + (col + 0.5) / columns * lonSpan
                grid.normalized(lat, lon)?.let { raster.at(it.u, it.v) }
            }
        }
    }

    /**
     * Decode composite bytes and resample straight into the sampling grid. Null on decode
     * failure so the caller keeps its last good cells.
     */
    fun intensityGrid(
        imageData: ByteArray,
        bbox: RadarBoundingBox,
        columns: Int,
        rows: Int,
        decoder: RasterImageDecoder?,
    ): List<List<WeatherIntensity?>>? {
        val raster = decodeRaster(imageData, decoder) ?: return null
        return intensityGrid(raster, bbox, columns, rows)
    }

    // endregion

    // region Resample → colorized Web-Mercator PNG (map overlay)

    /** An RGBA colour with straight (non-premultiplied) alpha. */
    data class Rgba(val r: Int, val g: Int, val b: Int, val a: Int)

    /**
     * A standard reflectivity color (RGBA) for a precipitation intensity, matching the
     * app's Light/Moderate/Heavy/Extreme legend. Alpha is baked in; the overlay view
     * applies the user's opacity on top.
     */
    fun color(intensity: WeatherIntensity): Rgba = when (intensity) {
        WeatherIntensity.LIGHT -> Rgba(0, 180, 60, 150)
        WeatherIntensity.MODERATE -> Rgba(235, 220, 40, 190)
        WeatherIntensity.HEAVY -> Rgba(245, 140, 20, 205)
        WeatherIntensity.EXTREME -> Rgba(220, 30, 30, 220)
        // No classified return → paint nothing.
        WeatherIntensity.UNKNOWN -> Rgba(0, 0, 0, 0)
    }

    /** A (lat, lon) pair in degrees. */
    data class LatLon(val lat: Double, val lon: Double)

    /** Inverse Web-Mercator (EPSG:3857 metres) → (lat, lon) degrees. */
    fun inverseMercator(x: Double, y: Double): LatLon {
        val lon = x * 180.0 / 20037508.342789244
        val lat = (2 * atan(exp(y / 6_378_137.0)) - PI / 2) * 180.0 / PI
        return LatLon(lat, lon)
    }

    /**
     * Render a decoded composite as a colorized RGBA PNG laid out in Web Mercator across
     * [bbox] (matching how the map displays the NOAA/NASA WMS overlays), at
     * [width] × [height] pixels. Returns null if the image can't be encoded.
     *
     * iOS builds a `premultipliedLast` CGImage and lets ImageIO un-premultiply on encode.
     * A PNG's own bytes are **straight** alpha, so the colour is written as-is here — the
     * decoded result is the same image, and it is what keeps
     * `color(for:)` → `classify(...)` a round trip.
     */
    fun renderMercatorPNG(
        raster: OPERARaster,
        bbox: RadarBoundingBox,
        width: Int,
        height: Int,
        grid: OPERALambertGrid = OPERALambertGrid(),
    ): ByteArray? {
        if (width <= 0 || height <= 0) return null
        fun mx(lon: Double): Double = lon * 20037508.342789244 / 180
        fun my(lat: Double): Double {
            val clamped = min(85.05112878, max(-85.05112878, lat))
            val rad = clamped * PI / 180
            return ln(tan(PI / 4 + rad / 2)) * 6_378_137.0
        }
        val xMin = mx(bbox.minLongitude)
        val xMax = mx(bbox.maxLongitude)
        val yMin = my(bbox.minLatitude)
        val yMax = my(bbox.maxLatitude)

        val bytesPerRow = 4 * width
        val pixels = ByteArray(bytesPerRow * height)
        for (py in 0 until height) {
            val ym = yMax - (py + 0.5) / height * (yMax - yMin)
            for (px in 0 until width) {
                val xm = xMin + (px + 0.5) / width * (xMax - xMin)
                val geo = inverseMercator(xm, ym)
                val n = grid.normalized(geo.lat, geo.lon) ?: continue
                val intensity = raster.at(n.u, n.v) ?: continue
                val c = color(intensity)
                val i = py * bytesPerRow + px * 4
                pixels[i] = c.r.toByte()
                pixels[i + 1] = c.g.toByte()
                pixels[i + 2] = c.b.toByte()
                pixels[i + 3] = c.a.toByte()
            }
        }
        return encodeRgbaPng(pixels, width, height)
    }

    /** Decode composite bytes and render the colorized Web-Mercator overlay PNG. */
    fun renderMercatorPNG(
        imageData: ByteArray,
        bbox: RadarBoundingBox,
        width: Int,
        height: Int,
        decoder: RasterImageDecoder?,
    ): ByteArray? {
        val raster = decodeRaster(imageData, decoder) ?: return null
        return renderMercatorPNG(raster, bbox, width, height)
    }

    // endregion

    // region PNG encode (pure)

    /**
     * Encode straight-alpha RGBA bytes as an 8-bit RGBA PNG.
     *
     * iOS hands its pixel buffer to `CGImageDestination`; `:core` may not touch
     * `android.graphics` (and `javax.imageio` does not exist on Android), so the container
     * is written by hand — IHDR, one zlib-deflated IDAT of filter-0 scanlines, IEND — with
     * `java.util.zip`, which both the JVM and Android provide.
     */
    internal fun encodeRgbaPng(pixels: ByteArray, width: Int, height: Int): ByteArray? {
        if (width <= 0 || height <= 0) return null
        val bytesPerRow = 4 * width
        if (pixels.size < bytesPerRow * height) return null

        // Filter type 0 ("None") in front of every scanline.
        val raw = ByteArray((bytesPerRow + 1) * height)
        for (row in 0 until height) {
            val dst = row * (bytesPerRow + 1)
            raw[dst] = 0
            System.arraycopy(pixels, row * bytesPerRow, raw, dst + 1, bytesPerRow)
        }

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        val ihdr = ByteArrayOutputStream()
        ihdr.writeInt32(width)
        ihdr.writeInt32(height)
        ihdr.write(8)    // bit depth
        ihdr.write(6)    // colour type 6 = RGBA
        ihdr.write(0)    // deflate
        ihdr.write(0)    // adaptive filtering
        ihdr.write(0)    // no interlace
        out.writeChunk("IHDR", ihdr.toByteArray())
        out.writeChunk("IDAT", deflate(raw))
        out.writeChunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 64)
            val buffer = ByteArray(16 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                if (n > 0) out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun ByteArrayOutputStream.writeInt32(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeChunk(type: String, payload: ByteArray) {
        writeInt32(payload.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        write(typeBytes)
        write(payload)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(payload)
        writeInt32(crc.value.toInt())
    }

    // endregion
}

/**
 * Caches the latest decoded whole-Europe OPERA composite so the many per-bbox renders
 * (overlay display + route-corridor sampling) reuse a single anonymous ORD fetch/decode
 * instead of re-downloading the multi-megabyte GeoTIFF each time.
 *
 * A **well-behaved public client** of a shared, low-limit anonymous service:
 *  - refreshes on a **5–8 minute jittered interval** (CIRRUS updates every 5 min),
 *    de-synchronizing requests across devices;
 *  - at each interval it does the **cheap listing first** and **skips the expensive GeoTIFF
 *    download when the product timestamp is unchanged**;
 *  - the download itself is **conditionally revalidated** (ETag/Last-Modified) by the HTTP
 *    layer;
 *  - on a 429/503/network error it **backs off exponentially** (honouring `Retry-After`)
 *    and keeps serving the last good raster;
 *  - it never downloads while a fresh raster is cached, and never on a background telemetry
 *    tick that arrives inside the interval.
 *
 * Swift makes this an `actor`; here it is a class guarded by a [Mutex], per the house
 * style. Ported from `IFATCCompanion/Weather/OPERACompositeRenderer.swift`.
 */
class OPERACompositeStore(
    private val decoder: RasterImageDecoder? = null,
    private val random: Random = Random.Default,
) {

    private val mutex = Mutex()

    private var raster: OPERARaster? = null
    private var product: EUMETNETORDClient.Product? = null

    /** Product timestamp (epoch millis) of the loaded raster. */
    private var currentTimestampMillis: Long? = null
    private var nextRefreshAtMillis: Long? = null
    private var nextRetryAtMillis: Long? = null
    private var failureCount = 0

    /**
     * Actual composite bytes downloaded — the latest download and the running total this
     * app run — so the app can surface real ORD data usage (the composite is the only
     * megabyte-scale weather source). Counted only on a real new-product download (the
     * timestamp-skip means unchanged products aren't re-fetched); a rare 304 on relaunch is
     * served from cache but still counted here, so this slightly over-reports network bytes
     * rather than under-reporting.
     */
    var lastDownloadBytes = 0
        private set

    var sessionDownloadBytes = 0
        private set

    /** Downloaded-bytes snapshot for diagnostics (`last`, session `total`). */
    data class DataUsage(val last: Int, val total: Int)

    suspend fun dataUsage(): DataUsage = mutex.withLock { DataUsage(lastDownloadBytes, sessionDownloadBytes) }

    /**
     * The current decoded composite for [product]. Fetches anonymously via [client] only
     * when due (interval elapsed, not backing off) and only downloads when the product
     * timestamp actually advanced. Returns the last good raster otherwise, or null if none
     * has ever been fetched.
     */
    suspend fun current(
        product: EUMETNETORDClient.Product,
        client: EUMETNETORDClient,
        nowMillis: Long,
    ): OPERARaster? = mutex.withLock {
        if (this.product != product) resetState(product)

        // Backing off after failures, or still within the refresh interval → serve what we
        // have without touching the network.
        nextRetryAtMillis?.let { if (nowMillis < it) return@withLock raster }
        nextRefreshAtMillis?.let { if (nowMillis < it && raster != null) return@withLock raster }

        // Due for a check. List (cheap) and compare the latest product timestamp.
        val latest = client.latestComposite(product, nowMillis)
        if (latest == null) {
            registerFailure(nowMillis, null)   // listing failed / no product
            return@withLock raster
        }
        if (currentTimestampMillis == latest.timestampMillis && raster != null) {
            scheduleNextRefresh(nowMillis)     // unchanged → skip the download
            return@withLock raster
        }

        // New product → download (conditionally revalidated) and decode.
        when (val outcome = client.fetchObject(latest.url)) {
            is EUMETNETORDClient.ObjectOutcome.Success -> {
                lastDownloadBytes = outcome.data.size
                sessionDownloadBytes += outcome.data.size
                val decoded = OPERACompositeRenderer.decodeRaster(outcome.data, decoder)
                if (decoded != null) {
                    raster = decoded
                    currentTimestampMillis = latest.timestampMillis
                    scheduleNextRefresh(nowMillis)
                } else {
                    registerFailure(nowMillis, null)   // decode failed → keep last good
                }
            }
            is EUMETNETORDClient.ObjectOutcome.Retry -> registerFailure(nowMillis, outcome.afterSeconds)
            // Object gone → try next interval.
            EUMETNETORDClient.ObjectOutcome.Unavailable -> scheduleNextRefresh(nowMillis)
        }
        raster
    }

    private fun resetState(product: EUMETNETORDClient.Product) {
        this.product = product
        raster = null
        currentTimestampMillis = null
        nextRefreshAtMillis = null
        nextRetryAtMillis = null
        failureCount = 0
    }

    private fun scheduleNextRefresh(nowMillis: Long) {
        failureCount = 0
        nextRetryAtMillis = null
        val jitter = random.nextDouble(0.0, MAX_JITTER_SECONDS)
        nextRefreshAtMillis = nowMillis + ((BASE_INTERVAL_SECONDS + jitter) * 1000).toLong()
    }

    private fun registerFailure(nowMillis: Long, retryAfterSeconds: Double?) {
        failureCount += 1
        val backoff = AppHttp.backoffDelaySeconds(failureCount)
        // Small jitter, as in the Swift.
        val delay = max(backoff, retryAfterSeconds ?: 0.0) + random.nextDouble(0.0, 15.0)
        nextRetryAtMillis = nowMillis + (delay * 1000).toLong()
        nextRefreshAtMillis = nextRetryAtMillis
    }

    companion object {
        /**
         * Minimum refresh interval and jitter (→ 5–8 min); the composite updates every
         * ~5 min, so checking more often just wastes the shared service's capacity.
         */
        const val BASE_INTERVAL_SECONDS = 300.0
        const val MAX_JITTER_SECONDS = 180.0
    }
}
