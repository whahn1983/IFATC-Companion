package com.h3consultingpartners.ifatccompanion.core.enroute

import com.h3consultingpartners.ifatccompanion.core.atc.ATCContext
import com.h3consultingpartners.ifatccompanion.core.atc.PilotResponseEngine
import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.phraseology.CallsignDigitStyle
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyEngine
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyMode
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Enroute Center sectors: the bundled boundary dataset, the geometry it is queried
 * with, the hysteresis that decides when a crossing is real, the phraseology that
 * names the controller, and the licence the data ships under.
 *
 * Ported from `IFATCCompanionTests/CenterSectorTests.swift`.
 */
class CenterSectorTest {

    // MARK: - Helpers

    /**
     * A rectangular sector, for tests that care about crossing behaviour rather than
     * real geography.
     */
    private fun box(
        id: String,
        radio: String,
        latitudes: ClosedFloatingPointRange<Double>,
        longitudes: ClosedFloatingPointRange<Double>,
        frequency: Double? = null,
    ): CenterSector = CenterSector(
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
                ),
            ),
        ),
    )

    /** West sector (lon −100…−90) and east sector (lon −90…−80), sharing the −90 edge. */
    private fun twoSectorDatabase(): CenterSectorDatabase = CenterSectorDatabase(
        listOf(
            box(id = "WEST", radio = "West Center", latitudes = 30.0..40.0, longitudes = -100.0..-90.0),
            box(id = "EAST", radio = "East Center", latitudes = 30.0..40.0, longitudes = -90.0..-80.0),
        ),
    )

    private fun coordinate(lat: Double, lon: Double) = Coordinate(latitude = lat, longitude = lon)

    /**
     * The dataset as the app loads it. The packaged resource is on the unit-test
     * classpath, so the file the app ships is the file under test.
     */
    private fun bundledDatabase(): CenterSectorDatabase {
        val database = CenterSectorDatabase()
        assertTrue(database.loadNow(), "CenterSectors.json failed to load: ${database.state}")
        return database
    }

    // MARK: - Bundled dataset

    @Test
    fun testBundledDatasetLoadsWithGlobalCoverage() {
        val database = bundledDatabase()
        assertTrue(database.isReady)
        assertTrue(
            database.count > 300,
            "the dataset should cover every FIR/ARTCC worldwide, not just the US",
        )
        assertEquals("CC BY-SA 4.0", database.provenance?.license)
        assertEquals(database.count, database.provenance?.sectorCount)
    }

    @Test
    fun testUnitedStatesFieldsResolveToTheirARTCC() {
        val database = bundledDatabase()
        val expected = listOf(
            "Houston Center" to coordinate(29.98, -95.34), // KIAH
            "Fort Worth Center" to coordinate(32.90, -97.04), // KDFW
            "Memphis Center" to coordinate(35.04, -89.98), // KMEM
            "Atlanta Center" to coordinate(33.64, -84.43), // KATL
            "Los Angeles Center" to coordinate(33.94, -118.41), // KLAX
            "New York Center" to coordinate(40.64, -73.78), // KJFK
        )
        for ((radioName, position) in expected) {
            assertEquals(radioName, database.sector(at = position)?.radioName)
        }
    }

    /**
     * The rest of the world is covered too, and ICAO area control centres are called
     * "Control", not "Center".
     */
    @Test
    fun testInternationalSectorsUseTheirOwnRadioNames() {
        val database = bundledDatabase()
        assertEquals("London Control", database.sector(at = coordinate(51.47, -0.45))?.radioName)
        assertEquals("Toronto Center", database.sector(at = coordinate(43.68, -79.63))?.radioName)
        assertEquals("Shanwick Oceanic", database.sector(at = coordinate(52.0, -30.0))?.radioName)
        assertNotNull(database.sector(at = coordinate(-33.95, 151.18))) // YSSY
        assertNotNull(database.sector(at = coordinate(35.55, 139.78))) // RJTT
    }

    /**
     * Flying Houston to Chicago crosses Fort Worth's and Memphis's airspace on the way,
     * in that order — the sequence of hand-offs the enroute leg should produce.
     */
    @Test
    fun testRouteCrossesSectorsInOrder() {
        val database = bundledDatabase()
        val start = coordinate(29.98, -95.34) // KIAH
        val end = coordinate(41.97, -87.91) // KORD
        val sequence = mutableListOf<String>()
        for (step in 0..120) {
            val fraction = step.toDouble() / 120
            val position = coordinate(
                start.latitude + (end.latitude - start.latitude) * fraction,
                start.longitude + (end.longitude - start.longitude) * fraction,
            )
            val name = database.sector(at = position)?.radioName ?: continue
            if (sequence.lastOrNull() != name) sequence.add(name)
        }
        assertEquals(
            listOf("Houston Center", "Fort Worth Center", "Memphis Center"),
            sequence.take(3),
        )
        assertTrue(sequence.contains("Chicago Center"), "$sequence")
    }

    @Test
    fun testNeighbouringSectorsNeverShareAFrequency() {
        val database = bundledDatabase()
        val houston = assertNotNull(database.sector(id = "KZHU"))
        val fortWorth = assertNotNull(database.sector(id = "KZFW"))
        val memphis = assertNotNull(database.sector(id = "KZME"))
        assertNotEquals(houston.frequency, fortWorth.frequency)
        assertNotEquals(fortWorth.frequency, memphis.frequency)
        assertNotEquals(houston.frequency, memphis.frequency)
    }

    // MARK: - Geometry

    @Test
    fun testContainmentAndDistanceToBoundary() {
        val west = box(id = "WEST", radio = "West Center", latitudes = 30.0..40.0, longitudes = -100.0..-90.0)
        assertTrue(west.contains(coordinate(35.0, -95.0)))
        assertFalse(west.contains(coordinate(35.0, -89.0)))
        assertFalse(west.contains(coordinate(45.0, -95.0)), "outside the bounding box entirely")
        // One degree of longitude at 35° N is ~49 NM; the nearest edge is the −90 one.
        assertEquals(49.0, west.distanceToBoundaryNM(from = coordinate(35.0, -91.0)), 2.0)
    }

    // MARK: - Frequencies

    @Test
    fun testSimulatedFrequenciesAreStableAndInTheEnrouteBand() {
        for (id in listOf("KZHU", "KZFW", "KZME", "EGTT", "CZQX")) {
            val frequency = CenterSector.simulatedFrequency(id)
            assertEquals(
                CenterSector.simulatedFrequency(id),
                frequency,
                "the same sector must always get the same frequency",
            )
            assertTrue(frequency >= CenterSector.LOWEST_FREQUENCY)
            assertTrue(frequency <= CenterSector.HIGHEST_FREQUENCY)
            assertEquals(
                0.0,
                round(frequency * 1000) % 25,
                0.001,
                "frequencies sit on the 25 kHz grid",
            )
        }
        // …and they are the *same* channels iOS synthesizes. The FNV-1a hash is what
        // makes that true across devices and launches, so the worked example in
        // docs/CenterSectors.md ("contact Houston Center on 133.775") is pinned here:
        // a sign-extended byte or a differently seeded hash would still look stable
        // and in-band while quietly disagreeing with the iOS build.
        assertEquals(133.775, CenterSector.simulatedFrequency("KZHU"), 0.0005)
        assertEquals(133.975, CenterSector.simulatedFrequency("KZFW"), 0.0005)
        assertEquals(133.750, CenterSector.simulatedFrequency("KZME"), 0.0005)
    }

    @Test
    fun testPublishedFrequencyWinsOverTheSimulatedOne() {
        val sector = box(
            id = "YBIK", radio = "Melbourne Center",
            latitudes = -40.0..-30.0, longitudes = 140.0..150.0, frequency = 129.8,
        )
        assertEquals(129.8, sector.frequency)
        val database = CenterSectorDatabase(listOf(sector))
        assertEquals(
            129.8,
            database.sector(id = "YBIK")?.frequency,
            "a real published frequency is never moved by de-confliction",
        )
    }

    // MARK: - Crossing hysteresis

    @Test
    fun testFirstFixAdoptsTheSectorWithoutAHandoff() {
        val database = twoSectorDatabase()
        val tracker = CenterSectorTracker()
        assertNull(tracker.update(coordinate(35.0, -95.0), atMillis = START, database = database))
        assertEquals("WEST", tracker.current?.id)
    }

    @Test
    fun testCrossingIsAnnouncedOnlyOnceWellInsideTheNextSector() {
        val database = twoSectorDatabase()
        val tracker = CenterSectorTracker()
        tracker.update(coordinate(35.0, -90.5), atMillis = START, database = database)

        // A mile inside the next sector is not a crossing — a track that skims the
        // boundary must not bounce the radio between two controllers.
        assertNull(
            tracker.update(coordinate(35.0, -89.98), atMillis = START + seconds(10), database = database),
        )
        assertEquals("WEST", tracker.current?.id)

        // Five miles inside it is.
        val crossing =
            tracker.update(coordinate(35.0, -89.9), atMillis = START + seconds(20), database = database)
        assertEquals("WEST", crossing?.from?.id)
        assertEquals("EAST", crossing?.to?.id)
        assertEquals("EAST", tracker.current?.id)
    }

    @Test
    fun testASecondCrossingWaitsOutTheMinimumSpacing() {
        val database = twoSectorDatabase()
        val tracker = CenterSectorTracker()
        tracker.update(coordinate(35.0, -90.5), atMillis = START, database = database)
        assertNotNull(
            tracker.update(coordinate(35.0, -89.9), atMillis = START + seconds(20), database = database),
        )

        // Straight back across a moment later: held, so two calls can't stack.
        assertNull(
            tracker.update(coordinate(35.0, -90.5), atMillis = START + seconds(30), database = database),
        )
        assertEquals("EAST", tracker.current?.id)

        // Once the spacing has elapsed the hand-off back is issued.
        val back =
            tracker.update(coordinate(35.0, -90.5), atMillis = START + seconds(200), database = database)
        assertEquals("WEST", back?.to?.id)
    }

    @Test
    fun testAPositionJumpAdoptsTheNewSectorSilently() {
        val database = twoSectorDatabase()
        val tracker = CenterSectorTracker()
        tracker.update(coordinate(35.0, -95.0), atMillis = START, database = database)
        // Hundreds of miles between two fixes: the app was backgrounded or the link
        // resynced. The pilot is already deep inside the new sector, so nothing is said.
        assertNull(
            tracker.update(coordinate(35.0, -85.0), atMillis = START + seconds(30), database = database),
        )
        assertEquals("EAST", tracker.current?.id)
    }

    @Test
    fun testAGapInTheDataKeepsTheWorkingSector() {
        val database = twoSectorDatabase()
        val tracker = CenterSectorTracker()
        tracker.update(coordinate(35.0, -95.0), atMillis = START, database = database)
        assertNull(
            tracker.update(coordinate(35.0, -70.0), atMillis = START + seconds(10), database = database),
            "a position no sector covers must not produce a hand-off",
        )
        assertEquals("WEST", tracker.current?.id)
    }

    @Test
    fun testNoSectorDataMeansNoCrossings() {
        val database = CenterSectorDatabase(emptyList())
        val tracker = CenterSectorTracker()
        assertNull(tracker.update(coordinate(35.0, -95.0), atMillis = START, database = database))
        assertNull(tracker.current)
    }

    // MARK: - Phraseology

    @Test
    fun testCenterCallsNameTheWorkingSector() {
        var engine = PhraseologyEngine(
            digitStyle = CallsignDigitStyle.INDIVIDUAL,
            mode = PhraseologyMode.FAA,
        )
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        assertEquals(
            "Center",
            engine.spokenName(ATCFacility.CENTER),
            "with no sector known the generic name is used",
        )

        engine = engine.copy(centerSectorName = "Memphis Center")
        assertEquals("Memphis Center", engine.spokenName(ATCFacility.CENTER))
        assertEquals("Tower", engine.spokenName(ATCFacility.TOWER), "only Center is sector-named")

        val handoff = engine.handoff(
            cs = cs, from = ATCFacility.CENTER, to = ATCFacility.CENTER, frequency = 133.975,
        )
        assertTrue(
            handoff.displayText.contains("contact Memphis Center on 133.975"),
            handoff.displayText,
        )
        assertTrue(
            handoff.readback?.displayText?.contains("Contacting Memphis Center on 133.975") ?: false,
            handoff.readback?.displayText ?: "",
        )
        assertEquals(ATCFacility.CENTER, handoff.readback?.tuneTo)

        val checkIn = engine.radarContact(cs = cs, facility = ATCFacility.CENTER)
        assertTrue(checkIn.displayText.contains("Memphis Center, radar contact"), checkIn.displayText)
    }

    @Test
    fun testPilotChecksInWithTheSectorByName() {
        val engine = PhraseologyEngine(
            digitStyle = CallsignDigitStyle.INDIVIDUAL,
            mode = PhraseologyMode.FAA,
        ).copy(centerSectorName = "Fort Worth Center")
        val pilot = PilotResponseEngine(engine = engine)
        val plan = FlightPlan(airline = "United", flightNumber = "598")
        val context = ATCContext(
            callsign = engine.callsign(airline = "United", flightNumber = "598", fallback = ""),
            plan = plan, assignedAltitude = 37000, cruiseAltitude = 37000,
            initialClimbAltitude = 5000, windDirection = 180, windSpeed = 10,
            squawk = "4271", runway = "27", taxiway = "A", crossingRunway = null,
            parkingTaxiway = "B", approachName = "ILS", departureFrequency = 124.3,
            centerFrequency = 133.975, approachFrequency = 119.7,
            towerFrequency = 118.3, groundFrequency = 121.8,
        )
        val call = pilot.requestHandoff(
            c = context, facility = ATCFacility.CENTER,
            currentAltitude = 37000, targetAltitude = 37000,
            onGround = false,
        )
        assertTrue(call.displayText.startsWith("Fort Worth Center,"), call.displayText)
    }

    // MARK: - Licensing

    @Test
    fun testSectorDataIsAttributedUnderShareAlike() {
        assertEquals("VATSIM VATSpy Data Project", CenterSectorData.PROVIDER_NAME)
        assertEquals("CC BY-SA 4.0", CenterSectorData.LICENSE_SHORT_NAME)
        assertTrue(CenterSectorData.LICENSE_NAME.contains("ShareAlike"))
        assertEquals(
            "Sector boundaries © VATSIM VATSpy Data Project",
            CenterSectorData.ATTRIBUTION_TEXT,
        )
        assertTrue(CenterSectorData.LICENSE_URL.startsWith("https://creativecommons.org/"))
        for (url in listOf(
            CenterSectorData.SOURCE_URL,
            CenterSectorData.LICENSE_URL,
            CenterSectorData.PUBLIC_DOCUMENTATION_URL,
        )) {
            assertTrue(url.startsWith("https://"), url)
        }
        assertFalse(
            CenterSectorData.ATTRIBUTION_TEXT.contains("OpenStreetMap"),
            "sector geometry does not come from OSM — OSM does not map airspace",
        )
    }

    private companion object {
        /** An arbitrary but fixed epoch, standing in for the Swift's `Date()`. */
        const val START = 1_700_000_000_000L

        fun seconds(value: Long): Long = value * 1000
    }
}
