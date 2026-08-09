import XCTest
import CoreLocation
@testable import IFATCCompanion

/// Tests for the approach-vector intercept geometry: a 30° intercept to the
/// final approach course, turning toward the extended centerline from whichever
/// side the aircraft is on. All math is deterministic and offline.
final class ApproachInterceptTests: XCTestCase {

    private let airport = CLLocationCoordinate2D(latitude: 40, longitude: -95)

    // MARK: - Runway heading parsing

    func testRunwayHeadingFromIdent() {
        XCTAssertEqual(RunwayDatabase.heading(forRunway: "36"), 360)
        XCTAssertEqual(RunwayDatabase.heading(forRunway: "4L"), 40)
        XCTAssertEqual(RunwayDatabase.heading(forRunway: "22R"), 220)
        XCTAssertEqual(RunwayDatabase.heading(forRunway: "9"), 90)
        XCTAssertNil(RunwayDatabase.heading(forRunway: ""), "no digits -> no heading")
        XCTAssertNil(RunwayDatabase.heading(forRunway: "RW"), "no digits -> no heading")
        XCTAssertNil(RunwayDatabase.heading(forRunway: "40"), "runway numbers are 1…36")
    }

    // MARK: - Heading normalization

    func testNormalizedHeadingWrapsTo0Through359() {
        XCTAssertEqual(ApproachIntercept.normalizedHeading(0), 0)
        XCTAssertEqual(ApproachIntercept.normalizedHeading(360), 0)
        XCTAssertEqual(ApproachIntercept.normalizedHeading(370), 10)
        XCTAssertEqual(ApproachIntercept.normalizedHeading(-10), 350)
    }

    // MARK: - Intercept, north-bound final (runway 36)

    func testStraightInWhenEstablishedOnCenterline() {
        // 15 NM south of the field, dead on the extended centerline.
        let onCenter = Geo.destination(from: airport, bearingDegrees: 180, distanceNM: 15)
        let hdg = ApproachIntercept.heading(finalCourse: 360, aircraft: onCenter, runwayReference: airport)
        XCTAssertEqual(hdg, 0, "on the centerline the vector is the final course straight in (360 → 000)")
    }

    func testWestOfCenterlineTurnsRightToIntercept() {
        // West (left) of a north-bound final → fly north-east (final + 30) to intercept.
        let onCenter = Geo.destination(from: airport, bearingDegrees: 180, distanceNM: 15)
        let west = Geo.destination(from: onCenter, bearingDegrees: 270, distanceNM: 6)
        let hdg = ApproachIntercept.heading(finalCourse: 360, aircraft: west, runwayReference: airport)
        XCTAssertEqual(hdg, 30, "west of centerline intercepts on a 030 heading (360 + 30)")
    }

    func testEastOfCenterlineTurnsLeftToIntercept() {
        // East (right) of a north-bound final → fly north-west (final − 30) to intercept.
        let onCenter = Geo.destination(from: airport, bearingDegrees: 180, distanceNM: 15)
        let east = Geo.destination(from: onCenter, bearingDegrees: 90, distanceNM: 6)
        let hdg = ApproachIntercept.heading(finalCourse: 360, aircraft: east, runwayReference: airport)
        XCTAssertEqual(hdg, 330, "east of centerline intercepts on a 330 heading (360 − 30)")
    }

    // MARK: - Intercept, east-bound final (runway 09)

    func testEastboundFinalInterceptFromEitherSide() {
        // Extended centerline runs west from the field for a 090 final.
        let onCenter = Geo.destination(from: airport, bearingDegrees: 270, distanceNM: 15)

        let north = Geo.destination(from: onCenter, bearingDegrees: 0, distanceNM: 6)
        XCTAssertEqual(ApproachIntercept.heading(finalCourse: 90, aircraft: north, runwayReference: airport),
                       120, "north (left) of an east-bound final intercepts on 120 (090 + 30)")

        let south = Geo.destination(from: onCenter, bearingDegrees: 180, distanceNM: 6)
        XCTAssertEqual(ApproachIntercept.heading(finalCourse: 90, aircraft: south, runwayReference: airport),
                       60, "south (right) of an east-bound final intercepts on 060 (090 − 30)")
    }

    // MARK: - Magnetic variation

    /// The final course is magnetic (it comes from the runway number) but the extended
    /// centerline is laid out with true-degree geometry, so somewhere with real
    /// declination the two have to be reconciled — otherwise the centerline is drawn
    /// rotated and the aircraft is judged to be on the wrong side of it.
    ///
    /// Under 15° east variation a 360 magnetic final really runs 015 true, so its
    /// extended centerline lies out on 195 true. An aircraft sitting due *true* south of
    /// the field — where the variation-blind geometry believed the centerline to be — is
    /// in fact ~3.9 NM east of it, i.e. right of the inbound course, and must turn left.
    func testVariationDecidesWhichSideOfTheCenterlineTheAircraftIsOn() {
        let variation = 15.0
        let onTrueNorthLine = Geo.destination(from: airport, bearingDegrees: 180, distanceNM: 15)

        XCTAssertEqual(ApproachIntercept.heading(finalCourse: 360, aircraft: onTrueNorthLine,
                                                 runwayReference: airport),
                       0, "ignoring variation, this point reads as established on the centerline")

        XCTAssertEqual(ApproachIntercept.heading(finalCourse: 360, aircraft: onTrueNorthLine,
                                                 runwayReference: airport,
                                                 variationDegreesEast: variation),
                       330, "the real centerline runs 195 true, so this point is right of it — turn left")
    }

    /// An aircraft genuinely on the centerline — placed along the *true* course the
    /// magnetic final course corresponds to — is still flown straight in.
    func testEstablishedOnTheTrueCenterlineUnderVariation() {
        let variation = 15.0
        // The 360 magnetic final is 015 true, so its extended centerline runs out on 195 true.
        let onCenter = Geo.destination(from: airport, bearingDegrees: 195, distanceNM: 15)
        XCTAssertEqual(ApproachIntercept.heading(finalCourse: 360, aircraft: onCenter,
                                                 runwayReference: airport,
                                                 variationDegreesEast: variation),
                       0, "established on the real centerline is straight in, in magnetic degrees")
    }

    /// Whatever the geometry decides, the answer never leaves the magnetic frame the
    /// final course arrived in — it is what the pilot dials into the heading bug.
    func testResultStaysMagneticRegardlessOfVariation() {
        let onCenter = Geo.destination(from: airport, bearingDegrees: 195, distanceNM: 15)
        for variation in [-20.0, -5, 0, 5, 20] {
            let hdg = ApproachIntercept.heading(finalCourse: 360, aircraft: onCenter,
                                                runwayReference: airport,
                                                variationDegreesEast: variation)
            // 360 ± 30 in magnetic degrees: 000, 030 or 330 — never rotated by the variation.
            XCTAssertTrue([0, 30, 330].contains(hdg),
                          "variation \(variation) produced \(hdg), which is not a magnetic 360 ± 30")
        }
    }
}
