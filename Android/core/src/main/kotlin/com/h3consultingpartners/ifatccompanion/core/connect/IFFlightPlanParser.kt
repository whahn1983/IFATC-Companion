package com.h3consultingpartners.ifatccompanion.core.connect

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.model.Waypoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs

/**
 * Best-effort parser for the flight plan exposed by the Infinite Flight Connect
 * API v2 (`aircraft/0/flightplan`). Across IF versions this has been served both
 * as a plain whitespace/arrow-separated route string *and* as a richer JSON
 * document (with per-fix coordinates, nested SID/STAR/approach procedures, and
 * planned altitudes). This parser handles both:
 *
 *   1. If the payload looks like JSON, it is walked recursively to recover fix
 *      names, coordinates, planned altitudes (the cruise/TOC level), and the
 *      published procedures.
 *   2. Otherwise it tokenises the route string, classifies tokens into airports
 *      (4-letter ICAO codes) and named fixes, strips departure/arrival runway
 *      tokens (so the runway is never mistaken for the first enroute waypoint),
 *      and recovers a cruise altitude from any `FLxxx` / altitude token.
 *
 * It degrades gracefully (returns `null`) when nothing usable is found, so callers
 * can keep any existing plan untouched.
 *
 * Ported from `IFATCCompanion/Connect/IFFlightPlanParser.swift`.
 */
object IFFlightPlanParser {

