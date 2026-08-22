package com.h3consultingpartners.ifatccompanion.core.connect

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Covers the Connect API v2 manifest framing/buffering fix for the intermittent
 * "Manifest Unavailable" error. Ported from
 * `IFATCCompanionTests/IFConnectManifestBufferingTests.swift`. Two failure classes
 * are exercised:
 *
 *  1. Framing — the full framed response may arrive across several TCP reads (or
 *     several frames may arrive in one). A partial response must never be treated as
 *     an unavailable/empty manifest, and the read must resume once more bytes land.
 *  2. Decoding — the manifest payload is a *length-prefixed* UTF-8 string. Decoding
 *     the whole payload as UTF-8 without stripping the nested Int32 length prefix was
 *     the root cause: whenever a length byte fell in 0x80–0xBF (a lone UTF-8
 *     continuation byte) the decode failed, consistently for a given manifest size and
 *     shifting when the aircraft/version changed that size.
 */
class IFConnectManifestBufferingTest {

    // region Wire-format builders

    /** A response frame: `Int32 id (LE) + Int32 payloadLength (LE) + payload`. */
    private fun frame(id: Int, payload: ByteArray): ByteArray =
        int32LE(id) + int32LE(payload.size) + payload

    /** A Connect length-prefixed string payload: `Int32 length (LE) + UTF-8 bytes`. */
    private fun lengthPrefixed(s: String): ByteArray {
        val body = s.toByteArray(Charsets.UTF_8)
        return int32LE(body.size) + body
    }

    /** A complete manifest frame carrying [body] (id defaults to the manifest id, -1). */
    private fun manifestFrame(
        body: String,
        id: Int = IFConnectClient.MANIFEST_COMMAND_ID,
    ): ByteArray = frame(id, lengthPrefixed(body))

    private val sampleManifest =
        "0,1,aircraft/0/latitude\n1,2,aircraft/0/groundspeed\n2,4,aircraft/0/name"
    private val sampleEntryCount = 3

    // endregion

    // region Test doubles

    /** Records every emitted manifest event for assertions. */
    private class EventLog {
        val events = mutableListOf<IFConnectManifestEvent>()
        fun record(e: IFConnectManifestEvent) {
            events += e
        }

        val parsedCount: Int?
            get() = events.filterIsInstance<IFConnectManifestEvent.Parsed>()
                .firstOrNull()?.stateCount
    }

    /**
     * A scripted transport: [send] counts requests, [next] yields the queued
     * chunks/errors in order (a thrown entry models a timeout or a close).
     */
    private class ScriptedTransport(private val script: List<Result<ByteArray>>) {
        private var index = 0
        var sendCount = 0
            private set

        fun send() {
            sendCount += 1
        }

        fun next(): ByteArray {
            if (index >= script.size) throw IFConnectError.ConnectionFailed("Connection closed")
            return script[index++].getOrThrow()
        }
    }

    private suspend fun run(
        reader: IFManifestReader,
        transport: ScriptedTransport,
        log: EventLog,
    ): List<IFManifestEntry> = reader.read(
        sendRequest = { transport.send() },
        nextChunk = { transport.next() },
        onEvent = { log.record(it) },
    )

    // endregion

    // region 1. Manifest split across multiple receive callbacks

    @Test
    fun manifestSplitAcrossMultipleCallbacks() = runTest {
        val full = manifestFrame(sampleManifest)
        // Split the single frame into three arbitrary, uneven chunks.
        val transport = ScriptedTransport(
            listOf(
                Result.success(full.copyOfRange(0, 5)),
                Result.success(full.copyOfRange(5, 11)),
                Result.success(full.copyOfRange(11, full.size)),
            ),
        )
        val log = EventLog()

        val entries = run(IFManifestReader(), transport, log)

        assertEquals(sampleEntryCount, entries.size, "the reassembled manifest must parse fully")
        assertEquals(sampleEntryCount, log.parsedCount)
        assertTrue(log.events.any { it is IFConnectManifestEvent.HeaderReceived })
        assertTrue(
            log.events.any { it is IFConnectManifestEvent.Progress },
            "partial payload must report progress, not failure",
        )
    }

