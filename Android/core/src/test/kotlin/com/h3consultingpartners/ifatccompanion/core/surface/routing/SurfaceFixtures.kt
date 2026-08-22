package com.h3consultingpartners.ifatccompanion.core.surface.routing

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.surface.GeoCoordinate
import com.h3consultingpartners.ifatccompanion.core.surface.OSMBoundingBox
import com.h3consultingpartners.ifatccompanion.core.surface.OSMSurfaceNormalizer
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceProvenance
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceRunway
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceRunwayEnd

/**
 * Shared helpers for the taxi-routing test fixtures. The Swift tests repeat a private
 * `makeEnds(_:)` and a `SurfaceProvenance(endpoint:fetchDate:boundingBox:rawElementCount:)`
 * in each file; both live here once so the hand-built models read the same everywhere.
 */

/** Mirror the normalizer's runway-end derivation for hand-built test models. */
internal fun makeEnds(r: SurfaceRunway): List<SurfaceRunwayEnd> {
    val first = r.centerline.firstOrNull()?.toCoordinate() ?: return emptyList()
    val last = r.centerline.lastOrNull()?.toCoordinate() ?: return emptyList()
    return r.idents.map { ident ->
        val heading = OSMSurfaceNormalizer.runwayHeading(ident) ?: Geo.bearing(first, last)
        val bFL = Geo.bearing(first, last)
        val bLF = Geo.bearing(last, first)
        val near = Geo.headingDifference(bFL, heading) <= Geo.headingDifference(bLF, heading)
        SurfaceRunwayEnd(
            ident = ident,
            threshold = GeoCoordinate(if (near) first else last),
            oppositeThreshold = GeoCoordinate(if (near) last else first),
            headingDegrees = heading,
            runwayOSMID = r.osmID,
            widthMeters = r.widthMeters,
        )
    }
}

/** A provenance stamp for a hand-built model — the Swift's `fetchDate: Date()` fixed at 0. */
internal fun testProvenance(
    center: Coordinate,
    halfSpanDegrees: Double = 0.04,
    rawElementCount: Int,
): SurfaceProvenance = SurfaceProvenance(
    endpoint = "t",
    fetchDateMillis = 0L,
    boundingBox = OSMBoundingBox(center = center, halfSpanDegrees = halfSpanDegrees),
    rawElementCount = rawElementCount,
)
