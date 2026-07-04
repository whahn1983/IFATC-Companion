import Foundation
import CoreLocation
import CoreGraphics
import MapKit

/// Selects the active precipitation overlay provider for a region and builds its
/// image URL. Provider preference order is **NOAA → EUMETNET OPERA → NASA GIBS**:
///
/// 1. Inside NOAA radar coverage → NOAA/NWS radar precipitation.
/// 2. Else inside EUMETNET OPERA (Europe) coverage → OPERA radar precipitation.
/// 3. Else → NASA global satellite precipitation *estimate* (never called radar).
/// 4. If none covers the region → no overlay ("Precipitation overlay unavailable
///    for this region.").
///
/// In Mock Mode the offline mock provider stands in. Only these providers ship —
/// no paid or unclear-commercial-use providers.
@MainActor
final class PrecipitationOverlayService {

    /// Real providers in selection order.
    private var providers: [RadarPrecipitationProvider]
    /// Offline stand-in for Mock Mode / tests.
    private let mockProvider: RadarPrecipitationProvider
    private var useMock = false
    private weak var diagnostics: DiagnosticsStore?

    private(set) var lastUpdate: Date?
    private(set) var lastError: String?

    init(providers: [RadarPrecipitationProvider] = [
            NOAARadarPrecipitationProvider(),
            EUMETNETOPERARadarProvider(),
            NASAGIBSPrecipitationProvider()
         ],
         mock: RadarPrecipitationProvider = MockRadarPrecipitationProvider()) {
        self.providers = providers
        self.mockProvider = mock
    }

    func configure(diagnostics: DiagnosticsStore?) {
        self.diagnostics = diagnostics
    }

    /// Use the mock provider (Mock Mode) instead of the live selection.
    func useMockProvider(_ on: Bool) {
        useMock = on
    }

    // MARK: - Selection

    /// The first provider (NOAA → OPERA → NASA) that covers the region, or nil.
    func selectedProvider(for region: MKCoordinateRegion) -> RadarPrecipitationProvider? {
        if useMock { return mockProvider }
        return providers.first { $0.covers(region: region) }
    }

    /// The provider selected for the region enclosing the given route positions.
    func selectedProvider(for positions: [CLLocationCoordinate2D]) -> RadarPrecipitationProvider? {
        if useMock { return mockProvider }
        guard let region = Self.region(enclosing: positions.filter { $0.isValid }) else { return nil }
        return selectedProvider(for: region)
    }

    // MARK: - Rendering

    /// The overlay image URL for the map's visible region, from the selected
    /// provider. Nil when nothing covers the region, the provider renders vector
    /// cells (mock), or the provider can't render an image (graceful).
    func imageURL(for region: MKCoordinateRegion, size: CGSize) -> URL? {
        guard let provider = selectedProvider(for: region) else { return nil }
        let frame = RadarFrame(id: "current", timestamp: Date(), label: "Current")
        let url = provider.exportImageURL(for: RadarBoundingBox(region: region), size: size, frame: frame)
        if url != nil { lastUpdate = Date() }
        return url
    }

    // MARK: - Helpers

    /// A padded region enclosing a set of coordinates (nil if empty).
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
