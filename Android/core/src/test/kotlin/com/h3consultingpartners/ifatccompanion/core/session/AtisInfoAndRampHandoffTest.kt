package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyEngine
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import com.h3consultingpartners.ifatccompanion.core.weather.METAR
import com.h3consultingpartners.ifatccompanion.core.weather.SIGMET
import com.h3consultingpartners.ifatccompanion.core.weather.SmootherAltitude
import com.h3consultingpartners.ifatccompanion.core.weather.radar.RadarOverlayModel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two things the pilot says on the ground.
 *
 * The ATIS information code is reported once per leg — on the departure taxi request and on
 * the first Approach check-in of the arrival. The Android UI promised it ("the information
 * code is added to your taxi request") while nothing added it.
 *
 * And at a ramp-controlled field, Taxi on the Ramp frequency is a hand-off, not a clearance:
 * push complete, contact Ground, *then* ask Ground for the route. Android answered it in one
 * step, with the ramp controller clearing the aircraft into a movement area it does not own.
 */
class AtisInfoAndRampHandoffTest {

    /** Hands out "Alpha" once for the departure and "Bravo" once for the arrival. */
    private class OneShotAtis : WeatherAnswering {
        var departureTaken = false
        var arrivalTaken = false

        override fun atisInfoWord(arriving: Boolean): String? = if (arriving) {
            if (arrivalTaken) null else "Bravo".also { arrivalTaken = true }
        } else {
            if (departureTaken) null else "Alpha".also { departureTaken = true }
        }

        override suspend fun rideReport(callsign: PhraseologyEngine.Callsign): ATCTransmission? = null
        override suspend fun destinationWeather(
            callsign: PhraseologyEngine.Callsign,
            icao: String,
        ): ATCTransmission? = null
        override fun smootherAltitude(): SmootherAltitude? = null
        override fun clearSmootherAltitude() = Unit
        override fun altitudeIsBlockedByRideReports(altitudeFt: Int): Boolean = false
        override fun metar(arriving: Boolean): METAR? = null
        override fun radarOverlay(): RadarOverlayModel = RadarOverlayModel()
        override fun routeSigmets(): List<SIGMET> = emptyList()
    }

    // KIAH has a ramp layer; KAUS does not.
    private val plan = FlightPlan.empty.copy(
        departure = "KIAH",
        destination = "KMSP",
        airline = "United",
        flightNumber = "598",
        callsign = "United 598",
        cruiseAltitude = 35_000,
    )

    private fun coordinator(scope: TestScope, atis: WeatherAnswering = OneShotAtis()) =
        FlightSessionCoordinator(
            scope = scope,
            clock = MutableClock(0),
            settingsProvider = { AppSettings(mockMode = true, voiceEnabled = false) },
            weatherAnswers = atis,
        )

    private fun lines(c: FlightSessionCoordinator) = c.state.value.transcript.map { it.displayText }

    private fun pilotLines(c: FlightSessionCoordinator) = c.state.value.transcript
        .filter { it.sender == ATCTransmission.Sender.PILOT }
        .map { it.displayText }

    // region The ramp hand-off

    @Test
    fun `taxi on the ramp frequency hands the pilot to Ground instead of clearing them`() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.tuneTo(ATCFacility.RAMP)

        coordinator.performPilotAction(PilotAction.TAXI)

        val all = lines(coordinator)
        assertTrue(all.any { it.contains("push complete") }, all.toString())
        assertTrue(all.any { it.contains("contact Ground") }, all.toString())
        assertTrue(
            all.none { it.contains("taxi to runway", ignoreCase = true) },
            "the ramp controller issued a movement-area clearance: $all",
        )
    }

    @Test
    fun `the hand-off moves the radio to Ground`() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.tuneTo(ATCFacility.RAMP)
        coordinator.performPilotAction(PilotAction.TAXI)

        assertEquals(ATCFacility.GROUND, coordinator.state.value.currentFacility)
    }

    @Test
    fun `asking Ground then gives the real taxi clearance`() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.tuneTo(ATCFacility.RAMP)
        coordinator.performPilotAction(PilotAction.TAXI)

        coordinator.performPilotAction(PilotAction.TAXI)

        assertTrue(
            lines(coordinator).any { it.contains("taxi to runway", ignoreCase = true) },
            lines(coordinator).toString(),
        )
    }

    @Test
    fun `the hand-off is not announced twice`() = runTest {
        // The ramp controller has already said "contact Ground". The state machine, still
        // seeing a Ramp state behind it, used to say it again.
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.tuneTo(ATCFacility.RAMP)
        coordinator.performPilotAction(PilotAction.TAXI)
        coordinator.performPilotAction(PilotAction.TAXI)

        assertEquals(
            1,
            lines(coordinator).count { it.contains("contact Ground") },
            lines(coordinator).toString(),
        )
    }

    @Test
    fun `a field with no ramp layer still taxis in one step`() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan.copy(departure = "KAUS"))
        coordinator.tuneTo(ATCFacility.GROUND)

        coordinator.performPilotAction(PilotAction.TAXI)

        assertTrue(
            lines(coordinator).any { it.contains("taxi to runway", ignoreCase = true) },
            lines(coordinator).toString(),
        )
    }

    // endregion

    // region The ATIS information code

    @Test
    fun `the taxi request reports the departure ATIS code`() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.tuneTo(ATCFacility.GROUND)

        coordinator.performPilotAction(PilotAction.TAXI)

        assertTrue(
            pilotLines(coordinator).any { it.contains("request taxi, information Alpha") },
            pilotLines(coordinator).toString(),
        )
    }

    @Test
    fun `it is reported once, not on every taxi request`() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.tuneTo(ATCFacility.GROUND)
        coordinator.performPilotAction(PilotAction.TAXI)
        coordinator.performPilotAction(PilotAction.TAXI)

        assertEquals(
            1,
            pilotLines(coordinator).count { it.contains("information Alpha") },
            pilotLines(coordinator).toString(),
        )
    }

    @Test
    fun `a taxi request with no ATIS received is bare`() = runTest {
        val coordinator = coordinator(this, atis = WeatherAnswering.None)
        coordinator.ingestFlightPlan(plan)
        coordinator.tuneTo(ATCFacility.GROUND)

        coordinator.performPilotAction(PilotAction.TAXI)

        assertTrue(
            pilotLines(coordinator).none { it.contains("information") },
            "a code was invented: ${pilotLines(coordinator)}",
        )
    }

    @Test
    fun `the Approach check-in reports the arrival ATIS code`() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.tuneTo(ATCFacility.APPROACH)

        coordinator.checkIn()

        assertTrue(
            pilotLines(coordinator).any { it.contains("information Bravo") },
            pilotLines(coordinator).toString(),
        )
    }

    @Test
    fun `a Center check-in carries no ATIS code`() = runTest {
        // Only the taxi request and the Approach check-in carry it. Reporting an ATIS to an
        // enroute sector is not something a pilot does.
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.tuneTo(ATCFacility.CENTER)

        coordinator.checkIn()

        assertTrue(
            pilotLines(coordinator).none { it.contains("information") },
            pilotLines(coordinator).toString(),
        )
    }

    // endregion
}
