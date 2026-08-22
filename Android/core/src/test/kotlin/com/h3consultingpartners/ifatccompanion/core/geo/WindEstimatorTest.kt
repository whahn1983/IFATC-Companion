package com.h3consultingpartners.ifatccompanion.core.geo

import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wind the deviation vectors are crabbed for.
 *
 * `HeadingSolver` had every piece of this arithmetic — the triangle, the sim's own reading,
 * the smoothing, both cross-checks — and nothing ran them per telemetry tick. So the
 * weather-deviation legs were assigned as raw true bearings: no crab, so the wind walked
 * the aircraft off the drawn line for the leg's whole length, and no declination, so a true
 * bearing was dialled into a magnetic heading bug.
 */
class WindEstimatorTest {

    /** Cruising north at 450 kt TAS, with the sim exposing both heading frames. */
    private fun flying(
        trueHeading: Double = 0.0,
        track: Double = 0.0,
        groundSpeed: Double = 450.0,
        trueAirspeed: Double = 450.0,
        magneticHeading: Double? = null,
        reportedWindFrom: Double? = null,
        reportedWindKnots: Double? = null,
        bankAngle: Double = 0.0,
    ) = AircraftState(
        latitude = 40.0,
        longitude = -95.0,
        altitudeMSL = 35_000.0,
        altitudeAGL = 35_000.0,
        groundSpeed = groundSpeed,
        trueAirspeed = trueAirspeed,
        heading = magneticHeading ?: trueHeading,
        trueHeading = trueHeading,
        track = track,
        verticalSpeed = 0.0,
        onGround = false,
        bankAngle = bankAngle,
        reportedWindDirectionTrue = reportedWindFrom,
        reportedWindSpeedKnots = reportedWindKnots,
    )

    // region Solving

    @Test
    fun `a pure crosswind is solved from the aircraft's own vectors`() {
        // Pointing 000 but tracking 010 means something is pushing it right: a wind from
        // the west. The triangle is the only source here — no reported wind.
        val estimator = WindEstimator()
        repeat(20) { estimator.update(flying(trueHeading = 0.0, track = 10.0)) }

        val wind = estimator.wind
        assertTrue(wind != null, "no wind was solved from a clear crab")
        assertTrue(
            wind.fromDegrees in 240.0..300.0,
            "expected a westerly, got ${wind.fromDegrees}° at ${wind.speedKnots} kt",
        )
    }

    @Test
    fun `on the ground no wind is solved`() {
        // The triangle means nothing at taxi speed, and a wind invented there would crab
        // the departure vector.
        val estimator = WindEstimator()
        estimator.update(flying(groundSpeed = 12.0, trueAirspeed = 0.0).copy(onGround = true))

        assertEquals(null, estimator.wind)
    }

    // endregion

    // region Choosing between the two sources

    @Test
    fun `the sim's own reading is preferred when the two agree`() {
        val estimator = WindEstimator()
        repeat(20) {
            estimator.update(
                flying(trueHeading = 0.0, track = 10.0, reportedWindFrom = 270.0, reportedWindKnots = 78.0),
            )
        }

        assertTrue(estimator.isSimReported, "the exact reading was not preferred")
        assertEquals(270.0, estimator.wind?.fromDegrees)
    }

    @Test
    fun `a reversed convention is overruled by the triangle`() {
        // Same speed, 180° apart: that is a build reporting the direction the wind blows
        // *toward*. The triangle's convention is fixed by the arithmetic, so it wins.
        val estimator = WindEstimator()
        repeat(20) {
            estimator.update(
                flying(trueHeading = 0.0, track = 10.0, reportedWindFrom = 90.0, reportedWindKnots = 78.0),
            )
        }

        assertTrue(!estimator.isSimReported, "a reversed reported wind was steered by")
        assertTrue(
            estimator.wind!!.fromDegrees in 240.0..300.0,
            "fell back to the wrong wind: ${estimator.wind}",
        )
    }

    @Test
    fun `a noisy triangle does not outvote the exact reading`() {
        // Far apart in direction AND in speed. That is not one wind described two ways —
        // the inferred one is noise, and it must not win by disagreeing loudly.
        val estimator = WindEstimator()
        repeat(20) {
            estimator.update(
                // 12 kt against a solved ~78 kt: the field case this guard was written for.
                // Above the cross-check floor, so the comparison really is made and really
                // does come down to the speeds disagreeing.
                flying(trueHeading = 0.0, track = 10.0, reportedWindFrom = 90.0, reportedWindKnots = 12.0),
            )
        }

        assertTrue(estimator.isSimReported, "noise outvoted the sim's own reading")
        assertEquals(90.0, estimator.wind?.fromDegrees)
    }

    @Test
    fun `two light winds far apart decide nothing`() {
        // Both effectively calm. Two calm winds can point anywhere relative to each other,
        // so a 180-degree disagreement between them is not evidence of a reversed
        // convention and must not throw out the exact reading.
        val estimator = WindEstimator()
        repeat(20) {
            estimator.update(
                flying(
                    trueHeading = 0.0, track = 0.4, trueAirspeed = 450.0, groundSpeed = 450.0,
                    reportedWindFrom = 90.0, reportedWindKnots = 4.0,
                ),
            )
        }

        assertTrue(estimator.isSimReported, "a 4 kt disagreement overruled the sim")
    }

    @Test
    fun `a sample taken in a turn is not folded in`() {
        // The triangle differences two ~450 kt vectors read in separate round-trips, so a
        // roll smears it — and the next leg's crab is computed precisely mid-turn.
        val estimator = WindEstimator()
        repeat(20) { estimator.update(flying(trueHeading = 0.0, track = 30.0, bankAngle = 25.0)) }

        assertEquals(null, estimator.wind, "a banked sample was solved for wind")
        assertEquals(null, estimator.variationDegreesEast, "a banked sample set the variation")
    }

