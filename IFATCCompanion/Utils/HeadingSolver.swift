import Foundation

/// Turns a desired **ground course** into the **heading a pilot dials in**.
///
/// Almost every heading the app assigns is already in the pilot's frame: departure,
/// pattern and approach vectors are all derived from a runway ident, which is magnetic
/// by definition. The weather-deviation vectors are the exception. The mint line is
/// built with great-circle geometry (`Geo.bearing`), so its legs are **true** courses,
/// and it asks the aircraft to *follow a drawn path* rather than merely point somewhere
/// — so the number handed to the pilot has to be the heading that makes the aircraft's
/// **track** lie along the leg, not the leg's bearing itself.
///
/// Two corrections separate the two numbers, applied in this order:
///
/// 1. **Wind correction angle** — the crab into wind that makes a heading produce the
///    desired track. Without it the aircraft is flown on the leg's bearing and the wind
///    walks it off the line for the whole length of the leg; the drift is what the
///    off-path re-plan then has to keep cleaning up.
/// 2. **Magnetic variation** — the aircraft's heading bug reads magnetic, so
///    `magnetic = true − variationEast`.
///
/// Both are solved from the sim's own telemetry rather than from a declination model or
/// a weather feed, and every entry point degrades to "no correction" when the states it
/// needs aren't exposed — Infinite Flight's manifest coverage varies by version, and the
/// uncorrected true bearing is exactly today's behaviour.
enum HeadingSolver {

    /// The wind at the aircraft, in the meteorological convention.
    struct Wind: Equatable {
        /// Direction the wind is blowing **from**, degrees true (0–360).
        var fromDegrees: Double
        /// Wind speed in knots.
        var speedKnots: Double

        static let calm = Wind(fromDegrees: 0, speedKnots: 0)
    }

    // MARK: - Tuning

    /// Largest crab the solver will ever ask for. A correct wind triangle rarely needs
    /// more than ~10° at jet speeds, so anything near this bound means the inputs are
    /// wrong (a stale TAS, a garbage track). Clamping keeps a bad estimate from turning
    /// a vector into a wild one.
    static let maxWindCorrectionDegrees: Double = 30

    /// Below this true airspeed the wind triangle is not solved: the crab angle goes
    /// hyperbolic as TAS approaches the wind speed, and a taxiing or rolling aircraft
    /// has no meaningful air vector at all.
    static let minWindSolveTAS: Double = 60

    /// A solved wind faster than this is treated as noise (a torn read, a state in the
    /// wrong units) rather than as weather, and discarded.
    static let maxPlausibleWindKnots: Double = 250

    /// Below this the solved direction is dominated by read noise — two ~450 kt vectors
    /// differenced a few milliseconds apart — so it is reported as calm. A wind this
    /// light produces well under a degree of crab anyway.
    static let minResolvableWindKnots: Double = 2

    /// Variation and wind are both read as differences between states that Connect
    /// serves in separate round-trips, so a roll smears them. Samples taken past this
    /// bank angle are dropped in favour of the last good one — variation changes over
    /// hundreds of miles and wind over tens, neither over the seconds a turn takes.
    static let maxSampleBankDegrees: Double = 5

    /// Weight given to a fresh wind sample when blending it into the running estimate.
    /// Low enough to absorb per-tick jitter, high enough to follow a real change within
    /// a few seconds of telemetry.
    static let windSmoothingWeight: Double = 0.3

    // MARK: - Magnetic variation

    /// Local magnetic variation in degrees, **east positive**, taken from the sim's own
    /// pair of headings rather than from a declination model: Infinite Flight reports
    /// the same nose direction in both frames, and the difference between them *is* the
    /// local variation. `nil` when the sim exposes only one of the two, in which case
    /// callers leave the heading in the true frame (today's behaviour).
    static func variationDegreesEast(from state: AircraftState) -> Double? {
        guard let trueHeading = state.trueHeading, let magnetic = state.heading else { return nil }
        return signedDifference(trueHeading - magnetic)
    }

    // MARK: - Wind

