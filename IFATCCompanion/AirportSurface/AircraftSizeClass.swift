import Foundation

/// A conservative aircraft size classification used to bias taxi routing away from
/// paths unsuitable for the aircraft (narrow taxilanes, tight turns) when OSM tags
/// carry enough information. Infinite Flight aircraft info is used when available;
/// otherwise the aircraft is classified conservatively by size, defaulting to `medium`.
enum AircraftSizeClass: String, Codable, CaseIterable {
    case light      // GA singles/twins
    case small      // regional jets, turboprops
    case medium     // A320/737 family
    case large      // 757/767/A330
    case heavy      // 777/747/A350/A380

    var title: String {
        switch self {
        case .light: return "Light"
        case .small: return "Small"
        case .medium: return "Medium"
        case .large: return "Large"
        case .heavy: return "Heavy"
        }
    }

    /// Approximate minimum taxiway width (meters) the class is comfortable on. Used
    /// only when OSM tags a taxiway/taxilane width; unknown widths never penalize.
    var minComfortableTaxiwayWidthMeters: Double {
        switch self {
        case .light: return 7.5
        case .small: return 15
        case .medium: return 18
        case .large: return 23
        case .heavy: return 30
        }
    }

    /// Whether taxilanes (apron lead-in lanes) are generally acceptable for the class.
    var acceptsTaxilanes: Bool {
        switch self {
        case .light, .small, .medium: return true
        case .large, .heavy: return false
        }
    }

    /// Best-effort classification from an Infinite Flight aircraft name. Conservative:
    /// anything unrecognised is `medium`.
    static func classify(aircraftName: String?) -> AircraftSizeClass {
        guard let raw = aircraftName?.uppercased(), !raw.isEmpty else { return .medium }
        let n = raw.replacingOccurrences(of: "-", with: "").replacingOccurrences(of: " ", with: "")

        // Heavy widebodies.
        for token in ["747", "748", "777", "77W", "A380", "A388", "A350", "A359", "A35", "MD11", "AN12", "AN22", "C17", "C5"] {
            if n.contains(token) { return .heavy }
        }
        // Large widebodies.
        for token in ["767", "757", "A330", "A339", "A340", "A300", "A310", "787", "78", "DC10", "L101"] {
            if n.contains(token) { return .large }
        }
        // Medium narrowbodies.
        for token in ["737", "738", "739", "73", "A320", "A319", "A321", "A318", "A32", "757200", "MD80", "MD90", "717", "727", "B52"] {
            if n.contains(token) { return .medium }
        }
        // Small regionals / turboprops.
        for token in ["CRJ", "E170", "E175", "E190", "E195", "EMB", "ERJ", "DASH", "DH8", "Q400", "ATR", "SF34", "SAAB", "A220", "BCS", "F50", "F70", "F100"] {
            if n.contains(token) { return .small }
        }
        // Light GA.
        for token in ["C172", "C152", "CESSNA", "SR22", "TBM", "PIPER", "PA28", "DA40", "DA42", "SPITFIRE", "XCUB", "CUB"] {
            if n.contains(token) { return .light }
        }
        return .medium
    }
}
