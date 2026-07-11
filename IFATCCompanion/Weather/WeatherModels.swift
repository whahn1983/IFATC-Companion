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

extension SIGMET {
    /// Coarse hazard classification derived from the advisory's hazard field
    /// (falling back to the raw text when the structured field is absent).
    enum Category { case convective, turbulence, icingOrMountainWave, other }

    var category: Category {
        let text = (hazard ?? raw).uppercased()
        if text.contains("CONV") || text.contains("TS") { return .convective }
        if text.contains("TURB") { return .turbulence }
        if text.contains("ICE") || text.contains("MTW") { return .icingOrMountainWave }
        return .other
    }

    /// The turbulence severity this advisory implies. This is the single source of
    /// truth used both to raise the composite ride index and to color the advisory
    /// area on the route map, so the two never disagree.
    var turbulenceSeverity: TurbulenceSeverity {
        switch category {
        case .convective:
            return .severe
        case .turbulence:
            let sev = (severity ?? "").uppercased()
            return (sev.contains("SEV") || sev.contains("EXTRM") || sev.contains("EXTREME")) ? .severe : .moderate
        case .icingOrMountainWave:
            return .light
        case .other:
            // IFR / volcanic-ash / other advisories don't imply a rough ride, so
            // they neither raise the ride index nor paint the turbulence overlay.
            return .smooth
        }
    }

    /// A short human label for the hazard, used in ride-report factors.
    var hazardLabel: String {
        switch category {
        case .convective: return "convective SIGMET"
        case .turbulence: return "turbulence SIGMET"
        case .icingOrMountainWave, .other: return "SIGMET advisory"
        }
    }

    /// The valid polygon vertices when this advisory has a drawable area (≥3
    /// points). Advisories without a real polygon can't be placed on the map and
    /// must not silently drive the ride index either.
    var drawableArea: [CLLocationCoordinate2D]? {
        let points = area.filter { $0.isValid }
        return points.count >= 3 ? points : nil
    }
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
    /// Age of the source report in minutes, when the report time is known.
    var ageMinutes: Double? = nil
    /// The report's actual level (ft), when known — for the altitude-matched PIREP relay
    /// and the smoother-altitude search. Distinct from `altitudeBand` (a ±2000 display band).
    var reportedAltitudeFt: Int? = nil
    /// Reporting aircraft type code (e.g. "B738"), when the source is a PIREP.
    var aircraftType: String? = nil
}

/// A reachable altitude with a smoother reported ride than the pilot's current level,
/// derived from PIREPs at other levels along the route. `higher` is relative to the
/// pilot. Only ever produced when a real report supports it (never invented).
struct SmootherAltitude: Equatable {
    var altitudeFt: Int
    var severity: TurbulenceSeverity
    var aircraftType: String?
    var higher: Bool
}
