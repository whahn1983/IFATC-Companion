package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What happens when the pilot holds the mic key and says it themselves.
 *
 * Two things were wrong. The app synthesized every read-back back at the pilot who had just
 * spoken it — the one thing push-to-talk must never do, since the reply lands on top of
 * their own voice. And "wilco" was answered with the full read-back, reciting an
 * instruction the pilot had deliberately not recited.
 */
class SpokenPilotInputTest {

    private val plan = FlightPlan.empty.copy(
        departure = "KIAH",
        destination = "KMSP",
        airline = "United",
        flightNumber = "598",
        callsign = "United 598",
        cruiseAltitude = 35_000,
    )

    private val spoken = mutableListOf<ATCTransmission>()

    private fun coordinator(scope: TestScope) = FlightSessionCoordinator(
        scope = scope,
        clock = MutableClock(0),
        settingsProvider = { AppSettings(mockMode = true, voiceEnabled = true, speakPilot = true) },
        speak = { spoken += it },
    )

    private fun flying(scope: TestScope): FlightSessionCoordinator {
        val c = coordinator(scope)
        c.ingestFlightPlan(plan)
        c.ingestAircraftState(
            AircraftState(
                latitude = 35.0,
                longitude = -95.0,
                altitudeMSL = 20_000.0,
                altitudeAGL = 20_000.0,
                groundSpeed = 420.0,
                verticalSpeed = 0.0,
                heading = 15.0,
                onGround = false,
            ),
        )
        c.tuneTo(ATCFacility.CENTER)
        c.performPilotAction(PilotAction.CHECK_IN)
        spoken.clear()
        return c
    }

    private fun pilotLines(c: FlightSessionCoordinator) =
        c.state.value.transcript.filter { it.sender == ATCTransmission.Sender.PILOT }

    @Test
    fun `a read-back the pilot spoke is not synthesized back at them`() = runTest {
        val coordinator = flying(this)
        val before = pilotLines(coordinator).size

        coordinator.handleSpokenPilotText("climb and maintain flight level three seven zero, United 598")

        assertTrue(
            pilotLines(coordinator).size > before,
            "the spoken read-back never reached the transcript",
        )
        assertTrue(
            spoken.none { it.sender == ATCTransmission.Sender.PILOT },
            "the app spoke the pilot's own words back at them: ${spoken.map { it.displayText }}",
        )
    }

    @Test
    fun `a read-back the pilot tapped is still spoken`() = runTest {
        // The suppression is scoped to the spoken path only. Tapping Read Back must still
        // put the pilot's voice on the radio, which is what makes the transcript audible.
        val coordinator = flying(this)

        coordinator.readBack()

        assertTrue(
            spoken.any { it.sender == ATCTransmission.Sender.PILOT },
            "the tapped read-back was silent",
        )
    }

    @Test
    fun `the suppression is lifted again after the spoken call`() = runTest {
        val coordinator = flying(this)
        coordinator.handleSpokenPilotText("climb and maintain flight level three seven zero, United 598")
        spoken.clear()

        coordinator.readBack()

        assertTrue(
            spoken.any { it.sender == ATCTransmission.Sender.PILOT },
            "the next tapped read-back stayed silent — the flag was left set",
        )
    }

    @Test
    fun `saying wilco produces a wilco, not the whole read-back`() = runTest {
        val coordinator = flying(this)

        val title = coordinator.handleSpokenPilotText("wilco")

        assertEquals("Wilco", title)
        val line = pilotLines(coordinator).last().displayText
        assertTrue(line.startsWith("Wilco"), "wilco produced: $line")
        assertTrue(line.contains("United 598"), line)
    }

    @Test
    fun `a wilco still acknowledges, so the controller does not nag`() = runTest {
        val coordinator = flying(this)
        coordinator.handleSpokenPilotText("wilco")

        assertTrue(
            !coordinator.state.value.awaitingReadback,
            "the wilco left a read-back outstanding",
        )
    }
}
