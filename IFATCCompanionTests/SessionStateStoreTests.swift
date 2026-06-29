import XCTest
@testable import IFATCCompanion

/// Verifies the session-state persistence used to resume an in-progress flight
/// after a disconnect/reconnect, instead of re-deriving the conversation (which
/// would jump a parked aircraft to cruise).
@MainActor
final class SessionStateStoreTests: XCTestCase {

    /// An isolated defaults suite so tests never touch the user's real session.
    private func makeStore() -> (SessionStateStore, UserDefaults) {
        let suite = "SessionStateStoreTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return (SessionStateStore(defaults: defaults), defaults)
    }

    private func snapshot(atcState: ATCState = .climb,
                          arrivalAnnounced: Bool = false,
                          mockMode: Bool = false,
                          savedAt: Date = Date(),
                          transcript: [ATCTransmission] = []) -> SessionSnapshot {
        SessionSnapshot(
            atcState: atcState,
            stateMachineCurrent: atcState,
            currentFacility: .center,
            phase: .climb,
            assignedAltitude: 28000,
            hasDeparted: true,
            arrivalAnnounced: arrivalAnnounced,
            awaitingGateArrival: false,
            manualTuning: false,
            transcript: transcript,
            departure: "KIAH",
            destination: "KMSP",
            mockMode: mockMode,
            savedAt: savedAt)
    }

    func testRoundTripsThroughDefaults() {
        let (store, _) = makeStore()
        let tx = ATCTransmission(sender: .atc, facility: .center,
                                 displayText: "Climb and maintain flight level two eight zero.")
        store.save(snapshot(transcript: [tx]))

        let loaded = store.load()
        XCTAssertEqual(loaded?.atcState, .climb)
        XCTAssertEqual(loaded?.assignedAltitude, 28000)
        XCTAssertEqual(loaded?.transcript.count, 1)
        XCTAssertEqual(loaded?.transcript.first?.displayText,
                       "Climb and maintain flight level two eight zero.")
    }

    func testResumableReturnsRecentInProgressSession() {
        let (store, _) = makeStore()
        store.save(snapshot(atcState: .cruise))
        XCTAssertNotNil(store.loadResumable(), "a recent in-progress session should resume")
    }

    func testResumableRejectsStaleSession() {
        let (store, _) = makeStore()
        store.maxAge = 3600
        store.save(snapshot(savedAt: Date(timeIntervalSinceNow: -7200)))
        XCTAssertNil(store.loadResumable(), "a session older than maxAge must not resume")
    }

    func testResumableRejectsCompletedFlight() {
        let (store, _) = makeStore()
        store.save(snapshot(atcState: .parked, arrivalAnnounced: true))
        XCTAssertNil(store.loadResumable(),
                     "a finished gate-to-gate flight has nothing to resume")
    }

    func testClearRemovesSnapshot() {
        let (store, _) = makeStore()
        store.save(snapshot())
        store.clear()
        XCTAssertNil(store.load())
        XCTAssertNil(store.loadResumable())
    }
}
