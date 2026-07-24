import XCTest
@testable import IFATCCompanion

/// Pure-phraseology and geometry tests for the go-around / missed-approach flow.
final class GoAroundPhraseologyTests: XCTestCase {

    private func engine() -> PhraseologyEngine {
        PhraseologyEngine(digitStyle: .individual, mode: .faa)
    }

    // MARK: - Tower go-around instruction

    /// Tower's go-around call carries every element the pilot must read back: the
    /// crosswind vector, the climb to the pattern altitude, left/right traffic for the
    /// same runway, and the hand-off to Approach.
    func testTowerGoAroundContainsAllElements() {
        let cs = engine().callsign(airline: "United", flightNumber: "598", fallback: "")
        let tx = engine().goAround(cs: cs, runway: "30L", leftTraffic: true,
                                   crosswindHeading: 210, patternAltitude: 3000,
                                   approachFrequency: 119.700)
        XCTAssertEqual(tx.facility, .tower)
        XCTAssertTrue(tx.displayText.contains("go around"), tx.displayText)
        XCTAssertTrue(tx.displayText.contains("turn left heading 210"), tx.displayText)
        XCTAssertTrue(tx.displayText.contains("climb and maintain 3,000"), tx.displayText)
        XCTAssertTrue(tx.displayText.contains("make left traffic runway 30L"), tx.displayText)
        XCTAssertTrue(tx.displayText.contains("contact Approach on 119.700"), tx.displayText)
        // The read-back echoes every element and tunes to Approach once read back.
        let rb = tx.readback
        XCTAssertNotNil(rb)
        XCTAssertEqual(rb?.tuneTo, .approach)
        XCTAssertTrue(rb?.displayText.contains("turn left heading 210") ?? false, rb?.displayText ?? "nil")
        XCTAssertTrue(rb?.displayText.contains("climb and maintain 3,000") ?? false, rb?.displayText ?? "nil")
        XCTAssertTrue(rb?.displayText.contains("make left traffic runway 30L") ?? false, rb?.displayText ?? "nil")
        XCTAssertTrue(rb?.displayText.contains("contacting Approach on 119.700") ?? false, rb?.displayText ?? "nil")
    }

    /// A right-hand pattern reverses the turn word and the traffic direction.
    func testTowerGoAroundRightTraffic() {
        let cs = engine().callsign(airline: "United", flightNumber: "598", fallback: "")
        let tx = engine().goAround(cs: cs, runway: "30L", leftTraffic: false,
                                   crosswindHeading: 30, patternAltitude: 3000,
                                   approachFrequency: 119.700)
        XCTAssertTrue(tx.displayText.contains("turn right heading 030"), tx.displayText)
        XCTAssertTrue(tx.displayText.contains("make right traffic runway 30L"), tx.displayText)
    }

    // MARK: - Approach continue-inbound

    /// After the go-around, Approach holds the pattern altitude and sends the aircraft
    /// back around, naming the same published approach and runway.
    func testContinueInboundNamesAltitudeAndApproach() {
        let cs = engine().callsign(airline: "United", flightNumber: "598", fallback: "")
        let proc = ProcedureParser.parseApproach("ILS 30L")
        let tx = engine().continueInbound(cs: cs, altitude: 3000, procedure: proc,
                                          approach: "the ILS", runway: "30L")
        XCTAssertEqual(tx.facility, .approach)
        XCTAssertTrue(tx.displayText.contains("maintain 3,000"), tx.displayText)
        XCTAssertTrue(tx.displayText.contains("continue inbound"), tx.displayText)
        XCTAssertTrue(tx.displayText.contains("expect the ILS runway 30L approach"), tx.displayText)
        XCTAssertTrue(tx.readback?.displayText.contains("Maintain 3,000, continue inbound") ?? false,
                      tx.readback?.displayText ?? "nil")
    }

