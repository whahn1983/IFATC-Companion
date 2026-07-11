import Foundation
import CoreLocation

// MARK: - Radar overlay

/// One radar time step. For NOAA base reflectivity / MRMS this is an observed
/// frame (`isForecast == false`); the type carries a forecast flag only so a
/// future provider that offered nowcast frames could be added without a model
/// change. This app never displays forecast/model precipitation *as* radar.
struct RadarFrame: Identifiable, Equatable {
    var id: String
    var timestamp: Date
    var isForecast: Bool
    var label: String

    init(id: String, timestamp: Date, isForecast: Bool = false, label: String) {
        self.id = id
        self.timestamp = timestamp
        self.isForecast = isForecast
        self.label = label
    }
}

/// A deterministic precipitation cell. Used for Mock Mode and tests (where we
/// have an exact polygon), and as the vector form NOAA raster sampling reduces to
/// so the route-conflict detector can treat both uniformly. Identity-only
/// equality (it carries non-Equatable coordinates).
struct RadarCell: Identifiable {
    var id = UUID()
    var polygon: [CLLocationCoordinate2D]
    var intensity: WeatherIntensity
    var movementDirectionDegrees: Double?
    var movementSpeedKnots: Double?

    init(polygon: [CLLocationCoordinate2D],
         intensity: WeatherIntensity,
         movementDirectionDegrees: Double? = nil,
         movementSpeedKnots: Double? = nil) {
        self.polygon = polygon
        self.intensity = intensity
        self.movementDirectionDegrees = movementDirectionDegrees
        self.movementSpeedKnots = movementSpeedKnots
    }

    var center: CLLocationCoordinate2D? {
        let valid = polygon.filter { $0.isValid }
        guard !valid.isEmpty else { return nil }
        let lat = valid.map { $0.latitude }.reduce(0, +) / Double(valid.count)
        let lon = valid.map { $0.longitude }.reduce(0, +) / Double(valid.count)
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }
}

/// The state the Weather View renders for the radar precipitation layer. Purely
/// descriptive — the actual tile/image fetch is done by `RadarOverlayRenderer`
/// from the live provider so this model never holds stale imagery.
struct RadarOverlayModel {
    /// Whether the user's setting enables the overlay (Auto where available).
    var isEnabled: Bool = false
    /// Whether a provider (NOAA / OPERA / NASA) actually covers the current region.
    var coverageAvailable: Bool = false
    var opacity: Double = 0.55
    var lastUpdated: Date?
    /// The active provider's display name (e.g. "NOAA/NWS radar precipitation").
    var sourceDescription: String = "NOAA/NWS radar precipitation"
    var attributionText: String? = "Radar precipitation data: NOAA/NWS"
    var coverageLabel: String = "Available in NOAA-covered radar regions"
    var unavailableMessage: String = "Precipitation overlay unavailable for this region."
    /// Radar vs satellite estimate — drives the user-facing layer label so a
    /// satellite estimate is never presented as radar.
    var layerType: PrecipitationLayerType = .radar
    /// The user-facing layer label ("Radar precipitation" / "Satellite
    /// precipitation estimate").
    var layerLabel: String = "Radar precipitation"
    /// Whether the active layer is a (lower-confidence) satellite estimate.
    var isSatelliteEstimate: Bool { layerType == .satelliteEstimate }
    var frames: [RadarFrame] = []
    /// Deterministic precipitation cells for Mock Mode / tests. Empty in live
    /// mode (live precipitation is drawn from the NOAA image overlay instead).
    var mockCells: [RadarCell] = []
    /// Moderate-or-greater precipitation cells sampled from the live radar image
    /// (NOAA/OPERA base reflectivity). These are the sole input to the weather-
    /// deviation flow, which threads the widest clear gap between them — never drawn
    /// on the map (the radar image overlay already shows the precipitation). Empty in
    /// Mock Mode (which uses `mockCells`) and wherever true-radar sampling is
    /// unavailable.
    var sampledCells: [RadarCell] = []

    /// Whether the overlay should actually be shown on the map right now.
    var shouldDisplay: Bool { isEnabled && coverageAvailable }
}