    // endregion

    // region 2. Header split across callbacks

    @Test
    fun headerSplitAcrossCallbacks() = runTest {
        val full = manifestFrame(sampleManifest)
        // Split the 8-byte header itself: 3 bytes, then 5 bytes, then the payload.
        val transport = ScriptedTransport(
            listOf(
                Result.success(full.copyOfRange(0, 3)),
                Result.success(full.copyOfRange(3, 8)),
                Result.success(full.copyOfRange(8, full.size)),
            ),
        )
        val log = EventLog()

        val entries = run(IFManifestReader(), transport, log)

        assertEquals(sampleEntryCount, entries.size)
        assertTrue(
            log.events.filterIsInstance<IFConnectManifestEvent.WaitingForHeader>()
                .any { it.received in 1..7 },
            "an incomplete header must report waiting-for-header, not a bad frame",
        )
    }

    // endregion

    // region 3. Multiple frames in one callback (buffer level)

    @Test
    fun multipleFramesInOneBuffer() {
        val buffer = IFConnectFrameBuffer()
        val a = frame(7, byteArrayOf(1, 2, 3))
        val b = frame(9, byteArrayOf(4, 5))
        // Both frames plus the first byte of a third arrive in a single chunk.
        buffer.append(a + b + byteArrayOf(0x00))

        val first = buffer.nextFrame() as? IFConnectFrameBuffer.FrameResult.Frame
            ?: fail("first frame missing")
        assertEquals(7, first.id)
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(first.payload))

        val second = buffer.nextFrame() as? IFConnectFrameBuffer.FrameResult.Frame
            ?: fail("second frame missing")
        assertEquals(9, second.id)
        assertTrue(byteArrayOf(4, 5).contentEquals(second.payload))