    /// Solve the wind at the aircraft from its own state, by the wind triangle:
    /// `wind = ground vector − air vector`.
    ///
    /// This is deliberately *not* read from a wind state in the manifest. The triangle
    /// needs no unit guessing (it is built from groundspeed and TAS, which the state
    /// reader has already normalised to knots), works on every IF version regardless of
    /// what the manifest happens to expose, and measures the wind the aircraft is
    /// actually in at its altitude — where a METAR only ever describes the surface at a
    /// field. Returns `nil` when the aircraft isn't in a regime where the triangle
    /// means anything, or when the result fails a plausibility check.
    static func wind(from state: AircraftState) -> Wind? {
        guard state.onGround != true,
              let track = state.track,
              let groundSpeed = state.groundSpeed,
              let trueHeading = state.trueHeading,
              let trueAirspeed = state.trueAirspeed,
              trueAirspeed >= minWindSolveTAS,
              groundSpeed.isFinite, trueAirspeed.isFinite else { return nil }

        // East/north components, knots. The aircraft's air vector points along its true
        // heading at TAS; its ground vector points along its track at groundspeed.
        let east = groundSpeed * sin(toRadians(track)) - trueAirspeed * sin(toRadians(trueHeading))
        let north = groundSpeed * cos(toRadians(track)) - trueAirspeed * cos(toRadians(trueHeading))
        let speed = (east * east + north * north).squareRoot()
        guard speed.isFinite, speed <= maxPlausibleWindKnots else { return nil }
        guard speed >= minResolvableWindKnots else { return .calm }

        // atan2 over (east, north) gives the direction the wind blows *toward*; the
        // meteorological convention names the direction it blows *from*.
        let toward = toDegrees(atan2(east, north))
        return Wind(fromDegrees: normalizedDegrees(toward + 180), speedKnots: speed)
    }

    /// Blend a fresh wind sample into the running estimate. Blending happens on the
    /// vector components, not on the reported direction and speed, so it stays sane when
    /// the direction wraps through north and so a light, noisy wind can't swing the
    /// estimate the way averaging two bearings would.
    static func blended(_ previous: Wind?, sample: Wind,
                        weight: Double = HeadingSolver.windSmoothingWeight) -> Wind {
        guard let previous else { return sample }
        let w = max(0, min(1, weight))
        // Components of the vector each wind blows *toward*; direction is recovered the
        // same way `wind(from:)` recovers it.
        func components(_ wind: Wind) -> (east: Double, north: Double) {
            let toward = toRadians(wind.fromDegrees + 180)
            return (wind.speedKnots * sin(toward), wind.speedKnots * cos(toward))
        }
        let p = components(previous), s = components(sample)
        let east = p.east + (s.east - p.east) * w
        let north = p.north + (s.north - p.north) * w
        let speed = (east * east + north * north).squareRoot()
        guard speed >= minResolvableWindKnots else { return .calm }
        return Wind(fromDegrees: normalizedDegrees(toDegrees(atan2(east, north)) + 180),
                    speedKnots: speed)
    }

    /// The crab angle, in degrees, that makes an aircraft flying at `trueAirspeed` in
    /// `wind` track along `trueCourse`. Positive turns the nose right of course.
    /// Zero whenever the wind or the airspeed needed to solve it is unavailable.
    static func windCorrectionDegrees(trueCourse: Double,
                                      wind: Wind?,
                                      trueAirspeed: Double?) -> Double {
        guard let wind, wind.speedKnots > 0,
              let trueAirspeed, trueAirspeed >= minWindSolveTAS else { return 0 }
        // Crosswind component across the desired course, then the standard wind
        // triangle: crab by asin(crosswind / TAS). A crosswind that outruns the aircraft
        // can't be held at all — clamp the ratio rather than hand `asin` a NaN.
        let crosswind = wind.speedKnots * sin(toRadians(wind.fromDegrees - trueCourse))
        let ratio = max(-1, min(1, crosswind / trueAirspeed))
        let correction = toDegrees(asin(ratio))
        return max(-maxWindCorrectionDegrees, min(maxWindCorrectionDegrees, correction))
    }

    // MARK: - Combined

    /// The heading to assign so the aircraft **tracks** `trueCourse`: crabbed into the
    /// wind, then converted out of the true frame the geometry is computed in and into
    /// the magnetic frame the sim's heading bug reads.
    ///
    /// Each correction is independently optional. With neither available this returns
    /// the rounded true course — exactly what the app assigned before either existed.
    static func assignedHeading(forTrueCourse trueCourse: Double,
                                wind: Wind?,
                                trueAirspeed: Double?,
                                variationDegreesEast: Double?) -> Int {
        let crab = windCorrectionDegrees(trueCourse: trueCourse,
                                         wind: wind, trueAirspeed: trueAirspeed)
        let trueHeading = trueCourse + crab
        return ApproachIntercept.normalizedHeading(trueHeading - (variationDegreesEast ?? 0))
    }

    // MARK: - Angle helpers

    private static func toRadians(_ value: Double) -> Double { value * .pi / 180 }
    private static func toDegrees(_ value: Double) -> Double { value * 180 / .pi }

    /// Normalize to 0–360.
    private static func normalizedDegrees(_ value: Double) -> Double {
        let wrapped = value.truncatingRemainder(dividingBy: 360)
        return wrapped < 0 ? wrapped + 360 : wrapped
    }

    /// Normalize to −180…180, so a variation straddling north stays small and signed.
    private static func signedDifference(_ value: Double) -> Double {
        var diff = normalizedDegrees(value)
        if diff > 180 { diff -= 360 }
        return diff
    }
}
