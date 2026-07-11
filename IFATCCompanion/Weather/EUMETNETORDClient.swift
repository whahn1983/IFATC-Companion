import Foundation

/// Anonymous client for the **EUMETNET Open Radar Data (ORD)** 24-hour cache — the
/// keyless, public S3 bucket that distributes the pan-European OPERA radar
/// composite (produced by **CIRRUS**, which replaced ODYSSEY in 2024). This is the
/// live data source behind the app's EUMETNET OPERA radar overlay.
///
/// **Access is anonymous.** The bucket is public and requires no credentials,
/// account, or API key — the AWS CLI equivalent is `--no-sign-request`. Over plain
/// HTTPS that simply means we issue unsigned `GET` requests (no `Authorization`
/// header, no query signing) against the CloudFerro path-style S3 endpoint.
///
/// The composite products are licensed **CC BY 4.0** ("Radar precipitation data:
/// EUMETNET OPERA (CC BY 4.0), CIRRUS composite"). Only the confirmed CC BY 4.0
/// composite products are requested: maximum reflectivity (`DBZH`), instantaneous
/// rain rate (`RATE`), and 1-hour accumulation (`ACRR`).
///
/// Object layout (observed from the ORD documentation):
///   `s3://openradar-24h/YYYY/MM/DD/OPERA/COMP/OPERA@<yyyyMMdd'T'HHmm>@0@<PROD>.<ext>`
/// e.g. `OPERA@20260604T0220@0@DBZH.h5` (ODIM HDF5) and the cloud-optimized GeoTIFF
/// sibling `OPERA@20260604T0220@0@DBZH.tif`. The app prefers the GeoTIFF (renderable
/// on iOS via ImageIO) over ODIM HDF5.
///
/// The pure pieces (URL building, key/timestamp parsing, latest-object selection)
/// are unit-tested; only the two `URLSession` fetches touch the network, and every
/// failure is surfaced as `nil` so the caller falls back gracefully.
struct EUMETNETORDClient: Sendable {

    /// The CIRRUS/OPERA composite products, keyed by their ORD product code.
    enum Product: String, Sendable {
        case maximumReflectivity = "DBZH"
        case instantaneousRainRate = "RATE"
        case oneHourAccumulation = "ACRR"
    }

    /// CloudFerro path-style S3 endpoint host (scheme + host, no trailing slash).
    let endpoint: String
    /// The public 24-hour-cache bucket name.
    let bucket: String

    init(endpoint: String = "https://s3.waw3-1.cloudferro.com", bucket: String = "openradar-24h") {
        self.endpoint = endpoint.hasSuffix("/") ? String(endpoint.dropLast()) : endpoint
        self.bucket = bucket
    }

    // MARK: - Pure URL / key helpers (unit-tested)

    /// Cloud-optimized GeoTIFF extensions, most-preferred first.
    static let geotiffExtensions = ["tif", "tiff"]

    /// The `YYYY/MM/DD/OPERA/COMP/` object-key prefix for a UTC day.
    static func compositePrefix(for date: Date) -> String {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        let c = cal.dateComponents([.year, .month, .day], from: date)
        let y = c.year ?? 1970, m = c.month ?? 1, d = c.day ?? 1
        return String(format: "%04d/%02d/%02d/OPERA/COMP/", y, m, d)
    }

    /// An anonymous S3 ListObjectsV2 URL for the given key prefix (path-style,
    /// keyless — no signing).
    func listURL(prefix: String, maxKeys: Int = 1000) -> URL? {
        var comps = URLComponents(string: "\(endpoint)/\(bucket)/")
        comps?.queryItems = [
            URLQueryItem(name: "list-type", value: "2"),
            URLQueryItem(name: "prefix", value: prefix),
            URLQueryItem(name: "max-keys", value: "\(maxKeys)")
        ]
        return comps?.url
    }

    /// The anonymous object URL for a bucket key (path-style, keyless).
    func objectURL(key: String) -> URL? {
        let escaped = key.split(separator: "/").map {
            $0.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? String($0)
        }.joined(separator: "/")
        return URL(string: "\(endpoint)/\(bucket)/\(escaped)")
    }

    /// Extract `<Key>…</Key>` object keys from an S3 ListObjectsV2 XML response.
    /// A deliberately small, dependency-free scan (the response is machine-generated
    /// and flat), so it stays pure and testable without an `XMLParser` delegate.
    static func parseKeys(fromListXML xml: String) -> [String] {
        var keys: [String] = []
        var search = xml.startIndex
        let open = "<Key>", close = "</Key>"
        while let o = xml.range(of: open, range: search..<xml.endIndex),
              let c = xml.range(of: close, range: o.upperBound..<xml.endIndex) {
            let raw = String(xml[o.upperBound..<c.lowerBound])
            keys.append(Self.xmlUnescape(raw))
            search = c.upperBound
        }
        return keys
    }

    /// Minimal XML entity unescape for object keys (they can contain `&`).
    static func xmlUnescape(_ s: String) -> String {
        s.replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&apos;", with: "'")
    }

