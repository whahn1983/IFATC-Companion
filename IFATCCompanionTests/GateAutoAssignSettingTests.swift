import XCTest
import CoreLocation
@testable import IFATCCompanion

/// The automatic gate assignment as the pilot meets it: a Settings toggle that is **off** on a
/// fresh install, fills only a gate field that was left blank, and hands back what it filled in
/// when it is switched off again.
@MainActor
final class GateAutoAssignSettingTests: XCTestCase {

    private let iah = CLLocationCoordinate2D(latitude: 29.9844, longitude: -95.3414)

    /// A stand-rich surface for a field, so an assignment has something real to pick from.
    private func standsSurface(icao: String, reference: CLLocationCoordinate2D) -> AirportSurfaceModel {
        var model = MockAirportSurface.model(icao: icao, reference: reference,
                                             primaryRunwayIdent: "15L", gate: "A1")
        model.parkingPositions = (1...8).map { index in
            SurfaceParking(osmID: "node/\(icao)-C\(index)",
                           tags: ["aeroway": "gate", "ref": "C\(index)",
                                  "operator": "United Airlines", "aircraft:type": "B738"],
                           kind: .gate, name: "C\(index)",
                           coordinate: GeoCoordinate(latitude: reference.latitude + 0.001,
                                                     longitude: reference.longitude + 0.001))
        }
        return model
    }

    /// A field whose stands are spread out, so a test can park the aircraft on a *specific* one.
    /// `C<n>` sits n × ~0.0005° (~55 m) north of the reference, far enough apart that the 80 m
    /// parked radius reaches only its own stand and its immediate neighbours.
    private func spreadStandsSurface(icao: String, reference: CLLocationCoordinate2D) -> AirportSurfaceModel {
        var model = MockAirportSurface.model(icao: icao, reference: reference,
                                             primaryRunwayIdent: "15L", gate: "A1")
        model.parkingPositions = (1...8).map { index in
            SurfaceParking(osmID: "node/\(icao)-C\(index)",
                           tags: ["aeroway": "gate", "ref": "C\(index)",
                                  "operator": "United Airlines", "aircraft:type": "B738"],
                           kind: .gate, name: "C\(index)",
                           coordinate: standCoordinate(reference: reference, index: index))
        }
        return model
    }

    private func standCoordinate(reference: CLLocationCoordinate2D, index: Int) -> GeoCoordinate {
        GeoCoordinate(latitude: reference.latitude + Double(index) * 0.0005,
                      longitude: reference.longitude)
    }

    /// Telemetry for an aircraft sitting still on the ground at a coordinate.
    private func parkedState(at coordinate: GeoCoordinate) -> AircraftState {
        var state = AircraftState.empty
        state.latitude = coordinate.latitude
        state.longitude = coordinate.longitude
        state.altitudeMSL = 100
        state.altitudeAGL = 0
        state.groundSpeed = 0
        state.onGround = true
        state.aircraftName = "Boeing 737-800"
        state.lastUpdate = Date()
        return state
    }

    private func makeModel() -> AppModel {
        let model = AppModel()
        model.settings.voiceEnabled = false
        model.settings.mockMode = false
        model.settings.callsign = "UAL598"
        model.settings.departureGate = ""
        model.settings.arrivalGate = ""
        model.settings.autoAssignedDepartureGate = ""
        model.settings.autoAssignedArrivalGate = ""
        return model
    }

    private func gate(_ model: AppModel, _ role: GateRole) -> String {
        role == .departure ? model.settings.departureGate : model.settings.arrivalGate
    }

