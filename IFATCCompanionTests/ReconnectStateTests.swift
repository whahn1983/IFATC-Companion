import XCTest
@testable import IFATCCompanion

/// Verifies the disconnect/reconnect behavior: an empty telemetry snapshot (which
/// Infinite Flight returns during the reconnect handshake) must not drive the
/// conversation, and a restored session resumes where the flight left off instead
/// of defaulting to cruise.
@MainActor
final class ReconnectStateTests: XCTestCase {

    private func makeLiveModel() -> AppModel {
        let model = AppModel()
        model.settings.voiceEnabled = false
        model.settings.mockMode = false
        model.settings.initialClimbAltitudeFt = 5000
        model.settings.traconCeilingFL = 180
        model.unicom.mode = .off

        var plan = FlightPlan()
        plan.airline = "United"
        plan.flightNumber = "598"
        plan.departure = "KIAH"
        plan.destination = "KMSP"
        plan.cruiseAltitude = 28000
        plan.waypoints = model.mock.route.waypoints
        model.flightPlan = plan
        return model
    }

    private func contains(_ model: AppModel, _ needle: String) -> Bool {
        model.transcript.contains { $0.displayText.contains(needle) }
    }

    // MARK: - The bug: empty telemetry must not jump to cruise

    /// An empty `AircraftState` (no position/altitude) — as received during the
    /// reconnect handshake — must not be treated as airborne. Previously the phase
    /// detector defaulted to "climb", which jumped the parked aircraft to cruise.
    func testEmptyTelemetryDoesNotAdvanceTheFlow() {
        let model = makeLiveModel()

        model.ingestStateForTesting(.empty)

        XCTAssertEqual(model.phase, .preflight, "empty telemetry must not change the phase")
        XCTAssertFalse(model.hasDeparted, "empty telemetry must not mark the flight departed")
        XCTAssertTrue(model.transcript.isEmpty, "empty telemetry must not generate any calls")
        XCTAssertFalse(contains(model, "Climb and maintain"),
                       "a parked aircraft must not be issued a climb on reconnect")
        XCTAssertEqual(model.atcState, .connectedIdle)
    }

    /// A parked aircraft (real telemetry, on ground, brakes set) followed by an
    /// empty handshake state stays put — it does not flip to cruise.
    func testParkedThenEmptyStaysParked() {
        let model = makeLiveModel()
        let parked = model.mock.state(for: .preflight)   // on ground, valid position

        model.ingestStateForTesting(parked)
        let phaseAfterParked = model.phase
        model.ingestStateForTesting(.empty)              // reconnect-handshake blip

        XCTAssertEqual(model.phase, phaseAfterParked)
        XCTAssertFalse(model.hasDeparted)
        XCTAssertFalse(contains(model, "Climb and maintain"))
    }

    // MARK: - Restore resumes where the flight left off

    /// Capturing a snapshot and re-applying it on a fresh model (as a reconnect
    /// does) resumes the same ATC state and transcript.
    func testSnapshotRestoreResumesWhereLeftOff() {
        // A session mid-climb with Center, an assigned altitude, and some transcript.
        let climbCall = ATCTransmission(sender: .atc, facility: .center,
                                        displayText: "United 598, climb and maintain flight level two eight zero.")
        let snapshot = SessionSnapshot(
            atcState: .climb,
            stateMachineCurrent: .climb,
            currentFacility: .center,
            phase: .climb,
            assignedAltitude: 28000,
            hasDeparted: true,
            arrivalAnnounced: false,
            awaitingGateArrival: false,
            manualTuning: false,
            transcript: [climbCall],
            departure: "KIAH",
            destination: "KMSP",
            mockMode: false,
            savedAt: Date())

        let model = makeLiveModel()
        model.applySnapshotForTesting(snapshot)

        XCTAssertEqual(model.atcState, .climb)
        XCTAssertEqual(model.currentFacility, .center)
        XCTAssertEqual(model.assignedAltitude, 28000)
        XCTAssertTrue(model.hasDeparted)
        XCTAssertEqual(model.transcript.count, 1)
        XCTAssertEqual(model.latestTransmission?.displayText, climbCall.displayText)

        // A reconnect-handshake blip after restore must not regress the state.
        model.ingestStateForTesting(.empty)
        XCTAssertEqual(model.atcState, .climb, "restored state must survive an empty handshake tick")
        XCTAssertEqual(model.transcript.count, 1)
    }

    /// The snapshot taken from a running session reflects the live state, so a
    /// reconnect has something accurate to resume from.
    func testSnapshotCapturesLiveState() {
        let model = makeLiveModel()
        model.requestClearance()                 // pilot-driven ground call
        let snapshot = model.snapshotForTesting()

        XCTAssertEqual(snapshot.atcState, .clearance)
        XCTAssertFalse(snapshot.mockMode)
        XCTAssertFalse(snapshot.transcript.isEmpty)
        XCTAssertTrue(snapshot.transcript.contains { $0.displayText.contains("cleared to KMSP") })
    }
}
