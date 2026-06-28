import XCTest
@testable import IFATCCompanion

final class PilotIntentParserTests: XCTestCase {

    private let parser = PilotIntentParser()

    func testSayAgain() {
        XCTAssertEqual(parser.parse("say again"), .sayAgain)
        XCTAssertEqual(parser.parse("Center, say again for United."), .sayAgain)
    }

    func testUnable() {
        XCTAssertEqual(parser.parse("unable that altitude"), .unable)
    }

    func testRequestHigherAndLower() {
        XCTAssertEqual(parser.parse("request higher"), .requestHigher)
        XCTAssertEqual(parser.parse("descend to one zero thousand"), .requestLower)
    }

    func testVectorsAndApproach() {
        XCTAssertEqual(parser.parse("request vectors for the approach"), .requestVectors)
        XCTAssertEqual(parser.parse("request the ILS approach"), .requestApproach)
    }

    func testWeatherAndRide() {
        XCTAssertEqual(parser.parse("any ride reports along the route"), .rideReport)
        XCTAssertEqual(parser.parse("destination weather please"), .destinationWeather)
    }

    func testCheckIn() {
        XCTAssertEqual(parser.parse("Denver Center, United 598 with you at three seven zero"), .checkIn)
    }

    func testReadbackCatchAll() {
        XCTAssertEqual(parser.parse("cleared to land runway three zero left"), .readback)
        XCTAssertEqual(parser.parse("roger"), .readback)
    }

    func testUnknown() {
        XCTAssertEqual(parser.parse("the weather is nice today"), .unknown)
    }
}
