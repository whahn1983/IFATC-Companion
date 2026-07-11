import Foundation

/// Shared HTTP conventions for the app's direct-to-public-service clients (NOAA
/// aviation weather, NOAA/NWS radar, EUMETNET OPERA ORD). The app has no backend, so
/// every device talks to these public services itself — this centralizes the
/// "well-behaved public client" bits: a descriptive User-Agent with contact info, a
/// shared revalidating URL cache, and pure `Retry-After` / exponential-backoff math.
enum AppHTTP {

    /// Contact/identity URL included in the User-Agent so service operators can reach
    /// the project (NWS asks clients to identify themselves; a public repo is a stable,
    /// non-personal contact point).
    static let contactURL = "https://github.com/whahn1983/IFATC-Companion"

    /// A descriptive User-Agent: app name + version + contact, e.g.
    /// `IFATCCompanion/1.4 (+https://github.com/whahn1983/IFATC-Companion)`.
    static let userAgent: String = {
        let version = (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "dev"
        return "IFATCCompanion/\(version) (+\(contactURL))"
    }()

    /// A URLSession backed by a sized on-disk `URLCache`, so conditional revalidation
    /// (ETag/If-None-Match, Last-Modified/If-Modified-Since) and any `Cache-Control`
    /// are honored by the loader. Sized generously enough that a few-MB radar composite
    /// is actually cacheable (URLCache skips entries larger than ~5% of capacity).
    static func makeCachingSession(cacheName: String = "app-http-cache",
                                   memoryMB: Int = 16, diskMB: Int = 256,
                                   timeout: TimeInterval = 20) -> URLSession {
        let config = URLSessionConfiguration.default
        let directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)
            .first?.appendingPathComponent(cacheName, isDirectory: true)
        config.urlCache = URLCache(memoryCapacity: memoryMB * 1_024 * 1_024,
                                   diskCapacity: diskMB * 1_024 * 1_024,
                                   directory: directory)
        config.requestCachePolicy = .useProtocolCachePolicy
        config.timeoutIntervalForRequest = timeout
        config.httpAdditionalHeaders = ["User-Agent": userAgent]
        return URLSession(configuration: config)
    }

    /// Shared revalidating-cache session for overlay **image** fetches (NOAA/NASA/OPERA
    /// WMS PNGs pulled programmatically for sampling/rendering). Carries the descriptive
    /// User-Agent and honors ETag/Last-Modified. Bounded LRU disk cache.
    static let imageSession = makeCachingSession(cacheName: "overlay-img-cache",
                                                 memoryMB: 16, diskMB: 64)

    // MARK: - Retry-After / backoff (pure, unit-tested)

    /// Parse an HTTP `Retry-After` header value, which is either an integer number of
    /// seconds or an HTTP-date. Returns the delay in seconds (clamped ≥ 0), or nil when
    /// absent/unparseable.
    static func parseRetryAfter(_ value: String?, now: Date = Date()) -> TimeInterval? {
        guard let value = value?.trimmingCharacters(in: .whitespaces), !value.isEmpty else { return nil }
        if let seconds = TimeInterval(value) { return max(0, seconds) }
        let fmt = DateFormatter()
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.timeZone = TimeZone(identifier: "GMT")
        fmt.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"   // RFC 1123 (HTTP-date)
        guard let date = fmt.date(from: value) else { return nil }
        return max(0, date.timeIntervalSince(now))
    }

    /// Exponential backoff delay for the Nth consecutive failure (1-based): capped
    /// `base · 2^(failureCount−1)`. Callers add jitter / honor a larger `Retry-After`.
    static func backoffDelay(failureCount: Int, base: TimeInterval = 30, cap: TimeInterval = 900) -> TimeInterval {
        guard failureCount > 0 else { return 0 }
        // Cap the exponent so 2^n can't overflow before the min() clamps it.
        let exponent = min(failureCount - 1, 20)
        return min(cap, base * pow(2, Double(exponent)))
    }

    /// Whether an HTTP status code is worth retrying with backoff (throttling /
    /// transient server errors), per NWS/ORD guidance.
    static func isRetryableStatus(_ code: Int) -> Bool {
        code == 429 || code == 503 || code == 502 || code == 504
    }
}
