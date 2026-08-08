import XCTest
import CoreLocation
@testable import IFATCCompanion

/// Covers the Infinite Flight Connect flight-plan parser, with emphasis on the
/// detailed JSON document (PascalCase keys, nested SID/STAR/approach groups, and a
/// side-by-side simplified `Waypoints` summary list) — the shape that previously
/// collapsed a 20+ fix route into a 5-fix summary with no procedures or coordinates.
final class FlightPlanParserTests: XCTestCase {

    /// A KTEB→KPHL document mirroring IF's structure: a detailed `FlightPlanItems`
    /// array under `DetailedInfo` (airports, a SID group, enroute VORs, a STAR group,
    /// an approach group) AND a top-level simplified `Waypoints` string list that
    /// includes the DPT/TOC/TOD display markers.
    private let detailedJSON = """
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
    """

    func testDetailedJSONPrefersFullRouteOverSummary() {
        guard let plan = IFFlightPlanParser.parse(detailedJSON) else {
            return XCTFail("expected a parsed plan")
        }
        XCTAssertEqual(plan.departure, "KTEB")
        XCTAssertEqual(plan.destination, "KPHL")

        // All enroute fixes from the detailed items — not the 5-fix Waypoints summary.
        let names = plan.waypoints.map(\.name)
        XCTAssertEqual(names, ["WHITE", "SBJ", "ARD", "LRP", "MXE", "PESKS"])

        // Every fix carries a coordinate, so the route can draw on the map.
        XCTAssertTrue(plan.waypoints.allSatisfy { $0.coordinate != nil })

        // The departure/destination airport coordinates survive on the plan (they are
        // not enroute waypoints), so the markers land on the real fields.
        XCTAssertEqual(plan.departureCoordinate?.latitude ?? 0, 40.8501, accuracy: 0.0001)
        XCTAssertEqual(plan.destinationCoordinate?.latitude ?? 0, 39.8719, accuracy: 0.0001)

        // The DPT/TOC/TOD display markers from the summary list never appear.
        XCTAssertFalse(names.contains("DPT"))
        XCTAssertFalse(names.contains("TOC"))
        XCTAssertFalse(names.contains("TOD"))
    }

    func testDetailedJSONClassifiesProcedures() {
        let plan = IFFlightPlanParser.parse(detailedJSON)
        XCTAssertEqual(plan?.sid, "RUUDY6")
        XCTAssertEqual(plan?.star, "VINNY1")
        XCTAssertEqual(plan?.approach, "ILS 27R")
        // First altitude in the approach section becomes the intercept altitude.
        XCTAssertEqual(plan?.approachInterceptAltitude, 3000)
    }

    func testDetailedJSONTagsFirstApproachFix() {
        let plan = IFFlightPlanParser.parse(detailedJSON)
        // The first fix of the approach section — the deepest a weather deviation may
        // rejoin the route (never past it toward the destination).
        XCTAssertEqual(plan?.approachStartFixName, "PESKS")
        XCTAssertEqual(plan?.approachStartCoordinate?.latitude ?? 0, 39.92, accuracy: 0.001)
        XCTAssertEqual(plan?.approachStartCoordinate?.longitude ?? 0, -75.40, accuracy: 0.001)
    }

    func testSimplifiedWaypointsListFallbackDropsPseudoFixes() {
        // When only the simplified string list is present, pseudo markers are still
        // stripped (leaving the real fixes), rather than shown as waypoints.
        let json = #"{ "Waypoints": ["KTEB", "DPT", "SBJ", "TOC", "LRP", "TOD", "KPHL"] }"#
        let plan = IFFlightPlanParser.parse(json)
        XCTAssertEqual(plan?.departure, "KTEB")
        XCTAssertEqual(plan?.destination, "KPHL")
        XCTAssertEqual(plan?.waypoints.map(\.name), ["SBJ", "LRP"])
    }

    func testRouteStringStillParses() {
        let plan = IFFlightPlanParser.parse("KIAH SBJ LRP FL370 KMSP")
        XCTAssertEqual(plan?.departure, "KIAH")
        XCTAssertEqual(plan?.destination, "KMSP")
        XCTAssertEqual(plan?.waypoints.map(\.name), ["SBJ", "LRP"])
        XCTAssertEqual(plan?.cruiseAltitude, 37000)
    }

