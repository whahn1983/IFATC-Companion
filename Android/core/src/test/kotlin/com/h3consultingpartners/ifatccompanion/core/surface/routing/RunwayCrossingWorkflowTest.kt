package com.h3consultingpartners.ifatccompanion.core.surface.routing

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.phraseology.CallsignDigitStyle
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyEngine
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyMode
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.surface.MockAirportSurface
import com.h3consultingpartners.ifatccompanion.core.surface.OSMSurfaceNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Crossing workflow: detection, hold-short, separate crossing clearance, read-back
 * required before authorization, early runway-entry warning, completion + taxi resume,
 * and low-confidence automation disabled.
 *
 * Ported from `IFATCCompanionTests/RunwayCrossingWorkflowTests.swift`.
 */
class RunwayCrossingWorkflowTest {

    private val ref = Coordinate(40.0, -75.0)

    private class Harness {
        val collected = mutableListOf<ATCTransmission>()
        lateinit var coord: AirportSurfaceCoordinator
    }

    private fun makeCoordinator(): Harness {
        val h = Harness()
        val coord = AirportSurfaceCoordinator(
            provider = null,
            scope = CoroutineScope(Dispatchers.Unconfined),
            clock = MutableClock(0L),
        )
        val engine = PhraseologyEngine(
            digitStyle = CallsignDigitStyle.INDIVIDUAL, mode = PhraseologyMode.FAA,
        )
        coord.configure(
            engine = engine,
            emit = { h.collected.add(it) },
            callsign = { engine.callsign(airline = "United", flightNumber = "598", fallback = "") },
        )
        h.coord = coord
        return h
    }

    private fun tick(coord: AirportSurfaceCoordinator, max: Int = 800, until: () -> Boolean) {
        var n = 0
        while (!until() && n < max) {
            coord.mockTickForTesting()
            n += 1
        }
    }

    @Test
    fun fullDepartureCrossingSequence() {
        val h = makeCoordinator()
        val coord = h.coord
        coord.beginMockTaxiForTesting(TaxiKind.DEPARTURE, ref, runway = "36", gate = "A1")
        assertNotNull(coord.routeForTesting)
        assertEquals(1, coord.routeForTesting?.crossings?.size)

        // Drive up to the point a crossing clearance is issued and awaits a read-back.
        tick(coord) { coord.awaitingCrossingReadback }
        assertTrue(
            coord.awaitingCrossingReadback,
            "a separate crossing clearance should await a read-back",
        )

        // A high-confidence crossing clearance is issued automatically as the aircraft nears
        // the runway — with no redundant hold-short call (the taxi clearance already held the
        // pilot short of this first crossing).
        val text = h.collected.map { it.displayText.lowercase() }
        assertTrue(text.any { it.contains("cross runway") }, "a separate crossing clearance is issued")
        assertFalse(
            text.any { it.contains("hold short") },
            "no redundant hold-short precedes an automatic crossing clearance",
        )

        // NOT authorized before the read-back.
        assertFalse(
            coord.crossingState.isAuthorized,
            "crossing must not be authorized before read-back",
        )

        // Read back → authorized.
        coord.crossingReadbackReceived()
        assertTrue(coord.crossingState.isAuthorized, "crossing authorized after read-back")
        assertFalse(coord.awaitingCrossingReadback)

        // Continue: the aircraft crosses, vacates, and reaches the departure runway hold.
        tick(coord) { coord.reachedDestination }
        assertTrue(coord.reachedDestination, "aircraft reaches the departure runway hold-short point")
        // The taxi route resumed (continue-taxi issued after vacating).
        assertTrue(h.collected.any { it.displayText.lowercase().contains("continue taxi") })
        // The crossing sequence is no longer active.
        assertTrue(
            coord.crossingState == RunwayCrossingState.NO_CROSSING_PENDING ||
                coord.crossingState == RunwayCrossingState.TAXI_RESUMED,
        )
    }

    @Test
    fun crossingNotAuthorizedUntilReadback() {
        val h = makeCoordinator()
        val coord = h.coord
        coord.beginMockTaxiForTesting(TaxiKind.DEPARTURE, ref, runway = "36", gate = "A1")
        tick(coord) { coord.awaitingCrossingReadback }
        // Keep ticking WITHOUT reading back: the aircraft must stay held short.
        repeat(30) { coord.mockTickForTesting() }
        assertFalse(
            coord.crossingState.isAuthorized,
            "no read-back → no authorization → no crossing",
        )
        assertFalse(coord.reachedDestination, "held short of the crossing without authorization")
    }

    @Test
    fun earlyRunwayEntryProducesWarningInManualMode() {
        val h = makeCoordinator()
        val coord = h.coord
        // The unauthorized-entry safety net applies only in the manual Request-Crossing mode
        // (automatic calls off). With automatic calls on the companion always clears the
        // crossing and never warns/stops the aircraft.
        coord.autoCrossingCalls = false
        coord.beginMockTaxiForTesting(TaxiKind.DEPARTURE, ref, runway = "36", gate = "A1")
        val route = coord.routeForTesting
        assertNotNull(route, "expected a crossing")
        val crossing = route.crossings.firstOrNull()
        assertNotNull(crossing, "expected a crossing")
        // Inject an aircraft moving into the corridor toward the runway, unauthorized.
        val line = route.line
        val approach = SurfaceGeometry.pointAlong(line, maxOf(0.0, crossing.alongMeters - 10))
            ?: crossing.point.toCoordinate()
        val heading = Geo.bearing(approach, crossing.point.toCoordinate())
        coord.feedForTesting(approach, heading, groundSpeed = 15.0)
        coord.feedForTesting(approach, heading, groundSpeed = 15.0) // sustained
        assertEquals(RunwayCrossingState.UNAUTHORIZED_CROSSING_DETECTED, coord.crossingState)
        assertTrue(
            h.collected.any {
                val t = it.displayText.lowercase()
                t.contains("hold position") || t.contains("stop immediately")
            },
            "an early runway entry must produce a simulated hold/stop warning",
        )
    }

