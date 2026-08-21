package com.h3consultingpartners.ifatccompanion.core.surface.routing

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.surface.AircraftSizeClass
import com.h3consultingpartners.ifatccompanion.core.surface.AirportSurfaceModel
import com.h3consultingpartners.ifatccompanion.core.surface.GeoCoordinate
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceConfidence
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceRunwayEnd
import com.h3consultingpartners.ifatccompanion.core.surface.toCoordinates
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A best-effort taxi-routing engine over the airport surface graph.
 *
 * Uses A* with a great-circle heuristic, but **never** chooses purely on shortest
 * distance. Strong penalties push routes away from unnecessary runway crossings,
 * active-runway back-taxi / runway occupancy, disconnected jumps, inferred apron
 * shortcuts, closed taxiways, aircraft-incompatible or unnamed low-confidence
 * segments, and sharp turns; it prefers named, connected, high-confidence geometry,
 * full-length runway entry, and fewer crossings. When the aircraft's heading is known
 * the route also **starts in the direction the aircraft is pointing** — it won't open with
 * a 180° pivot in place, instead setting off forward and turning around farther along if
 * the goal is behind — and a small per-turn cost makes it prefer routes with **fewer**
 * turns over ones that step down through many small sequential turns. A **departure** never
 * crosses its own runway, and never taxis the long way around it either: when every mapped
 * hold/entry for the assigned end sits on the far side, the route instead rolls up to the
 * nearest taxiway that crosses the runway toward the assigned end and holds short there —
 * that crossing threshold *is* the departure hold. Output confidence is graded so the caller
 * can suppress overly precise instructions when the data is weak.
 *
 * Ported from `IFATCCompanion/AirportSurface/TaxiRouteEngine.swift`.
 */
