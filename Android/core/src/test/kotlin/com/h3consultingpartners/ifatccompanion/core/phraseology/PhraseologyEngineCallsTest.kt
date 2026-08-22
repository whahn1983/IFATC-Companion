package com.h3consultingpartners.ifatccompanion.core.phraseology

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Every built-in controller call, word for word, in both packs.
 *
 * These are not in the iOS suite as such — iOS asserts on fragments (`contains`) —
 * but the wording *is* the product: a comma or a "take-off" hyphen moved in the port
 * is a silent regression that no fragment assertion catches. Each expectation here is
 * transcribed from the template in `IFATCCompanion/Phraseology/PhraseologyEngine.swift`,
 * so the test fails if the Kotlin ever drifts from the Swift.
 */
class PhraseologyEngineCallsTest {

    private val faa = PhraseologyEngine(
        digitStyle = CallsignDigitStyle.INDIVIDUAL,
        mode = PhraseologyMode.FAA,
    )
    private val icaoPack = PhraseologyEngine(
        digitStyle = CallsignDigitStyle.INDIVIDUAL,
        mode = PhraseologyMode.ICAO,
    )

    private fun cs(engine: PhraseologyEngine) =
        engine.callsign(airline = "United", flightNumber = "598", fallback = "")

    /** Stand-in for the ATC package's `Procedure`, which implements this contract. */
    private class TestProcedure(
        override val displayName: String,
        private val spoken: String,
        override val runway: String? = null,
        override val fixes: List<String> = emptyList(),
        override val approachTypeDisplay: String? = null,
        override val approachTypeSpoken: String? = null,
    ) : PhraseologyProcedure {
        override fun spokenName(icao: Boolean): String = spoken
    }

    // MARK: - Clearance Delivery

    @Test
    fun testIFRClearanceIsVerbatim() {
        val tx = faa.clearance(
            cs = cs(faa), destination = "KMSP", cruise = 37000, sid = "WAGON5",
            initialAlt = 5000, departureFreq = 124.300, squawk = "4271",
        )
        assertEquals(ATCFacility.CLEARANCE, tx.facility)
        assertEquals(
            "United 598, cleared to KMSP via the WAGON5 departure, climb via SID except maintain 5,000, " +
                "expect FL370 one zero minutes after departure, departure frequency 124.300, squawk 4271.",
            tx.displayText,
        )
        assertEquals(
            "United five niner eight, cleared to Minneapolis via the Whiskey Alpha Golf Oscar November five departure, " +
                "climb via SID except maintain five thousand, expect flight level three seven zero one zero minutes after departure, " +
                "departure frequency one two four point three, squawk four two seven one.",
            tx.spokenText,
        )
    }

    /** No SID filed: the route phrase falls back rather than naming an empty procedure. */
    @Test
    fun testClearanceWithoutASIDUsesTheFiledRoute() {
        val tx = faa.clearance(
            cs = cs(faa), destination = "", cruise = 37000, sid = "",
            initialAlt = 5000, departureFreq = 124.300, squawk = "4271",
        )
        assertEquals(
            "United 598, cleared to destination via the filed route, climb via SID except maintain 5,000, " +
                "expect FL370 one zero minutes after departure, departure frequency 124.300, squawk 4271.",
            tx.displayText,
        )
        assertEquals(
            "United five niner eight, cleared to destination via the filed route, climb via SID except maintain five thousand, " +
                "expect flight level three seven zero one zero minutes after departure, " +
                "departure frequency one two four point three, squawk four two seven one.",
            tx.spokenText,
        )
    }

    @Test
    fun testPushbackHandoffIsAppendedToTheClearance() {
        val clearance = faa.clearance(
            cs = cs(faa), destination = "KMSP", cruise = 37000, sid = "",
            initialAlt = 5000, departureFreq = 124.300, squawk = "4271",
        )
        val tx = faa.appendingPushbackHandoff(clearance, ATCFacility.RAMP, 131.0)
        assertEquals(
            clearance.displayText + " When ready for pushback, contact Ramp on 131.000.",
            tx.displayText,
        )
        assertEquals(
            clearance.spokenText + " When ready for pushback, contact Ramp on one three one point zero.",
            tx.spokenText,
        )
    }

    // MARK: - Ground

