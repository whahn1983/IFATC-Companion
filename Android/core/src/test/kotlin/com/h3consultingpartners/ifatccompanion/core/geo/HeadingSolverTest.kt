package com.h3consultingpartners.ifatccompanion.core.geo

import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The mint line is drawn with great-circle geometry, so its legs are **true** courses,
 * but the aircraft is flown on a magnetic heading bug and pushed sideways by the wind.
 * [HeadingSolver] is what closes both gaps; these tests pin the conversions it makes and
 * — just as importantly — the cases where it declines to make one.
 *
 * Ported from `IFATCCompanionTests/HeadingSolverTests.swift`.
 */
class HeadingSolverTest {

    // region Builders

    /**
     * A cruising state carrying an exact wind, built by vector addition rather than by
     * hand-computed track/groundspeed constants: the ground vector *is* the air vector
     * plus the wind, so a solver that recovers the wind from it has inverted the same
     * triangle the aircraft is actually flying.
     */
    private fun cruising(
        trueHeading: Double,
        trueAirspeed: Double,
        windFrom: Double,
        windSpeed: Double,
        variationEast: Double = 0.0,
    ): AircraftState {
        fun rad(d: Double): Double = d * PI / 180
        // The wind blows *toward* the reciprocal of the direction it is named for.
        val toward = windFrom + 180
        val east = trueAirspeed * sin(rad(trueHeading)) + windSpeed * sin(rad(toward))
        val north = trueAirspeed * cos(rad(trueHeading)) + windSpeed * cos(rad(toward))

        var track = atan2(east, north) * 180 / PI
        if (track < 0) track += 360
        return AircraftState(
            onGround = false,
            trueHeading = trueHeading,
            heading = (trueHeading - variationEast) % 360,
            trueAirspeed = trueAirspeed,
            groundSpeed = sqrt(east * east + north * north),
            track = track,
        )
    }

    // endregion

    // region Magnetic variation

    /**
     * Variation is read straight off the sim's own pair of headings: it reports the same
     * nose direction twice, and the difference between them is the local declination.
     */
    @Test
    fun variationIsTheDifferenceBetweenTheSimsTwoHeadings() {
        val east = AircraftState(trueHeading = 100.0, heading = 90.0)
        assertEquals(
            10.0, HeadingSolver.variationDegreesEast(east) ?: Double.NaN, 0.001,
            "true 100 / magnetic 090 is 10° east variation",
        )

        val west = AircraftState(trueHeading = 90.0, heading = 100.0)
        assertEquals(
            -10.0, HeadingSolver.variationDegreesEast(west) ?: Double.NaN, 0.001,
            "true 090 / magnetic 100 is 10° west variation",
        )
    }

    /** A pair straddling north must stay a small signed angle, not 350°. */
    @Test
    fun variationStaysSignedAcrossNorth() {
        var s = AircraftState(trueHeading = 5.0, heading = 355.0)
        assertEquals(10.0, HeadingSolver.variationDegreesEast(s) ?: Double.NaN, 0.001)

        s = s.copy(trueHeading = 355.0, heading = 5.0)
        assertEquals(-10.0, HeadingSolver.variationDegreesEast(s) ?: Double.NaN, 0.001)
    }

    /**
     * Connect coverage varies by version. With only one of the two headings exposed there
     * is nothing to measure, and the caller must be told so rather than handed a zero.
     */
    @Test
    fun variationUnavailableWhenTheSimExposesOnlyOneHeading() {
        val magneticOnly = AircraftState(heading = 90.0)
        assertNull(HeadingSolver.variationDegreesEast(magneticOnly))

        val trueOnly = AircraftState(trueHeading = 90.0)
        assertNull(HeadingSolver.variationDegreesEast(trueOnly))
    }

    // endregion

    // region Variation estimate (corroboration)

    /**
     * Nothing is used until two consecutive readings agree — a single sample is not an
     * estimate, and the caller's degrade path (assign the plain true bearing) is fine for
     * the second it takes a healthy link to produce the second one.
     */
    @Test
    fun variationNeedsASecondAgreeingReadingBeforeItIsUsed() {
        val estimate = HeadingSolver.VariationEstimate()
        assertNull(estimate.degreesEast)
        estimate.note(-7.4)
        assertNull(estimate.degreesEast, "one reading is not corroboration")
        estimate.note(-7.5)
        assertEquals(-7.5, estimate.degreesEast ?: Double.NaN, 0.001)
    }

