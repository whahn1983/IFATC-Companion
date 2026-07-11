import Foundation
import CoreLocation
import CoreGraphics
import MapKit

// MARK: - EUMETNET OPERA (Europe, true radar)

/// EUMETNET OPERA radar composite provider for Europe. True radar precipitation,
/// sourced from the **EUMETNET Open Radar Data (ORD)** 24-hour cache — the keyless,
/// anonymous public S3 bucket that distributes the pan-European OPERA composite
/// (produced by **CIRRUS**, which replaced ODYSSEY in 2024). Honors **CC BY 4.0**
/// attribution and uses the cloud-optimized GeoTIFF composite (renderable on iOS via
/// ImageIO) rather than ODIM HDF5. Coverage is best-effort over Europe — the app
/// does **not** assume every country has usable composite coverage, and fails
/// gracefully (falling through to the NASA satellite estimate) where the render
/// can't be produced.
///
/// Rendering: there is no public keyless *rendered* WMS/WMTS for the composite, so
/// the provider fetches the latest ORD composite GeoTIFF anonymously
/// (`EUMETNETORDClient`, `--no-sign-request` equivalent) and reprojects/colorizes it
/// itself (`OPERACompositeRenderer`) into the same PNG form the NOAA/NASA overlays
/// use. A configured `wmsBaseURL` still overrides this with a WMS GetMap when a
/// compatible ORD/WMS service is available. `exportImageURL` (the synchronous
/// `AsyncImage` path) only returns a URL for the WMS case; the ORD render is
/// asynchronous, so the overlay display is served from `PrecipitationOverlayService`'s
/// render cache. **The ORD decode/colorize scaling is best-effort and intended to be
/// verified/tuned on device against real composites.**
struct EUMETNETOPERARadarProvider: RadarPrecipitationProvider {

    /// OPERA composite products, in preference order.
    enum Product: String, CaseIterable {
        case maximumReflectivity        // preferred
        case instantaneousRainRate
        case oneHourAccumulation

        /// Candidate WMS layer name for the product (endpoint-specific; override as
        /// needed for the configured ORD/WMS service).
        var wmsLayerName: String {
            switch self {
            case .maximumReflectivity: return "opera_maximum_reflectivity"
            case .instantaneousRainRate: return "opera_instantaneous_rain_rate"
            case .oneHourAccumulation: return "opera_1h_accumulation"
            }
        }

        /// The ORD composite product code (`DBZH` / `RATE` / `ACRR`).
        var ordProduct: EUMETNETORDClient.Product {
            switch self {
            case .maximumReflectivity: return .maximumReflectivity
            case .instantaneousRainRate: return .instantaneousRainRate
            case .oneHourAccumulation: return .oneHourAccumulation
            }
        }
    }

    /// Preferred product order: max reflectivity → instantaneous rain rate → 1-hour
    /// accumulation.
    static let preferredProducts: [Product] = [.maximumReflectivity, .instantaneousRainRate, .oneHourAccumulation]

    /// Preferred raster format for rendering (cloud-optimized GeoTIFF over HDF5).
    static let preferredFormats: [String] = ["cog-geotiff", "geotiff", "odim-hdf5"]

    /// Optional WMS GetMap endpoint for a compatible OPERA/ORD composite service.
    /// When set it overrides the anonymous ORD GeoTIFF render path.
    var wmsBaseURL: String
    /// The product to request (defaults to the top preference).
    var product: Product
    /// Whether to render from the anonymous ORD composite GeoTIFF when no WMS
    /// endpoint is configured. Default on — this is the live European radar source.
    var useORD: Bool
    /// The anonymous ORD client (keyless public S3).
    var ordClient: EUMETNETORDClient

    init(wmsBaseURL: String = "", product: Product = .maximumReflectivity,
         useORD: Bool = true, ordClient: EUMETNETORDClient = EUMETNETORDClient()) {
        self.wmsBaseURL = wmsBaseURL
        self.product = product
        self.useORD = useORD
        self.ordClient = ordClient
    }

    let id = "eumetnet-opera-radar"
    let displayName = "EUMETNET OPERA radar precipitation"
    let coverageDescription = "Available where EUMETNET OPERA radar data is provided (Europe)"
    let attributionText: String? = "Radar precipitation data: EUMETNET OPERA / CIRRUS composite (CC BY 4.0)"
    let supportsTrueRadar = true
    let layerType: PrecipitationLayerType = .radar
    let confidence: HazardConfidence = .high

