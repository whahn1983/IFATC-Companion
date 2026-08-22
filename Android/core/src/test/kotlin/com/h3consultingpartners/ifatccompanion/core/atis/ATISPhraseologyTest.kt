package com.h3consultingpartners.ifatccompanion.core.atis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The spoken-ATIS renderer turns coded observation groups into the way an ATIS voice
 * reads them. Every rule is deterministic, so each one is pinned here — a regression
 * would be heard by the pilot as "R V R" gibberish or a doubled altimeter readback
 * rather than caught by the compiler.
 */
class ATISPhraseologyTest {

    private fun spoken(raw: String, icao: Boolean = false) = ATISPhraseology.spokenText(raw, icao)

    // region Coded observation groups

    @Test
    fun windGroupsReadAsOnTheAir() {
        assertTrue(spoken("WIND 25012KT").contains("wind two five zero at one two"))
        assertTrue(spoken("WIND 00000KT").contains("wind calm"))
        assertTrue(spoken("WIND VRB05KT").contains("wind variable at five"))
        assertTrue(spoken("WIND 25012G30KT").contains("wind two five zero at one two gusts three zero"))
        assertTrue(spoken("WIND 25012MPS").contains("meters per second"))
    }

    @Test
    fun aVariableDirectionRangeReadsAsARange() {
        assertTrue(spoken("210V280").contains("variable between two one zero and two eight zero"))
    }

    @Test
    fun theAltimeterIsSpokenDigitByDigit() {
        assertTrue(spoken("A2992").contains("altimeter two niner niner two"))
    }

    @Test
    fun qnhIsSpelledOut() {
        assertTrue(spoken("Q1013").contains("Q N H one zero one three"))
    }

    /**
     * The FAA appends a spelled-out readback after the altimeter. The coded group is
     * decoded already, so speaking the parenthetical too would say it twice.
     */
    @Test
    fun theSpelledOutAltimeterReadbackIsStripped() {
        val out = spoken("A2992 (TWO NINER NINER TWO)")
        assertEquals(1, Regex("niner niner two").findAll(out).count())
    }

    /** A parenthetical that isn't a number readback is preserved. */
    @Test
    fun aNonNumericParentheticalSurvives() {
        assertTrue(spoken("RNAV (GPS) RWY 16C APCH").contains("G P S"))
    }

    @Test
    fun visibilityFormsAllRead() {
        assertTrue(spoken("10SM").contains("visibility one zero"))
        assertTrue(spoken("P6SM").contains("visibility more than six"))
        assertTrue(spoken("1/2SM").contains("visibility one half"))
        assertTrue(spoken("M1/4SM").contains("visibility less than one quarter"))
        assertTrue(spoken("1 1/2SM").contains("visibility one and one half"))
        assertTrue(spoken("5/8SM").contains("visibility five eighths"))
    }

    @Test
    fun temperatureAndDewpointReadWithMinusForNegatives() {
        assertTrue(spoken("07/M02").contains("temperature seven, dewpoint minus two"))
        assertTrue(spoken("19/13").contains("temperature one niner, dewpoint one three"))
        assertTrue(spoken("04/-09").contains("dewpoint minus niner"))
    }

    @Test
    fun skyCoverReadsInTheAtisVoiceOrder() {
        assertTrue(spoken("FEW015").contains("few clouds at one thousand five hundred"))
        assertTrue(spoken("OVC008").contains("eight hundred overcast"))
        assertTrue(spoken("SCT250").contains("two five thousand scattered"))
        assertTrue(spoken("VV002").contains("indefinite ceiling two hundred"))
        assertTrue(spoken("BKN020CB").contains("two thousand broken cumulonimbus"))
    }

    @Test
    fun rvrReadsWithItsQualifiers() {
        assertTrue(spoken("R28L/2400FT").contains("runway two eight left R V R two thousand four hundred"))
        assertTrue(spoken("R28L/P6000FT").contains("more than"))
        assertTrue(spoken("R06/2000V3000FT").contains("variable"))
    }

    @Test
    fun theObservationTimeReadsAsZulu() {
        assertTrue(spoken("2352Z").contains("two three five two zulu"))
        assertTrue(spoken("SPECI 042252 OBS").contains("two two five two zulu"))
    }

    /** A 6-digit run that isn't a valid day stamp is left for the digit pass. */
    @Test
    fun anInvalidDayStampIsNotATime() {
        assertFalse(spoken("SQUAWK 992599").contains("zulu"))
    }

