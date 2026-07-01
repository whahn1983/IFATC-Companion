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

    /// Requesting taxi while still on the Ramp hands the pilot to Ground only — it
    /// must not issue the taxi clearance. The pilot then requests taxi again on
    /// Ground for the actual clearance.
    func testTaxiOnRampHandsOffToGroundBeforeClearing() {
        let model = makeModel()
        model.requestClearance();   model.readBack()
        model.requestPushback();    model.readBack()
        model.requestEngineStart(); model.readBack()
        XCTAssertEqual(model.currentFacility, .ramp)

        model.requestTaxi()
        XCTAssertTrue(has(model, "contact Ground"), "Ramp should hand the pilot to Ground")
        XCTAssertFalse(has(model, "taxi to runway"), "Ramp must not issue the taxi clearance")
        XCTAssertEqual(model.currentFacility, .ground, "the pilot is now on Ground")

        model.readBack()
        model.requestTaxi()
        XCTAssertTrue(has(model, "taxi to runway"), "Ground should issue the taxi clearance")
    }

    /// Reading back the Ramp→Ground hand-off must echo the Ground frequency /
    /// movement-area boundary — not a stale "start approved" derived from the
    /// engine-start state the conversation is still sitting on.
    func testRampToGroundHandoffReadbackEchoesGroundFrequency() {
        let model = makeModel()
        model.requestClearance();   model.readBack()
        model.requestPushback();    model.readBack()
        model.requestEngineStart(); model.readBack()

        model.requestTaxi()   // Ramp hands the pilot to Ground
        model.readBack()      // read back the hand-off

        let lastPilot = model.transcript.last { $0.sender == .pilot }
        XCTAssertNotNil(lastPilot)
        XCTAssertTrue(lastPilot?.displayText.contains("121.800") ?? false,
                      "read-back should echo the Ground frequency: \(lastPilot?.displayText ?? "nil")")
        XCTAssertFalse(lastPilot?.displayText.lowercased().contains("start approved") ?? true,
                       "read-back must not echo the stale start-approved call")
    }

    /// After the IFR clearance, Pushback is NOT offered while still on Clearance — the
    /// pilot must tune the Ramp frequency first, where the Pushback button then appears.
    func testPushbackOfferedOnlyAfterTuningRamp() {
        let model = makeModel()
        model.requestClearance(); model.readBack()
        XCTAssertEqual(model.currentFacility, .clearance)
        XCTAssertFalse(model.availableActions.contains(.pushback),
                       "Pushback must not show under Clearance")

        model.tuneTo(.ramp)
        XCTAssertTrue(model.availableActions.contains(.pushback),
                      "Pushback should appear once tuned to Ramp")
    }

    /// The pushback request uses the departure gate, never the arrival gate.
    func testPushbackUsesDepartureGateNotArrivalGate() {
        let model = makeModel()
        model.flightPlan.departureGate = "C12"
        model.flightPlan.arrivalGate = "B44"
        model.requestClearance(); model.readBack()
        model.requestPushback()

        XCTAssertTrue(model.transcript.contains {
            $0.sender == .pilot && $0.displayText.contains("C12")
        }, "pushback should name the departure gate")
        XCTAssertFalse(model.transcript.contains { $0.displayText.contains("B44") },
                       "pushback must not name the arrival gate")
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
