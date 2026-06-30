import XCTest
@testable import IFATCCompanion

/// Drives a full flight using the manual **frequency-tune** buttons instead of the
/// automatic position callouts, and asserts that:
///   1. each controller call appears, in gate-to-gate order;
///   2. the pilot checks in on every frequency change;
///   3. no automatic "contact …" hand-offs are inserted (the pilot tuned the
///      frequency, so the controller doesn't tell them to switch); and
///   4. once tuning manually, feeding telemetry no longer auto-advances the
///      conversation — fixing the "calls fire one after the next" behavior.
@MainActor
final class ManualTuningTests: XCTestCase {

    private func makeModel() -> AppModel {
        let model = AppModel()
        model.settings.voiceEnabled = false
        model.settings.mockMode = true
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

    /// Pilot-driven pre-departure, then tune every controller by hand.
    private func runManualFlight() -> AppModel {
        let model = makeModel()

        model.requestClearance();        model.readBack()
        model.requestPushback();         model.readBack()
        model.requestEngineStart();      model.readBack()
        model.requestTaxi();             model.readBack()   // Ramp hands off to Ground
        model.requestTaxi();             model.readBack()   // Ground issues the taxi clearance
        model.reportReadyForDeparture(); model.readBack()   // line up and wait

        // Pilot tunes each frequency in turn and then checks in to call up the new
        // controller (tuning no longer checks in automatically). Center/Approach/
        // Tower are re-tapped for their successive calls.
        model.tuneTo(.tower);     model.requestHandoff(); model.readBack()   // cleared for takeoff
        model.tuneTo(.departure); model.requestHandoff(); model.readBack()   // departure climb
        model.tuneTo(.center);    model.requestHandoff(); model.readBack()   // climb to cruise
        model.tuneTo(.center);    model.requestHandoff()                     // radar contact (not read back)
        model.tuneTo(.center);    model.requestHandoff(); model.readBack()   // descend via the STAR
        model.tuneTo(.approach);  model.requestHandoff(); model.readBack()   // expect approach
        model.tuneTo(.approach);  model.requestHandoff(); model.readBack()   // cleared approach
        model.tuneTo(.tower);     model.requestHandoff(); model.readBack()   // cleared to land
        model.tuneTo(.tower);     model.requestHandoff(); model.readBack()   // exit runway, contact Ground
        model.tuneTo(.ground);    model.requestHandoff(); model.readBack()   // taxi to parking
        model.arriveAtGate()                                                 // arrival courtesy
        return model
    }

    func testManualTuningProducesFullControllerSequenceInOrder() {
        let model = runManualFlight()

        XCTAssertTrue(contains(model, "cleared to KMSP", sender: .atc))
        XCTAssertTrue(contains(model, "cleared for takeoff", sender: .atc))
        XCTAssertTrue(contains(model, "radar contact", sender: .atc))
        XCTAssertTrue(contains(model, "descend via the KKILR arrival", sender: .atc))
        XCTAssertTrue(contains(model, "cleared ILS RWY 30L approach", sender: .atc))
        XCTAssertTrue(contains(model, "cleared to land", sender: .atc))
        XCTAssertTrue(contains(model, "exit the runway when able, contact Ground", sender: .atc))
        XCTAssertTrue(contains(model, "taxi to parking", sender: .atc))

        let order = [
            index(model, "cleared for takeoff", sender: .atc),
            index(model, "descend via the KKILR arrival", sender: .atc),
            index(model, "cleared ILS RWY 30L approach", sender: .atc),
            index(model, "cleared to land", sender: .atc),
            index(model, "taxi to parking", sender: .atc)
        ]
        XCTAssertFalse(order.contains(nil), "a stage is missing: \(order)")
        let unwrapped = order.compactMap { $0 }
        XCTAssertEqual(unwrapped, unwrapped.sorted(), "controller calls are out of order")
    }

    func testManualTuningPostsPilotCheckInsAndSuppressesAutoHandoffs() {
        let model = runManualFlight()

        // The pilot checks in on each newly tuned frequency.
        XCTAssertTrue(contains(model, "checking in", sender: .pilot),
                      "pilot should check in when tuning a frequency")

        // Because the pilot tuned the frequency, the controller never tells them to
        // "contact …" the next facility.
        for handoff in ["contact Departure", "contact Center", "contact Approach", "contact Tower"] {
            XCTAssertFalse(contains(model, handoff, sender: .atc),
                           "manual tuning should not insert an automatic '\(handoff)' hand-off")
        }
    }

    func testTelemetryDoesNotAutoAdvanceAfterManualTuning() {
        let model = makeModel()
        model.requestClearance();        model.readBack()
        model.requestPushback();         model.readBack()
        model.requestEngineStart();      model.readBack()
        model.requestTaxi();             model.readBack()   // Ramp hands off to Ground
        model.requestTaxi();             model.readBack()   // Ground issues the taxi clearance
        model.reportReadyForDeparture(); model.readBack()

        model.tuneTo(.tower)             // engages manual tuning (no auto check-in)
        let countAfterTune = model.transcript.count

        // Feeding airborne telemetry must NOT add any further controller calls — the
        // pilot is driving frequency changes by hand now.
        model.ingestStateForTesting(model.mock.state(for: .initialClimb))
        model.ingestStateForTesting(model.mock.state(for: .climb))
        model.ingestStateForTesting(model.mock.state(for: .cruise))

        XCTAssertEqual(model.transcript.count, countAfterTune,
                       "telemetry should not auto-advance the conversation once tuning manually")
        XCTAssertTrue(model.manualTuning)
    }

    /// The pushback hand-off is issued once — at the end of the IFR clearance —
    /// not repeated as a separate "contact Ramp" line when the pilot requests the
    /// push. (Previously Clearance told the pilot to contact Ramp and then Ramp
    /// approved the push in the same step, which read as a contradiction.)
    func testPushbackHandoffIssuedOnceByClearance() {
        let model = makeModel()
        model.requestClearance(); model.readBack()

        // The clearance itself tells the pilot whom to tune for the push.
        XCTAssertTrue(contains(model, "When ready for pushback, contact Ramp", sender: .atc))
        let rampHandoffsAfterClearance = model.transcript.filter {
            $0.sender == .atc && $0.displayText.contains("contact Ramp")
        }.count

        model.tuneTo(.ramp)
        model.requestPushback(); model.readBack()

        // Requesting the push must not insert another "contact Ramp" hand-off; Ramp
        // simply approves the push.
        let rampHandoffsAfterPush = model.transcript.filter {
            $0.sender == .atc && $0.displayText.contains("contact Ramp")
        }.count
        XCTAssertEqual(rampHandoffsAfterPush, rampHandoffsAfterClearance,
                       "requesting pushback should not add another 'contact Ramp' hand-off")
        XCTAssertTrue(contains(model, "pushback approved", sender: .atc))
    }
}
