import Foundation
import CoreLocation

/// Normalizes a raw OSM Overpass extract into an `AirportSurfaceModel`.
///
/// Only recognised `aeroway` features are kept; every retained feature carries its
/// original OSM identifier and full tag set (never discarded), plus provenance and
/// attribution metadata. Widths/holds/aprons are best-effort — where OSM omits a
/// value a conservative default is inferred and flagged as inferred/lower-confidence.
///
/// Nothing here treats OSM data as authoritative or guaranteed to match Infinite
/// Flight scenery.
enum OSMSurfaceNormalizer {

    /// Default runway width (meters) when OSM has no `width` tag — a mid-size value so
    /// the inferred crossing corridor is neither absurdly narrow nor wide.
    static let defaultRunwayWidthMeters = 45.0
    /// Default taxiway width (meters) when untagged.
    static let defaultTaxiwayWidthMeters = 23.0

    static func normalize(_ response: OverpassResponse,
                          icao: String,
                          reference: CLLocationCoordinate2D,
                          endpoint: String,
                          boundingBox: OSMBoundingBox,
                          fetchDate: Date) -> AirportSurfaceModel {
        // The query emits two `out` blocks (aeroway features, then buildings near the
        // movement surfaces), so an element tagged both ways — a terminal that is
        // `aeroway=terminal` and `building=*` — appears in both. Keep the first occurrence of
        // each stable OSM id so it isn't footprinted (or counted) twice.
        var seenIDs = Set<String>()
        let elements = response.elements.filter { seenIDs.insert($0.stableID).inserted }

        var runways: [SurfaceRunway] = []
        var runwayEnds: [SurfaceRunwayEnd] = []
        var taxiways: [SurfaceTaxiway] = []
        var holds: [SurfaceHoldingPosition] = []
        var parking: [SurfaceParking] = []
        var aprons: [SurfaceApron] = []
        var buildings: [SurfaceBuilding] = []

        // Refine the reference point from an aerodrome feature if OSM has one.
        var refined = reference

        for e in elements {
            let tags = e.tags ?? [:]
            // Building / terminal footprints (used to keep gate lead-ins from crossing a
            // concourse). Checked before the aeroway switch: a `building=*` element has no
            // aeroway tag, and an `aeroway=terminal` element is not a movement surface.
            if isBuilding(e, tags: tags), let building = makeBuilding(e, tags: tags) {
                buildings.append(building)
            }
            guard let aeroway = e.aeroway else { continue }
            switch aeroway {
            case "runway":
                if let runway = makeRunway(e, tags: tags) {
                    runways.append(runway)
                    runwayEnds.append(contentsOf: makeRunwayEnds(runway))
                }
            case "taxiway", "taxilane":
                if let taxiway = makeTaxiway(e, tags: tags, isTaxilane: aeroway == "taxilane") {
                    taxiways.append(taxiway)
                }
            case "holding_position":
                if let hold = makeHold(e, tags: tags) {
                    holds.append(hold)
                }
            case "gate":
                if let gate = makeParking(e, tags: tags, kind: .gate) {
                    parking.append(gate)
                }
            case "parking_position":
                if let stand = makeParking(e, tags: tags, kind: .parkingPosition) {
                    parking.append(stand)
                }
            case "apron":
                if let apron = makeApron(e, tags: tags) {
                    aprons.append(apron)
                }
            case "aerodrome":
                if let c = e.coordinate {
                    refined = c
                } else if let centroid = centroid(of: e.polyline) {
                    refined = centroid
                }
            default:
                continue
            }
        }

        let provenance = SurfaceProvenance(endpoint: endpoint,
                                           fetchDate: fetchDate,
                                           boundingBox: boundingBox,
                                           rawElementCount: elements.count)

        var model = AirportSurfaceModel(icao: icao.uppercased(),
                                        reference: GeoCoordinate(refined),
                                        runways: runways,
                                        runwayEnds: runwayEnds,
                                        taxiways: taxiways,
                                        holdingPositions: holds,
                                        parkingPositions: parking,
                                        aprons: aprons,
                                        buildings: buildings,
                                        source: provenance,
                                        confidence: .low)
        model.confidence = preliminaryConfidence(model)
        return model
    }

