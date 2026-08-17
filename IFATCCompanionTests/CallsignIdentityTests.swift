import XCTest
@testable import IFATCCompanion

/// Who the flight is — the callsign the controller actually uses.
///
/// Every call is built from the plan's *airline/flight-number pair*; the raw callsign is
/// only the fallback used when that pair is empty. So the pair has to be kept in step with
/// the pilot's own fields, or the app shows one callsign in the Flight tab while ATC says
/// another.
///
/// Regression: loading a saved flight restored the pilot's callsign into Settings but left
/// whatever airline/flight number the plan happened to carry — the demo's United 598, or
/// the identity of the flight that was live a moment earlier. The Flight tab showed the
/// right callsign, ATC used the wrong one, and the only way out was to edit the callsign
/// field (delete a digit, type it back) to force it through by hand.
@MainActor
final class CallsignIdentityTests: XCTestCase {

    private var directory: URL!

    override func setUpWithError() throws {
        directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("CallsignIdentityTests.\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        // The pilot's flight fields live in the shared defaults, and loading a flight
        // writes into them — put them back so other tests don't inherit this flight's.
        FlightOverrides().apply(to: AppSettings())
        try? FileManager.default.removeItem(at: directory)
    }

    private func makeStore() -> SavedFlightStore {
        let suite = "CallsignIdentityTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return SavedFlightStore(directory: directory, defaults: defaults)
    }

    /// A live model flying as United 598 — the identity a loaded flight has to displace.
    /// No host and auto-discover off, so the forced reconnect on a load idles instead of
    /// touching the network.
    private func makeLiveModel(store: SavedFlightStore? = nil) -> AppModel {
        let model = AppModel(savedFlights: store ?? makeStore())
        model.settings.voiceEnabled = false
        model.settings.mockMode = false
        model.settings.autoDiscover = false
        model.settings.host = ""
        FlightOverrides().apply(to: model.settings)

        var plan = FlightPlan()
        plan.airline = "United"
        plan.flightNumber = "598"
        plan.departure = "KIAH"
        plan.destination = "KMSP"
        model.flightPlan = plan
        return model
    }

    /// A flight saved under a callsign of its own.
    private func snapshot(overrides: FlightOverrides, plan: FlightPlan? = nil) -> SessionSnapshot {
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
            transcript: [],
            departure: "KIAH",
            destination: "KMSP",
            mockMode: false,
            savedAt: Date())
        snap.flightPlan = plan
        snap.overrides = overrides
        return snap
    }

    private func plan(airline: String, flightNumber: String) -> FlightPlan {
        var plan = FlightPlan()
        plan.airline = airline
        plan.flightNumber = flightNumber
        plan.departure = "KIAH"
        plan.destination = "KMSP"
        return plan
    }

    // MARK: - Loading a saved flight

    /// The callsign a flight was saved under is the callsign it is flown under when it
    /// comes back — including in what the controller says, not just what the field shows.
    func testLoadingAFlightUsesTheCallsignItWasSavedWith() {
        let store = makeStore()
        let model = makeLiveModel(store: store)
        let flight = store.save(snapshot(overrides: FlightOverrides(callsign: "DAL221"),
                                         plan: plan(airline: "United", flightNumber: "598")))

        model.loadSavedFlight(flight)

        XCTAssertEqual(model.settings.callsign, "DAL221")
        XCTAssertEqual(model.flightPlan.callsign, "DAL221")
        XCTAssertEqual(model.flightPlan.airline, "Delta",
                       "the saved plan's stale airline must not outlive the callsign it was loaded with")
        XCTAssertEqual(model.flightPlan.flightNumber, "221")
        XCTAssertEqual(model.contextForTesting(.clearance).callsign.display, "Delta 221",
                       "the controller calls the flight what the Flight tab says it is")
    }

    /// An older saved flight carries no plan at all. Its callsign still has to displace the
    /// identity of the flight that was live a moment before, rather than being shown in the
    /// Flight tab while ATC goes on calling the previous flight's number.
    func testLoadingAFlightWithoutASavedPlanStillTakesItsCallsign() {
        let store = makeStore()
        let model = makeLiveModel(store: store)
        let flight = store.save(snapshot(overrides: FlightOverrides(callsign: "DAL221"), plan: nil))

        model.loadSavedFlight(flight)

        XCTAssertEqual(model.flightPlan.airline, "Delta")
        XCTAssertEqual(model.flightPlan.flightNumber, "221")
    }

