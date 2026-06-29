import XCTest
@testable import IFATCCompanion

/// The "Ramp" button is context-aware: pushback before departure, taxi-to-gate on
/// arrival — and the arrival block-in ("flight complete") only fires once the
/// aircraft is actually parked at the gate with the parking brake set.
@MainActor
final class RampFlowTests: XCTestCase {

    private func makeModel(mock: Bool = true) -> AppModel {
        let model = AppModel()
        model.settings.voiceEnabled = false
        model.settings.mockMode = mock
        model.settings.initialClimbAltitudeFt = 5000
        model.settings.traconCeilingFL = 180
        model.unicom.mode = .off

        var plan = FlightPlan()
        plan.airline = "United"
        plan.flightNumber = "598"
        plan.departure = "KIAH"
        plan.destination = "KMSP"
        plan.cruiseAltitude = 37000
        plan.star = "KKILR"
        plan.approach = "ILS 30L"
        plan.waypoints = model.mock.route.waypoints
        model.flightPlan = plan
        return model
    }

    private func has(_ model: AppModel, _ needle: String) -> Bool {
        model.transcript.contains { $0.displayText.contains(needle) }
    }

    /// Feed a realistic airborne→arrival telemetry chain so the phase detector's
    /// hysteresis lands the aircraft in the taxi-in ground state.
    private func flyToArrivalGround(_ model: AppModel) {
        for phase in [FlightPhase.takeoff, .climb, .cruise, .descent, .approach, .landing, .taxiIn] {
            model.ingestStateForTesting(model.mock.state(for: phase))
        }
    }

    /// Contacting Ramp before departure approves the pushback — it must NOT run the
    /// arrival "proceed to the gate / flight complete" routine.
    func testContactRampBeforeDepartureIsPushbackNotArrival() {
        let model = makeModel()
        model.requestClearance(); model.readBack()
        XCTAssertTrue(model.isPreDeparture)

        model.contactRamp()

        XCTAssertTrue(has(model, "pushback approved"), "departure Ramp should approve the push")
        XCTAssertFalse(has(model, "proceed to"), "departure Ramp must not route to the gate")
        XCTAssertFalse(has(model, "Flight complete"), "departure Ramp must not end the flight")
    }

    /// On arrival, contacting Ramp routes to the gate but does NOT declare the flight
    /// complete until the aircraft is parked at the gate with the parking brake set.
    func testArrivalRampDefersFlightCompleteUntilParkedWithBrake() {
        let model = makeModel(mock: false)   // exercise the live deferral path

        // Drive into an arrival ground state via telemetry.
        flyToArrivalGround(model)
        XCTAssertTrue(model.isArrivalRamp)

        model.contactRamp()
        XCTAssertTrue(has(model, "proceed to"), "arrival Ramp should route to the gate")
        XCTAssertFalse(has(model, "Flight complete"), "must not be parked while still taxiing")

        // Stopped on the ramp but brake released (e.g. holding for traffic): not parked.
        var stoppedNoBrake = model.mock.state(for: .taxiIn)
        stoppedNoBrake.groundSpeed = 0
        stoppedNoBrake.parkingBrakeSet = false
        model.ingestStateForTesting(stoppedNoBrake)
        XCTAssertFalse(has(model, "Flight complete"), "a full stop without the brake set is not parked")

        // Parked at the gate with the brake set → block-in / flight complete.
        model.ingestStateForTesting(model.mock.state(for: .parked))
        XCTAssertTrue(has(model, "Flight complete"), "parked at the gate should complete the flight")
    }

    /// The Ramp button is offered before departure and on arrival, but not once parked.
    func testCanContactRampGating() {
        let model = makeModel(mock: false)
        XCTAssertTrue(model.canContactRamp, "available before departure for pushback")

        model.ingestStateForTesting(model.mock.state(for: .takeoff))
        model.ingestStateForTesting(model.mock.state(for: .climb))
        model.ingestStateForTesting(model.mock.state(for: .cruise))
        XCTAssertFalse(model.canContactRamp, "not available enroute")

        flyToArrivalGround(model)
        XCTAssertTrue(model.canContactRamp, "available on arrival to taxi to the gate")
    }
}
