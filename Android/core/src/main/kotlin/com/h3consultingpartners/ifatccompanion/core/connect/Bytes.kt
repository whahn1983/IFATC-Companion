package com.h3consultingpartners.ifatccompanion.core.connect

/**
 * Little-endian byte helpers for the Infinite Flight Connect API v2 wire protocol.
 * Ported from the `Data` extensions in `IFConnectFrameBuffer.swift`.
 */
internal fun ByteArray.readInt32LE(offset: Int): Int {
    var value = 0
    for (i in 0 until 4) value = value or ((this[offset + i].toInt() and 0xFF) shl (8 * i))
    return value
}

internal fun ByteArray.readInt64LE(offset: Int): Long {
    var value = 0L
    for (i in 0 until 8) value = value or ((this[offset + i].toLong() and 0xFF) shl (8 * i))
    return value
}

internal fun int32LE(value: Int): ByteArray = ByteArray(4) { i ->
    ((value ushr (8 * i)) and 0xFF).toByte()
}

internal fun byteLE(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)