    /// A pilot who filled in Airline / Flight # instead of a callsign gets those back.
    func testLoadingAFlightRestoresAPinnedAirlineAndFlightNumber() {
        let store = makeStore()
        let model = makeLiveModel(store: store)
        let flight = store.save(snapshot(overrides: FlightOverrides(airline: "Speedbird", flightNumber: "12"),
                                         plan: plan(airline: "United", flightNumber: "598")))

        model.loadSavedFlight(flight)

        XCTAssertEqual(model.contextForTesting(.clearance).callsign.display, "Speedbird 12")
    }

    /// A callsign naming no airline — a tail number — is spelled out. The stale airline it
    /// replaces must go, or the controller would keep flying the previous flight's number
    /// while the field reads N123AB.
    func testLoadingAFlightUnderATailNumberDropsAStaleAirline() {
        let store = makeStore()
        let model = makeLiveModel(store: store)
        let flight = store.save(snapshot(overrides: FlightOverrides(callsign: "N123AB"),
                                         plan: plan(airline: "United", flightNumber: "598")))

        model.loadSavedFlight(flight)

        XCTAssertTrue(model.flightPlan.airline.isEmpty)
        XCTAssertTrue(model.flightPlan.flightNumber.isEmpty)
        XCTAssertEqual(model.contextForTesting(.clearance).callsign.display, "N123AB")
    }

    /// A flight saved with nothing entered leaves the plan's own identity alone — blank
    /// fields are not an instruction to forget who the flight is.
    func testLoadingAFlightWithNoEnteredCallsignKeepsTheSavedPlansIdentity() {
        let store = makeStore()
        let model = makeLiveModel(store: store)
        let flight = store.save(snapshot(overrides: FlightOverrides(),
                                         plan: plan(airline: "Delta", flightNumber: "221")))

        model.loadSavedFlight(flight)

        XCTAssertEqual(model.contextForTesting(.clearance).callsign.display, "Delta 221")
    }

    // MARK: - Mock Mode's stand-in identity

    /// Mock Mode flies as United 598 only when the pilot hasn't said who they are.
    func testMockModeFliesAsUnitedWhenNoCallsignIsEntered() {
        let model = makeLiveModel()
        model.settings.mockMode = true

        model.syncFlightPlanFromSettings()

        XCTAssertEqual(model.flightPlan.airline, "United")
        XCTAssertEqual(model.flightPlan.flightNumber, "598")
    }

    /// …and an entered callsign beats it, in the demo as anywhere else.
    func testEnteredCallsignWinsOverTheMockDefault() {
        let model = makeLiveModel()
        model.settings.mockMode = true
        model.settings.callsign = "DAL221"

        model.syncFlightPlanFromSettings()

        XCTAssertEqual(model.flightPlan.airline, "Delta")
        XCTAssertEqual(model.flightPlan.flightNumber, "221")
    }

    /// Leaving the demo leaves its identity behind with it. The plan the live flight starts
    /// from is the pilot's, not United 598 — which used to ride along and be used for every
    /// call until the callsign field was re-entered by hand.
    func testTheDemoIdentityDoesNotFollowTheFlightIntoLiveMode() {
        let model = makeLiveModel()
        model.settings.mockMode = true
        model.syncFlightPlanFromSettings()
        XCTAssertEqual(model.flightPlan.airline, "United", "sanity: the demo is flying as United 598")

        model.settings.callsign = "DAL221"
        model.applyEntitlement(hasLiveAccess: true)

        XCTAssertFalse(model.settings.mockMode, "sanity: the app switched to Live Mode")
        XCTAssertEqual(model.flightPlan.airline, "Delta")
        XCTAssertEqual(model.flightPlan.flightNumber, "221")
    }

    // MARK: - Editing the field

    /// The manual path the pilot was falling back on still works: entering a callsign
    /// resolves the airline and flight number from it.
    func testEnteringACallsignResolvesTheAirlineAndFlightNumber() {
        let model = makeLiveModel()
        model.settings.callsign = "DAL221"

        model.applyManualCallsign()

        XCTAssertEqual(model.flightPlan.airline, "Delta")
        XCTAssertEqual(model.flightPlan.flightNumber, "221")
    }

    /// Clearing the field clears only the callsign: an airline/flight number that came from
    /// somewhere else stands, so blurring an empty field never wipes the flight's identity.
    func testClearingTheCallsignLeavesTheAirlineAlone() {
        let model = makeLiveModel()
        model.settings.callsign = ""

        model.applyManualCallsign()

        XCTAssertTrue(model.flightPlan.callsign.isEmpty)
        XCTAssertEqual(model.flightPlan.airline, "United")
        XCTAssertEqual(model.flightPlan.flightNumber, "598")
    }
}
