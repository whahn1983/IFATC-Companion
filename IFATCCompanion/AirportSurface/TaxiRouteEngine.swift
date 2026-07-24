import Foundation
import CoreLocation

/// A best-effort taxi-routing engine over the airport surface graph.
///
/// Uses A* with a great-circle heuristic, but **never** chooses purely on shortest
/// distance. Strong penalties push routes away from unnecessary runway crossings,
/// active-runway back-taxi / runway occupancy, disconnected jumps, inferred apron
/// shortcuts, closed taxiways, aircraft-incompatible or unnamed low-confidence
/// segments, and sharp turns; it prefers named, connected, high-confidence geometry,
/// full-length runway entry, and fewer crossings. Output confidence is graded so the
/// caller can suppress overly precise instructions when the data is weak.
struct TaxiRouteEngine {

    // Penalty weights (meters-equivalent) — deliberately large so geometry alone never
    // wins over these operational preferences.
    private let crossingPenalty = 4_000.0
    private let inferredPenalty = 3_000.0
    private let unnamedPenalty = 350.0
    private let taxilanePenalty = 200.0
    private let lowConfidencePenalty = 500.0
    private let widthPenalty = 3_000.0
    private let sharpTurnPenalty = 1_200.0
    private let moderateTurnPenalty = 300.0
    /// A connector whose straight lead-in cuts through a building/terminal — heavily
    /// disfavored so a clear alternative to the same stand always wins.
    private let buildingCrossingPenalty = 6_000.0

    let graph: SurfaceGraph
    let model: AirportSurfaceModel

    /// Distance (m) from the named gate within which the route is still anchored at the
    /// stand. Once the aircraft has pushed back and moved farther than this, the route
    /// starts from where the aircraft actually is instead — otherwise its first leg is
    /// the gate→pushback segment the aircraft has already left, which tracks as
    /// "off route" the moment the map appears.
    private let gateAnchorMeters = 30.0

    /// How close (m) the aircraft's projection onto an edge must be to one of that edge's
    /// endpoints before the start is treated as a plain node snap rather than a mid-edge
    /// one. Keeps a route that genuinely begins at a junction clean (no zero-length lead-in)
    /// while still letting the aircraft anchor partway along a long diagonal exit.
    private let endpointSnapMeters = 8.0

    /// How far from the assigned runway-end threshold a plain taxi node may sit and still
    /// serve as a last-resort goal when no runway-entry / holding-position node carries the
    /// runway's ident (e.g. the hold wasn't tagged in OSM).
    private let goalThresholdFallbackMeters = 300.0

    /// Upper bound on how many goal candidates the router probes with A* before giving up.
    /// Each probe is cheap, but a runway whose whole area is disconnected from the taxi
    /// network would otherwise probe every candidate; this keeps a hopeless case prompt.
    private let maxGoalAttempts = 16

    struct Request {
        var startCoordinate: CLLocationCoordinate2D
        var startGateName: String?
        var isDeparture: Bool
        var assignedRunwayIdent: String?
        var arrivalGateName: String?
        var aircraft: AircraftSizeClass = .medium
        var allowIntersectionDeparture: Bool = false
    }

    // MARK: - Public entry

    func route(_ request: Request) -> SurfaceTaxiRoute? {
        guard graph.nodes.count > 1, !graph.edges.isEmpty else { return nil }
        guard let anchor = resolveStart(request) else { return nil }
        if let r = attemptRoute(request, from: anchor) { return r }
        // An edge snap can land on a stub whose component reaches no goal; a connected node
        // elsewhere still might. Fall back to a plain node snap so this never fails a route the
        // old node-only snapping would have found.
        if case .edge = anchor, let fallback = nodeAnchorFallback(to: request.startCoordinate),
           let r = attemptRoute(request, from: fallback) { return r }
        return nil
    }

