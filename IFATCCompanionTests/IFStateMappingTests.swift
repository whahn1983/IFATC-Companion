import XCTest
@testable import IFATCCompanion

/// Guards how logical aircraft-state keys resolve against the live Connect manifest.
/// In particular the magnetic and true heading states must resolve to *distinct*
/// entries — the map orients the aircraft symbol by the true heading, while ATC
/// phraseology uses the magnetic heading, so a collision would silently reintroduce
/// the declination-sized rotation error the true-heading path fixes.
final class IFStateMappingTests: XCTestCase {

    /// A trimmed manifest carrying both heading states exactly as Infinite Flight
    /// names them (`id,type,name` per line; type 2 = float, 3 = double).
    private let manifest = """
    746,3,aircraft/0/latitude
    747,3,aircraft/0/longitude
    731,2,aircraft/0/heading_magnetic
    732,2,aircraft/0/heading_true
    744,2,aircraft/0/course
    716,2,aircraft/0/magnetic_variation
    """

    func testMagneticAndTrueHeadingResolveToDistinctStates() {
        let entries = IFManifestParser.parse(manifest)
        let store = IFStateMappingStore()
        store.resolve(from: entries)

        XCTAssertEqual(store.entry(for: .heading)?.name, "aircraft/0/heading_magnetic")
        XCTAssertEqual(store.entry(for: .trueHeading)?.name, "aircraft/0/heading_true")
        // They must not collapse onto the same manifest entry.
        XCTAssertNotEqual(store.entry(for: .heading)?.id, store.entry(for: .trueHeading)?.id)
    }

    /// When the sim only exposes a single magnetic heading, the true-heading key is
    /// left unresolved (the map then falls back to the magnetic heading rather than
    /// mis-binding to it).
    func testTrueHeadingUnresolvedWhenAbsent() {
        let entries = IFManifestParser.parse("""
        746,3,aircraft/0/latitude
        731,2,aircraft/0/heading_magnetic
        """)
        let store = IFStateMappingStore()
        store.resolve(from: entries)

        XCTAssertEqual(store.entry(for: .heading)?.name, "aircraft/0/heading_magnetic")
        XCTAssertNil(store.entry(for: .trueHeading))
    }

    // MARK: - What a name is allowed to match

    /// A signature matched across ~1700 states plus every command lands on whatever shares a
    /// word with it. The ground track resolved onto `is_on_flight_plan_track` — a *bool* — so
    /// the track read as 0° or 57° and went into the wind triangle as if it were a bearing.
    /// A measurement only ever resolves onto a measurement.
    func testTheGroundTrackNeverResolvesOntoAFlightPlanBool() {
        let entries = IFManifestParser.parse("""
        731,2,aircraft/0/heading_magnetic
        744,2,aircraft/0/course
        945,0,aircraft/0/is_on_flight_plan_track
        """)
        let store = IFStateMappingStore()
        store.resolve(from: entries)

        XCTAssertEqual(store.entry(for: .track)?.name, "aircraft/0/course")
        XCTAssertNotEqual(store.entry(for: .track)?.name, "aircraft/0/is_on_flight_plan_track")
    }

    /// With no track-like measurement exposed the key stays unresolved, so the wind triangle
    /// simply doesn't solve — rather than solving off a bool.
    func testTheGroundTrackIsUnresolvedRatherThanWrongWhenAbsent() {
        let store = IFStateMappingStore()
        store.resolve(from: IFManifestParser.parse("945,0,aircraft/0/is_on_flight_plan_track"))
        XCTAssertNil(store.entry(for: .track))
    }

    /// Commands share their words with the states they act on, and are not readable values.
    func testAStateNeverResolvesOntoACommand() {
        let entries = IFManifestParser.parse("""
        1100,-1,commands/ParkingBrakes
        845,0,aircraft/0/systems/parking_brakes/state
        1101,-1,commands/Autopilot.SetApproachModeState
        """)
        let store = IFStateMappingStore()
        store.resolve(from: entries)

        XCTAssertEqual(store.entry(for: .parkingBrake)?.name, "aircraft/0/systems/parking_brakes/state")
        XCTAssertNil(store.entry(for: .approachMode), "a command is not an approach-mode reading")
    }

