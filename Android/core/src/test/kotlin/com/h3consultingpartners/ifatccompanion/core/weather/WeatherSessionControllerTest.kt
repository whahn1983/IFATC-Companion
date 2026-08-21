package com.h3consultingpartners.ifatccompanion.core.weather

import com.h3consultingpartners.ifatccompanion.core.airports.AirportDatabase
import com.h3consultingpartners.ifatccompanion.core.atis.ATISService
import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.net.HttpFetching
import com.h3consultingpartners.ifatccompanion.core.net.HttpResponse
import com.h3consultingpartners.ifatccompanion.core.net.HttpResult
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import com.h3consultingpartners.ifatccompanion.core.weather.radar.PrecipitationOverlayService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The controller is the weather half of the iOS `AppModel`. These pin the parts a pilot
 * would notice going wrong: which fields get fetched, that a failed fetch says so instead
 * of showing stale data as current, that ATIS refreshes independently of weather, and that
 * the overlay's Layer/Source labels follow the route rather than the filed departure.
 */
class WeatherSessionControllerTest {

    private class FakeHttp : HttpFetching {
        val urls = mutableListOf<String>()
        var metarBody = """[{"icaoId":"KIAH","rawOb":"KIAH 281953Z 16008KT 10SM FEW250 31/21 A3001"}]"""
        var failEverything = false

        override suspend fun get(url: String, headers: Map<String, String>, timeoutSeconds: Long): HttpResult {
            urls += url
            if (failEverything) return HttpResult.Failure("offline")
            val body = when {
                url.contains("datis.clowd.io") ->
                    """[{"airport":"KIAH","type":"combined","code":"S","datis":"KIAH ATIS INFO S."}]"""
                url.contains("/metar") -> metarBody
                else -> "[]"
            }
            return HttpResult.Success(HttpResponse(200, body.toByteArray(Charsets.UTF_8), emptyMap()))
        }

        override suspend fun post(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String>,
            timeoutSeconds: Long,
        ): HttpResult = error("the weather controller never POSTs")
    }

    private fun controller(
        http: FakeHttp,
        clock: MutableClock = MutableClock(0),
        // Mock Mode is the shipping default (it is the free, offline experience), so the
        // live-fetch tests have to turn it off explicitly.
        settings: AppSettings = AppSettings(mockMode = false),
    ) = WeatherSessionController(
        weatherService = AviationWeatherService(http, clock = clock),
        atisService = ATISService(http, clock = clock),
        overlayService = PrecipitationOverlayService(http, clock),
        airports = AirportDatabase,
        clock = clock,
        settingsProvider = { settings },
    )

    private val houstonToMinneapolis = FlightPlan(
        callsign = "UAL123",
        departure = "KIAH",
        destination = "KMSP",
        cruiseAltitude = 36000,
    )

    // region Fetch

    @Test
    fun everyRouteFieldIsRequestedInOneMetarCall() = runTest {
        val http = FakeHttp()
        val controller = controller(http)
        controller.updateFlightContext(
            houstonToMinneapolis.copy(alternate = "KDEN"),
            AircraftState.empty,
            FlightPhase.PREFLIGHT,
        )
        controller.refresh()

        val metarUrl = assertNotNull(http.urls.firstOrNull { it.contains("/metar") })
        assertTrue(metarUrl.contains("KIAH"))
        assertTrue(metarUrl.contains("KMSP"))
        assertTrue(metarUrl.contains("KDEN"))
    }

    @Test
    fun asuccessfulFetchReportsWhatItLoaded() = runTest {
        val controller = controller(FakeHttp())
        controller.updateFlightContext(houstonToMinneapolis, AircraftState.empty, FlightPhase.PREFLIGHT)
        controller.refresh()

        assertEquals("KIAH", controller.state.value.departureMetar?.icao)
        assertTrue(controller.state.value.status.startsWith("Loaded 1 METARs"))
        assertNotNull(controller.state.value.lastUpdateMillis)
    }

    /** A failed fetch must say so — never present stale data as current. */
    @Test
    fun afailedFetchSaysSo() = runTest {
        val http = FakeHttp()
        http.failEverything = true
        val controller = controller(http)
        controller.updateFlightContext(houstonToMinneapolis, AircraftState.empty, FlightPhase.PREFLIGHT)
        controller.refresh()

        assertTrue(controller.state.value.status.startsWith("Weather unavailable:"))
        assertNull(controller.state.value.departureMetar)
    }

