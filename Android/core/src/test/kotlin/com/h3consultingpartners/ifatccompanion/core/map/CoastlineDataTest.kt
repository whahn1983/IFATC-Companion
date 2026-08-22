package com.h3consultingpartners.ifatccompanion.core.map

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The coastline asset is bundled, so these check the thing that actually breaks: the
 * resource being absent, renamed or truncated by a build change. A map that silently
 * loses its coastlines looks like a rendering bug and is really a packaging one.
 */
class CoastlineDataTest {

    @Test
    fun `the bundled dataset loads`() {
        val lines = CoastlineData.lines()
        assertTrue(lines.isNotEmpty(), "coastline resource missing or unparseable")
        // 1:110m Natural Earth is roughly 130 polylines; well under 50 would mean a
        // truncated or wrong file rather than a slightly different vintage.
        assertTrue(lines.size >= 50, "only ${lines.size} polylines — dataset looks truncated")
    }

    @Test
    fun `every polyline is drawable and inside the world`() {
        for (line in CoastlineData.lines()) {
            assertTrue(line.size >= 2, "a one-point polyline cannot be drawn")
            for (point in line) {
                assertTrue(
                    point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0,
                    "point outside the world: $point",
                )
            }
        }
    }

    @Test
    fun `the window filter is a real narrowing`() {
        val all = CoastlineData.lines()
        // A window over the Gulf of Mexico must keep some coast and drop most of the
        // planet — this is what stops the taxi-zoom case walking every continent.
        val gulf = CoastlineData.linesIn(south = 18.0, west = -98.0, north = 31.0, east = -80.0)
        assertTrue(gulf.isNotEmpty(), "expected coastline in the Gulf of Mexico window")
        assertTrue(gulf.size < all.size, "the filter kept everything")
    }

    @Test
    fun `an empty ocean window keeps nothing`() {
        // Mid South Pacific: no land, so nothing to draw and nothing to walk.
        val empty = CoastlineData.linesIn(south = -35.0, west = -140.0, north = -30.0, east = -135.0)
        assertTrue(empty.isEmpty(), "expected no coastline in open ocean, got ${empty.size}")
    }

    @Test
    fun `loading twice returns the same cached instance`() {
        assertTrue(CoastlineData.lines() === CoastlineData.lines(), "dataset should be cached")
    }
}
