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
    /// The wind Infinite Flight itself reports (`environment/wind_direction_true` /
    /// `environment/wind_velocity`), when the version exposes them. Shown **next to** the
    /// solved wind rather than replacing it: the state name doesn't settle whether the
    /// direction is the meteorological "from" or the direction the wind blows "toward", and
    /// the two differ by exactly 180°. `reportedWindDeltaText` puts that difference on screen,
    /// so one look at a real flight settles the convention — a delta near 0° means the sim
    /// reports "from" and the solver can read it directly; near 180° means "toward".
    var reportedWindDirectionTrue: Double?
    var reportedWindKnots: Double?
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

    /// "221° / 14 kt" — the wind the sim reports, or nil when it doesn't expose it.
    var reportedWindText: String? {
        guard let dir = reportedWindDirectionTrue, let kt = reportedWindKnots else { return nil }
        return String(format: "%03.0f° / %.0f kt", dir, kt)
    }

    /// How far the sim's reported direction sits from the wind the triangle solved, as a
    /// signed 0–180° difference. Near **0°** the sim reports the direction the wind blows
    /// *from* (the convention the app uses); near **180°** it reports the direction it blows
    /// *toward*. Anything in between means one of the two is wrong. Nil unless both are known.
    var reportedWindDeltaText: String? {
        guard let reported = reportedWindDirectionTrue, let solved = solvedWindFromDegrees else { return nil }
        var diff = (reported - solved).truncatingRemainder(dividingBy: 360)
        if diff > 180 { diff -= 360 }
        if diff < -180 { diff += 360 }
        let reading: String
        switch abs(diff) {
        case ..<45: reading = "sim reports “from”"
        case 135...: reading = "sim reports “toward”"
        default: reading = "inconsistent"
        }
        return String(format: "%.0f° — %@", diff, reading)
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
