import Foundation

/// Generates deterministic pilot readbacks/responses. Because the app knows
/// exactly what the controller said (it generated it), readbacks are composed
/// from the same context rather than parsed from text.
struct PilotResponseEngine {

    let engine: PhraseologyEngine

    private var icao: Bool { engine.icao }

    private func pilot(_ display: String, _ spoken: String, facility: ATCFacility) -> ATCTransmission {
        ATCTransmission(sender: .pilot, facility: facility, displayText: display, spokenText: spoken)
    }

    /// A correct readback for the controller instruction associated with `state`.
    func readback(for state: ATCState, context c: ATCContext) -> ATCTransmission {
        let cs = c.callsign
        switch state {
        case .clearance:
            let display = "Cleared to \(c.plan.destinationName), climb via SID except maintain \(engine.formatAltDisplay(c.initialClimbAltitude)), squawk \(c.squawk), \(cs.display)."
            let spoken = "Cleared to \(engine.spokenAirport(c.plan.destination)), climb via SID except maintain \(Phonetic.altitude(c.initialClimbAltitude, icao: icao)), \(Phonetic.squawk(c.squawk, icao: icao)), \(cs.spoken)."
            return pilot(display, spoken, facility: .clearance)
        case .pushback:
            // Ramp readback. Echo the tail/face direction when one was given.
            let dir = c.pushDirection.trimmingCharacters(in: .whitespaces).lowercased()
            let word = c.rampProfile.rampType.usesFaceDirection ? "face" : "tail"
            let tail = dir.isEmpty ? "" : ", \(word) \(dir)"
            let phrase = icao ? "Push approved" : "Pushback approved"
            return pilot("\(phrase)\(tail), \(cs.display).", "\(phrase)\(tail), \(cs.spoken).", facility: .ramp)
        case .engineStart:
            return pilot("Start approved, \(cs.display).", "Start approved, \(cs.spoken).", facility: .ramp)
        case .lineUpWait:
            return pilot("Runway \(c.runway), line up and wait, \(cs.display).",
                         "Runway \(Phonetic.runway(c.runway, icao: icao)), line up and wait, \(cs.spoken).",
                         facility: .tower)
        case .groundTaxi, .pushbackTaxi:
            var display = "Taxi to runway \(c.runway) via \(c.taxiway)"
            var spoken = "Taxi to runway \(Phonetic.runway(c.runway, icao: icao)) via \(Phonetic.spellToken(c.taxiway, icao: icao))"
            if let x = c.crossingRunway, !x.isEmpty {
                display += ", cross runway \(x)"; spoken += ", cross runway \(Phonetic.runway(x, icao: icao))"
            }
            return pilot(display + ", \(cs.display).", spoken + ", \(cs.spoken).", facility: .ground)
        case .towerDeparture:
            return pilot("Runway \(c.runway), cleared for takeoff, \(cs.display).",
                         "Runway \(Phonetic.runway(c.runway, icao: icao)), cleared for takeoff, \(cs.spoken).",
                         facility: .tower)
        case .initialClimb, .departure:
            let alt = max(c.assignedAltitude, c.initialClimbAltitude)
            return pilot("Climb and maintain \(engine.formatAltDisplay(alt)), \(cs.display).",
                         "Climb and maintain \(Phonetic.altitude(alt, icao: icao)), \(cs.spoken).",
                         facility: .departure)
        case .climb:
            return pilot("Climb and maintain \(engine.formatAltDisplay(c.cruiseAltitude)), \(cs.display).",
                         "Climb and maintain \(Phonetic.altitude(c.cruiseAltitude, icao: icao)), \(cs.spoken).",
                         facility: .center)
        case .cruise, .center:
            return pilot("\(cs.display), maintaining \(engine.formatAltDisplay(c.cruiseAltitude)).",
                         "\(cs.spoken), maintaining \(Phonetic.altitude(c.cruiseAltitude, icao: icao)).",
                         facility: .center)
        case .descent:
            if let star = c.starProcedure {
                return pilot("Descend via the \(star.displayName) arrival, \(cs.display).",
                             "Descend via the \(star.spokenName(icao: icao)) arrival, \(cs.spoken).",
                             facility: .center)
            }
            let alt = ATCStateMachine.descentTargetAltitude(context: c)
            return pilot("Descend and maintain \(engine.formatAltDisplay(alt)), \(cs.display).",
                         "Descend and maintain \(Phonetic.altitude(alt, icao: icao)), \(cs.spoken).",
                         facility: .center)
        case .approach:
            let alt = max(3000, c.assignedAltitude)
            if let approach = c.approachProcedure {
                let rwy = approach.runway ?? c.runway
                let typeD = approach.approachType?.display ?? "approach"
                let typeS = approach.approachType?.spoken ?? "approach"
                return pilot("Down to \(engine.formatAltDisplay(alt)), expecting the \(typeD) runway \(rwy), \(cs.display).",
                             "Down to \(Phonetic.altitude(alt, icao: icao)), expecting the \(typeS) runway \(Phonetic.runway(rwy, icao: icao)), \(cs.spoken).",
                             facility: .approach)
            }
            return pilot("Down to \(engine.formatAltDisplay(alt)), expecting \(c.approachName.isEmpty ? "ILS" : c.approachName) runway \(c.runway), \(cs.display).",
                         "Down to \(Phonetic.altitude(alt, icao: icao)), expecting \(c.approachName.isEmpty ? "I L S" : c.approachName) runway \(Phonetic.runway(c.runway, icao: icao)), \(cs.spoken).",
                         facility: .approach)
        case .final:
            if let approach = c.approachProcedure {
                let rwy = approach.runway ?? c.runway
                let typeD = approach.approachType?.display ?? "approach"
                let typeS = approach.approachType?.spoken ?? "approach"
                return pilot("Cleared the \(typeD) runway \(rwy), \(cs.display).",
                             "Cleared the \(typeS) runway \(Phonetic.runway(rwy, icao: icao)), \(cs.spoken).",
                             facility: .approach)
            }
            return pilot("Cleared \(c.approachName.isEmpty ? "ILS" : c.approachName) runway \(c.runway), \(cs.display).",
                         "Cleared \(c.approachName.isEmpty ? "I L S" : c.approachName) runway \(Phonetic.runway(c.runway, icao: icao)), \(cs.spoken).",
                         facility: .approach)
        case .landing:
            return pilot("Runway \(c.runway), cleared to land, \(cs.display).",
                         "Runway \(Phonetic.runway(c.runway, icao: icao)), cleared to land, \(cs.spoken).",
                         facility: .tower)
        case .runwayExit:
            return pilot("Exiting the runway, contact Ground, \(cs.display).",
                         "Exiting the runway, contact Ground, \(cs.spoken).",
                         facility: .tower)
        case .groundArrival:
            return pilot("Taxi to parking via \(c.parkingTaxiway), \(cs.display).",
                         "Taxi to parking via \(Phonetic.spellToken(c.parkingTaxiway, icao: icao)), \(cs.spoken).",
                         facility: .ground)
        default:
            return pilot("\(cs.display).", "\(cs.spoken).", facility: state.facility)
        }
    }

