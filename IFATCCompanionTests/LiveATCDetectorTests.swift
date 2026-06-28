import XCTest
@testable import IFATCCompanion

final class LiveATCDetectorTests: XCTestCase {

    private let detector = LiveATCDetector()

    func testSoloIsNotStaffed() {
        let s = detector.status(atcActive: false, facilityName: nil, facilityCount: 0,
                                online: false, serverName: nil)
        XCTAssertFalse(s.shouldStandBy)
        XCTAssertFalse(s.multiplayerOnline)
    }

    func testFacilityCountMeansStaffed() {
        let s = detector.status(atcActive: nil, facilityName: nil, facilityCount: 2,
                                online: true, serverName: "Expert")
        XCTAssertTrue(s.shouldStandBy)
        XCTAssertTrue(s.multiplayerOnline)
        XCTAssertEqual(s.serverName, "Expert")
    }

    func testNamedFacilityMeansStaffed() {
        let s = detector.status(atcActive: nil, facilityName: "Tower", facilityCount: nil,
                                online: true, serverName: nil)
        XCTAssertTrue(s.humanControllerActive)
        XCTAssertEqual(s.activeFacility, "Tower")
    }

    func testUnicomIsNotAHumanController() {
        let s = detector.status(atcActive: false, facilityName: "UNICOM", facilityCount: 0,
                                online: true, serverName: "Casual")
        XCTAssertFalse(s.humanControllerActive)
        XCTAssertTrue(s.multiplayerOnline)
    }
}
