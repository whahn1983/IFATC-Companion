package com.h3consultingpartners.ifatccompanion.core.surface

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import java.util.Locale

/**
 * A complete, hand-built airport surface for Mock Mode — no OpenStreetMap network
 * access required. It models a small field with named taxiways, one runway the taxi
 * route crosses, and a primary runway the departure route ends at (and the arrival
 * route exits from), plus mapped holding positions and a gate. The geometry is
 * synthetic (a demo scenario), laid out around a supplied reference so it renders near
 * the flight's field, and its primary runway / gate are labeled to match the active
 * flight so the demo stays coherent.
 *
 * It is deliberately well-formed so it grades High confidence and exercises the full
 * automatic runway-crossing workflow offline.
 *
 * Ported from `IFATCCompanion/AirportSurface/MockAirportSurface.swift`.
 */
object MockAirportSurface {

    const val DEFAULT_RUNWAY_IDENT = "36"
    const val DEFAULT_GATE_NAME = "A1"

    /**
     * Build the mock surface labeled with the given ICAO, primary runway, and gate,
     * laid out around [reference]. [nowMillis] stamps the provenance's fetch date, which
     * iOS takes from `Date()`.
     */
    fun model(
        icao: String,
        reference: Coordinate,
        primaryRunwayIdent: String,
        gate: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): AirportSurfaceModel {
        fun g(dLat: Double, dLon: Double) =
            GeoCoordinate(reference.latitude + dLat, reference.longitude + dLon)

        val primary =
            if (primaryRunwayIdent.isEmpty()) DEFAULT_RUNWAY_IDENT else primaryRunwayIdent.uppercase()
        val primaryRecip = reciprocal(primary)
        val crossing = crossingIdent(forPrimary = primary)
        val crossingRecip = reciprocal(crossing)
        val gateName =
            if (gate.trim { it == ' ' || it == '\t' }.isEmpty()) DEFAULT_GATE_NAME else gate

        // Crossing runway (east–west, through the reference latitude).
        val rwyCross = SurfaceRunway(
            osmID = "way/mock-rwy-cross",
            tags = mapOf("aeroway" to "runway", "ref" to "$crossing/$crossingRecip", "surface" to "asphalt"),
            idents = listOf(crossing, crossingRecip),
            centerline = listOf(g(0.0000, -0.0050), g(0.0000, 0.0050)),
            widthMeters = 45.0,
            widthInferred = false,
        )

        // Primary runway (north–south, east side). The departure route ends holding
        // short of this runway; the arrival route exits from it.
        val rwyPrimary = SurfaceRunway(
            osmID = "way/mock-rwy-primary",
            tags = mapOf("aeroway" to "runway", "ref" to "$primary/$primaryRecip", "surface" to "asphalt"),
            idents = listOf(primary, primaryRecip),
            centerline = listOf(g(-0.0035, 0.0070), g(0.0025, 0.0070)),
            widthMeters = 45.0,
            widthInferred = false,
        )

        val runways = listOf(rwyCross, rwyPrimary)
        val runwayEnds = makeEnds(rwyCross) + makeEnds(rwyPrimary)

        // Taxiway A (north–south) from the gate area, crossing the crossing runway.
        val twyA = SurfaceTaxiway(
            osmID = "way/mock-twy-A",
            tags = mapOf("aeroway" to "taxiway", "ref" to "A"),
            isTaxilane = false,
            name = "A",
            geometry = listOf(g(0.0035, 0.0030), g(0.0000, 0.0030), g(-0.0032, 0.0030)),
            oneway = false,
            access = null,
            widthMeters = null,
        )

        // Taxiway C (east–west, south) from taxiway A to the primary runway hold.
        val twyC = SurfaceTaxiway(
            osmID = "way/mock-twy-C",
            tags = mapOf("aeroway" to "taxiway", "ref" to "C"),
            isTaxilane = false,
            name = "C",
            geometry = listOf(g(-0.0032, 0.0030), g(-0.0032, 0.0062)),
            oneway = false,
            access = null,
            widthMeters = null,
        )

        val taxiways = listOf(twyA, twyC)

        // Mapped holding positions: one protecting the crossing, one at the primary runway.
        val holds = listOf(
            SurfaceHoldingPosition(
                osmID = "node/mock-hold-cross",
                tags = mapOf("aeroway" to "holding_position", "ref" to crossing),
                coordinate = g(0.0002, 0.0030),
                runwayRef = crossing,
                inferred = false,
            ),
            SurfaceHoldingPosition(
                osmID = "node/mock-hold-primary",
                tags = mapOf("aeroway" to "holding_position", "ref" to primary),
                coordinate = g(-0.0032, 0.0062),
                runwayRef = primary,
                inferred = false,
            ),
        )

        // Gate.
        val parking = listOf(
            SurfaceParking(
                osmID = "node/mock-gate",
                tags = mapOf("aeroway" to "gate", "ref" to gateName),
                kind = SurfaceParking.Kind.GATE,
                name = gateName,
                coordinate = g(0.0040, 0.0030),
            ),
        )

        val bbox = OSMBoundingBox(center = reference, halfSpanDegrees = OSMSurface.BBOX_HALF_SPAN_DEGREES)
        val provenance = SurfaceProvenance(
            endpoint = "Bundled sample (offline mock)",
            fetchDateMillis = nowMillis,
            boundingBox = bbox,
            rawElementCount = runways.size + taxiways.size + holds.size + parking.size,
        )

        val model = AirportSurfaceModel(
            icao = icao.uppercase(),
            reference = GeoCoordinate(reference),
            runways = runways,
            runwayEnds = runwayEnds,
            taxiways = taxiways,
            holdingPositions = holds,
            parkingPositions = parking,
            aprons = emptyList(),
            source = provenance,
            confidence = SurfaceConfidence.HIGH,
        )
        return model.copy(confidence = OSMSurfaceNormalizer.preliminaryConfidence(model))
    }