    /**
     * Regression for the reported KMCO takeoff clearance. A variation is a property of where
     * the aircraft *is*: it drifts about a degree per hundred miles and never jumps. A single
     * torn pair of headings — the failure mode #219 was about, where a reply lands in the
     * wrong read — used to be latched as-is and went straight into the departure vector, and
     * ~20° of bogus variation is exactly what turns a 152° departure into 172° and collapses
     * the clearance to "fly runway heading" against runway 17R.
     */
    @Test
    fun oneTornReadingCannotMoveTheVariationInUse() {
        val estimate = HeadingSolver.VariationEstimate()
        estimate.note(-7.4)
        estimate.note(-7.5)
        assertEquals(-7.5, estimate.degreesEast ?: Double.NaN, 0.001)

        estimate.note(-29.0) // a torn read
        assertEquals(
            -7.5, estimate.degreesEast ?: Double.NaN, 0.001,
            "the held variation stands until a second reading corroborates the jump",
        )
        estimate.note(-7.6) // link recovers
        assertEquals(-7.6, estimate.degreesEast ?: Double.NaN, 0.001)
    }

    /**
     * A jump that keeps repeating is not noise — it is the reading, and it has to win, or a
     * wrong early value would be held forever.
     */
    @Test
    fun aCorroboratedJumpIsAdopted() {
        val estimate = HeadingSolver.VariationEstimate()
        estimate.note(-7.5)
        estimate.note(-7.5)
        estimate.note(-20.0)
        estimate.note(-20.1)
        assertEquals(-20.1, estimate.degreesEast ?: Double.NaN, 0.001)
    }

    /**
     * Declination stays inside ~30° across the flyable world; a sample past the bound is a
     * bad pair of headings, not a place, and must not even become a candidate.
     */
    @Test
    fun implausibleVariationIsRejectedOutright() {
        val estimate = HeadingSolver.VariationEstimate()
        estimate.note(57.0) // "229 minus 172" — the #217 reading
        estimate.note(57.0)
        assertNull(estimate.degreesEast, "a corroborated impossibility is still an impossibility")

        estimate.note(Double.NaN)
        assertNull(estimate.degreesEast)
    }

    /**
     * Ordinary drift down a long leg is accepted every tick — the guard must not freeze the
     * estimate at wherever the aircraft first switched on.
     */
    @Test
    fun variationFollowsSlowDrift() {
        val estimate = HeadingSolver.VariationEstimate()
        estimate.note(-7.5)
        estimate.note(-7.5)
        var step = -7.5
        while (step >= -12.0) {
            estimate.note(step)
            step -= 0.5
        }
        assertEquals(-12.0, estimate.degreesEast ?: Double.NaN, 0.001)
    }

    // endregion

    // region Wind

    /**
     * The wind triangle, inverted: given what the aircraft is doing through the air and
     * over the ground, recover the wind that separates them.
     */
    @Test
    fun windIsSolvedFromTheAircraftsOwnTriangle() {
        val state = cruising(trueHeading = 90.0, trueAirspeed = 400.0, windFrom = 180.0, windSpeed = 40.0)
        val wind = HeadingSolver.wind(state)
            ?: fail("a cruising aircraft with track, heading, GS and TAS solves a wind")
        assertEquals(180.0, wind.fromDegrees, 0.5)
        assertEquals(40.0, wind.speedKnots, 0.5)
    }

    /**
     * A wind named across north must come back as ~350°, not as its reciprocal or as a
     * negative angle.
     */
    @Test
    fun windDirectionAcrossNorthIsRecovered() {
        val state = cruising(trueHeading = 270.0, trueAirspeed = 450.0, windFrom = 350.0, windSpeed = 60.0)
        val wind = HeadingSolver.wind(state) ?: fail("expected a wind")
        assertEquals(350.0, wind.fromDegrees, 0.5)
        assertEquals(60.0, wind.speedKnots, 0.5)
    }

    /** Track equal to heading with groundspeed equal to TAS is the definition of no wind. */
    @Test
    fun noWindWhenTrackAndHeadingAgree() {
        val s = AircraftState(
            onGround = false,
            trueHeading = 120.0,
            track = 120.0,
            trueAirspeed = 460.0,
            groundSpeed = 460.0,
        )
        val wind = HeadingSolver.wind(s) ?: fail("a still-air cruise solves a wind — a calm one")
        assertEquals(0.0, wind.speedKnots, 0.001)
    }

