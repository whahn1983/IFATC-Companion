package com.h3consultingpartners.ifatccompanion.core.surface.routing

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.surface.GeoCoordinate
import com.h3consultingpartners.ifatccompanion.core.surface.toCoordinates

/**
 * The kind of point a graph node represents.
 *
 * Ported from `IFATCCompanion/AirportSurface/SurfaceGraph.swift`.
 */
enum class SurfaceNodeKind(val rawValue: String) {
    TAXIWAY_ENDPOINT("taxiwayEndpoint"),
    INTERSECTION("intersection"),
    HOLDING_POSITION("holdingPosition"),
    RUNWAY_ENTRY("runwayEntry"),
    RUNWAY_CROSSING("runwayCrossing"),
    GATE("gate"),
    PARKING("parking"),
    APRON_CONNECTOR("apronConnector"),
}

/**
 * A node in the airport surface graph.
 *
 * The Swift is a `struct` whose fields the builder mutates in place (`nodes[i].kind = …`),
 * so the mutable ones are `var` here and the graph builder writes through them the same way.
 */
data class SurfaceNode(
    val id: Int,
    var coordinate: GeoCoordinate,
    var kind: SurfaceNodeKind,
    /** Runway ident this node serves (holding position / runway entry / crossing). */
    var runwayRef: String? = null,
    /** Gate / parking / taxiway name where applicable. */
    var name: String? = null,
    /** Original OSM feature id, when the node came from a mapped feature. */
    var osmID: String? = null,
    var inferred: Boolean = false,
) {
    val location: Coordinate get() = coordinate.toCoordinate()
}

/**
 * An edge in the airport surface graph — a routable segment of taxiway/taxilane
 * geometry between two nodes. Tracks everything routing and phraseology need.
 */
data class SurfaceEdge(
    val id: Int,
    var from: Int,
    var to: Int,
    var geometry: List<GeoCoordinate>,
    var distanceMeters: Double,
    /** Taxiway `ref`/name ("A", "Alpha"), or "" when unnamed. */
    var taxiwayName: String,
    var hasName: Boolean,
    var isTaxilane: Boolean,
    /** Ident of a runway crossed mid-edge (phraseology form, e.g. "16L"), else null. */
    var runwayCrossing: String? = null,
    /** Display name of the crossed runway ("16L/34R"), else null. */
    var runwayCrossingName: String? = null,
    /** Location of the runway-centerline crossing point, when [runwayCrossing] != null. */
    var crossingPoint: GeoCoordinate? = null,
    /** Whether traversing this edge means occupying/entering a runway surface. */
    var runwayOccupancy: Boolean = false,
    /** `oneway`: traversable only from→to. */
    var oneway: Boolean = false,
    /** Non-operational (closed) segment. */
    var closed: Boolean = false,
    /** Inferred connector (gate lead-in, apron connector) rather than mapped geometry. */
    var inferred: Boolean = false,
    /**
     * Whether this edge's straight geometry passes through a building/terminal footprint.
     * Only computed for inferred gate/parking lead-ins (mapped taxiways don't run through
     * terminals); such a connector is avoided when a clear alternative exists and lowers
     * route confidence otherwise.
     */
    var crossesBuilding: Boolean = false,
    /** Per-edge confidence 0…1 (names/closed/inferred lower it). */
    var confidence: Double = 0.0,
    /** Original OSM feature ids that contributed to this edge. */
    var osmIDs: List<String> = emptyList(),
    var widthMeters: Double? = null,
) {
    val line: List<Coordinate> get() = geometry.toCoordinates()
}

/** The connected airport surface graph derived from an `AirportSurfaceModel`. */
data class SurfaceGraph(
    val nodes: List<SurfaceNode>,
    val edges: List<SurfaceEdge>,
    /** nodeID → indices into [edges] incident on that node. */
    val adjacency: Map<Int, List<Int>>,
    /** Number of disconnected components (1 = fully connected). */
    val componentCount: Int,
    /** Whether any inferred connectors were added (gate lead-ins, etc.). */
    val inferredConnectorCount: Int,
) {

    fun node(id: Int): SurfaceNode? = nodes.firstOrNull { it.id == id }

    fun edgesIncident(nodeID: Int): List<SurfaceEdge> =
        (adjacency[nodeID] ?: emptyList()).mapNotNull { idx -> edges.getOrNull(idx) }

    /** Nearest node with distance, used to snap the aircraft/gate/runway onto the graph. */
    data class NodeHit(val node: SurfaceNode, val distanceMeters: Double)

    fun nearestNode(to: Coordinate, maxMeters: Double = Double.MAX_VALUE): NodeHit? {
        var best: NodeHit? = null
        for (n in nodes) {
            val d = SurfaceGeometry.distanceMeters(to, n.location)
            if (d <= maxMeters && (best == null || d < best!!.distanceMeters)) best = NodeHit(n, d)
        }
        return best
    }

    /** Nearest node of a given kind matching an optional runway ident. */
    fun nearestNode(kind: SurfaceNodeKind, runwayRef: String? = null, to: Coordinate): SurfaceNode? {
        val candidates = nodes.filter { n ->
            if (n.kind != kind) return@filter false
            val nodeRef = n.runwayRef
            if (runwayRef != null && nodeRef != null) {
                return@filter nodeRef.uppercase() == runwayRef.uppercase()
            }
            runwayRef == null
        }
        return candidates.minByOrNull { SurfaceGeometry.distanceMeters(to, it.location) }
    }

    val runwayCrossingEdges: List<SurfaceEdge> get() = edges.filter { it.runwayCrossing != null }

    val namedEdgeFraction: Double
        get() {
            val routable = edges.filter { !it.inferred }
            if (routable.isEmpty()) return 0.0
            return routable.count { it.hasName }.toDouble() / routable.size.toDouble()
        }
}