    /// A simple "Wilco" / acknowledgement when a full readback isn't required.
    func wilco(context c: ATCContext, facility: ATCFacility) -> ATCTransmission {
        pilot("Wilco, \(c.callsign.display).", "Wilco, \(c.callsign.spoken).", facility: facility)
    }

    /// Pilot says "say again".
    func sayAgain(context c: ATCContext, facility: ATCFacility) -> ATCTransmission {
        pilot("Say again for \(c.callsign.display).",
              "Say again for \(c.callsign.spoken).",
              facility: facility)
    }

    /// Pilot declines (Unable).
    func unable(context c: ATCContext, facility: ATCFacility) -> ATCTransmission {
        pilot("Unable, \(c.callsign.display).",
              "Unable, \(c.callsign.spoken).",
              facility: facility)
    }

    // MARK: - Pilot requests (pilot-initiated transmissions)

    // Departure ground flow — pilot-initiated requests, in order.

    func requestClearance(context c: ATCContext) -> ATCTransmission {
        let dest = c.plan.destination.isEmpty ? "destination" : c.plan.destinationName
        let destSpoken = engine.spokenAirport(c.plan.destination)
        return pilot("Clearance, \(c.callsign.display), request IFR clearance to \(dest).",
                     "Clearance, \(c.callsign.spoken), request IFR clearance to \(destSpoken).",
                     facility: .clearance)
    }

