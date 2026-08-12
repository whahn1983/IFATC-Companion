import XCTest
@testable import IFATCCompanion

/// The mint line is drawn with great-circle geometry, so its legs are **true** courses,
/// but the aircraft is flown on a magnetic heading bug and pushed sideways by the wind.
/// `HeadingSolver` is what closes both gaps; these tests pin the conversions it makes and
/// — just as importantly — the cases where it declines to make one.
final class HeadingSolverTests: XCTestCase {

    // MARK: - Builders

    /// A cruising state carrying an exact wind, built by vector addition rather than by
    /// hand-computed track/groundspeed constants: the ground vector *is* the air vector
    /// plus the wind, so a solver that recovers the wind from it has inverted the same
    /// triangle the aircraft is actually flying.
    private func cruising(trueHeading: Double,
                          trueAirspeed: Double,
                          windFrom: Double,
                          windSpeed: Double,
                          variationEast: Double = 0) -> AircraftState {
        func rad(_ d: Double) -> Double { d * .pi / 180 }
        // The wind blows *toward* the reciprocal of the direction it is named for.
        let toward = windFrom + 180
        let east = trueAirspeed * sin(rad(trueHeading)) + windSpeed * sin(rad(toward))
        let north = trueAirspeed * cos(rad(trueHeading)) + windSpeed * cos(rad(toward))

        var s = AircraftState()
        s.onGround = false
        s.trueHeading = trueHeading
        s.heading = (trueHeading - variationEast).truncatingRemainder(dividingBy: 360)
        s.trueAirspeed = trueAirspeed
        s.groundSpeed = (east * east + north * north).squareRoot()
        var track = atan2(east, north) * 180 / .pi
        if track < 0 { track += 360 }
        s.track = track
        return s
    }

    // MARK: - Magnetic variation

    /// Variation is read straight off the sim's own pair of headings: it reports the same
    /// nose direction twice, and the difference between them is the local declination.
    func testVariationIsTheDifferenceBetweenTheSimsTwoHeadings() {
        var east = AircraftState()
        east.trueHeading = 100
        east.heading = 90
        XCTAssertEqual(HeadingSolver.variationDegreesEast(from: east) ?? .nan, 10, accuracy: 0.001,
                       "true 100 / magnetic 090 is 10° east variation")

        var west = AircraftState()
        west.trueHeading = 90
        west.heading = 100
        XCTAssertEqual(HeadingSolver.variationDegreesEast(from: west) ?? .nan, -10, accuracy: 0.001,
                       "true 090 / magnetic 100 is 10° west variation")
    }

    /// A pair straddling north must stay a small signed angle, not 350°.
    func testVariationStaysSignedAcrossNorth() {
        var s = AircraftState()
        s.trueHeading = 5
        s.heading = 355
        XCTAssertEqual(HeadingSolver.variationDegreesEast(from: s) ?? .nan, 10, accuracy: 0.001)

        s.trueHeading = 355
        s.heading = 5
        XCTAssertEqual(HeadingSolver.variationDegreesEast(from: s) ?? .nan, -10, accuracy: 0.001)
    }

    /// Connect coverage varies by version. With only one of the two headings exposed there
    /// is nothing to measure, and the caller must be told so rather than handed a zero.
    func testVariationUnavailableWhenTheSimExposesOnlyOneHeading() {
        var magneticOnly = AircraftState()
        magneticOnly.heading = 90
        XCTAssertNil(HeadingSolver.variationDegreesEast(from: magneticOnly))

        var trueOnly = AircraftState()
        trueOnly.trueHeading = 90
        XCTAssertNil(HeadingSolver.variationDegreesEast(from: trueOnly))
    }

    // MARK: - Wind

    /// The wind triangle, inverted: given what the aircraft is doing through the air and
    /// over the ground, recover the wind that separates them.
    func testWindIsSolvedFromTheAircraftsOwnTriangle() {
        let state = cruising(trueHeading: 90, trueAirspeed: 400, windFrom: 180, windSpeed: 40)
        guard let wind = HeadingSolver.wind(from: state) else {
            return XCTFail("a cruising aircraft with track, heading, GS and TAS solves a wind")
        }
        XCTAssertEqual(wind.fromDegrees, 180, accuracy: 0.5)
        XCTAssertEqual(wind.speedKnots, 40, accuracy: 0.5)
    }

    /// A wind named across north must come back as ~350°, not as its reciprocal or as a
    /// negative angle.
    func testWindDirectionAcrossNorthIsRecovered() {
        let state = cruising(trueHeading: 270, trueAirspeed: 450, windFrom: 350, windSpeed: 60)
        guard let wind = HeadingSolver.wind(from: state) else { return XCTFail("expected a wind") }
        XCTAssertEqual(wind.fromDegrees, 350, accuracy: 0.5)
        XCTAssertEqual(wind.speedKnots, 60, accuracy: 0.5)
    }

