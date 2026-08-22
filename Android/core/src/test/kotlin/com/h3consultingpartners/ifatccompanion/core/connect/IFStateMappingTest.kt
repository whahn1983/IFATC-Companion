package com.h3consultingpartners.ifatccompanion.core.connect

import com.h3consultingpartners.ifatccompanion.core.geo.HeadingSolver
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards how logical aircraft-state keys resolve against the live Connect manifest.
 * In particular the magnetic and true heading states must resolve to *distinct*
 * entries — the map orients the aircraft symbol by the true heading, while ATC
 * phraseology uses the magnetic heading, so a collision would silently reintroduce
 * the declination-sized rotation error the true-heading path fixes.
 *
 * Ported from `IFATCCompanionTests/IFStateMappingTests.swift`. The three
 * `WeatherProviderDiagnostics` tests that also live in that file
 * (`testReportedWindDeltaNamesTheConvention`,
 * `testWindRowsCarryBothFramesSoTheyCanBeReadAgainstTheSimsPanel`,
 * `testATurnSmearsTheWindTriangleIntoAWindThatIsNotThere`) belong to the weather area
 * and are ported alongside it, not here.
 */
class IFStateMappingTest {

    /**
     * A trimmed manifest carrying both heading states exactly as Infinite Flight
     * names them (`id,type,name` per line; type 2 = float, 3 = double).
     */
    private val manifest = """
        746,3,aircraft/0/latitude
        747,3,aircraft/0/longitude
        731,2,aircraft/0/heading_magnetic
        732,2,aircraft/0/heading_true
        744,2,aircraft/0/course
        716,2,aircraft/0/magnetic_variation
    """.trimIndent()

    @Test
    fun magneticAndTrueHeadingResolveToDistinctStates() {
        val entries = IFManifestParser.parse(manifest)
        val store = IFStateMappingStore()
        store.resolve(entries)

        assertEquals(
            "aircraft/0/heading_magnetic",
            store.entry(IFStateMappingStore.Logical.HEADING)?.name,
        )
        assertEquals(
            "aircraft/0/heading_true",
            store.entry(IFStateMappingStore.Logical.TRUE_HEADING)?.name,
        )
        // They must not collapse onto the same manifest entry.
        assertNotEquals(
            store.entry(IFStateMappingStore.Logical.HEADING)?.id,
            store.entry(IFStateMappingStore.Logical.TRUE_HEADING)?.id,
        )
    }

    /**
     * When the sim only exposes a single magnetic heading, the true-heading key is
     * left unresolved (the map then falls back to the magnetic heading rather than
     * mis-binding to it).
     */
    @Test
    fun trueHeadingUnresolvedWhenAbsent() {
        val entries = IFManifestParser.parse(
            """
            746,3,aircraft/0/latitude
            731,2,aircraft/0/heading_magnetic
            """.trimIndent(),
        )
        val store = IFStateMappingStore()
        store.resolve(entries)

        assertEquals(
            "aircraft/0/heading_magnetic",
            store.entry(IFStateMappingStore.Logical.HEADING)?.name,
        )
        assertNull(store.entry(IFStateMappingStore.Logical.TRUE_HEADING))
    }

    // region What a name is allowed to match

    /**
     * A signature matched across ~1700 states plus every command lands on whatever shares a
     * word with it. The ground track resolved onto `is_on_flight_plan_track` — a *bool* — so
     * the track read as 0° or 57° and went into the wind triangle as if it were a bearing.
     * A measurement only ever resolves onto a measurement.
     */
    @Test
    fun theGroundTrackNeverResolvesOntoAFlightPlanBool() {
        val entries = IFManifestParser.parse(
            """
            731,2,aircraft/0/heading_magnetic
            744,2,aircraft/0/course
            945,0,aircraft/0/is_on_flight_plan_track
            """.trimIndent(),
        )
        val store = IFStateMappingStore()
        store.resolve(entries)

        assertEquals("aircraft/0/course", store.entry(IFStateMappingStore.Logical.TRACK)?.name)
        assertNotEquals(
            "aircraft/0/is_on_flight_plan_track",
            store.entry(IFStateMappingStore.Logical.TRACK)?.name,
        )
    }

