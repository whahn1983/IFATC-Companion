import XCTest
@testable import IFATCCompanion

/// Covers the Connect API v2 manifest framing/buffering fix for the intermittent
/// "Manifest Unavailable" error. Two failure classes are exercised:
///
///  1. Framing — the full framed response may arrive across several TCP callbacks (or
///     several frames may arrive in one). A partial response must never be treated as
///     an unavailable/empty manifest, and the read must resume once more bytes land.
///  2. Decoding — the manifest payload is a *length-prefixed* UTF-8 string. Decoding
///     the whole payload as UTF-8 without stripping the nested `Int32` length prefix
///     was the root cause: whenever a length byte fell in 0x80–0xBF (a lone UTF-8
///     continuation byte) the decode returned nil → "Manifest Unavailable",
///     consistently for a given manifest size and shifting when the aircraft/version
///     changed that size.
final class IFConnectManifestBufferingTests: XCTestCase {

    // MARK: - Wire-format builders

    /// A response frame: `Int32 id (LE) + Int32 payloadLength (LE) + payload`.
    private func frame(id: Int32, payload: Data) -> Data {
        var d = Data()
        d.append(littleEndian: id)
        d.append(littleEndian: Int32(payload.count))
        d.append(payload)
        return d
    }

    /// A Connect length-prefixed string payload: `Int32 length (LE) + UTF-8 bytes`.
    private func lengthPrefixed(_ s: String) -> Data {
        let body = Data(s.utf8)
        var d = Data()
        d.append(littleEndian: Int32(body.count))
        d.append(body)
        return d
    }

    /// A complete manifest frame carrying `body` (id defaults to the manifest id, -1).
    private func manifestFrame(_ body: String, id: Int32 = IFConnectClient.manifestCommandID) -> Data {
        frame(id: id, payload: lengthPrefixed(body))
    }

    private let sampleManifest = "0,1,aircraft/0/latitude\n1,2,aircraft/0/groundspeed\n2,4,aircraft/0/name"
    private let sampleEntryCount = 3

    // MARK: - Test doubles

    /// Records every emitted manifest event for assertions. `@unchecked Sendable`:
    /// the reader invokes it sequentially (never concurrently) within one `read`.
    private final class EventLog: @unchecked Sendable {
        private(set) var events: [IFConnectManifestEvent] = []
        func record(_ e: IFConnectManifestEvent) { events.append(e) }
        func contains(where predicate: (IFConnectManifestEvent) -> Bool) -> Bool {
            events.contains(where: predicate)
        }
        var parsedCount: Int? {
            for e in events { if case .parsed(let n) = e { return n } }
            return nil
        }
    }

    /// A scripted transport: `send()` counts requests, `next()` yields the queued
    /// chunks/errors in order (a `Result.failure` models a timeout or a close).
    private final class ScriptedTransport: @unchecked Sendable {
        private let script: [Result<Data, Error>]
        private var index = 0
        private(set) var sendCount = 0
        init(_ script: [Result<Data, Error>]) { self.script = script }
        func send() { sendCount += 1 }
        func next() throws -> Data {
            guard index < script.count else { throw IFConnectError.connectionFailed("Connection closed") }
            defer { index += 1 }
            return try script[index].get()
        }
    }

    /// Drive `IFManifestReader` over a scripted transport.
    private func run(_ reader: IFManifestReader,
                     _ transport: ScriptedTransport,
                     _ log: EventLog) async throws -> [IFManifestEntry] {
        try await reader.read(
            sendRequest: { transport.send() },
            nextChunk: { try transport.next() },
            onEvent: { log.record($0) })
    }

    // MARK: - 1. Manifest split across multiple receive callbacks

    func testManifestSplitAcrossMultipleCallbacks() async throws {
        let full = manifestFrame(sampleManifest)
        // Split the single frame into three arbitrary, uneven chunks.
        let c1 = full.subdata(in: 0..<5)
        let c2 = full.subdata(in: 5..<11)
        let c3 = full.subdata(in: 11..<full.count)
        let transport = ScriptedTransport([.success(c1), .success(c2), .success(c3)])
        let log = EventLog()

        let entries = try await run(IFManifestReader(), transport, log)

        XCTAssertEqual(entries.count, sampleEntryCount, "the reassembled manifest must parse fully")
        XCTAssertEqual(log.parsedCount, sampleEntryCount)
        XCTAssertTrue(log.contains { if case .headerReceived = $0 { return true }; return false })
        XCTAssertTrue(log.contains { if case .progress = $0 { return true }; return false },
                      "partial payload must report progress, not failure")
    }