    /**
     * On the ground, and at taxi/rollout speeds, the triangle means nothing — the crab
     * angle goes hyperbolic as TAS approaches the wind speed.
     */
    @Test
    fun windNotSolvedOnTheGroundOrBelowTheAirspeedFloor() {
        val onGround = cruising(trueHeading = 90.0, trueAirspeed = 400.0, windFrom = 180.0, windSpeed = 40.0)
            .copy(onGround = true)
        assertNull(HeadingSolver.wind(onGround))

        val slow = cruising(trueHeading = 90.0, trueAirspeed = 400.0, windFrom = 180.0, windSpeed = 40.0)
            .copy(trueAirspeed = HeadingSolver.MIN_WIND_SOLVE_TAS - 1)
        assertNull(HeadingSolver.wind(slow))
    }

    /**
     * A torn read (a track from one instant against a heading from another) can imply a
     * wind faster than any real one. That is noise, and must not reach a vector.
     */
    @Test
    fun implausiblyFastWindIsDiscardedRatherThanFlown() {
        val s = AircraftState(
            onGround = false,
            trueHeading = 0.0,
            track = 180.0, // reciprocal of the nose: physically impossible
            trueAirspeed = 460.0,
            groundSpeed = 460.0,
        )
        assertNull(
            HeadingSolver.wind(s),
            "a wind implied at ~920 kt is a bad read, not weather",
        )
    }

    /**
     * Blending happens on the wind's components, so an estimate straddling north settles
     * near north rather than swinging through south the way averaging bearings would.
     */
    @Test
    fun blendingAcrossNorthDoesNotSwingThroughSouth() {
        val previous = HeadingSolver.Wind(fromDegrees = 350.0, speedKnots = 40.0)
        val sample = HeadingSolver.Wind(fromDegrees = 10.0, speedKnots = 40.0)
        val blended = HeadingSolver.blended(previous, sample = sample, weight = 0.5)
        assertEquals(0.0, blended.fromDegrees, 0.5)
        assertTrue(blended.speedKnots > 35)
    }

    /**
     * A run of identical samples has to converge on them — the smoothing is there to
     * absorb per-tick jitter, not to permanently discount the wind.
     */
    @Test
    fun repeatedSamplesConvergeOnTheWind() {
        val sample = HeadingSolver.Wind(fromDegrees = 270.0, speedKnots = 50.0)
        var estimate: HeadingSolver.Wind? = null
        repeat(25) { estimate = HeadingSolver.blended(estimate, sample = sample) }
        assertEquals(270.0, estimate?.fromDegrees ?: Double.NaN, 0.5)
        assertEquals(50.0, estimate?.speedKnots ?: Double.NaN, 0.5)
    }

    // endregion

    // region Wind correction angle

    /** A wind off the right wing is corrected by crabbing right, into it. */
    @Test
    fun crabIsIntoTheWind() {
        val fromTheRight = HeadingSolver.Wind(fromDegrees = 180.0, speedKnots = 40.0)
        val right = HeadingSolver.windCorrectionDegrees(
            trueCourse = 90.0, wind = fromTheRight, trueAirspeed = 400.0,
        )
        assertEquals(asin(40.0 / 400.0) * 180 / PI, right, 0.01)
        assertTrue(right > 0, "a wind from the right is met with a turn to the right")

        val fromTheLeft = HeadingSolver.Wind(fromDegrees = 0.0, speedKnots = 40.0)
        val left = HeadingSolver.windCorrectionDegrees(
            trueCourse = 90.0, wind = fromTheLeft, trueAirspeed = 400.0,
        )
        assertEquals(-right, left, 0.01)
    }

    /**
     * A pure headwind or tailwind slows or hurries the aircraft but never pushes it off
     * the line, so it earns no correction at all.
     */
    @Test
    fun headwindAndTailwindNeedNoCorrection() {
        val headwind = HeadingSolver.Wind(fromDegrees = 90.0, speedKnots = 80.0)
        assertEquals(
            0.0,
            HeadingSolver.windCorrectionDegrees(trueCourse = 90.0, wind = headwind, trueAirspeed = 450.0),
            0.001,
        )
        val tailwind = HeadingSolver.Wind(fromDegrees = 270.0, speedKnots = 80.0)
        assertEquals(
            0.0,
            HeadingSolver.windCorrectionDegrees(trueCourse = 90.0, wind = tailwind, trueAirspeed = 450.0),
            0.001,
        )
    }

