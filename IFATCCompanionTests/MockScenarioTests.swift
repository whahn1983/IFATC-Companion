import XCTest
@testable import IFATCCompanion

/// End-to-end test that drives the `AppModel` through a complete, realistic mock
/// flight (gate → gate) and asserts the controller/pilot dialogue is correct and in
/// order. It exercises the full pipeline — phase detection → state machine →
/// phraseology → transcript — the same way the live/mock feeds do.
@MainActor
final class MockScenarioTests: XCTestCase {

    /// Build an AppModel wired for an offline, silent, automatic mock flight from
    /// KIAH to KMSP with a filed STAR and ILS approach.
    private func makeModel() -> AppModel {
        let model = AppModel()
        model.settings.voiceEnabled = false      // no audio in tests
        model.settings.mockMode = true
        model.settings.initialClimbAltitudeFt = 5000
        model.settings.traconCeilingFL = 180
        model.unicom.mode = .off                 // no Connect side effects

        var plan = FlightPlan()
        plan.airline = "United"
        plan.flightNumber = "598"
        plan.departure = "KIAH"
        plan.destination = "KMSP"
        plan.cruiseAltitude = 37000
        plan.star = "KKILR"                       // filed arrival
        plan.approach = "ILS 30L"                 // filed approach
        plan.waypoints = model.mock.route.waypoints
        model.flightPlan = plan
        return model
    }

    /// Feed a phase's synthesized aircraft state `times` ticks through the pipeline.
    private func feed(_ model: AppModel, _ phase: FlightPhase, times: Int = 1) {
        for _ in 0..<times {
            model.ingestStateForTesting(model.mock.state(for: phase))
        }
    }

    /// Drive a full gate-to-gate scenario and return the resulting model.
    ///
    /// The pre-departure ground flow is pilot-driven via the response-button
    /// methods (each instruction read back manually). The position-triggered
    /// controller calls — which the mock autopilot would play on a timer — are
    /// reproduced here synchronously by feeding the matching aircraft states, with
    /// a manual read-back after each substantive instruction.
    private func runFullFlight() -> AppModel {
        let model = makeModel()

        // Pilot-driven pre-departure flow (manual buttons + read-backs).
        model.requestClearance();        model.readBack()
        model.requestPushback();         model.readBack()
        model.requestEngineStart();      model.readBack()
        model.requestTaxi();             model.readBack()
        model.reportReadyForDeparture(); model.readBack()   // line up and wait

        // Automatic, position-triggered controller calls (pilot reads back / checks
        // in manually between them).
        feed(model, .takeoff);              model.readBack()  // cleared for takeoff
        feed(model, .initialClimb);         model.readBack()  // contact Departure + climb
        feed(model, .climb);                model.readBack()  // contact Center + climb
        feed(model, .cruise)                                  // radar contact (not read back)
        feed(model, .descent);              model.readBack()  // descend via the STAR
        feed(model, .approach, times: 2);   model.readBack()  // expect, then cleared approach
        feed(model, .landing, times: 2);    model.readBack()  // cleared to land, then exit runway
        feed(model, .taxiIn);               model.readBack()  // taxi to parking
        feed(model, .parked)                                  // arrival courtesy
        return model
    }

    // MARK: - Helpers

    private func index(_ model: AppModel, _ needle: String,
                       sender: ATCTransmission.Sender? = nil) -> Int? {
        model.transcript.firstIndex { tx in
            (sender == nil || tx.sender == sender) && tx.displayText.contains(needle)
        }
    }

    private func contains(_ model: AppModel, _ needle: String,
                          sender: ATCTransmission.Sender? = nil) -> Bool {
        index(model, needle, sender: sender) != nil
    }

    // MARK: - The full realistic sequence

