package com.h3consultingpartners.ifatccompanion.core.connect

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Covers the Infinite Flight Connect flight-plan parser, with emphasis on the
 * detailed JSON document (PascalCase keys, nested SID/STAR/approach groups, and a
 * side-by-side simplified `Waypoints` summary list) — the shape that previously
 * collapsed a 20+ fix route into a 5-fix summary with no procedures or coordinates.
 *
 * Ported from `IFATCCompanionTests/FlightPlanParserTests.swift`. Several assertions
 * there cannot hold against the shipping Swift; each is marked `PARITY` below with the
 * behaviour the shipping parser actually produces, which is what this port reproduces.
 */
class FlightPlanParserTest {

    /**
     * A KTEB→KPHL document mirroring IF's structure: a detailed `FlightPlanItems`
     * array under `DetailedInfo` (airports, a SID group, enroute VORs, a STAR group,
     * an approach group) AND a top-level simplified `Waypoints` string list that
     * includes the DPT/TOC/TOD display markers.
     */
    private val detailedJSON = """
    {
      "Waypoints": ["KTEB", "DPT", "SBJ", "TOC", "LRP", "TOD", "KPHL"],
      "FlightPlanType": 0,
      "DetailedInfo": {
        "FlightPlanItems": [
          { "Identifier": "KTEB", "Children": null,
            "Location": { "Latitude": 40.8501, "Longitude": -74.0608, "Altitude": 0 } },
          { "Identifier": "RUUDY6", "Altitude": -1000,
            "Children": [
              { "Identifier": "WHITE", "Children": null,
                "Location": { "Latitude": 40.70, "Longitude": -74.30 } },
              { "Identifier": "SBJ", "Children": null,
                "Location": { "Latitude": 40.58, "Longitude": -74.73 } }
            ] },
          { "Identifier": "ARD", "Children": null,
            "Location": { "Latitude": 40.20, "Longitude": -74.90 } },
          { "Identifier": "LRP", "Children": null,
            "Location": { "Latitude": 40.12, "Longitude": -76.29 } },
          { "Identifier": "VINNY1", "Children": [
              { "Identifier": "MXE", "Children": null,
                "Location": { "Latitude": 39.98, "Longitude": -75.86 } }
            ] },
          { "Identifier": "ILS 27R", "Children": [
              { "Identifier": "PESks", "Altitude": 3000, "Children": null,
                "Location": { "Latitude": 39.92, "Longitude": -75.40 } }
            ] },
          { "Identifier": "KPHL", "Children": null,
            "Location": { "Latitude": 39.8719, "Longitude": -75.2411, "Altitude": 0 } }
        ]
      }
    }
    """.trimIndent()

    @Test
    fun detailedJSONPrefersFullRouteOverSummary() {
        val plan = IFFlightPlanParser.parse(detailedJSON) ?: fail("expected a parsed plan")
        assertEquals("KTEB", plan.departure)
        assertEquals("KPHL", plan.destination)

        // All enroute fixes from the detailed items — not the 5-fix Waypoints summary.
        val names = plan.waypoints.map { it.name }
        assertEquals(listOf("WHITE", "SBJ", "ARD", "LRP", "MXE", "PESKS"), names)

        // Every fix carries a coordinate, so the route can draw on the map.
        assertTrue(plan.waypoints.all { it.coordinate != null })

        // The departure/destination airport coordinates survive on the plan (they are
        // not enroute waypoints), so the markers land on the real fields.
        assertEquals(40.8501, plan.departureCoordinate?.latitude ?: 0.0, 0.0001)
        assertEquals(39.8719, plan.destinationCoordinate?.latitude ?: 0.0, 0.0001)

        // The DPT/TOC/TOD display markers from the summary list never appear.
        assertFalse(names.contains("DPT"))
        assertFalse(names.contains("TOC"))
        assertFalse(names.contains("TOD"))
    }

    @Test
    fun detailedJSONClassifiesProcedures() {
        val plan = IFFlightPlanParser.parse(detailedJSON)
        assertEquals("RUUDY6", plan?.sid)
        assertEquals("VINNY1", plan?.star)
        assertEquals("ILS 27R", plan?.approach)
        // First altitude in the approach section becomes the intercept altitude.
        assertEquals(3000, plan?.approachInterceptAltitude)
    }

    @Test
    fun detailedJSONTagsFirstApproachFix() {
        val plan = IFFlightPlanParser.parse(detailedJSON)
        // The first fix of the approach section — the deepest a weather deviation may
        // rejoin the route (never past it toward the destination).
        assertEquals("PESKS", plan?.approachStartFixName)
        assertEquals(39.92, plan?.approachStartCoordinate?.latitude ?: 0.0, 0.001)
        assertEquals(-75.40, plan?.approachStartCoordinate?.longitude ?: 0.0, 0.001)
    }

    @Test
    fun simplifiedWaypointsListFallbackDropsPseudoFixes() {
        // When only the simplified string list is present, pseudo markers are still
        // stripped (leaving the real fixes), rather than shown as waypoints.
        //
        // PARITY: the shipping Swift guards `item as? [String: Any]` at the top of
        // `parseJSON`'s item loop (IFFlightPlanParser.swift:289), so a *bare string*
        // entry is skipped outright and this document yields no plan at all — even
        // though `appendFix`'s string branch was clearly written for it. The iOS
        // assertions (KTEB / KPHL / ["SBJ","LRP"]) cannot hold against that code. Ported
        // as-is per PORTING.md rule 3; the simplified list still reaches the app through
        // the `route` state, which is where every green test above gets it from.
        val json = """{ "Waypoints": ["KTEB", "DPT", "SBJ", "TOC", "LRP", "TOD", "KPHL"] }"""
        assertNull(IFFlightPlanParser.parse(json))
    }

    @Test
    fun routeStringStillParses() {
        val plan = IFFlightPlanParser.parse("KIAH SBJ LRP FL370 KMSP")
        assertEquals("KIAH", plan?.departure)
        assertEquals("KMSP", plan?.destination)
        assertEquals(listOf("SBJ", "LRP"), plan?.waypoints?.map { it.name })
        assertEquals(37000, plan?.cruiseAltitude)
    }

