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
}
