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
 * What the pilot did with the ATIS has to survive a reconnect.
 *
 * The broadcast itself comes back on the next fetch. What does not is the pilot's side of
 * it: which code they copied, whether they have already given it to ATC, and whether they
 * have tuned away. Losing that means a relaunch mid-taxi silently drops the information
 * code — or reports the same one twice.
 *
 * The snapshot has carried all six fields since the port began and nothing wrote or read
 * one of them.
 */
class AtisReceiptSurvivesReconnectTest {

    /** A weather side whose receipt can be inspected and set, standing in for the engine. */
    private class FakeAtis(var receipt: AtisReceipt = AtisReceipt()) : WeatherAnswering {
        override fun atisReceipt(): AtisReceipt = receipt
        override fun restoreAtisReceipt(receipt: AtisReceipt) { this.receipt = receipt }

        override fun atisInfoWord(arriving: Boolean): String? = null
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

    private val plan = FlightPlan.empty.copy(
        departure = "KIAH",
        destination = "KMSP",
        airline = "United",
        flightNumber = "598",
        callsign = "United 598",
        cruiseAltitude = 35_000,
    )

    private fun coordinator(scope: TestScope, atis: WeatherAnswering) = FlightSessionCoordinator(
        scope = scope,
        clock = MutableClock(1_700_000_000_000L),
        settingsProvider = { AppSettings(mockMode = true, voiceEnabled = false) },
        weatherAnswers = atis,
    )

    private val copied = AtisReceipt(
        reportedDeparture = "A",
        reportedArrival = "B",
        departureReported = true,
        arrivalReported = false,
        departureDismissed = true,
        arrivalDismissed = false,
    )

    @Test
    fun `the snapshot carries what the pilot did with the ATIS`() = runTest {
        val atis = FakeAtis(copied)
        val coordinator = coordinator(this, atis)
        coordinator.ingestFlightPlan(plan)
        coordinator.tuneTo(ATCFacility.GROUND)

        val snapshot = coordinator.captureSnapshot()

        assertEquals("A", snapshot.reportedDepartureInfo)
        assertEquals("B", snapshot.reportedArrivalInfo)
        assertEquals(true, snapshot.departureInfoAppended)
        assertEquals(false, snapshot.arrivalInfoAppended)
        assertEquals(true, snapshot.departureATISDismissed)
        assertEquals(false, snapshot.arrivalATISDismissed)
    }

    @Test
    fun `restoring gives the pilot back the code they copied`() = runTest {
        val atis = FakeAtis(copied)
        val snapshot = coordinator(this, atis).let {
            it.ingestFlightPlan(plan)
            it.captureSnapshot()
        }

        // A fresh session, as after a relaunch: the weather side starts blank.
        val resumed = FakeAtis()
        coordinator(this, resumed).restore(snapshot)

        assertEquals(copied, resumed.receipt)
    }

    @Test
    fun `an older snapshot with no ATIS fields restores as nothing reported`() = runTest {
        // Snapshots written before these fields existed decode with nulls, and must not
        // come back claiming the pilot has already reported a code they never heard.
        val resumed = FakeAtis(copied)
        val coordinator = coordinator(this, resumed)
        coordinator.ingestFlightPlan(plan)
        val bare = coordinator.captureSnapshot().copy(
            reportedDepartureInfo = null,
            reportedArrivalInfo = null,
            departureInfoAppended = null,
            arrivalInfoAppended = null,
            departureATISDismissed = null,
            arrivalATISDismissed = null,
        )

        coordinator(this, resumed).restore(bare)

        assertEquals(AtisReceipt(), resumed.receipt)
    }

    @Test
    fun `a session with no weather engine snapshots an empty receipt`() = runTest {
        val coordinator = coordinator(this, WeatherAnswering.None)
        coordinator.ingestFlightPlan(plan)

        val snapshot = coordinator.captureSnapshot()

        assertTrue(snapshot.reportedDepartureInfo == null && snapshot.reportedArrivalInfo == null)
        assertEquals(false, snapshot.departureInfoAppended)
    }
}
