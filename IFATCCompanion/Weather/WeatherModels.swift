import Foundation
import CoreLocation

/// Turbulence severity scale used by ride reports.
enum TurbulenceSeverity: Int, Comparable, CaseIterable {
    case smooth = 0
    case lightChop = 1
    case light = 2
    case moderate = 3
    case severe = 4

    static func < (lhs: TurbulenceSeverity, rhs: TurbulenceSeverity) -> Bool {
        lhs.rawValue < rhs.rawValue
    }

    var spoken: String {
        switch self {
        case .smooth: return "smooth"
        case .lightChop: return "light chop"
        case .light: return "light turbulence"
        case .moderate: return "moderate turbulence"
        case .severe: return "severe turbulence"
        }
    }

    var title: String { spoken.capitalized }

    /// Parse from a PIREP turbulence code or free text.
    static func parse(_ text: String) -> TurbulenceSeverity? {
        let t = text.uppercased()
        if t.contains("SEV") || t.contains("EXTRM") || t.contains("EXTREME") { return .severe }
        if t.contains("MOD") { return .moderate }
        if t.contains("LGT-MOD") || t.contains("LIGHT-MODERATE") { return .moderate }
        if t.contains("LGT") || t.contains("LIGHT") { return .light }
        if t.contains("CHOP") || t.contains("CAT") { return .lightChop }
        if t.contains("SMOOTH") || t.contains("NEG") || t.contains("SKC") { return .smooth }
        return nil
    }
}

struct CloudLayer: Equatable {
    let cover: String   // FEW, SCT, BKN, OVC
    let baseFt: Int?
}

struct METAR: Equatable {
    var icao: String
    var raw: String
    var observationTime: Date?
    var windDirection: Int?
    var windSpeed: Int?
    var windGust: Int?
    var visibilitySM: Double?
    var altimeterInHg: Double?
    var temperatureC: Double?
    var dewpointC: Double?
    var clouds: [CloudLayer] = []
    var flightCategory: String?  // VFR/MVFR/IFR/LIFR

    /// Lowest broken/overcast ceiling in feet, if any.
    var ceilingFt: Int? {
        clouds
            .filter { $0.cover == "BKN" || $0.cover == "OVC" }
            .compactMap { $0.baseFt }
            .min()
    }
}

struct TAFForecastPeriod: Equatable {
    var raw: String
    var windDirection: Int?
    var windSpeed: Int?
    var visibilitySM: Double?
    var changeIndicator: String?
}

struct TAF: Equatable {
    var icao: String
    var raw: String
    var issueTime: Date?
    var periods: [TAFForecastPeriod] = []
}

struct PIREP: Equatable, Identifiable {
    var id = UUID()
    var raw: String
    var coordinate: CLLocationCoordinate2D?
    var altitudeFt: Int?
    var turbulence: TurbulenceSeverity?
    var icing: String?
    var time: Date?
    var aircraftType: String?

    static func == (lhs: PIREP, rhs: PIREP) -> Bool { lhs.id == rhs.id }
}

struct SIGMET: Equatable, Identifiable {
    var id = UUID()
    var raw: String
    var hazard: String?      // TURB, ICE, CONVECTIVE, IFR, MTW, ASH
    var severity: String?
    var area: [CLLocationCoordinate2D] = []

    static func == (lhs: SIGMET, rhs: SIGMET) -> Bool { lhs.id == rhs.id }
}

/// A ride report relevant to the current route, produced by `RideReportEngine`.
struct RideReportItem: Identifiable {
    let id = UUID()
    var severity: TurbulenceSeverity
    var altitudeBand: ClosedRange<Int>?
    var distanceAheadNM: Double?
    var bearing: Double?
    var nearFix: String?
    var sourceRaw: String
}