    /**
     * A KIAH→KATL document in the exact shape Infinite Flight sends on device: camelCase
     * keys, the item array under `detailedInfo`, procedure groups tagged with the `type`
     * enum (Sid=0/STAR=1/Approach=2) while plain fixes also carry `type: 0`, a compound
     * `DPT RW15L` marker, and an approach whose transition repeats two of its own fixes.
     * Mirrors a reported flight where the I09R approach fixes were missing from the app.
     */
    private val kiahKatlJSON = """
    {
      "waypointName": "JNGLE",
      "detailedInfo": {
        "waypoints": ["KIAH", "RW15L", "DPT RW15L", "MMUGS4", "LCH", "GNDLF3", "I09R", "RW09R", "KATL"],
        "flightPlanType": 0,
        "flightPlanItems": [
          { "name": "KIAH", "type": 0, "children": [], "identifier": "KIAH", "altitude": -1,
            "location": { "Latitude": 29.9854, "Longitude": -95.3412 } },
          { "name": "RW15L", "type": 0, "children": [], "identifier": "RW15L", "altitude": -1,
            "location": { "Latitude": 29.9879, "Longitude": -95.3579 } },
          { "name": "DPT RW15L", "type": 0, "children": [], "identifier": "DPT RW15L", "altitude": -1,
            "location": { "Latitude": 29.9588, "Longitude": -95.3401 } },
          { "name": "MMUGS4", "type": 0, "children": [
              { "name": "TTAPS", "type": 0, "children": [], "identifier": "TTAPS", "altitude": 8500,
                "location": { "Latitude": 29.8884, "Longitude": -95.2389 } },
              { "name": "BOTLL", "type": 0, "children": [], "identifier": "BOTLL", "altitude": 12500,
                "location": { "Latitude": 29.8236, "Longitude": -95.1350 } },
              { "name": "TOC", "type": 0, "children": [], "identifier": "TOC", "altitude": 35000,
                "location": { "Latitude": 30.1169, "Longitude": -93.3132 } }
            ], "identifier": "MMUGS4", "altitude": -1 },
          { "name": "LCH", "type": 0, "children": [], "identifier": "LCH", "altitude": -1,
            "location": { "Latitude": 30.1414, "Longitude": -93.1056 } },
          { "name": "GNDLF3", "type": 1, "children": [
              { "name": "TOD", "type": 0, "children": [], "identifier": "TOD", "altitude": 35000,
                "location": { "Latitude": 32.5133, "Longitude": -86.1170 } },
              { "name": "JNGLE", "type": 0, "children": [], "identifier": "JNGLE", "altitude": 11000,
                "location": { "Latitude": 33.2997, "Longitude": -84.8297 } },
              { "name": "QUBIT", "type": 0, "children": [], "identifier": "QUBIT", "altitude": 8000,
                "location": { "Latitude": 33.4525, "Longitude": -84.8297 } }
            ], "identifier": "GNDLF3", "altitude": -1 },
          { "name": "I09R", "type": 2, "children": [
              { "name": "DFINS", "type": 0, "children": [], "identifier": "DFINS", "altitude": 4000,
                "location": { "Latitude": 33.6311, "Longitude": -84.7936 } },
              { "name": "GGUYY", "type": 0, "children": [], "identifier": "GGUYY", "altitude": 3000,
                "location": { "Latitude": 33.6314, "Longitude": -84.7186 } },
              { "name": "EEASY", "type": 0, "children": [], "identifier": "EEASY", "altitude": 3000,
                "location": { "Latitude": 33.6314, "Longitude": -84.6497 } },
              { "name": "GGUYY", "type": 0, "children": [], "identifier": "GGUYY", "altitude": 3000,
                "location": { "Latitude": 33.6314, "Longitude": -84.7186 } },
              { "name": "EEASY", "type": 0, "children": [], "identifier": "EEASY", "altitude": 3000,
                "location": { "Latitude": 33.6314, "Longitude": -84.6497 } },
              { "name": "BURNY", "type": 0, "children": [], "identifier": "BURNY", "altitude": 2400,
                "location": { "Latitude": 33.6317, "Longitude": -84.5492 } }
            ], "identifier": "I09R", "altitude": -1 },
          { "name": "RW09R", "type": 0, "children": [], "identifier": "RW09R", "altitude": -1,
            "location": { "Latitude": 33.6318, "Longitude": -84.4480 } },
          { "name": "KATL", "type": 0, "children": [], "identifier": "KATL", "altitude": -1,
            "location": { "Latitude": 33.6366, "Longitude": -84.4280 } }
        ]
      }
    }
    """.trimIndent()

    /**
     * The approach group's fixes are enroute waypoints like any other — the route must
     * not stop at the STAR's last fix (QUBIT) with the I09R fixes dropped.
     */
    @Test
    fun approachGroupFixesAreKeptAsWaypoints() {
        val plan = IFFlightPlanParser.parse(kiahKatlJSON) ?: fail("expected a parsed plan")
        assertEquals("KIAH", plan.departure)
        assertEquals("KATL", plan.destination)
        // Repeated transition fixes collapse to one entry each; the runway/marker tokens
        // (RW15L, DPT RW15L, TOC, TOD, RW09R) never appear.
        assertEquals(
            listOf(
                "TTAPS", "BOTLL", "LCH", "JNGLE", "QUBIT",
                "DFINS", "GGUYY", "EEASY", "BURNY",
            ),
            plan.waypoints.map { it.name },
        )
        assertTrue(plan.waypoints.all { it.coordinate != null })
        assertEquals("MMUGS4", plan.sid)
        assertEquals("GNDLF3", plan.star)
        assertEquals("I09R", plan.approach)
        assertEquals("DFINS", plan.approachStartFixName)
        assertEquals(4000, plan.approachInterceptAltitude)
        assertEquals("15L", plan.departureRunway)
        assertEquals("09R", plan.arrivalRunway)
        // The TOC/TOD markers are dropped as fixes but still set the cruise level.
        assertEquals(35000, plan.cruiseAltitude)
    }

    // region Multi-state combining (full + route + coordinates)

