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

    func testThreeLetterCodeResolvesToUSAirport() {
        XCTAssertFalse(db.runways(for: "LAX").isEmpty)
        XCTAssertEqual(db.activeRunway(for: "LAX", windDirection: 250, windSpeed: 12),
                       db.activeRunway(for: "KLAX", windDirection: 250, windSpeed: 12))
    }
}
