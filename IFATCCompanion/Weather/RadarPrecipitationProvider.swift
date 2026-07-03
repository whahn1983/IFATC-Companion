import Foundation
import CoreLocation
import CoreGraphics
import MapKit

// MARK: - Provider protocol
//
// The radar provider architecture is intentionally minimal and NOAA-only for v1.
// The protocol exists so a *mock* provider can stand in for tests / Mock Mode —
// NOT so paid or unclear-commercial-use providers can be plugged in. Only two
// conformers ship: `NOAARadarPrecipitationProvider` and
// `MockRadarPrecipitationProvider`. See `Docs/Weather.md` for the data-source rules.

/// A source of radar precipitation overlays for a map region. All fetches are
/// keyless and free; any conformer that would require a paid plan, user account,
/// API-key billing, or non-commercial-only terms is explicitly out of scope.
protocol RadarPrecipitationProvider {
    var id: String { get }
    var displayName: String { get }
    var coverageDescription: String { get }
    var attributionText: String? { get }
    /// Whether this provider serves *true observed radar* (vs a simulated/mock
    /// stand-in). Never advertise mock/model data as true radar.
    var supportsTrueRadar: Bool { get }

    /// Whether the provider covers the given region (coverage check — no network).
    func isAvailable(for region: MKCoordinateRegion) async -> Bool
    /// The radar time steps available for the region (observed frames).
    func availableFrames(for region: MKCoordinateRegion) async throws -> [RadarFrame]
    /// XYZ tile URL for a frame, when the provider is tiled. NOAA's time-enabled
    /// radar ImageServer is not XYZ-tiled, so it returns nil and callers use the
    /// export-image path instead.
    func overlayTileURL(z: Int, x: Int, y: Int, frame: RadarFrame) async throws -> URL?
    /// Rendered PNG for a bounding box (fetches bytes).
    func exportImage(for bbox: RadarBoundingBox, size: CGSize, frame: RadarFrame) async throws -> Data?
    /// A synchronous URL for the rendered image, for SwiftUI `AsyncImage`. Declared
    /// as a requirement (with a default of nil below) so it dispatches dynamically
    /// through the provider existential.
    func exportImageURL(for bbox: RadarBoundingBox, size: CGSize, frame: RadarFrame?) -> URL?
}

extension RadarPrecipitationProvider {
    /// Default: no synchronous URL (conformers that only return bytes need not
    /// implement it). NOAA overrides this.
    func exportImageURL(for bbox: RadarBoundingBox, size: CGSize, frame: RadarFrame?) -> URL? {
        nil
    }
}

// MARK: - NOAA / NWS

/// NOAA/NWS radar base reflectivity (MRMS) overlay provider. Uses the public,
/// keyless NWS ArcGIS radar ImageServer. Coverage is limited to the regions NOAA
/// radar serves (contiguous U.S. + Alaska/Hawaii/Puerto Rico approximations);
/// outside those the overlay reports unavailable rather than substituting
/// forecast or satellite precipitation.
struct NOAARadarPrecipitationProvider: RadarPrecipitationProvider {

    /// Public NWS radar base-reflectivity ImageServer (no API key). Configurable
    /// only to allow a mirror; not a hook for a different/paid provider.
    var baseURL: String

    init(baseURL: String = "https://mapservices.weather.noaa.gov/eventdriven/rest/services/radar/radar_base_reflectivity_time/ImageServer") {
        self.baseURL = baseURL
    }

    let id = "noaa-nws-radar"
    let displayName = "NOAA/NWS radar precipitation"
    let coverageDescription = "Available in NOAA-covered radar regions"
    let attributionText: String? = "Radar precipitation data: NOAA/NWS"
    let supportsTrueRadar = true

    /// Approximate NOAA radar coverage boxes. These bound where the overlay is
    /// offered; they are deliberately conservative and clearly labeled as
    /// NOAA-covered-regions only — the app never implies global radar coverage.
    static let coverageBoxes: [RadarBoundingBox] = [
        // Contiguous U.S. (+ coastal margins / near-border).
        RadarBoundingBox(minLatitude: 20, minLongitude: -130, maxLatitude: 52, maxLongitude: -60),
        // Alaska.
        RadarBoundingBox(minLatitude: 50, minLongitude: -180, maxLatitude: 72, maxLongitude: -129),
        // Hawaii.
        RadarBoundingBox(minLatitude: 18, minLongitude: -161, maxLatitude: 23, maxLongitude: -154),
        // Puerto Rico.
        RadarBoundingBox(minLatitude: 16, minLongitude: -68, maxLatitude: 20, maxLongitude: -64)
    ]