    // MARK: - Feature builders

    private static func makeRunway(_ e: OSMElement, tags: [String: String]) -> SurfaceRunway? {
        let line = e.polyline
        guard line.count >= 2 else { return nil }
        let ref = (tags["ref"] ?? tags["name"] ?? "").trimmingCharacters(in: .whitespaces)
        let idents = parseRunwayIdents(ref)
        let (width, inferred) = parseWidth(tags["width"]) ?? (defaultRunwayWidthMeters, true)
        return SurfaceRunway(osmID: e.stableID,
                             tags: tags,
                             idents: idents,
                             centerline: line.map(GeoCoordinate.init),
                             widthMeters: width,
                             widthInferred: inferred)
    }

    /// Split a runway `ref` into its two ends: "16L/34R" → ["16L","34R"]; "09/27" → …
    static func parseRunwayIdents(_ ref: String) -> [String] {
        let parts = ref.split(whereSeparator: { $0 == "/" || $0 == "-" })
            .map { $0.trimmingCharacters(in: .whitespaces).uppercased() }
            .filter { !$0.isEmpty }
        return parts
    }

    /// Build the two directional ends of a runway from its centerline + idents.
    private static func makeRunwayEnds(_ r: SurfaceRunway) -> [SurfaceRunwayEnd] {
        guard let first = r.centerline.first?.clLocation,
              let last = r.centerline.last?.clLocation else { return [] }
        guard !r.idents.isEmpty else { return [] }
        var ends: [SurfaceRunwayEnd] = []
        for ident in r.idents {
            let heading = runwayHeading(ident) ?? Geo.bearing(from: first, to: last)
            // Threshold for this ident is the end you start the takeoff roll from —
            // the end whose bearing toward the opposite end matches the ident heading.
            let bFirstToLast = Geo.bearing(from: first, to: last)
            let bLastToFirst = Geo.bearing(from: last, to: first)
            let threshold: CLLocationCoordinate2D
            let opposite: CLLocationCoordinate2D
            if Geo.headingDifference(bFirstToLast, heading) <= Geo.headingDifference(bLastToFirst, heading) {
                threshold = first; opposite = last
            } else {
                threshold = last; opposite = first
            }
            ends.append(SurfaceRunwayEnd(ident: ident,
                                         threshold: GeoCoordinate(threshold),
                                         oppositeThreshold: GeoCoordinate(opposite),
                                         headingDegrees: heading,
                                         runwayOSMID: r.osmID,
                                         widthMeters: r.widthMeters))
        }
        return ends
    }

    private static func makeTaxiway(_ e: OSMElement, tags: [String: String], isTaxilane: Bool) -> SurfaceTaxiway? {
        let line = e.polyline
        guard line.count >= 2 else { return nil }
        let name = (tags["ref"] ?? tags["name"] ?? "").trimmingCharacters(in: .whitespaces)
        let onewayRaw = tags["oneway"]?.lowercased()
        let oneway = onewayRaw == "yes" || onewayRaw == "true" || onewayRaw == "1"
        let width = parseWidth(tags["width"])?.0
        return SurfaceTaxiway(osmID: e.stableID,
                              tags: tags,
                              isTaxilane: isTaxilane,
                              name: name,
                              geometry: line.map(GeoCoordinate.init),
                              oneway: oneway,
                              access: tags["access"],
                              widthMeters: width)
    }

    private static func makeHold(_ e: OSMElement, tags: [String: String]) -> SurfaceHoldingPosition? {
        // Holding positions are nodes; some mappers place them as very short ways.
        let coord = e.coordinate ?? e.polyline.first
        guard let coord else { return nil }
        let ref = (tags["ref"] ?? "").trimmingCharacters(in: .whitespaces).uppercased()
        return SurfaceHoldingPosition(osmID: e.stableID,
                                      tags: tags,
                                      coordinate: GeoCoordinate(coord),
                                      runwayRef: ref,
                                      inferred: false)
    }

