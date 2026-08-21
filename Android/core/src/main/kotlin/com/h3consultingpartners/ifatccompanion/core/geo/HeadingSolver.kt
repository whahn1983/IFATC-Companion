package com.h3consultingpartners.ifatccompanion.core.geo

import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns a desired **ground course** into the **heading a pilot dials in**.
 *
 * Almost every heading the app assigns is already in the pilot's frame: departure,
 * pattern and approach vectors are all derived from a runway ident, which is magnetic
 * by definition. The weather-deviation vectors are the exception. The mint line is
 * built with great-circle geometry ([Geo.bearing]), so its legs are **true** courses,
 * and it asks the aircraft to *follow a drawn path* rather than merely point somewhere
 * — so the number handed to the pilot has to be the heading that makes the aircraft's
 * **track** lie along the leg, not the leg's bearing itself.
 *
 * Two corrections separate the two numbers, applied in this order:
 *
 *  1. **Wind correction angle** — the crab into wind that makes a heading produce the
 *     desired track. Without it the aircraft is flown on the leg's bearing and the wind
 *     walks it off the line for the whole length of the leg; the drift is what the
 *     off-path re-plan then has to keep cleaning up.
 *  2. **Magnetic variation** — the aircraft's heading bug reads magnetic, so
 *     `magnetic = true − variationEast`.
 *
 * Both are solved from the sim's own telemetry rather than from a declination model or
 * a weather feed, and every entry point degrades to "no correction" when the states it
 * needs aren't exposed — Infinite Flight's manifest coverage varies by version, and the
 * uncorrected true bearing is exactly today's behaviour.
 *
 * Ported from `IFATCCompanion/Utils/HeadingSolver.swift`. It lives in `core.geo`
 * rather than in a utils package because it is geodesy.
 */
object HeadingSolver {

    /** The wind at the aircraft, in the meteorological convention. */
    data class Wind(
        /** Direction the wind is blowing **from**, degrees true (0–360). */
        val fromDegrees: Double,
        /** Wind speed in knots. */
        val speedKnots: Double,
    ) {
        companion object {
            val calm = Wind(fromDegrees = 0.0, speedKnots = 0.0)
        }
    }

    // region Tuning

    /**
     * Largest crab the solver will ever ask for, in degrees. A correct wind triangle
     * rarely needs more than ~10° at jet speeds, so anything near this bound means the
     * inputs are wrong (a stale TAS, a garbage track). Clamping keeps a bad estimate
     * from turning a vector into a wild one.
     */
    const val MAX_WIND_CORRECTION_DEGREES: Double = 30.0

    /**
     * Below this true airspeed (knots) the wind triangle is not solved: the crab angle
     * goes hyperbolic as TAS approaches the wind speed, and a taxiing or rolling
     * aircraft has no meaningful air vector at all.
     */
    const val MIN_WIND_SOLVE_TAS: Double = 60.0

    /**
     * A solved wind faster than this (knots) is treated as noise (a torn read, a state
     * in the wrong units) rather than as weather, and discarded.
     */
    const val MAX_PLAUSIBLE_WIND_KNOTS: Double = 250.0

    /**
     * Below this (knots) the solved direction is dominated by read noise — two ~450 kt
     * vectors differenced a few milliseconds apart — so it is reported as calm. A wind
     * this light produces well under a degree of crab anyway.
     */
    const val MIN_RESOLVABLE_WIND_KNOTS: Double = 2.0

    /**
     * Variation and wind are both read as differences between states that Connect
     * serves in separate round-trips, so a roll smears them. Samples taken past this
     * bank angle (degrees) are dropped in favour of the last good one — variation
     * changes over hundreds of miles and wind over tens, neither over the seconds a
     * turn takes.
     */
    const val MAX_SAMPLE_BANK_DEGREES: Double = 5.0

    /**
     * Weight given to a fresh wind sample when blending it into the running estimate.
     * Low enough to absorb per-tick jitter, high enough to follow a real change within
     * a few seconds of telemetry.
     */
    const val WIND_SMOOTHING_WEIGHT: Double = 0.3

    // endregion

    // region Magnetic variation

    /**
     * Local magnetic variation in degrees, **east positive**, taken from the sim's own
     * pair of headings rather than from a declination model: Infinite Flight reports
     * the same nose direction in both frames, and the difference between them *is* the
     * local variation. `null` when the sim exposes only one of the two, in which case
     * callers leave the heading in the true frame (today's behaviour).
     *
     * This is a *sample*, not an estimate — see [VariationEstimate], which is what callers
     * should hold. The subtraction is only as good as the two readings behind it, and both
     * are read in separate round-trips over one socket: a reply that lands in the wrong read
     * makes a perfectly clean-looking `float` out of some other state, and the difference
     * between a real heading and someone else's number is tens of degrees of "variation".
     */
    fun variationDegreesEast(state: AircraftState): Double? {
        val trueHeading = state.trueHeading ?: return null
        val magnetic = state.heading ?: return null
        return signedDifference(trueHeading - magnetic)
    }

