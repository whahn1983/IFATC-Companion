import XCTest
@testable import IFATCCompanion

/// Tests for the automatic ATC flow building blocks: flight-plan parsing,
/// runway line-up detection, departure-aware phraseology and hand-offs.
final class AutomationTests: XCTestCase {

    private func engine() -> PhraseologyEngine {
        PhraseologyEngine(digitStyle: .individual, mode: .faa)
    }

    // MARK: - IF flight plan parsing

    func testParseFlightPlanExtractsAirportsAndFixes() {
        let plan = IFFlightPlanParser.parse("KIAH WAGON HOBTT DAS KMSP")
        XCTAssertEqual(plan?.departure, "KIAH")
        XCTAssertEqual(plan?.destination, "KMSP")
        XCTAssertEqual(plan?.waypoints.map { $0.name }, ["WAGON", "HOBTT", "DAS"])
    }

    func testParseFlightPlanHandlesNewlinesAndArrows() {
        let plan = IFFlightPlanParser.parse("KSEA\nGLASR\n>BANGR\nKSFO")
        XCTAssertEqual(plan?.departure, "KSEA")
        XCTAssertEqual(plan?.destination, "KSFO")
        XCTAssertEqual(plan?.waypoints.map { $0.name }, ["GLASR", "BANGR"])
    }

    func testParseFlightPlanDedupesAndDropsPureNumbers() {
        let plan = IFFlightPlanParser.parse("KDEN AKO AKO 12000 ONL KMSP")
        XCTAssertEqual(plan?.waypoints.map { $0.name }, ["AKO", "ONL"])
    }

    func testParseEmptyFlightPlanReturnsNil() {
        XCTAssertNil(IFFlightPlanParser.parse("   "))
        XCTAssertNil(IFFlightPlanParser.parse(""))
    }

    // MARK: - Runway line-up detection

    func testLinedUpWhenAlignedAndSlowOnGround() {
        let d = RunwayLineupDetector()
        var s = AircraftState()
        s.onGround = true
        s.groundSpeed = 5
        s.heading = 172            // runway 17R -> 170°
        XCTAssertTrue(d.isLinedUp(state: s, runway: "17R"))
    }

    func testNotLinedUpWhenMisaligned() {
        let d = RunwayLineupDetector()
        var s = AircraftState()
        s.onGround = true
        s.groundSpeed = 5
        s.heading = 90
        XCTAssertFalse(d.isLinedUp(state: s, runway: "17R"))
    }

    func testNotLinedUpWhenAirborne() {
        let d = RunwayLineupDetector()
        var s = AircraftState()
        s.onGround = false
        s.groundSpeed = 5
        s.heading = 170
        XCTAssertFalse(d.isLinedUp(state: s, runway: "17"))
    }

    func testDepartingRollWhenAlignedAndFast() {
        let d = RunwayLineupDetector()
        var s = AircraftState()
        s.onGround = true
        s.groundSpeed = 80
        s.heading = 168
        XCTAssertTrue(d.isDepartingRoll(state: s, runway: "17"))
        XCTAssertFalse(d.isLinedUp(state: s, runway: "17")) // too fast for "lined up"
    }

    // MARK: - Phraseology: takeoff with departure instructions

    func testTakeoffClearanceIncludesHeadingAndClimb() {
        let e = engine()
        let cs = e.callsign(airline: "United", flightNumber: "598", fallback: "")
        let tx = e.clearedForTakeoff(cs: cs, runway: "17R", windDir: 180, windSpeed: 8,
                                     departureHeading: 90, initialAltitude: 5000)
        XCTAssertTrue(tx.displayText.contains("cleared for takeoff"))
        XCTAssertTrue(tx.displayText.contains("fly heading 090"))
        XCTAssertTrue(tx.displayText.contains("climb and maintain 5,000"))
        XCTAssertTrue(tx.spokenText.contains("one seven right"))
    }

    func testTakeoffClearanceUsesRunwayHeadingWhenAligned() {
        let e = engine()
        let cs = e.callsign(airline: "United", flightNumber: "598", fallback: "")
        // Departure heading 172 is within 10° of runway 17 (170°).
        let tx = e.clearedForTakeoff(cs: cs, runway: "17", windDir: 180, windSpeed: 8,
                                     departureHeading: 172, initialAltitude: 5000)
        XCTAssertTrue(tx.displayText.contains("fly runway heading"))
        XCTAssertFalse(tx.displayText.contains("fly heading 172"))
    }

    func testRunwayHeadingAndAngularDiff() {
        XCTAssertEqual(PhraseologyEngine.runwayHeading("17R"), 170)
        XCTAssertEqual(PhraseologyEngine.runwayHeading("09"), 90)
        XCTAssertNil(PhraseologyEngine.runwayHeading("XX"))
        XCTAssertEqual(PhraseologyEngine.angularDiff(350, 10), 20, accuracy: 0.001)
        XCTAssertEqual(PhraseologyEngine.angularDiff(170, 172), 2, accuracy: 0.001)
    }

    // MARK: - Phraseology: departure climb + hand-off

    func testDepartureClimbMentionsCeilingAndDirectFix() {
        let e = engine()
        let cs = e.callsign(airline: "United", flightNumber: "598", fallback: "")
        let tx = e.departureClimb(cs: cs, altitude: 18000, firstFix: "WAGON")
        XCTAssertTrue(tx.displayText.contains("radar contact"))
        XCTAssertTrue(tx.displayText.contains("FL180"))
        XCTAssertTrue(tx.displayText.contains("direct WAGON"))
        XCTAssertEqual(tx.facility, .departure)
    }

    func testHandoffFromToNamesNextFacility() {
        let e = engine()
        let cs = e.callsign(airline: "United", flightNumber: "598", fallback: "")
        let tx = e.handoff(cs: cs, from: .tower, to: .departure, frequency: 124.3)
        XCTAssertEqual(tx.facility, .tower)               // spoken by the facility you leave
        XCTAssertTrue(tx.displayText.contains("contact Departure"))
        XCTAssertTrue(tx.displayText.contains("124.300"))
    }

    // MARK: - State machine wiring

    func testTowerDepartureUsesDepartureHeadingWhenProvided() {
        var m = ATCStateMachine(engine: engine())
        m.setConnected()
        var ctx = TestSupport.context(runway: "17R")
        ctx.departureHeading = 90
        ctx.initialClimbAltitude = 5000
        let tx = m.advance(to: .towerDeparture, context: ctx)
        XCTAssertTrue(tx?.displayText.contains("cleared for takeoff") ?? false)
        XCTAssertTrue(tx?.displayText.contains("fly heading 090") ?? false)
    }

    func testInitialClimbUsesTraconCeiling() {
        var m = ATCStateMachine(engine: engine())
        m.setConnected()
        var ctx = TestSupport.context()
        ctx.traconCeiling = 18000
        ctx.firstFixName = "WAGON"
        let tx = m.advance(to: .initialClimb, context: ctx)
        XCTAssertTrue(tx?.displayText.contains("FL180") ?? false)
        XCTAssertTrue(tx?.displayText.contains("direct WAGON") ?? false)
    }
}
