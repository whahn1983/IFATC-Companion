package com.h3consultingpartners.ifatccompanion.core.surface

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo

/**
 * Normalizes a raw OSM Overpass extract into an [AirportSurfaceModel].
 *
 * Only recognised `aeroway` features are kept; every retained feature carries its
 * original OSM identifier and full tag set (never discarded), plus provenance and
 * attribution metadata. Widths/holds/aprons are best-effort — where OSM omits a
 * value a conservative default is inferred and flagged as inferred/lower-confidence.
 *
 * Nothing here treats OSM data as authoritative or guaranteed to match Infinite
 * Flight scenery.
 *
 * Ported from `IFATCCompanion/AirportSurface/OSMSurfaceNormalizer.swift`.
 */
object OSMSurfaceNormalizer {

    /**
     * Default runway width (meters) when OSM has no `width` tag — a mid-size value so
     * the inferred crossing corridor is neither absurdly narrow nor wide.
     */
    const val DEFAULT_RUNWAY_WIDTH_METERS = 45.0

    /** Default taxiway width (meters) when untagged. */
    const val DEFAULT_TAXIWAY_WIDTH_METERS = 23.0

    /** Meters in a nautical mile — the surface layer works in meters, [Geo] in NM. */
    private const val METERS_PER_NM = 1852.0

    /**
     * Great-circle distance in meters. `SurfaceGeometry.distanceMeters` on iOS; that type
     * belongs to the graph/routing package, so the two lines of arithmetic live here
     * rather than creating a dependency (or a second copy of the type).
     */
    private fun distanceMeters(a: Coordinate, b: Coordinate): Double =
        Geo.distanceNM(a, b) * METERS_PER_NM

    fun normalize(
        response: OverpassResponse,
        icao: String,
        reference: Coordinate,
        endpoint: String,
        boundingBox: OSMBoundingBox,
        fetchDateMillis: Long,
    ): AirportSurfaceModel {
        val elements = response.elements

        val runways = mutableListOf<SurfaceRunway>()
        val taxiways = mutableListOf<SurfaceTaxiway>()
        val holds = mutableListOf<SurfaceHoldingPosition>()
        val parking = mutableListOf<SurfaceParking>()
        val aprons = mutableListOf<SurfaceApron>()
        val buildings = mutableListOf<SurfaceBuilding>()

        // Refine the reference point from an aerodrome feature if OSM has one.
        var refined = reference

        for (e in elements) {
            val tags = e.tags ?: emptyMap()
            // Building / terminal footprints (used to keep gate lead-ins from crossing a
            // concourse). Checked before the aeroway switch: a `building=*` element has no
            // aeroway tag, and an `aeroway=terminal` element is not a movement surface.
            if (isBuilding(e, tags)) {
                makeBuilding(e, tags)?.let { buildings.add(it) }
            }
            val aeroway = e.aeroway ?: continue
            when (aeroway) {
                "runway" -> makeRunway(e, tags)?.let { runways.add(it) }
                "taxiway", "taxilane" ->
                    makeTaxiway(e, tags, isTaxilane = aeroway == "taxilane")?.let { taxiways.add(it) }
                "holding_position" -> makeHold(e, tags)?.let { holds.add(it) }
                "gate" -> makeParking(e, tags, SurfaceParking.Kind.GATE)?.let { parking.add(it) }
                "parking_position" ->
                    makeParking(e, tags, SurfaceParking.Kind.PARKING_POSITION)?.let { parking.add(it) }
                "apron" -> makeApron(e, tags)?.let { aprons.add(it) }
                "aerodrome" -> {
                    val c = e.coordinate
                    if (c != null) {
                        refined = c
                    } else {
                        centroid(e.polyline)?.let { refined = it }
                    }
                }
                else -> continue
            }
        }

        // Runway ends are derived after every runway way is collected, so a runway split
        // across several OSM ways (a main centerline plus short stubs at the thresholds —
        // common at large fields, e.g. KLAX tags 06R/24L as two ways and 07L/25R as three)
        // yields one threshold per ident at the runway's true extremes, not a phantom
        // far-ident end wherever a stub happens to terminate.
        val runwayEnds = makeRunwayEnds(runways)

        val provenance = SurfaceProvenance(
            endpoint = endpoint,
            fetchDateMillis = fetchDateMillis,
            boundingBox = boundingBox,
            rawElementCount = elements.size,
        )

        val model = AirportSurfaceModel(
            icao = icao.uppercase(),
            reference = GeoCoordinate(refined),
            runways = runways,
            runwayEnds = runwayEnds,
            taxiways = taxiways,
            holdingPositions = holds,
            parkingPositions = parking,
            aprons = aprons,
            buildings = buildings,
            source = provenance,
            confidence = SurfaceConfidence.LOW,
        )
        return model.copy(confidence = preliminaryConfidence(model))
    }

