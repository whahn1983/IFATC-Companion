import XCTest
@testable import IFATCCompanion

/// Banned/outdated phrase detection, and a sweep asserting that every generated
/// controller, ramp, pilot, and UNICOM string is free of blocked phraseology.
final class PhraseologyValidatorTests: XCTestCase {

    private let validator = PhraseologyValidator()

    // MARK: - Banned phrase detection

    func testDetectsBlockedPhrases() {
        let blocked = [
            "United 1, cleared to taxi to runway 17R",
            "cleared for pushback",
            "runway 17R, position and hold",
            "taxi into position and hold",
            "cleared takeoff at your discretion",
            "any traffic please advise",
            "taking the active",
            "clear of the active",
            "cross all runways via Bravo"
        ]
        for s in blocked {
            XCTAssertFalse(validator.isClean(s), "should flag: \(s)")
        }
    }

    func testCleanPhrasesPass() {
        let clean = [
            "United 598, runway 17R, cleared for takeoff",
            "United 598, runway 30L, cleared to land",
            "United 598, taxi to runway 17R via Alpha, hold short runway 17L",
            "United 598, pushback approved, tail west",
            "United 598, cross runway 17L at Bravo, continue via Bravo",
            "United 598, line up and wait"
        ]
        for s in clean {
            XCTAssertTrue(validator.isClean(s), "should be clean: \(s)")
        }
    }

    func testWeakAckReadbackIsRejected() {
        XCTAssertFalse(validator.isAcceptableSafetyReadback("Roger", requiredElements: ["17R"]))
        XCTAssertFalse(validator.isAcceptableSafetyReadback("Wilco", requiredElements: ["17R"]))
        XCTAssertTrue(validator.isAcceptableSafetyReadback("Runway 17R, cleared for takeoff, United 598",
                                                           requiredElements: ["17R"]))
    }

    // MARK: - Sweep all generated phraseology

    private func engine() -> PhraseologyEngine { PhraseologyEngine(digitStyle: .individual, mode: .faa) }

    /// Every controller transmission emitted by the state machine for each state
    /// is free of blocked phraseology, in both display and spoken forms.
    func testStateMachineOutputsAreClean() {
        let m = ATCStateMachine(engine: engine())
        let ctx = TestSupport.context(runway: "17R")
        for state in ATCState.allCases {
            guard let tx = m.transmission(for: state, from: .connectedIdle, context: ctx) else { continue }
            XCTAssertTrue(validator.isClean(tx.displayText), "blocked phrase in \(state) display: \(tx.displayText)")
            XCTAssertTrue(validator.isClean(tx.spokenText), "blocked phrase in \(state) spoken: \(tx.spokenText)")
        }
    }

    func testPilotReadbacksAreClean() {
        let pilot = PilotResponseEngine(engine: engine())
        let ctx = TestSupport.context(runway: "17R")
        for state in ATCState.allCases {
            let tx = pilot.readback(for: state, context: ctx)
            XCTAssertTrue(validator.isClean(tx.displayText), "blocked phrase in pilot \(state): \(tx.displayText)")
            XCTAssertTrue(validator.isClean(tx.spokenText), "blocked phrase in pilot \(state) spoken: \(tx.spokenText)")
        }
    }

    func testUNICOMBroadcastsAreClean() {
        for event in UNICOMEvent.allCases {
            for runway in ["", "17R", "30L"] {
                let msg = event.broadcast(ident: "Minneapolis", runway: runway)
                XCTAssertTrue(validator.isClean(msg), "blocked phrase in UNICOM \(event): \(msg)")
            }
        }
    }

    /// UNICOM never says "the active" (banned), even with an unknown runway.
    func testUNICOMNeverSaysActive() {
        for event in UNICOMEvent.allCases {
            let msg = PhraseologyValidator.normalize(event.broadcast(ident: "", runway: ""))
            XCTAssertFalse(msg.contains("the active"), "\(event) used 'the active': \(msg)")
        }
    }
}