    /// A KIAH→KATL document in the exact shape Infinite Flight sends on device: camelCase
    /// keys, the item array under `detailedInfo`, procedure groups tagged with the `type`
    /// enum (Sid=0/STAR=1/Approach=2) while plain fixes also carry `type: 0`, a compound
    /// `DPT RW15L` marker, and an approach whose transition repeats two of its own fixes.
    /// Mirrors a reported flight where the I09R approach fixes were missing from the app.
    private let kiahKatlJSON = """
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
    """

    /// The approach group's fixes are enroute waypoints like any other — the route must
    /// not stop at the STAR's last fix (QUBIT) with the I09R fixes dropped.
    func testApproachGroupFixesAreKeptAsWaypoints() {
        guard let plan = IFFlightPlanParser.parse(kiahKatlJSON) else {
            return XCTFail("expected a parsed plan")
        }
        XCTAssertEqual(plan.departure, "KIAH")
        XCTAssertEqual(plan.destination, "KATL")
        // Repeated transition fixes collapse to one entry each; the runway/marker tokens
        // (RW15L, DPT RW15L, TOC, TOD, RW09R) never appear.
        XCTAssertEqual(plan.waypoints.map(\.name),
                       ["TTAPS", "BOTLL", "LCH", "JNGLE", "QUBIT",
                        "DFINS", "GGUYY", "EEASY", "BURNY"])
        XCTAssertTrue(plan.waypoints.allSatisfy { $0.coordinate != nil })
        XCTAssertEqual(plan.sid, "MMUGS4")
        XCTAssertEqual(plan.star, "GNDLF3")
        XCTAssertEqual(plan.approach, "I09R")
        XCTAssertEqual(plan.approachStartFixName, "DFINS")
        XCTAssertEqual(plan.approachInterceptAltitude, 4000)
        XCTAssertEqual(plan.departureRunway, "15L")
        XCTAssertEqual(plan.arrivalRunway, "09R")
        // The TOC/TOD markers are dropped as fixes but still set the cruise level.
        XCTAssertEqual(plan.cruiseAltitude, 35000)
    }

    // MARK: - Multi-state combining (full + route + coordinates)

    /// When `aircraft/0/flightplan` collapses the route to a sparse summary, the
    /// textual `flightplan/route` state's longer fix list is preferred — this is the
    /// real-device case where the summary yielded only SBJ→LRP.
    func testRouteStringEnrichesSparseSummary() {
        let full = #"{ "Waypoints": ["KTEB", "DPT", "SBJ", "TOC", "LRP", "TOD", "KPHL"] }"#
        let route = "KTEB SBJ WHITE ARD LRP MXE KPHL"
        let plan = IFFlightPlanParser.parse(full: full, route: route, coordinates: nil)
        XCTAssertEqual(plan?.departure, "KTEB")
        XCTAssertEqual(plan?.destination, "KPHL")
        XCTAssertEqual(plan?.waypoints.map(\.name), ["SBJ", "WHITE", "ARD", "LRP", "MXE"])
    }

    /// A richer `full` payload is not discarded just because a route state exists:
    /// the route only wins when it recovers *more* fixes.
    func testRicherFullPayloadIsNotReplacedByShorterRoute() {
        let route = "KTEB SBJ KPHL"   // only one enroute fix
        let plan = IFFlightPlanParser.parse(full: detailedJSON, route: route, coordinates: nil)
        XCTAssertEqual(plan?.waypoints.map(\.name), ["WHITE", "SBJ", "ARD", "LRP", "MXE", "PESKS"])
        XCTAssertEqual(plan?.sid, "RUUDY6")
    }

    // MARK: - Route/coordinate alignment (the reported missing-approach-fixes case)

