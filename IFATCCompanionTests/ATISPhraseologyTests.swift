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

    func testBareVAReadsAsVisualApproachNotVolcanicAsh() {
        // In the compact approach list ("ILS 4R, VA 4L") a lone "VA" is the visual approach,
        // not the volcanic-ash weather code.
        let s = ATISPhraseology.spokenText("ILS 4R, VA 4L, DEP 9.").lowercased()
        XCTAssertTrue(s.contains("visual approach four left"), s)
        XCTAssertFalse(s.contains("volcanic ash"), s)
        // A vicinity-qualified VA is still the real weather group.
        XCTAssertEqual(spoken("VCVA"), "volcanic ash in the vicinity")
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

    func testTaxiwayWithCommaAfterKeywordSpellsPhonetically() {
        // Some feeds put a comma right after the keyword ("TWY, S"). The ident must still
        // spell phonetically ("Sierra") instead of being left as the bare letter "S".
        XCTAssertEqual(ATISPhraseology.spokenText("TWY, S CLSD").lowercased(), "taxiway sierra closed")
        XCTAssertEqual(ATISPhraseology.spokenText("TWYS, SB CLSD").lowercased(), "taxiways sierra bravo closed")
        XCTAssertEqual(ATISPhraseology.spokenText("TWY, B4 CLSD").lowercased(), "taxiway bravo four closed")
        // A comma with no following space is handled too.
        XCTAssertEqual(ATISPhraseology.spokenText("TWY,S CLSD").lowercased(), "taxiway sierra closed")
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

    // MARK: - Units written flush against their number

    func testUnitGluedToNumberIsSpoken() {
        // "155FT" gives the abbreviation pass no word boundary to anchor on, so without the
        // split the unit survives to be voiced letter by letter ("F T") after the digits.
        XCTAssertEqual(spoken("CRANE 155FT"), "crane one five five feet")
        XCTAssertEqual(spoken("30KT"), "three zero knots")
        XCTAssertEqual(spoken("5NM"), "five nautical miles")
        // The spaced form keeps reading the same way.
        XCTAssertEqual(spoken("CRANE 155 FT"), "crane one five five feet")
        let s = ATISPhraseology.spokenText("CTN CRANE 155FT AGL 5NM SW OF ARPT.").lowercased()
        XCTAssertTrue(s.contains("caution crane one five five feet a g l"), s)
        XCTAssertTrue(s.contains("five nautical miles southwest of airport"), s)
    }

    // MARK: - Runway keyword written flush against its designator

    func testRunwayKeywordGluedToDesignatorIsSpoken() {
        // "RY8R" has no word boundary inside it, so without the split neither the designator
        // rule nor the abbreviation pass can reach it and the keyword is voiced letter by
        // letter around the digits.
        XCTAssertEqual(spoken("RY8R"), "runway eight right")
        XCTAssertEqual(spoken("RWY8R"), "runway eight right")
        XCTAssertEqual(spoken("RWY22L"), "runway two two left")
        XCTAssertEqual(spoken("RWYS27L AND 27R"), "runways two seven left and two seven right")
        // A designator with no side, and the spaced form, keep reading the same way.
        XCTAssertEqual(spoken("RY8"), "runway eight")
        XCTAssertEqual(spoken("RY 8R"), "runway eight right")
        let s = ATISPhraseology.spokenText("ILS RY8R APCH IN USE. DEPG RWY26L.").lowercased()
        XCTAssertTrue(s.contains("i l s runway eight right approach in use"), s)
        XCTAssertTrue(s.contains("departing runway two six left"), s)
    }

    // MARK: - Instruction / advisory abbreviations

    func testExpandsInstructionAndAdvisoryAbbreviations() {
        // Abbreviations that appear in the NOTAM/instruction body must be read as full words,
        // not spelled or voiced as the raw token.
        XCTAssertEqual(spoken("ADVSD"), "advised")
        XCTAssertEqual(spoken("OTHRWSE"), "otherwise")
        XCTAssertEqual(spoken("INSTRCNS"), "instructions")
        XCTAssertEqual(spoken("READBACK"), "read back")
        let s = ATISPhraseology.spokenText(
            "EXPCT FULL LENGTH UNLESS ADVSD OTHRWSE. READBACK ALL HOLD SHORT INSTRCNS.").lowercased()
        XCTAssertTrue(s.contains("expect full length unless advised otherwise"), s)
        XCTAssertTrue(s.contains("read back all hold short instructions"), s)
    }

    func testExpandsExpectSpellings() {
        // Every published shortening of "expect" reads as the word, including the
        // E-less "XPECT".
        XCTAssertEqual(spoken("XPECT"), "expect")
        XCTAssertEqual(spoken("XPCT"), "expect")
        XCTAssertEqual(spoken("EXPCT"), "expect")
        XCTAssertEqual(spoken("EXP"), "expect")
        let s = ATISPhraseology.spokenText("XPECT ILS RWY 27L APCH.").lowercased()
        XCTAssertTrue(s.contains("expect i l s runway two seven left approach"), s)
    }

    func testStandaloneVicinityAbbreviationIsSpoken() {
        // A lone "VC" is the vicinity qualifier of a plain-language advisory, not the
        // weather-group prefix, and must not be voiced letter by letter.
        let withOf = ATISPhraseology.spokenText("PILOTS USE CTN FOR BIRD ACTIVITY VC OF ARPT.").lowercased()
        XCTAssertTrue(withOf.contains("bird activity vicinity of airport"), withOf)
        XCTAssertFalse(withOf.contains("v c"), withOf)
        // The "OF"-less form published by some feeds reads the same way.
        let withoutOf = ATISPhraseology.spokenText("BIRD ACTIVITY VC ARPT.").lowercased()
        XCTAssertTrue(withoutOf.contains("bird activity vicinity of airport"), withoutOf)
        // The weather-group prefix and the longer VC… abbreviations are untouched.
        XCTAssertEqual(spoken("VCSH"), "showers in the vicinity")
        XCTAssertEqual(spoken("VCTRS"), "vectors")
        XCTAssertEqual(spoken("VCNTY"), "vicinity")
    }

    func testExpandsSurfaceSurveillanceAcronyms() {
        // Bare acronyms must be spelled on the air, not voiced as an invented word.
        XCTAssertEqual(spoken("ATC"), "a t c")
        XCTAssertEqual(spoken("ADS-B"), "a d s b")
        XCTAssertEqual(spoken("ADSB"), "a d s b")
        XCTAssertEqual(spoken("ASDE-X"), "a s d e x")
        XCTAssertEqual(spoken("ASDEX"), "a s d e x")
    }

    // MARK: - Runway condition codes / flight service

    func testRunwayConditionCodeReads() {
        // FICON runway condition-code reports: "COND CODE" must read "condition code",
        // not the bare token, and the codes/time read digit-by-digit.
        let s = ATISPhraseology.spokenText("RWY 22L, COND CODE, 5 5 5 AT, 1630Z.").lowercased()
        XCTAssertTrue(s.contains("runway two two left"), s)
        XCTAssertTrue(s.contains("condition code"), s)
        XCTAssertTrue(s.contains("five five five"), s)
        XCTAssertTrue(s.contains("one six three zero zulu"), s)
        // The bare "COND CODE" token must be gone (expanded to "condition code").
        XCTAssertFalse(s.contains("cond code"), s)
    }

    func testSlipperyWhenWetConditionCode() {
        let s = ATISPhraseology.spokenText("RWY 28C, COND CODE, 3, 3, 3. SLIPPERY WHEN WET, AT, 1630Z.").lowercased()
        XCTAssertTrue(s.contains("runway two eight center"), s)
        XCTAssertTrue(s.contains("condition code"), s)
        XCTAssertTrue(s.contains("slippery when wet"), s)
    }

    func testFlightServiceStationAbbreviation() {
        let s = ATISPhraseology.spokenText("HAZD WX INFO FOR ORD AREA AVBL ON FSS.").lowercased()
        XCTAssertTrue(s.contains("weather information"), s)
        XCTAssertTrue(s.contains("available on flight service station"), s)
        XCTAssertFalse(s.contains(" fss"), s)
    }

    // MARK: - A full, real broadcast

    func testFullBostonBroadcastDecodes() {
        let raw = "BOS ATIS INFO L 1954Z. 10012KT 10SM SCT090 BKN250 21/12 A2988 "
            + "(TWO NINER EIGHT EIGHT). ILS 4R, VA 4L, DEP 9. RWY 33R IS APPROVED FOR TURN OFF. "
            + "LAHSO IN EFFECT ON RWY 4L. EXPCT FULL LENGTH UNLESS ADVSD OTHRWSE. READBACK ALL "
            + "HOLD SHORT INSTRCNS AND ASSIGNED ALTITUDES. ...ADVS YOU HAVE INFO L."
        let s = ATISPhraseology.spokenText(raw).lowercased()
        XCTAssertTrue(s.contains("information lima"), s)
        XCTAssertTrue(s.contains("one niner five four zulu"), s)
        XCTAssertTrue(s.contains("wind one zero zero at one two"), s)
        XCTAssertTrue(s.contains("visibility one zero"), s)
        XCTAssertTrue(s.contains("altimeter two niner eight eight"), s)
        XCTAssertTrue(s.contains("i l s four right"), s)
        XCTAssertTrue(s.contains("visual approach four left"), s)
        XCTAssertFalse(s.contains("volcanic ash"), s)
        XCTAssertTrue(s.contains("land and hold short operations"), s)
        XCTAssertTrue(s.contains("expect full length unless advised otherwise"), s)
        XCTAssertTrue(s.contains("read back all hold short instructions"), s)
        XCTAssertTrue(s.contains("advise you have information lima"), s)
    }

    func testFullNewarkBroadcastDecodes() {
        let raw = "EWR ATIS INFO V 1851Z. 11007KT 10SM SCT065 SCT180 BKN250 29/12 A2985 "
            + "(TWO NINER EIGHT FIVE) RMK AO2 SLP106 T02890122. ILS RWY 4R APCH IN USE. "
            + "DEPARTING RWY 4L. ASDE-X IS ONLY AVAILABLE FOR ADS-B EQUIPPED AIRCRAFT AND "
            + "VEHICLES. RWY 22R GLIDESLOPE OTS, RY 4L GS OTS, RY 4L DME OTS. RY 4 DEPARTURES, "
            + "USE UPPER ANTENNA FOR ATC COMMUNICATIONS. READBACK ALL RUNWAY HOLD SHORT "
            + "INSTRUCTIONS AND ASSIGNED ALT. ...ADVS YOU HAVE INFO V."
        let s = ATISPhraseology.spokenText(raw).lowercased()
        XCTAssertTrue(s.contains("information victor"), s)
        XCTAssertTrue(s.contains("wind one one zero at seven"), s)
        XCTAssertTrue(s.contains("altimeter two niner eight five"), s)
        // The coded remarks group is dropped, not spoken.
        XCTAssertFalse(s.contains("slp"), s)
        XCTAssertFalse(s.contains("a o 2"), s)
        XCTAssertTrue(s.contains("i l s runway four right approach in use"), s)
        XCTAssertTrue(s.contains("departing runway four left"), s)
        XCTAssertTrue(s.contains("a s d e x is only available for a d s b equipped"), s)
        XCTAssertTrue(s.contains("glideslope out of service"), s)
        XCTAssertTrue(s.contains("d m e out of service"), s)
        XCTAssertTrue(s.contains("for a t c communications"), s)
        XCTAssertTrue(s.contains("read back all runway hold short instructions"), s)
        XCTAssertTrue(s.contains("assigned altitude"), s)
        XCTAssertTrue(s.contains("advise you have information victor"), s)
    }

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

    func testFullOHareBroadcastDecodes() {
        // Chicago O'Hare D-ATIS with the FICON runway condition-code block, ILS component
        // outages, the flight-service hazardous-weather advisory, and the metering handoff.
        let raw = "ORD ATIS INFO U 2151Z. 06015KT 10SM -TSRA SCT032CB BKN100 OVC250 24/21 A2974 "
            + "(TWO NINER SEVEN FOUR) RMK AO2 PK WND 06056/2138 RAB48 TSB36 SLP064 CONS LTGICCG "
            + "OHD TS OHD MOV S P0000 T02390206. ARR EXP VECTORS ILS RWY 10C APCH. DEPS EXP RWYS 9C. "
            + "RWY 22R LOC OTS, RWY 28L GS OTS, RWY 9L IM OTS, RWY 9L PAPI OTS. "
            + "RWY 22L, COND CODE, 5 5 5 AT, 1630Z, RWY 28C, COND CODE, 3, 3, 3. SLIPPERY WHEN WET, "
            + "AT, 1630Z. PILOTS USE CTN FOR BIRD ACTIVITY IN THE VICINITY OF THE ARPT. HAZD WX "
            + "INFO FOR ORD AREA AVBL ON FSS. READBACK ALL RWY HOLD SHORT INSTRUCTIONS. WHEN READY "
            + "TO TAXI CONTACT GND METERING ON FREQ 121.67. ...ADVS YOU HAVE INFO U."
        let s = ATISPhraseology.spokenText(raw).lowercased()
        XCTAssertTrue(s.contains("information uniform"), s)
        XCTAssertTrue(s.contains("wind zero six zero at one five"), s)
        XCTAssertTrue(s.contains("thunderstorm with light rain"), s)
        XCTAssertTrue(s.contains("altimeter two niner seven four"), s)
        // The coded remarks group is dropped, not spoken.
        XCTAssertFalse(s.contains("slp"), s)
        XCTAssertFalse(s.contains("ltgiccg"), s)
        XCTAssertFalse(s.contains("p0000"), s)
        XCTAssertTrue(s.contains("i l s runway one zero center approach"), s)
        XCTAssertTrue(s.contains("localizer out of service"), s)
        XCTAssertTrue(s.contains("glideslope out of service"), s)
        XCTAssertTrue(s.contains("inner marker out of service"), s)
        // Runway condition-code block reads as full words + digit-by-digit codes.
        XCTAssertTrue(s.contains("condition code"), s)
        XCTAssertTrue(s.contains("five five five"), s)
        XCTAssertTrue(s.contains("slippery when wet"), s)
        XCTAssertFalse(s.contains("cond code"), s)
        XCTAssertTrue(s.contains("use caution for bird activity"), s)
        XCTAssertTrue(s.contains("available on flight service station"), s)
        XCTAssertTrue(s.contains("read back all runway hold short instructions"), s)
        XCTAssertTrue(s.contains("contact ground metering on frequency one two one point six seven"), s)
        XCTAssertTrue(s.contains("advise you have information uniform"), s)
    }

    func testFullOHareInfoGBroadcastDecodes() {
        // A second Chicago O'Hare D-ATIS (INFO G) exercising the simultaneous-approach and
        // parallel-departure wording: expect-to-intercept the ILS final course, "ATTN
        // PILOTS", read-back direction of turns, the field-condition block, and the long
        // ILS-component / PAPI outage list.
        let raw = "ORD ATIS INFO G 0051Z. 13010G19KT 10SM FEW030 OVC050 23/15 A2983 "
            + "(TWO NINER EIGHT THREE) RMK AO2 SLP097 T02280150. ARR EXP VECTORS ILS RWY 9L "
            + "APCH, ILS RWY 10C APCH, VISUAL APCH RWY 10R. PILOTS EXP 2 INTCP THE ILS Y RY "
            + "10R FNA CRS. SIMUL APCHS IN USE. DEPS EXP RWYS 9C FROM FF 9200 FT AVL, 10L FROM "
            + "DD 10093 FT AVBL. ATTN PILOTS. SIMUL PARL DEPS IN USE. EXP TO INITIALLY FLY RWY "
            + "HDG ON DEP. PILOTS MUST READ BACK DRCTN OF TURNS BY ATC. RWY 4L, 22R CLSD. RWY "
            + "28L GS OTS, RWY 9L IM OTS, RWY 9C IM OTS, RWY 27R PAPI OTS. RWY 10C, COND CODE, "
            + "3, 3, 3. SLIPPERY WHEN WET, AT, 0141Z, ALL RWYS, COND CODE, 5, 5, 5 AT 0140Z. "
            + "PILOTS USE CTN FOR BIRD ACTIVITY IN THE VICINITY OF THE ARPT. HAZD WX INFO FOR "
            + "ORD AREA AVBL ON FSS. USE CTN FOR PERSONNEL AND EQUIP AT NUMEROUS SITES ON THE "
            + "FIELD. READBACK ALL RWY HOLD SHORT INSTRUCTIONS. WHEN READY TO TAXI CONTACT GND "
            + "METERING ON FREQ 121.67. ...ADVS YOU HAVE INFO G."
        let s = ATISPhraseology.spokenText(raw).lowercased()
        XCTAssertTrue(s.contains("information golf"), s)
        XCTAssertTrue(s.contains("zero zero five one zulu"), s)
        XCTAssertTrue(s.contains("wind one three zero at one zero gusts one niner"), s)
        XCTAssertTrue(s.contains("visibility one zero"), s)
        XCTAssertTrue(s.contains("few clouds at three thousand"), s)
        XCTAssertTrue(s.contains("five thousand overcast"), s)
        XCTAssertTrue(s.contains("temperature two three, dewpoint one five"), s)
        XCTAssertTrue(s.contains("altimeter two niner eight three"), s)
        // The coded remarks group is dropped, not spoken.
        XCTAssertFalse(s.contains("slp"), s)
        XCTAssertFalse(s.contains("a o 2"), s)
        // Arrival / approach wording.
        XCTAssertTrue(s.contains("i l s runway niner left approach"), s)
        XCTAssertTrue(s.contains("i l s runway one zero center approach"), s)
        XCTAssertTrue(s.contains("visual approach runway one zero right"), s)
        // Expect-to-intercept the ILS Yankee final course (the newly-added INTCP/FNA/CRS).
        XCTAssertTrue(s.contains("intercept the i l s yankee runway one zero right final course"), s)
        XCTAssertTrue(s.contains("simultaneous approaches in use"), s)
        // Departure / metering wording.
        XCTAssertTrue(s.contains("departures expect runways niner center"), s)
        XCTAssertTrue(s.contains("attention pilots"), s)            // ATTN
        XCTAssertTrue(s.contains("simultaneous parallel departures in use"), s)
        XCTAssertTrue(s.contains("initially fly runway heading on departure"), s)
        XCTAssertTrue(s.contains("read back direction of turns by a t c"), s)   // DRCTN
        // Closures and component outages.
        XCTAssertTrue(s.contains("runway four left, two two right closed"), s)
        XCTAssertTrue(s.contains("glideslope out of service"), s)
        XCTAssertTrue(s.contains("inner marker out of service"), s)
        // Field-condition block reads as full words + digit-by-digit codes.
        XCTAssertTrue(s.contains("condition code"), s)
        XCTAssertTrue(s.contains("slippery when wet"), s)
        XCTAssertFalse(s.contains("cond code"), s)
        // Advisory tail.
        XCTAssertTrue(s.contains("use caution for bird activity"), s)
        XCTAssertTrue(s.contains("available on flight service station"), s)
        XCTAssertTrue(s.contains("use caution for personnel and equipment"), s)
        XCTAssertTrue(s.contains("read back all runway hold short instructions"), s)
        XCTAssertTrue(s.contains("contact ground metering on frequency one two one point six seven"), s)
        XCTAssertTrue(s.contains("advise you have information golf"), s)
    }

    // Convenience: spoken text for a bare coded fragment, trimmed and case-folded so the
    // assertions read cleanly.
    private func spoken(_ raw: String) -> String {
        ATISPhraseology.spokenText(raw).lowercased()
    }
}
