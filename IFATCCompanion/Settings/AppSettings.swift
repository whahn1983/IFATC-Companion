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

/// How the simulated weather-deviation alerts behave. Purely a UI/prompting
/// preference — it never changes the underlying data sources.
enum WeatherDeviationAlertMode: String, CaseIterable, Identifiable {
    case off
    case advisoryOnly
    case advisoryPlusDeviation
    var id: String { rawValue }
    var title: String {
        switch self {
        case .off: return "Off"
        case .advisoryOnly: return "Advisory only"
        case .advisoryPlusDeviation: return "Advisory + suggested deviation"
        }
    }
    /// Whether any weather advisory/banner should be surfaced at all.
    var alertsEnabled: Bool { self != .off }
    /// Whether a suggested deviation (degrees/side) should accompany the advisory.
    var suggestsDeviation: Bool { self == .advisoryPlusDeviation }
}

/// The NOAA radar overlay preference: shown automatically where NOAA provides
/// coverage, or off. No third option — this app never selects a global/commercial
/// radar provider.
enum NOAARadarOverlayMode: String, CaseIterable, Identifiable {
    case autoWhereAvailable
    case off
    var id: String { rawValue }
    var title: String {
        switch self {
        case .autoWhereAvailable: return "Auto where available"
        case .off: return "Off"
        }
    }
}

