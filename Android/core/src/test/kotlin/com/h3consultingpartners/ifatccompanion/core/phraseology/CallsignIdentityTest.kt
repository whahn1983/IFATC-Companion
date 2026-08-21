package com.h3consultingpartners.ifatccompanion.core.phraseology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Who the flight is — the callsign the controller actually uses.
 *
 * Every call is built from the plan's *airline/flight-number pair*; the raw callsign is
 * only the fallback used when that pair is empty. So the pair has to be kept in step with
 * the pilot's own fields, or the app shows one callsign in the Flight tab while ATC says
 * another.
 *
 * Regression: loading a saved flight restored the pilot's callsign into Settings but left
 * whatever airline/flight number the plan happened to carry — the demo's United 598, or
 * the identity of the flight that was live a moment earlier. The Flight tab showed the
 * right callsign, ATC used the wrong one, and the only way out was to edit the callsign
 * field (delete a digit, type it back) to force it through by hand.
 *
 * Ported from `IFATCCompanionTests/CallsignIdentityTests.swift`. That suite drives
 * `AppModel`/`SavedFlightStore`, which belong to the orchestrator port; what lives in
 * this package is the resolution step `AppModel.applyFlightIdentity` leans on —
 * `AirlineDatabase.parse` plus the callsign the engine then speaks. The loading and
 * Mock-Mode cases belong with the AppModel port and are not duplicated here.
 */
class CallsignIdentityTest {

    private fun engine() = PhraseologyEngine(
        digitStyle = CallsignDigitStyle.INDIVIDUAL,
        mode = PhraseologyMode.FAA,
    )

    /**
     * A saved flight's callsign resolves to the airline and flight number the controller
     * uses: "DAL221" is called "Delta 221", not spelled out and not left as the previous
     * flight's identity.
     */
    @Test
    fun testACallsignResolvesTheAirlineAndFlightNumber() {
        val parsed = AirlineDatabase.parse("DAL221")
        assertEquals("Delta", parsed?.telephony)
        assertEquals("221", parsed?.flightNumber)
        assertEquals(
            "Delta 221",
            engine().displayCallsign(airline = "Delta", flightNumber = "221"),
        )
    }

    /**
     * A callsign naming no airline — a tail number — is spelled out. It resolves to no
     * airline at all, which is what lets the AppModel clear the stale pair instead of
     * flying on under the previous flight's number while the field reads N123AB.
     */
    @Test
    fun testATailNumberResolvesToNoAirlineAndIsSpelledOut() {
        assertNull(AirlineDatabase.parse("N123AB"))
        assertEquals(
            "N123AB",
            engine().displayCallsign(airline = "", flightNumber = "", fallback = "N123AB"),
        )
        assertEquals(
            "November one two tree Alpha Bravo",
            engine().copy(mode = PhraseologyMode.ICAO)
                .spokenCallsign(airline = "", flightNumber = "", fallback = "N123AB"),
        )
    }

    /**
     * A pilot who filled in Airline / Flight # instead of a callsign gets those back
     * verbatim — "Speedbird 12", not the designator.
     */
    @Test
    fun testAPinnedAirlineAndFlightNumberAreUsedAsEntered() {
        assertEquals(
            "Speedbird 12",
            engine().displayCallsign(airline = "Speedbird", flightNumber = "12"),
        )
        assertEquals(
            "Speedbird one two",
            engine().spokenCallsign(airline = "Speedbird", flightNumber = "12"),
        )
    }

    /**
     * With neither a pair nor a fallback there is still someone to talk to: the engine
     * says "aircraft" rather than producing an empty callsign.
     */
    @Test
    fun testNoIdentityAtAllStillAddressesTheAircraft() {
        assertEquals("aircraft", engine().spokenCallsign(airline = "", flightNumber = ""))
        assertEquals("Aircraft", engine().displayCallsign(airline = "", flightNumber = ""))
    }
}
