import XCTest
@testable import IFATCCompanion

/// Verifies the saved-flight library: how flights are named, that a slot is updated
/// rather than duplicated, that deleting releases the active binding, and that the
/// library survives being read back from disk.
@MainActor
final class SavedFlightStoreTests: XCTestCase {

    private var directory: URL!

    override func setUpWithError() throws {
        directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("SavedFlightStoreTests.\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func makeStore() -> SavedFlightStore {
        let suite = "SavedFlightStoreTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return SavedFlightStore(directory: directory, defaults: defaults)
    }

    private func snapshot(departure: String = "KIAH",
                          destination: String = "KORD",
                          atcState: ATCState = .cruise,
                          transcript: [ATCTransmission] = []) -> SessionSnapshot {
        SessionSnapshot(
            atcState: atcState,
            stateMachineCurrent: atcState,
            currentFacility: .center,
            phase: .cruise,
            assignedAltitude: 35000,
            hasDeparted: true,
            arrivalAnnounced: false,
            awaitingGateArrival: false,
            manualTuning: false,
            transcript: transcript,
            departure: departure,
            destination: destination,
            mockMode: false,
            savedAt: Date())
    }

    // MARK: - Naming

    /// Flights are named for the route they fly, and a repeat of the same route gets a
    /// numeric suffix rather than a second identical row.
    func testNamesFlightsByRouteWithSuffixesForRepeats() {
        let store = makeStore()
        XCTAssertEqual(store.save(snapshot()).name, "KIAH-KORD")
        XCTAssertEqual(store.save(snapshot()).name, "KIAH-KORD-1")
        XCTAssertEqual(store.save(snapshot()).name, "KIAH-KORD-2")
        XCTAssertEqual(store.save(snapshot(departure: "KSFO", destination: "KJFK")).name, "KSFO-KJFK")
    }

    /// A gap left by a deleted flight is reused, so the suffixes don't climb forever.
    func testReusesAFreedName() {
        let store = makeStore()
        store.save(snapshot())
        let second = store.save(snapshot())
        XCTAssertEqual(second.name, "KIAH-KORD-1")
        store.delete(id: second.id)
        XCTAssertEqual(store.save(snapshot()).name, "KIAH-KORD-1")
    }

    /// A plan with no endpoints still gets a usable name rather than an empty row.
    func testNamesAnEndpointlessFlight() {
        let store = makeStore()
        XCTAssertEqual(store.save(snapshot(departure: "", destination: "")).name, "Flight")
        XCTAssertEqual(store.save(snapshot(departure: "EGLL", destination: "")).name, "EGLL")
    }

    // MARK: - Slots

    /// Saving binds the new flight as the active one, so auto-save knows where to write.
    func testSavingBindsTheActiveFlight() {
        let store = makeStore()
        let flight = store.save(snapshot())
        XCTAssertEqual(store.activeFlightID, flight.id)
        XCTAssertEqual(store.activeFlight?.name, "KIAH-KORD")
    }

    /// Updating a slot keeps its identity and name — it must never fork into a second row.
    func testUpdateReplacesInPlace() {
        let store = makeStore()
        let flight = store.save(snapshot(atcState: .cruise))
        store.update(id: flight.id, snapshot: snapshot(atcState: .descent))

        XCTAssertEqual(store.flights.count, 1)
        XCTAssertEqual(store.flights.first?.id, flight.id)
        XCTAssertEqual(store.flights.first?.name, "KIAH-KORD")
        XCTAssertEqual(store.flights.first?.snapshot.atcState, .descent)
    }

    /// A deleted flight must not come back the next time the auto-save fires.
    func testUpdateIgnoresADeletedFlight() {
        let store = makeStore()
        let flight = store.save(snapshot())
        store.delete(id: flight.id)
        store.update(id: flight.id, snapshot: snapshot(atcState: .descent))
        XCTAssertTrue(store.flights.isEmpty)
    }

    /// Deleting the flight being flown releases the binding, so the session in progress
    /// stops auto-saving instead of writing into a slot that no longer exists.
    func testDeletingTheActiveFlightUnbindsIt() {
        let store = makeStore()
        let flight = store.save(snapshot())
        store.delete(id: flight.id)
        XCTAssertNil(store.activeFlightID)
    }

    // MARK: - Persistence

    /// The library is on disk, not just in memory: a second store over the same
    /// directory sees the same flights, newest first.
    func testLibrarySurvivesReload() {
        let store = makeStore()
        store.save(snapshot(departure: "KIAH", destination: "KORD"))
        store.save(snapshot(departure: "KSFO", destination: "KJFK"))

        let reopened = makeStore()
        XCTAssertEqual(reopened.flights.count, 2)
        XCTAssertEqual(reopened.flights.first?.name, "KSFO-KJFK", "newest first")
        XCTAssertEqual(reopened.flights.last?.name, "KIAH-KORD")
    }

    /// The active binding is remembered too, so a relaunch keeps auto-saving into the
    /// flight that was being flown.
    func testActiveBindingSurvivesReload() {
        let suite = "SavedFlightStoreTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)

        let store = SavedFlightStore(directory: directory, defaults: defaults)
        let flight = store.save(snapshot())

        let reopened = SavedFlightStore(directory: directory, defaults: defaults)
        XCTAssertEqual(reopened.activeFlightID, flight.id)
    }

    /// The whole session round-trips through JSON — the fields a saved flight adds are
    /// no use if they don't survive the encoder.
    func testWholeSessionRoundTripsThroughDisk() {
        let store = makeStore()
        var snap = snapshot(transcript: [ATCTransmission(sender: .atc, facility: .center,
                                                         displayText: "Descend and maintain one one thousand.")])
        var plan = FlightPlan()
        plan.departure = "KIAH"
        plan.destination = "KORD"
        plan.waypoints = [Waypoint(name: "DOOBI", latitude: 30.1, longitude: -95.2)]
        snap.flightPlan = plan
        snap.overrides = FlightOverrides(callsign: "UAL598", airline: "United", flightNumber: "598",
                                         departureGate: "C12", arrivalGate: "B44")
        snap.tunedFacility = .approach
        snap.awaitingReadback = true
        snap.pendingReadbackTx = ATCTransmission(sender: .atc, facility: .center,
                                                 displayText: "Turn left heading two seven zero.")
        snap.readbackPrompts = 2
        snap.arrivalGateLatitude = 41.9
        snap.arrivalGateLongitude = -87.9
        snap.diagnostics = DiagnosticsSnapshot(entries: [
            DiagnosticsStore.Entry(timestamp: Date(), category: .atc, message: "Cleared direct DOOBI.")
        ])
        store.save(snap)

        let restored = makeStore().flights.first?.snapshot
        XCTAssertEqual(restored?.flightPlan?.waypoints.first?.name, "DOOBI")
        XCTAssertEqual(restored?.overrides?.callsign, "UAL598")
        XCTAssertEqual(restored?.overrides?.arrivalGate, "B44")
        XCTAssertEqual(restored?.tunedFacility, .approach)
        XCTAssertEqual(restored?.awaitingReadback, true)
        XCTAssertEqual(restored?.pendingReadbackTx?.displayText, "Turn left heading two seven zero.")
        XCTAssertEqual(restored?.readbackPrompts, 2)
        XCTAssertEqual(restored?.arrivalGateCoordinate?.latitude ?? 0, 41.9, accuracy: 0.0001)
        XCTAssertEqual(restored?.diagnostics?.entries.first?.message, "Cleared direct DOOBI.")
        XCTAssertEqual(restored?.transcript.count, 1)
    }
}
