import Foundation
import CoreLocation

/// Fetches, normalizes, and caches airport surfaces from OpenStreetMap via a public
/// Overpass endpoint. A well-behaved direct-to-public-service client, mirroring
/// `ATISService`/`AviationWeatherService`:
///  - requests only a **small airport-specific bounding box** (never a region/planet);
///  - **caches** successful extracts on disk with a long (75-day) refresh interval, so
///    there is no network activity during taxi once an airport is loaded;
///  - **coalesces** concurrent identical requests and never runs parallel queries for
///    the same airport;
///  - a descriptive **User-Agent** identifying IFATC Companion / H3 Consulting Partners;
///  - **fails over** across the configured public endpoints and **backs off** politely
///    on 429/5xx, serving stale cached data rather than hammering a shared server;
///  - lets the user **manually refresh** (`forceRefresh`).
///
/// Free access to OSM data does not guarantee unlimited access to any particular public
/// Overpass server — hence the failover, backoff, dedup, and stale-serve behavior.
actor AirportSurfaceProvider {

    enum SurfaceError: LocalizedError {
        case badURL
        case http(Int)
        case throttled
        case emptyExtract
        case decoding
        case unreachable

        var errorDescription: String? {
            switch self {
            case .badURL: return "Invalid Overpass endpoint URL."
            case .http(let code): return "Overpass returned HTTP \(code)."
            case .throttled: return "Overpass requests are backing off after repeated errors."
            case .emptyExtract: return "OpenStreetMap returned no airport surface features for this area."
            case .decoding: return "Could not decode the Overpass response."
            case .unreachable: return "The airport surface data service is temporarily unavailable."
            }
        }
    }

    private let cache: AirportSurfaceCache
    private let session: URLSession
    private var endpoints: [String]
    private weak var diagnostics: DiagnosticsStore?

    /// In-memory hot cache so repeated same-session reads never touch disk/network.
    private var memory: [String: AirportSurfaceModel] = [:]
    private var inFlight: [String: Task<AirportSurfaceModel, Error>] = [:]
    /// Backoff is tracked **per airport**. It used to be one counter for the whole provider,
    /// which meant one field failing all its endpoints denied every *other* uncached airport
    /// for the next 60–900 s — so a slow monster on one end of the flight could starve the
    /// other end of its data entirely.
    private var failureCounts: [String: Int] = [:]
    private var nextRetryAt: [String: Date] = [:]
    private(set) var lastErrorMessage: String?

    init(cache: AirportSurfaceCache = AirportSurfaceCache(),
         endpoints: [String] = OSMSurface.overpassEndpoints,
         session: URLSession = AppHTTP.makeCachingSession(cacheName: "osm-overpass-cache",
                                                          memoryMB: 4, diskMB: 16,
                                                          timeout: OSMSurface.overpassRequestTimeout)) {
        self.cache = cache
        self.endpoints = endpoints
        self.session = session
    }

    func configure(diagnostics: DiagnosticsStore?) {
        self.diagnostics = diagnostics
    }

    // MARK: - Cache-only access (no network)

    /// The best cached surface for an airport (memory then disk), without any network.
    func cachedSurface(icao: String) -> AirportSurfaceModel? {
        let key = icao.uppercased()
        if let m = memory[key] { return m }
        if let disk = cache.load(icao: key) {
            memory[key] = disk
            return disk
        }
        return nil
    }

    func clearCache() {
        memory.removeAll()
        cache.deleteAll()
        failureCounts.removeAll()
        nextRetryAt.removeAll()
    }

    func deleteCache(icao: String) {
        let key = icao.uppercased()
        memory[key] = nil
        cache.delete(icao: key)
        // Deleting an airport's cache is a deliberate "try again" — don't leave it serving a
        // throttled error from a failure that happened before the pilot asked for a re-fetch.
        clearBackoff(icao: key)
    }

    func cacheInfo() -> (icaos: [String], bytes: Int) {
        (cache.cachedICAOs(), cache.totalSizeBytes())
    }

    // MARK: - Fetch / normalize / cache

    /// Get the normalized surface for an airport. Returns a cached model when fresh
    /// (or when offline/backing off and a cached copy exists), otherwise fetches a new
    /// airport-sized extract from Overpass, normalizes and caches it.
    func surface(for icao: String,
                 reference: CLLocationCoordinate2D,
                 forceRefresh: Bool = false) async throws -> AirportSurfaceModel {
        let key = icao.uppercased().trimmingCharacters(in: .whitespaces)
        guard key.count >= 3 else { throw SurfaceError.badURL }
        guard reference.isValid else { throw SurfaceError.badURL }

        // Fresh cache (memory or disk) → no network. A cache written by an older model
        // schema (e.g. before building footprints existed) is treated as not-fresh so it
        // is re-fetched now, even though its fetch date may be well within the refresh
        // interval — otherwise a stand behind a concourse keeps routing through it.
        if !forceRefresh, let cached = cachedSurface(icao: key),
           !cached.source.isStale, !cached.source.isOutdatedSchema {
            return cached
        }
        // Backing off for *this airport* → serve stale if we have it, else fail.
        if !forceRefresh, let retry = nextRetryAt[key], Date() < retry {
            if let cached = cachedSurface(icao: key) { return cached }
            throw SurfaceError.throttled
        }
        // Coalesce concurrent identical requests.
        if let existing = inFlight[key] {
            return try await joinOrStale(existing, key: key, isOwner: false)
        }
        let ref = reference
        let task = Task<AirportSurfaceModel, Error> {
            try await self.performFetch(icao: key, reference: ref)
        }
        inFlight[key] = task
        return try await joinOrStale(task, key: key, isOwner: true)
    }

    private func joinOrStale(_ task: Task<AirportSurfaceModel, Error>, key: String, isOwner: Bool) async throws -> AirportSurfaceModel {
        defer { if isOwner { inFlight[key] = nil } }
        do {
            return try await task.value
        } catch {
            if let cached = cachedSurface(icao: key) { return cached }
            throw error
        }
    }

    private func performFetch(icao: String, reference: CLLocationCoordinate2D) async throws -> AirportSurfaceModel {
        let query = OverpassQuery(icao: icao, center: reference)
        guard let body = query.httpBody else { throw SurfaceError.badURL }
        diagnostics?.logAsync(.app, "OSM Overpass GET \(icao) bbox \(query.boundingBox.overpassClause)")

        var lastStatus: Int?
        for endpoint in endpoints {
            guard let url = URL(string: endpoint) else { continue }
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.httpBody = body
            request.timeoutInterval = OSMSurface.overpassRequestTimeout
            request.setValue(OSMSurface.userAgent, forHTTPHeaderField: "User-Agent")
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            request.setValue("application/json", forHTTPHeaderField: "Accept")

            do {
                let (data, response) = try await session.data(for: request)
                if let http = response as? HTTPURLResponse {
                    lastStatus = http.statusCode
                    if AppHTTP.isRetryableStatus(http.statusCode) {
                        // Try the next endpoint before giving up.
                        continue
                    }
                    guard (200...299).contains(http.statusCode) else {
                        throw SurfaceError.http(http.statusCode)
                    }
                }
                guard let decoded = try? JSONDecoder().decode(OverpassResponse.self, from: data) else {
                    throw SurfaceError.decoding
                }
                guard !decoded.elements.isEmpty else {
                    // No aeroway features here; a real "no data" answer — try the next
                    // endpoint in case it's a transient partial, else surface empty.
                    lastStatus = 200
                    continue
                }
                let model = OSMSurfaceNormalizer.normalize(decoded,
                                                           icao: icao,
                                                           reference: reference,
                                                           endpoint: endpoint,
                                                           boundingBox: query.boundingBox,
                                                           fetchDate: Date())
                memory[icao] = model
                cache.save(model)
                clearBackoff(icao: icao)
                lastErrorMessage = nil
                diagnostics?.logAsync(.app, "OSM \(icao): \(decoded.elements.count) elements → \(model.runways.count) rwy, \(model.taxiways.count) twy, \(model.confidence.title) confidence")
                return model
            } catch {
                lastErrorMessage = (error as? LocalizedError)?.errorDescription ?? "\(error)"
                continue
            }
        }

        // Every endpoint failed or returned empty.
        registerFailure(icao: icao, retryAfter: nil)
        if let status = lastStatus, status == 200 {
            diagnostics?.logAsync(.app, "OSM \(icao): no airport surface features returned")
            throw SurfaceError.emptyExtract
        }
        diagnostics?.logAsync(.app, "OSM \(icao): all Overpass endpoints unavailable (last HTTP \(lastStatus.map(String.init) ?? "—"))")
        throw SurfaceError.unreachable
    }

    // MARK: - Backoff

    private func registerFailure(icao: String, retryAfter: TimeInterval?) {
        let count = (failureCounts[icao] ?? 0) + 1
        failureCounts[icao] = count
        let backoff = AppHTTP.backoffDelay(failureCount: count, base: 60, cap: 900)
        nextRetryAt[icao] = Date().addingTimeInterval(max(backoff, retryAfter ?? 0))
    }

    private func clearBackoff(icao: String) {
        failureCounts[icao] = nil
        nextRetryAt[icao] = nil
    }
}