        // The dangling byte of the next frame's header is retained, not misparsed.
        val third = buffer.nextFrame() as? IFConnectFrameBuffer.FrameResult.NeedMoreData
            ?: fail("trailing partial header must be retained")
        assertEquals(1, third.have)
        assertNull(third.needTotal, "the header length isn't known yet from a single byte")
    }

    // endregion

    // region 4. Incomplete payload timeout

    @Test
    fun incompletePayloadTimesOutWithoutPartialManifest() = runTest {
        // Header declares a 100-byte payload, but only 10 bytes arrive, then the read
        // times out (inactivity). A single same-connection attempt so the timeout is the
        // terminal outcome.
        val reader = IFManifestReader(maxAttempts = 1)
        val header = manifestFrame("x".repeat(100))
        val partial = header.copyOfRange(0, 18) // 8-byte header + 10 payload bytes
        val transport = ScriptedTransport(
            listOf(Result.success(partial), Result.failure(IFConnectError.Timeout)),
        )
        val log = EventLog()

        assertFailsWith<IFConnectError.Timeout> { run(reader, transport, log) }

        assertNull(log.parsedCount, "a partial payload must never be reported as parsed")
        assertTrue(
            log.events.any { it is IFConnectManifestEvent.Progress },
            "the partial payload must have been recorded as in-progress, not unavailable",
        )
    }

    // endregion

    // region 5. Invalid payload length

    @Test
    fun invalidPayloadLengthIsRejected() = runTest {
        // A frame whose declared payload length is absurd (corrupt/misframed stream).
        val bytes = int32LE(IFConnectClient.MANIFEST_COMMAND_ID) +
            int32LE(500_000_000) +
            byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val transport = ScriptedTransport(listOf(Result.success(bytes)))
        val log = EventLog()

        assertFailsWith<IFConnectError.DecodingFailed> {
            run(IFManifestReader(maxAttempts = 1), transport, log)
        }

        assertTrue(
            log.events.filterIsInstance<IFConnectManifestEvent.InvalidPayloadLength>()
                .any { it.length == 500_000_000 },
        )
    }

    /** Buffer-level: a negative declared length is invalid too. */
    @Test
    fun negativePayloadLengthIsInvalidAtBufferLevel() {
        val buffer = IFConnectFrameBuffer()
        buffer.append(int32LE(3) + int32LE(-1))
        val result = buffer.nextFrame() as? IFConnectFrameBuffer.FrameResult.InvalidLength
            ?: fail("negative length must be reported invalid")
        assertEquals(-1, result.length)
    }

    // endregion

    // region 6. Successful manifest parse after retry

    @Test
    fun successfulManifestParseAfterRetry() = runTest {
        // Attempt 1 receives a well-formed frame with the WRONG response id (as can
        // happen with a stale/garbled first response); attempt 2 receives the real
        // manifest. The reader must retry once on the same connection and succeed.
        val wrongId = frame(42, lengthPrefixed("garbage"))
        val good = manifestFrame(sampleManifest)
        val transport = ScriptedTransport(listOf(Result.success(wrongId), Result.success(good)))
        val log = EventLog()

        val entries = run(IFManifestReader(), transport, log)

        assertEquals(sampleEntryCount, entries.size)
        assertEquals(2, transport.sendCount, "the request must have been retried once on the same connection")
        assertTrue(
            log.events.filterIsInstance<IFConnectManifestEvent.InvalidResponseId>()
                .any { it.id == 42 },
        )
        assertTrue(
            log.events.filterIsInstance<IFConnectManifestEvent.RequestSent>().any { it.attempt == 2 },
        )
        assertEquals(sampleEntryCount, log.parsedCount)
    }

    @Test
    fun retryExhaustionSurfacesLastError() = runTest {
        // Both attempts get a wrong-id frame → the reader gives up after the retry.
        val wrong = frame(42, lengthPrefixed("garbage"))
        val transport = ScriptedTransport(listOf(Result.success(wrong), Result.success(wrong)))
        val log = EventLog()

        assertFailsWith<IFConnectError.DecodingFailed> { run(IFManifestReader(), transport, log) }
        assertEquals(2, transport.sendCount)
    }

    // endregion

    // region Root-cause regression: nested length prefix must be stripped

    @Test
    fun manifestLengthPrefixStrippedEvenWhenLengthByteBreaksWholeUtf8() {
        // Choose a body length whose little-endian bytes contain 0x9C — a lone UTF-8
        // continuation byte. Decoding the WHOLE payload as UTF-8 fails on that byte (the
        // historical bug); stripping the nested length prefix first succeeds.
        val body = "a".repeat(0x9C) // 156 bytes → length LE = 9C 00 00 00
        val payload = lengthPrefixed(body)

        // Sanity: whole-payload UTF-8 decode is exactly what used to fail.
        assertNull(
            IFConnectStringDecoder.decodeUtf8Strict(payload),
            "precondition: the length prefix makes a whole-payload UTF-8 decode fail",
        )

        val decoded = IFConnectStringDecoder.decodeLengthPrefixed(payload)
        val success = decoded as? IFConnectStringDecoder.Decoded.Success
            ?: fail("length-prefixed decode must succeed, got $decoded")
        assertEquals(body, success.value, "the nested length prefix must be stripped before decoding")
    }

    @Test
    fun invalidStringLengthReported() {
        // A payload whose nested length prefix overruns the available bytes.
        val payload = int32LE(1000) + byteArrayOf(0x41, 0x42, 0x43)
        val decoded = IFConnectStringDecoder.decodeLengthPrefixed(payload)
        val failure = (decoded as? IFConnectStringDecoder.Decoded.Error)?.failure
        val invalid = failure as? IFConnectStringDecoder.Failure.InvalidStringLength
            ?: fail("an overrunning string length must be reported invalid")
        assertEquals(1000, invalid.length)
    }

    @Test
    fun utf8DecodeFailureReported() {
        // A valid length prefix over bytes that are not valid UTF-8 (a lone 0xC3).
        val payload = int32LE(1) + byteArrayOf(0xC3.toByte())
        val decoded = IFConnectStringDecoder.decodeLengthPrefixed(payload)
        val failure = (decoded as? IFConnectStringDecoder.Decoded.Error)?.failure
        assertTrue(
            failure is IFConnectStringDecoder.Failure.Utf8,
            "invalid UTF-8 must be reported as a utf8 failure",
        )
    }

    @Test
    fun manifestFrameEndToEndParsesEntries() = runTest {
        // A single-callback happy path: one complete frame → parsed entries.
        val transport = ScriptedTransport(listOf(Result.success(manifestFrame(sampleManifest))))
        val log = EventLog()
        val entries = run(IFManifestReader(), transport, log)
        assertEquals(listOf(0, 1, 2), entries.map { it.id })
        assertEquals("aircraft/0/name", entries[2].name)
        assertEquals(IFDataType.STRING, entries[2].type)
    }

    // endregion

    // region A state read answers the state it asked for

    /** A canned sequence of framed responses, handed out one per nextFrame call. */
    private fun frameSource(frames: List<Pair<Int, ByteArray>>): suspend () -> Pair<Int, ByteArray> {
        val queue = ArrayDeque(frames)
        return {
            if (queue.isEmpty()) throw IFConnectError.Timeout
            queue.removeFirst()
        }
    }

    private fun float(value: Float): ByteArray = int32LE(value.toRawBits())

    /**
     * Regression (field report: the aircraft symbol stuck pointing north). Every state
     * read is request/response over one socket, so the reply to a read that timed out
     * lands in the *next* read — and the response id was never checked, so from then on
     * every read returned the previous request's answer. A float decodes cleanly
     * whatever it measures, so nothing looked wrong; downstream, one latitude landing in
     * a heading slot was read as proof the sim reports degrees, and every genuine radian
     * heading after it was shown as 0–6°.
     */
    @Test
    fun aStateReadSkipsTheLateAnswerToAnEarlierRead() = runTest {
        // The stale reply to state 746 (latitude, 28.43) arrives first; ours answers 731.
        val answer = IFConnectClient.payloadAnswering(
            answering = 731,
            nextFrame = frameSource(
                listOf(746 to float(28.43f), 731 to float(2.967f)),
            ),
        )

        assertEquals(1, answer.mismatched)
        assertEquals(
            2.967f,
            Float.fromBits(answer.payload.readInt32LE(0)),
            "the heading read must return the heading, not the latitude before it",
        )
    }

    /** The matching answer is returned untouched when the link is in step — the common case. */
    @Test
    fun anInStepReadReturnsItsOwnAnswerImmediately() = runTest {
        val answer = IFConnectClient.payloadAnswering(
            answering = 731,
            nextFrame = frameSource(listOf(731 to float(2.967f), 732 to float(2.98f))),
        )
        assertEquals(0, answer.mismatched)
    }

    /**
     * A read that never sees its own answer fails rather than returning someone else's
     * number: a null reading is skipped harmlessly upstream, a wrong one is believed.
     */
    @Test
    fun aReadThatNeverSeesItsAnswerFailsRatherThanGuessing() = runTest {
        val frames = (0 until 40).map { (900 + it) to float(it.toFloat()) }
        assertFailsWith<IFConnectError.DecodingFailed> {
            IFConnectClient.payloadAnswering(answering = 731, nextFrame = frameSource(frames))
        }
    }

    /**
     * …and the skipping is also bounded by the clock, so a peer answering ids we never
     * asked for can't stall the 1 Hz poll.
     */
    @Test
    fun skippingStopsWhenTheReadDeadlinePasses() = runTest {
        val frames = (0 until 40).map { (900 + it) to float(it.toFloat()) }
        assertFailsWith<IFConnectError.DecodingFailed> {
            IFConnectClient.payloadAnswering(
                answering = 731,
                isExpired = { true },
                nextFrame = frameSource(frames),
            )
        }
    }

    // endregion
}
