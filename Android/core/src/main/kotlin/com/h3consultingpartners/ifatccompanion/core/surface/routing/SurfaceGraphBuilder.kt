package com.h3consultingpartners.ifatccompanion.core.surface.routing

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.surface.AirportSurfaceModel
import com.h3consultingpartners.ifatccompanion.core.surface.GeoCoordinate
import com.h3consultingpartners.ifatccompanion.core.surface.OSMSurfaceNormalizer
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceRunwayEnd
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceTaxiway
import com.h3consultingpartners.ifatccompanion.core.surface.toCoordinates
import kotlin.math.max
import kotlin.math.min

/**
 * Builds the connected airport surface graph from a normalized [AirportSurfaceModel].
 *
 * Topology strategy: intersecting OSM taxiways share an identical vertex coordinate
 * (that is how OSM models a junction), so taxiway vertices are snapped to a ~1.1 m
 * grid and merged into shared nodes. Edges run between junction/endpoint nodes and
 * carry the full intermediate geometry, name, distance, and original OSM ids.
 *
 * Runway crossings are detected by intersecting taxiway edge geometry with runway
 * centerlines; an intersection near a runway end threshold is treated as a runway
 * *entry* (line-up), elsewhere as a *crossing*. Holding positions, runway entries, and
 * gates/parking are attached to the graph; gate lead-ins are marked as inferred, lower
 * confidence connectors. Nothing here assumes OSM matches Infinite Flight scenery.
 *
 * Ported from `IFATCCompanion/AirportSurface/SurfaceGraphBuilder.swift`.
 */
object SurfaceGraphBuilder {

    /** Intersection within this (m) of a threshold = a runway entry, not a crossing. */
    const val RUNWAY_END_THRESHOLD_METERS = 90.0
    const val HOLD_ATTACH_METERS = 60.0
    const val RUNWAY_ENTRY_ATTACH_METERS = 160.0
    const val GATE_ATTACH_METERS = 240.0

    /**
     * Added to a candidate gate connector's score when its straight lead-in would pass
     * through a building/terminal. Large enough that any clear node inside the attach
     * radius always beats a concourse-crossing one.
     */
    const val BUILDING_CONNECTOR_PENALTY_METERS = 5_000.0

    /**
     * Added per meter the lead-in actually spends *inside* a footprint, on top of the flat
     * penalty. The flat term keeps every clear candidate ahead of every crossing one; this
     * term orders the crossing candidates among themselves, which is what a stand mapped on
     * the concourse itself needs. KIAD tags each gate node as a vertex of the Concourse C/D
     * outline, so *every* candidate lead-in touches the footprint and a flat penalty alone
     * leaves the choice to raw distance — which at a 33 m-wide concourse is as likely to be
     * a node on the far side as one on the stand's own.
     */
    const val BUILDING_INTRUSION_PENALTY_PER_METER = 20.0

    /**
     * A lead-in running less than this far inside a footprint is not treated as crossing it:
     * a stand *on* the outline starts exactly on the boundary, and one heading away from the
     * building should read as clear rather than as cutting through it.
     */
    const val BUILDING_INTRUSION_TOLERANCE_METERS = 0.5

    /**
     * Added when continuing off the connector onto the taxi network would require a
     * near-reversal (the lead-in doubles back across the ramp) — a gentle tiebreak toward
     * a node the stand can leave naturally, deliberately small so it only decides between
     * otherwise-comparable candidates and never overrides a clearly nearer one.
     */
    const val CONNECTOR_REVERSAL_PENALTY_METERS = 150.0

    /**
     * A reversal is a turn sharper than this (degrees) from the connector onto the best
     * onward taxiway at the node.
     */
    const val CONNECTOR_REVERSAL_DEGREES = 120.0

    /** How many nearest taxi nodes to score as gate-connector candidates. */
    const val MAX_GATE_CONNECTOR_CANDIDATES = 8

    private val TAXI_KINDS = setOf(
        SurfaceNodeKind.TAXIWAY_ENDPOINT,
        SurfaceNodeKind.INTERSECTION,
        SurfaceNodeKind.RUNWAY_ENTRY,
        SurfaceNodeKind.HOLDING_POSITION,
    )

