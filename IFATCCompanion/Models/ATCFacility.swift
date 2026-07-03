import Foundation

/// The simulated controller sector currently working the aircraft.
enum ATCFacility: String, CaseIterable, Codable, Identifiable {
    case clearance
    case ramp
    case ground
    case tower
    case departure
    case center
    case approach

    var id: String { rawValue }

    /// Whether this facility is FAA air traffic control. Ramp is a simulated
    /// local/airline/company procedure, NOT FAA ATC, and is excluded.
    var isFAAATC: Bool {
        switch self {
        case .clearance, .ground, .tower, .departure, .center, .approach: return true
        case .ramp: return false
        }
    }

    var title: String {
        switch self {
        case .clearance: return "Clearance"
        case .ramp: return "Ramp"
        case .ground: return "Ground"
        case .tower: return "Tower"
        case .departure: return "Departure"
        case .center: return "Center"
        case .approach: return "Approach"
        }
    }

    /// Spoken position name used in handoffs / call-ins.
    var spokenName: String {
        switch self {
        case .clearance: return "Clearance Delivery"
        case .ramp: return "Ramp"
        case .ground: return "Ground"
        case .tower: return "Tower"
        case .departure: return "Departure"
        case .center: return "Center"
        case .approach: return "Approach"
        }
    }

    /// SF Symbol used for status chips.
    var symbol: String {
        switch self {
        case .clearance: return "doc.text"
        case .ramp: return "parkingsign"
        case .ground: return "car"
        case .tower: return "building.2"
        case .departure: return "airplane.departure"
        case .center: return "globe.americas"
        case .approach: return "airplane.arrival"
        }
    }

    /// Best-effort map from an Infinite Flight ATC facility name (e.g. "Ground",
    /// "KSFO Tower", "Approach", "Clearance Delivery") to the matching facility.
    /// Returns nil for names that don't correspond to a gate-to-gate FAA position
    /// (UNICOM, ATIS, …) or that can't be recognised. Matching is token-based and
    /// case-insensitive, checking the more specific words first so "Clearance
    /// Delivery" and "Ground Control" resolve unambiguously.
    static func matching(name: String?) -> ATCFacility? {
        guard let raw = name?.uppercased().trimmingCharacters(in: .whitespaces),
              !raw.isEmpty else { return nil }
        if raw.contains("CLEARANCE") || raw.contains("DELIVERY") { return .clearance }
        if raw.contains("GROUND") { return .ground }
        if raw.contains("TOWER") { return .tower }
        if raw.contains("DEPART") { return .departure }
        if raw.contains("APPROACH") || raw.contains("ARRIVAL") { return .approach }
        if raw.contains("CENTER") || raw.contains("CENTRE") { return .center }
        return nil
    }
}
