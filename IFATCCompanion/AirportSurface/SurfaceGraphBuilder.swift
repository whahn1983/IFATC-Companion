import Foundation
import CoreLocation

/// Builds the connected airport surface graph from a normalized `AirportSurfaceModel`.
///
/// Topology strategy: intersecting OSM taxiways share an identical vertex coordinate
/// (that is how OSM models a junction), so taxiway vertices are snapped to a ~1.1 m
/// grid and merged into shared nodes. Edges run between junction/endpoint nodes and
/// carry the full intermediate geometry, name, distance, and original OSM ids.
///
/// Runway crossings are detected by intersecting taxiway edge geometry with runway
/// centerlines; an intersection near a runway end threshold is treated as a runway
/// *entry* (line-up), elsewhere as a *crossing*. Holding positions, runway entries, and
/// gates/parking are attached to the graph; gate lead-ins are marked as inferred, lower
/// confidence connectors. Nothing here assumes OSM matches Infinite Flight scenery.
enum SurfaceGraphBuilder {

    static let runwayEndThresholdMeters = 90.0   // intersection within this of a threshold = entry
    static let holdAttachMeters = 60.0
    static let runwayEntryAttachMeters = 160.0
    static let gateAttachMeters = 240.0

    /// Added to a candidate gate connector's score when its straight lead-in would pass
    /// through a building/terminal. Large enough that any clear node inside the attach
    /// radius always beats a concourse-crossing one.
    static let buildingConnectorPenaltyMeters = 5_000.0
    /// Added per meter the lead-in actually spends *inside* a footprint, on top of the flat
    /// penalty. The flat term keeps every clear candidate ahead of every crossing one; this
    /// term orders the crossing candidates among themselves, which is what a stand mapped on
    /// the concourse itself needs. KIAD tags each gate node as a vertex of the Concourse C/D
    /// outline, so *every* candidate lead-in touches the footprint and a flat penalty alone
    /// leaves the choice to raw distance — which at a 33 m-wide concourse is as likely to be
    /// a node on the far side as one on the stand's own.
    static let buildingIntrusionPenaltyPerMeter = 20.0
    /// A lead-in running less than this far inside a footprint is not treated as crossing it:
    /// a stand *on* the outline starts exactly on the boundary, and one heading away from the
    /// building should read as clear rather than as cutting through it.
    static let buildingIntrusionToleranceMeters = 0.5
    /// Added when continuing off the connector onto the taxi network would require a
    /// near-reversal (the lead-in doubles back across the ramp) — a gentle tiebreak toward
    /// a node the stand can leave naturally, deliberately small so it only decides between
    /// otherwise-comparable candidates and never overrides a clearly nearer one.
    static let connectorReversalPenaltyMeters = 150.0
    /// A reversal is a turn sharper than this (degrees) from the connector onto the best
    /// onward taxiway at the node.
    static let connectorReversalDegrees = 120.0
    /// How many nearest taxi nodes to score as gate-connector candidates.
    static let maxGateConnectorCandidates = 8