    // MARK: - Environment wind states

    /// The sim's own wind states resolve — and the steady wind is never read off the **gust**
    /// state sitting next to it in the same group.
    func testEnvironmentWindStatesResolveAndNeverMatchTheGust() {
        let entries = IFManifestParser.parse("""
        901,2,environment/turbulence_factor
        902,2,environment/temperature
        903,2,environment/wind_gust_velocity
        904,2,environment/wind_velocity
        905,2,environment/wind_direction_true
        906,2,environment/surface_temperature
        """)
        let store = IFStateMappingStore()
        store.resolve(from: entries)

        XCTAssertEqual(store.entry(for: .windVelocity)?.name, "environment/wind_velocity")
        XCTAssertEqual(store.entry(for: .windDirectionTrue)?.name, "environment/wind_direction_true")
        XCTAssertNotEqual(store.entry(for: .windVelocity)?.name, "environment/wind_gust_velocity",
                          "the steady wind must not resolve onto the gust state")
    }

    /// A version that exposes neither leaves both unresolved, so the reader simply reports no
    /// sim wind and the solved wind triangle carries on as before.
    func testWindStatesUnresolvedWhenAbsent() {
        let entries = IFManifestParser.parse("746,3,aircraft/0/latitude")
        let store = IFStateMappingStore()
        store.resolve(from: entries)

        XCTAssertNil(store.entry(for: .windVelocity))
        XCTAssertNil(store.entry(for: .windDirectionTrue))
    }

    /// The reported-vs-solved delta is what settles whether the sim's `wind_direction_true`
    /// is the meteorological "from" or the direction the wind blows "toward" — the two differ
    /// by exactly 180°, and the state name doesn't say which.
    func testReportedWindDeltaNamesTheConvention() {
        var d = WeatherProviderDiagnostics.empty
        d.solvedWindFromDegrees = 220
        d.reportedWindKnots = 14

        d.reportedWindDirectionTrue = 221
        XCTAssertEqual(d.reportedWindDeltaText, "1° — sim reports “from”")

        d.reportedWindDirectionTrue = 41
        XCTAssertEqual(d.reportedWindDeltaText, "-179° — sim reports “toward”")

        // Neither: the two disagree in a way no convention explains.
        d.reportedWindDirectionTrue = 310
        XCTAssertEqual(d.reportedWindDeltaText, "90° — inconsistent")

        // Nothing to compare against until the triangle has solved.
        d.solvedWindFromDegrees = nil
        XCTAssertNil(d.reportedWindDeltaText)
    }

    /// Both wind rows print both frames. Every wind the app holds is true, while the sim's own
    /// panel shows the wind magnetic — so the true figure alone made a correct wind look wrong
    /// by exactly the local variation, which is the whole thing these rows exist to be checked
    /// against. Captured: 346° true beside an instrument reading 352°, 6.2°W apart.
    func testWindRowsCarryBothFramesSoTheyCanBeReadAgainstTheSimsPanel() {
        var d = WeatherProviderDiagnostics.empty
        d.reportedWindDirectionTrue = 346
        d.reportedWindKnots = 9
        d.solvedWindFromDegrees = 346
        d.solvedWindKnots = 9
        d.magneticVariationEast = -6.2      // 6.2°W
        XCTAssertEqual(d.reportedWindText, "346°T · 352°M / 9 kt")
        XCTAssertEqual(d.solvedWindText, "346°T · 352°M / 9 kt")

        // East variation steps the other way — the same 14.5° that pinned the convention
        // against the sim's PFD in the first place.
        d.magneticVariationEast = 14.5
        XCTAssertEqual(d.reportedWindText, "346°T · 332°M / 9 kt")

        // The magnetic figure wraps through north rather than printing 360°.
        d.magneticVariationEast = -6.2
        d.reportedWindDirectionTrue = 356
        XCTAssertEqual(d.reportedWindText, "356°T · 002°M / 9 kt")

        // Until the variation is solved there is nothing to step by — the true figure alone,
        // labelled so it can't be mistaken for the instrument's frame.
        d.magneticVariationEast = nil
        XCTAssertEqual(d.reportedWindText, "356°T / 9 kt")
    }

