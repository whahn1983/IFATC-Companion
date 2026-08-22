package com.h3consultingpartners.ifatccompanion.core.surface.routing

import com.h3consultingpartners.ifatccompanion.core.surface.AirportSurfaceModel
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceConfidence

/**
 * Grades an airport **dataset's** confidence by combining normalized-feature quality
 * with the connectivity of the derived surface graph. (Route confidence is graded
 * separately by [TaxiRouteEngine], which also factors the aircraft snap and crossings.)
 *
 * High confidence requires connected taxiway geometry, taxiway names/references, clear
 * runway geometry, and reliable holding positions. Medium tolerates some inferred
 * holds / missing names. Low means disconnected or largely unnamed geometry.
 * Unavailable means there is not enough to route on at all.
 *
 * Ported from `IFATCCompanion/AirportSurface/SurfaceConfidenceEvaluator.swift`.
 */
object SurfaceConfidenceEvaluator {

    fun datasetConfidence(model: AirportSurfaceModel, graph: SurfaceGraph): SurfaceConfidence {
        if (!model.hasUsableGeometry || graph.edges.isEmpty()) return SurfaceConfidence.UNAVAILABLE

        val namedFraction = graph.namedEdgeFraction
        val connected = graph.componentCount <= 1
        val hasMappedHolds = model.holdingPositions.any { !it.inferred }
        val hasRunways = model.runways.isNotEmpty() && model.runwayEnds.isNotEmpty()
        val hasParking = model.parkingPositions.isNotEmpty()

        if (namedFraction >= 0.6 && connected && hasMappedHolds && hasRunways) {
            return SurfaceConfidence.HIGH
        }
        if ((namedFraction >= 0.3 || hasMappedHolds) && hasRunways && (connected || hasParking)) {
            return SurfaceConfidence.MEDIUM
        }
        return SurfaceConfidence.LOW
    }
}