    /**
     * Parse a raw IF flight-plan payload into a structured [FlightPlan].
     * Returns `null` when the payload yields no recognisable departure/destination
     * or fixes, so callers can keep any existing plan untouched.
     */
    fun parse(raw: String): FlightPlan? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // A JSON payload is parsed structurally; never fall back to tokenising the
        // braces as a route string (that would yield garbage "waypoints").
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return parseJSON(trimmed)
        }
        return parseRouteString(trimmed)
    }

    /**
     * Build a plan from the several flight-plan states Infinite Flight exposes.
     *
     * [fullInfo] (`aircraft/0/flightplan/full_info`) is the richest source — the
     * detailed JSON document with per-fix planned altitudes and nested SID/STAR/approach
     * procedure groups. It is preferred when present. [full] (`aircraft/0/flightplan`)
     * is the fallback: on some IF versions it is also rich JSON, but on others it
     * collapses a long route to a handful of summary legs. The textual route
     * (`flightplan/route`) carries every fix, so whenever it names more of them than the
     * chosen base plan did, its longer list wins — the detailed document has been seen to
     * name a procedure while omitting its fixes. Those fixes are located from the parallel
     * `flightplan/coordinates` state, which is index-aligned with the route, and any
     * coordinate/altitude the base plan recovered is carried across by name.
     */
    fun parse(
        fullInfo: String? = null,
        full: String?,
        route: String?,
        coordinates: String?,
    ): FlightPlan? {
        // Prefer the detailed full_info document; fall back to the plain flightplan
        // state. full_info is the only state that carries planned altitudes and the
        // published procedure names, so a successful parse of it wins outright.
        var plan = fullInfo?.let { parse(it) }
        val summary = full?.let { parse(it) }
        if (summary != null) {
            val p = plan
            plan = if (p != null) {
                // Keep full_info's rich plan, but borrow any endpoint/cruise the summary
                // recovered that full_info left blank.
                p.copy(
                    departure = if (p.departure.isEmpty()) summary.departure else p.departure,
                    destination = if (p.destination.isEmpty()) summary.destination else p.destination,
                    cruiseAltitude =
                        if (p.cruiseAltitude <= 0) summary.cruiseAltitude else p.cruiseAltitude,
                    departureRunway =
                        if (p.departureRunway.isEmpty()) summary.departureRunway else p.departureRunway,
                    arrivalRunway =
                        if (p.arrivalRunway.isEmpty()) summary.arrivalRunway else p.arrivalRunway,
                )
            } else {
                summary
            }
        }

        // Prefer the route state's fix list whenever it names more enroute fixes than the
        // (possibly incomplete) full payload did.
        //
        // This used to be skipped entirely once the full payload carried any coordinates,
        // on the reasoning that a located-but-shorter list beats a longer coordinate-less
        // one. That cost real fixes: the detailed document has been observed to carry a
        // procedure's *name* while omitting its fixes — a KIAH→KATL plan whose route state
        // listed the I09R approach's DFINS/GGUYY/EEASY/BURNY showed a route ending at the
        // STAR's last fix, because the detailed plan's shorter list had coordinates and so
        // won outright. The trade-off is gone now: [parseAlignedRoute] locates the route
        // state's fixes from the parallel coordinate state, and whatever the detailed plan
        // did recover for the fixes they share is carried across by name.
        val routePlan = route?.let { parseRouteString(it) }
        if (route != null && routePlan != null) {
            // The route string carries the departure/arrival runway tokens even when
            // the detailed payload omits them, so borrow those regardless of whether
            // the route's fix list is preferred below.
            val borrowed = plan
            if (borrowed != null) {
                plan = borrowed.copy(
                    departureRunway = if (borrowed.departureRunway.isEmpty()) {
                        routePlan.departureRunway
                    } else {
                        borrowed.departureRunway
                    },
                    arrivalRunway = if (borrowed.arrivalRunway.isEmpty()) {
                        routePlan.arrivalRunway
                    } else {
                        borrowed.arrivalRunway
                    },
                )
            }
            val located = parseAlignedRoute(route, coordinates)
            val spine = if (located.isEmpty()) routePlan.waypoints else located
            if (spine.size > (plan?.waypoints?.size ?: 0)) {
                val p = plan
                plan = if (p != null) {
                    p.copy(
                        waypoints = carryingKnownDetail(spine, p.waypoints),
                        departure = if (p.departure.isEmpty()) routePlan.departure else p.departure,
                        destination =
                            if (p.destination.isEmpty()) routePlan.destination else p.destination,
                        cruiseAltitude =
                            if (p.cruiseAltitude <= 0) routePlan.cruiseAltitude else p.cruiseAltitude,
                    )
                } else {
                    routePlan.copy(waypoints = spine)
                }
            }
        }

        // Attach coordinates to fixes that still lack them, but only when the parsed
        // coordinate list lines up exactly with the recovered fixes — otherwise a
        // mismatched list would scatter the route across the map.
        val basePlan = plan
        if (coordinates != null && basePlan != null && basePlan.waypoints.isNotEmpty()) {
            val coords = parseCoordinateList(coordinates)
            val hasEndpoints = basePlan.departure.isNotEmpty() && basePlan.destination.isNotEmpty()
            if (coords.size == basePlan.waypoints.size) {
                plan = basePlan.copy(
                    waypoints = basePlan.waypoints.mapIndexed { i, wp ->
                        if (wp.coordinate == null) {
                            wp.copy(latitude = coords[i].latitude, longitude = coords[i].longitude)
                        } else {
                            wp
                        }
                    },
                )
            } else if (hasEndpoints && coords.size == basePlan.waypoints.size + 2) {
                // Infinite Flight includes the departure and destination airport
                // coordinates as the first and last entries of the flat list; capture
                // those endpoints and map the middle coordinates onto the enroute fixes.
                val dep = coords.firstOrNull()
                val dest = coords.lastOrNull()
                plan = basePlan.copy(
                    departureLatitude =
                        if (basePlan.departureCoordinate == null && dep != null) {
                            dep.latitude
                        } else {
                            basePlan.departureLatitude
                        },
                    departureLongitude =
                        if (basePlan.departureCoordinate == null && dep != null) {
                            dep.longitude
                        } else {
                            basePlan.departureLongitude
                        },
                    destinationLatitude =
                        if (basePlan.destinationCoordinate == null && dest != null) {
                            dest.latitude
                        } else {
                            basePlan.destinationLatitude
                        },
                    destinationLongitude =
                        if (basePlan.destinationCoordinate == null && dest != null) {
                            dest.longitude
                        } else {
                            basePlan.destinationLongitude
                        },
                    waypoints = basePlan.waypoints.mapIndexed { i, wp ->
                        if (wp.coordinate == null) {
                            wp.copy(
                                latitude = coords[i + 1].latitude,
                                longitude = coords[i + 1].longitude,
                            )
                        } else {
                            wp
                        }
                    },
                )
            }
        }
        return plan
    }

    /**
     * Ordered, *located* enroute fixes recovered from the `flightplan/route` and
     * `flightplan/coordinates` states together.
     *
     * Infinite Flight emits those two 1-for-1: `route` is a **comma**-separated list of
     * every entry in the plan and `coordinates` carries one lat/lon pair per entry in the
     * same order. Splitting the route on whitespace (as the general route-string parser
     * must, since a route can arrive space-separated) breaks that alignment, because a
     * compound display marker such as `DPT RW15L` is a single comma-separated entry
     * holding one space. Splitting on commas alone preserves the 1-for-1 mapping, so
     * every fix the route names can be given its true position.
     *
     * Returns an empty list when the two states don't line up, so the caller falls back
     * to the name-only route parse rather than scattering the route across the map.
     */
    fun parseAlignedRoute(route: String, coordinates: String?): List<Waypoint> {
        val fields = route.split(",").map { it.trimmingWhitespaces().uppercase() }
        if (fields.size <= 1) return emptyList()
        val coords = coordinates?.let { parseCoordinateList(it) } ?: emptyList()
        if (coords.size != fields.size) return emptyList()

        val fixes = mutableListOf<Waypoint>()
        val seen = mutableSetOf<String>()
        for ((i, field) in fields.withIndex()) {
            // The airports at either end are the endpoints, not enroute fixes.
            if ((i == 0 || i == fields.size - 1) && isICAO(field)) continue
            if (!isFix(field) || isRunwayToken(field) || isPseudoWaypoint(field) ||
                altitudeFromToken(field) != null || seen.contains(field)
            ) {
                continue
            }
            seen += field
            fixes += Waypoint(
                name = field,
                latitude = coords[i].latitude,
                longitude = coords[i].longitude,
            )
        }
        return fixes
    }

    /**
     * Carry the per-fix coordinate and planned altitude the detailed document recovered
     * onto a fix list taken from the flat route state, matching by name. Adopting the
     * longer list therefore never costs a fix the detail the detailed plan did have.
     */
    private fun carryingKnownDetail(spine: List<Waypoint>, known: List<Waypoint>): List<Waypoint> {
        if (known.isEmpty()) return spine
        val byName = mutableMapOf<String, Waypoint>()
        for (wp in known) if (byName[wp.name] == null) byName[wp.name] = wp
        return spine.map { wp ->
            val detail = byName[wp.name] ?: return@map wp
            var out = wp
            if (out.coordinate == null) {
                out = out.copy(latitude = detail.latitude, longitude = detail.longitude)
            }
            if (out.altitude == null) out = out.copy(altitude = detail.altitude)
            out
        }
    }

    /**
     * Parse a flat list of coordinate pairs from the `flightplan/coordinates` state.
     * Tolerant of separators: pulls every signed decimal number and pairs them as
     * (latitude, longitude). Returns only plausible on-globe pairs.
     */
    fun parseCoordinateList(raw: String): List<Coordinate> {
        // Pull every signed decimal number out of the payload, whatever separators
        // (commas, semicolons, whitespace, brackets) IF uses between them.
        val numbers = mutableListOf<Double>()
        val token = StringBuilder()
        fun flush() {
            token.toString().toDoubleOrNull()?.let { numbers += it }
            token.setLength(0)
        }
        for (ch in raw) {
            if (ch == '-' || ch == '.' || ch.isDigit()) {
                if (ch == '-' && token.isNotEmpty()) flush() // a '-' starts a new number
                token.append(ch)
            } else {
                flush()
            }
        }
        flush()

        val pairs = mutableListOf<Coordinate>()
        var i = 0
        while (i + 1 < numbers.size) {
            val lat = numbers[i]
            val lon = numbers[i + 1]
            if (abs(lat) <= 90 && abs(lon) <= 180 && (lat != 0.0 || lon != 0.0)) {
                pairs += Coordinate(lat, lon)
            }
            // Always advances by two: a rejected pair is dropped, never resynchronised.
            i += 2
        }
        return pairs
    }

    // region Route-string parsing

    private fun parseRouteString(trimmed: String): FlightPlan? {
        val tokens = trimmed
            .split(*ROUTE_SEPARATORS)
            .map { it.trimmingWhitespaces().uppercase() }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        val plan = MutablePlan()

        // First/last 4-letter alpha tokens are the departure/arrival airports.
        tokens.firstOrNull()?.let { if (isICAO(it)) plan.departure = it }
        tokens.lastOrNull()?.let { if (isICAO(it) && it != plan.departure) plan.destination = it }

        // Everything between the airports that looks like a fix becomes a waypoint
        // (procedures, airways, runway and altitude/speed tokens are filtered out).
        val middle = tokens.toMutableList()
        if (plan.departure.isNotEmpty() && middle.firstOrNull() == plan.departure) {
            middle.removeAt(0)
        }
        if (plan.destination.isNotEmpty() && middle.lastOrNull() == plan.destination) {
            middle.removeAt(middle.size - 1)
        }

        // Recover a cruise altitude from any flight-level / altitude token.
        plan.cruiseAltitude = middle.mapNotNull { altitudeFromToken(it) }.maxOrNull() ?: 0

        // Recover the departure/arrival runways from runway tokens before filtering. A route
        // *string* carries no coordinates, so there is no departure-runway position to collect
        // here — only the ident.
        val noCoordinate = RunwayMarkers()
        captureRunways(middle.map { Waypoint(name = it) }, plan, noCoordinate)

        val seen = mutableSetOf<String>()
        plan.waypoints = middle.mapNotNull { token ->
            if (!isFix(token) || isRunwayToken(token) || isPseudoWaypoint(token) ||
                altitudeFromToken(token) != null || seen.contains(token)
            ) {
                return@mapNotNull null
            }
            seen += token
            Waypoint(name = token)
        }

        // Require at least one useful field to count as a parse.
        if (plan.departure.isEmpty() && plan.destination.isEmpty() && plan.waypoints.isEmpty()) {
            return null
        }
        return plan.toFlightPlan()
    }

    // endregion

    // region JSON parsing

    /**
     * Parse the richer JSON flight-plan document. Tolerant of key/casing variation
     * across IF versions: it recovers ordered located fixes, the endpoints, the
     * cruise (highest planned altitude), and the SID/STAR/approach names.
     */
    fun parseJSON(raw: String): FlightPlan? {
        val root = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return null

        // Locate the array of flight-plan items wherever it lives in the document.
        val items = flightPlanItems(root) ?: return null

        val plan = MutablePlan()
        val acc = FixAccumulator()

        // Walk the (possibly nested) items in order. Procedure groups carry their
        // fixes as `children`; their name is the published procedure identifier.
        for (item in items) {
            // PARITY: the Swift guards `item as? [String: Any]`, so a *top-level* bare
            // string entry (a simplified `Waypoints` name list) is skipped outright — only
            // a procedure group's children ever reach `appendFix`'s string branch. Ported
            // verbatim; see the PARITY note on `appendFix`.
            val dict = item as? JsonObject ?: continue
            val name = (jsonString(dict, "identifier") ?: jsonString(dict, "name") ?: "")
                .trimmingWhitespaces().uppercase()
            val children = childArray(dict)

            if (children.isNotEmpty()) {
                // A SID/STAR/approach/track grouping. Infinite Flight tags it with an
                // explicit `Type` (Sid=0, STAR=1, Approach=2, Track=3); when present that
                // is authoritative. Otherwise fall back to name + position heuristics — a
                // procedure before any enroute (non-airport) fix is the SID, one after is
                // the STAR. The departure airport doesn't count as enroute.
                val procType = jsonNumber(dict, listOf("type", "Type"))?.toInt()
                val hadEnrouteFix = acc.fixes.any { !isICAO(it.name) }
                val sidWasEmpty = plan.sid.isEmpty()
                classifyProcedure(name, procType, plan, hadEnrouteFix)
                val isApproach = procType == 2 || (procType == null && isApproachName(name))
                // Whether classifying this group just populated the (previously empty)
                // SID slot — i.e. this is the departure procedure whose own first fix
                // drives the takeoff heading.
                val isSID = sidWasEmpty && plan.sid == name && plan.sidFixNames.isEmpty()
                for ((i, child) in children.withIndex()) {
                    appendFix(child, acc)
                    // Record the SID's published fixes in order (skipping runway /
                    // display markers) so the initial departure heading can target the
                    // SID's own first fix rather than an intermediate buffer fix filed
                    // ahead of it.
                    if (isSID) {
                        val cn = childName(child)
                        if (cn.isNotEmpty() && !isRunwayToken(cn) && !isPseudoWaypoint(cn)) {
                            plan.sidFixNames += cn
                        }
                    }
                    // The intercept altitude is the first altitude in the approach
                    // section of the flight plan.
                    if (isApproach && i == 0) {
                        (child as? JsonObject)?.let { childDict ->
                            plannedAltitude(childDict)?.let { plan.approachInterceptAltitude = it }
                        }
                    }
                    // Tag the first real approach fix (the initial approach fix) — the
                    // deepest a weather deviation may rejoin the route. Skip runway /
                    // display-marker tokens so the tag names an actual fix.
                    if (isApproach && plan.approachStartFixName.isEmpty()) {
                        val cn = childName(child)
                        if (cn.isNotEmpty() && !isRunwayToken(cn) && !isPseudoWaypoint(cn)) {
                            plan.approachStartFixName = cn
                        }
                    }
                }
            } else {
                appendFix(item, acc)
            }
        }

        val fixes = acc.fixes

        // Endpoints: first/last ICAO-looking fixes become departure/destination and
        // are dropped from the enroute list. Their coordinates are kept on the plan
        // (the airport isn't a waypoint, so its position would otherwise be lost) so
        // the departure/destination markers land on the real field worldwide, not on
        // the first/last enroute fix.
        fixes.firstOrNull()?.let { first ->
            if (isICAO(first.name)) {
                plan.departure = first.name
                plan.departureLatitude = first.latitude
                plan.departureLongitude = first.longitude
                fixes.removeAt(0)
            }
        }
        fixes.lastOrNull()?.let { last ->
            if (isICAO(last.name) && last.name != plan.departure) {
                plan.destination = last.name
                plan.destinationLatitude = last.latitude
                plan.destinationLongitude = last.longitude
                fixes.removeAt(fixes.size - 1)
            }
        }

        // The compound `DPT RW17R` / `ARR RW18C` markers name their runway outright, so they
        // are believed ahead of the positional guess [captureRunways] makes from bare runway
        // tokens. They are dropped from the fix list before that runs and used to contribute
        // nothing but a position, which left a detailed plan carrying no departure runway at
        // all — and an unknown departure runway is what sends the takeoff clearance down to a
        // runway number derived from the wind. See [markerRunway].
        if (plan.departureRunway.isEmpty()) {
            acc.markers.departureIdent?.let { plan.departureRunway = it }
        }
        if (plan.arrivalRunway.isEmpty()) {
            acc.markers.arrivalIdent?.let { plan.arrivalRunway = it }
        }

        // Recover the departure/arrival runways from any runway tokens (e.g. a
        // `RW22R` token after the field, or `22R` ahead of the destination) before
        // they are dropped from the enroute fixes — the departure one's coordinate
        // included, unless a `DPT RW…` marker already supplied it.
        captureRunways(fixes, plan, acc.markers)
        plan.departureRunwayLatitude = acc.markers.departureCoordinate?.latitude
        plan.departureRunwayLongitude = acc.markers.departureCoordinate?.longitude

        // Drop runway tokens / IF display markers so neither is shown as a fix.
        plan.waypoints = fixes.filter { !isRunwayToken(it.name) && !isPseudoWaypoint(it.name) }
        plan.cruiseAltitude = acc.maxAltitude

        if (plan.departure.isEmpty() && plan.destination.isEmpty() && plan.waypoints.isEmpty()) {
            return null
        }
        return plan.toFlightPlan()
    }

    /**
     * Find the richest flight-plan item array anywhere in the document.
     *
     * Infinite Flight serves both a *simplified* `Waypoints` string list (only the
     * high-level legs — including non-navigational DPT/TOC/TOD display markers) and
     * a *detailed* `FlightPlanItems` array (every fix, with coordinates and nested
     * SID/STAR/approach groups). Keys are PascalCase and the two live side-by-side,
     * so a case-insensitive search that *prefers the detailed array* is needed —
     * otherwise the plan reads as a 5-fix summary with no procedures or coordinates.
     *
     * Ties are broken by strict `>` (first encountered wins) exactly as the Swift does.
     * Do not add a tiebreak or sort the keys: Swift's dictionary order is unspecified,
     * so a deterministic Android rule would diverge on malformed documents.
     */
    private fun flightPlanItems(root: JsonElement): JsonArray? {
        var bestScore = 0
        var bestItems: JsonArray? = null

        fun consider(array: JsonArray, key: String) {
            if (array.isEmpty()) return
            val k = key.lowercase()
            val dicts = array.count { it is JsonObject }
            // Arrays of objects (detailed fixes) always rank above string lists.
            var score = if (dicts > 0) 1000 + dicts else array.size
            if (k.contains("flightplanitem")) {
                score += 5000
            } else if (k.contains("item") || k.contains("fix")) {
                score += 2000
            } else if (k.contains("waypoint")) {
                score += 100
            }
            if (bestItems == null || score > bestScore) {
                bestScore = score
                bestItems = array
            }
        }

        // Recurse through objects only — never descend into an array's elements, so a
        // procedure's nested `children` array is never mistaken for the whole plan.
        fun walk(node: JsonElement, key: String) {
            if (node is JsonArray) {
                consider(node, key)
                return
            }
            if (node is JsonObject) {
                for ((k, v) in node) walk(v, k)
            }
        }

        walk(root, "")
        return bestItems
    }

    /** A procedure group's child fixes, tolerant of key casing (IF uses `Children`). */
    private fun childArray(dict: JsonObject): JsonArray {
        (dict["children"] as? JsonArray)?.let { return it }
        val pair = dict.entries.firstOrNull { it.key.lowercase() == "children" }
        (pair?.value as? JsonArray)?.let { return it }
        return JsonArray(emptyList())
    }

    /**
     * The upper-cased identifier/name of a flight-plan item (string entry or dict),
     * or empty when it carries none. Mirrors the name extraction in [appendFix].
     */
    private fun childName(item: JsonElement): String {
        jsonStringValue(item)?.let { return it.trimmingWhitespaces().uppercase() }
        (item as? JsonObject)?.let { dict ->
            return (jsonString(dict, "identifier") ?: jsonString(dict, "name") ?: "")
                .trimmingWhitespaces().uppercase()
        }
        return ""
    }

    /**
     * What the compound `DPT RW…` / `ARR RW…` display markers are worth keeping once the
     * markers themselves have been dropped from the route: the runways they name, and where
     * the departure one sits. See [markerRunway].
     */
    class RunwayMarkers {
        var departureIdent: String? = null
        var arrivalIdent: String? = null
        var departureCoordinate: Coordinate? = null

        /**
         * Harvest a marker being dropped. First one of each kind wins, so a route that
         * somehow names two never lets the later one displace the one at its own end.
         */
        fun note(name: String, coordinate: Coordinate?) {
            val marker = markerRunway(name) ?: return
            when (marker.end) {
                RouteEnd.DEPARTURE -> {
                    if (departureIdent == null) departureIdent = marker.ident
                    if (departureCoordinate == null && coordinate != null) {
                        departureCoordinate = coordinate
                    }
                }

                RouteEnd.ARRIVAL -> {
                    if (arrivalIdent == null) arrivalIdent = marker.ident
                }
            }
        }
    }

    /** The ordered fixes recovered so far, plus the state [appendFix] threads through. */
    private class FixAccumulator {
        val fixes = mutableListOf<Waypoint>()
        val seen = mutableSetOf<String>()
        var maxAltitude = 0
        val markers = RunwayMarkers()
    }

    /**
     * Append a single fix (with coordinate + planned altitude when present).
     *
     * [FixAccumulator.markers] collects what is worth keeping from a fix that is otherwise
     * dropped: the runway a `DPT RW…` / `ARR RW…` marker names, and the departure marker's
     * position.
     */
    private fun appendFix(item: JsonElement, acc: FixAccumulator) {
        // A bare string entry (a `waypoints` name list) has no coordinate.
        jsonStringValue(item)?.let { raw ->
            val n = raw.trimmingWhitespaces().uppercase()
            if (n.isEmpty() || isPseudoWaypoint(n) || acc.seen.contains(n)) {
                // Dropped — but a compound marker still names its runway.
                acc.markers.note(n, null)
                return
            }
            acc.seen += n
            acc.fixes += Waypoint(name = n)
            return
        }
        val dict = item as? JsonObject ?: return
        val name = (jsonString(dict, "identifier") ?: jsonString(dict, "name") ?: "")
            .trimmingWhitespaces().uppercase()
        if (name.isEmpty()) return

        val coord = coordinate(dict)
        val alt = plannedAltitude(dict)
        // Planned altitudes raise the recovered cruise level even for the
        // non-navigational top-of-climb / top-of-descent display markers Infinite
        // Flight inserts — those sit at the cruise level, so their altitude must
        // count (otherwise the cruise reads as the highest *enroute fix* altitude,
        // i.e. the level just *before* the TOC rather than the final cruise level).
        if (alt != null && alt > acc.maxAltitude && alt < 60000) acc.maxAltitude = alt

        // The marker itself (DPT/TOC/TOD/DEST) contributed its altitude above but is
        // never shown as a fix. A compound runway marker's *runway* and *position* are worth
        // keeping even so: it names the runway explicitly, and it sits at the runway end,
        // which is where the departure leg is actually flown from.
        if (isPseudoWaypoint(name)) {
            acc.markers.note(name, coord)
            return
        }

        // De-dupe by name, but prefer the entry that carries a coordinate.
        if (acc.seen.contains(name)) {
            if (coord != null) {
                val idx = acc.fixes.indexOfFirst { it.name == name && it.coordinate == null }
                if (idx >= 0) {
                    acc.fixes[idx] = acc.fixes[idx]
                        .copy(latitude = coord.latitude, longitude = coord.longitude)
                }
            }
            return
        }
        acc.seen += name
        acc.fixes += Waypoint(
            name = name,
            latitude = coord?.latitude,
            longitude = coord?.longitude,
            altitude = alt?.toDouble(),
        )
    }

    /** Whether a procedure group name denotes an instrument/visual approach. */
    private fun isApproachName(name: String): Boolean {
        val upper = name.uppercase()
        return APPROACH_KEYWORDS.any { upper.contains(it) }
    }

    /**
     * Record a SID/STAR/approach name onto the plan from a procedure grouping.
     * [type] is Infinite Flight's procedure enum (Sid=0, STAR=1, Approach=2, Track=3)
     * when the document provides it; it is authoritative. When absent (`null`), fall
     * back to name + position heuristics.
     */
    private fun classifyProcedure(
        name: String,
        type: Int?,
        plan: MutablePlan,
        hasFixesBefore: Boolean,
    ) {
        if (name.isEmpty()) return
        when (type) {
            0 -> if (plan.sid.isEmpty()) plan.sid = name // SID
            1 -> plan.star = name // STAR — last wins
            2 -> if (plan.approach.isEmpty()) plan.approach = name // Approach
            3 -> Unit // Track (e.g. oceanic) — not a named SID/STAR/approach; fixes stay enroute.
            else -> { // Unknown / untyped — heuristic fallback.
                if (isApproachName(name)) {
                    if (plan.approach.isEmpty()) plan.approach = name
                } else if (!hasFixesBefore) {
                    if (plan.sid.isEmpty()) plan.sid = name
                } else {
                    plan.star = name
                }
            }
        }
    }

    // endregion

    // region JSON field helpers

    /** The value of [item] when it is a JSON string, else null. */
    private fun jsonStringValue(item: JsonElement): String? =
        (item as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun jsonString(dict: JsonObject, key: String): String? {
        dict[key]?.let { exact -> jsonStringValue(exact)?.let { return it } }
        // Case-insensitive fallback. As in the Swift, the *first* entry whose key matches
        // decides: if its value isn't a string, the lookup fails rather than looking on.
        val pair = dict.entries.firstOrNull { it.key.lowercase() == key.lowercase() } ?: return null
        return jsonStringValue(pair.value)
    }

    /**
     * Recover a coordinate from an item, looking inside a nested `location`
     * object as well as on the item itself. Tolerant of key casing.
     */
    private fun coordinate(dict: JsonObject): Coordinate? {
        val container = (dict["location"] as? JsonObject)
            ?: (dict["Location"] as? JsonObject)
            ?: dict
        val lat = jsonNumber(container, listOf("Latitude", "latitude", "lat")) ?: return null
        val lon = jsonNumber(container, listOf("Longitude", "longitude", "lon", "lng")) ?: return null
        // (0, 0) is what a failed read reports, not a position in the Gulf of Guinea.
        if (lat == 0.0 && lon == 0.0) return null
        if (abs(lat) > 90 || abs(lon) > 180) return null
        return Coordinate(lat, lon)
    }

    /** Recover a planned altitude (feet) from an item or its location. */
    private fun plannedAltitude(dict: JsonObject): Int? {
        // IF uses -1 / -1000 for "no altitude", so a non-positive value falls through to
        // the location rather than counting.
        jsonNumber(dict, ALTITUDE_KEYS)?.let { if (it > 0) return it.toInt() }
        val loc = (dict["location"] as? JsonObject) ?: (dict["Location"] as? JsonObject)
        if (loc != null) {
            jsonNumber(loc, ALTITUDE_KEYS)?.let { if (it > 0) return it.toInt() }
        }
        return null
    }

    /** Exact key match only — deliberately *not* case-insensitive, unlike [jsonString]. */
    private fun jsonNumber(dict: JsonObject, keys: List<String>): Double? {
        for (key in keys) {
            val primitive = dict[key] as? JsonPrimitive ?: continue
            if (primitive.isString) continue
            primitive.doubleOrNull?.let { return it }
        }
        return null
    }

    // endregion

    // region Token classification

    /** A 4-letter, all-alphabetic token treated as an ICAO airport identifier. */
    fun isICAO(token: String): Boolean = token.length == 4 && token.all { it.isLetter() }

    /**
     * A plausible named fix / VOR / waypoint: 2–6 alphanumerics containing at
     * least one letter. Excludes pure numbers (altitudes/speeds) and ICAOs.
     */
    fun isFix(token: String): Boolean {
        if (token.length < 2 || token.length > 6) return false
        if (!token.all { it.isLetter() || it.isDigit() }) return false
        if (!token.any { it.isLetter() }) return false
        return !isICAO(token)
    }

    /**
     * A non-navigational display marker Infinite Flight inserts into the simplified
     * route (departure, top-of-climb, top-of-descent, destination). These are not
     * real fixes and must never be shown as waypoints.
     */
    fun isPseudoWaypoint(token: String): Boolean {
        val upper = token.uppercase()
        if (PSEUDO_WAYPOINT_MARKERS.contains(upper)) return true
        // The detailed JSON also carries compound departure/arrival markers that pair
        // the marker word with the runway as a *single* identifier, e.g. "DPT RW15L"
        // or "ARR RW09" — located at the runway threshold/end. The route-string parser
        // never sees these (space is a token separator there), but in the JSON they
        // arrive whole, so neither the bare-word check above nor `isRunwayToken`
        // catches them. Left in, the departure-end point becomes the first "waypoint"
        // and, sitting straight down the runway from the aircraft, forces the takeoff
        // clearance to "fly runway heading". Treat "<marker> <runway>" as the same
        // non-navigational display marker.
        val parts = upper.split(" ").filter { it.isNotEmpty() }
        if (parts.size == 2 && PSEUDO_WAYPOINT_MARKERS.contains(parts[0]) &&
            isRunwayToken(parts[1])
        ) {
            return true
        }
        return false
    }

    /** Which end of the flight a compound display marker belongs to. */
    enum class RouteEnd { DEPARTURE, ARRIVAL }

    /** The runway a compound display marker names, and the end of the flight it belongs to. */
    data class MarkerRunway(val end: RouteEnd, val ident: String)

    /**
     * The runway a compound display marker names, and the end of the flight it belongs to:
     * `"DPT RW26L"` → `(DEPARTURE, "26L")`, `"ARR RW09"` → `(ARRIVAL, "09")`. Null for
     * anything that isn't one.
     *
     * SimBrief files these markers and Infinite Flight serves them located at the runway
     * threshold/end. [isPseudoWaypoint] already recognises them, on purpose: left in the route
     * the runway end becomes the first "waypoint" and, sitting straight down the runway from
     * the aircraft, collapses the takeoff clearance to "fly runway heading". This reads the two
     * things worth keeping out of a fix that is otherwise dropped — **which runway it names**
     * and (via [appendFix]) where it sits.
     *
     * The ident matters as much as the position. A detailed plan whose only runway evidence is
     * this marker used to leave `departureRunway` empty, because the marker is filtered out
     * before [captureRunways] — the only thing that set it — ever sees the fix list. An empty
     * departure runway sends `resolvedRunway` all the way down to its last resort, a runway
     * number *derived from the wind direction*: the clearance then names a runway the field may
     * not even have, and the "within 10° of runway heading" test in the takeoff clearance is
     * measured against the wind rather than against the runway — so a departure vector that
     * happens to lie near the wind collapses to "fly runway heading" with the turn thrown away.
     */
    fun markerRunway(token: String): MarkerRunway? {
        val parts = token.uppercase().split(" ").filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val ident = runwayIdent(parts[1]) ?: return null
        return when (parts[0]) {
            "DPT", "DEP", "DEPARTURE" -> MarkerRunway(RouteEnd.DEPARTURE, ident)
            "ARR", "ARRIVAL" -> MarkerRunway(RouteEnd.ARRIVAL, ident)
            else -> null
        }
    }

    /**
     * Whether a token is a compound **departure** marker (`DPT RW26L`, `DEP RW09`).
     * Arrival markers (`ARR RW09`) are deliberately excluded.
     */
    fun isDepartureRunwayMarker(token: String): Boolean =
        markerRunway(token)?.end == RouteEnd.DEPARTURE

    /**
     * A runway token such as `RW14`, `14`, `30L`, `09C` — these appear at the
     * ends of a route (the departure/arrival runway) and must not be treated as
     * enroute waypoints.
     *
     * PARITY: only the two-character `"RW"` prefix is stripped here, never `"RWY"`, so
     * `isRunwayToken("RWY09")` is false — see [runwayIdent].
     */
    fun isRunwayToken(token: String): Boolean {
        var s = token.uppercase()
        if (s.startsWith("RW")) s = s.drop(2)
        val digits = s.takeWhile { it.isDigit() }
        if (digits.isEmpty()) return false
        val n = digits.toIntOrNull() ?: return false
        if (n < 1 || n > 36) return false
        val rest = s.drop(digits.length)
        return rest.isEmpty() || (rest.length == 1 && rest.all { it in "LRC" })
    }

    /**
     * Normalise a runway token to its bare ident ("RW22R" → "22R", "22R" → "22R").
     * Returns null when the token isn't a runway.
     *
     * PARITY: the `"RWY"` branch below is **unreachable** in the shipping Swift, because
     * [isRunwayToken] strips only `"RW"` — leaving `"Y09"`, whose leading digit run is
     * empty — so `runwayIdent("RWY09")` is null and this early-returns first. Ported
     * as-is: Infinite Flight emits `RW09` / `DPT RW09`, never `RWY09`, and "improving"
     * [isRunwayToken] to accept `RWY` would also change which tokens are filtered out of
     * the enroute fix list. `FlightPlanParserTests.testRunwayIdentNormalisation` asserts
     * `"09"` here, which the shipping code cannot produce.
     */
    fun runwayIdent(token: String): String? {
        if (!isRunwayToken(token)) return null
        var s = token.uppercase()
        if (s.startsWith("RWY")) {
            s = s.drop(3)
        } else if (s.startsWith("RW")) {
            s = s.drop(2)
        }
        return s.ifEmpty { null }
    }

    /**
     * Record the departure/arrival runways from runway tokens in an ordered fix
     * list. The departure runway sits near the start of the route (after the
     * field), the arrival runway near the end. With a single token, its position
     * decides which end it belongs to (first half → departure, else arrival).
     * [markers]'s departure coordinate also collects the departure token's position when it
     * carries one and a `DPT RW…` marker hasn't already supplied it — a bare `RW26L` entry
     * is located at the runway just the same, and it is about to be dropped from the fix
     * list.
     */
    private fun captureRunways(
        fixes: List<Waypoint>,
        plan: MutablePlan,
        markers: RunwayMarkers,
    ) {
        val indices = fixes.indices.filter { isRunwayToken(fixes[it].name) }
        val first = indices.firstOrNull() ?: return
        val last = indices.last()

        fun noteDeparture(index: Int) {
            if (plan.departureRunway.isEmpty()) {
                plan.departureRunway = runwayIdent(fixes[index].name) ?: ""
            }
            if (markers.departureCoordinate == null) {
                fixes[index].coordinate?.let { markers.departureCoordinate = it }
            }
        }

        if (first == last) {
            if (first <= fixes.size / 2) {
                noteDeparture(first)
            } else if (plan.arrivalRunway.isEmpty()) {
                plan.arrivalRunway = runwayIdent(fixes[first].name) ?: ""
            }
        } else {
            noteDeparture(first)
            if (plan.arrivalRunway.isEmpty()) {
                plan.arrivalRunway = runwayIdent(fixes[last].name) ?: ""
            }
        }
    }

    /**
     * Recover an altitude in feet from a flight-level / altitude token:
     * `FL370` → 37000, `F350` → 35000, `37000` → 37000. Returns null otherwise.
     */
    fun altitudeFromToken(token: String): Int? {
        val t = token.uppercase()
        if (t.startsWith("FL") || (t.startsWith("F") && t.drop(1).all { it.isDigit() })) {
            val digits = t.dropWhile { !it.isDigit() }
            val fl = digits.toIntOrNull()
            if (fl != null && fl > 0 && fl <= 600) return fl * 100
        }
        if (t.all { it.isDigit() }) {
            val ft = t.toIntOrNull()
            if (ft != null && ft >= 1000 && ft <= 60000) return ft
        }
        return null
    }

    // endregion

    /**
     * The subset of [FlightPlan] this parser writes, mutable while the walk is in
     * progress. `FlightPlan` itself is an immutable value type, and the Swift's
     * `var plan` is read back mid-loop (`plan.sid.isEmpty`), so a builder keeps the
     * control flow identical without a copy per field.
     */
    private class MutablePlan {
        var departure = ""
        var destination = ""
        var cruiseAltitude = 0
        var departureRunway = ""
        var arrivalRunway = ""
        var sid = ""
        var star = ""
        var approach = ""
        var approachInterceptAltitude = 0
        var approachStartFixName = ""
        val sidFixNames = mutableListOf<String>()
        var waypoints: List<Waypoint> = emptyList()
        var departureLatitude: Double? = null
        var departureLongitude: Double? = null
        var destinationLatitude: Double? = null
        var destinationLongitude: Double? = null
        var departureRunwayLatitude: Double? = null
        var departureRunwayLongitude: Double? = null

        fun toFlightPlan() = FlightPlan(
            departure = departure,
            destination = destination,
            cruiseAltitude = cruiseAltitude,
            departureRunway = departureRunway,
            arrivalRunway = arrivalRunway,
            sid = sid,
            star = star,
            approach = approach,
            approachInterceptAltitude = approachInterceptAltitude,
            approachStartFixName = approachStartFixName,
            sidFixNames = sidFixNames.toList(),
            waypoints = waypoints,
            departureLatitude = departureLatitude,
            departureLongitude = departureLongitude,
            destinationLatitude = destinationLatitude,
            destinationLongitude = destinationLongitude,
            departureRunwayLatitude = departureRunwayLatitude,
            departureRunwayLongitude = departureRunwayLongitude,
        )
    }

    /**
     * Route-string token separators — space, tab, CR, LF, comma, semicolon, pipe,
     * greater-than, slash, hyphen. Ten characters, exactly as the Swift's
     * `CharacterSet(charactersIn: " \t\r\n,;|>/-")`.
     */
    private val ROUTE_SEPARATORS =
        charArrayOf(' ', '\t', '\r', '\n', ',', ';', '|', '>', '/', '-')

    /** IF's non-navigational display markers, upper-cased. */
    private val PSEUDO_WAYPOINT_MARKERS = listOf(
        "DPT", "DEP", "DEPARTURE", "TOC", "T/C", "TOD", "T/D",
        "DEST", "DESTINATION", "ARR", "ARRIVAL",
    )

    private val APPROACH_KEYWORDS =
        listOf("ILS", "RNAV", "RNP", "VOR", "GPS", "LOC", "NDB", "VISUAL", "APP")

    private val ALTITUDE_KEYS =
        listOf("altitude", "Altitude", "AltitudeMSL", "altitudeMSL", "alt")
}