    /// With no parsed procedure the free-text approach name is used, without doubling
    /// the article ("the ILS runway …", not "the the ILS runway …").
    func testContinueInboundFallsBackToApproachName() {
        let cs = engine().callsign(airline: "United", flightNumber: "598", fallback: "")
        let tx = engine().continueInbound(cs: cs, altitude: 3000, procedure: nil,
                                          approach: "the ILS", runway: "30L")
        XCTAssertTrue(tx.displayText.contains("expect the ILS runway 30L approach"), tx.displayText)
        XCTAssertFalse(tx.displayText.contains("the the"), tx.displayText)
    }

    // MARK: - Crosswind heading geometry

    /// The crosswind leg is 90° off the runway heading, turning in the pattern
    /// direction: left traffic subtracts 90°, right traffic adds 90° (normalized).
    func testCrosswindHeading() {
        // Runway 30 (300°): left → 210, right → 030.
        XCTAssertEqual(AppModel.crosswindHeading(runwayHeading: 300, leftTraffic: true), 210)
        XCTAssertEqual(AppModel.crosswindHeading(runwayHeading: 300, leftTraffic: false), 30)
        // Runway 09 (090°): left → 000 (north), right → 180.
        XCTAssertEqual(AppModel.crosswindHeading(runwayHeading: 90, leftTraffic: true), 0)
        XCTAssertEqual(AppModel.crosswindHeading(runwayHeading: 90, leftTraffic: false), 180)
        // Wrap-around: runway 01 (010°) left → 280; runway 36 (360°) right → 090.
        XCTAssertEqual(AppModel.crosswindHeading(runwayHeading: 10, leftTraffic: true), 280)
        XCTAssertEqual(AppModel.crosswindHeading(runwayHeading: 360, leftTraffic: false), 90)
        XCTAssertEqual(AppModel.crosswindHeading(runwayHeading: 360, leftTraffic: true), 270)
    }
}

/// End-to-end go-around flow driven through the `AppModel`, using the manual
/// frequency-tune buttons to reach the arrival Tower, then breaking off the approach.
@MainActor
final class GoAroundFlowTests: XCTestCase {

    private func makeModel() -> AppModel {
        let model = AppModel()
        model.settings.voiceEnabled = false
        model.settings.mockMode = true
        model.settings.initialClimbAltitudeFt = 5000
        model.settings.traconCeilingFL = 180

        var plan = FlightPlan()
        plan.airline = "United"
        plan.flightNumber = "598"
        plan.departure = "KIAH"
        plan.destination = "KMSP"
        plan.cruiseAltitude = 37000
        plan.star = "KKILR"
        plan.approach = "ILS 30L"
        plan.waypoints = model.mock.route.waypoints
        model.flightPlan = plan
        return model
    }

    private func count(_ model: AppModel, _ needle: String,
                       sender: ATCTransmission.Sender? = nil) -> Int {
        model.transcript.filter { tx in
            (sender == nil || tx.sender == sender) && tx.displayText.contains(needle)
        }.count
    }

    private func contains(_ model: AppModel, _ needle: String,
                          sender: ATCTransmission.Sender? = nil) -> Bool {
        count(model, needle, sender: sender) > 0
    }

    /// Drive the manual-tuning flow up to the arrival Tower's "cleared to land".
    private func flyToClearedToLand() -> AppModel {
        let model = makeModel()
        model.requestClearance();        model.readBack()
        model.requestPushback();         model.readBack()
        model.requestEngineStart();      model.readBack()
        model.requestTaxi();             model.readBack()   // Ramp → Ground
        model.requestTaxi();             model.readBack()   // Ground taxi clearance
        model.reportReadyForDeparture(); model.readBack()   // line up and wait
        model.tuneTo(.tower);     model.requestHandoff(); model.readBack()   // cleared for takeoff
        model.tuneTo(.departure); model.requestHandoff(); model.readBack()   // departure climb
        model.tuneTo(.center);    model.requestHandoff(); model.readBack()   // climb to cruise
        model.tuneTo(.center);    model.requestHandoff()                     // radar contact
        model.tuneTo(.center);    model.requestHandoff(); model.readBack()   // descend via STAR
        model.tuneTo(.approach);  model.requestHandoff(); model.readBack()   // expect approach
        model.tuneTo(.approach);  model.requestHandoff(); model.readBack()   // cleared approach
        model.tuneTo(.tower);     model.requestHandoff(); model.readBack()   // cleared to land
        return model
    }

