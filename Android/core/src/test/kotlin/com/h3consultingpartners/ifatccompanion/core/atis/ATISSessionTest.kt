package com.h3consultingpartners.ifatccompanion.core.atis

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the ATIS session rules: availability gating (at the gate, and within 100 NM
 * on arrival), and appending the received information code to the taxi request and the
 * Approach check-in — including graceful absence when no ATIS exists.
 *
 * Ported from `IFATCCompanionTests/ATISAppModelTests.swift`, whose subject is the ATIS
 * extension of `AppModel`. The rules themselves live in [ATISSession] here; the two
 * end-to-end iOS cases that drive the whole conversation to prove the code reaches the
 * taxi request and the Approach check-in are noted on `consumeATISInfoWord` below, and
 * belong to the session coordinator's own suite once it appends the word.
 */
class ATISSessionTest {

    private val now = 1_700_000_000_000L

    /** KIAH and KMSP, and a fix ~45 NM north of KMSP. */
    private val kiah = Coordinate(29.98, -95.34)
    private val kmsp = Coordinate(44.88, -93.22)
    private val nearKMSP = Coordinate(45.6, -93.22)

    private fun gateContext() = ATISSession.Context(
        isPreDeparture = true,
        hasDeparted = false,
        atcState = ATCState.CLEARANCE,
        position = kiah,
        destinationCoordinate = kmsp,
        departureICAO = "KIAH",
        destinationICAO = "KMSP",
    )

    private fun arrivalContext(position: Coordinate = nearKMSP) = ATISSession.Context(
        isPreDeparture = false,
        hasDeparted = true,
        atcState = ATCState.DESCENT,
        position = position,
        destinationCoordinate = kmsp,
        departureICAO = "KIAH",
        destinationICAO = "KMSP",
    )

    private fun report(icao: String, letter: String) = AirportATIS(
        airport = icao,
        parts = listOf(
            AirportATIS.Part(
                kind = AirportATIS.Kind.COMBINED,
                letter = letter,
                text = "$icao INFORMATION ${ATISPhraseology.phoneticLetter(letter)}.",
            ),
        ),
        fetchedAtMillis = now,
    )

    // region Availability

    @Test
    fun noButtonWhenNoATIS() {
        val session = ATISSession()
        assertNull(session.currentATIS(gateContext()))
        assertFalse(session.atisButtonVisible(gateContext()))
    }

    @Test
    fun departureATISAvailableAtGate() {
        val session = ATISSession()
        session.departureATIS = report("KIAH", "C")
        val ctx = gateContext()
        assertTrue(session.departureATISAvailable(ctx))
        assertTrue(session.atisButtonVisible(ctx))
        assertFalse(session.currentATISIsArrival(ctx))
        assertEquals("C", session.currentATISCode(ctx))
        assertEquals("Info C", session.atisButtonSubtitle(ctx))
        assertEquals("KIAH", session.atisAirport(ctx))
    }

    @Test
    fun arrivalATISAvailableWithin100NM() {
        val session = ATISSession()
        session.arrivalATIS = report("KMSP", "D")
        // Airborne ~45 NM north of KMSP → within the 100 NM arrival window.
        val ctx = arrivalContext()

        assertTrue(session.withinArrivalATISRange(ctx))
        assertTrue(session.arrivalATISAvailable(ctx))
        assertTrue(session.currentATISIsArrival(ctx))
        assertEquals("D", session.currentATISCode(ctx))
    }

    @Test
    fun arrivalATISHiddenBeyond100NM() {
        val session = ATISSession()
        session.arrivalATIS = report("KMSP", "D")
        // Airborne over KIAH (~900 NM from KMSP) → out of range.
        val ctx = arrivalContext(position = kiah)

        assertFalse(session.withinArrivalATISRange(ctx))
        assertFalse(session.arrivalATISAvailable(ctx))
    }

    /**
     * Parked is the end of the flight: the arrival ATIS button goes away even inside the
     * 100 NM window, because there is nothing left to copy it for.
     */
    @Test
    fun arrivalATISHiddenOnceParked() {
        val session = ATISSession()
        session.arrivalATIS = report("KMSP", "D")
        assertFalse(
            session.arrivalATISAvailable(arrivalContext().copy(atcState = ATCState.PARKED)),
        )
    }

    // endregion

    // region Reconnect stability (the app-switch flap)

