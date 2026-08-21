package com.h3consultingpartners.ifatccompanion.core.platform

/**
 * An RGBA pixel grid, row-major with row 0 at the **top** of the source image.
 *
 * [pixels] holds `height * width * 4` bytes as R, G, B, A. Bytes are unsigned; read a
 * channel with [channel] rather than reading the array directly, so the sign of Kotlin's
 * `Byte` never leaks into the colour maths.
 */
class RgbaGrid(val width: Int, val height: Int, val pixels: ByteArray) {

    init {
        require(pixels.size >= width * height * BYTES_PER_PIXEL) {
            "pixel buffer holds ${pixels.size} bytes, need ${width * height * BYTES_PER_PIXEL}"
        }
    }

    /** One channel (0=R, 1=G, 2=B, 3=A) of the pixel at [row], [column], as 0…255. */
    fun channel(row: Int, column: Int, channel: Int): Int =
        pixels[(row * width + column) * BYTES_PER_PIXEL + channel].toInt() and 0xFF

    companion object {
        const val BYTES_PER_PIXEL = 4
    }
}

/**
 * Decodes encoded image bytes (PNG from the radar/satellite overlay providers) into a
 * scaled RGBA grid.
 *
 * The platform port for image decoding: `:core` has the whole radar sampler — colour
 * classification, connected-component clustering, the pixel→coordinate projection — but
 * cannot decode a PNG, so that one step is injected. `:app` implements it with
 * `BitmapFactory`, which is the direct counterpart of the iOS `CGImageSource` +
 * `CGContext.draw` path; tests supply grids directly and never need an implementation.
 */
fun interface ImageDecoding {
    /**
     * Decode [data] and scale it to exactly [width] × [height]. Returns null when the
     * bytes can't be decoded, so callers degrade gracefully rather than failing a flight.
     */
    fun decodeRgba(data: ByteArray, width: Int, height: Int): RgbaGrid?
}
