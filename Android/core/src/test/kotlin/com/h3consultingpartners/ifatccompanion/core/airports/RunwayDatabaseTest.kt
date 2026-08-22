package com.h3consultingpartners.ifatccompanion.core.airports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ported from `IFATCCompanionTests/RunwayDatabaseTests.swift`. The curated table exists
 * so the active runway is always a runway the field actually has — the alternative is a
 * number invented from the wind, which the takeoff clearance then reads back as a heading.
 */
class RunwayDatabaseTest {

    private val db = RunwayDatabase

    @Test
    fun newarkHasRealRunwaysOnly() {
        val rwys = db.runways("KEWR").toSet()
        // Newark's real runways: 4L/22R, 4R/22L, 11/29. No "14".
        assertTrue(rwys.contains("22R"))
        assertTrue(rwys.contains("4L"))
        assertFalse(rwys.contains("14"), "Newark has no runway 14")
    }

    @Test
    fun newarkPicksRunway22ForSoutherlyWind() {
        // Wind from ~220° favours the 22s (the field's typical active config).
        assertEquals("22R", db.activeRunway("KEWR", windDirection = 220, windSpeed = 12))
        assertEquals("22R", db.activeRunway("EWR", windDirection = 200, windSpeed = 10))
    }

    @Test
    fun newarkPicksRunway4ForNortheastWind() {
        assertEquals("4L", db.activeRunway("KEWR", windDirection = 40, windSpeed = 12))
    }

    @Test
    fun activeRunwayIsAlwaysAValidRunway() {
        for (icao in listOf("KEWR", "KJFK", "KLAX", "KORD", "KATL", "KDEN", "KSFO")) {
            for (wind in 0..350 step 10) {
                val active = db.activeRunway(icao, windDirection = wind, windSpeed = 10)
                assertNotNull(active)
                assertTrue(
                    db.runways(icao).contains(active),
                    "$icao returned $active which is not a real runway",
                )
            }
        }
    }

    @Test
    fun calmWindKeepsPrimaryRunway() {
        val primary = db.runways("KEWR").firstOrNull()
        assertEquals(primary, db.activeRunway("KEWR", windDirection = 220, windSpeed = 2))
        assertEquals(primary, db.activeRunway("KEWR", windDirection = 0, windSpeed = 0))
    }

    @Test
    fun unknownAirportReturnsNil() {
        assertNull(db.activeRunway("ZZZZ", windDirection = 180, windSpeed = 10))
        assertTrue(db.runways("ZZZZ").isEmpty())
    }

    /**
     * A field the curated table doesn't cover can still be picked from its own runways —
     * the ones parsed off its airport surface. KMCO's four parallels are the case that
     * exposed this: absent from the table, so the active runway used to be a number derived
     * from the wind, which the takeoff clearance then read back as a heading.
     */
    @Test
    fun picksAmongASuppliedRunwayList() {
        val kmco = listOf("17L", "35R", "17R", "35L", "18L", "36R", "18R", "36L")
        assertEquals("17L", db.activeRunwayAmong(kmco, windDirection = 170, windSpeed = 12))
        assertEquals("35R", db.activeRunwayAmong(kmco, windDirection = 350, windSpeed = 12))
        // Calm wind keeps list order rather than chasing noise.
        assertEquals("17L", db.activeRunwayAmong(kmco, windDirection = 350, windSpeed = 2))
        // Nothing to pick from is still null, so the caller keeps its own last resort.
        assertNull(db.activeRunwayAmong(emptyList(), windDirection = 180, windSpeed = 10))
    }

    @Test
    fun threeLetterCodeResolvesToUSAirport() {
        assertFalse(db.runways("LAX").isEmpty())
        assertEquals(
            db.activeRunway("KLAX", windDirection = 250, windSpeed = 12),
            db.activeRunway("LAX", windDirection = 250, windSpeed = 12),
        )
    }
}
