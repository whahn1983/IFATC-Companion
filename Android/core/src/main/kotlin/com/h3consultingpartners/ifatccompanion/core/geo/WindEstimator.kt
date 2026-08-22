package com.h3consultingpartners.ifatccompanion.core.geo

import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink
import java.util.Locale
import kotlin.math.abs

/**
 * The wind in force at the aircraft and the local declination, kept across telemetry ticks.
 *
 * [HeadingSolver] supplies every piece of the arithmetic — the triangle, the sim's own
 * reading, the smoothing, the corroborated variation, and both cross-checks — but each is a
 * pure function of one sample. Something has to hold the running estimates between samples
 * and decide, on each one, which of the two winds to steer by. That is this.
 *
 * **Why two winds at all.** The sim's own `wind_direction_true` / `wind_velocity` is exact
 * and stays right *through a turn*, which is precisely when the next leg's crab is computed
 * and when the triangle is least trustworthy. So it is preferred wherever the states exist.
 * The triangle is the fallback — it works on every Infinite Flight version regardless of
 * what the manifest exposes — and it is also the *check* on the reported wind, because its
 * convention is fixed by the arithmetic that produced it.
 *
 * **When the check overrules the reported wind.** Only when both winds are strong enough
 * for a direction to mean anything, they disagree past a right angle, *and* they corroborate
 * on speed. Naming the other end of a vector reverses a wind without changing its strength,
 * so that combination is the signature of a build reporting the direction the wind blows
 * *toward*. If the speeds also disagree they are not one wind described two ways — one is
 * simply wrong, and it is the inferred one, which differences two ~450 kt vectors read in
 * separate round-trips. (Seen in the field: 12 kt reported, 84 kt solved, 118° apart, and
 * every weather vector crabbed for the gale.)
 *
 * **The triangle's estimate is never blended with a reported sample.** It stays an
 * independent second opinion: it is what the cross-check is checked against and what Weather
 * Diagnostics prints beside the sim's wind. Folding the reported wind into it would make
 * that comparison compare a number with itself.
 *
 * Ported from `updateHeadingCorrections` / `trustReportedWind` on iOS's `AppModel`, which
 * had no Android counterpart: every piece of [HeadingSolver] was here — including
 * [HeadingSolver.VariationEstimate], which had no call site at all — and nothing ran them
 * per tick, so the weather-deviation vectors were assigned as raw true bearings,
 * uncorrected for both drift and declination.
 */
class WindEstimator(private val diagnostics: DiagnosticsSink = DiagnosticsSink.noop) {

    private var variation = HeadingSolver.VariationEstimate()

    /** The smoothed triangle solution, or null before one has been solved. */
    var solved: HeadingSolver.Wind? = null
        private set

    /** The sim's own reading from the last tick, when it exposes one. */
    var reported: HeadingSolver.Wind? = null
        private set

    /**
     * The wind actually steered by, or null before either source has produced one.
     *
     * Held rather than derived: a tick where the triangle stands down (a turn, too slow, no
     * TAS) and the sim reports nothing has no new opinion, and the last one is better than
     * none.
     */
    var wind: HeadingSolver.Wind? = null
        private set

    /** Which of the two [wind] came from. */
    var isSimReported: Boolean = false
        private set

    /** Local magnetic variation, degrees east, or null until two readings have agreed. */
    val variationDegreesEast: Double? get() = variation.degreesEast

    /** The TAS the crab is solved against, carried so callers need not re-read it. */
    var trueAirspeed: Double? = null
        private set

    /**
     * The true course of the last leg handed out through [assignedHeading], and the
     * magnetic heading actually spoken for it.
     *
     * Kept so the pair can be read back: the whole correction is the difference between
     * these two numbers, and a vector that comes out pointing the wrong way is otherwise
     * indistinguishable from a leg that really did point there.
     */
    var lastAssignedTrueCourse: Double? = null
        private set

    var lastAssignedHeading: Int? = null
        private set

