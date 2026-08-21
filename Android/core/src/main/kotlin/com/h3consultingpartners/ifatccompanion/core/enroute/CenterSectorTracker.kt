package com.h3consultingpartners.ifatccompanion.core.enroute

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo

/**
 * Watches the aircraft's position against the enroute sector map and decides when it
 * has genuinely crossed into the next Center's airspace.
 *
 * Pure and deterministic — no timers, no I/O — so the hand-off logic can be tested by
 * feeding it positions. The caller decides whether a crossing is *spoken*: the tracker
 * follows the aircraft for the whole flight (so the sector name is right the moment
 * Departure hands over), while only the enroute leg puts a call on the radio.
 *
 * Ported from `IFATCCompanion/Enroute/CenterSectorTracker.swift`. The Swift is a
 * `struct` with `mutating` methods held by the app model; here it is a small mutable
 * class held the same way, and iOS's `Date` becomes epoch milliseconds, the time
 * currency of every ported engine (see `core.platform.Clock`).
 */
class CenterSectorTracker {

    /**
     * A confirmed crossing from one sector into the next. Entering the first sector of
     * a flight is not a crossing — the tracker adopts it silently, since Departure's
     * hand-off is what puts the pilot on that Center to begin with.
     */
    data class Crossing(val from: CenterSector, val to: CenterSector)

    /** The sector currently working the aircraft, as far as the tracker is concerned. */
    var current: CenterSector? = null
        private set

    private var lastCoordinate: Coordinate? = null
    private var lastHandoffAtMillis: Long? = null

    /** Forget everything — a new flight, or a session reset. */
    fun reset() {
        current = null
        lastCoordinate = null
        lastHandoffAtMillis = null
    }

    /**
     * Adopt a sector without producing a crossing. Used when restoring a saved session,
     * so a reconnect mid-cruise doesn't re-announce the sector the pilot is already
     * talking to.
     */
    fun adopt(sector: CenterSector?) {
        current = sector
    }

    /**
     * Feed a position fix. Returns a crossing only when the aircraft is confirmed to
     * have flown into a different sector.
     *
     * Several cases produce no crossing and leave the tracker's sector where it is: the
     * data isn't loaded yet, or the fix falls in a gap between boundaries (the source
     * data has a few, mostly over open ocean), or the aircraft is still within the
     * hysteresis buffer of the boundary, or another hand-off was issued moments ago.
     * Leaving [current] untouched in those cases is what makes the decision retry on the
     * next fix instead of silently swallowing the hand-off.
     */
    fun update(
        coordinate: Coordinate,
        atMillis: Long,
        database: CenterSectorDatabase,
    ): Crossing? {
        if (!coordinate.isValid) return null
        val found = database.sector(at = coordinate) ?: return null
        val previousCoordinate = lastCoordinate
        lastCoordinate = coordinate
        val working = current
        if (working == null) {
            // First fix with data in hand: adopt the sector we're already in. There is
            // nothing to hand off from.
            current = found
            return null
        }
        if (found.id == working.id) return null
        // A discontinuity in the track, not a flown crossing.
        val jumped = previousCoordinate?.let {
            Geo.distanceNM(from = it, to = coordinate) > MAXIMUM_FIX_SPACING_NM
        } ?: true
        if (jumped) {
            current = found
            return null
        }
        if (found.distanceToBoundaryNM(from = coordinate) < BOUNDARY_BUFFER_NM) return null
        val last = lastHandoffAtMillis
        if (last != null && atMillis - last < MINIMUM_SECONDS_BETWEEN_HANDOFFS * 1000L) return null
        current = found
        lastHandoffAtMillis = atMillis
        return Crossing(from = working, to = found)
    }

    companion object {
        /**
         * How far inside the next sector the aircraft must be before the crossing counts.
         * Boundaries are frequently flown *along* rather than across — an airway that
         * parallels one, a vector that skims a corner — and without a buffer the radio
         * would ping-pong between two controllers. Four miles is roughly 30 seconds at jet
         * cruise: long enough to be a real crossing, short enough that the new controller
         * still has the aircraft for essentially the whole sector.
         */
        const val BOUNDARY_BUFFER_NM = 4.0

        /**
         * Minimum spacing (seconds) between two sector hand-offs. Clipping the corner
         * where three sectors meet would otherwise produce two calls back to back.
         */
        const val MINIMUM_SECONDS_BETWEEN_HANDOFFS = 90L

        /**
         * Distance between consecutive fixes beyond which the aircraft did not *fly* the
         * crossing: the app was backgrounded, the link dropped and resynced, or the sim was
         * repositioned. Live telemetry moves a fraction of a mile between fixes, so 40 NM is
         * far outside normal flight and squarely inside "this is a jump". A jump adopts the
         * new sector silently — the pilot is already deep inside it, and announcing a
         * hand-off for a boundary crossed while the app was asleep would be nonsense.
         */
        const val MAXIMUM_FIX_SPACING_NM = 40.0
    }
}