    /// Track equal to heading with groundspeed equal to TAS is the definition of no wind.
    func testNoWindWhenTrackAndHeadingAgree() {
        var s = AircraftState()
        s.onGround = false
        s.trueHeading = 120
        s.track = 120
        s.trueAirspeed = 460
        s.groundSpeed = 460
        guard let wind = HeadingSolver.wind(from: s) else {
            return XCTFail("a still-air cruise solves a wind — a calm one")
        }
        XCTAssertEqual(wind.speedKnots, 0, accuracy: 0.001)
    }

    /// On the ground, and at taxi/rollout speeds, the triangle means nothing — the crab
    /// angle goes hyperbolic as TAS approaches the wind speed.
    func testWindNotSolvedOnTheGroundOrBelowTheAirspeedFloor() {
        var onGround = cruising(trueHeading: 90, trueAirspeed: 400, windFrom: 180, windSpeed: 40)
        onGround.onGround = true
        XCTAssertNil(HeadingSolver.wind(from: onGround))

        var slow = cruising(trueHeading: 90, trueAirspeed: 400, windFrom: 180, windSpeed: 40)
        slow.trueAirspeed = HeadingSolver.minWindSolveTAS - 1
        XCTAssertNil(HeadingSolver.wind(from: slow))
    }

    /// A torn read (a track from one instant against a heading from another) can imply a
    /// wind faster than any real one. That is noise, and must not reach a vector.
    func testImplausiblyFastWindIsDiscardedRatherThanFlown() {
        var s = AircraftState()
        s.onGround = false
        s.trueHeading = 0
        s.track = 180                 // reciprocal of the nose: physically impossible
        s.trueAirspeed = 460
        s.groundSpeed = 460
        XCTAssertNil(HeadingSolver.wind(from: s),
                     "a wind implied at ~920 kt is a bad read, not weather")
    }

    /// Blending happens on the wind's components, so an estimate straddling north settles
    /// near north rather than swinging through south the way averaging bearings would.
    func testBlendingAcrossNorthDoesNotSwingThroughSouth() {
        let previous = HeadingSolver.Wind(fromDegrees: 350, speedKnots: 40)
        let sample = HeadingSolver.Wind(fromDegrees: 10, speedKnots: 40)
        let blended = HeadingSolver.blended(previous, sample: sample, weight: 0.5)
        XCTAssertEqual(blended.fromDegrees, 0, accuracy: 0.5)
        XCTAssertGreaterThan(blended.speedKnots, 35)
    }

    /// A run of identical samples has to converge on them — the smoothing is there to
    /// absorb per-tick jitter, not to permanently discount the wind.
    func testRepeatedSamplesConvergeOnTheWind() {
        let sample = HeadingSolver.Wind(fromDegrees: 270, speedKnots: 50)
        var estimate: HeadingSolver.Wind?
        for _ in 0..<25 { estimate = HeadingSolver.blended(estimate, sample: sample) }
        XCTAssertEqual(estimate?.fromDegrees ?? .nan, 270, accuracy: 0.5)
        XCTAssertEqual(estimate?.speedKnots ?? .nan, 50, accuracy: 0.5)
    }

    // MARK: - Wind correction angle

    /// A wind off the right wing is corrected by crabbing right, into it.
    func testCrabIsIntoTheWind() {
        let fromTheRight = HeadingSolver.Wind(fromDegrees: 180, speedKnots: 40)
        let right = HeadingSolver.windCorrectionDegrees(trueCourse: 90, wind: fromTheRight,
                                                        trueAirspeed: 400)
        XCTAssertEqual(right, asin(40.0 / 400.0) * 180 / .pi, accuracy: 0.01)
        XCTAssertGreaterThan(right, 0, "a wind from the right is met with a turn to the right")

        let fromTheLeft = HeadingSolver.Wind(fromDegrees: 0, speedKnots: 40)
        let left = HeadingSolver.windCorrectionDegrees(trueCourse: 90, wind: fromTheLeft,
                                                       trueAirspeed: 400)
        XCTAssertEqual(left, -right, accuracy: 0.01)
    }

