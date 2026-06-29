import XCTest
@testable import IFATCCompanion

/// Tests that Ramp is modeled as a first-class, *simulated local/non-FAA* facility
/// — separate from Ground — and that it never issues FAA ATC clearances.
final class RampPhraseologyTests: XCTestCase {

    private func engine() -> PhraseologyEngine { PhraseologyEngine(digitStyle: .individual, mode: .faa) }
    private func cs() -> PhraseologyEngine.Callsign {
        engine().callsign(airline: "United", flightNumber: "598", fallback: "")
    }

    private let validator = PhraseologyValidator()

    // MARK: - Ramp is separate from Ground and not FAA ATC

    func testRampIsNotFAAATC() {
        XCTAssertFalse(ATCFacility.ramp.isFAAATC)
        XCTAssertTrue(ATCFacility.ground.isFAAATC)
        XCTAssertNotEqual(ATCFacility.ramp, ATCFacility.ground)
    }

    func testPushbackAndStartAreRampNotGround() {
        XCTAssertEqual(ATCState.pushback.facility, .ramp)
        XCTAssertEqual(ATCState.engineStart.facility, .ramp)
        XCTAssertEqual(ATCState.groundTaxi.facility, .ground)
    }

    // MARK: - Pushback approval phraseology

    func testPushbackApprovalWithDirection() {
        let r = RampPhraseologyEngine(engine: engine())
        let tx = r.pushbackApproved(cs: cs(), direction: "west")
        XCTAssertTrue(tx.displayText.contains("pushback approved, tail west"), tx.displayText)
        XCTAssertEqual(tx.facility, .ramp)
        XCTAssertTrue(validator.isClean(tx.displayText))
    }

    func testPushbackApprovalUnknownDirectionFallsBack() {
        let r = RampPhraseologyEngine(engine: engine())
        let tx = r.pushbackApproved(cs: cs(), direction: "")
        XCTAssertTrue(tx.displayText.contains("pushback approved, advise ready to taxi"), tx.displayText)
        XCTAssertFalse(tx.displayText.lowercased().contains("cleared"))
    }

    func testApronStyleUsesFaceDirection() {
        let r = RampPhraseologyEngine(engine: engine())
        var profile = RampProfile.generic
        profile.rampType = .apronControl
        let tx = r.pushbackApproved(cs: cs(), direction: "north", profile: profile)
        XCTAssertTrue(tx.displayText.contains("face north"), tx.displayText)
    }

    func testRampNeverSaysClearedForPushback() {
        let r = RampPhraseologyEngine(engine: engine())
        let tx = r.pushbackApproved(cs: cs(), direction: "east")
        XCTAssertFalse(tx.displayText.lowercased().contains("cleared for pushback"))
    }

    // MARK: - Ramp taxi / handoff

    func testRampTaxiToSpotUsesTaxiViaNotCleared() {
        let r = RampPhraseologyEngine(engine: engine())
        let tx = r.taxiToSpot(cs: cs(), spot: "5")
        XCTAssertTrue(tx.displayText.contains("taxi via the alley to spot 5"), tx.displayText)
        XCTAssertFalse(tx.displayText.lowercased().contains("cleared"))
    }

    func testHandoffToGroundAtSpot() {
        let r = RampPhraseologyEngine(engine: engine())
        let tx = r.contactGround(cs: cs(), groundFrequency: 121.9, spot: "5")
        XCTAssertTrue(tx.displayText.contains("contact Ground"), tx.displayText)
        XCTAssertTrue(tx.displayText.contains("spot 5"))
        XCTAssertTrue(tx.spokenText.contains("one two one point niner"))
    }

    func testHandoffWithoutSpotUsesMovementAreaBoundary() {
        let r = RampPhraseologyEngine(engine: engine())
        let tx = r.contactGround(cs: cs(), groundFrequency: 121.9, spot: "")
        XCTAssertTrue(tx.displayText.contains("movement-area boundary"), tx.displayText)
    }

    // MARK: - Ramp must never issue ATC clearances/authority

    /// Sweep the controller-side ramp calls and assert none contain runway,
    /// takeoff, landing, crossing, altitude, heading, SID/STAR, or approach
    /// authority — and that all are free of blocked phraseology.
    func testRampNeverIssuesATCClearances() {
        let r = RampPhraseologyEngine(engine: engine())
        let calls: [ATCTransmission] = [
            r.pushbackApproved(cs: cs(), direction: "west"),
            r.pushbackApproved(cs: cs(), direction: ""),
            r.holdPosition(cs: cs()),
            r.startApproved(cs: cs()),
            r.taxiToSpot(cs: cs(), spot: "5"),
            r.taxiToSpot(cs: cs(), spot: ""),
            r.proceed(cs: cs(), to: "spot 5"),
            r.giveWay(cs: cs(), to: "the Delta Airbus"),
            r.contactGround(cs: cs(), groundFrequency: 121.9, spot: "5"),
            r.proceedToGate(cs: cs(), gate: "B44"),
            r.gateOccupied(cs: cs(), gate: "B44"),
            r.monitorRampToGate(cs: cs())
        ]
        let forbidden = ["runway", "cleared", "takeoff", "climb", "descend",
                         "heading", "approach", "flight level", "squawk"]
        for tx in calls {
            XCTAssertEqual(tx.facility, .ramp, "ramp call has wrong facility: \(tx.displayText)")
            let lower = tx.displayText.lowercased()
            for word in forbidden {
                XCTAssertFalse(lower.contains(word),
                               "ramp call must not contain '\(word)': \(tx.displayText)")
            }
            XCTAssertTrue(validator.isClean(tx.displayText), "blocked phrase: \(tx.displayText)")
        }
    }

    // MARK: - Ramp profile resolution

    func testGenericProfileWhenUnknownAirport() {
        let p = RampProfile.profile(for: "ZZZZ")
        XCTAssertEqual(p.id, "generic")
        XCTAssertTrue(p.requiresPushApproval)
        XCTAssertTrue(p.defaultPushDirections.isEmpty, "generic must not invent a push direction")
    }

    func testKnownAirportProfile() {
        let p = RampProfile.profile(for: "KATL")
        XCTAssertEqual(p.airportICAO, "KATL")
        XCTAssertEqual(p.rampType, .rampControl)
    }
}
