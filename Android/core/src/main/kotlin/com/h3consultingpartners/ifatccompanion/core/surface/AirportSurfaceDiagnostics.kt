package com.h3consultingpartners.ifatccompanion.core.surface

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.max

/**
 * The status line the surface coordinator is in, spelled once here so the wording
 * cannot drift between the coordinator and this snapshot.
 *
 * CONTRACT for the taxi/coordinator agent: `AirportSurfaceStatus` (a sealed type with
 * `idle`/`loading`/`ready`/`unavailable(reason)`/`error(reason)`) lives in that agent's
 * package; it maps its own case to one of these strings and passes the result to
 * [AirportSurfaceDiagnostics.from] as `statusText`.
 */
object AirportSurfaceStatusText {
    const val IDLE = "Idle"
    const val LOADING = "Loading"
    const val READY = "Ready"
    fun unavailable(reason: String): String = "Unavailable — $reason"
    fun error(reason: String): String = "Error — $reason"
}

/**
 * The counts this snapshot needs from the connected surface graph.
 *
 * CONTRACT: `SurfaceGraph` belongs to the graph/routing agent. Rather than depend on it
 * (or duplicate it), the coordinator hands over this digest — null when no graph has
 * been built, which reads as zero nodes/edges and one component, exactly as the Swift's
 * `graph?.componentCount ?? 1` does.
 */
data class SurfaceGraphSummary(
    val nodeCount: Int,
    val edgeCount: Int,
    val componentCount: Int,
    val inferredConnectorCount: Int,
)

/**
 * The bits of a calculated taxi route this snapshot needs.
 *
 * CONTRACT: `SurfaceTaxiRoute` belongs to the taxi-routing agent; the coordinator maps
 * one into this digest. Null means no route has been calculated.
 */
data class TaxiRouteSummary(
    val isDeparture: Boolean,
    val destinationLabel: String,
    val taxiwaysText: String,
    val distanceMeters: Double,
    val crossingCount: Int,
)

/**
 * The active runway crossing, as this snapshot reports it.
 *
 * CONTRACT: `RouteCrossing` belongs to the taxi-routing agent; the coordinator maps the
 * active crossing (if any) into this digest.
 */
data class ActiveCrossingSummary(
    val runwayIdent: String,
    val confidence: SurfaceConfidence,
)

/**
 * A point-in-time snapshot of the airport-surface feature for the Airport Surface
 * Diagnostics view and its text export. Always identifies OpenStreetMap / ODbL 1.0 and
 * carries the visible attribution.
 *
 * Ported from `IFATCCompanion/AirportSurface/AirportSurfaceDiagnostics.swift`. The Swift
 * initializer takes the coordinator's own graph/route/crossing/status types; the ones
 * this package does not own arrive as the digests above (see each CONTRACT note). The
 * Swift `kind: TaxiKind` and `progress: RouteTracker.Progress?` parameters are not taken
 * here because the initializer never reads either of them.
 */