    /**
     * With no track-like measurement exposed the key stays unresolved, so the wind triangle
     * simply doesn't solve — rather than solving off a bool.
     */
    @Test
    fun theGroundTrackIsUnresolvedRatherThanWrongWhenAbsent() {
        val store = IFStateMappingStore()
        store.resolve(IFManifestParser.parse("945,0,aircraft/0/is_on_flight_plan_track"))
        assertNull(store.entry(IFStateMappingStore.Logical.TRACK))
    }

    /** Commands share their words with the states they act on, and are not readable values. */
    @Test
    fun aStateNeverResolvesOntoACommand() {
        val entries = IFManifestParser.parse(
            """
            1100,-1,commands/ParkingBrakes
            845,0,aircraft/0/systems/parking_brakes/state
            1101,-1,commands/Autopilot.SetApproachModeState
            """.trimIndent(),
        )
        val store = IFStateMappingStore()
        store.resolve(entries)

        assertEquals(
            "aircraft/0/systems/parking_brakes/state",
            store.entry(IFStateMappingStore.Logical.PARKING_BRAKE)?.name,
        )
        assertNull(
            store.entry(IFStateMappingStore.Logical.APPROACH_MODE),
            "a command is not an approach-mode reading",
        )
    }

    // endregion

    // region Environment wind states

    /**
     * The sim's own wind states resolve — and the steady wind is never read off the **gust**
     * state sitting next to it in the same group.
     */
    @Test
    fun environmentWindStatesResolveAndNeverMatchTheGust() {
        val entries = IFManifestParser.parse(
            """
            901,2,environment/turbulence_factor
            902,2,environment/temperature
            903,2,environment/wind_gust_velocity
            904,2,environment/wind_velocity
            905,2,environment/wind_direction_true
            906,2,environment/surface_temperature
            """.trimIndent(),
        )
        val store = IFStateMappingStore()
        store.resolve(entries)

        assertEquals(
            "environment/wind_velocity",
            store.entry(IFStateMappingStore.Logical.WIND_VELOCITY)?.name,
        )
        assertEquals(
            "environment/wind_direction_true",
            store.entry(IFStateMappingStore.Logical.WIND_DIRECTION_TRUE)?.name,
        )
        assertNotEquals(
            "environment/wind_gust_velocity",
            store.entry(IFStateMappingStore.Logical.WIND_VELOCITY)?.name,
            "the steady wind must not resolve onto the gust state",
        )
    }

    /**
     * A version that exposes neither leaves both unresolved, so the reader simply reports no
     * sim wind and the solved wind triangle carries on as before.
     */
    @Test
    fun windStatesUnresolvedWhenAbsent() {
        val entries = IFManifestParser.parse("746,3,aircraft/0/latitude")
        val store = IFStateMappingStore()
        store.resolve(entries)

        assertNull(store.entry(IFStateMappingStore.Logical.WIND_VELOCITY))
        assertNull(store.entry(IFStateMappingStore.Logical.WIND_DIRECTION_TRUE))
    }

    // endregion

    // region Heading units (radians vs degrees)

    /**
     * Radians are converted; degrees are taken at face value. The units are settled once
     * per state snapshot, because a single reading can't tell them apart.
     */
    @Test
    fun headingAnglesAreNormalizedByTheSnapshotsUnits() {
        // Radians in: π/2 is 090°, and 4 rad is 229°.
        assertEquals(90.0, IFConnectStateReader.normalizeAngle(PI / 2, false), 0.001)
        assertEquals(229.183, IFConnectStateReader.normalizeAngle(4.0, false), 0.01)
        // Degrees in: unchanged, and wrapped to 0–360.
        assertEquals(4.0, IFConnectStateReader.normalizeAngle(4.0, true), 0.001)
        assertEquals(350.0, IFConnectStateReader.normalizeAngle(-10.0, true), 0.001)
    }

    /**
     * Regression: judging each value on its own mangles a heading near north on a build that
     * reports degrees — 004° and 4 rad are the same number on the wire, and the old per-value
     * guess read every heading below ~6° as radians, turning 004° into 229°. That fed the
     * wind triangle (`HeadingSolver.wind`) and every weather vector solved from it. The
     * snapshot decides: any one angle too large to be radians makes them all degrees.
     */
    @Test
    fun nearNorthHeadingIsNotMistakenForRadiansWhenTheSnapshotIsInDegrees() {
        // A snapshot in degrees: nose on 004°, tracking 007°, magnetic 003°.
        val snapshot = listOf(4.0, 7.0, 3.0)
        val inDegrees = snapshot.any { IFConnectStateReader.exceedsFullCircleInRadians(it) }
        assertTrue(inDegrees, "a reading of 7 cannot be radians, so the snapshot is in degrees")
        assertEquals(4.0, IFConnectStateReader.normalizeAngle(4.0, inDegrees), 0.001)

        // The same snapshot in radians stays radians and converts.
        val radians = listOf(0.07, 0.12, 0.05)
        val radiansInDegrees = radians.any { IFConnectStateReader.exceedsFullCircleInRadians(it) }
        assertFalse(radiansInDegrees)
        assertEquals(4.011, IFConnectStateReader.normalizeAngle(0.07, radiansInDegrees), 0.01)
    }

