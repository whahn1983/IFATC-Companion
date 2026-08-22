package com.h3consultingpartners.ifatccompanion.core.model

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.validCoordinateOrNull

/**
 * Snapshot of live aircraft state, read from Infinite Flight Connect or the mock
 * feed. All values are nullable because Connect API coverage varies by
 * aircraft/version.
 *
 * Ported from `IFATCCompanion/Models/AircraftState.swift`.
 */
data class AircraftState(
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Feet. */
    val altitudeMSL: Double? = null,
    /** Feet. */
    val altitudeAGL: Double? = null,
    /** Knots. */
    val groundSpeed: Double? = null,
    /** Knots. */
    val indicatedAirspeed: Double? = null,
    /** Knots. */
    val trueAirspeed: Double? = null,
    /** Degrees magnetic — what the pilot flies and what ATC phraseology uses. */
    val heading: Double? = null,
    /**
     * True (geographic) heading in degrees, when Infinite Flight exposes it. Used to
     * rotate the aircraft symbol on the true-north map so it points where the aircraft
     * is actually pointing — [heading] (magnetic) would be off by the local magnetic
     * declination, which is small near the US/UK but ~20°+ in parts of the southern
     * hemisphere. ATC phraseology still uses the magnetic [heading].
     */
    val trueHeading: Double? = null,
    /** Degrees. */
    val track: Double? = null,
    /** Feet per minute. */
    val verticalSpeed: Double? = null,
    val onGround: Boolean? = null,
    /**
     * Autopilot approach mode (APPR) armed/engaged, read from Infinite Flight when
     * exposed. Used to detect the aircraft is established on the approach so the
     * "cleared … approach" call can be issued before the Tower hand-off.
     */
    val approachModeEngaged: Boolean? = null,
    /**
     * Parking brake state, read from Infinite Flight when exposed. Used to confirm the
     * aircraft is actually parked at the gate (brake set) before the arrival is
     * announced complete. Null when the sim/version doesn't expose it.
     */
    val parkingBrakeSet: Boolean? = null,
    val gForce: Double? = null,
    /**
     * Attitude in **signed degrees** — right/up positive — whichever units the sim
     * reported them in. The state reader settles radians-vs-degrees for the whole state
     * snapshot at once, these included, and wraps them to −180…180 rather than onto a
     * compass rose so "how far from level" is just `abs(bankAngle)`.
     */
    val bankAngle: Double? = null,
    val pitch: Double? = null,
    /**
     * The wind at the aircraft as the **sim itself reports it**
     * (`environment/wind_velocity` and `environment/wind_direction_true`), normalised
     * to knots and degrees true. Distinct from `HeadingSolver.wind`, which *solves* the
     * wind by inverting the wind triangle from track/groundspeed against true
     * heading/TAS. Null when the version doesn't expose the states. Whether the
     * reported direction is the meteorological "from" or the direction the wind blows
     * "toward" is not settled by the state name, so this is reported next to the solved
     * wind rather than substituted for it.
     */
    val reportedWindDirectionTrue: Double? = null,
    val reportedWindSpeedKnots: Double? = null,
    /** ICAO if known. */
    val nearestAirport: String? = null,
    val nearestAirportDistanceNM: Double? = null,
    val aircraftName: String? = null,
    val liveryName: String? = null,
    /** Epoch millis of the snapshot, or null when never updated. */
    val lastUpdateMillis: Long? = null,
) {

    val coordinate: Coordinate? get() = validCoordinateOrNull(latitude, longitude)

    /** True when we have enough position/altitude to drive phase detection. */
    val hasUsablePosition: Boolean get() = coordinate != null && altitudeMSL != null

    /**
     * Whether this snapshot carries any usable telemetry at all. The Connect link
     * returns an all-null snapshot during the reconnect handshake (every field read
     * fails), and there is nothing in one for any part of the app to act on — no
     * position to route from, no altitude to assign against. The phase detector holds
     * the phase on a snapshot with no ground reference rather than reading it as
     * airborne, so this is no longer the only thing standing between a handshake blip
     * and a parked aircraft jumping to cruise; it is still where a snapshot carrying
     * nothing at all stops.
     */
    val hasUsableTelemetry: Boolean
        get() = onGround != null || altitudeMSL != null || coordinate != null

    val isClimbing: Boolean get() = (verticalSpeed ?: 0.0) > 300
    val isDescending: Boolean get() = (verticalSpeed ?: 0.0) < -300

    companion object {
        val empty = AircraftState()
    }
}