    // endregion

    // region Weather phenomena

    @Test
    fun weatherGroupsDecode() {
        assertEquals("light rain", ATISPhraseology.decodeWeather("-RA"))
        assertEquals("thunderstorm with heavy rain", ATISPhraseology.decodeWeather("+TSRA"))
        assertEquals("showers in the vicinity", ATISPhraseology.decodeWeather("VCSH"))
        assertEquals("freezing fog", ATISPhraseology.decodeWeather("FZFG"))
        assertEquals("mist", ATISPhraseology.decodeWeather("BR"))
        assertEquals("thunderstorm", ATISPhraseology.decodeWeather("TS"))
    }

    /** A lone descriptor with nothing to qualify isn't a weather report. */
    @Test
    fun aLoneDescriptorIsNotWeather() {
        assertNull(ATISPhraseology.decodeWeather("BC"))
    }

    /** "GS OTS" is a glideslope outage, not small hail. */
    @Test
    fun aBareGlideslopeIsNotSmallHail() {
        assertNull(ATISPhraseology.decodeWeather("GS"))
        assertTrue(spoken("GS OTS").contains("glideslope out of service"))
    }

    /** "VA 4L" in an approach list is a visual approach, not volcanic ash. */
    @Test
    fun aBareVisualApproachIsNotVolcanicAsh() {
        assertNull(ATISPhraseology.decodeWeather("VA"))
        assertTrue(spoken("ILS 4R, VA 4L").contains("visual approach"))
    }

    @Test
    fun plainWordsAreNeverMistakenForWeather() {
        val out = spoken("RWY 16C IN USE. GROUND CTL 121.7")
        assertFalse(out.contains("drizzle"))
        assertFalse(out.contains("rain"))
    }

    // endregion

    // region Identifiers and abbreviations

    @Test
    fun runwayDesignatorsReadWithTheirSide() {
        assertTrue(spoken("RWY 24R").contains("runway two four right"))
        assertTrue(spoken("RWY 25L").contains("runway two five left"))
        assertTrue(spoken("RWY 16C").contains("runway one six center"))
    }

    /** A flush keyword would otherwise be voiced letter by letter around the digits. */
    @Test
    fun aFlushRunwayKeywordIsSplitBeforeItIsSpoken() {
        assertTrue(spoken("DEPG RY8R").contains("runway eight right"))
        assertFalse(spoken("DEPG RY8R").contains("R Y"))
    }

    @Test
    fun taxiwayIdentsSpellPhonetically() {
        assertTrue(spoken("TWY B CLSD").contains("taxiway Bravo closed"))
        assertTrue(spoken("TWY SB CLSD").contains("taxiway Sierra Bravo"))
        assertTrue(spoken("TWY B4 CLSD").contains("taxiway Bravo four"))
        // A comma right after the keyword still resolves the ident.
        assertTrue(spoken("TWY, S CLSD").contains("taxiway Sierra"))
    }

    /** A two-letter word after TWY is left for the abbreviation pass. */
    @Test
    fun aCommonWordAfterTheTaxiwayKeywordIsNotAnIdent() {
        assertTrue(spoken("TWYS IN USE").contains("taxiways IN USE"))
        assertTrue(spoken("TWY SW OF ARPT").contains("southwest"))
    }

    /**
     * A bare closure list has no TWY keyword, so the idents are resolved from the
     * grammar: a letter-plus-digit ident spells phonetically wherever it appears, and a
     * lone letter spells when it sits just before CLSD.
     */
    @Test
    fun bareClosureIdentsSpellPhonetically() {
        val flush = spoken("B1 CLSD BTWN B AND B2")
        assertTrue(flush.contains("Bravo one"))
        assertTrue(flush.contains("Bravo two"))

        val bare = spoken("C B CLSD BTWN B1 AND B2")
        assertTrue(bare.contains("Charlie Bravo closed"))
    }

    @Test
    fun approachVariantLettersSpellPhonetically() {
        assertTrue(spoken("RNAV Z RWY 4L APCH").contains("R NAV Zulu"))
        assertTrue(spoken("ILS Y RWY 10R").contains("I L S Yankee"))
    }

    @Test
    fun frequenciesReadWithTheirSeparator() {
        assertTrue(spoken("CTC GND 121.70").contains("one two one point seven zero"))
        assertTrue(spoken("CTC GND 121.70", icao = true).contains("decimal"))
    }

