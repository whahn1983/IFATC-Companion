import XCTest
import CoreLocation
@testable import IFATCCompanion

/// The active frequency must follow a hand-off only **after** the pilot reads it back —
/// never the moment ATC issues the "contact <next> on …". While the hand-off is
/// outstanding the radio stays on the controller the pilot is actually tuned to
/// (`currentFacility`), even though the controller they now have to deal with
/// (`workingFacility`, driving the response buttons / check-in) is the next one.
///
/// The `autoTuneOnHandoff` setting (on by default) gates the auto-tune: with it off,
/// reading the hand-off back changes nothing — the pilot tunes the next controller by
/// hand with the tune buttons.
@MainActor
final class AutoTuneOnHandoffTests: XCTestCase {

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

    /// Fly (manual tuning, live) up to the automatic Tower→Departure hand-off, leaving it
    /// posted but not yet read back.
    private func flyToDepartureHandoff(_ model: AppModel) {
        model.requestClearance();        model.readBack()
        model.requestPushback();         model.readBack()
        model.requestEngineStart();      model.readBack()
        model.requestTaxi();             model.readBack()
        model.requestTaxi();             model.readBack()
        model.reportReadyForDeparture(); model.readBack()
        model.tuneTo(.tower); model.requestHandoff(); model.readBack()   // cleared for takeoff
        feed(model, .takeoff)
        feed(model, .initialClimb)                                       // Tower → Departure hand-off
    }

    /// With auto-tune on (the default), the radio stays on Tower while the hand-off is
    /// outstanding and only moves to Departure once the pilot reads the hand-off back.
    func testAutoTunesToNextFrequencyOnlyAfterReadback() {
        let model = makeLiveModel()
        flyToDepartureHandoff(model)

        // The hand-off has been issued…
        XCTAssertTrue(contains(model, "contact Departure", sender: .atc),
                      "Tower should hand off to Departure automatically after takeoff")
        // …but the radio has NOT tuned yet — still on Tower, the frequency actually dialed.
        XCTAssertEqual(model.currentFacility, .tower,
                       "the active frequency must stay on Tower until the hand-off is read back")
        // The controller the pilot must now deal with is Departure (drives the buttons).
        XCTAssertEqual(model.workingFacility, .departure)
        XCTAssertTrue(model.availableActions.contains(.checkIn),
                      "the Departure check-in should be offered while the hand-off is pending")
        // Both frequencies are on the tune grid so the pilot can still change by hand.
        XCTAssertTrue(model.relevantFacilities.contains(.tower))
        XCTAssertTrue(model.relevantFacilities.contains(.departure))

        // Reading the hand-off back is what tunes the radio to Departure.
        model.readBack()
        XCTAssertTrue(contains(model, "Contacting Departure", sender: .pilot))
        XCTAssertEqual(model.currentFacility, .departure,
                       "reading the hand-off back tunes the radio to Departure")
    }

    /// With auto-tune off, reading the hand-off back changes nothing: the pilot has to
    /// tune Departure by hand with the tune button.
    func testAutoTuneOffRequiresManualTuning() {
        let model = makeLiveModel()
        model.settings.autoTuneOnHandoff = false
        flyToDepartureHandoff(model)

        XCTAssertTrue(contains(model, "contact Departure", sender: .atc))
        XCTAssertEqual(model.currentFacility, .tower)

        // Reading it back does NOT move the radio when auto-tune is off.
        model.readBack()
        XCTAssertEqual(model.currentFacility, .tower,
                       "auto-tune off: the read-back must not change the tuned frequency")

        // The pilot tunes Departure by hand — only then does the active frequency change.
        model.tuneTo(.departure)
        XCTAssertEqual(model.currentFacility, .departure)
        model.requestHandoff()
        XCTAssertTrue(contains(model, "radar contact", sender: .atc),
                      "checking in on the hand-tuned frequency still advances the flow")
    }
}
