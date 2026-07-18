import Foundation

/// A point-in-time snapshot of the airport-surface feature for the Airport Surface
/// Diagnostics view and its text export. Always identifies OpenStreetMap / ODbL 1.0 and
/// carries the visible attribution.
struct AirportSurfaceDiagnostics {
    var airportID: String
    var sourceProvider: String
    var license: String
    var attribution: String
    var endpoint: String
    var fetchDate: Date?
    var cacheAgeDays: Int?
    var stale: Bool
    var rawFeatureCount: Int
    var runwayCount: Int
    var taxiwayCount: Int
    var taxilaneCount: Int
    var holdingPositionCount: Int
    var parkingCount: Int
    var apronCount: Int
    var graphNodeCount: Int
    var graphEdgeCount: Int
    var disconnectedComponents: Int
    var inferredConnectors: Int
    var snappedSegment: String
    var routeSummary: String
    var routeDistanceMeters: Double?
    var runwayCrossings: Int
    var routeConfidence: SurfaceConfidence
    var datasetConfidence: SurfaceConfidence
    var nextCrossing: String
    var crossingState: String
    var authorizationState: String
    var statusText: String
    var lastError: String?

    init(surface: AirportSurfaceModel?, graph: SurfaceGraph?, route: SurfaceTaxiRoute?,
         kind: TaxiKind, status: AirportSurfaceStatus, datasetConfidence: SurfaceConfidence,
         routeConfidence: SurfaceConfidence, crossingState: RunwayCrossingState,
         activeCrossing: RouteCrossing?, progress: RouteTracker.Progress?,
         awaitingCrossingReadback: Bool, authorizedCrossingIndex: Int?,
         snappedSegment: String, lastError: String?) {

        airportID = surface?.icao ?? "—"
        sourceProvider = OSMSurface.providerName
        license = OSMSurface.licenseShortName
        attribution = OSMSurface.attributionText
        endpoint = surface?.source.endpoint ?? OSMSurface.primaryOverpassEndpoint
        fetchDate = surface?.source.fetchDate
        cacheAgeDays = surface.map { $0.source.cacheAgeDays }
        stale = surface?.source.isStale ?? false
        rawFeatureCount = surface?.source.rawElementCount ?? 0
        runwayCount = surface?.runways.count ?? 0
        taxiwayCount = surface?.taxiwaysOnly.count ?? 0
        taxilaneCount = surface?.taxilanes.count ?? 0
        holdingPositionCount = surface?.holdingPositions.count ?? 0
        parkingCount = surface?.parkingPositions.count ?? 0
        apronCount = surface?.aprons.count ?? 0
        graphNodeCount = graph?.nodes.count ?? 0
        graphEdgeCount = graph?.edges.count ?? 0
        disconnectedComponents = max(0, (graph?.componentCount ?? 1) - 1)
        inferredConnectors = graph?.inferredConnectorCount ?? 0
        self.snappedSegment = snappedSegment
        self.datasetConfidence = datasetConfidence
        self.routeConfidence = routeConfidence

        if let route {
            routeSummary = route.isDeparture
                ? "Departure → \(route.destinationLabel) via \(route.taxiwaysText)"
                : "Arrival → \(route.destinationLabel) via \(route.taxiwaysText)"
            routeDistanceMeters = route.distanceMeters
            runwayCrossings = route.crossings.count
        } else {
            routeSummary = "No route calculated"
            routeDistanceMeters = nil
            runwayCrossings = 0
        }

        if let c = activeCrossing {
            nextCrossing = "Runway \(c.runwayIdent) (\(c.confidence.title))"
        } else {
            nextCrossing = "—"
        }
        self.crossingState = crossingState.title
        if awaitingCrossingReadback {
            authorizationState = "Awaiting read back"
        } else if let idx = authorizedCrossingIndex {
            authorizationState = "Authorized (crossing \(idx + 1))"
        } else {
            authorizationState = crossingState.isAuthorized ? "Authorized" : "Not authorized"
        }

        switch status {
        case .idle: statusText = "Idle"
        case .loading: statusText = "Loading"
        case .ready: statusText = "Ready"
        case .unavailable(let reason): statusText = "Unavailable — \(reason)"
        case .error(let reason): statusText = "Error — \(reason)"
        }
        self.lastError = lastError
    }

    /// Shareable plain-text export of the surface diagnostics.
    func exportText() -> String {
        func date(_ d: Date?) -> String {
            guard let d else { return "—" }
            let f = ISO8601DateFormatter()
            return f.string(from: d)
        }
        var lines: [String] = []
        lines.append("IFATC Companion — Airport Surface Diagnostics")
        lines.append("Airport: \(airportID)")
        lines.append("Source: \(sourceProvider)")
        lines.append("License: \(license)")
        lines.append("Attribution: \(attribution)")
        lines.append("Query endpoint: \(endpoint)")
        lines.append("Fetch date: \(date(fetchDate))")
        lines.append("Cache age: \(cacheAgeDays.map { "\($0) days" } ?? "—")\(stale ? " (stale)" : "")")
        lines.append("Source features: \(rawFeatureCount)")
        lines.append("Runways: \(runwayCount)")
        lines.append("Taxiways: \(taxiwayCount)")
        lines.append("Taxilanes: \(taxilaneCount)")
        lines.append("Holding positions: \(holdingPositionCount)")
        lines.append("Gates/parking: \(parkingCount)")
        lines.append("Aprons: \(apronCount)")
        lines.append("Graph nodes: \(graphNodeCount)")
        lines.append("Graph edges: \(graphEdgeCount)")
        lines.append("Disconnected components: \(disconnectedComponents)")
        lines.append("Inferred connectors: \(inferredConnectors)")
        lines.append("Snapped segment: \(snappedSegment)")
        lines.append("Route: \(routeSummary)")
        lines.append("Route distance: \(routeDistanceMeters.map { "\(Int($0)) m" } ?? "—")")
        lines.append("Runway crossings: \(runwayCrossings)")
        lines.append("Dataset confidence: \(datasetConfidence.title)")
        lines.append("Route confidence: \(routeConfidence.title)")
        lines.append("Next crossing: \(nextCrossing)")
        lines.append("Crossing state: \(crossingState)")
        lines.append("Authorization: \(authorizationState)")
        lines.append("Status: \(statusText)")
        lines.append("Last error: \(lastError ?? "—")")
        return lines.joined(separator: "\n")
    }
}
