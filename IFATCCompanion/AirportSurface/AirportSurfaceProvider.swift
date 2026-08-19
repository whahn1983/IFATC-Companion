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
        /// Overpass answered `200 OK` with one of its own error pages — "too busy", rate
        /// limited, query timed out — instead of the extract. Distinct from `emptyExtract`
        /// on purpose: one says the server could not answer, the other says the airport
        /// has nothing mapped, and telling a pilot the second when the first is true sends
        /// them hunting the wrong problem.
        case serverBusy(String?)
        case decoding
        case unreachable

        var errorDescription: String? {
            switch self {
            case .badURL: return "Invalid Overpass endpoint URL."
            case .http(let code): return "Overpass returned HTTP \(code)."
            case .throttled: return "Overpass requests are backing off after repeated errors."
            case .emptyExtract: return "OpenStreetMap returned no airport surface features for this area."
            case .serverBusy(let reason):
                let detail = reason.map { " — \($0)." } ?? "."
                return "The OpenStreetMap Overpass servers could not answer right now\(detail) Airport data will be retried automatically."
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
        // What each endpoint actually said, kept apart so the failure reported at the end is
        // the true one. An empty extract is a real answer from a working server; an Overpass
        // error page served with HTTP 200 is not.
        var sawEmptyExtract = false
        var sawUndecodableBody = false
        var sawRateLimit = false
        var errorPage: OverpassErrorPage?
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
                        // Try the next endpoint before giving up. A 429 is the same "we are
                        // busy" answer the error page gives, just said with a status code.
                        if http.statusCode == 429 { sawRateLimit = true }
                        continue
                    }
                    guard (200...299).contains(http.statusCode) else {
                        throw SurfaceError.http(http.statusCode)
                    }
                }
                guard let decoded = try? JSONDecoder().decode(OverpassResponse.self, from: data) else {
                    // Overpass reports overload as an HTML page served with HTTP 200, so a
                    // body that isn't the JSON extract is the *server* answering, never an
                    // airport with nothing mapped.
                    if let page = OverpassErrorPage.detect(in: data) {
                        errorPage = errorPage ?? page
                        diagnostics?.logAsync(.app, "OSM \(icao): \(endpoint) returned an Overpass error page — \(page.summary)")
                        throw SurfaceError.serverBusy(page.reason)
                    }
                    sawUndecodableBody = true
                    throw SurfaceError.decoding
                }
                guard !decoded.elements.isEmpty else {
                    // No aeroway features here; a real "no data" answer — try the next
                    // endpoint in case it's a transient partial, else surface empty.
                    sawEmptyExtract = true
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

        // Every endpoint failed or returned empty. Report what actually happened, most
        // specific first: a server that answered "nothing here" is the only one of these
        // that is really about the airport.
        registerFailure(icao: icao, retryAfter: nil)
        if sawEmptyExtract {
            diagnostics?.logAsync(.app, "OSM \(icao): no airport surface features returned")
            throw SurfaceError.emptyExtract
        }
        if let errorPage {
            diagnostics?.logAsync(.app, "OSM \(icao): every Overpass endpoint answered with an error page (\(errorPage.reason ?? "unclassified"))")
            throw SurfaceError.serverBusy(errorPage.reason)
        }
        if sawUndecodableBody {
            diagnostics?.logAsync(.app, "OSM \(icao): Overpass response could not be decoded")
            throw SurfaceError.decoding
        }
        if sawRateLimit {
            diagnostics?.logAsync(.app, "OSM \(icao): every Overpass endpoint rate-limited the request (HTTP 429)")
            throw SurfaceError.serverBusy("the request rate limit is in force")
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