    /**
     * Crabbing by the solved angle must actually make the aircraft track the course —
     * that is the whole claim. Fly the corrected heading through the same wind and check
     * the resulting ground track lands back on the course asked for.
     */
    @Test
    fun flyingTheCorrectedHeadingTracksTheCourse() {
        val course = 45.0
        val tas = 430.0
        val wind = HeadingSolver.Wind(fromDegrees = 320.0, speedKnots = 55.0)
        val crab = HeadingSolver.windCorrectionDegrees(trueCourse = course, wind = wind, trueAirspeed = tas)
        val flown = cruising(
            trueHeading = course + crab, trueAirspeed = tas,
            windFrom = wind.fromDegrees, windSpeed = wind.speedKnots,
        )
        assertEquals(
            course, flown.track ?: Double.NaN, 0.1,
            "the crabbed heading must put the aircraft's track back on the leg",
        )
    }

    /**
     * A crosswind that outruns the aircraft cannot be held. The solver must clamp rather
     * than hand `asin` an out-of-range ratio and produce a NaN heading.
     */
    @Test
    fun crosswindBeyondTheAircraftIsClampedNotNaN() {
        val gale = HeadingSolver.Wind(fromDegrees = 180.0, speedKnots = 900.0)
        val correction = HeadingSolver.windCorrectionDegrees(
            trueCourse = 90.0, wind = gale, trueAirspeed = 200.0,
        )
        assertTrue(correction.isFinite())
        assertEquals(HeadingSolver.MAX_WIND_CORRECTION_DEGREES, correction, 0.001)
    }

    // endregion

    // region Combined

    /** Both corrections, in order: crab in the true frame, then step into the magnetic one. */
    @Test
    fun assignedHeadingAppliesCrabThenVariation() {
        val wind = HeadingSolver.Wind(fromDegrees = 180.0, speedKnots = 40.0)
        val crab = asin(40.0 / 400.0) * 180 / PI // ≈ 5.74°, to the right
        val assigned = HeadingSolver.assignedHeading(
            trueCourse = 90.0, wind = wind, trueAirspeed = 400.0, variationDegreesEast = 10.0,
        )
        assertEquals(
            rounded(90 + crab - 10), assigned,
            "crab into the wind, then subtract east variation to reach magnetic",
        )
    }

    /** East variation subtracts, west variation adds — the sign the sim's heading bug reads. */
    @Test
    fun westVariationAddsToTheAssignedHeading() {
        val east = HeadingSolver.assignedHeading(
            trueCourse = 90.0, wind = null, trueAirspeed = 400.0, variationDegreesEast = 12.0,
        )
        val west = HeadingSolver.assignedHeading(
            trueCourse = 90.0, wind = null, trueAirspeed = 400.0, variationDegreesEast = -12.0,
        )
        assertEquals(78, east)
        assertEquals(102, west)
    }

    /** The result is a compass heading, so it wraps rather than going negative. */
    @Test
    fun assignedHeadingWrapsThroughNorth() {
        assertEquals(
            355,
            HeadingSolver.assignedHeading(
                trueCourse = 5.0, wind = null, trueAirspeed = 400.0, variationDegreesEast = 10.0,
            ),
        )
        assertEquals(
            5,
            HeadingSolver.assignedHeading(
                trueCourse = 355.0, wind = null, trueAirspeed = 400.0, variationDegreesEast = -10.0,
            ),
        )
    }

    /**
     * With neither correction available the solver hands back the course it was given —
     * exactly what the app assigned before any of this existed. A sim that exposes no
     * true heading must not have its vectors changed.
     */
    @Test
    fun noCorrectionAvailableLeavesTheCourseUntouched() {
        assertEquals(
            123,
            HeadingSolver.assignedHeading(
                trueCourse = 123.4, wind = null, trueAirspeed = null, variationDegreesEast = null,
            ),
        )
    }

    // endregion

    // region The sim's own reported wind

    /**
     * `environment/wind_direction_true` is the direction the wind blows **from**, so it is
     * taken as read. Pinned against Infinite Flight's own PFD: with the state at 5.5069 rad
     * (315.5° true) the panel showed 301° — the same direction in the magnetic frame, ~14.5°
     * of local variation apart, not the ~135° a "blows toward" reading would have shown.
     */
    @Test
    fun reportedWindIsTakenAsTheFromDirection() {
        val s = AircraftState(reportedWindDirectionTrue = 315.5, reportedWindSpeedKnots = 40.0)
        val wind = HeadingSolver.reportedWind(s)
        assertEquals(315.5, wind?.fromDegrees ?: -1.0, 0.001)
        assertEquals(40.0, wind?.speedKnots ?: -1.0, 0.001)

        // Read as "from", a 315° wind on a course of 315 is a pure headwind — no crab at all.
        // Read as "toward" it would be a pure tailwind, which is also no crab — so the case
        // that separates them is a crosswind course.
        val crab = HeadingSolver.windCorrectionDegrees(trueCourse = 45.0, wind = wind, trueAirspeed = 450.0)
        assertEquals(asin(40 * sin((315.5 - 45) * PI / 180) / 450) * 180 / PI, crab, 0.01)
        assertTrue(crab < 0, "a wind from 90° left of course crabs the nose left, into it")
    }