    private static func makeParking(_ e: OSMElement, tags: [String: String], kind: SurfaceParking.Kind) -> SurfaceParking? {
        let coord = e.coordinate ?? centroid(of: e.polyline)
        guard let coord else { return nil }
        let name = (tags["ref"] ?? tags["name"] ?? "").trimmingCharacters(in: .whitespaces)
        return SurfaceParking(osmID: e.stableID,
                              tags: tags,
                              kind: kind,
                              name: name,
                              coordinate: GeoCoordinate(coord))
    }

    private static func makeApron(_ e: OSMElement, tags: [String: String]) -> SurfaceApron? {
        let poly = e.polyline
        guard poly.count >= 3 else { return nil }
        return SurfaceApron(osmID: e.stableID, tags: tags, polygon: poly.map(GeoCoordinate.init))
    }

    /// Movement-surface aeroway values — a feature carrying one of these is a routable
    /// surface, never treated as a building even if it also has a stray `building` tag.
    private static let routableAeroways: Set<String> =
        ["runway", "taxiway", "taxilane", "holding_position", "gate", "parking_position", "apron"]

    /// Whether an element should be captured as a building / terminal footprint: an
    /// `aeroway=terminal`, or any `building=*` (other than `building=no`) that is not
    /// itself a movement surface.
    private static func isBuilding(_ e: OSMElement, tags: [String: String]) -> Bool {
        if e.aeroway == "terminal" { return true }
        if let aeroway = e.aeroway, routableAeroways.contains(aeroway) { return false }
        guard let building = tags["building"]?.lowercased() else { return false }
        return !building.isEmpty && building != "no"
    }

    private static func makeBuilding(_ e: OSMElement, tags: [String: String]) -> SurfaceBuilding? {
        let poly = e.polyline
        guard poly.count >= 3 else { return nil }
        return SurfaceBuilding(osmID: e.stableID, tags: tags, polygon: poly.map(GeoCoordinate.init))
    }

    // MARK: - Helpers

    /// Magnetic heading implied by a runway ident's leading number (×10). "16L" → 160.
    static func runwayHeading(_ ident: String) -> Double? {
        let digits = ident.prefix { $0.isNumber }
        guard let n = Int(digits), n >= 1, n <= 36 else { return nil }
        return Double(n * 10)
    }

    /// Parse an OSM `width` value ("45", "45 m", "150 ft") into meters. Returns
    /// (meters, inferred=false) on success, nil when unparseable (caller defaults).
    static func parseWidth(_ raw: String?) -> (Double, Bool)? {
        guard let raw = raw?.trimmingCharacters(in: .whitespaces).lowercased(), !raw.isEmpty else { return nil }
        let numberPart = raw.prefix { $0.isNumber || $0 == "." }
        guard let value = Double(numberPart), value > 0 else { return nil }
        if raw.contains("ft") || raw.contains("'") {
            return (value * 0.3048, false)
        }
        return (value, false)
    }

    /// Simple average-of-vertices centroid for a polygon/way.
    static func centroid(of points: [CLLocationCoordinate2D]) -> CLLocationCoordinate2D? {
        guard !points.isEmpty else { return nil }
        let lat = points.map(\.latitude).reduce(0, +) / Double(points.count)
        let lon = points.map(\.longitude).reduce(0, +) / Double(points.count)
        let c = CLLocationCoordinate2D(latitude: lat, longitude: lon)
        return c.isValid ? c : nil
    }

    /// A coarse dataset confidence from the normalized features alone (refined later
    /// with graph connectivity by `SurfaceConfidenceEvaluator`). Names + holds + runway
    /// geometry raise it; sparse/unnamed data lowers it.
    static func preliminaryConfidence(_ m: AirportSurfaceModel) -> SurfaceConfidence {
        guard m.hasUsableGeometry else { return .unavailable }
        let named = m.taxiwaysOnly.filter { $0.hasName }.count
        let namedFraction = m.taxiwaysOnly.isEmpty ? 0 : Double(named) / Double(m.taxiwaysOnly.count)
        let hasHolds = !m.holdingPositions.isEmpty
        if namedFraction >= 0.6 && hasHolds && m.runways.count >= 1 {
            return .high
        }
        if namedFraction >= 0.3 || hasHolds {
            return .medium
        }
        return .low
    }
}
