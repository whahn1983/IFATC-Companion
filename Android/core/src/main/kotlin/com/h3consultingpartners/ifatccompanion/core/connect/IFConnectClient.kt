package com.h3consultingpartners.ifatccompanion.core.connect

import com.h3consultingpartners.ifatccompanion.core.config.AppConfig
import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Low-level TCP client for the Infinite Flight Connect API v2. Request/response
 * framing is serialized by a [Mutex] — the Kotlin equivalent of the Swift `actor`
 * that guards the iOS client. All operations are best-effort and throw
 * [IFConnectError] rather than crashing when Infinite Flight is unavailable.
 *
 * Protocol (v2):
 *  - Request a state/manifest: send Int32 id (LE) + 1 byte (0 = read).
 *  - Response framing: Int32 id (LE) + Int32 length (LE) + `length` payload bytes.
 *  - Run a command / write: send Int32 id (LE) + 1 byte (1 = write) + payload.
 *  - The manifest is requested with id == -1; its payload is a length-prefixed UTF-8
 *    string (Int32 length (LE) + bytes).
 *
 * TCP delivers those framed responses as an arbitrary stream of chunks, so all reads
 * go through a persistent [IFConnectFrameBuffer]: every chunk is appended and a frame
 * is only surfaced once its full declared length has arrived. A partial response is
 * therefore never mistaken for a missing/empty one.
 *
 * Ported from `IFATCCompanion/Connect/IFConnectClient.swift`.
 */
