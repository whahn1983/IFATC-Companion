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

    // MARK: - Coded wind

    func testCodedWind() {
        XCTAssertEqual(spoken("25012KT"), "wind two five zero at one two")
        XCTAssertEqual(spoken("08004KT"), "wind zero eight zero at four")   // leading zero on speed dropped
        XCTAssertEqual(spoken("00000KT"), "wind calm")
        XCTAssertEqual(spoken("VRB05KT"), "wind variable at five")
    }

    func testCodedWindGustAndVariableRange() {
        XCTAssertEqual(spoken("28027G40KT"), "wind two eight zero at two seven gusts four zero")
        XCTAssertEqual(spoken("34020G35KT 340V020"),
                       "wind three four zero at two zero gusts three five variable between three four zero and zero two zero")
    }

    // MARK: - Coded visibility

    func testWholeVisibility() {
        XCTAssertEqual(spoken("10SM"), "visibility one zero")
        XCTAssertEqual(spoken("8SM"), "visibility eight")
    }

    func testFractionalVisibility() {
        XCTAssertEqual(spoken("1/2SM"), "visibility one half")
        XCTAssertEqual(spoken("3/4SM"), "visibility three quarters")
        XCTAssertEqual(spoken("2 1/2SM"), "visibility two and one half")
        XCTAssertEqual(spoken("P6SM"), "visibility more than six")
        XCTAssertEqual(spoken("M1/4SM"), "visibility less than one quarter")
    }

    // MARK: - Clouds

    func testCloudLayers() {
        XCTAssertEqual(spoken("FEW015"), "few clouds at one thousand five hundred")
        XCTAssertEqual(spoken("OVC008"), "eight hundred overcast")
        XCTAssertEqual(spoken("BKN250"), "two five thousand broken")
        XCTAssertEqual(spoken("SCT016"), "one thousand six hundred scattered")
        XCTAssertEqual(spoken("BKN044CB"), "four thousand four hundred broken cumulonimbus")
        XCTAssertEqual(spoken("VV004"), "indefinite ceiling four hundred")
        XCTAssertEqual(spoken("CLR"), "clear below one two thousand")
    }

    // MARK: - Temperature / dewpoint

    func testTemperatureDewpoint() {
        XCTAssertEqual(spoken("19/13"), "temperature one niner, dewpoint one three")
        XCTAssertEqual(spoken("07/M02"), "temperature seven, dewpoint minus two")
        XCTAssertEqual(spoken("04/-09"), "temperature four, dewpoint minus niner")   // literal minus
        XCTAssertEqual(spoken("M05/M10"), "temperature minus five, dewpoint minus one zero")
        XCTAssertEqual(spoken("01/00"), "temperature one, dewpoint zero")
    }

    // MARK: - Present weather phenomena

    func testWeatherPhenomena() {
        XCTAssertEqual(spoken("-RA"), "light rain")
        XCTAssertEqual(spoken("+SN"), "heavy snow")
        XCTAssertEqual(spoken("BR"), "mist")
        XCTAssertEqual(spoken("FZFG"), "freezing fog")
        XCTAssertEqual(spoken("BCFG"), "patches of fog")
        XCTAssertEqual(spoken("BLSN"), "blowing snow")
        XCTAssertEqual(spoken("-SHRA"), "light rain showers")
        XCTAssertEqual(spoken("VCSH"), "showers in the vicinity")
    }

    func testThunderstormPhrasing() {
        XCTAssertEqual(spoken("TS"), "thunderstorm")
        XCTAssertEqual(spoken("TSRA"), "thunderstorm with rain")
        XCTAssertEqual(spoken("-TSRA"), "thunderstorm with light rain")
        XCTAssertEqual(spoken("+TSRA"), "thunderstorm with heavy rain")
        XCTAssertEqual(spoken("VCTS"), "thunderstorm in the vicinity")
    }

    func testWeatherDecoderLeavesNonWeatherWordsAlone() {
        // Plain ATIS words that happen to be all-caps are never mistaken for weather.
        let s = ATISPhraseology.spokenText("ILS RWY 24R APCH IN USE. GS OTS.").lowercased()
        XCTAssertTrue(s.contains("glideslope out of service"), s)   // "GS" here is glideslope, not small hail
        XCTAssertFalse(s.contains("small hail"), s)
    }

    // MARK: - Altimeter, time, remarks, info letter

    func testAltimeterDropsSpelledReadback() {
        // The parenthetical readback the FAA appends must not be spoken twice.
        let s = spoken("A2992 (TWO NINER NINER TWO)")
        XCTAssertEqual(s, "altimeter two niner niner two")
    }

    func testDayStampedObservationTime() {
        XCTAssertEqual(spoken("042252"), "two two five two zulu")   // day 04 dropped, time spoken
        XCTAssertEqual(spoken("1953Z"), "one niner five three zulu")
    }

    func testRemarksGroupIsDropped() {
        let s = ATISPhraseology.spokenText("A3017 (THREE ZERO ONE SEVEN) RMK AO2 SLP224 T00331122. ARR").lowercased()
        XCTAssertTrue(s.contains("altimeter three zero one seven"), s)
        XCTAssertFalse(s.contains("slp"), s)
        XCTAssertFalse(s.contains("a o 2"), s)
        XCTAssertTrue(s.contains("arr"), s)
    }

    func testInformationLetterBecomesPhonetic() {
        let s = ATISPhraseology.spokenText("ATL ATIS INFO S. ...ADVS YOU HAVE INFO S.").lowercased()
        XCTAssertTrue(s.contains("information sierra"), s)
        XCTAssertTrue(s.contains("advise you have information sierra"), s)
    }

    // MARK: - RVR, frequency, approach & taxiway phonetics

    func testRVR() {
        XCTAssertEqual(spoken("R28L/2400FT"), "runway two eight left r v r two thousand four hundred")
        XCTAssertEqual(spoken("R06/2000V3000FT"),
                       "runway zero six r v r variable two thousand to three thousand")
        XCTAssertEqual(spoken("R28L/P6000FT"), "runway two eight left r v r more than six thousand")
    }

    func testEmbeddedFrequency() {
        XCTAssertEqual(spoken("127.05"), "one two seven point zero five")
        XCTAssertEqual(spoken("121.67"), "one two one point six seven")
    }

    func testApproachVariantAndTaxiwayLetters() {
        XCTAssertEqual(spoken("RNAV Z"), "r nav zulu")
        XCTAssertEqual(ATISPhraseology.spokenText("ILS Z RWY 4L").lowercased(), "i l s zulu runway four left")
        XCTAssertEqual(ATISPhraseology.spokenText("TWY B CLSD").lowercased(), "taxiway bravo closed")
    }

    func testMultiLetterTaxiwayIsSpelledPhonetically() {
        // A two-letter taxiway ident must read phonetically ("Sierra Bravo"), not as the
        // bare letters the synthesizer would otherwise voice as "S B".
        XCTAssertEqual(ATISPhraseology.spokenText("TWY SB CLSD").lowercased(), "taxiway sierra bravo closed")
        // A trailing number stays part of the ident ("Bravo four"), and the following
        // abbreviation word is left intact.
        XCTAssertEqual(ATISPhraseology.spokenText("TWY B4 CLSD").lowercased(), "taxiway bravo four closed")
    }

    // MARK: - Hold short / hazard abbreviations

    func testHoldShortAbbreviation() {
        // Both the bare "HS" and the slashed "H/S" expand — the slash blocks the "HS"
        // word boundary, so "H/S" needs its own table entry.
        XCTAssertEqual(spoken("HS"), "hold short")
        XCTAssertEqual(spoken("H/S"), "hold short")
        let s = ATISPhraseology.spokenText("TWY A H/S RWY 10L.").lowercased()
        XCTAssertTrue(s.contains("hold short"), s)
        XCTAssertFalse(s.contains("h/s"), s)
    }

    func testHazardAbbreviation() {
        XCTAssertEqual(spoken("HAZD"), "hazard")
        XCTAssertEqual(spoken("HAZDS"), "hazards")
        let s = ATISPhraseology.spokenText("BIRD HAZD INVOF ARPT.").lowercased()
        XCTAssertTrue(s.contains("hazard"), s)
    }

    // MARK: - A full, real broadcast

    func testFullRealBroadcastDecodes() {
        let raw = "SFO ATIS INFO A 100056. 28027G40KT 8SM SCT016 BKN024 BKN070 12/09 "
            + "A2978 (TWO NINER SEVEN EIGHT). LDG RWY 28L, 28R. ...ADVS YOU HAVE INFO A."
        let s = ATISPhraseology.spokenText(raw).lowercased()
        XCTAssertTrue(s.contains("information alpha"), s)
        XCTAssertTrue(s.contains("wind two eight zero at two seven gusts four zero"), s)
        XCTAssertTrue(s.contains("visibility eight"), s)
        XCTAssertTrue(s.contains("one thousand six hundred scattered"), s)
        XCTAssertTrue(s.contains("temperature one two, dewpoint niner"), s)
        XCTAssertTrue(s.contains("altimeter two niner seven eight"), s)
        XCTAssertTrue(s.contains("landing runway two eight left"), s)
        XCTAssertTrue(s.contains("advise you have information alpha"), s)
        // The spelled altimeter readback isn't duplicated.
        XCTAssertEqual(s.components(separatedBy: "two niner seven eight").count - 1, 1, s)
    }

    // Convenience: spoken text for a bare coded fragment, trimmed and case-folded so the
    // assertions read cleanly.
    private func spoken(_ raw: String) -> String {
        ATISPhraseology.spokenText(raw).lowercased()
    }
}