    // MARK: - 2. Header split across callbacks

    func testHeaderSplitAcrossCallbacks() async throws {
        let full = manifestFrame(sampleManifest)
        // Split the 8-byte header itself: 3 bytes, then 5 bytes, then the payload.
        let c1 = full.subdata(in: 0..<3)
        let c2 = full.subdata(in: 3..<8)
        let c3 = full.subdata(in: 8..<full.count)
        let transport = ScriptedTransport([.success(c1), .success(c2), .success(c3)])
        let log = EventLog()

        let entries = try await run(IFManifestReader(), transport, log)

        XCTAssertEqual(entries.count, sampleEntryCount)
        XCTAssertTrue(log.contains { if case .waitingForHeader(let n) = $0 { return n > 0 && n < 8 }; return false },
                      "an incomplete header must report waiting-for-header, not a bad frame")
    }

    // MARK: - 3. Multiple frames in one callback (buffer level)

    func testMultipleFramesInOneBuffer() {
        var buffer = IFConnectFrameBuffer()
        let a = frame(id: 7, payload: Data([1, 2, 3]))
        let b = frame(id: 9, payload: Data([4, 5]))
        // Both frames plus the first byte of a third arrive in a single chunk.
        buffer.append(a + b + Data([0x00]))

        guard case .frame(let id1, let p1) = buffer.nextFrame() else { return XCTFail("first frame missing") }
        XCTAssertEqual(id1, 7)
        XCTAssertEqual(p1, Data([1, 2, 3]))

        guard case .frame(let id2, let p2) = buffer.nextFrame() else { return XCTFail("second frame missing") }
        XCTAssertEqual(id2, 9)
        XCTAssertEqual(p2, Data([4, 5]))

        // The dangling byte of the next frame's header is retained, not misparsed.
        guard case .needMoreData(let have, let need) = buffer.nextFrame() else {
            return XCTFail("trailing partial header must be retained")
        }
        XCTAssertEqual(have, 1)
        XCTAssertNil(need, "the header length isn't known yet from a single byte")
    }

    // MARK: - 4. Incomplete payload timeout

    func testIncompletePayloadTimesOutWithoutPartialManifest() async throws {
        // Header declares a 100-byte payload, but only 10 bytes arrive, then the read
        // times out (inactivity). A single same-connection attempt so the timeout is
        // the terminal outcome.
        var reader = IFManifestReader()
        reader.maxAttempts = 1
        let header = manifestFrame(String(repeating: "x", count: 100))   // well-formed, 100+ payload
        let partial = header.subdata(in: 0..<18)                          // 8-byte header + 10 payload bytes
        let transport = ScriptedTransport([.success(partial), .failure(IFConnectError.timeout)])
        let log = EventLog()

        do {
            _ = try await run(reader, transport, log)
            XCTFail("an incomplete payload must not resolve to a manifest")
        } catch {
            guard case IFConnectError.timeout = error else {
                return XCTFail("expected the inactivity timeout to surface, got \(error)")
            }
        }
        XCTAssertNil(log.parsedCount, "a partial payload must never be reported as parsed")
        XCTAssertTrue(log.contains { if case .progress = $0 { return true }; return false },
                      "the partial payload must have been recorded as in-progress, not unavailable")
    }

    // MARK: - 5. Invalid payload length

    func testInvalidPayloadLengthIsRejected() async throws {
        // A frame whose declared payload length is absurd (corrupt/misframed stream).
        var d = Data()
        d.append(littleEndian: IFConnectClient.manifestCommandID)
        d.append(littleEndian: Int32(500_000_000))           // > maxPayloadLength
        d.append(Data([0x01, 0x02, 0x03, 0x04]))
        let transport = ScriptedTransport([.success(d)])
        let log = EventLog()
        var reader = IFManifestReader()
        reader.maxAttempts = 1

        do {
            _ = try await run(reader, transport, log)
            XCTFail("an oversized payload length must be rejected")
        } catch {
            guard case IFConnectError.decodingFailed = error else {
                return XCTFail("expected decodingFailed, got \(error)")
            }
        }
        XCTAssertTrue(log.contains { if case .invalidPayloadLength(let n) = $0 { return n == 500_000_000 }; return false })
    }

    /// Buffer-level: a negative declared length is invalid too.
    func testNegativePayloadLengthIsInvalidAtBufferLevel() {
        var buffer = IFConnectFrameBuffer()
        var d = Data()
        d.append(littleEndian: Int32(3))
        d.append(littleEndian: Int32(-1))                    // negative payload length
        buffer.append(d)
        guard case .invalidLength(let n) = buffer.nextFrame() else {
            return XCTFail("negative length must be reported invalid")
        }
        XCTAssertEqual(n, -1)
    }

