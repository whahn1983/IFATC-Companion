import Foundation
import CoreLocation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

// MARK: - OPERA Lambert Azimuthal Equal Area grid
//
// The EUMETNET OPERA / CIRRUS pan-European composite is gridded in a **Lambert
// Azimuthal Equal Area** projection centered near (55° N, 10° E), covering roughly
// 3,800 × 4,400 km with documented geographic corners:
//   NW (70° N, 30° W), NE (70° N, 50° E), SW (32° N, 15° W), SE (32° N, 30° E).
//
// We derive the projected extent by forward-projecting those four documented
// corners (spherical LAEA, R = 6,378,137 m) and taking their bounding rectangle —
// so the grid mapping is self-consistent with the published corners without
// hard-coding false-easting/northing constants we can't verify. This is a
// simulation overlay: a few-km alignment error from the "approximate" corners is
// acceptable, and far better than treating the composite as an equirectangular
// image (which would badly misplace precipitation at UK latitudes).

/// The OPERA composite's LAEA grid: forward projection plus normalized-coordinate
/// mapping used to resample the composite into a lat/lon or Web-Mercator output.
struct OPERALambertGrid {
    /// Earth radius used for the spherical LAEA projection (WGS84 semi-major axis).
    static let radius = 6_378_137.0
    /// Projection origin.
    static let lat0 = 55.0, lon0 = 10.0

    /// Documented grid corners (lat, lon), degrees.
    static let cornerNW = (lat: 70.0, lon: -30.0)
    static let cornerNE = (lat: 70.0, lon: 50.0)
    static let cornerSW = (lat: 32.0, lon: -15.0)
    static let cornerSE = (lat: 32.0, lon: 30.0)

    let xmin: Double, xmax: Double, ymin: Double, ymax: Double

    init() {
        let nw = Self.project(lat: Self.cornerNW.lat, lon: Self.cornerNW.lon)
        let ne = Self.project(lat: Self.cornerNE.lat, lon: Self.cornerNE.lon)
        let sw = Self.project(lat: Self.cornerSW.lat, lon: Self.cornerSW.lon)
        let se = Self.project(lat: Self.cornerSE.lat, lon: Self.cornerSE.lon)
        xmin = min(nw.x, sw.x)
        xmax = max(ne.x, se.x)
        ymax = max(nw.y, ne.y)
        ymin = min(sw.y, se.y)
    }

    /// Spherical Lambert Azimuthal Equal Area forward projection about
    /// (`lat0`, `lon0`). Returns projected meters (x east, y north).
    static func project(lat: Double, lon: Double) -> (x: Double, y: Double) {
        let d = Double.pi / 180
        let phi = lat * d, lam = lon * d
        let phi0 = lat0 * d, lam0 = lon0 * d
        let dLam = lam - lam0
        let denom = 1 + sin(phi0) * sin(phi) + cos(phi0) * cos(phi) * cos(dLam)
        // Guard the antipode (denom → 0); the OPERA area never approaches it.
        let kPrime = denom > 1e-12 ? sqrt(2 / denom) : 0
        let x = radius * kPrime * cos(phi) * sin(dLam)
        let y = radius * kPrime * (cos(phi0) * sin(phi) - sin(phi0) * cos(phi) * cos(dLam))
        return (x, y)
    }

    /// Normalized source coordinates for a geographic point: `u` in 0…1 west→east,
    /// `v` in 0…1 north→south (image-row order, top = north). Returns nil when the
    /// point lies outside the composite grid.
    func normalized(lat: Double, lon: Double) -> (u: Double, v: Double)? {
        guard xmax > xmin, ymax > ymin else { return nil }
        let p = Self.project(lat: lat, lon: lon)
        let u = (p.x - xmin) / (xmax - xmin)
        let v = (ymax - p.y) / (ymax - ymin)
        guard u >= 0, u <= 1, v >= 0, v <= 1 else { return nil }
        return (u, v)
    }
}

