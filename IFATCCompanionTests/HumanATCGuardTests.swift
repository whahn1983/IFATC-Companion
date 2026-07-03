import XCTest
@testable import IFATCCompanion

/// The human-ATC "active guard" is per-frequency: the companion only stands aside
/// while the pilot is tuned to the frequency a human is actually staffing. Tuning off
/// that frequency (or onto a sector no human is working) lifts the guard so the
/// companion resumes covering that sector. These tests pin the exact behavior the
/// feature promises.
final class HumanATCGuardTests: XCTestCase {

    private func staffed(_ facility: String) -> LiveATCStatus {
        LiveATCStatus(multiplayerOnline: true, serverName: "Expert",
                      humanControllerActive: true, activeFacility: facility)
    }

    // MARK: - Facility name mapping

    func testFacilityNameMapping() {
        XCTAssertEqual(ATCFacility.matching(name: "Ground"), .ground)
        XCTAssertEqual(ATCFacility.matching(name: "KSFO Tower"), .tower)
        XCTAssertEqual(ATCFacility.matching(name: "Clearance Delivery"), .clearance)
        XCTAssertEqual(ATCFacility.matching(name: "Approach"), .approach)
        XCTAssertEqual(ATCFacility.matching(name: "Seattle Center"), .center)
        XCTAssertEqual(ATCFacility.matching(name: "Departure"), .departure)
        // Ground Control must resolve to Ground, not fall through.
        XCTAssertEqual(ATCFacility.matching(name: "Ground Control"), .ground)
        // Non-ATC / unrecognised names don't map.
        XCTAssertNil(ATCFacility.matching(name: "UNICOM"))
        XCTAssertNil(ATCFacility.matching(name: "ATIS"))
        XCTAssertNil(ATCFacility.matching(name: ""))
        XCTAssertNil(ATCFacility.matching(name: nil))
    }

    // MARK: - Per-frequency guard

    func testGuardAppliesOnlyOnTheStaffedFrequency() {
        let ground = staffed("Ground")
        // Only Ground is manned: the guard applies on Ground …
        XCTAssertTrue(ground.shouldStandBy(tunedTo: .ground))
        // … but NOT on any other frequency, so Clearance Delivery is still available
        // before switching to the manned Ground frequency for pushback.
        XCTAssertFalse(ground.shouldStandBy(tunedTo: .clearance))
        XCTAssertFalse(ground.shouldStandBy(tunedTo: .tower))
        XCTAssertFalse(ground.shouldStandBy(tunedTo: .departure))
        XCTAssertFalse(ground.shouldStandBy(tunedTo: .center))
    }

    func testTuningOffTheStaffedFrequencyLiftsTheGuard() {
        let tower = staffed("Tower")
        // On Tower (manned) the companion stands by …
        XCTAssertTrue(tower.shouldStandBy(tunedTo: .tower))
        // … then after departing and leaving Tower for the (unmanned) Departure and
        // Center sectors, the companion resumes covering them.
        XCTAssertFalse(tower.shouldStandBy(tunedTo: .departure))
        XCTAssertFalse(tower.shouldStandBy(tunedTo: .center))
        // Off frequency entirely — no tuned facility — also lifts the guard.
        XCTAssertFalse(tower.shouldStandBy(tunedTo: nil))
    }

    func testMannedArrivalReEngagesTheGuard() {
        let approach = staffed("Approach")
        // Enroute on Center (unmanned) the companion works the flight …
        XCTAssertFalse(approach.shouldStandBy(tunedTo: .center))
        // … and once the pilot tunes the manned Approach on arrival, the guard is back.
        XCTAssertTrue(approach.shouldStandBy(tunedTo: .approach))
    }

    func testRampIsNeverGuarded() {
        // Ramp is a simulated local procedure, not FAA ATC, so the companion always
        // handles the pushback / taxi-to-gate even with Ground staffed.
        XCTAssertFalse(staffed("Ground").shouldStandBy(tunedTo: .ramp))
    }

    func testNoHumanControllerNeverStandsBy() {
        let solo = LiveATCStatus.none
        for facility in ATCFacility.allCases {
            XCTAssertFalse(solo.shouldStandBy(tunedTo: facility))
        }
    }

    func testUnidentifiableFacilityDoesNotStandBy() {
        // Some IF versions expose only a facility count (no usable name). We can't
        // confirm which frequency is staffed, so the companion does NOT gate — the
        // pilot keeps the companion rather than being locked out of a frequency that
        // may well be uncontrolled.
        let countOnly = LiveATCStatus(multiplayerOnline: true, serverName: "Expert",
                                      humanControllerActive: true, activeFacility: nil)
        XCTAssertNil(countOnly.staffedFacility)
        for facility in ATCFacility.allCases {
            XCTAssertFalse(countOnly.shouldStandBy(tunedTo: facility))
        }
        XCTAssertFalse(countOnly.shouldStandBy(tunedTo: nil))
    }

    func testUnrecognisedFacilityNameDoesNotStandBy() {
        // A human controller reported under a name that doesn't map to a gate-to-gate
        // position (e.g. a controller's initials) can't be tied to the tuned frequency,
        // so the companion keeps working every sector instead of gating blindly.
        let odd = staffed("JAR")
        XCTAssertNil(odd.staffedFacility)
        for facility in ATCFacility.allCases {
            XCTAssertFalse(odd.shouldStandBy(tunedTo: facility))
        }
    }

    func testAtisIsNeverGuarded() {
        // ATIS is an automated broadcast, not a human controller: it is not detected as
        // human ATC and never gates the app, whatever the pilot is tuned to.
        let detector = LiveATCDetector()
        let atis = detector.status(atcActive: false, facilityName: "ATIS", facilityCount: 0,
                                   online: true, serverName: "Expert")
        XCTAssertFalse(atis.humanControllerActive)
        XCTAssertNil(atis.staffedFacility)
        for facility in ATCFacility.allCases {
            XCTAssertFalse(atis.shouldStandBy(tunedTo: facility))
        }
    }
}

/// Verifies the guard through `AppModel`: `companionStandby` follows the tuned
/// facility, and the pilot can always tune to leave a staffed frequency and lift it.
@MainActor
final class HumanATCGuardModelTests: XCTestCase {

    private func makeModel() -> AppModel {
        let model = AppModel()
        model.settings.voiceEnabled = false
        model.settings.mockMode = true
        return model
    }

    func testCompanionStandbyTracksTheTunedFacility() {
        let model = makeModel()
        // Only Ground is manned.
        model.liveATC = LiveATCStatus(multiplayerOnline: true, serverName: "Expert",
                                      humanControllerActive: true, activeFacility: "Ground")

        // Tuned to Clearance (unmanned): companion active — Clearance is available.
        model.currentFacility = .clearance
        XCTAssertFalse(model.companionStandby)

        // Tuned to the manned Ground: companion stands by.
        model.currentFacility = .ground
        XCTAssertTrue(model.companionStandby)
    }

    func testTuningIsAllowedWhileStandingBy() {
        let model = makeModel()
        model.liveATC = LiveATCStatus(multiplayerOnline: true, serverName: "Expert",
                                      humanControllerActive: true, activeFacility: "Tower")
        model.currentFacility = .tower
        XCTAssertTrue(model.companionStandby)

        // Tuning off the manned Tower must be permitted — it's how the guard lifts.
        model.tuneTo(.departure)
        XCTAssertEqual(model.currentFacility, .departure)
        XCTAssertFalse(model.companionStandby)
    }
}
