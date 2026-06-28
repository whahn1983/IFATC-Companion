import Foundation

/// A planned taxi route: an ordered list of taxiway identifiers (single-letter
/// codes spoken as "Alpha", "Bravo", …), an optional runway to cross, and the
/// taxiway used to/from the ramp.
struct TaxiPlan: Equatable {
    var taxiways: [String]
    var crossingRunway: String?
    var parkingTaxiway: String

    /// Comma-joined taxiway codes for display + (phonetically) for speech.
    /// e.g. ["A", "C"] -> "A, C" which is spoken "Alpha Charlie".
    var taxiwaysText: String {
        taxiways.isEmpty ? "available taxiways" : taxiways.joined(separator: ", ")
    }
}

/// A simplified model of an airport's movement surface: its taxiway codes, the
/// ramp taxiway, per-runway taxi routes, and any runway that must be crossed to
/// reach a given runway. Used to produce realistic taxi instructions.
struct AirportLayout: Equatable {
    let icao: String
    let taxiways: [String]
    let rampTaxiway: String
    /// Runway identifier -> ordered taxiway codes from the ramp to that runway.
    let runwayRoutes: [String: [String]]
    /// Runway identifier -> runway that must be crossed en route.
    let crossings: [String: String]
}

/// Produces deterministic taxi routes from a small built-in surface model, with a
/// stable generated fallback for airports not in the library. No AI.
struct TaxiRoutePlanner {

    func plan(airport: String, runway: String, arrival: Bool) -> TaxiPlan {
        let icao = airport.uppercased()
        let layout = TaxiRoutePlanner.layouts[icao] ?? TaxiRoutePlanner.generatedLayout(icao: icao, runway: runway)

        if arrival {
            // Arrivals roll out and taxi to parking via the ramp taxiway, plus one
            // feeder taxiway if the runway has a known route.
            let route = layout.runwayRoutes[runway]
            let feeder = route?.last
            var taxiways = [layout.rampTaxiway]
            if let feeder, feeder != layout.rampTaxiway { taxiways.insert(feeder, at: 0) }
            return TaxiPlan(taxiways: taxiways,
                            crossingRunway: nil,
                            parkingTaxiway: layout.rampTaxiway)
        }

        let route = layout.runwayRoutes[runway] ?? defaultRoute(for: layout, runway: runway)
        return TaxiPlan(taxiways: route,
                        crossingRunway: layout.crossings[runway],
                        parkingTaxiway: layout.rampTaxiway)
    }

    /// A deterministic route when the specific runway isn't in the layout: pick
    /// the ramp taxiway plus one taxiway chosen by the runway number so it's stable.
    private func defaultRoute(for layout: AirportLayout, runway: String) -> [String] {
        let others = layout.taxiways.filter { $0 != layout.rampTaxiway }
        guard !others.isEmpty else { return [layout.rampTaxiway] }
        let seed = abs(runwayNumber(runway))
        let pick = others[seed % others.count]
        return [layout.rampTaxiway, pick]
    }

    private func runwayNumber(_ runway: String) -> Int {
        Int(runway.prefix { $0.isNumber }) ?? 0
    }

    // MARK: - Built-in layouts

    static let layouts: [String: AirportLayout] = [
        "KIAH": AirportLayout(
            icao: "KIAH",
            taxiways: ["A", "B", "C", "E", "WB", "NB"],
            rampTaxiway: "A",
            runwayRoutes: ["15L": ["A", "B"], "15R": ["A", "C"],
                           "26L": ["A", "E"], "26R": ["A", "WB"],
                           "33L": ["A", "C"], "33R": ["A", "B"]],
            crossings: ["15R": "15L", "33L": "33R"]),
        "KMSP": AirportLayout(
            icao: "KMSP",
            taxiways: ["A", "B", "C", "G", "P", "Q"],
            rampTaxiway: "A",
            runwayRoutes: ["12L": ["A", "G"], "12R": ["A", "B"],
                           "30L": ["A", "B"], "30R": ["A", "G"],
                           "04": ["A", "P"], "22": ["A", "Q"]],
            crossings: ["30R": "30L", "12L": "12R"]),
        "KDEN": AirportLayout(
            icao: "KDEN",
            taxiways: ["A", "B", "C", "M", "WC", "EC"],
            rampTaxiway: "A",
            runwayRoutes: ["34L": ["A", "M"], "34R": ["A", "C"],
                           "16L": ["A", "C"], "16R": ["A", "M"],
                           "07": ["A", "WC"], "25": ["A", "EC"]],
            crossings: ["16L": "16R"])
    ]

    /// A stable generated layout for unknown airports so taxi instructions still
    /// sound plausible. Deterministic from the ICAO + runway.
    static func generatedLayout(icao: String, runway: String) -> AirportLayout {
        let pool = ["A", "B", "C", "D", "E", "F", "G"]
        // Deterministic seed from the ICAO characters (stable across launches).
        let seed = icao.unicodeScalars.reduce(0) { $0 + Int($1.value) }
        let ramp = pool[seed % pool.count]
        let feeder = pool[(seed / 7 + 2) % pool.count]
        return AirportLayout(icao: icao,
                             taxiways: pool,
                             rampTaxiway: ramp,
                             runwayRoutes: [:],
                             crossings: [:])
            .replacingFallbackRoute(runway: runway, ramp: ramp, feeder: feeder)
    }
}

private extension AirportLayout {
    /// Returns a copy with a single generated route for the given runway.
    func replacingFallbackRoute(runway: String, ramp: String, feeder: String) -> AirportLayout {
        var routes = runwayRoutes
        routes[runway] = ramp == feeder ? [ramp] : [ramp, feeder]
        return AirportLayout(icao: icao, taxiways: taxiways, rampTaxiway: ramp,
                             runwayRoutes: routes, crossings: crossings)
    }
}
