import Foundation

/// On-disk cache of normalized airport surfaces, one JSON file per ICAO under the
/// app's Caches directory.
///
/// Requirements met here:
///  - caches only airports actually used (files are written on demand);
///  - stores the source identifier, fetch date, cache age, ODbL metadata and
///    attribution (all inside the cached `AirportSurfaceModel.source` provenance);
///  - retains original OSM identifiers and tags (they are part of the model);
///  - supports deletion (single airport or all) for Settings;
///  - never bundles a global OSM database in the binary.
///
/// The refresh interval itself (60–90 days) is enforced by the provider, which treats
/// a cached model older than `OSMSurface.cacheRefreshInterval` as stale.
struct AirportSurfaceCache {

    let directory: URL

    init(directoryName: String = OSMSurface.cacheDirectoryName) {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
        directory = (base ?? FileManager.default.temporaryDirectory)
            .appendingPathComponent(directoryName, isDirectory: true)
    }

    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        return e
    }()

    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }()

    private func fileURL(for icao: String) -> URL {
        directory.appendingPathComponent("\(icao.uppercased()).json")
    }

    /// Load a cached surface, or nil when none / undecodable.
    func load(icao: String) -> AirportSurfaceModel? {
        let url = fileURL(for: icao)
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? Self.decoder.decode(AirportSurfaceModel.self, from: data)
    }

    /// Persist a normalized surface (atomic write). Errors are swallowed — a failed
    /// cache write only costs a re-fetch later.
    @discardableResult
    func save(_ model: AirportSurfaceModel) -> Bool {
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let data = try Self.encoder.encode(model)
            try data.write(to: fileURL(for: model.icao), options: .atomic)
            return true
        } catch {
            return false
        }
    }

    /// Delete a single airport's cached surface.
    func delete(icao: String) {
        try? FileManager.default.removeItem(at: fileURL(for: icao))
    }

    /// Delete every cached airport surface (Settings → clear cache).
    func deleteAll() {
        guard let files = try? FileManager.default.contentsOfDirectory(at: directory,
                                                                       includingPropertiesForKeys: nil) else { return }
        for f in files where f.pathExtension == "json" {
            try? FileManager.default.removeItem(at: f)
        }
    }

    /// ICAO codes with a cached surface on disk.
    func cachedICAOs() -> [String] {
        guard let files = try? FileManager.default.contentsOfDirectory(at: directory,
                                                                       includingPropertiesForKeys: nil) else { return [] }
        return files.filter { $0.pathExtension == "json" }
            .map { $0.deletingPathExtension().lastPathComponent.uppercased() }
            .sorted()
    }

    /// Total bytes used by the cache directory (for the Settings display).
    func totalSizeBytes() -> Int {
        guard let files = try? FileManager.default.contentsOfDirectory(at: directory,
                                                                       includingPropertiesForKeys: [.fileSizeKey]) else { return 0 }
        return files.reduce(0) { sum, url in
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
            return sum + size
        }
    }
}
