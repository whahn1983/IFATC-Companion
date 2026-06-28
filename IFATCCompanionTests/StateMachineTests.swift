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
    }
}