    /// The real KIAH→KATL states: `route` is comma-separated and 1-for-1 with the
    /// space-separated `coordinates` pairs — 34 entries each, including the compound
    /// `DPT RW15L` marker (one entry containing a space), the TOC/TOD markers, the runway
    /// tokens at both ends, and the I09R transition repeating GGUYY/EEASY.
    private let katlRoute = """
    KIAH,RW15L,DPT RW15L,TTAPS,BOTLL,MMUGS,MMALT,HOURN,BLING,TOC,LCH,LSU,IRUBE,PAYTN,SHYRE,\
    FRDDO,BLLBO,TOD,BGGNS,SMAWG,GNDLF,HALRR,SHULR,JNGLE,QUBIT,DFINS,GGUYY,EEASY,GGUYY,EEASY,\
    BURNY,RW09R,RW09R,KATL
    """
    private let katlCoordinates = """
    29.98544331,-95.34119568 29.98787689,-95.35786438 29.95878792,-95.34007263 \
    29.88835639,-95.23890306 29.82362611,-95.13500000 29.81751500,-94.98362889 \
    29.87002083,-94.78361139 29.99585444,-94.30445222 30.03056000,-94.01807639 \
    30.11686300,-93.31316000 30.14140139,-93.10555694 30.48501333,-91.29390667 \
    31.00419306,-88.93835056 31.46778750,-87.88530306 31.52584528,-87.79002056 \
    32.32167583,-86.44697056 32.42861611,-86.26361361 32.51327300,-86.11697200 \
    32.57529972,-86.00862500 33.02362583,-85.22194556 33.11750500,-85.07084306 \
    33.15807500,-85.00472556 33.24030139,-84.87141333 33.29974556,-84.82974000 \
    33.45252639,-84.82973972 33.63111778,-84.79363861 33.63138972,-84.71863361 \
    33.63140611,-84.64972694 33.63138972,-84.71863361 33.63140611,-84.64972694 \
    33.63167278,-84.54919111 33.63182068,-84.44801331 33.63182068,-84.44801331 \
    33.63662987,-84.42801819
    """

    /// The route and coordinate states pair by index, so every fix the route names gets its
    /// true position — including the ones after the STAR. Splitting the route on whitespace
    /// would break the alignment at `DPT RW15L` and shift every position after it.
    func testAlignedRouteLocatesEveryFix() {
        let fixes = IFFlightPlanParser.parseAlignedRoute(route: katlRoute,
                                                         coordinates: katlCoordinates)
        XCTAssertEqual(fixes.count, 24)
        XCTAssertEqual(fixes.first?.name, "TTAPS")
        XCTAssertEqual(fixes.last?.name, "BURNY")
        XCTAssertTrue(fixes.allSatisfy { $0.coordinate != nil })
        // TTAPS is entry 3 — off by one if `DPT RW15L` had been split into two tokens.
        XCTAssertEqual(fixes.first?.coordinate?.latitude ?? 0, 29.88835639, accuracy: 1e-6)
        XCTAssertEqual(fixes.first?.coordinate?.longitude ?? 0, -95.23890306, accuracy: 1e-6)
        // BURNY is entry 30, past the repeated GGUYY/EEASY transition fixes.
        XCTAssertEqual(fixes.last?.coordinate?.latitude ?? 0, 33.63167278, accuracy: 1e-6)
        XCTAssertEqual(fixes.last?.coordinate?.longitude ?? 0, -84.54919111, accuracy: 1e-6)
    }

    /// Mismatched states are refused rather than paired off-by-one.
    func testAlignedRouteRefusesMismatchedCoordinates() {
        XCTAssertTrue(IFFlightPlanParser.parseAlignedRoute(route: katlRoute,
                                                           coordinates: "33.6, -84.4").isEmpty)
        XCTAssertTrue(IFFlightPlanParser.parseAlignedRoute(route: katlRoute,
                                                           coordinates: nil).isEmpty)
    }