class IFConnectClient(
    private val transport: IFConnectTransport = TcpConnectTransport(),
    private val clock: Clock = Clock.system,
) {

    private val lock = Mutex()

    /**
     * Persistent receive buffer. Holds bytes across TCP reads and can carry more than
     * one frame at a time; reset whenever a new exchange begins or the link is
     * (re)connected so a fresh request never reads stale bytes.
     */
    private val receiveBuffer = IFConnectFrameBuffer()

    /**
     * Frames discarded because their id didn't echo the requested state. Non-zero means
     * the link desynchronised at least once; surfaced in Diagnostics rather than
     * silently absorbed, because a desync used to be *invisible* — see [readState].
     */
    private var mismatchedFrameCount = 0

    val isConnected: Boolean get() = transport.isConnected

    // region Lifecycle

    suspend fun connect(
        host: String,
        port: Int,
        timeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    ) = lock.withLock {
        val trimmed = host.trim()
        // Connect API v2 always speaks TCP/10112; fall back to it if the supplied port
        // is missing or out of range rather than failing outright.
        val resolvedPort = if (port in 1..65535) port else DEFAULT_PORT
        if (trimmed.isEmpty()) throw IFConnectError.InvalidHost
        transport.close()
        receiveBuffer.reset()
        transport.connect(trimmed, resolvedPort, timeoutMillis)
    }

    fun disconnect() {
        transport.close()
        // Drop any half-received frame so a later reconnect starts from a clean slate.
        receiveBuffer.reset()
    }

    private fun requireConnected() {
        if (!transport.isConnected) throw IFConnectError.NotConnected
    }

    // endregion

    // region High-level requests

    /**
     * Request and parse the Connect manifest.
     *
     * Uses an inactivity timeout: the read waits up to [timeoutMillis] for *more*
     * bytes, and the clock resets every time a chunk arrives — so a large manifest that
     * trickles in over several reads is never cut off mid-transfer. The request is
     * retried once on the same connection (a stale/partial first response right after
     * backgrounding is common); reconnect-and-retry is the caller's job.
     *
     * [onEvent] receives granular progress for diagnostics and the "Receiving
     * manifest…" status.
     */
    suspend fun requestManifest(
        timeoutMillis: Long = DEFAULT_MANIFEST_TIMEOUT_MILLIS,
        onEvent: (IFConnectManifestEvent) -> Unit = {},
    ): List<IFManifestEntry> = lock.withLock {
        requireConnected()
        // Delegate the framing/validation/retry to the pure, testable reader; back its
        // injected transport with this connection. Each chunk read is bounded by its own
        // timeout, so the inactivity clock resets every time bytes arrive.
        IFManifestReader().read(
            sendRequest = { sendStateRequest(MANIFEST_COMMAND_ID, write = false) },
            nextChunk = { transport.receive(timeoutMillis) },
            onEvent = onEvent,
        )
    }

    /**
     * Read a single state by its manifest entry, decoding per its declared type.
     *
     * The response frame carries the id it answers, and that id is **checked**. Every
     * read is request/response over one socket, so a reply that arrives after its read
     * has timed out lands in the *next* read instead — and taking it on trust shifted
     * every subsequent read by one for as long as the link stayed desynchronised,
     * silently, since a float decodes cleanly whatever it actually measures. The damage
     * is worst where a reading is interpreted rather than merely displayed: the heading
     * is judged radians-or-degrees by magnitude, so one altitude or latitude landing in
     * a heading slot reads as proof the sim reports degrees, and every genuine radian
     * heading afterwards — 0…6.28 — is then shown as 0–6°, pinning the aircraft symbol
     * to north on the taxi and weather maps for the rest of the connection.
     *
     * Frames for other ids are dropped (bounded) until this state's own answer arrives,
     * so a desync costs one read rather than every read that follows it.
     */
    suspend fun readState(
        entry: IFManifestEntry,
        timeoutMillis: Long = DEFAULT_STATE_TIMEOUT_MILLIS,
    ): IFStateValue = lock.withLock {
        requireConnected()
        // Each state read is a self-contained request/response; start from an empty
        // buffer so a leftover byte from a prior timed-out read can't misalign it.
        receiveBuffer.reset()
        sendStateRequest(entry.id, write = false)
        // Skipping is bounded by both a frame count and the wall clock: a stale reply is
        // already sitting in the buffer, so draining it costs nothing, but a peer that
        // keeps answering with ids we didn't ask for must not stall the 1 Hz poll
        // indefinitely.
        val deadline = clock.nowMillis() + timeoutMillis
        val answer = payload(
            answering = entry.id,
            isExpired = { clock.nowMillis() >= deadline },
            nextFrame = { readFrame(timeoutMillis) },
        )
        mismatchedFrameCount += answer.mismatched
        decode(answer.payload, entry.type)
    }

    /** Read and clear the mismatched-frame tally (diagnostics only). */
    fun takeMismatchedFrameCount(): Int {
        val count = mismatchedFrameCount
        mismatchedFrameCount = 0
        return count
    }

    /** Run a command (write) by id. Many IF commands take no payload. */
    suspend fun runCommand(id: Int) = lock.withLock {
        requireConnected()
        sendStateRequest(id, write = true)
    }

    // endregion

    // region Framing

    private data class Frame(val id: Int, val payload: ByteArray) {
        override fun equals(other: Any?) =
            other is Frame && id == other.id && payload.contentEquals(other.payload)

        override fun hashCode() = id * 31 + payload.contentHashCode()
    }

    private suspend fun sendStateRequest(id: Int, write: Boolean) {
        transport.send(int32LE(id) + byteArrayOf(if (write) 1 else 0))
    }

    /**
     * Read exactly one complete frame, buffering partial TCP chunks until the full
     * framed response has arrived. [timeoutMillis] is per-chunk inactivity, so slow but
     * steady delivery is tolerated.
     */
    private suspend fun readFrame(timeoutMillis: Long): Frame {
        while (true) {
            when (val result = receiveBuffer.nextFrame()) {
                is IFConnectFrameBuffer.FrameResult.Frame -> return Frame(result.id, result.payload)
                is IFConnectFrameBuffer.FrameResult.InvalidLength -> throw IFConnectError.DecodingFailed
                is IFConnectFrameBuffer.FrameResult.NeedMoreData -> {
                    val chunk = transport.receive(timeoutMillis)
                    if (chunk.isEmpty()) throw IFConnectError.ConnectionFailed("Connection closed")
                    receiveBuffer.append(chunk)
                }
            }
        }
    }

    private fun decode(data: ByteArray, type: IFDataType): IFStateValue = when (type) {
        IFDataType.BOOLEAN -> {
            if (data.isEmpty()) throw IFConnectError.DecodingFailed
            IFStateValue.BoolValue(data[0].toInt() != 0)
        }

        IFDataType.INT32 -> {
            if (data.size < 4) throw IFConnectError.DecodingFailed
            IFStateValue.IntValue(data.readInt32LE(0))
        }

        IFDataType.FLOAT -> {
            if (data.size < 4) throw IFConnectError.DecodingFailed
            IFStateValue.FloatValue(Float.fromBits(data.readInt32LE(0)))
        }

        IFDataType.DOUBLE -> {
            if (data.size < 8) throw IFConnectError.DecodingFailed
            IFStateValue.DoubleValue(Double.fromBits(data.readInt64LE(0)))
        }

        IFDataType.LONG -> {
            if (data.size < 8) throw IFConnectError.DecodingFailed
            IFStateValue.LongValue(data.readInt64LE(0))
        }

        IFDataType.STRING -> {
            var decoded: String? = null
            if (data.size >= 4) {
                val strLen = data.readInt32LE(0)
                if (strLen >= 0 && data.size >= 4 + strLen) {
                    decoded = IFConnectStringDecoder
                        .decodeUtf8Strict(data.copyOfRange(4, 4 + strLen)) ?: ""
                }
            }
            IFStateValue.StringValue(
                decoded ?: (IFConnectStringDecoder.decodeUtf8Strict(data) ?: ""),
            )
        }

        IFDataType.UNKNOWN -> throw IFConnectError.DecodingFailed
    }

    // endregion

    companion object {
        const val MANIFEST_COMMAND_ID = -1

        /**
         * The canonical TCP port for Connect API v2. The handshake always dials this
         * unless an explicit, valid override is supplied.
         */
        const val DEFAULT_PORT = AppConfig.Connect.DEFAULT_PORT

        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 6_000L
        const val DEFAULT_MANIFEST_TIMEOUT_MILLIS = 15_000L
        const val DEFAULT_STATE_TIMEOUT_MILLIS = 4_000L

        /**
         * How many frames belonging to some *other* state a single read will discard
         * while looking for its own answer before giving up. A handful covers the
         * realistic case — the late replies to one or two reads that timed out — while
         * still bounding the read.
         */
        const val MAX_MISMATCHED_FRAMES_PER_READ = 8

        data class Answer(val payload: ByteArray, val mismatched: Int) {
            override fun equals(other: Any?) = other is Answer &&
                payload.contentEquals(other.payload) && mismatched == other.mismatched

            override fun hashCode() = payload.contentHashCode() * 31 + mismatched
        }

        /**
         * Pull frames from [nextFrame] until one answers [answering], discarding any
         * that don't and reporting how many were discarded. Pure but for the injected
         * frame source, so the resynchronisation can be exercised without a socket.
         *
         * Throws [IFConnectError.DecodingFailed] once the skip budget or the deadline is
         * spent — better a read that fails than one that returns another state's number.
         */
        suspend fun payloadAnswering(
            answering: Int,
            maxMismatched: Int = MAX_MISMATCHED_FRAMES_PER_READ,
            isExpired: () -> Boolean = { false },
            nextFrame: suspend () -> Pair<Int, ByteArray>,
        ): Answer {
            var mismatched = 0
            while (mismatched <= maxMismatched) {
                val (id, payload) = nextFrame()
                if (id == answering) return Answer(payload, mismatched)
                mismatched += 1
                if (isExpired()) break
            }
            throw IFConnectError.DecodingFailed
        }
    }

    private suspend fun payload(
        answering: Int,
        isExpired: () -> Boolean,
        nextFrame: suspend () -> Frame,
    ): Answer = payloadAnswering(
        answering = answering,
        isExpired = isExpired,
        nextFrame = { val f = nextFrame(); f.id to f.payload },
    )
}
