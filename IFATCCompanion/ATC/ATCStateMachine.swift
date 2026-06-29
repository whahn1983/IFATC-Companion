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
    /// Ramp/apron frequency used for the simulated (non-FAA) ramp conversation.
    var rampFrequency: Double = 131.0
    /// Resolved ramp behavior for this airport (push approval, spots, directions).
    /// Defaults to the generic airline ramp profile when no airport profile exists.
    var rampProfile: RampProfile = .generic
    /// Pushback tail/face direction ("west", "east", …) when known, else "".
    var pushDirection: String = ""
    /// Ramp spot name used for the Ramp→Ground handoff ("5", "spot 5"), else "".
    var rampSpot: String = ""
    /// Gate/stand identifier ("B44") when known, else "".
    var gate: String = ""
    /// Initial assigned heading after departure (bearing to the first fix / route
    /// intercept). 0 when unknown — the takeoff clearance then says "runway heading".
    var departureHeading: Int = 0
    /// Name of the first enroute fix, used for "resume own navigation, direct …".
    var firstFixName: String = ""
    /// Altitude (ft MSL) up to which Departure works the climb before handing to
    /// Center. Default 18,000 (FL180). Configurable in settings.
    var traconCeiling: Int = 18000
    /// Intercept/initial altitude (ft MSL) Approach assigns for the ILS/GPS/Visual
    /// — the first altitude in the approach section of the flight plan when known,
    /// otherwise 0 (the state machine then falls back to a default 3,000 ft).
    var approachInterceptAltitude: Int = 0
    // Parsed published procedures (optional; populated when the pilot enters them).
    var sidProcedure: Procedure? = nil
    var starProcedure: Procedure? = nil
    var approachProcedure: Procedure? = nil

    /// Whom the pilot contacts for pushback: Ramp when the airport has a
    /// ramp/apron layer (the common commercial case), otherwise Ground directly.
    /// Clearance Delivery announces this at the end of the IFR clearance so the
    /// pilot knows which frequency to tune for the push.
    var pushbackFacility: ATCFacility {
        rampProfile.rampType == .none ? .ground : .ramp
    }

    /// Frequency for the pushback facility resolved by `pushbackFacility`.
    var pushbackFrequency: Double {
        pushbackFacility == .ground ? groundFrequency : rampFrequency
    }
}

/// Deterministic ATC interaction state machine. Maps physical `FlightPhase` to
/// `ATCState`, and emits the appropriate controller transmission when the state
/// advances.
struct ATCStateMachine {

    private(set) var current: ATCState = .notConnected
    private let engine: PhraseologyEngine
    private let ramp: RampPhraseologyEngine

    init(engine: PhraseologyEngine) {
        self.engine = engine
        self.ramp = RampPhraseologyEngine(engine: engine)
    }

    mutating func reset() { current = .notConnected }