    /// The reported failure: the detailed document carries the enroute and STAR fixes but
    /// nothing for the approach, so its plan stops at the STAR's last fix (QUBIT). The route
    /// state names all of them, so its longer list must win — a shorter list is no longer
    /// preferred just because it happens to carry coordinates.
    func testDetailedPlanMissingApproachFixesIsCompletedFromRoute() {
        let fullInfo = """
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
        """
        guard let plan = IFFlightPlanParser.parse(fullInfo: fullInfo, full: nil,
                                                  route: katlRoute,
                                                  coordinates: katlCoordinates) else {
            return XCTFail("expected a parsed plan")
        }
        XCTAssertEqual(plan.waypoints.count, 24)
        XCTAssertEqual(plan.waypoints.suffix(5).map(\.name),
                       ["QUBIT", "DFINS", "GGUYY", "EEASY", "BURNY"])
        XCTAssertTrue(plan.waypoints.allSatisfy { $0.coordinate != nil })
        XCTAssertEqual(plan.waypoints.last?.coordinate?.longitude ?? 0, -84.54919111, accuracy: 1e-6)
        // What the detailed document *did* carry survives: the procedure name…
        XCTAssertEqual(plan.star, "GNDLF3")
        // …and the planned altitudes for the fixes both lists share.
        XCTAssertEqual(plan.waypoints.first { $0.name == "QUBIT" }?.altitude, 8000)
        XCTAssertEqual(plan.waypoints.first { $0.name == "JNGLE" }?.altitude, 11000)
        XCTAssertEqual(plan.departure, "KIAH")
        XCTAssertEqual(plan.destination, "KATL")
    }

    /// Coordinates are attached to fixes when the parsed pair count matches.
    func testCoordinatesAttachedWhenCountMatches() {
        let full = #"{ "Waypoints": ["KTEB", "SBJ", "LRP", "KPHL"] }"#
        let coords = "40.58, -74.73; 40.12, -76.29"   // two enroute fixes
        let plan = IFFlightPlanParser.parse(full: full, route: nil, coordinates: coords)
        XCTAssertEqual(plan?.waypoints.count, 2)
        XCTAssertTrue(plan?.waypoints.allSatisfy { $0.coordinate != nil } ?? false)
    }

    /// A mismatched coordinate list is ignored rather than scattering the route.
    func testMismatchedCoordinatesIgnored() {
        let full = #"{ "Waypoints": ["KTEB", "SBJ", "LRP", "KPHL"] }"#
        let coords = "40.58, -74.73"   // only one pair for two fixes
        let plan = IFFlightPlanParser.parse(full: full, route: nil, coordinates: coords)
        XCTAssertEqual(plan?.waypoints.count, 2)
        XCTAssertTrue(plan?.waypoints.allSatisfy { $0.coordinate == nil } ?? false)
    }

    /// A flat coordinate list that carries the departure/destination airports as its
    /// first and last entries (two more than the enroute fixes) is mapped correctly:
    /// the endpoints land on the plan's departure/destination coordinates and the
    /// middle coordinates onto the fixes — so the route draws to both fields, not
    /// short of them. Regression for the "route shrunk to the enroute fixes" bug.
    func testCoordinateListWithEndpointsMapsToAirportsAndFixes() {
        let full = #"{ "Waypoints": ["SBGL", "KOKPI", "GAPE", "SBPS"] }"#
        let coords = "-22.8089,-43.2438;-22.637,-42.690;-21.927,-41.470;-16.4385,-39.0810"
        guard let plan = IFFlightPlanParser.parse(full: full, route: nil, coordinates: coords) else {
            return XCTFail("expected a parsed plan")
        }
        XCTAssertEqual(plan.departure, "SBGL")
        XCTAssertEqual(plan.destination, "SBPS")
        XCTAssertEqual(plan.waypoints.map(\.name), ["KOKPI", "GAPE"])
        XCTAssertEqual(plan.departureCoordinate?.latitude ?? 0, -22.8089, accuracy: 0.001)
        XCTAssertEqual(plan.destinationCoordinate?.longitude ?? 0, -39.0810, accuracy: 0.001)
        XCTAssertEqual(plan.waypoints.first?.coordinate?.latitude ?? 0, -22.637, accuracy: 0.001)
        XCTAssertEqual(plan.waypoints.last?.coordinate?.longitude ?? 0, -41.470, accuracy: 0.001)
    }

