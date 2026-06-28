import Foundation

/// Fetches aviation weather from the NOAA Aviation Weather Center public JSON API.
/// No API keys. Responses are cached in-memory with a TTL to reduce calls.
/// All failures are surfaced as thrown errors and handled gracefully by callers.
actor AviationWeatherService {

    enum WeatherError: LocalizedError {
        case badURL
        case http(Int)
        case noData
        var errorDescription: String? {
            switch self {
            case .badURL: return "Invalid weather endpoint URL."
            case .http(let code): return "Weather server returned HTTP \(code)."
            case .noData: return "No weather data returned."
            }
        }
    }

    private struct CacheEntry { let data: Data; let timestamp: Date }
    private var cache: [String: CacheEntry] = [:]
    private let ttl: TimeInterval = 300 // 5 minutes
    private let session: URLSession

    private var baseURL: String
    private weak var diagnostics: DiagnosticsStore?

    init(baseURL: String = "https://aviationweather.gov/api/data",
         session: URLSession = .shared) {
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
        if let cached = cache[key], Date().timeIntervalSince(cached.timestamp) < ttl {
            return cached.data
        }

        diagnostics?.logAsync(.weather, "GET \(path) \(query["ids"] ?? "")")
        var request = URLRequest(url: url)
        request.timeoutInterval = 12
        request.setValue("IFATCCompanion/1.0", forHTTPHeaderField: "User-Agent")

        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse {
            await setEndpointStatus("HTTP \(http.statusCode) — \(path)")
            guard (200...299).contains(http.statusCode) else { throw WeatherError.http(http.statusCode) }
        }
        guard !data.isEmpty else { throw WeatherError.noData }
        cache[key] = CacheEntry(data: data, timestamp: Date())
        return data
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