    /// A pure headwind or tailwind slows or hurries the aircraft but never pushes it off
    /// the line, so it earns no correction at all.
    func testHeadwindAndTailwindNeedNoCorrection() {
        let headwind = HeadingSolver.Wind(fromDegrees: 90, speedKnots: 80)
        XCTAssertEqual(HeadingSolver.windCorrectionDegrees(trueCourse: 90, wind: headwind,
                                                           trueAirspeed: 450),
                       0, accuracy: 0.001)
        let tailwind = HeadingSolver.Wind(fromDegrees: 270, speedKnots: 80)
        XCTAssertEqual(HeadingSolver.windCorrectionDegrees(trueCourse: 90, wind: tailwind,
                                                           trueAirspeed: 450),
                       0, accuracy: 0.001)
    }

    /// Crabbing by the solved angle must actually make the aircraft track the course —
    /// that is the whole claim. Fly the corrected heading through the same wind and check
    /// the resulting ground track lands back on the course asked for.
    func testFlyingTheCorrectedHeadingTracksTheCourse() {
        let course = 45.0, tas = 430.0
        let wind = HeadingSolver.Wind(fromDegrees: 320, speedKnots: 55)
        let crab = HeadingSolver.windCorrectionDegrees(trueCourse: course, wind: wind,
                                                       trueAirspeed: tas)
        let flown = cruising(trueHeading: course + crab, trueAirspeed: tas,
                             windFrom: wind.fromDegrees, windSpeed: wind.speedKnots)
        XCTAssertEqual(flown.track ?? .nan, course, accuracy: 0.1,
                       "the crabbed heading must put the aircraft's track back on the leg")
    }

    /// A crosswind that outruns the aircraft cannot be held. The solver must clamp rather
    /// than hand `asin` an out-of-range ratio and produce a NaN heading.
    func testCrosswindBeyondTheAircraftIsClampedNotNaN() {
        let gale = HeadingSolver.Wind(fromDegrees: 180, speedKnots: 900)
        let correction = HeadingSolver.windCorrectionDegrees(trueCourse: 90, wind: gale,
                                                             trueAirspeed: 200)
        XCTAssertTrue(correction.isFinite)
        XCTAssertEqual(correction, HeadingSolver.maxWindCorrectionDegrees, accuracy: 0.001)
    }

    // MARK: - Combined

    /// Both corrections, in order: crab in the true frame, then step into the magnetic one.
    func testAssignedHeadingAppliesCrabThenVariation() {
        let wind = HeadingSolver.Wind(fromDegrees: 180, speedKnots: 40)
        let crab = asin(40.0 / 400.0) * 180 / .pi           // ≈ 5.74°, to the right
        let assigned = HeadingSolver.assignedHeading(forTrueCourse: 90, wind: wind,
                                                     trueAirspeed: 400,
                                                     variationDegreesEast: 10)
        XCTAssertEqual(assigned, Int((90 + crab - 10).rounded()),
                       "crab into the wind, then subtract east variation to reach magnetic")
    }

    /// East variation subtracts, west variation adds — the sign the sim's heading bug reads.
    func testWestVariationAddsToTheAssignedHeading() {
        let east = HeadingSolver.assignedHeading(forTrueCourse: 90, wind: nil,
                                                 trueAirspeed: 400, variationDegreesEast: 12)
        let west = HeadingSolver.assignedHeading(forTrueCourse: 90, wind: nil,
                                                 trueAirspeed: 400, variationDegreesEast: -12)
        XCTAssertEqual(east, 78)
        XCTAssertEqual(west, 102)
    }

    /// The result is a compass heading, so it wraps rather than going negative.
    func testAssignedHeadingWrapsThroughNorth() {
        XCTAssertEqual(HeadingSolver.assignedHeading(forTrueCourse: 5, wind: nil,
                                                     trueAirspeed: 400,
                                                     variationDegreesEast: 10), 355)
        XCTAssertEqual(HeadingSolver.assignedHeading(forTrueCourse: 355, wind: nil,
                                                     trueAirspeed: 400,
                                                     variationDegreesEast: -10), 5)
    }

    /// With neither correction available the solver hands back the course it was given —
    /// exactly what the app assigned before any of this existed. A sim that exposes no
    /// true heading must not have its vectors changed.
    func testNoCorrectionAvailableLeavesTheCourseUntouched() {
        XCTAssertEqual(HeadingSolver.assignedHeading(forTrueCourse: 123.4, wind: nil,
                                                     trueAirspeed: nil,
                                                     variationDegreesEast: nil), 123)
    }

    // MARK: - The sim's own reported wind

