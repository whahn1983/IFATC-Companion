import XCTest
@testable import IFATCCompanion

/// Tests for procedure parsing and taxi routing (ATC realism roadmap items).
final class ATCRealismTests: XCTestCase {

    // MARK: - Procedure parsing

    func testParseSIDWithRevision() {
        let p = ProcedureParser.parseSID("WAGON5")
        XCTAssertEqual(p?.name, "WAGON")
        XCTAssertEqual(p?.revision, 5)
        XCTAssertNil(p?.transition)
        XCTAssertEqual(p?.displayName, "WAGON5")
    }

    func testParseSIDWithTransition() {
        let p = ProcedureParser.parseSID("WAGON5.HOBTT")
        XCTAssertEqual(p?.transition, "HOBTT")
        XCTAssertEqual(p?.displayName, "WAGON5.HOBTT")
    }

    func testSIDEnrichmentAttachesFixes() {
        let p = ProcedureParser.parseSID("WAGON", icao: "KIAH")
        XCTAssertEqual(p?.fixes, ["WAGON", "HOBTT", "DAS"])
    }

    func testParseApproachTypeAndRunway() {
        let ils = ProcedureParser.parseApproach("ILS 30L")
        XCTAssertEqual(ils?.approachType, .ils)
        XCTAssertEqual(ils?.runway, "30L")
        XCTAssertEqual(ils?.displayName, "ILS RWY 30L")

        let rnav = ProcedureParser.parseApproach("RNAV (GPS) 27")
        XCTAssertEqual(rnav?.approachType, .rnavGPS)
        XCTAssertEqual(rnav?.runway, "27")
    }

    func testExtractRunwayIgnoresNonRunwayDigits() {
        XCTAssertEqual(ProcedureParser.extractRunway("ILS 16R"), "16R")
        XCTAssertEqual(ProcedureParser.extractRunway("VOR 09"), "09")
        XCTAssertNil(ProcedureParser.extractRunway("ILS"))
    }

    func testClearanceMentionsParsedSID() {
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        let cs = engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        let sid = ProcedureParser.parseSID("WAGON5", icao: "KIAH")
        let tx = engine.clearance(cs: cs, destination: "KMSP", cruise: 37000, sid: "WAGON5",
                                  initialAlt: 5000, departureFreq: 124.3, squawk: "4271",
                                  sidProcedure: sid)
        XCTAssertTrue(tx.displayText.contains("WAGON5 departure"))
        XCTAssertTrue(tx.spokenText.contains("Wagon five"))
    }

    func testClearedApproachUsesProcedure() {
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        let cs = engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        let app = ProcedureParser.parseApproach("RNAV (GPS) 30L")!
        let tx = engine.clearedApproach(cs: cs, procedure: app, runway: "30L")
        XCTAssertTrue(tx.displayText.contains("RNAV (GPS) RWY 30L"))
        XCTAssertTrue(tx.spokenText.contains("R NAV G P S"))
    }

    // MARK: - Taxi routing

    func testTaxiRouteForKnownAirportWithCrossing() {
        let planner = TaxiRoutePlanner()
        let plan = planner.plan(airport: "KIAH", runway: "15R", arrival: false)
        XCTAssertEqual(plan.taxiways, ["A", "C"])
        XCTAssertEqual(plan.crossingRunway, "15L")
        XCTAssertEqual(plan.parkingTaxiway, "A")
    }

    func testTaxiRouteArrivalGoesToRamp() {
        let planner = TaxiRoutePlanner()
        let plan = planner.plan(airport: "KMSP", runway: "30L", arrival: true)
        XCTAssertEqual(plan.parkingTaxiway, "A")
        XCTAssertNil(plan.crossingRunway)
    }

    func testTaxiRouteUnknownAirportIsDeterministic() {
        let planner = TaxiRoutePlanner()
        let a = planner.plan(airport: "ZZZZ", runway: "27", arrival: false)
        let b = planner.plan(airport: "ZZZZ", runway: "27", arrival: false)
        XCTAssertEqual(a, b)
        XCTAssertFalse(a.taxiways.isEmpty)
    }
}