// MARK: - Decoded composite raster

/// A decoded OPERA composite as a grid of classified precipitation intensities at a
/// manageable resolution. Row 0 is the north edge of the grid (image top).
struct OPERARaster: Sendable {
    let width: Int
    let height: Int
    /// Row-major, length `width * height`; nil = no precipitation / no data.
    let intensity: [WeatherIntensity?]

    func at(u: Double, v: Double) -> WeatherIntensity? {
        guard width > 0, height > 0 else { return nil }
        let col = min(width - 1, max(0, Int(u * Double(width))))
        let row = min(height - 1, max(0, Int(v * Double(height))))
        return intensity[row * width + col]
    }
}

// MARK: - Renderer

/// Decodes an OPERA/CIRRUS composite GeoTIFF and resamples it, through the LAEA
/// grid, into either the app's precipitation-cell sampling grid (lat/lon-linear) or
/// a colorized Web-Mercator PNG overlay for the map. Pure resampling/classification
/// is unit-tested; only the ImageIO decode and PNG encode touch platform imaging,
/// and both fail to `nil` so the caller falls back gracefully.
///
/// Classification is intentionally conservative so the overlay never *invents*
/// precipitation from ambiguous data: clearly colored composite pixels are read via
/// the standard reflectivity color ramp (as with the NOAA/NASA image overlays),
/// while near-gray single-band `DBZH` data pixels are mapped through the common ODIM
/// reflectivity scaling (gain 0.5, offset −32 dBZ) with sentinel `0`/`255` treated
/// as "no data". These scaling assumptions are **best-effort and meant to be
/// verified/tuned on device** against real ORD composites.
enum OPERACompositeRenderer {

    /// Cap on the decoded source resolution (longest side, px). Keeps memory bounded
    /// while preserving enough detail for the route-corridor sampling window.
    static let maxSourceDimension = 2200

    // MARK: Classification

    /// Classify one decoded composite pixel into a precipitation intensity.
    static func classify(r: UInt8, g: UInt8, b: UInt8, a: UInt8) -> WeatherIntensity? {
        guard a >= 40 else { return nil }
        let rf = Double(r), gf = Double(g), bf = Double(b)
        let maxc = max(rf, max(gf, bf)), minc = min(rf, min(gf, bf))
        let value = maxc / 255.0
        let sat = maxc <= 0 ? 0 : (maxc - minc) / maxc

        // Clearly colored → standard reflectivity color ramp (shared with the other
        // image overlays), so a colorized composite reads exactly like NOAA/NASA.
        if value >= 0.25, sat >= 0.30 {
            return RadarImageSampler.intensity(r: r, g: g, b: b, a: a)
        }

        // Near-gray → treat as single-band DBZH data via ODIM scaling.
        // DN 0 and 255 are common no-data / undetect sentinels.
        let dn = Int(maxc.rounded())
        guard dn > 0, dn < 255 else { return nil }
        let dbz = 0.5 * Double(dn) - 32.0        // ODIM gain/offset
        switch dbz {
        case ..<30:   return nil        // below moderate rain → ignore (like light)
        case 30..<40: return .moderate
        case 40..<50: return .heavy
        default:      return .extreme
        }
    }

    // MARK: Decode (ImageIO)

