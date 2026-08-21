package com.h3consultingpartners.ifatccompanion.core.surface.routing

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.surface.AircraftSizeClass
import com.h3consultingpartners.ifatccompanion.core.surface.AirportSurfaceModel
import com.h3consultingpartners.ifatccompanion.core.surface.GeoCoordinate
import com.h3consultingpartners.ifatccompanion.core.surface.MockAirportSurface
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceParking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Automatic gate assignment: the optional (off-by-default) feature that fills a **blank**
 * Dep Gate / Arr Gate with a real stand from the airport's OpenStreetMap extract.
 *
 * Covers the three things that make it safe and useful: what the OSM stand tags are read to
 * mean, how a stand is chosen from them (airline, aircraft size, cargo, terminal vs. remote,
 * random among equals), and the "only when the pilot left it blank" rule that decides
 * whether the app is allowed to write the field at all.
 *
 * Ported from `IFATCCompanionTests/GateAssignmentTests.swift`.
 */
class GateAssignmentTest {

    private val ref = Coordinate(29.9844, -95.3414)

    // A deterministic generator, so a test that asserts on a random pick asserts on a fixed
    // one. Any fixed sequence will do — the point is repeatability, not statistical quality.
    private fun seeded(seed: Long) = Random(seed)

    private fun stand(
        name: String,
        kind: SurfaceParking.Kind = SurfaceParking.Kind.GATE,
        tags: Map<String, String> = emptyMap(),
        dLat: Double = 0.001,
        dLon: Double = 0.001,
    ): SurfaceParking {
        val allTags = tags.toMutableMap()
        allTags["aeroway"] = if (kind == SurfaceParking.Kind.GATE) "gate" else "parking_position"
        if (allTags["ref"] == null) allTags["ref"] = name
        return SurfaceParking(
            osmID = "node/$name", tags = allTags, kind = kind, name = name,
            coordinate = GeoCoordinate(ref.latitude + dLat, ref.longitude + dLon),
        )
    }

    /**
     * A surface carrying only the stands under test (the rest of the field is irrelevant to
     * the assignment, which reads `parkingPositions` alone).
     */
    private fun surface(stands: List<SurfaceParking>, icao: String = "KIAH"): AirportSurfaceModel =
        MockAirportSurface.model(
            icao = icao, reference = ref, primaryRunwayIdent = "15L", gate = "A1", nowMillis = 0L,
        ).copy(parkingPositions = stands)

    private fun assign(
        stands: List<SurfaceParking>,
        flight: GateAssigner.FlightContext,
        role: GateRole = GateRole.DEPARTURE,
        seed: Long = 42,
    ): GateAssigner.Assignment? =
        GateAssigner.assign(surface(stands), flight, role, seeded(seed))

    // MARK: - Reading the OSM stand tags

    @Test
    fun aircraftTypeTagGivesTheStandsSizeClass() {
        assertEquals(
            AircraftSizeClass.MEDIUM,
            StandProfile.from(mapOf("aircraft:type" to "A320"), "B12").maxClass,
            "an airframe designator sizes the stand",
        )
        assertEquals(
            AircraftSizeClass.HEAVY,
            StandProfile.from(mapOf("aircraft:type" to "B738;B77W"), "B12").maxClass,
            "a multi-value tag takes the largest aircraft named",
        )
        assertEquals(
            AircraftSizeClass.LARGE,
            StandProfile.from(mapOf("aircraft:type" to "wide_body"), "B12").maxClass,
            "a size band is understood as well as an airframe",
        )
        assertEquals(
            AircraftSizeClass.MEDIUM,
            StandProfile.from(mapOf("aircraft:type" to "code_c"), "B12").maxClass,
            "an ICAO aerodrome reference code is understood",
        )
        assertNull(
            StandProfile.from(mapOf("aircraft:type" to "whatever"), "B12").maxClass,
            "an unrecognised value stays unknown rather than inventing a size",
        )
        assertNull(
            StandProfile.from(emptyMap(), "B12").maxClass,
            "an untagged stand has no size",
        )
    }

