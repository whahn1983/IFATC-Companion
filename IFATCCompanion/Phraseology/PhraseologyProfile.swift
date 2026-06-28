import Foundation

/// A single overridable controller call template. `display` is shown in the
/// transcript (normal digits); `spoken` is fed to the speech synthesizer
/// (phonetic). Both support `{placeholder}` tokens substituted at render time.
struct PhraseologyTemplate: Codable, Equatable {
    var display: String
    var spoken: String
}

/// The controller calls a user profile may override. Each key documents the
/// placeholders available to its template so the editor can guide the user.
enum PhraseologyTemplateKey: String, Codable, CaseIterable, Identifiable {
    case clearance
    case taxiToRunway
    case takeoff
    case landing

    var id: String { rawValue }

    var title: String {
        switch self {
        case .clearance: return "IFR Clearance"
        case .taxiToRunway: return "Taxi to Runway"
        case .takeoff: return "Cleared for Takeoff"
        case .landing: return "Cleared to Land"
        }
    }

    /// Placeholder tokens supported by this template (without braces).
    var placeholders: [String] {
        switch self {
        case .clearance:
            return ["callsign", "dest", "sid", "initialAlt", "cruise", "depFreq", "squawk"]
        case .taxiToRunway:
            return ["callsign", "runway", "via", "crossing"]
        case .takeoff, .landing:
            return ["callsign", "runway", "wind"]
        }
    }

    /// A starting-point template the editor can pre-fill (mirrors built-in FAA wording).
    var defaultTemplate: PhraseologyTemplate {
        switch self {
        case .clearance:
            return PhraseologyTemplate(
                display: "{callsign}, cleared to {dest} via {sid}, climb via SID except maintain {initialAlt}, expect {cruise} one zero minutes after departure, departure frequency {depFreq}, squawk {squawk}.",
                spoken: "{callsign}, cleared to {dest} via {sid}, climb via SID except maintain {initialAlt}, expect {cruise} one zero minutes after departure, departure frequency {depFreq}, {squawk}.")
        case .taxiToRunway:
            return PhraseologyTemplate(
                display: "{callsign}, taxi to runway {runway} via {via}{crossing}.",
                spoken: "{callsign}, taxi to runway {runway} via {via}{crossing}.")
        case .takeoff:
            return PhraseologyTemplate(
                display: "{callsign}, wind {wind}, runway {runway}, cleared for takeoff.",
                spoken: "{callsign}, {wind}, runway {runway}, cleared for takeoff.")
        case .landing:
            return PhraseologyTemplate(
                display: "{callsign}, wind {wind}, runway {runway}, cleared to land.",
                spoken: "{callsign}, {wind}, runway {runway}, cleared to land.")
        }
    }
}

/// A user-created phraseology profile: a named set of call-template overrides plus
/// an airline call-set map (designator/name -> spoken radio telephony name). Fully
/// `Codable` so profiles can be shared as plain JSON. Deterministic — no AI.
struct PhraseologyProfile: Codable, Equatable, Identifiable {
    var id: UUID
    var name: String
    /// Keyed by `PhraseologyTemplateKey.rawValue`.
    var templates: [String: PhraseologyTemplate]
    /// Keyed by uppercased airline designator/name -> spoken radio name.
    var airlineCallSets: [String: String]

    init(id: UUID = UUID(),
         name: String,
         templates: [String: PhraseologyTemplate] = [:],
         airlineCallSets: [String: String] = [:]) {
        self.id = id
        self.name = name
        self.templates = templates
        self.airlineCallSets = airlineCallSets
    }

    func template(for key: PhraseologyTemplateKey) -> PhraseologyTemplate? {
        templates[key.rawValue]
    }

    func airlineCallName(for airline: String) -> String? {
        let key = airline.uppercased().trimmingCharacters(in: .whitespaces)
        guard !key.isEmpty else { return nil }
        return airlineCallSets[key]
    }

    /// An example profile users can duplicate as a starting point.
    static func example() -> PhraseologyProfile {
        PhraseologyProfile(
            name: "Custom Example",
            templates: [
                PhraseologyTemplateKey.takeoff.rawValue: PhraseologyTemplateKey.takeoff.defaultTemplate
            ],
            airlineCallSets: ["DLH": "Lufthansa", "BAW": "Speedbird"])
    }
}
