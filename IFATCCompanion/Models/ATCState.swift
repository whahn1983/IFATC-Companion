import Foundation

/// Deterministic ATC interaction state machine states.
/// Distinct from `FlightPhase` (physical) — this tracks the conversational /
/// procedural position in a normal IFR flight.
enum ATCState: String, CaseIterable, Codable, Identifiable {
    case notConnected
    case connectedIdle
    case clearance
    case pushback
    case engineStart
    case pushbackTaxi
    case groundTaxi
    case runwayCrossing
    case holdingShort
    case lineUpWait
    case towerDeparture
    case initialClimb
    case departure
    case climb
    case center
    case cruise
    case topOfDescent
    case descent
    case approach
    case final
    case landing
    case runwayExit
    case groundArrival
    case parked
    case abnormal

    var id: String { rawValue }

    var title: String {
        switch self {
        case .notConnected: return "Not Connected"
        case .connectedIdle: return "Connected"
        case .clearance: return "Clearance"
        case .pushback: return "Pushback"
        case .engineStart: return "Engine Start"
        case .pushbackTaxi: return "Pushback / Taxi"
        case .groundTaxi: return "Ground Taxi"
        case .runwayCrossing: return "Runway Crossing"
        case .holdingShort: return "Holding Short"
        case .lineUpWait: return "Line Up & Wait"
        case .towerDeparture: return "Tower"
        case .initialClimb: return "Initial Climb"
        case .departure: return "Departure"
        case .climb: return "Climb"
        case .center: return "Center"
        case .cruise: return "Cruise"
        case .topOfDescent: return "Top of Descent"
        case .descent: return "Descent"
        case .approach: return "Approach"
        case .final: return "Final"
        case .landing: return "Landing"
        case .runwayExit: return "Runway Exit"
        case .groundArrival: return "Ground (Arrival)"
        case .parked: return "Parked"
        case .abnormal: return "Off Route"
        }
    }

    /// The controller facility that normally works this state.
    var facility: ATCFacility {
        switch self {
        case .notConnected, .connectedIdle, .parked: return .ground
        case .clearance: return .clearance
        case .pushback, .engineStart, .pushbackTaxi, .groundTaxi,
             .runwayCrossing, .holdingShort: return .ground
        case .lineUpWait, .towerDeparture, .landing, .final, .runwayExit: return .tower
        case .initialClimb, .departure: return .departure
        case .climb, .center, .cruise, .topOfDescent: return .center
        case .descent, .approach: return .approach
        case .groundArrival: return .ground
        case .abnormal: return .center
        }
    }

    /// The pilot-driven pre-departure ground sequence (clearance → pushback →
    /// engine start → taxi → holding short → line up and wait). These steps are
    /// advanced manually via the response buttons so the flow never skips a phase.
    var isManualGroundFlow: Bool {
        switch self {
        case .clearance, .pushback, .engineStart, .pushbackTaxi, .groundTaxi,
             .runwayCrossing, .holdingShort, .lineUpWait:
            return true
        default:
            return false
        }
    }
}
