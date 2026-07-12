import Foundation
import CoreLocation
import MapKit

// MARK: - Hazard taxonomy
//
// A normalized, source-tagged weather hazard used by the route-conflict detector
// and the simulated weather-deviation flow. Deliberately kept separate from the
// existing ride-report / SIGMET types: `WeatherHazard` unifies NOAA radar
// precipitation, SIGMETs, PIREPs and the other aviation advisories behind one
// shape so the deviation logic can reason about "weather ahead" without caring
// where it came from — while the `source` tag preserves *what kind* of report it
// is so phraseology never, for example, calls radar colors "turbulence".

/// Where a hazard came from. Coverage limitations differ by source (see
/// `Docs/Weather.md`): NOAA radar is NOAA-covered-regions only; PIREPs/AIREPs are
/// primarily U.S. + North Atlantic; G-AIRMET is contiguous-U.S. only.
enum WeatherHazardSource: String, Codable, CaseIterable {
    case noaaRadar
    /// NASA GPM IMERG / GIBS global satellite precipitation *estimate* (not radar).
    /// Only ever drives a deviation when the user opts in via the satellite-estimate
    /// deviation setting; kept as its own source so diagnostics and phraseology never
    /// present the estimate as radar-grade.
    case satelliteEstimate
    case sigmet
    case pirep
    case metar
    case taf
    case cwa
    case gairmet
    case unknown

    /// Short human label used in diagnostics / data-source captions.
    var label: String {
        switch self {
        case .noaaRadar: return "NOAA/NWS radar precipitation"
        case .satelliteEstimate: return "NASA satellite precipitation estimate"
        case .sigmet: return "SIGMET"
        case .pirep: return "PIREP"
        case .metar: return "METAR"
        case .taf: return "TAF"
        case .cwa: return "CWA"
        case .gairmet: return "G-AIRMET"
        case .unknown: return "Unknown"
        }
    }

    /// Whether a "turbulence" characterization is ever valid for this source.
    /// Radar reflectivity is precipitation intensity only — it must never be
    /// spoken as turbulence. Turbulence wording is reserved for the report types
    /// that actually measure or forecast it.
    var supportsTurbulenceWording: Bool {
        switch self {
        case .pirep, .sigmet, .cwa, .gairmet: return true
        case .noaaRadar, .satelliteEstimate, .metar, .taf, .unknown: return false
        }
    }
}

/// The phenomenon a hazard represents.
enum WeatherPhenomenon: String, Codable, CaseIterable {
    case precipitation
    case thunderstorm
    case turbulence
    case icing
    case windShear
    case lowCeiling
    case lowVisibility
    case unknown
}

/// A coarse intensity scale shared by radar precipitation and hazard severity.
/// `unknown` sorts below `light` so "intensity unknown" never outranks a graded
/// cell in severity comparisons.
enum WeatherIntensity: Int, Codable, CaseIterable, Comparable {
    case unknown = -1
    case light = 0
    case moderate = 1
    case heavy = 2
    case extreme = 3

    static func < (lhs: WeatherIntensity, rhs: WeatherIntensity) -> Bool {
        lhs.rawValue < rhs.rawValue
    }

    /// Precipitation phrasing for radar-derived hazards. Always says
    /// "precipitation" — never "turbulence" — because radar shows precipitation.
    var spokenPrecipitation: String {
        switch self {
        case .light: return "light precipitation"
        case .moderate: return "moderate precipitation"
        case .heavy: return "heavy precipitation"
        case .extreme: return "extreme precipitation"
        case .unknown: return "precipitation"
        }
    }

    /// Short display label for legends / diagnostics.
    var displayLabel: String {
        switch self {
        case .light: return "Light"
        case .moderate: return "Moderate"
        case .heavy: return "Heavy"
        case .extreme: return "Extreme"
        case .unknown: return "Unknown"
        }
    }
}

// MARK: - Geometry

/// A lat/lon bounding box (used for radar coverage checks and image export).
struct RadarBoundingBox: Equatable {
    var minLatitude: Double
    var minLongitude: Double
    var maxLatitude: Double
    var maxLongitude: Double

    init(minLatitude: Double, minLongitude: Double, maxLatitude: Double, maxLongitude: Double) {
        self.minLatitude = min(minLatitude, maxLatitude)
        self.maxLatitude = max(minLatitude, maxLatitude)
        self.minLongitude = min(minLongitude, maxLongitude)
        self.maxLongitude = max(minLongitude, maxLongitude)
    }

