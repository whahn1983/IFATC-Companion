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
    ///
    /// This is a *sample*, not an estimate — see `VariationEstimate`, which is what callers
    /// should hold. The subtraction is only as good as the two readings behind it, and both
    /// are read in separate round-trips over one socket: a reply that lands in the wrong read
    /// makes a perfectly clean-looking `float` out of some other state, and the difference
    /// between a real heading and someone else's number is tens of degrees of "variation".
    static func variationDegreesEast(from state: AircraftState) -> Double? {
        guard let trueHeading = state.trueHeading, let magnetic = state.heading else { return nil }
        return signedDifference(trueHeading - magnetic)
    }

    /// The largest magnetic variation treated as a real reading. Declination stays inside
    /// ~30° across the whole flyable world outside the high Arctic, so a sample past this is
    /// not a place — it is a bad pair of headings. Generous on purpose: the corroboration in
    /// `VariationEstimate` is the load-bearing guard, and rejecting a real high-latitude
    /// variation would only degrade to "no correction", which is worse than a large-but-true
    /// number.
    static let maxPlausibleVariationDegrees: Double = 45

    /// How far a fresh variation sample may sit from the one in use and still be taken for the
    /// same place. Declination moves about a degree per hundred miles; a telemetry tick is
    /// seconds. Anything past this is a different reading, not a different location.
    static let variationAgreementDegrees: Double = 3

    /// A running magnetic-variation estimate that a single bad reading cannot move.
    ///
    /// The variation goes straight into every heading derived from great-circle geometry —
    /// the initial departure vector most visibly, where being wrong by twenty degrees is the
    /// difference between a turn and "fly runway heading". It used to be latched from whatever
    /// the last usable snapshot said, so one torn read poisoned every heading until the next
    /// tick overwrote it, and a *repeatably* torn read poisoned them all.
    ///
    /// Two guards, matching how the units decision is settled in `IFStateMappingStore`:
    ///
    ///  * a sample past `maxPlausibleVariationDegrees` is not a variation at all, and
    ///  * a sample that disagrees with the value in use by more than
    ///    `variationAgreementDegrees` has to be **corroborated by the next sample** before it
    ///    displaces it. A real variation drifts; it never jumps. So does the first value:
    ///    nothing is used until two consecutive readings agree, which a healthy link produces
    ///    within a second or so, and until then callers assign the plain true bearing — the
    ///    documented degrade path, unchanged.
    struct VariationEstimate: Equatable {
        /// The variation to correct headings by, or nil until two readings have agreed.
        private(set) var degreesEast: Double?
        /// The last sample that disagreed with `degreesEast`, awaiting corroboration.
        private var candidate: Double?

        init(degreesEast: Double? = nil) { self.degreesEast = degreesEast }

        /// Fold in a fresh sample. Ignores implausible ones outright.
        mutating func note(_ sample: Double) {
            guard sample.isFinite, abs(sample) <= maxPlausibleVariationDegrees else { return }
            if let held = degreesEast, abs(signedDifference(sample - held)) <= variationAgreementDegrees {
                degreesEast = sample                 // same place, drifting — take it.
                candidate = nil
                return
            }
            // Disagrees with what is in use (or nothing is in use yet): it only counts once a
            // second reading says the same thing.
            if let candidate, abs(signedDifference(sample - candidate)) <= variationAgreementDegrees {
                degreesEast = sample
                self.candidate = nil
            } else {
                candidate = sample
            }
        }
    }

    // MARK: - Wind

    /// The wind the sim itself reports (`environment/wind_direction_true` and
    /// `environment/wind_velocity`), normalised by the state reader to degrees true and knots.
    ///
    /// **It is the direction the wind blows *from*** — the same convention as `Wind`, so it is
    /// used as read. The state name alone doesn't settle that (a "wind direction" can name
    /// either end of the vector, and the two are exactly 180° apart), so it was pinned against
    /// Infinite Flight's own PFD wind readout: with the state at 5.5069 rad — 315.5° true — the
    /// panel showed **301°**, the same direction stepped into the magnetic frame by the local
    /// variation (~14.5°E). A "blows toward" reading would have shown ~135°.
    ///
    /// Preferred over the wind triangle below wherever the states exist. It is exact rather
    /// than inferred, it needs no differencing of two ~450 kt vectors read in separate
    /// round-trips, and — because of that — it stays right *through a turn*, which is precisely
    /// when the next leg's crab is computed and when the triangle is least trustworthy.
    static func reportedWind(from state: AircraftState) -> Wind? {
        guard let from = state.reportedWindDirectionTrue,
              let knots = state.reportedWindSpeedKnots,
              from.isFinite, knots.isFinite, knots >= 0,
              knots <= maxPlausibleWindKnots else { return nil }
        // Below the resolvable floor the crab is a rounding error either way; report calm so
        // the two sources agree on what "no wind" means.
        guard knots >= minResolvableWindKnots else { return .calm }
        return Wind(fromDegrees: normalizedDegrees(from), speedKnots: knots)
    }

    /// How far apart (degrees, 0–180) two winds' directions are. Used to cross-check the
    /// reported wind against the solved one: they should agree closely, and a disagreement
    /// past a right angle *may* mean one of them is not in the convention it is assumed to
    /// be — at which point the inferred wind, whose convention is fixed by the arithmetic
    /// that produced it, is the safer of the two to steer by. Only read together with
    /// `speedsCorroborate`, which separates that case from a triangle that has simply
    /// solved a wind out of noise.
    static func directionDisagreementDegrees(_ a: Wind, _ b: Wind) -> Double {
        abs(signedDifference(a.fromDegrees - b.fromDegrees))
    }

    /// Whether two winds' **speeds** agree closely enough that a difference in their
    /// directions can still be read as a difference of *convention*.
    ///
    /// This is what makes the direction cross-check above safe to act on. Naming the other
    /// end of the vector reverses a wind without changing its strength, so a genuine
    /// convention mismatch shows up as two winds of the *same speed* pointing opposite ways.
    /// Two winds that disagree about the speed as well aren't the same wind described two
    /// ways — one of them is simply wrong, and it is the inferred one: the triangle
    /// differences two ~450 kt vectors read in separate round-trips, so a smeared sample
    /// invents a wind of its own (12 kt reported against 84 kt solved, 118° apart), while the
    /// sim's own reading has nothing to smear. Without this check that garbage outvoted the
    /// exact number purely by disagreeing loudly enough.
    static func speedsCorroborate(_ a: Wind, _ b: Wind) -> Bool {
        let slower = min(a.speedKnots, b.speedKnots)
        let faster = max(a.speedKnots, b.speedKnots)
        // An absolute floor first, so two light winds a few knots apart still corroborate —
        // a ratio alone calls 3 kt against 7 kt a wild disagreement.
        if faster - slower <= speedCorroborationToleranceKnots { return true }
        return faster <= slower * speedCorroborationRatio
    }

    /// How far apart two winds' speeds may sit and still be taken for the same wind: within
    /// this many knots, or within this ratio, whichever is the more forgiving.
    static let speedCorroborationToleranceKnots: Double = 5
    static let speedCorroborationRatio: Double = 1.5

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
