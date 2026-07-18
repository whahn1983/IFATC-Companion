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

    let graph: SurfaceGraph
    let model: AirportSurfaceModel

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
        guard let start = resolveStart(request), let goal = resolveGoal(request) else { return nil }
        if start.node == goal.node { return nil }

        guard let (nodePath, edgePath) = astar(start: start.node, goal: goal.node,
                                               aircraft: request.aircraft) else { return nil }
        return assemble(nodePath: nodePath, edgePath: edgePath, request: request,
                        startNode: start.node, goalNode: goal.node,
                        snapMeters: start.distanceMeters, goalMeters: goal.distanceMeters)
    }

    // MARK: - Endpoint resolution

    private func resolveStart(_ req: Request) -> (node: Int, distanceMeters: Double)? {
        if req.isDeparture, let gate = req.startGateName, !gate.isEmpty,
           let node = graph.nodes.first(where: { ($0.kind == .gate || $0.kind == .parking)
               && ($0.name?.uppercased() == gate.uppercased()) }) {
            let d = SurfaceGeometry.distanceMeters(req.startCoordinate, node.clLocation)
            return (node.id, d)
        }
        guard let nearest = graph.nearestNode(to: req.startCoordinate) else { return nil }
        return (nearest.node.id, nearest.distanceMeters)
    }

    private func resolveGoal(_ req: Request) -> (node: Int, distanceMeters: Double)? {
        if req.isDeparture {
            guard let ident = req.assignedRunwayIdent, !ident.isEmpty else { return nil }
            // Prefer the runway-entry node for the assigned end (full-length departure).
            if let entry = graph.nodes.first(where: { $0.kind == .runwayEntry
                && ($0.runwayRef?.uppercased() == ident.uppercased()) }) {
                return (entry.id, 0)
            }
            // Then a holding position for that runway.
            if let hold = graph.nodes.first(where: { $0.kind == .holdingPosition
                && ($0.runwayRef?.uppercased() == ident.uppercased()) }) {
                return (hold.id, 0)
            }
            // Fall back to the nearest node to the runway-end threshold.
            if let end = model.runwayEnd(ident: ident),
               let nearest = graph.nearestNode(to: end.threshold.clLocation, maxMeters: 300) {
                return (nearest.node.id, nearest.distanceMeters)
            }
            return nil
        } else {
            let gate = (req.arrivalGateName ?? "").trimmingCharacters(in: .whitespaces)
            if !gate.isEmpty, let node = graph.nodes.first(where: {
                ($0.kind == .gate || $0.kind == .parking) && ($0.name?.uppercased() == gate.uppercased()) }) {
                return (node.id, 0)
            }
            // No named gate found: nearest parking/gate to the airport reference.
            let ref = model.reference.clLocation
            if let node = graph.nodes
                .filter({ $0.kind == .gate || $0.kind == .parking })
                .min(by: { SurfaceGeometry.distanceMeters(ref, $0.clLocation) < SurfaceGeometry.distanceMeters(ref, $1.clLocation) }) {
                return (node.id, 0)
            }
            return nil
        }
    }

    // MARK: - A*

    private func nodeCoord(_ id: Int) -> CLLocationCoordinate2D { graph.nodes[id].clLocation }

    private func astar(start: Int, goal: Int, aircraft: AircraftSizeClass) -> (nodes: [Int], edges: [Int])? {
        var gScore: [Int: Double] = [start: 0]
        var cameFrom: [Int: (node: Int, edge: Int)] = [:]
        var arrivedBy: [Int: Int] = [:]
        var closed = Set<Int>()
        var heap = MinHeap()
        heap.push(heuristic(start, goal), start)

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
                let cost = edgeCost(e, at: u, incomingEdge: incoming, start: start, goal: goal, aircraft: aircraft)
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
        while cur != start, let step = cameFrom[cur] {
            edgePath.append(step.edge)
            nodePath.append(step.node)
            cur = step.node
        }
        guard cur == start else { return nil }
        nodePath.reverse(); edgePath.reverse()
        return (nodePath, edgePath)
    }

    private func heuristic(_ a: Int, _ b: Int) -> Double {
        SurfaceGeometry.distanceMeters(nodeCoord(a), nodeCoord(b))
    }

    private func edgeCost(_ e: SurfaceEdge, at u: Int, incomingEdge: Int?,
                          start: Int, goal: Int, aircraft: AircraftSizeClass) -> Double {
        if e.closed { return .infinity }
        // Never taxi onto a runway surface lengthwise (entry / back-taxi / occupancy).
        // A crossing edge is allowed (heavily penalized); a runway-entry edge is not.
        if e.runwayOccupancy && e.runwayCrossing == nil { return .infinity }

        var cost = max(e.distanceMeters, 1)
        if e.runwayCrossing != nil { cost += crossingPenalty }
        let touchesEndpoint = e.from == start || e.to == start || e.from == goal || e.to == goal
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
                          startNode: Int, goalNode: Int,
                          snapMeters: Double, goalMeters: Double) -> SurfaceTaxiRoute {
        // Oriented geometry + taxiway sequence.
        var geometry: [GeoCoordinate] = []
        var taxiSeq: [String] = []
        var unnamed = 0
        var midInferred = false
        for (i, edgeIdx) in edgePath.enumerated() {
            let e = graph.edges[edgeIdx]
            let fromNode = nodePath[i]
            let oriented = (e.from == fromNode) ? e.geometry : Array(e.geometry.reversed())
            if geometry.isEmpty { geometry.append(contentsOf: oriented) }
            else { geometry.append(contentsOf: oriented.dropFirst()) }
            if !e.taxiwayName.isEmpty, taxiSeq.last != e.taxiwayName { taxiSeq.append(e.taxiwayName) }
            if !e.hasName && !e.inferred { unnamed += 1 }
            if e.inferred, !(e.from == startNode || e.to == startNode || e.from == goalNode || e.to == goalNode) {
                midInferred = true
            }
        }

        let fullLine = geometry.clLocations
        // Runway crossings along the route.
        var crossings: [RouteCrossing] = []
        for edgeIdx in edgePath {
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
                                 midInferred: Bool, crossings: [RouteCrossing],
                                 goalCorrectEnd: Bool) -> (SurfaceConfidence, Double, [String]) {
        var score = 1.0
        var notes: [String] = []
        if snapMeters > 120 { score -= 0.35; notes.append("aircraft is far from the mapped surface") }
        else if snapMeters > 60 { score -= 0.12 }
        if goalMeters > 200 { score -= 0.15; notes.append("runway hold point is approximate") }
        score -= (1 - namedFraction) * 0.45
        if namedFraction < 0.999 { notes.append("route includes unnamed taxiway segments") }
        if midInferred { score -= 0.3; notes.append("route relies on an inferred connector") }
        if !goalCorrectEnd { score -= 0.25; notes.append("could not confirm the assigned runway end") }
        if crossings.contains(where: { $0.confidence == .low }) {
            score -= 0.15; notes.append("a runway crossing has uncertain geometry")
        }

        // High confidence requires the strong conditions.
        let strong = namedFraction >= 0.7 && !model.holdingPositions.isEmpty
            && snapMeters <= 60 && goalCorrectEnd && !midInferred
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
