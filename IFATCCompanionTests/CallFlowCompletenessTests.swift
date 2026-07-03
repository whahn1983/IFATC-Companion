import XCTest
@testable import IFATCCompanion

/// Ensures the gate-to-gate call flow is complete: every substantive phase emits
/// a controller transmission, and every safety-critical instruction has a pilot
/// readback that echoes the safety-critical elements (runway, altitude, squawk)
/// plus the callsign.
final class CallFlowCompletenessTests: XCTestCase {

    private func engine() -> PhraseologyEngine { PhraseologyEngine(digitStyle: .individual, mode: .faa) }

    /// Substantive states that must always produce a controller transmission.
    /// `.cruise` is intentionally excluded: Center establishes radar contact and
    /// clears the climb to cruise at the TRACON-ceiling check-in, so reaching the
    /// cruise level itself is silent (no redundant second "radar contact").
    private let substantive: [ATCState] = [
        .clearance, .pushback, .engineStart, .groundTaxi, .lineUpWait,
        .towerDeparture, .initialClimb, .departure, .climb,
        .descent, .approach, .final, .landing, .runwayExit, .groundArrival
    ]

    func testEverySubstantiveStateEmitsATransmission() {
        let m = ATCStateMachine(engine: engine())
        let ctx = TestSupport.context(runway: "17R")
        for state in substantive {
            XCTAssertNotNil(m.transmission(for: state, from: .connectedIdle, context: ctx),
                            "no controller transmission for \(state)")
        }
    }

    func testSafetyCriticalReadbacksEchoElements() {
        let pilot = PilotResponseEngine(engine: engine())
        let ctx = TestSupport.context(runway: "17R", cruise: 37000)
        let cases: [(ATCState, [String])] = [
            (.clearance, ["4271"]),       // squawk
            (.groundTaxi, ["17R"]),       // runway
            (.lineUpWait, ["17R"]),       // runway
            (.towerDeparture, ["17R"]),   // runway
            (.climb, ["FL370"]),          // altitude
            (.final, ["17R"]),            // approach runway
            (.landing, ["17R"])           // runway
        ]
        for (state, required) in cases {
            let tx = pilot.readback(for: state, context: ctx)
            for el in required {
                XCTAssertTrue(tx.displayText.contains(el),
                              "\(state) readback missing \(el): \(tx.displayText)")
            }
            XCTAssertTrue(tx.displayText.contains("United 598"),
                          "\(state) readback missing callsign: \(tx.displayText)")
        }
    }

    /// Ramp handoff to Ground is reachable: pushback/start are Ramp, then taxi is
    /// Ground — so the facility changes (Ramp → Ground) at the movement boundary.
    func testRampToGroundHandoffIsReachable() {
        XCTAssertEqual(ATCState.engineStart.facility, .ramp)
        XCTAssertEqual(ATCState.groundTaxi.facility, .ground)
    }

    // MARK: - Check-in phrasing

    /// Airborne and level: the pilot reports "with you at <altitude>".
    func testCheckInLevelReportsCurrentAltitude() {
        let pilot = PilotResponseEngine(engine: engine())
        let ctx = TestSupport.context()
        let tx = pilot.requestHandoff(context: ctx, facility: .center,
                                      currentAltitude: 37000, targetAltitude: 37000, onGround: false)
        XCTAssertTrue(tx.displayText.contains("with you at FL370"), tx.displayText)
        XCTAssertFalse(tx.displayText.contains(" for "), "level check-in should not name a target: \(tx.displayText)")
    }

    /// Climbing: the pilot reports "with you at <current> for <target>".
    func testCheckInClimbingReportsCurrentAndTarget() {
        let pilot = PilotResponseEngine(engine: engine())
        let ctx = TestSupport.context()
        let tx = pilot.requestHandoff(context: ctx, facility: .departure,
                                      currentAltitude: 8000, targetAltitude: 18000, onGround: false)
        XCTAssertTrue(tx.displayText.contains("with you at 8,000 for FL180"), tx.displayText)
    }

    /// Descending: the pilot reports the current altitude and the lower target.
    func testCheckInDescendingReportsCurrentAndTarget() {
        let pilot = PilotResponseEngine(engine: engine())
        let ctx = TestSupport.context()
        let tx = pilot.requestHandoff(context: ctx, facility: .approach,
                                      currentAltitude: 12000, targetAltitude: 4000, onGround: false)
        XCTAssertTrue(tx.displayText.contains("with you at 12,000 for 4,000"), tx.displayText)
    }

    /// On the ground (Ramp/Ground) or with no altitude telemetry: plain "checking in".
    func testCheckInOnGroundOrUnknownAltitudeSaysCheckingIn() {
        let pilot = PilotResponseEngine(engine: engine())
        let ctx = TestSupport.context()
        // Ground facility, even if an altitude is somehow supplied.
        let ground = pilot.requestHandoff(context: ctx, facility: .ground,
                                          currentAltitude: 5000, targetAltitude: 10000, onGround: false)
        XCTAssertTrue(ground.displayText.contains("checking in"), ground.displayText)
        // Airborne facility but no telemetry available.
        let noAlt = pilot.requestHandoff(context: ctx, facility: .center,
                                         currentAltitude: nil, targetAltitude: 37000, onGround: false)
        XCTAssertTrue(noAlt.displayText.contains("checking in"), noAlt.displayText)
        // On the ground overrides altitude reporting.
        let onGround = pilot.requestHandoff(context: ctx, facility: .tower,
                                            currentAltitude: 200, targetAltitude: 5000, onGround: true)
        XCTAssertTrue(onGround.displayText.contains("checking in"), onGround.displayText)
    }
}