// MARK: - Deviation flow

/// Which side of course a deviation is requested / approved on.
enum DeviationDirection: String, Codable {
    case left
    case right

    var opposite: DeviationDirection { self == .left ? .right : .left }
    var word: String { rawValue }
}

/// Simulated ATC weather-deviation flow state. Mirrors the request → approval →
/// clear-of-weather → rejoin lifecycle. `radarUnavailableForRegion` is a terminal
/// informational state used outside NOAA coverage with no advisory data.
enum WeatherDeviationState: String, Codable, CaseIterable {
    case none
    case weatherAheadDetected
    case advisoryIssued
    case awaitingPilotIntentions
    case deviationRequested
    case deviationApproved
    case vectoringAroundWeather
    case deviatingAroundWeather
    case clearOfWeather
    case rejoinClearanceIssued
    case resumedOwnNavigation
    case radarUnavailableForRegion

    /// Whether the aircraft is currently off its filed course for weather (so the
    /// telemetry loop should watch for "clear of weather").
    var isDeviating: Bool {
        self == .deviationApproved || self == .deviatingAroundWeather || self == .vectoringAroundWeather
    }

    /// Whether the pilot has committed to a controller-approved reroute — a lateral
    /// deviation or a vector is being flown. While committed the mint line is
    /// **locked** to the path the pilot is following: a fresh radar sample no longer
    /// moves or re-proposes it, and confirm-clear hysteresis never tears it down.
    /// The lock releases only on clear-of-weather (or a fresh reroute request).
    var isCommittedDeviation: Bool {
        switch self {
        case .deviationApproved, .vectoringAroundWeather, .deviatingAroundWeather, .clearOfWeather:
            return true
        default:
            return false
        }
    }
}

/// A reference to the filed route segment a deviation departs from (by fix name),
/// so the rejoin clearance can name where the aircraft left course.
struct RouteSegmentRef: Codable {
    var from: String
    var to: String
}

/// Mutable storage for the active weather-deviation interaction. Held on
/// `AppModel`; the phraseology/engine read and update it. `Codable` so an
/// in-progress deviation can be captured in the session snapshot and restored on
/// reconnect (otherwise the deviation card and its "clear of weather" button
/// vanish when the Infinite Flight link drops and comes back mid-diversion).
struct WeatherDeviationContext: Codable {
    /// A Codable lat/lon pair. `CLLocationCoordinate2D` is not `Codable`, so the
    /// frozen mint line is stored as these, mirroring how the vector apex is stored
    /// as separate `Double`s so an in-progress deviation survives a reconnect.
    struct PathPoint: Codable {
        var latitude: Double
        var longitude: Double
        init(_ c: CLLocationCoordinate2D) { latitude = c.latitude; longitude = c.longitude }
        var coordinate: CLLocationCoordinate2D {
            CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
        }
    }

