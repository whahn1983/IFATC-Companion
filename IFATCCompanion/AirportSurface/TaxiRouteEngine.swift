import Foundation
import CoreLocation

/// A best-effort taxi-routing engine over the airport surface graph.
///
/// Uses A* with a great-circle heuristic, but **never** chooses purely on shortest
/// distance. Strong penalties push routes away from unnecessary runway crossings,
/// active-runway back-taxi / runway occupancy, disconnected jumps, inferred apron
/// shortcuts, closed taxiways, aircraft-incompatible or unnamed low-confidence
/// segments, and sharp turns; it prefers named, connected, high-confidence geometry,
/// full-length runway entry, and fewer crossings. When the aircraft's heading is known
/// the route also **starts in the direction the aircraft is pointing** — it won't open with
/// a 180° pivot in place, instead setting off forward and turning around farther along if
/// the goal is behind — and a small per-turn cost makes it prefer routes with **fewer**
/// turns over ones that step down through many small sequential turns. A **departure** never
/// crosses its own runway, and never taxis the long way around it either: when every mapped
/// hold/entry for the assigned end sits on the far side, the route instead rolls up to the
/// nearest taxiway that crosses the runway toward the assigned end and holds short there —
/// that crossing threshold *is* the departure hold. Output confidence is graded so the caller
/// can suppress overly precise instructions when the data is weak.
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
    /// Charged once for **every** ordinary direction change at a junction (a turn past
    /// `minTurnDegrees` but not sharp enough for the tiers above). Distance alone doesn't
    /// distinguish a route that "steps down" a series of small alternating turns from one
    /// that reaches the same place with a single left and a single right; this per-turn cost
    /// makes the router prefer the route with **fewer** turns when the distances are close.
    private let perTurnPenalty = 150.0
    /// Direction changes below this (degrees) read as taxiing straight through a junction /
    /// a gentle merge, not a turn, so they carry no per-turn cost.
    private let minTurnDegrees = 30.0
    /// Charged when the route would **begin** by reversing against the aircraft's current
    /// heading — a 180° pivot in place. Large (larger than a runway crossing) so the route
    /// instead sets off the way the aircraft is pointing and turns around farther along if it
    /// must, but finite so a genuinely unavoidable reversal (e.g. off a dead-end exit) still
    /// routes rather than failing.
    private let uTurnPenalty = 5_000.0
    /// How far (degrees) an intended direction of travel must differ from the aircraft's
    /// heading to count as reversing against it. Two ends of the edge under the aircraft are
    /// ~180° apart, so this cleanly separates the endpoint ahead from the one behind.
    private let reverseHeadingThreshold = 120.0
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

    /// How far (m) before a runway-centerline crossing the hold-short point sits along the
    /// route. Used both for a mid-route crossing's `holdShortPoint` and — for a **departure**
    /// that would otherwise cross its own runway — as the point the route is cut back to so it
    /// stops on the near side and holds short (see `assemble`).
    private let holdShortLeadMeters = 25.0

    /// How far (m) off a runway's centerline a point must sit before it is credited to one side
    /// of that runway. Comfortably wider than a runway half-width, so a node on the pavement or
    /// off the end of the centerline (a full-length entry at the threshold) reads as "neither
    /// side" rather than being mistaken for the far side.
    private let runwaySideToleranceMeters = 30.0

    /// Metres per degree of latitude — used only to give the signed side-of-runway offset a
    /// magnitude in metres so `runwaySideToleranceMeters` is meaningful.
    private let metersPerDegree = 111_320.0

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
        /// The aircraft's current heading (degrees true), when it is under way on the surface.
        /// Used to start the route in the direction the aircraft is pointing instead of pivoting
        /// 180° in place. Ignored while parked at a stand (the parked orientation isn't the taxi
        /// direction) and absent (`nil`) means heading isn't considered — routing is unchanged.
        var aircraftHeadingDegrees: Double? = nil
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
        // Start nodes whose *first* leaving edge must respect the aircraft's heading (a live
        // node snap under way). The edge-start case bakes the heading preference into the seed
        // costs below instead, so it contributes nothing here.
        var headingStartNodes = Set<Int>()
        switch anchor {
        case let .node(id, distance, headingApplies):
            seeds = [(id, 0)]
            snapMeters = distance
            if headingApplies, request.aircraftHeadingDegrees != nil { headingStartNodes.insert(id) }
        case let .edge(edgeIndex, projection, alongFromFrom, perpMeters):
            let e = graph.edges[edgeIndex]
            let fromCost = max(0, alongFromFrom)
            let toCost = max(0, e.distanceMeters - alongFromFrom)
            var edgeSeeds = [(node: e.from, cost: fromCost), (node: e.to, cost: toCost)]
            // Respect the aircraft's heading: leaving the edge toward the endpoint that lies
            // *behind* the aircraft is a 180° pivot in place. Drop that endpoint as a seed (not
            // merely penalize it — both endpoints are start nodes, so a penalized backward seed
            // still reconstructs as the backward lead-in) so A* sets off toward the endpoint the
            // aircraft is already pointing at; it reaches a goal that lies behind by taxiing
            // forward and turning around farther along. Keep both when neither/both endpoints are
            // clearly behind (heading roughly across the edge) so routing still succeeds. Because
            // the two endpoints sit on one line through the projection, at most one is "behind".
            if let hdg = request.aircraftHeadingDegrees {
                let fromReversing = Geo.headingDifference(hdg, Geo.bearing(from: projection.clLocation, to: nodeCoord(e.from))) > reverseHeadingThreshold
                let toReversing = Geo.headingDifference(hdg, Geo.bearing(from: projection.clLocation, to: nodeCoord(e.to))) > reverseHeadingThreshold
                if fromReversing && !toReversing { edgeSeeds = [(node: e.to, cost: toCost)] }
                else if toReversing && !fromReversing { edgeSeeds = [(node: e.from, cost: fromCost)] }
            }
            seeds = edgeSeeds
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
        //
        // A goal the route already starts at is skipped as degenerate — except a hold-short at a
        // crossing, whose route is the roll-out from that node up to the runway. Without the
        // exception, recalculating while parked on the junction of the crossing taxiway would
        // fall through to a far-side hold and taxi all the way around.
        let goals = resolveGoalCandidates(request).filter {
            $0.holdShortCrossingEdge != nil || !startNodes.contains($0.node)
        }
        var attempts = 0
        for goal in goals {
            if attempts >= maxGoalAttempts { break }
            attempts += 1
            guard let result = astar(starts: seeds, goal: goal.node,
                                     aircraft: request.aircraft,
                                     heading: request.aircraftHeadingDegrees,
                                     headingStartNodes: headingStartNodes) else { continue }
            let lead = leadIn(for: anchor, startNode: result.startNode)
            return assemble(nodePath: result.nodes, edgePath: result.edges, request: request,
                            startNodes: startNodes, goalNode: goal.node,
                            leadIn: lead.geometry, leadInName: lead.name,
                            leadInCrossingEdges: lead.crossingEdges,
                            snapMeters: snapMeters, goalMeters: goal.distanceMeters,
                            holdShortCrossingEdge: goal.holdShortCrossingEdge)
        }
        return nil
    }

    /// The plain node snap used to recover when an edge snap reaches no goal: the nearest
    /// connected node, or failing that the nearest node of any kind.
    private func nodeAnchorFallback(to coord: CLLocationCoordinate2D) -> StartAnchor? {
        if let connected = nearestConnectedNode(to: coord) {
            return .node(id: connected.node.id, distanceMeters: connected.distanceMeters, headingApplies: true)
        }
        guard let nearest = graph.nearestNode(to: coord) else { return nil }
        return .node(id: nearest.node.id, distanceMeters: nearest.distanceMeters, headingApplies: true)
    }

    // MARK: - Endpoint resolution

    /// Where a route begins on the graph. A `node` start anchors the first leg exactly at a
    /// graph node (parked at the stand, or the aircraft sitting essentially on top of a node);
    /// an `edge` start places the aircraft partway along a connected edge so the route can
    /// begin *under the aircraft* and join the network at whichever endpoint routes best.
    private enum StartAnchor {
        /// `headingApplies` is true when the node is the aircraft's live position under way (so
        /// the first edge should respect its heading) and false when it's the parked stand
        /// (whose orientation isn't the taxi direction).
        case node(id: Int, distanceMeters: Double, headingApplies: Bool)
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
            // Heading doesn't apply at the stand — the parked orientation isn't the direction
            // the aircraft will taxi once pushed back.
            if d <= gateAnchorMeters { return .node(id: node.id, distanceMeters: d, headingApplies: false) }
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
            return .node(id: connected.node.id, distanceMeters: connected.distanceMeters, headingApplies: true)
        }
        guard let nearest = graph.nearestNode(to: req.startCoordinate) else { return nil }
        return .node(id: nearest.node.id, distanceMeters: nearest.distanceMeters, headingApplies: true)
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

    /// Where a route is allowed to end.
    private struct GoalCandidate {
        /// The graph node A* routes to.
        var node: Int
        /// Distance (m) from the ideal hold point to `node`, for confidence grading. Zero when
        /// the node *is* a mapped hold / entry for the assigned end.
        var distanceMeters: Double
        /// When set, the route does not stop at `node`: it rolls on out along this
        /// runway-crossing edge and stops at the hold-short of the assigned runway on it. Used
        /// when the assigned end has no mapped hold on the aircraft's side of the runway (see
        /// `crossingHoldGoals`). The crossing edge itself is never taxied across.
        var holdShortCrossingEdge: Int? = nil
    }

    /// Goal candidates for the route, best first, so `route` can fall through to the next one
    /// when the top choice is unreachable (stranded in a disconnected graph patch). Departure:
    /// full-length runway-entry node(s) for the assigned end — nearest the threshold first —
    /// then holding positions for that end (an intersection departure), then plain taxi nodes
    /// near the runway-end threshold as a last resort; the whole list is then re-ordered so
    /// candidates on the aircraft's side of the runway come first, with hold-short-at-a-crossing
    /// goals ahead of anything stranded on the far side (see `crossingHoldGoals`).
    /// Runway-ident matching is tolerant of leading-zero padding, so an assigned "9L" matches
    /// OSM-tagged "09L". Arrival: the named gate, else the nearest parking/gate to the airport
    /// reference (a single choice, as before).
    private func resolveGoalCandidates(_ req: Request) -> [GoalCandidate] {
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
            return prioritizingAircraftSide(out, assignedEnd: assignedEnd, start: req.startCoordinate)
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
            return out.map { GoalCandidate(node: $0.node, distanceMeters: $0.distanceMeters) }
        }
    }

    /// Re-orders departure goals so the aircraft never taxis the length of the field and around
    /// a runway end to reach a hold tagged on the *far* side. Candidates on the aircraft's side
    /// of the runway keep their existing priority and come first; then — when the far side holds
    /// anything at all — hold-short goals at the taxiways that cross the runway toward the
    /// assigned end; then the far-side candidates, still available if nothing nearer routes.
    ///
    /// Left unchanged when the runway end is unknown, when the aircraft sits essentially on the
    /// runway centerline (its side can't be told), or when no candidate is on the far side.
    private func prioritizingAircraftSide(_ candidates: [(node: Int, distanceMeters: Double)],
                                          assignedEnd: SurfaceRunwayEnd?,
                                          start: CLLocationCoordinate2D) -> [GoalCandidate] {
        func plain(_ list: [(node: Int, distanceMeters: Double)]) -> [GoalCandidate] {
            list.map { GoalCandidate(node: $0.node, distanceMeters: $0.distanceMeters) }
        }
        guard let assignedEnd else { return plain(candidates) }
        let startOffset = signedOffsetFromRunway(start, end: assignedEnd)
        guard abs(startOffset) > runwaySideToleranceMeters else { return plain(candidates) }
        let side: Double = startOffset > 0 ? 1 : -1

        func isFarSide(_ nodeID: Int) -> Bool {
            guard graph.nodes.indices.contains(nodeID) else { return false }
            return signedOffsetFromRunway(nodeCoord(nodeID), end: assignedEnd) * side < -runwaySideToleranceMeters
        }
        let farSide = candidates.filter { isFarSide($0.node) }
        guard !farSide.isEmpty else { return plain(candidates) }
        let nearSide = candidates.filter { !isFarSide($0.node) }
        // A crossing whose near endpoint is already a candidate adds nothing — that node is
        // tried first anyway — so it is dropped rather than probed twice.
        let nearSideNodes = Set(nearSide.map { $0.node })
        let crossingGoals = crossingHoldGoals(assignedEnd: assignedEnd, side: side)
            .filter { !nearSideNodes.contains($0.node) }
        return plain(nearSide) + crossingGoals + plain(farSide)
    }

    /// Hold-short goals at the taxiways that cross the assigned runway, nearest its threshold
    /// first — the near-side endpoint of each crossing taxiway, carrying the crossing edge so
    /// the route can roll on out along it and stop short of the runway (see `holdShortStub`).
    /// Only crossings on the assigned half of the runway qualify: holding short at one past
    /// midfield would put the aircraft on the runway with too little of it left to depart from.
    private func crossingHoldGoals(assignedEnd: SurfaceRunwayEnd, side: Double) -> [GoalCandidate] {
        let keys = assignedRunwayKeys(for: assignedEnd.ident)
        let threshold = assignedEnd.threshold.clLocation
        let opposite = assignedEnd.oppositeThreshold.clLocation
        var found: [(candidate: GoalCandidate, toThreshold: Double)] = []
        for idx in graph.edges.indices {
            let e = graph.edges[idx]
            guard !e.closed, let ident = e.runwayCrossing, let crossingPoint = e.crossingPoint,
                  keys.contains(runwayKey(ident)) else { continue }
            let toThreshold = SurfaceGeometry.distanceMeters(crossingPoint.clLocation, threshold)
            guard toThreshold <= SurfaceGeometry.distanceMeters(crossingPoint.clLocation, opposite) else { continue }
            guard let nearNode = nearSideEndpoint(of: e, end: assignedEnd, side: side),
                  holdShortStub(onEdge: idx, from: nearNode) != nil else { continue }
            found.append((GoalCandidate(node: nearNode, distanceMeters: 0, holdShortCrossingEdge: idx), toThreshold))
        }
        return found.sorted { $0.toThreshold < $1.toThreshold }.map { $0.candidate }
    }

    /// The endpoint of a runway-crossing edge that lies on `side` of the runway while the other
    /// endpoint lies across it — i.e. the node the aircraft can reach without crossing. Nil when
    /// the edge doesn't straddle the runway cleanly (both or neither endpoint on that side), so
    /// an ambiguous crossing never becomes a hold.
    private func nearSideEndpoint(of edge: SurfaceEdge, end: SurfaceRunwayEnd, side: Double) -> Int? {
        guard graph.nodes.indices.contains(edge.from), graph.nodes.indices.contains(edge.to) else { return nil }
        let fromNear = signedOffsetFromRunway(nodeCoord(edge.from), end: end) * side > runwaySideToleranceMeters
        let toNear = signedOffsetFromRunway(nodeCoord(edge.to), end: end) * side > runwaySideToleranceMeters
        if fromNear && !toNear { return edge.from }
        if toNear && !fromNear { return edge.to }
        return nil
    }

    /// Signed perpendicular offset (m) of `p` from the runway centerline extended infinitely
    /// through both thresholds — positive on one side, negative on the other. Only the sign
    /// carries meaning; the magnitude exists so `runwaySideToleranceMeters` can dismiss points
    /// that are on the pavement, or off the end of the runway, as belonging to neither side.
    private func signedOffsetFromRunway(_ p: CLLocationCoordinate2D, end: SurfaceRunwayEnd) -> Double {
        let a = end.threshold.clLocation, b = end.oppositeThreshold.clLocation
        // Longitude scaled by cos(lat) so the two axes share a unit, as elsewhere in the
        // surface layer's planar math.
        let cosLat = max(0.2, cos(a.latitude * .pi / 180))
        let ax = a.longitude * cosLat, ay = a.latitude
        let bx = b.longitude * cosLat, by = b.latitude
        let px = p.longitude * cosLat, py = p.latitude
        let dx = bx - ax, dy = by - ay
        let length = (dx * dx + dy * dy).squareRoot()
        guard length > 1e-12 else { return 0 }
        return ((dx * (py - ay) - dy * (px - ax)) / length) * metersPerDegree
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
                       aircraft: AircraftSizeClass, heading: Double?,
                       headingStartNodes: Set<Int>) -> (nodes: [Int], edges: [Int], startNode: Int)? {
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
                let cost = edgeCost(e, at: u, incomingEdge: incoming, startNodes: startNodes, goal: goal,
                                    aircraft: aircraft, heading: heading, headingStartNodes: headingStartNodes)
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
                          startNodes: Set<Int>, goal: Int, aircraft: AircraftSizeClass,
                          heading: Double?, headingStartNodes: Set<Int>) -> Double {
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
        if let inc = incomingEdge {
            cost += turnPenalty(incoming: inc, outgoing: e, at: u)
        } else if let hdg = heading, headingStartNodes.contains(u) {
            // The very first edge off the aircraft's live position: discourage leaving in a
            // direction that reverses its heading, so the route sets off the way the aircraft
            // is pointing rather than making it spin around where it sits.
            let other = (e.from == u) ? e.to : e.from
            let outBearing = Geo.bearing(from: nodeCoord(u), to: nodeCoord(other))
            if Geo.headingDifference(hdg, outBearing) > reverseHeadingThreshold { cost += uTurnPenalty }
        }
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
        // Every ordinary turn costs a little, so a route that reaches the destination with
        // fewer turns (one left, one right) beats one that steps down through many small
        // sequential turns of otherwise-similar length.
        if turn > minTurnDegrees { return perTurnPenalty }
        return 0
    }

    // MARK: - Assembly + confidence

    private func assemble(nodePath: [Int], edgePath: [Int], request: Request,
                          startNodes: Set<Int>, goalNode: Int,
                          leadIn: [GeoCoordinate], leadInName: String?, leadInCrossingEdges: [Int],
                          snapMeters: Double, goalMeters: Double,
                          holdShortCrossingEdge: Int? = nil) -> SurfaceTaxiRoute {
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

        // The assigned end has no mapped hold on this side of the runway, so rather than taxi
        // around to one on the far side the route ends by rolling out along the taxiway that
        // crosses the runway toward that end and stopping short of it — that crossing threshold
        // *is* the departure hold. Only the geometry (and the taxiway's name) extends; the
        // crossing edge is never taxied across, so it stays out of nodeIDs/edgeIDs and is not
        // reported below as a crossing.
        var holdShortAtCrossing = false
        if let crossingEdge = holdShortCrossingEdge,
           let stub = holdShortStub(onEdge: crossingEdge, from: goalNode) {
            if geometry.isEmpty {
                geometry = stub
            } else if let last = geometry.last,
                      SurfaceGeometry.distanceMeters(last.clLocation, stub[0].clLocation) < 0.5 {
                geometry.append(contentsOf: stub.dropFirst())   // the goal node, already there
            } else {
                geometry.append(contentsOf: stub)
            }
            let name = graph.edges[crossingEdge].taxiwayName
            if !name.isEmpty, taxiSeq.last != name { taxiSeq.append(name) }
            holdShortAtCrossing = true
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
            let holdShort = SurfaceGeometry.pointAlong(fullLine, meters: max(0, along - holdShortLeadMeters)).map(GeoCoordinate.init) ?? cp
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

        // A departure never crosses its own runway. When A* could only reach a hold/entry
        // tagged on the *far* side by crossing the assigned runway, stop at the near-side
        // hold-short of that crossing — that point *is* the departure hold — and drop the
        // crossing (and anything beyond it) from the route. Without this the drawn route ran
        // across the active departure runway and placed the hold on the far side.
        var routeNodes = nodePath
        var routeEdges = edgePath
        if request.isDeparture, let assignedIdent = request.assignedRunwayIdent, !assignedIdent.isEmpty {
            let assignedKeys = assignedRunwayKeys(for: assignedIdent)
            if let depCross = crossings.first(where: { assignedKeys.contains(runwayKey($0.runwayIdent)) }) {
                let cut = max(0, depCross.alongMeters - holdShortLeadMeters)
                geometry = truncatedPolyline(geometry, atMeters: cut)
                // Keep only crossings that remain on the shortened route (other runways crossed
                // before the departure runway); drop the departure crossing and any beyond it.
                crossings = crossings.filter { $0.alongMeters < cut }
                for i in crossings.indices { crossings[i].index = i }
                let leadInPrefix = leadIn.count >= 2 ? leadInName : nil
                // Trim the node/edge path so it stays contiguous and ends on the near side, and
                // rebuild the spoken taxiway sequence so it names the taxiways actually taxied up
                // to the hold — including the crossing edge (the pilot rolls onto it to hold
                // short) but not the dropped far-side taxiways beyond the runway.
                if let ci = routeEdges.firstIndex(of: depCross.edgeID) {
                    taxiSeq = namedTaxiwaySequence(edges: Array(edgePath.prefix(ci + 1)), leadInName: leadInPrefix)
                    routeNodes = Array(routeNodes.prefix(ci + 1))
                    routeEdges = Array(routeEdges.prefix(ci))
                } else {
                    // The crossing rode in on the lead-in edge (the aircraft is already on the
                    // runway's approach), so the route holds short at once with no routed edges.
                    taxiSeq = namedTaxiwaySequence(edges: [], leadInName: leadInPrefix)
                    routeNodes = Array(routeNodes.prefix(1))
                    routeEdges = []
                }
            }
        }

        let distance = SurfaceGeometry.pathLengthMeters(geometry.clLocations)
        let namedFraction = routeEdges.isEmpty ? 0 :
            Double(routeEdges.filter { graph.edges[$0].hasName || graph.edges[$0].inferred }.count) / Double(routeEdges.count)

        // A hold-short at a crossing was chosen *because* it belongs to the assigned runway on
        // the assigned half, so the end is confirmed even though the node carries no ident.
        let goalCorrectEnd = request.isDeparture
            ? (holdShortAtCrossing
               || graph.node(goalNode)?.runwayRef?.uppercased() == request.assignedRunwayIdent?.uppercased())
            : true

        let graded = gradeConfidence(namedFraction: namedFraction,
                                     snapMeters: snapMeters,
                                     goalMeters: goalMeters,
                                     midInferred: midInferred,
                                     crossesBuilding: crossesBuilding,
                                     crossings: crossings,
                                     goalCorrectEnd: goalCorrectEnd)
        let confidence = graded.confidence
        let score = graded.score
        var notes = graded.notes
        if holdShortAtCrossing {
            notes.append("holding short at the taxiway crossing — no mapped hold on this side of the runway")
        }

        let destinationLabel: String
        if request.isDeparture {
            destinationLabel = "runway \(request.assignedRunwayIdent ?? "")"
        } else {
            let gate = graph.node(goalNode)?.name ?? request.arrivalGateName ?? ""
            destinationLabel = gate.isEmpty ? "parking" : "gate \(gate)"
        }

        return SurfaceTaxiRoute(isDeparture: request.isDeparture,
                                nodeIDs: routeNodes,
                                edgeIDs: routeEdges,
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

    /// The de-duplicated named taxiway sequence for a list of routed edges, with the lead-in
    /// taxiway name (when the route began on a snapped edge) prefixed. Used to rebuild the spoken
    /// sequence after a departure route is shortened to hold short of its own runway, so the
    /// dropped far-side taxiways are no longer named.
    private func namedTaxiwaySequence(edges: [Int], leadInName: String?) -> [String] {
        var seq: [String] = []
        for idx in edges where graph.edges.indices.contains(idx) {
            let name = graph.edges[idx].taxiwayName
            if !name.isEmpty, seq.last != name { seq.append(name) }
        }
        if let leadInName, !leadInName.isEmpty, seq.first != leadInName { seq.insert(leadInName, at: 0) }
        return seq
    }

    /// The stretch of a runway-crossing taxiway from its near-side endpoint `node` up to the
    /// hold-short a short distance before the runway centerline, oriented away from `node` so it
    /// appends straight onto the routed geometry. Nil when the crossing is already within the
    /// hold-short lead of `node` — the node itself is then the hold, and no stub is needed.
    private func holdShortStub(onEdge edgeIndex: Int, from node: Int) -> [GeoCoordinate]? {
        guard graph.edges.indices.contains(edgeIndex) else { return nil }
        let edge = graph.edges[edgeIndex]
        guard let crossingPoint = edge.crossingPoint, edge.geometry.count >= 2,
              edge.from == node || edge.to == node else { return nil }
        let oriented = (edge.from == node) ? edge.geometry : Array(edge.geometry.reversed())
        guard let along = SurfaceGeometry.nearestPointOnPath(crossingPoint.clLocation, oriented.clLocations)?.alongMeters
        else { return nil }
        let cut = max(0, along - holdShortLeadMeters)
        guard cut > 1 else { return nil }
        let stub = truncatedPolyline(oriented, atMeters: cut)
        return stub.count >= 2 ? stub : nil
    }

    /// Canonical idents (both ends) of the physical runway the assigned end belongs to, so a
    /// crossing of *that* runway is recognized no matter which end the crossing was tagged with
    /// or how it is zero-padded — the crossing ident is a runway's `idents.first` ("16L") while
    /// the departure may be assigned the reciprocal ("34R"). Falls back to just the assigned
    /// ident when the runway isn't present in the model.
    private func assignedRunwayKeys(for assignedIdent: String) -> Set<String> {
        let key = runwayKey(assignedIdent)
        if let runway = model.runways.first(where: { $0.idents.contains { runwayKey($0) == key } }) {
            return Set(runway.idents.map { runwayKey($0) })
        }
        return [key]
    }

    /// `line` cut to its first `meters` of length, ending exactly at that point — used to stop a
    /// departure route at the hold-short of its own runway. Always returns at least two points
    /// (when the input has two) so the shortened route still renders and tracks.
    private func truncatedPolyline(_ line: [GeoCoordinate], atMeters meters: Double) -> [GeoCoordinate] {
        guard line.count >= 2 else { return line }
        let cl = line.clLocations
        if meters >= SurfaceGeometry.pathLengthMeters(cl) { return line }
        var out: [GeoCoordinate] = [line[0]]
        var accumulated = 0.0
        for i in 1..<line.count {
            let segLen = SurfaceGeometry.distanceMeters(line[i - 1].clLocation, line[i].clLocation)
            if accumulated + segLen < meters {
                out.append(line[i])
                accumulated += segLen
            } else {
                if let p = SurfaceGeometry.pointAlong(cl, meters: meters) { out.append(GeoCoordinate(p)) }
                break
            }
        }
        if out.count < 2 { out.append(line[1]) }
        return out
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
                                 goalCorrectEnd: Bool) -> (confidence: SurfaceConfidence, score: Double, notes: [String]) {
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
