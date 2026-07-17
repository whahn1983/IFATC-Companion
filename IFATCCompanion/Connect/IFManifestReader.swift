import Foundation

/// Transport-agnostic engine that requests, buffers, validates and parses the
/// Infinite Flight Connect manifest. The socket-specific bits (sending the request,
/// receiving the next chunk with an inactivity timeout) are injected as closures, so
/// the framing/validation/retry logic can be unit-tested deterministically without a
/// live Infinite Flight: the actor backs the closures with a real `NWConnection`;
/// tests back them with scripted byte queues.
///
/// Behavioural contract this encodes:
///   - Every received chunk is appended to a persistent buffer; a frame is only
///     surfaced once its full framed length has arrived. A partial response is never
///     treated as an unavailable/empty manifest.
///   - The response id must echo the manifest command id; the framed payload length,
///     the nested string-length prefix and UTF-8 decoding are all validated, each
///     with a distinct diagnostic event.
///   - The request is retried once on the same connection before giving up (a
///     stale/partial first frame right after backgrounding is common).
struct IFManifestReader {

    /// Number of attempts on the *same* connection before failing (reconnect-and-
    /// retry is the caller's responsibility). Default 2 = one initial + one retry.
    var maxAttempts: Int = 2

    /// Read the manifest.
    /// - Parameters:
    ///   - sendRequest: transmit the manifest request bytes.
    ///   - nextChunk: return the next chunk of received bytes, or throw. A thrown
    ///     `IFConnectError.timeout` models the inactivity timeout firing; a thrown
    ///     `IFConnectError.connectionFailed` models the socket closing.
    ///   - onEvent: granular progress for diagnostics / the "Receiving manifest…" UI.
    func read(sendRequest: @Sendable () async throws -> Void,
              nextChunk: @Sendable () async throws -> Data,
              onEvent: @Sendable (IFConnectManifestEvent) -> Void) async throws -> [IFManifestEntry] {
        var lastError: Error?
        for attempt in 1...max(1, maxAttempts) {
            // Fresh buffer per attempt so a stale/partial prior response can't misalign.
            var buffer = IFConnectFrameBuffer()
            do {
                try await sendRequest()
                onEvent(.requestSent(attempt: attempt))
                let entries = try await readOnce(buffer: &buffer, nextChunk: nextChunk, onEvent: onEvent)
                onEvent(.parsed(stateCount: entries.count))
                return entries
            } catch {
                lastError = error
                // Fall through to the next same-connection attempt, if any.
            }
        }
        throw lastError ?? IFConnectError.manifestUnavailable
    }

    /// One attempt: pull chunks until a complete manifest frame is buffered, then
    /// validate and parse it.
    private func readOnce(buffer: inout IFConnectFrameBuffer,
                          nextChunk: @Sendable () async throws -> Data,
                          onEvent: @Sendable (IFConnectManifestEvent) -> Void) async throws -> [IFManifestEntry] {
        var announcedHeader = false
        while true {
            if !announcedHeader, let header = buffer.peekHeader() {
                announcedHeader = true
                onEvent(.headerReceived(id: header.id, payloadLength: header.payloadLength))
            }
            switch buffer.nextFrame() {
            case .frame(let id, let payload):
                guard id == IFConnectClient.manifestCommandID else {
                    onEvent(.invalidResponseID(id))
                    throw IFConnectError.decodingFailed
                }
                let raw = try decodeManifestString(payload, onEvent: onEvent)
                let entries = IFManifestParser.parse(raw)
                guard !entries.isEmpty else { throw IFConnectError.manifestUnavailable }
                return entries

            case .invalidLength(let len):
                onEvent(.invalidPayloadLength(len))
                throw IFConnectError.decodingFailed

            case .needMoreData(let have, let needTotal):
                let headerLen = IFConnectFrameBuffer.headerLength
                if let needTotal {
                    onEvent(.progress(received: max(0, have - headerLen),
                                      expected: max(0, needTotal - headerLen)))
                } else {
                    onEvent(.waitingForHeader(received: have))
                }
                let chunk: Data
                do {
                    chunk = try await nextChunk()
                } catch let error {
                    if case IFConnectError.connectionFailed = error { onEvent(.connectionClosedEarly) }
                    throw error
                }
                guard !chunk.isEmpty else {
                    // A non-throwing empty read means the peer closed; don't spin.
                    onEvent(.connectionClosedEarly)
                    throw IFConnectError.connectionFailed("Connection closed")
                }
                buffer.append(chunk)
            }
        }
    }

    /// Strip the manifest payload's nested `Int32 length + UTF-8` framing, surfacing
    /// the specific failure. Treating the whole payload as UTF-8 (without stripping
    /// the length prefix) is what produced the intermittent "Manifest Unavailable".
    private func decodeManifestString(_ payload: Data,
                                      onEvent: @Sendable (IFConnectManifestEvent) -> Void) throws -> String {
        switch IFConnectStringDecoder.decodeLengthPrefixed(payload) {
        case .success(let s):
            guard !s.isEmpty else { throw IFConnectError.manifestUnavailable }
            return s
        case .failure(.invalidStringLength(let n)):
            onEvent(.invalidStringLength(n))
            // The prefix didn't look like a valid length. Mirror the proven per-state
            // string decode and tolerate a bare-UTF-8 payload before giving up — a
            // string that parses to no entries is still rejected by the caller.
            if let raw = String(data: payload, encoding: .utf8), !raw.isEmpty {
                return raw
            }
            onEvent(.utf8DecodeFailed)
            throw IFConnectError.decodingFailed
        case .failure(.utf8):
            onEvent(.utf8DecodeFailed)
            throw IFConnectError.decodingFailed
        }
    }
}
