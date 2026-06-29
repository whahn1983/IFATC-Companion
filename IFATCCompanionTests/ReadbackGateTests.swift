import XCTest
@testable import IFATCCompanion

/// Verifies the live-mode read-back gate: automatic controller calls hold for the
/// pilot's read-back instead of firing back-to-back, and the flow resumes once the
/// pilot acknowledges. This is the fix for calls "spitting out" near the runway.
@MainActor
final class ReadbackGateTests: XCTestCase {

    /// A model configured for *live* mode (mockMode = false) so the read-back gate
    /// is active. No networking is involved — states are fed directly through the
    /// same pipeline the live feed uses.
    private func makeLiveModel() -> AppModel {
        let model = AppModel()
        model.settings.voiceEnabled = false
        model.settings.mockMode = false            // live mode → gate active
        model.settings.initialClimbAltitudeFt = 5000
        model.settings.traconCeilingFL = 180
        model.unicom.mode = .off

        var plan = FlightPlan()
        plan.airline = "United"
        plan.flightNumber = "598"
        plan.departure = "KIAH"
        plan.destination = "KMSP"
        plan.cruiseAltitude = 37000
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

    /// Drive the pilot-led pre-departure flow up to line-up-and-wait.
    private func driveToLineUp(_ model: AppModel) {
        model.requestClearance();        model.readBack()
        model.requestPushback();         model.readBack()
        model.requestEngineStart();      model.readBack()
        model.requestTaxi();             model.readBack()
        model.reportReadyForDeparture(); model.readBack()
    }

    /// Feed a phase a few times, reading back whenever the gate closes, so the
    /// automatic flow settles on the call(s) that phase produces.
    private func feedUntilSettled(_ model: AppModel, _ phase: FlightPhase, ticks: Int = 4) {
        for _ in 0..<ticks {
            model.ingestStateForTesting(model.mock.state(for: phase))
            if model.awaitingReadback { model.readBack() }
        }
    }

    func testAutomaticCallsHoldForReadbackAndDoNotPileUp() {
        let model = makeLiveModel()
        driveToLineUp(model)

        // The takeoff roll triggers the automatic takeoff clearance, which then
        // HOLDS for the pilot's read-back.
        model.ingestStateForTesting(model.mock.state(for: .takeoff))
        XCTAssertTrue(contains(model, "cleared for takeoff", sender: .atc))
        XCTAssertTrue(model.awaitingReadback, "controller should wait for a read-back")
        let afterClearance = model.transcript.count

        // Continuing to feed telemetry without reading back must NOT add new calls —
        // this is the behaviour that previously cascaded.
        model.ingestStateForTesting(model.mock.state(for: .initialClimb))
        model.ingestStateForTesting(model.mock.state(for: .climb))
        model.ingestStateForTesting(model.mock.state(for: .climb))
        XCTAssertEqual(model.transcript.count, afterClearance,
                       "automatic calls must not pile up before the pilot reads back")

        // Once the pilot reads back, the gate opens and the next telemetry advances.
        model.readBack()
        let afterReadback = model.transcript.count
        model.ingestStateForTesting(model.mock.state(for: .climb))
        XCTAssertGreaterThan(model.transcript.count, afterReadback,
                             "flow should resume after the pilot reads back")
    }

    /// The Departure → Center hand-off is announced as the climb passes the TRACON
    /// ceiling (FL180 by default).
    func testDepartureHandsOffToCenterThroughTheClimb() {
        let model = makeLiveModel()
        driveToLineUp(model)

        // Cleared for takeoff, read back.
        model.ingestStateForTesting(model.mock.state(for: .takeoff))
        model.readBack()

        // Work the climb, reading back at each step, until Center has the aircraft.
        for _ in 0..<6 {
            model.ingestStateForTesting(model.mock.state(for: .climb))
            if model.awaitingReadback { model.readBack() }
        }
        XCTAssertTrue(contains(model, "contact Center", sender: .atc),
                      "Departure should hand off to Center through the climb")
    }

    /// The arrival produces, in order: Center's descend-via-STAR at top of descent,
    /// the Center→Approach hand-off descending through the ceiling, the cleared
    /// approach once established, and the Approach→Tower hand-off.
    func testArrivalDescendViaStarApproachAndTowerHandoffs() {
        let model = makeLiveModel()
        model.flightPlan.star = "KKILR"
        model.flightPlan.approach = "ILS 30L"

        driveToLineUp(model)
        model.ingestStateForTesting(model.mock.state(for: .takeoff)); model.readBack()
        feedUntilSettled(model, .climb)
        feedUntilSettled(model, .cruise)
        feedUntilSettled(model, .descent)
        feedUntilSettled(model, .approach)

        XCTAssertTrue(contains(model, "descend via the KKILR arrival", sender: .atc),
                      "top of descent should produce the descend-via-STAR call")
        XCTAssertTrue(contains(model, "contact Approach", sender: .atc),
                      "Center should hand off to Approach through the ceiling")
        XCTAssertTrue(contains(model, "cleared ILS RWY 30L approach", sender: .atc),
                      "Approach should clear the approach once established")
        XCTAssertTrue(contains(model, "contact Tower", sender: .atc),
                      "Approach should hand off to Tower once established")
    }
}
