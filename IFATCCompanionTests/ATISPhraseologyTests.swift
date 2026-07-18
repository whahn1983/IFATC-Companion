import XCTest
@testable import IFATCCompanion

/// Tests the deterministic ATIS text→speech normalizer: abbreviation expansion,
/// digit-by-digit reading, Zulu time, altimeter and runway handling, and the
/// phonetic information letter.
final class ATISPhraseologyTests: XCTestCase {

    func testPhoneticLetter() {
        XCTAssertEqual(ATISPhraseology.phoneticLetter("A"), "Alpha")
        XCTAssertEqual(ATISPhraseology.phoneticLetter("l"), "Lima")   // case-insensitive
        XCTAssertEqual(ATISPhraseology.phoneticLetter("Z"), "Zulu")
    }

    // The normalizer leaves source words in their original (upper) case but emits
    // lowercase digit/expansion words — all fine for TTS. Assertions compare
    // case-insensitively so they don't depend on that casing detail.

    func testExpandsRunwayAndILS() {
        let spoken = ATISPhraseology.spokenText("ILS RWY 24R, 25L APCHS IN USE.").lowercased()
        XCTAssertTrue(spoken.contains("i l s"), spoken)
        XCTAssertTrue(spoken.contains("runway"), spoken)
        XCTAssertTrue(spoken.contains("two four right"), spoken)
        XCTAssertTrue(spoken.contains("two five left"), spoken)
        XCTAssertTrue(spoken.contains("approaches"), spoken)
    }

    func testSpeaksDigitsIndividually() {
        let spoken = ATISPhraseology.spokenText("WIND 250 AT 8. ALTIMETER 2992.").lowercased()
        XCTAssertTrue(spoken.contains("two five zero"), spoken)
        XCTAssertTrue(spoken.contains("at eight"), spoken)
        XCTAssertTrue(spoken.contains("two niner niner two"), spoken)
    }

    func testZuluTime() {
        let spoken = ATISPhraseology.spokenText("INFO ALPHA. 1953Z. WIND CALM.").lowercased()
        XCTAssertTrue(spoken.contains("one niner five three zulu"), spoken)
        // The info word passes through spoken fine.
        XCTAssertTrue(spoken.contains("alpha"), spoken)
        // "INFO" is expanded to "information".
        XCTAssertTrue(spoken.contains("information"), spoken)
    }

    func testCompactAltimeterForm() {
        let spoken = ATISPhraseology.spokenText("TEMP 22 DEWPOINT 12 A2992.").lowercased()
        XCTAssertTrue(spoken.contains("altimeter two niner niner two"), spoken)
        XCTAssertTrue(spoken.contains("temperature"), spoken)
    }

    func testICAODigitWords() {
        let spoken = ATISPhraseology.spokenText("WIND 330 AT 15.", icao: true).lowercased()
        XCTAssertTrue(spoken.contains("tree tree zero"), spoken)   // 3 -> "tree" under ICAO
        XCTAssertTrue(spoken.contains("one fife"), spoken)         // 5 -> "fife" under ICAO
    }

    func testDisplayTextIsVerbatimButTrimmed() {
        let raw = "  LOS ANGELES INTL   INFORMATION ALPHA.  "
        XCTAssertEqual(ATISPhraseology.displayText(raw), "LOS ANGELES INTL INFORMATION ALPHA.")
    }
}
