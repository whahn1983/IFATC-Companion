package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.enroute.CenterSector
import com.h3consultingpartners.ifatccompanion.core.enroute.CenterSectorDatabase
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Center-to-Center hand-offs.
 *
 * The sector was already being *named* — Center identified itself correctly — but a flight
 * crossing a boundary was never handed to the next sector, so a pilot flew a whole en-route
 * leg on one frequency while the app called the controller by a name that kept changing
 * under them.
 */
class CenterSectorHandoffTest {

    private val plan = FlightPlan.empty.copy(
        departure = "KIAH",
        destination = "KORD",
        callsign = "United 598",
        cruiseAltitude = 35_000,
    )

    /** A rectangular sector, for a test that cares about crossings rather than geography. */
    private fun box(
        id: String,
        radio: String,
        latitudes: ClosedFloatingPointRange<Double>,
        longitudes: ClosedFloatingPointRange<Double>,
        frequency: Double,
    ) = CenterSector(
        id = id,
        name = id,
        radioName = radio,
        isOceanic = false,
        publishedFrequency = frequency,
        minLat = latitudes.start,
        maxLat = latitudes.endInclusive,
        minLon = longitudes.start,
        maxLon = longitudes.endInclusive,
        polygons = listOf(
            listOf(
                listOf(
                    longitudes.start, latitudes.start,
                    longitudes.endInclusive, latitudes.start,
                    longitudes.endInclusive, latitudes.endInclusive,
                    longitudes.start, latitudes.endInclusive,
                    longitudes.start, latitudes.start,
                ),
            ),
        ),
    )

    /** Two sectors sharing the 95°W edge, wide enough to fly across in short hops. */
    private fun database() = CenterSectorDatabase(
        listOf(
            box("WEST", "Houston Center", 25.0..45.0, -105.0..-95.0, 133.400),
            box("EAST", "Memphis Center", 25.0..45.0, -95.0..-85.0, 134.750),
        ),
    )

    private fun coordinator(
        scope: TestScope,
        db: CenterSectorDatabase = database(),
    ) = FlightSessionCoordinator(
        scope = scope,
        clock = MutableClock(0),
        settingsProvider = {
            AppSettings(mockMode = true, voiceEnabled = false, centerSectorHandoffs = true)
        },
        sectorDatabase = db,
    )

    private fun cruising(longitude: Double) = AircraftState(
        latitude = 35.0,
        longitude = longitude,
        altitudeMSL = 35_000.0,
        altitudeAGL = 35_000.0,
        groundSpeed = 460.0,
        verticalSpeed = 0.0,
        heading = 90.0,
        onGround = false,
    )

    /** Fly east in hops small enough that the tracker reads a flown crossing, not a jump. */
    private fun FlightSessionCoordinator.flyEast(from: Double, to: Double, stepDegrees: Double = 0.4) {
        var longitude = from
        while (longitude <= to) {
            ingestAircraftState(cruising(longitude))
            longitude += stepDegrees
        }
    }

    private fun atcLines(coordinator: FlightSessionCoordinator) =
        coordinator.state.value.transcript
            .filter { it.sender == ATCTransmission.Sender.ATC }
            .map { it.displayText }

    @Test
    fun `crossing into the next sector puts a hand-off on the radio`() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        // Established well inside the western sector first: the tracker adopts the first
        // sector it sees silently, because entering the first sector of a flight is not a
        // crossing — Departure's hand-off is what put the pilot on that Center.
        coordinator.flyEast(from = -99.0, to = -96.0)
        val beforeCrossing = atcLines(coordinator)

        coordinator.flyEast(from = -95.6, to = -93.0)