    /**
     * When `aircraft/0/flightplan` collapses the route to a sparse summary, the
     * textual `flightplan/route` state's longer fix list is preferred — this is the
     * real-device case where the summary yielded only SBJ→LRP.
     */
    @Test
    fun routeStringEnrichesSparseSummary() {
        val full = """{ "Waypoints": ["KTEB", "DPT", "SBJ", "TOC", "LRP", "TOD", "KPHL"] }"""
        val route = "KTEB SBJ WHITE ARD LRP MXE KPHL"
        val plan = IFFlightPlanParser.parse(full = full, route = route, coordinates = null)
        assertEquals("KTEB", plan?.departure)
        assertEquals("KPHL", plan?.destination)
        assertEquals(
            listOf("SBJ", "WHITE", "ARD", "LRP", "MXE"),
            plan?.waypoints?.map { it.name },
        )
    }

    /**
     * A richer `full` payload is not discarded just because a route state exists:
     * the route only wins when it recovers *more* fixes.
     */
    @Test
    fun richerFullPayloadIsNotReplacedByShorterRoute() {
        val route = "KTEB SBJ KPHL" // only one enroute fix
        val plan = IFFlightPlanParser.parse(full = detailedJSON, route = route, coordinates = null)
        assertEquals(
            listOf("WHITE", "SBJ", "ARD", "LRP", "MXE", "PESKS"),
            plan?.waypoints?.map { it.name },
        )
        assertEquals("RUUDY6", plan?.sid)
    }

    // endregion

    // region Route/coordinate alignment (the reported missing-approach-fixes case)

    /**
     * The real KIAH→KATL states: `route` is comma-separated and 1-for-1 with the
     * space-separated `coordinates` pairs — 34 entries each, including the compound
     * `DPT RW15L` marker (one entry containing a space), the TOC/TOD markers, the runway
     * tokens at both ends, and the I09R transition repeating GGUYY/EEASY.
     */
    private val katlRoute =
        "KIAH,RW15L,DPT RW15L,TTAPS,BOTLL,MMUGS,MMALT,HOURN,BLING,TOC,LCH,LSU,IRUBE,PAYTN,SHYRE," +
            "FRDDO,BLLBO,TOD,BGGNS,SMAWG,GNDLF,HALRR,SHULR,JNGLE,QUBIT,DFINS,GGUYY,EEASY,GGUYY,EEASY," +
            "BURNY,RW09R,RW09R,KATL"

    private val katlCoordinates =
        "29.98544331,-95.34119568 29.98787689,-95.35786438 29.95878792,-95.34007263 " +
            "29.88835639,-95.23890306 29.82362611,-95.13500000 29.81751500,-94.98362889 " +
            "29.87002083,-94.78361139 29.99585444,-94.30445222 30.03056000,-94.01807639 " +
            "30.11686300,-93.31316000 30.14140139,-93.10555694 30.48501333,-91.29390667 " +
            "31.00419306,-88.93835056 31.46778750,-87.88530306 31.52584528,-87.79002056 " +
            "32.32167583,-86.44697056 32.42861611,-86.26361361 32.51327300,-86.11697200 " +
            "32.57529972,-86.00862500 33.02362583,-85.22194556 33.11750500,-85.07084306 " +
            "33.15807500,-85.00472556 33.24030139,-84.87141333 33.29974556,-84.82974000 " +
            "33.45252639,-84.82973972 33.63111778,-84.79363861 33.63138972,-84.71863361 " +
            "33.63140611,-84.64972694 33.63138972,-84.71863361 33.63140611,-84.64972694 " +
            "33.63167278,-84.54919111 33.63182068,-84.44801331 33.63182068,-84.44801331 " +
            "33.63662987,-84.42801819"

    /**
     * The route and coordinate states pair by index, so every fix the route names gets its
     * true position — including the ones after the STAR. Splitting the route on whitespace
     * would break the alignment at `DPT RW15L` and shift every position after it.
     */
    @Test
    fun alignedRouteLocatesEveryFix() {
        val fixes = IFFlightPlanParser.parseAlignedRoute(katlRoute, katlCoordinates)
        assertEquals(24, fixes.size)
        assertEquals("TTAPS", fixes.first().name)
        assertEquals("BURNY", fixes.last().name)
        assertTrue(fixes.all { it.coordinate != null })
        // TTAPS is entry 3 — off by one if `DPT RW15L` had been split into two tokens.
        assertEquals(29.88835639, fixes.first().coordinate?.latitude ?: 0.0, 1e-6)
        assertEquals(-95.23890306, fixes.first().coordinate?.longitude ?: 0.0, 1e-6)
        // BURNY is entry 30, past the repeated GGUYY/EEASY transition fixes.
        assertEquals(33.63167278, fixes.last().coordinate?.latitude ?: 0.0, 1e-6)
        assertEquals(-84.54919111, fixes.last().coordinate?.longitude ?: 0.0, 1e-6)
    }

    /** Mismatched states are refused rather than paired off-by-one. */
    @Test
    fun alignedRouteRefusesMismatchedCoordinates() {
        assertTrue(IFFlightPlanParser.parseAlignedRoute(katlRoute, "33.6, -84.4").isEmpty())
        assertTrue(IFFlightPlanParser.parseAlignedRoute(katlRoute, null).isEmpty())
    }

    /**
     * The reported failure: the detailed document carries the enroute and STAR fixes but
     * nothing for the approach, so its plan stops at the STAR's last fix (QUBIT). The route
     * state names all of them, so its longer list must win — a shorter list is no longer
     * preferred just because it happens to carry coordinates.
     */
    @Test
    fun detailedPlanMissingApproachFixesIsCompletedFromRoute() {
        val fullInfo = """
        { "detailedInfo": { "flightPlanItems": [
            { "identifier": "KIAH", "children": [], "altitude": -1,
              "location": { "Latitude": 29.9854, "Longitude": -95.3412 } },
            { "name": "GNDLF3", "type": 1, "children": [
                { "identifier": "JNGLE", "altitude": 11000, "children": [],
                  "location": { "Latitude": 33.29974556, "Longitude": -84.82974 } },
                { "identifier": "QUBIT", "altitude": 8000, "children": [],
                  "location": { "Latitude": 33.45252639, "Longitude": -84.82973972 } } ] },
            { "identifier": "KATL", "children": [], "altitude": -1,
              "location": { "Latitude": 33.6366, "Longitude": -84.4280 } }
        ] } }
        """.trimIndent()
        val plan = IFFlightPlanParser.parse(
            fullInfo = fullInfo,
            full = null,
            route = katlRoute,
            coordinates = katlCoordinates,
        ) ?: fail("expected a parsed plan")

        assertEquals(24, plan.waypoints.size)
        assertEquals(
            listOf("QUBIT", "DFINS", "GGUYY", "EEASY", "BURNY"),
            plan.waypoints.takeLast(5).map { it.name },
        )
        assertTrue(plan.waypoints.all { it.coordinate != null })
        assertEquals(-84.54919111, plan.waypoints.last().coordinate?.longitude ?: 0.0, 1e-6)
        // What the detailed document *did* carry survives: the procedure name…
        assertEquals("GNDLF3", plan.star)
        // …and the planned altitudes for the fixes both lists share.
        assertEquals(8000.0, plan.waypoints.first { it.name == "QUBIT" }.altitude)
        assertEquals(11000.0, plan.waypoints.first { it.name == "JNGLE" }.altitude)
        assertEquals("KIAH", plan.departure)
        assertEquals("KATL", plan.destination)
    }

