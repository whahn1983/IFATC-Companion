import XCTest
@testable import IFATCCompanion

/// The human-ATC "active guard" is per-frequency and location-aware: the companion
/// only stands aside while the pilot is tuned to a staffed human controller (read from
/// Infinite Flight's live COM1 frequency name). Tuning off that frequency — onto
/// UNICOM, ATIS, or an unstaffed field — lifts the guard so the companion resumes
/// covering that sector, and a human controlling a different airport elsewhere in the
/// session never gates the app. These tests pin the exact behavior the feature promises.
final class HumanATCGuardTests: XCTestCase {

    /// A live status where the pilot is tuned to `frequencyName`, with a human known to
    /// be controlling somewhere in the session.
    private func tuned(_ frequencyName: String?) -> LiveATCStatus {
        LiveATCStatus(multiplayerOnline: true, serverName: "Expert",
                      humanControllerActive: true, controllerName: "j_vonl",
                      tunedFrequencyName: frequencyName)
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

    // MARK: - Per-frequency, location-aware guard

    func testStandsByOnlyWhenTunedToAStaffedFrequency() {
        // Tuned to a staffed controller: the companion stands by …
        XCTAssertTrue(tuned("KSFO Tower").companionShouldStandBy)
        XCTAssertTrue(tuned("Ground").companionShouldStandBy)
        XCTAssertTrue(tuned("Seattle Center").companionShouldStandBy)
        XCTAssertTrue(tuned("Clearance Delivery").companionShouldStandBy)
    }

    func testDoesNotStandByOnUnicomAtisOrOffFrequency() {
        // UNICOM is unstaffed, ATIS is automated, and off-frequency is nothing to defer
        // to — the companion keeps working in every case.
        XCTAssertFalse(tuned("Unicom").companionShouldStandBy)
        XCTAssertFalse(tuned("KBOS ATIS").companionShouldStandBy)
        XCTAssertFalse(tuned(nil).companionShouldStandBy)
        XCTAssertFalse(tuned("").companionShouldStandBy)
    }

    func testNotTunedToAnyFrequencyDoesNotStandBy() {
        // Infinite Flight reports "Unknown"/"None" for COM1 when the pilot isn't tuned to
        // any frequency at all. That is not a controller to defer to — the guard is off.
        XCTAssertFalse(tuned("Unknown").companionShouldStandBy)
        XCTAssertFalse(tuned("unknown").companionShouldStandBy)
        XCTAssertFalse(tuned("None").companionShouldStandBy)
        // The placeholder must not leak into the UI summary or facility mapping.
        let detector = LiveATCDetector()
        let notTuned = detector.status(atcActive: false, controllerName: nil, facilityCount: 0,
                                       online: true, serverName: "Expert",
                                       tunedFrequencyName: "Unknown")
        XCTAssertFalse(notTuned.companionShouldStandBy)
        XCTAssertNil(notTuned.tunedFrequencyName)
        XCTAssertNil(notTuned.tunedFacility)
    }

    func testHumanElsewhereButNotTunedDoesNotStandBy() {
        // A human controller is active in the session (username known) but the pilot is
        // on UNICOM: the companion keeps working, because that controller isn't on the
        // pilot's frequency. This is the location-awareness the guard depends on.
        let s = tuned("Unicom")
        XCTAssertTrue(s.humanControllerActive)
        XCTAssertFalse(s.companionShouldStandBy)
    }

    func testLeavingAStaffedFrequencyLiftsTheGuard() {
        // On the manned Tower the companion stands by …
        XCTAssertTrue(tuned("KSFO Tower").companionShouldStandBy)
        // … then after tuning away to UNICOM enroute, it resumes covering the sector.
        XCTAssertFalse(tuned("Unicom").companionShouldStandBy)
    }

    func testTunedFacilityResolvesFromFrequencyName() {
        XCTAssertEqual(tuned("KSFO Tower").tunedFacility, .tower)
        XCTAssertEqual(tuned("Ground").tunedFacility, .ground)
        XCTAssertNil(tuned("Unicom").tunedFacility)
        XCTAssertNil(tuned(nil).tunedFacility)
    }

    func testNoHumanControllerNeverStandsBy() {
        XCTAssertFalse(LiveATCStatus.none.companionShouldStandBy)
        XCTAssertFalse(LiveATCStatus.none.humanControllerActive)
    }

    func testAtisIsNeverGuarded() {
        // ATIS is an automated broadcast: tuning it never puts the companion on standby.
        let detector = LiveATCDetector()
        let atis = detector.status(atcActive: false, controllerName: nil, facilityCount: 0,
                                   online: true, serverName: "Expert",
                                   tunedFrequencyName: "ATIS")
        XCTAssertFalse(atis.companionShouldStandBy)
        XCTAssertNil(atis.tunedFacility)
    }
}

/// Verifies the guard through `AppModel`: in live mode `companionStandby` follows the
/// pilot's tuned COM frequency, standing by only while tuned to a staffed human
/// controller and resuming the moment the pilot leaves that frequency.
@MainActor
final class HumanATCGuardModelTests: XCTestCase {

    private func makeModel() -> AppModel {
        let model = AppModel()
        model.settings.voiceEnabled = false
        // Exercise the live-mode gate (mock mode uses the demo toggle instead).
        model.settings.mockMode = false
        return model
    }

    func testCompanionStandbyFollowsTheTunedFrequency() {
        let model = makeModel()

        // Tuned to UNICOM (uncontrolled): companion active — requests are available.
        model.liveATC = LiveATCStatus(multiplayerOnline: true, humanControllerActive: true,
                                      controllerName: "j_vonl", tunedFrequencyName: "Unicom")
        XCTAssertFalse(model.companionStandby)

        // Tuned to the staffed Ground: companion stands by.
        model.liveATC = LiveATCStatus(multiplayerOnline: true, humanControllerActive: true,
                                      controllerName: "j_vonl", tunedFrequencyName: "Ground")
        XCTAssertTrue(model.companionStandby)
        XCTAssertTrue(model.availableActions.isEmpty)

        // Tuning back to UNICOM lifts the guard.
        model.liveATC = LiveATCStatus(multiplayerOnline: true, humanControllerActive: true,
                                      controllerName: "j_vonl", tunedFrequencyName: "Unicom")
        XCTAssertFalse(model.companionStandby)
    }

    func testHumanControllingElsewhereDoesNotGate() {
        let model = makeModel()
        // Human ATC online in the session, but the pilot is on UNICOM: companion active.
        model.liveATC = LiveATCStatus(multiplayerOnline: true, humanControllerActive: true,
                                      controllerName: "j_vonl", tunedFrequencyName: "Unicom")
        XCTAssertTrue(model.liveATC.humanControllerActive)
        XCTAssertFalse(model.companionStandby)
    }
}