    @Test
    fun standSizeDecidesWhatFitsAndHowSnugly() {
        val narrow = StandProfile.from(mapOf("aircraft:type" to "A320"), "B12")
        assertTrue(narrow.accepts(AircraftSizeClass.MEDIUM))
        assertTrue(narrow.accepts(AircraftSizeClass.SMALL), "a smaller aircraft fits a bigger stand")
        assertFalse(narrow.accepts(AircraftSizeClass.HEAVY), "a 777 does not fit an A320 stand")
        assertEquals(0, narrow.fitGap(AircraftSizeClass.MEDIUM), "an exact match is a snug fit")
        assertEquals(
            2, narrow.fitGap(AircraftSizeClass.LIGHT),
            "a light aircraft is two classes small for it",
        )

        val untagged = StandProfile.from(emptyMap(), "B12")
        assertTrue(
            untagged.accepts(AircraftSizeClass.HEAVY),
            "an untagged stand never disqualifies an aircraft",
        )
        assertEquals(
            0, untagged.fitGap(AircraftSizeClass.HEAVY),
            "there is nothing to grade an untagged stand on",
        )
    }

    @Test
    fun helicopterAndAccessAndCargoAndServiceTags() {
        assertTrue(StandProfile.from(mapOf("aircraft:type" to "helicopter"), "H1").helicopterOnly)
        assertFalse(
            StandProfile.from(mapOf("aircraft:type" to "helicopter;A320"), "H1").helicopterOnly,
            "a stand that also takes fixed-wing aircraft is not helicopter-only",
        )
        assertTrue(StandProfile.from(mapOf("access" to "private"), "X1").restricted)
        assertTrue(StandProfile.from(mapOf("access" to "no"), "X1").restricted)
        assertFalse(StandProfile.from(mapOf("access" to "yes"), "X1").restricted)
        assertTrue(
            StandProfile.from(mapOf("operator" to "Lufthansa Cargo"), "C1").cargo,
            "a freight operator marks a cargo stand",
        )
        assertTrue(StandProfile.from(mapOf("name" to "Cargo stand 4"), "C4").cargo)
        assertTrue(StandProfile.from(mapOf("description" to "de-icing pad"), "D1").servicePosition)
        assertTrue(StandProfile.from(mapOf("name" to "Maintenance apron"), "M1").servicePosition)
    }

    @Test
    fun anAirportAuthorityNameIsNeverReadAsAServicePosition() {
        // The operator text is deliberately not searched for purpose words: an authority or
        // city name that happens to contain one must not disqualify a real stand.
        val profile = StandProfile.from(mapOf("operator" to "Aeroporto di Firenze"), "201")
        assertFalse(
            profile.servicePosition,
            "an operator name is not a purpose tag — the stand stays assignable",
        )
    }

    // MARK: - Choosing a stand

    @Test
    fun unnamedAndServiceStandsAreNeverAssigned() {
        val flight = GateAssigner.FlightContext(callsign = "UAL598", aircraftName = "Boeing 737-800")
        assertNull(assign(emptyList(), flight), "a field with no stands assigns nothing")
        assertNull(
            assign(listOf(stand("", tags = mapOf("ref" to ""))), flight),
            "a stand with no identifier can't be named in a clearance",
        )
        assertNull(
            assign(listOf(stand("D1", tags = mapOf("name" to "De-icing pad"))), flight),
            "no flight is sent to park on the de-icing pad",
        )
    }

    @Test
    fun theAirlinesOwnStandWins() {
        val stands = listOf(
            stand("A1", tags = mapOf("operator" to "Delta Air Lines")),
            stand("B12", tags = mapOf("operator" to "United Airlines")),
            stand("C3", tags = mapOf("operator" to "American Airlines")),
        )
        val flight = GateAssigner.FlightContext(
            callsign = "UAL598", airline = "United", aircraftName = "Boeing 737-800",
        )
        assertEquals("B12", assign(stands, flight)?.gate)
        assertEquals(true, assign(stands, flight)?.matchedOperator)
    }

