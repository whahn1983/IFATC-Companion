package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.mock.MockSimulatorFeed
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The plan the app flies before Infinite Flight has said anything.
 *
 * Nothing in the port composed one, so a fresh launch had a blank plan: no route line, no
 * airport surface, no taxi map, no identity, no waypoints. These pin the composition and,
 * above all, the precedence — the demo's defaults exist to fill blanks, never to overwrite
 * what the pilot typed.
 */
class FlightPlanComposerTest {

    private val route = MockSimulatorFeed.defaultRoute()

    @Test
    fun mockModeFillsEveryBlankFromTheDemoRoute() {
        val plan = FlightPlanComposer.plan(AppSettings(mockMode = true), route)

        assertEquals(FlightPlanComposer.MOCK_AIRLINE, plan.airline)
        assertEquals(FlightPlanComposer.MOCK_FLIGHT_NUMBER, plan.flightNumber)
        assertEquals(route.departure, plan.departure)
        assertEquals(route.destination, plan.destination)
        assertEquals(route.cruiseAltitude, plan.cruiseAltitude)
        assertEquals(route.departureGate, plan.departureGate)
        assertEquals(route.arrivalGate, plan.arrivalGate)
        assertEquals(route.waypoints.map { it.name }, plan.waypoints.map { it.name })
    }

    /**
     * The one that bites. The controller composes every call from the airline/flight-number
     * pair and only falls back to the raw callsign when that pair is empty, so a demo
     * default that beat a typed callsign would keep the flight flying as United 598 however
     * the Callsign field read.
     */
    @Test
    fun aTypedCallsignBeatsTheDemoIdentity() {
        val plan = FlightPlanComposer.plan(
            AppSettings(mockMode = true, callsign = "N512SR"),
            route,
        )

        assertEquals("N512SR", plan.callsign)
        assertEquals("", plan.airline, "an unparseable callsign must not inherit the demo's airline")
        assertEquals("", plan.flightNumber)
    }

    @Test
    fun anAirlineCallsignIsParsedIntoItsSpokenPair() {
        val plan = FlightPlanComposer.plan(AppSettings(mockMode = true, callsign = "UAL598"), route)

        assertEquals("United", plan.airline)
        assertEquals("598", plan.flightNumber)
    }

    @Test
    fun explicitIdentityFieldsAreTakenAsEntered() {
        val plan = FlightPlanComposer.plan(
            AppSettings(mockMode = true, callsign = "UAL598", airline = "Speedbird", flightNumber = "17"),
            route,
        )

        assertEquals("Speedbird", plan.airline, "an entered airline must not be re-derived from the callsign")
        assertEquals("17", plan.flightNumber)
    }

    @Test
    fun typedEndpointsAndGatesBeatTheDemoRoute() {
        val plan = FlightPlanComposer.plan(
            AppSettings(
                mockMode = true,
                departure = "EGLL",
                destination = "LFPG",
                cruiseAltitude = 26_000,
                departureGate = "A12",
                arrivalGate = "K30",
            ),
            route,
        )

        assertEquals("EGLL", plan.departure)
        assertEquals("LFPG", plan.destination)
        assertEquals(26_000, plan.cruiseAltitude)
        assertEquals("A12", plan.departureGate)
        assertEquals("K30", plan.arrivalGate)
        assertTrue(plan.manualOverride, "typed endpoints are an override, and must lock the simulator out")
    }

    /**
     * Live mode gets nothing from the demo — not its identity, not its route, not its
     * gates. Leaving any of it in place is how a live flight ends up flying as United 598.
     */
    @Test
    fun liveModeInheritsNothingFromTheDemo() {
        val plan = FlightPlanComposer.plan(AppSettings(mockMode = false), route)

        assertEquals("", plan.airline)
        assertEquals("", plan.flightNumber)
        assertEquals("", plan.departure)
        assertEquals("", plan.destination)
        assertEquals(0, plan.cruiseAltitude)
        assertTrue(plan.waypoints.isEmpty())
        assertFalse(plan.manualOverride, "an empty live plan is not an override; the simulator owns it")
    }

    @Test
    fun aChangedRouteIsWhatInvalidatesTheWeatherSample() {
        val demo = FlightPlanComposer.plan(AppSettings(mockMode = true), route)
        val sameAgain = FlightPlanComposer.plan(AppSettings(mockMode = true), route)
        val elsewhere = FlightPlanComposer.plan(
            AppSettings(mockMode = true, departure = "EGLL", destination = "LFPG"),
            route,
        )

        assertFalse(FlightPlanComposer.routeChanged(demo, sameAgain))
        assertTrue(FlightPlanComposer.routeChanged(demo, elsewhere))
    }
}
