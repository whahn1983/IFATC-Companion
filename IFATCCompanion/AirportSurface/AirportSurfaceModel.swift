import Foundation
import CoreLocation

/// A Codable latitude/longitude pair. `CLLocationCoordinate2D` is not `Codable`, so the
/// normalized (and cached) surface model uses this and converts at the edges.
struct GeoCoordinate: Codable, Equatable, Hashable {
    var latitude: Double
    var longitude: Double

    init(latitude: Double, longitude: Double) {
        self.latitude = latitude
        self.longitude = longitude
    }
    init(_ c: CLLocationCoordinate2D) {
        latitude = c.latitude
        longitude = c.longitude
    }

    var clLocation: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}

extension Array where Element == GeoCoordinate {
    var clLocations: [CLLocationCoordinate2D] { map { $0.clLocation } }
}

/// Confidence assigned to an airport dataset or a calculated route. Ordered so
/// `high > medium > low > unavailable`. Drives how precise the automatic behavior is
/// allowed to be (see the confidence model in the routing docs).
enum SurfaceConfidence: String, Codable, CaseIterable, Comparable {
    case high
    case medium
    case low
    case unavailable

    /// Higher rank = more confident.
    var rank: Int {
        switch self {
        case .high: return 3
        case .medium: return 2
        case .low: return 1
        case .unavailable: return 0
        }
    }

    var title: String {
        switch self {
        case .high: return "High"
        case .medium: return "Medium"
        case .low: return "Low"
        case .unavailable: return "Unavailable"
        }
    }

    /// High/Medium allow the automatic runway-crossing workflow (Medium requires an
    /// extra confirmation, handled by the coordinator). Low/Unavailable do not.
    var allowsAutomaticCrossing: Bool { self == .high || self == .medium }

    /// Whether detailed, turn-by-turn taxi routing should be issued at all.
    var allowsDetailedRouting: Bool { rank >= SurfaceConfidence.low.rank && self != .unavailable }

    static func < (lhs: SurfaceConfidence, rhs: SurfaceConfidence) -> Bool {
        lhs.rank < rhs.rank
    }
}

/// Provenance + license metadata carried with every normalized airport surface. Kept
/// with the cached data so attribution, license, source endpoint, fetch date, and the
/// original OSM extract size are always available — the app never presents OSM-derived
/// geometry without this.
struct SurfaceProvenance: Codable, Equatable {
    var provider: String = OSMSurface.providerName
    var license: String = OSMSurface.licenseName
    var attribution: String = OSMSurface.attributionText
    var endpoint: String
    var fetchDate: Date
    var boundingBox: OSMBoundingBox
    /// Number of raw OSM elements in the source extract (traceability / diagnostics).
    var rawElementCount: Int

    /// Age of the cached extract at read time.
    var cacheAge: TimeInterval { Date().timeIntervalSince(fetchDate) }

    /// Whether the extract is older than the configured refresh interval.
    var isStale: Bool { cacheAge > OSMSurface.cacheRefreshInterval }

    var cacheAgeDays: Int { Int(cacheAge / 86_400) }
}

// MARK: - Feature types

/// A runway: its `ref` idents, centerline geometry, and width. Original OSM id + tags
/// are preserved.
struct SurfaceRunway: Codable, Equatable, Identifiable {
    var osmID: String
    var tags: [String: String]
    /// Runway-end designators parsed from `ref` (e.g. "16L/34R" → ["16L", "34R"]).
    var idents: [String]
    var centerline: [GeoCoordinate]
    var widthMeters: Double
    var widthInferred: Bool

    var id: String { osmID }
    var displayName: String { idents.isEmpty ? "Runway" : idents.joined(separator: "/") }
}

/// A single directional runway end (threshold + heading), derived from a runway's
/// centerline and one of its idents.
struct SurfaceRunwayEnd: Codable, Equatable, Identifiable {
    var ident: String                 // "16L"
    var threshold: GeoCoordinate      // where this end's numbers are painted
    var oppositeThreshold: GeoCoordinate
    var headingDegrees: Double        // 0–360, from ident×10 (fallback: geometry)
    var runwayOSMID: String
    var widthMeters: Double

    var id: String { "\(runwayOSMID):\(ident)" }
}

/// A taxiway or taxilane centerline. `isTaxilane` distinguishes `aeroway=taxilane`
/// (apron/stand lead-in lanes) from `aeroway=taxiway`.
struct SurfaceTaxiway: Codable, Equatable, Identifiable {
    var osmID: String
    var tags: [String: String]
    var isTaxilane: Bool
    /// `ref` (the letter/number controllers use) preferred, else `name`, else "".
    var name: String
    var geometry: [GeoCoordinate]
    /// `oneway=yes` (directional restriction).
    var oneway: Bool
    /// Truthy `access` values indicating a closed / non-operational segment
    /// ("no", "private"). nil when unrestricted.
    var access: String?
    var widthMeters: Double?

