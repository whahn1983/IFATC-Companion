package com.h3consultingpartners.ifatccompanion.core.atc

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure geometry for approach vectors: the heading that intercepts a runway's final
 * approach course at ~30°, turning toward the extended centreline from whichever side
 * the aircraft is on. Deterministic and dependency-free so it can be unit tested
 * without the flight session.
 *
 * Ported from `IFATCCompanion/ATC/ApproachIntercept.swift`.
 */
object ApproachIntercept {

    /** A conventional 30° intercept angle to the final approach course. */
    const val INTERCEPT_ANGLE_DEGREES = 30.0

    /**
     * Inside this cross-track distance the aircraft is treated as established on the
     * centreline, so the vector is the final course straight in.
     */
    const val ESTABLISHED_CROSS_TRACK_NM = 0.5

    /** How far out on the extended centreline the intercept gate is placed. */
    const val GATE_DISTANCE_NM = 20.0

    /**
     * The heading (0…359) to fly to intercept the final approach course.
     *
     * @param finalCourse the landing runway's heading (deg) — i.e. the direction of
     *   travel on final. Magnetic, derived from the runway number; the assigned "fly
     *   heading" is magnetic to match the sim.
     * @param aircraft current aircraft position.
     * @param runwayReference the runway threshold (or the airport reference point as an
     *   approximation), used to place the extended centreline.
     * @param variationDegreesEast local magnetic variation, east positive, used to lay
     *   the extended centreline out on the ground. [finalCourse] is magnetic but
     *   [Geo.destination] steers in true degrees, so without it the centreline is drawn
     *   rotated by the local declination — 1° is ~0.35 NM of error at the 20 NM gate, and
     *   ~15° of declination is over 5 NM, enough to put the aircraft on the wrong side of
     *   a centreline it is close to and turn it 30° the wrong way. Defaults to 0 (the
     *   geometry the app used before it was measured), which is exact wherever variation
     *   is.
     *
     * The aircraft's side of the extended centreline is found from the signed cross-track
     * distance to the inbound final course line; the intercept turns 30° toward the
     * centreline from that side. When the aircraft is already on or near the centreline,
     * the final course itself is returned (straight in).
     *
     * The result stays in the magnetic frame [finalCourse] arrived in: it is an intercept
     * *vector*, and the sim's own approach mode flies the centreline once established, so
     * no wind correction is applied to it.
     */
    fun heading(
        finalCourse: Double,
        aircraft: Coordinate,
        runwayReference: Coordinate,
        variationDegreesEast: Double = 0.0,
    ): Int {
        // The final approach course line: from a gate ~20 NM out on the extended
        // centreline, inbound to the runway. Laid out in true degrees, since that is the
        // frame the great-circle helpers steer in.
        val trueFinalCourse = finalCourse + variationDegreesEast
        val outbound = (trueFinalCourse + 180) % 360
        val gate = Geo.destination(runwayReference, outbound, GATE_DISTANCE_NM)
        val crossTrack = Geo.crossTrackDistanceNM(aircraft, gate, runwayReference)

        val intercept = if (abs(crossTrack) < ESTABLISHED_CROSS_TRACK_NM) {
            finalCourse
        } else {
            // Positive cross-track = right of the inbound course → turn left (−30);
            // negative = left of course → turn right (+30).
            finalCourse - if (crossTrack > 0) INTERCEPT_ANGLE_DEGREES else -INTERCEPT_ANGLE_DEGREES
        }
        return normalizedHeading(intercept)
    }

    /**
     * Normalise a heading to 0…359, matching the app's heading display/spoken convention
     * (`Phonetic.heading` / `headingDisplay`), where north reads "000".
     */
    fun normalizedHeading(degrees: Double): Int = ((degrees.roundToInt() % 360) + 360) % 360
}