    @Test
    fun testPushbackAndStartupWordingDiffersByPack() {
        assertEquals("United 598, pushback approved.", faa.pushbackApproved(cs(faa)).displayText)
        assertEquals("United 598, start up approved.", faa.startupApproved(cs(faa)).displayText)
        // ICAO writes "push back" / "start-up".
        assertEquals("United 598, push back approved.", icaoPack.pushbackApproved(cs(icaoPack)).displayText)
        assertEquals("United 598, start-up approved.", icaoPack.startupApproved(cs(icaoPack)).displayText)
    }

    @Test
    fun testTaxiToRunwayIsVerbatimInBothPacks() {
        val faaTx = faa.taxiToRunway(cs = cs(faa), runway = "17R", via = "A", crossing = "24L")
        assertEquals(
            "United 598, taxi to runway 17R via A, cross runway 6R-24L. Contact Tower when ready.",
            faaTx.displayText,
        )
        assertEquals(
            "United five niner eight, taxi to runway one seven right via Alpha, " +
                "cross runway six right two four left. Contact Tower when ready.",
            faaTx.spokenText,
        )

        val icaoTx = icaoPack.taxiToRunway(cs = cs(icaoPack), runway = "17R", via = "A", crossing = "24L")
        assertEquals(
            "United 598, taxi to holding point runway 17R via A, cross runway 6R-24L. Contact Tower when ready.",
            icaoTx.displayText,
        )
        assertEquals(
            "United fife niner eight, taxi to holding point runway one seven right via Alpha, " +
                "cross runway six right two fower left. Contact Tower when ready.",
            icaoTx.spokenText,
        )
    }

    /** No crossing: the clause vanishes entirely rather than leaving a dangling comma. */
    @Test
    fun testTaxiToRunwayWithoutACrossing() {
        val tx = faa.taxiToRunway(cs = cs(faa), runway = "17R", via = "A", crossing = null)
        assertEquals(
            "United 598, taxi to runway 17R via A. Contact Tower when ready.",
            tx.displayText,
        )
    }

    @Test
    fun testMonitorTowerCarriesATuningReadback() {
        val tx = faa.monitorTower(cs = cs(faa), frequency = 118.300)
        assertEquals("United 598, monitor Tower on 118.300.", tx.displayText)
        assertEquals(
            "United five niner eight, monitor Tower on one one eight point three.",
            tx.spokenText,
        )
        val readback = assertNotNull(tx.readback)
        assertEquals("Monitor Tower on 118.300, United 598.", readback.displayText)
        assertEquals(
            "Monitor Tower on one one eight point three, United five niner eight.",
            readback.spokenText,
        )
        assertEquals(ATCFacility.TOWER, readback.tuneTo)
    }

    // MARK: - Tower, departure

    @Test
    fun testLineUpAndWaitIsVerbatim() {
        val tx = faa.lineUpAndWait(cs = cs(faa), runway = "17R")
        assertEquals("United 598, runway 17R, line up and wait.", tx.displayText)
        assertEquals(
            "United five niner eight, runway one seven right, line up and wait.",
            tx.spokenText,
        )
    }

    @Test
    fun testNumberOneForTakeoffReportsSequenceOnly() {
        val tx = faa.numberOneForTakeoff(cs = cs(faa), runway = "17R")
        assertEquals(
            "United 598, roger, you're number one for departure, runway 17R.",
            tx.displayText,
        )
        assertEquals(
            "United five niner eight, roger, you're number one for departure, runway one seven right.",
            tx.spokenText,
        )
    }

    @Test
    fun testClearedForTakeoffIsVerbatimInBothPacks() {
        val faaTx = faa.clearedForTakeoff(cs = cs(faa), runway = "17R", windDir = 330, windSpeed = 12)
        assertEquals(
            "United 598, wind 330 at 12, runway 17R, cleared for takeoff.",
            faaTx.displayText,
        )
        assertEquals(
            "United five niner eight, wind three three zero at one two, runway one seven right, cleared for takeoff.",
            faaTx.spokenText,
        )

        // ICAO hyphenates "take-off" and speaks "tree" for 3.
        val icaoTx = icaoPack.clearedForTakeoff(cs = cs(icaoPack), runway = "17R", windDir = 330, windSpeed = 12)
        assertEquals(
            "United 598, wind 330 at 12, runway 17R, cleared for take-off.",
            icaoTx.displayText,
        )
        assertEquals(
            "United fife niner eight, wind tree tree zero at one two, runway one seven right, cleared for take-off.",
            icaoTx.spokenText,
        )
    }

