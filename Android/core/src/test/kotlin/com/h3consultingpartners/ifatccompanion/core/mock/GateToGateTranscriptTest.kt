package com.h3consultingpartners.ifatccompanion.core.mock

import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.session.FlightSessionCoordinator
import com.h3consultingpartners.ifatccompanion.core.session.PilotAction
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A golden transcript for the scripted gate-to-gate demo flight.
 *
 * The whole product is phraseology, so a changed word *is* a behaviour change — and the
 * kind that no other test would notice, because every other test asserts that a phrase
 * *contains* something. This one pins the entire conversation, sender, frequency and
 * wording, so any edit to any controller call fails here and has to be looked at.
 *
 * It is also the source of `Docs/ANDROID_GATE_TO_GATE_WALKTHROUGH.md`. When this test
 * fails because a call legitimately changed, update both — the document exists so a
 * reviewer can compare the two platforms without running either, and a stale one is worse
 * than none.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GateToGateTranscriptTest {

    @Test
    fun theScriptedFlightProducesTheDocumentedTranscript() = runTest {
        val clock = MutableClock(1_700_000_000_000L)
        val mock = MockSimulatorFeed(scope = this, clock = clock)
        val settings = AppSettings(
            voiceEnabled = false,
            mockMode = true,
            initialClimbAltitudeFt = 5000,
            traconCeilingFL = 180,
        )
        val model = FlightSessionCoordinator(scope = this, clock = clock, settingsProvider = { settings })
        model.ingestFlightPlan(
            FlightPlan(
                airline = "United",
                flightNumber = "598",
                departure = "KIAH",
                destination = "KMSP",
                cruiseAltitude = 37000,
                star = "KKILR",
                approach = "ILS 30L",
                waypoints = mock.route.waypoints,
            ),
        )

        fun feed(phase: FlightPhase, times: Int = 1) {
            repeat(times) { model.ingestAircraftState(mock.stateFor(phase)) }
        }

        // The pilot-driven pre-departure flow, then the position-triggered calls, with a
        // read-back after each substantive instruction — exactly the iOS scenario.
        model.performPilotAction(PilotAction.CLEARANCE); model.readBack()
        model.performPilotAction(PilotAction.PUSHBACK); model.readBack()
        model.performPilotAction(PilotAction.ENGINE_START); model.readBack()
        model.performPilotAction(PilotAction.TAXI); model.readBack()
        model.performPilotAction(PilotAction.TAXI); model.readBack()
        model.performPilotAction(PilotAction.READY); model.readBack()
        feed(FlightPhase.TAKEOFF); model.readBack()
        feed(FlightPhase.INITIAL_CLIMB); model.readBack()
        feed(FlightPhase.CLIMB); model.readBack()
        feed(FlightPhase.CRUISE)
        feed(FlightPhase.DESCENT); model.readBack()
        feed(FlightPhase.APPROACH, times = 2); model.readBack()
        feed(FlightPhase.LANDING, times = 2); model.readBack()
        feed(FlightPhase.TAXI_IN); model.readBack()
        feed(FlightPhase.PARKED)

        val actual = model.state.value.transcript.map { tx ->
            "${tx.sender.name}|${tx.facility.title}|${tx.displayText}"
        }

        // Compared as one joined string so a failure prints the whole conversation with the
        // difference in place, rather than "expected 39 but was 38".
        assertEquals(EXPECTED.joinToString("\n"), actual.joinToString("\n"))
    }

    private companion object {
        /**
         * The transcript in `Docs/ANDROID_GATE_TO_GATE_WALKTHROUGH.md`, verbatim.
         *
         * Frequencies are the engine defaults. The taxi routes are the deterministic
         * fallback planner's — the scripted run has no OpenStreetMap surface behind it, and
         * a live route would supersede them. The wind is the no-report default (270 at 8),
         * which is also what the runway in use is picked from, exactly as in the iOS
         * scenario.
         */
        val EXPECTED = listOf(
        "PILOT|Clearance|Clearance, United 598, request IFR clearance to KMSP.",
        "ATC|Clearance|United 598, cleared to KMSP via the filed route, climb via SID except maintain 5,000, expect FL370 one zero minutes after departure, departure frequency 124.300, squawk 0133. When ready for pushback, contact Ramp on 131.000.",
        "PILOT|Clearance|Cleared to KMSP, climb via SID except maintain 5,000, squawk 0133, United 598.",
        "PILOT|Ramp|Ramp, United 598, ready to push.",
        "ATC|Ramp|United 598, pushback approved, advise ready to taxi.",
        "PILOT|Ramp|Pushback approved, United 598.",
        "PILOT|Ramp|Ramp, United 598, request engine start.",
        "ATC|Ramp|United 598, start approved.",
        "PILOT|Ramp|Start approved, United 598.",
        "PILOT|Ground|Ground, United 598, request taxi.",
        "ATC|Ramp|United 598, contact Ground on 121.800.",
        "ATC|Ground|United 598, taxi to runway 27 via A, E. Contact Tower when ready.",
        "PILOT|Ground|Taxi to runway 27 via A, E, United 598.",
        "PILOT|Ground|Ground, United 598, request taxi.",
        "PILOT|Ground|Taxi to runway 27 via A, E, United 598.",
        "PILOT|Tower|Tower, United 598, holding short runway 27, ready for departure.",
        "ATC|Ground|United 598, contact Tower on 118.300.",
        "ATC|Tower|United 598, runway 27, line up and wait.",
        "PILOT|Tower|Runway 27, line up and wait, United 598.",
        "ATC|Tower|United 598, wind 270 at 8, runway 27, cleared for takeoff, fly heading 007, climb and maintain 6,000.",
        "PILOT|Tower|Runway 27, cleared for takeoff, heading 007, climb and maintain 6,000, United 598.",
        "ATC|Tower|United 598, contact Departure on 124.300.",
        "ATC|Departure|United 598, radar contact, climb and maintain FL180, resume own navigation, direct TBONE.",
        "PILOT|Departure|Climb and maintain FL180, resume own navigation, direct TBONE, United 598.",
        "ATC|Departure|United 598, contact Center on 132.450.",
        "ATC|Center|United 598, radar contact, climb and maintain FL370.",
        "PILOT|Center|Climb and maintain FL370, United 598.",
        "ATC|Center|United 598, descend via the KKILR arrival, maintain 11,000 crossing KKILR.",
        "PILOT|Center|Descend via the KKILR arrival, United 598.",
        "ATC|Center|United 598, contact Approach on 119.700.",
        "ATC|Approach|United 598, descend and maintain 4,000, expect the ILS runway 30L approach.",
        "ATC|Approach|United 598, cleared ILS RWY 30L approach.",
        "ATC|Approach|United 598, contact Tower on 118.300.",
        "PILOT|Tower|Contacting Tower on 118.300, United 598.",
        "ATC|Tower|United 598, wind 270 at 8, runway 30L, cleared to land.",
        "ATC|Tower|United 598, exit the runway when able, contact Ground on 121.800 once on the taxiway.",
        "PILOT|Tower|Exiting the runway, contact Ground, United 598.",
        "ATC|Ground|United 598, taxi to parking via A.",
        "PILOT|Ground|Taxi to parking via A, United 598.",
        )
    }
}