    /**
     * Unavailable, implausible, or below the resolvable floor — the same rules the solved
     * wind already follows, so the two sources agree on what "no usable wind" means.
     */
    @Test
    fun reportedWindDeclinesTheSameCasesAsTheSolvedWind() {
        assertNull(
            HeadingSolver.reportedWind(AircraftState()),
            "a version that exposes neither state reports no wind",
        )

        val partial = AircraftState(reportedWindDirectionTrue = 200.0)
        assertNull(HeadingSolver.reportedWind(partial), "direction without speed is unusable")

        val absurd = AircraftState(reportedWindDirectionTrue = 200.0, reportedWindSpeedKnots = 400.0)
        assertNull(HeadingSolver.reportedWind(absurd), "a 400 kt wind is a torn read, not weather")

        // 0.72 kt is the 0.3681 m/s in the captured state.
        val light = AircraftState(reportedWindDirectionTrue = 315.5, reportedWindSpeedKnots = 0.72)
        assertEquals(
            HeadingSolver.Wind.calm, HeadingSolver.reportedWind(light),
            "below the resolvable floor is reported calm, as the triangle does",
        )
    }

    /**
     * The cross-check that guards the convention: the reported and solved winds should agree
     * closely, and a disagreement past a right angle is the signature of a build reporting the
     * other end of the vector.
     */
    @Test
    fun directionDisagreementMeasuresTheShortWayRound() {
        val a = HeadingSolver.Wind(fromDegrees = 350.0, speedKnots = 40.0)
        val b = HeadingSolver.Wind(fromDegrees = 10.0, speedKnots = 40.0)
        assertEquals(
            20.0, HeadingSolver.directionDisagreementDegrees(a, b), 0.001,
            "the difference wraps through north rather than reading 340°",
        )

        val flipped = HeadingSolver.Wind(fromDegrees = 170.0, speedKnots = 40.0)
        assertEquals(180.0, HeadingSolver.directionDisagreementDegrees(a, flipped), 0.001)
    }

    /**
     * The second half of that cross-check. Naming the other end of the vector reverses a wind
     * without changing its strength, so only two winds of the *same speed* can be the same
     * wind described two ways.
     */
    @Test
    fun speedsCorroborateOnlyWhenTheTwoWindsCouldBeTheSameWind() {
        val reported = HeadingSolver.Wind(fromDegrees = 221.0, speedKnots = 40.0)
        val flipped = HeadingSolver.Wind(fromDegrees = 41.0, speedKnots = 40.0)
        assertTrue(
            HeadingSolver.speedsCorroborate(reported, flipped),
            "a convention flip changes the direction and nothing else",
        )

        // The captured failure: the sim reported 12 kt, the triangle solved 84 kt out of a
        // smeared mid-turn sample. Nothing about a convention explains that.
        val sim = HeadingSolver.Wind(fromDegrees = 331.0, speedKnots = 12.0)
        val smeared = HeadingSolver.Wind(fromDegrees = 89.0, speedKnots = 84.0)
        assertFalse(HeadingSolver.speedsCorroborate(sim, smeared))

        // Ordinary sampling noise between two readings of the same wind still corroborates —
        // by ratio when the wind is strong, and by the absolute floor when it is light enough
        // that a ratio would call a couple of knots a disagreement.
        assertTrue(
            HeadingSolver.speedsCorroborate(
                HeadingSolver.Wind(fromDegrees = 200.0, speedKnots = 40.0),
                HeadingSolver.Wind(fromDegrees = 20.0, speedKnots = 52.0),
            ),
        )
        assertTrue(
            HeadingSolver.speedsCorroborate(
                HeadingSolver.Wind(fromDegrees = 200.0, speedKnots = 3.0),
                HeadingSolver.Wind(fromDegrees = 20.0, speedKnots = 7.0),
            ),
        )
    }

    // endregion

    /** Swift's `Double.rounded()` — to nearest, ties away from zero — as the tests expect it. */
    private fun rounded(value: Double): Int =
        if (value < 0) ceil(value - 0.5).toInt() else floor(value + 0.5).toInt()
}
