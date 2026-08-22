package com.h3consultingpartners.ifatccompanion.map

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Turn fetched bytes into something the map can draw.
 *
 * Shared by the two rasters the route map composites — the satellite underlay and the
 * precipitation overlay — because the decode is identical and the reason it lives outside
 * `ui/map` is identical: everything in that package is compiled by the `:uicheck` build
 * against JetBrains Compose without the Android SDK, and `BitmapFactory` is Android-only.
 *
 * Returns null rather than throwing on anything that is not a decodable image. Both callers
 * treat a missing raster as ordinary; neither has anything useful to tell the pilot about it.
 */
internal suspend fun decodeRaster(bytes: ByteArray, context: CoroutineDispatcher): ImageBitmap? {
    if (bytes.isEmpty()) return null
    // Decoding is tens of milliseconds for a map-sized image — small, but not something to
    // do on whichever thread the caller happened to be on.
    return withContext(context) {
        runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
}
