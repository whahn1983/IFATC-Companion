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

    func testReciprocalRunway() {
        XCTAssertEqual(Phonetic.reciprocalRunway("24L"), "6R")
        XCTAssertEqual(Phonetic.reciprocalRunway("06R"), "24L")
        XCTAssertEqual(Phonetic.reciprocalRunway("36"), "18")
        XCTAssertEqual(Phonetic.reciprocalRunway("09"), "27")
        XCTAssertEqual(Phonetic.reciprocalRunway("13C"), "31C")
        XCTAssertNil(Phonetic.reciprocalRunway("ALPHA"))
    }

    func testRunwayPairDisplayIsLowerNumberFirst() {
        // Either end resolves to the same lower-number-first designation.
        XCTAssertEqual(Phonetic.runwayPairDisplay("24L"), "6R-24L")
        XCTAssertEqual(Phonetic.runwayPairDisplay("06R"), "6R-24L")
        XCTAssertEqual(Phonetic.runwayPairDisplay("36"), "18-36")
        XCTAssertEqual(Phonetic.runwayPairDisplay("09"), "9-27")
        XCTAssertEqual(Phonetic.runwayPairDisplay("13C"), "13C-31C")
    }

    func testRunwayPairSpokenNamesBothDirections() {
        // The example from the request: "hold short of runway 6R-24L" is spoken
        // "... six right two four left".
        XCTAssertEqual(Phonetic.runwayPairSpoken("24L"), "six right two four left")
        XCTAssertEqual(Phonetic.runwayPairSpoken("06R"), "six right two four left")
        XCTAssertEqual(Phonetic.runwayPairSpoken("36"), "one eight three six")
        XCTAssertEqual(Phonetic.runwayPairSpoken("09"), "niner two seven")
        // ICAO digits carry through ("niner"/"tree" etc.).
        XCTAssertEqual(Phonetic.runwayPairSpoken("31", icao: true), "one tree tree one")
    }

    func testRunwayPairFallsBackWhenNoRunwayNumber() {
        XCTAssertEqual(Phonetic.runwayPairDisplay("ALPHA"), "ALPHA")
        XCTAssertEqual(Phonetic.runwayPairSpoken("ALPHA"), Phonetic.runway("ALPHA"))
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
