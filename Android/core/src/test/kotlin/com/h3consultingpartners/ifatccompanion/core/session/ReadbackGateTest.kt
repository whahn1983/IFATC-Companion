package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate is what stops controller calls firing back-to-back near the runway, so its
 * timing rules are worth pinning down: it holds until the pilot answers, it nags at a
 * fixed interval and then stops nagging, it never nags over a live controller, and the
 * takeoff clearance holds it silently.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadbackGateTest {

    private fun call(text: String = "Cleared to land runway 27") = ATCTransmission.create(
        sender = ATCTransmission.Sender.ATC,
        facility = ATCFacility.TOWER,
        displayText = text,
        timestampMillis = 0,
    )

    @Test
    fun theGateHoldsUntilThePilotAnswers() = runTest {
        val gate = ReadbackGate(scope = this, repeatCall = {})
        assertFalse(gate.isClosed)

        gate.engage(call())
        assertTrue(gate.isClosed)

        gate.clear()
        assertFalse(gate.isClosed)
    }

    @Test
    fun anIdlePilotIsRePromptedAtTheInterval() = runTest {
        val repeats = mutableListOf<ATCTransmission>()
        val gate = ReadbackGate(scope = this, repeatCall = { repeats += it })
        gate.engage(call())

        advanceTimeBy(ReadbackGate.DEFAULT_REPEAT_INTERVAL_MILLIS + 1)
        assertEquals(1, repeats.size)

        advanceTimeBy(ReadbackGate.DEFAULT_REPEAT_INTERVAL_MILLIS)
        assertEquals(2, repeats.size)

        gate.clear()
    }

    @Test
    fun theControllerStopsNaggingAfterThreePromptsButTheGateStaysClosed() = runTest {
        val repeats = mutableListOf<ATCTransmission>()
        val gate = ReadbackGate(scope = this, repeatCall = { repeats += it })
        gate.engage(call())

        repeat(6) { advanceTimeBy(ReadbackGate.DEFAULT_REPEAT_INTERVAL_MILLIS) }

        assertEquals(
            ReadbackGate.DEFAULT_MAX_PROMPTS,
            repeats.size,
            "the controller must give up after ${ReadbackGate.DEFAULT_MAX_PROMPTS} prompts",
        )
        assertTrue(gate.isClosed, "the flow must not run away once the prompts stop")
        gate.clear()
    }

    @Test
    fun theControllerNeverNagsOverALiveController() = runTest {
        val repeats = mutableListOf<ATCTransmission>()
        var standingBy = true
        val gate = ReadbackGate(
            scope = this,
            isStandingBy = { standingBy },
            repeatCall = { repeats += it },
        )
        gate.engage(call())

        repeat(3) { advanceTimeBy(ReadbackGate.DEFAULT_REPEAT_INTERVAL_MILLIS) }
        assertTrue(repeats.isEmpty(), "the companion must not talk over a staffed controller")

        // Leaving the human frequency resumes the reminder rather than losing it.
        standingBy = false
        advanceTimeBy(ReadbackGate.DEFAULT_REPEAT_INTERVAL_MILLIS)
        assertEquals(1, repeats.size)
        gate.clear()
    }

    @Test
    fun aSilentGateHoldsWithoutEverNagging() = runTest {
        // The takeoff clearance closes the gate so the Departure hand-off cannot stack on
        // it, but a controller does not radio-check a pilot it has just cleared for
        // takeoff.
        val repeats = mutableListOf<ATCTransmission>()
        val gate = ReadbackGate(scope = this, repeatCall = { repeats += it })
        gate.engage(call("Cleared for takeoff runway 27"), promptIfIdle = false)

        repeat(4) { advanceTimeBy(ReadbackGate.DEFAULT_REPEAT_INTERVAL_MILLIS) }

        assertTrue(gate.isClosed)
        assertTrue(repeats.isEmpty(), "the takeoff clearance must hold the gate silently")
        gate.clear()
    }

    @Test
    fun softeningRepointsTheGateAtTheHandoffAndStopsTheNag() = runTest {
        val repeats = mutableListOf<ATCTransmission>()
        val gate = ReadbackGate(scope = this, repeatCall = { repeats += it })
        gate.engage(call("Cleared the ILS runway 27"))

        gate.soften(call("Contact Tower on 118.3"))
        repeat(4) { advanceTimeBy(ReadbackGate.DEFAULT_REPEAT_INTERVAL_MILLIS) }

        assertTrue(gate.isClosed, "the gate still holds for the read-back")
        assertTrue(
            repeats.isEmpty(),
            "a courtesy hand-off must never trigger a how-do-you-read",
        )
        gate.clear()
    }

    @Test
    fun theRepeatAddsTheCallsignOnlyWhenTheCallDidNotOpenWithIt() {
        val withPrefix = call("United 598, cleared to land runway 27.")
        val (display, _) = ReadbackGate.repeatText(withPrefix, "United 598")
        assertEquals("United 598, cleared to land runway 27. How do you read?", display)

        val without = call("Cleared to land runway 27.")
        val (display2, _) = ReadbackGate.repeatText(without, "United 598")
        assertEquals("Cleared to land runway 27. United 598, how do you read?", display2)
    }
}
