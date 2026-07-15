import XCTest
@testable import IFATCCompanion

/// In **live** mode the controller's position-based calls and facility hand-offs
/// must keep firing automatically from telemetry even while the pilot is changing
/// frequencies by hand — each hand-off prompting "contact <next> on …" and then the
/// new controller giving its instruction once the pilot tunes that frequency and
/// checks in. (In Mock Mode, by contrast, manual tuning advances only on a button
/// press — see `ManualTuningTests`.)
@MainActor
final class LiveSemiAutoTuningTests: XCTestCase {

    private func makeLiveModel() -> AppModel {
        let model = AppModel()
        model.settings.voiceEnabled = false
        model.settings.mockMode = false            // live mode → semi-automatic flow
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

    private func contains(_ model: AppModel, _ needle: String,
                          sender: ATCTransmission.Sender? = nil) -> Bool {
        model.transcript.contains { tx in
            (sender == nil || tx.sender == sender) && tx.displayText.contains(needle)
        }
    }

    private func feed(_ model: AppModel, _ phase: FlightPhase, times: Int = 1) {
        for _ in 0..<times {
            model.ingestStateForTesting(model.mock.state(for: phase))
            if model.awaitingReadback { model.readBack() }
        }
    }

    func testManualTuningInLiveModeStillIssuesAutomaticHandoffs() {
        let model = makeLiveModel()

        // Pilot-driven pre-departure flow.
        model.requestClearance();        model.readBack()
        model.requestPushback();         model.readBack()
        model.requestEngineStart();      model.readBack()
        model.requestTaxi();             model.readBack()   // Ramp hands off to Ground
        model.requestTaxi();             model.readBack()   // Ground issues the taxi clearance
        model.reportReadyForDeparture(); model.readBack()   // line up and wait

        // Pilot manually tunes Tower and checks in for the takeoff clearance.
        model.tuneTo(.tower); model.requestHandoff(); model.readBack()
        XCTAssertTrue(model.manualTuning)
        XCTAssertTrue(contains(model, "cleared for takeoff", sender: .atc))

        // Airborne: Tower hands off to Departure automatically (the pilot tuned Tower,
        // not Departure, so this hand-off must still be generated).
        feed(model, .takeoff)        // no-op (already cleared) — advances the phase
        feed(model, .initialClimb)
        XCTAssertTrue(contains(model, "contact Departure", sender: .atc),
                      "Tower should automatically hand off to Departure after takeoff")

        // Pilot tunes Departure and checks in for the climb + direct-to clearance.
        model.tuneTo(.departure); model.requestHandoff(); model.readBack()
        XCTAssertTrue(contains(model, "radar contact", sender: .atc))

        // Passing the TRACON ceiling, Departure hands off to Center automatically.
        feed(model, .climb, times: 2)
        XCTAssertTrue(contains(model, "contact Center", sender: .atc),
                      "Departure should automatically hand off to Center through the ceiling")
        model.tuneTo(.center); model.requestHandoff(); model.readBack()

        // Cruise, then top of descent: Center issues descend-via-STAR on its own
        // frequency (no hand-off needed — same controller).
        feed(model, .cruise)
        feed(model, .descent)
        XCTAssertTrue(contains(model, "descend via the KKILR arrival", sender: .atc),
                      "Center should automatically issue the descend-via-STAR at top of descent")

        // Descending through the ceiling: Center hands off to Approach automatically.
        feed(model, .descent, times: 2)
        XCTAssertTrue(contains(model, "contact Approach", sender: .atc),
                      "Center should automatically hand off to Approach")
        model.tuneTo(.approach); model.requestHandoff(); model.readBack()

        // Established: Approach clears the approach and hands off to Tower automatically.
        feed(model, .approach, times: 2)
        XCTAssertTrue(contains(model, "cleared ILS RWY 30L approach", sender: .atc),
                      "Approach should clear the approach once established")
        XCTAssertTrue(contains(model, "contact Tower", sender: .atc),
                      "Approach should automatically hand off to Tower")

        // Tower clears the landing on check-in, then automatically calls the runway exit.
        model.tuneTo(.tower); model.requestHandoff(); model.readBack()
        XCTAssertTrue(contains(model, "cleared to land", sender: .atc))
        feed(model, .landing, times: 2)
        XCTAssertTrue(contains(model, "exit the runway when able, contact Ground", sender: .atc),
                      "Tower should automatically issue the runway-exit / contact-Ground call")
    }

    /// Regression: on arrival the Ground taxi-to-gate must hold the conversation on
    /// Ground until the pilot reads it back. A "parked" telemetry reading on the very
    /// next tick — a short taxi-in that ends right at the gate — must NOT race the flow
    /// to the parked / flight-complete state. That race is what stranded the taxi
    /// read-back as a bare callsign and hid the arrival Ramp (taxi-to-gate) frequency
    /// before the pilot could use it.
    func testArrivalTaxiToGateHoldsForReadbackBeforeParking() {
        let model = makeLiveModel()
        model.flightPlan.arrivalGate = "C10"

        // Fly the whole flight (semi-automatic, manual tuning) down to the runway exit.
        model.requestClearance();        model.readBack()
        model.requestPushback();         model.readBack()
        model.requestEngineStart();      model.readBack()
        model.requestTaxi();             model.readBack()
        model.requestTaxi();             model.readBack()
        model.reportReadyForDeparture(); model.readBack()
        model.tuneTo(.tower);     model.requestHandoff(); model.readBack()
        feed(model, .takeoff)
        feed(model, .initialClimb)
        model.tuneTo(.departure); model.requestHandoff(); model.readBack()
        feed(model, .climb, times: 2)
        model.tuneTo(.center);    model.requestHandoff(); model.readBack()
        feed(model, .cruise)
        feed(model, .descent)
        feed(model, .descent, times: 2)
        model.tuneTo(.approach);  model.requestHandoff(); model.readBack()
        feed(model, .approach, times: 2)
        model.tuneTo(.tower);     model.requestHandoff(); model.readBack()
        feed(model, .landing, times: 2)
        XCTAssertTrue(contains(model, "exit the runway when able, contact Ground", sender: .atc))

        // Clear of the runway: the pilot tunes Ground and checks in for the taxi-in.
        model.tuneTo(.ground); model.requestHandoff()
        XCTAssertTrue(contains(model, "taxi to gate C10", sender: .atc),
                      "Ground should issue the taxi-to-gate on check-in")

        // A parked telemetry reading now arrives — before the pilot has read back.
        model.ingestStateForTesting(model.mock.state(for: .parked))
        model.ingestStateForTesting(model.mock.state(for: .parked))

        // The arrival stays on Ground with the Ramp hand-off still available — it must
        // not have collapsed to the parked / flight-complete state.
        XCTAssertEqual(model.atcState, .groundArrival,
                       "the taxi-to-gate must hold for the read-back, not race to parked")
        XCTAssertFalse(contains(model, "Flight complete"),
                       "the flight must not complete over an unacknowledged taxi instruction")
        XCTAssertTrue(model.isArrivalRamp)
        XCTAssertTrue(model.canContactRamp,
                      "the arrival Ramp (taxi-to-gate) frequency must still be offered")

        // The Read Back button echoes the taxi-to-gate routing, never a bare callsign.
        model.readBack()
        let lastPilot = model.transcript.last { $0.sender == .pilot }
        XCTAssertTrue(lastPilot?.displayText.lowercased().contains("taxi to gate c10") ?? false,
                      "read-back should echo the taxi-to-gate: \(lastPilot?.displayText ?? "nil")")
    }

    /// Tuning the controller you're already being handed to must not produce a
    /// redundant "contact <that facility>" — the original bug where requesting taxi
    /// on Ground replied "contact Ground" and then cleared the taxi.
    func testCheckInOnTunedFacilityHasNoRedundantHandoff() {
        let model = makeLiveModel()
        model.requestClearance(); model.readBack()
        model.requestPushback();  model.readBack()
        model.requestEngineStart(); model.readBack()

        // Pilot manually tunes Ground, then requests taxi.
        model.tuneTo(.ground)
        model.requestTaxi(); model.readBack()

        XCTAssertTrue(contains(model, "taxi to runway", sender: .atc))
        XCTAssertFalse(contains(model, "contact Ground", sender: .atc),
                       "tuning Ground yourself should not produce a 'contact Ground' hand-off")
    }
}
