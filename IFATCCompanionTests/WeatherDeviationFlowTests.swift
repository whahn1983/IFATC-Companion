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