    /// Inbound to land on Tower, the pilot can go around; before departure they
    /// cannot (no airborne approach to break off).
    func testGoAroundButtonOnlyInboundOnTower() {
        // Fresh at the gate: no go-around.
        let preflight = makeModel()
        XCTAssertFalse(preflight.canGoAround)
        XCTAssertFalse(preflight.availableActions.contains(.goAround))

        // Cleared to land on Tower: go-around available.
        let model = flyToClearedToLand()
        XCTAssertEqual(model.currentFacility, .tower)
        XCTAssertTrue(model.canGoAround)
        XCTAssertTrue(model.availableActions.contains(.goAround))
    }

    /// The full go-around: pilot calls it, Tower vectors onto a crosswind leg with a
    /// climb and left traffic for the same runway and hands to Approach; the pilot
    /// re-establishes with Approach (maintain, continue inbound); then the whole
    /// cleared-approach → Tower → cleared-to-land sequence replays.
    func testGoAroundIssuesPatternInstructionsAndReplaysApproach() {
        let model = flyToClearedToLand()

        model.goAround()
        // Pilot's go-around call, then Tower's pattern instructions (all elements).
        XCTAssertTrue(contains(model, "going around", sender: .pilot))
        XCTAssertTrue(contains(model, "go around, turn left heading 210", sender: .atc))
        XCTAssertTrue(contains(model, "climb and maintain 3,000", sender: .atc))
        XCTAssertTrue(contains(model, "make left traffic runway 30L", sender: .atc))
        XCTAssertTrue(contains(model, "contact Approach on 119.700", sender: .atc))
        XCTAssertEqual(model.assignedAltitude, 3000)

        // Reading back the go-around tunes the radio to Approach.
        model.readBack()
        XCTAssertEqual(model.currentFacility, .approach)
        XCTAssertTrue(contains(model, "contacting Approach on 119.700", sender: .pilot))

        // Checking in with Approach holds the pattern altitude and continues inbound.
        model.requestHandoff()
        XCTAssertTrue(contains(model, "maintain 3,000, continue inbound", sender: .atc))
        XCTAssertTrue(contains(model, "continue inbound, expect the ILS runway 30L approach", sender: .atc))
        model.readBack()

        // The approach replays exactly as before: cleared approach, then cleared to land.
        model.tuneTo(.approach); model.requestHandoff(); model.readBack()   // cleared approach (2nd)
        model.tuneTo(.tower);    model.requestHandoff(); model.readBack()   // cleared to land (2nd)
        XCTAssertEqual(count(model, "cleared ILS RWY 30L approach", sender: .atc), 2,
                       "the cleared-approach call should appear twice — once per approach")
        XCTAssertEqual(count(model, "cleared to land", sender: .atc), 2,
                       "the cleared-to-land call should appear twice — once per approach")
    }

    /// The pattern altitude uses the same elevation-aware math as the approach
    /// descent: 3,000 ft above the field (from live MSL − AGL), rounded up to the next
    /// thousand — so at a high field it clears the ground (9,000 ft at Denver's
    /// ~5,434 ft) rather than a sub-surface 3,000 ft.
    func testGoAroundPatternAltitudeIsElevationAware() {
        let model = flyToClearedToLand()
        // Feed an airborne snapshot near a high field (ground ≈ MSL − AGL = 5,434 ft).
        // In mock + manual tuning this only updates telemetry; it does not advance the
        // conversation.
        var s = AircraftState()
        s.onGround = false
        s.altitudeMSL = 8434
        s.altitudeAGL = 3000
        s.heading = 300
        model.ingestStateForTesting(s)

        model.goAround()
        // 5,434 field + 3,000 = 8,434 → rounded up to 9,000 ft MSL.
        XCTAssertTrue(contains(model, "climb and maintain 9,000", sender: .atc))
        XCTAssertEqual(model.assignedAltitude, 9000)
        XCTAssertFalse(contains(model, "climb and maintain 3,000", sender: .atc))
    }
}