    /// Intermediate altitude (ft MSL) Center assigns at top of descent — clearly
    /// below cruise (so "descend and maintain …" is never contradictory) and above
    /// the terminal/approach altitude that Approach later assigns.
    static func descentTargetAltitude(context c: ATCContext) -> Int {
        let cruise = c.cruiseAltitude > 0 ? c.cruiseAltitude : 37000
        return cruise > 15000 ? 11000 : max(4000, cruise - 4000)
    }

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
            let cleared = engine.clearance(cs: c.callsign, destination: c.plan.destination,
                                           cruise: c.cruiseAltitude, sid: c.plan.sid,
                                           initialAlt: c.initialClimbAltitude,
                                           departureFreq: c.departureFrequency, squawk: c.squawk,
                                           sidProcedure: c.sidProcedure)
            // End the clearance with the pushback hand-off so the pilot knows
            // which facility/frequency to tune for the push (Ramp or Ground).
            return engine.appendingPushbackHandoff(to: cleared, facility: c.pushbackFacility,
                                                   frequency: c.pushbackFrequency)
        case .pushback:
            // Ramp (simulated local/company), not FAA ATC. Includes tail/face
            // direction when known, else "advise ready to taxi".
            return ramp.pushbackApproved(cs: c.callsign, direction: c.pushDirection,
                                         profile: c.rampProfile)
        case .engineStart:
            return ramp.startApproved(cs: c.callsign)
        case .groundTaxi, .pushbackTaxi:
            return engine.taxiToRunway(cs: c.callsign, runway: c.runway,
                                       via: c.taxiway, crossing: c.crossingRunway)
        case .lineUpWait:
            return engine.lineUpAndWait(cs: c.callsign, runway: c.runway)
        case .towerDeparture:
            // When a departure heading is known, the takeoff clearance also issues
            // the initial heading + climb (real-world style); otherwise the simpler
            // "cleared for takeoff" form is used.
            if c.departureHeading > 0 {
                return engine.clearedForTakeoff(cs: c.callsign, runway: c.runway,
                                                windDir: c.windDirection, windSpeed: c.windSpeed,
                                                departureHeading: c.departureHeading,
                                                initialAltitude: c.initialClimbAltitude)
            }
            return engine.clearedForTakeoff(cs: c.callsign, runway: c.runway,
                                            windDir: c.windDirection, windSpeed: c.windSpeed)
        case .initialClimb, .departure:
            // Departure works the climb up to the TRACON ceiling (default FL180),
            // joining the filed route.
            let top = c.traconCeiling > 0 ? c.traconCeiling : max(c.assignedAltitude, c.initialClimbAltitude)
            return engine.departureClimb(cs: c.callsign, altitude: top, firstFix: c.firstFixName)
        case .climb:
            return engine.climbMaintain(cs: c.callsign, altitude: c.cruiseAltitude)
        case .cruise:
            // On reaching cruise, a brief center check-in/radar contact.
            return engine.radarContact(cs: c.callsign, facility: .center)
        case .descent:
            // A filed STAR yields "descend via the <STAR> arrival"; otherwise a plain
            // "descend and maintain <alt>". The target is an intermediate altitude
            // clearly below cruise (not the cruise level), so it is never contradictory.
            let alt = ATCStateMachine.descentTargetAltitude(context: c)
            if let star = c.starProcedure {
                return engine.descendViaArrival(cs: c.callsign, star: star, altitude: alt)
            }
            return engine.descendMaintain(cs: c.callsign, altitude: alt)
        case .approach:
            // Approach descends to the terminal intercept altitude and tells the
            // pilot which approach to expect — independent of the higher altitude
            // Center assigned during the enroute descent. The intercept altitude is
            // the first altitude in the approach section of the flight plan when
            // known, otherwise a default 3,000 ft.
            let interceptAlt = c.approachInterceptAltitude > 0 ? c.approachInterceptAltitude : 3000
            if let approach = c.approachProcedure {
                return engine.descendExpectApproach(cs: c.callsign, altitude: interceptAlt,
                                                    procedure: approach, runway: c.runway)
            }
            return engine.descendExpectApproach(cs: c.callsign, altitude: interceptAlt,
                                                approach: c.approachName, runway: c.runway)
        case .final:
            if let approach = c.approachProcedure {
                return engine.clearedApproach(cs: c.callsign, procedure: approach, runway: c.runway)
            }
            return engine.clearedApproach(cs: c.callsign, approach: c.approachName, runway: c.runway)
        case .landing:
            return engine.clearedToLand(cs: c.callsign, runway: c.runway,
                                        windDir: c.windDirection, windSpeed: c.windSpeed)
        case .runwayExit:
            // Tower instructs the aircraft to clear the runway and switch to Ground.
            return engine.exitRunwayContactGround(cs: c.callsign, frequency: c.groundFrequency)
        case .groundArrival:
            return engine.taxiToParking(cs: c.callsign, via: c.parkingTaxiway)
        case .notConnected, .connectedIdle, .holdingShort, .runwayCrossing,
             .topOfDescent, .parked, .abnormal, .center:
            return nil
        }
    }
}
