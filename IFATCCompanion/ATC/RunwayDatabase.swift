import Foundation

/// Real-world runway inventory for airports. Used to pick a realistic active
/// runway from the surface wind — the same way an ATIS/controller chooses the
/// active runway — instead of inventing a runway number that does not exist at
/// the field (e.g. "runway 14" at Newark, which has none).
///
/// Each airport lists its runway idents with the commonly-active end first so
/// the wind-based pick is stable for calm/ambiguous winds. A runway's magnetic
/// heading is derived from its number (×10), which is accurate to within a few
/// degrees — more than enough to choose the best-aligned runway.
struct RunwayDatabase {

    static let shared = RunwayDatabase()

    /// ICAO (4-letter) -> ordered runway idents (e.g. ["22R", "22L", "4L", "4R", "11", "29"]).
    let airports: [String: [String]]

    init() {
        airports = [
            // New York / New Jersey
            "KEWR": ["22R", "22L", "4L", "4R", "11", "29"],
            "KJFK": ["31L", "31R", "13L", "13R", "4L", "4R", "22L", "22R"],
            "KLGA": ["22", "4", "13", "31"],
            // Major US hubs (mirrors AirportDatabase coverage)
            "KIAH": ["26L", "26R", "8L", "8R", "9", "27", "15L", "15R", "33L", "33R"],
            "KMSP": ["30L", "30R", "12L", "12R", "4", "22"],
            "KDEN": ["34L", "34R", "16L", "16R", "17L", "17R", "35L", "35R", "7", "25", "8", "26"],
            "KORD": ["28R", "28C", "28L", "27R", "27C", "27L", "22L", "22R",
                     "10L", "10C", "10R", "9R", "9C", "9L", "4L", "4R"],
            "KATL": ["26R", "26L", "27R", "27L", "28", "8L", "8R", "9L", "9R", "10"],
            "KLAX": ["25R", "25L", "24R", "24L", "6L", "6R", "7L", "7R"],
            "KSFO": ["28L", "28R", "1L", "1R", "10L", "10R", "19L", "19R"],
            "KSEA": ["16L", "16C", "16R", "34L", "34C", "34R"],
            "KDFW": ["35L", "35C", "35R", "36L", "36R", "17L", "17C", "17R",
                     "18L", "18R", "13L", "13R", "31L", "31R"],
            "KBOS": ["4L", "4R", "22L", "22R", "9", "27", "14", "32", "15R", "15L", "33L", "33R"],
            "KMIA": ["8L", "8R", "26L", "26R", "9", "27", "12", "30"],
            "KLAS": ["26L", "26R", "8L", "8R", "1L", "1R", "19L", "19R"],
            "KPHX": ["25L", "25R", "26", "7L", "7R", "8"],
            "KDCA": ["1", "19", "15", "33", "4", "22"],
            "KMCI": ["19L", "19R", "1L", "1R", "9", "27"],
            "KSTL": ["30L", "30R", "12L", "12R", "6", "24", "11", "29"],
            "KOMA": ["32R", "32L", "14L", "14R", "18", "36"],
            "KDSM": ["31", "13", "5", "23"]
        ]
    }

    /// Look up an airport's runways. Accepts 4-letter ICAO ("KEWR") or 3-letter
    /// US codes ("EWR", resolved as "K"+code).
    func runways(for code: String) -> [String] {
        let normalized = code.uppercased().trimmingCharacters(in: .whitespaces)
        if let direct = airports[normalized] { return direct }
        if normalized.count == 3, let usPrefixed = airports["K" + normalized] { return usPrefixed }
        return []
    }

    /// The active runway best aligned into the wind (the direction the wind is
    /// coming *from*, in degrees). Returns nil when the airport is unknown, so
    /// the caller can fall back to a wind-derived guess.
    ///
    /// Calm/variable wind (≤ 3 kt) keeps the field's primary runway (first in the
    /// list) for a stable, realistic default rather than chasing noise.
    func activeRunway(for code: String, windDirection: Int, windSpeed: Int) -> String? {
        let runways = runways(for: code)
        guard let primary = runways.first else { return nil }
        guard windSpeed > 3, windDirection > 0 else { return primary }

        let wind = Double(windDirection)
        // Most into-wind runway: smallest angular difference between the runway's
        // heading and the wind direction. Stable on ties (keeps list order).
        let best = runways.min { a, b in
            Geo.headingDifference(heading(of: a), wind) < Geo.headingDifference(heading(of: b), wind)
        }
        return best ?? primary
    }

    /// Magnetic heading implied by a runway ident's leading number (×10).
    /// "22R" -> 220, "4L" -> 40, "36" -> 360.
    private func heading(of ident: String) -> Double {
        Self.heading(forRunway: ident) ?? 360
    }

    /// Magnetic heading implied by a runway ident's leading number (×10), or nil
    /// when the ident carries no usable runway number. "22R" -> 220, "4L" -> 40,
    /// "36" -> 360. Accurate to within a few degrees of the true runway heading.
    static func heading(forRunway ident: String) -> Double? {
        let digits = ident.prefix { $0.isNumber }
        guard let number = Int(digits), number > 0, number <= 36 else { return nil }
        return Double(number * 10)
    }
}
