import Foundation

/// Accumulates raw TCP bytes for the Infinite Flight Connect API v2 wire protocol
/// and hands back complete response frames. Pure and synchronous so the framing
/// logic can be unit-tested independently of the socket.
///
/// A response frame is `Int32 id (LE) + Int32 payloadLength (LE) + payloadLength
/// bytes`. TCP makes no promise about how those bytes are split across receive
/// callbacks: a single `recv` may deliver half a header, a header plus a partial
/// payload, or several whole frames at once. This buffer absorbs that by appending
/// every chunk and only surfacing a frame once its full length has arrived — a
/// partial response is never mistaken for a complete (or empty) one.
struct IFConnectFrameBuffer {

    /// Upper bound on a single frame's payload. Infinite Flight's largest response
    /// (the manifest, or a full-info flight plan) is comfortably under this; a larger
    /// declared length means the stream is misframed/corrupt, so we reject it rather
    /// than buffer unboundedly waiting for bytes that will never come.
    static let maxPayloadLength = 16 * 1024 * 1024   // 16 MB

    /// Number of bytes in a frame header: Int32 id + Int32 payload length.
    static let headerLength = 8

    private(set) var buffer = Data()

    /// Outcome of trying to pull one frame off the front of the buffer.
    enum FrameResult: Equatable {
        /// A complete frame was extracted and removed from the buffer.
        case frame(id: Int32, payload: Data)
        /// Not enough bytes yet. `have` is what's buffered; `needTotal` is the full
        /// frame size (header + payload) once the header is known, else nil.
        case needMoreData(have: Int, needTotal: Int?)
        /// The declared payload length is negative or exceeds `maxPayloadLength`;
        /// the stream is corrupt and cannot be recovered by waiting.
        case invalidLength(Int)
    }

    /// Bytes currently buffered but not yet consumed.
    var count: Int { buffer.count }

    var isEmpty: Bool { buffer.isEmpty }

    /// Append a freshly received TCP chunk.
    mutating func append(_ data: Data) {
        guard !data.isEmpty else { return }
        buffer.append(data)
    }

    /// Discard all buffered bytes — used when reconnecting so a new exchange never
    /// reads stale bytes left over from a previous, misaligned one.
    mutating func reset() {
        buffer.removeAll(keepingCapacity: false)
    }

    /// Peek the front frame's header (id + declared payload length) without
    /// consuming anything. `nil` until the full 8-byte header has arrived.
    func peekHeader() -> (id: Int32, payloadLength: Int)? {
        guard buffer.count >= Self.headerLength else { return nil }
        let id = buffer.readInt32LE(at: 0)
        let length = Int(buffer.readInt32LE(at: 4))
        return (id, length)
    }

    /// Try to pull one complete frame off the front of the buffer. On success the
    /// frame's bytes are removed and any trailing bytes (a following frame, or the
    /// start of one) remain buffered for the next call.
    mutating func nextFrame() -> FrameResult {
        let available = buffer.count
        guard available >= Self.headerLength else {
            return .needMoreData(have: available, needTotal: nil)
        }
        let id = buffer.readInt32LE(at: 0)
        let length = Int(buffer.readInt32LE(at: 4))
        guard length >= 0, length <= Self.maxPayloadLength else {
            return .invalidLength(length)
        }
        let total = Self.headerLength + length
        guard available >= total else {
            return .needMoreData(have: available, needTotal: total)
        }
        let base = buffer.startIndex
        let payload = length == 0
            ? Data()
            : Data(buffer[(base + Self.headerLength)..<(base + total)])
        // Rebuild from the remaining slice so the buffer's indices stay 0-based.
        buffer = Data(buffer[(base + total)...])
        return .frame(id: id, payload: payload)
    }
}

/// Decodes a Connect "string" payload. Infinite Flight length-prefixes strings on
/// the wire as `Int32 length (LE) + UTF-8 bytes`. The manifest (state id -1) is such
/// a string, and *not* stripping that nested length prefix before UTF-8 decoding is
/// what produced intermittent "Manifest Unavailable": whenever a byte of the length
/// prefix fell in 0x80–0xBF (a lone UTF-8 continuation byte) the whole-payload decode
/// returned nil, consistently for a given manifest size and shifting when a different
/// aircraft/version changed that size.
enum IFConnectStringDecoder {

    enum Failure: Error, Equatable {
        /// The nested Int32 length prefix is negative or overruns the payload.
        case invalidStringLength(Int)
        /// The bytes are not valid UTF-8.
        case utf8
    }

    /// Decode a length-prefixed string payload. When the payload is too short to
    /// carry a prefix it is treated as a bare UTF-8 string (older/edge captures).
    static func decodeLengthPrefixed(_ payload: Data) -> Result<String, Failure> {
        guard payload.count >= 4 else {
            guard let s = String(data: payload, encoding: .utf8) else { return .failure(.utf8) }
            return .success(s)
        }
        let declared = Int(payload.readInt32LE(at: 0))
        guard declared >= 0, declared <= payload.count - 4 else {
            return .failure(.invalidStringLength(declared))
        }
        let base = payload.startIndex + 4
        let slice = Data(payload[base..<(base + declared)])
        guard let s = String(data: slice, encoding: .utf8) else { return .failure(.utf8) }
        return .success(s)
    }
}

/// Granular progress/diagnostic events emitted while requesting and reading the
/// Connect manifest. The client emits these; `IFConnectManager` maps them to the
/// Diagnostics log and to the "Receiving manifest…" user-facing status. Value-typed
/// so it crosses the actor boundary without ceremony.
enum IFConnectManifestEvent: Sendable, Equatable {
    /// The manifest request bytes were sent (attempt 1 or the same-connection retry).
    case requestSent(attempt: Int)
    /// The 8-byte response header arrived: echoed id and declared payload length.
    case headerReceived(id: Int32, payloadLength: Int)
    /// More payload bytes arrived; `received`/`expected` are payload byte counts.
    case progress(received: Int, expected: Int)
    /// A complete frame hasn't arrived yet and we're still waiting on the header.
    case waitingForHeader(received: Int)
    /// The response id did not echo the manifest command id (-1).
    case invalidResponseID(Int32)
    /// The framed payload length was negative or implausibly large.
    case invalidPayloadLength(Int)
    /// The nested string-length prefix was negative or overran the payload.
    case invalidStringLength(Int)
    /// The payload bytes were not valid UTF-8.
    case utf8DecodeFailed
    /// The connection closed before the full manifest had arrived.
    case connectionClosedEarly
    /// The manifest parsed successfully; `stateCount` entries were resolved.
    case parsed(stateCount: Int)
}

// MARK: - Little-endian byte helpers

extension Data {
    mutating func append(littleEndian value: Int32) {
        var le = value.littleEndian
        Swift.withUnsafeBytes(of: &le) { append(contentsOf: $0) }
    }

    func readInt32LE(at offset: Int) -> Int32 {
        let start = startIndex.advanced(by: offset)
        var value: UInt32 = 0
        for i in 0..<4 { value |= UInt32(self[start.advanced(by: i)]) << (8 * i) }
        return Int32(bitPattern: value)
    }

    func readUInt64LE(at offset: Int) -> UInt64 {
        let start = startIndex.advanced(by: offset)
        var value: UInt64 = 0
        for i in 0..<8 { value |= UInt64(self[start.advanced(by: i)]) << (8 * i) }
        return value
    }
}