class TaxiRouteEngine(
    val graph: SurfaceGraph,
    val model: AirportSurfaceModel,
) {

    // Penalty weights (meters-equivalent) — deliberately large so geometry alone never
    // wins over these operational preferences.
    private val crossingPenalty = 4_000.0
    private val inferredPenalty = 3_000.0
    private val unnamedPenalty = 350.0
    private val taxilanePenalty = 200.0
    private val lowConfidencePenalty = 500.0
    private val widthPenalty = 3_000.0
    private val sharpTurnPenalty = 1_200.0
    private val moderateTurnPenalty = 300.0

    /**
     * Charged once for **every** ordinary direction change at a junction (a turn past
     * [minTurnDegrees] but not sharp enough for the tiers above). Distance alone doesn't
     * distinguish a route that "steps down" a series of small alternating turns from one
     * that reaches the same place with a single left and a single right; this per-turn cost
     * makes the router prefer the route with **fewer** turns when the distances are close.
     */
    private val perTurnPenalty = 150.0

    /**
     * Direction changes below this (degrees) read as taxiing straight through a junction /
     * a gentle merge, not a turn, so they carry no per-turn cost.
     */
    private val minTurnDegrees = 30.0

    /**
     * Charged when the route would **begin** by reversing against the aircraft's current
     * heading — a 180° pivot in place. Large (larger than a runway crossing) so the route
     * instead sets off the way the aircraft is pointing and turns around farther along if it
     * must, but finite so a genuinely unavoidable reversal (e.g. off a dead-end exit) still
     * routes rather than failing.
     */
    private val uTurnPenalty = 5_000.0

    /**
     * How far (degrees) an intended direction of travel must differ from the aircraft's
     * heading to count as reversing against it. Two ends of the edge under the aircraft are
     * ~180° apart, so this cleanly separates the endpoint ahead from the one behind.
     */
    private val reverseHeadingThreshold = 120.0

    /**
     * A connector whose straight lead-in cuts through a building/terminal — heavily
     * disfavored so a clear alternative to the same stand always wins.
     */
    private val buildingCrossingPenalty = 6_000.0

    /**
     * Distance (m) from the named gate within which the route is still anchored at the
     * stand. Once the aircraft has pushed back and moved farther than this, the route
     * starts from where the aircraft actually is instead — otherwise its first leg is
     * the gate→pushback segment the aircraft has already left, which tracks as
     * "off route" the moment the map appears.
     */
    private val gateAnchorMeters = 30.0

    /**
     * How close (m) the aircraft's projection onto an edge must be to one of that edge's
     * endpoints before the start is treated as a plain node snap rather than a mid-edge
     * one. Keeps a route that genuinely begins at a junction clean (no zero-length lead-in)
     * while still letting the aircraft anchor partway along a long diagonal exit.
     */
    private val endpointSnapMeters = 8.0

    /**
     * How far from the assigned runway-end threshold a plain taxi node may sit and still
     * serve as a last-resort goal when no runway-entry / holding-position node carries the
     * runway's ident (e.g. the hold wasn't tagged in OSM).
     */
    private val goalThresholdFallbackMeters = 300.0

    /**
     * How far (m) before a runway-centerline crossing the hold-short point sits along the
     * route. Used both for a mid-route crossing's `holdShortPoint` and — for a **departure**
     * that would otherwise cross its own runway — as the point the route is cut back to so it
     * stops on the near side and holds short (see `assemble`).
     */
    private val holdShortLeadMeters = 25.0

    /**
     * How far (m) off a runway's centerline a point must sit before it is credited to one side
     * of that runway. Comfortably wider than a runway half-width, so a node on the pavement or
     * off the end of the centerline (a full-length entry at the threshold) reads as "neither
     * side" rather than being mistaken for the far side.
     */
    private val runwaySideToleranceMeters = 30.0

    /**
     * Metres per degree of latitude — used only to give the signed side-of-runway offset a
     * magnitude in metres so [runwaySideToleranceMeters] is meaningful.
     */
    private val metersPerDegree = 111_320.0

    /**
     * Upper bound on how many goal candidates the router probes with A* before giving up.
     * Each probe is cheap, but a runway whose whole area is disconnected from the taxi
     * network would otherwise probe every candidate; this keeps a hopeless case prompt.
     */
    private val maxGoalAttempts = 16

    data class Request(
        val startCoordinate: Coordinate,
        val startGateName: String? = null,
        val isDeparture: Boolean,
        val assignedRunwayIdent: String? = null,
        val arrivalGateName: String? = null,
        val aircraft: AircraftSizeClass = AircraftSizeClass.MEDIUM,
        val allowIntersectionDeparture: Boolean = false,
        /**
         * The aircraft's current heading (degrees true), when it is under way on the surface.
         * Used to start the route in the direction the aircraft is pointing instead of pivoting
         * 180° in place. Ignored while parked at a stand (the parked orientation isn't the taxi
         * direction) and absent (null) means heading isn't considered — routing is unchanged.
         */
        val aircraftHeadingDegrees: Double? = null,
    )

    // MARK: - Public entry

    fun route(request: Request): SurfaceTaxiRoute? {
        if (graph.nodes.size <= 1 || graph.edges.isEmpty()) return null
        val anchor = resolveStart(request) ?: return null
        attemptRoute(request, anchor)?.let { return it }
        // An edge snap can land on a stub whose component reaches no goal; a connected node
        // elsewhere still might. Fall back to a plain node snap so this never fails a route the
        // old node-only snapping would have found.
        if (anchor is StartAnchor.Edge) {
            val fallback = nodeAnchorFallback(request.startCoordinate)
            if (fallback != null) attemptRoute(request, fallback)?.let { return it }
        }
        return null
    }

    private data class LeadIn(
        val geometry: List<GeoCoordinate>,
        val name: String?,
        val crossingEdges: List<Int>,
    )

    private data class Seed(val node: Int, val cost: Double)

    private fun attemptRoute(request: Request, anchor: StartAnchor): SurfaceTaxiRoute? {
        // A* seeds: a plain node start seeds that one node at zero cost; a mid-edge start
        // (the aircraft partway along a diagonal exit / taxiway) seeds *both* endpoints, each
        // at its along-edge distance from the aircraft's projection, and lets A* pick whichever
        // gives the better route. This is what keeps the route starting under the aircraft
        // rather than jumping to a node a taxiway away.
        val seeds: List<Seed>
        val snapMeters: Double
        // Start nodes whose *first* leaving edge must respect the aircraft's heading (a live
        // node snap under way). The edge-start case bakes the heading preference into the seed
        // costs below instead, so it contributes nothing here.
        val headingStartNodes = mutableSetOf<Int>()
        when (anchor) {
            is StartAnchor.Node -> {
                seeds = listOf(Seed(anchor.id, 0.0))
                snapMeters = anchor.distanceMeters
                if (anchor.headingApplies && request.aircraftHeadingDegrees != null) {
                    headingStartNodes.add(anchor.id)
                }
            }
            is StartAnchor.Edge -> {
                val e = graph.edges[anchor.edgeIndex]
                val fromCost = max(0.0, anchor.alongFromFrom)
                val toCost = max(0.0, e.distanceMeters - anchor.alongFromFrom)
                var edgeSeeds = listOf(Seed(e.from, fromCost), Seed(e.to, toCost))
                // Respect the aircraft's heading: leaving the edge toward the endpoint that lies
                // *behind* the aircraft is a 180° pivot in place. Drop that endpoint as a seed (not
                // merely penalize it — both endpoints are start nodes, so a penalized backward seed
                // still reconstructs as the backward lead-in) so A* sets off toward the endpoint the
                // aircraft is already pointing at; it reaches a goal that lies behind by taxiing
                // forward and turning around farther along. Keep both when neither/both endpoints are
                // clearly behind (heading roughly across the edge) so routing still succeeds. Because
                // the two endpoints sit on one line through the projection, at most one is "behind".
                val hdg = request.aircraftHeadingDegrees
                if (hdg != null) {
                    val proj = anchor.projection.toCoordinate()
                    val fromReversing = Geo.headingDifference(
                        hdg, Geo.bearing(proj, nodeCoord(e.from)),
                    ) > reverseHeadingThreshold
                    val toReversing = Geo.headingDifference(
                        hdg, Geo.bearing(proj, nodeCoord(e.to)),
                    ) > reverseHeadingThreshold
                    if (fromReversing && !toReversing) edgeSeeds = listOf(Seed(e.to, toCost))
                    else if (toReversing && !fromReversing) edgeSeeds = listOf(Seed(e.from, fromCost))
                }
                seeds = edgeSeeds
                snapMeters = anchor.perpMeters
            }
        }
        val startNodes = seeds.map { it.node }.toSet()

        // Try each goal candidate in priority order (full-length runway entry, then holds for
        // an intersection departure, then taxi nodes near the threshold) and take the first
        // the aircraft can actually reach. A single first-choice goal can be stranded in a
        // disconnected patch of the OSM graph — at a big field like KATL the far-end runway
        // entry may not be wired to the terminal taxiways — which used to fail the whole route
        // even though another node for the same runway was reachable. Bounded so a runway
        // whose entire area is disconnected still returns promptly.
        //
        // A goal the route already starts at is skipped as degenerate — except a hold-short at a
        // crossing, whose route is the roll-out from that node up to the runway. Without the
        // exception, recalculating while parked on the junction of the crossing taxiway would
        // fall through to a far-side hold and taxi all the way around.
        val goals = resolveGoalCandidates(request).filter {
            it.holdShortCrossingEdge != null || !startNodes.contains(it.node)
        }
        var attempts = 0
        for (goal in goals) {
            if (attempts >= maxGoalAttempts) break
            attempts += 1
            val result = astar(
                starts = seeds, goal = goal.node, aircraft = request.aircraft,
                heading = request.aircraftHeadingDegrees, headingStartNodes = headingStartNodes,
            ) ?: continue
            val lead = leadIn(anchor, result.startNode)
            return assemble(
                nodePath = result.nodes, edgePath = result.edges, request = request,
                startNodes = startNodes, goalNode = goal.node,
                leadIn = lead.geometry, leadInName = lead.name,
                leadInCrossingEdges = lead.crossingEdges,
                snapMeters = snapMeters, goalMeters = goal.distanceMeters,
                holdShortCrossingEdge = goal.holdShortCrossingEdge,
            )
        }
        return null
    }

    /**
     * The plain node snap used to recover when an edge snap reaches no goal: the nearest
     * connected node, or failing that the nearest node of any kind.
     */
    private fun nodeAnchorFallback(coord: Coordinate): StartAnchor? {
        val connected = nearestConnectedNode(coord)
        if (connected != null) {
            return StartAnchor.Node(connected.node.id, connected.distanceMeters, headingApplies = true)
        }
        val nearest = graph.nearestNode(coord) ?: return null
        return StartAnchor.Node(nearest.node.id, nearest.distanceMeters, headingApplies = true)
    }

    // MARK: - Endpoint resolution

    /**
     * Where a route begins on the graph. A [StartAnchor.Node] start anchors the first leg
     * exactly at a graph node (parked at the stand, or the aircraft sitting essentially on top
     * of a node); a [StartAnchor.Edge] start places the aircraft partway along a connected
     * edge so the route can begin *under the aircraft* and join the network at whichever
     * endpoint routes best.
     */
    private sealed interface StartAnchor {
        /**
         * [headingApplies] is true when the node is the aircraft's live position under way (so
         * the first edge should respect its heading) and false when it's the parked stand
         * (whose orientation isn't the taxi direction).
         */
        data class Node(val id: Int, val distanceMeters: Double, val headingApplies: Boolean) : StartAnchor

        /**
         * [projection] is the point on [edgeIndex] nearest the aircraft; [alongFromFrom] is
         * the along-edge distance (m) from `edge.from` to it; [perpMeters] is the aircraft's
         * perpendicular offset from the edge.
         */
        data class Edge(
            val edgeIndex: Int,
            val projection: GeoCoordinate,
            val alongFromFrom: Double,
            val perpMeters: Double,
        ) : StartAnchor
    }

    private fun resolveStart(req: Request): StartAnchor? {
        val gateName = req.startGateName
        if (req.isDeparture && gateName != null && gateName.isNotEmpty()) {
            val target = standNameSought(gateName)
            val node = graph.nodes.firstOrNull {
                (it.kind == SurfaceNodeKind.GATE || it.kind == SurfaceNodeKind.PARKING) &&
                    it.name?.uppercase() == target
            }
            if (target != null && node != null) {
                val d = SurfaceGeometry.distanceMeters(req.startCoordinate, node.location)
                // Anchor at the stand only while the aircraft is still parked there. After
                // pushback it has moved off the gate, so fall through to snap the route to
                // its real position instead of drawing a leg it has already taxied past.
                // Heading doesn't apply at the stand — the parked orientation isn't the direction
                // the aircraft will taxi once pushed back.
                if (d <= gateAnchorMeters) {
                    return StartAnchor.Node(node.id, d, headingApplies = false)
                }
            }
        }
        // Snap onto the nearest connected *edge* and begin the route at the aircraft's
        // projected position along it, rather than jumping to the nearest node. This is what
        // fixes the "route starts a taxiway away after landing" bug: with diagonal high-speed
        // exits the nearest node is often the exit's far end (out on the parallel taxiway) or a
        // junction the aircraft has already passed, so a node snap draws the first leg away
        // from where the aircraft actually is — and recalculating from a nearby position
        // resolves to the same node, so the route never moves. Projecting onto the edge tracks
        // the aircraft continuously. When the projection lands essentially on an endpoint fall
        // back to a plain node snap (below) so a route that really does begin at a junction
        // stays clean.
        val snap = nearestConnectedEdge(req.startCoordinate)
        if (snap != null) {
            val e = graph.edges[snap.edgeIndex]
            val nearEnd = snap.alongFromFrom <= endpointSnapMeters ||
                snap.alongFromFrom >= e.distanceMeters - endpointSnapMeters
            if (!nearEnd) {
                return StartAnchor.Edge(snap.edgeIndex, snap.projection, snap.alongFromFrom, snap.perpMeters)
            }
        }
        // Snap onto a node that actually participates in the routable network (has an incident
        // edge). `graph.nearestNode` scans every node, including display-only runway-crossing
        // markers and isolated stubs kept out of the adjacency; snapping the start onto one of
        // those strands the whole route (A* reaches nothing). Prefer the nearest connected
        // node; fall back to the nearest node only when the graph has no connected nodes at all.
        val connected = nearestConnectedNode(req.startCoordinate)
        if (connected != null) {
            return StartAnchor.Node(connected.node.id, connected.distanceMeters, headingApplies = true)
        }
        val nearest = graph.nearestNode(req.startCoordinate) ?: return null
        return StartAnchor.Node(nearest.node.id, nearest.distanceMeters, headingApplies = true)
    }

    /** Nearest node with at least one incident edge — i.e. one the router can actually leave. */
    private fun nearestConnectedNode(coord: Coordinate): SurfaceGraph.NodeHit? {
        var best: SurfaceGraph.NodeHit? = null
        for (n in graph.nodes) {
            if (graph.adjacency[n.id].isNullOrEmpty()) continue
            val d = SurfaceGeometry.distanceMeters(coord, n.location)
            if (best == null || d < best!!.distanceMeters) best = SurfaceGraph.NodeHit(n, d)
        }
        return best
    }

    private data class EdgeSnap(
        val edgeIndex: Int,
        val projection: GeoCoordinate,
        val alongFromFrom: Double,
        val perpMeters: Double,
    )

    /**
     * The routable edge whose geometry passes nearest [coord], with the projected point, the
     * along-edge distance (m) from `edge.from` to it, and the perpendicular offset. Closed
     * segments are skipped — the aircraft can't taxi onto one. Every edge is incident to two
     * nodes, so any edge returned is one the router can traverse from either end.
     */
    private fun nearestConnectedEdge(coord: Coordinate): EdgeSnap? {
        var best: EdgeSnap? = null
        for (idx in graph.edges.indices) {
            val e = graph.edges[idx]
            if (e.closed) continue
            val line = e.line
            if (line.size < 2) continue
            val proj = SurfaceGeometry.nearestPointOnPath(coord, line) ?: continue
            if (best == null || proj.distanceMeters < best!!.perpMeters) {
                best = EdgeSnap(idx, GeoCoordinate(proj.point), proj.alongMeters, proj.distanceMeters)
            }
        }
        return best
    }

    /**
     * The lead-in that carries the route from the aircraft's projected position up to the node
     * A* actually started from: its polyline (oriented projection→startNode, so it prepends
     * cleanly), the snap edge's taxiway name (so the segment the aircraft is already on still
     * appears in the spoken sequence), and — when the snap edge crosses a runway within the
     * still-to-be-taxied portion — that edge's id, so the crossing/hold-short isn't lost. All
     * empty for a node start.
     */
    private fun leadIn(anchor: StartAnchor, startNode: Int): LeadIn {
        if (anchor !is StartAnchor.Edge) return LeadIn(emptyList(), null, emptyList())
        val e = graph.edges[anchor.edgeIndex]
        val geo = e.geometry
        if (geo.size < 2) return LeadIn(emptyList(), null, emptyList())

        // Cumulative along-edge distance (from e.from) to each vertex.
        val cumulative = mutableListOf(0.0)
        for (i in 1 until geo.size) {
            cumulative.add(
                cumulative[i - 1] +
                    SurfaceGeometry.distanceMeters(geo[i - 1].toCoordinate(), geo[i].toCoordinate()),
            )
        }

        val out = mutableListOf(anchor.projection)
        if (startNode == e.to) {
            for (i in geo.indices) {
                if (cumulative[i] > anchor.alongFromFrom + 0.5) out.add(geo[i])
            }
            if (out.last() != geo.last()) out.add(geo[geo.size - 1])
        } else { // startNode == e.from
            for (i in geo.indices.reversed()) {
                if (cumulative[i] < anchor.alongFromFrom - 0.5) out.add(geo[i])
            }
            if (out.last() != geo.first()) out.add(geo[0])
        }
        if (out.size < 2) return LeadIn(emptyList(), null, emptyList())

        // If the snap edge crosses a runway, keep the crossing only when its centerline point
        // lies *ahead* of the aircraft along the direction it will taxi (from the projection
        // toward the node it heads to). A crossing at or behind the projection has already been
        // passed — the aircraft is exiting across it, not approaching it — so reporting a
        // hold-short there would be spurious.
        val crossingEdges = mutableListOf<Int>()
        val startAlong = if (startNode == e.to) e.distanceMeters else 0.0
        val cp = e.crossingPoint
        if (e.runwayCrossing != null && cp != null) {
            val cpAlong = SurfaceGeometry.nearestPointOnPath(cp.toCoordinate(), e.line)?.alongMeters
            if (cpAlong != null) {
                // Signed distance from the projection to the crossing, positive when ahead.
                val ahead = if (startAlong >= anchor.alongFromFrom) {
                    cpAlong - anchor.alongFromFrom
                } else {
                    anchor.alongFromFrom - cpAlong
                }
                if (ahead > 5) crossingEdges.add(anchor.edgeIndex)
            }
        }
        return LeadIn(out.toList(), e.taxiwayName.ifEmpty { null }, crossingEdges)
    }

    /** Where a route is allowed to end. */
    private data class GoalCandidate(
        /** The graph node A* routes to. */
        val node: Int,
        /**
         * Distance (m) from the ideal hold point to [node], for confidence grading. Zero when
         * the node *is* a mapped hold / entry for the assigned end.
         */
        val distanceMeters: Double,
        /**
         * When set, the route does not stop at [node]: it rolls on out along this
         * runway-crossing edge and stops at the hold-short of the assigned runway on it. Used
         * when the assigned end has no mapped hold on the aircraft's side of the runway (see
         * `crossingHoldGoals`). The crossing edge itself is never taxied across.
         */
        val holdShortCrossingEdge: Int? = null,
    )

    private data class RawGoal(val node: Int, val distanceMeters: Double)

    /**
     * Goal candidates for the route, best first, so `route` can fall through to the next one
     * when the top choice is unreachable (stranded in a disconnected graph patch). Departure:
     * full-length runway-entry node(s) for the assigned end — nearest the threshold first —
     * then holding positions for that end (an intersection departure), then plain taxi nodes
     * near the runway-end threshold as a last resort; the whole list is then re-ordered so
     * candidates on the aircraft's side of the runway come first, with hold-short-at-a-crossing
     * goals ahead of anything stranded on the far side (see `crossingHoldGoals`).
     * Runway-ident matching is tolerant of leading-zero padding, so an assigned "9L" matches
     * OSM-tagged "09L". Arrival: the named gate, else the nearest parking/gate to the airport
     * reference (a single choice, as before).
     */
    private fun resolveGoalCandidates(req: Request): List<GoalCandidate> {
        if (req.isDeparture) {
            val ident = req.assignedRunwayIdent
            if (ident == null || ident.isEmpty()) return emptyList()
            val key = runwayKey(ident)
            val assignedEnd = model.runwayEnds.firstOrNull { runwayKey(it.ident) == key }
            val threshold = assignedEnd?.threshold?.toCoordinate()
            val opposite = assignedEnd?.oppositeThreshold?.toCoordinate()

            fun distanceToThreshold(node: SurfaceNode): Double {
                if (threshold == null) return 0.0
                return SurfaceGeometry.distanceMeters(threshold, node.location)
            }

            fun matchesRunway(node: SurfaceNode): Boolean {
                val ref = node.runwayRef ?: return false
                return runwayKey(ref) == key
            }

            // Reject a candidate that sits on the *opposite* half of the runway — a guard
            // against a wrong-end goal reaching the router from ambiguous OSM tagging (e.g. a
            // runway split across ways, or a mistagged hold). A node closer to the opposite
            // threshold than to the assigned one is on the wrong side, so a "24L" departure
            // can never be sent to the "06R" end.
            fun onAssignedHalf(node: SurfaceNode): Boolean {
                if (threshold == null || opposite == null) return true
                return SurfaceGeometry.distanceMeters(threshold, node.location) <=
                    SurfaceGeometry.distanceMeters(opposite, node.location)
            }

            val out = mutableListOf<RawGoal>()
            val seen = mutableSetOf<Int>()
            fun add(id: Int, distance: Double) {
                if (seen.add(id)) out.add(RawGoal(id, distance))
            }

            // 1) Full-length runway-entry nodes for the assigned end.
            for (node in graph.nodes
                .filter { it.kind == SurfaceNodeKind.RUNWAY_ENTRY && matchesRunway(it) && onAssignedHalf(it) }
                .sortedBy { distanceToThreshold(it) }) {
                add(node.id, 0.0)
            }
            // 2) Holding positions for the assigned end (intersection departure).
            for (node in graph.nodes
                .filter { it.kind == SurfaceNodeKind.HOLDING_POSITION && matchesRunway(it) && onAssignedHalf(it) }
                .sortedBy { distanceToThreshold(it) }) {
                add(node.id, 0.0)
            }
            // 3) Last resort: taxi nodes near the runway-end threshold (the ident may be
            //    untagged on any node), nearest first.
            if (threshold != null) {
                val taxiKinds = setOf(
                    SurfaceNodeKind.TAXIWAY_ENDPOINT, SurfaceNodeKind.INTERSECTION,
                    SurfaceNodeKind.RUNWAY_ENTRY, SurfaceNodeKind.HOLDING_POSITION,
                )
                val near = mutableListOf<RawGoal>()
                for (node in graph.nodes) {
                    if (!taxiKinds.contains(node.kind)) continue
                    val distance = SurfaceGeometry.distanceMeters(threshold, node.location)
                    if (distance <= goalThresholdFallbackMeters) near.add(RawGoal(node.id, distance))
                }
                for (candidate in near.sortedBy { it.distanceMeters }) {
                    add(candidate.node, candidate.distanceMeters)
                }
            }
            return prioritizingAircraftSide(out, assignedEnd, req.startCoordinate)
        }

        // Arrival goals, best first, so `route` can fall through to the next when the top
        // choice is stranded in a disconnected patch of the OSM graph — at a big field like
        // KMSP the named stand may attach to a taxiway component the runway-exit start can't
        // reach, which used to fail the whole arrival route (there was only ever one
        // candidate) and, in the mock demo, revert the map to the synthetic field. Mirrors
        // the multi-candidate resilience the departure goals already have: the entered gate
        // first, then other stands on the same concourse (same leading letter), then every
        // remaining stand — each tier nearest the aircraft's rollout start first — so the
        // arrival always lands at a reachable *real* stand rather than giving up.
        val stands = graph.nodes.filter {
            it.kind == SurfaceNodeKind.GATE || it.kind == SurfaceNodeKind.PARKING
        }
        if (stands.isEmpty()) return emptyList()
        val gate = (req.arrivalGateName ?: "").trim { it == ' ' || it == '\t' }
        val target = standNameSought(gate)
        val letter = gate.takeWhile { it.isLetter() }.uppercase()

        fun distanceToStart(node: SurfaceNode): Double =
            SurfaceGeometry.distanceMeters(req.startCoordinate, node.location)

        val out = mutableListOf<RawGoal>()
        val seen = mutableSetOf<Int>()
        fun add(id: Int, distance: Double) {
            if (seen.add(id)) out.add(RawGoal(id, distance))
        }

        // 1) The exact named stand.
        if (target != null) {
            for (node in stands) {
                if (node.name?.uppercase() == target) add(node.id, 0.0)
            }
            // 2) Other stands on the same concourse (same leading letter), nearest first.
            if (letter.isNotEmpty()) {
                for (node in stands
                    .filter { it.name?.uppercase()?.startsWith(letter) ?: false }
                    .sortedBy { distanceToStart(it) }) {
                    add(node.id, 0.0)
                }
            }
        }
        // 3) Every remaining stand, nearest the rollout start first.
        for (node in stands.sortedBy { distanceToStart(it) }) {
            add(node.id, 0.0)
        }
        return out.map { GoalCandidate(it.node, it.distanceMeters) }
    }

    /**
     * Re-orders departure goals so the aircraft never taxis the length of the field and around
     * a runway end to reach a hold tagged on the *far* side. Candidates on the aircraft's side
     * of the runway keep their existing priority and come first; then — when the far side holds
     * anything at all — hold-short goals at the taxiways that cross the runway toward the
     * assigned end; then the far-side candidates, still available if nothing nearer routes.
     *
     * Left unchanged when the runway end is unknown, when the aircraft sits essentially on the
     * runway centerline (its side can't be told), or when no candidate is on the far side.
     */
    private fun prioritizingAircraftSide(
        candidates: List<RawGoal>,
        assignedEnd: SurfaceRunwayEnd?,
        start: Coordinate,
    ): List<GoalCandidate> {
        fun plain(list: List<RawGoal>) = list.map { GoalCandidate(it.node, it.distanceMeters) }
        if (assignedEnd == null) return plain(candidates)
        val startOffset = signedOffsetFromRunway(start, assignedEnd)
        if (abs(startOffset) <= runwaySideToleranceMeters) return plain(candidates)
        val side: Double = if (startOffset > 0) 1.0 else -1.0

        fun isFarSide(nodeID: Int): Boolean {
            if (nodeID !in graph.nodes.indices) return false
            return signedOffsetFromRunway(nodeCoord(nodeID), assignedEnd) * side < -runwaySideToleranceMeters
        }
        val farSide = candidates.filter { isFarSide(it.node) }
        if (farSide.isEmpty()) return plain(candidates)
        val nearSide = candidates.filter { !isFarSide(it.node) }
        // A crossing whose near endpoint is already a candidate adds nothing — that node is
        // tried first anyway — so it is dropped rather than probed twice.
        val nearSideNodes = nearSide.map { it.node }.toSet()
        val crossingGoals = crossingHoldGoals(assignedEnd, side).filter { !nearSideNodes.contains(it.node) }
        return plain(nearSide) + crossingGoals + plain(farSide)
    }

    /**
     * Hold-short goals at the taxiways that cross the assigned runway, nearest its threshold
     * first — the near-side endpoint of each crossing taxiway, carrying the crossing edge so
     * the route can roll on out along it and stop short of the runway (see [holdShortStub]).
     * Only crossings on the assigned half of the runway qualify: holding short at one past
     * midfield would put the aircraft on the runway with too little of it left to depart from.
     */
    private fun crossingHoldGoals(assignedEnd: SurfaceRunwayEnd, side: Double): List<GoalCandidate> {
        val keys = assignedRunwayKeys(assignedEnd.ident)
        val threshold = assignedEnd.threshold.toCoordinate()
        val opposite = assignedEnd.oppositeThreshold.toCoordinate()
        val found = mutableListOf<Pair<GoalCandidate, Double>>()
        for (idx in graph.edges.indices) {
            val e = graph.edges[idx]
            if (e.closed) continue
            val ident = e.runwayCrossing ?: continue
            val crossingPoint = e.crossingPoint ?: continue
            if (!keys.contains(runwayKey(ident))) continue
            val toThreshold = SurfaceGeometry.distanceMeters(crossingPoint.toCoordinate(), threshold)
            if (toThreshold > SurfaceGeometry.distanceMeters(crossingPoint.toCoordinate(), opposite)) continue
            val nearNode = nearSideEndpoint(e, assignedEnd, side) ?: continue
            if (holdShortStub(idx, nearNode) == null) continue
            found.add(GoalCandidate(nearNode, 0.0, holdShortCrossingEdge = idx) to toThreshold)
        }
        return found.sortedBy { it.second }.map { it.first }
    }

    /**
     * The endpoint of a runway-crossing edge that lies on [side] of the runway while the other
     * endpoint lies across it — i.e. the node the aircraft can reach without crossing. Null when
     * the edge doesn't straddle the runway cleanly (both or neither endpoint on that side), so
     * an ambiguous crossing never becomes a hold.
     */
    private fun nearSideEndpoint(edge: SurfaceEdge, end: SurfaceRunwayEnd, side: Double): Int? {
        if (edge.from !in graph.nodes.indices || edge.to !in graph.nodes.indices) return null
        val fromNear = signedOffsetFromRunway(nodeCoord(edge.from), end) * side > runwaySideToleranceMeters
        val toNear = signedOffsetFromRunway(nodeCoord(edge.to), end) * side > runwaySideToleranceMeters
        if (fromNear && !toNear) return edge.from
        if (toNear && !fromNear) return edge.to
        return null
    }

    /**
     * Signed perpendicular offset (m) of [p] from the runway centerline extended infinitely
     * through both thresholds — positive on one side, negative on the other. Only the sign
     * carries meaning; the magnitude exists so [runwaySideToleranceMeters] can dismiss points
     * that are on the pavement, or off the end of the runway, as belonging to neither side.
     */
    private fun signedOffsetFromRunway(p: Coordinate, end: SurfaceRunwayEnd): Double {
        val a = end.threshold.toCoordinate()
        val b = end.oppositeThreshold.toCoordinate()
        // Longitude scaled by cos(lat) so the two axes share a unit, as elsewhere in the
        // surface layer's planar math.
        val cosLat = max(0.2, cos(a.latitude * PI / 180))
        val ax = a.longitude * cosLat; val ay = a.latitude
        val bx = b.longitude * cosLat; val by = b.latitude
        val px = p.longitude * cosLat; val py = p.latitude
        val dx = bx - ax; val dy = by - ay
        val length = sqrt(dx * dx + dy * dy)
        if (length <= 1e-12) return 0.0
        return ((dx * (py - ay) - dy * (px - ax)) / length) * metersPerDegree
    }

    /**
     * Canonical comparison key for a runway ident, tolerant of leading-zero padding and case,
     * so an assigned "9L" matches OSM-tagged "09L" (and "8" matches "08"). The leading number
     * collapses to its integer value; any L/C/R designator is preserved. An ident with no
     * leading number falls back to its trimmed, uppercased form.
     */
    private fun runwayKey(raw: String): String {
        val s = raw.trim { it == ' ' || it == '\t' }.uppercase()
        val digits = s.takeWhile { it.isDigit() }
        val n = digits.toIntOrNull() ?: return s
        return "$n${s.drop(digits.length)}"
    }

    // MARK: - A*

    private fun nodeCoord(id: Int): Coordinate = graph.nodes[id].location

    private data class AStarResult(val nodes: List<Int>, val edges: List<Int>, val startNode: Int)

    /**
     * Multi-source A*: [starts] seeds one or more entry nodes, each with an initial cost (the
     * aircraft's along-edge distance to that node when it snapped mid-edge). Returns the path
     * to [goal] and the entry node it actually left from, so the caller can prepend the matching
     * lead-in.
     */
    private fun astar(
        starts: List<Seed>,
        goal: Int,
        aircraft: AircraftSizeClass,
        heading: Double?,
        headingStartNodes: Set<Int>,
    ): AStarResult? {
        val gScore = mutableMapOf<Int, Double>()
        val cameFrom = mutableMapOf<Int, Pair<Int, Int>>() // node -> (previous node, edge)
        val arrivedBy = mutableMapOf<Int, Int>()
        val closed = mutableSetOf<Int>()
        val startNodes = starts.map { it.node }.toSet()
        val heap = MinHeap()
        for (s in starts) {
            if (s.cost >= (gScore[s.node] ?: Double.POSITIVE_INFINITY)) continue
            gScore[s.node] = s.cost
            heap.push(s.cost + heuristic(s.node, goal), s.node)
        }

        while (true) {
            val popped = heap.pop() ?: break
            val u = popped.node
            if (u == goal) break
            if (closed.contains(u)) continue
            closed.add(u)
            val gu = gScore[u] ?: Double.POSITIVE_INFINITY
            val incoming = arrivedBy[u]
            for (edgeIdx in graph.adjacency[u] ?: emptyList()) {
                val e = graph.edges[edgeIdx]
                val v: Int = when {
                    e.from == u -> e.to
                    e.to == u && !e.oneway -> e.from
                    else -> continue
                }
                if (closed.contains(v)) continue
                val cost = edgeCost(
                    e, u, incoming, startNodes, goal, aircraft, heading, headingStartNodes,
                )
                if (!cost.isFinite()) continue // prohibited
                val tentative = gu + cost
                if (tentative < (gScore[v] ?: Double.POSITIVE_INFINITY)) {
                    gScore[v] = tentative
                    cameFrom[v] = u to edgeIdx
                    arrivedBy[v] = edgeIdx
                    heap.push(tentative + heuristic(v, goal), v)
                }
            }
        }

        if (gScore[goal] == null) return null
        val nodePath = mutableListOf(goal)
        val edgePath = mutableListOf<Int>()
        var cur = goal
        while (!startNodes.contains(cur)) {
            val step = cameFrom[cur] ?: break
            edgePath.add(step.second)
            nodePath.add(step.first)
            cur = step.first
        }
        if (!startNodes.contains(cur)) return null
        nodePath.reverse(); edgePath.reverse()
        return AStarResult(nodePath.toList(), edgePath.toList(), cur)
    }

    private fun heuristic(a: Int, b: Int): Double =
        SurfaceGeometry.distanceMeters(nodeCoord(a), nodeCoord(b))

    private fun edgeCost(
        e: SurfaceEdge,
        u: Int,
        incomingEdge: Int?,
        startNodes: Set<Int>,
        goal: Int,
        aircraft: AircraftSizeClass,
        heading: Double?,
        headingStartNodes: Set<Int>,
    ): Double {
        if (e.closed) return Double.POSITIVE_INFINITY
        // Never taxi onto a runway surface lengthwise (entry / back-taxi / occupancy).
        // A crossing edge is allowed (heavily penalized); a runway-entry edge is not.
        if (e.runwayOccupancy && e.runwayCrossing == null) return Double.POSITIVE_INFINITY

        var cost = max(e.distanceMeters, 1.0)
        if (e.runwayCrossing != null) cost += crossingPenalty
        if (e.crossesBuilding) cost += buildingCrossingPenalty
        val touchesEndpoint = startNodes.contains(e.from) || startNodes.contains(e.to) ||
            e.from == goal || e.to == goal
        if (e.inferred && !touchesEndpoint) cost += inferredPenalty
        if (!e.hasName && !e.inferred) cost += unnamedPenalty
        if (e.isTaxilane) cost += taxilanePenalty
        val w = e.widthMeters
        if (w != null && w > 0 && w < aircraft.minComfortableTaxiwayWidthMeters) cost += widthPenalty
        if (e.isTaxilane && !aircraft.acceptsTaxilanes) cost += widthPenalty
        if (e.confidence < 0.4) cost += lowConfidencePenalty
        if (incomingEdge != null) {
            cost += turnPenalty(incomingEdge, e, u)
        } else if (heading != null && headingStartNodes.contains(u)) {
            // The very first edge off the aircraft's live position: discourage leaving in a
            // direction that reverses its heading, so the route sets off the way the aircraft
            // is pointing rather than making it spin around where it sits.
            val other = if (e.from == u) e.to else e.from
            val outBearing = Geo.bearing(nodeCoord(u), nodeCoord(other))
            if (Geo.headingDifference(heading, outBearing) > reverseHeadingThreshold) cost += uTurnPenalty
        }
        return cost
    }

    private fun turnPenalty(incoming: Int, e: SurfaceEdge, u: Int): Double {
        if (incoming !in graph.edges.indices) return 0.0
        val pe = graph.edges[incoming]
        val prevNode = if (pe.from == u) pe.to else pe.from
        val nextNode = if (e.from == u) e.to else e.from
        if (prevNode == u || nextNode == u) return 0.0
        if (prevNode !in graph.nodes.indices || nextNode !in graph.nodes.indices) return 0.0
        val inB = Geo.bearing(nodeCoord(prevNode), nodeCoord(u))
        val outB = Geo.bearing(nodeCoord(u), nodeCoord(nextNode))
        val turn = Geo.headingDifference(inB, outB)
        if (turn > 120) return sharpTurnPenalty
        if (turn > 95) return moderateTurnPenalty
        // Every ordinary turn costs a little, so a route that reaches the destination with
        // fewer turns (one left, one right) beats one that steps down through many small
        // sequential turns of otherwise-similar length.
        if (turn > minTurnDegrees) return perTurnPenalty
        return 0.0
    }

    // MARK: - Assembly + confidence

    private fun assemble(
        nodePath: List<Int>,
        edgePath: List<Int>,
        request: Request,
        startNodes: Set<Int>,
        goalNode: Int,
        leadIn: List<GeoCoordinate>,
        leadInName: String?,
        leadInCrossingEdges: List<Int>,
        snapMeters: Double,
        goalMeters: Double,
        holdShortCrossingEdge: Int? = null,
    ): SurfaceTaxiRoute {
        // Oriented geometry + taxiway sequence.
        var geometry = mutableListOf<GeoCoordinate>()
        var taxiSeq = mutableListOf<String>()
        var unnamed = 0
        var midInferred = false
        var crossesBuilding = false
        for ((i, edgeIdx) in edgePath.withIndex()) {
            val e = graph.edges[edgeIdx]
            val fromNode = nodePath[i]
            val oriented = if (e.from == fromNode) e.geometry else e.geometry.reversed()
            if (geometry.isEmpty()) geometry.addAll(oriented) else geometry.addAll(oriented.drop(1))
            if (e.taxiwayName.isNotEmpty() && taxiSeq.lastOrNull() != e.taxiwayName) {
                taxiSeq.add(e.taxiwayName)
            }
            if (!e.hasName && !e.inferred) unnamed += 1
            if (e.inferred && !(
                    startNodes.contains(e.from) || startNodes.contains(e.to) ||
                        e.from == goalNode || e.to == goalNode
                    )
            ) {
                midInferred = true
            }
            if (e.crossesBuilding) crossesBuilding = true
        }

        // Prepend the lead-in from the aircraft's projected position up to the node the route
        // leaves from, so the drawn route begins under the aircraft (on the diagonal exit /
        // taxiway it is actually on) instead of at that node a taxiway away. Its trailing point
        // is the start node, which the routed geometry already opens with — drop the duplicate.
        if (leadIn.size >= 2) {
            val first = geometry.firstOrNull()
            geometry = if (first != null && leadIn.last() == first) {
                (leadIn + geometry.drop(1)).toMutableList()
            } else {
                (leadIn + geometry).toMutableList()
            }
            if (leadInName != null && taxiSeq.firstOrNull() != leadInName) taxiSeq.add(0, leadInName)
        }

        // The assigned end has no mapped hold on this side of the runway, so rather than taxi
        // around to one on the far side the route ends by rolling out along the taxiway that
        // crosses the runway toward that end and stopping short of it — that crossing threshold
        // *is* the departure hold. Only the geometry (and the taxiway's name) extends; the
        // crossing edge is never taxied across, so it stays out of nodeIDs/edgeIDs and is not
        // reported below as a crossing.
        var holdShortAtCrossing = false
        if (holdShortCrossingEdge != null) {
            val stub = holdShortStub(holdShortCrossingEdge, goalNode)
            if (stub != null) {
                val last = geometry.lastOrNull()
                if (geometry.isEmpty()) {
                    geometry = stub.toMutableList()
                } else if (last != null &&
                    SurfaceGeometry.distanceMeters(last.toCoordinate(), stub[0].toCoordinate()) < 0.5
                ) {
                    geometry.addAll(stub.drop(1)) // the goal node, already there
                } else {
                    geometry.addAll(stub)
                }
                val name = graph.edges[holdShortCrossingEdge].taxiwayName
                if (name.isNotEmpty() && taxiSeq.lastOrNull() != name) taxiSeq.add(name)
                holdShortAtCrossing = true
            }
        }

        val fullLine = geometry.toCoordinates()
        // Runway crossings along the route — the routed edges, plus any crossing carried by the
        // lead-in edge whose crossed portion the aircraft still has to taxi over (so a mid-edge
        // start never silently drops a hold-short of a runway ahead).
        var crossings = mutableListOf<RouteCrossing>()
        for (edgeIdx in edgePath + leadInCrossingEdges) {
            val e = graph.edges[edgeIdx]
            val cp = e.crossingPoint ?: continue
            val ident = e.runwayCrossing ?: continue
            val along = SurfaceGeometry.nearestPointOnPath(cp.toCoordinate(), fullLine)?.alongMeters ?: 0.0
            val holdShort = SurfaceGeometry.pointAlong(fullLine, max(0.0, along - holdShortLeadMeters))
                ?.let { GeoCoordinate(it) } ?: cp
            crossings.add(
                RouteCrossing(
                    index = crossings.size,
                    runwayIdent = ident,
                    runwayName = e.runwayCrossingName ?: ident,
                    point = cp,
                    holdShortPoint = holdShort,
                    alongMeters = along,
                    edgeID = edgeIdx,
                    confidence = crossingConfidence(cp, e.hasName),
                ),
            )
        }
        crossings.sortBy { it.alongMeters }
        for (i in crossings.indices) crossings[i].index = i

        // A departure never crosses its own runway. When A* could only reach a hold/entry
        // tagged on the *far* side by crossing the assigned runway, stop at the near-side
        // hold-short of that crossing — that point *is* the departure hold — and drop the
        // crossing (and anything beyond it) from the route. Without this the drawn route ran
        // across the active departure runway and placed the hold on the far side.
        var routeNodes = nodePath
        var routeEdges = edgePath
        val assignedIdent = request.assignedRunwayIdent
        if (request.isDeparture && assignedIdent != null && assignedIdent.isNotEmpty()) {
            val assignedKeys = assignedRunwayKeys(assignedIdent)
            val depCross = crossings.firstOrNull { assignedKeys.contains(runwayKey(it.runwayIdent)) }
            if (depCross != null) {
                val cut = max(0.0, depCross.alongMeters - holdShortLeadMeters)
                geometry = truncatedPolyline(geometry, cut).toMutableList()
                // Keep only crossings that remain on the shortened route (other runways crossed
                // before the departure runway); drop the departure crossing and any beyond it.
                crossings = crossings.filter { it.alongMeters < cut }.toMutableList()
                for (i in crossings.indices) crossings[i].index = i
                val leadInPrefix = if (leadIn.size >= 2) leadInName else null
                // Trim the node/edge path so it stays contiguous and ends on the near side, and
                // rebuild the spoken taxiway sequence so it names the taxiways actually taxied up
                // to the hold — including the crossing edge (the pilot rolls onto it to hold
                // short) but not the dropped far-side taxiways beyond the runway.
                val ci = routeEdges.indexOf(depCross.edgeID)
                if (ci >= 0) {
                    taxiSeq = namedTaxiwaySequence(edgePath.take(ci + 1), leadInPrefix).toMutableList()
                    routeNodes = routeNodes.take(ci + 1)
                    routeEdges = routeEdges.take(ci)
                } else {
                    // The crossing rode in on the lead-in edge (the aircraft is already on the
                    // runway's approach), so the route holds short at once with no routed edges.
                    taxiSeq = namedTaxiwaySequence(emptyList(), leadInPrefix).toMutableList()
                    routeNodes = routeNodes.take(1)
                    routeEdges = emptyList()
                }
            }
        }

        val distance = SurfaceGeometry.pathLengthMeters(geometry.toCoordinates())
        val namedFraction = if (routeEdges.isEmpty()) {
            0.0
        } else {
            routeEdges.count { graph.edges[it].hasName || graph.edges[it].inferred }.toDouble() /
                routeEdges.size.toDouble()
        }

        // A hold-short at a crossing was chosen *because* it belongs to the assigned runway on
        // the assigned half, so the end is confirmed even though the node carries no ident.
        val goalCorrectEnd = if (request.isDeparture) {
            holdShortAtCrossing ||
                graph.node(goalNode)?.runwayRef?.uppercase() == request.assignedRunwayIdent?.uppercase()
        } else {
            true
        }

        val graded = gradeConfidence(
            namedFraction = namedFraction, snapMeters = snapMeters, goalMeters = goalMeters,
            midInferred = midInferred, crossesBuilding = crossesBuilding,
            crossings = crossings, goalCorrectEnd = goalCorrectEnd,
        )
        val confidence = graded.confidence
        val score = graded.score
        val notes = graded.notes.toMutableList()
        if (holdShortAtCrossing) {
            notes.add("holding short at the taxiway crossing — no mapped hold on this side of the runway")
        }

        val arrivalGate = if (request.isDeparture) null else arrivalGateName(goalNode, request)
        val destinationLabel: String = if (request.isDeparture) {
            "runway ${request.assignedRunwayIdent ?: ""}"
        } else {
            val g = arrivalGate ?: ""
            if (g.isEmpty()) "parking" else "gate $g"
        }

        return SurfaceTaxiRoute(
            isDeparture = request.isDeparture,
            nodeIDs = routeNodes,
            edgeIDs = routeEdges,
            geometry = geometry.toList(),
            distanceMeters = distance,
            taxiwaySequence = taxiSeq.toList(),
            crossings = crossings.toList(),
            confidence = confidence,
            confidenceScore = score,
            destinationLabel = destinationLabel,
            holdShortRunway = if (request.isDeparture) request.assignedRunwayIdent else null,
            arrivalGate = arrivalGate,
            startCoordinate = geometry.firstOrNull() ?: GeoCoordinate(request.startCoordinate),
            endCoordinate = geometry.lastOrNull() ?: GeoCoordinate(nodeCoord(goalNode)),
            usedInferredConnectorMidRoute = midInferred,
            unnamedSegmentCount = unnamed,
            notes = notes.toList(),
        )
    }

    /**
     * The graph name to look for when the pilot enters a gate, upper-cased for comparison:
     * the stand's own name when the entry is one of its *other* identifiers (a pilot filing
     * "A2" means the stand tagged `A1;A2`, which the graph knows as "A1"), else the entry
     * itself so an unmapped gate behaves exactly as before. Null for a blank entry.
     */
    private fun standNameSought(entered: String): String? {
        val key = entered.trim { it == ' ' || it == '\t' }
        if (key.isEmpty()) return null
        return (model.parking(key)?.name ?: key).uppercase()
    }

    /**
     * What to call the stand an arrival route ends at.
     *
     * The stand as the map names it, except when the pilot's own entry names that same
     * stand under another of its identifiers: a stand tagged `A1;A2` is named "A1", but a
     * pilot who filed "A2" is going to the same place and should be cleared to the gate
     * they filed. Falls back to the filed name when the goal stand is unnamed, and to null
     * when there is neither — the caller then says "parking".
     */
    private fun arrivalGateName(goalNode: Int, request: Request): String? {
        val mapped = graph.node(goalNode)?.name
        val filed = (request.arrivalGateName ?: "").trim { it == ' ' || it == '\t' }
        if (mapped == null || mapped.isEmpty()) return filed.ifEmpty { null }
        if (filed.isNotEmpty() && model.parking(filed)?.name == mapped) return filed
        return mapped
    }

    /**
     * The de-duplicated named taxiway sequence for a list of routed edges, with the lead-in
     * taxiway name (when the route began on a snapped edge) prefixed. Used to rebuild the spoken
     * sequence after a departure route is shortened to hold short of its own runway, so the
     * dropped far-side taxiways are no longer named.
     */
    private fun namedTaxiwaySequence(edges: List<Int>, leadInName: String?): List<String> {
        val seq = mutableListOf<String>()
        for (idx in edges) {
            if (idx !in graph.edges.indices) continue
            val name = graph.edges[idx].taxiwayName
            if (name.isNotEmpty() && seq.lastOrNull() != name) seq.add(name)
        }
        if (leadInName != null && leadInName.isNotEmpty() && seq.firstOrNull() != leadInName) {
            seq.add(0, leadInName)
        }
        return seq
    }

    /**
     * The stretch of a runway-crossing taxiway from its near-side endpoint [node] up to the
     * hold-short a short distance before the runway centerline, oriented away from [node] so it
     * appends straight onto the routed geometry. Null when the crossing is already within the
     * hold-short lead of [node] — the node itself is then the hold, and no stub is needed.
     */
    private fun holdShortStub(edgeIndex: Int, node: Int): List<GeoCoordinate>? {
        if (edgeIndex !in graph.edges.indices) return null
        val edge = graph.edges[edgeIndex]
        val crossingPoint = edge.crossingPoint ?: return null
        if (edge.geometry.size < 2) return null
        if (edge.from != node && edge.to != node) return null
        val oriented = if (edge.from == node) edge.geometry else edge.geometry.reversed()
        val along = SurfaceGeometry
            .nearestPointOnPath(crossingPoint.toCoordinate(), oriented.toCoordinates())
            ?.alongMeters ?: return null
        val cut = max(0.0, along - holdShortLeadMeters)
        if (cut <= 1) return null
        val stub = truncatedPolyline(oriented, cut)
        return if (stub.size >= 2) stub else null
    }

    /**
     * Canonical idents (both ends) of the physical runway the assigned end belongs to, so a
     * crossing of *that* runway is recognized no matter which end the crossing was tagged with
     * or how it is zero-padded — the crossing ident is a runway's `idents.first` ("16L") while
     * the departure may be assigned the reciprocal ("34R"). Falls back to just the assigned
     * ident when the runway isn't present in the model.
     */
    private fun assignedRunwayKeys(assignedIdent: String): Set<String> {
        val key = runwayKey(assignedIdent)
        val runway = model.runways.firstOrNull { r -> r.idents.any { runwayKey(it) == key } }
        if (runway != null) return runway.idents.map { runwayKey(it) }.toSet()
        return setOf(key)
    }

    /**
     * [line] cut to its first [meters] of length, ending exactly at that point — used to stop a
     * departure route at the hold-short of its own runway. Always returns at least two points
     * (when the input has two) so the shortened route still renders and tracks.
     */
    private fun truncatedPolyline(line: List<GeoCoordinate>, meters: Double): List<GeoCoordinate> {
        if (line.size < 2) return line
        val cl = line.toCoordinates()
        if (meters >= SurfaceGeometry.pathLengthMeters(cl)) return line
        val out = mutableListOf(line[0])
        var accumulated = 0.0
        for (i in 1 until line.size) {
            val segLen = SurfaceGeometry
                .distanceMeters(line[i - 1].toCoordinate(), line[i].toCoordinate())
            if (accumulated + segLen < meters) {
                out.add(line[i])
                accumulated += segLen
            } else {
                SurfaceGeometry.pointAlong(cl, meters)?.let { out.add(GeoCoordinate(it)) }
                break
            }
        }
        if (out.size < 2) out.add(line[1])
        return out
    }

    private fun crossingConfidence(point: GeoCoordinate, named: Boolean): SurfaceConfidence {
        val hasMappedHold = model.holdingPositions.any {
            !it.inferred &&
                SurfaceGeometry.distanceMeters(point.toCoordinate(), it.coordinate.toCoordinate()) < 90
        }
        if (hasMappedHold && named) return SurfaceConfidence.HIGH
        if (hasMappedHold || named) return SurfaceConfidence.MEDIUM
        return SurfaceConfidence.LOW
    }

    private data class Graded(
        val confidence: SurfaceConfidence,
        val score: Double,
        val notes: List<String>,
    )

    private fun gradeConfidence(
        namedFraction: Double,
        snapMeters: Double,
        goalMeters: Double,
        midInferred: Boolean,
        crossesBuilding: Boolean,
        crossings: List<RouteCrossing>,
        goalCorrectEnd: Boolean,
    ): Graded {
        var score = 1.0
        val notes = mutableListOf<String>()
        if (snapMeters > 120) {
            score -= 0.35; notes.add("aircraft is far from the mapped surface")
        } else if (snapMeters > 60) {
            score -= 0.12
        }
        if (goalMeters > 200) {
            score -= 0.15; notes.add("runway hold point is approximate")
        }
        score -= (1 - namedFraction) * 0.45
        if (namedFraction < 0.999) notes.add("route includes unnamed taxiway segments")
        if (midInferred) {
            score -= 0.3; notes.add("route relies on an inferred connector")
        }
        if (crossesBuilding) {
            score -= 0.3; notes.add("gate lead-in passes through a building footprint")
        }
        if (!goalCorrectEnd) {
            score -= 0.25; notes.add("could not confirm the assigned runway end")
        }
        if (crossings.any { it.confidence == SurfaceConfidence.LOW }) {
            score -= 0.15; notes.add("a runway crossing has uncertain geometry")
        }

        // High confidence requires the strong conditions.
        val strong = namedFraction >= 0.7 && model.holdingPositions.isNotEmpty() &&
            snapMeters <= 60 && goalCorrectEnd && !midInferred && !crossesBuilding &&
            crossings.none { it.confidence == SurfaceConfidence.LOW }

        var confidence: SurfaceConfidence = when {
            score >= 0.8 && strong -> SurfaceConfidence.HIGH
            score >= 0.55 -> SurfaceConfidence.MEDIUM
            score >= 0.3 -> SurfaceConfidence.LOW
            else -> SurfaceConfidence.UNAVAILABLE
        }
        // Never report High when the dataset itself is weak.
        if (confidence == SurfaceConfidence.HIGH &&
            model.confidence.rank < SurfaceConfidence.MEDIUM.rank
        ) {
            confidence = SurfaceConfidence.MEDIUM
        }
        return Graded(confidence, max(0.0, score), notes)
    }
}

/** A minimal binary min-heap keyed on a Double priority, for A*. */
internal class MinHeap {
    data class Item(val priority: Double, val node: Int)

    private val items = mutableListOf<Item>()

    fun push(priority: Double, node: Int) {
        items.add(Item(priority, node))
        var child = items.size - 1
        while (child > 0) {
            val parent = (child - 1) / 2
            if (items[child].priority < items[parent].priority) {
                val tmp = items[child]; items[child] = items[parent]; items[parent] = tmp
                child = parent
            } else {
                break
            }
        }
    }

    fun pop(): Item? {
        if (items.isEmpty()) return null
        val last = items.size - 1
        val tmp = items[0]; items[0] = items[last]; items[last] = tmp
        val top = items.removeAt(items.size - 1)
        if (items.isEmpty()) return top
        var parent = 0
        val n = items.size
        while (true) {
            val l = 2 * parent + 1
            val r = 2 * parent + 2
            var smallest = parent
            if (l < n && items[l].priority < items[smallest].priority) smallest = l
            if (r < n && items[r].priority < items[smallest].priority) smallest = r
            if (smallest == parent) break
            val t = items[parent]; items[parent] = items[smallest]; items[smallest] = t
            parent = smallest
        }
        return top
    }
}