    /**
     * The largest magnetic variation treated as a real reading. Declination stays inside
     * ~30° across the whole flyable world outside the high Arctic, so a sample past this is
     * not a place — it is a bad pair of headings. Generous on purpose: the corroboration in
     * [VariationEstimate] is the load-bearing guard, and rejecting a real high-latitude
     * variation would only degrade to "no correction", which is worse than a large-but-true
     * number.
     */
    const val MAX_PLAUSIBLE_VARIATION_DEGREES: Double = 45.0

    /**
     * How far a fresh variation sample may sit from the one in use and still be taken for the
     * same place. Declination moves about a degree per hundred miles; a telemetry tick is
     * seconds. Anything past this is a different reading, not a different location.
     */
    const val VARIATION_AGREEMENT_DEGREES: Double = 3.0

    /**
     * A running magnetic-variation estimate that a single bad reading cannot move.
     *
     * The variation goes straight into every heading derived from great-circle geometry —
     * the initial departure vector most visibly, where being wrong by twenty degrees is the
     * difference between a turn and "fly runway heading". It used to be latched from whatever
     * the last usable snapshot said, so one torn read poisoned every heading until the next
     * tick overwrote it, and a *repeatably* torn read poisoned them all.
     *
     * Two guards, matching how the units decision is settled in `IFStateMappingStore`:
     *
     *  * a sample past [MAX_PLAUSIBLE_VARIATION_DEGREES] is not a variation at all, and
     *  * a sample that disagrees with the value in use by more than
     *    [VARIATION_AGREEMENT_DEGREES] has to be **corroborated by the next sample** before it
     *    displaces it. A real variation drifts; it never jumps. So does the first value:
     *    nothing is used until two consecutive readings agree, which a healthy link produces
     *    within a second or so, and until then callers assign the plain true bearing — the
     *    documented degrade path, unchanged.
     *
     * The Swift original is a `struct` with a `mutating` method; Kotlin expresses the same
     * thing as a small mutable class the caller holds.
     */
    class VariationEstimate(degreesEast: Double? = null) {

        /** The variation to correct headings by, or null until two readings have agreed. */
        var degreesEast: Double? = degreesEast
            private set

        /** The last sample that disagreed with [degreesEast], awaiting corroboration. */
        private var candidate: Double? = null

        /** Fold in a fresh sample. Ignores implausible ones outright. */
        fun note(sample: Double) {
            if (!sample.isFinite() || abs(sample) > MAX_PLAUSIBLE_VARIATION_DEGREES) return
            val held = degreesEast
            if (held != null && abs(signedDifference(sample - held)) <= VARIATION_AGREEMENT_DEGREES) {
                degreesEast = sample // same place, drifting — take it.
                candidate = null
                return
            }
            // Disagrees with what is in use (or nothing is in use yet): it only counts once a
            // second reading says the same thing.
            val pending = candidate
            if (pending != null && abs(signedDifference(sample - pending)) <= VARIATION_AGREEMENT_DEGREES) {
                degreesEast = sample
                candidate = null
            } else {
                candidate = sample
            }
        }

        override fun equals(other: Any?): Boolean =
            other is VariationEstimate && other.degreesEast == degreesEast && other.candidate == candidate

        override fun hashCode(): Int = 31 * (degreesEast?.hashCode() ?: 0) + (candidate?.hashCode() ?: 0)

        override fun toString(): String = "VariationEstimate(degreesEast=$degreesEast, candidate=$candidate)"
    }

    // endregion

    // region Wind

    /**
     * The wind the sim itself reports (`environment/wind_direction_true` and
     * `environment/wind_velocity`), normalised by the state reader to degrees true and knots.
     *
     * **It is the direction the wind blows *from*** — the same convention as [Wind], so it is
     * used as read. The state name alone doesn't settle that (a "wind direction" can name
     * either end of the vector, and the two are exactly 180° apart), so it was pinned against
     * Infinite Flight's own PFD wind readout: with the state at 5.5069 rad — 315.5° true — the
     * panel showed **301°**, the same direction stepped into the magnetic frame by the local
     * variation (~14.5°E). A "blows toward" reading would have shown ~135°.
     *
     * Preferred over the wind triangle below wherever the states exist. It is exact rather
     * than inferred, it needs no differencing of two ~450 kt vectors read in separate
     * round-trips, and — because of that — it stays right *through a turn*, which is precisely
     * when the next leg's crab is computed and when the triangle is least trustworthy.
     */
    fun reportedWind(state: AircraftState): Wind? {
        val from = state.reportedWindDirectionTrue ?: return null
        val knots = state.reportedWindSpeedKnots ?: return null
        if (!from.isFinite() || !knots.isFinite() || knots < 0 || knots > MAX_PLAUSIBLE_WIND_KNOTS) return null
        // Below the resolvable floor the crab is a rounding error either way; report calm so
        // the two sources agree on what "no wind" means.
        if (knots < MIN_RESOLVABLE_WIND_KNOTS) return Wind.calm
        return Wind(fromDegrees = normalizedDegrees(from), speedKnots = knots)
    }