    /** A unit written flush against its number would otherwise be voiced "F T". */
    @Test
    fun unitsFlushAgainstTheirNumberAreSplit() {
        assertTrue(spoken("CRANE 155FT AGL").contains("one five five feet"))
        assertTrue(spoken("WITHIN 5NM").contains("five nautical miles"))
        assertTrue(spoken("GUSTS TO 30KT").contains("three zero knots"))
    }

    /** "HAZD WX" is the advisory adjective, not the NOTAM noun. */
    @Test
    fun hazardousWeatherResolvesFromItsFollowingWord() {
        assertTrue(spoken("HAZD WX INFO AVBL").contains("hazardous weather"))
        assertTrue(spoken("BIRD HAZD INVOF ARPT").contains("hazard in vicinity of airport"))
    }

    /** A lone "VC" is the plain-language vicinity qualifier. */
    @Test
    fun aLoneVicinityQualifierIsNotSpelledOut() {
        assertTrue(spoken("BIRD ACTIVITY VC OF ARPT").contains("vicinity of airport"))
        assertTrue(spoken("BIRD ACTIVITY VC ARPT").contains("vicinity of airport"))
        // …but VCNTY keeps its own expansion.
        assertTrue(spoken("IN THE VCNTY").contains("vicinity"))
    }

    /** Hold short is published both ways. */
    @Test
    fun bothHoldShortSpellingsExpand() {
        assertTrue(spoken("RDBK HS INSTRCNS").contains("read back hold short instructions"))
        assertTrue(spoken("RDBK H/S INSTRCNS").contains("hold short"))
    }

    /** The dense coded remarks group is never read aloud. */
    @Test
    fun theRemarksGroupIsDropped() {
        val out = spoken("A2992 RMK AO2 SLP224 T00331122. ADVS YOU HAVE INFO S.")
        assertFalse(out.contains("SLP"))
        assertFalse(out.contains("AO"))
        assertTrue(out.contains("information Sierra"))
    }

    @Test
    fun theInformationCodeReadsPhonetically() {
        assertTrue(spoken("KSEA ATIS INFO S 2153Z").contains("information Sierra"))
        assertEquals("Alpha", ATISPhraseology.phoneticLetter("a"))
    }

    /** Any digit run left at the end is spoken one digit at a time. */
    @Test
    fun leftoverDigitsAreSpokenIndividually() {
        assertTrue(spoken("COND CODE 5 5 5").contains("five five five"))
    }

    @Test
    fun theIcaoPackChangesTheDigitWords() {
        val out = spoken("A2992", icao = true)
        assertTrue(out.contains("tree") || out.contains("niner"))
    }

    // endregion

    // region Whole broadcasts

    /**
     * A real published D-ATIS end to end. The assertion that matters most is the last
     * one: nothing coded survives to the synthesizer as bare letters.
     */
    @Test
    fun awholeBroadcastReadsCleanly() {
        val raw = """
            SEATTLE TACOMA INTL ATIS INFO S 2153Z. 16008KT 10SM FEW035 SCT250 18/11 A2992
            (TWO NINER NINER TWO). ILS RWY 16C APCH IN USE. DEPG RWY 16L. TWY B CLSD BTWN
            B1 AND B2. RDBK HS INSTRCNS. ADVS YOU HAVE INFO S.
        """.trimIndent()
        val out = spoken(raw)
        assertTrue(out.contains("information Sierra"))
        assertTrue(out.contains("wind one six zero at eight"))
        assertTrue(out.contains("visibility one zero"))
        assertTrue(out.contains("few clouds at three thousand five hundred"))
        assertTrue(out.contains("temperature one eight, dewpoint one one"))
        assertTrue(out.contains("altimeter two niner niner two"))
        assertTrue(out.contains("I L S runway one six center approach IN USE"))
        assertTrue(out.contains("departing runway one six left"))
        assertTrue(out.contains("read back hold short instructions"))
        assertFalse(out.contains("("))
        assertFalse(out.contains(")"))
    }

    /** The transcript keeps the published text verbatim, only collapsing whitespace. */
    @Test
    fun theDisplayTextIsVerbatim() {
        val raw = "  KSEA  ATIS  INFO S\n2153Z. ILS RWY 16C APCH IN USE.  "
        assertEquals("KSEA ATIS INFO S 2153Z. ILS RWY 16C APCH IN USE.", ATISPhraseology.displayText(raw))
    }

    // endregion
}