    /// A southern-hemisphere detailed document keeps the departure and destination
    /// airport coordinates on the plan (they are dropped from the enroute waypoint
    /// list, but their position must survive so the markers land on the real field
    /// when it is outside the built-in US airport database). Regression for the
    /// Southern-Hemisphere map bug: SBGL→SBPS drew the destination at the last enroute
    /// fix (VAMUR), well short of the coast, because the airport coordinate was lost.
    func testDetailedJSONKeepsEndpointCoordinates() {
        let json = """
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
        """
        guard let plan = IFFlightPlanParser.parse(json) else {
            return XCTFail("expected a parsed plan")
        }
        XCTAssertEqual(plan.departure, "SBGL")
        XCTAssertEqual(plan.destination, "SBPS")
        XCTAssertEqual(plan.waypoints.map(\.name), ["KOKPI", "VAMUR"])
        // The destination marker must resolve to SBPS on the coast, not to VAMUR.
        XCTAssertEqual(plan.departureCoordinate?.latitude ?? 0, -22.808890, accuracy: 0.0001)
        XCTAssertEqual(plan.departureCoordinate?.longitude ?? 0, -43.243754, accuracy: 0.0001)
        XCTAssertEqual(plan.destinationCoordinate?.latitude ?? 0, -16.438536, accuracy: 0.0001)
        XCTAssertEqual(plan.destinationCoordinate?.longitude ?? 0, -39.080952, accuracy: 0.0001)
    }

    // MARK: - Detailed `flightplan/full_info` document

    /// The rich document Infinite Flight serves at `aircraft/0/flightplan/full_info`:
    /// camelCase keys, per-fix planned `altitude`, and procedure groups tagged with an
    /// explicit `type` (Sid=0, STAR=1, Approach=2). This is the only state that carries
    /// the cruise altitude and the published procedure names.
    private let fullInfoJSON = """
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
    """

    /// full_info supplies the cruise altitude (highest planned level), per-fix
    /// altitudes, and the SID/STAR/approach names — and is preferred over both the
    /// collapsed summary and the route string when present.
    func testFullInfoProvidesAltitudesAndProcedures() {
        let summary = #"{ "Waypoints": ["KTEB","DPT","SBJ","TOC","LRP","TOD","KPHL"] }"#
        let route = "KTEB WHITE SBJ LRP MXE PETER KPHL"
        guard let plan = IFFlightPlanParser.parse(fullInfo: fullInfoJSON, full: summary,
                                                  route: route, coordinates: nil) else {
            return XCTFail("expected a parsed plan")
        }
        XCTAssertEqual(plan.departure, "KTEB")
        XCTAssertEqual(plan.destination, "KPHL")
        XCTAssertEqual(plan.waypoints.map(\.name), ["WHITE", "SBJ", "LRP", "MXE", "PETER"])

        // Cruise altitude = highest planned per-fix altitude.
        XCTAssertEqual(plan.cruiseAltitude, 37000)

        // Procedures classified from the explicit `type` enum.
        XCTAssertEqual(plan.sid, "RUUDY6")
        XCTAssertEqual(plan.star, "VINNY1")
        XCTAssertEqual(plan.approach, "ILS 27R")
        XCTAssertEqual(plan.approachInterceptAltitude, 3000)

        // Per-fix planned altitudes are preserved on each waypoint.
        let alt = Dictionary(plan.waypoints.map { ($0.name, $0.altitude) },
                             uniquingKeysWith: { a, _ in a })
        XCTAssertEqual(alt["WHITE"] ?? nil, 4000)
        XCTAssertEqual(alt["SBJ"] ?? nil, 11000)
        XCTAssertEqual(alt["LRP"] ?? nil, 37000)
        XCTAssertEqual(alt["PETER"] ?? nil, 3000)
    }