    @Test
    fun aBrandThatDiffersFromTheTelephonyNameStillMatches() {
        // "Speedbird" is British Airways on the radio and nowhere in OSM, so the brand table
        // is what makes this match.
        val stands = listOf(
            stand("A1", tags = mapOf("operator" to "Lufthansa")),
            stand("T5-501", tags = mapOf("operator" to "British Airways")),
        )
        val flight = GateAssigner.FlightContext(
            callsign = "BAW117", airline = "Speedbird", aircraftName = "Boeing 777-300ER",
        )
        assertEquals("T5-501", assign(stands, flight)?.gate)
    }

    @Test
    fun theSnuggestStandThatFitsIsPreferred() {
        val stands = listOf(
            stand("H1", tags = mapOf("aircraft:type" to "B77W")),
            stand("N4", tags = mapOf("aircraft:type" to "A320")),
            stand("U9"),
        )
        val narrowbody = GateAssigner.FlightContext(callsign = "UAL598", aircraftName = "Airbus A320")
        assertEquals(
            "N4", assign(stands, narrowbody)?.gate,
            "a narrowbody takes the narrowbody stand, not the widebody one",
        )

        val widebody = GateAssigner.FlightContext(callsign = "UAL1", aircraftName = "Boeing 777-300ER")
        assertEquals(
            "H1", assign(stands, widebody)?.gate,
            "a widebody takes the stand that actually fits it",
        )
    }

    @Test
    fun aStandTooSmallIsUsedOnlyWhenNothingElseIs() {
        val heavy = GateAssigner.FlightContext(callsign = "UAL1", aircraftName = "Boeing 777-300ER")
        val onlySmall = listOf(stand("R2", tags = mapOf("aircraft:type" to "CRJ900")))
        assertEquals(
            "R2", assign(onlySmall, heavy)?.gate,
            "an undersized stand still beats leaving the pilot with no gate",
        )

        val withUntagged = onlySmall + stand("U9")
        assertEquals(
            "U9", assign(withUntagged, heavy)?.gate,
            "but an untagged stand that might fit is preferred over one that can't",
        )
    }

    @Test
    fun helicopterPadsAndRestrictedStandsAreLastResorts() {
        val flight = GateAssigner.FlightContext(callsign = "UAL598", aircraftName = "Boeing 737-800")
        assertEquals(
            "B2",
            assign(
                listOf(stand("H1", tags = mapOf("aircraft:type" to "helicopter")), stand("B2")),
                flight,
            )?.gate,
        )
        assertEquals(
            "B2",
            assign(
                listOf(stand("P1", tags = mapOf("access" to "private")), stand("B2")),
                flight,
            )?.gate,
        )
    }

    @Test
    fun cargoAndPassengerStandsAreNotInterchangeable() {
        val stands = listOf(stand("PAX3"), stand("CGO1", tags = mapOf("operator" to "FedEx Cargo")))
        val airliner = GateAssigner.FlightContext(callsign = "UAL598", aircraftName = "Boeing 737-800")
        assertEquals(
            "PAX3", assign(stands, airliner)?.gate,
            "a passenger flight is not sent to the freight ramp",
        )

        val freighter = GateAssigner.FlightContext(callsign = "FDX1234", aircraftName = "Boeing 777F")
        assertEquals(
            "CGO1", assign(stands, freighter)?.gate,
            "and a freighter is not sent to a passenger gate",
        )
    }

    @Test
    fun terminalGatesForAirlinersAndRemoteStandsForLightAircraft() {
        val stands = listOf(
            stand("B12", kind = SurfaceParking.Kind.GATE),
            stand("GA7", kind = SurfaceParking.Kind.PARKING_POSITION),
        )
        val airliner = GateAssigner.FlightContext(callsign = "UAL598", aircraftName = "Boeing 737-800")
        assertEquals("B12", assign(stands, airliner)?.gate)

        val ga = GateAssigner.FlightContext(callsign = "N123AB", aircraftName = "Cessna 172")
        assertEquals(
            "GA7", assign(stands, ga)?.gate,
            "a light single parks on the ramp, not at a jet bridge",
        )
    }

