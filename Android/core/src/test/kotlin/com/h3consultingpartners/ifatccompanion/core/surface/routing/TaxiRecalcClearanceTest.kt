package com.h3consultingpartners.ifatccompanion.core.surface.routing

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.phraseology.CallsignDigitStyle
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyEngine
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyMode
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.surface.MockAirportSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Recalculating a taxi route — whether the pilot taps Recalculate / Request New Taxi, or
 * an automatic off-route recalculation fires — issues a fresh Ground taxi clearance with a
 * read-back **only when the route materially changes**. An identical route stays silent so
 * recalculating doesn't repeat the same instruction.
 *
 * Ported from `IFATCCompanionTests/TaxiRecalcClearanceTests.swift`. Its two Settings tests
 * (`testTaxiSurfaceTogglesPersistAcrossLaunches` / `testAppModelAppliesPersistedTaxiSurfaceToggles`)
 * exercise `AppSettings` and `AppModel`, which live in other packages, so they are ported
 * with those.
 */
class TaxiRecalcClearanceTest {

    private val ref = Coordinate(40.0, -75.0)

    /**
     * A live (non-mock) departure with a computed route to runway 36 that crosses runway 09,
     * with the initial Ground clearance already read back. Emitted transmissions append to
     * [emit].
     */
    private fun departureCoordinator(emit: (ATCTransmission) -> Unit): AirportSurfaceCoordinator {
        val coord = AirportSurfaceCoordinator(
            provider = null,
            scope = CoroutineScope(Dispatchers.Unconfined),
            clock = MutableClock(0L),
        )
        val engine = PhraseologyEngine(
            digitStyle = CallsignDigitStyle.INDIVIDUAL, mode = PhraseologyMode.FAA,
        )
        coord.configure(
            engine = engine, emit = emit,
            callsign = { engine.callsign(airline = "United", flightNumber = "598", fallback = "") },
        )
        val model = MockAirportSurface.model(
            icao = "KTEST", reference = ref, primaryRunwayIdent = "36", gate = "A1", nowMillis = 0L,
        )
        coord.simulateDeferredDepartureForTesting(model, runway = "36", gate = "A1")
        coord.taxiReadBackComplete() // the pilot reads back the initial clearance
        return coord
    }

    /**
     * Ground taxi clearances ("… taxi to runway …") emitted so far. Excludes runway crossing
     * clearances ("cross runway …") and the low-confidence "taxi toward runway …" fallback.
     */
    private fun taxiClearances(txs: List<ATCTransmission>): List<ATCTransmission> =
        txs.filter { it.displayText.lowercase().contains("taxi to runway") }

    // MARK: - Manual recalculate

    @Test
    fun manualRecalculateIssuesNewGroundClearanceWhenRouteChanges() {
        val emitted = mutableListOf<ATCTransmission>()
        val coord = departureCoordinator { emitted.add(it) }
        assertEquals(1, taxiClearances(emitted).size, "the initial detailed clearance went out")
        assertEquals(
            false, coord.routeForTesting?.crossings?.isEmpty(),
            "the initial route crosses runway 09",
        )

        // The aircraft has taxied down taxiway A, past the crossing. Recalculating from here
        // resolves to a route that no longer crosses a runway — a materially different clearance.
        val pastCrossing = Coordinate(ref.latitude - 0.0015, ref.longitude + 0.0030)
        coord.feedForTesting(pastCrossing, heading = 180.0, groundSpeed = 0.0)
        coord.recalculateRoute()

        val clearances = taxiClearances(emitted)
        assertEquals(2, clearances.size, "recalculating to a new route re-issues a Ground taxi clearance")
        assertTrue(
            clearances.last().displayText.lowercase().contains("taxi to runway 36 via"),
            "the new clearance names the runway and taxiway sequence",
        )
        assertNotNull(clearances.last().readback, "the recalculated clearance carries a read-back")
        assertTrue(coord.awaitingTaxiReadback, "the recalculated clearance re-arms the read-back")
        assertEquals(
            true, coord.routeForTesting?.crossings?.isEmpty(),
            "the recalculated route no longer crosses a runway",
        )
    }

    @Test
    fun manualRecalculateStaysSilentWhenRouteUnchanged() {
        val emitted = mutableListOf<ATCTransmission>()
        val coord = departureCoordinator { emitted.add(it) }
        assertEquals(1, taxiClearances(emitted).size)
        assertFalse(coord.awaitingTaxiReadback, "the initial read-back is complete")

        // Recalculating from the same stand reproduces the same route → no new instruction.
        val gate = MockAirportSurface.gateCoordinate(ref)
        coord.feedForTesting(gate, heading = 180.0, groundSpeed = 0.0)
        coord.recalculateRoute()

        assertEquals(
            1, taxiClearances(emitted).size,
            "an unchanged route does not re-issue a clearance",
        )
        assertFalse(coord.awaitingTaxiReadback, "no read-back is armed when the route is unchanged")
    }

    // MARK: - Automatic off-route recalculate

    @Test
    fun autoRecalculateIssuesNewClearanceOnSustainedOffRoute() {
        val emitted = mutableListOf<ATCTransmission>()
        val coord = departureCoordinator { emitted.add(it) }
        coord.autoRecalculate = true

        // Off the route (a lateral offset beyond the tracker threshold) and past the crossing,
        // held for several ticks. Auto-recalculate re-plans from here — dropping the crossing —
        // and Ground issues the updated clearance rather than latching the off-route banner.
        val offRoutePastCrossing = Coordinate(ref.latitude - 0.0015, ref.longitude + 0.0037)
        repeat(6) {
            coord.feedForTesting(offRoutePastCrossing, heading = 180.0, groundSpeed = 0.0)
        }

        assertFalse(
            coord.offRoute,
            "auto-recalculate re-plans instead of latching the off-route banner",
        )
        assertEquals(
            2, taxiClearances(emitted).size,
            "the automatic recalculation issues one fresh Ground clearance",
        )
        assertTrue(coord.awaitingTaxiReadback, "the automatic clearance arms a read-back")
    }

    @Test
    fun offRouteWithoutAutoRecalculateShowsBannerAndDoesNotReissue() {
        val emitted = mutableListOf<ATCTransmission>()
        val coord = departureCoordinator { emitted.add(it) }
        // autoRecalculate stays at its default (off).

        val offRoutePastCrossing = Coordinate(ref.latitude - 0.0015, ref.longitude + 0.0037)
        repeat(6) {
            coord.feedForTesting(offRoutePastCrossing, heading = 180.0, groundSpeed = 0.0)
        }

        assertTrue(
            coord.offRoute,
            "without auto-recalculate the off-route banner latches for the pilot to decide",
        )
        assertEquals(
            1, taxiClearances(emitted).size,
            "no new clearance is issued while the pilot decides",
        )
        assertFalse(coord.awaitingTaxiReadback, "no read-back is armed while the banner is shown")
    }
}