    /**
     * Coordinates are attached to fixes when the parsed pair count matches.
     *
     * PARITY: same top-level-string skip as
     * [simplifiedWaypointsListFallbackDropsPseudoFixes] — a `Waypoints`-only summary
     * yields no base plan, so there is nothing for the coordinate list to attach to.
     */
    @Test
    fun coordinatesAttachedWhenCountMatches() {
        val full = """{ "Waypoints": ["KTEB", "SBJ", "LRP", "KPHL"] }"""
        val coords = "40.58, -74.73; 40.12, -76.29" // two enroute fixes
        assertNull(IFFlightPlanParser.parse(full = full, route = null, coordinates = coords))
    }

    /**
     * A mismatched coordinate list is ignored rather than scattering the route.
     *
     * PARITY: as above — the summary-only document yields no base plan at all, which is
     * a stronger form of "the mismatched list changes nothing".
     */
    @Test
    fun mismatchedCoordinatesIgnored() {
        val full = """{ "Waypoints": ["KTEB", "SBJ", "LRP", "KPHL"] }"""
        val coords = "40.58, -74.73" // only one pair for two fixes
        assertNull(IFFlightPlanParser.parse(full = full, route = null, coordinates = coords))
    }

    /**
     * A flat coordinate list that carries the departure/destination airports as its
     * first and last entries (two more than the enroute fixes) is mapped correctly:
     * the endpoints land on the plan's departure/destination coordinates and the
     * middle coordinates onto the fixes — so the route draws to both fields, not
     * short of them. Regression for the "route shrunk to the enroute fixes" bug.
     *
     * The iOS fixture feeds this through a `Waypoints`-only *string* summary, which the
     * shipping parser drops (see [simplifiedWaypointsListFallbackDropsPseudoFixes]). The
     * same four unlocated entries are supplied here as the item objects the parser does
     * read, so the endpoints-included mapping rule itself is still covered.
     */
    @Test
    fun coordinateListWithEndpointsMapsToAirportsAndFixes() {
        val full = """
        { "flightPlanItems": [
            { "identifier": "SBGL", "children": null },
            { "identifier": "KOKPI", "children": null },
            { "identifier": "GAPE", "children": null },
            { "identifier": "SBPS", "children": null }
        ] }
        """.trimIndent()
        val coords = "-22.8089,-43.2438;-22.637,-42.690;-21.927,-41.470;-16.4385,-39.0810"
        val plan = IFFlightPlanParser.parse(full = full, route = null, coordinates = coords)
            ?: fail("expected a parsed plan")
        assertEquals("SBGL", plan.departure)
        assertEquals("SBPS", plan.destination)
        assertEquals(listOf("KOKPI", "GAPE"), plan.waypoints.map { it.name })
        assertEquals(-22.8089, plan.departureCoordinate?.latitude ?: 0.0, 0.001)
        assertEquals(-39.0810, plan.destinationCoordinate?.longitude ?: 0.0, 0.001)
        assertEquals(-22.637, plan.waypoints.first().coordinate?.latitude ?: 0.0, 0.001)
        assertEquals(-41.470, plan.waypoints.last().coordinate?.longitude ?: 0.0, 0.001)
    }

    /**
     * A southern-hemisphere detailed document keeps the departure and destination
     * airport coordinates on the plan (they are dropped from the enroute waypoint
     * list, but their position must survive so the markers land on the real field
     * when it is outside the built-in US airport database). Regression for the
     * Southern-Hemisphere map bug: SBGL→SBPS drew the destination at the last enroute
     * fix (VAMUR), well short of the coast, because the airport coordinate was lost.
     */
    @Test
    fun detailedJSONKeepsEndpointCoordinates() {
        val json = """
        {
          "flightPlanItems": [
            { "identifier": "SBGL", "children": [],
              "location": { "Latitude": -22.808890, "Longitude": -43.243754 } },
            { "identifier": "KOKPI", "children": [],
              "location": { "Latitude": -22.636967, "Longitude": -42.690017 } },
            { "identifier": "VAMUR", "children": [],
              "location": { "Latitude": -16.815283, "Longitude": -39.658617 } },
            { "identifier": "SBPS", "children": [],
              "location": { "Latitude": -16.438536, "Longitude": -39.080952 } }
          ]
        }
        """.trimIndent()
        val plan = IFFlightPlanParser.parse(json) ?: fail("expected a parsed plan")
        assertEquals("SBGL", plan.departure)
        assertEquals("SBPS", plan.destination)
        assertEquals(listOf("KOKPI", "VAMUR"), plan.waypoints.map { it.name })
        // The destination marker must resolve to SBPS on the coast, not to VAMUR.
        assertEquals(-22.808890, plan.departureCoordinate?.latitude ?: 0.0, 0.0001)
        assertEquals(-43.243754, plan.departureCoordinate?.longitude ?: 0.0, 0.0001)
        assertEquals(-16.438536, plan.destinationCoordinate?.latitude ?: 0.0, 0.0001)
        assertEquals(-39.080952, plan.destinationCoordinate?.longitude ?: 0.0, 0.0001)
    }

    // endregion

    // region Detailed `flightplan/full_info` document

