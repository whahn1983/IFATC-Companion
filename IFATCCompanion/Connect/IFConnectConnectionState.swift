import Foundation

/// High-level connection state for the Infinite Flight Connect link.
enum IFConnectConnectionState: Equatable {
    case disconnected
    case discovering
    case connecting
    case connected
    /// `reason` is the short, space-constrained summary shown in compact UI (e.g.
    /// the ATC view status pill). `detail`, when present, is the fuller message —
    /// including any recovery instructions — surfaced where there's room (Settings).
    case failed(String, detail: String? = nil)

    /// Compact status string for space-constrained UI. Always uses the short reason.
    var title: String {
        switch self {
        case .disconnected: return "Disconnected"
        case .discovering: return "Searching…"
        case .connecting: return "Connecting…"
        case .connected: return "Connected"
        case .failed(let reason, _): return "Failed: \(reason)"
        }
    }

    /// Fuller status string for UI with room for detail (e.g. the Settings page).
    /// Falls back to `title` when there's no extended detail.
    var detailedTitle: String {
        switch self {
        case .failed(let reason, let detail): return "Failed: \(detail ?? reason)"
        default: return title
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
        case .manifestUnavailable: return "Manifest Unavailable"
        case .unknownState: return "Requested state is not available."
        case .decodingFailed: return "Failed to decode a response."
        case .cancelled: return "Operation cancelled."
        }
    }

    var recoverySuggestion: String? {
        switch self {
        case .manifestUnavailable:
            return "Try force closing Infinite Flight and IFATC Companion, then open Infinite Flight first and then the Companion again."
        default:
            return nil
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