    // MARK: - Heading units (radians vs degrees)

    /// Radians are converted; degrees are taken at face value. The units are settled once
    /// per state snapshot, because a single reading can't tell them apart.
    func testHeadingAnglesAreNormalizedByTheSnapshotsUnits() {
        // Radians in: π/2 is 090°, and 4 rad is 229°.
        XCTAssertEqual(IFConnectStateReader.normalizeAngle(.pi / 2, alreadyDegrees: false), 90, accuracy: 0.001)
        XCTAssertEqual(IFConnectStateReader.normalizeAngle(4, alreadyDegrees: false), 229.183, accuracy: 0.01)
        // Degrees in: unchanged, and wrapped to 0–360.
        XCTAssertEqual(IFConnectStateReader.normalizeAngle(4, alreadyDegrees: true), 4, accuracy: 0.001)
        XCTAssertEqual(IFConnectStateReader.normalizeAngle(-10, alreadyDegrees: true), 350, accuracy: 0.001)
    }

    /// Regression: judging each value on its own mangles a heading near north on a build that
    /// reports degrees — 004° and 4 rad are the same number on the wire, and the old per-value
    /// guess read every heading below ~6° as radians, turning 004° into 229°. That fed the
    /// wind triangle (`HeadingSolver.wind`) and every weather vector solved from it. The
    /// snapshot decides: any one angle too large to be radians makes them all degrees.
    func testNearNorthHeadingIsNotMistakenForRadiansWhenTheSnapshotIsInDegrees() {
        // A snapshot in degrees: nose on 004°, tracking 007°, magnetic 003°.
        let snapshot: [Double] = [4, 7, 3]
        let inDegrees = snapshot.contains { IFConnectStateReader.exceedsFullCircleInRadians($0) }
        XCTAssertTrue(inDegrees, "a reading of 7 cannot be radians, so the snapshot is in degrees")
        XCTAssertEqual(IFConnectStateReader.normalizeAngle(4, alreadyDegrees: inDegrees), 4, accuracy: 0.001)

        // The same snapshot in radians stays radians and converts.
        let radians: [Double] = [0.07, 0.12, 0.05]
        let radiansInDegrees = radians.contains { IFConnectStateReader.exceedsFullCircleInRadians($0) }
        XCTAssertFalse(radiansInDegrees)
        XCTAssertEqual(IFConnectStateReader.normalizeAngle(0.07, alreadyDegrees: radiansInDegrees),
                       4.011, accuracy: 0.01)
    }

    /// Feed one telemetry snapshot's raw angles to the units decision, exactly as
    /// `IFConnectStateReader.readState` does.
    private func note(_ angles: [Double], on store: IFStateMappingStore, heading: Double? = nil) {
        store.noteAngleSnapshot(
            provesDegrees: angles.contains { IFConnectStateReader.provesDegrees($0) },
            anyAboveRadianCircle: angles.contains { IFConnectStateReader.exceedsFullCircleInRadians($0) },
            rawHeading: heading ?? angles.first)
    }

    /// Regression: a snapshot whose angles are *all* within ~6° of north witnesses nothing —
    /// nose 004°, track 004°, a northerly wind are each a valid radian reading — so a build
    /// reporting degrees was read as radians for as long as it stayed pointed north, which is
    /// exactly what a north-facing runway makes an aircraft do. The proof therefore carries
    /// across snapshots rather than being retaken on each one.
    func testTheDegreesProofCarriesAcrossSnapshots() {
        let store = IFStateMappingStore()
        XCTAssertFalse(store.anglesProvedDegrees, "nothing witnessed yet — assume radians")

        // A snapshot with no witness changes nothing.
        note([4.0, 4.0, 3.5], on: store)
        XCTAssertFalse(store.anglesProvedDegrees)

        // Headings off north prove it — 47 cannot be radians — once corroborated.
        for _ in 0..<IFStateMappingStore.degreeWitnessesToProve { note([47.0, 44.0, 350.0], on: store) }
        XCTAssertTrue(store.anglesProvedDegrees)

        // Back to a north-facing runway: the proof holds, so 004° stays 004°.
        note([4.0, 4.0, 3.5], on: store)
        XCTAssertTrue(store.anglesProvedDegrees, "units don't change mid-connection")
        XCTAssertEqual(IFConnectStateReader.normalizeAngle(4, alreadyDegrees: store.anglesProvedDegrees),
                       4, accuracy: 0.001)

        // A fresh manifest is a fresh connection, and possibly a different IF build.
        store.resolve(from: IFManifestParser.parse(manifest))
        XCTAssertFalse(store.anglesProvedDegrees)
    }

