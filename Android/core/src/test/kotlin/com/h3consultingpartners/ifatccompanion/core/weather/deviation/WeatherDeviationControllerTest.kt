package com.h3consultingpartners.ifatccompanion.core.weather.deviation

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.model.Waypoint
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.session.WeatherDeviationAction
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import com.h3consultingpartners.ifatccompanion.core.weather.radar.RadarCell
import com.h3consultingpartners.ifatccompanion.core.weather.radar.RadarOverlayModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The simulated weather-deviation flow, end to end.
 *
 * Every piece of it — the detector, the engine, the phraseology, the hazard model — was
 * ported and tested and then constructed nowhere, so on Android no hazard was ever built,
 * no conflict was ever found, the mint line had no assignment, and the response card was an
 * empty default. These pin the part that was missing: the thing that runs them.
 */
class WeatherDeviationControllerTest {

    private val departure = Coordinate(40.0, -95.0)

    /** Due north from the departure, so "along the route" and "north" are the same thing. */
    private fun onCourse(alongNM: Double): Coordinate = Geo.destination(departure, 0.0, alongNM)

    private val destination = onCourse(400.0)

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

    private fun waypoint(name: String, at: Coordinate) =
        Waypoint(name = name, latitude = at.latitude, longitude = at.longitude)

    /** A box straddling the course, [alongNM] ahead of the departure. */
    private fun stormOnCourse(alongNM: Double, halfNM: Double = 12.0): RadarCell {
        val centre = onCourse(alongNM)
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

    private fun overlay(cells: List<RadarCell>) = RadarOverlayModel(
        isEnabled = true,
        coverageAvailable = true,
        mockCells = cells,
    )

    private fun controller() = WeatherDeviationController(
        clock = MutableClock(0),
        settingsProvider = { AppSettings(mockMode = true, voiceEnabled = false) },
    )

    private fun inputs(
        cells: List<RadarCell>,
        atAlongNM: Double,
        atcState: ATCState = ATCState.CRUISE,
        phase: FlightPhase = FlightPhase.CRUISE,
        hasDeparted: Boolean = true,
    ): WeatherDeviationController.Inputs {
        val position = onCourse(atAlongNM)
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
            phase = phase,
            atcState = atcState,
            currentFacility = ATCFacility.CENTER,
            hasDeparted = hasDeparted,
            companionStandby = false,
            assignedAltitude = 35_000,
            overlay = overlay(cells),
        )
    }

    private fun atc(emission: WeatherDeviationController.Emission) =
        emission.transmissions.filter { it.sender == ATCTransmission.Sender.ATC }

    // region Detection and drawing

    @Test
    fun aStormOnTheRouteIsFoundAndARerouteIsDrawn() {
        val flow = controller()

        flow.update(inputs(listOf(stormOnCourse(150.0)), atAlongNM = 110.0))

        val state = flow.state.value
        assertTrue(state.hazards.isNotEmpty(), "moderate-or-worse cells are what drive the whole flow")
        assertNotNull(state.conflict, "a heavy cell straddling the course is a conflict")
        assertTrue(
            state.deviationLine.size >= 2,
            "the mint line is the reroute; without it there is nothing for the pilot to fly",
        )
    }

    /** Light returns do not drive deviations, so they never become hazards. */
    @Test
    fun lightPrecipitationIsNotAHazard() {
        val flow = controller()
        val light = stormOnCourse(150.0).let {
            RadarCell(polygon = it.polygon, intensity = WeatherIntensity.LIGHT)
        }

        flow.update(inputs(listOf(light), atAlongNM = 110.0))

        assertTrue(flow.state.value.hazards.isEmpty())
        assertEquals(null, flow.state.value.conflict)
    }

    @Test
    fun aClearRouteDrawsNothingAndOffersNothing() {
        val flow = controller()

        flow.update(inputs(emptyList(), atAlongNM = 110.0))

        val state = flow.state.value
        assertEquals(null, state.conflict)
        assertTrue(state.deviationLine.isEmpty())
        assertTrue(state.actions.isEmpty())
        assertEquals(null, state.bannerText)
    }

    /**
     * The flow runs in flight and nowhere else. A taxiing aircraft is not deviating around
     * anything, and a controller volunteering a weather advisory on the ramp is noise.
     */
    @Test
    fun theFlowIsSilentOnTheGround() {
        val flow = controller()

        val emission = flow.update(
            inputs(
                listOf(stormOnCourse(150.0)),
                atAlongNM = 0.0,
                atcState = ATCState.GROUND_TAXI,
                phase = FlightPhase.TAXI_OUT,
                hasDeparted = false,
            ),
        )

        assertTrue(emission.transmissions.isEmpty())
        assertEquals(null, flow.state.value.bannerText)
    }

    // endregion

    // region The exchange