    var id: String { osmID }
    var hasName: Bool { !name.isEmpty }
    /// Whether the taxiway is closed / non-operational per its access tag.
    var isClosed: Bool {
        guard let access = access?.lowercased() else { return false }
        return access == "no" || access == "private"
    }
}

/// A runway holding position (hold-short point). `inferred` marks a hold synthesized
/// for simulation where OSM had none mapped (always lower confidence).
struct SurfaceHoldingPosition: Codable, Equatable, Identifiable {
    var osmID: String
    var tags: [String: String]
    var coordinate: GeoCoordinate
    /// The runway this hold protects, from `ref` (e.g. "16L"). May be empty.
    var runwayRef: String
    var inferred: Bool

    var id: String { osmID }
}

/// A gate or parking position (aircraft stand).
struct SurfaceParking: Codable, Equatable, Identifiable {
    enum Kind: String, Codable {
        case gate
        case parkingPosition
    }
    var osmID: String
    var tags: [String: String]
    var kind: Kind
    /// `ref` preferred, else `name` (e.g. "B44").
    var name: String
    var coordinate: GeoCoordinate

    var id: String { osmID }
}

/// An apron area polygon.
struct SurfaceApron: Codable, Equatable, Identifiable {
    var osmID: String
    var tags: [String: String]
    var polygon: [GeoCoordinate]

    var id: String { osmID }
}

// MARK: - The normalized airport surface

/// The normalized internal airport-surface model, built from an OSM extract. Retains
/// every original OSM feature identifier and its tags, plus provenance / attribution /
/// license metadata and a dataset confidence.
///
/// This model — and the connected surface graph derived from it — may constitute an
/// OSM-derived database under the ODbL; the transformation is documented conservatively
/// (see `Docs/OpenStreetMapLicensing.md`) and reproduction information is made available.
struct AirportSurfaceModel: Codable, Equatable {
    var icao: String
    var reference: GeoCoordinate
    var runways: [SurfaceRunway]
    var runwayEnds: [SurfaceRunwayEnd]
    /// Taxiways and taxilanes together; use `taxiwaysOnly` / `taxilanes` to separate.
    var taxiways: [SurfaceTaxiway]
    var holdingPositions: [SurfaceHoldingPosition]
    var parkingPositions: [SurfaceParking]
    var aprons: [SurfaceApron]
    var source: SurfaceProvenance
    var confidence: SurfaceConfidence

    // MARK: Derived accessors

    var taxiwaysOnly: [SurfaceTaxiway] { taxiways.filter { !$0.isTaxilane } }
    var taxilanes: [SurfaceTaxiway] { taxiways.filter { $0.isTaxilane } }
    var gates: [SurfaceParking] { parkingPositions.filter { $0.kind == .gate } }
    var standCount: Int { parkingPositions.count }

    /// Whether there is enough geometry to attempt any routing at all.
    var hasUsableGeometry: Bool { !runways.isEmpty && !taxiways.isEmpty }

    /// All runway-end idents present at the field (e.g. ["16L","34R","09","27"]).
    var allRunwayIdents: [String] { runwayEnds.map { $0.ident } }

    /// The runway end matching an ident ("16L"), case-insensitively.
    func runwayEnd(ident: String) -> SurfaceRunwayEnd? {
        let key = ident.uppercased().trimmingCharacters(in: .whitespaces)
        return runwayEnds.first { $0.ident.uppercased() == key }
    }

    /// Locate the parking position (gate/stand) whose name matches, case-insensitively.
    func parking(named name: String) -> SurfaceParking? {
        let key = name.uppercased().trimmingCharacters(in: .whitespaces)
        guard !key.isEmpty else { return nil }
        return parkingPositions.first { $0.name.uppercased() == key }
    }

    /// The parking stand nearest `coordinate`, within `maxMeters` (nil when none is close).
    /// Used to identify the gate a departure taxi is leaving from its route start.
    func nearestParking(to coordinate: CLLocationCoordinate2D, within maxMeters: Double) -> SurfaceParking? {
        var best: (parking: SurfaceParking, distance: Double)?
        for p in parkingPositions {
            let d = SurfaceGeometry.distanceMeters(coordinate, p.coordinate.clLocation)
            guard d <= maxMeters else { continue }
            if best == nil || d < best!.distance { best = (p, d) }
        }
        return best?.parking
    }
}