    /// Build a box from a MapKit coordinate region (the map's visible extent).
    ///
    /// Longitude is linear in Web Mercator, so the east/west edges are just
    /// `center ± longitudeDelta/2`. Latitude is **not**: MapKit draws the map in Web
    /// Mercator (EPSG:3857) with `region.center` at the view's centre, so
    /// reconstructing the north/south edges as `center ± latitudeDelta/2` in *degrees*
    /// yields a box that is off-centre in Mercator — and the offset grows with the
    /// span. An overlay requested for that box (the NASA GIBS / OPERA WMS layers all
    /// export in 3857) therefore drifts vertically as the map is zoomed instead of
    /// simply scaling. Placing the edges symmetrically about the centre *in Mercator*
    /// keeps the overlay registered at every zoom level.
    init(region: MKCoordinateRegion) {
        let lonHalf = region.span.longitudeDelta / 2
        let centerLat = region.center.latitude
        let latHalf = region.span.latitudeDelta / 2

        // Normalized (Earth-radius-free) Web-Mercator y and its inverse; the radius
        // cancels because we only use y to re-centre the latitude span symmetrically.
        func mercatorY(_ lat: Double) -> Double {
            let clamped = min(85.05112878, max(-85.05112878, lat))
            return log(tan(.pi / 4 + clamped * .pi / 180 / 2))
        }
        func latitude(fromMercatorY y: Double) -> Double {
            (2 * atan(exp(y)) - .pi / 2) * 180 / .pi
        }

        let yCenter = mercatorY(centerLat)
        let yHalf = (mercatorY(centerLat + latHalf) - mercatorY(centerLat - latHalf)) / 2
        self.init(minLatitude: latitude(fromMercatorY: yCenter - yHalf),
                  minLongitude: region.center.longitude - lonHalf,
                  maxLatitude: latitude(fromMercatorY: yCenter + yHalf),
                  maxLongitude: region.center.longitude + lonHalf)
    }

    var center: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: (minLatitude + maxLatitude) / 2,
                               longitude: (minLongitude + maxLongitude) / 2)
    }

    /// The four corners as a closed-ish polygon (SW, SE, NE, NW).
    var corners: [CLLocationCoordinate2D] {
        [CLLocationCoordinate2D(latitude: minLatitude, longitude: minLongitude),
         CLLocationCoordinate2D(latitude: minLatitude, longitude: maxLongitude),
         CLLocationCoordinate2D(latitude: maxLatitude, longitude: maxLongitude),
         CLLocationCoordinate2D(latitude: maxLatitude, longitude: minLongitude)]
    }

    func contains(_ c: CLLocationCoordinate2D) -> Bool {
        c.latitude >= minLatitude && c.latitude <= maxLatitude
            && c.longitude >= minLongitude && c.longitude <= maxLongitude
    }

    /// Whether this box overlaps another (axis-aligned).
    func overlaps(_ other: RadarBoundingBox) -> Bool {
        minLatitude <= other.maxLatitude && maxLatitude >= other.minLatitude
            && minLongitude <= other.maxLongitude && maxLongitude >= other.minLongitude
    }

    /// The bbox in Web Mercator (EPSG:3857) meters as "xmin,ymin,xmax,ymax", for
    /// WMS providers that render in 3857 (aligns with MapKit's projection).
    var mercatorBBoxString: String {
        func x(_ lon: Double) -> Double { lon * 20037508.342789244 / 180 }
        func y(_ lat: Double) -> Double {
            let clamped = min(85.05112878, max(-85.05112878, lat))
            let rad = clamped * .pi / 180
            return log(tan(.pi / 4 + rad / 2)) * 6378137.0
        }
        return "\(x(minLongitude)),\(y(minLatitude)),\(x(maxLongitude)),\(y(maxLatitude))"
    }
}

/// The shape a hazard occupies. Kept as an enum so point reports (PIREPs),
/// advisory polygons (SIGMETs), radar boxes and computed route intersections all
/// reduce to a drawable/testable geometry.
enum HazardGeometry {
    case pointRadius(center: CLLocationCoordinate2D, radiusNM: Double)
    case polygon([CLLocationCoordinate2D])
    case boundingBox(RadarBoundingBox)
    case routeSegmentIntersection(entry: CLLocationCoordinate2D, exit: CLLocationCoordinate2D)

