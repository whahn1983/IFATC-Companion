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
        XCTAssertTrue(tx.displayText.lowercased().contains("you're number one for departure"),
                      "Tower acknowledges the check-in with the departure sequence: \(tx.displayText)")
        XCTAssertTrue(tx.displayText.contains("36"), "the acknowledgement names the runway")
        // A sequencing report only — never a takeoff clearance.
        XCTAssertFalse(tx.displayText.lowercased().contains("cleared for takeoff"),
                       "checking in while monitoring must not issue a takeoff clearance")
        XCTAssertNil(tx.readback, "a sequence report needs no read-back")
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

        // No check-in is required, but if the pilot checks in — well before the runway,
        // not lined up — Tower gives ONLY the sequence, never a takeoff clearance.
        XCTAssertFalse(model.aircraftState.onGround == true &&
                       model.transcript.contains { $0.displayText.lowercased().contains("cleared for takeoff") },
                       "precondition: no takeoff clearance issued yet")
        model.requestHandoff()
        XCTAssertTrue(model.transcript.contains {
            $0.sender == .atc && $0.facility == .tower &&
            $0.displayText.lowercased().contains("you're number one for departure")
        }, "checking in while monitoring Tower gets a 'you're number one for departure' acknowledgement")
        XCTAssertFalse(model.transcript.contains {
            $0.displayText.lowercased().contains("cleared for takeoff")
        }, "checking in well before the runway must NOT issue a takeoff clearance")
    }

    // MARK: - Automatic line-up-and-wait (live mode)

    /// A *live*-mode model whose departure runway is pinned to "36" (heading 360°), so
    /// the line-up detector's alignment check is deterministic in the test.
    private func makeLiveModelRunway36() -> AppModel {
        let model = AppModel()
        model.settings.voiceEnabled = false
        model.settings.mockMode = false           // live mode → automatic Tower calls active
        var plan = FlightPlan()
        plan.airline = "United"; plan.flightNumber = "598"
        plan.departure = "KIAH"; plan.destination = "KMSP"
        plan.departureGate = "C12"; plan.arrivalGate = "B44"
        plan.departureRunway = "36"
        plan.cruiseAltitude = 37000
        plan.waypoints = model.mock.route.waypoints
        model.flightPlan = plan
        return model
    }

    /// On the ground, slow, heading 270° — perpendicular to runway 36, so the aircraft
    /// reads as holding short (NOT lined up on the runway centerline).
    private func holdingShortState() -> AircraftState {
        var s = AircraftState()
        s.onGround = true
        s.groundSpeed = 2
        s.heading = 270
        s.trueHeading = 270
        s.latitude = ref.latitude
        s.longitude = ref.longitude
        s.altitudeMSL = 0
        s.altitudeAGL = 0
        s.verticalSpeed = 0
        return s
    }

    /// On the ground, aligned with runway 36 (heading ≈ 360°) and beginning the takeoff
    /// roll. Ground speed 35 kt reads as `isDepartingRoll` (> 30 kt, aligned) — i.e. "on
    /// the runway" — yet stays below the 40 kt the phase detector treats as airborne, so
    /// the pre-departure Tower flow (not the airborne fallback) issues the clearance.
    private func linedUpRollingState() -> AircraftState {
        var s = AircraftState()
        s.onGround = true
        s.groundSpeed = 35
        s.heading = 2
        s.trueHeading = 2
        s.latitude = ref.latitude
        s.longitude = ref.longitude
        s.altitudeMSL = 0
        s.altitudeAGL = 0
        s.verticalSpeed = 0
        return s
    }

    /// After the Ground→Tower "monitor" hand-off, Tower automatically issues
    /// "line up and wait" as the aircraft *nears* the runway (the same lead distance the
    /// automatic runway-crossing clearance uses, so it can make a rolling line-up) — no
    /// pilot "ready" report or check-in required — then clears the takeoff once it is
    /// lined up.
    func testMonitoringTowerAutomaticallyIssuesLineUpAndWaitThenTakeoff() {
        let model = makeLiveModelRunway36()

        // Pilot-driven ground sequence up to the ground-taxi state (live mode).
        model.requestClearance();   model.readBack()
        model.requestPushback();    model.readBack()
        model.requestEngineStart(); model.readBack()
        model.requestTaxi();        model.readBack()   // Ramp → Ground hand-off
        model.requestTaxi();        model.readBack()   // Ground taxi clearance

        // Seed a synthetic departure surface on the model's coordinator and drive it up to
        // the monitor-Tower cue (the live surface can't be fetched offline). This is a
        // longer lead than the line-up cue, so it latches first.
        model.airportSurface.beginMockTaxiForTesting(kind: .departure, reference: ref,
                                                     runway: "36", gate: "C12")
        var n = 0
        while !model.airportSurface.approachingRunwayHandoff && n < 5000 {
            model.airportSurface.mockTickForTesting()
            if model.airportSurface.awaitingCrossingReadback { model.airportSurface.crossingReadbackReceived() }
            n += 1
        }
        XCTAssertTrue(model.airportSurface.approachingRunwayHandoff,
                      "surface drive reached the monitor-Tower cue")
        XCTAssertFalse(model.airportSurface.approachingRunwayLineup,
                       "the closer line-up cue has not latched yet at the monitor-Tower distance")

        // A telemetry tick at the monitor-Tower distance → Ground hands the pilot to Tower
        // to monitor, but NOT yet "line up and wait" (the line-up lead isn't reached).
        model.ingestStateForTesting(holdingShortState())
        XCTAssertTrue(model.transcript.contains {
            $0.sender == .atc && $0.facility == .ground &&
            $0.displayText.lowercased().contains("monitor tower on")
        }, "Ground hands the pilot to Tower to monitor approaching the runway")
        XCTAssertFalse(model.transcript.contains {
            $0.displayText.lowercased().contains("line up and wait")
        }, "no line-up-and-wait until the aircraft nears the runway (line-up lead distance)")

        // Continue the surface drive to the line-up cue (the crossing-clearance lead distance).
        n = 0
        while !model.airportSurface.approachingRunwayLineup && n < 5000 {
            model.airportSurface.mockTickForTesting()
            if model.airportSurface.awaitingCrossingReadback { model.airportSurface.crossingReadbackReceived() }
            n += 1
        }
        XCTAssertTrue(model.airportSurface.approachingRunwayLineup, "surface drive reached the line-up cue")

        // Next telemetry tick, nearing the runway → Tower automatically issues "line up and
        // wait" (before the aircraft has stopped, so it can roll straight onto the runway).
        model.ingestStateForTesting(holdingShortState())
        XCTAssertTrue(model.transcript.contains {
            $0.sender == .atc && $0.facility == .tower &&
            $0.displayText.lowercased().contains("line up and wait")
        }, "Tower automatically issues line up and wait as the aircraft nears the runway")
        XCTAssertFalse(model.transcript.contains {
            $0.displayText.lowercased().contains("cleared for takeoff")
        }, "the takeoff is not cleared until the aircraft is lined up")

        // Lined up and rolling on runway 36 → Tower clears the takeoff.
        model.ingestStateForTesting(linedUpRollingState())
        XCTAssertTrue(model.transcript.contains {
            $0.sender == .atc && $0.displayText.lowercased().contains("cleared for takeoff")
        }, "Tower clears the takeoff once the aircraft is lined up on the runway")
    }

    /// If the aircraft taxis straight onto the runway without stopping short (already
    /// lined up when it reaches the runway), Tower skips the line-up-and-wait and clears
    /// the takeoff directly.
    func testMonitoringTowerSkipsLineUpAndWaitWhenAlreadyLinedUp() {
        let model = makeLiveModelRunway36()
        model.requestClearance();   model.readBack()
        model.requestPushback();    model.readBack()
        model.requestEngineStart(); model.readBack()
        model.requestTaxi();        model.readBack()
        model.requestTaxi();        model.readBack()

        model.airportSurface.beginMockTaxiForTesting(kind: .departure, reference: ref,
                                                     runway: "36", gate: "C12")
        var n = 0
        while !model.airportSurface.approachingRunwayLineup && n < 5000 {
            model.airportSurface.mockTickForTesting()
            if model.airportSurface.awaitingCrossingReadback { model.airportSurface.crossingReadbackReceived() }
            n += 1
        }
        XCTAssertTrue(model.airportSurface.approachingRunwayLineup)

        // The aircraft is already rolling down runway 36 as it nears the runway —
        // Ground hands to Tower to monitor and the takeoff clearance fires, but the
        // line-up-and-wait is skipped (the aircraft is already on the runway).
        model.ingestStateForTesting(linedUpRollingState())

        XCTAssertTrue(model.transcript.contains {
            $0.sender == .atc && $0.facility == .ground &&
            $0.displayText.lowercased().contains("monitor tower on")
        }, "Ground still hands the pilot to Tower to monitor")
        XCTAssertTrue(model.transcript.contains {
            $0.sender == .atc && $0.displayText.lowercased().contains("cleared for takeoff")
        }, "Tower clears the takeoff for an aircraft that taxied straight onto the runway")
        XCTAssertFalse(model.transcript.contains {
            $0.displayText.lowercased().contains("line up and wait")
        }, "no line-up-and-wait when the aircraft is already lined up on the runway")
    }

    /// Regression: the departure taxi map (and its surface tracking) must survive the
    /// monitor-Tower hand-off. After Ground hands the pilot to Tower to *monitor* and the
    /// pilot reads it back — which auto-tunes the radio to Tower — the map must NOT be torn
    /// down. The automatic "line up and wait" cue (`approachingRunwayLineup`) is reached only
    /// by the surface continuing to track the aircraft up to the runway; tearing the map down
    /// on the Tower tune (the previous behavior) stopped that tracking, so the call never
    /// fired. Reading the line-up-and-wait back then retires the map.
    func testMonitorTowerReadbackKeepsMapLiveSoLineUpAndWaitStillFires() {
        let model = makeLiveModelRunway36()
        model.requestClearance();   model.readBack()
        model.requestPushback();    model.readBack()
        model.requestEngineStart(); model.readBack()
        model.requestTaxi();        model.readBack()
        model.requestTaxi();        model.readBack()

        // Seed a synthetic departure surface (the live surface can't be fetched offline) and
        // drive it up to the monitor-Tower cue (the longer lead, so it latches first).
        model.airportSurface.beginMockTaxiForTesting(kind: .departure, reference: ref,
                                                     runway: "36", gate: "C12")
        XCTAssertTrue(model.airportSurface.taxiMapVisible, "departure taxi map is up")
        var n = 0
        while !model.airportSurface.approachingRunwayHandoff && n < 5000 {
            model.airportSurface.mockTickForTesting()
            if model.airportSurface.awaitingCrossingReadback { model.airportSurface.crossingReadbackReceived() }
            n += 1
        }
        XCTAssertTrue(model.airportSurface.approachingRunwayHandoff)

        // A telemetry tick → Ground hands the pilot to Tower to monitor.
        model.ingestStateForTesting(holdingShortState())
        XCTAssertTrue(model.transcript.contains {
            $0.facility == .ground && $0.displayText.lowercased().contains("monitor tower on")
        }, "Ground hands the pilot to Tower to monitor")

        // The pilot reads the monitor call back — auto-tune switches the radio to Tower. The
        // taxi map must STAY visible (this is the tune-to-Tower step the earlier test skips,
        // and exactly where the map used to be torn down, killing the line-up cue).
        model.readBack()
        XCTAssertEqual(model.currentFacility, .tower, "reading back the monitor call tunes to Tower")
        XCTAssertTrue(model.airportSurface.taxiMapVisible,
                      "the taxi map stays visible after tuning Tower to monitor")

        // Continue the surface drive to the line-up cue; the next telemetry tick issues the
        // automatic line-up-and-wait even though the radio is already on Tower.
        n = 0
        while !model.airportSurface.approachingRunwayLineup && n < 5000 {
            model.airportSurface.mockTickForTesting()
            if model.airportSurface.awaitingCrossingReadback { model.airportSurface.crossingReadbackReceived() }
            n += 1
        }
        XCTAssertTrue(model.airportSurface.approachingRunwayLineup, "surface drive reached the line-up cue")

        model.ingestStateForTesting(holdingShortState())
        XCTAssertTrue(model.transcript.contains {
            $0.facility == .tower && $0.displayText.lowercased().contains("line up and wait")
        }, "Tower automatically issues line up and wait after the monitor hand-off, on Tower")

        // Reading the line-up-and-wait back retires the map — the aircraft is at the runway.
        model.readBack()
        XCTAssertFalse(model.airportSurface.taxiMapVisible,
                       "the taxi map is retired once the pilot reads back the line-up-and-wait")
    }
}