    /** Coordinate of the gate (departure taxi start). */
    fun gateCoordinate(reference: Coordinate): Coordinate =
        Coordinate(reference.latitude + 0.0040, reference.longitude + 0.0030)

    /** Coordinate of the primary-runway exit / arrival taxi start. */
    fun runwayExitCoordinate(reference: Coordinate): Coordinate =
        Coordinate(reference.latitude - 0.0032, reference.longitude + 0.0062)

    /**
     * A crossing-runway ident chosen ~90° from the primary so it never collides with
     * the primary's two ends.
     */
    fun crossingIdent(forPrimary: String): String {
        val n = number(forPrimary) ?: 18
        val cross = ((n + 9 - 1) % 36) + 1 // 1…36
        return String.format(Locale.US, "%02d", cross)
    }

    /** The reciprocal runway ident (e.g. "26L" → "08R"). */
    fun reciprocal(ident: String): String {
        val n = number(ident) ?: 18
        val r = ((n + 18 - 1) % 36) + 1
        val suffix = ident.uppercase().dropWhile { it.isDigit() }
        val recipSuffix = when (suffix) {
            "L" -> "R"
            "R" -> "L"
            else -> suffix
        }
        return String.format(Locale.US, "%02d", r) + recipSuffix
    }

    private fun number(ident: String): Int? = ident.takeWhile { it.isDigit() }.toIntOrNull()

    private fun makeEnds(r: SurfaceRunway): List<SurfaceRunwayEnd> {
        val first = r.centerline.firstOrNull()?.toCoordinate() ?: return emptyList()
        val last = r.centerline.lastOrNull()?.toCoordinate() ?: return emptyList()
        val ends = mutableListOf<SurfaceRunwayEnd>()
        for (ident in r.idents) {
            val heading = OSMSurfaceNormalizer.runwayHeading(ident) ?: Geo.bearing(first, last)
            val bFL = Geo.bearing(first, last)
            val bLF = Geo.bearing(last, first)
            val threshold: Coordinate
            val opposite: Coordinate
            if (Geo.headingDifference(bFL, heading) <= Geo.headingDifference(bLF, heading)) {
                threshold = first; opposite = last
            } else {
                threshold = last; opposite = first
            }
            ends.add(
                SurfaceRunwayEnd(
                    ident = ident,
                    threshold = GeoCoordinate(threshold),
                    oppositeThreshold = GeoCoordinate(opposite),
                    headingDegrees = heading,
                    runwayOSMID = r.osmID,
                    widthMeters = r.widthMeters,
                ),
            )
        }
        return ends
    }
}