    /** Mock Mode is the offline demo: the advisory has to play out on its own. */
    @Test
    fun theAdvisoryIsIssuedOnceTheWeatherIsInTacticalRange() {
        val flow = controller()

        val emission = flow.update(inputs(listOf(stormOnCourse(150.0)), atAlongNM = 110.0))

        assertTrue(
            atc(emission).isNotEmpty(),
            "a storm 40 NM ahead with nobody having said anything is exactly what the advisory is for",
        )
        assertTrue(emission.controllerInitiated)
        assertTrue(
            flow.state.value.actions.contains(WeatherDeviationAction.REQUEST_RIGHT_DEVIATION),
            "the response card must come up with the call: ${flow.state.value.actions}",
        )
    }

    @Test
    fun theAdvisoryIsNotRepeatedOnEveryTick() {
        val flow = controller()
        val cells = listOf(stormOnCourse(150.0))
        flow.update(inputs(cells, atAlongNM = 110.0))

        val second = flow.update(inputs(cells, atAlongNM = 112.0))

        assertTrue(second.transmissions.isEmpty(), "the advisory is a one-shot, not a metronome")
    }

    @Test
    fun requestingADeviationIsApprovedAndCommitsThePath() {
        val flow = controller()
        val cells = listOf(stormOnCourse(150.0))
        flow.update(inputs(cells, atAlongNM = 110.0))

        val emission = flow.perform(
            WeatherDeviationAction.REQUEST_RIGHT_DEVIATION,
            inputs(cells, atAlongNM = 110.0),
        )

        assertTrue(
            emission.transmissions.any { it.sender == ATCTransmission.Sender.PILOT },
            "the pilot's request belongs in the transcript too",
        )
        assertTrue(atc(emission).isNotEmpty(), "a request left unanswered reads as a dropped call")
        val context = flow.state.value.context
        assertTrue(
            context.state.isCommittedDeviation || context.state == WeatherDeviationState.DEVIATION_REQUESTED,
            "actual state: ${context.state}",
        )
        assertNotNull(
            context.committedDeviationPath,
            "the committed line is what stops the reroute shifting under the pilot",
        )
    }

    @Test
    fun reportingClearOfWeatherEndsTheDeviation() {
        val flow = controller()
        val cells = listOf(stormOnCourse(150.0))
        flow.update(inputs(cells, atAlongNM = 110.0))
        flow.perform(WeatherDeviationAction.REQUEST_RIGHT_DEVIATION, inputs(cells, atAlongNM = 110.0))

        val emission = flow.perform(WeatherDeviationAction.CLEAR_OF_WEATHER, inputs(cells, atAlongNM = 160.0))

        assertTrue(atc(emission).isNotEmpty())
        assertEquals(WeatherDeviationState.NONE, flow.state.value.context.state)
        assertEquals(null, flow.state.value.conflict)
        assertTrue(flow.state.value.actions.isEmpty())
    }

    @Test
    fun continuingOnCourseClosesTheExchangeWithoutADeviation() {
        val flow = controller()
        val cells = listOf(stormOnCourse(150.0))
        flow.update(inputs(cells, atAlongNM = 110.0))

        val emission = flow.perform(WeatherDeviationAction.CONTINUE_ON_COURSE, inputs(cells, atAlongNM = 110.0))

        assertTrue(atc(emission).any { "advise if you need to deviate" in it.displayText })
        assertEquals(WeatherDeviationState.NONE, flow.state.value.context.state)
        assertEquals(
            null,
            flow.state.value.context.committedDeviationPath,
            "electing to continue commits nothing",
        )
    }

    @Test
    fun requestingHigherForWeatherAssignsAnAltitude() {
        val flow = controller()
        val cells = listOf(stormOnCourse(150.0))
        flow.update(inputs(cells, atAlongNM = 110.0))

        val emission = flow.perform(WeatherDeviationAction.REQUEST_HIGHER, inputs(cells, atAlongNM = 110.0))

        assertTrue(
            atc(emission).any { "climb and maintain" in it.displayText.lowercase() },
            "actual: ${atc(emission).map { it.displayText }}",
        )
    }

    // endregion

    // region Reset

    @Test
    fun aNewFlightPutsTheWholeFlowDown() {
        val flow = controller()
        val cells = listOf(stormOnCourse(150.0))
        flow.update(inputs(cells, atAlongNM = 110.0))
        flow.perform(WeatherDeviationAction.REQUEST_RIGHT_DEVIATION, inputs(cells, atAlongNM = 110.0))

        flow.reset()

        val state = flow.state.value
        assertEquals(WeatherDeviationState.NONE, state.context.state)
        assertEquals(null, state.conflict)
        assertTrue(state.hazards.isEmpty())
        assertTrue(state.deviationLine.isEmpty())
        assertFalse(state.isActive)
    }

    // endregion
}