    /// Decode composite image bytes (cloud-optimized GeoTIFF, or any ImageIO format)
    /// into a classified `OPERARaster`, downsampled so the longest side is at most
    /// `maxSourceDimension`. Returns nil when the bytes can't be decoded.
    static func decodeRaster(from data: Data) -> OPERARaster? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return nil }
        let srcW = image.width, srcH = image.height
        guard srcW > 0, srcH > 0 else { return nil }

        let scale = min(1.0, Double(maxSourceDimension) / Double(max(srcW, srcH)))
        let w = max(1, Int((Double(srcW) * scale).rounded()))
        let h = max(1, Int((Double(srcH) * scale).rounded()))

        let bytesPerPixel = 4, bytesPerRow = 4 * w
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGImageAlphaInfo.premultipliedLast.rawValue
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8,
                                  bytesPerRow: bytesPerRow, space: colorSpace,
                                  bitmapInfo: bitmapInfo),
              let raw = ctx.data else { return nil }
        ctx.interpolationQuality = .low
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))

        let buffer = raw.bindMemory(to: UInt8.self, capacity: bytesPerRow * h)
        var out = [WeatherIntensity?](repeating: nil, count: w * h)
        // CoreGraphics origin is bottom-left; flip so row 0 is the image top (north).
        for row in 0..<h {
            let bufferRow = h - 1 - row
            for col in 0..<w {
                let i = bufferRow * bytesPerRow + col * bytesPerPixel
                out[row * w + col] = classify(r: buffer[i], g: buffer[i + 1],
                                              b: buffer[i + 2], a: buffer[i + 3])
            }
        }
        return OPERARaster(width: w, height: h, intensity: out)
    }

    // MARK: Resample → sampling grid (lat/lon-linear)

    /// Resample a decoded composite into a `rows × cols` intensity grid over `bbox`,
    /// laid out linearly in lat/lon (row 0 = max latitude), matching what
    /// `RadarImageSampler.cells(from:bbox:)` expects. Pure.
    static func intensityGrid(from raster: OPERARaster, bbox: RadarBoundingBox,
                              columns: Int, rows: Int,
                              grid: OPERALambertGrid = OPERALambertGrid()) -> [[WeatherIntensity?]] {
        var out = [[WeatherIntensity?]](repeating: [WeatherIntensity?](repeating: nil, count: columns),
                                        count: rows)
        guard columns > 0, rows > 0 else { return out }
        let latSpan = bbox.maxLatitude - bbox.minLatitude
        let lonSpan = bbox.maxLongitude - bbox.minLongitude
        for row in 0..<rows {
            let lat = bbox.maxLatitude - (Double(row) + 0.5) / Double(rows) * latSpan
            for col in 0..<columns {
                let lon = bbox.minLongitude + (Double(col) + 0.5) / Double(columns) * lonSpan
                if let n = grid.normalized(lat: lat, lon: lon) {
                    out[row][col] = raster.at(u: n.u, v: n.v)
                }
            }
        }
        return out
    }

    /// Decode composite bytes and resample straight into the sampling grid. Nil on
    /// decode failure so the caller keeps its last good cells.
    static func intensityGrid(fromImageData data: Data, bbox: RadarBoundingBox,
                              columns: Int, rows: Int) -> [[WeatherIntensity?]]? {
        guard let raster = decodeRaster(from: data) else { return nil }
        return intensityGrid(from: raster, bbox: bbox, columns: columns, rows: rows)
    }

    // MARK: Resample → colorized Web-Mercator PNG (map overlay)

    /// A standard reflectivity color (RGBA) for a precipitation intensity, matching
    /// the app's Light/Moderate/Heavy/Extreme legend. Alpha is baked in; the overlay
    /// view applies the user's opacity on top.
    static func color(for intensity: WeatherIntensity) -> (r: UInt8, g: UInt8, b: UInt8, a: UInt8) {
        switch intensity {
        case .light:    return (0, 180, 60, 150)
        case .moderate: return (235, 220, 40, 190)
        case .heavy:    return (245, 140, 20, 205)
        case .extreme:  return (220, 30, 30, 220)
        case .unknown:  return (0, 0, 0, 0)     // no classified return → paint nothing
        }
    }

    /// Inverse Web-Mercator (EPSG:3857 meters) → (lat, lon) degrees.
    static func inverseMercator(x: Double, y: Double) -> (lat: Double, lon: Double) {
        let lon = x * 180.0 / 20037508.342789244
        let lat = (2 * atan(exp(y / 6_378_137.0)) - Double.pi / 2) * 180.0 / Double.pi
        return (lat, lon)
    }

    /// Render a decoded composite as a colorized RGBA PNG laid out in Web Mercator
    /// across `bbox` (matching how MapKit displays the NOAA/NASA WMS overlays), at
    /// `width × height` pixels. Returns nil if the image can't be encoded.
    static func renderMercatorPNG(from raster: OPERARaster, bbox: RadarBoundingBox,
                                  width: Int, height: Int,
                                  grid: OPERALambertGrid = OPERALambertGrid()) -> Data? {
        guard width > 0, height > 0 else { return nil }
        func mx(_ lon: Double) -> Double { lon * 20037508.342789244 / 180 }
        func my(_ lat: Double) -> Double {
            let clamped = min(85.05112878, max(-85.05112878, lat))
            let rad = clamped * .pi / 180
            return log(tan(.pi / 4 + rad / 2)) * 6_378_137.0
        }
        let xMin = mx(bbox.minLongitude), xMax = mx(bbox.maxLongitude)
        let yMin = my(bbox.minLatitude), yMax = my(bbox.maxLatitude)

        let bytesPerRow = 4 * width
        var pixels = [UInt8](repeating: 0, count: bytesPerRow * height)
        for py in 0..<height {
            let ym = yMax - (Double(py) + 0.5) / Double(height) * (yMax - yMin)
            for px in 0..<width {
                let xm = xMin + (Double(px) + 0.5) / Double(width) * (xMax - xMin)
                let geo = inverseMercator(x: xm, y: ym)
                guard let n = grid.normalized(lat: geo.lat, lon: geo.lon),
                      let intensity = raster.at(u: n.u, v: n.v) else { continue }
                let c = color(for: intensity)
                let i = py * bytesPerRow + px * 4
                // Premultiplied-last RGBA.
                let af = Double(c.a) / 255.0
                pixels[i]     = UInt8(Double(c.r) * af)
                pixels[i + 1] = UInt8(Double(c.g) * af)
                pixels[i + 2] = UInt8(Double(c.b) * af)
                pixels[i + 3] = c.a
            }
        }

        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue)
        guard let provider = CGDataProvider(data: Data(pixels) as CFData),
              let cgImage = CGImage(width: width, height: height,
                                    bitsPerComponent: 8, bitsPerPixel: 32,
                                    bytesPerRow: bytesPerRow, space: colorSpace,
                                    bitmapInfo: bitmapInfo, provider: provider,
                                    decode: nil, shouldInterpolate: false,
                                    intent: .defaultIntent) else { return nil }
        let outData = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(outData, UTType.png.identifier as CFString, 1, nil)
        else { return nil }
        CGImageDestinationAddImage(dest, cgImage, nil)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return outData as Data
    }

    /// Decode composite bytes and render the colorized Web-Mercator overlay PNG.
    static func renderMercatorPNG(fromImageData data: Data, bbox: RadarBoundingBox,
                                  width: Int, height: Int) -> Data? {
        guard let raster = decodeRaster(from: data) else { return nil }
        return renderMercatorPNG(from: raster, bbox: bbox, width: width, height: height)
    }
}