        val newLines = atcLines(coordinator).drop(beforeCrossing.size)
        val handoff = newLines.lastOrNull { it.contains("contact", ignoreCase = true) }
        assertNotNull(handoff, "no hand-off was issued crossing into the next sector: $newLines")
        assertTrue(handoff.contains("Memphis Center"), "the wrong sector was named: $handoff")
        assertTrue(handoff.contains("134.750"), "the new sector's frequency was not given: $handoff")
    }

    @Test
    fun `the hand-off names the sector, not just Center`() = runTest {
        // The whole point of the sector map. "Contact Center on 134.750" is what the app
        // said before the sector was resolved, and it is not what a controller says.
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.flyEast(from = -99.0, to = -96.0)
        assertEquals("Houston Center", coordinator.state.value.centerSectorName)

        coordinator.flyEast(from = -95.6, to = -93.0)
        assertEquals("Memphis Center", coordinator.state.value.centerSectorName)
    }

    @Test
    fun `checking in after a sector hand-off is answered with radar contact, not the next clearance`() = runTest {
        // Falling through to the generic check-in answers a cruise call-up with the
        // top-of-descent call, because descent is the next Center state after cruise — the
        // pilot announces themselves and is told to start down.
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.flyEast(from = -99.0, to = -96.0)
        coordinator.flyEast(from = -95.6, to = -93.0)

        coordinator.tuneTo(ATCFacility.CENTER)
        coordinator.checkIn()

        val last = atcLines(coordinator).last()
        assertTrue(
            last.contains("radar contact", ignoreCase = true),
            "the check-in was answered with \"$last\" instead of radar contact",
        )
        assertTrue(
            !last.contains("descend", ignoreCase = true),
            "the check-in was answered with a descent clearance: $last",
        )
    }

    @Test
    fun `a crossing under another controller is never announced`() = runTest {
        // Approach owns the radio at the arrival end of the en-route leg. The sector still
        // moves under the aircraft, but nobody says so: a crossing made under another
        // controller is never announced late, because whoever hands the flight to Center
        // names the sector it is in at that moment.
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.flyEast(from = -99.0, to = -96.0)

        // Put the conversation on Approach and keep it there while the boundary passes.
        val onApproach = coordinator.captureSnapshot().copy(
            atcState = ATCState.APPROACH,
            stateMachineCurrent = ATCState.APPROACH,
            currentFacility = ATCFacility.APPROACH,
        )
        val arriving = coordinator(this)
        arriving.ingestFlightPlan(plan)
        arriving.restore(onApproach)
        assertEquals(ATCFacility.APPROACH, arriving.state.value.currentFacility)
        val before = atcLines(arriving).size

        arriving.flyEast(from = -95.6, to = -93.0)

        assertTrue(
            atcLines(arriving).drop(before).none { it.contains("contact Memphis Center", ignoreCase = true) },
            "a sector hand-off was announced while Approach had the radio: " +
                atcLines(arriving).drop(before),
        )
    }

    @Test
    fun `a reconnect does not re-announce a hand-off the pilot already made`() = runTest {
        // The restore runs before the sector map has finished parsing, so without carrying
        // the sector id the tracker starts empty and the first fix looks like a first entry
        // — and the pilot is handed to the sector they are already talking to.
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.flyEast(from = -99.0, to = -96.0)
        coordinator.flyEast(from = -95.6, to = -93.0)
        val snapshot = coordinator.captureSnapshot()
        assertEquals("EAST", snapshot.centerSectorID, "the working sector was not captured")

        val relaunched = coordinator(this)
        relaunched.ingestFlightPlan(plan)
        relaunched.restore(snapshot)
        val afterRestore = atcLines(relaunched).size
        relaunched.flyEast(from = -92.6, to = -91.0)

        assertTrue(
            atcLines(relaunched).drop(afterRestore).none { it.contains("contact", ignoreCase = true) },
            "the restored session re-announced a hand-off already made",
        )
    }

    @Test
    fun `starting a new flight forgets the sector`() = runTest {
        val coordinator = coordinator(this)
        coordinator.ingestFlightPlan(plan)
        coordinator.flyEast(from = -99.0, to = -96.0)
        assertNotNull(coordinator.state.value.centerSectorName)

        coordinator.resetForNewFlight()
        assertEquals(null, coordinator.state.value.centerSectorName)
        assertEquals(null, coordinator.captureSnapshot().centerSectorID)
    }
}
