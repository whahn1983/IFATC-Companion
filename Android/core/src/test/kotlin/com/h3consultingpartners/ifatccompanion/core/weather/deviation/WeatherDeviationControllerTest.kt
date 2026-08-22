package com.h3consultingpartners.ifatccompanion.core.weather.deviation

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.geo.WindEstimator
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

    /**
     * The mint line is great-circle geometry, so its legs are **true** courses, while the
     * pilot flies a magnetic bug in a wind. Both frames are needed, and which number lives
     * where is not a detail: the stored turn geometry is compared against the leg bearings
     * for the turn size and against the aircraft's track by the never-reverse guard, so a
     * crabbed magnetic heading stored there silently corrupts both. The correction belongs
     * only where the number is spoken.
     */
    @Test
    fun theSpokenHeadingIsCorrectedButTheStoredTurnGeometryStaysTrue() {
        val cells = listOf(stormOnCourse(150.0))

        // True 010 reading magnetic 000 is ten degrees of easterly variation, and the track
        // matches the heading, so the triangle solves calm and only the declination applies.
        val level = AircraftState(
            latitude = 40.0, longitude = -95.0,
            altitudeMSL = 35_000.0, altitudeAGL = 35_000.0,
            groundSpeed = 450.0, trueAirspeed = 450.0,
            heading = 0.0, trueHeading = 10.0, track = 10.0,
            onGround = false, bankAngle = 0.0,
        )
        val estimator = WindEstimator().apply { repeat(3) { update(level) } }
        assertEquals(10.0, estimator.variationDegreesEast, "the fixture's own premise")

        // "Vectors" is the response that gets a heading assigned rather than a number of
        // degrees, so it is where the correction is visible; the same request arms the
        // turn geometry, which is where it must *not* be.
        fun run(headings: WindEstimator?): Pair<Int, WeatherDeviationContext> {
            val flow = controller()
            fun ins(at: Double) = inputs(cells, atAlongNM = at).copy(headings = headings)
            flow.update(ins(110.0))
            val emission = flow.perform(WeatherDeviationAction.REQUEST_VECTOR, ins(110.0))
            val spoken = atc(emission).firstNotNullOfOrNull { tx ->
                Regex("heading (\\d{3})").find(tx.displayText)?.groupValues?.get(1)?.toInt()
            }
            return assertNotNull(
                spoken,
                "no heading was assigned; said: " + atc(emission).joinToString(" | ") { it.displayText },
            ) to flow.state.value.context
        }

        val (plainHeading, plain) = run(null)
        val (correctedHeading, corrected) = run(estimator)

        assertEquals(
            ((plainHeading - 10) % 360 + 360) % 360,
            correctedHeading,
            "the spoken heading did not carry the declination",
        )
        assertTrue(
            plain.deviationStartHeading != null || plain.pendingRejoinHeading != null,
            "no turn was armed, so this test would pass on nothing",
        )
        assertEquals(
            plain.deviationStartHeading, corrected.deviationStartHeading,
            "the held beginning turn must stay in the true frame",
        )
        assertEquals(
            plain.pendingRejoinHeading, corrected.pendingRejoinHeading,
            "the armed interior turn must stay in the true frame",
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