    // MARK: - Feature builders

    private fun makeRunway(e: OSMElement, tags: Map<String, String>): SurfaceRunway? {
        val line = e.polyline
        if (line.size < 2) return null
        val ref = (tags["ref"] ?: tags["name"] ?: "").trim { it == ' ' || it == '\t' }
        val idents = parseRunwayIdents(ref)
        val (width, inferred) = parseWidth(tags["width"]) ?: (DEFAULT_RUNWAY_WIDTH_METERS to true)
        return SurfaceRunway(
            osmID = e.stableID,
            tags = tags,
            idents = idents,
            centerline = line.map { GeoCoordinate(it) },
            widthMeters = width,
            widthInferred = inferred,
        )
    }

    /** Split a runway `ref` into its two ends: "16L/34R" → ["16L","34R"]; "09/27" → … */
    fun parseRunwayIdents(ref: String): List<String> =
        ref.split('/', '-')
            .map { it.trim { c -> c == ' ' || c == '\t' }.uppercase() }
            .filter { it.isNotEmpty() }

    /**
     * Build the directional ends (threshold + heading) for every physical runway.
     *
     * OSM frequently splits one runway into several ways — a main centerline plus short
     * stubs at the thresholds (KLAX tags `06R/24L` as two ways and `07L/25R` as three).
     * Deriving ends per way is wrong: a stub tagged `06R/24L` sitting at the west end
     * fabricates a `24L` end whose threshold lands at the *west* extreme of the runway
     * (right where `06R` actually is), which then sends a 24L departure to the wrong side.
     * So ways describing the same physical runway (identical ident set) are grouped, and the
     * two thresholds are taken from the pair of way-endpoints that are farthest apart — the
     * runway's true extremes — giving exactly one end per ident regardless of how OSM sliced
     * the pavement. Threshold-for-ident is still the end whose bearing toward the opposite end
     * matches the ident heading.
     */
    fun makeRunwayEnds(runways: List<SurfaceRunway>): List<SurfaceRunwayEnd> {
        // LinkedHashMap keeps the first-seen group order the Swift tracks with a separate
        // `order` array.
        val groups = LinkedHashMap<String, MutableList<SurfaceRunway>>()
        for (r in runways) {
            if (r.idents.isEmpty()) continue
            val key = r.idents.map(::canonicalRunwayIdent).sorted().joinToString("/")
            groups.getOrPut(key) { mutableListOf() }.add(r)
        }

        val ends = mutableListOf<SurfaceRunwayEnd>()
        for (group in groups.values) {
            // The physical runway runs between the two way-endpoints that are farthest apart.
            val endpoints = mutableListOf<Coordinate>()
            for (r in group) {
                r.centerline.firstOrNull()?.let { endpoints.add(it.toCoordinate()) }
                r.centerline.lastOrNull()?.let { endpoints.add(it.toCoordinate()) }
            }
            val (a, b) = farthestPair(endpoints) ?: continue
            val bAToB = Geo.bearing(a, b)
            val bBToA = Geo.bearing(b, a)
            // The longest way in the group supplies the OSM id + width for its ends.
            val rep = group.maxByOrNull { runwayLengthMeters(it) } ?: group[0]

            val seen = mutableSetOf<String>()
            for (ident in group.flatMap { it.idents }) {
                if (!seen.add(canonicalRunwayIdent(ident))) continue
                val heading = runwayHeading(ident) ?: bAToB
                val threshold: Coordinate
                val opposite: Coordinate
                if (Geo.headingDifference(bAToB, heading) <= Geo.headingDifference(bBToA, heading)) {
                    threshold = a; opposite = b
                } else {
                    threshold = b; opposite = a
                }
                ends.add(
                    SurfaceRunwayEnd(
                        ident = ident,
                        threshold = GeoCoordinate(threshold),
                        oppositeThreshold = GeoCoordinate(opposite),
                        headingDegrees = heading,
                        runwayOSMID = rep.osmID,
                        widthMeters = rep.widthMeters,
                    ),
                )
            }
        }
        return ends
    }