// MARK: - Shared composite cache

/// Caches the latest decoded whole-Europe OPERA composite so the many per-bbox
/// renders (overlay display + route-corridor sampling) reuse a single anonymous ORD
/// fetch/decode instead of re-downloading the multi-megabyte GeoTIFF each time.
///
/// A **well-behaved public client** of a shared, low-limit anonymous service:
///  - refreshes on a **5–8 minute jittered interval** (CIRRUS updates every 5 min),
///    de-synchronizing requests across devices;
///  - at each interval it does the **cheap listing first** and **skips the expensive
///    GeoTIFF download when the product timestamp is unchanged**;
///  - the download itself is **conditionally revalidated** (ETag/Last-Modified) by the
///    client's caching session;
///  - on a 429/503/network error it **backs off exponentially** (honoring
///    `Retry-After`) and keeps serving the last good raster;
///  - it never downloads while a fresh raster is cached, and never on a background
///    telemetry tick that arrives inside the interval.
actor OPERACompositeStore {
    static let shared = OPERACompositeStore()

    private var raster: OPERARaster?
    private var product: EUMETNETORDClient.Product?
    private var currentTimestamp: Date?     // product timestamp of the loaded raster
    private var nextRefreshAt: Date?
    private var nextRetryAt: Date?
    private var failureCount = 0

    /// Actual composite bytes downloaded — the latest download and the running total
    /// this app run — so the app can surface real ORD data usage (the composite is the
    /// only megabyte-scale weather source). Counted only on a real new-product download
    /// (the timestamp-skip means unchanged products aren't re-fetched); a rare 304 on
    /// relaunch is served from cache but still counted here, so this slightly
    /// over-reports network bytes rather than under-reporting.
    private(set) var lastDownloadBytes = 0
    private(set) var sessionDownloadBytes = 0

    /// Downloaded-bytes snapshot for diagnostics (`last`, session `total`).
    func dataUsage() -> (last: Int, total: Int) { (lastDownloadBytes, sessionDownloadBytes) }

    /// Minimum refresh interval and jitter (→ 5–8 min); the composite updates every
    /// ~5 min, so checking more often just wastes the shared service's capacity.
    private let baseInterval: TimeInterval = 300
    private let maxJitter: TimeInterval = 180

    /// The current decoded composite for `product`. Fetches anonymously via `client`
    /// only when due (interval elapsed, not backing off) and only downloads when the
    /// product timestamp actually advanced. Returns the last good raster otherwise, or
    /// nil if none has ever been fetched.
    func current(product: EUMETNETORDClient.Product,
                 client: EUMETNETORDClient,
                 now: Date) async -> OPERARaster? {
        if self.product != product { resetState(for: product) }

        // Backing off after failures, or still within the refresh interval → serve
        // what we have without touching the network.
        if let retry = nextRetryAt, now < retry { return raster }
        if let next = nextRefreshAt, now < next, raster != nil { return raster }

        // Due for a check. List (cheap) and compare the latest product timestamp.
        guard let latest = await client.latestComposite(product: product, now: now) else {
            registerFailure(now: now, retryAfter: nil)   // listing failed / no product
            return raster
        }
        if let ts = currentTimestamp, ts == latest.timestamp, raster != nil {
            scheduleNextRefresh(now: now)                // unchanged → skip the download
            return raster
        }

        // New product → download (conditionally revalidated) and decode.
        switch await client.fetchObject(url: latest.url) {
        case .success(let data):
            lastDownloadBytes = data.count
            sessionDownloadBytes += data.count
            if let decoded = OPERACompositeRenderer.decodeRaster(from: data) {
                raster = decoded
                currentTimestamp = latest.timestamp
                scheduleNextRefresh(now: now)
            } else {
                registerFailure(now: now, retryAfter: nil)   // decode failed → keep last good
            }
        case .retry(let after):
            registerFailure(now: now, retryAfter: after)
        case .unavailable:
            scheduleNextRefresh(now: now)                    // object gone → try next interval
        }
        return raster
    }

    private func resetState(for product: EUMETNETORDClient.Product) {
        self.product = product
        raster = nil
        currentTimestamp = nil
        nextRefreshAt = nil
        nextRetryAt = nil
        failureCount = 0
    }

    private func scheduleNextRefresh(now: Date) {
        failureCount = 0
        nextRetryAt = nil
        nextRefreshAt = now.addingTimeInterval(baseInterval + Double.random(in: 0...maxJitter))
    }

    private func registerFailure(now: Date, retryAfter: TimeInterval?) {
        failureCount += 1
        let backoff = AppHTTP.backoffDelay(failureCount: failureCount)
        let delay = max(backoff, retryAfter ?? 0) + Double.random(in: 0...15)   // small jitter
        nextRetryAt = now.addingTimeInterval(delay)
        nextRefreshAt = nextRetryAt
    }
}
