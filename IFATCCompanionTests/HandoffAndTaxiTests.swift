import XCTest
@testable import IFATCCompanion

/// Covers the fixes from the post-test-flight feedback: the taxi instruction ends
/// with "Contact Tower when ready", Center greets the climb with "radar contact",
/// the departure "direct …" fix is the next un-passed waypoint, the arrival gate
/// flows into the ramp routing, and — in live mode — the controller's hand-offs and
/// position calls still fire automatically while the pilot tunes frequencies by hand.
@MainActor
final class HandoffAndTaxiTests: XCTestCase {

    private func engine() -> PhraseologyEngine { PhraseologyEngine(digitStyle: .individual, mode: .faa) }

    // MARK: - Phraseology

    func testTaxiInstructionEndsWithContactTowerWhenReady() {
        let e = engine()
        let cs = e.callsign(airline: "United", flightNumber: "598", fallback: "")
        let tx = e.taxiToRunway(cs: cs, runway: "17R", via: "A", crossing: nil)
        XCTAssertTrue(tx.displayText.contains("taxi to runway 17R via A"))
        XCTAssertTrue(tx.displayText.hasSuffix("Contact Tower when ready."),
                      "taxi clearance should end by telling the pilot to call Tower: \(tx.displayText)")
        XCTAssertTrue(tx.spokenText.contains("Contact Tower when ready."))
    }

    func testCenterClimbStartsWithRadarContact() {
        let e = engine()
        let cs = e.callsign(airline: "United", flightNumber: "598", fallback: "")
        let tx = e.centerRadarContactClimb(cs: cs, altitude: 37000)
        XCTAssertEqual(tx.facility, .center)
        XCTAssertTrue(tx.displayText.contains("radar contact"))
        XCTAssertTrue(tx.displayText.contains("climb and maintain FL370"))
    }

    /// The state machine's Center climb call (after the Departure hand-off) leads
    /// with "radar contact" before clearing the aircraft to the cruise level.
    func testStateMachineClimbCallLeadsWithRadarContact() {
        var m = ATCStateMachine(engine: engine())
        m.setConnected()
        let tx = m.advance(to: .climb, context: TestSupport.context(cruise: 37000))
        XCTAssertEqual(tx?.facility, .center)
        XCTAssertTrue(tx?.displayText.contains("radar contact") ?? false)
        XCTAssertTrue(tx?.displayText.contains("FL370") ?? false)
    }

    // MARK: - Direct-to the next un-passed waypoint

    func testNextUnpassedWaypointSkipsFixesAlreadyBehind() {
        var plan = FlightPlan()
        plan.departure = "KIAH"
        let origin = CLLocationCoordinate2D(latitude: 30.0, longitude: -95.0)
        // Three fixes strung out north of the field.
        plan.waypoints = [
            Waypoint(name: "NEAR", latitude: 30.2, longitude: -95.0),
            Waypoint(name: "MID",  latitude: 31.0, longitude: -95.0),
            Waypoint(name: "FAR",  latitude: 32.0, longitude: -95.0)
        ]
        // Aircraft already past NEAR (at ~30.5N): the next un-passed fix is MID.
        let pos = CLLocationCoordinate2D(latitude: 30.5, longitude: -95.0)
        let next = plan.nextUnpassedWaypoint(from: pos, origin: origin)
        XCTAssertEqual(next?.name, "MID", "should clear direct to the next fix ahead, not one already passed")
    }

    // MARK: - Arrival gate routing

    func testArrivalGateFlowsIntoRampRouting() {
        let model = AppModel()
        model.settings.voiceEnabled = false
        model.settings.mockMode = false
        var plan = FlightPlan()
        plan.airline = "United"; plan.flightNumber = "598"
        plan.departure = "KIAH"; plan.destination = "KMSP"
        plan.arrivalGate = "B44"
        plan.waypoints = model.mock.route.waypoints
        model.flightPlan = plan

        // Drive into an arrival ground state, then taxi to the gate.
        for phase in [FlightPhase.takeoff, .climb, .cruise, .descent, .approach, .landing, .taxiIn] {
            model.ingestStateForTesting(model.mock.state(for: phase))
        }
        model.contactRamp()
        XCTAssertTrue(model.transcript.contains { $0.displayText.contains("gate B44") },
                      "arrival ramp routing should name the manually-entered gate")
    }

    private func arrivalModel(arrivalGate: String) -> AppModel {
        let model = AppModel()
        model.settings.voiceEnabled = false
        model.settings.mockMode = false
        var plan = FlightPlan()
        plan.airline = "United"; plan.flightNumber = "598"
        plan.departure = "KIAH"; plan.destination = "KMSP"
        plan.arrivalGate = arrivalGate
        plan.waypoints = model.mock.route.waypoints
        model.flightPlan = plan
        for phase in [FlightPhase.takeoff, .climb, .cruise, .descent, .approach, .landing, .taxiIn] {
            model.ingestStateForTesting(model.mock.state(for: phase))
        }
        return model
    }

    func testArrivalWithoutGateSkipsTaxiMapAndRouting() {
        // No arrival gate entered: there's no destination to route to, so the OSM taxi map
        // and routing are never armed — Ground's generic "taxi to parking" call stands on
        // its own.
        let model = arrivalModel(arrivalGate: "")
        XCTAssertFalse(model.airportSurface.awaitingTaxiReadback,
                       "no arrival gate → the OSM taxi clearance/map is not armed")
        XCTAssertFalse(model.airportSurface.taxiMapVisible, "no taxi map without an arrival gate")
    }

    func testArrivalWithGateArmsTaxiMap() {
        // Positive control: a manually-entered arrival gate begins the OSM arrival taxi.
        // Ground waits for the destination surface to load so it can route to the gate
        // (rather than giving a generic clearance); offline, with no surface, the flow holds
        // with the arrival taxi armed, and the routed clearance follows once it loads.
        let model = arrivalModel(arrivalGate: "B44")
        XCTAssertEqual(model.airportSurface.kind, .arrival,
                       "an arrival gate begins the OSM arrival taxi and waits for the route")
    }
}