    /**
     * How far apart (degrees, 0–180) two winds' directions are. Used to cross-check the
     * reported wind against the solved one: they should agree closely, and a disagreement
     * past a right angle *may* mean one of them is not in the convention it is assumed to
     * be — at which point the inferred wind, whose convention is fixed by the arithmetic
     * that produced it, is the safer of the two to steer by. Only read together with
     * [speedsCorroborate], which separates that case from a triangle that has simply
     * solved a wind out of noise.
     */
    fun directionDisagreementDegrees(a: Wind, b: Wind): Double =
        abs(signedDifference(a.fromDegrees - b.fromDegrees))

    /**
     * Whether two winds' **speeds** agree closely enough that a difference in their
     * directions can still be read as a difference of *convention*.
     *
     * This is what makes the direction cross-check above safe to act on. Naming the other
     * end of the vector reverses a wind without changing its strength, so a genuine
     * convention mismatch shows up as two winds of the *same speed* pointing opposite ways.
     * Two winds that disagree about the speed as well aren't the same wind described two
     * ways — one of them is simply wrong, and it is the inferred one: the triangle
     * differences two ~450 kt vectors read in separate round-trips, so a smeared sample
     * invents a wind of its own (12 kt reported against 84 kt solved, 118° apart), while the
     * sim's own reading has nothing to smear. Without this check that garbage outvoted the
     * exact number purely by disagreeing loudly enough.
     */
    fun speedsCorroborate(a: Wind, b: Wind): Boolean {
        val slower = min(a.speedKnots, b.speedKnots)
        val faster = max(a.speedKnots, b.speedKnots)
        // An absolute floor first, so two light winds a few knots apart still corroborate —
        // a ratio alone calls 3 kt against 7 kt a wild disagreement.
        if (faster - slower <= SPEED_CORROBORATION_TOLERANCE_KNOTS) return true
        return faster <= slower * SPEED_CORROBORATION_RATIO
    }

    /**
     * How far apart two winds' speeds may sit and still be taken for the same wind: within
     * this many knots, or within this ratio, whichever is the more forgiving.
     */
    const val SPEED_CORROBORATION_TOLERANCE_KNOTS: Double = 5.0
    const val SPEED_CORROBORATION_RATIO: Double = 1.5

    /**
     * Solve the wind at the aircraft from its own state, by the wind triangle:
     * `wind = ground vector − air vector`.
     *
     * This is deliberately *not* read from a wind state in the manifest. The triangle
     * needs no unit guessing (it is built from groundspeed and TAS, which the state
     * reader has already normalised to knots), works on every IF version regardless of
     * what the manifest happens to expose, and measures the wind the aircraft is
     * actually in at its altitude — where a METAR only ever describes the surface at a
     * field. Returns `null` when the aircraft isn't in a regime where the triangle
     * means anything, or when the result fails a plausibility check.
     */
    fun wind(state: AircraftState): Wind? {
        if (state.onGround == true) return null
        val track = state.track ?: return null
        val groundSpeed = state.groundSpeed ?: return null
        val trueHeading = state.trueHeading ?: return null
        val trueAirspeed = state.trueAirspeed ?: return null
        if (!(trueAirspeed >= MIN_WIND_SOLVE_TAS)) return null
        if (!groundSpeed.isFinite() || !trueAirspeed.isFinite()) return null

        // East/north components, knots. The aircraft's air vector points along its true
        // heading at TAS; its ground vector points along its track at groundspeed.
        val east = groundSpeed * sin(toRadians(track)) - trueAirspeed * sin(toRadians(trueHeading))
        val north = groundSpeed * cos(toRadians(track)) - trueAirspeed * cos(toRadians(trueHeading))
        val speed = sqrt(east * east + north * north)
        if (!speed.isFinite() || speed > MAX_PLAUSIBLE_WIND_KNOTS) return null
        if (speed < MIN_RESOLVABLE_WIND_KNOTS) return Wind.calm

        // atan2 over (east, north) gives the direction the wind blows *toward*; the
        // meteorological convention names the direction it blows *from*.
        val toward = toDegrees(atan2(east, north))
        return Wind(fromDegrees = normalizedDegrees(toward + 180), speedKnots = speed)
    }

