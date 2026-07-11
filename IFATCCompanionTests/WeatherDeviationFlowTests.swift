import XCTest
import CoreLocation
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

    private func box(around c: CLLocationCoordinate2D, half: Double) -> [CLLocationCoordinate2D] {
        [CLLocationCoordinate2D(latitude: c.latitude - half, longitude: c.longitude - half),
         CLLocationCoordinate2D(latitude: c.latitude - half, longitude: c.longitude + half),
         CLLocationCoordinate2D(latitude: c.latitude + half, longitude: c.longitude + half),
         CLLocationCoordinate2D(latitude: c.latitude + half, longitude: c.longitude - half)]
    }

    // MARK: - Turbulence / icing SIGMET → altitude advisory (not a lateral reroute)

    func testTurbulenceSigmetOffersAltitudeNotDeviation() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        guard let pos = model.aircraftState.coordinate else {
            return XCTFail("expected an aircraft position at cruise")
        }
        // The mock auto-issues the precip advisory; settle it back to idle first.
        model.continueThroughWeather()

        // Swap the precipitation cell for a severe-turbulence SIGMET over the aircraft.
        model.radarOverlay.mockCells = []
        model.sigmets = [SIGMET(raw: "SEV TURB", hazard: "TURB", severity: "SEV",
                                area: box(around: pos, half: 0.8))]
        model.recomputeRideItems()
        model.recomputeWeatherHazards()

        XCTAssertNil(model.activeWeatherConflict, "no precipitation → no lateral conflict")
        XCTAssertEqual(model.weatherDeviationState, .none, "the precip flow settled before the ride advisory")
        XCTAssertNotNil(model.activeRideSigmet, "a turbulence SIGMET on the route drives a ride advisory")
        XCTAssertTrue(model.weatherBannerVisible)
        XCTAssertTrue(model.weatherBannerText.contains("Turbulence"), model.weatherBannerText)

        model.askCenterAboutWeather()
        XCTAssertEqual(model.weatherDeviationState, .awaitingPilotIntentions)
        XCTAssertTrue(atcContains(model, "turbulence"), "advisory should name the turbulence")
        XCTAssertTrue(atcContains(model, "smoother air"), "advisory should point at an altitude change")

        // The offered responses are altitude changes — never a lateral deviation.
        XCTAssertTrue(model.weatherActions.contains(.requestHigher))
        XCTAssertTrue(model.weatherActions.contains(.requestLower))
        XCTAssertFalse(model.weatherActions.contains(.requestRightDeviation))
        XCTAssertFalse(model.weatherActions.contains(.requestLeftDeviation))
        XCTAssertFalse(model.weatherActions.contains(.requestVector))

        // Requesting higher assigns a new altitude for the smoother ride.
        model.requestHigherForWeather()
        XCTAssertEqual(model.weatherDeviationState, .deviatingAroundWeather)
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

    /// A weather vector must fly toward the recommended reroute (the mint deviation
    /// path) measured from the aircraft's current position — not the current heading
    /// offset by the deviation amount. Otherwise a second vector requested while
    /// already deviated stacks another turn onto the nose and points the wrong way.
    func testVectorHeadingFollowsMintPathNotCurrentHeading() async {
        let model = makeModel()
        await driveToCruiseConflict(model)

        // Simulate the aircraft already being deviated well off its filed course:
        // keep the position, but swing the reported heading 70° to one side. The
        // recommended reroute is anchored to the route from the current position, so
        // the vector that follows it must not swing with the nose.
        var deviated = model.mock.state(for: .cruise)
        let skewed = ((deviated.heading ?? 0) + 70).truncatingRemainder(dividingBy: 360)
        deviated.heading = skewed
        deviated.track = skewed
        model.ingestStateForTesting(deviated)

        guard let conflict = model.activeWeatherConflict,
              conflict.deviationPath.count >= 2,
              let pos = model.aircraftState.coordinate else {
            return XCTFail("expected a conflict with a deviation path")
        }
        let apex = conflict.deviationPath[1]
        let expected = ((Int(Geo.bearing(from: pos, to: apex).rounded()) % 360) + 360) % 360

        model.requestVectorAroundWeather()
        XCTAssertEqual(model.weatherDeviation.assignedHeading, expected,
                       "vector must follow the mint deviation path from the current position")

        // It must NOT be the old current-heading ± degrees offset that caused the bug.
        let base = Int(skewed.rounded())
        let degrees = conflict.recommendedDeviationDegrees
        let naiveRight = ((base + degrees) % 360 + 360) % 360
        let naiveLeft = ((base - degrees) % 360 + 360) % 360
        XCTAssertNotEqual(model.weatherDeviation.assignedHeading, naiveRight,
                          "vector must not stack a fresh right offset on the deviated heading")
        XCTAssertNotEqual(model.weatherDeviation.assignedHeading, naiveLeft,
                          "vector must not stack a fresh left offset on the deviated heading")
    }

    /// The deviation path has one or more turns in it — deviate around the weather,
    /// then turn back to intercept the filed route. When the aircraft reaches **each**
    /// turn (every interior vertex of the mint line), the controller must automatically
    /// issue the turn onto the next leg, without the pilot asking. The final turn
    /// rejoins the filed course; a side-hug line has an earlier intermediate turn (out
    /// onto the parallel leg) that must be called too.
    func testWeatherVectorAutoTurnsBackAtEachDeviationTurn() async {
        let model = makeModel()
        await driveToCruiseConflict(model)

        // Pilot requests the vector; the first turn is armed at the first interior vertex.
        model.requestVectorAroundWeather()
        XCTAssertEqual(model.weatherDeviationState, .vectoringAroundWeather)
        guard let line = model.weatherDeviationLine, line.count >= 3 else {
            return XCTFail("expected a committed mint line with at least one turn")
        }
        // The interior vertices (all but the start and the rejoin) are the turns.
        let interiorTurns = Array(1...(line.count - 2))
        XCTAssertNotNil(model.weatherDeviation.pendingRejoinHeading,
                        "issuing the vector should arm the first turn")

        var sawIntermediateTurn = false
        for (n, i) in interiorTurns.enumerated() {
            let apex = line[i]
            let expectedHeading = ApproachIntercept.normalizedHeading(Geo.bearing(from: apex, to: line[i + 1]))
            XCTAssertEqual(model.weatherDeviation.pendingRejoinHeading, expectedHeading,
                           "the turn at vertex \(i) should be armed toward the next vertex")

            let atcBefore = model.transcript.filter { $0.sender == .atc }.count

            // Fly to this turn vertex; the controller auto-issues the turn.
            var atApex = model.mock.state(for: .cruise)
            atApex.latitude = apex.latitude
            atApex.longitude = apex.longitude
            model.ingestStateForTesting(atApex)

            XCTAssertGreaterThan(model.transcript.filter { $0.sender == .atc }.count, atcBefore,
                                 "reaching turn \(i) must issue an automatic turn call")
            XCTAssertEqual(model.weatherDeviation.assignedHeading, expectedHeading,
                           "the auto-turn assigns the heading onto the next leg")
            XCTAssertEqual(model.weatherDeviationState, .vectoringAroundWeather,
                           "still advising clear of weather after each turn")

            let isFinal = (n == interiorTurns.count - 1)
            if isFinal {
                XCTAssertTrue(atcContains(model, "rejoin course"),
                              "the final turn rejoins the filed course")
                XCTAssertNil(model.weatherDeviation.pendingRejoinHeading,
                             "no turn stays armed after the final rejoin turn")
            } else {
                sawIntermediateTurn = true
                XCTAssertNotNil(model.weatherDeviation.pendingRejoinHeading,
                                "an intermediate turn re-arms the next turn (a side-hug has two)")
            }
        }

        // The mock's single large close cell forces a side-hug, so there is an
        // intermediate turn before the rejoin — the one that used to be missed.
        XCTAssertTrue(sawIntermediateTurn,
                      "the side-hug mint line has an intermediate turn out onto the parallel leg")
    }

    /// Flying wide of a turn vertex (never within the capture radius) must still
    /// trigger the turn once the aircraft passes abeam/beyond it along the leg into it.
    func testRejoinTurnFiresWhenPassingAbeamApexBeyondRadius() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        model.requestVectorAroundWeather()
        guard let line = model.weatherDeviationLine, line.count >= 3 else {
            return XCTFail("expected a committed mint line with at least one turn")
        }
        let start = line[0]
        let apex = line[1]
        let legBearing = Geo.bearing(from: start, to: apex)
        let armedHeading = model.weatherDeviation.pendingRejoinHeading
        XCTAssertNotNil(armedHeading)

        let atcBefore = model.transcript.filter { $0.sender == .atc }.count

        // A point 8 NM beyond the first turn vertex along the leg into it — outside the
        // capture radius, but past its abeam line.
        let beyondApex = Geo.destination(from: apex, bearingDegrees: legBearing, distanceNM: 8)
        XCTAssertGreaterThan(Geo.distanceNM(from: beyondApex, to: apex), 4,
                             "the test point must be outside the capture radius")
        var atBeyond = model.mock.state(for: .cruise)
        atBeyond.latitude = beyondApex.latitude
        atBeyond.longitude = beyondApex.longitude
        model.ingestStateForTesting(atBeyond)

        // Passing abeam fires the turn even outside the radius: it's issued onto the
        // armed heading (a "fly heading" turn call).
        XCTAssertGreaterThan(model.transcript.filter { $0.sender == .atc }.count, atcBefore,
                             "passing abeam the turn vertex fires the turn even outside the radius")
        XCTAssertEqual(model.weatherDeviation.assignedHeading, armedHeading,
                       "the turn is issued onto the armed heading")
        XCTAssertTrue(atcContains(model, "fly heading"))
    }

    /// The rejoin turn is only armed for the vectoring flow, and does not fire
    /// before the aircraft reaches the apex.
    func testRejoinTurnDoesNotFireBeforeReachingApex() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        model.requestVectorAroundWeather()
        XCTAssertNotNil(model.weatherDeviation.pendingRejoinHeading)

        let atcBefore = model.transcript.filter { $0.sender == .atc }.count
        // A tick well short of the apex must not trigger the turn.
        model.ingestStateForTesting(model.mock.state(for: .cruise))
        XCTAssertNotNil(model.weatherDeviation.pendingRejoinHeading,
                        "the turn stays armed until the aircraft reaches the apex")
        XCTAssertEqual(model.transcript.filter { $0.sender == .atc }.count, atcBefore,
                       "no automatic turn before the apex")
    }

    /// Flying the mint line all the way to its end (the flight-plan intercept) without
    /// reporting clear of weather auto-resumes own navigation and ends the deviation.
    func testAutoResumesOwnNavigationNearRouteIntercept() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        model.requestWeatherDeviation(.right)
        XCTAssertEqual(model.weatherDeviationState, .deviationApproved)
        guard let line = model.weatherDeviationLine, let end = line.last else {
            return XCTFail("expected a committed mint line ending on the route")
        }

        // Reach the rejoin end of the mint line, never having reported clear of weather.
        var atEnd = model.mock.state(for: .cruise)
        atEnd.latitude = end.latitude
        atEnd.longitude = end.longitude
        model.ingestStateForTesting(atEnd)

        XCTAssertTrue(atcContains(model, "resume own navigation"),
                      "reaching the intercept without reporting clear auto-resumes own navigation")
        XCTAssertEqual(model.weatherDeviationState, .none, "the deviation ends")
        XCTAssertNil(model.activeWeatherConflict, "the conflict clears on auto-resume")
    }

    // MARK: - Banner persists for a later reroute

    /// After the pilot contacts ATC and elects to continue on course, the weather
    /// is still ahead — so the "contact ATC" banner must come back up, letting the
    /// pilot re-open the deviation flow if they decide to reroute later.
    func testBannerReturnsAfterContinuingThroughWeather() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        XCTAssertNotNil(model.activeWeatherConflict)

        // The demo auto-issues the advisory, so the deviation card is up (not the banner).
        XCTAssertTrue(model.weatherDeviationCardVisible)
        XCTAssertFalse(model.weatherBannerVisible)

        // Pilot elects to continue on course; the deviation flow settles.
        model.continueThroughWeather()
        XCTAssertEqual(model.weatherDeviationState, .none)
        XCTAssertFalse(model.weatherDeviationCardVisible)

        // Weather is still ahead, so the banner comes back for a possible reroute.
        XCTAssertNotNil(model.activeWeatherConflict)
        XCTAssertTrue(model.weatherBannerVisible,
                      "banner must persist while weather is still ahead after continuing")

        // Tapping it re-opens the deviation flow.
        model.askCenterAboutWeather()
        XCTAssertTrue(model.weatherDeviationCardVisible,
                      "re-contacting ATC must re-open the weather-deviation card")
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

    /// An intermediate turn (onto the parallel leg of a side-hug) keeps vectoring and
    /// must NOT claim to rejoin course; the final turn does rejoin, naming the fix.
    func testRejoinInterceptVectorDistinguishesIntermediateFromFinalTurn() {
        let phr = WeatherDeviationPhraseology(engine: PhraseologyEngine(digitStyle: .individual, mode: .faa))
        let cs = phr.engine.callsign(airline: "United", flightNumber: "598", fallback: "")

        let intermediate = phr.rejoinInterceptVector(cs: cs, heading: 120, rejoinFix: "WAGON", finalTurn: false)
        XCTAssertTrue(intermediate.displayText.contains("fly heading 120"), intermediate.displayText)
        XCTAssertTrue(intermediate.displayText.contains("vectors around precipitation"), intermediate.displayText)
        XCTAssertFalse(intermediate.displayText.contains("rejoin course"),
                       "an intermediate turn is not yet rejoining course")

        let final = phr.rejoinInterceptVector(cs: cs, heading: 150, rejoinFix: "WAGON", finalTurn: true)
        XCTAssertTrue(final.displayText.contains("fly heading 150"), final.displayText)
        XCTAssertTrue(final.displayText.contains("rejoin course direct WAGON"), final.displayText)
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

    // MARK: - Confirm-clear hysteresis (no flicker)

    /// A single radar sample that momentarily loses a storm still ahead must NOT drop
    /// the mint line, the banner, or the deviation lifecycle — they're held until the
    /// route has tested clear long enough to confirm a clean route.
    func testMintLineAndBannerHoldThroughTransientRadarClear() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        XCTAssertNotNil(model.activeWeatherConflict)
        XCTAssertNotNil(model.weatherDeviationLine, "a mint line is drawn for the conflict")

        // A noisy resample momentarily reports the sky clear (the storm is still there).
        model.radarOverlay.mockCells = []
        model.recomputeWeatherHazards()

        XCTAssertNotNil(model.activeWeatherConflict,
                        "a single empty sample must not drop a just-detected conflict")
        XCTAssertNotNil(model.weatherDeviationLine, "the mint line holds through a transient clear")
        XCTAssertEqual(model.weatherDeviationState, .awaitingPilotIntentions,
                       "the deviation lifecycle is not torn down on a transient clear")
    }

    /// Once the route has tested clear past the confirm window, the mint line, banner
    /// and lifecycle are removed — a confirmed clean route.
    func testConfirmedClearRemovesMintLineAndBanner() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        XCTAssertNotNil(model.activeWeatherConflict)

        model.radarOverlay.mockCells = []
        model.recomputeWeatherHazards()
        XCTAssertNotNil(model.activeWeatherConflict, "held within the confirm window")

        // Let the confirm window elapse: the next clear sample confirms a clean route.
        model.expireWeatherClearWindowForTesting()
        model.recomputeWeatherHazards()
        XCTAssertNil(model.activeWeatherConflict, "confirmed clear removes the conflict")
        XCTAssertNil(model.weatherDeviationLine, "confirmed clear removes the mint line")
        XCTAssertFalse(model.weatherBannerVisible)
        XCTAssertEqual(model.weatherDeviationState, .none, "lifecycle rolls back after a confirmed clear")
    }

    // MARK: - Far weather is monitored but not drawn ("no weather, crazy mint line")

    /// A conflict whose weather is beyond the draw range must NOT put a mint line (or its
    /// rejoin marker) on the map — the reported "no weather nearby, crazy mint line
    /// shooting across the map" case. The same conflict inside the draw range does draw.
    func testFarConflictDrawsNoMintLine() {
        // weatherDeviationLine keys off the active conflict (no committed path yet), so a
        // synthesized conflict exercises the draw gate directly.
        let model = makeModel()
        let pos = CLLocationCoordinate2D(latitude: 40, longitude: -95)
        let apex = Geo.destination(from: pos, bearingDegrees: 20, distanceNM: 60)
        let end = Geo.destination(from: pos, bearingDegrees: 0, distanceNM: 160)
        let hazard = WeatherHazard(source: .noaaRadar, phenomenon: .precipitation, intensity: .heavy,
                                   geometry: .polygon(box(around: apex, half: 0.3)), confidence: .high)
        func conflict(drawable: Bool) -> RouteWeatherConflict {
            RouteWeatherConflict(
                hazard: hazard, distanceAheadNM: drawable ? 70 : 140, relativeBearingDegrees: 0,
                leftClock: 12, centerClock: 12, rightClock: 12, estimatedTimeMinutes: nil,
                severity: .heavy, leftBypassScore: 0, rightBypassScore: 0,
                recommendedDirection: .right, recommendedDeviationDegrees: 20,
                rejoinFix: Waypoint(name: "RJOIN", latitude: end.latitude, longitude: end.longitude),
                originalSegment: nil, shouldPrompt: false, withinTacticalRange: false,
                withinDrawRange: drawable, intersectionArea: [], deviationPath: [pos, apex, end])
        }

        model.activeWeatherConflict = conflict(drawable: false)
        XCTAssertNil(model.weatherDeviationLine, "far weather must not draw a mint line")
        XCTAssertNil(model.weatherRejoinMarker, "far weather must not draw a rejoin marker")

        model.activeWeatherConflict = conflict(drawable: true)
        XCTAssertNotNil(model.weatherDeviationLine, "weather in the draw range draws the mint line")
        XCTAssertNotNil(model.weatherRejoinMarker, "weather in the draw range draws the rejoin marker")
    }

    // MARK: - Strategic preview (faint lines for each system ahead, incl. from the gate)

    /// The whole route's deviations can be eyeballed at once: a faint preview reroute is
    /// produced for **each** distinct weather system ahead along the filed route — even
    /// on the ground, with no aircraft telemetry yet — so lines can be verified from the
    /// gate before takeoff.
    func testStrategicPreviewDrawsALineForEachSystemAhead() {
        let model = makeModel()
        model.flightPlan.waypoints = []   // straight dep→dest so the cells sit on the corridor

        let dep = model.mock.route.depCoord
        let dest = model.mock.route.destCoord
        let course = Geo.bearing(from: dep, to: dest)
        func cellAt(_ nm: Double) -> RadarCell {
            RadarCell(polygon: box(around: Geo.destination(from: dep, bearingDegrees: course, distanceNM: nm),
                                   half: 0.2),
                      intensity: .heavy)
        }
        // Two systems well apart (beyond the 30 NM cluster margin) along the route, both
        // beyond the ~75 NM draw range so neither is drawn solid — both preview faint.
        model.radarOverlay.mockCells = [cellAt(110), cellAt(230)]
        model.recomputeWeatherHazards()   // no ingest: still at the departure gate

        XCTAssertNil(model.aircraftState.coordinate, "this is the on-the-ground case")
        XCTAssertGreaterThanOrEqual(model.weatherDeviationPreviews.count, 2,
                                    "a faint preview line is drawn for each distinct system ahead")
        if model.weatherDeviationPreviews.count >= 2 {
            let a = model.weatherDeviationPreviews[0].first!
            let b = model.weatherDeviationPreviews[1].first!
            XCTAssertGreaterThan(Geo.distanceNM(from: a, to: b), 30,
                                 "each system's preview is a separate line further down the route")
        }
    }

    /// Once a system is being worked (drawn solid), the preview lines are the systems
    /// *beyond* it — the solid one is not duplicated as a faint line.
    func testPreviewExcludesTheSystemDrawnSolid() async {
        let model = makeModel()
        await driveToCruiseConflict(model)   // the close demo cell becomes the solid active line
        guard model.weatherDeviationLine != nil else {
            return XCTFail("expected the near system drawn solid")
        }
        model.flightPlan.waypoints = []   // straight dep→dest so the added far cell sits on the corridor
        // Add a second, far system beyond the demo cell.
        guard let pos = model.aircraftState.coordinate else { return XCTFail("no cruise position") }
        let course = Geo.bearing(from: model.mock.route.depCoord, to: model.mock.route.destCoord)
        let far = Geo.destination(from: pos, bearingDegrees: course, distanceNM: 200)
        model.radarOverlay.mockCells = model.radarOverlay.mockCells
            + [RadarCell(polygon: box(around: far, half: 0.2), intensity: .heavy)]
        model.recomputeWeatherHazards()

        // The solid line is the near cell; the far cell previews faint. The preview set
        // must not include a line starting back at the (solid) near system.
        guard let solidStart = model.weatherDeviationLine?.first else { return XCTFail() }
        for preview in model.weatherDeviationPreviews {
            guard let s = preview.first else { continue }
            XCTAssertGreaterThan(Geo.distanceNM(from: s, to: solidStart), 20,
                                 "previews are the upcoming systems, not the one drawn solid")
        }
    }

    // MARK: - Deferred deviation (reroute drawn ahead → hold the turn, issue it at the turn-out)

    /// When the reroute is drawn ahead, requesting a deviation approves it but holds the
    /// turn: the controller says "continue, expect the turn in X miles". Only once the
    /// aircraft reaches the turn-out point at the start of the mint line does the
    /// controller issue the beginning turn.
    func testDeferredDeviationHoldsTurnThenIssuesItAtTheTurnOut() async {
        let model = makeModel()
        await driveToCruiseConflict(model)   // settles cruise/enroute state (weather flow allowed)
        guard let pos = model.aircraftState.coordinate else { return XCTFail("no cruise position") }

        // Replace the demo cell with a narrow one ~60 NM ahead on the filed course, so its
        // reroute is drawn ahead with the turn-out well in front of the aircraft.
        let course = Geo.bearing(from: model.mock.route.depCoord, to: model.mock.route.destCoord)
        let center = Geo.destination(from: pos, bearingDegrees: course, distanceNM: 60)
        model.radarOverlay.mockCells = [RadarCell(polygon: box(around: center, half: 0.15), intensity: .moderate)]
        model.recomputeWeatherHazards()
        guard let v0 = model.activeWeatherConflict?.deviationPath.first else {
            return XCTFail("expected a drawn-ahead conflict")
        }
        XCTAssertGreaterThan(Geo.distanceNM(from: pos, to: v0), 6,
                             "the turn-out point must be drawn ahead of the aircraft")

        // Request the deviation: approved, but the turn is held.
        model.requestWeatherDeviation(.right)
        XCTAssertEqual(model.weatherDeviationState, .deviationApproved)
        XCTAssertTrue(atcContains(model, "expect the turn"),
                      "a deviation drawn ahead is approved with the turn held")
        XCTAssertNotNil(model.weatherDeviation.deviationStartLatitude,
                        "the beginning turn is armed at the turn-out point")
        XCTAssertNil(model.weatherDeviation.assignedHeading, "no turn is assigned while held")
        XCTAssertNotNil(model.weatherDeviationLine, "the mint line is drawn while the turn is held")

        // Fly to the turn-out; the controller now issues the beginning turn.
        var atV0 = model.mock.state(for: .cruise)
        atV0.latitude = v0.latitude
        atV0.longitude = v0.longitude
        model.ingestStateForTesting(atV0)
        XCTAssertEqual(model.weatherDeviationState, .vectoringAroundWeather,
                       "reaching the turn-out begins the deviation")
        XCTAssertTrue(atcContains(model, "fly heading"), "the beginning turn is issued at the turn-out")
        XCTAssertNil(model.weatherDeviation.deviationStartLatitude,
                     "the held turn is consumed once issued")
    }

    // MARK: - Committed mint line is locked

    /// Once the pilot commits to a vector, the mint line freezes to the path being
    /// flown: neither a fresh radar sample nor an elapsed confirm window moves or
    /// drops it. Only clear-of-weather releases it.
    func testCommittedMintLineIsLockedThroughRadarResamples() async {
        let model = makeModel()
        await driveToCruiseConflict(model)

        model.requestVectorAroundWeather()
        XCTAssertEqual(model.weatherDeviationState, .vectoringAroundWeather)
        guard let locked = model.weatherDeviationLine else {
            return XCTFail("expected a frozen mint line after committing to a vector")
        }

        // A radar clear plus an elapsed window would remove a not-yet-committed line —
        // the committed line stays locked.
        model.radarOverlay.mockCells = []
        model.expireWeatherClearWindowForTesting()
        model.recomputeWeatherHazards()
        XCTAssertEqual(model.weatherDeviationLine?.count, locked.count,
                       "the committed mint line stays drawn, locked, through a radar clear")
        XCTAssertEqual(model.weatherDeviationLine?.first?.latitude, locked.first?.latitude)
        XCTAssertEqual(model.weatherDeviationLine?.last?.longitude, locked.last?.longitude)

        // Reporting clear of weather releases the lock and removes the line.
        model.reportClearOfWeather()
        XCTAssertNil(model.weatherDeviationLine, "clear of weather releases the locked mint line")
    }

    // MARK: - Re-vector while committed (new weather ahead)

    /// While already committed to a deviation, Vectors stays available so the pilot
    /// can re-plan around NEW weather that appears on the reroute — re-issuing a
    /// vector, mint line and rejoin turn computed from the current position.
    func testReVectorWhileCommittedReplansAroundNewWeather() async {
        let model = makeModel()
        await driveToCruiseConflict(model)

        model.requestVectorAroundWeather()
        XCTAssertEqual(model.weatherDeviationState, .vectoringAroundWeather)
        XCTAssertTrue(model.weatherActions.contains(.requestVector),
                      "Vectors stays available while flying a lateral deviation")
        guard let firstPath = model.weatherDeviation.committedDeviationPath, firstPath.count >= 2 else {
            return XCTFail("expected a committed mint line after the first vector")
        }

        // New weather straddles the committed mint line ahead of the aircraft.
        let mid = firstPath[firstPath.count / 2].coordinate
        model.radarOverlay.mockCells = [RadarCell(polygon: box(around: mid, half: 0.3), intensity: .heavy)]
        model.recomputeWeatherHazards()

        // Re-request vectors: the reroute is re-planned and the rejoin turn re-armed.
        model.requestVectorAroundWeather()
        XCTAssertEqual(model.weatherDeviationState, .vectoringAroundWeather)
        XCTAssertNotNil(model.weatherDeviation.committedDeviationPath,
                        "the re-vector re-freezes a committed mint line")
        XCTAssertNotNil(model.weatherDeviation.pendingRejoinHeading,
                        "a fresh re-vector re-arms the rejoin turn")
        XCTAssertTrue(atcContains(model, "fly heading"), "the re-vector assigns a fresh heading")
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
