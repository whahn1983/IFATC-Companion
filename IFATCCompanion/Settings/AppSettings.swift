import Foundation
import Combine

enum PhraseologyMode: String, CaseIterable, Identifiable {
    case faa
    case icao
    var id: String { rawValue }
    var title: String {
        switch self {
        case .faa: return "FAA / US"
        case .icao: return "ICAO"
        }
    }

    /// Short description of the pack's distinguishing conventions.
    var detail: String {
        switch self {
        case .faa: return "US digits, \"point\" frequencies, inHg altimeter."
        case .icao: return "\"tree/fower/fife\" digits, \"decimal\" frequencies, QNH in hPa."
        }
    }
}

/// How airline flight numbers are spoken (e.g. "twelve thirty four" vs "one two three four").
enum CallsignDigitStyle: String, CaseIterable, Identifiable {
    case grouped     // 1234 -> "twelve thirty four"
    case individual  // 1234 -> "one two three four"
    var id: String { rawValue }
    var title: String {
        switch self {
        case .grouped: return "Grouped (twelve thirty four)"
        case .individual: return "Individual (one two three four)"
        }
    }
}

/// Centralised, persisted user preferences. Backed by `UserDefaults`.
/// Exposed as an `ObservableObject` so SwiftUI views update on change.
final class AppSettings: ObservableObject {

    private let defaults: UserDefaults
    private var isLoading = false

    // Connection
    @Published var host: String { didSet { save(host, .host) } }
    @Published var port: Int { didSet { save(port, .port) } }
    @Published var autoDiscover: Bool { didSet { save(autoDiscover, .autoDiscover) } }
    /// Keep the screen awake while the app is open. Infinite Flight drops the
    /// Connect link when the companion device's screen locks, so this defaults on.
    @Published var keepScreenAwake: Bool { didSet { save(keepScreenAwake, .keepScreenAwake) } }

    // Manual flight overrides
    @Published var callsign: String { didSet { save(callsign, .callsign) } }
    @Published var airline: String { didSet { save(airline, .airline) } }
    @Published var flightNumber: String { didSet { save(flightNumber, .flightNumber) } }
    @Published var departure: String { didSet { save(departure, .departure) } }
    @Published var destination: String { didSet { save(destination, .destination) } }
    @Published var alternate: String { didSet { save(alternate, .alternate) } }
    @Published var cruiseAltitude: Int { didSet { save(cruiseAltitude, .cruiseAltitude) } }
    @Published var runway: String { didSet { save(runway, .runway) } }
    @Published var sid: String { didSet { save(sid, .sid) } }
    @Published var star: String { didSet { save(star, .star) } }
    @Published var approach: String { didSet { save(approach, .approach) } }
    /// Departure gate / stand the pushback is requested from (manual-override only;
    /// IF doesn't expose it).
    @Published var departureGate: String { didSet { save(departureGate, .departureGate) } }
    /// Arrival gate / stand to taxi to (manual-override only; IF doesn't expose it).
    @Published var arrivalGate: String { didSet { save(arrivalGate, .arrivalGate) } }

    // Voice
    @Published var voiceEnabled: Bool { didSet { save(voiceEnabled, .voiceEnabled) } }
    @Published var defaultVoiceID: String { didSet { save(defaultVoiceID, .defaultVoiceID) } }
    @Published var speechRate: Double { didSet { save(speechRate, .speechRate) } }
    @Published var speechPitch: Double { didSet { save(speechPitch, .speechPitch) } }
    /// Voice playback volume (0…1) applied to every spoken transmission. Kept
    /// independent of the device volume so it stays consistent across PTT/system
    /// audio interruptions.
    @Published var voiceVolume: Double { didSet { save(voiceVolume, .voiceVolume) } }
    @Published var respectSilentSwitch: Bool { didSet { save(respectSilentSwitch, .respectSilentSwitch) } }
    @Published var voiceGround: String { didSet { save(voiceGround, .voiceGround) } }
    @Published var voiceTower: String { didSet { save(voiceTower, .voiceTower) } }
    @Published var voiceDeparture: String { didSet { save(voiceDeparture, .voiceDeparture) } }
    @Published var voiceCenter: String { didSet { save(voiceCenter, .voiceCenter) } }
    @Published var voiceApproach: String { didSet { save(voiceApproach, .voiceApproach) } }
    /// Voice used for the pilot's own transmissions (readbacks/requests).
    @Published var voicePilot: String { didSet { save(voicePilot, .voicePilot) } }
    /// Speak the pilot's readbacks/requests aloud when they are triggered by a
    /// button/text tap. Push-to-talk input is never re-spoken (the user already
    /// said it).
    @Published var speakPilot: Bool { didSet { save(speakPilot, .speakPilot) } }

