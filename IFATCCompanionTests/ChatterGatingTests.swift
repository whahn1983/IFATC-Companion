import XCTest
@testable import IFATCCompanion

/// Exercises when the ambient background chatter is allowed to run: held silent until the
/// pilot's first ATC communication, and stopped again once the flight ends. The decision is
/// read through `AppModel.shouldRunAmbientChatter`; snapshots stand in for reaching a given
/// point in the flight without driving the whole telemetry loop.
@MainActor
final class ChatterGatingTests: XCTestCase {

    private func makeModel() -> AppModel {
        let model = AppModel()
        model.settings.voiceEnabled = false
        model.settings.mockMode = true
        model.settings.backgroundChatterEnabled = true
        return model
    }

    private func snapshot(atcState: ATCState, machine: ATCState, hasDeparted: Bool,
                          arrivalAnnounced: Bool = false,
                          transcript: [ATCTransmission]) -> SessionSnapshot {
        SessionSnapshot(atcState: atcState, stateMachineCurrent: machine,
                        currentFacility: .clearance, phase: .preflight, assignedAltitude: 0,
                        hasDeparted: hasDeparted, arrivalAnnounced: arrivalAnnounced,
                        awaitingGateArrival: false, manualTuning: false,
                        transcript: transcript, departure: "KIAH", destination: "KMSP",
                        mockMode: true, savedAt: Date(timeIntervalSince1970: 0))
    }

    private func controllerLine() -> ATCTransmission {
        ATCTransmission(sender: .atc, facility: .clearance, displayText: "Cleared to KMSP as filed.")
    }

    func testChatterHeldUntilFirstATCCommunication() {
        let model = makeModel()
        // Fresh flight, nothing said yet.
        model.applySnapshotForTesting(snapshot(atcState: .connectedIdle, machine: .connectedIdle,
                                                hasDeparted: false, transcript: []))
        XCTAssertFalse(model.shouldRunAmbientChatter,
                       "chatter should stay silent before the first ATC communication")

        // Once a controller/pilot exchange exists, the chatter may run.
        model.applySnapshotForTesting(snapshot(atcState: .clearance, machine: .clearance,
                                                hasDeparted: false, transcript: [controllerLine()]))
        XCTAssertTrue(model.shouldRunAmbientChatter,
                      "chatter should run once the pilot is working ATC")
    }

    func testChatterStopsWhenFlightEnds() {
        let model = makeModel()
        model.applySnapshotForTesting(snapshot(atcState: .parked, machine: .parked,
                                                hasDeparted: true, arrivalAnnounced: true,
                                                transcript: [controllerLine()]))
        XCTAssertFalse(model.shouldRunAmbientChatter, "chatter should stop once parked at the gate")
    }

    func testChatterRespectsTheSettingToggle() {
        let model = makeModel()
        model.applySnapshotForTesting(snapshot(atcState: .cruise, machine: .cruise,
                                                hasDeparted: true, transcript: [controllerLine()]))
        XCTAssertTrue(model.shouldRunAmbientChatter)
        model.settings.backgroundChatterEnabled = false
        XCTAssertFalse(model.shouldRunAmbientChatter, "disabling the setting stops the chatter")
    }

    /// An ATIS broadcast alone is not an ATC communication — it must not start the chatter.
    func testATISBroadcastDoesNotStartChatter() {
        let model = makeModel()
        let atis = ATCTransmission(sender: .system, facility: .clearance,
                                   displayText: "KIAH information Alpha.", isATIS: true)
        model.applySnapshotForTesting(snapshot(atcState: .connectedIdle, machine: .connectedIdle,
                                                hasDeparted: false, transcript: [atis]))
        XCTAssertFalse(model.shouldRunAmbientChatter,
                       "an ATIS broadcast is not a two-way ATC communication")
    }
}
