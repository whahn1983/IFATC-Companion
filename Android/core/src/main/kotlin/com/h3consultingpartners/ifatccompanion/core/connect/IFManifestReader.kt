package com.h3consultingpartners.ifatccompanion.core.connect

/**
 * Transport-agnostic engine that requests, buffers, validates and parses the Infinite
 * Flight Connect manifest. The socket-specific bits (sending the request, receiving
 * the next chunk with an inactivity timeout) are injected as lambdas, so the
 * framing/validation/retry logic can be unit-tested deterministically without a live
 * Infinite Flight: the client backs the lambdas with a real socket; tests back them
 * with scripted byte queues.
 *
 * Behavioural contract this encodes:
 *  - Every received chunk is appended to a persistent buffer; a frame is only
 *    surfaced once its full framed length has arrived. A partial response is never
 *    treated as an unavailable/empty manifest.
 *  - The response id must echo the manifest command id; the framed payload length,
 *    the nested string-length prefix and UTF-8 decoding are all validated, each with
 *    a distinct diagnostic event.
 *  - The request is retried once on the same connection before giving up (a
 *    stale/partial first frame right after backgrounding is common).
 *
 * Ported from `IFATCCompanion/Connect/IFManifestReader.swift`.
 */
class IFManifestReader(
    /**
     * Number of attempts on the *same* connection before failing (reconnect-and-retry
     * is the caller's responsibility). Default 2 = one initial + one retry.
     */
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {

    suspend fun read(
        sendRequest: suspend () -> Unit,
        nextChunk: suspend () -> ByteArray,
        onEvent: (IFConnectManifestEvent) -> Unit = {},
    ): List<IFManifestEntry> {
        var lastError: Throwable? = null
        for (attempt in 1..maxOf(1, maxAttempts)) {
            // Fresh buffer per attempt so a stale/partial prior response can't misalign.
            val buffer = IFConnectFrameBuffer()
            try {
                sendRequest()
                onEvent(IFConnectManifestEvent.RequestSent(attempt))
                val entries = readOnce(buffer, nextChunk, onEvent)
                onEvent(IFConnectManifestEvent.Parsed(entries.size))
                return entries
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                lastError = error
                // Fall through to the next same-connection attempt, if any.
            }
        }
        throw lastError ?: IFConnectError.ManifestUnavailable
    }

    /**
     * One attempt: pull chunks until a complete manifest frame is buffered, then
     * validate and parse it.
     */
    private suspend fun readOnce(
        buffer: IFConnectFrameBuffer,
        nextChunk: suspend () -> ByteArray,
        onEvent: (IFConnectManifestEvent) -> Unit,
    ): List<IFManifestEntry> {
        var announcedHeader = false
        while (true) {
            if (!announcedHeader) {
                buffer.peekHeader()?.let { header ->
                    announcedHeader = true
                    onEvent(IFConnectManifestEvent.HeaderReceived(header.id, header.payloadLength))
                }
            }
            when (val result = buffer.nextFrame()) {
                is IFConnectFrameBuffer.FrameResult.Frame -> {
                    if (result.id != IFConnectClient.MANIFEST_COMMAND_ID) {
                        onEvent(IFConnectManifestEvent.InvalidResponseId(result.id))
                        throw IFConnectError.DecodingFailed
                    }
                    val raw = decodeManifestString(result.payload, onEvent)
                    val entries = IFManifestParser.parse(raw)
                    if (entries.isEmpty()) throw IFConnectError.ManifestUnavailable
                    return entries
                }

                is IFConnectFrameBuffer.FrameResult.InvalidLength -> {
                    onEvent(IFConnectManifestEvent.InvalidPayloadLength(result.length))
                    throw IFConnectError.DecodingFailed
                }

                is IFConnectFrameBuffer.FrameResult.NeedMoreData -> {
                    val headerLen = IFConnectFrameBuffer.HEADER_LENGTH
                    val needTotal = result.needTotal
                    if (needTotal != null) {
                        onEvent(
                            IFConnectManifestEvent.Progress(
                                received = maxOf(0, result.have - headerLen),
                                expected = maxOf(0, needTotal - headerLen),
                            ),
                        )
                    } else {
                        onEvent(IFConnectManifestEvent.WaitingForHeader(result.have))
                    }
                    val chunk = try {
                        nextChunk()
                    } catch (error: Throwable) {
                        if (error is IFConnectError.ConnectionFailed) {
                            onEvent(IFConnectManifestEvent.ConnectionClosedEarly)
                        }
                        throw error
                    }
                    if (chunk.isEmpty()) {
                        // A non-throwing empty read means the peer closed; don't spin.
                        onEvent(IFConnectManifestEvent.ConnectionClosedEarly)
                        throw IFConnectError.ConnectionFailed("Connection closed")
                    }
                    buffer.append(chunk)
                }
            }
        }
    }

    /**
     * Strip the manifest payload's nested `Int32 length + UTF-8` framing, surfacing the
     * specific failure. Treating the whole payload as UTF-8 (without stripping the
     * length prefix) is what produced the intermittent "Manifest Unavailable".
     */
    private fun decodeManifestString(
        payload: ByteArray,
        onEvent: (IFConnectManifestEvent) -> Unit,
    ): String = when (val decoded = IFConnectStringDecoder.decodeLengthPrefixed(payload)) {
        is IFConnectStringDecoder.Decoded.Success -> {
            if (decoded.value.isEmpty()) throw IFConnectError.ManifestUnavailable
            decoded.value
        }

        is IFConnectStringDecoder.Decoded.Error -> when (val failure = decoded.failure) {
            is IFConnectStringDecoder.Failure.InvalidStringLength -> {
                onEvent(IFConnectManifestEvent.InvalidStringLength(failure.length))
                // The prefix didn't look like a valid length. Mirror the proven per-state
                // string decode and tolerate a bare-UTF-8 payload before giving up — a
                // string that parses to no entries is still rejected by the caller.
                val raw = IFConnectStringDecoder.decodeUtf8Strict(payload)
                if (raw != null && raw.isNotEmpty()) {
                    raw
                } else {
                    onEvent(IFConnectManifestEvent.Utf8DecodeFailed)
                    throw IFConnectError.DecodingFailed
                }
            }

            IFConnectStringDecoder.Failure.Utf8 -> {
                onEvent(IFConnectManifestEvent.Utf8DecodeFailed)
                throw IFConnectError.DecodingFailed
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 2
    }
}
