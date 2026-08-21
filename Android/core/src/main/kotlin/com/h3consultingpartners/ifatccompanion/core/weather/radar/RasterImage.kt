package com.h3consultingpartners.ifatccompanion.core.weather.radar

/**
 * A decoded raster image, row-major with row 0 at the **top** of the source image.
 *
 * iOS samples radar/satellite overlay pixels with CoreGraphics (`CGImageSource` +
 * `CGContext.draw`). `:core` is pure Kotlin/JVM and has no image codec, so the whole
 * sampling stack — colour classification, connected-component clustering, the
 * pixel→coordinate projection — works against this interface instead, and the one impure
 * step (turning PNG/GeoTIFF bytes into pixels) is injected as a [RasterImageDecoder].
 *
 * The Android implementation is `BitmapFactory`-backed and lives in `:app`; tests use
 * [InMemoryRasterImage].
 */
interface RasterImage {
    /** Width in pixels. */
    val width: Int

    /** Height in pixels. */
    val height: Int

    /**
     * The pixel at column [x], row [y] as packed ARGB (`0xAARRGGBB`), with row 0 at the
     * image's top (north, for the georeferenced overlays this samples). Alpha is
     * **straight**, not premultiplied — the classifier reads the colour channels and the
     * alpha independently.
     */
    fun argbAt(x: Int, y: Int): Int
}

/** The alpha channel (0…255) of a packed ARGB pixel. */
fun argbAlpha(argb: Int): Int = (argb ushr 24) and 0xFF

/** The red channel (0…255) of a packed ARGB pixel. */
fun argbRed(argb: Int): Int = (argb ushr 16) and 0xFF

/** The green channel (0…255) of a packed ARGB pixel. */
fun argbGreen(argb: Int): Int = (argb ushr 8) and 0xFF

/** The blue channel (0…255) of a packed ARGB pixel. */
fun argbBlue(argb: Int): Int = argb and 0xFF

/** Pack straight-alpha channels into an ARGB pixel. */
fun packArgb(a: Int, r: Int, g: Int, b: Int): Int =
    ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

/**
 * Decodes encoded image bytes — the PNG the NOAA/NASA overlay endpoints return, or the
 * cloud-optimized GeoTIFF the EUMETNET ORD bucket serves — into a [RasterImage].
 *
 * The counterpart of the iOS `CGImageSourceCreateWithData` + `CGContext.draw` pair, which
 * decodes and rescales in one step. Returning null on any failure is load bearing: every
 * caller degrades gracefully (falls back to the full SIGMET area, or keeps its last good
 * raster) rather than failing a flight because a radar image did not decode.
 */
interface RasterImageDecoder {
    /**
     * Decode [data] and scale it to exactly [width] × [height]. Returns null when the
     * bytes can't be decoded.
     *
     * Scaling must be **nearest-neighbour-ish / low quality on purpose** for the radar
     * sources: the composites are classified rasters with sentinel no-data values, so
     * smooth interpolation averages sentinels into data and fabricates reflectivity at
     * every no-data boundary (iOS sets `interpolationQuality = .none` / `.low` for the
     * same reason).
     */
    fun decode(data: ByteArray, width: Int, height: Int): RasterImage?

    /**
     * Decode [data] at its natural size, downsampled so the longest side is at most
     * [maxDimension]. Used by the OPERA composite path, which does not know the
     * composite's pixel dimensions in advance. Returns null when the bytes can't be
     * decoded.
     */
    fun decodeScaled(data: ByteArray, maxDimension: Int): RasterImage?
}

/**
 * An in-memory [RasterImage] over a packed-ARGB array. The test double for the platform
 * decoder, and the form the OPERA renderer builds its colorized overlay in before
 * encoding it to PNG.
 */
class InMemoryRasterImage(
    override val width: Int,
    override val height: Int,
    private val argb: IntArray,
) : RasterImage {

    init {
        require(argb.size >= width * height) {
            "pixel buffer holds ${argb.size} pixels, need ${width * height}"
        }
    }

    override fun argbAt(x: Int, y: Int): Int = argb[y * width + x]

    companion object {
        /** A fully transparent image of the given size. */
        fun transparent(width: Int, height: Int): InMemoryRasterImage =
            InMemoryRasterImage(width, height, IntArray(width * height))

        /** Build an image from a per-pixel function, row 0 at the top. */
        inline fun build(width: Int, height: Int, pixel: (x: Int, y: Int) -> Int): InMemoryRasterImage {
            val buffer = IntArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) buffer[y * width + x] = pixel(x, y)
            }
            return InMemoryRasterImage(width, height, buffer)
        }
    }
}
