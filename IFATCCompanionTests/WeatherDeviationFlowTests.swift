import XCTest
@testable import IFATCCompanion

/// Drives the `AppModel` through the offline mock weather-deviation demo and
/// asserts the full request → approval → clear-of-weather flow, plus the ATCView
/// banner gating and that subscription/live gating never breaks the mock demo.
@MainActor
final class WeatherDeviationFlowTests: XCTestCase {

    private func makeModel() -> AppModel {
        let model = AppModel()
        model.settings.voiceEnabled = false
        model.settings.mockMode = true
        model.settings.weatherDeviationAlerts = .advisoryPlusDeviation
        model.settings.noaaRadarOverlay = .autoWhereAvailable

        var plan = FlightPlan()
        plan.airline = "United"
        plan.flightNumber = "598"
        plan.departure = "KIAH"
        plan.destination = "KMSP"
        plan.cruiseAltitude = 37000
        plan.waypoints = model.mock.route.waypoints
        model.flightPlan = plan
        return model
    }

    /// Get the mock aircraft to cruise with the demo precipitation cell loaded.
    private func driveToCruiseConflict(_ model: AppModel) async {
        await model.refreshWeather()                 // loads the mock radar cell
        for _ in 0..<3 {
            model.ingestStateForTesting(model.mock.state(for: .cruise))
        }
    }

    private func atcContains(_ model: AppModel, _ needle: String) -> Bool {
        model.transcript.contains { $0.sender == .atc && $0.displayText.contains(needle) }
    }

    private func pilotContains(_ model: AppModel, _ needle: String) -> Bool {
        model.transcript.contains { $0.sender == .pilot && $0.displayText.contains(needle) }
    }

    // MARK: - Banner only when a conflict exists

    func testBannerHiddenWithNoConflict() {
        let model = makeModel()
        XCTAssertNil(model.activeWeatherConflict)
        XCTAssertFalse(model.weatherBannerVisible)
        XCTAssertFalse(model.weatherDeviationCardVisible)
    }

    // MARK: - Full mock weather-deviation flow

    func testMockWeatherDeviationFlow() async {
        let model = makeModel()
        await driveToCruiseConflict(model)

        // A conflict is detected and the demo auto-issues the advisory.
        XCTAssertNotNil(model.activeWeatherConflict, "mock precipitation cell should conflict with the route")
        XCTAssertEqual(model.weatherDeviationState, .awaitingPilotIntentions)
        XCTAssertTrue(atcContains(model, "precipitation"), "advisory should mention precipitation")
        XCTAssertFalse(atcContains(model, "turbulence"), "radar advisory must not say turbulence")
        XCTAssertTrue(model.weatherDeviationCardVisible)

        // Pilot requests a right deviation; ATC approves with a rejoin fix.
        model.requestWeatherDeviation(.right)
        XCTAssertEqual(model.weatherDeviationState, .deviationApproved)
        XCTAssertTrue(pilotContains(model, "requests"), "pilot deviation request should be posted")
        XCTAssertTrue(atcContains(model, "approved"), "ATC should approve the deviation")
        XCTAssertTrue(atcContains(model, "maintain"), "approval should assign a maintain altitude")

        // Pilot reports clear of weather; ATC resumes own navigation.
        model.reportClearOfWeather()
        XCTAssertTrue(pilotContains(model, "clear of weather"))
        XCTAssertTrue(atcContains(model, "resume own navigation"))
        XCTAssertNil(model.activeWeatherConflict, "the conflict clears after reporting clear of weather")
        XCTAssertEqual(model.weatherDeviationState, .none)

        // Reading the call back must echo "resume own navigation", not a stale
        // state-derived read-back.
        model.readBack()
        XCTAssertTrue(model.transcript.contains {
            $0.sender == .pilot && $0.displayText.lowercased().contains("resume own navigation")
        }, "clear-of-weather read-back should echo resume own navigation")
    }