    private fun priority(k: SurfaceNodeKind): Int = when (k) {
        SurfaceNodeKind.GATE, SurfaceNodeKind.PARKING -> 5
        SurfaceNodeKind.HOLDING_POSITION -> 4
        SurfaceNodeKind.RUNWAY_ENTRY, SurfaceNodeKind.RUNWAY_CROSSING -> 3
        SurfaceNodeKind.INTERSECTION -> 2
        SurfaceNodeKind.APRON_CONNECTOR -> 1
        SurfaceNodeKind.TAXIWAY_ENDPOINT -> 0
    }

    private fun edgeConfidence(t: SurfaceTaxiway): Double {
        var c = 0.9
        if (!t.hasName) c -= 0.35
        if (t.isTaxilane) c -= 0.1
        if (t.isClosed) c = 0.1
        return max(0.05, min(1.0, c))
    }

    /** A candidate mid-edge attachment for a stand that has no routable node in range. */
    private data class EdgeAttach(
        val edgeIndex: Int,
        val projection: Coordinate,
        val alongFromFrom: Double,
        val perpMeters: Double,
    )

    private data class AttachScore(val score: Double, val crosses: Boolean)

    private data class NodeCandidate(val id: Int, val distance: Double)

    fun build(model: AirportSurfaceModel): SurfaceGraph {
        val nodes = mutableListOf<SurfaceNode>()
        val edges = mutableListOf<SurfaceEdge>()
        val keyToNodeID = mutableMapOf<String, Int>()

        fun makeNode(
            coord: GeoCoordinate,
            kind: SurfaceNodeKind,
            osmID: String? = null,
            inferred: Boolean = false,
        ): Int {
            val key = SurfaceGeometry.snapKey(coord)
            val existing = keyToNodeID[key]
            if (existing != null) {
                if (priority(kind) > priority(nodes[existing].kind)) nodes[existing].kind = kind
                if (nodes[existing].osmID == null && osmID != null) nodes[existing].osmID = osmID
                return existing
            }
            val id = nodes.size // id == array index invariant
            keyToNodeID[key] = id
            nodes.add(
                SurfaceNode(
                    id = id, coordinate = coord, kind = kind,
                    runwayRef = null, name = null, osmID = osmID, inferred = inferred,
                ),
            )
            return id
        }

        // Pass 1: count taxiway vertex occurrences → junctions are shared vertices.
        val vertexCount = mutableMapOf<String, Int>()
        for (twy in model.taxiways) {
            for (v in twy.geometry) {
                val k = SurfaceGeometry.snapKey(v)
                vertexCount[k] = (vertexCount[k] ?: 0) + 1
            }
        }

        // Pass 2: build nodes + edges between junction/endpoint vertices.
        for (twy in model.taxiways) {
            val geo = twy.geometry
            if (geo.size < 2) continue
            var currentNodeID = makeNode(geo[0], SurfaceNodeKind.TAXIWAY_ENDPOINT)
            var segGeo = mutableListOf(geo[0])
            for (i in 1 until geo.size) {
                segGeo.add(geo[i])
                val isJunction = (vertexCount[SurfaceGeometry.snapKey(geo[i])] ?: 0) >= 2
                val isEnd = i == geo.size - 1
                if (!isJunction && !isEnd) continue
                val toNodeID = makeNode(
                    geo[i],
                    if (isJunction) SurfaceNodeKind.INTERSECTION else SurfaceNodeKind.TAXIWAY_ENDPOINT,
                )
                if (toNodeID != currentNodeID && segGeo.size >= 2) {
                    val dist = SurfaceGeometry.pathLengthMeters(segGeo.toCoordinates())
                    edges.add(
                        SurfaceEdge(
                            id = edges.size, from = currentNodeID, to = toNodeID,
                            geometry = segGeo.toList(), distanceMeters = dist,
                            taxiwayName = twy.name, hasName = twy.hasName,
                            isTaxilane = twy.isTaxilane,
                            runwayCrossing = null, runwayCrossingName = null, crossingPoint = null,
                            runwayOccupancy = false, oneway = twy.oneway, closed = twy.isClosed,
                            inferred = false, confidence = edgeConfidence(twy),
                            osmIDs = listOf(twy.osmID), widthMeters = twy.widthMeters,
                        ),
                    )
                }
                currentNodeID = toNodeID
                segGeo = mutableListOf(geo[i])
            }
        }

        // Each runway way's directional ends, matched by ident rather than by OSM id: a
        // physical runway split across several OSM ways has its ends attributed to one
        // representative way, so keying by OSM id would leave the stub ways without ends and
        // misclassify a threshold intersection on them as a crossing. Matching by ident gives
        // every way of the same runway the same (merged) ends.
        val endByIdent = mutableMapOf<String, SurfaceRunwayEnd>()
        for (e in model.runwayEnds) {
            endByIdent.putIfAbsent(OSMSurfaceNormalizer.canonicalRunwayIdent(e.ident), e)
        }
        val endsByRunway = mutableMapOf<String, List<SurfaceRunwayEnd>>()
        for (r in model.runways) {
            endsByRunway.putIfAbsent(
                r.osmID,
                r.idents.mapNotNull { endByIdent[OSMSurfaceNormalizer.canonicalRunwayIdent(it)] },
            )
        }

        // Detect runway crossings / entries on each edge.
        data class CrossingSite(val point: GeoCoordinate, val ident: String, val name: String)
        val crossingSites = mutableListOf<CrossingSite>()
        for (eIdx in edges.indices) {
            val egeo = edges[eIdx].line
            if (egeo.size < 2) continue
            for (runway in model.runways) {
                val rgeo = runway.centerline.toCoordinates()
                if (rgeo.size < 2) continue
                var found = false
                for (i in 1 until egeo.size) {
                    if (found) continue
                    for (j in 1 until rgeo.size) {
                        val p = SurfaceGeometry.segmentIntersection(
                            egeo[i - 1], egeo[i], rgeo[j - 1], rgeo[j],
                        ) ?: continue
                        val ends = endsByRunway[runway.osmID] ?: emptyList()
                        val nearThreshold = ends.any {
                            SurfaceGeometry.distanceMeters(p, it.threshold.toCoordinate()) <
                                RUNWAY_END_THRESHOLD_METERS
                        }
                        edges[eIdx].runwayOccupancy = true
                        if (!nearThreshold) {
                            val ident = runway.idents.firstOrNull() ?: runway.displayName
                            edges[eIdx].runwayCrossing = ident
                            edges[eIdx].runwayCrossingName = runway.displayName
                            edges[eIdx].crossingPoint = GeoCoordinate(p)
                            crossingSites.add(CrossingSite(GeoCoordinate(p), ident, runway.displayName))
                        }
                        found = true
                        break
                    }
                }
            }
        }

        // Attach mapped holding positions to the nearest taxiway node.
        fun nearestNodeIndex(coord: Coordinate, kinds: Set<SurfaceNodeKind>, maxMeters: Double): Int? {
            var bestID: Int? = null
            var bestDistance = 0.0
            for (n in nodes) {
                if (!kinds.contains(n.kind)) continue
                val d = SurfaceGeometry.distanceMeters(coord, n.location)
                if (d <= maxMeters && (bestID == null || d < bestDistance)) {
                    bestID = n.id; bestDistance = d
                }
            }
            return bestID
        }

        for (hold in model.holdingPositions) {
            val idx = nearestNodeIndex(hold.coordinate.toCoordinate(), TAXI_KINDS, HOLD_ATTACH_METERS)
                ?: continue
            if (priority(SurfaceNodeKind.HOLDING_POSITION) >= priority(nodes[idx].kind)) {
                nodes[idx].kind = SurfaceNodeKind.HOLDING_POSITION
            }
            nodes[idx].runwayRef =
                if (hold.runwayRef.isEmpty()) nodes[idx].runwayRef else hold.runwayRef
            if (nodes[idx].osmID == null) nodes[idx].osmID = hold.osmID
            nodes[idx].inferred = nodes[idx].inferred || hold.inferred
        }

        // Mark a runway-entry node for each runway end (nearest taxiway node to threshold).
        for (end in model.runwayEnds) {
            val idx = nearestNodeIndex(
                end.threshold.toCoordinate(), TAXI_KINDS, RUNWAY_ENTRY_ATTACH_METERS,
            ) ?: continue
            if (nodes[idx].kind == SurfaceNodeKind.TAXIWAY_ENDPOINT ||
                nodes[idx].kind == SurfaceNodeKind.INTERSECTION
            ) {
                nodes[idx].kind = SurfaceNodeKind.RUNWAY_ENTRY
            }
            if (nodes[idx].runwayRef == null) nodes[idx].runwayRef = end.ident
        }

        // Inferred holds: a runway-entry node with no mapped holding position nearby
        // becomes an inferred, lower-confidence holding position (for simulation).
        for (idx in nodes.indices) {
            if (nodes[idx].kind != SurfaceNodeKind.RUNWAY_ENTRY) continue
            val coord = nodes[idx].location
            val hasMappedHold = model.holdingPositions.any {
                !it.inferred &&
                    SurfaceGeometry.distanceMeters(coord, it.coordinate.toCoordinate()) < HOLD_ATTACH_METERS
            }
            if (!hasMappedHold) {
                nodes[idx].kind = SurfaceNodeKind.HOLDING_POSITION
                nodes[idx].inferred = true
            }
        }

        // Building / terminal footprints, with a cheap bounding box each so most stands
        // skip the full polygon test. Gate lead-ins are steered clear of these so a route
        // to a thin-concourse stand doesn't cut straight through the building to reach it.
        data class BuildingPoly(val poly: List<Coordinate>, val box: SurfaceGeometry.BoundingBox)
        val buildingPolys = model.buildings.mapNotNull { b ->
            val poly = b.polygon.toCoordinates()
            if (poly.size < 3) return@mapNotNull null
            val box = SurfaceGeometry.boundingBox(poly) ?: return@mapNotNull null
            BuildingPoly(poly, box)
        }

        // node id → indices of the (real) taxiway edges built so far, for reversal scoring.
        val nodeToEdges = mutableMapOf<Int, MutableList<Int>>()
        for ((idx, e) in edges.withIndex()) {
            nodeToEdges.getOrPut(e.from) { mutableListOf() }.add(idx)
            nodeToEdges.getOrPut(e.to) { mutableListOf() }.add(idx)
        }

        /**
         * How far the straight connector a→b runs inside a building footprint, in meters —
         * the deepest single footprint it passes through rather than the sum, since OSM
         * routinely maps overlapping `building` and `building:part` outlines over one
         * structure and summing them would charge the same concourse several times.
         */
        fun connectorIntrusionMeters(a: Coordinate, b: Coordinate): Double {
            if (buildingPolys.isEmpty()) return 0.0
            val loLat = min(a.latitude, b.latitude); val hiLat = max(a.latitude, b.latitude)
            val loLon = min(a.longitude, b.longitude); val hiLon = max(a.longitude, b.longitude)
            var deepest = 0.0
            for (bp in buildingPolys) {
                // AABB reject: skip a building whose box can't overlap the connector's.
                if (bp.box.maxLat < loLat || bp.box.minLat > hiLat ||
                    bp.box.maxLon < loLon || bp.box.minLon > hiLon
                ) {
                    continue
                }
                deepest = max(deepest, SurfaceGeometry.segmentIntrusionMeters(a, b, bp.poly))
            }
            return deepest
        }

        /**
         * The building term of a candidate's score: nothing when the lead-in stays clear,
         * otherwise the flat penalty plus what the intrusion itself costs.
         */
        fun buildingPenalty(a: Coordinate, b: Coordinate): AttachScore {
            val intrusion = connectorIntrusionMeters(a, b)
            if (intrusion <= BUILDING_INTRUSION_TOLERANCE_METERS) return AttachScore(0.0, false)
            return AttachScore(
                BUILDING_CONNECTOR_PENALTY_METERS + intrusion * BUILDING_INTRUSION_PENALTY_PER_METER,
                true,
            )
        }

        /**
         * Penalty when leaving the stand onto the taxi network at [node] would require a
         * near-reversal from the connector's arrival bearing — i.e. the lead-in doubles
         * back across the ramp instead of feeding the taxiway naturally.
         */
        fun reversalPenalty(gate: Coordinate, node: Int): Double {
            val incident = nodeToEdges[node] ?: emptyList()
            if (incident.isEmpty()) return 0.0
            val arrival = Geo.bearing(gate, nodes[node].location)
            var bestTurn = 180.0
            for (idx in incident) {
                val e = edges[idx]
                val other = if (e.from == node) e.to else e.from
                if (other !in nodes.indices || other == node) continue
                val onward = Geo.bearing(nodes[node].location, nodes[other].location)
                bestTurn = min(bestTurn, Geo.headingDifference(arrival, onward))
            }
            return if (bestTurn > CONNECTOR_REVERSAL_DEGREES) CONNECTOR_REVERSAL_PENALTY_METERS else 0.0
        }

        // Candidate taxi nodes for a stand, nearest first, capped.
        fun connectorCandidates(coord: Coordinate): List<NodeCandidate> =
            nodes.filter { TAXI_KINDS.contains(it.kind) }
                .map { NodeCandidate(it.id, SurfaceGeometry.distanceMeters(coord, it.location)) }
                .filter { it.distance <= GATE_ATTACH_METERS }
                .sortedBy { it.distance }
                .take(MAX_GATE_CONNECTOR_CANDIDATES)

        // A stand normally snaps to the nearest routable *node* within `GATE_ATTACH_METERS`. But
        // an apron taxilane is often drawn as one long, sparsely-noded OSM way whose *line* runs
        // right past a row of stands while its nearest node is hundreds of metres away — so a
        // stand can sit well within taxi distance of a taxiway yet have no node to attach to
        // (e.g. KDEN's inner Concourse-B gates sit ~260 m from the nearest node on the "Green"
        // apron taxilane, whose centreline passes ~140 m away as a single 800 m+ segment). When
        // no node is in range — or when the only node's lead-in would cut through a terminal —
        // the stand instead attaches to the nearest point *projected onto a taxiway edge*,
        // splitting that edge to insert the junction. Stands that already have a clear node in
        // range keep their exact existing attachment, so well-mapped fields are unchanged.
        fun edgeAttachCandidates(coord: Coordinate): List<EdgeAttach> {
            val out = mutableListOf<EdgeAttach>()
            for (idx in edges.indices) {
                val e = edges[idx]
                // Skip inferred connectors, closed segments, and runway-occupancy/crossing edges
                // (a stand never leads onto a runway, and splitting a crossing edge would drop
                // its crossing metadata).
                if (e.inferred || e.closed || e.runwayOccupancy) continue
                val line = e.line
                if (line.size < 2) continue
                val proj = SurfaceGeometry.nearestPointOnPath(coord, line) ?: continue
                if (proj.distanceMeters > GATE_ATTACH_METERS) continue
                out.add(EdgeAttach(idx, proj.point, proj.alongMeters, proj.distanceMeters))
            }
            return out.sortedBy { it.perpMeters }.take(MAX_GATE_CONNECTOR_CANDIDATES)
        }

        /**
         * Reversal penalty for leaving a stand onto a mid-edge projection: the sharpest turn
         * from the lead-in bearing onto the taxiway (either direction, or forward only when the
         * edge is one-way) — mirrors [reversalPenalty] for node candidates.
         */
        fun edgeReversalPenalty(gate: Coordinate, c: EdgeAttach): Double {
            val e = edges[c.edgeIndex]
            val line = e.line
            val arrival = Geo.bearing(gate, c.projection)
            var bestTurn = 180.0
            val fwd = SurfaceGeometry.pointAlong(line, c.alongFromFrom + 8)
            if (fwd != null && SurfaceGeometry.distanceMeters(c.projection, fwd) > 0.5) {
                bestTurn = min(bestTurn, Geo.headingDifference(arrival, Geo.bearing(c.projection, fwd)))
            }
            if (!e.oneway && c.alongFromFrom > 0.5) {
                val bwd = SurfaceGeometry.pointAlong(line, max(0.0, c.alongFromFrom - 8))
                if (bwd != null && SurfaceGeometry.distanceMeters(c.projection, bwd) > 0.5) {
                    bestTurn = min(bestTurn, Geo.headingDifference(arrival, Geo.bearing(c.projection, bwd)))
                }
            }
            return if (bestTurn > CONNECTOR_REVERSAL_DEGREES) CONNECTOR_REVERSAL_PENALTY_METERS else 0.0
        }

        fun nodeAttachScore(gate: Coordinate, c: NodeCandidate): AttachScore {
            val building = buildingPenalty(gate, nodes[c.id].location)
            val s = c.distance + building.score + reversalPenalty(gate, c.id)
            return AttachScore(s, building.crosses)
        }

        fun edgeAttachScore(gate: Coordinate, c: EdgeAttach): AttachScore {
            val building = buildingPenalty(gate, c.projection)
            val s = c.perpMeters + building.score + edgeReversalPenalty(gate, c)
            return AttachScore(s, building.crosses)
        }

        /**
         * Split real edge [eIdx] at [along] metres from its `from`, inserting a routable node at
         * [point] and returning its id (or an existing endpoint's id when the projection lands
         * essentially on one). The original edge index becomes the from→split half and a new
         * edge is appended for the split→to half; every attribute (name, taxilane, oneway,
         * confidence, OSM ids, width) is carried onto both halves. Runway-crossing edges are
         * never passed here (excluded as candidates), so no crossing metadata is lost.
         */
        fun splitEdgeForConnector(eIdx: Int, point: Coordinate, along: Double): Int {
            val e = edges[eIdx]
            if (along <= 1.0) return e.from
            if (along >= e.distanceMeters - 1.0) return e.to
            val geo = e.geometry
            val cumulative = mutableListOf(0.0)
            for (i in 1 until geo.size) {
                cumulative.add(
                    cumulative[i - 1] +
                        SurfaceGeometry.distanceMeters(geo[i - 1].toCoordinate(), geo[i].toCoordinate()),
                )
            }
            val pGeo = GeoCoordinate(point)
            val geomA = mutableListOf<GeoCoordinate>()
            val geomB = mutableListOf<GeoCoordinate>()
            for (i in geo.indices) {
                if (cumulative[i] < along - 0.5) geomA.add(geo[i])
                else if (cumulative[i] > along + 0.5) geomB.add(geo[i])
            }
            geomA.add(pGeo)
            geomB.add(0, pGeo)
            if (geomA.size < 2 || geomB.size < 2) return e.to
            val pID = makeNode(pGeo, SurfaceNodeKind.APRON_CONNECTOR)
            if (pID == e.from || pID == e.to) return pID // snapped onto an existing endpoint

            fun half(a: Int, b: Int, geometry: List<GeoCoordinate>, newID: Int) = SurfaceEdge(
                id = newID, from = a, to = b, geometry = geometry,
                distanceMeters = SurfaceGeometry.pathLengthMeters(geometry.toCoordinates()),
                taxiwayName = e.taxiwayName, hasName = e.hasName, isTaxilane = e.isTaxilane,
                runwayCrossing = null, runwayCrossingName = null, crossingPoint = null,
                runwayOccupancy = e.runwayOccupancy, oneway = e.oneway, closed = e.closed,
                inferred = e.inferred, crossesBuilding = e.crossesBuilding,
                confidence = e.confidence, osmIDs = e.osmIDs, widthMeters = e.widthMeters,
            )
            edges[eIdx] = half(e.from, pID, geomA.toList(), e.id)
            edges.add(half(pID, e.to, geomB.toList(), edges.size))
            return pID
        }

        // Attach gates / parking via inferred connectors, preferring a nearby node the lead-in
        // can reach without crossing a concourse or doubling back — and, when no node is in range
        // (or the only node lead-in crosses a terminal), a point projected onto a nearby taxiway
        // edge so a stand beside a long, sparsely-noded apron taxilane still connects.
        // `routableStands`, not `parkingPositions`: a field that maps one stand twice — KIAD's
        // `gate` C24 is a vertex of the Concourse C/D outline, `parking_position` C24 the stand
        // 75 m south of it — contributes one stand node, the one an aircraft can park on.
        var inferredConnectors = 0
        for (parking in model.routableStands) {
            val gateCoord = parking.coordinate.toCoordinate()

            val bestNode = connectorCandidates(gateCoord)
                .map { it to nodeAttachScore(gateCoord, it) }
                .minByOrNull { it.second.score }

            // Only pay for the edge scan when a node snap is unavailable or would cross a
            // building — the well-mapped stands keep their exact existing node attachment.
            var bestEdge: Pair<EdgeAttach, AttachScore>? = null
            val needEdge = bestNode?.second?.crosses ?: true
            if (needEdge) {
                bestEdge = edgeAttachCandidates(gateCoord)
                    .map { it to edgeAttachScore(gateCoord, it) }
                    .minByOrNull { it.second.score }
            }

            // Prefer the in-range node (unchanged routing); fall to an edge projection when
            // there is no node in range, or when the node lead-in crosses a terminal but an edge
            // lead-in stays clear.
            val useEdge = if (bestNode != null) {
                bestNode.second.crosses && (bestEdge?.second?.crosses == false)
            } else {
                bestEdge != null
            }

            val taxiIdx: Int = when {
                useEdge && bestEdge != null -> splitEdgeForConnector(
                    bestEdge.first.edgeIndex, bestEdge.first.projection, bestEdge.first.alongFromFrom,
                )
                bestNode != null -> bestNode.first.id
                else -> continue
            }

            val gateNodeID = makeNode(
                parking.coordinate,
                if (parking.kind == com.h3consultingpartners.ifatccompanion.core.surface.SurfaceParking.Kind.GATE) {
                    SurfaceNodeKind.GATE
                } else {
                    SurfaceNodeKind.PARKING
                },
                osmID = parking.osmID,
                inferred = true,
            )
            nodes[gateNodeID].name = parking.name
            // A stand sitting essentially on the taxiway can resolve to its own attach node;
            // skip the zero-length self-connector.
            if (taxiIdx == gateNodeID) continue
            val a = nodes[gateNodeID].coordinate
            val b = nodes[taxiIdx].coordinate
            val dist = SurfaceGeometry.distanceMeters(a.toCoordinate(), b.toCoordinate())
            // Even the best available lead-in may still clip a footprint (a stand ringed by
            // building). Flag it so routing penalizes it and confidence reflects it.
            val crosses = buildingPenalty(a.toCoordinate(), b.toCoordinate()).crosses
            edges.add(
                SurfaceEdge(
                    id = edges.size, from = gateNodeID, to = taxiIdx,
                    geometry = listOf(a, b), distanceMeters = dist,
                    taxiwayName = "", hasName = false, isTaxilane = false,
                    runwayCrossing = null, runwayCrossingName = null, crossingPoint = null,
                    runwayOccupancy = false, oneway = false, closed = false,
                    inferred = true, crossesBuilding = crosses,
                    confidence = if (crosses) 0.2 else 0.4, osmIDs = emptyList(), widthMeters = null,
                ),
            )
            inferredConnectors += 1
        }

        // Adjacency.
        val adjacency = mutableMapOf<Int, MutableList<Int>>()
        for ((idx, e) in edges.withIndex()) {
            adjacency.getOrPut(e.from) { mutableListOf() }.add(idx)
            adjacency.getOrPut(e.to) { mutableListOf() }.add(idx)
        }

        // Connected components over nodes that participate in an edge (union-find).
        val componentCount = connectedComponents(nodes.size, edges)

        // Register de-duplicated runway-crossing marker nodes (display/diagnostics only,
        // kept out of adjacency so they never alter routing or component counts).
        val seenCrossingKeys = mutableSetOf<String>()
        for (site in crossingSites) {
            val key = SurfaceGeometry.snapKey(site.point)
            if (!seenCrossingKeys.add(key)) continue
            nodes.add(
                SurfaceNode(
                    id = nodes.size, coordinate = site.point, kind = SurfaceNodeKind.RUNWAY_CROSSING,
                    runwayRef = site.ident, name = site.name, osmID = null, inferred = false,
                ),
            )
        }

        return SurfaceGraph(
            nodes = nodes.toList(), edges = edges.toList(),
            adjacency = adjacency.mapValues { it.value.toList() },
            componentCount = componentCount, inferredConnectorCount = inferredConnectors,
        )
    }

    /** Count connected components among nodes that appear in at least one edge. */
    private fun connectedComponents(nodeCount: Int, edges: List<SurfaceEdge>): Int {
        if (nodeCount <= 0) return 0
        val parent = IntArray(nodeCount) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) {
                parent[r] = parent[parent[r]]
                r = parent[r]
            }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        val touched = mutableSetOf<Int>()
        for (e in edges) {
            if (e.from >= nodeCount || e.to >= nodeCount) continue
            union(e.from, e.to)
            touched.add(e.from); touched.add(e.to)
        }
        val roots = mutableSetOf<Int>()
        for (n in touched) roots.add(find(n))
        return roots.size
    }
}