    /// Regression (field report: the aircraft symbol pointed north on the taxi and weather
    /// maps whatever the nose was doing). On a build reporting radians every heading is 0…6.28,
    /// so reading it as degrees pins the symbol within 6° of north — and the proof was taken on
    /// a *single* reading, so one anomalous number settled the whole session. Two consecutive
    /// witnesses are required, and a lone stray reading is forgotten by the next snapshot.
    func testOneStrayReadingCannotDecideTheUnits() {
        let store = IFStateMappingStore()
        let radians = [2.967, 2.9, 5.5]      // nose 170°, tracking 166°, wind from 315°

        note(radians, on: store)
        // One desynchronised read drops another state's number into a heading slot.
        note([28.43, 2.9, 5.5], on: store)   // the aircraft's latitude, in a heading's place
        XCTAssertFalse(store.anglesProvedDegrees, "a single reading is not proof of the units")

        note(radians, on: store)
        XCTAssertFalse(store.anglesProvedDegrees, "and the stray reading is forgotten")
        XCTAssertEqual(IFConnectStateReader.normalizeAngle(2.967, alreadyDegrees: store.anglesProvedDegrees),
                       170, accuracy: 0.1, "the nose still reads 170°, not 003°")
    }

    /// A number past a full circle *in degrees* is not an angle in either convention — it is
    /// the answer to some other state — so it witnesses nothing however often it repeats.
    func testAnImplausibleReadingProvesNothingAboutTheUnits() {
        XCTAssertTrue(IFConnectStateReader.provesDegrees(170))
        XCTAssertTrue(IFConnectStateReader.provesDegrees(350))
        XCTAssertFalse(IFConnectStateReader.provesDegrees(3.0), "3 is a valid radian heading")
        XCTAssertFalse(IFConnectStateReader.provesDegrees(450), "no heading reads 450°")
        XCTAssertFalse(IFConnectStateReader.provesDegrees(29_260), "an altitude in a heading slot")

        let store = IFStateMappingStore()
        for _ in 0..<6 { note([29_260, 2.9, 5.5], on: store) }
        XCTAssertFalse(store.anglesProvedDegrees)
    }

    /// The proof can be contradicted, so a wrong one costs seconds rather than the session. No
    /// single reading proves radians — every radian value is a valid degree value — but a
    /// heading that visits three quadrants of the 0…2π circle without one reading ever passing
    /// a full circle in radians is an aircraft turning through the compass, not one holding
    /// inside a 6° arc of north.
    func testARunOfRadianHeadingsContradictsAWrongDegreesProof() {
        let store = IFStateMappingStore()
        // Two plausible-but-wrong readings in a row take the proof.
        for _ in 0..<IFStateMappingStore.degreeWitnessesToProve { note([28.43, 2.9], on: store) }
        XCTAssertTrue(store.anglesProvedDegrees)

        // Then the aircraft taxis a circuit: every reading a radian heading, sweeping the rose.
        let sweep: [Double] = [0.4, 1.2, 2.0, 2.9, 3.6, 4.4, 5.2, 6.0, 0.6, 1.8, 3.1, 4.9]
        XCTAssertGreaterThanOrEqual(sweep.count, IFStateMappingStore.radianSamplesToDisprove)
        for heading in sweep { note([heading, 2.9], on: store, heading: heading) }

        XCTAssertFalse(store.anglesProvedDegrees, "a full sweep of the rose can only be radians")
        XCTAssertEqual(IFConnectStateReader.normalizeAngle(2.967, alreadyDegrees: store.anglesProvedDegrees),
                       170, accuracy: 0.1)
    }