    /**
     * Feed one telemetry snapshot's raw angles to a family's units decision, exactly as
     * [IFConnectStateReader.readState] does.
     */
    private fun note(
        angles: List<Double>,
        store: IFStateMappingStore,
        family: IFStateMappingStore.AngleFamily = IFStateMappingStore.AngleFamily.AIRCRAFT,
        heading: Double? = null,
    ) {
        store.noteAngleSnapshot(
            family = family,
            provesDegrees = angles.any { IFConnectStateReader.provesDegrees(it) },
            anyAboveRadianCircle = angles.any {
                IFConnectStateReader.exceedsFullCircleInRadians(it)
            },
            rawHeading = if (family == IFStateMappingStore.AngleFamily.AIRCRAFT) {
                heading ?: angles.firstOrNull()
            } else {
                null
            },
        )
    }

    /**
     * Regression (field report, with the sim's own PFD beside the app: nose on 084° magnetic,
     * Flight tab reading 001°). The aircraft's angles were decided together with the *wind's*,
     * on the reasoning that every angle comes out of one API in one convention — and
     * `environment/wind_direction_true` reports the weather in degrees on builds whose aircraft
     * states are radians. A wind from 331 then witnessed "degrees" on every single snapshot, so
     * 084° magnetic — 1.466 rad on the wire — was shown as 001°, and every heading in the app
     * landed within 6° of north. It re-witnessed continuously, so the radians contradiction
     * never got a run to accumulate either. The weather gets no vote on the nose.
     */
    @Test
    fun aWindReportedInDegreesCannotDecideTheAircraftsUnits() {
        val store = IFStateMappingStore()
        // Taxiing at KMCO: nose 084° (1.466 rad), true 081° (1.421 rad), wind from 331°.
        repeat(8) {
            note(listOf(1.466, 1.421), store)
            note(listOf(331.0), store, IFStateMappingStore.AngleFamily.ENVIRONMENT)
        }

        assertFalse(store.anglesProvedDegrees, "a wind in degrees says nothing about the nose")
        assertEquals(
            84.0,
            IFConnectStateReader.normalizeAngle(1.466, store.anglesProvedDegrees),
            0.5,
            "084° magnetic must read 084°, not 001°",
        )

        // …and the wind still reads correctly in its own convention, rather than being
        // multiplied by 57.3 to satisfy the aircraft's.
        assertTrue(store.windAnglesProvedDegrees)
        assertEquals(
            331.0,
            IFConnectStateReader.normalizeAngle(331.0, store.windAnglesProvedDegrees),
            0.001,
        )
    }

    /**
     * Only the two headings decide the heading's units. They are the states the decision is
     * *for*, and the only angles matched by an exact name; the ground track is matched by a
     * looser signature and has already been seen to land on something that isn't a bearing at
     * all. It follows the decision rather than making it.
     */
    @Test
    fun onlyTheHeadingsDecideTheHeadingsUnits() {
        val store = IFStateMappingStore()
        // A track state reading in degrees — or simply reading something that isn't a bearing —
        // alongside headings that are plainly radians.
        repeat(8) { note(listOf(1.466, 1.421), store) }

        assertFalse(store.anglesProvedDegrees)
        assertEquals(
            84.0,
            IFConnectStateReader.normalizeAngle(1.466, store.anglesProvedDegrees),
            0.5,
        )

        // The headings themselves still settle it the moment they read in degrees.
        repeat(IFStateMappingStore.DEGREE_WITNESSES_TO_PROVE) { note(listOf(84.0, 82.0), store) }
        assertTrue(store.anglesProvedDegrees)
    }