    /** The AWC PIREP endpoint 400s without a bbox, so one is always built when it can be. */
    @Test
    fun thePirepBoundingBoxEnclosesTheRouteWithPadding() {
        val controller = controller(FakeHttp())
        controller.updateFlightContext(
            houstonToMinneapolis,
            AircraftState.empty.copy(latitude = 35.0, longitude = -95.0),
            FlightPhase.CRUISE,
        )
        val box = assertNotNull(controller.pirepBoundingBox())
        val parts = box.split(",").map { it.toDouble() }
        assertEquals(4, parts.size)
        // The aircraft fix is inside the padded box.
        assertTrue(parts[0] <= 35.0 && parts[2] >= 35.0)
        assertTrue(parts[1] <= -95.0 && parts[3] >= -95.0)
    }

    @Test
    fun withNoPositionAtAllThereIsNoPirepBox() {
        val controller = controller(FakeHttp())
        controller.updateFlightContext(FlightPlan.empty, AircraftState.empty, FlightPhase.PREFLIGHT)
        assertNull(controller.pirepBoundingBox())
    }

    // endregion

    // region ATIS

    /** ATIS refreshes on the weather cadence but is independent of the weather result. */
    @Test
    fun atisRefreshesEvenWhenWeatherFails() = runTest {
        val http = object : HttpFetching {
            val urls = mutableListOf<String>()
            override suspend fun get(url: String, headers: Map<String, String>, timeoutSeconds: Long): HttpResult {
                urls += url
                if (url.contains("datis.clowd.io")) {
                    val body = """[{"airport":"KIAH","type":"combined","code":"S","datis":"KIAH ATIS INFO S."}]"""
                    return HttpResult.Success(HttpResponse(200, body.toByteArray(Charsets.UTF_8), emptyMap()))
                }
                return HttpResult.Failure("weather is down")
            }
            override suspend fun post(
                url: String,
                body: String,
                contentType: String,
                headers: Map<String, String>,
                timeoutSeconds: Long,
            ): HttpResult = error("never")
        }
        val clock = MutableClock(0)
        val controller = WeatherSessionController(
            weatherService = AviationWeatherService(http, clock = clock),
            atisService = ATISService(http, clock = clock),
            overlayService = PrecipitationOverlayService(http, clock),
            airports = AirportDatabase,
            clock = clock,
            settingsProvider = { AppSettings(mockMode = false) },
        )
        controller.updateFlightContext(houstonToMinneapolis, AircraftState.empty, FlightPhase.PREFLIGHT)
        controller.refresh()

        assertTrue(controller.state.value.status.startsWith("Weather unavailable:"))
        assertEquals("S", controller.state.value.departureAtis?.letter(arrival = false))
        assertTrue(controller.state.value.atisDiagnostics.departureReceived)
    }

    /** The arrival field's ATIS is only fetched once the aircraft is within range. */
    @Test
    fun theArrivalAtisWaitsUntilWithinRange() = runTest {
        val http = FakeHttp()
        val controller = controller(http)
        controller.updateFlightContext(
            houstonToMinneapolis,
            // Somewhere over Oklahoma — far from KMSP.
            AircraftState.empty.copy(latitude = 35.0, longitude = -97.0),
            FlightPhase.CRUISE,
        )
        controller.refreshAtis()
        assertFalse(controller.state.value.atisDiagnostics.withinArrivalRange)
        assertTrue(http.urls.none { it.endsWith("/KMSP") })
    }

    /**
     * The information code is only "reported" once the pilot has actually tuned ATIS —
     * the app never claims the pilot has information it merely fetched in the background.
     */
    @Test
    fun theReportedCodeFollowsTuningNotFetching() = runTest {
        val controller = controller(FakeHttp())
        controller.updateFlightContext(houstonToMinneapolis, AircraftState.empty, FlightPhase.PREFLIGHT)
        controller.refresh()

        assertNotNull(controller.state.value.departureAtis)
        assertNull(controller.state.value.atisDiagnostics.reportedDeparture)

        controller.noteAtisTuned(arrival = false)
        assertEquals("S", controller.state.value.atisDiagnostics.reportedDeparture)
    }

    // endregion

    // region Radar overlay descriptor

    @Test
    fun aUsRouteIsLabelledAsRadar() {
        val controller = controller(FakeHttp())
        controller.updateFlightContext(
            houstonToMinneapolis,
            AircraftState.empty.copy(latitude = 38.0, longitude = -95.0),
            FlightPhase.CRUISE,
        )
        val overlay = controller.state.value.radarOverlay
        assertTrue(overlay.coverageAvailable)
        assertEquals("Radar precipitation", overlay.layerLabel)
        assertFalse(overlay.isSatelliteEstimate)
        assertEquals("Radar precipitation data: NOAA/NWS", overlay.attributionText)
    }

