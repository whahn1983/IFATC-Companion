import Foundation

/// Context the state machine and phraseology engine need to compose realistic
/// instructions. Populated by `AppModel` from the flight plan, weather, and
/// deterministic defaults.
struct ATCContext {
    var callsign: PhraseologyEngine.Callsign
    var plan: FlightPlan
    var assignedAltitude: Int
    var cruiseAltitude: Int
    var initialClimbAltitude: Int
    var windDirection: Int
    var windSpeed: Int
    var squawk: String
    var runway: String
    var taxiway: String
    var crossingRunway: String?
    var parkingTaxiway: String
    var approachName: String
    var departureFrequency: Double
    var centerFrequency: Double
    var approachFrequency: Double
    var towerFrequency: Double
    var groundFrequency: Double
    // Parsed published procedures (optional; populated when the pilot enters them).
    var sidProcedure: Procedure? = nil
    var starProcedure: Procedure? = nil
    var approachProcedure: Procedure? = nil
}

/// Deterministic ATC interaction state machine. Maps physical `FlightPhase` to
/// `ATCState`, and emits the appropriate controller transmission when the state
/// advances.
struct ATCStateMachine {

    private(set) var current: ATCState = .notConnected
    private let engine: PhraseologyEngine

    init(engine: PhraseologyEngine) {
        self.engine = engine
    }

    mutating func reset() { current = .notConnected }

    mutating func setConnected() {
        if current == .notConnected { current = .connectedIdle }
    }

    /// Map a detected physical phase to the appropriate ATC state, honoring the
    /// natural one-directional flow of a flight.
    func mappedState(for phase: FlightPhase) -> ATCState {
        switch phase {
        case .preflight: return .clearance
        case .taxiOut: return .groundTaxi
        case .takeoff: return .towerDeparture
        case .initialClimb: return .initialClimb
        case .climb: return .climb
        case .cruise: return .cruise
        case .descent: return .descent
        case .approach: return .approach
        case .landing: return .landing
        case .taxiIn: return .groundArrival
        case .parked: return .parked
        case .unknown: return current == .notConnected ? .connectedIdle : current
        }
    }

    /// Advance to a new state if warranted and, if so, return the controller
    /// transmission that accompanies the change. Returns nil when no change.
    mutating func advance(to target: ATCState, context: ATCContext) -> ATCTransmission? {
        guard target != current else { return nil }
        let previous = current
        current = target
        return transmission(for: target, from: previous, context: context)
    }

    /// The transmission a controller issues upon entering `state`.
    func transmission(for state: ATCState, from previous: ATCState, context c: ATCContext) -> ATCTransmission? {
        switch state {
        case .clearance:
            return engine.clearance(cs: c.callsign, destination: c.plan.destination,
                                    cruise: c.cruiseAltitude, sid: c.plan.sid,
                                    initialAlt: c.initialClimbAltitude,
                                    departureFreq: c.departureFrequency, squawk: c.squawk,
                                    sidProcedure: c.sidProcedure)
        case .groundTaxi, .pushbackTaxi:
            return engine.taxiToRunway(cs: c.callsign, runway: c.runway,
                                       via: c.taxiway, crossing: c.crossingRunway)
        case .towerDeparture:
            return engine.clearedForTakeoff(cs: c.callsign, runway: c.runway,
                                            windDir: c.windDirection, windSpeed: c.windSpeed)
        case .initialClimb, .departure:
            return engine.radarContactClimb(cs: c.callsign, altitude: max(c.assignedAltitude, c.initialClimbAltitude))
        case .climb:
            return engine.climbMaintain(cs: c.callsign, altitude: c.cruiseAltitude)
        case .cruise:
            // On reaching cruise, a brief center check-in/radar contact.
            return engine.radarContact(cs: c.callsign, facility: .center)
        case .descent:
            if let star = c.starProcedure {
                return engine.descendViaArrival(cs: c.callsign, star: star, altitude: max(10000, c.assignedAltitude))
            }
            return engine.descendPilotsDiscretion(cs: c.callsign, altitude: max(10000, c.assignedAltitude))
        case .approach:
            return engine.descendExpectApproach(cs: c.callsign, altitude: max(3000, c.assignedAltitude),
                                                approach: c.approachName, runway: c.runway)
        case .final:
            if let approach = c.approachProcedure {
                return engine.clearedApproach(cs: c.callsign, procedure: approach, runway: c.runway)
            }
            return engine.clearedApproach(cs: c.callsign, approach: c.approachName, runway: c.runway)
        case .landing:
            return engine.clearedToLand(cs: c.callsign, runway: c.runway,
                                        windDir: c.windDirection, windSpeed: c.windSpeed)
        case .groundArrival, .runwayExit:
            return engine.taxiToParking(cs: c.callsign, via: c.parkingTaxiway)
        case .notConnected, .connectedIdle, .holdingShort, .runwayCrossing,
             .topOfDescent, .parked, .abnormal, .center:
            return nil
        }
    }
}
