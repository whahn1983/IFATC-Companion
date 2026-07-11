import Foundation

/// Fetches aviation weather from the NOAA Aviation Weather Center public JSON Data API
/// (`aviationweather.gov/api/data`). No API keys, no account. A **well-behaved
/// direct-to-public-service client** (the app has no backend, so every device calls
/// the service itself):
///  - an **in-memory TTL cache** (5 min) fronts the network so repeated reads and the
///    event-driven refreshes (on connect / manual pull-to-refresh — there is no
///    periodic poll) don't re-fetch within a product's update window;
///  - the shared session **revalidates conditionally** (ETag / Last-Modified) beyond
///    the TTL, and carries a **descriptive User-Agent with contact info**;
///  - concurrent identical requests are **coalesced** into one fetch;
///  - on 429/503/5xx/timeout it **backs off exponentially** (honoring `Retry-After`)
///    and **serves the last good cached data** rather than failing hard;
///  - non-retryable errors (e.g. HTTP 400) don't trigger backoff.
///
/// Note: this app uses the AWC Data API, **not** `api.weather.gov`, so it makes no
/// `/points`, forecast-office, gridpoint, or station-list metadata calls to cache.
actor AviationWeatherService {

    enum WeatherError: LocalizedError {
        case badURL
        case http(Int)
        case noData
        case throttled
        var errorDescription: String? {
            switch self {
            case .badURL: return "Invalid weather endpoint URL."
            case .http(let code): return "Weather server returned HTTP \(code)."
            case .noData: return "No weather data returned."
            case .throttled: return "Weather requests are backing off after repeated errors."
            }
        }
    }

    private struct CacheEntry { let data: Data; let timestamp: Date }
    private var cache: [String: CacheEntry] = [:]
    private let ttl: TimeInterval = 300 // 5 minutes
    private let session: URLSession

    /// In-flight fetches keyed by URL, so concurrent identical requests share one call.
    private var inFlight: [String: Task<Data, Error>] = [:]
    /// Exponential-backoff state after throttling / transient failures.
    private var failureCount = 0
    private var nextRetryAt: Date?

    private var baseURL: String
    private weak var diagnostics: DiagnosticsStore?

    init(baseURL: String = "https://aviationweather.gov/api/data",
         session: URLSession = AppHTTP.makeCachingSession(cacheName: "avwx-http-cache",
                                                          memoryMB: 8, diskMB: 32, timeout: 12)) {
        self.baseURL = baseURL
        self.session = session
    }

    func configure(baseURL: String, diagnostics: DiagnosticsStore?) {
        self.baseURL = baseURL.isEmpty ? self.baseURL : baseURL
        self.diagnostics = diagnostics
    }

    // MARK: - Public fetches

    func metars(for icaos: [String]) async throws -> [METAR] {
        let ids = sanitized(icaos)
        guard !ids.isEmpty else { return [] }
        let data = try await get(path: "metar", query: ["ids": ids.joined(separator: ","), "format": "json"])
        return METARParser.parseJSON(data)
    }

    func taf(for icao: String) async throws -> TAF? {
        let id = sanitized([icao]).first
        guard let id else { return nil }
        let data = try await get(path: "taf", query: ["ids": id, "format": "json"])
        return TAFParser.parseJSON(data).first
    }

    func pireps(ageHours: Int = 3) async throws -> [PIREP] {
        let data = try await get(path: "pirep", query: ["format": "json", "age": String(ageHours)])
        return PIREPParser.parseJSON(data)
    }

    func airSigmets() async throws -> [SIGMET] {
        let data = try await get(path: "airsigmet", query: ["format": "json"])
        return SIGMETParser.parseJSON(data)
    }

    // MARK: - Networking

    private func get(path: String, query: [String: String]) async throws -> Data {
        var components = URLComponents(string: "\(baseURL)/\(path)")
        components?.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        guard let url = components?.url else { throw WeatherError.badURL }
        let key = url.absoluteString

        // Fresh within the TTL → no network.
        if let cached = cache[key], Date().timeIntervalSince(cached.timestamp) < ttl {
            return cached.data
        }
        // Backing off after repeated failures → serve stale if we have it, else fail.
        if let retry = nextRetryAt, Date() < retry {
            if let cached = cache[key] { return cached.data }
            throw WeatherError.throttled
        }
        // Coalesce concurrent identical requests into a single fetch.
        if let existing = inFlight[key] {
            return try await joinOrStale(existing, key: key, isOwner: false)
        }
        let task = Task<Data, Error> { try await self.performFetch(url: url, path: path, key: key) }
        inFlight[key] = task
        return try await joinOrStale(task, key: key, isOwner: true)
    }

    /// Await a (possibly shared) fetch task; on failure prefer stale cached data over
    /// surfacing the error. Only the task's owner clears the in-flight slot.
    private func joinOrStale(_ task: Task<Data, Error>, key: String, isOwner: Bool) async throws -> Data {
        defer { if isOwner { inFlight[key] = nil } }
        do {
            return try await task.value
        } catch {
            if let cached = cache[key] { return cached.data }   // stale-but-usable fallback
            throw error
        }
    }

    private func performFetch(url: URL, path: String, key: String) async throws -> Data {
        diagnostics?.logAsync(.weather, "GET \(path)")
        var request = URLRequest(url: url)
        request.cachePolicy = .reloadRevalidatingCacheData   // honor ETag / Last-Modified
        request.timeoutInterval = 12
        request.setValue(AppHTTP.userAgent, forHTTPHeaderField: "User-Agent")

        do {
            let (data, response) = try await session.data(for: request)
            if let http = response as? HTTPURLResponse {
                await setEndpointStatus("HTTP \(http.statusCode) — \(path)")
                if AppHTTP.isRetryableStatus(http.statusCode) {
                    registerFailure(retryAfter: AppHTTP.parseRetryAfter(http.value(forHTTPHeaderField: "Retry-After")))
                    throw WeatherError.http(http.statusCode)
                }
                guard (200...299).contains(http.statusCode) else {
                    throw WeatherError.http(http.statusCode)   // e.g. 400 — not a throttle; don't back off
                }
            }
            guard !data.isEmpty else { throw WeatherError.noData }
            cache[key] = CacheEntry(data: data, timestamp: Date())
            clearBackoff()
            return data
        } catch {
            // Network / timeout (non-HTTP) errors are transient → back off.
            if !(error is WeatherError) { registerFailure(retryAfter: nil) }
            throw error
        }
    }

    private func registerFailure(retryAfter: TimeInterval?) {
        failureCount += 1
        let backoff = AppHTTP.backoffDelay(failureCount: failureCount, base: 15, cap: 600)
        nextRetryAt = Date().addingTimeInterval(max(backoff, retryAfter ?? 0))
    }

    private func clearBackoff() {
        failureCount = 0
        nextRetryAt = nil
    }

    private func setEndpointStatus(_ status: String) async {
        let diag = diagnostics
        await MainActor.run { diag?.weatherEndpointStatus = status }
    }

    func clearCache() { cache.removeAll() }

    private func sanitized(_ icaos: [String]) -> [String] {
        icaos.map { $0.uppercased().trimmingCharacters(in: .whitespaces) }
            .filter { $0.count >= 3 && $0.allSatisfy { $0.isLetter || $0.isNumber } }
    }
}