    /**
     * The rich document Infinite Flight serves at `aircraft/0/flightplan/full_info`:
     * camelCase keys, per-fix planned `altitude`, and procedure groups tagged with an
     * explicit `type` (Sid=0, STAR=1, Approach=2). This is the only state that carries
     * the cruise altitude and the published procedure names.
     */
    private val fullInfoJSON = """
    {
      "flightPlanItems": [
        { "identifier": "KTEB", "altitude": -1, "children": null,
          "location": { "latitude": 40.8501, "longitude": -74.0608 } },
        { "name": "RUUDY6", "type": 0, "identifier": "RUUDY6",
          "children": [
            { "identifier": "WHITE", "altitude": 4000, "children": null,
              "location": { "latitude": 40.70, "longitude": -74.30 } },
            { "identifier": "SBJ", "altitude": 11000, "children": null,
              "location": { "latitude": 40.58, "longitude": -74.73 } }
          ] },
        { "identifier": "LRP", "altitude": 37000, "children": null,
          "location": { "latitude": 40.12, "longitude": -76.29 } },
        { "name": "VINNY1", "type": 1, "identifier": "VINNY1",
          "children": [
            { "identifier": "MXE", "altitude": 11000, "children": null,
              "location": { "latitude": 39.98, "longitude": -75.86 } }
          ] },
        { "name": "ILS 27R", "type": 2, "identifier": "I27R",
          "children": [
            { "identifier": "PETER", "altitude": 3000, "children": null,
              "location": { "latitude": 39.92, "longitude": -75.40 } }
          ] },
        { "identifier": "KPHL", "altitude": -1, "children": null,
          "location": { "latitude": 39.8719, "longitude": -75.2411 } }
      ]
    }
    """.trimIndent()

    /**
     * full_info supplies the cruise altitude (highest planned level), per-fix
     * altitudes, and the SID/STAR/approach names — and is preferred over both the
     * collapsed summary and the route string when present.
     */
    @Test
    fun fullInfoProvidesAltitudesAndProcedures() {
        val summary = """{ "Waypoints": ["KTEB","DPT","SBJ","TOC","LRP","TOD","KPHL"] }"""
        val route = "KTEB WHITE SBJ LRP MXE PETER KPHL"
        val plan = IFFlightPlanParser.parse(
            fullInfo = fullInfoJSON,
            full = summary,
            route = route,
            coordinates = null,
        ) ?: fail("expected a parsed plan")

        assertEquals("KTEB", plan.departure)
        assertEquals("KPHL", plan.destination)
        assertEquals(listOf("WHITE", "SBJ", "LRP", "MXE", "PETER"), plan.waypoints.map { it.name })

        // Cruise altitude = highest planned per-fix altitude.
        assertEquals(37000, plan.cruiseAltitude)

        // Procedures classified from the explicit `type` enum.
        assertEquals("RUUDY6", plan.sid)
        assertEquals("VINNY1", plan.star)
        // PARITY: the shipping Swift names a procedure group by `identifier` first and
        // only then `name`, so the approach here is its identifier "I27R" — the iOS
        // assertion of "ILS 27R" cannot hold against that fixture.
        assertEquals("I27R", plan.approach)
        assertEquals(3000, plan.approachInterceptAltitude)

        // Per-fix planned altitudes are preserved on each waypoint.
        val alt = plan.waypoints.associate { it.name to it.altitude }
        assertEquals(4000.0, alt["WHITE"])
        assertEquals(11000.0, alt["SBJ"])
        assertEquals(37000.0, alt["LRP"])
        assertEquals(3000.0, alt["PETER"])
    }

    /**
     * The explicit procedure `type` is authoritative — it overrides the name/position
     * heuristics. A first procedure tagged STAR is the STAR (not the SID), and a
     * keyword-less name tagged Approach is the approach.
     */
    @Test
    fun procedureTypeOverridesNameHeuristic() {
        val json = """
        { "flightPlanItems": [
            { "identifier": "EGLL", "children": null, "location": {"latitude":51.47,"longitude":-0.46} },
            { "name": "LOGAN1", "type": 1, "children": [
                { "identifier": "LOGAN", "children": null, "location": {"latitude":51.0,"longitude":-0.5} } ] },
            { "name": "FINALX", "type": 2, "children": [
                { "identifier": "DET", "altitude": 2000, "children": null, "location": {"latitude":51.3,"longitude":0.6} } ] },
            { "identifier": "EGKK", "children": null, "location": {"latitude":51.15,"longitude":-0.19} }
        ] }
        """.trimIndent()
        val plan = IFFlightPlanParser.parse(json)
        assertEquals("LOGAN1", plan?.star) // type 1, despite being the first procedure
        assertTrue(plan?.sid?.isEmpty() ?: false) // …so it is not mistaken for a SID
        assertEquals("FINALX", plan?.approach) // type 2, despite no approach keyword
        assertEquals(2000, plan?.approachInterceptAltitude)
    }

    @Test
    fun parseCoordinateList() {
        val pairs = IFFlightPlanParser.parseCoordinateList("40.58, -74.73; 40.12, -76.29")
        assertEquals(2, pairs.size)
        assertEquals(40.58, pairs[0].latitude, 0.0001)
        assertEquals(-74.73, pairs[0].longitude, 0.0001)
        assertEquals(-76.29, pairs[1].longitude, 0.0001)
    }

    @Test
    fun combiningAllNilReturnsNil() {
        assertNull(IFFlightPlanParser.parse(full = null, route = null, coordinates = null))
    }

    /**
     * The cruise altitude is the final cruise level even when only the TOC/TOD
     * display marker carries it — not the highest *enroute fix* altitude (which is
     * the climbing level reached just before the top of climb).
     */
    @Test
    fun cruiseAltitudeComesFromTOCMarkerNotLastClimbFix() {
        val json = """
        { "flightPlanItems": [
            { "identifier": "KTEB", "altitude": -1, "children": null,
              "location": { "latitude": 40.85, "longitude": -74.06 } },
            { "identifier": "SBJ", "altitude": 27800, "children": null,
              "location": { "latitude": 40.58, "longitude": -74.73 } },
            { "identifier": "TOC", "altitude": 28000, "children": null,
              "location": { "latitude": 40.50, "longitude": -75.00 } },
            { "identifier": "LRP", "altitude": 27800, "children": null,
              "location": { "latitude": 40.12, "longitude": -76.29 } },
            { "identifier": "KPHL", "altitude": -1, "children": null,
              "location": { "latitude": 39.87, "longitude": -75.24 } }
        ] }
        """.trimIndent()
        val plan = IFFlightPlanParser.parse(json)
        assertEquals(28000, plan?.cruiseAltitude, "cruise should be the TOC level, not FL278")
        // …and the TOC marker is still never shown as a waypoint.
        assertFalse(plan?.waypoints?.map { it.name }?.contains("TOC") ?: true)
    }

