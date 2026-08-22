package com.h3consultingpartners.ifatccompanion.core.weather.deviation

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.model.Waypoint
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import com.h3consultingpartners.ifatccompanion.core.weather.radar.PrecipitationLayerType
import com.h3consultingpartners.ifatccompanion.core.weather.radar.RadarCell
import com.h3consultingpartners.ifatccompanion.core.weather.radar.RadarOverlayModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Deviations from satellite estimate", the Settings toggle that was read by nothing.
 *
 * Where there is no radar the app can fall back to NASA's global satellite precipitation
 * estimate. It is ~10 km, hours latent, and cannot grade severity, so a reroute drawn from
 * it would be a confident-looking line around weather nobody has observed. Off by default,
 * and the overlay image still shows either way.
 */
class SatelliteDeviationOptInTest {

    private val departure = Coordinate(40.0, -95.0)

    private fun onCourse(alongNM: Double): Coordinate = Geo.destination(departure, 0.0, alongNM)

    private val destination = onCourse(400.0)

    private fun waypoint(name: String, at: Coordinate) =
        Waypoint(name = name, latitude = at.latitude, longitude = at.longitude)

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

    /** A heavy box straddling the course 150 NM ahead of the departure. */
    private fun storm(halfNM: Double = 12.0): RadarCell {
        val centre = onCourse(150.0)
        fun corner(along: Double, cross: Double): Coordinate =
            Geo.destination(Geo.destination(centre, 0.0, along), 90.0, cross)
        return RadarCell(
            polygon = listOf(
                corner(-halfNM, -halfNM),
                corner(halfNM, -halfNM),
                corner(halfNM, halfNM),
                corner(-halfNM, halfNM),
            ),
            intensity = WeatherIntensity.HEAVY,
        )
    }

    private fun controller(satelliteOptIn: Boolean) = WeatherDeviationController(
        clock = MutableClock(0),
        settingsProvider = {
            AppSettings(
                mockMode = true,
                voiceEnabled = false,
                satelliteDeviationsEnabled = satelliteOptIn,
            )
        },
    )

    private fun inputs(layer: PrecipitationLayerType): WeatherDeviationController.Inputs {
        val position = onCourse(110.0)
        return WeatherDeviationController.Inputs(
            plan = plan,
            aircraft = AircraftState(
                latitude = position.latitude,
                longitude = position.longitude,
                altitudeMSL = 35_000.0,
                altitudeAGL = 35_000.0,
                groundSpeed = 450.0,
                heading = 0.0,
                onGround = false,
            ),
            phase = FlightPhase.CRUISE,
            atcState = ATCState.CRUISE,
            currentFacility = ATCFacility.CENTER,
            hasDeparted = true,
            companionStandby = false,
            assignedAltitude = 35_000,
            overlay = RadarOverlayModel(
                isEnabled = true,
                coverageAvailable = true,
                layerType = layer,
                mockCells = listOf(storm()),
            ),
        )
    }

    @Test
    fun `observed radar drives a deviation`() {
        // The control: with true radar the flow works whatever the opt-in says.
        val flow = controller(satelliteOptIn = false)
        flow.update(inputs(PrecipitationLayerType.RADAR))

        assertTrue(
            flow.state.value.conflict != null,
            "heavy precipitation across the route raised no conflict",
        )
    }

    @Test
    fun `a satellite estimate draws no deviation while the pilot has not opted in`() {
        val flow = controller(satelliteOptIn = false)
        flow.update(inputs(PrecipitationLayerType.SATELLITE_ESTIMATE))

        val state = flow.state.value
        assertEquals(null, state.conflict, "a deviation was built on a satellite estimate")
        assertTrue(state.deviationLine.isEmpty(), "a reroute line was drawn: ${state.deviationLine}")
    }

    @Test
    fun `opting in lets the satellite estimate drive a deviation`() {
        val flow = controller(satelliteOptIn = true)
        flow.update(inputs(PrecipitationLayerType.SATELLITE_ESTIMATE))

        assertTrue(flow.state.value.conflict != null, "the opt-in never reached the flow")
    }
}
