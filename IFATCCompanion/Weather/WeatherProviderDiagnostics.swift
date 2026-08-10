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

    /// The inputs that turn a mint-line leg (a **true** course) into the heading spoken to
    /// the pilot: the wind solved from the aircraft's own wind triangle, the local magnetic
    /// variation read from the sim's two headings, and the crab those produce for the leg
    /// currently assigned. None of it was visible anywhere before, so a vector that came out
    /// pointing the wrong way could only be argued about — these rows make the correction
    /// checkable against what Infinite Flight itself is showing.
    var solvedWindFromDegrees: Double?
    var solvedWindKnots: Double?
    var magneticVariationEast: Double?
    /// The true course of the leg last assigned, and the magnetic heading actually spoken.
    var lastAssignedTrueCourse: Double?
    var lastAssignedHeading: Int?

    static let empty = WeatherProviderDiagnostics()

    /// Human-readable coverage yes/no for the panel.
    var coverageText: String { radarCoverageAvailable ? "Yes" : "No" }

    /// "270° / 85 kt" — the solved wind, or nil until the triangle has a usable sample.
    var solvedWindText: String? {
        guard let from = solvedWindFromDegrees, let kt = solvedWindKnots else { return nil }
        return String(format: "%03.0f° / %.0f kt", from, kt)
    }

    /// "6.2°E" / "3.1°W" — the variation the magnetic conversion is using.
    var magneticVariationText: String? {
        guard let v = magneticVariationEast else { return nil }
        return String(format: "%.1f°%@", abs(v), v >= 0 ? "E" : "W")
    }

    /// "true 042° → assigned 038°" for the last weather vector, so the crab plus variation
    /// applied to it can be read off directly.
    var assignedHeadingText: String? {
        guard let course = lastAssignedTrueCourse, let assigned = lastAssignedHeading else { return nil }
        return String(format: "true %03.0f° → assigned %03d°", course, assigned)
    }

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