    /// Wait for the assignment's background surface read to land. It is a fetch-shaped
    /// operation even when the surface is already in hand, so it completes on a later turn.
    private func waitForAssignment(_ model: AppModel, role: GateRole) async -> String {
        for _ in 0..<200 {
            if !gate(model, role).isEmpty { break }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        return gate(model, role)
    }

    /// Wait for a gate field to reach an expected value — the assignment *changing* one gate
    /// for a better one, which the non-empty wait above can't see.
    private func waitForGate(_ model: AppModel, role: GateRole, toEqual expected: String) async -> String {
        for _ in 0..<200 {
            if gate(model, role) == expected { break }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        return gate(model, role)
    }

    /// Give any pending assignment time to run, for the cases that assert nothing happens.
    private func settle() async {
        try? await Task.sleep(nanoseconds: 300_000_000)
    }

    func testTheFeatureIsOffOnAFreshInstallAndPersistsWhenSwitchedOn() {
        let defaults = UserDefaults(suiteName: "gate-auto-assign-tests")!
        defaults.removePersistentDomain(forName: "gate-auto-assign-tests")
        let settings = AppSettings(defaults: defaults)
        XCTAssertFalse(settings.autoAssignGates, "automatic gate assignment is off on a fresh install")

        settings.autoAssignGates = true
        let relaunched = AppSettings(defaults: defaults)
        XCTAssertTrue(relaunched.autoAssignGates, "the choice survives the next launch")
        defaults.removePersistentDomain(forName: "gate-auto-assign-tests")
    }

    func testABlankGateIsFilledFromTheAirportsStandData() async {
        let model = makeModel()
        model.airportSurface.injectSimulatedSurfaceForTesting(standsSurface(icao: "KIAH", reference: iah),
                                                             icao: "KIAH")
        model.settings.autoAssignGates = true
        model.settings.departure = "KIAH"
        model.syncFlightPlanFromSettings()

        let assigned = await waitForAssignment(model, role: .departure)
        XCTAssertTrue(assigned.hasPrefix("C"),
                      "a real stand from the extract is assigned, got \"\(assigned)\"")
        XCTAssertEqual(model.flightPlan.departureGate, assigned, "the plan flies the assigned gate")
        XCTAssertEqual(model.settings.autoAssignedDepartureGate, "KIAH:\(assigned)",
                       "the assignment is stamped as the app's own")
        XCTAssertTrue(model.settings.arrivalGate.isEmpty,
                      "no destination is filed, so no arrival gate is assigned")
    }

    func testNothingIsAssignedWhileTheFeatureIsOff() async {
        let model = makeModel()
        model.airportSurface.injectSimulatedSurfaceForTesting(standsSurface(icao: "KIAH", reference: iah),
                                                             icao: "KIAH")
        model.settings.departure = "KIAH"
        model.syncFlightPlanFromSettings()

        await settle()
        XCTAssertTrue(model.settings.departureGate.isEmpty,
                      "the gate field is left alone until the pilot opts in")
        XCTAssertTrue(model.settings.autoAssignedDepartureGate.isEmpty)
    }

    func testAGateThePilotTypedIsNeverOverwritten() async {
        let model = makeModel()
        model.airportSurface.injectSimulatedSurfaceForTesting(standsSurface(icao: "KIAH", reference: iah),
                                                             icao: "KIAH")
        model.settings.autoAssignGates = true
        model.settings.departureGate = "E7"
        model.settings.departure = "KIAH"
        model.syncFlightPlanFromSettings()

        await settle()
        XCTAssertEqual(model.settings.departureGate, "E7", "the pilot's gate stands")
        XCTAssertTrue(model.settings.autoAssignedDepartureGate.isEmpty,
                      "and nothing is stamped, so it stays the pilot's at the next airport too")
    }

    func testAGateAlreadyOnTheLoadedPlanIsNotReplaced() {
        let model = makeModel()
        model.settings.autoAssignGates = true
        model.settings.departure = "KIAH"
        model.syncFlightPlanFromSettings()
        // A saved flight carries its gates in the plan rather than in the editable field.
        model.flightPlan.arrivalGate = "B44"
        XCTAssertFalse(model.mayAutoAssignGate(role: .arrival, icao: "KMSP"),
                       "a reloaded flight keeps the gate it was flying")
    }

    func testSwitchingTheFeatureOffGivesBackOnlyWhatItFilledIn() async {
        let model = makeModel()
        model.airportSurface.injectSimulatedSurfaceForTesting(standsSurface(icao: "KIAH", reference: iah),
                                                             icao: "KIAH")
        model.settings.autoAssignGates = true
        model.settings.departure = "KIAH"
        model.syncFlightPlanFromSettings()
        let assigned = await waitForAssignment(model, role: .departure)
        XCTAssertFalse(assigned.isEmpty, "the departure gate is assigned first")

        // The pilot's own arrival gate, alongside the automatic departure gate.
        model.settings.arrivalGate = "B44"
        model.applyManualGates()

        model.settings.autoAssignGates = false
        model.applyAutoGateSettingChange()
        XCTAssertTrue(model.settings.departureGate.isEmpty, "the automatic gate is withdrawn")
        XCTAssertTrue(model.settings.autoAssignedDepartureGate.isEmpty, "and so is its marker")
        XCTAssertEqual(model.settings.arrivalGate, "B44", "the pilot's gate is left alone")
        XCTAssertEqual(model.flightPlan.arrivalGate, "B44")
    }

    func testTheDepartureGateIsLeftAloneOnceTheTaxiHasBegun() {
        let model = makeModel()
        model.settings.autoAssignGates = true
        XCTAssertTrue(model.mayAutoAssignGate(role: .departure, icao: "KIAH"),
                      "a blank field before pushback is the app's to fill")

        model.airportSurface.beginMockTaxiForTesting(kind: .departure, reference: iah,
                                                     runway: "36", gate: "A1")
        XCTAssertFalse(model.mayAutoAssignGate(role: .departure, icao: "KIAH"),
                       "moving the departure gate mid-taxi would re-route the pilot")
        XCTAssertTrue(model.mayAutoAssignGate(role: .arrival, icao: "KMSP"),
                      "the arrival gate is still open — it is hours away")
        model.airportSurface.hideTaxiMap()
    }

    func testTheDepartureGateIsReadOffTheStandTheAircraftIsParkedOn() async {
        let model = makeModel()
        model.airportSurface.injectSimulatedSurfaceForTesting(spreadStandsSurface(icao: "KIAH", reference: iah),
                                                             icao: "KIAH")
        model.settings.autoAssignGates = true
        // Parked on C5 before the plan is loaded, so the very first assignment can read it.
        model.aircraftState = parkedState(at: standCoordinate(reference: iah, index: 5))
        model.settings.departure = "KIAH"
        model.syncFlightPlanFromSettings()

        XCTAssertEqual(await waitForGate(model, role: .departure, toEqual: "C5"), "C5",
                       "the stand the aircraft is sitting on is the gate")
        XCTAssertEqual(model.settings.autoAssignedDepartureGate, "KIAH:C5:P",
                       "and the marker records that it was read, not chosen")
    }

    func testAGateChosenBeforeTelemetryIsUpgradedOnceTheAircraftReportsParked() async {
        let model = makeModel()
        model.airportSurface.injectSimulatedSurfaceForTesting(spreadStandsSurface(icao: "KIAH", reference: iah),
                                                             icao: "KIAH")
        model.settings.autoAssignGates = true
        model.settings.departure = "KIAH"
        // No telemetry yet — the usual case at flight load — so the first gate is a guess.
        model.syncFlightPlanFromSettings()
        let chosen = await waitForAssignment(model, role: .departure)
        XCTAssertFalse(chosen.isEmpty, "a gate is filled in straight away rather than waiting")
        XCTAssertEqual(model.settings.autoAssignedDepartureGate, "KIAH:\(chosen)",
                       "marked as chosen, so it can still be improved on")

        // The first fix arrives with the aircraft parked on C5. Fed through the real telemetry
        // path, so this also covers the transition hook that re-runs the assignment.
        model.ingestStateForTesting(parkedState(at: standCoordinate(reference: iah, index: 5)))

        XCTAssertEqual(await waitForGate(model, role: .departure, toEqual: "C5"), "C5",
                       "the guess is replaced by the stand the aircraft is actually on")
        XCTAssertEqual(model.settings.autoAssignedDepartureGate, "KIAH:C5:P")
        XCTAssertEqual(model.flightPlan.departureGate, "C5", "and the plan follows")
    }

    func testAPilotsGateIsNotUpgradedByThePosition() async {
        let model = makeModel()
        model.airportSurface.injectSimulatedSurfaceForTesting(spreadStandsSurface(icao: "KIAH", reference: iah),
                                                             icao: "KIAH")
        model.settings.autoAssignGates = true
        model.settings.departureGate = "E7"
        model.aircraftState = parkedState(at: standCoordinate(reference: iah, index: 5))
        model.settings.departure = "KIAH"
        model.syncFlightPlanFromSettings()

        await settle()
        XCTAssertEqual(model.settings.departureGate, "E7",
                       "being parked elsewhere doesn't override what the pilot typed")
        XCTAssertTrue(model.settings.autoAssignedDepartureGate.isEmpty)
    }

    func testAPositionDerivedGateIsNotRePickedWhenTheAircraftMoves() async {
        let model = makeModel()
        model.airportSurface.injectSimulatedSurfaceForTesting(spreadStandsSurface(icao: "KIAH", reference: iah),
                                                             icao: "KIAH")
        model.settings.autoAssignGates = true
        model.aircraftState = parkedState(at: standCoordinate(reference: iah, index: 5))
        model.settings.departure = "KIAH"
        model.syncFlightPlanFromSettings()
        XCTAssertEqual(await waitForGate(model, role: .departure, toEqual: "C5"), "C5")

        // Nudged onto a different stand. The gate already came from the aircraft's position, so
        // it stands — the pilot clears the field if they want it read again.
        model.ingestStateForTesting(parkedState(at: standCoordinate(reference: iah, index: 2)))
        await settle()
        XCTAssertEqual(model.settings.departureGate, "C5",
                       "a gate read off the position isn't re-read on every reposition")
    }

    func testSwappingGatesCarriesTheirMarkers() {
        let model = makeModel()
        model.settings.departureGate = "C24"
        model.settings.autoAssignedDepartureGate = "KIAH:C24"
        model.settings.arrivalGate = "B44"
        model.settings.autoAssignedArrivalGate = ""
        model.swapManualGates()

        XCTAssertEqual(model.settings.departureGate, "B44")
        XCTAssertEqual(model.settings.arrivalGate, "C24")
        XCTAssertEqual(model.settings.autoAssignedDepartureGate, "",
                       "the hand-typed gate stays hand-typed after the swap")
        XCTAssertEqual(model.settings.autoAssignedArrivalGate, "KIAH:C24",
                       "and the automatic one stays the app's")
    }
}
