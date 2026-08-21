package com.h3consultingpartners.ifatccompanion.core.phraseology

import com.h3consultingpartners.ifatccompanion.core.platform.InMemoryFileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the FAA/ICAO phraseology packs and user phraseology profiles.
 *
 * Ported from `IFATCCompanionTests/PhraseologyPackTests.swift`.
 */
class PhraseologyPackTest {

    // MARK: - ICAO digit + phrase differences

    @Test
    fun testICAODigitWords() {
        assertEquals("tree fower fife", Phonetic.spellDigits("345", icao = true))
        // FAA default unchanged.
        assertEquals("three four five", Phonetic.spellDigits("345"))
    }

    @Test
    fun testICAOFrequencyUsesDecimal() {
        assertEquals("one one eight decimal tree", Phonetic.frequency(118.300, icao = true))
        assertEquals("one one eight point three", Phonetic.frequency(118.300))
    }

    @Test
    fun testICAOAltitudeAndFlightLevel() {
        assertEquals("flight level tree seven zero", Phonetic.altitude(37000, icao = true))
        assertEquals("fife thousand", Phonetic.altitude(5000, icao = true))
    }

    @Test
    fun testICAOAltimeterIsQNHInHectopascals() {
        // 29.92 inHg ~ 1013 hPa.
        assertEquals("QNH one zero one tree", Phonetic.altimeterSetting(inHg = 29.92, icao = true))
        assertEquals("altimeter three zero one two", Phonetic.altimeterSetting(inHg = 30.12, icao = false))
    }

    @Test
    fun testEngineICAOClearanceUsesDecimalSeparator() {
        val engine = PhraseologyEngine(
            digitStyle = CallsignDigitStyle.INDIVIDUAL,
            mode = PhraseologyMode.ICAO,
        )
        val cs = engine.callsign(airline = "Speedbird", flightNumber = "12", fallback = "")
        val tx = engine.clearance(
            cs = cs, destination = "KMSP", cruise = 37000, sid = "",
            initialAlt = 5000, departureFreq = 124.300, squawk = "4271",
        )
        assertTrue(tx.spokenText.contains("decimal"))
        assertFalse(tx.spokenText.contains(" point "))
    }

    @Test
    fun testICAOTaxiUsesHoldingPoint() {
        val engine = PhraseologyEngine(
            digitStyle = CallsignDigitStyle.INDIVIDUAL,
            mode = PhraseologyMode.ICAO,
        )
        val cs = engine.callsign(airline = "Speedbird", flightNumber = "12", fallback = "")
        val tx = engine.taxiToRunway(cs = cs, runway = "27", via = "A", crossing = null)
        assertTrue(tx.displayText.contains("holding point"))
    }

    // MARK: - User profiles

    @Test
    fun testProfileTemplateOverridesTakeoffCall() {
        val profile = PhraseologyProfile(
            name = "Test",
            templates = mapOf(
                PhraseologyTemplateKey.TAKEOFF.rawValue to PhraseologyTemplate(
                    display = "{callsign}, runway {runway}, you are clear to go.",
                    spoken = "{callsign}, runway {runway}, you are clear to go.",
                ),
            ),
        )
        val engine = PhraseologyEngine(
            digitStyle = CallsignDigitStyle.INDIVIDUAL,
            mode = PhraseologyMode.FAA,
        ).copy(profile = profile)
        val cs = engine.callsign(airline = "United", flightNumber = "1", fallback = "")
        val tx = engine.clearedForTakeoff(cs = cs, runway = "17R", windDir = 180, windSpeed = 8)
        assertEquals("United 1, runway 17R, you are clear to go.", tx.displayText)
    }

    @Test
    fun testProfileAirlineCallNameOverridesSpokenAirline() {
        val profile = PhraseologyProfile(
            name = "Test",
            airlineCallSets = mapOf("DLH" to "Lufthansa"),
        )
        val engine = PhraseologyEngine(
            digitStyle = CallsignDigitStyle.INDIVIDUAL,
            mode = PhraseologyMode.FAA,
        ).copy(profile = profile)
        assertEquals(
            "Lufthansa four zero zero",
            engine.spokenCallsign(airline = "DLH", flightNumber = "400"),
        )
    }

    @Test
    fun testProfileRoundTripsThroughJSON() {
        val store = PhraseologyProfileStore(InMemoryFileStore())
        val profile = PhraseologyProfile(
            name = "Roundtrip",
            airlineCallSets = mapOf("BAW" to "Speedbird"),
        )
        val json = store.exportJSON(profile)
        val imported = store.importJSON(json)
        assertNotNull(imported)
        assertEquals("Speedbird", imported.airlineCallSets["BAW"])
    }
}
