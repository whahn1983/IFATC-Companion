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
///   - The manifest is requested with id == -1; its payload is a UTF-8 string.
actor IFConnectClient {

    static let manifestCommandID: Int32 = -1

    private var connection: NWConnection?
    private let queue = DispatchQueue(label: "com.h3consultingpartners.ifatccompanion.connect")

    var isConnected: Bool { connection?.state == .ready }

    // MARK: - Lifecycle

    func connect(host: String, port: Int, timeout: TimeInterval = 6) async throws {
        let trimmed = host.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, port > 0, let nwPort = NWEndpoint.Port(rawValue: UInt16(truncatingIfNeeded: port)) else {
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
    }

    private func activeConnection() throws -> NWConnection {
        guard let conn = connection, conn.state == .ready else { throw IFConnectError.notConnected }
        return conn
    }

    // MARK: - High-level requests

    /// Request and parse the Connect manifest.
    func requestManifest(timeout: TimeInterval = 8) async throws -> [IFManifestEntry] {
        _ = try activeConnection()
        try await sendStateRequest(id: Self.manifestCommandID, write: false)
        let frame = try await withTimeout(timeout) { try await self.readFrame() }
        guard let raw = String(data: frame.payload, encoding: .utf8), !raw.isEmpty else {
            throw IFConnectError.manifestUnavailable
        }
        let entries = IFManifestParser.parse(raw)
        if entries.isEmpty { throw IFConnectError.manifestUnavailable }
        return entries
    }

    /// Read a single state by its manifest entry, decoding per its declared type.
    func readState(_ entry: IFManifestEntry, timeout: TimeInterval = 4) async throws -> IFStateValue {
        _ = try activeConnection()
        try await sendStateRequest(id: Int32(entry.id), write: false)
        let frame = try await withTimeout(timeout) { try await self.readFrame() }
        return try decode(frame.payload, as: entry.type)
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

    private func readFrame() async throws -> Frame {
        let header = try await receive(exactly: 8)
        let id = header.readInt32LE(at: 0)
        let length = header.readInt32LE(at: 4)
        guard length >= 0, length < 5_000_000 else { throw IFConnectError.decodingFailed }
        let payload = length == 0 ? Data() : try await receive(exactly: Int(length))
        return Frame(id: id, payload: payload)
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

    private func receive(exactly count: Int) async throws -> Data {
        var buffer = Data()
        buffer.reserveCapacity(count)
        while buffer.count < count {
            let conn = try activeConnection()
            let remaining = count - buffer.count
            let chunk: Data = try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Data, Error>) in
                conn.receive(minimumIncompleteLength: 1, maximumLength: remaining) { content, _, isComplete, error in
                    if let error { cont.resume(throwing: IFConnectError.connectionFailed(error.localizedDescription)); return }
                    if let content, !content.isEmpty { cont.resume(returning: content); return }
                    if isComplete { cont.resume(throwing: IFConnectError.connectionFailed("Connection closed")); return }
                    cont.resume(returning: Data())
                }
            }
            if chunk.isEmpty { throw IFConnectError.connectionFailed("Connection closed") }
            buffer.append(chunk)
        }
        return buffer
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

// MARK: - Byte helpers

private extension Data {
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