    func testFullMockFlightProducesRealisticControllerSequence() {
        let model = runFullFlight()

        // Departure controller calls.
        XCTAssertTrue(contains(model, "cleared to KMSP", sender: .atc), "clearance missing")
        XCTAssertTrue(contains(model, "pushback approved", sender: .atc))
        XCTAssertTrue(contains(model, "start approved", sender: .atc))
        XCTAssertTrue(contains(model, "taxi to runway", sender: .atc))
        XCTAssertTrue(contains(model, "line up and wait", sender: .atc))
        XCTAssertTrue(contains(model, "cleared for takeoff", sender: .atc))
        XCTAssertTrue(contains(model, "radar contact", sender: .atc))

        // Facility hand-offs in both directions.
        XCTAssertTrue(contains(model, "contact Departure", sender: .atc))
        XCTAssertTrue(contains(model, "contact Center", sender: .atc))
        XCTAssertTrue(contains(model, "contact Approach", sender: .atc))
        XCTAssertTrue(contains(model, "contact Tower", sender: .atc))

        // Arrival controller calls.
        XCTAssertTrue(contains(model, "expect the ILS runway 30L approach", sender: .atc),
                      "expect-approach call missing or doubly worded")
        XCTAssertTrue(contains(model, "cleared ILS RWY 30L approach", sender: .atc),
                      "cleared-approach call missing before Tower hand-off")
        XCTAssertTrue(contains(model, "cleared to land", sender: .atc))
        XCTAssertTrue(contains(model, "exit the runway when able, contact Ground", sender: .atc),
                      "post-landing exit/contact-ground call missing")
        XCTAssertTrue(contains(model, "taxi to parking", sender: .atc))
    }

    // MARK: - Descent: STAR + no contradiction

    func testDescentSaysDescendViaStarAndIsNotContradictory() {
        let model = runFullFlight()
        XCTAssertTrue(contains(model, "descend via the KKILR arrival", sender: .atc),
                      "filed STAR should produce a descend-via-arrival call")
        // The contradictory "descend at pilot's discretion … maintain <cruise>" must
        // not appear in the automatic descent.
        let allText = model.transcript.map(\.displayText).joined(separator: "\n")
        XCTAssertFalse(allText.contains("pilot's discretion"),
                       "automatic descent must not use the contradictory discretion phrasing")
        XCTAssertFalse(allText.contains("maintain FL370"),
                       "descent must not tell the pilot to maintain the cruise level")
    }

    // MARK: - Pilot readbacks before progressing

    func testPilotReadsBackBeforeFlowProgresses() {
        let model = runFullFlight()

        // The clearance is read back before pushback is issued.
        let clearanceReadback = index(model, "Cleared to KMSP", sender: .pilot)
        let pushback = index(model, "pushback approved", sender: .atc)
        XCTAssertNotNil(clearanceReadback, "missing clearance readback")
        XCTAssertNotNil(pushback)
        XCTAssertLessThan(clearanceReadback!, pushback!,
                          "pilot should read back the clearance before pushback")

        // Substantive arrival instructions are read back too.
        XCTAssertTrue(contains(model, "Descend via the KKILR arrival", sender: .pilot),
                      "missing STAR readback")
        XCTAssertTrue(contains(model, "Cleared the ILS runway 30L", sender: .pilot),
                      "missing approach-clearance readback")
        XCTAssertTrue(contains(model, "Exiting the runway, contact Ground", sender: .pilot),
                      "missing runway-exit readback")

        // Plenty of readbacks across the flight (one per substantive instruction).
        let pilotReadbacks = model.transcript.filter { $0.sender == .pilot }
        XCTAssertGreaterThan(pilotReadbacks.count, 8)
    }

    // MARK: - Overall ordering is gate-to-gate

    func testTranscriptOrderingIsGateToGate() {
        let model = runFullFlight()
        let order = [
            index(model, "cleared to KMSP", sender: .atc),
            index(model, "cleared for takeoff", sender: .atc),
            index(model, "descend via the KKILR arrival", sender: .atc),
            index(model, "cleared ILS RWY 30L approach", sender: .atc),
            index(model, "cleared to land", sender: .atc),
            index(model, "exit the runway when able", sender: .atc),
            index(model, "taxi to parking", sender: .atc)
        ]
        XCTAssertFalse(order.contains(nil), "a stage of the flight is missing: \(order)")
        let unwrapped = order.compactMap { $0 }
        XCTAssertEqual(unwrapped, unwrapped.sorted(), "controller calls are out of order")
    }
}
