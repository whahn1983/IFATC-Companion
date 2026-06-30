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

    /// Clearance Delivery ends the IFR clearance with the pushback hand-off so the
    /// pilot knows which frequency to tune for the push. With a ramp/apron layer
    /// (the default companyRamp profile) that is the Ramp frequency.
    func testClearanceEndsWithRampPushbackHandoff() {
        let m = makeMachine()
        var ctx = TestSupport.context()
        ctx.rampFrequency = 131.0
        let tx = m.transmission(for: .clearance, from: .connectedIdle, context: ctx)
        XCTAssertEqual(tx?.facility, .clearance)
        XCTAssertTrue(tx?.displayText.contains("When ready for pushback, contact Ramp on 131.000") ?? false,
                      tx?.displayText ?? "nil")
    }

    /// When the airport has no ramp layer, the clearance hands off to Ground for
    /// the push instead of Ramp.
    func testClearancePushbackHandoffUsesGroundWhenNoRamp() {
        let m = makeMachine()
        var ctx = TestSupport.context()
        var profile = RampProfile.generic
        profile.rampType = .none
        ctx.rampProfile = profile
        let tx = m.transmission(for: .clearance, from: .connectedIdle, context: ctx)
        XCTAssertTrue(tx?.displayText.contains("contact Ground on 121.800") ?? false,
                      tx?.displayText ?? "nil")
        XCTAssertFalse(tx?.displayText.contains("contact Ramp") ?? true)
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

    func testReachingCruiseProducesNoExtraRadarContact() {
        // Center already established radar contact and cleared the climb to cruise at
        // the TRACON-ceiling check-in, so reaching the cruise level itself is silent —
        // no second "radar contact" call. The state still advances.
        var m = makeMachine()
        m.setConnected()
        let tx = m.advance(to: .cruise, context: TestSupport.context(cruise: 37000))
        XCTAssertEqual(m.current, .cruise)
        XCTAssertNil(tx, "reaching cruise should not emit a controller call")
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
