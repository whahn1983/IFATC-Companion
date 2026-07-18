import Foundation

/// Fetches real-world FAA D-ATIS from the free, public, keyless `datis.clowd.io`
/// endpoint. A **well-behaved direct-to-public-service client**, mirroring
/// `AviationWeatherService`: the app has no backend, so every device calls the
/// service itself.
///  - a short in-memory **TTL cache** (2 min) fronts the network so the periodic
///    availability checks and the telemetry-driven range checks don't re-fetch within
///    a product update window; tuning ATIS passes `forceRefresh` to always pull the
///    latest;
///  - the shared session **revalidates conditionally** (ETag / Last-Modified) and
///    carries a **descriptive User-Agent with contact info**;
///  - concurrent identical requests are **coalesced** into one fetch;
///  - on 429/5xx/timeout it **backs off exponentially** and **serves the last good
///    cached data** rather than failing hard;
///  - a **404 (or other 4xx)** means the field simply has no D-ATIS: it is cached as a
///    `nil` miss so the app hides the feature without hammering the endpoint.
///
/// A successful fetch can legitimately return `nil` (airport has no D-ATIS). The
/// method only *throws* on a transient network/server failure with no cached fallback.
actor ATISService {

    enum ATISError: LocalizedError {
        case badURL
        case http(Int)
        case throttled
        var errorDescription: String? {
            switch self {
            case .badURL: return "Invalid ATIS endpoint URL."
            case .http(let code): return "ATIS server returned HTTP \(code)."
            case .throttled: return "ATIS requests are backing off after repeated errors."
            }
        }
    }

    private struct CacheEntry { let atis: AirportATIS?; let timestamp: Date }
    private var cache: [String: CacheEntry] = [:]
    private let ttl: TimeInterval = 120 // 2 minutes
    private let session: URLSession

    /// In-flight fetches keyed by ICAO, so concurrent identical requests share one call.
    private var inFlight: [String: Task<AirportATIS?, Error>] = [:]
    private var failureCount = 0
    private var nextRetryAt: Date?

    private var baseURL: String
    private weak var diagnostics: DiagnosticsStore?

    init(baseURL: String = "https://datis.clowd.io/api",
         session: URLSession = AppHTTP.makeCachingSession(cacheName: "atis-http-cache",
                                                          memoryMB: 4, diskMB: 8, timeout: 12)) {
        self.baseURL = baseURL
        self.session = session
    }

    func configure(baseURL: String? = nil, diagnostics: DiagnosticsStore?) {
        if let baseURL, !baseURL.isEmpty { self.baseURL = baseURL }
        self.diagnostics = diagnostics
    }

    // MARK: - Fetch

    /// Fetch the current ATIS for an airport. Returns nil when the field has no
    /// published D-ATIS (a normal condition — the feature then quietly disappears).
    /// Throws only on a network/transient failure with no cached fallback.
    func atis(for icao: String, forceRefresh: Bool = false) async throws -> AirportATIS? {
        let id = icao.uppercased().trimmingCharacters(in: .whitespaces)
        guard id.count >= 3, id.allSatisfy({ $0.isLetter || $0.isNumber }) else { return nil }
        let key = id

        // Fresh within the TTL → no network (unless the caller forces a pull, e.g. tune).
        if !forceRefresh, let cached = cache[key], Date().timeIntervalSince(cached.timestamp) < ttl {
            return cached.atis
        }
        // Backing off after repeated failures → serve stale if we have it, else fail.
        if !forceRefresh, let retry = nextRetryAt, Date() < retry {
            if let cached = cache[key] { return cached.atis }
            throw ATISError.throttled
        }
        // Coalesce concurrent identical requests into a single fetch.
        if let existing = inFlight[key] {
            return try await joinOrStale(existing, key: key, isOwner: false)
        }
        let task = Task<AirportATIS?, Error> { try await self.performFetch(id: id, key: key) }
        inFlight[key] = task
        return try await joinOrStale(task, key: key, isOwner: true)
    }

    /// Await a (possibly shared) fetch; on failure prefer stale cached data over the
    /// error. Only the task's owner clears the in-flight slot.
    private func joinOrStale(_ task: Task<AirportATIS?, Error>, key: String, isOwner: Bool) async throws -> AirportATIS? {
        defer { if isOwner { inFlight[key] = nil } }
        do {
            return try await task.value
        } catch {
            if let cached = cache[key] { return cached.atis }
            throw error
        }
    }

    private func performFetch(id: String, key: String) async throws -> AirportATIS? {
        guard let url = URL(string: "\(baseURL)/\(id)") else { throw ATISError.badURL }
        diagnostics?.logAsync(.atis, "ATIS GET \(id)")

        var request = URLRequest(url: url)
        request.cachePolicy = .reloadRevalidatingCacheData
        request.timeoutInterval = 12
        request.setValue(AppHTTP.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        do {
            let (data, response) = try await session.data(for: request)
            if let http = response as? HTTPURLResponse {
                await setStatus("HTTP \(http.statusCode) — \(id)")
                if AppHTTP.isRetryableStatus(http.statusCode) {
                    registerFailure(retryAfter: AppHTTP.parseRetryAfter(http.value(forHTTPHeaderField: "Retry-After")))
                    throw ATISError.http(http.statusCode)
                }
                // A 4xx (typically 404) means this field has no D-ATIS. Cache the miss
                // so we hide the feature without re-hammering the endpoint.
                if (400...499).contains(http.statusCode) {
                    cache[key] = CacheEntry(atis: nil, timestamp: Date())
                    clearBackoff()
                    return nil
                }
                guard (200...299).contains(http.statusCode) else { throw ATISError.http(http.statusCode) }
            }
            let atis = ATISParser.parse(data, airport: id, now: Date())
            cache[key] = CacheEntry(atis: atis, timestamp: Date())
            clearBackoff()
            return atis
        } catch {
            if !(error is ATISError) { registerFailure(retryAfter: nil) }
            throw error
        }
    }

    // MARK: - Backoff

    private func registerFailure(retryAfter: TimeInterval?) {
        failureCount += 1
        let backoff = AppHTTP.backoffDelay(failureCount: failureCount, base: 15, cap: 600)
        nextRetryAt = Date().addingTimeInterval(max(backoff, retryAfter ?? 0))
    }

    private func clearBackoff() {
        failureCount = 0
        nextRetryAt = nil
    }

    private func setStatus(_ status: String) async {
        let diag = diagnostics
        await MainActor.run { diag?.atisEndpointStatus = status }
    }

    func clearCache() { cache.removeAll() }
}
