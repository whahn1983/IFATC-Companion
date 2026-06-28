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
}
