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
}
