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

    /// The departure/arrival runway must not be mistaken for the first enroute
    /// waypoint (previously "next waypoint" showed the departure runway).
    func testParseFlightPlanStripsRunwayTokens() {
        let plan = IFFlightPlanParser.parse("KIAH RW15L WAGON HOBTT 30L KMSP")
        XCTAssertEqual(plan?.departure, "KIAH")
        XCTAssertEqual(plan?.destination, "KMSP")
        XCTAssertEqual(plan?.waypoints.map { $0.name }, ["WAGON", "HOBTT"])
    }

    func testParseFlightPlanRecoversCruiseFromFlightLevel() {
        let plan = IFFlightPlanParser.parse("KIAH WAGON FL370 HOBTT KMSP")
        XCTAssertEqual(plan?.cruiseAltitude, 37000)
        XCTAssertEqual(plan?.waypoints.map { $0.name }, ["WAGON", "HOBTT"])
    }

    /// The richer JSON flight plan yields coordinates, the cruise (TOC) level, and
    /// the published SID/STAR/approach.
    func testParseJSONFlightPlanExtractsCoordinatesProceduresAndCruise() {
        let json = """
        {"flightPlanItems":[
          {"name":"KIAH","identifier":"KIAH","location":{"Latitude":29.98,"Longitude":-95.34}},
          {"name":"WAGON3","children":[
             {"name":"WAGON","identifier":"WAGON","altitude":12000,"location":{"Latitude":30.5,"Longitude":-95.0}}
          ]},
          {"name":"HOBTT","identifier":"HOBTT","altitude":37000,"location":{"Latitude":38.0,"Longitude":-94.0}},
          {"name":"KKILR1","children":[
             {"name":"KKILR","identifier":"KKILR","altitude":11000,"location":{"Latitude":43.0,"Longitude":-93.5}}
          ]},
          {"name":"ILS 30L","children":[
             {"name":"FAF30L","identifier":"FAF30L","altitude":3000,"location":{"Latitude":44.5,"Longitude":-93.3}}
          ]},
          {"name":"KMSP","identifier":"KMSP","location":{"Latitude":44.88,"Longitude":-93.22}}
        ]}
        """
        let plan = IFFlightPlanParser.parse(json)
        XCTAssertEqual(plan?.departure, "KIAH")
        XCTAssertEqual(plan?.destination, "KMSP")
        XCTAssertEqual(plan?.sid, "WAGON3")
        XCTAssertEqual(plan?.star, "KKILR1")
        XCTAssertEqual(plan?.approach, "ILS 30L")
        XCTAssertEqual(plan?.cruiseAltitude, 37000)
        XCTAssertEqual(plan?.approachInterceptAltitude, 3000,
                       "intercept altitude should be the first altitude in the approach section")
        XCTAssertTrue(plan?.waypoints.contains { $0.name == "WAGON" } ?? false)
        XCTAssertNotNil(plan?.waypoints.first?.coordinate, "JSON fixes should carry coordinates")
    }

    // MARK: - Live unit conversion

    /// Infinite Flight reports speeds and vertical speed in m/s; the app expects
    /// knots and feet-per-minute. (The bug showed ~half the real knots and never
    /// detected descents.)
    func testLiveSpeedAndVerticalSpeedUnitConversion() {
        XCTAssertEqual(158.0 * IFConnectStateReader.metresPerSecondToKnots, 307, accuracy: 1.5)
        XCTAssertEqual(128.0 * IFConnectStateReader.metresPerSecondToKnots, 249, accuracy: 1.5)
        XCTAssertEqual(-9.0 * IFConnectStateReader.metresPerSecondToFeetPerMinute, -1772, accuracy: 5)
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

    /// A frequency hand-off carries a read-back ("contacting <next> on <freq>") that
    /// names the facility to auto-tune to once the pilot reads it back.
    func testHandoffCarriesContactingReadbackThatTunesAhead() {
        let e = engine()
        let cs = e.callsign(airline: "United", flightNumber: "598", fallback: "")
        let tx = e.handoff(cs: cs, from: .tower, to: .departure, frequency: 124.3)
        XCTAssertEqual(tx.readback?.tuneTo, .departure)
        XCTAssertTrue(tx.readback?.displayText.contains("Contacting Departure on 124.300") ?? false,
                      "read-back should echo the frequency hand-off: \(tx.readback?.displayText ?? "nil")")
    }

    /// Arrival taxi names the assigned gate when one is known, else "parking".
    func testTaxiToParkingNamesArrivalGate() {
        let e = engine()
        let cs = e.callsign(airline: "United", flightNumber: "598", fallback: "")
        XCTAssertTrue(e.taxiToParking(cs: cs, gate: "B44", via: "A").displayText.contains("taxi to gate B44"))
        XCTAssertTrue(e.taxiToParking(cs: cs, gate: "", via: "A").displayText.contains("taxi to parking"))
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

    // MARK: - Read-backs echo heading + altitude and "resume own navigation"

    /// The takeoff clearance issues an initial heading and a climb; the read-back
    /// must echo both, not just the runway.
    func testTakeoffReadbackEchoesHeadingAndAltitude() {
        let e = engine()
        let cs = e.callsign(airline: "United", flightNumber: "598", fallback: "")
        let tx = e.clearedForTakeoff(cs: cs, runway: "17R", windDir: 180, windSpeed: 8,
                                     departureHeading: 90, initialAltitude: 5000)
        let rb = tx.readback
        XCTAssertNotNil(rb, "takeoff clearance with a heading must carry a read-back")
        XCTAssertTrue(rb?.displayText.contains("heading 090") ?? false, rb?.displayText ?? "")
        XCTAssertTrue(rb?.displayText.contains("climb and maintain 5,000") ?? false, rb?.displayText ?? "")
        XCTAssertTrue(rb?.displayText.contains("17R") ?? false, rb?.displayText ?? "")
        XCTAssertTrue(rb?.displayText.contains("United 598") ?? false, rb?.displayText ?? "")
    }

    /// When the departure heading aligns with the runway the clearance says
    /// "fly runway heading" — the read-back echoes "runway heading".
    func testTakeoffReadbackUsesRunwayHeadingWhenAligned() {
        let e = engine()
        let cs = e.callsign(airline: "United", flightNumber: "598", fallback: "")
        // 17R ≈ 170°; a 172° assignment is within tolerance → "runway heading".
        let tx = e.clearedForTakeoff(cs: cs, runway: "17R", windDir: 180, windSpeed: 8,
                                     departureHeading: 172, initialAltitude: 5000)
        XCTAssertTrue(tx.readback?.displayText.contains("runway heading") ?? false, tx.readback?.displayText ?? "")
    }

    /// The departure climb clears the aircraft to "resume own navigation"; the
    /// read-back must include it (with the direct fix when one is named).
    func testDepartureClimbReadbackIncludesResumeOwnNavigation() {
        let e = engine()
        let cs = e.callsign(airline: "United", flightNumber: "598", fallback: "")
        let withFix = e.departureClimb(cs: cs, altitude: 18000, firstFix: "WAGON")
        XCTAssertTrue(withFix.readback?.displayText.contains("resume own navigation") ?? false, withFix.readback?.displayText ?? "")
        XCTAssertTrue(withFix.readback?.displayText.contains("direct WAGON") ?? false, withFix.readback?.displayText ?? "")
        XCTAssertTrue(withFix.readback?.displayText.contains("climb and maintain FL180") ?? false, withFix.readback?.displayText ?? "")

        let noFix = e.departureClimb(cs: cs, altitude: 18000, firstFix: "")
        XCTAssertTrue(noFix.readback?.displayText.contains("resume own navigation") ?? false, noFix.readback?.displayText ?? "")
        XCTAssertFalse(noFix.readback?.displayText.contains("direct") ?? true, noFix.readback?.displayText ?? "")
    }

    // MARK: - Descent phraseology (non-contradictory)

    func testDescentTargetIsIntermediateBelowCruise() {
        var ctx = TestSupport.context(cruise: 37000)
        XCTAssertEqual(ATCStateMachine.descentTargetAltitude(context: ctx), 11000)
        ctx.cruiseAltitude = 12000
        XCTAssertEqual(ATCStateMachine.descentTargetAltitude(context: ctx), 8000)
    }

    func testDescentWithoutStarIsPlainDescendAndMaintain() {
        var m = ATCStateMachine(engine: engine())
        m.setConnected()
        let ctx = TestSupport.context(cruise: 37000)   // no STAR
        let tx = m.advance(to: .descent, context: ctx)
        XCTAssertTrue(tx?.displayText.contains("descend and maintain 11,000") ?? false)
        XCTAssertFalse(tx?.displayText.contains("pilot's discretion") ?? true)
        XCTAssertFalse(tx?.displayText.contains("FL370") ?? true)
    }

    func testDescentWithStarSaysDescendViaArrival() {
        var m = ATCStateMachine(engine: engine())
        m.setConnected()
        var ctx = TestSupport.context(cruise: 37000)
        ctx.starProcedure = ProcedureParser.parseSTAR("KKILR", icao: "KMSP")
        let tx = m.advance(to: .descent, context: ctx)
        XCTAssertTrue(tx?.displayText.contains("descend via the KKILR arrival") ?? false)
        XCTAssertFalse(tx?.displayText.contains("pilot's discretion") ?? true)
    }

    // MARK: - Cleared approach + runway exit

    func testRunwayExitTellsPilotToExitAndContactGround() {
        var m = ATCStateMachine(engine: engine())
        m.setConnected()
        let tx = m.advance(to: .runwayExit, context: TestSupport.context())
        XCTAssertEqual(tx?.facility, .tower)
        XCTAssertTrue(tx?.displayText.contains("exit the runway when able") ?? false)
        XCTAssertTrue(tx?.displayText.contains("contact Ground") ?? false)
    }

    func testExitRunwayContactGroundPhraseology() {
        let e = engine()
        let cs = e.callsign(airline: "United", flightNumber: "598", fallback: "")
        let tx = e.exitRunwayContactGround(cs: cs, frequency: 121.8)
        XCTAssertEqual(tx.facility, .tower)
        XCTAssertTrue(tx.displayText.contains("121.800"))
        XCTAssertTrue(tx.displayText.contains("once on the taxiway"))
    }

    // MARK: - Final-approach establishment

    func testOnFinalApproachDetectedWhenAlignedLowAndDescending() {
        let d = RunwayLineupDetector()
        var s = AircraftState()
        s.onGround = false
        s.heading = 302            // runway 30L -> 300°
        s.altitudeAGL = 2000
        s.verticalSpeed = -700
        XCTAssertTrue(d.isOnFinalApproach(state: s, runway: "30L"))
    }

    func testNotOnFinalApproachWhenLevelOrHigh() {
        let d = RunwayLineupDetector()
        var s = AircraftState()
        s.onGround = false
        s.heading = 300
        s.altitudeAGL = 9000       // too high
        s.verticalSpeed = -700
        XCTAssertFalse(d.isOnFinalApproach(state: s, runway: "30L"))
    }
}
