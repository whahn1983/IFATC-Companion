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
    /// radius always beats a concourse-crossing one; when every candidate crosses (a stand
    /// fully enclosed by a footprint) the nearest still wins on distance.
    static let buildingConnectorPenaltyMeters = 5_000.0
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

        // Runway ends grouped by runway for threshold checks.
        let endsByRunway = Dictionary(grouping: model.runwayEnds, by: { $0.runwayOSMID })

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

        /// Whether the straight connector a→b passes through any building footprint.
        func connectorCrossesBuilding(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D) -> Bool {
            guard !buildingPolys.isEmpty else { return false }
            let loLat = min(a.latitude, b.latitude), hiLat = max(a.latitude, b.latitude)
            let loLon = min(a.longitude, b.longitude), hiLon = max(a.longitude, b.longitude)
            for bp in buildingPolys {
                // AABB reject: skip a building whose box can't overlap the connector's.
                if bp.box.maxLat < loLat || bp.box.minLat > hiLat
                    || bp.box.maxLon < loLon || bp.box.minLon > hiLon { continue }
                if SurfaceGeometry.segmentIntersectsPolygon(a, b, bp.poly) { return true }
            }
            return false
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

        // Attach gates / parking via inferred connectors, preferring a nearby node the
        // lead-in can reach without crossing a concourse or doubling back.
        var inferredConnectors = 0
        for parking in model.parkingPositions {
            let gateCoord = parking.coordinate.clLocation
            let candidates = connectorCandidates(to: gateCoord)
            guard !candidates.isEmpty else { continue }

            func score(_ c: (id: Int, distance: Double)) -> Double {
                var s = c.distance
                if connectorCrossesBuilding(gateCoord, nodes[c.id].clLocation) {
                    s += buildingConnectorPenaltyMeters
                }
                s += reversalPenalty(from: gateCoord, to: c.id)
                return s
            }
            guard let taxiIdx = candidates.min(by: { score($0) < score($1) })?.id else { continue }

            let gateNodeID = makeNode(at: parking.coordinate,
                                      kind: parking.kind == .gate ? .gate : .parking,
                                      osmID: parking.osmID, inferred: true)
            nodes[gateNodeID].name = parking.name
            let a = nodes[gateNodeID].coordinate, b = nodes[taxiIdx].coordinate
            let dist = SurfaceGeometry.distanceMeters(a.clLocation, b.clLocation)
            // Even the best available lead-in may still clip a footprint (a stand ringed by
            // building). Flag it so routing penalizes it and confidence reflects it.
            let crosses = connectorCrossesBuilding(a.clLocation, b.clLocation)
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