    // Phraseology
    @Published var phraseologyMode: PhraseologyMode { didSet { save(phraseologyMode.rawValue, .phraseologyMode) } }
    @Published var digitStyle: CallsignDigitStyle { didSet { save(digitStyle.rawValue, .digitStyle) } }

    // ATC automation
    /// Initial climb altitude (ft) assigned in the clearance/takeoff before Departure.
    @Published var initialClimbAltitudeFt: Int { didSet { save(initialClimbAltitudeFt, .initialClimbAltitudeFt) } }
    /// Flight level at which Departure hands off to Center (TRACON ceiling), e.g. 180.
    @Published var traconCeilingFL: Int { didSet { save(traconCeilingFL, .traconCeilingFL) } }

    // Weather
    @Published var routeCorridorNM: Double { didSet { save(routeCorridorNM, .routeCorridorNM) } }
    @Published var altitudeBandFt: Double { didSet { save(altitudeBandFt, .altitudeBandFt) } }
    @Published var weatherBaseURL: String { didSet { save(weatherBaseURL, .weatherBaseURL) } }

    // Diagnostics / dev
    @Published var debugLogging: Bool { didSet { save(debugLogging, .debugLogging) } }
    @Published var mockMode: Bool { didSet { save(mockMode, .mockMode) } }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        isLoading = true

        host = defaults.string(forKey: Key.host.rawValue) ?? ""
        port = defaults.object(forKey: Key.port.rawValue) as? Int ?? 10112
        autoDiscover = defaults.object(forKey: Key.autoDiscover.rawValue) as? Bool ?? true
        keepScreenAwake = defaults.object(forKey: Key.keepScreenAwake.rawValue) as? Bool ?? true

        callsign = defaults.string(forKey: Key.callsign.rawValue) ?? ""
        airline = defaults.string(forKey: Key.airline.rawValue) ?? ""
        flightNumber = defaults.string(forKey: Key.flightNumber.rawValue) ?? ""
        departure = defaults.string(forKey: Key.departure.rawValue) ?? ""
        destination = defaults.string(forKey: Key.destination.rawValue) ?? ""
        alternate = defaults.string(forKey: Key.alternate.rawValue) ?? ""
        cruiseAltitude = defaults.object(forKey: Key.cruiseAltitude.rawValue) as? Int ?? 0
        runway = defaults.string(forKey: Key.runway.rawValue) ?? ""
        sid = defaults.string(forKey: Key.sid.rawValue) ?? ""
        star = defaults.string(forKey: Key.star.rawValue) ?? ""
        approach = defaults.string(forKey: Key.approach.rawValue) ?? ""
        departureGate = defaults.string(forKey: Key.departureGate.rawValue) ?? ""
        // Migrate the pre-split single "gate" key into the arrival gate.
        arrivalGate = defaults.string(forKey: Key.arrivalGate.rawValue)
            ?? defaults.string(forKey: "gate") ?? ""

        voiceEnabled = defaults.object(forKey: Key.voiceEnabled.rawValue) as? Bool ?? true
        defaultVoiceID = defaults.string(forKey: Key.defaultVoiceID.rawValue) ?? ""
        speechRate = defaults.object(forKey: Key.speechRate.rawValue) as? Double ?? 0.5
        speechPitch = defaults.object(forKey: Key.speechPitch.rawValue) as? Double ?? 1.0
        voiceVolume = defaults.object(forKey: Key.voiceVolume.rawValue) as? Double ?? 1.0
        respectSilentSwitch = defaults.object(forKey: Key.respectSilentSwitch.rawValue) as? Bool ?? false
        voiceGround = defaults.string(forKey: Key.voiceGround.rawValue) ?? ""
        voiceTower = defaults.string(forKey: Key.voiceTower.rawValue) ?? ""
        voiceDeparture = defaults.string(forKey: Key.voiceDeparture.rawValue) ?? ""
        voiceCenter = defaults.string(forKey: Key.voiceCenter.rawValue) ?? ""
        voiceApproach = defaults.string(forKey: Key.voiceApproach.rawValue) ?? ""
        voicePilot = defaults.string(forKey: Key.voicePilot.rawValue) ?? ""
        speakPilot = defaults.object(forKey: Key.speakPilot.rawValue) as? Bool ?? true