    var state: WeatherDeviationState = .none
    var activeHazardID: UUID?
    /// The mint deviation line the pilot has committed to fly, frozen when the
    /// controller approves the vector/deviation. While set, the map draws this fixed
    /// path instead of the live per-sample recommendation, so the mint line stops
    /// shifting and blinking once the pilot is following it. Cleared on clear-of-
    /// weather / reset; replaced only when the pilot requests another reroute.
    var committedDeviationPath: [PathPoint]?
    var requestedDeviationDirection: DeviationDirection?
    var approvedDeviationDegrees: Int?
    var assignedHeading: Int?
    var maintainAltitude: Int?
    var rejoinFix: String?
    /// The next turn point in the committed mint line (the vertex the aircraft is
    /// flying toward) and the heading to fly out of it toward the following vertex,
    /// so the telemetry loop can auto-issue the turn once the aircraft reaches it. A
    /// side-hug line (`[start, turnOut, turnBack, rejoin]`) has **two** such turns —
    /// out onto the parallel leg, then back down to the route — so these advance from
    /// one interior vertex to the next each time a turn fires, rather than clearing
    /// after a single turn. `pendingTurnIndex` is the index (into the committed line)
    /// of the vertex being turned at; all are cleared when the final turn fires.
    var pendingTurnIndex: Int?
    var vectorApexLatitude: Double?
    var vectorApexLongitude: Double?
    var pendingRejoinHeading: Int?
    /// Bearing of the leg leading into the pending turn vertex (previous → apex), so
    /// the loop can detect the aircraft passing abeam/past the vertex even if it flies
    /// wide of it.
    var vectorLegBearing: Double?
    /// A deviation approved while the mint line is still drawn ahead: the turn-out
    /// (start of the mint line) the aircraft is flying toward, the heading to fly out of
    /// it onto the reroute, and the bearing of the leg into it (to detect passing abeam).
    /// While these are set the controller has approved the deviation but is **holding the
    /// turn** — the pilot continues on course until reaching the turn-out, then the
    /// beginning turn is issued. Cleared once the turn fires (or on reset).
    var deviationStartLatitude: Double?
    var deviationStartLongitude: Double?
    var deviationStartHeading: Int?
    var deviationStartLegBearing: Double?
    var originalRouteSegment: RouteSegmentRef?
    var timeDeviationStarted: Date?
    var lastATCWeatherCall: String?
    var radarCoverageAvailable: Bool = false
    var radarSourceDescription: String = "NOAA/NWS radar precipitation"

    static let none = WeatherDeviationContext()

    /// Reset back to the idle state, keeping the radar coverage/source facts so the
    /// diagnostics panel still reflects the last known provider status.
    mutating func reset() {
        let coverage = radarCoverageAvailable
        let source = radarSourceDescription
        self = WeatherDeviationContext()
        radarCoverageAvailable = coverage
        radarSourceDescription = source
    }
}

// MARK: - Conflict

/// A detected route-weather conflict, produced by `RouteWeatherConflictDetector`.
/// Identity-only equality (carries coordinate geometry).
struct RouteWeatherConflict: Identifiable {
    let id = UUID()
    var hazard: WeatherHazard
    /// Distance from the aircraft to the near edge of the weather (NM).
    var distanceAheadNM: Double
    /// Bearing to the weather relative to the aircraft's course (−180…180; + is right).
    var relativeBearingDegrees: Double
    /// Clock positions (1…12) for the left edge, center, and right edge of the cell.
    var leftClock: Int
    var centerClock: Int
    var rightClock: Int
    var estimatedTimeMinutes: Double?
    var severity: WeatherIntensity
    var leftBypassScore: Double
    var rightBypassScore: Double
    var recommendedDirection: DeviationDirection
    var recommendedDeviationDegrees: Int
    var rejoinFix: Waypoint?
    var originalSegment: RouteSegmentRef?
    /// Whether to raise the "contact ATC" banner and (in Mock Mode) auto-issue the
    /// advisory. True only for genuinely on-path weather that is also within the
    /// tactical deviation range (`withinTacticalRange`); far-ahead weather is detected
    /// and monitored without yet triggering the banner.
    var shouldPrompt: Bool
    /// Whether the weather is close enough (near edge within `deviationTriggerNM`) to
    /// work the deviation now. The banner / ATC advisory hold off until this is true.
    var withinTacticalRange: Bool = true
    /// Whether the weather is close enough (near edge within `mintLineDrawNM`) to draw
    /// the recommended reroute line on the map. A conflict is still detected out to the
    /// full lookahead — so Diagnostics can report far weather as "monitoring" — but the
    /// mint line is held until this is true, so a straight-corridor deviation aimed at
    /// distant weather (across the route's bends) doesn't render as a runaway line.
    var withinDrawRange: Bool = true
    /// The polygon the route passes through, for shading on the map.
    var intersectionArea: [CLLocationCoordinate2D]
    /// A recommended deviation path for drawing on the map: `position → turn(s) →
    /// rejoin`. A single-apex dogleg has three points; a side-hug down one edge of a
    /// long line has four (step-out, run parallel, then close to the rejoin).
    var deviationPath: [CLLocationCoordinate2D]

    var isConvectiveSigmet: Bool { hazard.isConvectiveSigmet }
    var source: WeatherHazardSource { hazard.source }
}