    /**
     * The departure runway (`DPT RW22R`) and an arrival runway token are recovered
     * from the route and never shown as enroute fixes.
     */
    @Test
    fun routeStringRecoversDepartureAndArrivalRunways() {
        val plan = IFFlightPlanParser.parse("KEWR RW22R MERIT NEION 01R KBOS")
        assertEquals("22R", plan?.departureRunway)
        assertEquals("01R", plan?.arrivalRunway)
        assertEquals(listOf("MERIT", "NEION"), plan?.waypoints?.map { it.name })
    }

    /**
     * A lone departure runway token near the start is recorded as the departure
     * runway (not the arrival), and stripped from the fixes.
     */
    @Test
    fun routeStringRecoversDepartureRunwayOnly() {
        val plan = IFFlightPlanParser.parse("KEWR RW22R MERIT NEION KBOS")
        assertEquals("22R", plan?.departureRunway)
        assertTrue(plan?.arrivalRunway?.isEmpty() ?: false)
        assertEquals(listOf("MERIT", "NEION"), plan?.waypoints?.map { it.name })
    }

    @Test
    fun runwayIdentNormalisation() {
        assertEquals("22R", IFFlightPlanParser.runwayIdent("RW22R"))
        // PARITY: the iOS test asserts "09" here, but `isRunwayToken` strips only the
        // two-character "RW" prefix — leaving "Y09", whose leading digit run is empty —
        // so the shipping parser returns nil and the `"RWY"` branch inside `runwayIdent`
        // is dead code. Ported as-is: Infinite Flight emits RW09 / DPT RW09, never RWY09,
        // and widening `isRunwayToken` would also change which tokens are filtered out of
        // the enroute fix list.
        assertNull(IFFlightPlanParser.runwayIdent("RWY09"))
        assertEquals("30L", IFFlightPlanParser.runwayIdent("30L"))
        assertNull(IFFlightPlanParser.runwayIdent("MERIT"))
        assertNull(IFFlightPlanParser.runwayIdent("FL370"))
    }

    @Test
    fun pseudoWaypointDetection() {
        for (marker in listOf("DPT", "TOC", "TOD", "T/C", "T/D", "DEP", "DEST")) {
            assertTrue(IFFlightPlanParser.isPseudoWaypoint(marker), "$marker should be pseudo")
        }
        // Compound departure/arrival markers (marker word + runway), the form Infinite
        // Flight emits as a single identifier in the detailed JSON.
        for (marker in listOf("DPT RW15L", "DEP RW09", "ARR RW09", "DEPARTURE RW04L")) {
            assertTrue(IFFlightPlanParser.isPseudoWaypoint(marker), "$marker should be pseudo")
        }
        assertFalse(IFFlightPlanParser.isPseudoWaypoint("SBJ"))
        assertFalse(IFFlightPlanParser.isPseudoWaypoint("LRP"))
        // A real fix whose first token merely resembles a marker word is not dropped —
        // only "<marker> <runway>" is a marker.
        assertFalse(IFFlightPlanParser.isPseudoWaypoint("DPT ABCDE"))
    }

    /**
     * The narrower test that picks the *departure* markers out of the pseudo-waypoints, so
     * their position can be kept as the origin of the departure leg. Arrival markers carry a
     * runway position too and must not be mistaken for it.
     */
    @Test
    fun departureRunwayMarkerDetection() {
        for (marker in listOf("DPT RW15L", "DEP RW09", "DEPARTURE RW04L", "dpt rw26l")) {
            assertTrue(
                IFFlightPlanParser.isDepartureRunwayMarker(marker),
                "$marker is a departure marker",
            )
        }
        for (other in listOf(
            "ARR RW09", "ARRIVAL RW22R", "DEST RW09", "DPT", "RW15L", "DPT ABCDE", "MERIT",
        )) {
            assertFalse(IFFlightPlanParser.isDepartureRunwayMarker(other), "$other is not one")
        }
    }

    /** A compound marker names its runway, and which end of the flight it belongs to. */
    @Test
    fun markerRunwayReadsBothEnds() {
        assertEquals("17R", IFFlightPlanParser.markerRunway("DPT RW17R")?.ident)
        assertEquals(
            IFFlightPlanParser.RouteEnd.DEPARTURE,
            IFFlightPlanParser.markerRunway("DPT RW17R")?.end,
        )
        assertEquals("09", IFFlightPlanParser.markerRunway("dep rw09")?.ident)
        assertEquals("18C", IFFlightPlanParser.markerRunway("ARR RW18C")?.ident)
        assertEquals(
            IFFlightPlanParser.RouteEnd.ARRIVAL,
            IFFlightPlanParser.markerRunway("ARR RW18C")?.end,
        )
        for (other in listOf("DPT", "RW17R", "DPT ABCDE", "TOC", "MERIT")) {
            assertNull(IFFlightPlanParser.markerRunway(other), "$other names no runway")
        }
    }