    @Test
    fun equallySuitableStandsAreDrawnAtRandomSoTheGateVaries() {
        val stands = (1..12).map { stand("B$it") }
        val flight = GateAssigner.FlightContext(callsign = "UAL598", aircraftName = "Boeing 737-800")
        val picks = mutableSetOf<String>()
        for (seed in 1L..40L) {
            val assignment = assign(stands, flight, seed = seed)
            assertNotNull(assignment, "a field of twelve plain stands must always assign one")
            assertEquals(12, assignment.tiedCandidates, "all twelve stands are equally suitable")
            assertEquals(12, assignment.totalCandidates)
            picks.add(assignment.gate)
        }
        assertTrue(
            picks.size > 1,
            "the same field must not hand out the same stand every flight",
        )
    }

    @Test
    fun assignmentReportsWhereItCameFrom() {
        val stands = listOf(
            stand("B12", tags = mapOf("operator" to "United Airlines", "aircraft:type" to "B738")),
        )
        val flight = GateAssigner.FlightContext(
            callsign = "UAL598", airline = "United", aircraftName = "Boeing 737-800",
        )
        val assignment = assign(stands, flight)
        assertNotNull(assignment, "the tagged United stand must be assigned")
        assertEquals("B12", assignment.gate)
        assertEquals("node/B12", assignment.osmID)
        assertTrue(assignment.matchedOperator)
        assertTrue(assignment.matchedAircraftType)
        assertTrue(
            assignment.reason.contains("operator match"),
            "the log line explains the choice: ${assignment.reason}",
        )
    }

    // MARK: - The gate the aircraft is parked on

    /**
     * A coordinate offset from the field reference, so a test can park the aircraft a known
     * distance from a stand built with the same offset convention.
     */
    private fun offset(dLat: Double, dLon: Double) =
        GeoCoordinate(ref.latitude + dLat, ref.longitude + dLon)

    @Test
    fun aStandTheAircraftIsParkedOnBeatsEverySignal() {
        // B12 is the airline's own stand *and* sized for the aircraft — it would win on the
        // tags alone. The aircraft is sitting on F3, so F3 is the gate.
        val stands = listOf(
            stand(
                "B12", tags = mapOf("operator" to "United Airlines", "aircraft:type" to "B738"),
                dLat = 0.001, dLon = 0.001,
            ),
            stand("F3", dLat = 0.010, dLon = 0.010),
        )
        val flight = GateAssigner.FlightContext(
            callsign = "UAL598", airline = "United", aircraftName = "Boeing 737-800",
            parkedPosition = offset(0.010, 0.010),
        )
        val assignment = assign(stands, flight)
        assertNotNull(assignment, "a parked aircraft on a mapped stand must be assigned that stand")
        assertEquals("F3", assignment.gate)
        assertTrue(assignment.fromAircraftPosition, "it was read, not chosen")
        assertTrue(
            assignment.reason.contains("parked on it"),
            "the log says where it came from: ${assignment.reason}",
        )
    }

    @Test
    fun theNearestStandWinsWhenSeveralAreInRange() {
        val stands = listOf(
            stand("A1", dLat = 0.0000, dLon = 0.0000),
            stand("A2", dLat = 0.0003, dLon = 0.0000),
            stand("A3", dLat = 0.0006, dLon = 0.0000),
        )
        // ~0.0003° of latitude is ~33 m, so all three sit inside the 80 m radius; the aircraft
        // is parked on A2's node exactly.
        val flight = GateAssigner.FlightContext(
            callsign = "UAL598", aircraftName = "Boeing 737-800",
            parkedPosition = offset(0.0003, 0.0000),
        )
        assertEquals("A2", assign(stands, flight)?.gate)
    }

