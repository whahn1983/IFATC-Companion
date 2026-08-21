package com.h3consultingpartners.ifatccompanion.core.weather.deviation

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.phraseology.CallsignDigitStyle
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyEngine
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine/phraseology unit cases from
 * `IFATCCompanionTests/WeatherDeviationFlowTests.swift`.
 *
 * The rest of that file drives the whole `AppModel` through the offline mock demo
 * (telemetry ingestion, mint-line locking, background resync, strategic previews); those
 * belong with the app-level coordinator and are not ported here.
 */
class WeatherDeviationFlowTest {

    private fun phraseology(): WeatherDeviationPhraseology = WeatherDeviationPhraseology(
        PhraseologyEngine(digitStyle = CallsignDigitStyle.INDIVIDUAL, mode = PhraseologyMode.FAA),
    )

    /**
     * When a held turn genuinely can no longer be flown — the turn-out is behind the
     * aircraft and no revised line solves from where it is — the clearance is cancelled
     * **out loud**. The pilot was told to continue on course and expect a turn, so dropping
     * it in silence leaves them flying toward a turn that never comes.
     */
    @Test
    fun cancelledHeldDeviationIsAnnouncedAndEndsTheLifecycle() {
        val phr = phraseology()
        val eng = WeatherDeviationEngine(phr)
        val cs = phr.engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val ctx = WeatherDeviationContext(state = WeatherDeviationState.DEVIATION_APPROVED)

        val result = eng.cancelHeldDeviation(cs = cs, context = ctx, facility = ATCFacility.CENTER)

        assertNull(result.pilot, "the controller initiates the cancellation")
        val atc = assertNotNull(result.atc.firstOrNull(), "expected a cancellation call")
        assertTrue(atc.displayText.contains("weather deviation cancelled"), atc.displayText)
        assertTrue(atc.displayText.contains("resume own navigation"), atc.displayText)
        assertTrue(
            atc.readback?.displayText?.contains("Resume own navigation") ?: false,
            atc.readback?.displayText ?: "",
        )
        assertEquals(
            WeatherDeviationState.RESUMED_OWN_NAVIGATION,
            result.context.state,
            "the deviation is no longer committed",
        )
    }

    // MARK: - Read-back phraseology (unit)

    /** The weather vector assigns a heading and an altitude; the read-back echoes both. */
    @Test
    fun weatherVectorReadbackEchoesHeadingAndAltitude() {
        val phr = phraseology()
        val cs = phr.engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val tx = phr.vectorApproval(cs = cs, heading = 90, maintainAltitude = 37000)
        val rb = tx.readback
        assertNotNull(rb, "weather vector must carry a read-back")
        assertTrue(rb.displayText.contains("Heading 090"), rb.displayText)
        assertTrue(rb.displayText.contains("maintain FL370"), rb.displayText)
        assertTrue(rb.displayText.contains("United 598"), rb.displayText)
    }

    /**
     * An intermediate turn (onto the parallel leg of a side-hug) keeps vectoring and
     * must NOT claim to rejoin course; the final turn does rejoin, naming the fix.
     */
    @Test
    fun rejoinInterceptVectorDistinguishesIntermediateFromFinalTurn() {
        val phr = phraseology()
        val cs = phr.engine.callsign(airline = "United", flightNumber = "598", fallback = "")

        val intermediate = phr.rejoinInterceptVector(
            cs = cs, heading = 120, rejoinFix = "WAGON", finalTurn = false,
        )
        assertTrue(intermediate.displayText.contains("fly heading 120"), intermediate.displayText)
        assertTrue(
            intermediate.displayText.contains("vectors around precipitation"),
            intermediate.displayText,
        )
        assertFalse(
            intermediate.displayText.contains("rejoin course"),
            "an intermediate turn is not yet rejoining course",
        )

        val final = phr.rejoinInterceptVector(cs = cs, heading = 150, rejoinFix = "WAGON", finalTurn = true)
        assertTrue(final.displayText.contains("fly heading 150"), final.displayText)
        assertTrue(final.displayText.contains("rejoin course direct WAGON"), final.displayText)
    }