    /**
     * The two decisions are genuinely independent: a build reporting the aircraft in degrees
     * and the wind in radians is read correctly too.
     */
    @Test
    fun theAircraftAndTheWindSettleTheirUnitsSeparately() {
        val store = IFStateMappingStore()
        repeat(8) {
            note(listOf(84.0, 82.0), store) // headings in degrees
            note(listOf(5.777), store, IFStateMappingStore.AngleFamily.ENVIRONMENT) // 331°, radians
        }
        assertTrue(store.anglesProvedDegrees)
        assertFalse(store.windAnglesProvedDegrees)
        assertEquals(
            84.0,
            IFConnectStateReader.normalizeAngle(84.0, store.anglesProvedDegrees),
            0.001,
        )
        assertEquals(
            331.0,
            IFConnectStateReader.normalizeAngle(5.777, store.windAnglesProvedDegrees),
            0.5,
        )
    }

    /**
     * Regression: a snapshot whose angles are *all* within ~6° of north witnesses nothing —
     * nose 004°, track 004°, a northerly wind are each a valid radian reading — so a build
     * reporting degrees was read as radians for as long as it stayed pointed north, which is
     * exactly what a north-facing runway makes an aircraft do. The proof therefore carries
     * across snapshots rather than being retaken on each one.
     */
    @Test
    fun theDegreesProofCarriesAcrossSnapshots() {
        val store = IFStateMappingStore()
        assertFalse(store.anglesProvedDegrees, "nothing witnessed yet — assume radians")

        // A snapshot with no witness changes nothing.
        note(listOf(4.0, 4.0, 3.5), store)
        assertFalse(store.anglesProvedDegrees)

        // Headings off north prove it — 47 cannot be radians — once corroborated.
        repeat(IFStateMappingStore.DEGREE_WITNESSES_TO_PROVE) {
            note(listOf(47.0, 44.0, 350.0), store)
        }
        assertTrue(store.anglesProvedDegrees)

        // Back to a north-facing runway: the proof holds, so 004° stays 004°.
        note(listOf(4.0, 4.0, 3.5), store)
        assertTrue(store.anglesProvedDegrees, "units don't change mid-connection")
        assertEquals(4.0, IFConnectStateReader.normalizeAngle(4.0, store.anglesProvedDegrees), 0.001)

        // A fresh manifest is a fresh connection, and possibly a different IF build.
        store.resolve(IFManifestParser.parse(manifest))
        assertFalse(store.anglesProvedDegrees)
    }

    /**
     * Regression (field report: the aircraft symbol pointed north on the taxi and weather
     * maps whatever the nose was doing). On a build reporting radians every heading is 0…6.28,
     * so reading it as degrees pins the symbol within 6° of north — and the proof was taken on
     * a *single* reading, so one anomalous number settled the whole session. Two consecutive
     * witnesses are required, and a lone stray reading is forgotten by the next snapshot.
     */
    @Test
    fun oneStrayReadingCannotDecideTheUnits() {
        val store = IFStateMappingStore()
        val radians = listOf(2.967, 2.9, 5.5) // nose 170°, tracking 166°, wind from 315°

        note(radians, store)
        // One desynchronised read drops another state's number into a heading slot.
        note(listOf(28.43, 2.9, 5.5), store) // the aircraft's latitude, in a heading's place
        assertFalse(store.anglesProvedDegrees, "a single reading is not proof of the units")

        note(radians, store)
        assertFalse(store.anglesProvedDegrees, "and the stray reading is forgotten")
        assertEquals(
            170.0,
            IFConnectStateReader.normalizeAngle(2.967, store.anglesProvedDegrees),
            0.1,
            "the nose still reads 170°, not 003°",
        )
    }

    /**
     * A number past a full circle *in degrees* is not an angle in either convention — it is
     * the answer to some other state — so it witnesses nothing however often it repeats.
     */
    @Test
    fun anImplausibleReadingProvesNothingAboutTheUnits() {
        assertTrue(IFConnectStateReader.provesDegrees(170.0))
        assertTrue(IFConnectStateReader.provesDegrees(350.0))
        assertFalse(IFConnectStateReader.provesDegrees(3.0), "3 is a valid radian heading")
        assertFalse(IFConnectStateReader.provesDegrees(450.0), "no heading reads 450°")
        assertFalse(
            IFConnectStateReader.provesDegrees(29_260.0),
            "an altitude in a heading slot",
        )

        val store = IFStateMappingStore()
        repeat(6) { note(listOf(29_260.0, 2.9, 5.5), store) }
        assertFalse(store.anglesProvedDegrees)
    }

