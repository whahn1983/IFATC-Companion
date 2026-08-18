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