    @Test
    fun aPositionNowhereNearAStandFallsBackToChoosingOne() {
        val stands = listOf(
            stand("B12", tags = mapOf("operator" to "United Airlines"), dLat = 0.001, dLon = 0.001),
        )
        // Parked a long way from the only stand — out on a taxiway, or a field whose stands
        // aren't mapped where the aircraft is.
        val flight = GateAssigner.FlightContext(
            callsign = "UAL598", airline = "United", aircraftName = "Boeing 737-800",
            parkedPosition = offset(0.050, 0.050),
        )
        val assignment = assign(stands, flight)
        assertNotNull(assignment, "it still assigns a stand, just not a position-derived one")
        assertEquals("B12", assignment.gate)
        assertFalse(assignment.fromAircraftPosition, "this one was chosen, not read")
    }

    @Test
    fun noPositionAtAllStillChoosesAStand() {
        val stands = listOf(stand("B12"))
        val flight = GateAssigner.FlightContext(callsign = "UAL598", aircraftName = "Boeing 737-800")
        val assignment = assign(stands, flight)
        assertEquals("B12", assignment?.gate)
        assertEquals(false, assignment?.fromAircraftPosition)
    }

    @Test
    fun theArrivalGateIsNeverReadOffTheAircraftsPosition() {
        // Origin and destination are the same field (a there-and-back leg), so the aircraft is
        // parked on a stand of the *arrival* surface too. Reading it would hand back the stand
        // the flight is leaving.
        val stands = listOf(
            stand("F3", dLat = 0.010, dLon = 0.010),
            stand("B12", dLat = 0.001, dLon = 0.001),
        )
        val flight = GateAssigner.FlightContext(
            callsign = "UAL598", aircraftName = "Boeing 737-800",
            parkedPosition = offset(0.010, 0.010),
        )
        val arrival = assign(stands, flight, role = GateRole.ARRIVAL)
        assertEquals(
            false, arrival?.fromAircraftPosition,
            "the arrival gate is chosen from the stand data, never from where we are",
        )
        assertEquals(
            "F3", assign(stands, flight, role = GateRole.DEPARTURE)?.gate,
            "while the departure gate at the same field is read off the position",
        )
    }

    @Test
    fun parkingOnAServiceOrUnnamedStandIsStillNotAGate() {
        val deicing = stand("D1", tags = mapOf("name" to "De-icing pad"), dLat = 0.010, dLon = 0.010)
        val unnamed = stand("", tags = mapOf("ref" to ""), dLat = 0.010, dLon = 0.010)
        val realGate = stand("B12", dLat = 0.001, dLon = 0.001)
        val flight = GateAssigner.FlightContext(
            callsign = "UAL598", aircraftName = "Boeing 737-800",
            parkedPosition = offset(0.010, 0.010),
        )
        // Sitting on the de-icing pad (or an unidentified stand) names neither — the real gate
        // is chosen instead, because neither can be said in a clearance.
        val assignment = assign(listOf(deicing, unnamed, realGate), flight)
        assertNotNull(assignment, "the real gate is still assignable")
        assertEquals("B12", assignment.gate)
        assertFalse(assignment.fromAircraftPosition)
    }

    // MARK: - "Only when the pilot left it blank"

    @Test
    fun stampRoundTrips() {
        val stamp = AutoGateStamp(icao = "kiah", gate = " C24 ")
        assertEquals("KIAH:C24", stamp.encoded)
        assertEquals(AutoGateStamp("KIAH", "C24"), AutoGateStamp.decode("KIAH:C24"))
        assertNull(AutoGateStamp.decode(""))
        assertNull(AutoGateStamp.decode("KIAH"))
        assertNull(AutoGateStamp.decode(":C24"))
        assertEquals(
            "", AutoGateStamp(icao = "KIAH", gate = "").encoded,
            "there is nothing to remember without a gate",
        )
    }

    @Test
    fun stampRemembersWhetherTheGateWasReadOffThePosition() {
        val read = AutoGateStamp(icao = "KIAH", gate = "C24", fromAircraftPosition = true)
        assertEquals("KIAH:C24:P", read.encoded)
        assertEquals(read, AutoGateStamp.decode("KIAH:C24:P"))
        assertEquals(
            "C24", AutoGateStamp.decode("KIAH:C24:P")?.gate,
            "the flag is not folded into the gate name",
        )
        assertEquals(
            false, AutoGateStamp.decode("KIAH:C24")?.fromAircraftPosition,
            "a marker written before the flag existed decodes as a chosen gate",
        )
    }

