package com.h3consultingpartners.ifatccompanion.core.surface.routing

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import com.h3consultingpartners.ifatccompanion.core.surface.AirportSurfaceModel
import com.h3consultingpartners.ifatccompanion.core.surface.GeoCoordinate
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceConfidence
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceParking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Filling a blank gate from the airport's own stand data.
 *
 * `GateAssigner` was ported with tests and called from nowhere, so the "Assign gates
 * automatically" switch wrote a boolean nobody read. These pin the three rules that make
 * the feature safe rather than merely present: a gate the pilot typed is never touched, a
 * gate assigned at a different airport is dropped before anything else is read, and the
 * app's own guess is replaced only by better information.
 */
class AutoGateControllerTest {

    private val field = Coordinate(29.98, -95.34)

    private fun stand(name: String, offsetMetres: Double) = SurfaceParking(
        osmID = "stand-$name",
        tags = mapOf("aeroway" to "gate", "ref" to name),
        kind = SurfaceParking.Kind.GATE,
        name = name,
        coordinate = GeoCoordinate(Geo.destination(field, 90.0, offsetMetres / 1852.0)),
    )

    private val surface = AirportSurfaceModel(
        icao = "KIAH",
        reference = GeoCoordinate(field),
        runways = emptyList(),
        runwayEnds = emptyList(),
        taxiways = emptyList(),
        holdingPositions = emptyList(),
        parkingPositions = listOf(stand("C24", 0.0), stand("C26", 400.0)),
        aprons = emptyList(),
        source = testProvenance(center = field, rawElementCount = 2),
        confidence = SurfaceConfidence.HIGH,
    )

    /** Records everything the controller writes, exactly as the app's settings would. */
    private class Rig(
        initial: AppSettings,
        private val surface: AirportSurfaceModel?,
        val plan: FlightPlan,
        val aircraft: AircraftState = AircraftState.empty,
        val taxiing: Boolean = false,
    ) {
        var settings: AppSettings = initial
            private set
        var reads = 0
            private set

        val controller = AutoGateController(
            clock = MutableClock(0),
            settingsProvider = { settings },
            planProvider = { plan },
            aircraftProvider = { aircraft },
            taxiHasBegun = { taxiing },
            surfaceProvider = { _, _ ->
                reads += 1
                surface
            },
            referenceProvider = { Coordinate(29.98, -95.34) },
            writeGate = { role, gate, stamp ->
                settings = when (role) {
                    GateRole.DEPARTURE -> settings.copy(departureGate = gate, autoAssignedDepartureGate = stamp)
                    GateRole.ARRIVAL -> settings.copy(arrivalGate = gate, autoAssignedArrivalGate = stamp)
                }
            },
        )
    }

    private val houston = FlightPlan.empty.copy(
        callsign = "UAL598",
        airline = "United",
        flightNumber = "598",
        departure = "KIAH",
        destination = "KMSP",
    )

    @Test
    fun aBlankDepartureGateIsFilledFromTheFieldsStands() = runTest {
        val rig = Rig(AppSettings(autoAssignGates = true), surface, houston)

        rig.controller.assignIfNeeded()

        assertTrue(
            rig.settings.departureGate.isNotEmpty(),
            "a blank field with stands in the extract is exactly what the feature is for",
        )
        assertTrue(
            rig.settings.autoAssignedDepartureGate.contains("KIAH"),
            "the marker is what tells a later assignment the gate is the app's: ${rig.settings.autoAssignedDepartureGate}",
        )
    }

    /** The switch is what turns it on. Off means nothing is read and nothing is written. */
    @Test
    fun nothingHappensWhenTheFeatureIsOff() = runTest {
        val rig = Rig(AppSettings(autoAssignGates = false), surface, houston)

        rig.controller.assignIfNeeded()

        assertEquals("", rig.settings.departureGate)
        assertEquals(0, rig.reads)
    }

    @Test
    fun aGateThePilotTypedIsNeverOverwritten() = runTest {
        val rig = Rig(
            AppSettings(autoAssignGates = true, departureGate = "E7"),
            surface,
            houston,
        )

        rig.controller.assignIfNeeded()

        assertEquals("E7", rig.settings.departureGate)
    }

    /**
     * A stale gate reads as though it belonged here, which is how an arrival comes to show
     * a stand that exists at no terminal at the field. It goes before anything else is read.
     */
    @Test
    fun aGateAssignedAtAnotherAirportIsDropped() = runTest {
        val rig = Rig(
            AppSettings(
                autoAssignGates = true,
                arrivalGate = "B44",
                autoAssignedArrivalGate = "KLAX:B44",
            ),
            // No extract for this field, so nothing can replace it — the drop still happens.
            surface = null,
            plan = houston,
        )

        rig.controller.assignIfNeeded()

        assertEquals("", rig.settings.arrivalGate)
        assertEquals("", rig.settings.autoAssignedArrivalGate)
    }

    /** Once the taxi is under way the gate is where the aircraft is; moving it re-routes the push. */
    @Test
    fun theDepartureGateIsLeftAloneOnceTheTaxiHasBegun() = runTest {
        val rig = Rig(AppSettings(autoAssignGates = true), surface, houston, taxiing = true)

        rig.controller.assignIfNeeded()

        assertEquals("", rig.settings.departureGate)
    }

    /** Switching the feature off gives back the app's own gate and leaves the pilot's alone. */
    @Test
    fun switchingOffWithdrawsOnlyTheAppsOwnGate() = runTest {
        val rig = Rig(
            AppSettings(
                autoAssignGates = false,
                departureGate = "C24",
                autoAssignedDepartureGate = "KIAH:C24",
                arrivalGate = "E7",
            ),
            surface,
            houston,
        )

        rig.controller.applySettingChange()

        assertEquals("", rig.settings.departureGate, "the app assigned this one, so it is the app's to withdraw")
        assertEquals("E7", rig.settings.arrivalGate, "the pilot typed this one")
    }

    /**
     * A field with no readable extract stops being asked after a few tries rather than being
     * re-requested from a shared public endpoint for the rest of the flight.
     */
    @Test
    fun anUnreadableExtractIsNotRetriedForever() = runTest {
        val rig = Rig(AppSettings(autoAssignGates = true), surface = null, plan = houston)

        repeat(10) { rig.controller.assignIfNeeded() }

        assertTrue(
            rig.reads <= AutoGateController.MAX_READ_FAILURES * GateRole.entries.size,
            "read ${rig.reads} times, which is more than the failure budget allows",
        )
    }
}
