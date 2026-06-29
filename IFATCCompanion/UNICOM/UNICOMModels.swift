import Foundation

/// How the app handles UNICOM broadcasts.
enum UNICOMMode: String, CaseIterable, Identifiable {
    case off
    case preview
    case auto

    var id: String { rawValue }
    var title: String {
        switch self {
        case .off: return "Off (suggest only)"
        case .preview: return "Preview then send"
        case .auto: return "Auto-send trusted events"
        }
    }
}

/// A UNICOM-able pilot intention. Each event maps to candidate Infinite Flight
/// Connect command names (matched against the discovered manifest) and produces
/// a plain-language broadcast.
enum UNICOMEvent: String, CaseIterable, Identifiable {
    case taxiingToRunway
    case crossingRunway
    case takingRunway
    case departingRunway
    case remainingInPattern
    case inbound
    case enteringDownwind
    case enteringBase
    case onFinal
    case clearOfRunway
    case taxiingToParking

    var id: String { rawValue }

    var title: String {
        switch self {
        case .taxiingToRunway: return "Taxiing to runway"
        case .crossingRunway: return "Crossing runway"
        case .takingRunway: return "Taking the runway"
        case .departingRunway: return "Departing"
        case .remainingInPattern: return "Remaining in pattern"
        case .inbound: return "Inbound"
        case .enteringDownwind: return "Entering downwind"
        case .enteringBase: return "Entering base"
        case .onFinal: return "On final"
        case .clearOfRunway: return "Clear of runway"
        case .taxiingToParking: return "Taxiing to parking"
        }
    }

    /// Whether this is a "safe"/routine event eligible for auto-send.
    var isTrusted: Bool {
        switch self {
        case .taxiingToRunway, .inbound, .clearOfRunway, .taxiingToParking,
             .remainingInPattern, .enteringDownwind, .enteringBase, .onFinal:
            return true
        case .takingRunway, .departingRunway, .crossingRunway:
            // Runway-occupancy events are higher-stakes; require preview.
            return false
        }
    }

    /// Substrings used to locate the corresponding command in the IF manifest.
    /// IF exposes ATC/UNICOM message commands whose exact ids vary by version.
    var commandKeywords: [String] {
        switch self {
        case .taxiingToRunway: return ["taxiingtotherunway", "taxitorunway", "taxiing"]
        case .crossingRunway: return ["crossrunway", "crossingrunway"]
        case .takingRunway: return ["takingrunway", "takingofftherunway", "departing"]
        case .departingRunway: return ["departing", "takeoff"]
        case .remainingInPattern: return ["remaininginthepattern", "pattern"]
        case .inbound: return ["inbound", "inboundonthe", "inboundforlanding"]
        case .enteringDownwind: return ["downwind"]
        case .enteringBase: return ["base"]
        case .onFinal: return ["final", "onthefinal"]
        case .clearOfRunway: return ["clearoftherunway", "clearrunway", "runwayvacated"]
        case .taxiingToParking: return ["taxiingtoparking", "parking", "taxitoramp"]
        }
    }

    /// The plain-language broadcast text. `ident` is the airport/traffic name.
    func broadcast(ident: String, runway: String) -> String {
        // Never say "the active" — name the runway when known, else "the runway".
        let rwy = runway.isEmpty ? "the runway" : "runway \(runway)"
        let at = ident.isEmpty ? "" : " \(ident)"
        switch self {
        case .taxiingToRunway: return "Traffic\(at), taxiing to \(rwy)."
        case .crossingRunway: return "Traffic\(at), crossing \(rwy)."
        case .takingRunway: return "Traffic\(at), taking \(rwy) for departure."
        case .departingRunway: return "Traffic\(at), departing \(rwy)."
        case .remainingInPattern: return "Traffic\(at), remaining in the pattern."
        case .inbound: return "Traffic\(at), inbound for landing."
        case .enteringDownwind: return "Traffic\(at), entering left downwind for \(rwy)."
        case .enteringBase: return "Traffic\(at), turning base for \(rwy)."
        case .onFinal: return "Traffic\(at), on final for \(rwy)."
        case .clearOfRunway: return "Traffic\(at), clear of \(rwy)."
        case .taxiingToParking: return "Traffic\(at), clear of the runway, taxiing to parking."
        }
    }
}

/// Mapping from a UNICOM event to a resolved Connect command id (if found in manifest).
struct UNICOMCommandMapping {
    let event: UNICOMEvent
    let commandID: Int?      // resolved manifest id, nil if not found
    let resolvedName: String?
}

/// Availability of a UNICOM command, surfaced on Diagnostics.
struct UNICOMCommandAvailability: Identifiable {
    let id = UUID()
    let event: UNICOMEvent
    let isAvailable: Bool
    let detail: String?
}

/// A suggested or pending UNICOM broadcast shown to the pilot.
struct UNICOMSuggestion: Identifiable, Equatable {
    let id = UUID()
    var event: UNICOMEvent
    var message: String
    var isAvailable: Bool
    var willAutoSend: Bool

    static func == (lhs: UNICOMSuggestion, rhs: UNICOMSuggestion) -> Bool { lhs.id == rhs.id }
}
