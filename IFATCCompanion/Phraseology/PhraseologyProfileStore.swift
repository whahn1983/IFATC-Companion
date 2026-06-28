import Foundation
import Combine

/// Persists user-created phraseology profiles and tracks the active one.
/// Profiles are stored as JSON in `UserDefaults`; the active selection is a
/// stored UUID string. Fully local — no accounts, no network. Profiles can be
/// exported/imported as plain JSON text for sharing.
@MainActor
final class PhraseologyProfileStore: ObservableObject {

    @Published private(set) var profiles: [PhraseologyProfile] = []
    @Published var activeProfileID: UUID? { didSet { persistActiveID() } }

    private let defaults: UserDefaults
    private let profilesKey = "phraseologyProfiles"
    private let activeKey = "phraseologyActiveProfileID"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        load()
    }

    /// The active profile, if any is selected and still exists.
    var activeProfile: PhraseologyProfile? {
        guard let id = activeProfileID else { return nil }
        return profiles.first { $0.id == id }
    }

    // MARK: - CRUD

    func add(_ profile: PhraseologyProfile) {
        profiles.append(profile)
        persistProfiles()
    }

    func update(_ profile: PhraseologyProfile) {
        guard let idx = profiles.firstIndex(where: { $0.id == profile.id }) else { return }
        profiles[idx] = profile
        persistProfiles()
    }

    func delete(_ profile: PhraseologyProfile) {
        profiles.removeAll { $0.id == profile.id }
        if activeProfileID == profile.id { activeProfileID = nil }
        persistProfiles()
    }

    /// Create a fresh, empty profile with a unique default name and return it.
    @discardableResult
    func createNew(named name: String? = nil) -> PhraseologyProfile {
        let base = name ?? "New Profile"
        var candidate = base
        var n = 2
        while profiles.contains(where: { $0.name == candidate }) {
            candidate = "\(base) \(n)"; n += 1
        }
        let profile = PhraseologyProfile(name: candidate)
        add(profile)
        return profile
    }

    // MARK: - Import / Export

    /// Export a profile as pretty-printed JSON for sharing.
    func exportJSON(_ profile: PhraseologyProfile) -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? encoder.encode(profile),
              let json = String(data: data, encoding: .utf8) else { return "" }
        return json
    }

    /// Import a profile from JSON text. A new id is assigned to avoid clobbering
    /// an existing profile; the name is de-duplicated. Returns the imported
    /// profile, or nil if the JSON could not be decoded.
    @discardableResult
    func importJSON(_ json: String) -> PhraseologyProfile? {
        guard let data = json.data(using: .utf8),
              var profile = try? JSONDecoder().decode(PhraseologyProfile.self, from: data) else { return nil }
        profile.id = UUID()
        if profiles.contains(where: { $0.name == profile.name }) {
            profile.name += " (Imported)"
        }
        add(profile)
        return profile
    }

    // MARK: - Persistence

    private func load() {
        if let data = defaults.data(forKey: profilesKey),
           let decoded = try? JSONDecoder().decode([PhraseologyProfile].self, from: data) {
            profiles = decoded
        }
        if let idString = defaults.string(forKey: activeKey), let id = UUID(uuidString: idString) {
            activeProfileID = id
        }
    }

    private func persistProfiles() {
        if let data = try? JSONEncoder().encode(profiles) {
            defaults.set(data, forKey: profilesKey)
        }
    }

    private func persistActiveID() {
        if let id = activeProfileID {
            defaults.set(id.uuidString, forKey: activeKey)
        } else {
            defaults.removeObject(forKey: activeKey)
        }
    }
}
