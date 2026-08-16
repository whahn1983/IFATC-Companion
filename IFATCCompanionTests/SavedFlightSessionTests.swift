import XCTest
@testable import IFATCCompanion

/// Verifies saving, loading and swapping whole flights: that a saved flight captures
/// the entire session, that loading one resets before it applies (so a flight at the
/// gate is never left wearing the previous flight's arrival state), and that the
/// auto-save writes into the loaded flight — and only when it is switched on.
@MainActor
final class SavedFlightSessionTests: XCTestCase {

    private var directory: URL!

    override func setUpWithError() throws {
        directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("SavedFlightSessionTests.\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        // The pilot's flight fields live in the shared defaults, and loading a flight
        // writes into them — put them back so other tests don't inherit this flight's
        // callsign and gates.
        FlightOverrides().apply(to: AppSettings())
        try? FileManager.default.removeItem(at: directory)
    }

    private func makeStore() -> SavedFlightStore {
        let suite = "SavedFlightSessionTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return SavedFlightStore(directory: directory, defaults: defaults)
    }

    private func makeLiveModel(store: SavedFlightStore) -> AppModel {
        let model = AppModel(savedFlights: store)
        model.settings.voiceEnabled = false
        model.settings.mockMode = false
        model.settings.autoSaveFlights = true
        // No host and auto-discover off, so the forced reconnect on a flight swap idles
        // instead of touching the network.
        model.settings.autoDiscover = false
        model.settings.host = ""
        model.settings.initialClimbAltitudeFt = 5000
        model.settings.traconCeilingFL = 180

        var plan = FlightPlan()
        plan.airline = "United"
        plan.flightNumber = "598"
        plan.departure = "KIAH"
        plan.destination = "KMSP"
        plan.cruiseAltitude = 28000
        model.flightPlan = plan
        return model
    }

    /// A session parked at the gate, as it would be saved before pushback.
    private func gateSnapshot() -> SessionSnapshot {
        var plan = FlightPlan()
        plan.departure = "KIAH"
        plan.destination = "KMSP"
        plan.cruiseAltitude = 28000
        plan.waypoints = [Waypoint(name: "DOOBI", latitude: 30.1, longitude: -95.2)]

        var snap = SessionSnapshot(
            atcState: .clearance,
            stateMachineCurrent: .clearance,
            currentFacility: .clearance,
            phase: .preflight,
            assignedAltitude: 5000,
            hasDeparted: false,
            arrivalAnnounced: false,
            awaitingGateArrival: false,
            manualTuning: false,
            transcript: [ATCTransmission(sender: .atc, facility: .clearance,
                                         displayText: "United 598, cleared to Minneapolis as filed.")],
            departure: "KIAH",
            destination: "KMSP",
            mockMode: false,
            savedAt: Date())
        snap.flightPlan = plan
        snap.overrides = FlightOverrides(callsign: "UAL598", airline: "United", flightNumber: "598",
                                         departure: "KIAH", destination: "KMSP",
                                         departureGate: "C12", arrivalGate: "B44")
        return snap
    }

    /// A session finished at the destination gate — blocked in, arrival announced.
    private func completedSnapshot() -> SessionSnapshot {
        var snap = gateSnapshot()
        snap.atcState = .parked
        snap.stateMachineCurrent = .parked
        snap.currentFacility = .ground
        snap.phase = .parked
        snap.hasDeparted = true
        snap.arrivalAnnounced = true
        return snap
    }

    /// A level fix at cruise, enough to drive the flow airborne.
    private func cruiseState() -> AircraftState {
        AircraftState(latitude: 33.0, longitude: -95.0, altitudeMSL: 35000,
                      groundSpeed: 450, heading: 10, verticalSpeed: 0, onGround: false)
    }

    // MARK: - Saving

    /// Saving captures the whole session — the plan, the pilot's own fields, the radio
    /// and the transcript — under a name taken from the route.
    func testSavingCapturesTheWholeSession() {
        let store = makeStore()
        let model = makeLiveModel(store: store)
        model.settings.callsign = "UAL598"
        model.settings.arrivalGate = "B44"
        model.applySnapshotForTesting(gateSnapshot())
        model.tuneTo(.ground)

        let saved = model.saveCurrentFlight()

        XCTAssertEqual(saved?.name, "KIAH-KMSP")
        XCTAssertEqual(store.flights.count, 1)
        XCTAssertEqual(store.activeFlightID, saved?.id, "saving binds the flight so auto-save knows where to write")
        let snapshot = store.flights.first?.snapshot
        XCTAssertEqual(snapshot?.overrides?.callsign, "UAL598")
        XCTAssertEqual(snapshot?.overrides?.arrivalGate, "B44")
        XCTAssertEqual(snapshot?.tunedFacility, .ground, "the frequency the pilot is actually on is part of the flight")
        XCTAssertEqual(snapshot?.flightPlan?.departure, "KIAH")
        XCTAssertFalse(snapshot?.transcript.isEmpty ?? true)
    }

    /// Tapping Save twice updates the one flight rather than leaving a duplicate behind.
    func testSavingTwiceUpdatesTheSameFlight() {
        let store = makeStore()
        let model = makeLiveModel(store: store)
        model.applySnapshotForTesting(gateSnapshot())

        let first = model.saveCurrentFlight()
        let second = model.saveCurrentFlight()

        XCTAssertEqual(store.flights.count, 1)
        XCTAssertEqual(first?.id, second?.id)
        XCTAssertEqual(store.flights.first?.name, "KIAH-KMSP")
    }

    /// Mock Mode is a scripted demo that always starts at the gate — there is nothing
    /// there worth keeping, and it must never land in the library.
    func testMockModeNeverSaves() {
        let store = makeStore()
        let model = makeLiveModel(store: store)
        model.settings.mockMode = true

        XCTAssertFalse(model.canSaveCurrentFlight)
        XCTAssertNil(model.saveCurrentFlight())
        XCTAssertTrue(store.flights.isEmpty)
    }

    // MARK: - Loading

    /// The load path must reset the session before applying the snapshot. Layering a
    /// gate flight onto an arrival would leave `hasDeparted` true — the pre-departure
    /// ground flow is then skipped forever, and the forward-only guard refuses to walk
    /// the state machine back.
    func testLoadingAGateFlightOverAnArrivalStartsCleanlyAtTheGate() {
        let store = makeStore()
        let model = makeLiveModel(store: store)
        let flight = store.save(gateSnapshot())

        // The live session is on approach into somewhere else entirely.
        var arrival = gateSnapshot()
        arrival.atcState = .approach
        arrival.stateMachineCurrent = .approach
        arrival.currentFacility = .approach
        arrival.phase = .approach
        arrival.hasDeparted = true
        model.applySnapshotForTesting(arrival)
        XCTAssertFalse(model.isPreDeparture)

        model.loadSavedFlight(flight)

        XCTAssertEqual(model.atcState, .clearance)
        XCTAssertEqual(model.currentFacility, .clearance)
        XCTAssertTrue(model.isPreDeparture, "loading a gate flight must put the ground flow back in play")
        XCTAssertEqual(model.transcript.count, 1)
        XCTAssertEqual(store.activeFlightID, flight.id)
    }

    /// Loading brings back the pilot's own entries and the saved route, not just the
    /// conversation.
    func testLoadingRestoresOverridesAndPlan() {
        let store = makeStore()
        let model = makeLiveModel(store: store)
        model.settings.callsign = "SWA1"
        model.settings.arrivalGate = ""
        let flight = store.save(gateSnapshot())

        model.loadSavedFlight(flight)

        XCTAssertEqual(model.settings.callsign, "UAL598")
        XCTAssertEqual(model.settings.arrivalGate, "B44")
        XCTAssertEqual(model.flightPlan.waypoints.first?.name, "DOOBI",
                       "the saved route must survive the load rather than be rebuilt from Settings")
    }

    /// A controller waiting on a read-back stays waiting after the flight is put away
    /// and picked up again.
    func testLoadingRestoresTheReadbackGate() {
        let store = makeStore()
        let model = makeLiveModel(store: store)
        var snap = gateSnapshot()
        snap.tunedFacility = .tower
        snap.awaitingReadback = true
        snap.pendingReadbackTx = ATCTransmission(sender: .atc, facility: .tower,
                                                 displayText: "United 598, line up and wait runway 15L.")
        let flight = store.save(snap)

        model.loadSavedFlight(flight)

        XCTAssertTrue(model.awaitingReadback, "the controller is still waiting on the pilot")
    }

    /// The Diagnostics log belongs to the flight: it is saved with it and comes back
    /// with it, rather than showing whatever session the pilot switched away from.
    func testDiagnosticsLogTravelsWithTheFlight() throws {
        let store = makeStore()
        let model = makeLiveModel(store: store)
        model.applySnapshotForTesting(gateSnapshot())
        model.diagnostics.log(.atc, "Cleared to Minneapolis as filed.")
        let saved = try XCTUnwrap(model.saveCurrentFlight())

        model.clearFlight()
        model.diagnostics.clear()
        model.diagnostics.log(.app, "A different session entirely.")

        model.loadSavedFlight(try XCTUnwrap(store.flight(id: saved.id)))

        XCTAssertTrue(model.diagnostics.entries.contains { $0.message.contains("Cleared to Minneapolis") },
                      "the saved flight's log comes back with it")
        XCTAssertFalse(model.diagnostics.entries.contains { $0.message.contains("A different session") },
                       "and replaces the log of the session it took over from")
    }

    /// Loading a saved flight is refused in Mock Mode, which owns its own scripted feed.
    func testLoadingIsRefusedInMockMode() {
        let store = makeStore()
        let model = makeLiveModel(store: store)
        let flight = store.save(gateSnapshot())
        model.settings.mockMode = true
        var inProgress = gateSnapshot()
        inProgress.atcState = .cruise
        model.applySnapshotForTesting(inProgress)

        model.loadSavedFlight(flight)

        XCTAssertEqual(model.atcState, .cruise, "the mock session is left alone")
    }

    // MARK: - Flight swaps force a fresh link

    /// Both ways of swapping flights re-establish the Infinite Flight link. The socket is
    /// bound to the flight that was live when it opened, so without this the app keeps
    /// showing the previous aircraft's position, plan and map — which is why pilots were
    /// having to hit Reconnect in Settings by hand after clearing or loading.
    func testSwappingFlightsForcesAFreshConnection() {
        let store = makeStore()
        let model = makeLiveModel(store: store)
        let flight = store.save(gateSnapshot())

        model.loadSavedFlight(flight)
        XCTAssertTrue(didReconnect(model), "loading a saved flight re-establishes the link")

        model.diagnostics.clear()
        model.clearFlight()
        XCTAssertTrue(didReconnect(model), "starting a new flight re-establishes the link")
    }

    /// Mock Mode owns its own scripted feed and has no link to re-establish.
    func testMockModeDoesNotReconnectOnClear() {
        let store = makeStore()
        let model = makeLiveModel(store: store)
        model.settings.mockMode = true
        model.diagnostics.clear()

        model.clearFlight()

        XCTAssertFalse(didReconnect(model))
    }

    private func didReconnect(_ model: AppModel) -> Bool {
        model.diagnostics.entries.contains { $0.message.contains("Reconnecting to Infinite Flight") }
    }

    // MARK: - Reconnect is not a load

    /// A reconnect resumes the conversation but must leave the pilot's Settings alone:
    /// those fields are already theirs, and may be newer than the snapshot.
    func testReconnectLeavesTheSettingsFieldsAlone() {
        let model = makeLiveModel(store: makeStore())
        model.settings.callsign = "SWA1"

        model.applySnapshotForTesting(gateSnapshot())   // saved under UAL598

        XCTAssertEqual(model.settings.callsign, "SWA1")
    }

    /// A reconnect may fill in a route the live plan doesn't have yet — the cold-relaunch
    /// case — but must never replace one already in memory, nor donate fixes from a
    /// snapshot for a different flight.
    func testReconnectSeedsAMissingRouteOnly() {
        let model = makeLiveModel(store: makeStore())   // KIAH → KMSP, no route yet
        model.applySnapshotForTesting(gateSnapshot())
        XCTAssertEqual(model.flightPlan.waypoints.first?.name, "DOOBI",
                       "a blank route is worth seeding from the snapshot")

        model.flightPlan.waypoints = [Waypoint(name: "LIVE1", latitude: 31, longitude: -95)]
        model.applySnapshotForTesting(gateSnapshot())
        XCTAssertEqual(model.flightPlan.waypoints.first?.name, "LIVE1",
                       "a route already in memory may be newer than the snapshot")

        model.flightPlan.waypoints = []
        var elsewhere = gateSnapshot()
        elsewhere.flightPlan?.destination = "KBOS"
        model.applySnapshotForTesting(elsewhere)
        XCTAssertTrue(model.flightPlan.waypoints.isEmpty,
                      "a snapshot for another flight never donates its fixes")
    }

    // MARK: - Auto-save

    /// With auto-save on, flying the loaded flight keeps its slot current — that is what
    /// makes switching away and back resume where you left off.
    func testAutoSaveKeepsTheLoadedFlightCurrent() {
        let store = makeStore()
        let model = makeLiveModel(store: store)
        model.settings.autoSaveFlights = true
        let flight = store.save(gateSnapshot())
        model.loadSavedFlight(flight)

        model.ingestStateForTesting(cruiseState())

        XCTAssertEqual(model.atcState, .cruise, "sanity: the session advanced")
        XCTAssertEqual(store.flight(id: flight.id)?.snapshot.atcState, .cruise,
                       "the saved flight follows the session it is bound to")
    }

    /// With auto-save off the slot is a fixed point in time: flying on changes nothing
    /// until the pilot taps Save.
    func testAutoSaveOffLeavesTheSlotAlone() {
        let store = makeStore()
        let model = makeLiveModel(store: store)
        let flight = store.save(gateSnapshot())
        model.loadSavedFlight(flight)
        model.settings.autoSaveFlights = false

        model.ingestStateForTesting(cruiseState())

        XCTAssertEqual(model.atcState, .cruise, "sanity: the session advanced")
        XCTAssertEqual(store.flight(id: flight.id)?.snapshot.atcState, .clearance,
                       "the saved flight stays as it was saved")
    }

    /// Starting a new flight must release the binding, or the empty session would
    /// auto-save straight over the flight that was just put away.
    func testClearingTheFlightUnbindsTheSavedSlot() {
        let store = makeStore()
        let model = makeLiveModel(store: store)
        let flight = store.save(gateSnapshot())
        model.loadSavedFlight(flight)

        model.clearFlight()
        model.ingestStateForTesting(cruiseState())

        XCTAssertNil(store.activeFlightID)
        XCTAssertNotNil(store.flight(id: flight.id),
                        "a flight still under way is kept — clearing is how you switch to another one")
        XCTAssertEqual(store.flight(id: flight.id)?.snapshot.atcState, .clearance,
                       "the saved flight is untouched by the new one")
    }

    /// A flight that has blocked in at the destination gate is over. Clearing retires it
    /// from the list rather than leaving a finished flight there to be picked up.
    func testClearingAFinishedFlightRetiresItFromTheList() throws {
        let store = makeStore()
        let model = makeLiveModel(store: store)
        // Saved while it was still being flown, then flown to the gate.
        model.applySnapshotForTesting(gateSnapshot())
        let saved = try XCTUnwrap(model.saveCurrentFlight())
        model.applySnapshotForTesting(completedSnapshot())
        XCTAssertTrue(model.flightIsComplete, "sanity: parked with the arrival announced")
        XCTAssertEqual(model.savedFlightRetiredByClearing, saved.name)

        model.clearFlight()

        XCTAssertNil(store.flight(id: saved.id), "there is nothing to come back to")
        XCTAssertTrue(store.flights.isEmpty)
        XCTAssertNil(store.activeFlightID)
    }

    /// A finished flight cannot be saved: there is nothing to come back to, and clearing
    /// retires it anyway, so saving one would only set up a flight the next tap deletes.
    func testAFinishedFlightCannotBeSaved() {
        let store = makeStore()
        let model = makeLiveModel(store: store)
        model.applySnapshotForTesting(completedSnapshot())

        XCTAssertFalse(model.canSaveCurrentFlight, "the Save button is disabled")
        XCTAssertNil(model.saveCurrentFlight(), "and the model refuses it, not just the button")
        XCTAssertTrue(store.flights.isEmpty)
        XCTAssertFalse(model.hasUnsavedFlight, "nothing to warn about losing — it is done")
    }

    /// Only the flight being flown is retired — a finished session that was never saved
    /// must not take someone else's saved flight down with it.
    func testClearingAFinishedFlightLeavesOtherSavedFlightsAlone() {
        let store = makeStore()
        let model = makeLiveModel(store: store)
        let other = store.save(gateSnapshot())
        store.setActive(nil)
        model.applySnapshotForTesting(completedSnapshot())
        XCTAssertNil(model.savedFlightRetiredByClearing, "nothing bound, nothing to retire")

        model.clearFlight()

        XCTAssertNotNil(store.flight(id: other.id))
    }

    // MARK: - Warnings

    /// The endpoint check warns only when the saved flight really is a different route
    /// from the one Infinite Flight is reporting.
    func testEndpointMismatchWarnsOnlyOnADifferentRoute() {
        let store = makeStore()
        let model = makeLiveModel(store: store)       // live plan: KIAH → KMSP
        let sameRoute = store.save(gateSnapshot())

        var elsewhere = gateSnapshot()
        elsewhere.departure = "EGLL"
        elsewhere.destination = "KBOS"
        let differentRoute = store.save(elsewhere)

        XCTAssertNil(model.endpointMismatch(with: sameRoute))
        let warning = model.endpointMismatch(with: differentRoute)
        XCTAssertNotNil(warning)
        XCTAssertTrue(warning?.contains("EGLL-KBOS") ?? false)
        XCTAssertTrue(warning?.contains("KIAH-KMSP") ?? false)
    }

    /// A flight already in the library (and kept current by auto-save) has nothing to
    /// lose; one that was never saved does, as soon as it has any history.
    func testUnsavedFlightDetection() {
        let store = makeStore()
        let model = makeLiveModel(store: store)
        XCTAssertFalse(model.hasUnsavedFlight, "an empty session has nothing worth warning about")

        model.applySnapshotForTesting(gateSnapshot())
        XCTAssertTrue(model.hasUnsavedFlight)

        model.saveCurrentFlight()
        XCTAssertFalse(model.hasUnsavedFlight)

        model.settings.autoSaveFlights = false
        XCTAssertTrue(model.hasUnsavedFlight, "with auto-save off, progress since the save is at risk")
    }
}
