import Foundation

/// Deterministic detection of "lined up on the departure runway" from telemetry,
/// used by the automatic ATC flow to issue the takeoff clearance once the aircraft
/// has entered and aligned with the runway. Without a per-runway position model we
/// use a robust proxy: on the ground, low ground speed, and heading aligned with
/// the assigned runway's magnetic heading.
struct RunwayLineupDetector {

    /// Heading alignment tolerance (degrees) for "lined up".
    var headingToleranceDeg: Double = 18
    /// Maximum ground speed (kts) still considered "lining up / holding".
    var maxLineupGroundSpeed: Double = 45
    /// Ground speed (kts) above which the aircraft is considered to be on its
    /// takeoff roll while aligned with the runway.
    var rollGroundSpeed: Double = 30

    /// True when the aircraft appears established on the runway centerline at low
    /// speed (entered the runway and aligned).
    func isLinedUp(state: AircraftState, runway: String) -> Bool {
        guard state.onGround ?? true else { return false }
        guard let aligned = headingAligned(state: state, runway: runway), aligned else { return false }
        let gs = state.groundSpeed ?? 0
        return gs <= maxLineupGroundSpeed
    }

    /// True when aligned with the runway and accelerating down it (takeoff roll).
    func isDepartingRoll(state: AircraftState, runway: String) -> Bool {
        guard let aligned = headingAligned(state: state, runway: runway), aligned else { return false }
        let gs = state.groundSpeed ?? 0
        return gs > rollGroundSpeed
    }

    /// True when airborne, descending, low, and aligned with the landing runway —
    /// i.e. established on final. Used to issue the approach clearance even when the
    /// autopilot approach mode (APPR) is not exposed by Infinite Flight.
    func isOnFinalApproach(state: AircraftState, runway: String) -> Bool {
        guard !(state.onGround ?? false) else { return false }
        guard let aligned = headingAligned(state: state, runway: runway), aligned else { return false }
        let vs = state.verticalSpeed ?? 0
        let agl = state.altitudeAGL ?? (state.altitudeMSL ?? 0)
        return vs < -100 && agl < 4000
    }

    /// Whether the aircraft heading is within tolerance of the runway heading.
    /// Returns nil when the runway/heading can't be determined.
    private func headingAligned(state: AircraftState, runway: String) -> Bool? {
        guard let rwy = PhraseologyEngine.runwayHeading(runway),
              let hdg = state.heading else { return nil }
        return PhraseologyEngine.angularDiff(hdg, Double(rwy)) <= headingToleranceDeg
    }
}
