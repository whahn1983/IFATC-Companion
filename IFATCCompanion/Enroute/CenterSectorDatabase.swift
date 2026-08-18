import Foundation
import CoreLocation

/// The bundled enroute sector map: ~450 ARTCC / FIR boundaries covering the globe,
/// loaded once off the main thread and then queried by position.
///
/// Loading is lazy and asynchronous because the dataset is ~0.5 MB of JSON and there
/// is no reason to pay for it on a flight that never leaves the pattern. `prepare()`
/// is called when a flight is set up; every query before the load finishes simply
/// returns nil, which the hand-off tracker reads as "no crossing yet".
final class CenterSectorDatabase {

    static let shared = CenterSectorDatabase()

    /// Sectors ordered smallest-area first (the generator sorts them). Where two
    /// boundaries overlap — an upper sector stacked on a lower one, a national FIR
    /// wrapping the ACCs inside it — `sector(at:)` therefore returns the most specific
    /// one, deterministically, instead of whichever happened to decode first.
    private var sectors: [CenterSector] = []
    private var loadState: LoadState = .idle
    private var loadedProvenance: Provenance?
    private let lock = NSLock()
    private let bundle: Bundle

    enum LoadState: Equatable {
        case idle
        case loading
        case ready
        case failed(String)
    }

    init(bundle: Bundle = .main) {
        self.bundle = bundle
    }

    /// Build a database from sectors already in memory, skipping the bundle entirely.
    /// The test seam: a handful of known boundaries makes hand-off behavior assertable
    /// in a way the shipped global dataset does not. Pass them smallest-area first, the
    /// order the generator writes, so overlapping boundaries resolve the same way here
    /// as they do in the app.
    init(sectors: [CenterSector]) {
        self.bundle = .main
        self.sectors = CenterSectorDatabase.deconflictFrequencies(sectors)
        self.loadState = .ready
        self.loadedProvenance = Provenance(generated: "in-memory", source: "test",
                                           license: CenterSectorData.licenseShortName,
                                           sectorCount: sectors.count)
    }

    // MARK: - Loading

    /// Kick off the background load if it hasn't started. Idempotent and cheap to call
    /// from anywhere; returns immediately.
    func prepare() {
        lock.lock()
        guard loadState == .idle else { lock.unlock(); return }
        loadState = .loading
        lock.unlock()
        DispatchQueue.global(qos: .utility).async { [weak self] in
            self?.load()
        }
    }

    /// Load synchronously. Used by the background worker and by tests, which need the
    /// data in hand before they can assert on it.
    @discardableResult
    func loadNow() -> Bool {
        lock.lock()
        let alreadyReady = loadState == .ready
        if !alreadyReady { loadState = .loading }
        lock.unlock()
        if alreadyReady { return true }
        return load()
    }

    @discardableResult
    private func load() -> Bool {
        do {
            guard let url = bundle.url(forResource: CenterSectorData.resourceName,
                                       withExtension: CenterSectorData.resourceExtension)
                    ?? Bundle.allBundles.compactMap({
                        $0.url(forResource: CenterSectorData.resourceName,
                               withExtension: CenterSectorData.resourceExtension)
                    }).first
            else {
                finish(.failed("CenterSectors.json is not in the bundle"))
                return false
            }
            let document = try JSONDecoder().decode(Document.self, from: Data(contentsOf: url))
            guard document.schemaVersion <= CenterSectorData.supportedSchemaVersion else {
                finish(.failed("sector data schema \(document.schemaVersion) is newer than this build"))
                return false
            }
            lock.lock()
            sectors = CenterSectorDatabase.deconflictFrequencies(document.sectors)
            loadedProvenance = Provenance(generated: document.generated,
                                          source: document.source,
                                          license: document.license,
                                          sectorCount: document.sectors.count)
            loadState = .ready
            lock.unlock()
            return true
        } catch {
            finish(.failed(error.localizedDescription))
            return false
        }
    }

    private func finish(_ state: LoadState) {
        lock.lock()
        loadState = state
        lock.unlock()
    }

    /// Keep neighbouring sectors off the same frequency.
    ///
    /// Most sectors' frequencies are synthesized from a hash of the sector id (real
    /// sector frequencies are not published as open data), and there are more sectors
    /// than 25 kHz slots in the enroute band, so two of them landing on the same
    /// frequency is inevitable. Between distant sectors that is harmless; between two
    /// that share a boundary it is not — the hand-off would tell the pilot to switch to
    /// the frequency they are already on. Sectors whose bounding boxes are close enough
    /// to be worked back to back are therefore stepped up the band until they differ.
    ///
    /// Real frequencies the source publishes are never moved: two adjacent Australian
    /// sectors sharing one is a fact about that airspace, not a collision to fix. The
    /// pass runs in file order, which the generator fixes, so the result is identical on
    /// every device and every launch.
    static func deconflictFrequencies(_ sectors: [CenterSector]) -> [CenterSector] {
        var result = sectors
        // Bounded so a pathological input can't spin: the band holds 160 slots, and a
        // sector with more same-frequency neighbours than that is not worth chasing.
        let maximumSteps = 160
        for index in result.indices where result[index].publishedFrequency == nil {
            var frequency = result[index].frequency
            var steps = 0
            while steps < maximumSteps,
                  result[..<index].contains(where: { earlier in
                      earlier.isNeighbour(of: result[index])
                          && abs(earlier.frequency - frequency) < 0.0005
                  }) {
                frequency = CenterSector.nextFrequency(after: frequency)
                steps += 1
            }
            result[index].assignedFrequency = frequency
        }
        return result
    }

    // MARK: - Queries

    var state: LoadState {
        lock.lock(); defer { lock.unlock() }
        return loadState
    }

    var isReady: Bool { state == .ready }

    var count: Int {
        lock.lock(); defer { lock.unlock() }
        return sectors.count
    }

    /// The sector working the given position, or nil when the data isn't loaded yet or
    /// the position falls in a gap between boundaries (the source data does have a few,
    /// mostly over open ocean). A gap is deliberately *not* an error: the caller keeps
    /// working the sector it already has rather than inventing a hand-off.
    func sector(at coordinate: CLLocationCoordinate2D) -> CenterSector? {
        lock.lock(); defer { lock.unlock() }
        guard loadState == .ready else { return nil }
        return sectors.first { $0.contains(coordinate) }
    }

    func sector(id: String) -> CenterSector? {
        lock.lock(); defer { lock.unlock() }
        return sectors.first { $0.id == id }
    }

    /// Provenance of the loaded dataset, for diagnostics.
    var provenance: Provenance? {
        lock.lock(); defer { lock.unlock() }
        return loadedProvenance
    }

    struct Provenance: Equatable {
        let generated: String
        let source: String
        let license: String
        let sectorCount: Int
    }

    // MARK: - File format

    private struct Document: Decodable {
        let schemaVersion: Int
        let generated: String
        let source: String
        let license: String
        let sectors: [CenterSector]
    }
}
