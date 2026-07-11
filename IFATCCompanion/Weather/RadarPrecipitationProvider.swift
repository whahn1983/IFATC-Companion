import Foundation
import CoreLocation
import CoreGraphics
import MapKit

// MARK: - Layer type
//
// Precipitation overlays come from two fundamentally different kinds of source
// and MUST be labeled accordingly. NOAA and EUMETNET OPERA are **true radar**;
// NASA GPM IMERG (via GIBS) is a **satellite precipitation estimate** and is never
// presented as radar. The app never shows the phrase "global radar".

enum PrecipitationLayerType {
    case radar             // NOAA/NWS, EUMETNET OPERA
    case satelliteEstimate // NASA GPM IMERG / GIBS

    /// The user-facing label for this layer type.
    var uiLabel: String {
        switch self {
        case .radar: return "Radar precipitation"
        case .satelliteEstimate: return "Satellite precipitation estimate"
        }
    }

    var isRadar: Bool { self == .radar }
}

// MARK: - Provider protocol
//
// A precipitation overlay provider for a map region. All fetches are keyless and
// free, and every conformer must be compatible with commercial app inclusion,
// redistribution/display, and attribution-only terms. Providers that would require
// a paid plan, user account, API-key billing, or non-commercial-only terms are out
// of scope. Selection order is NOAA → OPERA → NASA (see `PrecipitationOverlayService`).

protocol RadarPrecipitationProvider {
    var id: String { get }
    var displayName: String { get }
    var coverageDescription: String { get }
    var attributionText: String? { get }
    /// Whether this provider serves *true observed radar* (vs a satellite estimate
    /// or a mock stand-in). Never advertise satellite/mock data as true radar.
    var supportsTrueRadar: Bool { get }
    /// Radar vs satellite-estimate — drives the UI label and confidence.
    var layerType: PrecipitationLayerType { get }
    /// Coarse confidence in this layer (radar high, satellite lower).
    var confidence: HazardConfidence { get }

    /// Whether the provider covers a region (synchronous coverage check — no I/O).
    func covers(region: MKCoordinateRegion) -> Bool
    /// Whether the provider can actually **render an overlay** for the region right
    /// now — a stricter check than `covers`. A provider may geographically cover a
    /// region yet be unable to produce imagery there (e.g. no data endpoint is
    /// configured), in which case it must not be selected as the active overlay and
    /// selection falls through to the next provider. Defaults to `covers` (a provider
    /// that covers a region can render it) — override to gate on a live capability.
    func canRenderOverlay(for region: MKCoordinateRegion) -> Bool
    /// Whether the provider covers the region (async form; defaults to `covers`).
    func isAvailable(for region: MKCoordinateRegion) async -> Bool
    /// The time steps available for the region.
    func availableFrames(for region: MKCoordinateRegion) async throws -> [RadarFrame]
    /// XYZ tile URL for a frame, when the provider is tiled (else nil).
    func overlayTileURL(z: Int, x: Int, y: Int, frame: RadarFrame) async throws -> URL?
    /// Rendered PNG for a bounding box (fetches bytes).
    func exportImage(for bbox: RadarBoundingBox, size: CGSize, frame: RadarFrame) async throws -> Data?
    /// A synchronous URL for the rendered image, for SwiftUI `AsyncImage`. Nil when
    /// the provider cannot render an image for the region (fail gracefully).
    func exportImageURL(for bbox: RadarBoundingBox, size: CGSize, frame: RadarFrame?) -> URL?
}

extension RadarPrecipitationProvider {
    /// Default: a provider that geographically covers a region can also render it.
    /// Override where rendering depends on a live capability (e.g. a configured or
    /// reachable data source) that coverage alone doesn't guarantee.
    func canRenderOverlay(for region: MKCoordinateRegion) -> Bool { covers(region: region) }
    /// Default async availability delegates to the synchronous coverage check.
    func isAvailable(for region: MKCoordinateRegion) async -> Bool { covers(region: region) }
    /// Default: no synchronous image URL (conformers override where they can render).
    func exportImageURL(for bbox: RadarBoundingBox, size: CGSize, frame: RadarFrame?) -> URL? { nil }
    /// The user-facing layer label (radar vs satellite estimate).
    var uiLayerLabel: String { layerType.uiLabel }
}

// MARK: - NOAA / NWS

/// NOAA/NWS radar base reflectivity (MRMS) overlay provider. Public, keyless NWS
/// ArcGIS radar ImageServer. Coverage is limited to NOAA-covered regions
/// (contiguous U.S. + Alaska/Hawaii/Puerto Rico approximations).
struct NOAARadarPrecipitationProvider: RadarPrecipitationProvider {

    var baseURL: String