    @Test
    fun testClearedForTakeoffWithDepartureInstructionsReadsBackBothElements() {
        val tx = faa.clearedForTakeoff(
            cs = cs(faa), runway = "17R", windDir = 330, windSpeed = 12,
            departureHeading = 250, initialAltitude = 5000,
        )
        assertEquals(
            "United 598, wind 330 at 12, runway 17R, cleared for takeoff, fly heading 250, climb and maintain 5,000.",
            tx.displayText,
        )
        assertEquals(
            "United five niner eight, wind three three zero at one two, runway one seven right, cleared for takeoff, " +
                "fly heading two five zero, climb and maintain five thousand.",
            tx.spokenText,
        )
        val readback = assertNotNull(tx.readback)
        // The leading "fly " is dropped so the echo reads naturally.
        assertEquals(
            "Runway 17R, cleared for takeoff, heading 250, climb and maintain 5,000, United 598.",
            readback.displayText,
        )
        assertEquals(
            "Runway one seven right, cleared for takeoff, heading two five zero, climb and maintain five thousand, " +
                "United five niner eight.",
            readback.spokenText,
        )
    }

    /**
     * A departure vector within 10° of the runway heading becomes "fly runway heading"
     * rather than a number the pilot would have to chase.
     */
    @Test
    fun testDepartureHeadingWithinTenDegreesSaysRunwayHeading() {
        val tx = faa.clearedForTakeoff(
            cs = cs(faa), runway = "17R", windDir = 330, windSpeed = 12,
            departureHeading = 175, initialAltitude = 5000,
        )
        assertEquals(
            "United 598, wind 330 at 12, runway 17R, cleared for takeoff, fly runway heading, climb and maintain 5,000.",
            tx.displayText,
        )
        assertEquals(
            "Runway 17R, cleared for takeoff, runway heading, climb and maintain 5,000, United 598.",
            assertNotNull(tx.readback).displayText,
        )
    }

    /**
     * When nothing in the plan named a runway the "runway heading" substitution is off:
     * the ident is a rounded wind direction, so comparing the departure vector against it
     * asks the wrong question, and the numeric heading is always spoken instead.
     */
    @Test
    fun testUnknownRunwayAlwaysSpeaksTheNumericHeading() {
        val tx = faa.clearedForTakeoff(
            cs = cs(faa), runway = "17R", windDir = 330, windSpeed = 12,
            departureHeading = 175, initialAltitude = 5000, runwayIsKnown = false,
        )
        assertEquals(
            "United 598, wind 330 at 12, runway 17R, cleared for takeoff, fly heading 175, climb and maintain 5,000.",
            tx.displayText,
        )
    }

    // MARK: - Departure / Center

    @Test
    fun testDepartureClimbEchoesResumeOwnNavigation() {
        val tx = faa.departureClimb(cs = cs(faa), altitude = 15000, firstFix = "WAGON")
        assertEquals(
            "United 598, radar contact, climb and maintain 15,000, resume own navigation, direct WAGON.",
            tx.displayText,
        )
        assertEquals(
            "United five niner eight, radar contact, climb and maintain one five thousand, " +
                "resume own navigation, direct Whiskey Alpha Golf Oscar November.",
            tx.spokenText,
        )
        assertEquals(
            "Climb and maintain 15,000, resume own navigation, direct WAGON, United 598.",
            assertNotNull(tx.readback).displayText,
        )
    }

    @Test
    fun testDepartureClimbWithoutAFirstFix() {
        val tx = faa.departureClimb(cs = cs(faa), altitude = 15000, firstFix = "")
        assertEquals(
            "United 598, radar contact, climb and maintain 15,000, resume own navigation.",
            tx.displayText,
        )
    }