    /**
     * Regression for the reported KMCO departure: a detailed plan whose only runway
     * evidence is the compound `DPT RW17R` marker must still come out naming runway 17R.
     *
     * The marker is filtered out of the fix list as a display marker before `captureRunways`
     * — the only thing that set `departureRunway` — ever sees it, so it used to contribute a
     * position and nothing else. An empty departure runway sends `resolvedRunway` past the
     * runway inventory (KMCO isn't in it) down to its last resort: the wind direction rounded
     * to the nearest ten, called a runway. The clearance then names a runway the field does
     * not have, and — the part that loses the turn — the takeoff clearance's "within 10° of
     * runway heading" test is measured against the wind, so a departure vector that happens
     * to lie near it collapses to "fly runway heading".
     */
    @Test
    fun compoundMarkersNameTheDepartureAndArrivalRunways() {
        val json = """
        {
          "flightPlanItems": [
            { "name": "KMCO", "type": 0, "children": [],
              "location": { "Latitude": 28.4294, "Longitude": -81.3089 } },
            { "name": "DPT RW17R", "type": 0, "children": [],
              "location": { "Latitude": 28.4147, "Longitude": -81.3161 } },
            { "name": "KAAPE", "type": 0, "children": [],
              "location": { "Latitude": 28.3600, "Longitude": -81.2700 } },
            { "name": "ARR RW18C", "type": 0, "children": [],
              "location": { "Latitude": 32.8900, "Longitude": -97.0400 } },
            { "name": "KDFW", "type": 0, "children": [],
              "location": { "Latitude": 32.8968, "Longitude": -97.0380 } }
          ]
        }
        """.trimIndent()
        val plan = IFFlightPlanParser.parse(json) ?: fail("expected a parsed plan")
        assertEquals("KMCO", plan.departure)
        assertEquals("KDFW", plan.destination)
        // Neither marker is a fix …
        assertEquals(listOf("KAAPE"), plan.waypoints.map { it.name })
        // … but both name their runway, with no bare runway token anywhere in the plan.
        assertEquals("17R", plan.departureRunway)
        assertEquals("18C", plan.arrivalRunway)
        // The departure marker's position is still kept as the origin of the departure leg.
        assertNotNull(plan.departureRunwayCoordinate)
    }

    /**
     * With both sources present they agree on the ident, and the marker still supplies the
     * origin — it sits at the runway end, which is where the departure leg is flown from.
     */
    @Test
    fun markerAndBareTokenAgreeOnTheDepartureRunway() {
        val json = """
        {
          "flightPlanItems": [
            { "name": "KMCO", "type": 0, "children": [],
              "location": { "Latitude": 28.4294, "Longitude": -81.3089 } },
            { "name": "RW17L", "type": 0, "children": [],
              "location": { "Latitude": 28.4494, "Longitude": -81.3053 } },
            { "name": "DPT RW17L", "type": 0, "children": [],
              "location": { "Latitude": 28.4189, "Longitude": -81.3053 } },
            { "name": "KAAPE", "type": 0, "children": [],
              "location": { "Latitude": 28.3600, "Longitude": -81.2700 } },
            { "name": "KDFW", "type": 0, "children": [],
              "location": { "Latitude": 32.8968, "Longitude": -97.0380 } }
          ]
        }
        """.trimIndent()
        val plan = IFFlightPlanParser.parse(json) ?: fail("expected a parsed plan")
        assertEquals("17L", plan.departureRunway)
        // The marker still supplies the origin: it sits at the runway *end*, the bare token at
        // the threshold, and the marker is read first.
        assertEquals(28.4189, plan.departureRunwayCoordinate?.latitude ?: 0.0, 0.0001)
    }

    /**
     * Infinite Flight's detailed JSON carries a "DPT RW__" marker at the departure end
     * of the runway as a single identifier (unlike the route string, where the space
     * splits it apart). It is a non-navigational display marker, not a fix. Left in, it
     * becomes the first waypoint and — sitting straight down the runway from the
     * aircraft — forces the takeoff clearance to "fly runway heading" on every flight.
     * Regression for the reported KIAH / MMUGS4 departure.
     */
    @Test
    fun detailedJSONDropsCompoundDepartureRunwayMarker() {
        val json = """
        {
          "flightPlanItems": [
            { "name": "KIAH", "type": 0, "children": [],
              "location": { "Latitude": 29.9854, "Longitude": -95.3412 } },
            { "name": "RW15L", "type": 0, "children": [],
              "location": { "Latitude": 29.9879, "Longitude": -95.3579 } },
            { "name": "DPT RW15L", "type": 0, "children": [],
              "location": { "Latitude": 29.9588, "Longitude": -95.3401 } },
            { "name": "MMUGS4", "type": 0, "identifier": "MMUGS4", "children": [
                { "name": "TTAPS", "type": 0, "children": [],
                  "location": { "Latitude": 29.8884, "Longitude": -95.2389 } },
                { "name": "BOTLL", "type": 0, "children": [],
                  "location": { "Latitude": 29.8236, "Longitude": -95.1350 } } ] },
            { "name": "LLA", "type": 0, "children": [],
              "location": { "Latitude": 29.6714, "Longitude": -92.8112 } },
            { "name": "KMIA", "type": 0, "children": [],
              "location": { "Latitude": 25.7938, "Longitude": -80.2870 } }
          ]
        }
        """.trimIndent()
        val plan = IFFlightPlanParser.parse(json) ?: fail("expected a parsed plan")
        assertEquals("KIAH", plan.departure)
        assertEquals("KMIA", plan.destination)
        // Both the "DPT RW15L" runway-end marker and the bare "RW15L" runway are dropped.
        assertFalse(plan.waypoints.any { it.name == "DPT RW15L" })
        assertFalse(plan.waypoints.any { it.name == "RW15L" })
        // The first waypoint is the SID's first published fix, not the runway end.
        assertEquals("TTAPS", plan.waypoints.first().name)
        assertEquals("MMUGS4", plan.sid)
        // The SID's own fix structure is recovered from the procedure group.
        assertEquals(listOf("TTAPS", "BOTLL"), plan.sidFixNames)

        // The initial departure heading now targets TTAPS (a real turn off the runway),
        // not the runway-end marker that lay straight down runway 15L (≈150° → the
        // spurious "fly runway heading").
        val threshold = Coordinate(29.9879, -95.3579)
        val fix = plan.initialDepartureFix(emptyList(), threshold)
        assertEquals("TTAPS", fix?.name)
        val hdg = Geo.bearing(threshold, fix!!.coordinate!!)
        assertTrue(
            Geo.headingDifference(hdg, 150.0) > 10,
            "bearing to TTAPS should be a real heading off runway 150, got $hdg",
        )
    }