    private func attemptRoute(_ request: Request, from anchor: StartAnchor) -> SurfaceTaxiRoute? {
        // A* seeds: a plain node start seeds that one node at zero cost; a mid-edge start
        // (the aircraft partway along a diagonal exit / taxiway) seeds *both* endpoints, each
        // at its along-edge distance from the aircraft's projection, and lets A* pick whichever
        // gives the better route. This is what keeps the route starting under the aircraft
        // rather than jumping to a node a taxiway away.
        let seeds: [(node: Int, cost: Double)]
        let snapMeters: Double
        switch anchor {
        case let .node(id, distance):
            seeds = [(id, 0)]
            snapMeters = distance
        case let .edge(edgeIndex, _, alongFromFrom, perpMeters):
            let e = graph.edges[edgeIndex]
            seeds = [(e.from, max(0, alongFromFrom)),
                     (e.to, max(0, e.distanceMeters - alongFromFrom))]
            snapMeters = perpMeters
        }
        let startNodes = Set(seeds.map { $0.node })

        // Try each goal candidate in priority order (full-length runway entry, then holds for
        // an intersection departure, then taxi nodes near the threshold) and take the first
        // the aircraft can actually reach. A single first-choice goal can be stranded in a
        // disconnected patch of the OSM graph — at a big field like KATL the far-end runway
        // entry may not be wired to the terminal taxiways — which used to fail the whole route
        // even though another node for the same runway was reachable. Bounded so a runway
        // whose entire area is disconnected still returns promptly.
        var attempts = 0
        for goal in resolveGoalCandidates(request) where !startNodes.contains(goal.node) {
            if attempts >= maxGoalAttempts { break }
            attempts += 1
            guard let result = astar(starts: seeds, goal: goal.node,
                                     aircraft: request.aircraft) else { continue }
            let lead = leadIn(for: anchor, startNode: result.startNode)
            return assemble(nodePath: result.nodes, edgePath: result.edges, request: request,
                            startNodes: startNodes, goalNode: goal.node,
                            leadIn: lead.geometry, leadInName: lead.name,
                            leadInCrossingEdges: lead.crossingEdges,
                            snapMeters: snapMeters, goalMeters: goal.distanceMeters)
        }
        return nil
    }

    /// The plain node snap used to recover when an edge snap reaches no goal: the nearest
    /// connected node, or failing that the nearest node of any kind.
    private func nodeAnchorFallback(to coord: CLLocationCoordinate2D) -> StartAnchor? {
        if let connected = nearestConnectedNode(to: coord) {
            return .node(id: connected.node.id, distanceMeters: connected.distanceMeters)
        }
        guard let nearest = graph.nearestNode(to: coord) else { return nil }
        return .node(id: nearest.node.id, distanceMeters: nearest.distanceMeters)
    }

    // MARK: - Endpoint resolution

    /// Where a route begins on the graph. A `node` start anchors the first leg exactly at a
    /// graph node (parked at the stand, or the aircraft sitting essentially on top of a node);
    /// an `edge` start places the aircraft partway along a connected edge so the route can
    /// begin *under the aircraft* and join the network at whichever endpoint routes best.
    private enum StartAnchor {
        case node(id: Int, distanceMeters: Double)
        /// `projection` is the point on `edgeIndex` nearest the aircraft; `alongFromFrom` is
        /// the along-edge distance (m) from `edge.from` to it; `perpMeters` is the aircraft's
        /// perpendicular offset from the edge.
        case edge(edgeIndex: Int, projection: GeoCoordinate, alongFromFrom: Double, perpMeters: Double)
    }

