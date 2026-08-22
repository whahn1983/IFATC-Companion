package com.h3consultingpartners.ifatccompanion.core.weather

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Filters weather reports to those relevant to the current route corridor and
 * altitude band, sorted by distance ahead. Pure, deterministic, unit-tested.
 *
 * Ported from `IFATCCompanion/Weather/WeatherRouteAnalyzer.swift`.
 */
class WeatherRouteAnalyzer(
    /** Only used for the `now` default of [relevantReports]; tests pass the value directly. */
    private val clock: Clock = Clock.system,
) {

    /** Mutable like the Swift struct's `var config`, so callers tune one field at a time. */
    data class Config(
        var corridorNM: Double = 100.0,
        var altitudeBandFt: Double = 5000.0,
        /**
         * A PIREP is labeled "near <fix>" only when a route fix lies within this many
         * nautical miles of the PIREP's own position. Beyond it no fix is named and the
         * report keeps just the "… miles ahead" distance — so a report is never labeled
         * with a fix that is actually far from where the turbulence was reported.
         */
        var fixProximityNM: Double = 50.0,
    )

    var config: Config = Config()

    /**
     * A named route fix with a known position. Used to label each PIREP with the nearest
     * fix to *its own* location, rather than a single fix taken from the aircraft position.
     */
    data class NamedFix(val name: String, val coordinate: Coordinate)

    /**
     * Returns relevant PIREPs as [RideReportItem]s ahead of the aircraft along
     * the path toward [routeEnd]. Pass `ignoreAltitudeBand = true` to keep reports at
     * **all** levels (for the smoother-altitude search, which needs the other levels the
     * ±band filter would otherwise hide).
     *
     * [RideReportItem.distanceAheadNM] is measured from [position]. Set
     * `positionIsLiveAircraft = false` when [position] is a route-start fallback (e.g. the
     * departure airport) rather than the live aircraft fix — the resulting items are flagged
     * so the ride-report phrase and the Weather tab omit the "… miles ahead" distance instead
     * of presenting a distance-from-origin as if it were distance ahead of the aircraft.
     *
     * Each item's [RideReportItem.nearFix] is the [routeFixes] entry nearest to *that PIREP's
     * own position* (within [Config.fixProximityNM]), not a single fix taken from the aircraft
     * — so "near <fix>" describes where the turbulence is, not where the aircraft is. When
     * no route fix is close enough, the item carries no fix and the report keeps only the
     * distance-ahead clause.
     */
    fun relevantReports(
        pireps: List<PIREP>,
        position: Coordinate,
        routeEnd: Coordinate?,
        altitudeFt: Double,
        routeFixes: List<NamedFix> = emptyList(),
        ignoreAltitudeBand: Boolean = false,
        positionIsLiveAircraft: Boolean = true,
        nowMillis: Long = clock.nowMillis(),
    ): List<RideReportItem> {

        val courseTo = routeEnd?.let { Geo.bearing(position, it) }

        val items = mutableListOf<RideReportItem>()
        for (pirep in pireps) {
            val severity = pirep.turbulence ?: continue
            if (severity <= TurbulenceSeverity.SMOOTH) continue
            val coord = pirep.coordinate ?: continue
            if (!coord.isValid) continue

            // Altitude band filter (unknown altitude is included conservatively).
            if (!ignoreAltitudeBand) {
                val alt = pirep.altitudeFt
                if (alt != null && abs(alt.toDouble() - altitudeFt) > config.altitudeBandFt) continue
            }

            val distance = Geo.distanceNM(position, coord)
            val bearingToPirep = Geo.bearing(position, coord)

            // Determine "ahead" relative to course (if we have one).
            var distanceAhead = distance
            if (courseTo != null) {
                val angle = Geo.headingDifference(courseTo, bearingToPirep) * PI / 180
                val alongTrack = distance * cos(angle)
                val crossTrack = abs(distance * sin(angle))
                if (alongTrack < -10) continue                    // clearly behind
                if (crossTrack > config.corridorNM) continue      // outside corridor
                distanceAhead = max(0.0, alongTrack)
            } else {
                if (distance > config.corridorNM) continue
            }

            val band: IntRange? = pirep.altitudeFt?.let { alt ->
                val lo = max(0, alt - 2000)
                val hi = alt + 2000
                lo..hi
            }

            val age = pirep.timeMillis?.let { max(0.0, (nowMillis - it) / 1000.0 / 60.0) }
            items.add(
                RideReportItem(
                    severity = severity,
                    altitudeBand = band,
                    distanceAheadNM = distanceAhead,
                    distanceIsFromAircraft = positionIsLiveAircraft,
                    bearing = bearingToPirep,
                    nearFix = nearestFix(coord, routeFixes),
                    sourceRaw = pirep.raw,
                    ageMinutes = age,
                    reportedAltitudeFt = pirep.altitudeFt,
                    aircraftType = pirep.aircraftType,
                ),
            )
        }

        return items.sortedBy { it.distanceAheadNM ?: Double.MAX_VALUE }
    }

    /**
     * The route fix nearest to [coordinate] (a PIREP's own position), or null when the
     * closest one is farther than [Config.fixProximityNM] — in which case the report names
     * no fix rather than one that is far from where the turbulence was reported. Fixes
     * without a name or a valid coordinate are ignored.
     */
    private fun nearestFix(coordinate: Coordinate, fixes: List<NamedFix>): String? {
        val located = fixes.filter { it.name.isNotEmpty() && it.coordinate.isValid }
        val nearest = located.minByOrNull { Geo.distanceNM(coordinate, it.coordinate) } ?: return null
        if (Geo.distanceNM(coordinate, nearest.coordinate) > config.fixProximityNM) return null
        return nearest.name
    }

    /**
     * A specific smoother altitude to suggest, drawn from PIREPs at *other* levels along
     * the route, or null when none supports one (the caller then keeps the generic "advise
     * higher or lower"). Considers reports within [band] that are strictly smoother than
     * [currentSeverity] and at a level at least [minSeparationFt] from [referenceAltFt];
     * prefers the level needing the **least altitude change** (fewest feet of climb or
     * descent from [referenceAltFt]), using the smoother ride only to break ties between
     * two equally-near levels, snapped to 1000 ft. **Data-driven only** — it never invents
     * a smooth level with no report behind it. Pure/testable.
     */
    fun smootherAltitude(
        items: List<RideReportItem>,
        referenceAltFt: Int,
        currentSeverity: TurbulenceSeverity,
        band: IntRange = CRUISE_BAND_FT,
        minSeparationFt: Int = 1500,
    ): SmootherAltitude? {
        if (currentSeverity <= TurbulenceSeverity.SMOOTH) return null
        val candidates: List<Pair<Int, RideReportItem>> = items.mapNotNull { item ->
            val raw = item.reportedAltitudeFt ?: return@mapNotNull null
            // Positive altitudes only, so `roundToInt` (ties toward +infinity) agrees
            // exactly with Swift's `.rounded()` (ties away from zero).
            val alt = (raw.toDouble() / 1000).roundToInt() * 1000
            if (alt !in band) return@mapNotNull null
            if (item.severity >= currentSeverity) return@mapNotNull null
            if (abs(alt - referenceAltFt) < minSeparationFt) return@mapNotNull null
            alt to item
        }
        // Least altitude change first — the smallest climb/descent that reaches a smoother
        // reported ride — and only when two levels are equally near does the smoother of the
        // two win. (A far-off but perfectly smooth level no longer beats a nearer light one.)
        val best = candidates.minWithOrNull(
            compareBy({ abs(it.first - referenceAltFt) }, { it.second.severity }),
        ) ?: return null
        return SmootherAltitude(
            altitudeFt = best.first,
            severity = best.second.severity,
            aircraftType = best.second.aircraftType,
            higher = best.first > referenceAltFt,
        )
    }

    /**
     * Filter SIGMET/AIRMET advisories to those the route actually passes through.
     * Unlike a PIREP — a point report we buffer by the route corridor — a SIGMET
     * covers a wide area, so it is only applicable when the route line genuinely
     * crosses (or starts/ends inside) its polygon; being merely *near* the area
     * does not count. Advisories with no usable geometry are excluded — they can't
     * be placed on the route, and the nationwide AIR/SIGMET feed otherwise makes a
     * distant turbulence advisory look like it's on every flight. Pure and
     * deterministic.
     *
     * Evaluate against the full route polyline (aircraft → remaining fixes →
     * destination), so an advisory on a leg *after* a turn is caught, not just one on
     * the straight line to the destination — "along the entire route."
     */
    fun relevantSigmets(sigmets: List<SIGMET>, routePolyline: List<Coordinate>): List<SIGMET> {
        val route = routePolyline.filter { it.isValid }
        return sigmets.filter { sigmet ->
            // Require a drawable polygon (>= 3 valid vertices): an advisory that can't
            // be placed on the map must not silently drive the ride index either.
            val area = sigmet.drawableArea ?: return@filter false
            routePassesThroughPolygon(area, route)
        }
    }

    /**
     * Convenience for a single straight leg (aircraft → route end). With no route end
     * only the current position being inside the area counts — a lone point isn't a
     * route, so proximity alone is not applicability.
     */
    fun relevantSigmets(
        sigmets: List<SIGMET>,
        position: Coordinate,
        routeEnd: Coordinate?,
    ): List<SIGMET> =
        relevantSigmets(sigmets, listOf(position) + (routeEnd?.let { listOf(it) } ?: emptyList()))

    /**
     * Whether the route actually passes through the advisory polygon: either a
     * polyline vertex lies inside the area, or one of its legs crosses an edge. A lone
     * point (no legs) is applicable only if it sits inside — proximity alone is not.
     */
    private fun routePassesThroughPolygon(
        polygon: List<Coordinate>,
        polyline: List<Coordinate>,
    ): Boolean {
        if (polyline.isEmpty()) return false
        for (p in polyline) if (pointInPolygon(p, polygon)) return true
        if (polyline.size < 2) return false

        // Each leg enters and leaves the area by crossing its boundary, so a
        // pass-through with both endpoints outside is caught by an edge crossing.
        for (k in 0 until polyline.size - 1) {
            val a = polyline[k]
            val b = polyline[k + 1]
            var j = polygon.size - 1
            for (i in polygon.indices) {
                if (Geo.segmentsIntersect(a, b, polygon[j], polygon[i])) return true
                j = i
            }
        }
        return false
    }

    companion object {
        /**
         * Cruise band (ft) a smoother-altitude suggestion is bounded to — commercial jets
         * including regional and business jets (FL240–FL430). Suggestions never fall outside it.
         */
        val CRUISE_BAND_FT: IntRange = 24_000..43_000

        /**
         * Ray-casting point-in-polygon test on lat/lon (adequate at the scale of a
         * SIGMET area; the route corridor check above covers near-edge cases).
         */
        fun pointInPolygon(point: Coordinate, polygon: List<Coordinate>): Boolean {
            if (polygon.size < 3) return false
            var inside = false
            var j = polygon.size - 1
            for (i in polygon.indices) {
                val pi = polygon[i]
                val pj = polygon[j]
                if ((pi.latitude > point.latitude) != (pj.latitude > point.latitude)) {
                    val slope = (point.latitude - pi.latitude) / (pj.latitude - pi.latitude)
                    val intersectLon = pi.longitude + slope * (pj.longitude - pi.longitude)
                    if (point.longitude < intersectLon) inside = !inside
                }
                j = i
            }
            return inside
        }
    }
}