    @Test
    fun `a tick with nothing usable leaves the last wind standing`() {
        val estimator = WindEstimator()
        repeat(20) {
            estimator.update(
                flying(trueHeading = 0.0, track = 10.0, reportedWindFrom = 270.0, reportedWindKnots = 78.0),
            )
        }

        // Rolled into a turn, and this build stops reporting a wind. Neither source has an
        // opinion this tick; the last one is better than none.
        estimator.update(flying(trueHeading = 0.0, track = 30.0, bankAngle = 25.0))

        assertEquals(270.0, estimator.wind?.fromDegrees)
        assertTrue(estimator.isSimReported)
    }

    // endregion

    // region Magnetic variation

    @Test
    fun `one torn pair of headings does not move the variation`() {
        // The variation goes straight into the initial departure vector, where being wrong
        // by twenty degrees is the difference between a turn and "fly runway heading".
        val estimator = WindEstimator()
        repeat(2) { estimator.update(flying(trueHeading = 0.0, track = 0.0, magneticHeading = 0.0)) }
        assertEquals(0.0, estimator.variationDegreesEast)

        estimator.update(flying(trueHeading = 20.0, track = 20.0, magneticHeading = 0.0))
        assertEquals(0.0, estimator.variationDegreesEast, "a single torn reading was latched")

        // Said twice, it is a place rather than a torn read.
        estimator.update(flying(trueHeading = 20.0, track = 20.0, magneticHeading = 0.0))
        assertEquals(20.0, estimator.variationDegreesEast)
    }

    // endregion

    // region What the pilot is told

    @Test
    fun `the assigned heading is crabbed into the wind`() {
        // A wind from the west pushes the aircraft right of a northerly course, so the
        // heading has to point left of it.
        val estimator = WindEstimator()
        repeat(20) {
            estimator.update(
                flying(trueHeading = 0.0, track = 10.0, reportedWindFrom = 270.0, reportedWindKnots = 78.0),
            )
        }

        val heading = estimator.assignedHeading(trueCourse = 0.0)
        assertTrue(
            heading in 330..359,
            "a westerly crosswind produced heading $heading for a course of 000",
        )
    }

    @Test
    fun `the assigned heading is converted out of the true frame`() {
        // The sim's two heading states differ by the local declination: true 010,
        // magnetic 000 is 10 degrees east. A true course of 090 is magnetic 080.
        val estimator = WindEstimator()
        repeat(20) {
            estimator.update(flying(trueHeading = 10.0, track = 10.0, magneticHeading = 0.0))
        }

        assertEquals(10.0, estimator.variationDegreesEast)
        assertEquals(80, estimator.assignedHeading(trueCourse = 90.0))
    }

    @Test
    fun `with nothing to solve from the true course is handed over unchanged`() {
        // Every correction is independently optional, and the fallback is exactly what the
        // app assigned before any of this existed.
        val estimator = WindEstimator()
        estimator.update(
            AircraftState(latitude = 40.0, longitude = -95.0, altitudeMSL = 35_000.0, onGround = false),
        )

        assertEquals(123, estimator.assignedHeading(trueCourse = 123.4))
    }

    @Test
    fun `a new flight forgets the old wind`() {
        val estimator = WindEstimator()
        repeat(20) { estimator.update(flying(trueHeading = 0.0, track = 10.0)) }
        assertTrue(estimator.wind != null)

        estimator.reset()

        assertEquals(null, estimator.wind)
        assertEquals(null, estimator.variationDegreesEast)
    }

    @Test
    fun `the crab never exceeds the solver's clamp`() {
        // A crosswind that outruns the aircraft cannot be held at all; the correction is
        // bounded rather than allowed to run to 90 degrees.
        val estimator = WindEstimator()
        repeat(20) {
            estimator.update(
                flying(
                    trueHeading = 0.0, track = 0.0, trueAirspeed = 90.0, groundSpeed = 90.0,
                    reportedWindFrom = 270.0, reportedWindKnots = 200.0,
                ),
            )
        }

        val heading = estimator.assignedHeading(trueCourse = 0.0)
        val offset = abs(((heading - 0 + 540) % 360) - 180)
        assertTrue(
            offset <= HeadingSolver.MAX_WIND_CORRECTION_DEGREES.toInt() + 1,
            "crab ran to $offset degrees",
        )
    }

    @Test
    fun `an assignment records both ends of the correction`() {
        // The Diagnostics row prints the pair rather than the difference: a vector pointing
        // the wrong way and a leg that genuinely pointed there produce the same crab.
        val estimator = WindEstimator()
        // True 006 reading magnetic 000 is six degrees of easterly variation, and the
        // track matches the heading, so the triangle solves calm and only the declination
        // is left to apply.
        repeat(20) {
            estimator.update(
                flying(trueHeading = 6.0, track = 6.0, magneticHeading = 0.0),
            )
        }
        assertEquals(null, estimator.lastAssignedTrueCourse)
        assertEquals(null, estimator.lastAssignedHeading)

        val assigned = estimator.assignedHeading(trueCourse = 90.0)

        assertEquals(90.0, estimator.lastAssignedTrueCourse)
        assertEquals(assigned, estimator.lastAssignedHeading)
        // Magnetic = true − variation east, so the bug reads six degrees less.
        assertEquals(84, assigned)
    }

    @Test
    fun `a new flight forgets the last assignment too`() {
        val estimator = WindEstimator()
        estimator.update(flying())
        estimator.assignedHeading(trueCourse = 270.0)

        estimator.reset()

        assertEquals(null, estimator.lastAssignedTrueCourse)
        assertEquals(null, estimator.lastAssignedHeading)
    }

    // endregion
}
