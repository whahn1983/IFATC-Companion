package com.h3consultingpartners.ifatccompanion.core.connect

/**
 * Accumulates raw TCP bytes for the Infinite Flight Connect API v2 wire protocol and
 * hands back complete response frames. Pure and synchronous so the framing logic can
 * be unit-tested independently of the socket.
 *
 * A response frame is `Int32 id (LE) + Int32 payloadLength (LE) + payloadLength
 * bytes`. TCP makes no promise about how those bytes are split across reads: a single
 * read may deliver half a header, a header plus a partial payload, or several whole
 * frames at once. This buffer absorbs that by appending every chunk and only
 * surfacing a frame once its full length has arrived — a partial response is never
 * mistaken for a complete (or empty) one.
 *
 * Ported from `IFATCCompanion/Connect/IFConnectFrameBuffer.swift`.
 */
class IFConnectFrameBuffer {

    private var buffer = ByteArray(0)

    /** Outcome of trying to pull one frame off the front of the buffer. */
    sealed interface FrameResult {
        /** A complete frame was extracted and removed from the buffer. */
        data class Frame(val id: Int, val payload: ByteArray) : FrameResult {
            override fun equals(other: Any?) =
                other is Frame && id == other.id && payload.contentEquals(other.payload)

            override fun hashCode() = id * 31 + payload.contentHashCode()
        }

        /**
         * Not enough bytes yet. [have] is what's buffered; [needTotal] is the full frame
         * size (header + payload) once the header is known, else null.
         */
        data class NeedMoreData(val have: Int, val needTotal: Int?) : FrameResult

        /**
         * The declared payload length is negative or exceeds [MAX_PAYLOAD_LENGTH]; the
         * stream is corrupt and cannot be recovered by waiting.
         */
        data class InvalidLength(val length: Int) : FrameResult
    }

    /** The front frame's header, or null until the full 8-byte header has arrived. */
    data class Header(val id: Int, val payloadLength: Int)

    /** Bytes currently buffered but not yet consumed. */
    val count: Int get() = buffer.size

    val isEmpty: Boolean get() = buffer.isEmpty()

    /** Append a freshly received TCP chunk. */
    fun append(data: ByteArray) {
        if (data.isEmpty()) return
        buffer = buffer + data
    }

    /**
     * Discard all buffered bytes — used when reconnecting so a new exchange never
     * reads stale bytes left over from a previous, misaligned one.
     */
    fun reset() {
        buffer = ByteArray(0)
    }

    /**
     * Peek the front frame's header (id + declared payload length) without consuming
     * anything. Null until the full 8-byte header has arrived.
     */
    fun peekHeader(): Header? {
        if (buffer.size < HEADER_LENGTH) return null
        return Header(buffer.readInt32LE(0), buffer.readInt32LE(4))
    }

    /**
     * Try to pull one complete frame off the front of the buffer. On success the
     * frame's bytes are removed and any trailing bytes (a following frame, or the
     * start of one) remain buffered for the next call.
     */
    fun nextFrame(): FrameResult {
        val available = buffer.size
        if (available < HEADER_LENGTH) return FrameResult.NeedMoreData(available, null)
        val id = buffer.readInt32LE(0)
        val length = buffer.readInt32LE(4)
        if (length < 0 || length > MAX_PAYLOAD_LENGTH) return FrameResult.InvalidLength(length)
        val total = HEADER_LENGTH + length
        if (available < total) return FrameResult.NeedMoreData(available, total)
        val payload =
            if (length == 0) ByteArray(0) else buffer.copyOfRange(HEADER_LENGTH, total)
        buffer = buffer.copyOfRange(total, buffer.size)
        return FrameResult.Frame(id, payload)
    }

    companion object {
        /**
         * Upper bound on a single frame's payload. Infinite Flight's largest response
         * (the manifest, or a full-info flight plan) is comfortably under this; a larger
         * declared length means the stream is misframed/corrupt, so we reject it rather
         * than buffer unboundedly waiting for bytes that will never come.
         */
        const val MAX_PAYLOAD_LENGTH = 16 * 1024 * 1024 // 16 MB

        /** Number of bytes in a frame header: Int32 id + Int32 payload length. */
        const val HEADER_LENGTH = 8
    }
}