data class AirportSurfaceDiagnostics(
    val airportID: String,
    val sourceProvider: String,
    val license: String,
    val attribution: String,
    val endpoint: String,
    /** Epoch millis the extract was fetched, or null when there is no surface loaded. */
    val fetchDateMillis: Long?,
    val cacheAgeDays: Int?,
    val stale: Boolean,
    val rawFeatureCount: Int,
    val runwayCount: Int,
    val taxiwayCount: Int,
    val taxilaneCount: Int,
    val holdingPositionCount: Int,
    val parkingCount: Int,
    val apronCount: Int,
    val buildingCount: Int,
    val schemaVersion: Int,
    val schemaOutdated: Boolean,
    val graphNodeCount: Int,
    val graphEdgeCount: Int,
    val disconnectedComponents: Int,
    val inferredConnectors: Int,
    val snappedSegment: String,
    val routeSummary: String,
    val routeDistanceMeters: Double?,
    val runwayCrossings: Int,
    val routeConfidence: SurfaceConfidence,
    val datasetConfidence: SurfaceConfidence,
    val nextCrossing: String,
    val crossingState: String,
    val authorizationState: String,
    val statusText: String,
    val lastError: String?,
) {

    /** Shareable plain-text export of the surface diagnostics. */
    fun exportText(): String {
        val lines = mutableListOf<String>()
        lines.add("IFATC Companion — Airport Surface Diagnostics")
        lines.add("Airport: $airportID")
        lines.add("Source: $sourceProvider")
        lines.add("License: $license")
        lines.add("Attribution: $attribution")
        lines.add("Query endpoint: $endpoint")
        lines.add("Fetch date: ${formatDate(fetchDateMillis)}")
        lines.add(
            "Cache age: ${cacheAgeDays?.let { "$it days" } ?: "—"}" +
                if (stale) " (stale)" else "",
        )
        lines.add("Source features: $rawFeatureCount")
        lines.add("Runways: $runwayCount")
        lines.add("Taxiways: $taxiwayCount")
        lines.add("Taxilanes: $taxilaneCount")
        lines.add("Holding positions: $holdingPositionCount")
        lines.add("Gates/parking: $parkingCount")
        lines.add("Aprons: $apronCount")
        lines.add("Buildings/terminals: $buildingCount")
        lines.add(
            "Schema version: $schemaVersion" +
                if (schemaOutdated) " (outdated — will refresh)" else "",
        )
        lines.add("Graph nodes: $graphNodeCount")
        lines.add("Graph edges: $graphEdgeCount")
        lines.add("Disconnected components: $disconnectedComponents")
        lines.add("Inferred connectors: $inferredConnectors")
        lines.add("Snapped segment: $snappedSegment")
        lines.add("Route: $routeSummary")
        lines.add("Route distance: ${routeDistanceMeters?.let { "${it.toInt()} m" } ?: "—"}")
        lines.add("Runway crossings: $runwayCrossings")
        lines.add("Dataset confidence: ${datasetConfidence.title}")
        lines.add("Route confidence: ${routeConfidence.title}")
        lines.add("Next crossing: $nextCrossing")
        lines.add("Crossing state: $crossingState")
        lines.add("Authorization: $authorizationState")
        lines.add("Status: $statusText")
        lines.add("Last error: ${lastError ?: "—"}")
        return lines.joinToString("\n")
    }

    companion object {
        /** `ISO8601DateFormatter().string(from:)` — an instant in UTC, second precision. */
        private val iso8601: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

        private fun formatDate(millis: Long?): String =
            if (millis == null) "—" else iso8601.format(Instant.ofEpochMilli(millis))

        /**
         * Build a snapshot. Mirrors the Swift `init(surface:graph:route:…)`, including the
         * fall-backs it applies when there is no surface loaded yet.
         *
         * [crossingStateTitle] and [crossingStateAuthorized] are `RunwayCrossingState.title`
         * and `.isAuthorized` — that enum belongs to the taxi/coordinator agent.
         */
        fun from(
            surface: AirportSurfaceModel?,
            graph: SurfaceGraphSummary?,
            route: TaxiRouteSummary?,
            statusText: String,
            datasetConfidence: SurfaceConfidence,
            routeConfidence: SurfaceConfidence,
            crossingStateTitle: String,
            crossingStateAuthorized: Boolean,
            activeCrossing: ActiveCrossingSummary?,
            awaitingCrossingReadback: Boolean,
            authorizedCrossingIndex: Int?,
            snappedSegment: String,
            lastError: String?,
            nowMillis: Long = System.currentTimeMillis(),
        ): AirportSurfaceDiagnostics {
            val routeSummaryText: String
            val routeDistance: Double?
            val crossings: Int
            if (route != null) {
                routeSummaryText = if (route.isDeparture) {
                    "Departure → ${route.destinationLabel} via ${route.taxiwaysText}"
                } else {
                    "Arrival → ${route.destinationLabel} via ${route.taxiwaysText}"
                }
                routeDistance = route.distanceMeters
                crossings = route.crossingCount
            } else {
                routeSummaryText = "No route calculated"
                routeDistance = null
                crossings = 0
            }

            val nextCrossing = if (activeCrossing != null) {
                "Runway ${activeCrossing.runwayIdent} (${activeCrossing.confidence.title})"
            } else {
                "—"
            }

            val authorizationState = when {
                awaitingCrossingReadback -> "Awaiting read back"
                authorizedCrossingIndex != null -> "Authorized (crossing ${authorizedCrossingIndex + 1})"
                crossingStateAuthorized -> "Authorized"
                else -> "Not authorized"
            }

            return AirportSurfaceDiagnostics(
                airportID = surface?.icao ?: "—",
                sourceProvider = OSMSurface.PROVIDER_NAME,
                license = OSMSurface.LICENSE_SHORT_NAME,
                attribution = OSMSurface.ATTRIBUTION_TEXT,
                endpoint = surface?.source?.endpoint ?: OSMSurface.PRIMARY_OVERPASS_ENDPOINT,
                fetchDateMillis = surface?.source?.fetchDateMillis,
                cacheAgeDays = surface?.source?.cacheAgeDays(nowMillis),
                stale = surface?.source?.isStale(nowMillis) ?: false,
                rawFeatureCount = surface?.source?.rawElementCount ?: 0,
                runwayCount = surface?.runways?.size ?: 0,
                taxiwayCount = surface?.taxiwaysOnly?.size ?: 0,
                taxilaneCount = surface?.taxilanes?.size ?: 0,
                holdingPositionCount = surface?.holdingPositions?.size ?: 0,
                parkingCount = surface?.parkingPositions?.size ?: 0,
                apronCount = surface?.aprons?.size ?: 0,
                buildingCount = surface?.buildings?.size ?: 0,
                schemaVersion = surface?.source?.schemaVersion ?: OSMSurface.SURFACE_SCHEMA_VERSION,
                schemaOutdated = surface?.source?.isOutdatedSchema ?: false,
                graphNodeCount = graph?.nodeCount ?: 0,
                graphEdgeCount = graph?.edgeCount ?: 0,
                disconnectedComponents = max(0, (graph?.componentCount ?: 1) - 1),
                inferredConnectors = graph?.inferredConnectorCount ?: 0,
                snappedSegment = snappedSegment,
                routeSummary = routeSummaryText,
                routeDistanceMeters = routeDistance,
                runwayCrossings = crossings,
                routeConfidence = routeConfidence,
                datasetConfidence = datasetConfidence,
                nextCrossing = nextCrossing,
                crossingState = crossingStateTitle,
                authorizationState = authorizationState,
                statusText = statusText,
                lastError = lastError,
            )
        }
    }
}
