import Foundation

/// High-level connection state for the Infinite Flight Connect link.
enum IFConnectConnectionState: Equatable {
    case disconnected
    case discovering
    case connecting
    case connected
    case failed(String)

    var title: String {
        switch self {
        case .disconnected: return "Disconnected"
        case .discovering: return "Searching…"
        case .connecting: return "Connecting…"
        case .connected: return "Connected"
        case .failed(let reason): return "Failed: \(reason)"
        }
    }

    var isConnected: Bool { if case .connected = self { return true }; return false }
    var isActive: Bool {
        switch self {
        case .connecting, .discovering, .connected: return true
        default: return false
        }
    }
}

/// Errors surfaced by the Connect client. All are non-fatal — the app degrades
/// to manual/mock operation.
enum IFConnectError: LocalizedError {
    case notConnected
    case invalidHost
    case timeout
    case connectionFailed(String)
    case manifestUnavailable
    case unknownState
    case decodingFailed
    case cancelled

    var errorDescription: String? {
        switch self {
        case .notConnected: return "Not connected to Infinite Flight."
        case .invalidHost: return "Invalid host or port."
        case .timeout: return "The connection timed out."
        case .connectionFailed(let r): return "Connection failed: \(r)"
        case .manifestUnavailable: return "Manifest unavailable."
        case .unknownState: return "Requested state is not available."
        case .decodingFailed: return "Failed to decode a response."
        case .cancelled: return "Operation cancelled."
        }
    }
}

/// A decoded value read from a Connect state.
enum IFStateValue: Equatable {
    case bool(Bool)
    case int(Int32)
    case float(Float)
    case double(Double)
    case long(Int64)
    case string(String)

    var doubleValue: Double? {
        switch self {
        case .bool(let b): return b ? 1 : 0
        case .int(let i): return Double(i)
        case .float(let f): return Double(f)
        case .double(let d): return d
        case .long(let l): return Double(l)
        case .string: return nil
        }
    }

    var boolValue: Bool? {
        switch self {
        case .bool(let b): return b
        case .int(let i): return i != 0
        default: return nil
        }
    }

    var stringValue: String? {
        if case .string(let s) = self { return s }
        return nil
    }
}