    private func resolveStart(_ req: Request) -> StartAnchor? {
        if req.isDeparture, let gate = req.startGateName, !gate.isEmpty,
           let node = graph.nodes.first(where: { ($0.kind == .gate || $0.kind == .parking)
               && ($0.name?.uppercased() == gate.uppercased()) }) {
            let d = SurfaceGeometry.distanceMeters(req.startCoordinate, node.clLocation)
            // Anchor at the stand only while the aircraft is still parked there. After
            // pushback it has moved off the gate, so fall through to snap the route to
            // its real position instead of drawing a leg it has already taxied past.
            if d <= gateAnchorMeters { return .node(id: node.id, distanceMeters: d) }
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
        if let snap = nearestConnectedEdge(to: req.startCoordinate) {
            let e = graph.edges[snap.edgeIndex]
            let nearEnd = snap.alongFromFrom <= endpointSnapMeters
                || snap.alongFromFrom >= e.distanceMeters - endpointSnapMeters
            if !nearEnd {
                return .edge(edgeIndex: snap.edgeIndex, projection: snap.projection,
                             alongFromFrom: snap.alongFromFrom, perpMeters: snap.perpMeters)
            }
        }
        // Snap onto a node that actually participates in the routable network (has an incident
        // edge). `graph.nearestNode` scans every node, including display-only runway-crossing
        // markers and isolated stubs kept out of the adjacency; snapping the start onto one of
        // those strands the whole route (A* reaches nothing). Prefer the nearest connected
        // node; fall back to the nearest node only when the graph has no connected nodes at all.
        if let connected = nearestConnectedNode(to: req.startCoordinate) {
            return .node(id: connected.node.id, distanceMeters: connected.distanceMeters)
        }
        guard let nearest = graph.nearestNode(to: req.startCoordinate) else { return nil }
        return .node(id: nearest.node.id, distanceMeters: nearest.distanceMeters)
    }

    /// Nearest node with at least one incident edge — i.e. one the router can actually leave.
    private func nearestConnectedNode(to coord: CLLocationCoordinate2D) -> (node: SurfaceNode, distanceMeters: Double)? {
        var best: (SurfaceNode, Double)?
        for n in graph.nodes where !(graph.adjacency[n.id]?.isEmpty ?? true) {
            let d = SurfaceGeometry.distanceMeters(coord, n.clLocation)
            if best == nil || d < best!.1 { best = (n, d) }
        }
        return best.map { (node: $0.0, distanceMeters: $0.1) }
    }

    /// The routable edge whose geometry passes nearest `coord`, with the projected point, the
    /// along-edge distance (m) from `edge.from` to it, and the perpendicular offset. Closed
    /// segments are skipped — the aircraft can't taxi onto one. Every edge is incident to two
    /// nodes, so any edge returned is one the router can traverse from either end.
    private func nearestConnectedEdge(to coord: CLLocationCoordinate2D)
        -> (edgeIndex: Int, projection: GeoCoordinate, alongFromFrom: Double, perpMeters: Double)? {
        var best: (edgeIndex: Int, projection: GeoCoordinate, alongFromFrom: Double, perpMeters: Double)?
        for idx in graph.edges.indices {
            let e = graph.edges[idx]
            if e.closed { continue }
            let line = e.clGeometry
            guard line.count >= 2, let proj = SurfaceGeometry.nearestPointOnPath(coord, line) else { continue }
            if best == nil || proj.distanceMeters < best!.perpMeters {
                best = (idx, GeoCoordinate(proj.point), proj.alongMeters, proj.distanceMeters)
            }
        }
        return best
    }

    /// The lead-in that carries the route from the aircraft's projected position up to the node
    /// A* actually started from: its polyline (oriented projection→startNode, so it prepends
    /// cleanly), the snap edge's taxiway name (so the segment the aircraft is already on still
    /// appears in the spoken sequence), and — when the snap edge crosses a runway within the
    /// still-to-be-taxied portion — that edge's id, so the crossing/hold-short isn't lost. All
    /// empty for a node start.
    private func leadIn(for anchor: StartAnchor, startNode: Int)
        -> (geometry: [GeoCoordinate], name: String?, crossingEdges: [Int]) {
        guard case let .edge(edgeIndex, projection, alongFromFrom, _) = anchor else { return ([], nil, []) }
        let e = graph.edges[edgeIndex]
        let geo = e.geometry
        guard geo.count >= 2 else { return ([], nil, []) }

        // Cumulative along-edge distance (from e.from) to each vertex.
        var cumulative = [0.0]
        for i in 1..<geo.count {
            cumulative.append(cumulative[i - 1] + SurfaceGeometry.distanceMeters(geo[i - 1].clLocation, geo[i].clLocation))
        }

        var out: [GeoCoordinate] = [projection]
        if startNode == e.to {
            for i in 0..<geo.count where cumulative[i] > alongFromFrom + 0.5 { out.append(geo[i]) }
            if out.last != geo.last { out.append(geo[geo.count - 1]) }
        } else {   // startNode == e.from
            for i in stride(from: geo.count - 1, through: 0, by: -1) where cumulative[i] < alongFromFrom - 0.5 { out.append(geo[i]) }
            if out.last != geo.first { out.append(geo[0]) }
        }
        guard out.count >= 2 else { return ([], nil, []) }

        // If the snap edge crosses a runway, keep the crossing only when its centerline point
        // lies *ahead* of the aircraft along the direction it will taxi (from the projection
        // toward the node it heads to). A crossing at or behind the projection has already been
        // passed — the aircraft is exiting across it, not approaching it — so reporting a
        // hold-short there would be spurious.
        var crossingEdges: [Int] = []
        let startAlong = (startNode == e.to) ? e.distanceMeters : 0
        if e.runwayCrossing != nil, let cp = e.crossingPoint,
           let cpAlong = SurfaceGeometry.nearestPointOnPath(cp.clLocation, e.clGeometry)?.alongMeters {
            // Signed distance from the projection to the crossing, positive when ahead.
            let ahead = (startAlong >= alongFromFrom) ? (cpAlong - alongFromFrom) : (alongFromFrom - cpAlong)
            if ahead > 5 { crossingEdges.append(edgeIndex) }
        }
        return (out, e.taxiwayName.isEmpty ? nil : e.taxiwayName, crossingEdges)
    }

    /// Goal candidates for the route, best first, so `route` can fall through to the next one
    /// when the top choice is unreachable (stranded in a disconnected graph patch). Departure:
    /// full-length runway-entry node(s) for the assigned end — nearest the threshold first —
    /// then holding positions for that end (an intersection departure), then plain taxi nodes
    /// near the runway-end threshold as a last resort. Runway-ident matching is tolerant of
    /// leading-zero padding, so an assigned "9L" matches OSM-tagged "09L". Arrival: the named
    /// gate, else the nearest parking/gate to the airport reference (a single choice, as before).
    private func resolveGoalCandidates(_ req: Request) -> [(node: Int, distanceMeters: Double)] {
        if req.isDeparture {
            guard let ident = req.assignedRunwayIdent, !ident.isEmpty else { return [] }
            let key = runwayKey(ident)
            let assignedEnd = model.runwayEnds.first { runwayKey($0.ident) == key }
            let threshold = assignedEnd?.threshold.clLocation
            let opposite = assignedEnd?.oppositeThreshold.clLocation

            func distanceToThreshold(_ node: SurfaceNode) -> Double {
                guard let threshold else { return 0 }
                return SurfaceGeometry.distanceMeters(threshold, node.clLocation)
            }
            func matchesRunway(_ node: SurfaceNode) -> Bool {
                guard let ref = node.runwayRef else { return false }
                return runwayKey(ref) == key
            }
            // Reject a candidate that sits on the *opposite* half of the runway — a guard
            // against a wrong-end goal reaching the router from ambiguous OSM tagging (e.g. a
            // runway split across ways, or a mistagged hold). A node closer to the opposite
            // threshold than to the assigned one is on the wrong side, so a "24L" departure
            // can never be sent to the "06R" end.
            func onAssignedHalf(_ node: SurfaceNode) -> Bool {
                guard let threshold, let opposite else { return true }
                return SurfaceGeometry.distanceMeters(threshold, node.clLocation)
                    <= SurfaceGeometry.distanceMeters(opposite, node.clLocation)
            }

            var out: [(node: Int, distanceMeters: Double)] = []
            var seen = Set<Int>()
            func add(_ id: Int, _ distance: Double) {
                if seen.insert(id).inserted { out.append((node: id, distanceMeters: distance)) }
            }

            // 1) Full-length runway-entry nodes for the assigned end.
            for node in graph.nodes.filter({ $0.kind == .runwayEntry && matchesRunway($0) && onAssignedHalf($0) })
                .sorted(by: { distanceToThreshold($0) < distanceToThreshold($1) }) {
                add(node.id, 0)
            }
            // 2) Holding positions for the assigned end (intersection departure).
            for node in graph.nodes.filter({ $0.kind == .holdingPosition && matchesRunway($0) && onAssignedHalf($0) })
                .sorted(by: { distanceToThreshold($0) < distanceToThreshold($1) }) {
                add(node.id, 0)
            }
            // 3) Last resort: taxi nodes near the runway-end threshold (the ident may be
            //    untagged on any node), nearest first.
            if let threshold {
                let taxiKinds: Set<SurfaceNodeKind> = [.taxiwayEndpoint, .intersection, .runwayEntry, .holdingPosition]
                var near: [(id: Int, distance: Double)] = []
                for node in graph.nodes where taxiKinds.contains(node.kind) {
                    let distance = SurfaceGeometry.distanceMeters(threshold, node.clLocation)
                    if distance <= goalThresholdFallbackMeters { near.append((id: node.id, distance: distance)) }
                }
                for candidate in near.sorted(by: { $0.distance < $1.distance }) {
                    add(candidate.id, candidate.distance)
                }
            }
            return out
        } else {
            // Arrival goals, best first, so `route` can fall through to the next when the top
            // choice is stranded in a disconnected patch of the OSM graph — at a big field like
            // KMSP the named stand may attach to a taxiway component the runway-exit start can't
            // reach, which used to fail the whole arrival route (there was only ever one
            // candidate) and, in the mock demo, revert the map to the synthetic field. Mirrors
            // the multi-candidate resilience the departure goals already have: the entered gate
            // first, then other stands on the same concourse (same leading letter), then every
            // remaining stand — each tier nearest the aircraft's rollout start first — so the
            // arrival always lands at a reachable *real* stand rather than giving up.
            let stands = graph.nodes.filter { $0.kind == .gate || $0.kind == .parking }
            guard !stands.isEmpty else { return [] }
            let gate = (req.arrivalGateName ?? "").trimmingCharacters(in: .whitespaces)
            let letter = gate.prefix { $0.isLetter }.uppercased()

            func distanceToStart(_ node: SurfaceNode) -> Double {
                SurfaceGeometry.distanceMeters(req.startCoordinate, node.clLocation)
            }
            var out: [(node: Int, distanceMeters: Double)] = []
            var seen = Set<Int>()
            func add(_ id: Int, _ distance: Double) {
                if seen.insert(id).inserted { out.append((node: id, distanceMeters: distance)) }
            }

            // 1) The exact named stand.
            if !gate.isEmpty {
                for node in stands where node.name?.uppercased() == gate.uppercased() { add(node.id, 0) }
                // 2) Other stands on the same concourse (same leading letter), nearest first.
                if !letter.isEmpty {
                    for node in stands
                        .filter({ ($0.name?.uppercased().hasPrefix(letter) ?? false) })
                        .sorted(by: { distanceToStart($0) < distanceToStart($1) }) {
                        add(node.id, 0)
                    }
                }
            }
            // 3) Every remaining stand, nearest the rollout start first.
            for node in stands.sorted(by: { distanceToStart($0) < distanceToStart($1) }) {
                add(node.id, 0)
            }
            return out
        }
    }

    /// Canonical comparison key for a runway ident, tolerant of leading-zero padding and case,
    /// so an assigned "9L" matches OSM-tagged "09L" (and "8" matches "08"). The leading number
    /// collapses to its integer value; any L/C/R designator is preserved. An ident with no
    /// leading number falls back to its trimmed, uppercased form.
    private func runwayKey(_ raw: String) -> String {
        let s = raw.trimmingCharacters(in: .whitespaces).uppercased()
        let digits = s.prefix { $0.isNumber }
        guard let n = Int(digits) else { return s }
        return "\(n)\(s.dropFirst(digits.count))"
    }

    // MARK: - A*

    private func nodeCoord(_ id: Int) -> CLLocationCoordinate2D { graph.nodes[id].clLocation }

    /// Multi-source A*: `starts` seeds one or more entry nodes, each with an initial cost (the
    /// aircraft's along-edge distance to that node when it snapped mid-edge). Returns the path
    /// to `goal` and the entry node it actually left from, so the caller can prepend the matching
    /// lead-in.
    private func astar(starts: [(node: Int, cost: Double)], goal: Int,
                       aircraft: AircraftSizeClass) -> (nodes: [Int], edges: [Int], startNode: Int)? {
        var gScore: [Int: Double] = [:]
        var cameFrom: [Int: (node: Int, edge: Int)] = [:]
        var arrivedBy: [Int: Int] = [:]
        var closed = Set<Int>()
        let startNodes = Set(starts.map { $0.node })
        var heap = MinHeap()
        for s in starts where s.cost < (gScore[s.node] ?? .infinity) {
            gScore[s.node] = s.cost
            heap.push(s.cost + heuristic(s.node, goal), s.node)
        }

        while let popped = heap.pop() {
            let u = popped.node
            if u == goal { break }
            if closed.contains(u) { continue }
            closed.insert(u)
            let gu = gScore[u] ?? .infinity
            let incoming = arrivedBy[u]
            for edgeIdx in graph.adjacency[u] ?? [] {
                let e = graph.edges[edgeIdx]
                let v: Int
                if e.from == u { v = e.to }
                else if e.to == u && !e.oneway { v = e.from }
                else { continue }
                if closed.contains(v) { continue }
                let cost = edgeCost(e, at: u, incomingEdge: incoming, startNodes: startNodes, goal: goal, aircraft: aircraft)
                if !cost.isFinite { continue }   // prohibited
                let tentative = gu + cost
                if tentative < (gScore[v] ?? .infinity) {
                    gScore[v] = tentative
                    cameFrom[v] = (u, edgeIdx)
                    arrivedBy[v] = edgeIdx
                    heap.push(tentative + heuristic(v, goal), v)
                }
            }
        }

        guard gScore[goal] != nil else { return nil }
        var nodePath: [Int] = [goal]
        var edgePath: [Int] = []
        var cur = goal
        while !startNodes.contains(cur), let step = cameFrom[cur] {
            edgePath.append(step.edge)
            nodePath.append(step.node)
            cur = step.node
        }
        guard startNodes.contains(cur) else { return nil }
        nodePath.reverse(); edgePath.reverse()
        return (nodePath, edgePath, cur)
    }

    private func heuristic(_ a: Int, _ b: Int) -> Double {
        SurfaceGeometry.distanceMeters(nodeCoord(a), nodeCoord(b))
    }

    private func edgeCost(_ e: SurfaceEdge, at u: Int, incomingEdge: Int?,
                          startNodes: Set<Int>, goal: Int, aircraft: AircraftSizeClass) -> Double {
        if e.closed { return .infinity }
        // Never taxi onto a runway surface lengthwise (entry / back-taxi / occupancy).
        // A crossing edge is allowed (heavily penalized); a runway-entry edge is not.
        if e.runwayOccupancy && e.runwayCrossing == nil { return .infinity }

        var cost = max(e.distanceMeters, 1)
        if e.runwayCrossing != nil { cost += crossingPenalty }
        if e.crossesBuilding { cost += buildingCrossingPenalty }
        let touchesEndpoint = startNodes.contains(e.from) || startNodes.contains(e.to)
            || e.from == goal || e.to == goal
        if e.inferred && !touchesEndpoint { cost += inferredPenalty }
        if !e.hasName && !e.inferred { cost += unnamedPenalty }
        if e.isTaxilane { cost += taxilanePenalty }
        if let w = e.widthMeters, w > 0, w < aircraft.minComfortableTaxiwayWidthMeters { cost += widthPenalty }
        if e.isTaxilane && !aircraft.acceptsTaxilanes { cost += widthPenalty }
        if e.confidence < 0.4 { cost += lowConfidencePenalty }
        if let inc = incomingEdge { cost += turnPenalty(incoming: inc, outgoing: e, at: u) }
        return cost
    }

    private func turnPenalty(incoming: Int, outgoing e: SurfaceEdge, at u: Int) -> Double {
        guard graph.edges.indices.contains(incoming) else { return 0 }
        let pe = graph.edges[incoming]
        let prevNode = (pe.from == u) ? pe.to : pe.from
        let nextNode = (e.from == u) ? e.to : e.from
        guard prevNode != u, nextNode != u,
              graph.nodes.indices.contains(prevNode), graph.nodes.indices.contains(nextNode) else { return 0 }
        let inB = Geo.bearing(from: nodeCoord(prevNode), to: nodeCoord(u))
        let outB = Geo.bearing(from: nodeCoord(u), to: nodeCoord(nextNode))
        let turn = Geo.headingDifference(inB, outB)
        if turn > 120 { return sharpTurnPenalty }
        if turn > 95 { return moderateTurnPenalty }
        return 0
    }

    // MARK: - Assembly + confidence

    private func assemble(nodePath: [Int], edgePath: [Int], request: Request,
                          startNodes: Set<Int>, goalNode: Int,
                          leadIn: [GeoCoordinate], leadInName: String?, leadInCrossingEdges: [Int],
                          snapMeters: Double, goalMeters: Double) -> SurfaceTaxiRoute {
        // Oriented geometry + taxiway sequence.
        var geometry: [GeoCoordinate] = []
        var taxiSeq: [String] = []
        var unnamed = 0
        var midInferred = false
        var crossesBuilding = false
        for (i, edgeIdx) in edgePath.enumerated() {
            let e = graph.edges[edgeIdx]
            let fromNode = nodePath[i]
            let oriented = (e.from == fromNode) ? e.geometry : Array(e.geometry.reversed())
            if geometry.isEmpty { geometry.append(contentsOf: oriented) }
            else { geometry.append(contentsOf: oriented.dropFirst()) }
            if !e.taxiwayName.isEmpty, taxiSeq.last != e.taxiwayName { taxiSeq.append(e.taxiwayName) }
            if !e.hasName && !e.inferred { unnamed += 1 }
            if e.inferred, !(startNodes.contains(e.from) || startNodes.contains(e.to)
                             || e.from == goalNode || e.to == goalNode) {
                midInferred = true
            }
            if e.crossesBuilding { crossesBuilding = true }
        }

        // Prepend the lead-in from the aircraft's projected position up to the node the route
        // leaves from, so the drawn route begins under the aircraft (on the diagonal exit /
        // taxiway it is actually on) instead of at that node a taxiway away. Its trailing point
        // is the start node, which the routed geometry already opens with — drop the duplicate.
        if leadIn.count >= 2 {
            if let first = geometry.first, leadIn.last == first {
                geometry = leadIn + geometry.dropFirst()
            } else {
                geometry = leadIn + geometry
            }
            if let leadInName, taxiSeq.first != leadInName { taxiSeq.insert(leadInName, at: 0) }
        }

        let fullLine = geometry.clLocations
        // Runway crossings along the route — the routed edges, plus any crossing carried by the
        // lead-in edge whose crossed portion the aircraft still has to taxi over (so a mid-edge
        // start never silently drops a hold-short of a runway ahead).
        var crossings: [RouteCrossing] = []
        for edgeIdx in edgePath + leadInCrossingEdges {
            let e = graph.edges[edgeIdx]
            guard let cp = e.crossingPoint, let ident = e.runwayCrossing else { continue }
            let along = SurfaceGeometry.nearestPointOnPath(cp.clLocation, fullLine)?.alongMeters ?? 0
            let holdShort = SurfaceGeometry.pointAlong(fullLine, meters: max(0, along - 25)).map(GeoCoordinate.init) ?? cp
            crossings.append(RouteCrossing(index: crossings.count,
                                           runwayIdent: ident,
                                           runwayName: e.runwayCrossingName ?? ident,
                                           point: cp,
                                           holdShortPoint: holdShort,
                                           alongMeters: along,
                                           edgeID: edgeIdx,
                                           confidence: crossingConfidence(point: cp, named: e.hasName)))
        }
        crossings.sort { $0.alongMeters < $1.alongMeters }
        for i in crossings.indices { crossings[i].index = i }

        let distance = SurfaceGeometry.pathLengthMeters(fullLine)
        let namedFraction = edgePath.isEmpty ? 0 :
            Double(edgePath.filter { graph.edges[$0].hasName || graph.edges[$0].inferred }.count) / Double(edgePath.count)

        let goalCorrectEnd = request.isDeparture
            ? (graph.node(goalNode)?.runwayRef?.uppercased() == request.assignedRunwayIdent?.uppercased())
            : true

        let (confidence, score, notes) = gradeConfidence(namedFraction: namedFraction,
                                                          snapMeters: snapMeters,
                                                          goalMeters: goalMeters,
                                                          midInferred: midInferred,
                                                          crossesBuilding: crossesBuilding,
                                                          crossings: crossings,
                                                          goalCorrectEnd: goalCorrectEnd)

        let destinationLabel: String
        if request.isDeparture {
            destinationLabel = "runway \(request.assignedRunwayIdent ?? "")"
        } else {
            let gate = graph.node(goalNode)?.name ?? request.arrivalGateName ?? ""
            destinationLabel = gate.isEmpty ? "parking" : "gate \(gate)"
        }

        return SurfaceTaxiRoute(isDeparture: request.isDeparture,
                                nodeIDs: nodePath,
                                edgeIDs: edgePath,
                                geometry: geometry,
                                distanceMeters: distance,
                                taxiwaySequence: taxiSeq,
                                crossings: crossings,
                                confidence: confidence,
                                confidenceScore: score,
                                destinationLabel: destinationLabel,
                                holdShortRunway: request.isDeparture ? request.assignedRunwayIdent : nil,
                                arrivalGate: request.isDeparture ? nil : graph.node(goalNode)?.name,
                                startCoordinate: geometry.first ?? GeoCoordinate(request.startCoordinate),
                                endCoordinate: geometry.last ?? GeoCoordinate(nodeCoord(goalNode)),
                                usedInferredConnectorMidRoute: midInferred,
                                unnamedSegmentCount: unnamed,
                                notes: notes)
    }

    private func crossingConfidence(point: GeoCoordinate, named: Bool) -> SurfaceConfidence {
        let hasMappedHold = model.holdingPositions.contains {
            !$0.inferred && SurfaceGeometry.distanceMeters(point.clLocation, $0.coordinate.clLocation) < 90
        }
        if hasMappedHold && named { return .high }
        if hasMappedHold || named { return .medium }
        return .low
    }

    private func gradeConfidence(namedFraction: Double, snapMeters: Double, goalMeters: Double,
                                 midInferred: Bool, crossesBuilding: Bool, crossings: [RouteCrossing],
                                 goalCorrectEnd: Bool) -> (SurfaceConfidence, Double, [String]) {
        var score = 1.0
        var notes: [String] = []
        if snapMeters > 120 { score -= 0.35; notes.append("aircraft is far from the mapped surface") }
        else if snapMeters > 60 { score -= 0.12 }
        if goalMeters > 200 { score -= 0.15; notes.append("runway hold point is approximate") }
        score -= (1 - namedFraction) * 0.45
        if namedFraction < 0.999 { notes.append("route includes unnamed taxiway segments") }
        if midInferred { score -= 0.3; notes.append("route relies on an inferred connector") }
        if crossesBuilding { score -= 0.3; notes.append("gate lead-in passes through a building footprint") }
        if !goalCorrectEnd { score -= 0.25; notes.append("could not confirm the assigned runway end") }
        if crossings.contains(where: { $0.confidence == .low }) {
            score -= 0.15; notes.append("a runway crossing has uncertain geometry")
        }

        // High confidence requires the strong conditions.
        let strong = namedFraction >= 0.7 && !model.holdingPositions.isEmpty
            && snapMeters <= 60 && goalCorrectEnd && !midInferred && !crossesBuilding
            && !crossings.contains(where: { $0.confidence == .low })

        var confidence: SurfaceConfidence
        if score >= 0.8 && strong { confidence = .high }
        else if score >= 0.55 { confidence = .medium }
        else if score >= 0.3 { confidence = .low }
        else { confidence = .unavailable }
        // Never report High when the dataset itself is weak.
        if confidence == .high && model.confidence < .medium { confidence = .medium }
        return (confidence, max(0, score), notes)
    }
}

/// A minimal binary min-heap keyed on a Double priority, for A*.
private struct MinHeap {
    private var items: [(priority: Double, node: Int)] = []

    mutating func push(_ priority: Double, _ node: Int) {
        items.append((priority, node))
        var child = items.count - 1
        while child > 0 {
            let parent = (child - 1) / 2
            if items[child].priority < items[parent].priority {
                items.swapAt(child, parent); child = parent
            } else { break }
        }
    }

    mutating func pop() -> (priority: Double, node: Int)? {
        guard !items.isEmpty else { return nil }
        items.swapAt(0, items.count - 1)
        let top = items.removeLast()
        guard !items.isEmpty else { return top }
        var parent = 0
        let n = items.count
        while true {
            let l = 2 * parent + 1, r = 2 * parent + 2
            var smallest = parent
            if l < n && items[l].priority < items[smallest].priority { smallest = l }
            if r < n && items[r].priority < items[smallest].priority { smallest = r }
            if smallest == parent { break }
            items.swapAt(parent, smallest); parent = smallest
        }
        return top
    }
}
