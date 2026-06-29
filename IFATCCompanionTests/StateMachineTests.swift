import XCTest
@testable import IFATCCompanion

final class StateMachineTests: XCTestCase {

    private func makeMachine() -> ATCStateMachine {
        ATCStateMachine(engine: PhraseologyEngine(digitStyle: .individual, mode: .faa))
    }

    func testPhaseToStateMapping() {
        let m = makeMachine()
        XCTAssertEqual(m.mappedState(for: .preflight), .clearance)
        XCTAssertEqual(m.mappedState(for: .taxiOut), .groundTaxi)
        XCTAssertEqual(m.mappedState(for: .takeoff), .towerDeparture)
        XCTAssertEqual(m.mappedState(for: .cruise), .cruise)
        XCTAssertEqual(m.mappedState(for: .approach), .approach)
        XCTAssertEqual(m.mappedState(for: .parked), .parked)
    }

    func testConnectTransition() {
        var m = makeMachine()
        XCTAssertEqual(m.current, .notConnected)
        m.setConnected()
        XCTAssertEqual(m.current, .connectedIdle)
    }

    func testAdvanceProducesTransmissionAndChangesState() {
        var m = makeMachine()
        m.setConnected()
        let ctx = TestSupport.context()
        let tx = m.advance(to: .clearance, context: ctx)
        XCTAssertNotNil(tx)
        XCTAssertEqual(m.current, .clearance)
        XCTAssertEqual(tx?.sender, .atc)
        XCTAssertEqual(tx?.facility, .clearance)
        XCTAssertTrue(tx?.displayText.contains("cleared to") ?? false)
    }

    func testAdvanceToSameStateReturnsNil() {
        var m = makeMachine()
        m.setConnected()
        let ctx = TestSupport.context()
        _ = m.advance(to: .clearance, context: ctx)
        let again = m.advance(to: .clearance, context: ctx)
        XCTAssertNil(again)
    }

    func testTakeoffTransmissionMentionsRunway() {
        var m = makeMachine()
        m.setConnected()
        let ctx = TestSupport.context(runway: "17R")
        let tx = m.advance(to: .towerDeparture, context: ctx)
        XCTAssertTrue(tx?.spokenText.contains("one seven right") ?? false)
        XCTAssertTrue(tx?.displayText.contains("cleared for takeoff") ?? false)
    }

    func testFacilityForState() {
        XCTAssertEqual(ATCState.clearance.facility, .clearance)
        XCTAssertEqual(ATCState.groundTaxi.facility, .ground)
        XCTAssertEqual(ATCState.cruise.facility, .center)
        XCTAssertEqual(ATCState.approach.facility, .approach)
        XCTAssertEqual(ATCState.landing.facility, .tower)
        // Pushback and engine start are Ramp (simulated local/company), not Ground.
        XCTAssertEqual(ATCState.pushback.facility, .ramp)
        XCTAssertEqual(ATCState.engineStart.facility, .ramp)
        XCTAssertEqual(ATCState.lineUpWait.facility, .tower)
    }

    func testPushbackTransmission() {
        var m = makeMachine()
        m.setConnected()
        let tx = m.advance(to: .pushback, context: TestSupport.context())
        XCTAssertEqual(m.current, .pushback)
        XCTAssertEqual(tx?.facility, .ramp)
        XCTAssertTrue(tx?.displayText.contains("pushback approved") ?? false)
    }

    func testEngineStartTransmission() {
        var m = makeMachine()
        m.setConnected()
        let tx = m.advance(to: .engineStart, context: TestSupport.context())
        XCTAssertEqual(m.current, .engineStart)
        XCTAssertEqual(tx?.facility, .ramp)
        XCTAssertTrue(tx?.displayText.contains("start approved") ?? false)
    }

    func testLineUpAndWaitTransmission() {
        var m = makeMachine()
        m.setConnected()
        let tx = m.advance(to: .lineUpWait, context: TestSupport.context(runway: "17R"))
        XCTAssertEqual(m.current, .lineUpWait)
        XCTAssertTrue(tx?.displayText.contains("line up and wait") ?? false)
        XCTAssertTrue(tx?.spokenText.contains("one seven right") ?? false)
    }

    func testDepartureGroundFlowIsOrdered() {
        // The pre-departure ground states are recognised as the manual flow so
        // telemetry cannot skip them.
        for s in [ATCState.clearance, .pushback, .engineStart, .groundTaxi, .lineUpWait] {
            XCTAssertTrue(s.isManualGroundFlow, "\(s) should be part of the manual ground flow")
        }
        for s in [ATCState.towerDeparture, .climb, .cruise, .approach, .groundArrival] {
            XCTAssertFalse(s.isManualGroundFlow, "\(s) should not be part of the manual ground flow")
        }
    }
}