    @Test
    fun testEnrouteClimbsAndDescentsAreVerbatim() {
        assertEquals(
            "United 598, radar contact, climb and maintain 10,000.",
            faa.radarContactClimb(cs = cs(faa), altitude = 10000).displayText,
        )
        assertEquals(
            "United 598, climb and maintain FL370.",
            faa.climbMaintain(cs = cs(faa), altitude = 37000).displayText,
        )
        assertEquals(
            "United 598, radar contact, climb and maintain FL370.",
            faa.centerRadarContactClimb(cs = cs(faa), altitude = 37000).displayText,
        )
        assertEquals(
            "United 598, descend and maintain 11,000.",
            faa.descendMaintain(cs = cs(faa), altitude = 11000).displayText,
        )
        assertEquals(
            "United 598, descend at pilot's discretion, maintain 11,000.",
            faa.descendPilotsDiscretion(cs = cs(faa), altitude = 11000).displayText,
        )
        assertEquals(
            "United five niner eight, descend and maintain one one thousand.",
            faa.descendMaintain(cs = cs(faa), altitude = 11000).spokenText,
        )
    }

    /** The crossing restriction names the *second* fix on the arrival, when there is one. */
    @Test
    fun testDescendViaArrivalNamesTheSecondFix() {
        val star = TestProcedure(
            displayName = "KKILR3",
            spoken = "Kkilr three",
            fixes = listOf("ABC", "BDF"),
        )
        val tx = faa.descendViaArrival(cs = cs(faa), star = star, altitude = 11000)
        assertEquals(
            "United 598, descend via the KKILR3 arrival, maintain 11,000 crossing BDF.",
            tx.displayText,
        )
        assertEquals(
            "United five niner eight, descend via the Kkilr three arrival, maintain one one thousand " +
                "crossing Bravo Delta Foxtrot.",
            tx.spokenText,
        )
    }

    @Test
    fun testDescendViaArrivalWithASingleFixHasNoCrossingClause() {
        val star = TestProcedure(displayName = "KKILR3", spoken = "Kkilr three", fixes = listOf("ABC"))
        assertEquals(
            "United 598, descend via the KKILR3 arrival, maintain 11,000.",
            faa.descendViaArrival(cs = cs(faa), star = star, altitude = 11000).displayText,
        )
    }

    // MARK: - Approach / arrival

    @Test
    fun testDescendExpectApproachFallsBackToTheILS() {
        val tx = faa.descendExpectApproach(cs = cs(faa), altitude = 5000, approach = "", runway = "30L")
        assertEquals(
            "United 598, descend and maintain 5,000, expect the I-L-S runway 30L approach.",
            tx.displayText,
        )
        assertEquals(
            "United five niner eight, descend and maintain five thousand, expect the I L S runway three zero left approach.",
            tx.spokenText,
        )
    }

    @Test
    fun testDescendExpectApproachWithAProcedureAvoidsTheDoubledRunway() {
        val proc = TestProcedure(
            displayName = "ILS RWY 30L",
            spoken = "I L S runway three zero left",
            runway = "30L",
            approachTypeDisplay = "ILS",
            approachTypeSpoken = "I L S",
        )
        val tx = faa.descendExpectApproach(cs = cs(faa), altitude = 5000, procedure = proc, runway = "30L")
        assertEquals(
            "United 598, descend and maintain 5,000, expect the ILS runway 30L approach.",
            tx.displayText,
        )
        assertEquals(
            "United five niner eight, descend and maintain five thousand, expect the I L S runway three zero left approach.",
            tx.spokenText,
        )
    }

    @Test
    fun testClearedApproachIsVerbatim() {
        val tx = faa.clearedApproach(cs = cs(faa), approach = "", runway = "30L")
        assertEquals("United 598, cleared ILS runway 30L approach.", tx.displayText)
        assertEquals(
            "United five niner eight, cleared I L S runway three zero left approach.",
            tx.spokenText,
        )
    }

    @Test
    fun testClearedToLandIsVerbatim() {
        val tx = faa.clearedToLand(cs = cs(faa), runway = "30L", windDir = 330, windSpeed = 12)
        assertEquals("United 598, wind 330 at 12, runway 30L, cleared to land.", tx.displayText)
        assertEquals(
            "United five niner eight, wind three three zero at one two, runway three zero left, cleared to land.",
            tx.spokenText,
        )
    }

