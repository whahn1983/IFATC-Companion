package com.h3consultingpartners.ifatccompanion.core.phraseology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Ported from `IFATCCompanionTests/PhraseologyTests.swift`. */
class PhraseologyTest {

    @Test
    fun testDigitSpelling() {
        assertEquals("four two seven one", Phonetic.spellDigits("4271"))
        assertEquals("niner", Phonetic.spellDigits("9"))
    }

    @Test
    fun testAltitudePronunciation() {
        assertEquals("one zero thousand", Phonetic.altitude(10000))
        assertEquals("flight level three seven zero", Phonetic.altitude(37000))
        assertEquals("two thousand five hundred", Phonetic.altitude(2500))
        assertEquals("five thousand", Phonetic.altitude(5000))
        assertEquals("one one thousand", Phonetic.altitude(11000))
    }

    @Test
    fun testHeadingPronunciation() {
        assertEquals("two seven zero", Phonetic.heading(270))
        assertEquals("zero niner zero", Phonetic.heading(90))
        assertEquals("zero zero zero", Phonetic.heading(360))
    }

    @Test
    fun testFrequencyPronunciation() {
        assertEquals("one one eight point three", Phonetic.frequency(118.300))
        assertEquals("one two four point eight seven five", Phonetic.frequency(124.875))
    }

    @Test
    fun testRunwayPronunciation() {
        assertEquals("one seven right", Phonetic.runway("17R"))
        assertEquals("zero four left", Phonetic.runway("04L"))
        assertEquals("zero niner", Phonetic.runway("9"))
        assertEquals("three zero center", Phonetic.runway("30C"))
    }

    @Test
    fun testReciprocalRunway() {
        assertEquals("6R", Phonetic.reciprocalRunway("24L"))
        assertEquals("24L", Phonetic.reciprocalRunway("06R"))
        assertEquals("18", Phonetic.reciprocalRunway("36"))
        assertEquals("27", Phonetic.reciprocalRunway("09"))
        assertEquals("31C", Phonetic.reciprocalRunway("13C"))
        assertNull(Phonetic.reciprocalRunway("ALPHA"))
    }

    @Test
    fun testRunwayPairDisplayIsLowerNumberFirst() {
        // Either end resolves to the same lower-number-first designation.
        assertEquals("6R-24L", Phonetic.runwayPairDisplay("24L"))
        assertEquals("6R-24L", Phonetic.runwayPairDisplay("06R"))
        assertEquals("18-36", Phonetic.runwayPairDisplay("36"))
        assertEquals("9-27", Phonetic.runwayPairDisplay("09"))
        assertEquals("13C-31C", Phonetic.runwayPairDisplay("13C"))
    }

    @Test
    fun testRunwayPairSpokenNamesBothDirections() {
        // The example from the request: "hold short of runway 6R-24L" is spoken
        // "... six right two four left".
        assertEquals("six right two four left", Phonetic.runwayPairSpoken("24L"))
        assertEquals("six right two four left", Phonetic.runwayPairSpoken("06R"))
        assertEquals("one eight three six", Phonetic.runwayPairSpoken("36"))
        assertEquals("niner two seven", Phonetic.runwayPairSpoken("09"))
        // ICAO digits carry through ("niner"/"tree" etc.).
        assertEquals("one tree tree one", Phonetic.runwayPairSpoken("31", icao = true))
    }

    @Test
    fun testRunwayPairFallsBackWhenNoRunwayNumber() {
        assertEquals("ALPHA", Phonetic.runwayPairDisplay("ALPHA"))
        assertEquals(Phonetic.runway("ALPHA"), Phonetic.runwayPairSpoken("ALPHA"))
    }

    @Test
    fun testWindPronunciation() {
        assertEquals("wind three three zero at one two", Phonetic.wind(direction = 330, speed = 12))
        assertEquals("wind calm", Phonetic.wind(direction = 0, speed = 0))
    }

    @Test
    fun testSquawkPronunciation() {
        assertEquals("squawk four two seven one", Phonetic.squawk("4271"))
    }

    @Test
    fun testAltimeterPronunciation() {
        assertEquals("three zero one two", Phonetic.altimeter(30.12))
    }

    @Test
    fun testCallsignIndividualStyle() {
        val engine = PhraseologyEngine(
            digitStyle = CallsignDigitStyle.INDIVIDUAL,
            mode = PhraseologyMode.FAA,
        )
        assertEquals(
            "United five niner eight",
            engine.spokenCallsign(airline = "United", flightNumber = "598"),
        )
    }

    @Test
    fun testCallsignGroupedStyle() {
        val engine = PhraseologyEngine(
            digitStyle = CallsignDigitStyle.GROUPED,
            mode = PhraseologyMode.FAA,
        )
        assertEquals(
            "American twelve thirty four",
            engine.spokenCallsign(airline = "American", flightNumber = "1234"),
        )
    }

    @Test
    fun testCallsignFallbackTailNumber() {
        val engine = PhraseologyEngine(
            digitStyle = CallsignDigitStyle.GROUPED,
            mode = PhraseologyMode.FAA,
        )
        assertEquals(
            "November one two Alpha Bravo",
            engine.spokenCallsign(airline = "", flightNumber = "", fallback = "N12AB"),
        )
    }

    @Test
    fun testAltitudeDisplayFormatting() {
        val engine = PhraseologyEngine()
        assertEquals("FL370", engine.formatAltDisplay(37000))
        assertEquals("5,000", engine.formatAltDisplay(5000))
    }
}
