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
    /// The wind triangle's own estimate — always the triangle's, never whichever wind is in
    /// use. The two rows only mean something as a cross-check if the solved one is solved
    /// independently, so a tick spent steering by the sim's wind must not write that wind
    /// into this row and turn the comparison below into `0° — sim reports “from”` forever.
    var solvedWindFromDegrees: Double?
    var solvedWindKnots: Double?
    /// The wind Infinite Flight itself reports (`environment/wind_direction_true` /
    /// `environment/wind_velocity`), when the version exposes them — the preferred source for
    /// the crab. Both winds stay on screen with the signed difference between them
    /// (`reportedWindDeltaText`): the reported direction is used as the meteorological "from",
    /// and that row is the standing check on it. It should sit near 0°; near 180° would mean a
    /// build reporting the direction the wind blows *toward*, and — if the two agree on the
    /// speed — the cross-check in `AppModel.trustReportedWind` will already have fallen back
    /// to the solved wind. A difference at some other angle, with the speeds far apart, is the
    /// triangle mis-solving rather than the sim mis-reporting; compare the two speeds to tell
    /// which row to disbelieve.
    var reportedWindDirectionTrue: Double?
    var reportedWindKnots: Double?
    /// Which of the two the assigned headings are actually being crabbed for.
    var windSourceIsSimReported = false
    var magneticVariationEast: Double?
    /// The true course of the leg last assigned, and the magnetic heading actually spoken.
    var lastAssignedTrueCourse: Double?
    var lastAssignedHeading: Int?
    /// How the initial departure heading was arrived at — the runway the "fly runway
    /// heading" test measures against (flagged when it was guessed from the wind), the
    /// origin the bearing was taken from, the fix it targeted, and the true→magnetic step.
    /// Composed by `AppModel`; the panel prints it verbatim.
    var departureHeadingSummary: String?

    static let empty = WeatherProviderDiagnostics()

    /// Human-readable coverage yes/no for the panel.
    var coverageText: String { radarCoverageAvailable ? "Yes" : "No" }

    /// "270°T · 276°M / 85 kt" — the solved wind, or nil until the triangle has a usable sample.
    var solvedWindText: String? {
        guard let from = solvedWindFromDegrees, let kt = solvedWindKnots else { return nil }
        return WeatherProviderDiagnostics.windText(fromTrue: from, knots: kt,
                                                   variationEast: magneticVariationEast)
    }

    /// "221°T · 227°M / 14 kt" — the wind the sim reports, or nil when it doesn't expose it.
    var reportedWindText: String? {
        guard let dir = reportedWindDirectionTrue, let kt = reportedWindKnots else { return nil }
        return WeatherProviderDiagnostics.windText(fromTrue: dir, knots: kt,
                                                   variationEast: magneticVariationEast)
    }

    /// A wind rendered in **both frames**, because the two rows exist to be held up against
    /// Infinite Flight's own panel and the two panels don't speak the same one: every wind
    /// here is true (`wind_direction_true`, and a triangle built from true heading and track),
    /// while the sim's PFD shows the wind magnetic, like the heading bug beside it. Printing
    /// the true number alone made a correct wind look wrong by exactly the local variation —
    /// 346°T beside an instrument reading 352°M, with 6.2°W of variation between them, is the
    /// same wind twice and nothing to chase. The magnetic step is the one the assigned heading
    /// already uses (`magnetic = true − variationEast`); with no variation solved yet there is
    /// nothing to step by, so only the true figure is shown, labelled as such.
    static func windText(fromTrue: Double, knots: Double, variationEast: Double?) -> String {
        // Rounded before wrapping, so a wind just shy of north prints 000° rather than 360°.
        func degrees(_ value: Double) -> Int { (Int(value.rounded()) % 360 + 360) % 360 }
        guard let variationEast else {
            return String(format: "%03d°T / %.0f kt", degrees(fromTrue), knots)
        }
        return String(format: "%03d°T · %03d°M / %.0f kt",
                      degrees(fromTrue), degrees(fromTrue - variationEast), knots)
    }

    /// Which wind the assigned headings are crabbed for — never left to inference.
    var windSourceText: String {
        windSourceIsSimReported ? "sim-reported" : "solved (wind triangle)"
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
