import XCTest
import CoreLocation
@testable import IFATCCompanion

/// The automatic Ground → Tower "monitor" hand-off as the aircraft approaches the
/// departure runway (real-world "monitor Tower on …", the red sign short of the
/// runway). Covers the phraseology, the surface coordinator's approaching-runway
/// trigger, and the AppModel wiring (Ground issues the call; no check-in required;
/// checking in gets "number one for departure").
@MainActor
final class MonitorTowerHandoffTests: XCTestCase {

    private let ref = CLLocationCoordinate2D(latitude: 40, longitude: -75)

    private func engine() -> PhraseologyEngine { PhraseologyEngine(digitStyle: .individual, mode: .faa) }

    // MARK: - Phraseology

    func testMonitorTowerCallIsFromGroundAndTunesToTower() {
        let e = engine()
        let cs = e.callsign(airline: "United", flightNumber: "598", fallback: "")
        let tx = e.monitorTower(cs: cs, frequency: 118.3)
        XCTAssertEqual(tx.facility, .ground, "the monitor-tower hand-off is spoken by Ground")
        XCTAssertTrue(tx.displayText.lowercased().contains("monitor tower on 118.300"),
                      "Ground tells the pilot to monitor Tower on the frequency: \(tx.displayText)")
        // The read-back echoes "monitor Tower on …" and switches the radio to Tower.
        XCTAssertEqual(tx.readback?.facility, .tower)
        XCTAssertEqual(tx.readback?.tuneTo, .tower, "reading it back tunes the radio to Tower (auto-tune)")
        XCTAssertTrue(tx.readback?.displayText.lowercased().contains("monitor tower on") ?? false,
                      "the read-back is 'monitor Tower on …', not 'contacting Tower'")
    }

    func testNumberOneForTakeoffIsFromTowerAndNamesTheRunway() {
        let e = engine()
        let cs = e.callsign(airline: "United", flightNumber: "598", fallback: "")
        let tx = e.numberOneForTakeoff(cs: cs, runway: "36")
        XCTAssertEqual(tx.facility, .tower)
        XCTAssertTrue(tx.displayText.lowercased().contains("number one"),
                      "Tower acknowledges the check-in with the departure sequence: \(tx.displayText)")
        XCTAssertTrue(tx.displayText.contains("36"), "the acknowledgement names the runway")
    }

    // MARK: - Surface coordinator trigger

    func testDepartureFlagsApproachingRunwayHandoffNearTheRunway() {
        let coord = AirportSurfaceCoordinator()
        let e = engine()
        coord.configure(diagnostics: nil, engine: e, emit: { _ in },
                        callsign: { e.callsign(airline: "United", flightNumber: "598", fallback: "") })
        coord.beginMockTaxiForTesting(kind: .departure, reference: ref, runway: "36", gate: "A1")

        // Not flagged sitting at the gate.
        XCTAssertFalse(coord.approachingRunwayHandoff, "no monitor-tower cue at the gate")

        // Drive to the crossing, read it back, then continue to the runway hold-short.
        var n = 0
        while !coord.awaitingCrossingReadback && !coord.reachedDestination && n < 1200 {
            coord.mockTickForTesting(); n += 1
        }
        if coord.awaitingCrossingReadback { coord.crossingReadbackReceived() }
        n = 0
        while !coord.reachedDestination && n < 1200 { coord.mockTickForTesting(); n += 1 }

        // By the time the aircraft reaches the runway hold-short, the earlier
        // "approaching the runway" cue (fired within monitorTowerLeadMeters) has latched.
        XCTAssertTrue(coord.approachingRunwayHandoff,
                      "the monitor-tower cue latches as the departure taxi nears the runway hold-short")
    }

    func testArrivalNeverFlagsApproachingRunwayHandoff() {
        let coord = AirportSurfaceCoordinator()
        let e = engine()
        coord.configure(diagnostics: nil, engine: e, emit: { _ in },
                        callsign: { e.callsign(airline: "United", flightNumber: "598", fallback: "") })
        coord.beginMockTaxiForTesting(kind: .arrival, reference: ref, runway: "36", gate: "A1")
        var n = 0
        while !coord.reachedDestination && n < 1200 { coord.mockTickForTesting(); n += 1 }
        XCTAssertFalse(coord.approachingRunwayHandoff,
                       "the monitor-tower cue is a departure-only concept (never fires taxiing in)")
    }

    // MARK: - AppModel wiring

    private func makeModel() -> AppModel {
        let model = AppModel()
        model.settings.voiceEnabled = false
        model.settings.mockMode = true
        var plan = FlightPlan()
        plan.airline = "United"; plan.flightNumber = "598"
        plan.departure = "KIAH"; plan.destination = "KMSP"
        plan.departureGate = "C12"; plan.arrivalGate = "B44"
        plan.cruiseAltitude = 37000
        plan.waypoints = model.mock.route.waypoints
        model.flightPlan = plan
        return model
    }

    /// Drive the pilot-driven ground sequence to the point the departure taxi map is up,
    /// then run the surface mock drive up to the runway so Ground hands off to Tower.
    func testGroundHandsOffToTowerApproachingRunway() {
        let model = makeModel()
        model.requestClearance();   model.readBack()
        model.requestPushback();    model.readBack()
        model.requestEngineStart(); model.readBack()
        model.requestTaxi();        model.readBack()   // Ramp → Ground hand-off
        model.requestTaxi();        model.readBack()   // Ground taxi clearance + map
        XCTAssertTrue(model.airportSurface.taxiMapVisible, "departure taxi map is up")

        // Run the surface mock drive up to the runway, reading back any crossing so it proceeds.
        var n = 0
        while !model.airportSurface.approachingRunwayHandoff && n < 2000 {
            model.airportSurface.mockTickForTesting()
            if model.airportSurface.awaitingCrossingReadback { model.readBack() }
            n += 1
        }
        XCTAssertTrue(model.airportSurface.approachingRunwayHandoff,
                      "the surface drive reached the approaching-runway cue")

        // The next telemetry tick has Ground hand the pilot to Tower to monitor.
        model.ingestStateForTesting(model.mock.state(for: .taxiOut))
        XCTAssertTrue(model.transcript.contains {
            $0.sender == .atc && $0.facility == .ground &&
            $0.displayText.lowercased().contains("monitor tower on")
        }, "Ground automatically issues the monitor-tower hand-off approaching the runway")

        // It fires once — a second tick does not repeat it.
        let monitorCalls = model.transcript.filter { $0.displayText.lowercased().contains("monitor tower on") }.count
        model.ingestStateForTesting(model.mock.state(for: .taxiOut))
        let after = model.transcript.filter { $0.displayText.lowercased().contains("monitor tower on") }.count
        XCTAssertEqual(monitorCalls, after, "the monitor-tower hand-off is issued only once")

        // The read-back echoes "monitor Tower" and (auto-tune on by default) switches to Tower.
        model.readBack()
        XCTAssertTrue(model.transcript.contains {
            $0.sender == .pilot && $0.displayText.lowercased().contains("monitor tower on")
        }, "the pilot reads back 'monitor Tower on …'")
        XCTAssertEqual(model.currentFacility, .tower, "reading back auto-tunes the radio to Tower")

        // No check-in is required, but if the pilot checks in, Tower gives the sequence.
        model.requestHandoff()
        XCTAssertTrue(model.transcript.contains {
            $0.sender == .atc && $0.facility == .tower &&
            $0.displayText.lowercased().contains("number one")
        }, "checking in while monitoring Tower gets a 'number one for departure' acknowledgement")
    }
}