    /// The explicit procedure `type` is authoritative — it overrides the name/position
    /// heuristics. A first procedure tagged STAR is the STAR (not the SID), and a
    /// keyword-less name tagged Approach is the approach.
    func testProcedureTypeOverridesNameHeuristic() {
        let json = """
        { "flightPlanItems": [
            { "identifier": "EGLL", "children": null, "location": {"latitude":51.47,"longitude":-0.46} },
            { "name": "LOGAN1", "type": 1, "children": [
                { "identifier": "LOGAN", "children": null, "location": {"latitude":51.0,"longitude":-0.5} } ] },
            { "name": "FINALX", "type": 2, "children": [
                { "identifier": "DET", "altitude": 2000, "children": null, "location": {"latitude":51.3,"longitude":0.6} } ] },
            { "identifier": "EGKK", "children": null, "location": {"latitude":51.15,"longitude":-0.19} }
        ] }
        """
        let plan = IFFlightPlanParser.parse(json)
        XCTAssertEqual(plan?.star, "LOGAN1")        // type 1, despite being the first procedure
        XCTAssertTrue(plan?.sid.isEmpty ?? false)   // …so it is not mistaken for a SID
        XCTAssertEqual(plan?.approach, "FINALX")    // type 2, despite no approach keyword
        XCTAssertEqual(plan?.approachInterceptAltitude, 2000)
    }

    func testParseCoordinateList() {
        let pairs = IFFlightPlanParser.parseCoordinateList("40.58, -74.73; 40.12, -76.29")
        XCTAssertEqual(pairs.count, 2)
        XCTAssertEqual(pairs[0].lat, 40.58, accuracy: 0.0001)
        XCTAssertEqual(pairs[0].lon, -74.73, accuracy: 0.0001)
        XCTAssertEqual(pairs[1].lon, -76.29, accuracy: 0.0001)
    }

    func testCombiningAllNilReturnsNil() {
        XCTAssertNil(IFFlightPlanParser.parse(full: nil, route: nil, coordinates: nil))
    }

    /// The cruise altitude is the final cruise level even when only the TOC/TOD
    /// display marker carries it — not the highest *enroute fix* altitude (which is
    /// the climbing level reached just before the top of climb).
    func testCruiseAltitudeComesFromTOCMarkerNotLastClimbFix() {
        let json = """
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
        """
        let plan = IFFlightPlanParser.parse(json)
        XCTAssertEqual(plan?.cruiseAltitude, 28000, "cruise should be the TOC level, not FL278")
        // …and the TOC marker is still never shown as a waypoint.
        XCTAssertFalse(plan?.waypoints.map(\.name).contains("TOC") ?? true)
    }

    /// The departure runway (`DPT RW22R`) and an arrival runway token are recovered
    /// from the route and never shown as enroute fixes.
    func testRouteStringRecoversDepartureAndArrivalRunways() {
        let plan = IFFlightPlanParser.parse("KEWR RW22R MERIT NEION 01R KBOS")
        XCTAssertEqual(plan?.departureRunway, "22R")
        XCTAssertEqual(plan?.arrivalRunway, "01R")
        XCTAssertEqual(plan?.waypoints.map(\.name), ["MERIT", "NEION"])
    }

    /// A lone departure runway token near the start is recorded as the departure
    /// runway (not the arrival), and stripped from the fixes.
    func testRouteStringRecoversDepartureRunwayOnly() {
        let plan = IFFlightPlanParser.parse("KEWR RW22R MERIT NEION KBOS")
        XCTAssertEqual(plan?.departureRunway, "22R")
        XCTAssertTrue(plan?.arrivalRunway.isEmpty ?? false)
        XCTAssertEqual(plan?.waypoints.map(\.name), ["MERIT", "NEION"])
    }

    func testRunwayIdentNormalisation() {
        XCTAssertEqual(IFFlightPlanParser.runwayIdent(from: "RW22R"), "22R")
        XCTAssertEqual(IFFlightPlanParser.runwayIdent(from: "RWY09"), "09")
        XCTAssertEqual(IFFlightPlanParser.runwayIdent(from: "30L"), "30L")
        XCTAssertNil(IFFlightPlanParser.runwayIdent(from: "MERIT"))
        XCTAssertNil(IFFlightPlanParser.runwayIdent(from: "FL370"))
    }

