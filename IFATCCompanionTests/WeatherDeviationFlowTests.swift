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
        // The locked deviations hold until refreshed, so refresh against the now-clear
        // radar to clear the precip reroute before the ride advisory takes over.
        model.radarOverlay.mockCells = []
        model.sigmets = [SIGMET(raw: "SEV TURB", hazard: "TURB", severity: "SEV",
                                area: box(around: pos, half: 0.8))]
        model.recomputeRideItems()
        await model.refreshDeviations()

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

    // MARK: - True course → assigned heading (variation + wind)

    /// A cruising state carrying a known magnetic variation and a known wind, built from
    /// the mock's cruise fix so the deviation geometry is unchanged. The ground vector is
    /// the air vector plus the wind, so the app solves back exactly this wind.
    private func windyCruise(_ model: AppModel,
                             variationEast: Double,
                             windFrom: Double,
                             windSpeed: Double) -> AircraftState {
        func rad(_ d: Double) -> Double { d * .pi / 180 }
        var s = model.mock.state(for: .cruise)
        let trueHeading = s.trueHeading ?? 0
        let tas = s.trueAirspeed ?? 460
        s.heading = (trueHeading - variationEast + 360).truncatingRemainder(dividingBy: 360)
        let toward = windFrom + 180
        let east = tas * sin(rad(trueHeading)) + windSpeed * sin(rad(toward))
        let north = tas * cos(rad(trueHeading)) + windSpeed * cos(rad(toward))
        s.groundSpeed = (east * east + north * north).squareRoot()
        var track = atan2(east, north) * 180 / .pi
        if track < 0 { track += 360 }
        s.track = track
        return s
    }

    /// The mint line is drawn in true degrees, but the pilot flies a magnetic heading bug
    /// through a wind that pushes the aircraft sideways for the whole length of a leg.
    /// The assigned vector must therefore be the leg's course crabbed into the wind and
    /// stepped into the magnetic frame — not the raw bearing off the map.
    func testWeatherVectorIsCorrectedForVariationAndWind() async {
        let model = makeModel()
        await driveToCruiseConflict(model)

        let variationEast = 8.0, windFrom = 200.0, windSpeed = 45.0
        // The wind estimate is smoothed across ticks, so let it settle before reading the
        // vector it produces — as it would over a few seconds of live telemetry.
        let windy = windyCruise(model, variationEast: variationEast,
                                windFrom: windFrom, windSpeed: windSpeed)
        for _ in 0..<25 { model.ingestStateForTesting(windy) }

        guard let conflict = model.activeWeatherConflict,
              conflict.deviationPath.count >= 2,
              let pos = model.aircraftState.coordinate else {
            return XCTFail("expected a conflict with a deviation path")
        }
        let apex = conflict.deviationPath[1]
        let course = Geo.bearing(from: pos, to: apex)
        let tas = windy.trueAirspeed ?? 460

        model.requestVectorAroundWeather()
        guard let assigned = model.weatherDeviation.assignedHeading else {
            return XCTFail("expected an assigned vector")
        }

        // Worked independently of the solver, from the wind this state was built to carry.
        let crab = asin(windSpeed * sin((windFrom - course) * .pi / 180) / tas) * 180 / .pi
        let expected = ((Int((course + crab - variationEast).rounded()) % 360) + 360) % 360
        var error = abs(Double(assigned - expected)).truncatingRemainder(dividingBy: 360)
        if error > 180 { error = 360 - error }
        XCTAssertLessThanOrEqual(error, 1,
                                 "the vector must be the leg crabbed into wind, in magnetic degrees")

        // And it must actually differ from the raw map bearing — the bug this fixes.
        let raw = ((Int(course.rounded()) % 360) + 360) % 360
        XCTAssertNotEqual(assigned, raw,
                          "assigning the true bearing walks the aircraft off the drawn line")
    }

    /// Every auto-issued turn along the line gets the same treatment, not just the first:
    /// the armed turn stays in true degrees (it is compared against the drawn geometry),
    /// while the heading spoken to the pilot is the corrected one.
    func testAutoTurnsAlongTheMintLineAreAlsoCorrected() async {
        let model = makeModel()
        await driveToCruiseConflict(model)

        let variationEast = 8.0, windFrom = 200.0, windSpeed = 45.0
        let windy = windyCruise(model, variationEast: variationEast,
                                windFrom: windFrom, windSpeed: windSpeed)
        for _ in 0..<25 { model.ingestStateForTesting(windy) }

        model.requestVectorAroundWeather()
        guard let line = model.weatherDeviationLine, line.count >= 3,
              let armed = model.weatherDeviation.pendingRejoinHeading else {
            return XCTFail("expected a committed mint line with an armed turn")
        }
        let apex = line[1]
        let course = Geo.bearing(from: apex, to: line[2])
        XCTAssertEqual(armed, ((Int(course.rounded()) % 360) + 360) % 360,
                       "the armed turn stays a true course, matching the drawn line")

        // Fly to the turn vertex; the controller issues it.
        let atcBefore = model.transcript.filter { $0.sender == .atc }.count
        var atApex = windy
        atApex.latitude = apex.latitude
        atApex.longitude = apex.longitude
        model.ingestStateForTesting(atApex)
        XCTAssertGreaterThan(model.transcript.filter { $0.sender == .atc }.count, atcBefore,
                             "reaching the turn vertex must issue the turn")

        guard let assigned = model.weatherDeviation.assignedHeading else {
            return XCTFail("reaching the turn must assign a heading")
        }
        let crab = asin(windSpeed * sin((windFrom - course) * .pi / 180)
                        / (windy.trueAirspeed ?? 460)) * 180 / .pi
        let expected = ((Int((course + crab - variationEast).rounded()) % 360) + 360) % 360
        var error = abs(Double(assigned - expected)).truncatingRemainder(dividingBy: 360)
        if error > 180 { error = 360 - error }
        XCTAssertLessThanOrEqual(error, 1, "the turn spoken to the pilot is corrected")
        XCTAssertNotEqual(assigned, armed,
                          "the spoken heading is not the raw true course of the leg")
    }

    /// A sim that exposes no true heading gives the app nothing to measure variation
    /// against, and no way to solve the wind triangle. Vectors must then come out exactly
    /// as they always did rather than being corrected by a guess.
    func testVectorIsUncorrectedWhenTheSimExposesNoTrueHeading() async {
        let model = makeModel()
        // Drive to the cruise conflict as usual, but with the true heading stripped from
        // every tick — so no sample ever establishes a variation or a wind to correct by.
        await model.refreshWeather()
        for _ in 0..<4 {
            var s = model.mock.state(for: .cruise)
            s.trueHeading = nil
            model.ingestStateForTesting(s)
        }

        guard let conflict = model.activeWeatherConflict,
              conflict.deviationPath.count >= 2,
              let pos = model.aircraftState.coordinate else {
            return XCTFail("expected a conflict with a deviation path")
        }
        let raw = ApproachIntercept.normalizedHeading(Geo.bearing(from: pos, to: conflict.deviationPath[1]))

        model.requestVectorAroundWeather()
        XCTAssertEqual(model.weatherDeviation.assignedHeading, raw,
                       "with nothing to measure, the vector is the plain map bearing")
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
        // Swing the true heading with it: a magnetic heading 70° off the true heading
        // would read as 70° of magnetic variation, which is not what this test is about.
        deviated.trueHeading = skewed
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

    // MARK: - Returning from the background must not queue up turns on a jumped fix

    /// Regression: the app keeps polling while backgrounded (audio mode), but the Connect
    /// socket freezes, so the first fix after returning jumps to the live position. The
    /// vectoring flow must NOT then replay every turn the aircraft flew during the gap as
    /// its own "fly heading …" call — the reported queue of near-identical vector calls.
    /// On the jumped tick it re-syncs the armed turn to the current position and issues
    /// nothing; the flow then completes normally on the following continuous ticks.
    func testBackgroundResyncCollapsesTheCatchUpTurnQueue() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        model.requestVectorAroundWeather()
        XCTAssertEqual(model.weatherDeviationState, .vectoringAroundWeather)
        guard let line = model.weatherDeviationLine, line.count >= 3, let rejoin = line.last else {
            return XCTFail("expected a committed mint line with at least one turn")
        }
        XCTAssertNotNil(model.weatherDeviation.pendingTurnIndex, "issuing the vector arms the first turn")

        func flyHeadingCalls() -> Int {
            model.transcript.filter { $0.sender == .atc && $0.displayText.contains("fly heading") }.count
        }
        let callsBefore = flyHeadingCalls()

        // Return from the background: the first fresh fix has jumped past every interior turn.
        model.markTelemetryResyncPendingForTesting()
        var jumped = model.mock.state(for: .cruise)
        jumped.latitude = rejoin.latitude
        jumped.longitude = rejoin.longitude
        model.ingestStateForTesting(jumped)

        // The jumped tick issues nothing and clears the armed turn — no retroactive replay.
        XCTAssertEqual(flyHeadingCalls(), callsBefore,
                       "a jumped fix must not replay the passed turns as a queue of vector calls")
        XCTAssertNil(model.weatherDeviation.pendingTurnIndex,
                     "past every interior turn, the armed turn is cleared rather than fired retroactively")

        // The following continuous tick completes the flow normally — resume own navigation,
        // never a burst of the passed "fly heading" turns.
        model.ingestStateForTesting(jumped)
        XCTAssertEqual(flyHeadingCalls(), callsBefore,
                       "no vector turns are replayed on the continuous catch-up tick either")
        XCTAssertTrue(atcContains(model, "resume own navigation"),
                      "reaching the rejoin end resumes own navigation on the continuous tick")
        XCTAssertEqual(model.weatherDeviationState, .none, "the deviation ends cleanly, with no queued turns")
    }

    /// Regression: if the background jump carries the aircraft **past the held entry turn**
    /// of a drawn-ahead deviation, the maneuver must be re-derived from the current
    /// position — never left on the stale entry (pinned behind the aircraft), which would
    /// fly it straight through the weather. On the resync it either vectors onto the reroute
    /// now or re-holds the entry at a fresh turn-out ahead, but the held entry is never left
    /// behind the aircraft.
    func testBackgroundResyncReanchorsEntryWhenJumpedPastHeldTurn() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        guard let pos0 = model.aircraftState.coordinate else { return XCTFail("no cruise position") }

        // A narrow cell ~60 NM ahead: the reroute is drawn ahead with the turn-out well in
        // front of the aircraft, and the deviation is approved with the beginning turn held.
        let course = Geo.bearing(from: model.mock.route.depCoord, to: model.mock.route.destCoord)
        let center = Geo.destination(from: pos0, bearingDegrees: course, distanceNM: 60)
        model.radarOverlay.mockCells = [RadarCell(polygon: box(around: center, half: 0.15), intensity: .moderate)]
        await model.refreshDeviations()
        model.requestWeatherDeviation(.right)
        XCTAssertEqual(model.weatherDeviationState, .deviationApproved)
        guard let v0 = model.activeWeatherConflict?.deviationPath.first else {
            return XCTFail("expected a drawn-ahead turn-out")
        }
        XCTAssertNotNil(model.weatherDeviation.deviationStartLatitude, "the entry turn is held ahead")

        // Return from the background having jumped 10 NM past the held turn-out (still short
        // of the weather) — the stale entry is now behind the aircraft.
        let pastEntry = Geo.destination(from: v0, bearingDegrees: course, distanceNM: 10)
        model.markTelemetryResyncPendingForTesting()
        var jumped = model.mock.state(for: .cruise)
        jumped.latitude = pastEntry.latitude
        jumped.longitude = pastEntry.longitude
        model.ingestStateForTesting(jumped)

        // The deviation is still actively managed, and the entry is re-anchored to the
        // current position — a re-held entry (if any) sits at/ahead of the aircraft, never
        // pinned behind it on the original stale turn-out.
        XCTAssertNotEqual(model.weatherDeviationState, .none, "the deviation is not silently dropped")
        XCTAssertNotEqual(model.weatherDeviationState, .resumedOwnNavigation)
        if let hLat = model.weatherDeviation.deviationStartLatitude,
           let hLon = model.weatherDeviation.deviationStartLongitude {
            let held = CLLocationCoordinate2D(latitude: hLat, longitude: hLon)
            let toHeld = Geo.bearing(from: pastEntry, to: held)
            let alongCourse = Geo.distanceNM(from: pastEntry, to: held) * cos((toHeld - course) * .pi / 180)
            XCTAssertGreaterThan(alongCourse, -2.0,
                                 "a re-held entry turn is at/ahead of the aircraft, not pinned behind it")
        }
    }

    /// Regression: an issued-but-unanswered advisory must survive returning from the
    /// background. While away, the frozen socket / a confirm-clear window that elapsed on
    /// wall-clock could otherwise read the route momentarily clear and tear the lifecycle
    /// down — so the response card was gone on return and the pilot had to re-tap the
    /// banner to get the deviation buttons back. A resync tick holds the conflict and
    /// keeps the card up.
    func testBackgroundResyncKeepsUnansweredAdvisoryCardUp() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        XCTAssertEqual(model.weatherDeviationState, .awaitingPilotIntentions)
        XCTAssertTrue(model.weatherDeviationCardVisible, "the advisory shows the response card")

        // The worst case on return: the confirm-clear window has elapsed and the locked
        // deviation momentarily selects clear (no active conflict is detected this tick).
        model.expireWeatherClearWindowForTesting()
        model.lockedDeviations = []
        model.markTelemetryResyncPendingForTesting()
        model.ingestStateForTesting(model.mock.state(for: .cruise))

        XCTAssertEqual(model.weatherDeviationState, .awaitingPilotIntentions,
                       "a resync tick must not tear down an unanswered advisory")
        XCTAssertTrue(model.weatherDeviationCardVisible,
                      "the weather-deviation response card is still shown after returning from the background")
        XCTAssertNotNil(model.activeWeatherConflict, "the conflict is held through the discontinuity")
    }

    /// Regression (reported): the pilot had an **accepted** deviation with its turn still
    /// held ahead — "continue present heading, expect the turn in X miles" — backgrounded the
    /// app, and came back to the controller asking "…say intentions" all over again, as if
    /// nothing had been agreed, with no response card on screen.
    ///
    /// The cause was the resync re-plan believing a jumped fix: the radar sample is stale
    /// after the gap, nothing re-solved from the new position, and the approved deviation was
    /// cancelled on the spot — silently, and clearing the "already worked" flag, so the
    /// near-turn auto-advisory re-opened the same weather seconds later. An accepted clearance
    /// must survive a fix the hysteresis itself refuses to read a clear route from.
    func testBackgroundResyncKeepsAcceptedDeviationAndDoesNotReAdvise() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        guard let pos0 = model.aircraftState.coordinate else { return XCTFail("no cruise position") }

        // A narrow cell ~60 NM ahead: the reroute is drawn ahead, and the pilot's request is
        // approved with the beginning turn held ("expect the turn in …").
        let course = Geo.bearing(from: model.mock.route.depCoord, to: model.mock.route.destCoord)
        let center = Geo.destination(from: pos0, bearingDegrees: course, distanceNM: 60)
        model.radarOverlay.mockCells = [RadarCell(polygon: box(around: center, half: 0.15), intensity: .moderate)]
        await model.refreshDeviations()
        model.requestVectorAroundWeather()
        XCTAssertEqual(model.weatherDeviationState, .deviationApproved)
        XCTAssertNotNil(model.weatherDeviation.deviationStartLatitude, "the entry turn is held ahead")
        XCTAssertTrue(atcContains(model, "expect the turn"), "the approval holds the turn")
        guard let v0 = model.activeWeatherConflict?.deviationPath.first else {
            return XCTFail("expected a drawn-ahead turn-out")
        }

        func advisories() -> Int {
            model.transcript.filter { $0.sender == .atc && $0.displayText.contains("Say intentions") }.count
        }
        let advisoriesBefore = advisories()

        // Return from the background 10 NM short of the held turn-out — inside the 15 NM
        // near-turn auto-advisory range — with the radar sample gone stale while away, so
        // nothing re-solves from the new position.
        model.radarOverlay.mockCells = []
        model.expireWeatherClearWindowForTesting()
        let shortOfEntry = Geo.destination(from: v0, bearingDegrees: course + 180, distanceNM: 10)
        model.markTelemetryResyncPendingForTesting()
        var jumped = model.mock.state(for: .cruise)
        jumped.latitude = shortOfEntry.latitude
        jumped.longitude = shortOfEntry.longitude
        model.ingestStateForTesting(jumped)

        XCTAssertEqual(model.weatherDeviationState, .deviationApproved,
                       "an accepted deviation is not cancelled off a jumped fix")
        XCTAssertNotNil(model.weatherDeviation.deviationStartLatitude,
                        "the held turn stays armed — the pilot is still flying toward it")
        XCTAssertTrue(model.weatherDeviationCardVisible,
                      "the deviation card is still up on return from the background")

        // …and the following continuous tick doesn't re-open the advisory either.
        model.ingestStateForTesting(jumped)
        XCTAssertEqual(advisories(), advisoriesBefore,
                       "the controller must not re-ask intentions for weather the pilot already deviated for")
        XCTAssertEqual(model.weatherDeviationState, .deviationApproved)
    }

    /// When a held turn genuinely can no longer be flown — the turn-out is behind the
    /// aircraft and no revised line solves from where it is — the clearance is cancelled
    /// **out loud**. The pilot was told to continue on course and expect a turn, so dropping
    /// it in silence leaves them flying toward a turn that never comes.
    func testCancelledHeldDeviationIsAnnouncedAndEndsTheLifecycle() {
        let phr = WeatherDeviationPhraseology(engine: PhraseologyEngine(digitStyle: .individual, mode: .faa))
        let eng = WeatherDeviationEngine(phraseology: phr)
        let cs = phr.engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        var ctx = WeatherDeviationContext()
        ctx.state = .deviationApproved

        let result = eng.cancelHeldDeviation(cs: cs, context: ctx, facility: .center)

        XCTAssertNil(result.pilot, "the controller initiates the cancellation")
        guard let atc = result.atc.first else { return XCTFail("expected a cancellation call") }
        XCTAssertTrue(atc.displayText.contains("weather deviation cancelled"), atc.displayText)
        XCTAssertTrue(atc.displayText.contains("resume own navigation"), atc.displayText)
        XCTAssertTrue(atc.readback?.displayText.contains("Resume own navigation") ?? false,
                      atc.readback?.displayText ?? "")
        XCTAssertEqual(result.context.state, .resumedOwnNavigation,
                       "the deviation is no longer committed")
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

    /// The locked deviations hold in place even when the radar momentarily (or lastingly)
    /// clears — they are removed only when the pilot taps "Refresh Deviations", which
    /// re-runs the whole-route search against the now-clear radar and locks an empty set.
    func testRefreshDeviationsClearsMintLineAndBannerWhenWeatherIsGone() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        XCTAssertNotNil(model.activeWeatherConflict)

        // The radar clears, but a plain recompute never re-solves the locked deviations —
        // the line and banner stay put (they don't disappear on their own).
        model.radarOverlay.mockCells = []
        model.recomputeWeatherHazards()
        XCTAssertNotNil(model.activeWeatherConflict, "locked deviations hold through a radar clear")
        XCTAssertNotNil(model.weatherDeviationLine, "the mint line holds until a refresh")

        // The pilot refreshes: the search re-runs against the clear radar and locks nothing.
        await model.refreshDeviations()
        XCTAssertNil(model.activeWeatherConflict, "a refresh against a clear route removes the conflict")
        XCTAssertNil(model.weatherDeviationLine, "a refresh against a clear route removes the mint line")
        XCTAssertFalse(model.weatherBannerVisible)
        XCTAssertEqual(model.weatherDeviationState, .none, "lifecycle rolls back after the refresh")
    }

    // MARK: - Automatic 5-min deviation refresh (skipped while deviating)

    /// The periodic auto-refresh re-runs the whole-route search against the latest radar — the
    /// Refresh Deviations button without a tap — when the pilot has not committed to a
    /// deviation. Here the weather clears, so the re-solve drops the now-stale mint line.
    func testAutoDeviationRefreshRerunsTheSearchWhenNotDeviating() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        XCTAssertNotNil(model.weatherDeviationLine, "the demo storm locks a mint line")
        XCTAssertFalse(model.weatherDeviation.state.isCommittedDeviation,
                       "precondition: the pilot has not committed to a deviation")

        // The radar clears; a plain recompute keeps the locked line (locked until refreshed).
        model.radarOverlay.mockCells = []
        model.recomputeWeatherHazards()
        XCTAssertNotNil(model.weatherDeviationLine, "locked deviations hold through a radar clear")

        // The automatic refresh (not deviating) re-runs the search and drops the stale line.
        let refreshed = model.autoRefreshDeviationsUnlessDeviating()
        XCTAssertTrue(refreshed, "not deviating → the auto-refresh runs")
        XCTAssertNil(model.activeWeatherConflict, "the auto-refresh clears the conflict for cleared weather")
        XCTAssertNil(model.weatherDeviationLine, "the auto-refresh removes the mint line")
    }

    /// While the pilot is committed to and flying a deviation, the automatic refresh must not
    /// fire — re-proposing the mint line under them would move the path they're following. The
    /// committed deviation is left untouched until it clears.
    func testAutoDeviationRefreshIsSkippedWhileDeviating() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        XCTAssertNotNil(model.weatherDeviationLine, "the demo storm locks a mint line")

        // The pilot is committed to and flying the deviation.
        model.weatherDeviation.state = .vectoringAroundWeather
        XCTAssertTrue(model.weatherDeviation.state.isCommittedDeviation)
        let lockedBefore = model.lockedDeviations.count

        // Even with the weather cleared (a refresh WOULD drop the line), the auto-refresh must
        // step aside while a deviation is being flown.
        model.radarOverlay.mockCells = []
        let refreshed = model.autoRefreshDeviationsUnlessDeviating()
        XCTAssertFalse(refreshed, "a committed deviation blocks the automatic refresh")
        XCTAssertEqual(model.lockedDeviations.count, lockedBefore, "the locked set is left intact")
        XCTAssertNotNil(model.weatherDeviationLine, "the committed mint line stays put while deviating")
    }

    // MARK: - Live-mode lock waits for radar cells to actually land

    /// Regression: in **live** mode the whole-route deviation set is locked once and then
    /// held. That lock must wait until a radar sample has actually **produced cells** —
    /// not merely started. `lastPrecipSampleAt` is stamped at the *start* of a sample,
    /// before the async image fetch lands, so gating the lock on it let a telemetry
    /// recompute that ran mid-fetch (cells still empty) lock an empty set — and because the
    /// set never re-locks, the mint line then never drew in live mode even with a storm
    /// dead on the route. The lock is gated on `livePrecipCellsReady` instead, which flips
    /// true only once the decoded cells are in place. (Mock mode is unaffected: its cells
    /// are set synchronously before any recompute runs.)
    func testLiveModeLockWaitsForRadarCellsBeforeLocking() {
        let model = makeModel()
        model.settings.mockMode = false          // live path: hazards come from sampledCells
        model.flightPlan.waypoints = []          // straight dep→dest so the cell sits on the route

        guard let dep = AirportDatabase.shared.coordinate(for: model.flightPlan.departure),
              let dest = AirportDatabase.shared.coordinate(for: model.flightPlan.destination) else {
            return XCTFail("test needs located CONUS endpoints for radar coverage")
        }
        let course = Geo.bearing(from: dep, to: dest)
        let stormCenter = Geo.destination(from: dep, bearingDegrees: course, distanceNM: 45)
        let storm = RadarCell(polygon: box(around: stormCenter, half: 0.25), intensity: .heavy)

        // The decoded storm cells are already present, but the sample has not been marked
        // ready (`livePrecipCellsReady == false`) — exactly the mid-fetch window where a
        // telemetry recompute used to lock. The lock must be held: nothing is locked yet.
        model.radarOverlay.sampledCells = [storm]
        model.livePrecipCellsReady = false
        model.recomputeWeatherHazards()
        XCTAssertTrue(model.lockedDeviations.isEmpty,
                      "the deviation must not lock before the sample is marked ready")
        XCTAssertNil(model.weatherDeviationLine,
                     "no mint line while the first live sample is still in flight")

        // The sample completes: cells are marked ready. The lock now takes and the reroute
        // is found — proving the earlier mid-fetch recompute did not permanently lock empty.
        model.livePrecipCellsReady = true
        model.recomputeWeatherHazards()
        XCTAssertFalse(model.lockedDeviations.isEmpty,
                       "once radar cells are ready the whole-route deviation locks")
        XCTAssertNotNil(model.weatherDeviationLine,
                        "the mint line draws in live mode once the storm's cells have landed")
    }

    /// Live mode renders the deviation set **synchronously** the moment radar lands: the full
    /// optimized search runs directly in one pass (the earlier "quick hug first, refine in the
    /// background" two-step is gone, now that the slow part — radar-polygon sampling — is
    /// fixed), so a reroute shows at once with no follow-up swap to reconcile.
    func testLiveModeRendersDeviationSynchronouslyWhenRadarLands() async {
        let model = makeModel()
        model.settings.mockMode = false
        model.flightPlan.waypoints = []          // straight dep→dest so the cell sits on the route

        guard let dep = AirportDatabase.shared.coordinate(for: model.flightPlan.departure),
              let dest = AirportDatabase.shared.coordinate(for: model.flightPlan.destination) else {
            return XCTFail("test needs located CONUS endpoints for radar coverage")
        }
        let course = Geo.bearing(from: dep, to: dest)
        let stormCenter = Geo.destination(from: dep, bearingDegrees: course, distanceNM: 45)
        model.radarOverlay.sampledCells = [RadarCell(polygon: box(around: stormCenter, half: 0.3),
                                                     intensity: .heavy)]
        model.livePrecipCellsReady = true

        // Synchronous recompute: the full search runs directly and draws the reroute right away.
        model.recomputeWeatherHazards()
        XCTAssertFalse(model.lockedDeviations.isEmpty,
                       "live mode solves and locks the deviation set in one synchronous pass")
        XCTAssertNotNil(model.weatherDeviationLine, "the reroute is drawn at once")

        // No background refine to wait for: the locked set is stable across later hops.
        await Task.yield()
        await Task.yield()
        XCTAssertFalse(model.lockedDeviations.isEmpty, "the locked set is stable — no async swap follows")
        XCTAssertNotNil(model.weatherDeviationLine, "a mint line is still drawn")
    }

    /// Regression: on a new flight the first live radar frame after connecting can decode empty
    /// (a cold fetch renders a partial/blank image), so the initial whole-route solve finds no
    /// weather. That empty result must **not** freeze the lock — otherwise the mint lines never
    /// draw until a manual pull-to-refresh, even though a later resample lands the storm cells.
    /// The set stays unlocked while empty and re-solves once real cells arrive.
    func testEmptyInitialLockRecomputesWhenRadarCellsLandLater() {
        let model = makeModel()
        model.settings.mockMode = false          // live path: hazards come from sampledCells
        model.flightPlan.waypoints = []          // straight dep→dest so the cell sits on the route

        guard let dep = AirportDatabase.shared.coordinate(for: model.flightPlan.departure),
              let dest = AirportDatabase.shared.coordinate(for: model.flightPlan.destination) else {
            return XCTFail("test needs located CONUS endpoints for radar coverage")
        }

        // First sample lands "ready" but the decoded frame is blank (no cells) — the cold-fetch
        // case. The solve finds nothing, but it must NOT lock that empty result permanently.
        model.radarOverlay.sampledCells = []
        model.livePrecipCellsReady = true
        model.recomputeWeatherHazards()
        XCTAssertTrue(model.lockedDeviations.isEmpty, "a blank first frame yields no lines yet")
        XCTAssertNil(model.weatherDeviationLine, "no mint line while the radar frame decoded empty")

        // A later resample lands the storm on the route. Detection must re-solve on its own —
        // no pull-to-refresh — and draw the reroute, proving the empty lock was not frozen.
        let course = Geo.bearing(from: dep, to: dest)
        let stormCenter = Geo.destination(from: dep, bearingDegrees: course, distanceNM: 45)
        model.radarOverlay.sampledCells = [RadarCell(polygon: box(around: stormCenter, half: 0.3),
                                                     intensity: .heavy)]
        model.recomputeWeatherHazards()
        XCTAssertFalse(model.lockedDeviations.isEmpty,
                       "the deviation locks once real radar cells land, without a manual refresh")
        XCTAssertNotNil(model.weatherDeviationLine,
                        "the mint line draws automatically after the radar cells arrive")
    }

    /// Regression (mock mode): the simulator feed emits a telemetry state the instant it
    /// starts — before the async `refreshWeather` has loaded the mock radar cells — so the very
    /// first `recomputeWeatherHazards` runs with no cells and finds no weather. That empty solve
    /// must **not** freeze the lock; otherwise the mint lines never draw until a manual
    /// pull-to-refresh, exactly as reported. Once the cells load the set must fill on its own.
    func testMockLinesDrawOnceCellsLoadAfterTheFirstEmit() async {
        let model = makeModel()                  // mock mode, KIAH→KMSP
        model.flightPlan.waypoints = []          // straight dep→dest so the cells sit on the corridor

        // The immediate-emit ordering: a telemetry tick at the gate arrives while the mock cells
        // are still empty. The deviation solve finds nothing but must leave the set unlocked.
        model.radarOverlay.mockCells = []
        model.ingestStateForTesting(model.mock.state(for: .preflight))
        XCTAssertTrue(model.lockedDeviations.isEmpty, "no lines yet while the mock cells are empty")

        // `refreshWeather` loads the mock cells a beat later (as it does after start). The
        // deviation set must fill and the faint previews draw — with no pull-to-refresh.
        await model.refreshWeather()
        XCTAssertFalse(model.lockedDeviations.isEmpty,
                       "the deviations lock once the mock cells load, without a manual refresh")
        XCTAssertFalse(model.weatherDeviationPreviews.isEmpty,
                       "the mint preview lines draw at the gate on their own")
    }

    // MARK: - Endpoints resolve from Infinite Flight, not just the built-in hub table

    /// The route corridor must resolve for airports **outside** the 21-hub built-in table by
    /// using Infinite Flight's reported endpoint coordinates. Previously departure/destination
    /// leaned on the built-in table first, so a non-hub field (e.g. an Oklahoma airport) had no
    /// coordinate at the gate — the corridor collapsed and the deviation flow drew nothing
    /// ("no cells / no conflict" for a clear on-route hazard).
    func testEndpointsResolveFromInfiniteFlightForFieldsOutsideTheBuiltInDatabase() {
        let model = makeModel()
        model.settings.mockMode = false
        XCTAssertNil(AirportDatabase.shared.coordinate(for: "KADM"),
                     "precondition: the departure is not one of the built-in hubs")
        XCTAssertNil(AirportDatabase.shared.coordinate(for: "KOUN"),
                     "precondition: the destination is not one of the built-in hubs")

        var plan = FlightPlan()
        plan.departure = "KADM"                                   // Ardmore, OK — not in the table
        plan.destination = "KOUN"                                 // Norman, OK — not in the table
        plan.departureLatitude = 34.30; plan.departureLongitude = -97.02      // Infinite Flight-reported
        plan.destinationLatitude = 35.24; plan.destinationLongitude = -97.47
        plan.waypoints = []
        model.flightPlan = plan

        let dep = CLLocationCoordinate2D(latitude: 34.30, longitude: -97.02)
        let dest = CLLocationCoordinate2D(latitude: 35.24, longitude: -97.47)
        let ahead = model.upcomingRouteCoordinatesForTesting(from: dep)
        XCTAssertFalse(ahead.isEmpty,
                       "the corridor resolves from the IF-reported endpoints for a non-hub field")
        XCTAssertTrue(ahead.contains { Geo.distanceNM(from: $0, to: dest) < 30 },
                      "the corridor runs to the IF-reported destination, not nowhere")
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

    // MARK: - A reroute that deviates nowhere is not drawn

    /// The reported anomaly: a mint deviation drawn right on top of the flight path. When
    /// the solved line never leaves the route, no mint line and no rejoin marker are drawn
    /// — and committing to it must not sneak it back onto the map as a frozen path, which
    /// is drawn ahead of every guard. The conflict itself stays put, so the weather is
    /// still detected and still prompts.
    func testDeviationThatNeverLeavesTheRouteDrawsNoMintLine() {
        let model = makeModel()
        let pos = CLLocationCoordinate2D(latitude: 40, longitude: -95)
        let apex = Geo.destination(from: pos, bearingDegrees: 20, distanceNM: 60)
        let end = Geo.destination(from: pos, bearingDegrees: 0, distanceNM: 160)
        let hazard = WeatherHazard(source: .noaaRadar, phenomenon: .precipitation, intensity: .heavy,
                                   geometry: .polygon(box(around: apex, half: 0.3)), confidence: .high)
        model.weatherHazards = [hazard]        // the line passes the engagement guard either way
        func conflict(excursionNM: Double) -> RouteWeatherConflict {
            RouteWeatherConflict(
                hazard: hazard, distanceAheadNM: 40, relativeBearingDegrees: 0,
                leftClock: 12, centerClock: 12, rightClock: 12, estimatedTimeMinutes: nil,
                severity: .heavy, leftBypassScore: 0, rightBypassScore: 0,
                recommendedDirection: .right, recommendedDeviationDegrees: 20,
                rejoinFix: Waypoint(name: "RJOIN", latitude: end.latitude, longitude: end.longitude),
                originalSegment: nil, shouldPrompt: true, withinTacticalRange: true,
                withinDrawRange: true, intersectionArea: [], deviationPath: [pos, apex, end],
                maxRouteExcursionNM: excursionNM)
        }

        model.activeWeatherConflict = conflict(excursionNM: 1.5)
        XCTAssertNil(model.weatherDeviationLine,
                     "a reroute that never leaves the route must not be drawn on top of it")
        XCTAssertNil(model.weatherRejoinMarker, "and no lone rejoin marker is left behind")

        model.activeWeatherConflict = conflict(excursionNM: 24)
        XCTAssertNotNil(model.weatherDeviationLine, "a reroute that does leave the route is drawn")
        XCTAssertNotNil(model.weatherRejoinMarker)
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

    /// The faint previews are locked in place: a noisy resample that momentarily (or
    /// lastingly) samples the route clear must not blink them out while the storms are
    /// still there. They are recomputed only on a "Refresh Deviations" tap, which — against
    /// a clear radar — removes them.
    func testStrategicPreviewsHoldUntilRefreshed() async {
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
        model.radarOverlay.mockCells = [cellAt(110), cellAt(230)]
        model.recomputeWeatherHazards()
        let shown = model.weatherDeviationPreviews.count
        XCTAssertGreaterThanOrEqual(shown, 2, "previews drawn for each system ahead")

        // The radar clears, but a plain recompute never re-solves the locked previews.
        model.radarOverlay.mockCells = []
        model.recomputeWeatherHazards()
        XCTAssertEqual(model.weatherDeviationPreviews.count, shown,
                       "an empty sample must not blink the faint previews out")

        // A refresh against the clear radar removes them.
        await model.refreshDeviations()
        XCTAssertTrue(model.weatherDeviationPreviews.isEmpty,
                      "a refresh against a clear route removes the strategic previews")
    }

    /// Mock mode seeds several storm systems down the route, and the strategic preview
    /// scans the whole route (past clear gaps) to draw a line for each — visible from the
    /// departure gate, with no telemetry yet, so scenarios can be eyeballed before flying.
    func testMockModeSeedsMultipleSystemsVisibleFromTheGate() async {
        let model = makeModel()
        await model.refreshWeather()   // mock loads its sample storm systems + recomputes
        XCTAssertGreaterThanOrEqual(model.radarOverlay.mockCells.count, 3,
                                    "mock mode seeds several storm systems along the route")
        XCTAssertNil(model.aircraftState.coordinate, "no telemetry yet — still at the gate")
        XCTAssertGreaterThanOrEqual(model.weatherDeviationPreviews.count, 2,
                                    "systems spread down the route each preview from the gate")
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
        await model.refreshDeviations()

        // The solid line is the near cell; the far cell previews faint. The preview set
        // must not include a line starting back at the (solid) near system.
        guard let solidStart = model.weatherDeviationLine?.first else { return XCTFail() }
        for preview in model.weatherDeviationPreviews {
            guard let s = preview.first else { continue }
            XCTAssertGreaterThan(Geo.distanceNM(from: s, to: solidStart), 20,
                                 "previews are the upcoming systems, not the one drawn solid")
        }
    }

    // MARK: - Route ahead follows the filed route (not radial distance from departure)

    /// The route the detector treats as "ahead" must follow the filed route past a bend,
    /// even where a downstream fix is *closer to the departure* than the aircraft already
    /// is. The old distance-from-departure test dropped such a fix once telemetry arrived,
    /// reshaping the detection corridor away from the drawn route — so on-route storms
    /// stopped being detected the moment the aircraft icon appeared (the reported "mint
    /// line drew perfectly while disconnected, then vanished as soon as the flight
    /// reconnected"). Projection onto the route keeps the fix, so detection tracks the
    /// drawn line.
    func testRouteAheadFollowsTheFiledRouteThroughABendNotRadialDistance() {
        let model = makeModel()
        func wp(_ name: String, _ lat: Double, _ lon: Double) -> Waypoint {
            Waypoint(name: name, latitude: lat, longitude: lon)
        }
        // A route that jogs: ORIG → far east fix A → fix B (back west, so B is CLOSER to
        // the departure than A) → DEST. `departure`/`destination` are left blank so the
        // origin/dest fall back to the first/last fix coordinates.
        let origin = CLLocationCoordinate2D(latitude: 30, longitude: -95)
        let bend = CLLocationCoordinate2D(latitude: 34, longitude: -96)   // the fix past the bend
        var plan = FlightPlan()
        plan.departure = ""
        plan.destination = ""
        plan.waypoints = [wp("ORIG", 30, -95), wp("AAAAA", 33, -90),
                          wp("BBEND", 34, -96), wp("DESTF", 40, -95)]
        model.flightPlan = plan
        _ = origin

        // Aircraft abeam the A→B leg, having flown past A. Its straight-line distance from
        // the departure now exceeds fix B's, so the old heuristic dropped B.
        let aircraft = CLLocationCoordinate2D(latitude: 33.3, longitude: -91.0)
        let ahead = model.upcomingRouteCoordinatesForTesting(from: aircraft)
        XCTAssertTrue(ahead.contains { Geo.distanceNM(from: $0, to: bend) < 5 },
                      "the fix past the bend stays on the route ahead (it is not dropped for being closer to the departure)")
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
        // reroute is drawn ahead with the turn-out well in front of the aircraft. Refresh
        // so the locked deviations pick up the moved weather.
        let course = Geo.bearing(from: model.mock.route.depCoord, to: model.mock.route.destCoord)
        let center = Geo.destination(from: pos, bearingDegrees: course, distanceNM: 60)
        model.radarOverlay.mockCells = [RadarCell(polygon: box(around: center, half: 0.15), intensity: .moderate)]
        await model.refreshDeviations()
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

    /// The turn is called a little *early* — a lead distance before the turn-out — so the
    /// aircraft rolls onto the reroute through the vertex instead of overshooting the mint
    /// line. It fires while still short of the turn-out (beyond the old fixed capture
    /// radius), not only once the vertex is reached.
    func testDeviationBeginningTurnIsAnticipatedBeforeTheTurnOut() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        guard let pos = model.aircraftState.coordinate else { return XCTFail("no cruise position") }

        // A narrow cell ~60 NM ahead so its reroute is drawn ahead with the turn-out well
        // in front of the aircraft (the deferred / held-turn flow).
        let course = Geo.bearing(from: model.mock.route.depCoord, to: model.mock.route.destCoord)
        let center = Geo.destination(from: pos, bearingDegrees: course, distanceNM: 60)
        model.radarOverlay.mockCells = [RadarCell(polygon: box(around: center, half: 0.15), intensity: .moderate)]
        await model.refreshDeviations()
        guard let v0 = model.activeWeatherConflict?.deviationPath.first else {
            return XCTFail("expected a drawn-ahead conflict")
        }
        model.requestWeatherDeviation(.right)
        XCTAssertEqual(model.weatherDeviationState, .deviationApproved)
        XCTAssertNotNil(model.weatherDeviation.deviationStartLatitude, "the beginning turn is armed")

        // A point 5 NM short of the turn-out along the leg into it. Under the old behaviour
        // the turn was only called within one capture radius (max(2, gs/120) ≈ 3.8 NM at
        // cruise) of the vertex, so 5 NM short would NOT have fired; with turn anticipation
        // the turn is called earlier and fires here.
        let base = max(2.0, (model.aircraftState.groundSpeed ?? 300) / 120)
        XCTAssertLessThan(base, 5.0, "the test point must lie beyond the old fixed capture radius")
        let legBearing = Geo.bearing(from: pos, to: v0)
        let short = Geo.destination(from: v0, bearingDegrees: legBearing + 180, distanceNM: 5)
        var near = model.mock.state(for: .cruise)
        near.latitude = short.latitude
        near.longitude = short.longitude
        model.ingestStateForTesting(near)

        XCTAssertEqual(model.weatherDeviationState, .vectoringAroundWeather,
                       "the beginning turn is anticipated — issued while still short of the turn-out")
        XCTAssertTrue(atcContains(model, "fly heading"), "the anticipated beginning turn is issued")
        XCTAssertNil(model.weatherDeviation.deviationStartLatitude, "the held turn is consumed once issued")
    }

    // MARK: - Entry point behind the aircraft → redraw the mint line 20 NM ahead

    /// A point `nm` NM further along the mock route (a straight lat/lon interpolation from
    /// the departure to the destination), measured from the aircraft's current position.
    private func routePointAhead(_ model: AppModel, _ nm: Double) -> CLLocationCoordinate2D {
        let dest = model.mock.route.destCoord
        guard let pos = model.aircraftState.coordinate else { return dest }
        let f = nm / max(1, Geo.distanceNM(from: pos, to: dest))
        return CLLocationCoordinate2D(latitude: pos.latitude + (dest.latitude - pos.latitude) * f,
                                      longitude: pos.longitude + (dest.longitude - pos.longitude) * f)
    }

    /// The locked mint lines are solved for the whole route and then held, so a pilot who
    /// ignores the banner can fly straight past the turn-out at the start of one — leaving
    /// the reroute drawn *behind* the aircraft, where it can no longer be flown. Passing (or
    /// missing) that entry point redraws the deviation ~20 NM ahead of the aircraft, and the
    /// controller says so.
    func testDeviationRedrawsAheadWhenTheAircraftPassesTheEntryPoint() async {
        let model = makeModel()
        await driveToCruiseConflict(model)

        // One heavy cell well down the route: its reroute is drawn with a turn-out far
        // enough ahead of the weather that the aircraft can be flown past it with the
        // storm still in front.
        let center = routePointAhead(model, 150)
        model.radarOverlay.mockCells = [RadarCell(polygon: box(around: center, half: 0.3), intensity: .heavy)]
        await model.refreshDeviations()
        guard let entry = model.activeWeatherConflict?.deviationPath.first, entry.isValid else {
            return XCTFail("expected a drawn deviation for the cell ahead")
        }

        // Fly 3 NM past the entry point without ever committing to the deviation.
        let course = Geo.bearing(from: entry, to: model.mock.route.destCoord)
        let past = Geo.destination(from: entry, bearingDegrees: course, distanceNM: 3)
        var state = model.mock.state(for: .cruise)
        state.latitude = past.latitude
        state.longitude = past.longitude
        model.ingestStateForTesting(state)

        guard let line = model.weatherDeviationLine, let redrawn = line.first, redrawn.isValid else {
            return XCTFail("expected the deviation to be redrawn ahead of the aircraft")
        }
        XCTAssertGreaterThan(Geo.distanceNM(from: past, to: redrawn), 15,
                             "the redrawn entry point is placed ~20 NM ahead of the aircraft")
        let toEntry = Geo.bearing(from: past, to: redrawn)
        var off = abs(toEntry - course).truncatingRemainder(dividingBy: 360)
        if off > 180 { off = 360 - off }
        XCTAssertLessThan(off, 90, "the redrawn entry point lies ahead of the aircraft, not behind it")
        XCTAssertTrue(atcContains(model, "weather deviation updated"),
                      "ATC notifies the pilot that the deviation was redrawn ahead")
        // The call carries its own read-back — the courtesy "Roger" — so Read Back
        // acknowledges this advisory instead of re-deriving one from the conversational state.
        let redrawCall = model.transcript.last {
            $0.sender == .atc && $0.displayText.contains("weather deviation updated")
        }
        XCTAssertTrue(redrawCall?.readback?.displayText.hasPrefix("Roger,") ?? false,
                      redrawCall?.readback?.displayText ?? "no read-back on the redraw call")
    }

    /// Nothing was activated — the pilot elected to continue on course, which settles the
    /// lifecycle back to idle — so when the line is redrawn ahead the update **re-opens the
    /// decision**: the response card comes back with the call, carrying Vectors and the
    /// left/right deviation buttons, so the revised deviation can be activated on the spot
    /// rather than waiting for the near-turn advisory to re-raise it.
    func testRedrawnDeviationReopensTheResponseCardWhenNothingWasActivated() async {
        let model = makeModel()
        await driveToCruiseConflict(model)

        let center = routePointAhead(model, 150)
        model.radarOverlay.mockCells = [RadarCell(polygon: box(around: center, half: 0.3), intensity: .heavy)]
        await model.refreshDeviations()
        guard let entry = model.activeWeatherConflict?.deviationPath.first, entry.isValid else {
            return XCTFail("expected a drawn deviation for the cell ahead")
        }

        // "Continuing on course" answers the advisory without activating anything: the card
        // goes away and the lifecycle is idle again.
        model.continueThroughWeather()
        XCTAssertFalse(model.weatherDeviationCardVisible, "continuing on course closes the card")

        // Fly 3 NM past the entry point: the line is redrawn ahead and ATC advises it.
        let course = Geo.bearing(from: entry, to: model.mock.route.destCoord)
        let past = Geo.destination(from: entry, bearingDegrees: course, distanceNM: 3)
        var state = model.mock.state(for: .cruise)
        state.latitude = past.latitude
        state.longitude = past.longitude
        model.ingestStateForTesting(state)

        XCTAssertTrue(atcContains(model, "weather deviation updated"),
                      "ATC advises the revised deviation")
        XCTAssertEqual(model.weatherDeviationState, .awaitingPilotIntentions,
                       "the update opens the decision again")
        XCTAssertTrue(model.weatherDeviationCardVisible, "the response card comes up with the call")
        XCTAssertTrue(model.weatherActions.contains(.requestVector), "\(model.weatherActions)")
        XCTAssertTrue(model.weatherActions.contains(.requestLeftDeviation), "\(model.weatherActions)")
        XCTAssertTrue(model.weatherActions.contains(.requestRightDeviation), "\(model.weatherActions)")
    }

    /// The redraw never touches a deviation the pilot has committed to: once the turn is
    /// approved, the frozen line is the one being flown and its start legitimately falls
    /// behind the aircraft as the maneuver begins.
    func testCommittedDeviationIsNotRedrawnWhenItsStartFallsBehind() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        guard let pos = model.aircraftState.coordinate else { return XCTFail("no cruise position") }

        // A narrow cell ~60 NM ahead: the reroute is drawn ahead, so the request is approved
        // with the turn held at the turn-out.
        let course = Geo.bearing(from: model.mock.route.depCoord, to: model.mock.route.destCoord)
        let center = Geo.destination(from: pos, bearingDegrees: course, distanceNM: 60)
        model.radarOverlay.mockCells = [RadarCell(polygon: box(around: center, half: 0.15), intensity: .moderate)]
        await model.refreshDeviations()
        guard let v0 = model.activeWeatherConflict?.deviationPath.first, v0.isValid else {
            return XCTFail("expected a drawn-ahead conflict")
        }
        model.requestWeatherDeviation(.right)
        XCTAssertEqual(model.weatherDeviationState, .deviationApproved)

        // Fly 10 NM past the turn-out: the held beginning turn is issued, and the committed
        // line stays exactly where it was — it is not redrawn ahead.
        let past = Geo.destination(from: v0, bearingDegrees: course, distanceNM: 10)
        var state = model.mock.state(for: .cruise)
        state.latitude = past.latitude
        state.longitude = past.longitude
        model.ingestStateForTesting(state)

        XCTAssertEqual(model.weatherDeviationState, .vectoringAroundWeather,
                       "passing the turn-out begins the committed deviation")
        XCTAssertFalse(atcContains(model, "weather deviation updated"),
                       "a committed deviation is flown, never redrawn ahead")
        guard let committed = model.weatherDeviationLine?.first else {
            return XCTFail("the committed mint line should still be drawn")
        }
        XCTAssertLessThan(Geo.distanceNM(from: committed, to: v0), 1,
                          "the committed line still starts at the turn-out the pilot is flying from")
    }

    // MARK: - Drifting off the line being flown → re-plan from the aircraft

    /// A point `offsetNM` to one side of the middle of the mint line's first leg — the drift a
    /// wind push or a late roll-in leaves, measured off the line the aircraft was cleared to fly.
    private func abeamFirstLeg(_ line: [CLLocationCoordinate2D], offsetNM: Double) -> CLLocationCoordinate2D {
        let a = line[0], b = line[1]
        let leg = Geo.bearing(from: a, to: b)
        let mid = Geo.destination(from: a, bearingDegrees: leg,
                                  distanceNM: Geo.distanceNM(from: a, to: b) / 2)
        // Offset outboard of the leg — away from the filed course and the weather it rounds —
        // so the leg itself is the nearest part of the drawn line, and the drifted point is
        // never inside a cell.
        return Geo.destination(from: mid, bearingDegrees: leg + 90, distanceNM: offsetNM)
    }

    /// Once the aircraft is more than 5 NM off the mint line it is flying — wind, a late turn —
    /// the deviation is re-planned from the aircraft's current position: the line is re-anchored
    /// to start at the aircraft, ATC re-vectors onto it, and the upcoming auto-turn is re-armed
    /// against the new geometry.
    func testDriftingOffTheMintLineReplansFromTheAircraftPosition() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        model.requestVectorAroundWeather()
        XCTAssertEqual(model.weatherDeviationState, .vectoringAroundWeather)
        guard let line = model.weatherDeviationLine, line.count >= 3 else {
            return XCTFail("expected a committed mint line with a turn to fly")
        }

        // Drift 10 NM off the leg the aircraft was cleared to fly.
        let drifted = abeamFirstLeg(line, offsetNM: 10)
        var state = model.mock.state(for: .cruise)
        state.latitude = drifted.latitude
        state.longitude = drifted.longitude
        model.ingestStateForTesting(state)

        XCTAssertTrue(atcContains(model, "off the assigned deviation"),
                      "ATC re-vectors an aircraft that has drifted off the deviation")
        XCTAssertEqual(model.weatherDeviationState, .vectoringAroundWeather)
        guard let replanned = model.weatherDeviationLine, replanned.count >= 3,
              let start = replanned.first, let last = replanned.last else {
            return XCTFail("expected a re-planned mint line with a turn still to fly")
        }
        XCTAssertLessThan(Geo.distanceNM(from: start, to: drifted), 0.5,
                          "the line is re-anchored to the aircraft's current position")
        XCTAssertGreaterThan(Geo.distanceNM(from: drifted, to: last), 10,
                             "the re-planned line still runs out to a rejoin well ahead")
        XCTAssertEqual(model.weatherDeviation.pendingTurnIndex, 1,
                       "the upcoming auto turn is re-armed against the re-anchored line")
        let heading = model.weatherDeviation.assignedHeading
        XCTAssertEqual(heading,
                       ((Int(Geo.bearing(from: drifted, to: replanned[1]).rounded()) % 360) + 360) % 360,
                       "the fresh vector intercepts the re-anchored line")
    }

    /// Normal tracking error — a couple of miles off while rolling through a turn — must not
    /// re-vector: the committed line stays exactly as cleared.
    func testSmallDriftOnTheMintLineDoesNotReplan() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        model.requestVectorAroundWeather()
        guard let line = model.weatherDeviationLine, line.count >= 2, let start = line.first else {
            return XCTFail("expected a committed mint line to fly")
        }

        let slightlyOff = abeamFirstLeg(line, offsetNM: 3)
        var state = model.mock.state(for: .cruise)
        state.latitude = slightlyOff.latitude
        state.longitude = slightlyOff.longitude
        model.ingestStateForTesting(state)

        XCTAssertFalse(atcContains(model, "off the assigned deviation"),
                       "a few miles of tracking error is not a re-plan")
        XCTAssertLessThan(Geo.distanceNM(from: model.weatherDeviationLine?.first ?? start, to: start), 0.5,
                          "the committed line is untouched")
    }

    /// Regression: the re-plan must never turn the aircraft around. With the aircraft off the
    /// line *and travelling away from it*, the old re-anchoring kept a trailing vertex "so the
    /// line still ends on the route" and vectored back to it — a near-reciprocal turn (216° →
    /// 015°). Nothing of the reroute is ahead, so nothing is issued and the line is left alone.
    func testOffPathReplanNeverTurnsTheAircraftAround() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        model.requestVectorAroundWeather()
        XCTAssertEqual(model.weatherDeviationState, .vectoringAroundWeather)
        guard let line = model.weatherDeviationLine, line.count >= 3 else {
            return XCTFail("expected a committed mint line to fly")
        }
        let heldHeading = model.weatherDeviation.assignedHeading

        // Abeam the middle of the line, well off it, tracking the reciprocal of the line's
        // own direction — every vertex of the reroute is now behind the aircraft.
        let lineCourse = Geo.bearing(from: line[0], to: line[line.count - 1])
        let abeam = Geo.destination(from: line[1], bearingDegrees: lineCourse + 90, distanceNM: 25)
        let away = (lineCourse + 180).truncatingRemainder(dividingBy: 360)
        var state = model.mock.state(for: .cruise)
        state.latitude = abeam.latitude
        state.longitude = abeam.longitude
        state.heading = away
        state.trueHeading = away
        state.track = away
        model.ingestStateForTesting(state)

        XCTAssertFalse(atcContains(model, "off the assigned deviation"),
                       "no re-vector when nothing of the reroute lies ahead")
        XCTAssertEqual(model.weatherDeviation.assignedHeading, heldHeading,
                       "the aircraft is never turned around to pick up geometry behind it")
        if let assigned = model.weatherDeviation.assignedHeading {
            var turn = abs(Double(assigned) - away).truncatingRemainder(dividingBy: 360)
            if turn > 180 { turn = 360 - turn }
            XCTAssertLessThanOrEqual(turn, 135,
                                     "no automatically-issued weather vector reverses the aircraft")
        }
    }

    /// A deviation still drawn ahead (the turn held at the turn-out) is not "off path" just
    /// because the aircraft has not reached it yet — it is flying the filed course to it.
    func testDeviationDrawnAheadIsNotTreatedAsOffPath() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        guard let pos = model.aircraftState.coordinate else { return XCTFail("no cruise position") }

        let course = Geo.bearing(from: model.mock.route.depCoord, to: model.mock.route.destCoord)
        let center = Geo.destination(from: pos, bearingDegrees: course, distanceNM: 60)
        model.radarOverlay.mockCells = [RadarCell(polygon: box(around: center, half: 0.15), intensity: .moderate)]
        await model.refreshDeviations()
        model.requestWeatherDeviation(.right)
        XCTAssertEqual(model.weatherDeviationState, .deviationApproved,
                       "the turn is held while the reroute is drawn ahead")

        model.ingestStateForTesting(model.mock.state(for: .cruise))
        XCTAssertFalse(atcContains(model, "off the assigned deviation"),
                       "an aircraft short of the turn-out is on course, not off the deviation")
    }

    // MARK: - Gradual return to course (rejoin at a fix farther down)

    /// A straight north-bound plan with fixes every ~60 NM, so a test can place a rejoin and
    /// know exactly which fixes lie down-route of it.
    private func straightPlanModel() -> AppModel {
        let model = makeModel()
        var plan = FlightPlan()
        plan.departure = ""
        plan.destination = ""
        plan.cruiseAltitude = 37000
        plan.waypoints = (0...10).map { i in
            Waypoint(name: "FIX\(i)", latitude: 30.0 + Double(i), longitude: -95)
        }
        model.flightPlan = plan
        model.radarOverlay.mockCells = []
        return model
    }

    /// A closing leg that turns hard onto the route is re-aimed at the next fix down the flight
    /// plan, so the return to course is gradual — and the rejoin fix is retagged to whatever the
    /// line now ends on, so the "rejoin course direct …" call names the fix actually flown to.
    func testSharpRejoinTurnIsSoftenedToTheNextFixDown() {
        let model = straightPlanModel()
        // A closing leg that meets the route at a hard angle: the parallel leg runs north at a
        // 25 NM offset, then cuts straight back east onto the route — a ~90° turn onto course.
        let parallelStart = CLLocationCoordinate2D(latitude: 32.0, longitude: -95.5)
        let turnBack = CLLocationCoordinate2D(latitude: 33.0, longitude: -95.5)
        let rejoin = CLLocationCoordinate2D(latitude: 33.0, longitude: -95)      // straight across
        let steep = Geo.bearing(from: parallelStart, to: turnBack)
        var steepTurn = abs(Geo.bearing(from: turnBack, to: rejoin) - steep).truncatingRemainder(dividingBy: 360)
        if steepTurn > 180 { steepTurn = 360 - steepTurn }
        XCTAssertGreaterThan(steepTurn, 60, "precondition: the closing leg turns hard onto the route")

        guard let softened = model.gentleRejoinForTesting(path: [parallelStart, turnBack, rejoin]) else {
            return XCTFail("expected the sharp rejoin to be softened to a fix farther down")
        }
        guard let end = softened.path.last else { return XCTFail("expected a softened path") }
        var turn = abs(Geo.bearing(from: turnBack, to: end) - steep).truncatingRemainder(dividingBy: 360)
        if turn > 180 { turn = 360 - turn }
        XCTAssertLessThanOrEqual(turn, 60, "the return to course is gradual")
        XCTAssertGreaterThan(end.latitude, rejoin.latitude,
                             "the rejoin moved to a fix farther down the route, not back up it")
        XCTAssertEqual(softened.fix.coordinate?.latitude, end.latitude,
                       "the named rejoin fix is the one the line now ends on")
        XCTAssertEqual(softened.path.count, 3, "only the rejoin point moves — the hug is untouched")
        XCTAssertEqual(softened.path[1].latitude, turnBack.latitude)
    }

    /// A rejoin that already meets the route gradually is left exactly as it is.
    func testGentleRejoinTurnIsLeftAlone() {
        let model = straightPlanModel()
        let parallelStart = CLLocationCoordinate2D(latitude: 32.0, longitude: -95.4)
        let turnBack = CLLocationCoordinate2D(latitude: 33.0, longitude: -95.4)
        // A long, shallow closing leg back onto the route — well under 60°.
        let rejoin = CLLocationCoordinate2D(latitude: 34.0, longitude: -95)
        XCTAssertNil(model.gentleRejoinForTesting(path: [parallelStart, turnBack, rejoin]),
                     "a gradual intercept needs no softening")
    }

    /// The softened rejoin never reaches past the rejoin cap — a mint line still ends on the
    /// flight path well short of the field.
    func testSoftenedRejoinStaysShortOfTheRejoinCap() {
        let model = straightPlanModel()
        guard let cap = model.weatherRejoinCapForTesting() else {
            return XCTFail("expected a rejoin cap for the straight plan")
        }
        // A hard closing turn near the end of the route: the fixes past the cap are not
        // candidates, so the softened rejoin lands on the last one short of it.
        let parallelStart = CLLocationCoordinate2D(latitude: 37.5, longitude: -95.5)
        let turnBack = CLLocationCoordinate2D(latitude: 38.5, longitude: -95.5)
        let rejoin = CLLocationCoordinate2D(latitude: 38.5, longitude: -95)
        guard let softened = model.gentleRejoinForTesting(path: [parallelStart, turnBack, rejoin]),
              let end = softened.path.last else {
            return XCTFail("expected the sharp rejoin to be softened to a fix short of the cap")
        }
        XCTAssertGreaterThan(end.latitude, rejoin.latitude, "it moved down the route…")
        XCTAssertLessThanOrEqual(end.latitude, cap.latitude + 0.01,
                                 "…but never past the rejoin cap, so the line still ends short of the field")
    }

    // MARK: - The held turn must never be promised for a turn-out that is already behind

    /// Requesting vectors *after* flying past the mint line's turn-out must not hold the turn.
    ///
    /// The turn-out is measured as a distance *ahead*, not as a straight-line distance:
    /// a turn-out the aircraft has already passed abeam is still tens of miles away as the
    /// crow flies, and read that way the request was held — "continue present heading, expect
    /// the turn in X miles" — pinning the beginning turn to a point behind the aircraft. The
    /// reach test can never be satisfied by a point behind, so that turn was never called and
    /// the pilot flew on through the weather waiting for it.
    func testVectorRequestPastTheTurnOutIsWorkedNowRatherThanHeldForATurnBehind() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        guard let pos = model.aircraftState.coordinate else { return XCTFail("no cruise position") }

        // A cell ~60 NM ahead on the filed course, so its reroute is drawn well ahead.
        let course = Geo.bearing(from: model.mock.route.depCoord, to: model.mock.route.destCoord)
        let center = Geo.destination(from: pos, bearingDegrees: course, distanceNM: 60)
        model.radarOverlay.mockCells = [RadarCell(polygon: box(around: center, half: 0.15), intensity: .moderate)]
        await model.refreshDeviations()
        guard let v0 = model.activeWeatherConflict?.deviationPath.first else {
            return XCTFail("expected a drawn-ahead conflict")
        }

        // Fly 15 NM *past* the turn-out along the course — far enough away that the
        // straight-line distance still reads as a comfortable "expect the turn in 15 miles".
        let past = Geo.destination(from: v0, bearingDegrees: course, distanceNM: 15)
        XCTAssertGreaterThan(Geo.distanceNM(from: past, to: v0), 6,
                             "the passed turn-out is still far enough away to look like a held turn")
        var beyond = model.mock.state(for: .cruise)
        beyond.latitude = past.latitude
        beyond.longitude = past.longitude
        beyond.track = course
        model.ingestStateForTesting(beyond)

        model.requestVectorAroundWeather()

        XCTAssertNotEqual(model.weatherDeviationState, .awaitingPilotIntentions,
                          "the request is acted on, not dropped")
        // Either the turn was worked now, or it was re-held against a *fresh* turn-out. What
        // must never happen is a hold pinned to the turn-out already behind the aircraft —
        // that turn can never be issued.
        if let armed = armedTurnOut(model) {
            XCTAssertGreaterThan(aheadNM(armed, from: past, course: course), 0,
                                 "a turn is only ever held for a turn-out ahead of the aircraft")
            XCTAssertGreaterThan(Geo.distanceNM(from: armed, to: v0), 1,
                                 "the stale turn-out behind the aircraft is not what gets armed")
        }
    }

    /// The armed held-turn turn-out, when one is held.
    private func armedTurnOut(_ model: AppModel) -> CLLocationCoordinate2D? {
        guard let lat = model.weatherDeviation.deviationStartLatitude,
              let lon = model.weatherDeviation.deviationStartLongitude else { return nil }
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    /// How far ahead of `pos` (NM) `coord` lies along `course` — negative once it is behind.
    private func aheadNM(_ coord: CLLocationCoordinate2D,
                         from pos: CLLocationCoordinate2D, course: Double) -> Double {
        let bearing = Geo.bearing(from: pos, to: coord)
        return Geo.distanceNM(from: pos, to: coord) * cos((bearing - course) * .pi / 180)
    }

    /// A held beginning turn whose turn-out the aircraft flies past must be recovered, not
    /// waited on forever. `maybeIssueDeviationStartTurn`'s reach test only grows more negative
    /// as the aircraft flies away from the turn-out, so without a watchdog the lifecycle sits
    /// in `.deviationApproved` — which counts as committed, so the per-tick rollback skips it
    /// — with no armed turn to fire, for the rest of the flight.
    func testHeldTurnFlownPastIsRecoveredRatherThanStrandedForever() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        guard let pos = model.aircraftState.coordinate else { return XCTFail("no cruise position") }

        let course = Geo.bearing(from: model.mock.route.depCoord, to: model.mock.route.destCoord)
        let center = Geo.destination(from: pos, bearingDegrees: course, distanceNM: 60)
        model.radarOverlay.mockCells = [RadarCell(polygon: box(around: center, half: 0.15), intensity: .moderate)]
        await model.refreshDeviations()
        guard let v0 = model.activeWeatherConflict?.deviationPath.first else {
            return XCTFail("expected a drawn-ahead conflict")
        }

        // Approve with the turn held, exactly as the drawn-ahead flow does.
        model.requestWeatherDeviation(.right)
        XCTAssertEqual(model.weatherDeviationState, .deviationApproved)
        XCTAssertNotNil(model.weatherDeviation.deviationStartLatitude, "the beginning turn is armed")

        // Now fly well past the armed turn-out without the turn ever having fired.
        let past = Geo.destination(from: v0, bearingDegrees: course, distanceNM: 20)
        var beyond = model.mock.state(for: .cruise)
        beyond.latitude = past.latitude
        beyond.longitude = past.longitude
        beyond.track = course
        model.ingestStateForTesting(beyond)

        // Whatever the recovery decides — re-hold ahead, vector now, or end the deviation —
        // it must never be "still approved, still armed at the point behind us", which is a
        // dead end: the turn can never fire, and `.deviationApproved` is exempt from the
        // per-tick rollback, so no later conflict can prompt afresh either.
        if let armed = armedTurnOut(model) {
            XCTAssertGreaterThan(aheadNM(armed, from: past, course: course), 0,
                                 "a still-held turn is re-anchored ahead, not left on the passed turn-out")
            XCTAssertGreaterThan(Geo.distanceNM(from: armed, to: v0), 1,
                                 "the stale hold on the passed turn-out is released")
        } else {
            XCTAssertNotEqual(model.weatherDeviationState, .deviationApproved,
                              "an approved deviation with nothing armed is a dead end — it must be resolved")
        }
    }

    // MARK: - Auto-call the advisory when the turn is imminent (banner ignored)

    /// Because the mint lines are locked and drawn ahead, a pilot who never taps the
    /// "contact ATC" banner could otherwise fly straight past the first turn with no ATC
    /// call. Closing to within 15 NM of the upcoming deviation's turn-out auto-issues the
    /// advisory on its own.
    func testAdvisoryAutoIssuesWithin15NMOfTurnOutWhenBannerIgnored() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        guard let turnOut = model.activeWeatherConflict?.deviationPath.first else {
            return XCTFail("expected a locked deviation with a turn-out")
        }
        // Simulate the pilot ignoring the banner: back to the un-engaged state, with the
        // one-shot mock demo advisory already spent so only the near-turn net can fire.
        model.markWeatherUnengagedForTesting()
        XCTAssertEqual(model.weatherDeviationState, .none)
        let atcBefore = model.transcript.filter { $0.sender == .atc }.count

        // Fly to 12 NM short of the turn-out without touching the banner.
        let course = Geo.bearing(from: model.mock.route.depCoord, to: model.mock.route.destCoord)
        let approach = Geo.destination(from: turnOut, bearingDegrees: course + 180, distanceNM: 12)
        var near = model.mock.state(for: .cruise)
        near.latitude = approach.latitude
        near.longitude = approach.longitude
        model.ingestStateForTesting(near)

        XCTAssertEqual(model.weatherDeviationState, .awaitingPilotIntentions,
                       "ATC auto-issues the advisory within 15 NM of the turn even without a banner tap")
        XCTAssertGreaterThan(model.transcript.filter { $0.sender == .atc }.count, atcBefore,
                             "the auto-issued advisory is transmitted by ATC")
        XCTAssertTrue(atcContains(model, "precipitation"),
                      "the auto-issued call is the precipitation advisory")
    }

    /// The auto-call respects an explicit choice to continue: a pilot who elected to
    /// continue on course is not re-prompted as the turn approaches.
    func testTurnProximityDoesNotReissueAfterPilotContinued() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        guard let turnOut = model.activeWeatherConflict?.deviationPath.first else {
            return XCTFail("expected a locked deviation with a turn-out")
        }
        model.continueThroughWeather()            // the pilot handles it: continue on course
        XCTAssertEqual(model.weatherDeviationState, .none)

        let course = Geo.bearing(from: model.mock.route.depCoord, to: model.mock.route.destCoord)
        let approach = Geo.destination(from: turnOut, bearingDegrees: course + 180, distanceNM: 12)
        var near = model.mock.state(for: .cruise)
        near.latitude = approach.latitude
        near.longitude = approach.longitude
        model.ingestStateForTesting(near)

        XCTAssertEqual(model.weatherDeviationState, .none,
                       "a pilot who chose to continue is not auto-prompted again near the turn")
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

    /// A pull-to-refresh re-solves the whole deviation set, but it must never move the line
    /// the pilot is already flying — that would literally change the path underneath him.
    /// Once committed the mint line is frozen, so even a refresh against *moved* weather
    /// (which re-solves to a different line elsewhere) leaves the flown line byte-for-byte
    /// unchanged. Exercises the deviation half of the pull-to-refresh directly.
    func testPullToRefreshDoesNotMoveTheCommittedMintLine() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        model.requestVectorAroundWeather()
        XCTAssertEqual(model.weatherDeviationState, .vectoringAroundWeather)
        guard let flown = model.weatherDeviationLine, flown.count >= 2 else {
            return XCTFail("expected a frozen mint line after committing to a vector")
        }

        // Move the storm far down the route so a fresh solve would draw a *different* line
        // (the original storm the committed line rounds is now gone from the radar)…
        let dep = model.mock.route.depCoord
        let dest = model.mock.route.destCoord
        let course = Geo.bearing(from: dep, to: dest)
        model.radarOverlay.mockCells = [RadarCell(
            polygon: box(around: Geo.destination(from: dep, bearingDegrees: course, distanceNM: 300), half: 0.4),
            intensity: .heavy)]
        model.expireWeatherClearWindowForTesting()

        // …then run the deviation half of a pull-to-refresh. The flown line must not budge.
        model.refreshDeviationsFromCurrentRadar()
        XCTAssertEqual(model.weatherDeviationLine?.count, flown.count,
                       "a pull-to-refresh leaves the committed mint line locked")
        XCTAssertEqual(model.weatherDeviationLine?.first?.latitude, flown.first?.latitude,
                       "the committed line's start does not move")
        XCTAssertEqual(model.weatherDeviationLine?.last?.latitude, flown.last?.latitude,
                       "the committed line's rejoin does not move")
        XCTAssertEqual(model.weatherDeviationLine?.last?.longitude, flown.last?.longitude)
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

    /// While already flying a deviation, tapping Vectors when the reroute ahead is still
    /// clear must NOT re-vector: the controller has the pilot continue on the current
    /// deviation, and the committed line and its armed turns are left untouched.
    func testReVectorWhileCommittedContinuesWhenRerouteStillClear() async {
        let model = makeModel()
        await driveToCruiseConflict(model)

        model.requestVectorAroundWeather()
        XCTAssertEqual(model.weatherDeviationState, .vectoringAroundWeather)
        guard let committed = model.weatherDeviation.committedDeviationPath, committed.count >= 2 else {
            return XCTFail("expected a committed mint line after the first vector")
        }
        let firstStart = committed.first!.coordinate
        let lastEnd = committed.last!.coordinate
        let armedTurn = model.weatherDeviation.pendingRejoinHeading

        // No new weather sits on the reroute ahead (here the storm clears entirely). The
        // committed deviation is locked, so it stays drawn while the pilot flies it.
        model.radarOverlay.mockCells = []
        model.recomputeWeatherHazards()
        XCTAssertNotNil(model.weatherDeviationLine, "the committed line stays locked through the clear")

        let atcBefore = model.transcript.filter { $0.sender == .atc }.count
        model.requestVectorAroundWeather()

        // ATC has the pilot continue on the current deviation — no fresh vector issued.
        XCTAssertEqual(model.weatherDeviationState, .vectoringAroundWeather,
                       "a clear reroute keeps the pilot on the current deviation")
        XCTAssertTrue(atcContains(model, "continue present deviation"),
                      "the controller has the pilot continue on the current deviation")
        XCTAssertGreaterThan(model.transcript.filter { $0.sender == .atc }.count, atcBefore,
                             "the controller answers the request")

        // The committed line and its armed turn are preserved (nothing is re-planned).
        guard let stillCommitted = model.weatherDeviation.committedDeviationPath,
              let newStart = stillCommitted.first?.coordinate,
              let newEnd = stillCommitted.last?.coordinate else {
            return XCTFail("the committed line must be preserved on continue")
        }
        XCTAssertEqual(newStart.latitude, firstStart.latitude,
                       "the committed line's start is not moved when continuing")
        XCTAssertEqual(newEnd.longitude, lastEnd.longitude,
                       "the committed line's rejoin is not moved when continuing")
        XCTAssertEqual(model.weatherDeviation.pendingRejoinHeading, armedTurn,
                       "the armed rejoin turn is preserved when continuing")
    }

    /// A reply to a pilot request is never held as a duplicate. The controller's words here
    /// are identical every time the pilot taps Vectors on a still-clear reroute, but a request
    /// left unanswered reads as a dropped call — only calls the controller makes on its own
    /// are held when they would repeat something the pilot already acknowledged.
    func testRepeatedPilotRequestIsAnsweredEveryTime() async {
        let model = makeModel()
        await driveToCruiseConflict(model)
        model.requestVectorAroundWeather()
        XCTAssertEqual(model.weatherDeviationState, .vectoringAroundWeather)

        // The reroute ahead is clear, so every further Vectors tap draws the same reply.
        model.radarOverlay.mockCells = []
        model.recomputeWeatherHazards()

        model.requestVectorAroundWeather()
        model.readBack()
        XCTAssertEqual(continueCalls(model), 1)
        model.requestVectorAroundWeather()
        XCTAssertEqual(continueCalls(model), 2,
                       "the controller answers the pilot every time, identical words or not")
    }

    private func continueCalls(_ model: AppModel) -> Int {
        model.transcript.filter {
            $0.sender == .atc && $0.displayText.contains("continue present deviation")
        }.count
    }

    /// Regression: a second re-vector — a fresh deviation off the aircraft's current
    /// position while already flying a first deviation — must run all the way to the filed
    /// route, not end mid-air on the first deviation it replaces. Freezing the new line
    /// erases the first one, so if the new line merely rejoined the (now-gone) first
    /// deviation partway, its rejoin would sit off the flight path and the aircraft would
    /// "resume own navigation" in the middle of nowhere. Every deviation ends on the route.
    func testReVectorWhileCommittedEndsOnFiledRoute() async {
        let model = makeModel()
        await driveToCruiseConflict(model)

        model.requestVectorAroundWeather()
        XCTAssertEqual(model.weatherDeviationState, .vectoringAroundWeather)
        guard let firstPath = model.weatherDeviation.committedDeviationPath, firstPath.count >= 3 else {
            return XCTFail("expected a committed mint line after the first vector")
        }
        let firstEnd = firstPath.last!.coordinate

        // The filed route is (very nearly) the straight KIAH→KMSP line, so cross-track
        // distance from that line tells whether a point lies on the flight path.
        guard let routeStart = model.flightPlan.waypoints.first?.coordinate,
              let routeEnd = model.flightPlan.waypoints.last?.coordinate else {
            return XCTFail("mock route should have located waypoints")
        }
        func crossTrackNM(_ p: CLLocationCoordinate2D) -> Double {
            abs(Geo.crossTrackDistanceNM(point: p, pathStart: routeStart, pathEnd: routeEnd))
        }
        XCTAssertLessThan(crossTrackNM(firstEnd), 6,
                          "the first deviation already rejoins the filed route")

        // New heavy weather straddles the committed mint line near its start (well off the
        // filed route, out on the deviation), so the fresh re-vector must round it and —
        // before the fix — would rejoin the committed line, now erased, far short of the route.
        let earlyOnLine = firstPath[1].coordinate
        model.radarOverlay.mockCells = [RadarCell(polygon: box(around: earlyOnLine, half: 0.3), intensity: .heavy)]
        model.recomputeWeatherHazards()

        model.requestVectorAroundWeather()
        XCTAssertEqual(model.weatherDeviationState, .vectoringAroundWeather)
        guard let newPath = model.weatherDeviation.committedDeviationPath, newPath.count >= 2,
              let newEnd = newPath.last?.coordinate else {
            return XCTFail("the re-vector re-freezes a committed mint line")
        }

        // The new deviation ends on the filed route — carried down the rest of the first
        // deviation to its rejoin — not mid-air on the erased first line.
        XCTAssertLessThan(crossTrackNM(newEnd), 6,
                          "every deviation must end on the flight path, not on the erased first deviation")
        XCTAssertEqual(newEnd.latitude, firstEnd.latitude, accuracy: 0.05,
                       "the second deviation carries all the way to the first deviation's rejoin on the route")
        XCTAssertEqual(newEnd.longitude, firstEnd.longitude, accuracy: 0.05,
                       "the second deviation carries all the way to the first deviation's rejoin on the route")
    }

    // MARK: - Existing weather features still work

    func testExistingWeatherStillLoadsInMock() async {
        let model = makeModel()
        await model.refreshWeather()
        XCTAssertNotNil(model.departureMETAR, "existing METAR loading must still work")
        XCTAssertFalse(model.pireps.isEmpty, "existing PIREPs must still load")
        XCTAssertTrue(model.weatherStatus.contains("Mock weather loaded"))
    }

    // MARK: - Sampled-radar-cell diagnostics overlay

    /// The overlay of the sampler's radar clusters is a verification aid — off until turned on.
    func testSampledRadarCellsOverlayDefaultsOff() {
        XCTAssertFalse(makeModel().showSampledRadarCells,
                       "the sampled-radar-cell diagnostic overlay is off by default")
    }

    /// The Diagnostics readout reports how many cells the sampler produced, most-severe first,
    /// so an empty result (with weather plainly on the route) points at the sampling step.
    func testSampledRadarCellSummaryReportsCountAndBreakdown() {
        let model = makeModel()
        XCTAssertEqual(model.sampledRadarCellSummary, "None", "no sampled cells reads as None")

        let c = CLLocationCoordinate2D(latitude: 35, longitude: -97)
        model.radarOverlay.sampledCells = [
            RadarCell(polygon: box(around: c, half: 0.2), intensity: .moderate),
            RadarCell(polygon: box(around: c, half: 0.2), intensity: .heavy),
            RadarCell(polygon: box(around: c, half: 0.2), intensity: .heavy),
            RadarCell(polygon: box(around: c, half: 0.2), intensity: .extreme)
        ]
        let summary = model.sampledRadarCellSummary
        XCTAssertTrue(summary.hasPrefix("4 "), summary)
        XCTAssertTrue(summary.contains("1 extreme"), summary)
        XCTAssertTrue(summary.contains("2 heavy"), summary)
        XCTAssertTrue(summary.contains("1 moderate"), summary)
        // Ordered most-severe first.
        guard let ext = summary.range(of: "extreme"),
              let hvy = summary.range(of: "heavy"),
              let mod = summary.range(of: "moderate") else {
            return XCTFail("summary should break down every present intensity: \(summary)")
        }
        XCTAssertTrue(ext.lowerBound < hvy.lowerBound && hvy.lowerBound < mod.lowerBound,
                      "breakdown is ordered most-severe first: \(summary)")
    }

    // MARK: - Rejoin cap: mint lines end at least 20 NM before the airport

    /// A straight northbound plan on one meridian — departure, three enroute fixes, then
    /// the airport — so along-route distance is just the latitude span. When
    /// `approachFixBeforeAirportNM > 0`, a named approach fix is inserted that far before
    /// the field. Used to exercise the rejoin cap in isolation.
    private func northboundPlanModel(approachFixBeforeAirportNM: Double = 0) -> AppModel {
        let model = AppModel()
        model.settings.mockMode = true
        let lon = -95.0
        var plan = FlightPlan()
        plan.departure = "KAAA"
        plan.destination = "KZZZ"
        plan.departureLatitude = 30.0;  plan.departureLongitude = lon
        plan.destinationLatitude = 34.0; plan.destinationLongitude = lon
        var wps = [Waypoint(name: "WPTA", latitude: 31.0, longitude: lon),
                   Waypoint(name: "WPTB", latitude: 32.0, longitude: lon),
                   Waypoint(name: "WPTC", latitude: 33.0, longitude: lon)]
        if approachFixBeforeAirportNM > 0 {
            wps.append(Waypoint(name: "APPFX",
                                latitude: 34.0 - approachFixBeforeAirportNM / 60.0, longitude: lon))
            plan.approachStartFixName = "APPFX"
        }
        plan.waypoints = wps
        model.flightPlan = plan
        return model
    }

    private var northboundAirport: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: 34.0, longitude: -95.0)
    }

    /// With no approach fix, the rejoin cap sits ~20 NM before the airport, on the filed
    /// route — so the mint line always terminates on the flight path short of the field.
    func testRejoinCapEndsAtLeast20NMBeforeAirport() {
        guard let cap = northboundPlanModel().weatherRejoinCapForTesting() else {
            return XCTFail("expected a rejoin cap for a plan with a destination")
        }
        XCTAssertEqual(Geo.distanceNM(from: cap, to: northboundAirport), 20, accuracy: 1.0,
                       "the cap sits ~20 NM before the airport")
        XCTAssertEqual(cap.longitude, -95.0, accuracy: 0.02, "the cap lies on the filed route")
    }

    /// An approach fix farther out than the 20 NM floor still bounds the rejoin: the cap
    /// holds at the approach fix, so the line never routes into the approach.
    func testRejoinCapHoldsShortOfAFartherApproachFix() {
        guard let cap = northboundPlanModel(approachFixBeforeAirportNM: 40).weatherRejoinCapForTesting() else {
            return XCTFail("expected a rejoin cap")
        }
        XCTAssertEqual(Geo.distanceNM(from: cap, to: northboundAirport), 40, accuracy: 1.5,
                       "an approach fix farther out than the margin caps the line there")
    }

    /// A close-in approach fix (inside the floor) does not let the line end nearer than
    /// 20 NM: the airport margin wins, so the mint line still terminates ≥ 20 NM out.
    func testRejoinCapKeepsThe20NMFloorOverACloseApproachFix() {
        guard let cap = northboundPlanModel(approachFixBeforeAirportNM: 8).weatherRejoinCapForTesting() else {
            return XCTFail("expected a rejoin cap")
        }
        XCTAssertEqual(Geo.distanceNM(from: cap, to: northboundAirport), 20, accuracy: 1.0,
                       "a close-in approach fix does not let the line end inside the 20 NM floor")
    }

    // MARK: - Departure floor: the first mint line starts at least 20 NM out

    /// No mint line may start within 20 NM of the departure airport — weather on the immediate
    /// climb-out is worked by departure vectors, not a drawn enroute deviation. A storm close to
    /// the departure (whose ~30° turn-out lead would otherwise reach right back to the field)
    /// must have its line begin at least 20 NM out, and a line is still drawn.
    func testFirstMintLineStartsAtLeast20NMFromDeparture() {
        let model = makeModel()
        model.flightPlan.waypoints = []   // straight departure→destination corridor
        let dep = model.mock.route.depCoord
        let dest = model.mock.route.destCoord
        let course = Geo.bearing(from: dep, to: dest)
        // A heavy storm whose near edge sits ~29 NM off the departure, on the corridor: without
        // the floor its ~30° turn-out lead would reach right back to the field; the floor holds
        // the start ≥ 20 NM out.
        model.radarOverlay.mockCells = [
            RadarCell(polygon: box(around: Geo.destination(from: dep, bearingDegrees: course, distanceNM: 38),
                                   half: 0.15),
                      intensity: .heavy)
        ]
        model.recomputeWeatherHazards()   // on the ground at the departure gate

        XCTAssertFalse(model.lockedDeviations.isEmpty, "the storm still draws a mint line")
        for dev in model.lockedDeviations {
            guard let start = dev.deviationPath.first else { continue }
            XCTAssertGreaterThanOrEqual(Geo.distanceNM(from: dep, to: start), 18,
                                        "no mint line starts within 20 NM of the departure airport")
        }
    }
}