    @Test
    fun reconnectKeepsReceivedATIS() {
        val session = ATISSession()
        session.departureATIS = report("KIAH", "C")
        session.arrivalATIS = report("KMSP", "D")
        val ctx = gateContext()
        assertTrue(session.departureATISAvailable(ctx))
        assertTrue(session.diagnostics(ctx).departureReceived)
        assertTrue(session.diagnostics(ctx).arrivalReceived)
        assertEquals("C", session.diagnostics(ctx).departureLetter)

        // Returning from another app runs disconnect()/startLive(), which resets ATIS with
        // clearReported = false. That must NOT blank an ATIS already received for the
        // ongoing flight — nulling it made the Diagnostics line flap "received → not
        // available → received" on every app switch, until the connect-time refresh
        // re-populated it.
        session.reset(clearReported = false)
        assertTrue(
            session.departureATISAvailable(ctx),
            "a reconnect must keep the received departure ATIS",
        )
        assertTrue(session.diagnostics(ctx).departureReceived)
        assertTrue(session.diagnostics(ctx).arrivalReceived)
        assertEquals("C", session.diagnostics(ctx).departureLetter)

        // A genuinely fresh flight (clearReported = true) still wipes everything.
        session.reset(clearReported = true)
        assertFalse(session.departureATISAvailable(ctx))
        assertFalse(session.diagnostics(ctx).departureReceived)
        assertFalse(session.diagnostics(ctx).arrivalReceived)
    }

    // endregion

    // region Tune button: activation & per-phase dismissal

    @Test
    fun playingATISCapturesCodeAndStaysOnCurrentFrequency() {
        val session = ATISSession()
        val ctx = gateContext()
        session.departureATIS = report("KIAH", "C")
        assertTrue(session.atisButtonVisible(ctx))

        val line = session.applyTunedATIS(report("KIAH", "C"), arrival = false, nowMillis = now)
        // ATIS is a momentary listen: it plays and captures the code but never becomes the
        // active/tuned frequency — the pilot stays on their current frequency, so the line
        // is a one-way SYSTEM broadcast rather than a controller call.
        assertNotNull(line)
        assertEquals(ATCTransmission.Sender.SYSTEM, line.sender)
        assertEquals(ATCFacility.GROUND, line.facility)
        assertTrue(line.isATISLine, "an ATIS line is never a controller instruction")
        assertNull(line.readback, "and is never read back")
        assertEquals("C", session.currentATISCode(ctx))
        assertEquals(
            "KIAH departure information Charlie — added to your taxi request.",
            session.atisReceiptSummary(ctx),
        )
    }

    /** An ATIS that vanished between the button appearing and the tune drops the report. */
    @Test
    fun tuningAnAtisThatVanishedDropsIt() {
        val session = ATISSession()
        session.departureATIS = report("KIAH", "C")
        assertNull(session.applyTunedATIS(null, arrival = false, nowMillis = now))
        assertNull(session.departureATIS)
        assertFalse(session.atisButtonVisible(gateContext()))
    }

    @Test
    fun departureATISButtonHidesAfterTuningAway() {
        val session = ATISSession()
        val ctx = gateContext()
        session.departureATIS = report("KIAH", "C")
        session.applyTunedATIS(report("KIAH", "C"), arrival = false, nowMillis = now)
        assertTrue(
            session.atisButtonVisible(ctx),
            "the ATIS button stays available after listening",
        )

        // Tuning any controller / ramp frequency means the pilot has moved on: the ATIS
        // button drops out of the grid for this phase.
        session.leaveATISFrequency(ctx)
        assertFalse(
            session.atisButtonVisible(ctx),
            "ATIS button hides once the pilot tunes a controller",
        )
    }

    @Test
    fun tuningAwayAtGateStillLetsArrivalATISReappear() {
        val session = ATISSession()
        session.departureATIS = report("KIAH", "C")
        session.arrivalATIS = report("KMSP", "D")
        // Leave the departure ATIS at the gate.
        val gate = gateContext()
        session.leaveATISFrequency(gate)
        assertFalse(session.atisButtonVisible(gate))

        // Airborne within 100 NM of KMSP → the arrival ATIS window opens and the button
        // returns even though the departure ATIS was dismissed (dismissal is per phase).
        val arrival = arrivalContext()
        assertTrue(session.currentATISIsArrival(arrival))
        assertTrue(
            session.atisButtonVisible(arrival),
            "arrival ATIS button reappears within 100 NM",
        )

        // And leaving the arrival ATIS hides it again.
        session.leaveATISFrequency(arrival)
        assertFalse(session.atisButtonVisible(arrival))
    }

    /**
     * An early tune — before the feed has delivered anything — must not permanently hide
     * an ATIS that arrives a moment later, which is why the dismissal is guarded on the
     * report actually being available.
     */
    @Test
    fun leavingBeforeTheFeedArrivesDoesNotHideALaterATIS() {
        val session = ATISSession()
        session.leaveATISFrequency(gateContext())
        session.departureATIS = report("KIAH", "C")
        assertTrue(session.atisButtonVisible(gateContext()))
    }

    // endregion

    // region Refresh cadence