    /** "Resume own navigation" (with and without a rejoin fix) is echoed in the read-back. */
    @Test
    fun clearOfWeatherReadbackIncludesResumeOwnNavigation() {
        val phr = phraseology()
        val cs = phr.engine.callsign(airline = "United", flightNumber = "598", fallback = "")

        val noFix = phr.clearOfWeatherResume(cs = cs, rejoinFix = null, nearRoute = true)
        assertTrue(
            noFix.readback?.displayText?.contains("Resume own navigation") ?: false,
            noFix.readback?.displayText ?: "",
        )

        val withFix = phr.clearOfWeatherResume(cs = cs, rejoinFix = "WAGON", nearRoute = false)
        assertTrue(
            withFix.readback?.displayText?.contains("resume own navigation") ?: false,
            withFix.readback?.displayText ?: "",
        )
        assertTrue(
            withFix.readback?.displayText?.contains("Direct WAGON") ?: false,
            withFix.readback?.displayText ?: "",
        )
    }

    /** Every weather deviation approval echoes the maintain altitude in its read-back. */
    @Test
    fun deviationApprovalReadbacksEchoMaintainAltitude() {
        val phr = phraseology()
        val cs = phr.engine.callsign(airline = "United", flightNumber = "598", fallback = "")

        val rejoin = phr.approvalWithRejoin(
            cs = cs, direction = DeviationDirection.RIGHT, degrees = 20,
            maintainAltitude = 37000, rejoinFix = "WAGON",
        )
        assertTrue(
            rejoin.readback?.displayText?.contains("Maintain FL370") ?: false,
            rejoin.readback?.displayText ?: "",
        )
        assertTrue(
            rejoin.readback?.displayText?.contains("WAGON") ?: false,
            rejoin.readback?.displayText ?: "",
        )

        val noRejoin = phr.approvalNoRejoin(
            cs = cs, direction = DeviationDirection.LEFT, degrees = 15, maintainAltitude = 34000,
        )
        assertTrue(
            noRejoin.readback?.displayText?.contains("Maintain FL340") ?: false,
            noRejoin.readback?.displayText ?: "",
        )

        val star = phr.starDeviationApproval(
            cs = cs, direction = DeviationDirection.RIGHT, degrees = 20, maintainAltitude = 11000,
            starDisplay = "KKILR", starSpoken = "killer", rejoinFix = "HOBTT",
        )
        assertTrue(
            star.readback?.displayText?.contains("Maintain 11,000") ?: false,
            star.readback?.displayText ?: "",
        )
        assertTrue(
            star.readback?.displayText?.contains("HOBTT") ?: false,
            star.readback?.displayText ?: "",
        )
    }

    /**
     * Pilot weather requests address whatever controller is working the flight —
     * Approach on arrival, Departure on climb — not a hard-coded "Center".
     */
    @Test
    fun pilotWeatherRequestsAddressTunedFacility() {
        val phr = phraseology()
        val cs = phr.engine.callsign(airline = "United", flightNumber = "598", fallback = "")

        val approach = phr.pilotRequestDeviation(
            cs = cs, direction = DeviationDirection.RIGHT, degrees = 20, facility = ATCFacility.APPROACH,
        )
        assertTrue(approach.displayText.startsWith("Approach,"), approach.displayText)
        assertTrue(approach.spokenText.startsWith("Approach,"), approach.spokenText)

        val departure = phr.pilotRequestVectors(cs = cs, facility = ATCFacility.DEPARTURE)
        assertTrue(departure.displayText.startsWith("Departure,"), departure.displayText)

        val center = phr.pilotRequestAltitude(cs = cs, higher = true, facility = ATCFacility.CENTER)
        assertTrue(center.displayText.startsWith("Center,"), center.displayText)
    }

    /** Rejoining the STAR echoes the direct fix and the descend-via clearance. */
    @Test
    fun rejoinStarReadbackEchoesDirectFixAndDescendVia() {
        val phr = phraseology()
        val cs = phr.engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val tx = phr.rejoinStar(cs = cs, rejoinFix = "HOBTT", starDisplay = "KKILR", starSpoken = "killer")
        assertTrue(
            tx.readback?.displayText?.contains("Direct HOBTT") ?: false,
            tx.readback?.displayText ?: "",
        )
        assertTrue(
            tx.readback?.displayText?.contains("descend via the KKILR arrival") ?: false,
            tx.readback?.displayText ?: "",
        )
    }

    /** A weather altitude change (higher/lower) echoes the assigned altitude. */
    @Test
    fun weatherAltitudeChangeReadbackEchoesAltitude() {
        val phr = phraseology()
        val eng = WeatherDeviationEngine(phr)
        val cs = phr.engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val result = eng.requestAltitude(
            cs = cs, higher = false, targetAltitude = 33000,
            context = WeatherDeviationContext(), facility = ATCFacility.CENTER,
        )
        val atc = result.atc.firstOrNull()
        assertTrue(
            atc?.readback?.displayText?.contains("Descend and maintain FL330") ?: false,
            atc?.readback?.displayText ?: "",
        )
    }
}
