import XCTest
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

    func testPseudoWaypointDetection() {
        for marker in ["DPT", "TOC", "TOD", "T/C", "T/D", "DEP", "DEST"] {
            XCTAssertTrue(IFFlightPlanParser.isPseudoWaypoint(marker), "\(marker) should be pseudo")
        }
        XCTAssertFalse(IFFlightPlanParser.isPseudoWaypoint("SBJ"))
        XCTAssertFalse(IFFlightPlanParser.isPseudoWaypoint("LRP"))
    }
}
