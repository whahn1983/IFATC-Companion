import XCTest
@testable import IFATCCompanion

final class LiveATCDetectorTests: XCTestCase {

    private let detector = LiveATCDetector()

    func testSoloIsNotStaffed() {
        let s = detector.status(atcActive: false, controllerName: nil, facilityCount: 0,
                                online: false, serverName: nil)
        XCTAssertFalse(s.humanControllerActive)
        XCTAssertFalse(s.companionShouldStandBy)
        XCTAssertFalse(s.multiplayerOnline)
    }

    func testFacilityCountMeansHumanPresentButNotStandbyUntilTuned() {
        // A human is controlling somewhere in the session, but until the pilot tunes a
        // controller frequency the companion keeps working — we can't confirm that
        // controller is on the pilot's frequency / at the pilot's field.
        let s = detector.status(atcActive: nil, controllerName: nil, facilityCount: 2,
                                online: true, serverName: "Expert")
        XCTAssertTrue(s.humanControllerActive)
        XCTAssertFalse(s.companionShouldStandBy)
        XCTAssertTrue(s.multiplayerOnline)
        XCTAssertEqual(s.serverName, "Expert")
    }

    func testControllerNameIsPresenceOnly() {
        // The manifest exposes a controller username but no facility/frequency, so it's
        // a presence signal only — not enough to stand by on its own.
        let s = detector.status(atcActive: nil, controllerName: "j_vonl", facilityCount: nil,
                                online: true, serverName: nil)
        XCTAssertTrue(s.humanControllerActive)
        XCTAssertEqual(s.controllerName, "j_vonl")
        XCTAssertFalse(s.companionShouldStandBy)
    }

    func testTunedToStaffedFrequencyStandsBy() {
        let s = detector.status(atcActive: nil, controllerName: "j_vonl", facilityCount: 1,
                                online: true, serverName: "Expert",
                                tunedFrequencyName: "KSFO Tower")
        XCTAssertTrue(s.companionShouldStandBy)
        XCTAssertEqual(s.tunedFacility, .tower)
    }

    func testTunedFrequencyAloneImpliesHumanOnFrequency() {
        // Even when the standalone staffing flags don't resolve on this IF version,
        // a named controller frequency in the tuned COM means a human is on the air.
        let s = detector.status(atcActive: nil, controllerName: nil, facilityCount: nil,
                                online: nil, serverName: nil,
                                tunedFrequencyName: "Ground")
        XCTAssertTrue(s.companionShouldStandBy)
        XCTAssertTrue(s.humanControllerActive)
        XCTAssertEqual(s.tunedFacility, .ground)
    }

    func testTunedToUnicomDoesNotStandBy() {
        let s = detector.status(atcActive: nil, controllerName: "j_vonl", facilityCount: 1,
                                online: true, serverName: "Expert",
                                tunedFrequencyName: "Unicom")
        XCTAssertFalse(s.companionShouldStandBy)
        XCTAssertNil(s.tunedFacility)
    }

    func testTunedToAtisDoesNotStandBy() {
        // ATIS is an automated broadcast, not a human controller.
        let s = detector.status(atcActive: nil, controllerName: nil, facilityCount: 1,
                                online: true, serverName: "Expert",
                                tunedFrequencyName: "KBOS ATIS")
        XCTAssertFalse(s.companionShouldStandBy)
    }

    func testUnicomControllerNameIsNotHuman() {
        let s = detector.status(atcActive: false, controllerName: "UNICOM", facilityCount: 0,
                                online: true, serverName: "Casual")
        XCTAssertFalse(s.humanControllerActive)
        XCTAssertTrue(s.multiplayerOnline)
    }
}
