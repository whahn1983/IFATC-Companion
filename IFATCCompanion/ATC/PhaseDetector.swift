import Foundation
import CoreLocation

/// Deterministic, conservative flight-phase detection from aircraft state.
/// Exposes intermediate reasoning via `Debug` for the Diagnostics screen.
struct PhaseDetector {

    struct Debug: Equatable {
        var onGround: Bool = false
        var groundSpeed: Double = 0
        var altitudeMSL: Double = 0
        var verticalSpeed: Double = 0
        var distanceToDestNM: Double?
        var distanceToDepNM: Double?
        var notes: [String] = []
    }

    /// Detect the current phase. `previous` provides hysteresis so we don't
    /// oscillate between adjacent phases on noisy data.
    func detect(state: AircraftState,
                plan: FlightPlan,
                airports: AirportDatabase,
                previous: FlightPhase) -> (phase: FlightPhase, debug: Debug) {
        var debug = Debug()
        let gs = state.groundSpeed ?? 0
        let alt = state.altitudeMSL ?? 0
        let vs = state.verticalSpeed ?? 0
        // Whether the aircraft is on the ground is *reported*, never assumed. Infinite
        // Flight answers each state read as its own request/response, so any one of them
        // can time out or be dropped while the rest of the snapshot arrives intact — most
        // often around a reconnect, including the forced one the app performs when it
        // returns from the background. A snapshot carrying a position and an altitude but
        // no on-ground flag is therefore routine, and reading that missing flag as
        // "airborne" is what put a taxiing aircraft into the climb: the airborne branch
        // below has no better answer for a slow, level fix than "climb", which is Center
        // on the radio while the aircraft is still on the taxiway.
        let reportedOnGround = state.onGround ?? state.altitudeAGL.map { $0 < 10 }
        debug.groundSpeed = gs
        debug.altitudeMSL = alt
        debug.verticalSpeed = vs

        let coord = state.coordinate
        // Infinite Flight's reported field position is the source of truth; the built-in
        // hub table is only a last resort for a manually-entered ICAO IF isn't reporting.
        let depCoord = plan.departureCoordinate ?? airports.coordinate(for: plan.departure)
        let destCoord = plan.destinationCoordinate ?? airports.coordinate(for: plan.destination)
        if let coord, let depCoord {
            debug.distanceToDepNM = Geo.distanceNM(from: coord, to: depCoord)
        }
        if let coord, let destCoord {
            debug.distanceToDestNM = Geo.distanceNM(from: coord, to: destCoord)
        }

        // With no ground reference in this snapshot, hold the phase the flight is already
        // in rather than guess at it. Only a genuine vertical rate overrides the hold —
        // ground speed can't, since a takeoff roll is fast and firmly on the runway.
        let onGround: Bool
        if let reportedOnGround {
            onGround = reportedOnGround
        } else if abs(vs) > 500 {
            debug.notes.append("On-ground state not reported — vertical rate shows airborne")
            onGround = false
        } else {
            debug.onGround = previous.isGround
            debug.notes.append("On-ground state not reported — holding \(previous.title)")
            return (previous, debug)
        }
        debug.onGround = onGround

        // --- On the ground ---
        if onGround {
            if gs < 1 {
                // Distinguish pre-departure vs parked-after-arrival using prior phase.
                if [.descent, .approach, .landing, .taxiIn].contains(previous) {
                    // Parked only once the parking brake is set (when the sim exposes
                    // it); a full stop with the brake released is still taxiing in (e.g.
                    // holding for traffic on the ramp), not parked at the gate.
                    if state.parkingBrakeSet == false {
                        debug.notes.append("Stopped on ground, brake released — still taxiing in")
                        return (.taxiIn, debug)
                    }
                    debug.notes.append("Stopped on ground after arrival")
                    return (.parked, debug)
                }
                debug.notes.append("Stopped on ground")
                return (previous == .taxiIn ? .parked : .preflight, debug)
            }
            if gs < 40 {
                // Taxi speed. Decide out vs in by proximity / prior phase.
                let arriving = [.landing, .taxiIn, .descent, .approach].contains(previous)
                debug.notes.append("Taxi speed")
                return (arriving ? .taxiIn : .taxiOut, debug)
            }
            // High ground speed -> takeoff roll or landing rollout.
            let rolloutContext = [.approach, .landing, .descent].contains(previous)
            debug.notes.append("High ground speed on ground")
            return (rolloutContext ? .landing : .takeoff, debug)
        }

        // --- Airborne ---
        let dDest = debug.distanceToDestNM
        let dDep = debug.distanceToDepNM
        let cruise = Double(plan.cruiseAltitude)

        // Landing flare / very low and descending near destination.
        if alt < 2000, let dDest, dDest < 8, vs < -100 {
            debug.notes.append("Low and close to destination")
            return (.approach, debug)
        }

        if vs > 500 {
            // Climbing
            if let dDep, dDep < 15, alt < 8000 {
                debug.notes.append("Climbing near departure")
                return (.initialClimb, debug)
            }
            debug.notes.append("Climbing")
            return (.climb, debug)
        }

        if vs < -500 {
            // Descending
            if let dDest, dDest < 40 {
                debug.notes.append("Descending in terminal area")
                return (.approach, debug)
            }
            debug.notes.append("Descending")
            return (.descent, debug)
        }

        // Roughly level.
        if cruise > 0 {
            if alt >= cruise - 1500 {
                debug.notes.append("Level at/near cruise")
                return (.cruise, debug)
            }
        } else if alt > 17000 {
            debug.notes.append("Level at high altitude")
            return (.cruise, debug)
        }

        if let dDest, dDest < 30 {
            debug.notes.append("Level in terminal area")
            return (.approach, debug)
        }

        // Default: maintain previous airborne phase if sensible, else climb.
        if [.cruise, .climb, .descent, .approach].contains(previous) {
            return (previous, debug)
        }
        debug.notes.append("Default airborne -> climb")
        return (.climb, debug)
    }
}
