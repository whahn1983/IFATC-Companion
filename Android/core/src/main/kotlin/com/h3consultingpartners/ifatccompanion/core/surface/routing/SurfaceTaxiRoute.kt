package com.h3consultingpartners.ifatccompanion.core.surface.routing

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.surface.GeoCoordinate
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceConfidence
import com.h3consultingpartners.ifatccompanion.core.surface.toCoordinates

/**
 * A runway crossing along a calculated taxi route.
 *
 * Ported from `IFATCCompanion/AirportSurface/SurfaceTaxiRoute.swift`.
 */
data class RouteCrossing(
    /** 0-based order along the route. */
    var index: Int,
    /** Phraseology ident of the runway being crossed ("16L"). */
    val runwayIdent: String,
    /** Display name of the crossed runway ("16L/34R"). */
    val runwayName: String,
    /** The runway-centerline crossing point. */
    val point: GeoCoordinate,
    /** A hold-short point a short distance before the crossing along the route. */
    val holdShortPoint: GeoCoordinate,
    /** Along-route distance (meters) from the route start to the crossing. */
    val alongMeters: Double,
    /** The graph edge carrying the crossing. */
    val edgeID: Int,
    /** Confidence in this specific crossing's geometry. */
    val confidence: SurfaceConfidence,
) {
    val id: Int get() = index
}

/** A best-effort calculated taxi route over the airport surface graph. */
data class SurfaceTaxiRoute(
    val isDeparture: Boolean,
    val nodeIDs: List<Int>,
    val edgeIDs: List<Int>,
    /** Full route polyline for rendering / progress tracking. */
    val geometry: List<GeoCoordinate>,
    val distanceMeters: Double,
    /** Ordered, de-duplicated named taxiway sequence spoken to the pilot. */
    val taxiwaySequence: List<String>,
    val crossings: List<RouteCrossing>,
    val confidence: SurfaceConfidence,
    val confidenceScore: Double,
    /** "runway 16L" (departure) or "gate B44" / "parking" (arrival). */
    val destinationLabel: String,
    /** Assigned runway (departure) — the hold-short runway at the end of the route. */
    val holdShortRunway: String?,
    /** Gate/parking name (arrival). */
    val arrivalGate: String?,
    val startCoordinate: GeoCoordinate,
    val endCoordinate: GeoCoordinate,
    /** Whether any inferred connector was used mid-route (not at the gate endpoints). */
    val usedInferredConnectorMidRoute: Boolean,
    val unnamedSegmentCount: Int,
    val notes: List<String>,
) {
    val line: List<Coordinate> get() = geometry.toCoordinates()
    val distanceNM: Double get() = distanceMeters / SurfaceGeometry.METERS_PER_NM

    /** The taxiway sequence rendered for phraseology/display ("A, C, B"). */
    val taxiwaysText: String
        get() = if (taxiwaySequence.isEmpty()) "available taxiways" else taxiwaySequence.joinToString(", ")
}