    /**
     * The regression this exists for: an aircraft over England must not still be labelled
     * NOAA radar because the flight departed the U.S. The region follows the aircraft and
     * the route ahead, not the filed departure.
     */
    @Test
    fun anAircraftOverEuropeIsLabelledAsASatelliteEstimate() {
        val controller = controller(FakeHttp())
        controller.updateFlightContext(
            FlightPlan(departure = "KIAH", destination = "EGLL"),
            AircraftState.empty.copy(latitude = 51.5, longitude = -0.5),
            FlightPhase.DESCENT,
        )
        val overlay = controller.state.value.radarOverlay
        assertTrue(overlay.coverageAvailable)
        assertTrue(overlay.isSatelliteEstimate)
        assertEquals("Satellite precipitation estimate", overlay.layerLabel)
    }

    /** Turning the setting off leaves the layer described but not displayed. */
    @Test
    fun theOverlaySettingGatesDisplayNotCoverage() {
        val settings = AppSettings(
            mockMode = false,
            noaaRadarOverlay = com.h3consultingpartners.ifatccompanion.core.settings.NOAARadarOverlayMode.OFF,
        )
        val controller = controller(FakeHttp(), settings = settings)
        controller.updateFlightContext(
            houstonToMinneapolis,
            AircraftState.empty.copy(latitude = 38.0, longitude = -95.0),
            FlightPhase.CRUISE,
        )
        val overlay = controller.state.value.radarOverlay
        assertTrue(overlay.coverageAvailable)
        assertFalse(overlay.isEnabled)
        assertFalse(overlay.shouldDisplay)
    }

    // endregion

    // region Ride reports

    /**
     * Without a live aircraft fix the distance would be measured from the departure, so
     * the items are flagged and the Weather tab shows "Along route" instead of a number.
     */
    @Test
    fun distancesAreFlaggedWhenThereIsNoLiveFix() = runTest {
        val http = FakeHttp()
        val controller = controller(http)
        controller.updateFlightContext(houstonToMinneapolis, AircraftState.empty, FlightPhase.PREFLIGHT)
        controller.refresh()
        assertTrue(controller.state.value.rideReportItems.all { !it.distanceIsFromAircraft })
    }

    /** With no position at all there is nothing to report along. */
    @Test
    fun noPositionMeansNoRideItems() {
        val controller = controller(FakeHttp())
        controller.updateFlightContext(FlightPlan.empty, AircraftState.empty, FlightPhase.PREFLIGHT)
        assertTrue(controller.state.value.rideReportItems.isEmpty())
        assertTrue(controller.state.value.routeSigmets.isEmpty())
    }

    /** The reference altitude is the filed cruise level once one is set. */
    @Test
    fun theRideReferenceAltitudeIsTheFiledCruiseLevel() {
        val controller = controller(FakeHttp())
        controller.updateFlightContext(
            houstonToMinneapolis,
            AircraftState.empty.copy(altitudeMSL = 12000.0),
            FlightPhase.CLIMB,
        )
        assertEquals(36000, controller.rideReferenceAltitudeFt())
    }

    /** Before a cruise level is filed it falls back to the live altitude. */
    @Test
    fun theRideReferenceAltitudeFallsBackToTheLiveAltitude() {
        val controller = controller(FakeHttp())
        controller.updateFlightContext(
            FlightPlan(departure = "KIAH", destination = "KMSP"),
            AircraftState.empty.copy(altitudeMSL = 12000.0),
            FlightPhase.CLIMB,
        )
        assertEquals(12000, controller.rideReferenceAltitudeFt())
    }

    /** The smoother-altitude hint is one-shot: it appears when noted and clears on demand. */
    @Test
    fun theSmootherAltitudeHintIsOneShot() {
        val controller = controller(FakeHttp())
        controller.updateFlightContext(houstonToMinneapolis, AircraftState.empty, FlightPhase.CRUISE)
        assertNull(controller.state.value.suggestedSmootherAltitude)

        controller.noteSmootherAltitude(
            SmootherAltitude(
                altitudeFt = 38000,
                severity = TurbulenceSeverity.SMOOTH,
                aircraftType = null,
                higher = true,
            ),
        )
        assertEquals(38000, controller.state.value.suggestedSmootherAltitude?.altitudeFt)

        controller.clearSmootherAltitude()
        assertNull(controller.state.value.suggestedSmootherAltitude)
    }

    /** Nothing is invented: with no PIREPs there is no smoother level to suggest. */
    @Test
    fun noReportsMeansNoSmootherAltitude() {
        val controller = controller(FakeHttp())
        controller.updateFlightContext(houstonToMinneapolis, AircraftState.empty, FlightPhase.CRUISE)
        assertNull(controller.computeSmootherAltitude())
    }

    // endregion
}
