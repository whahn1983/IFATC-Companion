import XCTest
@testable import IFATCCompanion

final class PhraseologyTests: XCTestCase {

    func testDigitSpelling() {
        XCTAssertEqual(Phonetic.spellDigits("4271"), "four two seven one")
        XCTAssertEqual(Phonetic.spellDigits("9"), "niner")
    }

    func testAltitudePronunciation() {
        XCTAssertEqual(Phonetic.altitude(10000), "one zero thousand")
        XCTAssertEqual(Phonetic.altitude(37000), "flight level three seven zero")
        XCTAssertEqual(Phonetic.altitude(2500), "two thousand five hundred")
        XCTAssertEqual(Phonetic.altitude(5000), "five thousand")
        XCTAssertEqual(Phonetic.altitude(11000), "one one thousand")
    }

    func testHeadingPronunciation() {
        XCTAssertEqual(Phonetic.heading(270), "two seven zero")
        XCTAssertEqual(Phonetic.heading(90), "zero niner zero")
        XCTAssertEqual(Phonetic.heading(360), "zero zero zero")
    }

    func testFrequencyPronunciation() {
        XCTAssertEqual(Phonetic.frequency(118.300), "one one eight point three")
        XCTAssertEqual(Phonetic.frequency(124.875), "one two four point eight seven five")
    }

    func testRunwayPronunciation() {
        XCTAssertEqual(Phonetic.runway("17R"), "one seven right")
        XCTAssertEqual(Phonetic.runway("04L"), "zero four left")
        XCTAssertEqual(Phonetic.runway("9"), "zero niner")
        XCTAssertEqual(Phonetic.runway("30C"), "three zero center")
    }

    func testWindPronunciation() {
        XCTAssertEqual(Phonetic.wind(direction: 330, speed: 12), "wind three three zero at one two")
        XCTAssertEqual(Phonetic.wind(direction: 0, speed: 0), "wind calm")
    }

    func testSquawkPronunciation() {
        XCTAssertEqual(Phonetic.squawk("4271"), "squawk four two seven one")
    }

    func testAltimeterPronunciation() {
        XCTAssertEqual(Phonetic.altimeter(30.12), "three zero one two")
    }

    func testCallsignIndividualStyle() {
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        XCTAssertEqual(engine.spokenCallsign(airline: "United", flightNumber: "598"),
                       "United five niner eight")
    }

    func testCallsignGroupedStyle() {
        let engine = PhraseologyEngine(digitStyle: .grouped, mode: .faa)
        XCTAssertEqual(engine.spokenCallsign(airline: "American", flightNumber: "1234"),
                       "American twelve thirty four")
    }

    func testCallsignFallbackTailNumber() {
        let engine = PhraseologyEngine(digitStyle: .grouped, mode: .faa)
        XCTAssertEqual(engine.spokenCallsign(airline: "", flightNumber: "", fallback: "N12AB"),
                       "November one two Alpha Bravo")
    }

    func testAltitudeDisplayFormatting() {
        let engine = PhraseologyEngine()
        XCTAssertEqual(engine.formatAltDisplay(37000), "FL370")
        XCTAssertEqual(engine.formatAltDisplay(5000), "5,000")
    }
}
