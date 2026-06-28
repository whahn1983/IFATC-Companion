import Foundation

/// The simulated controller sector currently working the aircraft.
enum ATCFacility: String, CaseIterable, Codable, Identifiable {
    case clearance
    case ground
    case tower
    case departure
    case center
    case approach
    case unicom

    var id: String { rawValue }

    var title: String {
        switch self {
        case .clearance: return "Clearance"
        case .ground: return "Ground"
        case .tower: return "Tower"
        case .departure: return "Departure"
        case .center: return "Center"
        case .approach: return "Approach"
        case .unicom: return "UNICOM"
        }
    }

    /// Spoken position name used in handoffs / call-ins.
    var spokenName: String {
        switch self {
        case .clearance: return "Clearance Delivery"
        case .ground: return "Ground"
        case .tower: return "Tower"
        case .departure: return "Departure"
        case .center: return "Center"
        case .approach: return "Approach"
        case .unicom: return "UNICOM"
        }
    }

    /// SF Symbol used for status chips.
    var symbol: String {
        switch self {
        case .clearance: return "doc.text"
        case .ground: return "car"
        case .tower: return "building.2"
        case .departure: return "airplane.departure"
        case .center: return "globe.americas"
        case .approach: return "airplane.arrival"
        case .unicom: return "antenna.radiowaves.left.and.right"
        }
    }
}
