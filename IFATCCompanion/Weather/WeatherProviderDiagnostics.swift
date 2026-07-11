import Foundation

/// A read-only snapshot of the weather/radar provider state for the Weather
/// Diagnostics panel. Assembled by `AppModel`; purely informational.
struct WeatherProviderDiagnostics {
    var radarSource: String = "NOAA/NWS"
    var radarCoverageAvailable: Bool = false
    var lastRadarUpdate: Date?
    var lastAviationUpdate: Date?
    var hazardCount: Int = 0
    var routeConflictStatus: String = "No conflict"
    var selectedRejoinFix: String?
    var lastDeviationState: WeatherDeviationState = .none
    var providerError: String?
    var coverageMessage: String?

    /// Actual radar composite bytes downloaded (latest download / running session
    /// total). Only the EUMETNET OPERA / CIRRUS composite is megabyte-scale — NOAA and
    /// NASA return small server-cropped PNGs — so this measures real ORD data usage.
    var radarLastBytes: Int = 0
    var radarSessionBytes: Int = 0

    static let empty = WeatherProviderDiagnostics()

    /// Human-readable coverage yes/no for the panel.
    var coverageText: String { radarCoverageAvailable ? "Yes" : "No" }

    /// "1.8 MB (last 1.8 MB)"-style summary of composite data usage, or nil when
    /// nothing has been downloaded (NOAA/NASA/mock, or no composite fetched yet).
    var radarDataUsageText: String? {
        guard radarSessionBytes > 0 else { return nil }
        let total = Self.formatBytes(radarSessionBytes)
        let last = Self.formatBytes(radarLastBytes)
        return "\(total) this session (last \(last))"
    }

    static func formatBytes(_ bytes: Int) -> String {
        let mb = Double(bytes) / (1_024 * 1_024)
        if mb >= 1 { return String(format: "%.1f MB", mb) }
        let kb = Double(bytes) / 1_024
        return String(format: "%.0f KB", kb)
    }
}