        phraseologyMode = PhraseologyMode(rawValue: defaults.string(forKey: Key.phraseologyMode.rawValue) ?? "") ?? .faa
        digitStyle = CallsignDigitStyle(rawValue: defaults.string(forKey: Key.digitStyle.rawValue) ?? "") ?? .grouped

        initialClimbAltitudeFt = defaults.object(forKey: Key.initialClimbAltitudeFt.rawValue) as? Int ?? 5000
        traconCeilingFL = defaults.object(forKey: Key.traconCeilingFL.rawValue) as? Int ?? 180

        routeCorridorNM = defaults.object(forKey: Key.routeCorridorNM.rawValue) as? Double ?? 100
        altitudeBandFt = defaults.object(forKey: Key.altitudeBandFt.rawValue) as? Double ?? 5000
        weatherBaseURL = defaults.string(forKey: Key.weatherBaseURL.rawValue) ?? "https://aviationweather.gov/api/data"

        debugLogging = defaults.object(forKey: Key.debugLogging.rawValue) as? Bool ?? true
        mockMode = defaults.object(forKey: Key.mockMode.rawValue) as? Bool ?? true

        isLoading = false
    }

    /// Reset all stored preferences to defaults.
    func resetAll() {
        for key in Key.allCases { defaults.removeObject(forKey: key.rawValue) }
        let fresh = AppSettings(defaults: defaults)
        copy(from: fresh)
    }

    private func copy(from other: AppSettings) {
        isLoading = true
        host = other.host; port = other.port; autoDiscover = other.autoDiscover
        keepScreenAwake = other.keepScreenAwake
        callsign = other.callsign; airline = other.airline; flightNumber = other.flightNumber
        departure = other.departure; destination = other.destination; alternate = other.alternate
        cruiseAltitude = other.cruiseAltitude; runway = other.runway
        sid = other.sid; star = other.star; approach = other.approach
        departureGate = other.departureGate; arrivalGate = other.arrivalGate
        voiceEnabled = other.voiceEnabled; defaultVoiceID = other.defaultVoiceID
        speechRate = other.speechRate; speechPitch = other.speechPitch
        voiceVolume = other.voiceVolume
        respectSilentSwitch = other.respectSilentSwitch
        voiceGround = other.voiceGround; voiceTower = other.voiceTower
        voiceDeparture = other.voiceDeparture; voiceCenter = other.voiceCenter
        voiceApproach = other.voiceApproach
        voicePilot = other.voicePilot; speakPilot = other.speakPilot
        phraseologyMode = other.phraseologyMode; digitStyle = other.digitStyle
        initialClimbAltitudeFt = other.initialClimbAltitudeFt
        traconCeilingFL = other.traconCeilingFL
        routeCorridorNM = other.routeCorridorNM; altitudeBandFt = other.altitudeBandFt
        weatherBaseURL = other.weatherBaseURL
        debugLogging = other.debugLogging; mockMode = other.mockMode
        isLoading = false
    }

    // MARK: - Persistence

    private enum Key: String, CaseIterable {
        case host, port, autoDiscover, keepScreenAwake
        case callsign, airline, flightNumber, departure, destination, alternate
        case cruiseAltitude, runway, sid, star, approach, departureGate, arrivalGate
        case voiceEnabled, defaultVoiceID, speechRate, speechPitch, voiceVolume, respectSilentSwitch
        case voiceGround, voiceTower, voiceDeparture, voiceCenter, voiceApproach
        case voicePilot, speakPilot
        case phraseologyMode, digitStyle
        case initialClimbAltitudeFt, traconCeilingFL
        case routeCorridorNM, altitudeBandFt, weatherBaseURL
        case debugLogging, mockMode
    }

    private func save(_ value: Any, _ key: Key) {
        guard !isLoading else { return }
        defaults.set(value, forKey: key.rawValue)
    }
}