    /// Parse the composite timestamp from an object key or filename of the form
    /// `…/OPERA@20260604T0220@0@DBZH.tif` → the UTC `Date` of `20260604T0220`.
    /// Returns nil when the key isn't a recognizable OPERA composite name.
    static func compositeTimestamp(fromKey key: String) -> Date? {
        guard let file = key.split(separator: "/").last.map(String.init) else { return nil }
        // Token between the first two '@' is the timestamp: OPERA@<ts>@0@PROD.ext
        let parts = file.split(separator: "@", omittingEmptySubsequences: false)
        guard parts.count >= 2 else { return nil }
        let stamp = String(parts[1])   // e.g. 20260604T0220
        let fmt = DateFormatter()
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.timeZone = TimeZone(identifier: "UTC")
        fmt.dateFormat = "yyyyMMdd'T'HHmm"
        return fmt.date(from: stamp)
    }

    /// Whether a key names a cloud-optimized GeoTIFF composite for `product`
    /// (`…@<PROD>.tif`/`.tiff`, case-insensitive on the extension).
    static func isGeoTIFFComposite(_ key: String, product: Product) -> Bool {
        guard let file = key.split(separator: "/").last.map(String.init) else { return false }
        let lower = file.lowercased()
        guard geotiffExtensions.contains(where: { lower.hasSuffix(".\($0)") }) else { return false }
        // Product code appears as the last '@'-delimited token before the extension.
        return lower.contains("@\(product.rawValue.lowercased()).")
    }

    /// The most recent GeoTIFF composite key for `product` among `keys` (by embedded
    /// timestamp), or nil when none match.
    static func latestGeoTIFFKey(from keys: [String], product: Product) -> String? {
        keys.filter { isGeoTIFFComposite($0, product: product) }
            .compactMap { key -> (String, Date)? in
                guard let ts = compositeTimestamp(fromKey: key) else { return nil }
                return (key, ts)
            }
            .max { $0.1 < $1.1 }?
            .0
    }

    // MARK: - Network (thin, defensive; well-behaved public client)

    /// Shared revalidating-cache session (honors ETag/Last-Modified/Cache-Control) with
    /// the app's descriptive User-Agent. The ORD bucket is a **shared public resource**
    /// with low anonymous limits, so requests are minimized and cache-revalidated.
    /// Bounded (LRU) cache — hard-capped at `diskMB`, so cached composites never grow
    /// past it and old timestamped products are evicted as new ones arrive.
    static let cachingSession = AppHTTP.makeCachingSession(cacheName: "ord-http-cache",
                                                           memoryMB: 8, diskMB: 64)

    /// Outcome of an object fetch, distinguishing a retryable throttle/transient error
    /// (429/503/5xx/network — back off) from a non-retryable "gone" (keep last good).
    enum ObjectOutcome: Sendable {
        case success(Data)
        case retry(after: TimeInterval?)
        case unavailable
    }

    /// The latest cloud-optimized GeoTIFF composite (object URL + its product
    /// timestamp) for `product`, scanning today's and yesterday's UTC prefixes (the
    /// 24-hour cache straddles the day boundary). Returns the timestamp too so the
    /// caller can **skip the expensive download when the product hasn't changed**. Nil
    /// on a listing failure / no match, so the caller keeps its last-good data.
    func latestComposite(product: Product, now: Date,
                         session: URLSession = EUMETNETORDClient.cachingSession) async -> (url: URL, timestamp: Date)? {
        let day: TimeInterval = 86_400
        let prefixes = [Self.compositePrefix(for: now),
                        Self.compositePrefix(for: now.addingTimeInterval(-day))]
        var keys: [String] = []
        for prefix in prefixes {
            guard let url = listURL(prefix: prefix),
                  let xml = await fetchText(url: url, session: session) else { continue }
            keys.append(contentsOf: Self.parseKeys(fromListXML: xml))
        }
        guard let key = Self.latestGeoTIFFKey(from: keys, product: product),
              let ts = Self.compositeTimestamp(fromKey: key),
              let url = objectURL(key: key) else { return nil }
        return (url, ts)
    }

    /// GET the composite bytes with **conditional revalidation** — the caching session
    /// sends `If-None-Match` / `If-Modified-Since` from the stored validators and serves
    /// the cached body on `304 Not Modified`. Throttling / transient errors are reported
    /// as `.retry` (with any `Retry-After`) so the caller backs off; other 4xx are
    /// `.unavailable` (keep last good, no aggressive retry).
    func fetchObject(url: URL, session: URLSession = EUMETNETORDClient.cachingSession) async -> ObjectOutcome {
        var request = URLRequest(url: url)
        request.cachePolicy = .reloadRevalidatingCacheData
        request.timeoutInterval = 20
        request.setValue(AppHTTP.userAgent, forHTTPHeaderField: "User-Agent")
        guard let (data, response) = try? await session.data(for: request) else {
            return .retry(after: nil)   // network / timeout → back off
        }
        if let http = response as? HTTPURLResponse {
            if AppHTTP.isRetryableStatus(http.statusCode) {
                return .retry(after: AppHTTP.parseRetryAfter(http.value(forHTTPHeaderField: "Retry-After")))
            }
            guard (200...299).contains(http.statusCode) else { return .unavailable }
        }
        return data.isEmpty ? .unavailable : .success(data)
    }

    /// GET a text (XML) body, decoded as UTF-8, with the app User-Agent. Nil on any
    /// HTTP/URL error.
    private func fetchText(url: URL, session: URLSession) async -> String? {
        var request = URLRequest(url: url)
        request.timeoutInterval = 15
        request.setValue(AppHTTP.userAgent, forHTTPHeaderField: "User-Agent")
        guard let (data, response) = try? await session.data(for: request) else { return nil }
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) { return nil }
        return String(data: data, encoding: .utf8)
    }
}