/**
 * Decodes a Connect "string" payload. Infinite Flight length-prefixes strings on the
 * wire as `Int32 length (LE) + UTF-8 bytes`. The manifest (state id -1) is such a
 * string, and *not* stripping that nested length prefix before UTF-8 decoding is what
 * produced intermittent "Manifest Unavailable": whenever a byte of the length prefix
 * fell in 0x80–0xBF (a lone UTF-8 continuation byte) the whole-payload decode failed,
 * consistently for a given manifest size and shifting when a different
 * aircraft/version changed that size.
 */
object IFConnectStringDecoder {

    sealed interface Failure {
        /** The nested Int32 length prefix is negative or overruns the payload. */
        data class InvalidStringLength(val length: Int) : Failure

        /** The bytes are not valid UTF-8. */
        data object Utf8 : Failure
    }

    sealed interface Decoded {
        data class Success(val value: String) : Decoded
        data class Error(val failure: Failure) : Decoded
    }

    /**
     * Decode a length-prefixed string payload. When the payload is too short to carry
     * a prefix it is treated as a bare UTF-8 string (older/edge captures).
     */
    fun decodeLengthPrefixed(payload: ByteArray): Decoded {
        if (payload.size < 4) {
            val s = decodeUtf8Strict(payload) ?: return Decoded.Error(Failure.Utf8)
            return Decoded.Success(s)
        }
        val declared = payload.readInt32LE(0)
        if (declared < 0 || declared > payload.size - 4) {
            return Decoded.Error(Failure.InvalidStringLength(declared))
        }
        val slice = payload.copyOfRange(4, 4 + declared)
        val s = decodeUtf8Strict(slice) ?: return Decoded.Error(Failure.Utf8)
        return Decoded.Success(s)
    }

    /**
     * Strict UTF-8 decode. Kotlin's `String(bytes)` silently substitutes U+FFFD for
     * malformed input, which would hide exactly the failure this decoder exists to
     * catch, so the decode is done through a rejecting CharsetDecoder instead.
     */
    internal fun decodeUtf8Strict(bytes: ByteArray): String? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull()
    }
}

/**
 * Granular progress/diagnostic events emitted while requesting and reading the
 * Connect manifest. The client emits these; the manager maps them to the Diagnostics
 * log and to the "Receiving manifest…" user-facing status.
 */
sealed interface IFConnectManifestEvent {
    /** The manifest request bytes were sent (attempt 1 or the same-connection retry). */
    data class RequestSent(val attempt: Int) : IFConnectManifestEvent

    /** The 8-byte response header arrived: echoed id and declared payload length. */
    data class HeaderReceived(val id: Int, val payloadLength: Int) : IFConnectManifestEvent

    /** More payload bytes arrived; [received]/[expected] are payload byte counts. */
    data class Progress(val received: Int, val expected: Int) : IFConnectManifestEvent

    /** A complete frame hasn't arrived yet and we're still waiting on the header. */
    data class WaitingForHeader(val received: Int) : IFConnectManifestEvent

    /** The response id did not echo the manifest command id (-1). */
    data class InvalidResponseId(val id: Int) : IFConnectManifestEvent

    /** The framed payload length was negative or implausibly large. */
    data class InvalidPayloadLength(val length: Int) : IFConnectManifestEvent

    /** The nested string-length prefix was negative or overran the payload. */
    data class InvalidStringLength(val length: Int) : IFConnectManifestEvent

    /** The payload bytes were not valid UTF-8. */
    data object Utf8DecodeFailed : IFConnectManifestEvent

    /** The connection closed before the full manifest had arrived. */
    data object ConnectionClosedEarly : IFConnectManifestEvent

    /** The manifest parsed successfully; [stateCount] entries were resolved. */
    data class Parsed(val stateCount: Int) : IFConnectManifestEvent
}