    // MARK: - Vector variant

    func testMockWeatherVectorFlow() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        XCTAssertNotNil(model.activeWeatherConflict)

        model.requestVectorAroundWeather()
        XCTAssertEqual(model.weatherDeviationState, .vectoringAroundWeather)
        XCTAssertTrue(atcContains(model, "vectors around precipitation"))
        XCTAssertTrue(atcContains(model, "fly heading"))

        // Reading back the vector echoes both the heading and the maintain altitude.
        model.readBack()
        XCTAssertTrue(pilotContains(model, "Heading"), "vector read-back should echo the heading")
        XCTAssertTrue(pilotContains(model, "maintain"), "vector read-back should echo the maintain altitude")
    }

    // MARK: - Read-back phraseology (unit)

    /// The weather vector assigns a heading and an altitude; the read-back echoes both.
    func testWeatherVectorReadbackEchoesHeadingAndAltitude() {
        let phr = WeatherDeviationPhraseology(engine: PhraseologyEngine(digitStyle: .individual, mode: .faa))
        let cs = phr.engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        let tx = phr.vectorApproval(cs: cs, heading: 90, maintainAltitude: 37000)
        let rb = tx.readback
        XCTAssertNotNil(rb, "weather vector must carry a read-back")
        XCTAssertTrue(rb?.displayText.contains("Heading 090") ?? false, rb?.displayText ?? "")
        XCTAssertTrue(rb?.displayText.contains("maintain FL370") ?? false, rb?.displayText ?? "")
        XCTAssertTrue(rb?.displayText.contains("United 598") ?? false, rb?.displayText ?? "")
    }

    /// "Resume own navigation" (with and without a rejoin fix) is echoed in the read-back.
    func testClearOfWeatherReadbackIncludesResumeOwnNavigation() {
        let phr = WeatherDeviationPhraseology(engine: PhraseologyEngine(digitStyle: .individual, mode: .faa))
        let cs = phr.engine.callsign(airline: "United", flightNumber: "598", fallback: "")

        let noFix = phr.clearOfWeatherResume(cs: cs, rejoinFix: nil, nearRoute: true)
        XCTAssertTrue(noFix.readback?.displayText.contains("Resume own navigation") ?? false, noFix.readback?.displayText ?? "")

        let withFix = phr.clearOfWeatherResume(cs: cs, rejoinFix: "WAGON", nearRoute: false)
        XCTAssertTrue(withFix.readback?.displayText.contains("resume own navigation") ?? false, withFix.readback?.displayText ?? "")
        XCTAssertTrue(withFix.readback?.displayText.contains("Direct WAGON") ?? false, withFix.readback?.displayText ?? "")
    }

    /// Every weather deviation approval echoes the maintain altitude in its read-back.
    func testDeviationApprovalReadbacksEchoMaintainAltitude() {
        let phr = WeatherDeviationPhraseology(engine: PhraseologyEngine(digitStyle: .individual, mode: .faa))
        let cs = phr.engine.callsign(airline: "United", flightNumber: "598", fallback: "")

        let rejoin = phr.approvalWithRejoin(cs: cs, direction: .right, degrees: 20,
                                            maintainAltitude: 37000, rejoinFix: "WAGON")
        XCTAssertTrue(rejoin.readback?.displayText.contains("Maintain FL370") ?? false, rejoin.readback?.displayText ?? "")
        XCTAssertTrue(rejoin.readback?.displayText.contains("WAGON") ?? false, rejoin.readback?.displayText ?? "")

        let noRejoin = phr.approvalNoRejoin(cs: cs, direction: .left, degrees: 15, maintainAltitude: 34000)
        XCTAssertTrue(noRejoin.readback?.displayText.contains("Maintain FL340") ?? false, noRejoin.readback?.displayText ?? "")

        let star = phr.starDeviationApproval(cs: cs, direction: .right, degrees: 20, maintainAltitude: 11000,
                                             starDisplay: "KKILR", starSpoken: "killer", rejoinFix: "HOBTT")
        XCTAssertTrue(star.readback?.displayText.contains("Maintain 11,000") ?? false, star.readback?.displayText ?? "")
        XCTAssertTrue(star.readback?.displayText.contains("HOBTT") ?? false, star.readback?.displayText ?? "")
    }

    /// Pilot weather requests address whatever controller is working the flight —
    /// Approach on arrival, Departure on climb — not a hard-coded "Center".
    func testPilotWeatherRequestsAddressTunedFacility() {
        let phr = WeatherDeviationPhraseology(engine: PhraseologyEngine(digitStyle: .individual, mode: .faa))
        let cs = phr.engine.callsign(airline: "United", flightNumber: "598", fallback: "")

        let approach = phr.pilotRequestDeviation(cs: cs, direction: .right, degrees: 20, facility: .approach)
        XCTAssertTrue(approach.displayText.hasPrefix("Approach,"), approach.displayText)
        XCTAssertTrue(approach.spokenText.hasPrefix("Approach,"), approach.spokenText)

        let departure = phr.pilotRequestVectors(cs: cs, facility: .departure)
        XCTAssertTrue(departure.displayText.hasPrefix("Departure,"), departure.displayText)

        let center = phr.pilotRequestAltitude(cs: cs, higher: true, facility: .center)
        XCTAssertTrue(center.displayText.hasPrefix("Center,"), center.displayText)
    }

    /// Rejoining the STAR echoes the direct fix and the descend-via clearance.
    func testRejoinStarReadbackEchoesDirectFixAndDescendVia() {
        let phr = WeatherDeviationPhraseology(engine: PhraseologyEngine(digitStyle: .individual, mode: .faa))
        let cs = phr.engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        let tx = phr.rejoinStar(cs: cs, rejoinFix: "HOBTT", starDisplay: "KKILR", starSpoken: "killer")
        XCTAssertTrue(tx.readback?.displayText.contains("Direct HOBTT") ?? false, tx.readback?.displayText ?? "")
        XCTAssertTrue(tx.readback?.displayText.contains("descend via the KKILR arrival") ?? false, tx.readback?.displayText ?? "")
    }

    /// A weather altitude change (higher/lower) echoes the assigned altitude.
    func testWeatherAltitudeChangeReadbackEchoesAltitude() {
        let phr = WeatherDeviationPhraseology(engine: PhraseologyEngine(digitStyle: .individual, mode: .faa))
        let eng = WeatherDeviationEngine(phraseology: phr)
        let cs = phr.engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        let result = eng.requestAltitude(cs: cs, higher: false, targetAltitude: 33000,
                                         context: WeatherDeviationContext(), facility: .center)
        let atc = result.atc.first
        XCTAssertTrue(atc?.readback?.displayText.contains("Descend and maintain FL330") ?? false, atc?.readback?.displayText ?? "")
    }

    // MARK: - Live/subscription gating does not break the mock demo

    func testMockDemoWorksWithoutSubscription() async {
        let model = makeModel()
        // No StoreKit configuration in tests → Live access is not granted.
        XCTAssertFalse(model.entitlements.hasLiveAccess)
        await driveToCruiseConflict(model)
        // The mock weather-deviation demo still runs end-to-end.
        XCTAssertNotNil(model.activeWeatherConflict)
        model.requestWeatherDeviation(.right)
        XCTAssertEqual(model.weatherDeviationState, .deviationApproved)
    }

    // MARK: - Existing weather features still work

    func testExistingWeatherStillLoadsInMock() async {
        let model = makeModel()
        await model.refreshWeather()
        XCTAssertNotNil(model.departureMETAR, "existing METAR loading must still work")
        XCTAssertFalse(model.pireps.isEmpty, "existing PIREPs must still load")
        XCTAssertTrue(model.weatherStatus.contains("Mock weather loaded"))
    }
}
