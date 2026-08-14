import Foundation
import Network

/// Low-level TCP client for the Infinite Flight Connect API v2, built on
/// Network.framework. Request/response framing is serialized by making this an
/// `actor`. All operations are best-effort and throw `IFConnectError` rather
/// than crashing when Infinite Flight is unavailable.
///
/// Protocol (v2):
///   - Request a state/manifest: send Int32 id (LE) + 1 byte (0 = read).
///   - Response framing: Int32 id (LE) + Int32 length (LE) + `length` payload bytes.
///   - Run a command / write: send Int32 id (LE) + 1 byte (1 = write) + payload.
///   - The manifest is requested with id == -1; its payload is a length-prefixed
///     UTF-8 string (Int32 length (LE) + bytes).
///
/// TCP delivers those framed responses as an arbitrary stream of chunks, so all
/// reads go through a persistent `IFConnectFrameBuffer`: every chunk is appended and
/// a frame is only surfaced once its full declared length has arrived. A partial
/// response is therefore never mistaken for a missing/empty one.
actor IFConnectClient {

    static let manifestCommandID: Int32 = -1

    /// The canonical TCP port for Connect API v2. The handshake always dials this
    /// unless an explicit, valid override is supplied.
    static let defaultPort = 10112

    private var connection: NWConnection?
    private let queue = DispatchQueue(label: "com.h3consultingpartners.ifatccompanion.connect")

    /// Persistent receive buffer. Holds bytes across TCP callbacks and can carry
    /// more than one frame at a time; reset whenever a new exchange begins or the
    /// link is (re)connected so a fresh request never reads stale bytes.
    private var receiveBuffer = IFConnectFrameBuffer()

    var isConnected: Bool { connection?.state == .ready }

    // MARK: - Lifecycle

    func connect(host: String, port: Int, timeout: TimeInterval = 6) async throws {
        let trimmed = host.trimmingCharacters(in: .whitespaces)
        // Connect API v2 always speaks TCP/10112; fall back to it if the supplied
        // port is missing or out of range rather than failing outright.
        let resolvedPort = (port > 0 && port <= 65535) ? port : Self.defaultPort
        guard !trimmed.isEmpty, let nwPort = NWEndpoint.Port(rawValue: UInt16(resolvedPort)) else {
            throw IFConnectError.invalidHost
        }
        disconnect()

        let conn = NWConnection(host: NWEndpoint.Host(trimmed), port: nwPort, using: .tcp)
        connection = conn
        try await withTimeout(timeout) { try await self.awaitReady() }
    }

    private func awaitReady() async throws {
        guard let conn = connection else { throw IFConnectError.notConnected }
        final class Box { var resumed = false }
        let box = Box()
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            conn.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    if !box.resumed { box.resumed = true; cont.resume() }
                case .failed(let err):
                    if !box.resumed { box.resumed = true; cont.resume(throwing: IFConnectError.connectionFailed(err.localizedDescription)) }
                case .cancelled:
                    if !box.resumed { box.resumed = true; cont.resume(throwing: IFConnectError.cancelled) }
                default:
                    break
                }
            }
            conn.start(queue: queue)
        }
    }

    func disconnect() {
        connection?.stateUpdateHandler = nil
        connection?.cancel()
        connection = nil
        // Drop any half-received frame so a later reconnect starts from a clean slate.
        receiveBuffer.reset()
    }

    private func activeConnection() throws -> NWConnection {
        guard let conn = connection, conn.state == .ready else { throw IFConnectError.notConnected }
        return conn
    }

    // MARK: - High-level requests

    /// Request and parse the Connect manifest.
    ///
    /// Uses an inactivity timeout: the read waits up to `timeout` seconds for *more*
    /// bytes, and the clock resets every time a chunk arrives — so a large manifest
    /// that trickles in over several callbacks is never cut off mid-transfer. The
    /// request is retried once on the same connection (a stale/partial first response
    /// right after backgrounding is common); reconnect-and-retry is the caller's job.
    ///
    /// `onEvent` receives granular progress for diagnostics and the "Receiving
    /// manifest…" status. It defaults to a no-op so existing callers are unaffected.
    func requestManifest(timeout: TimeInterval = 15,
                         onEvent: @Sendable (IFConnectManifestEvent) -> Void = { _ in }) async throws -> [IFManifestEntry] {
        _ = try activeConnection()
        // Delegate the framing/validation/retry to the pure, testable reader; back its
        // injected transport with this connection. Each chunk read is bounded by its
        // own `timeout`, so the inactivity clock resets every time bytes arrive.
        let reader = IFManifestReader()
        return try await reader.read(
            sendRequest: { try await self.sendStateRequest(id: Self.manifestCommandID, write: false) },
            nextChunk: { try await self.receiveChunk(timeout: timeout) },
            onEvent: onEvent)
    }

    /// How many frames belonging to some *other* state a single read will discard while
    /// looking for its own answer before giving up. A handful covers the realistic case —
    /// the late replies to one or two reads that timed out — while still bounding the read.
    static let maxMismatchedFramesPerRead = 8

    /// Frames discarded because their id didn't echo the requested state. Non-zero means
    /// the link desynchronised at least once; surfaced in Diagnostics rather than silently
    /// absorbed, because a desync used to be *invisible* — see `readState`.
    private(set) var mismatchedFrameCount = 0

    /// Read a single state by its manifest entry, decoding per its declared type.
    ///
    /// The response frame carries the id it answers, and that id is **checked**. Every read
    /// is request/response over one socket, so a reply that arrives after its read has timed
    /// out lands in the *next* read instead — and taking it on trust shifted every subsequent
    /// read by one for as long as the link stayed desynchronised, silently, since a `float`
    /// decodes cleanly whatever it actually measures. The damage is worst where a reading is
    /// interpreted rather than merely displayed: the heading is judged radians-or-degrees by
    /// magnitude (`IFStateMappingStore.noteAngleSnapshot`), so one altitude or latitude
    /// landing in a heading slot reads as proof the sim reports degrees, and every genuine
    /// radian heading afterwards — 0…6.28 — is then shown as 0–6°, pinning the aircraft
    /// symbol to north on the taxi and weather maps for the rest of the connection.
    ///
    /// Frames for other ids are dropped (bounded) until this state's own answer arrives, so a
    /// desync costs one read rather than every read that follows it.
    func readState(_ entry: IFManifestEntry, timeout: TimeInterval = 4) async throws -> IFStateValue {
        _ = try activeConnection()
        // Each state read is a self-contained request/response; start from an empty
        // buffer so a leftover byte from a prior timed-out read can't misalign it.
        receiveBuffer.reset()
        try await sendStateRequest(id: Int32(entry.id), write: false)
        // Skipping is bounded by both a frame count and the wall clock: a stale reply is
        // already sitting in the buffer, so draining it costs nothing, but a peer that keeps
        // answering with ids we didn't ask for must not stall the 1 Hz poll indefinitely.
        let deadline = Date().addingTimeInterval(timeout)
        let answer = try await IFConnectClient.payload(
            answering: Int32(entry.id),
            isExpired: { Date() >= deadline },
            nextFrame: {
                let frame = try await self.readFrame(timeout: timeout)
                return (frame.id, frame.payload)
            })
        mismatchedFrameCount += answer.mismatched
        return try decode(answer.payload, as: entry.type)
    }

    /// Pull frames from `nextFrame` until one answers `id`, discarding any that don't and
    /// reporting how many were discarded. Pure but for the injected frame source, so the
    /// resynchronisation can be exercised without a socket.
    ///
    /// Throws `decodingFailed` once the skip budget or the deadline is spent — better a read
    /// that fails than one that returns another state's number.
    static func payload(answering id: Int32,
                        maxMismatched: Int = IFConnectClient.maxMismatchedFramesPerRead,
                        isExpired: @Sendable () -> Bool = { false },
                        nextFrame: @Sendable () async throws -> (id: Int32, payload: Data))
        async throws -> (payload: Data, mismatched: Int) {
        var mismatched = 0
        while mismatched <= maxMismatched {
            let frame = try await nextFrame()
            if frame.id == id { return (frame.payload, mismatched) }
            mismatched += 1
            if isExpired() { break }
        }
        throw IFConnectError.decodingFailed
    }

    /// Read and clear the mismatched-frame tally (diagnostics only).
    func takeMismatchedFrameCount() -> Int {
        defer { mismatchedFrameCount = 0 }
        return mismatchedFrameCount
    }

    /// Run a command (write) by id. Many IF commands take no payload.
    func runCommand(id: Int) async throws {
        _ = try activeConnection()
        try await sendStateRequest(id: Int32(id), write: true)
    }

    // MARK: - Framing

    private struct Frame { let id: Int32; let payload: Data }

    private func sendStateRequest(id: Int32, write: Bool) async throws {
        var data = Data()
        data.append(littleEndian: id)
        data.append(write ? 1 : 0)
        try await send(data)
    }

    /// Read exactly one complete frame, buffering partial TCP chunks until the full
    /// framed response has arrived. `timeout` is per-chunk inactivity, so slow but
    /// steady delivery is tolerated.
    private func readFrame(timeout: TimeInterval) async throws -> Frame {
        while true {
            switch receiveBuffer.nextFrame() {
            case .frame(let id, let payload):
                return Frame(id: id, payload: payload)
            case .invalidLength:
                throw IFConnectError.decodingFailed
            case .needMoreData:
                let chunk = try await withTimeout(timeout) { try await self.receiveChunk() }
                receiveBuffer.append(chunk)
            }
        }
    }

    private func decode(_ data: Data, as type: IFDataType) throws -> IFStateValue {
        switch type {
        case .boolean:
            guard let b = data.first else { throw IFConnectError.decodingFailed }
            return .bool(b != 0)
        case .int32:
            guard data.count >= 4 else { throw IFConnectError.decodingFailed }
            return .int(data.readInt32LE(at: 0))
        case .float:
            guard data.count >= 4 else { throw IFConnectError.decodingFailed }
            return .float(Float(bitPattern: UInt32(bitPattern: data.readInt32LE(at: 0))))
        case .double:
            guard data.count >= 8 else { throw IFConnectError.decodingFailed }
            return .double(Double(bitPattern: data.readUInt64LE(at: 0)))
        case .long:
            guard data.count >= 8 else { throw IFConnectError.decodingFailed }
            return .long(Int64(bitPattern: data.readUInt64LE(at: 0)))
        case .string:
            if data.count >= 4 {
                let strLen = Int(data.readInt32LE(at: 0))
                if strLen >= 0, data.count >= 4 + strLen {
                    let s = String(data: data.subdata(in: 4..<(4 + strLen)), encoding: .utf8) ?? ""
                    return .string(s)
                }
            }
            return .string(String(data: data, encoding: .utf8) ?? "")
        case .unknown:
            throw IFConnectError.decodingFailed
        }
    }

    // MARK: - Socket primitives

    private func send(_ data: Data) async throws {
        let conn = try activeConnection()
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            conn.send(content: data, completion: .contentProcessed { error in
                if let error { cont.resume(throwing: IFConnectError.connectionFailed(error.localizedDescription)) }
                else { cont.resume() }
            })
        }
    }

    /// Receive the next chunk, bounded by a per-chunk inactivity `timeout`. Because
    /// each call gets its own timeout, the clock effectively resets every time bytes
    /// arrive — a large response that trickles in over many chunks is not cut off.
    private func receiveChunk(timeout: TimeInterval) async throws -> Data {
        try await withTimeout(timeout) { try await self.receiveChunk() }
    }

    /// Receive the next available chunk of bytes (up to 64 KB). Callers append the
    /// result to `receiveBuffer` and re-attempt frame extraction; a closed connection
    /// throws so the manifest path can report "closed before full manifest".
    private func receiveChunk() async throws -> Data {
        let conn = try activeConnection()
        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Data, Error>) in
            conn.receive(minimumIncompleteLength: 1, maximumLength: 65536) { content, _, isComplete, error in
                if let error { cont.resume(throwing: IFConnectError.connectionFailed(error.localizedDescription)); return }
                if let content, !content.isEmpty { cont.resume(returning: content); return }
                if isComplete { cont.resume(throwing: IFConnectError.connectionFailed("Connection closed")); return }
                cont.resume(returning: Data())
            }
        }
    }

    // MARK: - Timeout helper

    private func withTimeout<T: Sendable>(_ seconds: TimeInterval, _ operation: @escaping @Sendable () async throws -> T) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { try await operation() }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                throw IFConnectError.timeout
            }
            guard let result = try await group.next() else { throw IFConnectError.timeout }
            group.cancelAll()
            return result
        }
    }
}