    func requestPushback(context c: ATCContext) -> ATCTransmission {
        // Pushback is a Ramp (local/company) request, not FAA Ground ATC.
        let gate = c.gate.trimmingCharacters(in: .whitespaces)
        let at = gate.isEmpty ? "" : " at \(gate)"
        let atSpoken = gate.isEmpty ? "" : " at \(Phonetic.spellToken(gate, icao: icao))"
        return pilot("Ramp, \(c.callsign.display)\(at), ready to push.",
                     "Ramp, \(c.callsign.spoken)\(atSpoken), ready to push.",
                     facility: .ramp)
    }

    func requestEngineStart(context c: ATCContext) -> ATCTransmission {
        return pilot("Ramp, \(c.callsign.display), request engine start.",
                     "Ramp, \(c.callsign.spoken), request engine start.",
                     facility: .ramp)
    }

    func requestTaxi(context c: ATCContext) -> ATCTransmission {
        pilot("Ground, \(c.callsign.display), request taxi.",
              "Ground, \(c.callsign.spoken), request taxi.",
              facility: .ground)
    }

    func readyForDeparture(context c: ATCContext) -> ATCTransmission {
        pilot("Tower, \(c.callsign.display), holding short runway \(c.runway), ready for departure.",
              "Tower, \(c.callsign.spoken), holding short runway \(Phonetic.runway(c.runway, icao: icao)), ready for departure.",
              facility: .tower)
    }

    func requestTakeoff(context c: ATCContext) -> ATCTransmission {
        pilot("\(c.callsign.display), ready for departure.",
              "\(c.callsign.spoken), ready for departure.",
              facility: .tower)
    }

    func requestHigher(context c: ATCContext, target: Int) -> ATCTransmission {
        pilot("\(c.callsign.display), request \(engine.formatAltDisplay(target)).",
              "\(c.callsign.spoken), request \(Phonetic.altitude(target, icao: icao)).",
              facility: .center)
    }

    func requestLower(context c: ATCContext, target: Int) -> ATCTransmission {
        pilot("\(c.callsign.display), request descent to \(engine.formatAltDisplay(target)).",
              "\(c.callsign.spoken), request descent to \(Phonetic.altitude(target, icao: icao)).",
              facility: .center)
    }

    func requestVectors(context c: ATCContext) -> ATCTransmission {
        pilot("\(c.callsign.display), request vectors for the approach.",
              "\(c.callsign.spoken), request vectors for the approach.",
              facility: .approach)
    }

    func requestApproach(context c: ATCContext) -> ATCTransmission {
        // Prefer the parsed procedure (approach *type* + runway) so the runway is
        // named exactly once — "request the ILS runway 01R approach" — rather than
        // echoing a display name that already contains "RWY 01R" and then repeating
        // the runway ("the ILS RWY 01R runway 01R approach").
        if let approach = c.approachProcedure {
            let rwy = approach.runway ?? c.runway
            let typeD = approach.approachType?.display ?? "approach"
            let typeS = approach.approachType?.spoken ?? "approach"
            return pilot("\(c.callsign.display), request the \(typeD) runway \(rwy) approach.",
                         "\(c.callsign.spoken), request the \(typeS) runway \(Phonetic.runway(rwy, icao: icao)) approach.",
                         facility: .approach)
        }
        let app = c.approachName.isEmpty ? "ILS" : c.approachName
        return pilot("\(c.callsign.display), request the \(app) runway \(c.runway) approach.",
                     "\(c.callsign.spoken), request the \(c.approachName.isEmpty ? "I L S" : c.approachName) runway \(Phonetic.runway(c.runway, icao: icao)) approach.",
                     facility: .approach)
    }

    func requestRideReports(context c: ATCContext) -> ATCTransmission {
        pilot("\(c.callsign.display), any ride reports along our route?",
              "\(c.callsign.spoken), any ride reports along our route?",
              facility: .center)
    }

    func requestWeather(context c: ATCContext, airport: String) -> ATCTransmission {
        pilot("\(c.callsign.display), request latest \(airport) weather.",
              "\(c.callsign.spoken), request latest \(Phonetic.spellToken(airport, icao: icao)) weather.",
              facility: .center)
    }

    func requestHandoff(context c: ATCContext, facility: ATCFacility) -> ATCTransmission {
        pilot("\(facility.spokenName), \(c.callsign.display), checking in.",
              "\(facility.spokenName), \(c.callsign.spoken), checking in.",
              facility: facility)
    }
}