    /**
     * The proof can be contradicted, so a wrong one costs seconds rather than the session. No
     * single reading proves radians — every radian value is a valid degree value — but a
     * heading that visits three quadrants of the 0…2π circle without one reading ever passing
     * a full circle in radians is an aircraft turning through the compass, not one holding
     * inside a 6° arc of north.
     */
    @Test
    fun aRunOfRadianHeadingsContradictsAWrongDegreesProof() {
        val store = IFStateMappingStore()
        // Two plausible-but-wrong readings in a row take the proof.
        repeat(IFStateMappingStore.DEGREE_WITNESSES_TO_PROVE) { note(listOf(28.43, 2.9), store) }
        assertTrue(store.anglesProvedDegrees)

        // Then the aircraft taxis a circuit: every reading a radian heading, sweeping the rose.
        val sweep = listOf(0.4, 1.2, 2.0, 2.9, 3.6, 4.4, 5.2, 6.0, 0.6, 1.8, 3.1, 4.9)
        assertTrue(sweep.size >= IFStateMappingStore.RADIAN_SAMPLES_TO_DISPROVE)
        for (heading in sweep) {
            note(listOf(heading, 2.9), store, heading = heading)
        }

        assertFalse(store.anglesProvedDegrees, "a full sweep of the rose can only be radians")
        assertEquals(
            170.0,
            IFConnectStateReader.normalizeAngle(2.967, store.anglesProvedDegrees),
            0.1,
        )
    }

    /**
     * …and the contradiction never fires on a build that really does report degrees: holding
     * short on a north-facing runway keeps every angle inside the radian circle, but the nose
     * stays in one quadrant of it, and any reading off north resets the run outright.
     */
    @Test
    fun aGenuineDegreesProofSurvivesHoldingShortOnANorthFacingRunway() {
        val store = IFStateMappingStore()
        repeat(IFStateMappingStore.DEGREE_WITNESSES_TO_PROVE) { note(listOf(170.0, 166.0), store) }
        assertTrue(store.anglesProvedDegrees)

        // Lined up on 36: 004° magnetic, 003° true — no witness, for a long hold.
        repeat(IFStateMappingStore.RADIAN_SAMPLES_TO_DISPROVE * 3) { note(listOf(4.0, 3.0), store) }
        assertTrue(
            store.anglesProvedDegrees,
            "004° is one quadrant of the radian circle, not three",
        )
        assertEquals(4.0, IFConnectStateReader.normalizeAngle(4.0, store.anglesProvedDegrees), 0.001)
    }

    /**
     * Regression: bank was passed through raw, so on a build reporting radians a 25° bank
     * arrived as `0.44` and every degree-scaled test of it quietly passed — the wings-level
     * guard on the wind sample (`HeadingSolver.MAX_SAMPLE_BANK_DEGREES`, 5°) never tripped, and
     * the triangle was solved in the middle of a turn. Bank follows the snapshot's units like
     * every other angle, and stays signed about zero rather than wrapping onto a compass rose.
     */
    @Test
    fun bankFollowsTheSnapshotsUnitsAndStaysSigned() {
        // Radians in: a 25° right bank, and a 25° left bank that must not read as 335°.
        assertEquals(25.0, IFConnectStateReader.normalizeSignedAngle(0.4363, false), 0.01)
        assertEquals(-25.0, IFConnectStateReader.normalizeSignedAngle(-0.4363, false), 0.01)
        // Degrees in: taken at face value, sign intact.
        assertEquals(25.0, IFConnectStateReader.normalizeSignedAngle(25.0, true), 0.001)
        assertEquals(-4.0, IFConnectStateReader.normalizeSignedAngle(-4.0, true), 0.001)

        // The guard the conversion exists for: banked past the threshold either way.
        for (raw in listOf(0.4363, -0.4363)) {
            val bank = IFConnectStateReader.normalizeSignedAngle(raw, false)
            assertTrue(
                abs(bank) > HeadingSolver.MAX_SAMPLE_BANK_DEGREES,
                "a quarter-bank turn must stand the wind triangle down",
            )
        }
        // …and wings level still reads as level.
        assertTrue(
            abs(IFConnectStateReader.normalizeSignedAngle(-0.0349, false)) <=
                HeadingSolver.MAX_SAMPLE_BANK_DEGREES,
        )
    }

    // endregion
}
