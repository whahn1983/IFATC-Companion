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

    static let empty = WeatherProviderDiagnostics()

    /// Human-readable coverage yes/no for the panel.
    var coverageText: String { radarCoverageAvailable ? "Yes" : "No" }
}