    /// Whether any NOAA coverage box overlaps the region.
    func isAvailable(for region: MKCoordinateRegion) async -> Bool {
        Self.covers(region: region)
    }

    static func covers(region: MKCoordinateRegion) -> Bool {
        let box = RadarBoundingBox(region: region)
        return coverageBoxes.contains { boxesOverlap($0, box) }
    }

    static func covers(coordinate: CLLocationCoordinate2D) -> Bool {
        coverageBoxes.contains { $0.contains(coordinate) }
    }

    private static func boxesOverlap(_ a: RadarBoundingBox, _ b: RadarBoundingBox) -> Bool {
        a.minLatitude <= b.maxLatitude && a.maxLatitude >= b.minLatitude
            && a.minLongitude <= b.maxLongitude && a.maxLongitude >= b.minLongitude
    }

    func availableFrames(for region: MKCoordinateRegion) async throws -> [RadarFrame] {
        // The time-enabled ImageServer returns the most recent observation when no
        // time is specified, so a single "current" observed frame is offered.
        guard Self.covers(region: region) else { return [] }
        return [RadarFrame(id: "current", timestamp: Date(), isForecast: false, label: "Current")]
    }

    func overlayTileURL(z: Int, x: Int, y: Int, frame: RadarFrame) async throws -> URL? {
        // Not XYZ-tiled; the overlay is rendered via exportImage.
        nil
    }

    func exportImage(for bbox: RadarBoundingBox, size: CGSize, frame: RadarFrame) async throws -> Data? {
        guard let url = exportImageURL(for: bbox, size: size, frame: frame) else { return nil }
        var request = URLRequest(url: url)
        request.timeoutInterval = 12
        request.setValue("IFATCCompanion/1.0", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            return nil
        }
        return data.isEmpty ? nil : data
    }

    func exportImageURL(for bbox: RadarBoundingBox, size: CGSize, frame: RadarFrame?) -> URL? {
        guard size.width > 0, size.height > 0 else { return nil }
        var components = URLComponents(string: "\(baseURL)/exportImage")
        // bbox is xmin,ymin,xmax,ymax in EPSG:4326; request the image in Web
        // Mercator (3857) to align with MapKit's projection.
        let bboxValue = "\(bbox.minLongitude),\(bbox.minLatitude),\(bbox.maxLongitude),\(bbox.maxLatitude)"
        let w = Int(size.width.rounded())
        let h = Int(size.height.rounded())
        components?.queryItems = [
            URLQueryItem(name: "bbox", value: bboxValue),
            URLQueryItem(name: "bboxSR", value: "4326"),
            URLQueryItem(name: "imageSR", value: "3857"),
            URLQueryItem(name: "size", value: "\(w),\(h)"),
            URLQueryItem(name: "format", value: "png"),
            URLQueryItem(name: "transparent", value: "true"),
            URLQueryItem(name: "f", value: "image")
        ]
        return components?.url
    }
}

// MARK: - Mock

/// A deterministic, offline radar stand-in for Mock Mode and tests. It advertises
/// itself as NOT true radar and serves precipitation as vector cells (drawn as
/// polygons on the map), never as an image claiming to be observed radar.
struct MockRadarPrecipitationProvider: RadarPrecipitationProvider {
    let id = "mock-radar"
    let displayName = "Simulated radar (Mock Mode)"
    let coverageDescription = "Simulated coverage for the mock flight"
    let attributionText: String? = "Simulated precipitation — Mock Mode"
    let supportsTrueRadar = false

    /// Mock coverage always applies (the demo route is inside NOAA's region), so
    /// the full deviation flow is demoable offline.
    func isAvailable(for region: MKCoordinateRegion) async -> Bool { true }

    func availableFrames(for region: MKCoordinateRegion) async throws -> [RadarFrame] {
        [RadarFrame(id: "mock-current", timestamp: Date(), isForecast: false, label: "Current (mock)")]
    }

    func overlayTileURL(z: Int, x: Int, y: Int, frame: RadarFrame) async throws -> URL? { nil }

    /// Mock precipitation is drawn from vector cells, so no image is exported.
    func exportImage(for bbox: RadarBoundingBox, size: CGSize, frame: RadarFrame) async throws -> Data? { nil }
}
