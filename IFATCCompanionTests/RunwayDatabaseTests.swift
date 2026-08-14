import XCTest
@testable import IFATCCompanion

final class RunwayDatabaseTests: XCTestCase {

    let db = RunwayDatabase.shared

    func testNewarkHasRealRunwaysOnly() {
        let rwys = Set(db.runways(for: "KEWR"))
        // Newark's real runways: 4L/22R, 4R/22L, 11/29. No "14".
        XCTAssertTrue(rwys.contains("22R"))
        XCTAssertTrue(rwys.contains("4L"))
        XCTAssertFalse(rwys.contains("14"), "Newark has no runway 14")
    }

    func testNewarkPicksRunway22ForSoutherlyWind() {
        // Wind from ~220° favours the 22s (the field's typical active config).
        XCTAssertEqual(db.activeRunway(for: "KEWR", windDirection: 220, windSpeed: 12), "22R")
        XCTAssertEqual(db.activeRunway(for: "EWR", windDirection: 200, windSpeed: 10), "22R")
    }

    func testNewarkPicksRunway4ForNortheastWind() {
        XCTAssertEqual(db.activeRunway(for: "KEWR", windDirection: 40, windSpeed: 12), "4L")
    }

    func testActiveRunwayIsAlwaysAValidRunway() {
        for icao in ["KEWR", "KJFK", "KLAX", "KORD", "KATL", "KDEN", "KSFO"] {
            for wind in stride(from: 0, through: 350, by: 10) {
                let active = db.activeRunway(for: icao, windDirection: wind, windSpeed: 10)
                XCTAssertNotNil(active)
                XCTAssertTrue(db.runways(for: icao).contains(active ?? ""),
                              "\(icao) returned \(active ?? "nil") which is not a real runway")
            }
        }
    }

    func testCalmWindKeepsPrimaryRunway() {
        let primary = db.runways(for: "KEWR").first
        XCTAssertEqual(db.activeRunway(for: "KEWR", windDirection: 220, windSpeed: 2), primary)
        XCTAssertEqual(db.activeRunway(for: "KEWR", windDirection: 0, windSpeed: 0), primary)
    }

    func testUnknownAirportReturnsNil() {
        XCTAssertNil(db.activeRunway(for: "ZZZZ", windDirection: 180, windSpeed: 10))
        XCTAssertTrue(db.runways(for: "ZZZZ").isEmpty)
    }

    /// A field the curated table doesn't cover can still be picked from its own runways —
    /// the ones parsed off its airport surface. KMCO's four parallels are the case that
    /// exposed this: absent from the table, so the active runway used to be a number derived
    /// from the wind, which the takeoff clearance then read back as a heading.
    func testPicksAmongASuppliedRunwayList() {
        let kmco = ["17L", "35R", "17R", "35L", "18L", "36R", "18R", "36L"]
        XCTAssertEqual(db.activeRunway(among: kmco, windDirection: 170, windSpeed: 12), "17L")
        XCTAssertEqual(db.activeRunway(among: kmco, windDirection: 350, windSpeed: 12), "35R")
        // Calm wind keeps list order rather than chasing noise.
        XCTAssertEqual(db.activeRunway(among: kmco, windDirection: 350, windSpeed: 2), "17L")
        // Nothing to pick from is still nil, so the caller keeps its own last resort.
        XCTAssertNil(db.activeRunway(among: [], windDirection: 180, windSpeed: 10))
    }

    func testThreeLetterCodeResolvesToUSAirport() {
        XCTAssertFalse(db.runways(for: "LAX").isEmpty)
        XCTAssertEqual(db.activeRunway(for: "LAX", windDirection: 250, windSpeed: 12),
                       db.activeRunway(for: "KLAX", windDirection: 250, windSpeed: 12))
    }
}