    @Test
    fun aChosenGateIsUpgradedByTheOneTheAircraftIsParkedOn() {
        val chosen = AutoGateStamp(icao = "KIAH", gate = "C24").encoded
        val parkedAssignment = GateAssigner.Assignment(
            gate = "F3", osmID = "node/F3", coordinate = GeoCoordinate(ref),
            matchedOperator = false, matchedAircraftType = false, fromAircraftPosition = true,
            tiedCandidates = 1, totalCandidates = 12, reason = "aircraft is parked on it",
        )

        assertTrue(
            GateAssigner.couldUpgrade(current = "C24", stamp = chosen, icao = "KIAH"),
            "a gate the app chose for this field is worth a second look",
        )
        assertTrue(
            GateAssigner.mayUpgrade(
                current = "C24", stamp = chosen, icao = "KIAH", assignment = parkedAssignment,
            ),
        )

        // Not a pilot's gate, not a re-roll, and not once the gate already came from the position.
        assertFalse(
            GateAssigner.couldUpgrade(current = "E7", stamp = chosen, icao = "KIAH"),
            "a gate the pilot typed over ours is theirs",
        )
        assertFalse(
            GateAssigner.couldUpgrade(current = "C24", stamp = chosen, icao = "KMSP"),
            "a different airport is a fresh assignment, not an upgrade",
        )
        val alreadyRead =
            AutoGateStamp(icao = "KIAH", gate = "C24", fromAircraftPosition = true).encoded
        assertFalse(
            GateAssigner.couldUpgrade(current = "C24", stamp = alreadyRead, icao = "KIAH"),
            "a gate already read off the position is the truth — never re-picked",
        )

        val chosenAssignment = parkedAssignment.copy(fromAircraftPosition = false)
        assertFalse(
            GateAssigner.mayUpgrade(
                current = "C24", stamp = chosen, icao = "KIAH", assignment = chosenAssignment,
            ),
            "a second *chosen* gate would just re-roll the dice on the pilot",
        )
    }

    @Test
    fun aBlankFieldMayBeAssignedAndATypedGateMayNot() {
        assertTrue(GateAssigner.mayAssign(current = "", stamp = "", icao = "KIAH"))
        assertTrue(
            GateAssigner.mayAssign(current = "   ", stamp = "", icao = "KIAH"),
            "whitespace is a blank field",
        )
        assertFalse(
            GateAssigner.mayAssign(current = "C24", stamp = "", icao = "KIAH"),
            "a gate the pilot typed is never overwritten",
        )
        assertFalse(
            GateAssigner.mayAssign(current = "E7", stamp = "KIAH:C24", icao = "KIAH"),
            "a gate the pilot typed over an automatic one is theirs now",
        )
    }

    @Test
    fun theAppReplacesItsOwnGateOnlyWhenTheAirportChanges() {
        assertFalse(
            GateAssigner.mayAssign(current = "C24", stamp = "KIAH:C24", icao = "KIAH"),
            "already assigned for this field — it must not re-roll every tick",
        )
        assertTrue(
            GateAssigner.mayAssign(current = "C24", stamp = "KIAH:C24", icao = "KMSP"),
            "the last flight's automatic gate is stale at a new airport",
        )
        assertTrue(
            GateAssigner.mayAssign(current = "c24", stamp = "KIAH:C24", icao = "KMSP"),
            "the match ignores case",
        )
    }

    @Test
    fun appAssignedRecognisesOnlyItsOwnValue() {
        assertTrue(GateAssigner.isAppAssigned(current = "C24", stamp = "KIAH:C24"))
        assertFalse(GateAssigner.isAppAssigned(current = "E7", stamp = "KIAH:C24"))
        assertFalse(GateAssigner.isAppAssigned(current = "C24", stamp = ""))
        assertFalse(GateAssigner.isAppAssigned(current = "", stamp = "KIAH:C24"))
    }
}