    func testPseudoWaypointDetection() {
        for marker in ["DPT", "TOC", "TOD", "T/C", "T/D", "DEP", "DEST"] {
            XCTAssertTrue(IFFlightPlanParser.isPseudoWaypoint(marker), "\(marker) should be pseudo")
        }
        // Compound departure/arrival markers (marker word + runway), the form Infinite
        // Flight emits as a single identifier in the detailed JSON.
        for marker in ["DPT RW15L", "DEP RW09", "ARR RW09", "DEPARTURE RW04L"] {
            XCTAssertTrue(IFFlightPlanParser.isPseudoWaypoint(marker), "\(marker) should be pseudo")
        }
        XCTAssertFalse(IFFlightPlanParser.isPseudoWaypoint("SBJ"))
        XCTAssertFalse(IFFlightPlanParser.isPseudoWaypoint("LRP"))
        // A real fix whose first token merely resembles a marker word is not dropped —
        // only "<marker> <runway>" is a marker.
        XCTAssertFalse(IFFlightPlanParser.isPseudoWaypoint("DPT ABCDE"))
    }

    /// Infinite Flight's detailed JSON carries a "DPT RW__" marker at the departure end
    /// of the runway as a single identifier (unlike the route string, where the space
    /// splits it apart). It is a non-navigational display marker, not a fix. Left in, it
    /// becomes the first waypoint and — sitting straight down the runway from the
    /// aircraft — forces the takeoff clearance to "fly runway heading" on every flight.
    /// Regression for the reported KIAH / MMUGS4 departure.
    func testDetailedJSONDropsCompoundDepartureRunwayMarker() {
        let json = """
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
        """
        guard let plan = IFFlightPlanParser.parse(json) else {
            return XCTFail("expected a parsed plan")
        }
        XCTAssertEqual(plan.departure, "KIAH")
        XCTAssertEqual(plan.destination, "KMIA")
        // Both the "DPT RW15L" runway-end marker and the bare "RW15L" runway are dropped.
        XCTAssertFalse(plan.waypoints.contains { $0.name == "DPT RW15L" })
        XCTAssertFalse(plan.waypoints.contains { $0.name == "RW15L" })
        // The first waypoint is the SID's first published fix, not the runway end.
        XCTAssertEqual(plan.waypoints.first?.name, "TTAPS")
        XCTAssertEqual(plan.sid, "MMUGS4")
        // The SID's own fix structure is recovered from the procedure group.
        XCTAssertEqual(plan.sidFixNames, ["TTAPS", "BOTLL"])

        // The initial departure heading now targets TTAPS (a real turn off the runway),
        // not the runway-end marker that lay straight down runway 15L (≈150° → the
        // spurious "fly runway heading").
        let threshold = CLLocationCoordinate2D(latitude: 29.9879, longitude: -95.3579)
        let fix = plan.initialDepartureFix(sidFixes: [], origin: threshold)
        XCTAssertEqual(fix?.name, "TTAPS")
        let hdg = Geo.bearing(from: threshold, to: fix!.coordinate!)
        XCTAssertGreaterThan(PhraseologyEngine.angularDiff(hdg, 150), 10,
                             "bearing to TTAPS should be a real heading off runway 150, got \(hdg)")
    }

    /// A pilot may file an intermediate "buffer" fix between the runway and the SID so
    /// the autopilot doesn't turn the instant it activates at rotation. The initial
    /// departure heading must still target the SID's own first fix (matched by name via
    /// the recovered SID structure), not the buffer fix filed ahead of it.
    func testInitialDepartureFixTargetsSIDFixPastAnIntermediateBufferFix() {
        let json = """
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
        """
        guard let plan = IFFlightPlanParser.parse(json) else {
            return XCTFail("expected a parsed plan")
        }
        // The buffer fix is a real waypoint that sits ahead of the SID in route order …
        XCTAssertEqual(plan.waypoints.first?.name, "BUF01")
        XCTAssertEqual(plan.sidFixNames, ["TTAPS", "BOTLL"])
        // … yet the initial departure fix is the SID's own first fix, TTAPS — matched by
        // name from the SID structure, not by "the next fix after the runway".
        let threshold = CLLocationCoordinate2D(latitude: 29.9879, longitude: -95.3579)
        let fix = plan.initialDepartureFix(sidFixes: [], origin: threshold)
        XCTAssertEqual(fix?.name, "TTAPS")
    }
}