/// How busy the ambient background radio-chatter frequency sounds. Controls the
/// gap between simulated transmissions (shorter gaps = busier sector).
enum ChatterDensity: String, CaseIterable, Identifiable {
    case light
    case moderate
    case busy
    var id: String { rawValue }
    var title: String {
        switch self {
        case .light: return "Light"
        case .moderate: return "Moderate"
        case .busy: return "Busy"
        }
    }
    /// Random gap (seconds) between the end of one transmission and the start of the
    /// next, as a closed range sampled uniformly.
    var gapRange: ClosedRange<Double> {
        switch self {
        case .light: return 9...22
        case .moderate: return 5...14
        case .busy: return 2...7
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
    /// Voice used for the one-way ATIS broadcast (configurable like the frequencies).
    @Published var voiceATIS: String { didSet { save(voiceATIS, .voiceATIS) } }
    /// Voice used for the pilot's own transmissions (readbacks/requests).
    @Published var voicePilot: String { didSet { save(voicePilot, .voicePilot) } }
    /// Speak the pilot's readbacks/requests aloud when they are triggered by a
    /// button/text tap. Push-to-talk input is never re-spoken (the user already
    /// said it).
    @Published var speakPilot: Bool { didSet { save(speakPilot, .speakPilot) } }
    /// Show the "Hold to Talk" push-to-talk button in the ATC responses card. On by
    /// default; turn off to hide the button for those who don't use voice input and
    /// keep hitting it by accident.
    @Published var holdToTalkEnabled: Bool { didSet { save(holdToTalkEnabled, .holdToTalkEnabled) } }

    /// The configured controller-voice identifier for a facility (empty = fall back to the
    /// default controller voice). Ramp shares the Ground voice (both work the surface);
    /// Clearance uses the default controller voice. Shared by the real-controller speech and
    /// the background chatter so a simulated <facility> is spoken in the same voice as the
    /// <facility> the pilot is actually working.
    func controllerVoiceID(for facility: ATCFacility) -> String {
        switch facility {
        case .ground: return voiceGround
        case .tower: return voiceTower
        case .departure: return voiceDeparture
        case .center: return voiceCenter
        case .approach: return voiceApproach
        case .ramp: return voiceGround
        case .clearance: return defaultVoiceID
        }
    }

    // Phraseology
    @Published var phraseologyMode: PhraseologyMode { didSet { save(phraseologyMode.rawValue, .phraseologyMode) } }
    @Published var digitStyle: CallsignDigitStyle { didSet { save(digitStyle.rawValue, .digitStyle) } }

    // Background radio chatter & Live Activity
    /// Play ambient, randomly-generated background ATC radio chatter — quiet,
    /// static-wrapped transmissions bounded to the frequency the pilot is tuned to.
    /// This is also what keeps the app running (and audio flowing) in the background,
    /// so live callbacks no longer stall when you switch apps or lock the screen.
    @Published var backgroundChatterEnabled: Bool {
        didSet {
            save(backgroundChatterEnabled, .backgroundChatterEnabled)
            // The Live Activity rides on the chatter audio; turning chatter off must
            // turn the notification off too.
            if !isLoading, !backgroundChatterEnabled, liveActivityEnabled {
                liveActivityEnabled = false
            }
        }
    }
    /// Show a live-updating Lock Screen / Dynamic Island notification for the flight
    /// (phase, altitude, heading, controller, weather) with Read Back / Check In
    /// buttons. Requires background chatter, which supplies the continuous audio that
    /// keeps the flight updating while the app is backgrounded.
    @Published var liveActivityEnabled: Bool {
        didSet {
            save(liveActivityEnabled, .liveActivityEnabled)
            // Enabling the Live Activity requires the background chatter that keeps the
            // app (and its live updates) running while backgrounded.
            if !isLoading, liveActivityEnabled, !backgroundChatterEnabled {
                backgroundChatterEnabled = true
            }
        }
    }
    /// Loudness of the background chatter bed (0…1). Deliberately low so it sits under
    /// the real ATC calls.
    @Published var chatterVolume: Double { didSet { save(chatterVolume, .chatterVolume) } }
    /// How busy the simulated chatter frequency sounds.
    @Published var chatterDensity: ChatterDensity { didSet { save(chatterDensity.rawValue, .chatterDensity) } }
    /// Bracket the pilot's own transmissions with a short mic-key / squelch static
    /// burst so keying up sounds like a real radio.
    @Published var transmissionStaticEnabled: Bool { didSet { save(transmissionStaticEnabled, .transmissionStaticEnabled) } }

    // ATC automation
    /// Initial climb height (ft above field) assigned in the clearance/takeoff
    /// before Departure. Added to the departure field elevation and rounded up to
    /// the next thousand for the MSL callout, so it stays valid at high-elevation
    /// airports.
    @Published var initialClimbAltitudeFt: Int { didSet { save(initialClimbAltitudeFt, .initialClimbAltitudeFt) } }
    /// Flight level at which Departure hands off to Center (TRACON ceiling), e.g. 180.
    @Published var traconCeilingFL: Int { didSet { save(traconCeilingFL, .traconCeilingFL) } }
    /// Auto-tune the radio to the next controller when the pilot reads back a frequency
    /// hand-off. On by default: the active frequency follows the hand-off, but only once
    /// the pilot has read it back — never the moment the controller issues it. When off,
    /// nothing tunes on its own; the pilot changes every frequency by hand with the tune
    /// buttons.
    @Published var autoTuneOnHandoff: Bool { didSet { save(autoTuneOnHandoff, .autoTuneOnHandoff) } }
    /// Hand the flight from one enroute Center sector to the next as it crosses the
    /// boundaries — "contact Fort Worth Center on 133.425" leaving Houston's airspace.
    /// On by default. Only ever applies to the enroute leg (from the Departure hand-off
    /// at the TRACON ceiling until Approach takes over); with it off, one generic
    /// "Center" works the whole flight, as before.
    @Published var centerSectorHandoffs: Bool { didSet { save(centerSectorHandoffs, .centerSectorHandoffs) } }

    // Airport surface (OpenStreetMap taxi routing)
    /// Issue the runway-crossing clearances automatically as an OSM taxi route reaches each
    /// hold-short, rather than waiting for the pilot to tap Request Crossing. On by default.
    /// Applied to `AirportSurfaceCoordinator.autoCrossingCalls` by `AppModel`.
    @Published var taxiAutoCrossingCalls: Bool { didSet { save(taxiAutoCrossingCalls, .taxiAutoCrossingCalls) } }
    /// Re-plan the taxi route automatically when the aircraft leaves it, instead of latching
    /// the off-route banner for the pilot to decide. Off by default. Applied to
    /// `AirportSurfaceCoordinator.autoRecalculate` by `AppModel`.
    @Published var taxiAutoRecalculate: Bool { didSet { save(taxiAutoRecalculate, .taxiAutoRecalculate) } }

    // Saved flights
    /// Keep a loaded saved flight up to date as it is flown, so switching away to
    /// another flight (or being killed by the OS) never loses the leg you just flew.
    /// On by default; turn it off to treat a saved flight as a fixed point-in-time
    /// snapshot that only changes when you tap Save.
    @Published var autoSaveFlights: Bool { didSet { save(autoSaveFlights, .autoSaveFlights) } }

    // Weather
    @Published var routeCorridorNM: Double { didSet { save(routeCorridorNM, .routeCorridorNM) } }
    @Published var altitudeBandFt: Double { didSet { save(altitudeBandFt, .altitudeBandFt) } }
    @Published var weatherBaseURL: String { didSet { save(weatherBaseURL, .weatherBaseURL) } }

    // Weather data (NOAA radar precipitation + simulated deviation)
    /// NOAA radar overlay preference (auto where available, or off).
    @Published var noaaRadarOverlay: NOAARadarOverlayMode { didSet { save(noaaRadarOverlay.rawValue, .noaaRadarOverlay) } }
    /// Radar overlay opacity (0…1), default 0.55.
    @Published var radarOpacity: Double { didSet { save(radarOpacity, .radarOpacity) } }
    /// Simulated weather-deviation alert level.
    @Published var weatherDeviationAlerts: WeatherDeviationAlertMode { didSet { save(weatherDeviationAlerts.rawValue, .weatherDeviationAlerts) } }
    /// Opt in to driving the weather-deviation flow (mint reroute line + advisory) from
    /// the **NASA global satellite precipitation estimate** where there is no NOAA/OPERA
    /// radar coverage. Off by default: the estimate is coarse (~10 km), latent (hours),
    /// and cannot reliably grade severity, so it is treated as low confidence and always
    /// labeled "satellite estimate — not radar". When off, satellite coverage still shows
    /// the overlay image but never draws a deviation (radar-only behavior).
    @Published var satelliteDeviationsEnabled: Bool { didSet { save(satelliteDeviationsEnabled, .satelliteDeviationsEnabled) } }
    /// Show data-source labels (e.g. "Radar precipitation data: NOAA/NWS").
    @Published var showWeatherDataSourceLabels: Bool { didSet { save(showWeatherDataSourceLabels, .showWeatherDataSourceLabels) } }
    /// Show coverage/unavailable warnings.
    @Published var showWeatherCoverageWarnings: Bool { didSet { save(showWeatherCoverageWarnings, .showWeatherCoverageWarnings) } }
    /// On a cellular / expensive connection, skip the background EUMETNET OPERA radar
    /// composite downloads (the megabyte-scale source that drives the auto reroute).
    /// The overlay still loads when you open the Weather map. On by default.
    ///
    /// **Currently dormant and hidden from Settings:** OPERA's ORD render is disabled
    /// (see `PrecipitationOverlayService`), so there are no megabyte-scale downloads to
    /// throttle — the remaining NOAA/NASA sources are small. The property and its
    /// network-path plumbing are kept so re-enabling OPERA restores the throttle and
    /// its toggle together.
    @Published var reduceCellularData: Bool { didSet { save(reduceCellularData, .reduceCellularData) } }

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
        voiceATIS = defaults.string(forKey: Key.voiceATIS.rawValue) ?? ""
        voicePilot = defaults.string(forKey: Key.voicePilot.rawValue) ?? ""
        speakPilot = defaults.object(forKey: Key.speakPilot.rawValue) as? Bool ?? true
        holdToTalkEnabled = defaults.object(forKey: Key.holdToTalkEnabled.rawValue) as? Bool ?? true

        phraseologyMode = PhraseologyMode(rawValue: defaults.string(forKey: Key.phraseologyMode.rawValue) ?? "") ?? .faa
        digitStyle = CallsignDigitStyle(rawValue: defaults.string(forKey: Key.digitStyle.rawValue) ?? "") ?? .grouped

        backgroundChatterEnabled = defaults.object(forKey: Key.backgroundChatterEnabled.rawValue) as? Bool ?? false
        liveActivityEnabled = defaults.object(forKey: Key.liveActivityEnabled.rawValue) as? Bool ?? false
        chatterVolume = defaults.object(forKey: Key.chatterVolume.rawValue) as? Double ?? 0.16
        chatterDensity = ChatterDensity(rawValue: defaults.string(forKey: Key.chatterDensity.rawValue) ?? "") ?? .moderate
        transmissionStaticEnabled = defaults.object(forKey: Key.transmissionStaticEnabled.rawValue) as? Bool ?? true

        initialClimbAltitudeFt = defaults.object(forKey: Key.initialClimbAltitudeFt.rawValue) as? Int ?? 5000
        traconCeilingFL = defaults.object(forKey: Key.traconCeilingFL.rawValue) as? Int ?? 180
        autoTuneOnHandoff = defaults.object(forKey: Key.autoTuneOnHandoff.rawValue) as? Bool ?? true
        centerSectorHandoffs = defaults.object(forKey: Key.centerSectorHandoffs.rawValue) as? Bool ?? true

        taxiAutoCrossingCalls = defaults.object(forKey: Key.taxiAutoCrossingCalls.rawValue) as? Bool ?? true
        taxiAutoRecalculate = defaults.object(forKey: Key.taxiAutoRecalculate.rawValue) as? Bool ?? false

        autoSaveFlights = defaults.object(forKey: Key.autoSaveFlights.rawValue) as? Bool ?? true

        routeCorridorNM = defaults.object(forKey: Key.routeCorridorNM.rawValue) as? Double ?? 100
        altitudeBandFt = defaults.object(forKey: Key.altitudeBandFt.rawValue) as? Double ?? 5000
        weatherBaseURL = defaults.string(forKey: Key.weatherBaseURL.rawValue) ?? "https://aviationweather.gov/api/data"

        noaaRadarOverlay = NOAARadarOverlayMode(rawValue: defaults.string(forKey: Key.noaaRadarOverlay.rawValue) ?? "") ?? .autoWhereAvailable
        radarOpacity = defaults.object(forKey: Key.radarOpacity.rawValue) as? Double ?? 0.55
        weatherDeviationAlerts = WeatherDeviationAlertMode(rawValue: defaults.string(forKey: Key.weatherDeviationAlerts.rawValue) ?? "") ?? .advisoryPlusDeviation
        satelliteDeviationsEnabled = defaults.object(forKey: Key.satelliteDeviationsEnabled.rawValue) as? Bool ?? false
        showWeatherDataSourceLabels = defaults.object(forKey: Key.showWeatherDataSourceLabels.rawValue) as? Bool ?? true
        showWeatherCoverageWarnings = defaults.object(forKey: Key.showWeatherCoverageWarnings.rawValue) as? Bool ?? true
        reduceCellularData = defaults.object(forKey: Key.reduceCellularData.rawValue) as? Bool ?? true

        debugLogging = defaults.object(forKey: Key.debugLogging.rawValue) as? Bool ?? true
        mockMode = defaults.object(forKey: Key.mockMode.rawValue) as? Bool ?? true

        // One-time migration: the radio voice effect ships ON by default. Fresh installs
        // already default `transmissionStaticEnabled` to true above; this additionally
        // flips it on once for installs that persisted it OFF during earlier testing, so
        // the release is on-by-default for everyone. After this runs, the user's own
        // on/off choice sticks. (Persisted directly since `save()` is disabled while
        // `isLoading` is still true here.)
        if defaults.object(forKey: Key.radioEffectDefaultMigration.rawValue) == nil {
            transmissionStaticEnabled = true
            defaults.set(true, forKey: Key.transmissionStaticEnabled.rawValue)
            defaults.set(true, forKey: Key.radioEffectDefaultMigration.rawValue)
        }

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
        voiceApproach = other.voiceApproach; voiceATIS = other.voiceATIS
        voicePilot = other.voicePilot; speakPilot = other.speakPilot
        holdToTalkEnabled = other.holdToTalkEnabled
        phraseologyMode = other.phraseologyMode; digitStyle = other.digitStyle
        backgroundChatterEnabled = other.backgroundChatterEnabled
        liveActivityEnabled = other.liveActivityEnabled
        chatterVolume = other.chatterVolume; chatterDensity = other.chatterDensity
        transmissionStaticEnabled = other.transmissionStaticEnabled
        initialClimbAltitudeFt = other.initialClimbAltitudeFt
        traconCeilingFL = other.traconCeilingFL
        autoTuneOnHandoff = other.autoTuneOnHandoff
        centerSectorHandoffs = other.centerSectorHandoffs
        taxiAutoCrossingCalls = other.taxiAutoCrossingCalls
        taxiAutoRecalculate = other.taxiAutoRecalculate
        autoSaveFlights = other.autoSaveFlights
        routeCorridorNM = other.routeCorridorNM; altitudeBandFt = other.altitudeBandFt
        weatherBaseURL = other.weatherBaseURL
        noaaRadarOverlay = other.noaaRadarOverlay; radarOpacity = other.radarOpacity
        weatherDeviationAlerts = other.weatherDeviationAlerts
        satelliteDeviationsEnabled = other.satelliteDeviationsEnabled
        showWeatherDataSourceLabels = other.showWeatherDataSourceLabels
        showWeatherCoverageWarnings = other.showWeatherCoverageWarnings
        reduceCellularData = other.reduceCellularData
        debugLogging = other.debugLogging; mockMode = other.mockMode
        isLoading = false
    }

    // MARK: - Persistence

    private enum Key: String, CaseIterable {
        case host, port, autoDiscover, keepScreenAwake
        case callsign, airline, flightNumber, departure, destination, alternate
        case cruiseAltitude, runway, sid, star, approach, departureGate, arrivalGate
        case voiceEnabled, defaultVoiceID, speechRate, speechPitch, voiceVolume, respectSilentSwitch
        case voiceGround, voiceTower, voiceDeparture, voiceCenter, voiceApproach, voiceATIS
        case voicePilot, speakPilot, holdToTalkEnabled
        case phraseologyMode, digitStyle
        case backgroundChatterEnabled, liveActivityEnabled, chatterVolume, chatterDensity, transmissionStaticEnabled
        case radioEffectDefaultMigration
        case initialClimbAltitudeFt, traconCeilingFL, autoTuneOnHandoff, centerSectorHandoffs
        case taxiAutoCrossingCalls, taxiAutoRecalculate
        case autoSaveFlights
        case routeCorridorNM, altitudeBandFt, weatherBaseURL
        case noaaRadarOverlay, radarOpacity, weatherDeviationAlerts, satelliteDeviationsEnabled
        case showWeatherDataSourceLabels, showWeatherCoverageWarnings, reduceCellularData
        case debugLogging, mockMode
    }

    private func save(_ value: Any, _ key: Key) {
        guard !isLoading else { return }
        defaults.set(value, forKey: key.rawValue)
    }
}
