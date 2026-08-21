package com.h3consultingpartners.ifatccompanion.core.surface

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stands that OSM maps under **more than one identifier** on a single node — `A1;A2` at
 * Newark, `A54/A56` at Frankfurt, `C16/C16A + C16B` — which is how roughly 8–10% of the
 * stands at those fields are tagged.
 *
 * Kept verbatim, such a `ref` is displayed and spoken as written, and a pilot who *types*
 * one of its identifiers matches nothing at all, because the stand is named `A1;A2` and
 * every lookup is exact. That hits manual entry as much as the automatic assignment.
 *
 * Ported from `IFATCCompanionTests/MultiIdentifierStandTests.swift`.
 */
class MultiIdentifierStandTest {

    private val ref = Coordinate(40.0, -75.0)

    // MARK: - Splitting the tag

    @Test
    fun aSingleIdentifierIsLeftExactlyAsTagged() {
        val parsed = StandIdentifier.parse("B44")
        assertEquals("B44", parsed.name)
        assertTrue(parsed.aliases.isEmpty(), "the ordinary stand gains nothing to match on")
    }

    @Test
    fun semicolonSeparatedIdentifiersSplit() {
        // The OSM multi-value separator; EWR's A1;A2.
        val parsed = StandIdentifier.parse("A1;A2")
        assertEquals("A1", parsed.name, "the first identifier is the one a controller says")
        assertEquals(listOf("A2", "A1;A2"), parsed.aliases)
    }

    @Test
    fun slashAndPlusSeparatedIdentifiersSplit() {
        assertEquals("A54", StandIdentifier.parse("A54/A56").name)
        assertEquals(listOf("A56", "A54/A56"), StandIdentifier.parse("A54/A56").aliases)

        val mixed = StandIdentifier.parse("C16/C16A + C16B")
        assertEquals("C16", mixed.name)
        assertEquals(listOf("C16A", "C16B", "C16/C16A + C16B"), mixed.aliases)
    }

    @Test
    fun aBareNumberInheritsThePrecedingLetterPrefix() {
        // "A54/56" is A54 and A56, not A54 and 56.
        val parsed = StandIdentifier.parse("A54/56")
        assertEquals("A54", parsed.name)
        assertEquals(listOf("A56", "A54/56"), parsed.aliases)
        // Only in that shape: nothing to inherit from, or a part that carries its own letters.
        assertEquals("2", StandIdentifier.parse("1/2").aliases.firstOrNull())
        assertEquals("B2", StandIdentifier.parse("A1/B2").aliases.firstOrNull())
    }

    @Test
    fun rangesSpacedNamesAndStraySeparatorsAreNotSplit() {
        // A dash reads as a range, not a list, and a space is part of the identifier.
        assertEquals("A1-A5", StandIdentifier.parse("A1-A5").name)
        assertTrue(StandIdentifier.parse("A1-A5").aliases.isEmpty())
        assertEquals("Gate 12", StandIdentifier.parse("Gate 12").name)
        assertTrue(StandIdentifier.parse("Gate 12").aliases.isEmpty())
        // A trailing separator or a repeat leaves one identifier and no aliases worth keeping.
        assertEquals("A1", StandIdentifier.parse("A1;").name)
        assertTrue(StandIdentifier.parse("A1;").aliases.isEmpty())
        assertEquals("A1", StandIdentifier.parse("A1;A1").name)
        assertTrue(StandIdentifier.parse("A1;A1").aliases.isEmpty())
    }

    // MARK: - Through the normalizer and the lookup

    private fun normalized(elements: String): AirportSurfaceModel {
        val json = "{\"version\":0.6,\"generator\":\"Overpass API\",\"elements\":[$elements]}"
        val response = OverpassResponse.decode(json)
        val bbox = OSMBoundingBox(center = ref, halfSpanDegrees = 0.04)
        return OSMSurfaceNormalizer.normalize(
            response, icao = "KEWR", reference = ref,
            endpoint = "test", boundingBox = bbox, fetchDateMillis = 1_700_000_000_000L,
        )
    }

    private fun field(): AirportSurfaceModel = normalized(
        """
        {"type":"node","id":1,"lat":40.0020,"lon":-75.0012,"tags":{"aeroway":"gate","ref":"A1;A2"}},
        {"type":"node","id":2,"lat":40.0021,"lon":-75.0013,"tags":{"aeroway":"gate","ref":"C16/C16A + C16B"}},
        {"type":"node","id":3,"lat":40.0022,"lon":-75.0014,"tags":{"aeroway":"gate","ref":"C16A"}},
        {"type":"node","id":4,"lat":40.0023,"lon":-75.0015,"tags":{"aeroway":"gate","ref":"B44"}}
        """.trimIndent(),
    )

    @Test
    fun theStandIsNamedByItsFirstIdentifier() {
        val m = field()
        assertEquals(
            "A1", m.parkingPositions.firstOrNull { it.osmID == "node/1" }?.name,
            "the map label and the clearance say A1, never \"A1;A2\"",
        )
        assertEquals("C16", m.parkingPositions.firstOrNull { it.osmID == "node/2" }?.name)
        // The tag itself is never discarded — provenance keeps it verbatim.
        assertEquals("A1;A2", m.parkingPositions.firstOrNull { it.osmID == "node/1" }?.tags?.get("ref"))
    }

    @Test
    fun aPilotTypingEitherIdentifierFindsTheStand() {
        val m = field()
        assertEquals("node/1", m.parking("A1")?.osmID)
        assertEquals("node/1", m.parking("A2")?.osmID, "the second identifier matched too")
        assertEquals("node/1", m.parking("a2")?.osmID, "case-insensitively")
        assertEquals("node/1", m.parking(" A2 ")?.osmID, "and ignoring stray spaces")
        assertEquals("node/1", m.parking("A1;A2")?.osmID, "as does the tag as written")
        assertEquals("node/2", m.parking("C16B")?.osmID)
    }

    @Test
    fun aStandsOwnNameBeatsAnotherStandsAlias() {
        // C16A is both an alias of node/2 (C16/C16A + C16B) and the name of node/3.
        assertEquals("node/3", field().parking("C16A")?.osmID)
    }

    @Test
    fun anUnknownGateStillMatchesNothing() {
        assertNull(field().parking("Z9"))
        assertNull(field().parking(""))
    }

    // MARK: - Cached extracts

    @Test
    fun aCacheWrittenBeforeIdentifiersWereSplitStillDecodesAndIsRefetched() {
        // A v2 cache has no `aliases` key and holds the raw tag as the stand's name. It must
        // still decode (never crash a load), and its schema version must mark it for re-fetch.
        val legacy = """
        {"osmID":"node/1","tags":{"aeroway":"gate","ref":"A1;A2"},"kind":"gate","name":"A1;A2",
         "coordinate":{"latitude":40.002,"longitude":-75.0012}}
        """.trimIndent()
        val stand = SurfaceJson.decodeFromString(SurfaceParking.serializer(), legacy)
        assertEquals("A1;A2", stand.name)
        assertTrue(stand.aliases.isEmpty())
        assertTrue(
            OSMSurface.SURFACE_SCHEMA_VERSION > 2,
            "the schema bump is what re-fetches those caches",
        )
    }
}