    /**
     * Blend a fresh wind sample into the running estimate. Blending happens on the
     * vector components, not on the reported direction and speed, so it stays sane when
     * the direction wraps through north and so a light, noisy wind can't swing the
     * estimate the way averaging two bearings would.
     */
    fun blended(previous: Wind?, sample: Wind, weight: Double = WIND_SMOOTHING_WEIGHT): Wind {
        if (previous == null) return sample
        val w = max(0.0, min(1.0, weight))
        // Components of the vector each wind blows *toward*; direction is recovered the
        // same way `wind(state:)` recovers it.
        fun components(wind: Wind): Pair<Double, Double> {
            val toward = toRadians(wind.fromDegrees + 180)
            return Pair(wind.speedKnots * sin(toward), wind.speedKnots * cos(toward))
        }
        val p = components(previous)
        val s = components(sample)
        val east = p.first + (s.first - p.first) * w
        val north = p.second + (s.second - p.second) * w
        val speed = sqrt(east * east + north * north)
        if (speed < MIN_RESOLVABLE_WIND_KNOTS) return Wind.calm
        return Wind(
            fromDegrees = normalizedDegrees(toDegrees(atan2(east, north)) + 180),
            speedKnots = speed,
        )
    }

    /**
     * The crab angle, in degrees, that makes an aircraft flying at [trueAirspeed] in
     * [wind] track along [trueCourse]. Positive turns the nose right of course.
     * Zero whenever the wind or the airspeed needed to solve it is unavailable.
     */
    fun windCorrectionDegrees(trueCourse: Double, wind: Wind?, trueAirspeed: Double?): Double {
        if (wind == null || !(wind.speedKnots > 0)) return 0.0
        if (trueAirspeed == null || !(trueAirspeed >= MIN_WIND_SOLVE_TAS)) return 0.0
        // Crosswind component across the desired course, then the standard wind
        // triangle: crab by asin(crosswind / TAS). A crosswind that outruns the aircraft
        // can't be held at all — clamp the ratio rather than hand `asin` a NaN.
        val crosswind = wind.speedKnots * sin(toRadians(wind.fromDegrees - trueCourse))
        val ratio = max(-1.0, min(1.0, crosswind / trueAirspeed))
        val correction = toDegrees(asin(ratio))
        return max(-MAX_WIND_CORRECTION_DEGREES, min(MAX_WIND_CORRECTION_DEGREES, correction))
    }

    // endregion

    // region Combined

    /**
     * The heading to assign so the aircraft **tracks** [trueCourse]: crabbed into the
     * wind, then converted out of the true frame the geometry is computed in and into
     * the magnetic frame the sim's heading bug reads.
     *
     * Each correction is independently optional. With neither available this returns
     * the rounded true course — exactly what the app assigned before either existed.
     */
    fun assignedHeading(
        trueCourse: Double,
        wind: Wind?,
        trueAirspeed: Double?,
        variationDegreesEast: Double?,
    ): Int {
        val crab = windCorrectionDegrees(trueCourse = trueCourse, wind = wind, trueAirspeed = trueAirspeed)
        val trueHeading = trueCourse + crab
        return normalizedHeading(trueHeading - (variationDegreesEast ?: 0.0))
    }

    // endregion

    // region Angle helpers

    private fun toRadians(value: Double): Double = value * PI / 180
    private fun toDegrees(value: Double): Double = value * 180 / PI

    /** Normalize to 0–360. */
    private fun normalizedDegrees(value: Double): Double {
        val wrapped = value % 360
        return if (wrapped < 0) wrapped + 360 else wrapped
    }

    /** Normalize to −180…180, so a variation straddling north stays small and signed. */
    private fun signedDifference(value: Double): Double {
        var diff = normalizedDegrees(value)
        if (diff > 180) diff -= 360
        return diff
    }

    /**
     * Normalize a heading to 0…359, matching the app's heading display/spoken convention
     * (`Phonetic.heading` / `headingDisplay`), where north reads "000".
     *
     * CONTRACT: iOS calls `ApproachIntercept.normalizedHeading` here. `ApproachIntercept`
     * belongs to the ATC package, so this is the same one-line rounding kept private to
     * the solver; if the ATC port exposes its own, the two must agree exactly.
     */
    private fun normalizedHeading(degrees: Double): Int =
        ((roundedAwayFromZero(degrees).toInt() % 360) + 360) % 360

    /**
     * Swift's `Double.rounded()` — to nearest, ties away from zero. Kotlin's
     * `kotlin.math.round` breaks ties to even, which would round a heading of x.5 the
     * other way.
     */
    private fun roundedAwayFromZero(value: Double): Double =
        if (value < 0) ceil(value - 0.5) else floor(value + 0.5)

    // endregion
}
