package com.h3consultingpartners.ifatccompanion.core.phraseology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Ported from `IFATCCompanionTests/AirlineCallsignTests.swift`. */
class AirlineCallsignTest {

    @Test
    fun testParseIATATwoLetterPrefix() {
        val parsed = AirlineDatabase.parse("UA598")
        assertEquals("United", parsed?.telephony)
        assertEquals("598", parsed?.flightNumber)
        assertEquals("UA", parsed?.designator)
    }

    @Test
    fun testParseICAOThreeLetterPrefix() {
        val parsed = AirlineDatabase.parse("UAL598")
        assertEquals("United", parsed?.telephony)
        assertEquals("598", parsed?.flightNumber)
        assertEquals("UAL", parsed?.designator)
    }

    @Test
    fun testParseHandlesLowercaseHyphenAndSpaces() {
        assertEquals("Speedbird", AirlineDatabase.parse("ba-2490")?.telephony)
        assertEquals("Lufthansa", AirlineDatabase.parse("dlh 400")?.telephony)
        assertEquals("400", AirlineDatabase.parse("dlh 400")?.flightNumber)
    }

    @Test
    fun testTailNumberIsNotParsedAsAirline() {
        assertNull(AirlineDatabase.parse("N12AB"))
        assertNull(AirlineDatabase.parse("G-ABCD"))
    }

    @Test
    fun testUnknownDesignatorReturnsNil() {
        assertNull(AirlineDatabase.parse("ZZ123"))
    }

    @Test
    fun testPureNumberOrPureLettersReturnNil() {
        assertNull(AirlineDatabase.parse("598"))
        assertNull(AirlineDatabase.parse("UAL"))
    }

    @Test
    fun testCallNameResolvesBothDesignatorStyles() {
        assertEquals("Delta", AirlineDatabase.callName("DAL"))
        assertEquals("Delta", AirlineDatabase.callName("DL"))
        assertEquals("Emirates", AirlineDatabase.callName("ek"))
        assertNull(AirlineDatabase.callName("United"))
    }

    @Test
    fun testSpokenCallsignThroughParsedAirline() {
        val engine = PhraseologyEngine(
            digitStyle = CallsignDigitStyle.INDIVIDUAL,
            mode = PhraseologyMode.FAA,
        )
        val parsed = AirlineDatabase.parse("UA598")!!
        assertEquals(
            "United five niner eight",
            engine.spokenCallsign(airline = parsed.telephony, flightNumber = parsed.flightNumber),
        )
    }

    @Test
    fun testEngineResolvesDesignatorToTelephony() {
        val engine = PhraseologyEngine(
            digitStyle = CallsignDigitStyle.INDIVIDUAL,
            mode = PhraseologyMode.FAA,
        )
        assertEquals(
            "Lufthansa four zero zero",
            engine.spokenCallsign(airline = "DLH", flightNumber = "400"),
        )
        assertEquals(
            "Lufthansa 400",
            engine.displayCallsign(airline = "DLH", flightNumber = "400"),
        )
    }
}