    @Test
    fun testGoAroundCarriesEveryElementIntoTheReadback() {
        val tx = faa.goAround(
            cs = cs(faa), runway = "30L", leftTraffic = true, crosswindHeading = 210,
            patternAltitude = 3000, approachFrequency = 119.100,
        )
        assertEquals(
            "United 598, go around, turn left heading 210, climb and maintain 3,000, " +
                "make left traffic runway 30L, contact Approach on 119.100.",
            tx.displayText,
        )
        assertEquals(
            "United five niner eight, go around, turn left heading two one zero, climb and maintain three thousand, " +
                "make left traffic runway three zero left, contact Approach on one one niner point one.",
            tx.spokenText,
        )
        val readback = assertNotNull(tx.readback)
        assertEquals(
            "Going around, turn left heading 210, climb and maintain 3,000, make left traffic runway 30L, " +
                "contacting Approach on 119.100, United 598.",
            readback.displayText,
        )
        assertEquals(ATCFacility.APPROACH, readback.tuneTo)
    }

    @Test
    fun testContinueInboundHoldsThePatternAltitude() {
        val tx = faa.continueInbound(
            cs = cs(faa), altitude = 3000, procedure = null, approach = "", runway = "30L",
        )
        assertEquals(
            "United 598, maintain 3,000, continue inbound, expect the ILS runway 30L approach.",
            tx.displayText,
        )
        assertEquals(
            "United five niner eight, maintain three thousand, continue inbound, " +
                "expect the I L S runway three zero left approach.",
            tx.spokenText,
        )
        assertEquals(
            "Maintain 3,000, continue inbound, United 598.",
            assertNotNull(tx.readback).displayText,
        )
    }

    @Test
    fun testExitRunwayContactGroundIsVerbatim() {
        val tx = faa.exitRunwayContactGround(cs = cs(faa), frequency = 121.900)
        assertEquals(
            "United 598, exit the runway when able, contact Ground on 121.900 once on the taxiway.",
            tx.displayText,
        )
        assertEquals(
            "United five niner eight, exit the runway when able, contact Ground on one two one point niner " +
                "once on the taxiway.",
            tx.spokenText,
        )
    }

    @Test
    fun testTaxiToParkingPrecomposesItsReadback() {
        val tx = faa.taxiToParking(cs = cs(faa), gate = "B44", via = "A")
        assertEquals("United 598, taxi to gate B44 via A.", tx.displayText)
        assertEquals(
            "United five niner eight, taxi to gate Bravo four four via Alpha.",
            tx.spokenText,
        )
        val readback = assertNotNull(tx.readback)
        assertEquals("Taxi to gate B44 via A, United 598.", readback.displayText)
        assertEquals(
            "Taxi to gate Bravo four four via Alpha, United five niner eight.",
            readback.spokenText,
        )
    }

    @Test
    fun testTaxiToParkingWithoutAGateOrRoute() {
        val tx = faa.taxiToParking(cs = cs(faa), gate = "", via = "")
        assertEquals("United 598, taxi to parking via available taxiways.", tx.displayText)
        assertEquals(
            "United five niner eight, taxi to parking via available taxiways.",
            tx.spokenText,
        )
    }

    @Test
    fun testWelcomeArrivalNamesTheCityWhenKnown() {
        assertEquals(
            "United 598, welcome to Minneapolis, good day.",
            faa.welcomeArrival(cs = cs(faa), airport = "KMSP").displayText,
        )
        // An unknown ICAO is left as entered in the transcript and spelled in the audio.
        assertEquals(
            "United 598, welcome to KZZZ, good day.",
            faa.welcomeArrival(cs = cs(faa), airport = "KZZZ").displayText,
        )
        assertEquals(
            "United five niner eight, welcome to Kilo Zulu Zulu Zulu, good day.",
            faa.welcomeArrival(cs = cs(faa), airport = "KZZZ").spokenText,
        )
        assertEquals(
            "United 598, welcome, monitor ground, good day.",
            faa.welcomeArrival(cs = cs(faa), airport = "").displayText,
        )
    }

    // MARK: - Hand-offs and the Center sector name

    @Test
    fun testHandoffCarriesATuningReadback() {
        val tx = faa.handoff(cs = cs(faa), to = ATCFacility.DEPARTURE, frequency = 124.300)
        assertEquals(ATCFacility.DEPARTURE, tx.facility)
        assertEquals("United 598, contact Departure on 124.300.", tx.displayText)
        val readback = assertNotNull(tx.readback)
        assertEquals("Contacting Departure on 124.300, United 598.", readback.displayText)
        assertEquals(
            "Contacting Departure on one two four point three, United five niner eight.",
            readback.spokenText,
        )
        assertEquals(ATCFacility.DEPARTURE, readback.tuneTo)
    }

