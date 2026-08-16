import Foundation
import Combine

/// One saved flight: a whole-session `SessionSnapshot` under a name the pilot can
/// pick from a list.
///
/// This is the deliberate flight *library*, separate from the single auto-resume
/// snapshot `SessionStateStore` keeps. The auto-resume snapshot answers "the app was
/// killed, put me back where I was"; a saved flight answers "I have three flights on
/// the go — give me that one". Both use the same snapshot type, so anything the
/// reconnect path restores, a saved flight restores too.
struct SavedFlight: Codable, Identifiable {
    var id = UUID()
    /// Display name, `DEP-DEST` with a `-1`, `-2` suffix when the same route is saved
    /// more than once (see `SavedFlightStore.makeName(for:)`).
    var name: String
    /// When this slot was last written — the explicit save, or the most recent
    /// auto-save while the flight was loaded. Orders the list, newest first.
    var savedAt: Date
    var snapshot: SessionSnapshot

    /// Where the flight had got to, for the list's subtitle ("Cruise", "On approach").
    var stateTitle: String { snapshot.atcState.title }
    /// The controller being worked when the flight was put away.
    var facilityTitle: String { snapshot.currentFacility.title }
}

/// Stores the pilot's saved flights as a single JSON file in Application Support, and
/// remembers which one (if any) the live session is currently flying.
///
/// Application Support rather than `UserDefaults`: a flight carries its transcript and
/// diagnostics log, and several of them together are far past what belongs in a
/// preferences plist that is read into memory wholesale at launch.
@MainActor
final class SavedFlightStore: ObservableObject {

    /// Saved flights, newest first.
    @Published private(set) var flights: [SavedFlight] = []
    /// The saved flight the live session is currently flying, if it was loaded from
    /// (or saved into) the library. Auto-save writes here.
    @Published private(set) var activeFlightID: UUID?
    /// Set when the library could not be read or written, so the app can surface it in
    /// Diagnostics rather than failing silently.
    @Published private(set) var lastError: String?

    private let fileURL: URL
    private let defaults: UserDefaults
    private let activeKey = "savedFlights.activeID"

    /// The on-disk envelope, versioned so a future format change can migrate rather
    /// than discard.
    private struct Library: Codable {
        var version = 1
        var flights: [SavedFlight] = []
    }

    /// - Parameters:
    ///   - directory: where `SavedFlights.json` lives. Defaults to Application Support;
    ///     tests pass a temporary directory.
    ///   - defaults: backs the active-flight binding only (a single UUID string).
    init(directory: URL? = nil, defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let dir = directory ?? Self.defaultDirectory()
        fileURL = dir.appendingPathComponent("SavedFlights.json")
        if let id = defaults.string(forKey: activeKey) { activeFlightID = UUID(uuidString: id) }
        load()
    }

    private static func defaultDirectory() -> URL {
        let fm = FileManager.default
        if let url = try? fm.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                 appropriateFor: nil, create: true) {
            return url
        }
        return URL(fileURLWithPath: NSTemporaryDirectory())
    }

    // MARK: - Reading

    var activeFlight: SavedFlight? {
        guard let activeFlightID else { return nil }
        return flights.first { $0.id == activeFlightID }
    }

    func flight(id: UUID) -> SavedFlight? { flights.first { $0.id == id } }

    /// Load the library from disk. A file that cannot be decoded is left untouched —
    /// the list simply comes up empty and `lastError` explains why, rather than the
    /// next save overwriting flights that might still be recoverable by hand.
    private func load() {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return }
        do {
            let data = try Data(contentsOf: fileURL)
            let library = try JSONDecoder().decode(Library.self, from: data)
            flights = library.flights.sorted { $0.savedAt > $1.savedAt }
        } catch {
            lastError = "Could not read saved flights: \(error.localizedDescription)"
        }
    }

    // MARK: - Writing

    /// A name for a new slot: `DEP-DEST`, with `-1`, `-2`… appended when that route is
    /// already saved, so flying the same route twice never collapses into one entry.
    func makeName(for snapshot: SessionSnapshot) -> String {
        let base = snapshot.routeName
        let taken = Set(flights.map(\.name))
        guard taken.contains(base) else { return base }
        var suffix = 1
        while taken.contains("\(base)-\(suffix)") { suffix += 1 }
        return "\(base)-\(suffix)"
    }

    /// Save a snapshot as a new flight and make it the active one, so subsequent
    /// auto-saves keep it current.
    @discardableResult
    func save(_ snapshot: SessionSnapshot, name: String? = nil) -> SavedFlight {
        let flight = SavedFlight(name: name ?? makeName(for: snapshot),
                                 savedAt: Date(),
                                 snapshot: snapshot)
        flights.insert(flight, at: 0)
        setActive(flight.id)
        persist()
        return flight
    }

    /// Overwrite an existing slot in place, keeping its name and identity. Used by the
    /// auto-save so the loaded flight stays current as it is flown. No-op when the slot
    /// has since been deleted — a deleted flight must not resurrect itself.
    func update(id: UUID, snapshot: SessionSnapshot) {
        guard let index = flights.firstIndex(where: { $0.id == id }) else { return }
        flights[index].snapshot = snapshot
        flights[index].savedAt = Date()
        // Keep the list ordered by most recently flown.
        let flight = flights.remove(at: index)
        flights.insert(flight, at: 0)
        persist()
    }

    func delete(id: UUID) {
        flights.removeAll { $0.id == id }
        if activeFlightID == id { setActive(nil) }
        persist()
    }

    /// Bind (or unbind) the live session to a slot. Persisted so the binding survives a
    /// relaunch, which is what lets auto-save keep writing into the right flight after
    /// the app is killed and reopened.
    func setActive(_ id: UUID?) {
        activeFlightID = id
        if let id {
            defaults.set(id.uuidString, forKey: activeKey)
        } else {
            defaults.removeObject(forKey: activeKey)
        }
    }

    private func persist() {
        do {
            let data = try JSONEncoder().encode(Library(flights: flights))
            try data.write(to: fileURL, options: .atomic)
            lastError = nil
        } catch {
            lastError = "Could not save flights: \(error.localizedDescription)"
        }
    }
}
