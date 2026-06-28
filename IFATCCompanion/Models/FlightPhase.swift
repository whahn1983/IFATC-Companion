import Foundation

/// Physical phase of flight inferred from aircraft state by `PhaseDetector`,
/// and produced directly by `MockSimulatorFeed`.
enum FlightPhase: String, CaseIterable, Codable, Identifiable {
    case preflight
    case taxiOut
    case takeoff
    case initialClimb
    case climb
    case cruise
    case descent
    case approach
    case landing
    case taxiIn
    case parked
    case unknown

    var id: String { rawValue }

    var title: String {
        switch self {
        case .preflight: return "Preflight"
        case .taxiOut: return "Taxi Out"
        case .takeoff: return "Takeoff"
        case .initialClimb: return "Initial Climb"
        case .climb: return "Climb"
        case .cruise: return "Cruise"
        case .descent: return "Descent"
        case .approach: return "Approach"
        case .landing: return "Landing"
        case .taxiIn: return "Taxi In"
        case .parked: return "Parked"
        case .unknown: return "Unknown"
        }
    }

    /// Whether the aircraft is expected to be on the ground in this phase.
    var isGround: Bool {
        switch self {
        case .preflight, .taxiOut, .taxiIn, .parked: return true
        default: return false
        }
    }

    /// Demo ordering used by the mock feed "advance phase" control.
    static let demoSequence: [FlightPhase] = [
        .preflight, .taxiOut, .takeoff, .initialClimb, .climb,
        .cruise, .descent, .approach, .landing, .taxiIn, .parked
    ]
}