    static func build(from model: AirportSurfaceModel) -> SurfaceGraph {
        var nodes: [SurfaceNode] = []
        var edges: [SurfaceEdge] = []
        var keyToNodeID: [String: Int] = [:]

        func priority(_ k: SurfaceNodeKind) -> Int {
            switch k {
            case .gate, .parking: return 5
            case .holdingPosition: return 4
            case .runwayEntry, .runwayCrossing: return 3
            case .intersection: return 2
            case .apronConnector: return 1
            case .taxiwayEndpoint: return 0
            }
        }

        func makeNode(at coord: GeoCoordinate, kind: SurfaceNodeKind,
                      osmID: String? = nil, inferred: Bool = false) -> Int {
            let key = SurfaceGeometry.snapKey(coord)
            if let existing = keyToNodeID[key] {
                if priority(kind) > priority(nodes[existing].kind) { nodes[existing].kind = kind }
                if nodes[existing].osmID == nil, let osmID { nodes[existing].osmID = osmID }
                return existing
            }
            let id = nodes.count   // id == array index invariant
            keyToNodeID[key] = id
            nodes.append(SurfaceNode(id: id, coordinate: coord, kind: kind,
                                     runwayRef: nil, name: nil, osmID: osmID, inferred: inferred))
            return id
        }

        func edgeConfidence(_ t: SurfaceTaxiway) -> Double {
            var c = 0.9
            if !t.hasName { c -= 0.35 }
            if t.isTaxilane { c -= 0.1 }
            if t.isClosed { c = 0.1 }
            return max(0.05, min(1, c))
        }

        // Pass 1: count taxiway vertex occurrences → junctions are shared vertices.
        var vertexCount: [String: Int] = [:]
        for twy in model.taxiways {
            for v in twy.geometry { vertexCount[SurfaceGeometry.snapKey(v), default: 0] += 1 }
        }

        // Pass 2: build nodes + edges between junction/endpoint vertices.
        for twy in model.taxiways {
            let geo = twy.geometry
            guard geo.count >= 2 else { continue }
            var currentNodeID = makeNode(at: geo[0], kind: .taxiwayEndpoint)
            var segGeo: [GeoCoordinate] = [geo[0]]
            for i in 1..<geo.count {
                segGeo.append(geo[i])
                let isJunction = vertexCount[SurfaceGeometry.snapKey(geo[i]), default: 0] >= 2
                let isEnd = (i == geo.count - 1)
                guard isJunction || isEnd else { continue }
                let toNodeID = makeNode(at: geo[i], kind: isJunction ? .intersection : .taxiwayEndpoint)
                if toNodeID != currentNodeID && segGeo.count >= 2 {
                    let dist = SurfaceGeometry.pathLengthMeters(segGeo.clLocations)
                    edges.append(SurfaceEdge(id: edges.count, from: currentNodeID, to: toNodeID,
                                             geometry: segGeo, distanceMeters: dist,
                                             taxiwayName: twy.name, hasName: twy.hasName,
                                             isTaxilane: twy.isTaxilane,
                                             runwayCrossing: nil, runwayCrossingName: nil, crossingPoint: nil,
                                             runwayOccupancy: false, oneway: twy.oneway, closed: twy.isClosed,
                                             inferred: false, confidence: edgeConfidence(twy),
                                             osmIDs: [twy.osmID], widthMeters: twy.widthMeters))
                }
                currentNodeID = toNodeID
                segGeo = [geo[i]]
            }
        }

        // Each runway way's directional ends, matched by ident rather than by OSM id: a
        // physical runway split across several OSM ways has its ends attributed to one
        // representative way, so keying by OSM id would leave the stub ways without ends and
        // misclassify a threshold intersection on them as a crossing. Matching by ident gives
        // every way of the same runway the same (merged) ends.
        let endByIdent = Dictionary(
            model.runwayEnds.map { (OSMSurfaceNormalizer.canonicalRunwayIdent($0.ident), $0) },
            uniquingKeysWith: { first, _ in first })
        let endsByRunway: [String: [SurfaceRunwayEnd]] = Dictionary(
            model.runways.map { r in
                (r.osmID, r.idents.compactMap { endByIdent[OSMSurfaceNormalizer.canonicalRunwayIdent($0)] })
            },
            uniquingKeysWith: { first, _ in first })

        // Detect runway crossings / entries on each edge.
        var crossingSites: [(point: GeoCoordinate, ident: String, name: String)] = []
        for eIdx in edges.indices {
            let egeo = edges[eIdx].clGeometry
            guard egeo.count >= 2 else { continue }
            for runway in model.runways {
                let rgeo = runway.centerline.clLocations
                guard rgeo.count >= 2 else { continue }
                var found = false
                for i in 1..<egeo.count where !found {
                    for j in 1..<rgeo.count {
                        guard let p = SurfaceGeometry.segmentIntersection(egeo[i - 1], egeo[i], rgeo[j - 1], rgeo[j]) else { continue }
                        let ends = endsByRunway[runway.osmID] ?? []
                        let nearThreshold = ends.contains { SurfaceGeometry.distanceMeters(p, $0.threshold.clLocation) < runwayEndThresholdMeters }
                        edges[eIdx].runwayOccupancy = true
                        if !nearThreshold {
                            let ident = runway.idents.first ?? runway.displayName
                            edges[eIdx].runwayCrossing = ident
                            edges[eIdx].runwayCrossingName = runway.displayName
                            edges[eIdx].crossingPoint = GeoCoordinate(p)
                            crossingSites.append((GeoCoordinate(p), ident, runway.displayName))
                        }
                        found = true
                        break
                    }
                }
            }
        }

        // Attach mapped holding positions to the nearest taxiway node.
        func nearestNodeIndex(to coord: CLLocationCoordinate2D, kinds: Set<SurfaceNodeKind>, maxMeters: Double) -> Int? {
            var best: (Int, Double)?
            for n in nodes where kinds.contains(n.kind) {
                let d = SurfaceGeometry.distanceMeters(coord, n.clLocation)
                if d <= maxMeters, best == nil || d < best!.1 { best = (n.id, d) }
            }
            return best?.0
        }
        let taxiKinds: Set<SurfaceNodeKind> = [.taxiwayEndpoint, .intersection, .runwayEntry, .holdingPosition]
        for hold in model.holdingPositions {
            if let idx = nearestNodeIndex(to: hold.coordinate.clLocation, kinds: taxiKinds, maxMeters: holdAttachMeters) {
                if priority(.holdingPosition) >= priority(nodes[idx].kind) {
                    nodes[idx].kind = .holdingPosition
                }
                nodes[idx].runwayRef = hold.runwayRef.isEmpty ? nodes[idx].runwayRef : hold.runwayRef
                if nodes[idx].osmID == nil { nodes[idx].osmID = hold.osmID }
                nodes[idx].inferred = nodes[idx].inferred || hold.inferred
            }
        }

        // Mark a runway-entry node for each runway end (nearest taxiway node to threshold).
        for end in model.runwayEnds {
            if let idx = nearestNodeIndex(to: end.threshold.clLocation, kinds: taxiKinds, maxMeters: runwayEntryAttachMeters) {
                if nodes[idx].kind == .taxiwayEndpoint || nodes[idx].kind == .intersection {
                    nodes[idx].kind = .runwayEntry
                }
                if nodes[idx].runwayRef == nil { nodes[idx].runwayRef = end.ident }
            }
        }

        // Inferred holds: a runway-entry node with no mapped holding position nearby
        // becomes an inferred, lower-confidence holding position (for simulation).
        for idx in nodes.indices where nodes[idx].kind == .runwayEntry {
            let coord = nodes[idx].clLocation
            let hasMappedHold = model.holdingPositions.contains {
                !$0.inferred && SurfaceGeometry.distanceMeters(coord, $0.coordinate.clLocation) < holdAttachMeters
            }
            if !hasMappedHold {
                nodes[idx].kind = .holdingPosition
                nodes[idx].inferred = true
            }
        }

        // Building / terminal footprints, with a cheap bounding box each so most stands
        // skip the full polygon test. Gate lead-ins are steered clear of these so a route
        // to a thin-concourse stand doesn't cut straight through the building to reach it.
        let buildingPolys: [(poly: [CLLocationCoordinate2D],
                             box: (minLat: Double, minLon: Double, maxLat: Double, maxLon: Double))] =
            model.buildings.compactMap { b in
                let poly = b.polygon.clLocations
                guard poly.count >= 3, let box = SurfaceGeometry.boundingBox(of: poly) else { return nil }
                return (poly, box)
            }

        // node id → indices of the (real) taxiway edges built so far, for reversal scoring.
        var nodeToEdges: [Int: [Int]] = [:]
        for (idx, e) in edges.enumerated() {
            nodeToEdges[e.from, default: []].append(idx)
            nodeToEdges[e.to, default: []].append(idx)
        }

        /// How far the straight connector a→b runs inside a building footprint, in meters —
        /// the deepest single footprint it passes through rather than the sum, since OSM
        /// routinely maps overlapping `building` and `building:part` outlines over one
        /// structure and summing them would charge the same concourse several times.
        func connectorIntrusionMeters(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D) -> Double {
            guard !buildingPolys.isEmpty else { return 0 }
            let loLat = min(a.latitude, b.latitude), hiLat = max(a.latitude, b.latitude)
            let loLon = min(a.longitude, b.longitude), hiLon = max(a.longitude, b.longitude)
            var deepest = 0.0
            for bp in buildingPolys {
                // AABB reject: skip a building whose box can't overlap the connector's.
                if bp.box.maxLat < loLat || bp.box.minLat > hiLat
                    || bp.box.maxLon < loLon || bp.box.minLon > hiLon { continue }
                deepest = max(deepest, SurfaceGeometry.segmentIntrusionMeters(a, b, bp.poly))
            }
            return deepest
        }

        /// The building term of a candidate's score: nothing when the lead-in stays clear,
        /// otherwise the flat penalty plus what the intrusion itself costs.
        func buildingPenalty(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D) -> (penalty: Double, crosses: Bool) {
            let intrusion = connectorIntrusionMeters(a, b)
            guard intrusion > buildingIntrusionToleranceMeters else { return (0, false) }
            return (buildingConnectorPenaltyMeters + intrusion * buildingIntrusionPenaltyPerMeter, true)
        }

        /// Penalty when leaving the stand onto the taxi network at `node` would require a
        /// near-reversal from the connector's arrival bearing — i.e. the lead-in doubles
        /// back across the ramp instead of feeding the taxiway naturally.
        func reversalPenalty(from gate: CLLocationCoordinate2D, to node: Int) -> Double {
            let incident = nodeToEdges[node] ?? []
            guard !incident.isEmpty else { return 0 }
            let arrival = Geo.bearing(from: gate, to: nodes[node].clLocation)
            var bestTurn = 180.0
            for idx in incident {
                let e = edges[idx]
                let other = (e.from == node) ? e.to : e.from
                guard nodes.indices.contains(other), other != node else { continue }
                let onward = Geo.bearing(from: nodes[node].clLocation, to: nodes[other].clLocation)
                bestTurn = min(bestTurn, Geo.headingDifference(arrival, onward))
            }
            return bestTurn > connectorReversalDegrees ? connectorReversalPenaltyMeters : 0
        }

        // Candidate taxi nodes for a stand, nearest first, capped.
        func connectorCandidates(to coord: CLLocationCoordinate2D) -> [(id: Int, distance: Double)] {
            nodes.filter { taxiKinds.contains($0.kind) }
                .map { (id: $0.id, distance: SurfaceGeometry.distanceMeters(coord, $0.clLocation)) }
                .filter { $0.distance <= gateAttachMeters }
                .sorted { $0.distance < $1.distance }
                .prefix(maxGateConnectorCandidates)
                .map { $0 }
        }

        // A stand normally snaps to the nearest routable *node* within `gateAttachMeters`. But
        // an apron taxilane is often drawn as one long, sparsely-noded OSM way whose *line* runs
        // right past a row of stands while its nearest node is hundreds of metres away — so a
        // stand can sit well within taxi distance of a taxiway yet have no node to attach to
        // (e.g. KDEN's inner Concourse-B gates sit ~260 m from the nearest node on the "Green"
        // apron taxilane, whose centreline passes ~140 m away as a single 800 m+ segment). When
        // no node is in range — or when the only node's lead-in would cut through a terminal —
        // the stand instead attaches to the nearest point *projected onto a taxiway edge*,
        // splitting that edge to insert the junction. Stands that already have a clear node in
        // range keep their exact existing attachment, so well-mapped fields are unchanged.
        struct EdgeAttach { let edgeIndex: Int; let projection: CLLocationCoordinate2D; let alongFromFrom: Double; let perpMeters: Double }

        func edgeAttachCandidates(to coord: CLLocationCoordinate2D) -> [EdgeAttach] {
            var out: [EdgeAttach] = []
            for idx in edges.indices {
                let e = edges[idx]
                // Skip inferred connectors, closed segments, and runway-occupancy/crossing edges
                // (a stand never leads onto a runway, and splitting a crossing edge would drop
                // its crossing metadata).
                if e.inferred || e.closed || e.runwayOccupancy { continue }
                let line = e.clGeometry
                guard line.count >= 2, let proj = SurfaceGeometry.nearestPointOnPath(coord, line),
                      proj.distanceMeters <= gateAttachMeters else { continue }
                out.append(EdgeAttach(edgeIndex: idx, projection: proj.point,
                                      alongFromFrom: proj.alongMeters, perpMeters: proj.distanceMeters))
            }
            return Array(out.sorted { $0.perpMeters < $1.perpMeters }.prefix(maxGateConnectorCandidates))
        }

        /// Reversal penalty for leaving a stand onto a mid-edge projection: the sharpest turn
        /// from the lead-in bearing onto the taxiway (either direction, or forward only when the
        /// edge is one-way) — mirrors `reversalPenalty(from:to:)` for node candidates.
        func edgeReversalPenalty(from gate: CLLocationCoordinate2D, _ c: EdgeAttach) -> Double {
            let e = edges[c.edgeIndex]
            let line = e.clGeometry
            let arrival = Geo.bearing(from: gate, to: c.projection)
            var bestTurn = 180.0
            if let fwd = SurfaceGeometry.pointAlong(line, meters: c.alongFromFrom + 8),
               SurfaceGeometry.distanceMeters(c.projection, fwd) > 0.5 {
                bestTurn = min(bestTurn, Geo.headingDifference(arrival, Geo.bearing(from: c.projection, to: fwd)))
            }
            if !e.oneway, c.alongFromFrom > 0.5,
               let bwd = SurfaceGeometry.pointAlong(line, meters: max(0, c.alongFromFrom - 8)),
               SurfaceGeometry.distanceMeters(c.projection, bwd) > 0.5 {
                bestTurn = min(bestTurn, Geo.headingDifference(arrival, Geo.bearing(from: c.projection, to: bwd)))
            }
            return bestTurn > connectorReversalDegrees ? connectorReversalPenaltyMeters : 0
        }

        func nodeAttachScore(_ gate: CLLocationCoordinate2D, _ c: (id: Int, distance: Double)) -> (score: Double, crosses: Bool) {
            let building = buildingPenalty(gate, nodes[c.id].clLocation)
            let s = c.distance + building.penalty + reversalPenalty(from: gate, to: c.id)
            return (s, building.crosses)
        }
        func edgeAttachScore(_ gate: CLLocationCoordinate2D, _ c: EdgeAttach) -> (score: Double, crosses: Bool) {
            let building = buildingPenalty(gate, c.projection)
            let s = c.perpMeters + building.penalty + edgeReversalPenalty(from: gate, c)
            return (s, building.crosses)
        }

        /// Split real edge `eIdx` at `along` metres from its `from`, inserting a routable node at
        /// `point` and returning its id (or an existing endpoint's id when the projection lands
        /// essentially on one). The original edge index becomes the from→split half and a new
        /// edge is appended for the split→to half; every attribute (name, taxilane, oneway,
        /// confidence, OSM ids, width) is carried onto both halves. Runway-crossing edges are
        /// never passed here (excluded as candidates), so no crossing metadata is lost.
        func splitEdgeForConnector(_ eIdx: Int, at point: CLLocationCoordinate2D, alongFromFrom along: Double) -> Int {
            let e = edges[eIdx]
            if along <= 1.0 { return e.from }
            if along >= e.distanceMeters - 1.0 { return e.to }
            let geo = e.geometry
            var cumulative = [0.0]
            for i in 1..<geo.count {
                cumulative.append(cumulative[i - 1] + SurfaceGeometry.distanceMeters(geo[i - 1].clLocation, geo[i].clLocation))
            }
            let pGeo = GeoCoordinate(point)
            var geomA: [GeoCoordinate] = []
            var geomB: [GeoCoordinate] = []
            for i in geo.indices {
                if cumulative[i] < along - 0.5 { geomA.append(geo[i]) }
                else if cumulative[i] > along + 0.5 { geomB.append(geo[i]) }
            }
            geomA.append(pGeo)
            geomB.insert(pGeo, at: 0)
            guard geomA.count >= 2, geomB.count >= 2 else { return e.to }
            let pID = makeNode(at: pGeo, kind: .apronConnector)
            if pID == e.from || pID == e.to { return pID }   // snapped onto an existing endpoint
            func half(from a: Int, to b: Int, geometry: [GeoCoordinate], newID: Int) -> SurfaceEdge {
                SurfaceEdge(id: newID, from: a, to: b, geometry: geometry,
                            distanceMeters: SurfaceGeometry.pathLengthMeters(geometry.clLocations),
                            taxiwayName: e.taxiwayName, hasName: e.hasName, isTaxilane: e.isTaxilane,
                            runwayCrossing: nil, runwayCrossingName: nil, crossingPoint: nil,
                            runwayOccupancy: e.runwayOccupancy, oneway: e.oneway, closed: e.closed,
                            inferred: e.inferred, crossesBuilding: e.crossesBuilding,
                            confidence: e.confidence, osmIDs: e.osmIDs, widthMeters: e.widthMeters)
            }
            edges[eIdx] = half(from: e.from, to: pID, geometry: geomA, newID: e.id)
            edges.append(half(from: pID, to: e.to, geometry: geomB, newID: edges.count))
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
        for parking in model.routableStands {
            let gateCoord = parking.coordinate.clLocation

            let bestNode = connectorCandidates(to: gateCoord)
                .map { (c: $0, s: nodeAttachScore(gateCoord, $0)) }
                .min { $0.s.score < $1.s.score }

            // Only pay for the edge scan when a node snap is unavailable or would cross a
            // building — the well-mapped stands keep their exact existing node attachment.
            var bestEdge: (c: EdgeAttach, s: (score: Double, crosses: Bool))?
            let needEdge: Bool
            if let bestNode { needEdge = bestNode.s.crosses } else { needEdge = true }
            if needEdge {
                bestEdge = edgeAttachCandidates(to: gateCoord)
                    .map { (c: $0, s: edgeAttachScore(gateCoord, $0)) }
                    .min { $0.s.score < $1.s.score }
            }

            // Prefer the in-range node (unchanged routing); fall to an edge projection when
            // there is no node in range, or when the node lead-in crosses a terminal but an edge
            // lead-in stays clear.
            let useEdge: Bool
            if let bestNode {
                useEdge = bestNode.s.crosses && (bestEdge?.s.crosses == false)
            } else {
                useEdge = bestEdge != nil
            }

            let taxiIdx: Int
            if useEdge, let bestEdge {
                taxiIdx = splitEdgeForConnector(bestEdge.c.edgeIndex, at: bestEdge.c.projection,
                                                alongFromFrom: bestEdge.c.alongFromFrom)
            } else if let bestNode {
                taxiIdx = bestNode.c.id
            } else {
                continue
            }

            let gateNodeID = makeNode(at: parking.coordinate,
                                      kind: parking.kind == .gate ? .gate : .parking,
                                      osmID: parking.osmID, inferred: true)
            nodes[gateNodeID].name = parking.name
            // A stand sitting essentially on the taxiway can resolve to its own attach node;
            // skip the zero-length self-connector.
            guard taxiIdx != gateNodeID else { continue }
            let a = nodes[gateNodeID].coordinate, b = nodes[taxiIdx].coordinate
            let dist = SurfaceGeometry.distanceMeters(a.clLocation, b.clLocation)
            // Even the best available lead-in may still clip a footprint (a stand ringed by
            // building). Flag it so routing penalizes it and confidence reflects it.
            let crosses = buildingPenalty(a.clLocation, b.clLocation).crosses
            edges.append(SurfaceEdge(id: edges.count, from: gateNodeID, to: taxiIdx,
                                     geometry: [a, b], distanceMeters: dist,
                                     taxiwayName: "", hasName: false, isTaxilane: false,
                                     runwayCrossing: nil, runwayCrossingName: nil, crossingPoint: nil,
                                     runwayOccupancy: false, oneway: false, closed: false,
                                     inferred: true, crossesBuilding: crosses,
                                     confidence: crosses ? 0.2 : 0.4, osmIDs: [], widthMeters: nil))
            inferredConnectors += 1
        }

        // Adjacency.
        var adjacency: [Int: [Int]] = [:]
        for (idx, e) in edges.enumerated() {
            adjacency[e.from, default: []].append(idx)
            adjacency[e.to, default: []].append(idx)
        }

        // Connected components over nodes that participate in an edge (union-find).
        let componentCount = connectedComponents(nodeCount: nodes.count, edges: edges)

        // Register de-duplicated runway-crossing marker nodes (display/diagnostics only,
        // kept out of adjacency so they never alter routing or component counts).
        var seenCrossingKeys = Set<String>()
        for site in crossingSites {
            let key = SurfaceGeometry.snapKey(site.point)
            guard !seenCrossingKeys.contains(key) else { continue }
            seenCrossingKeys.insert(key)
            nodes.append(SurfaceNode(id: nodes.count, coordinate: site.point, kind: .runwayCrossing,
                                     runwayRef: site.ident, name: site.name, osmID: nil, inferred: false))
        }

        return SurfaceGraph(nodes: nodes, edges: edges, adjacency: adjacency,
                            componentCount: componentCount, inferredConnectorCount: inferredConnectors)
    }

    /// Count connected components among nodes that appear in at least one edge.
    private static func connectedComponents(nodeCount: Int, edges: [SurfaceEdge]) -> Int {
        guard nodeCount > 0 else { return 0 }
        var parent = Array(0..<nodeCount)
        func find(_ x: Int) -> Int {
            var r = x
            while parent[r] != r { parent[r] = parent[parent[r]]; r = parent[r] }
            return r
        }
        func union(_ a: Int, _ b: Int) {
            let ra = find(a), rb = find(b)
            if ra != rb { parent[ra] = rb }
        }
        var touched = Set<Int>()
        for e in edges where e.from < nodeCount && e.to < nodeCount {
            union(e.from, e.to)
            touched.insert(e.from); touched.insert(e.to)
        }
        var roots = Set<Int>()
        for n in touched { roots.insert(find(n)) }
        return roots.count
    }
}