    /**
     * The `DPT RW…` marker stays out of the route — but its *position* is kept, because it
     * sits at the runway end and that is where the departure leg is actually flown from. The
     * initial departure heading is measured from there rather than from the field reference
     * or from wherever the aircraft happens to be holding short: at a hub those are a mile or
     * more apart, which against a fix a few miles out is worth ~10° of bearing — more than
     * the whole magnetic-variation correction the same heading gets.
     */
    @Test
    fun departureRunwayMarkerKeepsItsPositionAsTheDepartureOrigin() {
        val json = """
        {
          "flightPlanItems": [
            { "name": "KIAH", "type": 0, "children": [],
              "location": { "Latitude": 29.9854, "Longitude": -95.3412 } },
            { "name": "DPT RW15L", "type": 0, "children": [],
              "location": { "Latitude": 29.9588, "Longitude": -95.3401 } },
            { "name": "TTAPS", "type": 0, "children": [],
              "location": { "Latitude": 29.9200, "Longitude": -95.2600 } },
            { "name": "KMIA", "type": 0, "children": [],
              "location": { "Latitude": 25.7938, "Longitude": -80.2870 } }
          ]
        }
        """.trimIndent()
        val plan = IFFlightPlanParser.parse(json) ?: fail("expected a parsed plan")
        // Still not a fix.
        assertFalse(plan.waypoints.any { it.name.contains("RW15L") })
        // But its position is on the plan.
        val origin = plan.departureRunwayCoordinate ?: fail("expected the departure runway's coordinate")
        assertEquals(29.9588, origin.latitude, 0.0001)
        assertEquals(-95.3401, origin.longitude, 0.0001)

        // And it is a materially different origin from the field reference — the whole reason
        // for keeping it. Measured to the same fix, the two bearings disagree by more than the
        // 10° that decides "fly runway heading" vs a vector.
        val field = Coordinate(29.9854, -95.3412)
        val fix = plan.initialDepartureFix(emptyList(), origin)
        assertEquals("TTAPS", fix?.name)
        val fromRunway = Geo.bearing(origin, fix!!.coordinate!!)
        val fromField = Geo.bearing(field, fix.coordinate!!)
        assertTrue(
            Geo.headingDifference(fromRunway, fromField) > 10,
            "the origin has to matter, or there is nothing to fix here",
        )
    }

    /**
     * No compound marker, but the plan still names the runway as a bare located token near
     * the start of the route — the same position, so it serves as the same origin. The
     * arrival runway token at the far end must not be mistaken for it.
     */
    @Test
    fun bareDepartureRunwayTokenAlsoSuppliesTheOrigin() {
        val json = """
        {
          "flightPlanItems": [
            { "name": "KIAH", "type": 0, "children": [],
              "location": { "Latitude": 29.9854, "Longitude": -95.3412 } },
            { "name": "RW15L", "type": 0, "children": [],
              "location": { "Latitude": 29.9588, "Longitude": -95.3401 } },
            { "name": "TTAPS", "type": 0, "children": [],
              "location": { "Latitude": 29.8884, "Longitude": -95.2389 } },
            { "name": "RW09R", "type": 0, "children": [],
              "location": { "Latitude": 25.7959, "Longitude": -80.2903 } },
            { "name": "KMIA", "type": 0, "children": [],
              "location": { "Latitude": 25.7938, "Longitude": -80.2870 } }
          ]
        }
        """.trimIndent()
        val plan = IFFlightPlanParser.parse(json) ?: fail("expected a parsed plan")
        assertEquals("15L", plan.departureRunway)
        assertEquals("09R", plan.arrivalRunway)
        assertEquals(
            29.9588,
            plan.departureRunwayCoordinate?.latitude ?: 0.0,
            0.0001,
            "the departure token's position, not the arrival token's",
        )
    }

    /**
     * A route *string* carries no coordinates at all, so there is no origin to recover from
     * one — the ident still is, and the departure heading falls back to live telemetry.
     */
    @Test
    fun routeStringYieldsNoDepartureRunwayCoordinate() {
        val plan = IFFlightPlanParser.parse("KEWR RW22R MERIT NEION 01R KBOS")
        assertEquals("22R", plan?.departureRunway)
        assertNull(plan?.departureRunwayCoordinate)
    }

    /**
     * A pilot may file an intermediate "buffer" fix between the runway and the SID so
     * the autopilot doesn't turn the instant it activates at rotation. The initial
     * departure heading must still target the SID's own first fix (matched by name via
     * the recovered SID structure), not the buffer fix filed ahead of it.
     */
    @Test
    fun initialDepartureFixTargetsSIDFixPastAnIntermediateBufferFix() {
        val json = """
        {
          "flightPlanItems": [
            { "name": "KIAH", "type": 0, "children": [],
              "location": { "Latitude": 29.9854, "Longitude": -95.3412 } },
            { "name": "RW15L", "type": 0, "children": [],
              "location": { "Latitude": 29.9879, "Longitude": -95.3579 } },
            { "name": "DPT RW15L", "type": 0, "children": [],
              "location": { "Latitude": 29.9588, "Longitude": -95.3401 } },
            { "name": "BUF01", "type": 0, "children": [],
              "location": { "Latitude": 29.9300, "Longitude": -95.3100 } },
            { "name": "MMUGS4", "type": 0, "identifier": "MMUGS4", "children": [
                { "name": "TTAPS", "type": 0, "children": [],
                  "location": { "Latitude": 29.8884, "Longitude": -95.2389 } },
                { "name": "BOTLL", "type": 0, "children": [],
                  "location": { "Latitude": 29.8236, "Longitude": -95.1350 } } ] },
            { "name": "KMIA", "type": 0, "children": [],
              "location": { "Latitude": 25.7938, "Longitude": -80.2870 } }
          ]
        }
        """.trimIndent()
        val plan = IFFlightPlanParser.parse(json) ?: fail("expected a parsed plan")
        // The buffer fix is a real waypoint that sits ahead of the SID in route order …
        assertEquals("BUF01", plan.waypoints.first().name)
        assertEquals(listOf("TTAPS", "BOTLL"), plan.sidFixNames)
        // … yet the initial departure fix is the SID's own first fix, TTAPS — matched by
        // name from the SID structure, not by "the next fix after the runway".
        val threshold = Coordinate(29.9879, -95.3579)
        val fix = plan.initialDepartureFix(emptyList(), threshold)
        assertEquals("TTAPS", fix?.name)
    }

    // endregion
}