    @Test
    fun lowConfidenceStillAutoClearsCrossing() {
        val h = makeCoordinator()
        val coord = h.coord
        // A stripped, unnamed, hold-less surface → low crossing confidence.
        var m = MockAirportSurface.model(
            icao = "KLOW", reference = ref, primaryRunwayIdent = "36", gate = "A1", nowMillis = 0L,
        )
        m = m.copy(taxiways = m.taxiways.map { it.copy(name = "") }, holdingPositions = emptyList())
        m = m.copy(confidence = OSMSurfaceNormalizer.preliminaryConfidence(m))
        coord.installSurfaceForTesting(m, TaxiKind.DEPARTURE, runway = "36", gate = "A1")
        assertNotNull(coord.routeForTesting?.crossings?.firstOrNull(), "expected a crossing")

        // With automatic crossing calls on, the crossing is ALWAYS cleared automatically —
        // regardless of confidence. It never holds the pilot short waiting on Request Crossing.
        tick(coord) { coord.awaitingCrossingReadback }
        assertTrue(
            coord.awaitingCrossingReadback,
            "crossings auto-clear regardless of OSM confidence when automatic calls are on",
        )
        val text = h.collected.map { it.displayText.lowercase() }
        assertTrue(text.any { it.contains("cross runway") }, "a crossing clearance is issued")
        assertFalse(
            text.any { it.contains("hold short") },
            "a low-confidence crossing must not be held short when automatic calls are on",
        )
    }

    @Test
    fun autoModeNeverStopsOrHoldsAtCrossing() {
        // The "always clear to cross, never stop the user" behavior: even an aircraft driving
        // into the corridor must never draw a hold-position / stop warning while automatic
        // crossing calls are on.
        val h = makeCoordinator()
        val coord = h.coord
        coord.beginMockTaxiForTesting(TaxiKind.DEPARTURE, ref, runway = "36", gate = "A1")
        val route = coord.routeForTesting
        assertNotNull(route, "expected a crossing")
        val crossing = route.crossings.firstOrNull()
        assertNotNull(crossing, "expected a crossing")
        val line = route.line
        val approach = SurfaceGeometry.pointAlong(line, maxOf(0.0, crossing.alongMeters - 10))
            ?: crossing.point.toCoordinate()
        val heading = Geo.bearing(approach, crossing.point.toCoordinate())
        coord.feedForTesting(approach, heading, groundSpeed = 15.0)
        coord.feedForTesting(approach, heading, groundSpeed = 15.0)
        assertNotEquals(
            RunwayCrossingState.UNAUTHORIZED_CROSSING_DETECTED, coord.crossingState,
            "automatic mode never flags an unauthorized crossing",
        )
        assertFalse(
            h.collected.any {
                val t = it.displayText.lowercase()
                t.contains("hold position") || t.contains("stop immediately")
            },
            "automatic mode never issues a hold/stop warning at a crossing",
        )
    }

    @Test
    fun requestCrossingIssuesClearanceBeforeSettlingAtHold() {
        // Regression: at the runway threshold the Request Crossing button did nothing when
        // the aircraft hadn't tripped the settle-at-hold heuristics (the OSM hold point not
        // matching the sim scenery). Tapping it must issue the clearance regardless.
        val h = makeCoordinator()
        val coord = h.coord
        coord.autoCrossingCalls = false // force the manual Request-Crossing path
        coord.beginMockTaxiForTesting(TaxiKind.DEPARTURE, ref, runway = "36", gate = "A1")
        val route = coord.routeForTesting
        assertNotNull(route, "expected a crossing")
        val crossing = route.crossings.firstOrNull()
        assertNotNull(crossing, "expected a crossing")
        // Approach the crossing but stay moving and short of the mapped hold point, so the
        // "holding short + settled" gate has NOT been met.
        val line = route.line
        val approach = SurfaceGeometry.pointAlong(line, maxOf(0.0, crossing.alongMeters - 60))
            ?: crossing.point.toCoordinate()
        val heading = Geo.bearing(approach, crossing.point.toCoordinate())
        coord.feedForTesting(approach, heading, groundSpeed = 10.0)
        assertFalse(coord.awaitingCrossingReadback, "no automatic clearance with auto calls off")

        coord.requestCrossing()
        assertTrue(
            coord.awaitingCrossingReadback,
            "Request Crossing must issue the clearance even before settling at the hold",
        )
    }

    @Test
    fun autoCrossingCallsOverrideDisablesAutomation() {
        val h = makeCoordinator()
        val coord = h.coord
        coord.autoCrossingCalls = false
        coord.beginMockTaxiForTesting(TaxiKind.DEPARTURE, ref, runway = "36", gate = "A1")
        repeat(300) { coord.mockTickForTesting() }
        assertFalse(
            coord.awaitingCrossingReadback,
            "with automatic crossing calls off, no clearance auto-issues",
        )
        coord.requestCrossing()
        repeat(10) { coord.mockTickForTesting() }
        assertTrue(coord.awaitingCrossingReadback)
    }
}