    /// Best-effort OPERA coverage box over Europe. Deliberately conservative; not
    /// every country inside the box necessarily has usable ORD coverage — rendering
    /// fails gracefully where it does not.
    static let coverageBox = RadarBoundingBox(minLatitude: 34, minLongitude: -32,
                                              maxLatitude: 72, maxLongitude: 45)

    func covers(region: MKCoordinateRegion) -> Bool {
        Self.coverageBox.overlaps(RadarBoundingBox(region: region))
    }

    static func covers(coordinate: CLLocationCoordinate2D) -> Bool {
        coverageBox.contains(coordinate)
    }

    /// OPERA can render where it covers the region **and** it has a working source —
    /// the anonymous ORD composite (default) or a configured WMS endpoint. With
    /// neither, it can't produce imagery, so it must not win selection (the service
    /// then falls through to the NASA satellite estimate) rather than claiming
    /// coverage it can't draw.
    func canRenderOverlay(for region: MKCoordinateRegion) -> Bool {
        guard covers(region: region) else { return false }
        return useORD || !wmsBaseURL.trimmingCharacters(in: .whitespaces).isEmpty
    }

    func availableFrames(for region: MKCoordinateRegion) async throws -> [RadarFrame] {
        guard covers(region: region) else { return [] }
        return [RadarFrame(id: "opera-current", timestamp: Date(), isForecast: false, label: "Current (OPERA)")]
    }

    func overlayTileURL(z: Int, x: Int, y: Int, frame: RadarFrame) async throws -> URL? { nil }

    func exportImage(for bbox: RadarBoundingBox, size: CGSize, frame: RadarFrame) async throws -> Data? {
        // Prefer a configured WMS GetMap (rendered server-side) when present.
        if let url = exportImageURL(for: bbox, size: size, frame: frame) {
            var request = URLRequest(url: url)
            request.cachePolicy = .reloadRevalidatingCacheData
            request.timeoutInterval = 12
            request.setValue(AppHTTP.userAgent, forHTTPHeaderField: "User-Agent")
            let (data, response) = try await AppHTTP.imageSession.data(for: request)
            if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) { return nil }
            return data.isEmpty ? nil : data
        }
        // Otherwise render the anonymous ORD composite GeoTIFF ourselves.
        guard useORD else { return nil }
        return await renderORDComposite(for: bbox, size: size)
    }

    /// Fetch the latest anonymous ORD composite GeoTIFF and reproject/colorize it into
    /// a Web-Mercator PNG for `bbox` (the same layout the NOAA/NASA overlays use, so
    /// the existing sampler and overlay renderer consume it unchanged). Nil on any
    /// listing/fetch/decode failure so the caller degrades gracefully.
    func renderORDComposite(for bbox: RadarBoundingBox, size: CGSize) async -> Data? {
        guard size.width > 0, size.height > 0,
              let raster = await OPERACompositeStore.shared.current(
                product: product.ordProduct, client: ordClient, now: Date()) else { return nil }
        return OPERACompositeRenderer.renderMercatorPNG(
            from: raster, bbox: bbox,
            width: Int(size.width.rounded()), height: Int(size.height.rounded()))
    }

    /// Build a WMS 1.1.1 GetMap for the configured OPERA/ORD service (EPSG:3857).
    /// Returns nil when no WMS endpoint is configured — the overlay is then produced
    /// asynchronously from the anonymous ORD composite GeoTIFF (see `exportImage` /
    /// `renderORDComposite`) and served from the overlay render cache, rather than
    /// displaying satellite/forecast data as radar.
    func exportImageURL(for bbox: RadarBoundingBox, size: CGSize, frame: RadarFrame?) -> URL? {
        let base = wmsBaseURL.trimmingCharacters(in: .whitespaces)
        guard !base.isEmpty, size.width > 0, size.height > 0 else { return nil }
        var components = URLComponents(string: base)
        var items = components?.queryItems ?? []
        items.append(contentsOf: [
            URLQueryItem(name: "SERVICE", value: "WMS"),
            URLQueryItem(name: "VERSION", value: "1.1.1"),
            URLQueryItem(name: "REQUEST", value: "GetMap"),
            URLQueryItem(name: "LAYERS", value: product.wmsLayerName),
            URLQueryItem(name: "STYLES", value: ""),
            URLQueryItem(name: "SRS", value: "EPSG:3857"),
            URLQueryItem(name: "BBOX", value: bbox.mercatorBBoxString),
            URLQueryItem(name: "WIDTH", value: "\(Int(size.width.rounded()))"),
            URLQueryItem(name: "HEIGHT", value: "\(Int(size.height.rounded()))"),
            URLQueryItem(name: "FORMAT", value: "image/png"),
            URLQueryItem(name: "TRANSPARENT", value: "TRUE")
        ])
        components?.queryItems = items
        return components?.url
    }
}

