import XCTest
@testable import IFATCCompanion

final class PhaseDetectionTests: XCTestCase {

    let detector = PhaseDetector()
    let airports = AirportDatabase.shared

    func testStoppedOnGroundIsPreflight() {
        var s = AircraftState()
        s.onGround = true
        s.groundSpeed = 0
        let (phase, _) = detector.detect(state: s, plan: .empty, airports: airports, previous: .preflight)
        XCTAssertEqual(phase, .preflight)
    }

    func testTaxiSpeedOnGroundIsTaxiOut() {
        var s = AircraftState()
        s.onGround = true
        s.groundSpeed = 16
        let (phase, _) = detector.detect(state: s, plan: .empty, airports: airports, previous: .preflight)
        XCTAssertEqual(phase, .taxiOut)
    }

    func testHighSpeedOnGroundIsTakeoff() {
        var s = AircraftState()
        s.onGround = true
        s.groundSpeed = 150
        let (phase, _) = detector.detect(state: s, plan: .empty, airports: airports, previous: .taxiOut)
        XCTAssertEqual(phase, .takeoff)
    }

    func testClimbingAirborneIsClimb() {
        var s = AircraftState()
        s.onGround = false
        s.altitudeMSL = 12000
        s.verticalSpeed = 2000
        s.latitude = 40
        s.longitude = -95
        let (phase, _) = detector.detect(state: s, plan: .empty, airports: airports, previous: .takeoff)
        XCTAssertEqual(phase, .climb)
    }

    func testLevelNearCruiseIsCruise() {
        var s = AircraftState()
        s.onGround = false
        s.altitudeMSL = 37000
        s.verticalSpeed = 0
        var plan = FlightPlan()
        plan.cruiseAltitude = 37000
        let (phase, _) = detector.detect(state: s, plan: plan, airports: airports, previous: .climb)
        XCTAssertEqual(phase, .cruise)
    }

    func testDescendingFarFromDestIsDescent() {
        var s = AircraftState()
        s.onGround = false
        s.altitudeMSL = 20000
        s.verticalSpeed = -1800
        s.latitude = 41
        s.longitude = -96
        var plan = FlightPlan()
        plan.destination = "KMSP"
        let (phase, _) = detector.detect(state: s, plan: plan, airports: airports, previous: .cruise)
        XCTAssertEqual(phase, .descent)
    }

    func testParkedAfterArrival() {
        var s = AircraftState()
        s.onGround = true
        s.groundSpeed = 0
        let (phase, _) = detector.detect(state: s, plan: .empty, airports: airports, previous: .taxiIn)
        XCTAssertEqual(phase, .parked)
    }

    // MARK: - A snapshot with no ground reference

    /// A half-read snapshot — position and altitude, but the on-ground read dropped —
    /// is what a poll around a reconnect returns. It must not read as airborne: doing so
    /// sent a taxiing aircraft to "climb", and Center on the radio, from the taxiway.
    private func snapshotWithoutGroundReference(altitudeMSL: Double = 97) -> AircraftState {
        var s = AircraftState()
        s.latitude = 29.98        // KIAH
        s.longitude = -95.34
        s.altitudeMSL = altitudeMSL
        XCTAssertNil(s.onGround)
        XCTAssertNil(s.altitudeAGL)
        return s
    }

    func testMissingGroundStateHoldsTheGroundPhase() {
        let s = snapshotWithoutGroundReference()
        var plan = FlightPlan()
        plan.departure = "KIAH"
        plan.destination = "KMSP"
        plan.cruiseAltitude = 28000

        for previous: FlightPhase in [.preflight, .taxiOut, .taxiIn, .parked] {
            let (phase, _) = detector.detect(state: s, plan: plan, airports: airports, previous: previous)
            XCTAssertEqual(phase, previous,
                           "an unreported on-ground state must hold \(previous.title), not go airborne")
        }
    }

    func testMissingGroundStateHoldsTheAirbornePhase() {
        let s = snapshotWithoutGroundReference(altitudeMSL: 28000)
        var plan = FlightPlan()
        plan.destination = "KMSP"
        plan.cruiseAltitude = 28000

        for previous: FlightPhase in [.cruise, .descent, .approach] {
            let (phase, _) = detector.detect(state: s, plan: plan, airports: airports, previous: previous)
            XCTAssertEqual(phase, previous, "the hold applies in the air too")
        }
    }

    /// The hold is not a freeze: a real climb rate still proves the aircraft is flying
    /// even when the on-ground read is missing.
    func testMissingGroundStateStillDetectsAClimbFromVerticalRate() {
        var s = snapshotWithoutGroundReference(altitudeMSL: 12000)
        s.verticalSpeed = 2000
        let (phase, _) = detector.detect(state: s, plan: .empty, airports: airports, previous: .takeoff)
        XCTAssertEqual(phase, .climb)
    }
}