    /// `environment/wind_direction_true` is the direction the wind blows **from**, so it is
    /// taken as read. Pinned against Infinite Flight's own PFD: with the state at 5.5069 rad
    /// (315.5° true) the panel showed 301° — the same direction in the magnetic frame, ~14.5°
    /// of local variation apart, not the ~135° a "blows toward" reading would have shown.
    func testReportedWindIsTakenAsTheFromDirection() {
        var s = AircraftState()
        s.reportedWindDirectionTrue = 315.5
        s.reportedWindSpeedKnots = 40
        let wind = HeadingSolver.reportedWind(from: s)
        XCTAssertEqual(wind?.fromDegrees ?? -1, 315.5, accuracy: 0.001)
        XCTAssertEqual(wind?.speedKnots ?? -1, 40, accuracy: 0.001)

        // Read as "from", a 315° wind on a course of 315 is a pure headwind — no crab at all.
        // Read as "toward" it would be a pure tailwind, which is also no crab — so the case
        // that separates them is a crosswind course.
        let crab = HeadingSolver.windCorrectionDegrees(trueCourse: 45, wind: wind, trueAirspeed: 450)
        XCTAssertEqual(crab, asin(40 * sin((315.5 - 45) * .pi / 180) / 450) * 180 / .pi, accuracy: 0.01)
        XCTAssertLessThan(crab, 0, "a wind from 90° left of course crabs the nose left, into it")
    }

    /// Unavailable, implausible, or below the resolvable floor — the same rules the solved
    /// wind already follows, so the two sources agree on what "no usable wind" means.
    func testReportedWindDeclinesTheSameCasesAsTheSolvedWind() {
        XCTAssertNil(HeadingSolver.reportedWind(from: AircraftState()),
                     "a version that exposes neither state reports no wind")

        var partial = AircraftState()
        partial.reportedWindDirectionTrue = 200
        XCTAssertNil(HeadingSolver.reportedWind(from: partial), "direction without speed is unusable")

        var absurd = AircraftState()
        absurd.reportedWindDirectionTrue = 200
        absurd.reportedWindSpeedKnots = 400
        XCTAssertNil(HeadingSolver.reportedWind(from: absurd), "a 400 kt wind is a torn read, not weather")

        var light = AircraftState()
        light.reportedWindDirectionTrue = 315.5
        light.reportedWindSpeedKnots = 0.72     // the 0.3681 m/s in the captured state
        XCTAssertEqual(HeadingSolver.reportedWind(from: light), HeadingSolver.Wind.calm,
                       "below the resolvable floor is reported calm, as the triangle does")
    }

    /// The cross-check that guards the convention: the reported and solved winds should agree
    /// closely, and a disagreement past a right angle is the signature of a build reporting the
    /// other end of the vector.
    func testDirectionDisagreementMeasuresTheShortWayRound() {
        let a = HeadingSolver.Wind(fromDegrees: 350, speedKnots: 40)
        let b = HeadingSolver.Wind(fromDegrees: 10, speedKnots: 40)
        XCTAssertEqual(HeadingSolver.directionDisagreementDegrees(a, b), 20, accuracy: 0.001,
                       "the difference wraps through north rather than reading 340°")

        let flipped = HeadingSolver.Wind(fromDegrees: 170, speedKnots: 40)
        XCTAssertEqual(HeadingSolver.directionDisagreementDegrees(a, flipped), 180, accuracy: 0.001)
    }

    /// The second half of that cross-check. Naming the other end of the vector reverses a wind
    /// without changing its strength, so only two winds of the *same speed* can be the same
    /// wind described two ways.
    func testSpeedsCorroborateOnlyWhenTheTwoWindsCouldBeTheSameWind() {
        let reported = HeadingSolver.Wind(fromDegrees: 221, speedKnots: 40)
        let flipped = HeadingSolver.Wind(fromDegrees: 41, speedKnots: 40)
        XCTAssertTrue(HeadingSolver.speedsCorroborate(reported, flipped),
                      "a convention flip changes the direction and nothing else")

        // The captured failure: the sim reported 12 kt, the triangle solved 84 kt out of a
        // smeared mid-turn sample. Nothing about a convention explains that.
        let sim = HeadingSolver.Wind(fromDegrees: 331, speedKnots: 12)
        let smeared = HeadingSolver.Wind(fromDegrees: 89, speedKnots: 84)
        XCTAssertFalse(HeadingSolver.speedsCorroborate(sim, smeared))

        // Ordinary sampling noise between two readings of the same wind still corroborates —
        // by ratio when the wind is strong, and by the absolute floor when it is light enough
        // that a ratio would call a couple of knots a disagreement.
        XCTAssertTrue(HeadingSolver.speedsCorroborate(HeadingSolver.Wind(fromDegrees: 200, speedKnots: 40),
                                                      HeadingSolver.Wind(fromDegrees: 20, speedKnots: 52)))
        XCTAssertTrue(HeadingSolver.speedsCorroborate(HeadingSolver.Wind(fromDegrees: 200, speedKnots: 3),
                                                      HeadingSolver.Wind(fromDegrees: 20, speedKnots: 7)))
    }
}
