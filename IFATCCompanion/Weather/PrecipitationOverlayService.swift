import Foundation
import CoreLocation
import CoreGraphics
import MapKit

/// Selects the active precipitation overlay provider for a region and builds its
/// image URL. Provider preference order is **NOAA → EUMETNET OPERA → NASA GIBS**:
///
/// 1. Inside NOAA radar coverage → NOAA/NWS radar precipitation.
/// 2. Else inside EUMETNET OPERA (Europe) coverage → OPERA radar precipitation
///    **when it can render**. OPERA's ORD render is **currently disabled** (see the
///    provider construction below), so today Europe falls through to case 3.
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

    /// Rendered-overlay cache for providers (e.g. ORD OPERA) whose imagery is
    /// produced asynchronously and can't be served as a single `AsyncImage` URL.
    /// Keyed by a coarse region+size signature → a local PNG file URL.
    private var overlayCache: [String: URL] = [:]
    /// Region keys with an in-flight render, so we don't stack duplicate fetches.
    private var overlayRendersInFlight: Set<String> = []
    private var overlayRenderSeq = 0
    /// Called on the main actor when an async overlay render completes, so the map
    /// can re-request `imageURL` and pick up the freshly cached file.
    var onOverlayUpdated: (() -> Void)?

    /// Consecutive async-render failures per provider id, and a cooldown after too
    /// many, so a provider whose live source is persistently unreachable stops
    /// winning selection and falls through to the next (e.g. NASA) instead of leaving
    /// the map blank while claiming coverage — self-recovering once the cooldown ends.
    private var renderFailureStreak: [String: Int] = [:]
    private var renderCooldownUntil: [String: Date] = [:]
    private let renderFailureThreshold = 3
    private let renderCooldown: TimeInterval = 120

    init(providers: [RadarPrecipitationProvider] = [
            NOAARadarPrecipitationProvider(),
            // OPERA ORD rendering is disabled in shipping builds. Decoding the raw
            // scientific DBZH GeoTIFF with ImageIO produces a garbled field — false
            // clutter speckle over clear ocean AND little/no signal where real
            // precipitation is heavy — because ImageIO can't faithfully read/scale the
            // single-band sample values. There is no keyless, rendered, cleanly
            // licensed pan-European radar source to swap in: LibreWXR is close
            // (keyless, RainViewer-compatible tiles, includes OPERA) but its European
            // composite carries a CC-BY-SA **share-alike** obligation via DPC Italy and
            // offers no production reliability. Until a validated source exists, OPERA
            // still *covers* Europe but *cannot render*, so selection falls through to
            // the NASA satellite estimate (clearly labeled, not called radar). The
            // provider and its whole ORD/renderer/store stack stay in place — flip
            // `useORD: true` (or configure a WMS endpoint) to re-enable.
            EUMETNETOPERARadarProvider(useORD: false),
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

    /// The first provider (NOAA → OPERA → NASA) that both **covers** the region and
    /// can actually **render** an overlay there, or nil. The stricter render check is
    /// what keeps a provider that geographically covers Europe but has no working data
    /// source (e.g. an OPERA provider with no configured/reachable endpoint) from
    /// winning selection and blanking the map while falsely reporting coverage —
    /// selection falls through to the next provider that can render (e.g. the NASA
    /// satellite estimate) instead.
    func selectedProvider(for region: MKCoordinateRegion) -> RadarPrecipitationProvider? {
        if useMock { return mockProvider }
        return providers.first {
            $0.covers(region: region) && $0.canRenderOverlay(for: region) && !inRenderCooldown($0.id)
        }
    }

    /// Whether a provider is currently cooling down after repeated render failures.
    private func inRenderCooldown(_ id: String) -> Bool {
        guard let until = renderCooldownUntil[id] else { return false }
        if until > Date() { return true }
        renderCooldownUntil[id] = nil   // expired → allow a retry
        return false
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
    ///
    /// Providers that render server-side (NOAA/NASA WMS, or a configured OPERA WMS)
    /// return a direct `AsyncImage` URL. The ORD OPERA provider renders its composite
    /// asynchronously, so this serves the last cached render for the region and kicks
    /// a background refresh; the map re-requests once `onOverlayUpdated` fires.
    func imageURL(for region: MKCoordinateRegion, size: CGSize) -> URL? {
        guard let provider = selectedProvider(for: region) else { return nil }
        let bbox = RadarBoundingBox(region: region)
        let frame = RadarFrame(id: "current", timestamp: Date(), label: "Current")
        if let url = provider.exportImageURL(for: bbox, size: size, frame: frame) {
            lastUpdate = Date()
            return url
        }
        // No direct URL: asynchronously-rendered provider (ORD OPERA). Serve cache +
        // refresh. Only true-radar providers reach here (satellite estimate/mock use
        // a direct URL or vector cells).
        guard provider.supportsTrueRadar else { return nil }
        let key = Self.overlayKey(region: region, size: size)
        refreshOverlayRender(provider: provider, bbox: bbox, size: size, key: key, frame: frame)
        return overlayCache[key]
    }

    /// Kick a single background render for `key` (deduped) that fetches + renders the
    /// provider's image, writes it to a temp PNG, caches the file URL, and notifies.
    private func refreshOverlayRender(provider: RadarPrecipitationProvider,
                                      bbox: RadarBoundingBox, size: CGSize,
                                      key: String, frame: RadarFrame) {
        guard !overlayRendersInFlight.contains(key) else { return }
        overlayRendersInFlight.insert(key)
        Task { @MainActor in
            defer { overlayRendersInFlight.remove(key) }
            let data = try? await provider.exportImage(for: bbox, size: size, frame: frame)
            guard let data = data ?? nil, let url = writeOverlayPNG(data, key: key) else {
                noteRenderFailure(provider.id)
                return
            }
            overlayCache[key] = url
            lastUpdate = Date()
            lastError = nil
            renderFailureStreak[provider.id] = 0
            renderCooldownUntil[provider.id] = nil
            onOverlayUpdated?()
        }
    }

    /// Record an async-render failure; after `renderFailureThreshold` in a row put the
    /// provider in a cooldown so selection falls through, and notify so the map
    /// re-requests (and picks up the fallback provider).
    private func noteRenderFailure(_ id: String) {
        lastError = "OPERA composite unavailable"
        let streak = (renderFailureStreak[id] ?? 0) + 1
        renderFailureStreak[id] = streak
        if streak >= renderFailureThreshold {
            renderCooldownUntil[id] = Date().addingTimeInterval(renderCooldown)
            renderFailureStreak[id] = 0
            onOverlayUpdated?()
        }
    }

    /// A coarse region+size cache key (quantized so small pans reuse a render).
    static func overlayKey(region: MKCoordinateRegion, size: CGSize) -> String {
        func q(_ v: Double, _ p: Double) -> Int { Int((v / p).rounded()) }
        return [q(region.center.latitude, 0.25), q(region.center.longitude, 0.25),
                q(region.span.latitudeDelta, 0.25), q(region.span.longitudeDelta, 0.25),
                Int(size.width.rounded()), Int(size.height.rounded())]
            .map(String.init).joined(separator: "_")
    }

    /// Write rendered PNG bytes to a fresh temp file for `key` (replacing the prior
    /// one so `AsyncImage` doesn't serve a stale cached URL). Returns the file URL.
    private func writeOverlayPNG(_ data: Data, key: String) -> URL? {
        if let old = overlayCache[key] { try? FileManager.default.removeItem(at: old) }
        overlayRenderSeq += 1
        let name = "opera-overlay-\(key)-\(overlayRenderSeq).png"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        do { try data.write(to: url); return url } catch { return nil }
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