    /**
     * Canonical comparison key for a runway ident, tolerant of leading-zero padding and
     * case: "09L"/"9L" → "9L", "06R" → "6R". An ident with no leading number falls back to
     * its trimmed, uppercased form. Matches `TaxiRouteEngine`'s runway-key semantics so an
     * assigned end and an OSM-tagged end compare equal across the two layers.
     */
    fun canonicalRunwayIdent(raw: String): String {
        val s = raw.trim { it == ' ' || it == '\t' }.uppercase()
        val digits = s.takeWhile { it.isDigit() }
        val n = digits.toIntOrNull() ?: return s
        return "$n${s.drop(digits.length)}"
    }

    /** The pair of points farthest apart (great-circle), or null for fewer than two points. */
    private fun farthestPair(points: List<Coordinate>): Pair<Coordinate, Coordinate>? {
        if (points.size < 2) return null
        var best: Pair<Coordinate, Coordinate>? = null
        var bestMeters = -1.0
        for (i in 0 until points.size - 1) {
            for (j in i + 1 until points.size) {
                val d = distanceMeters(points[i], points[j])
                if (d > bestMeters) {
                    bestMeters = d
                    best = points[i] to points[j]
                }
            }
        }
        return best
    }

    /** Straight-line length (m) of a runway way between its centerline extremes. */
    private fun runwayLengthMeters(r: SurfaceRunway): Double {
        val a = r.centerline.firstOrNull() ?: return 0.0
        val b = r.centerline.lastOrNull() ?: return 0.0
        return distanceMeters(a.toCoordinate(), b.toCoordinate())
    }

    private fun makeTaxiway(
        e: OSMElement,
        tags: Map<String, String>,
        isTaxilane: Boolean,
    ): SurfaceTaxiway? {
        val line = e.polyline
        if (line.size < 2) return null
        val name = (tags["ref"] ?: tags["name"] ?: "").trim { it == ' ' || it == '\t' }
        val onewayRaw = tags["oneway"]?.lowercase()
        val oneway = onewayRaw == "yes" || onewayRaw == "true" || onewayRaw == "1"
        val width = parseWidth(tags["width"])?.first
        return SurfaceTaxiway(
            osmID = e.stableID,
            tags = tags,
            isTaxilane = isTaxilane,
            name = name,
            geometry = line.map { GeoCoordinate(it) },
            oneway = oneway,
            access = tags["access"],
            widthMeters = width,
        )
    }

    private fun makeHold(e: OSMElement, tags: Map<String, String>): SurfaceHoldingPosition? {
        // Holding positions are nodes; some mappers place them as very short ways.
        val coord = e.coordinate ?: e.polyline.firstOrNull() ?: return null
        val ref = (tags["ref"] ?: "").trim { it == ' ' || it == '\t' }.uppercase()
        return SurfaceHoldingPosition(
            osmID = e.stableID,
            tags = tags,
            coordinate = GeoCoordinate(coord),
            runwayRef = ref,
            inferred = false,
        )
    }

    private fun makeParking(
        e: OSMElement,
        tags: Map<String, String>,
        kind: SurfaceParking.Kind,
    ): SurfaceParking? {
        val coord = e.coordinate ?: centroid(e.polyline) ?: return null
        // A stand's identifier tag may carry several identifiers for the one stand
        // ("A1;A2", "A54/A56"): the first names it, the rest are aliases it answers to.
        val identifier = StandIdentifier.parse(tags["ref"] ?: tags["name"] ?: "")
        return SurfaceParking(
            osmID = e.stableID,
            tags = tags,
            kind = kind,
            name = identifier.name,
            coordinate = GeoCoordinate(coord),
            aliases = identifier.aliases,
        )
    }