    /**
     * Fold one telemetry sample in.
     *
     * Both the variation and the triangle are sampled **only near wings-level**. Each
     * differences two readings taken in separate round-trips, so a roll smears them, and a
     * stale sample beats a smeared one: variation changes over hundreds of miles and wind
     * over tens, not over the seconds a turn takes. The reported wind needs neither guard —
     * it is read, not inferred.
     */
    fun update(state: AircraftState) {
        trueAirspeed = state.trueAirspeed

        val nearLevel = abs(state.bankAngle ?: 0.0) <= HeadingSolver.MAX_SAMPLE_BANK_DEGREES
        if (nearLevel) {
            HeadingSolver.variationDegreesEast(state)?.let(variation::note)
        }

        // This tick's fresh triangle solution, kept separate from the running estimate: the
        // cross-check below is made against the fresh sample, because with none this tick
        // the triangle has stood down and has no opinion to overrule the sim with.
        val sample = if (nearLevel) HeadingSolver.wind(state) else null
        if (sample != null) solved = HeadingSolver.blended(previous = solved, sample = sample)

        val exact = HeadingSolver.reportedWind(state)
        reported = exact
        if (exact != null && trustReportedWind(exact, sample)) {
            wind = exact
            isSimReported = true
            return
        }
        if (sample != null) {
            solved?.let {
                wind = it
                isSimReported = false
            }
        }
    }

    /**
     * The heading to assign so the aircraft **tracks** [trueCourse] — crabbed into the wind
     * and converted into the magnetic frame the heading bug reads.
     *
     * Degrades exactly as [HeadingSolver.assignedHeading] does: with neither correction
     * available it returns the rounded true course, which is what the app assigned before
     * any of this existed.
     */
    fun assignedHeading(trueCourse: Double): Int {
        val assigned = HeadingSolver.assignedHeading(
            trueCourse = trueCourse,
            wind = wind,
            trueAirspeed = trueAirspeed,
            variationDegreesEast = variationDegreesEast,
        )
        lastAssignedTrueCourse = trueCourse
        lastAssignedHeading = assigned
        return assigned
    }

    /** Forget everything. A new flight is a new aircraft in a new place. */
    fun reset() {
        variation = HeadingSolver.VariationEstimate()
        solved = null
        reported = null
        wind = null
        isSimReported = false
        trueAirspeed = null
        lastAssignedTrueCourse = null
        lastAssignedHeading = null
    }

    /**
     * Whether the sim's reported wind may be steered by.
     *
     * It is used as the direction the wind blows **from**, pinned against Infinite Flight's
     * own PFD readout — but that is one observation of one build, and getting it backwards
     * would put every weather vector on the wrong side of course. So when the triangle has
     * independently solved a wind worth comparing against, a disagreement past a right angle
     * *at the same speed* is read as "this isn't the convention we think it is", and the
     * inferred wind is used instead.
     *
     * The comparison is only made on winds strong enough for a direction to mean anything.
     * In light air the two can differ wildly while both are effectively calm, and disagreeing
     * about the direction of a 3 kt wind decides nothing.
     */
    private fun trustReportedWind(exact: HeadingSolver.Wind, sample: HeadingSolver.Wind?): Boolean {
        if (sample == null) return true
        if (exact.speedKnots < CROSS_CHECK_MIN_KNOTS || sample.speedKnots < CROSS_CHECK_MIN_KNOTS) return true
        val disagreement = HeadingSolver.directionDisagreementDegrees(exact, sample)
        if (disagreement <= CROSS_CHECK_MAX_DISAGREEMENT_DEGREES) return true
        // A convention mismatch reverses a wind without changing its strength, so it is only
        // that if the two agree about the speed. When they don't, the disagreement is the
        // triangle's own and the sim's exact reading must not be thrown out on its word.
        if (!HeadingSolver.speedsCorroborate(exact, sample)) {
            diagnostics.log(
                DiagnosticCategory.WEATHER,
                message = String.format(
                    Locale.US,
                    "Wind: the solved wind (%03.0f° / %.0f kt) disagrees with the sim's own " +
                        "(%03.0f° / %.0f kt) about the speed as well as the direction — the " +
                        "triangle is unreliable here, keeping the sim-reported wind.",
                    sample.fromDegrees, sample.speedKnots, exact.fromDegrees, exact.speedKnots,
                ),
            )
            return true
        }
        diagnostics.log(
            DiagnosticCategory.WEATHER,
            message = String.format(
                Locale.US,
                "Wind: the sim-reported direction (%03.0f°) disagrees with the solved wind " +
                    "(%03.0f°) by %.0f° at the same speed — using the solved wind.",
                exact.fromDegrees, sample.fromDegrees, disagreement,
            ),
        )
        return false
    }

    companion object {
        /**
         * Below this, a wind's *direction* carries no information worth cross-checking:
         * two effectively-calm winds can point anywhere relative to each other.
         */
        const val CROSS_CHECK_MIN_KNOTS: Double = 10.0

        /**
         * How far apart two winds' directions must sit before a convention mismatch is even
         * considered. A right angle: no real disagreement between an exact reading and a
         * solved one gets near it, and a reversed convention is 180° away.
         */
        const val CROSS_CHECK_MAX_DISAGREEMENT_DEGREES: Double = 90.0
    }
}