    /**
     * The opportunistic arrival fetch fires once the aircraft comes within range, then
     * backs off to one attempt a minute — the telemetry loop calls it at 1 Hz.
     */
    @Test
    fun theArrivalFetchIsThrottledToOneAttemptAMinute() {
        val session = ATISSession()
        val ctx = arrivalContext()
        assertTrue(session.shouldAttemptArrivalFetch(ctx, now))
        assertFalse(session.shouldAttemptArrivalFetch(ctx, now + 1_000))
        assertFalse(session.shouldAttemptArrivalFetch(ctx, now + 59_999))
        assertTrue(session.shouldAttemptArrivalFetch(ctx, now + 60_000))

        // Out of range, already held, or parked: never.
        assertFalse(session.shouldAttemptArrivalFetch(arrivalContext(kiah), now + 200_000))
        session.arrivalATIS = report("KMSP", "D")
        assertFalse(session.shouldAttemptArrivalFetch(ctx, now + 200_000))
    }

    /**
     * ATIS is a real-world, live-data feature keyed to the actual flight. Mock Mode is a
     * scripted demo, so it never reaches the network — the feature simply isn't there.
     */
    @Test
    fun mockModeNeverFetches() {
        val session = ATISSession()
        assertFalse(session.shouldRefreshDeparture(gateContext().copy(mockMode = true)))
        assertFalse(session.shouldRefreshArrival(arrivalContext().copy(mockMode = true)))
        assertFalse(
            session.shouldAttemptArrivalFetch(arrivalContext().copy(mockMode = true), now),
        )
        assertTrue(session.shouldRefreshDeparture(gateContext()))
        assertTrue(session.shouldRefreshArrival(arrivalContext()))
    }

    /** A field with no name in the plan is never requested. */
    @Test
    fun anUnnamedFieldIsNeverRefreshed() {
        val session = ATISSession()
        assertFalse(session.shouldRefreshDeparture(gateContext().copy(departureICAO = "")))
        assertFalse(session.shouldRefreshArrival(arrivalContext().copy(destinationICAO = "")))
    }

    // endregion

    // region Appending the information code

    private fun taxiRequest() = ATCTransmission(
        sender = ATCTransmission.Sender.PILOT,
        facility = ATCFacility.GROUND,
        displayText = "Ground, United 598, request taxi.",
        spokenText = "Ground, United five niner eight, request taxi.",
        timestampMillis = now,
    )

    @Test
    fun appendingATISInfoHelper() {
        val tx = taxiRequest()
        val out = ATISSession.appendingATISInfo(tx, "Alpha")
        assertEquals("Ground, United 598, request taxi, information Alpha.", out.displayText)
        assertTrue(out.spokenText.endsWith(", information Alpha."))
        // A null word leaves the transmission untouched.
        assertEquals(tx.displayText, ATISSession.appendingATISInfo(tx, null).displayText)
        assertEquals(tx.displayText, ATISSession.appendingATISInfo(tx, "  ").displayText)
    }

    /**
     * The taxi request carries the received information code **once**: the coordinator
     * consumes the word when it builds the Ground taxi request, and a second request must
     * not repeat it. (The iOS test drives the whole pre-departure conversation to prove
     * the word reaches the transcript; that half belongs to the session coordinator.)
     */
    @Test
    fun taxiRequestAppendsReceivedInformationOnce() {
        val session = ATISSession()
        session.reportedDepartureInfo = "A"

        val first = ATISSession.appendingATISInfo(
            taxiRequest(),
            session.consumeATISInfoWord(arrival = false),
        )
        assertTrue(
            first.displayText.contains("request taxi, information Alpha"),
            first.displayText,
        )

        val second = ATISSession.appendingATISInfo(
            taxiRequest(),
            session.consumeATISInfoWord(arrival = false),
        )
        assertFalse(second.displayText.contains("information"), second.displayText)
    }

    @Test
    fun taxiRequestOmitsInformationWhenNoATIS() {
        val session = ATISSession() // no ATIS received
        val taxi = ATISSession.appendingATISInfo(
            taxiRequest(),
            session.consumeATISInfoWord(arrival = false),
        )
        assertFalse(taxi.displayText.contains("information"), taxi.displayText)
    }

    /**
     * The arrival code is reported to Approach exactly once, and the departure and
     * arrival memories are independent — copying the departure ATIS must not consume the
     * arrival one.
     */
    @Test
    fun approachCheckInAppendsArrivalInformationOnce() {
        val session = ATISSession()
        session.reportedArrivalInfo = "B"

        assertEquals("Bravo", session.consumeATISInfoWord(arrival = true))
        assertNull(
            session.consumeATISInfoWord(arrival = true),
            "arrival ATIS code should be reported to Approach exactly once",
        )
        assertNull(
            session.consumeATISInfoWord(arrival = false),
            "the departure code was never received, so nothing is reported for it",
        )
    }

    // endregion
}
