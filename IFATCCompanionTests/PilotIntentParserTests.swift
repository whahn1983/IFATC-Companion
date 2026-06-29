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

    func testDepartureGroundRequests() {
        XCTAssertEqual(parser.parse("request pushback"), .requestPushback)
        XCTAssertEqual(parser.parse("Ground, United 598, ready for push"), .requestPushback)
        XCTAssertEqual(parser.parse("request start-up"), .requestEngineStart)
        XCTAssertEqual(parser.parse("request engine start"), .requestEngineStart)
        XCTAssertEqual(parser.parse("request IFR clearance to Denver"), .requestClearance)
        XCTAssertEqual(parser.parse("request taxi"), .requestTaxi)
        XCTAssertEqual(parser.parse("holding short runway two seven, ready for departure"), .readyForDeparture)
        XCTAssertEqual(parser.parse("request takeoff"), .requestTakeoff)
    }

    func testTaxiReadbackIsNotATaxiRequest() {
        // Reading back a taxi clearance must remain a readback, not a new request.
        XCTAssertEqual(parser.parse("taxi to runway two seven via alpha"), .readback)
    }

    func testUnknown() {
        XCTAssertEqual(parser.parse("the weather is nice today"), .unknown)
    }
}