    /// A drawable polygon (≥3 valid vertices) when one exists, for the map + the
    /// point-in-polygon / edge-crossing route tests. Point/segment geometries
    /// return nil (they are handled by distance/segment tests instead).
    var polygonPoints: [CLLocationCoordinate2D]? {
        switch self {
        case .polygon(let pts):
            let valid = pts.filter { $0.isValid }
            return valid.count >= 3 ? valid : nil
        case .boundingBox(let box):
            return box.corners
        case .pointRadius, .routeSegmentIntersection:
            return nil
        }
    }

    /// A single representative coordinate (polygon centroid, point center, or the
    /// midpoint of a route intersection).
    var representativeCenter: CLLocationCoordinate2D? {
        switch self {
        case .pointRadius(let center, _):
            return center.isValid ? center : nil
        case .boundingBox(let box):
            return box.center
        case .polygon(let pts):
            let valid = pts.filter { $0.isValid }
            guard !valid.isEmpty else { return nil }
            let lat = valid.map { $0.latitude }.reduce(0, +) / Double(valid.count)
            let lon = valid.map { $0.longitude }.reduce(0, +) / Double(valid.count)
            return CLLocationCoordinate2D(latitude: lat, longitude: lon)
        case .routeSegmentIntersection(let entry, let exit):
            return CLLocationCoordinate2D(latitude: (entry.latitude + exit.latitude) / 2,
                                          longitude: (entry.longitude + exit.longitude) / 2)
        }
    }
}

// MARK: - Hazard

/// Coarse confidence in a hazard, used to weight prompting decisions.
enum HazardConfidence: String, Codable {
    case high
    case medium
    case low
}

/// A normalized weather hazard. Equatable/Hashable by `id` only (it carries
/// non-Equatable `CLLocationCoordinate2D` geometry, following the same pattern as
/// the existing `SIGMET` model).
struct WeatherHazard: Identifiable {
    var id = UUID()
    var source: WeatherHazardSource
    var providerID: String?
    var phenomenon: WeatherPhenomenon
    var intensity: WeatherIntensity
    var geometry: HazardGeometry
    var confidence: HazardConfidence
    var validFrom: Date?
    var validUntil: Date?
    var movementDirectionDegrees: Double?
    var movementSpeedKnots: Double?
    var distanceAheadNM: Double?
    var estimatedTimeToHazardMinutes: Double?
    var altitudeLower: Int?
    var altitudeUpper: Int?
    var notes: String?

    init(source: WeatherHazardSource,
         providerID: String? = nil,
         phenomenon: WeatherPhenomenon,
         intensity: WeatherIntensity,
         geometry: HazardGeometry,
         confidence: HazardConfidence = .medium,
         validFrom: Date? = nil,
         validUntil: Date? = nil,
         movementDirectionDegrees: Double? = nil,
         movementSpeedKnots: Double? = nil,
         distanceAheadNM: Double? = nil,
         estimatedTimeToHazardMinutes: Double? = nil,
         altitudeLower: Int? = nil,
         altitudeUpper: Int? = nil,
         notes: String? = nil) {
        self.source = source
        self.providerID = providerID
        self.phenomenon = phenomenon
        self.intensity = intensity
        self.geometry = geometry
        self.confidence = confidence
        self.validFrom = validFrom
        self.validUntil = validUntil
        self.movementDirectionDegrees = movementDirectionDegrees
        self.movementSpeedKnots = movementSpeedKnots
        self.distanceAheadNM = distanceAheadNM
        self.estimatedTimeToHazardMinutes = estimatedTimeToHazardMinutes
        self.altitudeLower = altitudeLower
        self.altitudeUpper = altitudeUpper
        self.notes = notes
    }

    /// Whether the movement vector is known well enough to voice ("moving east at
    /// two zero knots"). Both a direction and a non-trivial speed are required.
    var hasKnownMovement: Bool {
        guard let dir = movementDirectionDegrees, dir >= 0,
              let spd = movementSpeedKnots, spd >= 1 else { return false }
        return true
    }

    /// Whether this hazard is a convective SIGMET (thunderstorm activity), which
    /// warrants the stronger "convective weather / thunderstorms" phrasing.
    var isConvectiveSigmet: Bool {
        source == .sigmet && (phenomenon == .thunderstorm)
    }
}
