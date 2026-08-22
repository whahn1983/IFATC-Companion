package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.model.Waypoint
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.WeatherDeviationController
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.WeatherIntensity
import com.h3consultingpartners.ifatccompanion.core.weather.radar.RadarCell
import com.h3consultingpartners.ifatccompanion.core.weather.radar.RadarOverlayModel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tapping the ATC screen's weather banner.
 *
 * The banner warns about precipitation on the route, so the call it sends has to be about
 * that. Android sent a *ride report* request — a different question, answered with
 * turbulence rather than with the weather the pilot is looking at.
 */
class WeatherBannerTapTest {

    private val clock = MutableClock(1_700_000_000_000L)
    private val departure = Coordinate(40.0, -95.0)

    private fun onCourse(nm: Double): Coordinate = Geo.destination(departure, 0.0, nm)

    private fun waypoint(name: String, at: Coordinate): Waypoint =
        Waypoint(name = name, latitude = at.latitude, longitude = at.longitude)

    private val destination: Coordinate = onCourse(400.0)

    private val plan = FlightPlan.empty.copy(
        callsign = "United 598",
        airline = "United",
        flightNumber = "598",
        departure = "KIAH",
        destination = "KMSP",
        cruiseAltitude = 35_000,
        departureLatitude = departure.latitude,
        departureLongitude = departure.longitude,
        destinationLatitude = destination.latitude,
        destinationLongitude = destination.longitude,
        waypoints = listOf(
            waypoint("ALPHA", onCourse(100.0)),
            waypoint("BRAVO", onCourse(200.0)),
            waypoint("CHRLI", onCourse(300.0)),
        ),
    )

    private fun storm(halfNM: Double = 12.0): RadarCell {
        val centre = onCourse(150.0)
        fun corner(along: Double, cross: Double) =
            Geo.destination(Geo.destination(centre, 0.0, along), 90.0, cross)
        return RadarCell(
            polygon = listOf(
                corner(-halfNM, -halfNM), corner(halfNM, -halfNM),
                corner(halfNM, halfNM), corner(-halfNM, halfNM),
            ),
            intensity = WeatherIntensity.HEAVY,
        )
    }

    private val settings = AppSettings(mockMode = true, voiceEnabled = false)

    private fun airborne(): AircraftState {
        val at = onCourse(110.0)
        return AircraftState(
            latitude = at.latitude,
            longitude = at.longitude,
            altitudeMSL = 35_000.0,
            altitudeAGL = 35_000.0,
            groundSpeed = 450.0,
            verticalSpeed = 0.0,
            heading = 0.0,
            onGround = false,
        )
    }

    private fun flying(scope: TestScope, withDeviation: Boolean): FlightSessionCoordinator {
        val flow = if (!withDeviation) null else WeatherDeviationController(
            clock = clock,
            settingsProvider = { settings },
        )
        val coordinator = FlightSessionCoordinator(
            scope = scope,
            clock = clock,
            settingsProvider = { settings },
            weatherDeviation = flow,
            weatherAnswers = object : WeatherAnswering by WeatherAnswering.None {
                override fun radarOverlay() = RadarOverlayModel(
                    isEnabled = true,
                    coverageAvailable = true,
                    mockCells = listOf(storm()),
                )
            },
        )
        coordinator.ingestFlightPlan(plan)
        coordinator.tuneTo(ATCFacility.CENTER)
        coordinator.ingestAircraftState(airborne())
        return coordinator
    }

    private fun atcLines(c: FlightSessionCoordinator) = c.state.value.transcript
        .filter { it.sender == ATCTransmission.Sender.ATC }
        .map { it.displayText }

    @Test
    fun `tapping the banner asks the controller about the weather ahead`() = runTest {
        val coordinator = flying(this, withDeviation = true)
        val before = atcLines(coordinator).size

        coordinator.contactAtcAboutWeather()

        val answers = atcLines(coordinator).drop(before)
        assertTrue(answers.isNotEmpty(), "tapping the banner went unanswered")
        assertTrue(
            answers.any { it.contains("precipitation", ignoreCase = true) },
            "the answer was not about the weather: $answers",
        )
    }

    @Test
    fun `the answer is not a ride report`() = runTest {
        // A ride report answers a different question — turbulence, not the precipitation
        // the banner is warning about.
        val coordinator = flying(this, withDeviation = true)
        val before = atcLines(coordinator).size

        coordinator.contactAtcAboutWeather()

        val answers = atcLines(coordinator).drop(before)
        assertTrue(
            answers.none { it.contains("ride", ignoreCase = true) },
            "the banner still sends a ride-report request: $answers",
        )
    }

    @Test
    fun `with no deviation flow the tap still puts something on the frequency`() = runTest {
        // A banner that does nothing when tapped is worse than one that answers the wrong
        // question, so the ride report stays as the fallback.
        val coordinator = flying(this, withDeviation = false)
        val before = coordinator.state.value.transcript.size

        coordinator.contactAtcAboutWeather()

        assertTrue(
            coordinator.state.value.transcript.size > before,
            "the tap was silent with no deviation flow attached",
        )
    }
}