// MARK: - NASA GPM IMERG / GIBS (global satellite estimate)

/// NASA global satellite precipitation estimate via NASA Global Imagery Browse
/// Services (GIBS), part of NASA Earth Science Data and Information System, using
/// GPM IMERG where applicable. This is a **satellite precipitation estimate — not
/// radar** — and is always labeled as such and treated as lower confidence than
/// NOAA/OPERA radar. Used as the global fallback outside NOAA and OPERA coverage.
struct NASAGIBSPrecipitationProvider: RadarPrecipitationProvider {

    /// GIBS WMS endpoint (keyless). EPSG:3857 to align with MapKit.
    var wmsBaseURL: String
    /// GIBS layer identifier for the IMERG precipitation rate.
    var layerIdentifier: String

    init(wmsBaseURL: String = "https://gibs.earthdata.nasa.gov/wms/epsg3857/best/wms.cgi",
         layerIdentifier: String = "IMERG_Precipitation_Rate") {
        self.wmsBaseURL = wmsBaseURL
        self.layerIdentifier = layerIdentifier
    }

    let id = "nasa-gibs-imerg"
    let displayName = "NASA global satellite precipitation estimate"
    let coverageDescription = "Global satellite precipitation estimate (approx. ±60° latitude)"
    let attributionText: String? = "Imagery/data provided by NASA Global Imagery Browse Services (GIBS), part of NASA Earth Science Data and Information System, and NASA GPM IMERG where applicable."
    let supportsTrueRadar = false
    let layerType: PrecipitationLayerType = .satelliteEstimate
    let confidence: HazardConfidence = .low

    /// IMERG is near-global but not polar; coverage is ~60°S–60°N.
    static let coverageBox = RadarBoundingBox(minLatitude: -60, minLongitude: -180,
                                              maxLatitude: 60, maxLongitude: 180)

    func covers(region: MKCoordinateRegion) -> Bool {
        Self.coverageBox.overlaps(RadarBoundingBox(region: region))
    }

    func availableFrames(for region: MKCoordinateRegion) async throws -> [RadarFrame] {
        guard covers(region: region) else { return [] }
        return [RadarFrame(id: "imerg-current", timestamp: Date(), isForecast: false,
                           label: "Latest satellite estimate")]
    }

    func overlayTileURL(z: Int, x: Int, y: Int, frame: RadarFrame) async throws -> URL? { nil }

    func exportImage(for bbox: RadarBoundingBox, size: CGSize, frame: RadarFrame) async throws -> Data? {
        guard let url = exportImageURL(for: bbox, size: size, frame: frame) else { return nil }
        var request = URLRequest(url: url)
        request.cachePolicy = .reloadRevalidatingCacheData
        request.timeoutInterval = 12
        request.setValue(AppHTTP.userAgent, forHTTPHeaderField: "User-Agent")
        let (data, response) = try await AppHTTP.imageSession.data(for: request)
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) { return nil }
        return data.isEmpty ? nil : data
    }

    /// GIBS WMS 1.1.1 GetMap in EPSG:3857. TIME is omitted so GIBS serves the
    /// layer's default (latest available) estimate.
    func exportImageURL(for bbox: RadarBoundingBox, size: CGSize, frame: RadarFrame?) -> URL? {
        guard size.width > 0, size.height > 0 else { return nil }
        var components = URLComponents(string: wmsBaseURL)
        components?.queryItems = [
            URLQueryItem(name: "SERVICE", value: "WMS"),
            URLQueryItem(name: "VERSION", value: "1.1.1"),
            URLQueryItem(name: "REQUEST", value: "GetMap"),
            URLQueryItem(name: "LAYERS", value: layerIdentifier),
            URLQueryItem(name: "STYLES", value: ""),
            URLQueryItem(name: "SRS", value: "EPSG:3857"),
            URLQueryItem(name: "BBOX", value: bbox.mercatorBBoxString),
            URLQueryItem(name: "WIDTH", value: "\(Int(size.width.rounded()))"),
            URLQueryItem(name: "HEIGHT", value: "\(Int(size.height.rounded()))"),
            URLQueryItem(name: "FORMAT", value: "image/png"),
            URLQueryItem(name: "TRANSPARENT", value: "TRUE")
        ]
        return components?.url
    }
}
