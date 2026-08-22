package com.h3consultingpartners.ifatccompanion.core.atc

/**
 * The traffic pattern a go-around flies.
 *
 * Pure arithmetic, separated out because it is the one part of the missed approach that can
 * be wrong without anybody noticing: a crosswind vector 180° out still sounds like a
 * plausible instruction on the radio, and the only way to catch it is to check the number.
 */
object GoAroundPattern {

    /**
     * The crosswind-leg heading: 90° off the landing runway, turning the way the pattern
     * turns. Left traffic is the standard, and turns left.
     *
     * Normalised to 0…359, so a runway on a westerly heading does not produce a negative
     * vector the phraseology would read out as "turn heading minus twenty".
     */
    fun crosswindHeading(runwayHeading: Int, leftTraffic: Boolean): Int {
        val delta = if (leftTraffic) -90 else 90
        return ((runwayHeading + delta) % 360 + 360) % 360
    }

    /**
     * The standard pattern direction. Left-hand unless a field says otherwise, which none
     * of the data the app has does — so this is a named constant rather than a magic `true`
     * at the call site, and the place to look when right-hand patterns arrive.
     */
    const val LEFT_TRAFFIC = true

    /** Due north, for a runway identifier that carries no usable number. */
    const val FALLBACK_RUNWAY_HEADING = 360
}
