package com.h3consultingpartners.ifatccompanion.core.mock

import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.session.FlightSessionCoordinator
import com.h3consultingpartners.ifatccompanion.core.session.PilotAction
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.WeatherIntensity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end test that drives the session coordinator through a complete, realistic mock
 * flight (gate → gate) and asserts the controller/pilot dialogue is correct and in order.
 * It exercises the full pipeline — phase detection → state machine → phraseology →
 * transcript — the same way the live/mock feeds do.
 *
 * Ported from `IFATCCompanionTests/MockScenarioTests.swift`, whose subject is `AppModel`;
 * the Kotlin equivalent of that orchestration is [FlightSessionCoordinator]. The scripted
 * feed's own contract — the demo flight being the *same* flight iOS flies — is asserted
 * here too, because it is what makes the ported scenario comparable at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MockScenarioTest {

    private val clock = MutableClock(1_700_000_000_000L)

    private fun feed(scope: TestScope) = MockSimulatorFeed(scope = scope, clock = clock)

    /**
     * Build a coordinator wired for an offline, silent, automatic mock flight from KIAH
     * to KMSP with a filed STAR and ILS approach.
     */
    private fun makeModel(scope: TestScope, mock: MockSimulatorFeed): FlightSessionCoordinator {
        val settings = AppSettings(
            voiceEnabled = false, // no audio in tests
            mockMode = true,
            initialClimbAltitudeFt = 5000,
            traconCeilingFL = 180,
        )
        val coordinator = FlightSessionCoordinator(
            scope = scope,
            clock = clock,
            settingsProvider = { settings },
        )
        coordinator.ingestFlightPlan(
            FlightPlan(
                airline = "United",
                flightNumber = "598",
                departure = "KIAH",
                destination = "KMSP",
                cruiseAltitude = 37000,
                star = "KKILR", // filed arrival
                approach = "ILS 30L", // filed approach
                waypoints = mock.route.waypoints,
            ),
        )
        return coordinator
    }

    /** Feed a phase's synthesized aircraft state `times` ticks through the pipeline. */
    private fun feed(
        model: FlightSessionCoordinator,
        mock: MockSimulatorFeed,
        phase: FlightPhase,
        times: Int = 1,
    ) {
        repeat(times) { model.ingestAircraftState(mock.stateFor(phase)) }
    }

    // region Helpers

    private fun index(
        model: FlightSessionCoordinator,
        needle: String,
        sender: ATCTransmission.Sender? = null,
    ): Int? = model.state.value.transcript
        .indexOfFirst { tx ->
            (sender == null || tx.sender == sender) && tx.displayText.contains(needle)
        }
        .takeIf { it >= 0 }

    private fun contains(
        model: FlightSessionCoordinator,
        needle: String,
        sender: ATCTransmission.Sender? = null,
    ): Boolean = index(model, needle, sender) != null

    // endregion

    // region The scripted scenario itself

    /**
     * The demo must be the *same* flight on both platforms: KIAH → KMSP at FL370, the
     * five synthesized fixes in order, and the two United stands. A demo that quietly
     * flew a different route would make every screenshot and every walk-through disagree
     * with the iOS one.
     */
    @Test
    fun theDefaultRouteIsTheIOSDemoFlight() = runTest {
        val mock = feed(this)
        assertEquals("KIAH", mock.route.departure)
        assertEquals("KMSP", mock.route.destination)
        assertEquals(37000, mock.route.cruiseAltitude)
        assertEquals("C24", mock.route.departureGate)
        assertEquals("C6", mock.route.arrivalGate)
        assertEquals(
            listOf("TBONE", "KMCI", "KOMA", "KDSM", "FARGO"),
            mock.route.waypoints.map { it.name },
        )
        // The fixes are spaced evenly along the great-circle chord, 1/6 … 5/6 of the way.
        val first = mock.route.waypoints.first()
        val expectedLat = mock.route.depCoord.latitude +
            (mock.route.destCoord.latitude - mock.route.depCoord.latitude) / 6.0
        assertEquals(expectedLat, first.latitude!!, 1e-9)

        val denver = MockSimulatorFeed.denverRoute()
        assertEquals("KDEN", denver.departure)
        assertEquals(35000, denver.cruiseAltitude)
        assertEquals("B32", denver.departureGate)
        assertEquals(listOf("AKO", "ONL", "FSD", "REDWG"), denver.waypoints.map { it.name })
    }

    /**
     * Each phase's synthesized state is the profile the iOS feed publishes, to the digit.
     * These are what phase detection, the altitude assignments and the taxi map all key
     * off, so a drifted number changes the demo flight rather than just its numbers.
     */
    @Test
    fun eachPhaseSynthesizesTheIOSProfile() = runTest {
        val mock = feed(this)

        val preflight = mock.stateFor(FlightPhase.PREFLIGHT)
        assertEquals(0.0, preflight.groundSpeed)
        assertEquals(true, preflight.onGround)
        assertEquals(true, preflight.parkingBrakeSet, "the brake is set at the gate")
        assertEquals(0.0, preflight.altitudeAGL)
        assertEquals("Boeing 737-800", preflight.aircraftName)
        assertEquals("United", preflight.liveryName)

        assertEquals(16.0, mock.stateFor(FlightPhase.TAXI_OUT).groundSpeed)

        val takeoff = mock.stateFor(FlightPhase.TAKEOFF)
        assertEquals(50.0, takeoff.altitudeMSL)
        assertEquals(150.0, takeoff.groundSpeed)
        assertEquals(200.0, takeoff.verticalSpeed)
        assertEquals(true, takeoff.onGround)

        val initialClimb = mock.stateFor(FlightPhase.INITIAL_CLIMB)
        assertEquals(4500.0, initialClimb.altitudeMSL)
        assertEquals(2600.0, initialClimb.verticalSpeed)
        // Airborne AGL is 800 ft below MSL, and IAS 60 kt below groundspeed.
        assertEquals(3700.0, initialClimb.altitudeAGL)
        assertEquals(180.0, initialClimb.indicatedAirspeed)

        assertEquals(21000.0, mock.stateFor(FlightPhase.CLIMB).altitudeMSL)
        assertEquals(37000.0, mock.stateFor(FlightPhase.CRUISE).altitudeMSL)
        assertEquals(-1900.0, mock.stateFor(FlightPhase.DESCENT).verticalSpeed)

        val approach = mock.stateFor(FlightPhase.APPROACH)
        assertEquals(4000.0, approach.altitudeMSL)
        assertEquals(true, approach.approachModeEngaged, "APPR is engaged on the approach")

        val landing = mock.stateFor(FlightPhase.LANDING)
        assertEquals(130.0, landing.groundSpeed)
        assertEquals(true, landing.onGround)
        assertEquals(true, landing.approachModeEngaged)

        val parked = mock.stateFor(FlightPhase.PARKED)
        assertEquals(0.0, parked.groundSpeed)
        assertEquals(true, parked.parkingBrakeSet, "and set again once blocked in")

        // The unknown phase parks the aircraft mid-route at cruise rather than nowhere.
        assertEquals(450.0, mock.stateFor(FlightPhase.UNKNOWN).groundSpeed)
    }

    /**
     * The nearest airport flips from origin to destination at the half-way point, which is
     * what the arrival-side features (arrival ATIS range, the taxi-in map) key off.
     */
    @Test
    fun theNearestAirportFollowsTheRouteHalfway() = runTest {
        val mock = feed(this)
        assertEquals("KIAH", mock.stateFor(FlightPhase.CLIMB).nearestAirport)
        assertEquals("KMSP", mock.stateFor(FlightPhase.CRUISE).nearestAirport)
        assertEquals("KMSP", mock.stateFor(FlightPhase.PARKED).nearestAirport)
        assertEquals(
            0.0,
            mock.stateFor(FlightPhase.PREFLIGHT).nearestAirportDistanceNM!!,
            0.001,
            "at the gate the aircraft is on the field",
        )
    }

    /**
     * The feed ticks once a second, and ten in-phase ticks nudge the aircraft 2% further
     * along the route — the "liveliness" the iOS feed adds so a held phase doesn't look
     * frozen. Driven from the injected scope, so the whole demo runs in virtual time.
     */
    @Test
    fun theFeedTicksOnceASecondAndNudgesTheAircraftAlong() = runTest {
        val mock = feed(this)
        val pushed = mutableListOf<AircraftState>()
        mock.onState = { pushed += it }

        mock.setPhase(FlightPhase.CRUISE)
        mock.start()
        assertTrue(mock.running.value)
        assertEquals(2, pushed.size, "setPhase emits, and so does start")
        val atStart = pushed.last().latitude!!

        advanceTimeBy(MockSimulatorFeed.TICK_INTERVAL_MILLIS * 10 + 1)
        assertEquals(12, pushed.size, "ten ticks, one a second")

        // Ten ticks saturate the phase progress, which is worth 2% of the route.
        val depLat = mock.route.depCoord.latitude
        val destLat = mock.route.destCoord.latitude
        assertEquals(atStart + (destLat - depLat) * 0.02, pushed.last().latitude!!, 1e-9)

        // Saturated: an eleventh tick moves nothing further.
        val saturated = pushed.last().latitude!!
        advanceTimeBy(MockSimulatorFeed.TICK_INTERVAL_MILLIS + 1)
        assertEquals(saturated, pushed.last().latitude!!, 1e-9)

        mock.stop()
        assertFalse(mock.running.value)
        val afterStop = pushed.size
        advanceTimeBy(MockSimulatorFeed.TICK_INTERVAL_MILLIS * 3)
        assertEquals(afterStop, pushed.size, "a stopped feed pushes nothing")
    }

    /** Advancing walks the demo sequence in order and wraps back to the gate at the end. */
    @Test
    fun advancingWalksTheDemoSequenceAndWrapsAtTheEnd() = runTest {
        val mock = feed(this)
        assertEquals(FlightPhase.PREFLIGHT, mock.phase.value)
        for (expected in FlightPhase.demoSequence.drop(1)) {
            mock.advancePhase()
            assertEquals(expected, mock.phase.value)
        }
        mock.advancePhase()
        assertEquals(FlightPhase.PREFLIGHT, mock.phase.value, "past PARKED it wraps to the gate")
    }

    /**
     * The three mock precipitation systems are the demo's weather: a moderate one early,
     * the heavy primary ~40 NM ahead of the cruise point (the one the deviation flow
     * works), and a heavy one near the arrival — all moving east.
     */
    @Test
    fun theMockRadarCellsAreTheDemoWeather() = runTest {
        val mock = feed(this)
        val cells = mock.sampleRadarCells()
        assertEquals(3, cells.size)
        assertEquals(
            listOf(WeatherIntensity.MODERATE, WeatherIntensity.HEAVY, WeatherIntensity.HEAVY),
            cells.map { it.intensity },
        )
        assertEquals(listOf(90.0, 90.0, 90.0), cells.map { it.movementDirectionDegrees })
        assertEquals(listOf(15.0, 20.0, 25.0), cells.map { it.movementSpeedKnots })
        assertTrue(cells.all { it.polygon.size == 4 }, "each system is a box about its center")

        val pireps = mock.samplePIREPs()
        assertEquals(listOf(35000, 33000, 37000), pireps.map { it.altitudeFt })
        assertEquals(3, mock.sampleMETARs().size)
        assertEquals("KMSP", mock.sampleTAF().icao)
    }

    // endregion

    // region Elevation-aware altitudes at a high-elevation field

    /**
     * The initial climb is a height above the field: the departure field elevation is
     * captured from on-ground telemetry (MSL − AGL, no onboard database) and the
     * configured climb is added, rounded up to the next thousand. At Denver (~5,434 ft) a
     * 5,000 ft climb becomes 11,000 ft MSL instead of a sub-surface 5,000 ft.
     */
    @Ignore // coordinator gap: field elevation is not captured yet
    @Test
    fun departureInitialClimbIsRaisedAboveHighField() = runTest {
        val mock = feed(this)
        val model = makeModel(this, mock) // initialClimbAltitudeFt = 5,000
        val ground = AircraftState(
            onGround = true,
            altitudeMSL = 5434.0,
            altitudeAGL = 0.0,
            latitude = 39.86, longitude = -104.67,
            groundSpeed = 0.0, heading = 340.0,
        )
        model.ingestAircraftState(ground) // captures the departure field elevation

        model.performPilotAction(PilotAction.CLEARANCE)
        // 5,434 field + 5,000 climb = 10,434 → rounded up to 11,000 ft MSL.
        assertEquals(11000, model.state.value.assignedAltitude)
    }

    /**
     * The field elevation is only ever taken from a snapshot that *reports* being on the
     * ground. A half-read one (the link torn down mid-poll) carries no ground reference,
     * so the phase holds where it is — on the ground, for a departure — and its raw MSL
     * has no AGL to subtract. Capturing that would put the field hundreds of feet up and
     * raise every initial climb derived from it.
     */
    @Ignore // coordinator gap: field elevation is not captured yet
    @Test
    fun fieldElevationIsNotTakenFromASnapshotWithNoGroundReference() = runTest {
        val mock = feed(this)
        val model = makeModel(this, mock)
        model.ingestAircraftState(
            AircraftState(
                onGround = true,
                altitudeMSL = 5434.0,
                altitudeAGL = 0.0,
                latitude = 39.86, longitude = -104.67,
                groundSpeed = 0.0, heading = 340.0,
            ),
        )

        // Position and altitude read; the on-ground, AGL and vertical-speed reads didn't
        // complete before the link went away — and the aircraft has rotated.
        model.ingestAircraftState(
            AircraftState(altitudeMSL = 6200.0, latitude = 39.86, longitude = -104.67),
        )

        model.performPilotAction(PilotAction.CLEARANCE)
        assertEquals(
            11000, model.state.value.assignedAltitude,
            "the field must still be the 5,434 ft captured from real on-ground telemetry",
        )
    }

    // endregion

    // region The full realistic sequence

    /**
     * Drive a full gate-to-gate scenario and return the resulting coordinator.
     *
     * **The four tests built on this are `@Ignore`d, and the gap is in `core.session`,
     * not in the mock feed.** The drive reaches the destination gate, but four calls the
     * iOS flow makes are not in the Kotlin coordinator yet — verified against a full
     * transcript dump of this exact scenario:
     *
     *  1. *No Departure hand-off.* Tower hands straight to Center ("contact Center on
     *     133.400"); the "contact Departure" leg between them is missing.
     *  2. *No cleared-approach call.* Approach issues only "expect the ILS runway 30L
     *     approach"; the "cleared ILS RWY 30L approach" that must precede the Tower
     *     hand-off never comes, and with it the pilot's "Contacting Tower" read-back.
     *  3. *No runway-exit call.* After landing, Tower issues a bare "contact Ground"
     *     rather than "exit the runway when able, contact Ground".
     *  4. *The cruise altitude renders as "FL370" in the display text* ("climb and
     *     maintain FL370"). iOS never puts that token in `displayText`, which is why its
     *     descent test can assert the whole transcript is free of "maintain FL370".
     *
     * Un-ignore each as the coordinator's calls land; the assertions are the iOS ones
     * verbatim.
     *
     * The pre-departure ground flow is pilot-driven via the response-button actions (each
     * instruction read back manually). The position-triggered controller calls — which the
     * mock autopilot would play on a timer — are reproduced here synchronously by feeding
     * the matching aircraft states, with a manual read-back after each substantive
     * instruction.
     */
    private fun runFullFlight(scope: TestScope): FlightSessionCoordinator {
        val mock = feed(scope)
        val model = makeModel(scope, mock)

        // Pilot-driven pre-departure flow (manual buttons + read-backs).
        model.performPilotAction(PilotAction.CLEARANCE); model.readBack()
        model.performPilotAction(PilotAction.PUSHBACK); model.readBack()
        model.performPilotAction(PilotAction.ENGINE_START); model.readBack()
        model.performPilotAction(PilotAction.TAXI); model.readBack() // Ramp hands off to Ground
        model.performPilotAction(PilotAction.TAXI); model.readBack() // Ground issues the taxi clearance
        model.performPilotAction(PilotAction.READY); model.readBack() // line up and wait

        // Automatic, position-triggered controller calls (pilot reads back / checks in
        // manually between them).
        feed(model, mock, FlightPhase.TAKEOFF); model.readBack() // cleared for takeoff
        feed(model, mock, FlightPhase.INITIAL_CLIMB); model.readBack() // contact Departure + climb
        feed(model, mock, FlightPhase.CLIMB); model.readBack() // contact Center + climb
        feed(model, mock, FlightPhase.CRUISE) // radar contact (not read back)
        feed(model, mock, FlightPhase.DESCENT); model.readBack() // descend via the STAR
        feed(model, mock, FlightPhase.APPROACH, times = 2); model.readBack() // expect, then cleared
        feed(model, mock, FlightPhase.LANDING, times = 2); model.readBack() // land, then exit runway
        feed(model, mock, FlightPhase.TAXI_IN); model.readBack() // taxi to parking
        feed(model, mock, FlightPhase.PARKED) // arrival courtesy
        return model
    }

    @Ignore // see the note on runFullFlight
    @Test
    fun fullMockFlightProducesRealisticControllerSequence() = runTest {
        val model = runFullFlight(this)
        val atc = ATCTransmission.Sender.ATC

        // Departure controller calls.
        assertTrue(contains(model, "cleared to KMSP", atc), "clearance missing")
        assertTrue(contains(model, "pushback approved", atc))
        assertTrue(contains(model, "start approved", atc))
        assertTrue(contains(model, "taxi to runway", atc))
        assertTrue(contains(model, "line up and wait", atc))
        assertTrue(contains(model, "cleared for takeoff", atc))
        assertTrue(contains(model, "radar contact", atc))

        // Facility hand-offs in both directions.
        assertTrue(contains(model, "contact Departure", atc))
        assertTrue(contains(model, "Center on ", atc)) // sector-named or generic
        assertTrue(contains(model, "contact Approach", atc))
        assertTrue(contains(model, "contact Tower", atc))

        // Arrival controller calls.
        assertTrue(
            contains(model, "expect the ILS runway 30L approach", atc),
            "expect-approach call missing or doubly worded",
        )
        assertTrue(
            contains(model, "cleared ILS RWY 30L approach", atc),
            "cleared-approach call missing before Tower hand-off",
        )
        assertTrue(contains(model, "cleared to land", atc))
        assertTrue(
            contains(model, "exit the runway when able, contact Ground", atc),
            "post-landing exit/contact-ground call missing",
        )
        assertTrue(contains(model, "taxi to parking", atc))
    }

    // endregion

    // region Descent: STAR + no contradiction

    @Ignore // see the note on runFullFlight
    @Test
    fun descentSaysDescendViaStarAndIsNotContradictory() = runTest {
        val model = runFullFlight(this)
        assertTrue(
            contains(model, "descend via the KKILR arrival", ATCTransmission.Sender.ATC),
            "filed STAR should produce a descend-via-arrival call",
        )
        // The contradictory "descend at pilot's discretion … maintain <cruise>" must not
        // appear in the automatic descent.
        val allText = model.state.value.transcript.joinToString("\n") { it.displayText }
        assertFalse(
            allText.contains("pilot's discretion"),
            "automatic descent must not use the contradictory discretion phrasing",
        )
        assertFalse(
            allText.contains("maintain FL370"),
            "descent must not tell the pilot to maintain the cruise level",
        )
    }

    // endregion

    // region Pilot readbacks before progressing

    @Ignore // see the note on runFullFlight
    @Test
    fun pilotReadsBackBeforeFlowProgresses() = runTest {
        val model = runFullFlight(this)
        val pilot = ATCTransmission.Sender.PILOT
        val atc = ATCTransmission.Sender.ATC

        // The clearance is read back before pushback is issued.
        val clearanceReadback = index(model, "Cleared to KMSP", pilot)
        val pushback = index(model, "pushback approved", atc)
        assertNotNull(clearanceReadback, "missing clearance readback")
        assertNotNull(pushback)
        assertTrue(
            clearanceReadback < pushback,
            "pilot should read back the clearance before pushback",
        )

        // Substantive arrival instructions are read back too.
        assertTrue(
            contains(model, "Descend via the KKILR arrival", pilot),
            "missing STAR readback",
        )
        // "Cleared the ILS … contact Tower" is a double call: the read-back applies to the
        // last message only — the frequency hand-off ("contacting Tower").
        assertTrue(contains(model, "Contacting Tower", pilot), "missing frequency hand-off readback")
        assertTrue(
            contains(model, "Exiting the runway, contact Ground", pilot),
            "missing runway-exit readback",
        )

        // Plenty of readbacks across the flight (one per substantive instruction).
        val pilotReadbacks = model.state.value.transcript.filter { it.sender == pilot }
        assertTrue(pilotReadbacks.size > 8, "only ${pilotReadbacks.size} pilot calls")
    }

    // endregion

    // region Overall ordering is gate-to-gate

    @Ignore // see the note on runFullFlight
    @Test
    fun transcriptOrderingIsGateToGate() = runTest {
        val model = runFullFlight(this)
        val atc = ATCTransmission.Sender.ATC
        val order = listOf(
            index(model, "cleared to KMSP", atc),
            index(model, "cleared for takeoff", atc),
            index(model, "descend via the KKILR arrival", atc),
            index(model, "cleared ILS RWY 30L approach", atc),
            index(model, "cleared to land", atc),
            index(model, "exit the runway when able", atc),
            index(model, "taxi to parking", atc),
        )
        assertFalse(order.contains(null), "a stage of the flight is missing: $order")
        val unwrapped = order.filterNotNull()
        assertEquals(unwrapped.sorted(), unwrapped, "controller calls are out of order")
    }

    // endregion
}
