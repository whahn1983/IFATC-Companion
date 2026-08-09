import Foundation
import CoreLocation

/// Pure geometry for approach vectors: the heading that intercepts a runway's
/// final approach course at ~30°, turning toward the extended centerline from
/// whichever side the aircraft is on. Deterministic and dependency-free so it
/// can be unit-tested without the app model.
enum ApproachIntercept {

    /// A conventional 30° intercept angle to the final approach course.
    static let interceptAngleDegrees: Double = 30

    /// Inside this cross-track distance the aircraft is treated as established on
    /// the centerline, so the vector is the final course straight in.
    static let establishedCrossTrackNM: Double = 0.5

    /// The heading (0…359) to fly to intercept the final approach course.
    ///
    /// - Parameters:
    ///   - finalCourse: the landing runway's heading (deg) — i.e. the direction
    ///     of travel on final. Magnetic, derived from the runway number; the
    ///     assigned "fly heading" is magnetic to match the sim.
    ///   - aircraft: current aircraft position.
    ///   - runwayReference: the runway threshold (or the airport reference point
    ///     as an approximation), used to place the extended centerline.
    ///   - variationDegreesEast: local magnetic variation, east positive, used to
    ///     lay the extended centerline out on the ground. `finalCourse` is magnetic
    ///     but `Geo.destination` steers in true degrees, so without it the centerline
    ///     is drawn rotated by the local declination — 1° is ~0.35 NM of error at the
    ///     20 NM gate, and ~15° of declination is over 5 NM, enough to put the aircraft
    ///     on the wrong side of a centerline it is close to and turn it 30° the wrong
    ///     way. Defaults to 0 (the geometry the app used before it was measured), which
    ///     is exact wherever variation is.
    ///
    /// The aircraft's side of the extended centerline is found from the signed
    /// cross-track distance to the inbound final course line; the intercept turns
    /// 30° toward the centerline from that side. When the aircraft is already on
    /// or near the centerline, the final course itself is returned (straight in).
    ///
    /// The result stays in the magnetic frame `finalCourse` arrived in: it is an
    /// intercept *vector*, and the sim's own approach mode flies the centerline once
    /// established, so no wind correction is applied to it.
    static func heading(finalCourse: Double,
                        aircraft: CLLocationCoordinate2D,
                        runwayReference: CLLocationCoordinate2D,
                        variationDegreesEast: Double = 0) -> Int {
        // The final approach course line: from a gate ~20 NM out on the extended
        // centerline, inbound to the runway. Laid out in true degrees, since that is
        // the frame the great-circle helpers steer in.
        let trueFinalCourse = finalCourse + variationDegreesEast
        let outbound = (trueFinalCourse + 180).truncatingRemainder(dividingBy: 360)
        let gate = Geo.destination(from: runwayReference, bearingDegrees: outbound, distanceNM: 20)
        let crossTrack = Geo.crossTrackDistanceNM(point: aircraft, pathStart: gate, pathEnd: runwayReference)

        let intercept: Double
        if abs(crossTrack) < establishedCrossTrackNM {
            intercept = finalCourse
        } else {
            // Positive cross-track = right of the inbound course → turn left (−30);
            // negative = left of course → turn right (+30).
            intercept = finalCourse - (crossTrack > 0 ? interceptAngleDegrees : -interceptAngleDegrees)
        }
        return normalizedHeading(intercept)
    }

    /// Normalize a heading to 0…359, matching the app's heading display/spoken
    /// convention (`Phonetic.heading` / `headingDisplay`), where north reads "000".
    static func normalizedHeading(_ degrees: Double) -> Int {
        ((Int(degrees.rounded()) % 360) + 360) % 360
    }
}
