import Foundation
import CoreLocation
import CoreGraphics
import MapKit

/// Coordinates a single `RadarPrecipitationProvider` (NOAA in Live mode, Mock in
/// Mock Mode) for the app. Keeps the provider architecture NOAA-only for v1: it
/// selects between the NOAA provider and the offline mock stand-in and never
/// composes a paid/commercial provider.
@MainActor
final class NOAARadarPrecipitationService {

    private(set) var provider: RadarPrecipitationProvider
    private weak var diagnostics: DiagnosticsStore?

    /// Last successful radar coverage/frames check, for the diagnostics panel.
    private(set) var lastRadarUpdate: Date?
    /// Most recent provider error message (nil when healthy).
    private(set) var lastError: String?

    init(provider: RadarPrecipitationProvider = NOAARadarPrecipitationProvider()) {
        self.provider = provider
    }

    func configure(diagnostics: DiagnosticsStore?) {
        self.diagnostics = diagnostics
    }

    /// Swap the active provider. Only the NOAA provider or the mock stand-in are
    /// ever passed here — this is not a hook for additional commercial providers.
    func useProvider(_ provider: RadarPrecipitationProvider) {
        self.provider = provider
    }

    var providerID: String { provider.id }
    var sourceDescription: String { provider.displayName }
    var attributionText: String? { provider.attributionText }
    var coverageDescription: String { provider.coverageDescription }
    var supportsTrueRadar: Bool { provider.supportsTrueRadar }

    // MARK: - Coverage

    /// Whether the provider covers the region enclosing the given route positions.
    /// Returns false (with no error) when there are no usable positions.
    func coverage(positions: [CLLocationCoordinate2D]) async -> Bool {
        let valid = positions.filter { $0.isValid }
        guard let region = Self.region(enclosing: valid) else { return false }
        return await coverage(region: region)
    }

    /// Whether the provider covers a map region.
    func coverage(region: MKCoordinateRegion) async -> Bool {
        let available = await provider.isAvailable(for: region)
        if available {
            lastRadarUpdate = Date()
            lastError = nil
        }
        return available
    }

    /// Observed radar frames for a region (empty outside coverage).
    func frames(region: MKCoordinateRegion) async -> [RadarFrame] {
        do {
            let frames = try await provider.availableFrames(for: region)
            if !frames.isEmpty { lastRadarUpdate = frames.first?.timestamp ?? Date() }
            lastError = nil
            return frames
        } catch {
            let msg = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            lastError = msg
            diagnostics?.log(.weather, "Radar frames fetch failed: \(msg)")
            return []
        }
    }

    // MARK: - Rendering

    /// A synchronous export-image URL for the visible region, for `AsyncImage`.
    /// Nil when the provider renders vector cells instead (Mock Mode) or the
    /// region is degenerate.
    func exportImageURL(region: MKCoordinateRegion, size: CGSize) -> URL? {
        let bbox = RadarBoundingBox(region: region)
        let frame = RadarFrame(id: "current", timestamp: Date(), label: "Current")
        return provider.exportImageURL(for: bbox, size: size, frame: frame)
    }

    // MARK: - Helpers

    /// A padded region that encloses a set of coordinates (nil if empty).
    static func region(enclosing coords: [CLLocationCoordinate2D]) -> MKCoordinateRegion? {
        guard let first = coords.first else { return nil }
        var minLat = first.latitude, maxLat = first.latitude
        var minLon = first.longitude, maxLon = first.longitude
        for c in coords {
            minLat = min(minLat, c.latitude); maxLat = max(maxLat, c.latitude)
            minLon = min(minLon, c.longitude); maxLon = max(maxLon, c.longitude)
        }
        let center = CLLocationCoordinate2D(latitude: (minLat + maxLat) / 2,
                                            longitude: (minLon + maxLon) / 2)
        let span = MKCoordinateSpan(latitudeDelta: max(0.4, (maxLat - minLat) * 1.2),
                                    longitudeDelta: max(0.4, (maxLon - minLon) * 1.2))
        return MKCoordinateRegion(center: center, span: span)
    }
}
