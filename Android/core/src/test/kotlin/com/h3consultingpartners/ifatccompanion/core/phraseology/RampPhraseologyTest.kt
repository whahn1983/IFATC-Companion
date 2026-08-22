package com.h3consultingpartners.ifatccompanion.core.phraseology

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests that Ramp is modeled as a first-class, *simulated local/non-FAA* facility
 * — separate from Ground — and that it never issues FAA ATC clearances.
 *
 * Ported from `IFATCCompanionTests/RampPhraseologyTests.swift`. The two
 * `RampProfile` resolution tests at the end of the Swift file
 * (`testGenericProfileWhenUnknownAirport`, `testKnownAirportProfile`) exercise
 * `RampProfile`, which lives in the ATC package, and belong with that port.
 */
class RampPhraseologyTest {

    private fun engine() = PhraseologyEngine(
        digitStyle = CallsignDigitStyle.INDIVIDUAL,
        mode = PhraseologyMode.FAA,
    )

    private fun cs() = engine().callsign(airline = "United", flightNumber = "598", fallback = "")

    private val validator = PhraseologyValidator()

    // MARK: - Ramp is separate from Ground and not FAA ATC

    @Test
    fun testRampIsNotFAAATC() {
        assertFalse(ATCFacility.RAMP.isFAAATC)
        assertTrue(ATCFacility.GROUND.isFAAATC)
        assertNotEquals(ATCFacility.GROUND, ATCFacility.RAMP)
    }

    @Test
    fun testPushbackAndStartAreRampNotGround() {
        assertEquals(ATCFacility.RAMP, ATCState.PUSHBACK.facility)
        assertEquals(ATCFacility.RAMP, ATCState.ENGINE_START.facility)
        assertEquals(ATCFacility.GROUND, ATCState.GROUND_TAXI.facility)
    }

    // MARK: - Pushback approval phraseology

    @Test
    fun testPushbackApprovalWithDirection() {
        val r = RampPhraseologyEngine(engine())
        val tx = r.pushbackApproved(cs = cs(), direction = "west")
        assertTrue(tx.displayText.contains("pushback approved, tail west"), tx.displayText)
        assertEquals(ATCFacility.RAMP, tx.facility)
        assertTrue(validator.isClean(tx.displayText))
    }

    @Test
    fun testPushbackApprovalUnknownDirectionFallsBack() {
        val r = RampPhraseologyEngine(engine())
        val tx = r.pushbackApproved(cs = cs(), direction = "")
        assertTrue(tx.displayText.contains("pushback approved, advise ready to taxi"), tx.displayText)
        assertFalse(tx.displayText.lowercase().contains("cleared"))
    }

    @Test
    fun testApronStyleUsesFaceDirection() {
        val r = RampPhraseologyEngine(engine())
        // iOS passes a `RampProfile` whose `rampType` is `.apronControl`; the only thing
        // the engine reads off it is `usesFaceDirection`, which that type sets true.
        val tx = r.pushbackApproved(cs = cs(), direction = "north", usesFaceDirection = true)
        assertTrue(tx.displayText.contains("face north"), tx.displayText)
    }

    @Test
    fun testRampNeverSaysClearedForPushback() {
        val r = RampPhraseologyEngine(engine())
        val tx = r.pushbackApproved(cs = cs(), direction = "east")
        assertFalse(tx.displayText.lowercase().contains("cleared for pushback"))
    }

    // MARK: - Ramp taxi / handoff

    @Test
    fun testRampTaxiToSpotUsesTaxiViaNotCleared() {
        val r = RampPhraseologyEngine(engine())
        val tx = r.taxiToSpot(cs = cs(), spot = "5")
        assertTrue(tx.displayText.contains("taxi via the alley to spot 5"), tx.displayText)
        assertFalse(tx.displayText.lowercase().contains("cleared"))
    }

    @Test
    fun testHandoffToGroundAtSpot() {
        val r = RampPhraseologyEngine(engine())
        val tx = r.contactGround(cs = cs(), groundFrequency = 121.9, spot = "5")
        assertTrue(tx.displayText.contains("contact Ground"), tx.displayText)
        assertTrue(tx.displayText.contains("spot 5"))
        assertTrue(tx.spokenText.contains("one two one point niner"))
    }

    @Test
    fun testHandoffWithoutSpotUsesMovementAreaBoundary() {
        val r = RampPhraseologyEngine(engine())
        val tx = r.contactGround(cs = cs(), groundFrequency = 121.9, spot = "")
        assertTrue(tx.displayText.contains("movement-area boundary"), tx.displayText)
    }

    // MARK: - Ramp must never issue ATC clearances/authority

    /**
     * Sweep the controller-side ramp calls and assert none contain runway,
     * takeoff, landing, crossing, altitude, heading, SID/STAR, or approach
     * authority — and that all are free of blocked phraseology.
     */
    @Test
    fun testRampNeverIssuesATCClearances() {
        val r = RampPhraseologyEngine(engine())
        val calls: List<ATCTransmission> = listOf(
            r.pushbackApproved(cs = cs(), direction = "west"),
            r.pushbackApproved(cs = cs(), direction = ""),
            r.holdPosition(cs = cs()),
            r.startApproved(cs = cs()),
            r.taxiToSpot(cs = cs(), spot = "5"),
            r.taxiToSpot(cs = cs(), spot = ""),
            r.proceed(cs = cs(), to = "spot 5"),
            r.giveWay(cs = cs(), to = "the Delta Airbus"),
            r.contactGround(cs = cs(), groundFrequency = 121.9, spot = "5"),
            r.proceedToGate(cs = cs(), gate = "B44"),
            r.gateOccupied(cs = cs(), gate = "B44"),
            r.monitorRampToGate(cs = cs()),
        )
        val forbidden = listOf(
            "runway", "cleared", "takeoff", "climb", "descend",
            "heading", "approach", "flight level", "squawk",
        )
        for (tx in calls) {
            assertEquals(ATCFacility.RAMP, tx.facility, "ramp call has wrong facility: ${tx.displayText}")
            val lower = tx.displayText.lowercase()
            for (word in forbidden) {
                assertFalse(
                    lower.contains(word),
                    "ramp call must not contain '$word': ${tx.displayText}",
                )
            }
            assertTrue(validator.isClean(tx.displayText), "blocked phrase: ${tx.displayText}")
        }
    }
}
