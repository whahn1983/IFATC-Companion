package com.h3consultingpartners.ifatccompanion.core.phraseology

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Banned/outdated phrase detection.
 *
 * Ported from `IFATCCompanionTests/PhraseologyValidatorTests.swift`. The two sweep
 * tests in the Swift file (`testStateMachineOutputsAreClean`,
 * `testPilotReadbacksAreClean`) drive `ATCStateMachine` / `PilotResponseEngine`,
 * which live in the ATC package; they belong with that port. The ramp half of the
 * sweep is covered here by `RampPhraseologyTest.testRampNeverIssuesATCClearances`.
 */
class PhraseologyValidatorTest {

    private val validator = PhraseologyValidator()

    // MARK: - Banned phrase detection

    @Test
    fun testDetectsBlockedPhrases() {
        val blocked = listOf(
            "United 1, cleared to taxi to runway 17R",
            "cleared for pushback",
            "runway 17R, position and hold",
            "taxi into position and hold",
            "cleared takeoff at your discretion",
            "any traffic please advise",
            "taking the active",
            "clear of the active",
            "cross all runways via Bravo",
        )
        for (s in blocked) {
            assertFalse(validator.isClean(s), "should flag: $s")
        }
    }

    @Test
    fun testCleanPhrasesPass() {
        val clean = listOf(
            "United 598, runway 17R, cleared for takeoff",
            "United 598, runway 30L, cleared to land",
            "United 598, taxi to runway 17R via Alpha, hold short runway 17L",
            "United 598, pushback approved, tail west",
            "United 598, cross runway 17L at Bravo, continue via Bravo",
            "United 598, line up and wait",
        )
        for (s in clean) {
            assertTrue(validator.isClean(s), "should be clean: $s")
        }
    }

    @Test
    fun testWeakAckReadbackIsRejected() {
        assertFalse(validator.isAcceptableSafetyReadback("Roger", requiredElements = listOf("17R")))
        assertFalse(validator.isAcceptableSafetyReadback("Wilco", requiredElements = listOf("17R")))
        assertTrue(
            validator.isAcceptableSafetyReadback(
                "Runway 17R, cleared for takeoff, United 598",
                requiredElements = listOf("17R"),
            ),
        )
    }
}