    /// …and the contradiction never fires on a build that really does report degrees: holding
    /// short on a north-facing runway keeps every angle inside the radian circle, but the nose
    /// stays in one quadrant of it, and any reading off north resets the run outright.
    func testAGenuineDegreesProofSurvivesHoldingShortOnANorthFacingRunway() {
        let store = IFStateMappingStore()
        for _ in 0..<IFStateMappingStore.degreeWitnessesToProve { note([170.0, 166.0], on: store) }
        XCTAssertTrue(store.anglesProvedDegrees)

        // Lined up on 36: 004° magnetic, 003° true — no witness, for a long hold.
        for _ in 0..<(IFStateMappingStore.radianSamplesToDisprove * 3) { note([4.0, 3.0], on: store) }
        XCTAssertTrue(store.anglesProvedDegrees, "004° is one quadrant of the radian circle, not three")
        XCTAssertEqual(IFConnectStateReader.normalizeAngle(4, alreadyDegrees: store.anglesProvedDegrees),
                       4, accuracy: 0.001)
    }

    /// Regression: bank was passed through raw, so on a build reporting radians a 25° bank
    /// arrived as `0.44` and every degree-scaled test of it quietly passed — the wings-level
    /// guard on the wind sample (`HeadingSolver.maxSampleBankDegrees`, 5°) never tripped, and
    /// the triangle was solved in the middle of a turn. Bank follows the snapshot's units like
    /// every other angle, and stays signed about zero rather than wrapping onto a compass rose.
    func testBankFollowsTheSnapshotsUnitsAndStaysSigned() {
        // Radians in: a 25° right bank, and a 25° left bank that must not read as 335°.
        XCTAssertEqual(IFConnectStateReader.normalizeSignedAngle(0.4363, alreadyDegrees: false),
                       25, accuracy: 0.01)
        XCTAssertEqual(IFConnectStateReader.normalizeSignedAngle(-0.4363, alreadyDegrees: false),
                       -25, accuracy: 0.01)
        // Degrees in: taken at face value, sign intact.
        XCTAssertEqual(IFConnectStateReader.normalizeSignedAngle(25, alreadyDegrees: true), 25, accuracy: 0.001)
        XCTAssertEqual(IFConnectStateReader.normalizeSignedAngle(-4, alreadyDegrees: true), -4, accuracy: 0.001)

        // The guard the conversion exists for: banked past the threshold either way.
        for raw in [0.4363, -0.4363] {
            let bank = IFConnectStateReader.normalizeSignedAngle(raw, alreadyDegrees: false)
            XCTAssertGreaterThan(abs(bank), HeadingSolver.maxSampleBankDegrees,
                                 "a quarter-bank turn must stand the wind triangle down")
        }
        // ...and wings level still reads as level.
        XCTAssertLessThanOrEqual(abs(IFConnectStateReader.normalizeSignedAngle(-0.0349, alreadyDegrees: false)),
                                 HeadingSolver.maxSampleBankDegrees)
    }

    /// Why that guard matters: the triangle differences two ~450 kt vectors, so a sample taken
    /// where heading and track are seconds apart in a roll invents a wind out of nothing. This
    /// is the captured failure — 11° of lag at 460 kt solving to ~87 kt of wind that was never
    /// there, against the 12 kt the sim itself was reporting.
    func testATurnSmearsTheWindTriangleIntoAWindThatIsNotThere() {
        var s = AircraftState()
        s.onGround = false
        s.trueHeading = 287
        s.track = 276          // still swinging round behind the nose
        s.trueAirspeed = 460
        s.groundSpeed = 460
        let solved = HeadingSolver.wind(from: s)
        XCTAssertGreaterThan(solved?.speedKnots ?? 0, 60,
                             "a lagging track alone solves to a gale — hence the wings-level guard")

        // Wings level, the same aircraft in the same air solves the real wind: 12 kt from 331.
        var level = s
        level.track = 285.94
        level.groundSpeed = 451.44
        let real = HeadingSolver.wind(from: level)
        XCTAssertEqual(real?.speedKnots ?? 0, 12, accuracy: 1.5)
        XCTAssertEqual(real?.fromDegrees ?? 0, 331, accuracy: 8)
    }
}
