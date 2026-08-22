package com.h3consultingpartners.ifatccompanion.core.model

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.geo.coordinateOrNull
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A single flight-plan fix / waypoint. */
@OptIn(ExperimentalUuidApi::class)
@Serializable
data class Waypoint(
    val id: String = Uuid.random().toString(),
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Feet, if specified. */
    val altitude: Double? = null,
) {
    val coordinate: Coordinate? get() = coordinateOrNull(latitude, longitude)
}

/**
 * The active flight plan. Fields come from Connect when available, otherwise from
 * manual overrides entered by the pilot in the Flight tab.
 *
 * Ported from `IFATCCompanion/Models/FlightPlan.swift`.
 */
@Serializable
data class FlightPlan(
    val callsign: String = "",
    val airline: String = "",
    val flightNumber: String = "",
    /** ICAO. */
    val departure: String = "",
    /** ICAO. */
    val destination: String = "",
    /** ICAO. */
    val alternate: String = "",
    /** Feet. */
    val cruiseAltitude: Int = 0,
    /** Manual/override runway (applies to both ends when set in the Flight tab). */
    val runway: String = "",
    /**
     * Departure runway recovered from the flight plan (e.g. "22R" from a `DPT RW22R`
     * token). Empty when the plan does not name one.
     */
    val departureRunway: String = "",
    /**
     * Arrival runway recovered from the flight plan (a runway token near the end of the
     * route). The parsed approach's runway takes precedence over this on arrival.
     */
    val arrivalRunway: String = "",
    val sid: String = "",
    val star: String = "",
    val approach: String = "",
    /**
     * Departure gate / stand identifier (e.g. "C12"). Manual-override only — Infinite
     * Flight does not expose it. Used by the pushback request at the gate.
     */
    val departureGate: String = "",
    /**
     * Arrival gate / stand identifier (e.g. "B44"). Manual-override only — Infinite
     * Flight does not expose it. Used by the arrival Ramp taxi-to-gate instruction.
     */
    val arrivalGate: String = "",
    /**
     * Intercept/initial altitude (ft MSL) for the approach — the first altitude in the
     * approach section of the flight plan when known, else 0 (callers default).
     */
    val approachInterceptAltitude: Int = 0,
    /**
     * Name of the first fix of the approach procedure (the initial approach fix), when
     * the plan carries a parsed approach. This is the deepest a weather deviation may
     * rejoin the route — the mint line never routes past it toward the destination.
     * Empty when no approach is known.
     */
    val approachStartFixName: String = "",
    /**
     * Ordered fix names of the filed departure procedure (SID), recovered from the SID
     * group in the flight plan — Infinite Flight nests the SID's own fixes under the
     * procedure. Empty when no SID is filed (or its fixes aren't known). The initial
     * departure heading targets the first of these that is a located waypoint, so an
     * intermediate "buffer" fix a pilot files between the runway and the SID (to keep
     * the autopilot from turning at rotation) never displaces the SID's true first fix.
     */
    val sidFixNames: List<String> = emptyList(),
    val waypoints: List<Waypoint> = emptyList(),
    /**
     * Coordinate Infinite Flight reports for the departure field, captured from the
     * flight plan itself. The built-in `AirportDatabase` only covers a handful of US
     * hubs, so this is how the departure marker lands on the real field for airports
     * outside that list (the whole world). Null when the plan carries no located
     * departure endpoint.
     */
    val departureLatitude: Double? = null,
    val departureLongitude: Double? = null,
    /** Coordinate Infinite Flight reports for the destination field (see above). */
    val destinationLatitude: Double? = null,
    val destinationLongitude: Double? = null,
    /**
     * Coordinate of the **departure runway** itself, recovered from the plan's departure
     * marker (`DPT RW26L`, which SimBrief and Infinite Flight place at the runway end)
     * or from a bare `RW26L` token near the start of the route. The marker is not a
     * navigable fix and is never shown as a waypoint — only its position is kept,
     * because it is the point the departure leg is actually flown *from*, and the
     * initial departure heading is measured from it. Null when the plan names no
     * departure runway with a coordinate.
     */
    val departureRunwayLatitude: Double? = null,
    val departureRunwayLongitude: Double? = null,
    /**
     * Source of truth flag — when true, fields were entered manually and should not be
     * overwritten by Connect parsing.
     */
    val manualOverride: Boolean = false,
) {

    val departureName: String get() = departure.ifEmpty { "departure" }
    val destinationName: String get() = destination.ifEmpty { "destination" }

    /**
     * The departure field's coordinate as reported by Infinite Flight, when the plan
     * carries one. Preferred over the first-waypoint fallback so the departure marker
     * sits on the actual field rather than the first enroute fix.
     */
    val departureCoordinate: Coordinate?
        get() = coordinateOrNull(departureLatitude, departureLongitude)

    /**
     * The departure runway's own coordinate, when the plan carries one. This is where
     * the departure leg starts, so it is the truest origin for the initial departure
     * heading — truer than the field reference (which at a hub like KATL can sit miles
     * from the threshold you are actually rolling off) and truer than the aircraft's
     * live position (which is wherever it happens to be holding short).
     */
    val departureRunwayCoordinate: Coordinate?
        get() = coordinateOrNull(departureRunwayLatitude, departureRunwayLongitude)

    /**
     * The destination field's coordinate as reported by Infinite Flight, when the plan
     * carries one. Preferred over the last-waypoint fallback so the destination marker
     * sits on the actual field rather than the last enroute fix.
     */
    val destinationCoordinate: Coordinate?
        get() = coordinateOrNull(destinationLatitude, destinationLongitude)

    /**
     * Coordinate of the first located enroute fix, used as a route-start fallback when
     * the departure airport isn't in the built-in coordinate database.
     */
    val firstWaypointCoordinate: Coordinate?
        get() = waypoints.firstOrNull { it.coordinate != null }?.coordinate

    /**
     * Coordinate of the last located enroute fix, used as a route-end fallback when the
     * destination airport isn't in the built-in coordinate database.
     */
    val lastWaypointCoordinate: Coordinate?
        get() = waypoints.lastOrNull { it.coordinate != null }?.coordinate

    /**
     * Coordinate of the first approach fix, when the plan names one and it carries a
     * coordinate. The deepest point a weather deviation may rejoin the route.
     */
    val approachStartCoordinate: Coordinate?
        get() {
            if (approachStartFixName.isEmpty()) return null
            return waypoints.firstOrNull { it.name == approachStartFixName }?.coordinate
        }

    /** The next un-passed waypoint relative to a position, or destination. */
    fun nextWaypoint(from: Coordinate?): Waypoint? {
        val located = waypoints.filter { it.coordinate != null }
        if (from == null || located.isEmpty()) return waypoints.firstOrNull()
        return located.minByOrNull { Geo.distanceNM(from, it.coordinate!!) }
    }

    /**
     * The next waypoint *ahead* of the aircraft along the filed route — the fix the
     * pilot has not yet passed — used for the "resume own navigation, direct …"
     * clearance so the companion never clears the pilot direct to a fix already behind
     * them (e.g. the runway-end fix). When the route origin is known, a fix is "ahead"
     * if it lies farther down-route than the aircraft's current progress; otherwise it
     * falls back to the nearest located fix, then the first waypoint.
     */
    fun nextUnpassedWaypoint(from: Coordinate?, origin: Coordinate?): Waypoint? {
        val located = waypoints.filter { it.coordinate != null }
        if (from == null || located.isEmpty()) return waypoints.firstOrNull()
        if (origin != null) {
            val progress = Geo.distanceNM(origin, from)
            val ahead = located.firstOrNull {
                Geo.distanceNM(origin, it.coordinate!!) > progress + 1
            }
            if (ahead != null) return ahead
        }
        return located.minByOrNull { Geo.distanceNM(from, it.coordinate!!) }
            ?: waypoints.firstOrNull()
    }

    /**
     * The fix the initial departure heading should intercept off the runway — the
     * bearing to it (from the aircraft's position on the runway) is the heading the
     * takeoff clearance issues. This is airport-agnostic: it never depends on the field
     * being in a built-in table.
     *
     *   1. When a SID is filed, the SID's first published fix that is present as a
     *      located flight-plan waypoint **and clear of the field**. The SID's own fix
     *      list is taken from the filed procedure structure ([sidFixNames], recovered
     *      from the SID group in the plan) first, then from any caller-supplied list
     *      ([sidFixes] — the built-in library for the demo airports). The first name
     *      that matches a located filed waypoint wins. Because the match is by name —
     *      not by route position — an intermediate "buffer" fix filed between the
     *      runway and the SID never displaces the SID's true first fix.
     *   2. Only when no SID structure is known: the next filed fix after the runway —
     *      the first located fix clear of the field, so a fix sitting on the field is
     *      never chosen. Falls back to the first located fix, then the first filed fix
     *      (which may be unlocated).
     *
     * Returns null only when the plan carries no fixes at all. When the chosen fix has
     * no coordinate the caller cannot form a bearing and should issue "runway heading" —
     * it must never fall back to a bearing toward the destination, which for a northern
     * departure to a southern destination points ~180° the wrong way.
     */
    fun initialDepartureFix(sidFixes: List<String>, origin: Coordinate?): Waypoint? {
        // Whether a fix is far enough from the departure runway to give a meaningful
        // bearing off it. Applied to *both* branches: a published SID commonly names a
        // fly-over fix at the runway end as its first fix, and taking that one leaves the
        // caller measuring a bearing across a few hundred feet — which it rejects,
        // collapsing the whole clearance to "runway heading".
        fun clearOfTheField(waypoint: Waypoint): Boolean {
            val coordinate = waypoint.coordinate ?: return false
            if (origin == null) return true
            return Geo.distanceNM(origin, coordinate) >= DEPARTURE_FIX_CLEARANCE_NM
        }
        // The SID's own first published fix, matched by name to a located waypoint. The
        // filed SID structure (sidFixNames) is authoritative; sidFixes covers the demo
        // airports whose fixes come from the built-in library.
        for (name in sidFixNames + sidFixes) {
            val sidFix = waypoints.firstOrNull {
                it.name.equals(name, ignoreCase = true) && clearOfTheField(it)
            }
            if (sidFix != null) return sidFix
        }
        // No SID structure: the next filed fix after the runway.
        val located = waypoints.filter { it.coordinate != null }
        located.firstOrNull { clearOfTheField(it) }?.let { return it }
        return located.firstOrNull() ?: waypoints.firstOrNull()
    }

    companion object {
        val empty = FlightPlan()

        /**
         * How far from the departure runway a fix must sit before the bearing to it is
         * worth flying. Inside this the fix is on the field and the bearing is noise.
         */
        const val DEPARTURE_FIX_CLEARANCE_NM: Double = 1.0
    }
}