    init(baseURL: String = "https://mapservices.weather.noaa.gov/eventdriven/rest/services/radar/radar_base_reflectivity_time/ImageServer") {
        self.baseURL = baseURL
    }

    let id = "noaa-nws-radar"
    let displayName = "NOAA/NWS radar precipitation"
    let coverageDescription = "Available in NOAA-covered radar regions"
    let attributionText: String? = "Radar precipitation data: NOAA/NWS"
    let supportsTrueRadar = true
    let layerType: PrecipitationLayerType = .radar
    let confidence: HazardConfidence = .high

    /// Approximate NOAA radar coverage boxes (conservative; NOAA-covered regions
    /// only — never implying global coverage).
    static let coverageBoxes: [RadarBoundingBox] = [
        RadarBoundingBox(minLatitude: 20, minLongitude: -130, maxLatitude: 52, maxLongitude: -60),  // CONUS
        RadarBoundingBox(minLatitude: 50, minLongitude: -180, maxLatitude: 72, maxLongitude: -129), // Alaska
        RadarBoundingBox(minLatitude: 18, minLongitude: -161, maxLatitude: 23, maxLongitude: -154), // Hawaii
        RadarBoundingBox(minLatitude: 16, minLongitude: -68, maxLatitude: 20, maxLongitude: -64)     // Puerto Rico
    ]

    func covers(region: MKCoordinateRegion) -> Bool { Self.covers(region: region) }

    static func covers(region: MKCoordinateRegion) -> Bool {
        let box = RadarBoundingBox(region: region)
        return coverageBoxes.contains { $0.overlaps(box) }
    }

    static func covers(coordinate: CLLocationCoordinate2D) -> Bool {
        coverageBoxes.contains { $0.contains(coordinate) }
    }

    func availableFrames(for region: MKCoordinateRegion) async throws -> [RadarFrame] {
        guard covers(region: region) else { return [] }
        return [RadarFrame(id: "current", timestamp: Date(), isForecast: false, label: "Current")]
    }

    func overlayTileURL(z: Int, x: Int, y: Int, frame: RadarFrame) async throws -> URL? { nil }

    func exportImage(for bbox: RadarBoundingBox, size: CGSize, frame: RadarFrame) async throws -> Data? {
        guard let url = exportImageURL(for: bbox, size: size, frame: frame) else { return nil }
        var request = URLRequest(url: url)
        request.timeoutInterval = 12
        request.setValue("IFATCCompanion/1.0", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) { return nil }
        return data.isEmpty ? nil : data
    }

    func exportImageURL(for bbox: RadarBoundingBox, size: CGSize, frame: RadarFrame?) -> URL? {
        guard size.width > 0, size.height > 0 else { return nil }
        var components = URLComponents(string: "\(baseURL)/exportImage")
        // 4326 bbox, rendered in Web Mercator (3857) to align with MapKit.
        let bboxValue = "\(bbox.minLongitude),\(bbox.minLatitude),\(bbox.maxLongitude),\(bbox.maxLatitude)"
        components?.queryItems = [
            URLQueryItem(name: "bbox", value: bboxValue),
            URLQueryItem(name: "bboxSR", value: "4326"),
            URLQueryItem(name: "imageSR", value: "3857"),
            URLQueryItem(name: "size", value: "\(Int(size.width.rounded())),\(Int(size.height.rounded()))"),
            URLQueryItem(name: "format", value: "png"),
            URLQueryItem(name: "transparent", value: "true"),
            URLQueryItem(name: "f", value: "image")
        ]
        return components?.url
    }
}

// MARK: - Mock

/// A deterministic, offline radar stand-in for Mock Mode and tests. Advertises
/// itself as NOT true radar and serves precipitation as vector cells (drawn as
/// polygons), never as an image claiming to be observed radar.
struct MockRadarPrecipitationProvider: RadarPrecipitationProvider {
    let id = "mock-radar"
    let displayName = "Simulated radar (Mock Mode)"
    let coverageDescription = "Simulated coverage for the mock flight"
    let attributionText: String? = "Simulated precipitation — Mock Mode"
    let supportsTrueRadar = false
    let layerType: PrecipitationLayerType = .radar
    let confidence: HazardConfidence = .high

    func covers(region: MKCoordinateRegion) -> Bool { true }
    func availableFrames(for region: MKCoordinateRegion) async throws -> [RadarFrame] {
        [RadarFrame(id: "mock-current", timestamp: Date(), isForecast: false, label: "Current (mock)")]
    }
    func overlayTileURL(z: Int, x: Int, y: Int, frame: RadarFrame) async throws -> URL? { nil }
    func exportImage(for bbox: RadarBoundingBox, size: CGSize, frame: RadarFrame) async throws -> Data? { nil }
}