    /** The releasing facility owns the transcript line when the hand-off names a `from`. */
    @Test
    fun testHandoffFromAFacilityIsAttributedToIt() {
        val tx = faa.handoff(
            cs = cs(faa), from = ATCFacility.TOWER, to = ATCFacility.DEPARTURE, frequency = 124.300,
        )
        assertEquals(ATCFacility.TOWER, tx.facility)
        assertEquals("United 598, contact Departure on 124.300.", tx.displayText)
    }

    /**
     * Center is the one facility whose spoken name is not fixed: the sector working the
     * flight is named in every call that names the controller.
     */
    @Test
    fun testCenterTakesTheWorkingSectorName() {
        val houston = faa.copy(centerSectorName = "Houston Center")
        assertEquals(
            "United 598, contact Houston Center on 133.400.",
            houston.handoff(cs = cs(faa), to = ATCFacility.CENTER, frequency = 133.400).displayText,
        )
        assertEquals(
            "United 598, Houston Center, radar contact.",
            houston.radarContact(cs = cs(faa), facility = ATCFacility.CENTER).displayText,
        )
        // Blank or absent sector falls back to the generic "Center"…
        assertEquals(
            "United 598, Center, radar contact.",
            faa.radarContact(cs = cs(faa), facility = ATCFacility.CENTER).displayText,
        )
        assertEquals(
            "United 598, Center, radar contact.",
            faa.copy(centerSectorName = "   ").radarContact(cs = cs(faa), facility = ATCFacility.CENTER).displayText,
        )
        // …and no other facility is renamed.
        assertEquals(
            "United 598, Tower, radar contact.",
            houston.radarContact(cs = cs(faa), facility = ATCFacility.TOWER).displayText,
        )
    }

    // MARK: - Profile template rendering

    /**
     * A `{placeholder}` resolves to the display value in `display` and the spoken value
     * in `spoken`, including the composed crossing clause.
     */
    @Test
    fun testProfileTemplateRendersDisplayAndSpokenSeparately() {
        val profile = PhraseologyProfile(
            name = "Test",
            templates = mapOf(
                PhraseologyTemplateKey.TAXI_TO_RUNWAY.rawValue to
                    PhraseologyTemplateKey.TAXI_TO_RUNWAY.defaultTemplate,
            ),
        )
        val engine = faa.copy(profile = profile)
        val tx = engine.taxiToRunway(cs = cs(engine), runway = "17R", via = "A", crossing = "24L")
        assertEquals("United 598, taxi to runway 17R via A, cross runway 6R-24L.", tx.displayText)
        assertEquals(
            "United five niner eight, taxi to runway one seven right via Alpha, " +
                "cross runway six right two four left.",
            tx.spokenText,
        )
    }

    /**
     * The default clearance template's *spoken* half deliberately drops the literal word
     * "squawk", because `{squawk}` already resolves to "squawk four two seven one".
     */
    @Test
    fun testDefaultClearanceTemplateDoesNotDoubleTheSquawkKeyword() {
        val profile = PhraseologyProfile(
            name = "Test",
            templates = mapOf(
                PhraseologyTemplateKey.CLEARANCE.rawValue to
                    PhraseologyTemplateKey.CLEARANCE.defaultTemplate,
            ),
        )
        val engine = faa.copy(profile = profile)
        val tx = engine.clearance(
            cs = cs(engine), destination = "KMSP", cruise = 37000, sid = "WAGON5",
            initialAlt = 5000, departureFreq = 124.300, squawk = "4271",
        )
        assertEquals(
            "United 598, cleared to KMSP via the WAGON5 departure, climb via SID except maintain 5,000, " +
                "expect FL370 one zero minutes after departure, departure frequency 124.300, squawk 4271.",
            tx.displayText,
        )
        assertEquals(
            "United five niner eight, cleared to Minneapolis via the Whiskey Alpha Golf Oscar November five departure, " +
                "climb via SID except maintain five thousand, expect flight level three seven zero one zero minutes after departure, " +
                "departure frequency one two four point three, squawk four two seven one.",
            tx.spokenText,
        )
    }
}