    private fun makeApron(e: OSMElement, tags: Map<String, String>): SurfaceApron? {
        val poly = e.polyline
        if (poly.size < 3) return null
        return SurfaceApron(osmID = e.stableID, tags = tags, polygon = poly.map { GeoCoordinate(it) })
    }

    /**
     * Movement-surface aeroway values — a feature carrying one of these is a routable
     * surface, never treated as a building even if it also has a stray `building` tag.
     */
    private val routableAeroways: Set<String> =
        setOf("runway", "taxiway", "taxilane", "holding_position", "gate", "parking_position", "apron")

    /**
     * Whether an element should be captured as a building / terminal footprint: an
     * `aeroway=terminal`, or any `building=*` (other than `building=no`) that is not
     * itself a movement surface.
     */
    private fun isBuilding(e: OSMElement, tags: Map<String, String>): Boolean {
        if (e.aeroway == "terminal") return true
        val aeroway = e.aeroway
        if (aeroway != null && routableAeroways.contains(aeroway)) return false
        val building = tags["building"]?.lowercase() ?: return false
        return building.isNotEmpty() && building != "no"
    }

    private fun makeBuilding(e: OSMElement, tags: Map<String, String>): SurfaceBuilding? {
        val poly = e.polyline
        if (poly.size < 3) return null
        return SurfaceBuilding(osmID = e.stableID, tags = tags, polygon = poly.map { GeoCoordinate(it) })
    }

    // MARK: - Helpers

    /** Magnetic heading implied by a runway ident's leading number (×10). "16L" → 160. */
    fun runwayHeading(ident: String): Double? {
        val digits = ident.takeWhile { it.isDigit() }
        val n = digits.toIntOrNull() ?: return null
        if (n < 1 || n > 36) return null
        return (n * 10).toDouble()
    }

    /**
     * Parse an OSM `width` value ("45", "45 m", "150 ft") into meters. Returns
     * (meters, inferred=false) on success, null when unparseable (caller defaults).
     */
    fun parseWidth(raw: String?): Pair<Double, Boolean>? {
        val text = raw?.trim { it == ' ' || it == '\t' }?.lowercase()
        if (text.isNullOrEmpty()) return null
        val numberPart = text.takeWhile { it.isDigit() || it == '.' }
        val value = numberPart.toDoubleOrNull() ?: return null
        if (value <= 0) return null
        if (text.contains("ft") || text.contains("'")) {
            return (value * 0.3048) to false
        }
        return value to false
    }

    /** Simple average-of-vertices centroid for a polygon/way. */
    fun centroid(points: List<Coordinate>): Coordinate? {
        if (points.isEmpty()) return null
        val lat = points.sumOf { it.latitude } / points.size
        val lon = points.sumOf { it.longitude } / points.size
        val c = Coordinate(lat, lon)
        return if (c.isValid) c else null
    }

    /**
     * A coarse dataset confidence from the normalized features alone (refined later
     * with graph connectivity by `SurfaceConfidenceEvaluator`). Names + holds + runway
     * geometry raise it; sparse/unnamed data lowers it.
     */
    fun preliminaryConfidence(m: AirportSurfaceModel): SurfaceConfidence {
        if (!m.hasUsableGeometry) return SurfaceConfidence.UNAVAILABLE
        val named = m.taxiwaysOnly.count { it.hasName }
        val namedFraction =
            if (m.taxiwaysOnly.isEmpty()) 0.0 else named.toDouble() / m.taxiwaysOnly.size.toDouble()
        val hasHolds = m.holdingPositions.isNotEmpty()
        if (namedFraction >= 0.6 && hasHolds && m.runways.size >= 1) {
            return SurfaceConfidence.HIGH
        }
        if (namedFraction >= 0.3 || hasHolds) {
            return SurfaceConfidence.MEDIUM
        }
        return SurfaceConfidence.LOW
    }
}
