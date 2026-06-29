import XCTest
@testable import IFATCCompanion

final class AirlineCallsignTests: XCTestCase {

    func testParseIATATwoLetterPrefix() {
        let parsed = AirlineDatabase.parse("UA598")
        XCTAssertEqual(parsed?.telephony, "United")
        XCTAssertEqual(parsed?.flightNumber, "598")
        XCTAssertEqual(parsed?.designator, "UA")
    }

    func testParseICAOThreeLetterPrefix() {
        let parsed = AirlineDatabase.parse("UAL598")
        XCTAssertEqual(parsed?.telephony, "United")
        XCTAssertEqual(parsed?.flightNumber, "598")
        XCTAssertEqual(parsed?.designator, "UAL")
    }

    func testParseHandlesLowercaseHyphenAndSpaces() {
        XCTAssertEqual(AirlineDatabase.parse("ba-2490")?.telephony, "Speedbird")
        XCTAssertEqual(AirlineDatabase.parse("dlh 400")?.telephony, "Lufthansa")
        XCTAssertEqual(AirlineDatabase.parse("dlh 400")?.flightNumber, "400")
    }

    func testTailNumberIsNotParsedAsAirline() {
        XCTAssertNil(AirlineDatabase.parse("N12AB"))
        XCTAssertNil(AirlineDatabase.parse("G-ABCD"))
    }

    func testUnknownDesignatorReturnsNil() {
        XCTAssertNil(AirlineDatabase.parse("ZZ123"))
    }

    func testPureNumberOrPureLettersReturnNil() {
        XCTAssertNil(AirlineDatabase.parse("598"))
        XCTAssertNil(AirlineDatabase.parse("UAL"))
    }

    func testCallNameResolvesBothDesignatorStyles() {
        XCTAssertEqual(AirlineDatabase.callName(for: "DAL"), "Delta")
        XCTAssertEqual(AirlineDatabase.callName(for: "DL"), "Delta")
        XCTAssertEqual(AirlineDatabase.callName(for: "ek"), "Emirates")
        XCTAssertNil(AirlineDatabase.callName(for: "United"))
    }

    func testSpokenCallsignThroughParsedAirline() {
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        let parsed = AirlineDatabase.parse("UA598")!
        XCTAssertEqual(engine.spokenCallsign(airline: parsed.telephony,
                                             flightNumber: parsed.flightNumber),
                       "United five niner eight")
    }

    func testEngineResolvesDesignatorToTelephony() {
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        XCTAssertEqual(engine.spokenCallsign(airline: "DLH", flightNumber: "400"),
                       "Lufthansa four zero zero")
        XCTAssertEqual(engine.displayCallsign(airline: "DLH", flightNumber: "400"),
                       "Lufthansa 400")
    }
}