    // MARK: - 6. Successful manifest parse after retry

    func testSuccessfulManifestParseAfterRetry() async throws {
        // Attempt 1 receives a well-formed frame with the WRONG response id (as can
        // happen with a stale/garbled first response); attempt 2 receives the real
        // manifest. The reader must retry once on the same connection and succeed.
        let wrongID = frame(id: 42, payload: lengthPrefixed("garbage"))
        let good = manifestFrame(sampleManifest)
        let transport = ScriptedTransport([.success(wrongID), .success(good)])
        let log = EventLog()

        let entries = try await run(IFManifestReader(), transport, log)

        XCTAssertEqual(entries.count, sampleEntryCount)
        XCTAssertEqual(transport.sendCount, 2, "the request must have been retried once on the same connection")
        XCTAssertTrue(log.contains { if case .invalidResponseID(let id) = $0 { return id == 42 }; return false })
        XCTAssertTrue(log.contains { if case .requestSent(let a) = $0 { return a == 2 }; return false })
        XCTAssertEqual(log.parsedCount, sampleEntryCount)
    }

    func testRetryExhaustionSurfacesLastError() async throws {
        // Both attempts get a wrong-id frame → the reader gives up after the retry.
        let wrong = frame(id: 42, payload: lengthPrefixed("garbage"))
        let transport = ScriptedTransport([.success(wrong), .success(wrong)])
        let log = EventLog()

        do {
            _ = try await run(IFManifestReader(), transport, log)
            XCTFail("two bad responses must fail the read")
        } catch {
            guard case IFConnectError.decodingFailed = error else {
                return XCTFail("expected decodingFailed, got \(error)")
            }
        }
        XCTAssertEqual(transport.sendCount, 2)
    }

    // MARK: - Root-cause regression: nested length prefix must be stripped

    func testManifestLengthPrefixStrippedEvenWhenLengthByteBreaksWholeUTF8() {
        // Choose a body length whose little-endian bytes contain 0x9C — a lone UTF-8
        // continuation byte. Decoding the WHOLE payload as UTF-8 fails on that byte
        // (the historical bug); stripping the nested length prefix first succeeds.
        let body = String(repeating: "a", count: 0x9C)      // 156 bytes → length LE = 9C 00 00 00
        let payload = lengthPrefixed(body)

        // Sanity: whole-payload UTF-8 decode is exactly what used to fail.
        XCTAssertNil(String(data: payload, encoding: .utf8),
                     "precondition: the length prefix makes a whole-payload UTF-8 decode fail")

        switch IFConnectStringDecoder.decodeLengthPrefixed(payload) {
        case .success(let s):
            XCTAssertEqual(s, body, "the nested length prefix must be stripped before decoding")
        case .failure(let f):
            XCTFail("length-prefixed decode must succeed, got \(f)")
        }
    }

    func testInvalidStringLengthReported() {
        // A payload whose nested length prefix overruns the available bytes.
        var payload = Data()
        payload.append(littleEndian: Int32(1000))            // claims 1000 bytes …
        payload.append(Data([0x41, 0x42, 0x43]))             // … but only 3 follow
        switch IFConnectStringDecoder.decodeLengthPrefixed(payload) {
        case .failure(.invalidStringLength(let n)):
            XCTAssertEqual(n, 1000)
        default:
            XCTFail("an overrunning string length must be reported invalid")
        }
    }

    func testUTF8DecodeFailureReported() {
        // A valid length prefix over bytes that are not valid UTF-8 (a lone 0xC3).
        var payload = Data()
        payload.append(littleEndian: Int32(1))
        payload.append(Data([0xC3]))                         // truncated multibyte sequence
        switch IFConnectStringDecoder.decodeLengthPrefixed(payload) {
        case .failure(.utf8):
            break
        default:
            XCTFail("invalid UTF-8 must be reported as a utf8 failure")
        }
    }

    func testManifestFrameEndToEndParsesEntries() async throws {
        // A single-callback happy path: one complete frame → parsed entries.
        let transport = ScriptedTransport([.success(manifestFrame(sampleManifest))])
        let log = EventLog()
        let entries = try await run(IFManifestReader(), transport, log)
        XCTAssertEqual(entries.map(\.id), [0, 1, 2])
        XCTAssertEqual(entries[2].name, "aircraft/0/name")
        XCTAssertEqual(entries[2].type, .string)
    }
}
