import Foundation
import Combine
import CoreLocation
import CoreGraphics
import MapKit
import Network
#if canImport(UIKit)
import UIKit
#endif

/// A pilot response-button action. Used to surface only the buttons that make
/// sense for the controller the pilot is currently tuned to and the phase of
/// flight, instead of showing every button all the time.
enum PilotAction: CaseIterable {
    case clearance, pushback, engineStart, taxi, ready, takeoff
    case requestHigher, requestLower, vectors, approach, rideReport, destWx, checkIn
    case toGate
}

/// A pilot response-button action for the simulated weather-deviation flow. Kept
/// separate from `PilotAction` so the existing gate-to-gate button logic is
/// untouched; surfaced only while a route-weather conflict / deviation is active.
enum WeatherDeviationAction: CaseIterable {
    case askCenter
    case requestRightDeviation
    case requestLeftDeviation
    case requestVector
    case requestHigher
    case requestLower
    case clearOfWeather
    case continueOnCourse
    case sayAgain
}

/// Central coordinator. Owns all services, holds the published app state the UI
/// renders, and turns live/mock aircraft state into deterministic ATC dialogue.
@MainActor
final class AppModel: ObservableObject {

    // Services (injected into the SwiftUI environment by the app root).
    let settings: AppSettings
    let diagnostics: DiagnosticsStore
    let speech: SpeechService
    let connect: IFConnectManager
    let mock: MockSimulatorFeed
    let phraseologyProfiles: PhraseologyProfileStore
    let speechRecognizer: SpeechRecognitionService
    /// Owns the StoreKit subscription state. Live Connected Mode is available
    /// only while `entitlements.hasLiveAccess` is true; otherwise the app is
    /// locked to Mock Mode.
    let entitlements: EntitlementManager

    private let weatherService: AviationWeatherService

    // Deterministic engines.
    private var engine: PhraseologyEngine
    private var stateMachine: ATCStateMachine
    private var pilotEngine: PilotResponseEngine
    private var rampEngine: RampPhraseologyEngine
    private var phaseDetector = PhaseDetector()
    private let taxiPlanner = TaxiRoutePlanner()
    private let intentParser = PilotIntentParser()
    private let lineupDetector = RunwayLineupDetector()
    private var routeAnalyzer = WeatherRouteAnalyzer()
    private var turbulenceModel = TurbulenceModel()
    private var rideEngine: RideReportEngine
    /// Precipitation overlay provider selector (NOAA → OPERA → NASA, or mock).
    private let precipService = PrecipitationOverlayService()
    /// Pure route-weather conflict detector.
    private var conflictDetector = RouteWeatherConflictDetector()
    /// Simulated weather-deviation flow coordinator (rebuilt with the engine).
    private var deviationEngine: WeatherDeviationEngine
    private let airports = AirportDatabase.shared
    private let runways = RunwayDatabase.shared

    // Published UI state.
    @Published var aircraftState = AircraftState.empty
    @Published var flightPlan = FlightPlan.empty
    @Published var phase: FlightPhase = .preflight
    @Published var atcState: ATCState = .notConnected
    // Clearance Delivery is the first controller a flight calls, so the radio starts
    // tuned there (not Ground) at the gate.
    @Published var currentFacility: ATCFacility = .clearance
    @Published var transcript: [ATCTransmission] = []
    @Published var latestTransmission: ATCTransmission?
    @Published var phaseDebug = PhaseDetector.Debug()
    @Published var assignedAltitude: Int = 0

    // Multiplayer / human-ATC staffing. When a human controller is detected the
    // companion stands by (stops generating controller calls).
    @Published var liveATC: LiveATCStatus = .none
    /// Mock-mode demo toggle to exercise the "step aside" behavior in the Simulator.
    @Published var simulateStaffedATC: Bool = false

    /// Whether the companion should defer to a human controller right now. The guard is
    /// per-frequency and location-aware: in live mode it applies only while the pilot's
    /// tuned COM frequency is a staffed human controller (read from Infinite Flight), so
    /// tuning to UNICOM, ATIS, or an unstaffed field lifts it and the companion resumes
    /// covering that sector. In mock mode it follows the demo toggle against the tuned
    /// facility so the standby behavior can be exercised in the Simulator.
    var companionStandby: Bool {
        if settings.mockMode {
            return simulateStaffedATC && currentFacility.isFAAATC
        }
        return liveATC.companionShouldStandBy
    }

    /// Whether the pre-departure ground actions (clearance → pushback → engine
    /// start → taxi → ready) should be offered. True until the first departure.
    var isPreDeparture: Bool { !hasDeparted }

    /// The runway in use for the current phase (departure or arrival end), resolved
    /// from the flight plan / approach / wind. Empty only when nothing is known yet.
    var activeRunwayDisplay: String { buildContext(for: atcState).runway }

    /// Whether the simulated arrival Ramp (taxi-to-gate) flow applies right now: the
    /// aircraft has departed and is back on the ground arriving, not yet parked.
    var isArrivalRamp: Bool {
        hasDeparted && atcState != .parked
            && ([.landing, .taxiIn, .parked].contains(phase)
                || [.runwayExit, .groundArrival].contains(atcState))
    }

    /// Whether the "Ramp" button should be live: pushback before departure, or the
    /// taxi-to-gate hand-off on arrival — but never once parked.
    var canContactRamp: Bool {
        guard atcState != .parked else { return false }
        return isPreDeparture || isArrivalRamp
    }

    // MARK: - Response-button visibility
    //
    // The response buttons are tied to the controller the pilot is currently tuned
    // to (`currentFacility`) and gated by the phase of flight, so only the calls
    // that make sense right now are shown — e.g. Clearance at the gate, push/start
    // on Ramp, taxi on Ground, takeoff on Tower, and the enroute/arrival requests on
    // their respective controllers. This drives the ATC view's button grid.

    /// The response-button actions to surface right now, keyed off the tuned facility
    /// and the flight phase.
    var availableActions: Set<PilotAction> {
        // Defer entirely to a human controller when one is staffing the position.
        if companionStandby { return [] }

        if isPreDeparture {
            switch currentFacility {
            case .clearance:
                // Offer the IFR clearance until it's issued. The push is NOT offered
                // here — the clearance ends by telling the pilot to contact Ramp (or
                // Ground) for the pushback, so the Pushback button appears only after
                // they tune that frequency, never under Clearance.
                let beforeClearance = stateMachine.current == .notConnected
                    || stateMachine.current == .connectedIdle
                return beforeClearance ? [.clearance] : []
            case .ramp:
                // Tuning to Ramp does not transmit, so the push must be requested
                // here. Before the push: offer Pushback. After it: engine start, then
                // a taxi request hands off to Ground (handled in requestTaxi).
                if [.notConnected, .connectedIdle, .clearance].contains(stateMachine.current) {
                    return [.pushback]
                }
                return [.engineStart, .taxi]
            case .ground:
                // On Ground only the taxi is requested. "Ready for departure" is a
                // Tower call (it addresses Tower while holding short), so it is offered
                // only once the pilot has tuned Tower — never on Ground. The exception
                // is a no-ramp airport, where the push happens on Ground: offer it
                // there (and only before it has been done).
                let prePush = [.notConnected, .connectedIdle, .clearance].contains(stateMachine.current)
                if prePush, buildContext(for: stateMachine.current).pushbackFacility == .ground {
                    return [.pushback, .taxi]
                }
                return [.taxi]
            case .tower:
                return [.ready, .takeoff]
            default:
                return [.clearance]
            }
        }

        // Flight finished at the gate — nothing left to request.
        if stateMachine.current == .parked { return [] }

        // Airborne / arrival — tie the requests to the controller currently tuned.
        switch currentFacility {
        case .departure:
            return [.checkIn, .requestHigher, .requestLower]
        case .center:
            return [.requestHigher, .requestLower, .rideReport, .destWx, .checkIn]
        case .approach:
            return [.checkIn, .vectors, .approach, .requestLower, .destWx]
        case .ramp:
            // Arrival Ramp: tuning in does not transmit, so the taxi-to-gate call is
            // made here with To Gate.
            return [.toGate]
        case .tower, .ground, .clearance:
            // Tower (landing) and Ground (taxi-in) progress with a check-in; no
            // enroute requests apply.
            return [.checkIn]
        }
    }

    /// The frequency-tune buttons worth showing right now: the controller currently
    /// working the flight plus the next distinct controller ahead, so the pilot can
    /// tune ahead for the upcoming hand-off without every facility cluttering the
    /// page (e.g. Tower doesn't appear until the taxi is underway).
    var relevantFacilities: Set<ATCFacility> {
        var set: Set<ATCFacility> = [currentFacility]
        if let next = nextDistinctFacility(after: stateMachine.current) { set.insert(next) }
        return set
    }

    // Weather state.
    @Published var departureMETAR: METAR?
    @Published var destinationMETAR: METAR?
    @Published var alternateMETAR: METAR?
    @Published var destinationTAF: TAF?
    @Published var pireps: [PIREP] = []
    @Published var sigmets: [SIGMET] = []
    /// SIGMETs whose advisory area actually intersects the active route corridor —
    /// the subset that feeds the ride assessment and the Weather tab's advisory card.
    @Published var routeSigmets: [SIGMET] = []
    @Published var rideReportItems: [RideReportItem] = []
    @Published var rideAssessment: RideAssessment = .smooth
    @Published var weatherStatus: String = "Not loaded"

    // Radar precipitation + simulated weather-deviation state.
    /// Descriptive state for the Weather View radar layer (coverage, opacity,
    /// source, last update, mock cells). The image/tiles themselves are fetched by
    /// `RadarOverlayRenderer` from the live provider.
    @Published var radarOverlay = RadarOverlayModel()
    /// Normalized weather hazards fed to the route-conflict detector.
    @Published var weatherHazards: [WeatherHazard] = []
    /// The most significant route-weather conflict currently detected (nil = none).
    @Published var activeWeatherConflict: RouteWeatherConflict?
    /// Faint "preview" reroute lines for the weather systems ahead along the route,
    /// beyond the one drawn solid — one per distinct system. Display only (never drives
    /// ATC), and computed even on the ground, so the whole route's deviations can be
    /// eyeballed from the gate before takeoff.
    @Published var weatherDeviationPreviews: [[CLLocationCoordinate2D]] = []
    /// The active simulated weather-deviation interaction (state + assignments).
    @Published var weatherDeviation = WeatherDeviationContext()
    /// A read-only snapshot for the Weather Diagnostics panel.
    @Published var weatherDiagnostics = WeatherProviderDiagnostics.empty
    /// True once a mock weather advisory has been auto-issued for the current
    /// conflict, so the demo advisory fires once rather than every telemetry tick.
    private var mockWeatherAdvisoryIssued = false
    /// True once the pilot has acted on the current conflict (asked / deviated /
    /// continued), so the "ask Center" banner doesn't re-appear while the same
    /// weather is still ahead. Reset when the conflict clears.
    private var weatherHandled = false
    /// When a route-weather conflict was last actually detected. Drives the confirm-
    /// clear hysteresis: once weather is shown, the mint line and "contact ATC"
    /// banner are held until the route has tested continuously clear for
    /// `weatherClearConfirmWindow`, so a noisy radar resample that momentarily loses
    /// a storm still ahead doesn't blink them out. Nil once clear is confirmed.
    private var lastConflictSeenAt: Date?
    /// How long the route must test continuously clear before a shown conflict is
    /// removed — long enough to span a radar resample cycle (~45 s) so a single
    /// empty sample never drops a storm that's really still there.
    private var weatherClearConfirmWindow: TimeInterval = 90
    /// How close (NM) to the flight-plan intercept at the end of the mint line the
    /// aircraft must get before the controller auto-resumes own navigation when the
    /// pilot hasn't reported clear of weather.
    private let autoResumeInterceptNM: Double = 15
    /// How far ahead (NM) the mint line's turn-out point must sit for a deviation request
    /// to be *held* — approved now, but the beginning turn deferred until the aircraft
    /// reaches the turn-out ("continue, expect the turn in X miles"). Within this the
    /// aircraft is essentially at the turn-out, so the turn is worked immediately.
    private let deviationTurnHoldNM: Double = 6
    /// The most weather systems to draw faint preview mint lines for down the route, so
    /// the strategic preview can never run away (one detection per system).
    private let maxWeatherPreviewSystems = 6
    /// How far (NM) the strategic preview jumps down the route when a detection window
    /// turns up no system, so it scans the whole route past clear gaps rather than
    /// stopping at the first one. A little under the detector's lookahead so windows
    /// overlap and a system straddling a boundary isn't skipped.
    private let previewScanStepNM: Double = 150
    /// Timestamp of the last aviation-weather refresh, for the diagnostics panel.
    private var lastAviationWeatherUpdate: Date?

    private var lastATCTransmission: ATCTransmission?
    private var cancellables = Set<AnyCancellable>()
    private var started = false

    /// Persists the in-progress ATC session so a disconnect/reconnect (or app
    /// relaunch) resumes where the flight left off instead of re-deriving the
    /// conversation from raw telemetry — which would jump a parked aircraft to cruise.
    private let sessionStore = SessionStateStore()
    /// Signature of the last persisted state, so we only write when something
    /// meaningful changed (or the heartbeat below is due).
    private var lastPersistSignature: String?
    /// When the snapshot was last written, used to re-stamp it periodically while
    /// connected so a long, quiet cruise doesn't age the snapshot out of restore.
    private var lastPersistedAt: Date?
    /// Re-save at least this often while the session is active, even if nothing
    /// changed, so `savedAt` stays fresh through long level phases.
    private let persistHeartbeat: TimeInterval = 120

    /// Once airborne, automatic telemetry-driven controller calls run. Before the
    /// first departure the pre-departure ground flow (clearance → pushback →
    /// engine start → taxi → ready) is always pilot-driven via the response
    /// buttons; the only controller call issued automatically on the ground is the
    /// takeoff clearance, once the aircraft is lined up on the runway.
    private var hasDeparted = false

    /// Guards the one-time arrival courtesy call at the gate.
    private var arrivalAnnounced = false

    /// True between contacting arrival Ramp (taxi-to-gate cleared) and the aircraft
    /// actually stopping at the gate with the parking brake set. While true, the
    /// telemetry loop watches for the gate stop and only then announces the block-in
    /// / "flight complete" — so the arrival never claims "parked" mid-taxi.
    private var awaitingGateArrival = false

    /// True once the pilot starts changing controllers with the frequency-tune
    /// buttons. While set, the airborne conversation advances only when the pilot
    /// tunes a frequency — automatic, telemetry-driven controller calls are
    /// suppressed so messages never play one after another without waiting.
    @Published private(set) var manualTuning = false

    /// The facility the pilot has tuned to but not yet checked in with. Switching
    /// frequency no longer auto-checks-in, so this keeps the header and the active
    /// button highlight on the tuned controller until the next check-in or request
    /// advances the conversation. Cleared once the state machine advances.
    private var tunedFacility: ATCFacility?

    /// While the pilot is tuning frequencies by hand in *live* mode, the controller's
    /// position-based calls and facility hand-offs still fire automatically from
    /// telemetry — but once a hand-off ("contact Approach on …") is issued, the new
    /// controller's instruction waits until the pilot tunes that frequency and checks
    /// in. This holds the facility the pilot has been handed to but not yet checked in
    /// with, so the automatic flow pauses there instead of talking for a controller
    /// the pilot hasn't switched to. Nil when no hand-off is pending.
    private var pendingCheckInFacility: ATCFacility?

    /// Live-mode countdown that issues the takeoff clearance a few seconds after the
    /// aircraft is detected lined up and stopped on the departure runway, so Tower
    /// does not clear the takeoff the instant the nose swings onto the centerline.
    var takeoffClearanceDelay: TimeInterval = 5
    private var takeoffClearanceTimer: Task<Void, Never>?

    /// Aircraft altitude (ft MSL) captured when the takeoff clearance is issued, so
    /// the Tower→Departure hand-off can be held until the aircraft has actually
    /// climbed out (~2,000 ft above the field) even when Infinite Flight does not
    /// expose AGL. Without this the departure call could fire the instant after the
    /// takeoff clearance — "one right after the other" on the runway.
    private var liftoffAltitudeMSL: Double = 0

    /// True once the arrival "monitor ramp to the gate" call has been issued, so the
    /// staged arrival (proceed-to-gate → monitor → flight complete) never collapses
    /// into a single burst when the aircraft is already stopped at the gate.
    private var gateMonitored = false

    // MARK: - Readback gate
    //
    // Real controllers wait for the pilot to read an instruction back before
    // issuing the next one. The automatic (telemetry-driven) flow mirrors that:
    // after a controller call that expects a readback, the conversation holds —
    // no further automatic call is issued — until the pilot reads back (any pilot
    // transmission clears the gate). If the pilot is idle, the controller repeats
    // the call and asks "how do you read?" every `readbackRepeatInterval` seconds.
    // This is what stops calls from firing back-to-back near the runway.

    /// True while waiting for the pilot to acknowledge the last automatic call.
    @Published private(set) var awaitingReadback = false
    /// The controller call to repeat if the pilot stays idle.
    private var pendingReadbackTx: ATCTransmission?
    /// Drives the idle re-prompt while `awaitingReadback`.
    private var readbackTimer: Task<Void, Never>?
    /// How many times the idle re-prompt has fired for the current call.
    private var readbackPrompts = 0

    /// Seconds of pilot silence before the controller repeats the call.
    var readbackRepeatInterval: TimeInterval = 10
    /// How many times to repeat before giving up (the gate stays closed so the
    /// flow does not run away; the pilot can still act via the buttons/PTT).
    var readbackMaxPrompts = 3

    init() {
        let settings = AppSettings()
        self.settings = settings
        let diagnostics = DiagnosticsStore()
        self.diagnostics = diagnostics
        self.speech = SpeechService()
        self.connect = IFConnectManager()
        self.mock = MockSimulatorFeed()
        let profiles = PhraseologyProfileStore()
        self.phraseologyProfiles = profiles
        self.speechRecognizer = SpeechRecognitionService()
        self.entitlements = EntitlementManager()
        self.weatherService = AviationWeatherService(baseURL: settings.weatherBaseURL)

        var engine = PhraseologyEngine(digitStyle: settings.digitStyle, mode: settings.phraseologyMode)
        engine.profile = profiles.activeProfile
        self.engine = engine
        self.stateMachine = ATCStateMachine(engine: engine)
        self.pilotEngine = PilotResponseEngine(engine: engine)
        self.rampEngine = RampPhraseologyEngine(engine: engine)
        self.rideEngine = RideReportEngine(engine: engine)
        self.deviationEngine = WeatherDeviationEngine(phraseology: WeatherDeviationPhraseology(engine: engine))
    }

    /// Apply the current settings + active profile to the engine and rebuild the
    /// engine-dependent objects.
    private func applyEngineConfig() {
        engine.digitStyle = settings.digitStyle
        engine.mode = settings.phraseologyMode
        engine.profile = phraseologyProfiles.activeProfile
        stateMachine = ATCStateMachine(engine: engine)
        pilotEngine = PilotResponseEngine(engine: engine)
        rampEngine = RampPhraseologyEngine(engine: engine)
        rideEngine = RideReportEngine(engine: engine)
        deviationEngine = WeatherDeviationEngine(phraseology: WeatherDeviationPhraseology(engine: engine))
    }

    // MARK: - Lifecycle

    func onAppear() {
        guard !started else { return }
        started = true

        startNetworkMonitor()
        diagnostics.verbose = settings.debugLogging
        applyKeepScreenAwake()
        speech.configure(settings: settings)
        connect.configure(diagnostics: diagnostics)
        Task { await weatherService.configure(baseURL: settings.weatherBaseURL, diagnostics: diagnostics) }
        precipService.configure(diagnostics: diagnostics)
        // An async OPERA/ORD overlay render finished — nudge the map to re-request the
        // now-cached image (touch the published overlay model without recomputing).
        precipService.onOverlayUpdated = { [weak self] in self?.radarOverlay.lastUpdated = Date() }
        applyRadarProvider()

        // Route state from whichever feed is active.
        mock.onState = { [weak self] state in self?.handle(state: state) }
        connect.onState = { [weak self] state in self?.handle(state: state) }

        // Read the flight plan from Infinite Flight when it changes.
        connect.onFlightPlan = { [weak self] plan in self?.mergeLiveFlightPlan(plan) }

        // Route recognized push-to-talk speech to the deterministic intent handler.
        speechRecognizer.onResult = { [weak self] text in self?.handleSpokenInput(text) }

        observeSettings()
        observeEntitlements()
        syncFlightPlanFromSettings()

        // Without an active subscription the app is locked to Mock Mode. Settle
        // the mode before starting a feed so a lapsed subscriber never resumes in
        // Live Connected Mode. The async entitlement refresh below re-checks once
        // StoreKit responds and re-locks if needed.
        if !entitlements.hasLiveAccess { settings.mockMode = true }

        diagnostics.log(.app, "IFATC Companion ready. Mock mode: \(settings.mockMode).")

        if settings.mockMode {
            startMock()
        } else {
            startLive()
        }

        // Bring up StoreKit: listen for transaction updates, then load products
        // and re-evaluate entitlements. The observer below enforces the lock.
        Task {
            await entitlements.startListeningForTransactions()
            await entitlements.refresh()
        }
    }

    /// Keep the active mode in sync with the subscription state: whenever Live
    /// access is lost (expired, revoked, never purchased) force Mock Mode on, and
    /// whenever it is gained (subscription confirmed at launch, purchased, or
    /// restored) default into Live Connected Mode. At startup the app is pinned to
    /// Mock Mode until the async entitlement refresh completes; this promotes a
    /// subscribed user to Live once that check confirms their access.
    private func observeEntitlements() {
        entitlements.$hasLiveAccess
            .sink { [weak self] hasAccess in self?.applyEntitlement(hasLiveAccess: hasAccess) }
            .store(in: &cancellables)
    }

    /// React to a change in Live-access entitlement, switching the active mode to
    /// match. Driven by the `hasLiveAccess` value the publisher hands us — **not**
    /// by re-reading `entitlements.hasLiveAccess` (nor via `toggleMockMode`, whose
    /// guard does). `@Published` emits from `willSet`, so while this runs the
    /// property still returns the *previous* value; routing the "access gained"
    /// case through `toggleMockMode(false)` would let its guard read that stale
    /// `false`, refuse the switch, and bounce a just-confirmed subscriber straight
    /// back into Mock Mode — leaving the mock toggle stuck on after the entitlement
    /// check passes.
    func applyEntitlement(hasLiveAccess: Bool) {
        if !hasLiveAccess && !settings.mockMode {
            diagnostics.log(.app, "Live subscription not active — locking to Mock Mode.")
            settings.mockMode = true
            startMock()
        } else if hasLiveAccess && settings.mockMode {
            diagnostics.log(.app, "Live subscription active — switching to Live Connected Mode.")
            settings.mockMode = false
            startLive()
        }
    }

    /// Disable the iOS idle timer so the screen stays on while the app is open.
    /// Infinite Flight closes the Connect socket when the companion device sleeps,
    /// so keeping the screen awake prevents the connection from dropping.
    private func applyKeepScreenAwake() {
        #if canImport(UIKit)
        UIApplication.shared.isIdleTimerDisabled = settings.keepScreenAwake
        #endif
    }

    private func observeSettings() {
        // Keep phraseology engines and dependent objects in sync with settings.
        settings.$digitStyle
            .combineLatest(settings.$phraseologyMode)
            .sink { [weak self] _, _ in self?.applyEngineConfig() }
            .store(in: &cancellables)

        // Rebuild engines when the active phraseology profile changes.
        phraseologyProfiles.$activeProfileID
            .dropFirst()
            .sink { [weak self] _ in self?.applyEngineConfig() }
            .store(in: &cancellables)

        phraseologyProfiles.$profiles
            .dropFirst()
            .sink { [weak self] _ in self?.applyEngineConfig() }
            .store(in: &cancellables)

        settings.$debugLogging
            .sink { [weak self] on in self?.diagnostics.verbose = on }
            .store(in: &cancellables)

        settings.$keepScreenAwake
            .sink { [weak self] _ in self?.applyKeepScreenAwake() }
            .store(in: &cancellables)

        // Track live ATC-staffing status from the Connect link (live mode only).
        connect.$liveATC
            .sink { [weak self] status in
                guard let self, !self.settings.mockMode else { return }
                self.applyLiveATC(status)
            }
            .store(in: &cancellables)

        // In mock mode, derive staffing from the demo toggle.
        $simulateStaffedATC
            .sink { [weak self] on in
                guard let self, self.settings.mockMode else { return }
                self.applyLiveATC(on ? self.mockStaffedStatus() : .none)
            }
            .store(in: &cancellables)
    }

    /// A simulated staffing snapshot for the Diagnostics demo toggle in mock mode:
    /// pretend the pilot is tuned to a staffed controller matching the current facility.
    private func mockStaffedStatus() -> LiveATCStatus {
        LiveATCStatus(multiplayerOnline: true,
                      serverName: "Expert",
                      humanControllerActive: true,
                      controllerName: "Demo Controller",
                      tunedFrequencyName: currentFacility.isFAAATC ? currentFacility.title : nil)
    }

    /// Apply a new live-ATC status and log human-ATC transitions. Logs both the
    /// detection of a human controller in the session and the per-frequency standby
    /// state (which only engages while the pilot is tuned to that controller).
    private func applyLiveATC(_ status: LiveATCStatus) {
        let wasHuman = liveATC.humanControllerActive
        let wasStandby = liveATC.companionShouldStandBy
        liveATC = status
        if status.humanControllerActive != wasHuman {
            diagnostics.log(.atc, status.humanControllerActive
                ? "Human ATC online in session\(status.controllerName.map { " (\($0))" } ?? "")."
                : "Human ATC no longer present in session.")
        }
        if status.companionShouldStandBy != wasStandby {
            if status.companionShouldStandBy {
                let f = status.tunedFacility?.title ?? status.tunedFrequencyName ?? "a controller"
                diagnostics.log(.atc, "Tuned to human ATC (\(f)) — companion standing by on this frequency.")
            } else {
                diagnostics.log(.atc, "Off the human-controlled frequency — companion resuming.")
            }
        }
    }

    // MARK: - Source selection

    func startMock() {
        connect.disconnect()
        manualTuning = false
        tunedFacility = nil
        pendingCheckInFacility = nil
        clearReadbackGate()
        cancelTakeoffClearanceTimer()
        stateMachine.reset()
        hasDeparted = false
        arrivalAnnounced = false
        awaitingGateArrival = false
        gateMonitored = false
        atcState = .connectedIdle
        currentFacility = .clearance
        stateMachine.setConnected()
        applyLiveATC(simulateStaffedATC ? mockStaffedStatus() : .none)
        applyRadarProvider()
        resetWeatherDeviation()
        mock.start()
        diagnostics.log(.app, "Mock simulator feed started.")
        Task { await refreshWeather() }
    }

    func stopMock() {
        mock.stop()
    }

    func startLive() {
        mock.stop()
        tunedFacility = nil
        pendingCheckInFacility = nil
        clearReadbackGate()
        cancelTakeoffClearanceTimer()
        applyRadarProvider()
        resetWeatherDeviation()
        // Resume a recent in-progress session if one was saved (reconnect / relaunch);
        // otherwise start the conversation fresh. Restoring is what keeps a parked
        // aircraft from being re-derived straight to cruise after a dropped link.
        if !restoreSession() {
            manualTuning = false
            stateMachine.reset()
            hasDeparted = false
            arrivalAnnounced = false
            awaitingGateArrival = false
            gateMonitored = false
            currentFacility = .clearance
        }
        if settings.autoDiscover && settings.host.isEmpty {
            connect.startAutoDiscover { [weak self] device in
                guard let self else { return }
                self.settings.host = device.address
                self.settings.port = device.port
                self.connect.connect(host: device.address, port: device.port)
                self.afterConnect()
            }
        } else if !settings.host.isEmpty {
            connect.connect(host: settings.host, port: settings.port)
            afterConnect()
        } else {
            diagnostics.log(.app, "No host set and auto-discover off — staying idle. Enter an IP in Settings.")
        }
    }

    private func afterConnect() {
        // Load weather once the connection is established.
        Task {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            await self.refreshWeather()
        }
    }

    func toggleMockMode(_ on: Bool) {
        // Live Connected Mode requires an active subscription. Without one, keep
        // the app pinned to Mock Mode regardless of the requested value.
        guard on || entitlements.hasLiveAccess else {
            settings.mockMode = true
            if !mock.running { startMock() }
            return
        }
        settings.mockMode = on
        if on { startMock() } else { startLive() }
    }

    func reconnect() {
        if settings.mockMode { startMock() } else { connect.disconnect(); startLive() }
    }

    /// True after the app has been sent to the background, so the next return to the
    /// foreground forces a fresh Connect link.
    private var wasBackgrounded = false

    /// Whether the app is currently in the foreground. Gates network polling (radar
    /// resampling) so we don't fetch from public weather services while backgrounded.
    private var appActive = true

    /// Watches the network path so the "Reduce cellular data" setting can suppress the
    /// megabyte-scale OPERA composite downloads on a cellular / expensive connection.
    /// Dormant while OPERA is disabled (no megabyte downloads to throttle), but kept
    /// running so `isExpensiveNetwork` is ready when OPERA is re-enabled.
    private let networkMonitor = NWPathMonitor()
    /// True on a cellular / personal-hotspot / Low-Data-Mode connection.
    private(set) var isExpensiveNetwork = false

    /// Record that the app went to the background (the OS may have torn down or
    /// silently stalled the Infinite Flight TCP link while we were away).
    func markBackgrounded() { wasBackgrounded = true; appActive = false }

    /// When the app returns to the foreground after being backgrounded, force a
    /// reconnect so live flight details resume updating immediately. (Infinite Flight
    /// can leave the socket looking "connected" while no new telemetry flows, which
    /// otherwise required a manual Reconnect.) The in-progress session is restored on
    /// reconnect, so the conversation picks up where it left off. No-op in Mock Mode.
    func handleReturnToForeground() {
        appActive = true
        guard started, wasBackgrounded else { return }
        wasBackgrounded = false
        guard !settings.mockMode else { return }
        diagnostics.log(.app, "Returned to foreground — forcing an Infinite Flight reconnect.")
        connect.disconnect()
        startLive()
    }

    /// Start watching the network path so `isExpensiveNetwork` reflects a cellular /
    /// hotspot / Low-Data-Mode connection (drives the "Reduce cellular data" setting).
    private func startNetworkMonitor() {
        networkMonitor.pathUpdateHandler = { [weak self] path in
            let expensive = path.isExpensive || path.isConstrained
            Task { @MainActor in self?.isExpensiveNetwork = expensive }
        }
        networkMonitor.start(queue: DispatchQueue(label: "ifatc.network.monitor"))
    }

    // MARK: - State handling

    #if DEBUG
    /// Test hook: feed a single aircraft-state snapshot through the same pipeline the
    /// live/mock feeds use (phase detection → state machine → transcript). Lets tests
    /// drive a full mock scenario without starting timers, networking, or audio.
    func ingestStateForTesting(_ state: AircraftState) { handle(state: state) }

    /// Test hook: capture the current session as a snapshot (as persistence would).
    func snapshotForTesting() -> SessionSnapshot { currentSnapshot() }

    /// Test hook: restore from a snapshot the same way a reconnect would, so a test
    /// can verify the conversation resumes where it left off.
    func applySnapshotForTesting(_ snapshot: SessionSnapshot) { apply(snapshot: snapshot) }

    /// Test hook: force the confirm-clear window to have elapsed, so the next
    /// recompute drops a held (no-longer-detected) conflict instead of waiting out
    /// the real hysteresis window.
    func expireWeatherClearWindowForTesting() { lastConflictSeenAt = .distantPast }
    #endif

    private func handle(state: AircraftState) {
        aircraftState = state
        stateMachine.setConnected()
        // Persist the session on the way out of every path so a drop after this tick
        // resumes from here.
        defer { persistSession() }

        // Ignore an empty telemetry snapshot — Infinite Flight returns one during the
        // reconnect handshake, and PhaseDetector would read its nil "on ground" as
        // airborne and default to "climb". Driving the flow from that would jump a
        // parked aircraft to cruise on reconnect. Hold the current (restored) ATC
        // state until real telemetry arrives. (Partial telemetry — e.g. altitude or
        // ground state without a position fix — is still processed.)
        guard state.hasUsableTelemetry else {
            atcState = stateMachine.current
            currentFacility = tunedFacility ?? controller(for: stateMachine.current)
            return
        }

        let result = phaseDetector.detect(state: state, plan: flightPlan,
                                          airports: airports, previous: phase)
        phase = result.phase
        phaseDebug = result.debug

        // Re-evaluate route-weather conflicts / radar coverage as the aircraft
        // moves. Pure and cheap; never advances the ATC state machine, so it can't
        // interfere with the gate-to-gate flow or the readback gate.
        recomputeWeatherHazards()
        // Keep the sampled precipitation current as the aircraft flies (throttled),
        // so the deviation line tracks the weather ahead instead of going stale and
        // dropping between manual refreshes.
        maybeResamplePrecipitation()

        if !phase.isGround { hasDeparted = true }

        let mapped = stateMachine.mappedState(for: phase)

        // Defer to a human controller on the tuned frequency: track state for
        // display, but do not generate or speak controller calls. Honor a manual
        // tune so the guard stays pinned to the frequency the pilot is actually on
        // (rather than snapping to the phase-derived controller, which would fight
        // the per-frequency guard as the pilot tunes on and off the staffed sector).
        if companionStandby {
            atcState = mapped
            currentFacility = tunedFacility ?? controller(for: mapped)
            return
        }

        // After contacting arrival Ramp, walk the arrival in to the gate in stages so
        // the ramp calls never all fire at once: first "monitor ramp to the gate" as
        // the aircraft slows toward a stop, then — a tick later — the block-in /
        // "flight complete" once it is actually parked with the parking brake set.
        // This runs even while manually tuned, since the pilot drove the Ramp call.
        if awaitingGateArrival {
            if !gateMonitored, isSlowingAtGate(state) {
                gateMonitored = true
                let c = buildContext(for: .groundArrival, arrivalOverride: true)
                post(rampEngine.monitorRampToGate(cs: c.callsign), speak: true)
            } else if gateMonitored, isParkedAtGate(state) {
                completeGateArrival()
            }
            atcState = stateMachine.current
            currentFacility = controller(for: stateMachine.current)
            return
        }

        // --- Pre-departure (on the ground, before the first takeoff) ---
        // The pilot drives their own calls (clearance → pushback → engine start →
        // taxi → ready) with the response buttons. The only controller call issued
        // automatically here is the takeoff clearance, once the aircraft is lined
        // up on the runway — Tower does not wait for a pilot prompt for that.
        if !hasDeparted {
            if readbackGateClosed {
                // Hold: the controller is waiting for the pilot to read back the
                // last call (or to answer the "how do you read?" prompt) before
                // issuing anything further.
            } else if stateMachine.current == .lineUpWait, !settings.mockMode {
                // Tower clears the takeoff automatically once the aircraft is on the
                // runway — immediately if it's already rolling, otherwise a few
                // seconds after it settles lined up and stopped. This fires even while
                // the pilot is tuning frequencies by hand (they tuned Tower for it).
                autoAdvanceTakeoffClearance(state: state)
            } else if !manualTuning, !mapped.isManualGroundFlow, isForward(mapped) {
                // Telemetry already shows the takeoff roll (the pilot rolled without
                // using the buttons) — advance so the flow is never stuck on ground.
                advanceAndPost(to: mapped, context: buildContext(for: mapped), automatic: true)
            }
            // Otherwise hold: the pilot-driven ground flow advances via the buttons.
            atcState = stateMachine.current
            currentFacility = tunedFacility ?? controller(for: stateMachine.current)
            return
        }

        // --- Airborne / arrival ---
        // When the pilot is changing controllers manually with the frequency buttons:
        //  • In Mock Mode there is no live telemetry, so the conversation advances only
        //    on a button press (keep the display fresh and return).
        //  • In live mode the controller's position-based calls and facility hand-offs
        //    still fire automatically from telemetry — but each hand-off only prompts
        //    "contact <next> on …" and then waits for the pilot to tune that frequency
        //    and check in before the new controller gives its instruction.
        if manualTuning {
            if !settings.mockMode { advanceSemiAutomatic(mapped: mapped, state: state) }
            atcState = stateMachine.current
            currentFacility = tunedFacility ?? pendingCheckInFacility ?? controller(for: stateMachine.current)
            return
        }

        // Hold the automatic flow until the pilot has acknowledged the last call.
        // Without this, telemetry polling re-issues calls every tick and they pile
        // up "one after the next" near the runway.
        if readbackGateClosed {
            atcState = stateMachine.current
            currentFacility = controller(for: stateMachine.current)
            return
        }

        // Otherwise controller callouts (hand-offs, climb/descent, approach,
        // landing, taxi-in) are issued automatically as the aircraft reaches each
        // position. The flow only ever moves forward (never bounces back to an
        // earlier state while the phase detector flickers). Pilot read-backs and
        // check-ins stay manual.
        let previousState = stateMachine.current
        let target = adjustedAirborneTarget(mapped: mapped, state: state)
        if isForward(target) {
            advanceAndPost(to: target, context: buildContext(for: target), automatic: true)
            // Once the approach is cleared and the aircraft is established, Approach
            // hands the pilot to Tower (instruction first, then the hand-off — the
            // reverse of the usual "contact … then instruction" order, so it is
            // posted explicitly here rather than via the generic facility-change
            // hand-off, which the .final → .landing step then suppresses).
            if previousState != .final, stateMachine.current == .final {
                announceApproachToTowerHandoff()
            }
        }

        // One-time arrival courtesy once stopped at the gate.
        if stateMachine.current == .parked, !arrivalAnnounced {
            announceArrival()
            arrivalAnnounced = true
        }

        atcState = stateMachine.current
        currentFacility = controller(for: stateMachine.current)
    }

    // MARK: - Automatic flow

    /// The controller (facility) actually working a given ATC state. Used to drive
    /// realistic "contact …" handoffs whenever control passes between facilities,
    /// and to label the current facility in the UI.
    private func controller(for state: ATCState) -> ATCFacility {
        switch state {
        case .clearance: return .clearance
        case .pushback, .engineStart: return .ramp
        case .pushbackTaxi, .groundTaxi, .runwayCrossing,
             .holdingShort, .groundArrival, .parked: return .ground
        case .lineUpWait, .towerDeparture, .landing, .runwayExit: return .tower
        case .initialClimb, .departure: return .departure
        case .climb, .center, .cruise, .topOfDescent, .descent: return .center
        case .approach, .final: return .approach
        case .notConnected, .connectedIdle, .abnormal: return currentFacility
        }
    }

    /// Frequency the given facility is reached on, from the current context.
    private func frequency(for facility: ATCFacility, context c: ATCContext) -> Double {
        switch facility {
        case .ramp: return c.rampFrequency
        case .clearance, .ground: return c.groundFrequency
        case .tower: return c.towerFrequency
        case .departure: return c.departureFrequency
        case .center: return c.centerFrequency
        case .approach: return c.approachFrequency
        }
    }

    /// Advance the state machine and post the resulting controller call, preceding
    /// it with a "contact …" handoff whenever the controlling facility changes.
    private func advanceAndPost(to target: ATCState, context c: ATCContext,
                                speak: Bool = true, announceHandoff: Bool = true,
                                automatic: Bool = false) {
        let previous = stateMachine.current
        // The conversation is advancing, so any pending manual tune has now been
        // acted on — the controller working the new state speaks for itself. Capture
        // which facility the pilot had tuned first: if they already switched to the
        // controller now taking over, that controller must not tell them to "contact"
        // it (they're already there).
        let wasTuned = tunedFacility
        tunedFacility = nil
        guard let tx = stateMachine.advance(to: target, context: c) else {
            atcState = stateMachine.current
            currentFacility = controller(for: stateMachine.current)
            return
        }
        let fromFacility = controller(for: previous)
        let toFacility = controller(for: target)
        // Announce a handoff only between two established controllers (not the very
        // first contact, and not Clearance which is the initial call-up). The
        // runway-exit call already tells the pilot to contact Ground, so suppress
        // the duplicate hand-off when leaving that state. The Ramp hand-off for
        // pushback is already issued at the end of the IFR clearance, so don't
        // repeat it here. When the pilot tuned the frequency themselves, the
        // controller does not say "contact …" either.
        let firstContact = previous == .notConnected || previous == .connectedIdle
        // The Approach → Tower hand-off is issued explicitly when the approach is
        // cleared (established), so don't repeat it when .final advances to .landing.
        if announceHandoff, !firstContact, previous != .runwayExit, previous != .final,
           fromFacility != toFacility, toFacility != .clearance, toFacility != .ramp,
           wasTuned != toFacility, lastATCTransmission != nil {
            post(engine.handoff(cs: c.callsign, from: fromFacility, to: toFacility,
                                frequency: frequency(for: toFacility, context: c)), speak: speak)
        }
        // Capture the field altitude at takeoff so the climb-out hand-off can be
        // held until the aircraft is well above the runway (see adjustedAirborneTarget).
        if target == .towerDeparture { liftoffAltitudeMSL = aircraftState.altitudeMSL ?? 0 }
        updateAssignedAltitude(for: target, context: c)
        post(tx, speak: speak)
        // An automatic call that carries a read-back instruction closes the gate:
        // the flow holds here until the pilot reads back (or the idle prompt loop
        // runs its course). Pilot-driven advances never close the gate — the pilot
        // is already driving the conversation. The takeoff clearance holds the gate
        // too (so the Departure hand-off can't stack on it), but must NOT arm the
        // idle "how do you read?" nag — a controller does not radio-check a pilot it
        // just cleared for takeoff, and the nag was firing before the pilot could
        // even read the clearance back.
        if automatic, target.expectsReadback {
            engageReadbackGate(tx, promptIfIdle: target != .towerDeparture)
        }
        atcState = stateMachine.current
        currentFacility = controller(for: stateMachine.current)
    }

    /// Whether the aircraft is positioned for the automatic takeoff clearance:
    /// lined up on the departure runway, already rolling, or telemetry reports the
    /// takeoff phase. Used in live mode once the pilot has reported ready (the
    /// state machine is at line-up-and-wait).
    private func isReadyForTakeoffClearance(state: AircraftState) -> Bool {
        let runway = buildContext(for: stateMachine.current).runway
        return lineupDetector.isLinedUp(state: state, runway: runway)
            || lineupDetector.isDepartingRoll(state: state, runway: runway)
            || phase == .takeoff
    }

    /// Tower automatically clears the aircraft for takeoff (no pilot prompt needed).
    private func autoIssueTakeoffClearance() {
        cancelTakeoffClearanceTimer()
        advanceAndPost(to: .towerDeparture, context: buildContext(for: .towerDeparture),
                       automatic: true)
    }

    /// Drive the automatic takeoff clearance from telemetry while at line-up-and-wait
    /// (live mode). If the aircraft is already rolling, clear it immediately; if it is
    /// lined up and stopped, arm a short countdown and clear it once that elapses; if
    /// it is neither (still maneuvering onto the runway), stand down the countdown.
    private func autoAdvanceTakeoffClearance(state: AircraftState) {
        let runway = buildContext(for: stateMachine.current).runway
        if lineupDetector.isDepartingRoll(state: state, runway: runway) || phase == .takeoff {
            autoIssueTakeoffClearance()
        } else if isLinedUpAndStopped(state, runway: runway) {
            armTakeoffClearance()
        } else {
            cancelTakeoffClearanceTimer()
        }
    }

    /// Whether the aircraft is established and stopped on the runway centerline,
    /// ready for the takeoff clearance after the brief realism delay.
    private func isLinedUpAndStopped(_ s: AircraftState, runway: String) -> Bool {
        lineupDetector.isLinedUp(state: s, runway: runway)
            && (s.onGround ?? true) && (s.groundSpeed ?? 0) < 5
    }

    /// Start the takeoff-clearance countdown if one isn't already running. When it
    /// elapses, re-check the aircraft is still lined up and waiting, then clear it.
    private func armTakeoffClearance() {
        guard takeoffClearanceTimer == nil else { return }
        takeoffClearanceTimer = Task { @MainActor [weak self] in
            guard let self else { return }
            try? await Task.sleep(nanoseconds: UInt64(max(0, self.takeoffClearanceDelay) * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self.takeoffClearanceTimer = nil
            // Stand by while tuned to a human controller — Tower's automatic takeoff
            // clearance must not fire over a live controller. The next telemetry tick
            // re-arms it once the pilot leaves the human frequency.
            guard !self.hasDeparted, self.stateMachine.current == .lineUpWait,
                  !self.settings.mockMode, !self.readbackGateClosed, !self.companionStandby,
                  self.isReadyForTakeoffClearance(state: self.aircraftState) else { return }
            self.autoIssueTakeoffClearance()
        }
    }

    private func cancelTakeoffClearanceTimer() {
        takeoffClearanceTimer?.cancel()
        takeoffClearanceTimer = nil
    }

    /// Whether the aircraft is slow enough on the ground to be settling onto the gate
    /// (used to stage the arrival "monitor ramp to the gate" call before the block-in).
    private func isSlowingAtGate(_ s: AircraftState) -> Bool {
        (s.onGround ?? true) && (s.groundSpeed ?? 0) < 8
    }

    // MARK: - Semi-automatic airborne flow (live + manual tuning)

    /// In live mode, while the pilot tunes frequencies by hand, keep issuing the
    /// controller's automatic, position-based calls and facility hand-offs — but
    /// withhold a new controller's instruction until the pilot tunes that frequency
    /// and checks in. Same-facility continuations (descend-via-STAR, cleared-approach,
    /// exit-the-runway) play on their own; a change of controller only prompts the
    /// "contact <next> on …" hand-off and then waits.
    private func advanceSemiAutomatic(mapped: ATCState, state: AircraftState) {
        // Hold while the last instruction is unacknowledged, or while we're waiting
        // for the pilot to check in on a frequency they were just handed to.
        guard !readbackGateClosed, pendingCheckInFacility == nil else { return }

        let target = adjustedAirborneTarget(mapped: mapped, state: state)
        guard isForward(target), target != stateMachine.current else { return }

        let fromFacility = controller(for: stateMachine.current)
        let toFacility = controller(for: target)

        if toFacility == fromFacility {
            // The same controller keeps working the aircraft — issue the call now.
            let previousState = stateMachine.current
            advanceAndPost(to: target, context: buildContext(for: target),
                           announceHandoff: false, automatic: true)
            if previousState != .final, stateMachine.current == .final {
                // The cleared-approach call hands the pilot to Tower; wait for them to
                // tune Tower and check in for the landing clearance.
                announceApproachToTowerHandoff()
                pendingCheckInFacility = .tower
            } else if stateMachine.current == .runwayExit {
                // The exit-the-runway call already tells the pilot to contact Ground;
                // wait for them to tune Ground and check in for the taxi-in.
                pendingCheckInFacility = .ground
            }
        } else {
            // Control passes to a new facility: prompt the hand-off and wait for the
            // pilot to switch frequency and check in before that controller speaks.
            issueAutoHandoff(from: fromFacility, to: toFacility)
            pendingCheckInFacility = toFacility
        }
    }

    /// Issue a stand-alone "contact <next> on …" hand-off (no state-machine advance):
    /// the new controller's instruction follows once the pilot checks in.
    private func issueAutoHandoff(from: ATCFacility, to: ATCFacility) {
        let c = buildContext(for: stateMachine.current)
        post(engine.handoff(cs: c.callsign, from: from, to: to,
                            frequency: frequency(for: to, context: c)), speak: true)
    }

    // MARK: - Readback gate

    /// Whether the automatic flow is currently holding for a pilot read-back. Only
    /// engaged in live mode — the deterministic mock/demo and the unit tests drive
    /// read-backs synchronously and never need the controller to wait.
    private var readbackGateClosed: Bool { awaitingReadback && !settings.mockMode }

    /// Index of a state in the canonical gate-to-gate flow order, used to keep the
    /// automatic flow moving forward only (never bouncing back to an earlier call
    /// while the phase detector flickers near the ground).
    private func flowIndex(of state: ATCState) -> Int? {
        Self.flowOrder.firstIndex(of: state)
    }

    /// Whether advancing to `target` would move the conversation forward (or stay
    /// put). States outside the gate-to-gate order are treated as allowed.
    private func isForward(_ target: ATCState) -> Bool {
        guard let ti = flowIndex(of: target),
              let ci = flowIndex(of: stateMachine.current) else { return true }
        return ti >= ci
    }

    /// Close the gate after an automatic instruction so the next call waits for the
    /// pilot's read-back. When `promptIfIdle` is true the idle re-prompt loop is armed
    /// so an unanswered call is repeated with "how do you read?"; the takeoff
    /// clearance passes false so it holds the gate silently (no runway radio-check).
    private func engageReadbackGate(_ tx: ATCTransmission, promptIfIdle: Bool = true) {
        guard !settings.mockMode else { return }
        awaitingReadback = true
        pendingReadbackTx = tx
        readbackPrompts = 0
        if promptIfIdle { armReadbackTimer() }
    }

    /// Schedule the next idle re-prompt. If the pilot stays silent the controller
    /// repeats the call and asks "how do you read?", up to `readbackMaxPrompts`
    /// times, after which the gate stays closed (the flow does not run away) until
    /// the pilot acts.
    private func armReadbackTimer() {
        readbackTimer?.cancel()
        guard awaitingReadback else { return }
        readbackTimer = Task { @MainActor [weak self] in
            guard let self else { return }
            try? await Task.sleep(nanoseconds: UInt64(self.readbackRepeatInterval * 1_000_000_000))
            guard !Task.isCancelled, self.awaitingReadback,
                  let tx = self.pendingReadbackTx,
                  self.readbackPrompts < self.readbackMaxPrompts else { return }
            // Stand by while tuned to a human controller: don't nag "how do you read?"
            // over a live controller, but keep the timer alive (re-arm without counting
            // a prompt) so the reminder resumes if the pilot leaves the human frequency
            // before reading back.
            if self.companionStandby {
                self.armReadbackTimer()
                return
            }
            self.readbackPrompts += 1
            self.repeatPendingCall(tx)
            self.armReadbackTimer()
        }
    }

    /// Re-issue the pending controller call with a "how do you read?" tag.
    private func repeatPendingCall(_ tx: ATCTransmission) {
        let cs = engine.callsign(airline: flightPlan.airline,
                                 flightNumber: flightPlan.flightNumber,
                                 fallback: flightPlan.callsign)
        let prefix = "\(cs.display), "
        let display = tx.displayText.hasPrefix(prefix)
            ? "\(tx.displayText) How do you read?"
            : "\(tx.displayText) \(cs.display), how do you read?"
        let spoken = "\(tx.spokenText) How do you read?"
        post(ATCTransmission(sender: .atc, facility: tx.facility,
                             displayText: display, spokenText: spoken), speak: true)
    }

    /// Re-point a closed read-back gate at a freshly posted hand-off so the pilot's
    /// read-back echoes "contacting <next>" (the genuine last message) — and cancel
    /// the idle re-prompt, since a courtesy hand-off must never trigger a "how do you
    /// read?" the way a missed instruction does. Used when an instruction is
    /// immediately followed by a hand-off (e.g. "cleared the ILS … contact Tower").
    private func softenReadbackGate(to tx: ATCTransmission) {
        guard awaitingReadback else { return }
        pendingReadbackTx = tx
        readbackTimer?.cancel()
        readbackTimer = nil
    }

    /// Open the gate once the pilot has responded (any pilot transmission counts as
    /// an acknowledgement) so the automatic flow can resume.
    private func clearReadbackGate() {
        guard awaitingReadback else { return }
        awaitingReadback = false
        pendingReadbackTx = nil
        readbackPrompts = 0
        readbackTimer?.cancel()
        readbackTimer = nil
    }

    // MARK: - Manual frequency tuning

    /// Facilities the pilot can tune to with a button, in the order they're worked
    /// across a normal flight. The same Ground/Tower button serves both the
    /// departure and the arrival visit — checking in advances to whichever call
    /// lies ahead of the current state for that facility.
    static let tunableFacilities: [ATCFacility] =
        [.clearance, .ground, .tower, .departure, .center, .approach]

    /// Canonical gate-to-gate order of ATC states, used to pick the next call a
    /// manually-tuned facility should issue.
    private static let flowOrder: [ATCState] = [
        .clearance, .pushback, .engineStart, .groundTaxi, .lineUpWait,
        .towerDeparture, .initialClimb, .departure, .climb, .cruise, .descent,
        .approach, .final, .landing, .runwayExit, .groundArrival, .parked
    ]

    /// The frequency (MHz) a facility is reached on, for display on its button.
    func frequency(for facility: ATCFacility) -> Double {
        frequency(for: facility, context: buildContext(for: stateMachine.current))
    }

    /// Formatted frequency for a facility's tune button, e.g. "118.300".
    func frequencyText(for facility: ATCFacility) -> String {
        String(format: "%.3f", frequency(for: facility))
    }

    /// Whether the given facility still has a call ahead in the flight, so its
    /// button is live (e.g. Departure is dimmed once enroute with Center).
    func canTune(_ facility: ATCFacility) -> Bool {
        guard let next = nextState(workedBy: facility, after: stateMachine.current) else { return false }
        return next != stateMachine.current
    }

    /// The next state worked by `facility`, searching forward from the current
    /// state. When nothing lies ahead, returns the last state this facility works
    /// (e.g. Center for a further descent) so a re-tap still issues a sensible call.
    private func nextState(workedBy facility: ATCFacility, after current: ATCState) -> ATCState? {
        let order = Self.flowOrder
        let start = order.firstIndex(of: current).map { $0 + 1 } ?? 0
        if let ahead = order[start...].first(where: { controller(for: $0) == facility }) {
            return ahead
        }
        return order.last { controller(for: $0) == facility }
    }

    /// The next controller (different from the one currently tuned) the flight will
    /// be handed to, searching forward through the gate-to-gate order from `current`.
    /// Nil when no later state is worked by a different facility. Used to decide which
    /// frequency buttons are worth surfacing now.
    private func nextDistinctFacility(after current: ATCState) -> ATCFacility? {
        let order = Self.flowOrder
        guard let idx = order.firstIndex(of: current) else { return nil }
        for state in order[(idx + 1)...] {
            let facility = controller(for: state)
            if facility != currentFacility { return facility }
        }
        return nil
    }

    /// Manually tune the radio to a facility. Switching frequency does **not**
    /// check in or advance the conversation on its own: it only moves the radio to
    /// the new controller. From here the pilot either taps Check In to call up the
    /// controller (and get the next instruction) or makes a specific request — e.g.
    /// requesting pushback after Clearance hands you to Ground. This is how the
    /// pilot drives the flight forward without calls auto-playing back-to-back.
    func tuneTo(_ facility: ATCFacility) {
        // Tuning is always allowed — even while standing by for a human controller.
        // Moving the radio is how the pilot leaves a staffed frequency to lift the
        // guard (and how they tune onto one to re-engage it); it never transmits.
        manualTuning = true
        tunedFacility = facility
        currentFacility = facility
        // No transcript, no state change, and no mock-phase change: tuning only
        // moves the radio. The mock display advances with the conversation when the
        // pilot checks in or makes a request.
        persistSession()
    }

    /// Make the Ramp call once tuned to the Ramp frequency. Context-aware: before
    /// departure it contacts Ramp for the pushback (Ramp approves the push; Ground
    /// then handles the taxi). On arrival it contacts Ramp for the taxi-in to the
    /// gate. Reached from the "To Gate" response button on arrival (pre-departure the
    /// pilot uses the Pushback button); tuning to Ramp itself never transmits. It is
    /// never the arrival routine during departure (which previously jumped straight to
    /// "parked, flight complete").
    func contactRamp() {
        guard !companionStandby else { return }
        if isArrivalRamp {
            arriveAtGate()
        } else if isPreDeparture,
                  [.notConnected, .connectedIdle, .clearance, .pushback].contains(stateMachine.current) {
            // Only at/around the pushback point — a late tap (after engine start/taxi)
            // must not rewind the flow; the pilot uses Ground for the taxi from there.
            requestPushback()
        }
    }

    /// Arrival Ramp hand-off: the aircraft is clear of the runway and taxiing in, so
    /// Ramp routes it to the gate. This does NOT announce the block-in — the arrival
    /// is only declared complete once the aircraft actually stops at the gate with
    /// the parking brake set (watched in `handle(state:)`).
    func arriveAtGate() {
        guard !companionStandby else { return }
        guard !arrivalAnnounced else { return }
        manualTuning = true
        hasDeparted = true
        currentFacility = .ramp
        let c = buildContext(for: .groundArrival, arrivalOverride: true)
        // Simulated arrival Ramp (local/company, non-FAA): the pilot checks in
        // inbound and Ramp gives a non-movement-area routing to the gate.
        if c.rampProfile.rampType != .none {
            postPilot(rampEngine.arrivalInbound(cs: c.callsign, gate: c.gate))
            post(rampEngine.proceedToGate(cs: c.callsign, gate: c.gate,
                                          via: c.rampProfile.arrivalRampEntryPhrase.contains("inner")
                                               ? "the inner alley" : "the ramp"), speak: true)
        }
        // Now monitor for the actual gate stop; the staged "monitor ramp" then the
        // block-in fire from telemetry as the aircraft slows and parks, not here.
        gateMonitored = false
        awaitingGateArrival = true
        atcState = .groundArrival
        // In mock mode advance the scripted aircraft to the gate so the monitored
        // block-in plays out (the brake is set in the parked mock state).
        if settings.mockMode { mock.setPhase(.parked) }
    }

    /// Whether the aircraft is genuinely parked at the gate: stopped on the ground
    /// with the parking brake set. Falls back to a full ground stop when the sim
    /// does not expose the parking brake.
    private func isParkedAtGate(_ s: AircraftState) -> Bool {
        let stopped = (s.onGround ?? true) && (s.groundSpeed ?? 0) < 1
        if let brake = s.parkingBrakeSet { return stopped && brake }
        return stopped
    }

    /// Finish the monitored arrival once stopped at the gate: block-in on Ramp and
    /// the "flight complete" advisory, then settle into the parked state.
    private func completeGateArrival() {
        awaitingGateArrival = false
        let c = buildContext(for: .parked, arrivalOverride: true)
        advanceAndPost(to: .parked, context: c, announceHandoff: false)
        if !arrivalAnnounced { announceArrival(); arrivalAnnounced = true }
    }

    /// Representative physical phase for an ATC state, so the mock telemetry display
    /// (altitude, position, on-ground) stays believable while tuning manually.
    private func mockPhase(for state: ATCState) -> FlightPhase {
        switch state {
        case .clearance, .pushback, .engineStart: return .preflight
        case .pushbackTaxi, .groundTaxi, .runwayCrossing, .holdingShort, .lineUpWait: return .taxiOut
        case .towerDeparture: return .takeoff
        case .initialClimb, .departure: return .initialClimb
        case .climb: return .climb
        case .center, .cruise: return .cruise
        case .topOfDescent, .descent: return .descent
        case .approach, .final: return .approach
        case .landing, .runwayExit: return .landing
        case .groundArrival: return .taxiIn
        case .parked: return .parked
        case .notConnected, .connectedIdle, .abnormal: return phase
        }
    }

    /// Adjust the telemetry-mapped airborne state for real-ATC realism: keep
    /// Departure working the climb until the TRACON ceiling, and insert the
    /// cleared-approach / cleared-to-land steps when established on final.
    private func adjustedAirborneTarget(mapped: ATCState, state: AircraftState) -> ATCState {
        let alt = state.altitudeMSL ?? 0
        let ceiling = Double(currentTraconCeiling)
        let onGround = state.onGround ?? false
        let runway = buildContext(for: stateMachine.current).runway

        // Hold Tower → Departure until the aircraft is through ~2,000 ft AGL. Handing
        // off the instant the wheels leave the ground clears the pilot "direct" to the
        // first filed fix while it's still the runway-end waypoint just ahead, and
        // stacks the departure call right on top of the takeoff clearance — so wait for
        // the climb to carry past it first. When Infinite Flight does not expose AGL,
        // fall back to the altitude gained since the takeoff clearance was issued.
        if stateMachine.current == .towerDeparture, mapped == .initialClimb || mapped == .climb {
            let agl = state.altitudeAGL ?? max(0, (state.altitudeMSL ?? 0) - liftoffAltitudeMSL)
            if agl < 2000 { return .towerDeparture }
        }

        // Departure hands off to Center 1,000 ft below the TRACON ceiling (17,000 ft
        // for a FL180 ceiling) rather than right at it. That buffer gives the pilot
        // time to check in with Center and be cleared to the next altitude before the
        // climb reaches the ceiling, so it continues past FL180 without pausing.
        if mapped == .climb, alt < ceiling - 1000,
           [.towerDeparture, .initialClimb, .departure].contains(stateMachine.current) {
            return .initialClimb
        }

        // Top of descent: leaving cruise, Center issues the descend-via-STAR
        // (or plain descend) first, before any Approach hand-off.
        if [.cruise, .center, .topOfDescent].contains(stateMachine.current),
           mapped == .descent || mapped == .approach {
            return .descent
        }

        // Descending through the TRACON ceiling (FL180), or arriving in the
        // terminal area, Center hands the aircraft to Approach.
        if stateMachine.current == .descent, mapped == .descent || mapped == .approach {
            return (mapped == .approach || alt < ceiling - 200) ? .approach : .descent
        }

        // Approach clears the approach once the aircraft is established — autopilot
        // approach mode (APPR) engaged or lined up on final and wings level — before
        // the Tower hand-off. This must follow the "descend, expect approach" call.
        if stateMachine.current == .approach, isEstablishedOnApproach(state, runway: runway) {
            return .final
        }
        // Cleared to land (Tower) on short final or at touchdown.
        if stateMachine.current == .final, onGround || isOnShortFinal(state) {
            return .landing
        }
        // After touchdown, Tower instructs to exit the runway and contact Ground.
        if stateMachine.current == .landing, onGround {
            return .runwayExit
        }
        // Once clear of the runway / at taxi speed, switch to Ground and taxi in.
        if stateMachine.current == .runwayExit {
            let gs = state.groundSpeed ?? 0
            return (onGround && gs < 40) ? .groundArrival : .runwayExit
        }
        return mapped
    }

    /// TRACON ceiling in feet (where Departure hands off to Center).
    private var currentTraconCeiling: Int {
        settings.traconCeilingFL > 0 ? settings.traconCeilingFL * 100 : 18000
    }

    /// Whether the aircraft is established on the approach: the autopilot approach
    /// mode (APPR) is engaged, or it is lined up on final with the runway. Read from
    /// Infinite Flight telemetry (mock feed simulates APPR on the approach phase).
    private func isEstablishedOnApproach(_ s: AircraftState, runway: String) -> Bool {
        guard !(s.onGround ?? false) else { return false }
        if s.approachModeEngaged == true { return true }
        guard lineupDetector.isOnFinalApproach(state: s, runway: runway) else { return false }
        // Lined up with the runway and not still turning onto final (wings roughly
        // level). Bank is reported in degrees; if a build reports radians the check
        // simply never trips and the line-up test alone governs.
        if let bank = s.bankAngle, abs(bank) > 6 { return false }
        return true
    }

    /// Whether the aircraft is on short final (airborne, low, descending).
    private func isOnShortFinal(_ s: AircraftState) -> Bool {
        guard !(s.onGround ?? false) else { return false }
        let agl = s.altitudeAGL ?? (s.altitudeMSL ?? 0)
        let vs = s.verticalSpeed ?? 0
        return agl < 1500 && vs < -100
    }

    /// After the approach is cleared (aircraft established), Approach hands the
    /// pilot off to Tower for the landing clearance.
    private func announceApproachToTowerHandoff() {
        let c = buildContext(for: .final)
        let tx = engine.handoff(cs: c.callsign, from: .approach, to: .tower,
                                frequency: c.towerFrequency)
        post(tx, speak: true)
        // This hand-off follows the cleared-approach call that just closed the gate.
        // Re-aim the gate at the hand-off so the pilot reads back "contacting Tower"
        // (the last message) and the controller does not nag "how do you read?".
        softenReadbackGate(to: tx)
    }

    /// Arrival block-in once stopped at the gate. Emits the simulated Ramp
    /// (local/company, non-FAA) block-in call followed by a System "flight
    /// complete" advisory.
    private func announceArrival() {
        let c = buildContext(for: .parked, arrivalOverride: true)
        let display = "\(c.callsign.display) parked\(c.gate.isEmpty ? "" : " at \(c.gate)"). Flight complete."
        let spoken = "\(c.callsign.spoken) parked. Flight complete."
        post(ATCTransmission(sender: .system, facility: .ramp, displayText: display, spokenText: spoken), speak: false)
    }

    // MARK: - Live flight plan

    /// Apply the manually-entered callsign to the active flight plan without
    /// rebuilding it from settings (so a live-read route is preserved). Infinite
    /// Flight's Connect API exposes no callsign for the user's own aircraft, so the
    /// pilot sets it here and it becomes the source of truth.
    func applyManualCallsign() {
        let trimmed = settings.callsign.trimmingCharacters(in: .whitespacesAndNewlines)
        flightPlan.callsign = trimmed

        // An empty field clears only the callsign — leave any airline/flight number
        // already supplied by the mock feed, a live read, or the Airline/Flight #
        // overrides untouched, so blurring an empty field never wipes them.
        guard !trimmed.isEmpty else {
            diagnostics.log(.app, "Callsign cleared.")
            return
        }
        // Derive an airline/flight number from the callsign only when the pilot has
        // not pinned those separately. Resolve an airline prefix (e.g. "UA598" /
        // "UAL598" -> United 598) so the companion uses the proper telephony name
        // instead of spelling it out.
        guard settings.airline.isEmpty, settings.flightNumber.isEmpty else { return }
        if let parsed = AirlineDatabase.parse(trimmed) {
            flightPlan.airline = parsed.telephony
            flightPlan.flightNumber = parsed.flightNumber
            diagnostics.log(.app, "Callsign \(trimmed) resolved to \(parsed.telephony) \(parsed.flightNumber).")
        } else {
            flightPlan.airline = ""
            flightPlan.flightNumber = ""
            diagnostics.log(.app, "Callsign set to \(trimmed).")
        }
    }

    /// Push the departure/arrival gate fields (edited on the ATC card or in the
    /// Flight overrides) into the active plan. Infinite Flight never supplies gates,
    /// so this simply mirrors the settings values — like `applyManualCallsign`, it
    /// avoids a full re-sync so nothing else in the plan is disturbed.
    func applyManualGates() {
        flightPlan.departureGate = settings.departureGate.trimmingCharacters(in: .whitespacesAndNewlines)
        flightPlan.arrivalGate = settings.arrivalGate.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Merge a flight plan read from Infinite Flight into the active plan. Manual
    /// overrides win; otherwise empty fields are filled from the live plan.
    private func mergeLiveFlightPlan(_ live: FlightPlan) {
        var plan = flightPlan
        let manual = plan.manualOverride
        let before = (plan.departure, plan.destination)

        // For each field: take the live value when the pilot has not pinned it with
        // a manual override, or when the field is still empty.
        func fill(_ keep: inout String, _ value: String) {
            guard !value.isEmpty else { return }
            if !manual || keep.isEmpty { keep = value }
        }

        fill(&plan.departure, live.departure)
        fill(&plan.destination, live.destination)
        fill(&plan.departureRunway, live.departureRunway)
        fill(&plan.arrivalRunway, live.arrivalRunway)
        fill(&plan.sid, live.sid)
        fill(&plan.star, live.star)
        fill(&plan.approach, live.approach)
        if (!manual || plan.waypoints.isEmpty), !live.waypoints.isEmpty { plan.waypoints = live.waypoints }
        // Cruise altitude (from the plan's TOC / highest planned level).
        if (!manual || plan.cruiseAltitude <= 0), live.cruiseAltitude > 0 {
            plan.cruiseAltitude = live.cruiseAltitude
        }
        // Approach intercept altitude (first altitude in the approach section).
        if (!manual || plan.approachInterceptAltitude <= 0), live.approachInterceptAltitude > 0 {
            plan.approachInterceptAltitude = live.approachInterceptAltitude
        }
        flightPlan = plan

        // Refresh weather only when the endpoints actually changed.
        if before != (plan.departure, plan.destination) {
            Task { await refreshWeather() }
        }
    }

    private func updateAssignedAltitude(for state: ATCState, context c: ATCContext) {
        switch state {
        case .clearance: assignedAltitude = c.initialClimbAltitude
        case .towerDeparture: assignedAltitude = c.initialClimbAltitude
        case .initialClimb, .departure:
            assignedAltitude = c.traconCeiling > 0 ? c.traconCeiling : max(c.assignedAltitude, c.initialClimbAltitude)
        case .climb, .cruise: assignedAltitude = c.cruiseAltitude
        case .descent: assignedAltitude = ATCStateMachine.descentTargetAltitude(context: c)
        case .approach: assignedAltitude = c.approachInterceptAltitude > 0 ? c.approachInterceptAltitude : 3000
        default: break
        }
    }

    // MARK: - Transcript

    private func post(_ tx: ATCTransmission, speak: Bool) {
        transcript.append(tx)
        if transcript.count > 200 { transcript.removeFirst(transcript.count - 200) }
        if tx.sender == .atc {
            latestTransmission = tx
            lastATCTransmission = tx
        }
        diagnostics.log(.atc, "[\(tx.sender.rawValue.uppercased())] \(tx.displayText)")
        if speak { speech.speak(tx) }
        // A new transcript line is a meaningful change — capture it immediately so a
        // button-driven call (which doesn't go through the telemetry loop) is saved.
        persistSession()
    }

    /// Post a pilot transmission, speaking it aloud when appropriate. The pilot
    /// voice is heard for button/text-driven calls; push-to-talk input is never
    /// re-spoken because the user already said it.
    private func postPilot(_ tx: ATCTransmission) {
        // Any pilot transmission (read-back, request, check-in, PTT) acknowledges
        // the controller and releases the read-back gate so the flow can resume.
        clearReadbackGate()
        post(tx, speak: shouldSpeakPilot)
    }

    /// Whether the pilot's next transmission should be spoken aloud.
    private var shouldSpeakPilot: Bool { settings.speakPilot && !pilotInputViaVoice }

    /// True while handling a push-to-talk utterance, so the resulting pilot
    /// transmission is not spoken back over the user.
    private var pilotInputViaVoice = false

    // MARK: - Session persistence

    /// Build a snapshot of the live ATC session for persistence.
    private func currentSnapshot() -> SessionSnapshot {
        SessionSnapshot(
            atcState: atcState,
            stateMachineCurrent: stateMachine.current,
            currentFacility: currentFacility,
            phase: phase,
            assignedAltitude: assignedAltitude,
            hasDeparted: hasDeparted,
            arrivalAnnounced: arrivalAnnounced,
            awaitingGateArrival: awaitingGateArrival,
            manualTuning: manualTuning,
            weatherDeviation: weatherDeviation,
            transcript: transcript,
            departure: flightPlan.departure,
            destination: flightPlan.destination,
            mockMode: settings.mockMode,
            savedAt: Date())
    }

    /// Persist the current session when something meaningful changed (or the
    /// heartbeat is due, so `savedAt` stays fresh through long quiet phases). Only
    /// live sessions are saved — the mock feed is a deterministic demo that always
    /// starts fresh.
    private func persistSession() {
        guard !settings.mockMode else { return }
        let sig = [stateMachine.current.rawValue, atcState.rawValue, currentFacility.rawValue,
                   phase.rawValue, String(assignedAltitude), String(hasDeparted),
                   String(arrivalAnnounced), String(awaitingGateArrival), String(manualTuning),
                   weatherDeviation.state.rawValue, String(transcript.count)].joined(separator: "|")
        let now = Date()
        let heartbeatDue = lastPersistedAt.map { now.timeIntervalSince($0) >= persistHeartbeat } ?? true
        guard sig != lastPersistSignature || heartbeatDue else { return }
        lastPersistSignature = sig
        lastPersistedAt = now
        sessionStore.save(currentSnapshot())
    }

    /// Restore a recent in-progress session, if one was saved, so a reconnect or
    /// relaunch resumes where the flight left off. Returns whether a session was
    /// restored. Never restores in mock mode or from a mock snapshot.
    @discardableResult
    private func restoreSession() -> Bool {
        guard !settings.mockMode,
              let snap = sessionStore.loadResumable(),
              !snap.mockMode else { return false }
        apply(snapshot: snap)
        diagnostics.log(.app, "Resumed session at \(snap.atcState.title) "
            + "(\(snap.transcript.count) messages) — picking up where the flight left off.")
        return true
    }

    /// Apply a snapshot's state to the live model so the conversation resumes from it.
    private func apply(snapshot snap: SessionSnapshot) {
        stateMachine.restore(to: snap.stateMachineCurrent)
        atcState = snap.atcState
        currentFacility = snap.currentFacility
        phase = snap.phase
        assignedAltitude = snap.assignedAltitude
        hasDeparted = snap.hasDeparted
        arrivalAnnounced = snap.arrivalAnnounced
        awaitingGateArrival = snap.awaitingGateArrival
        manualTuning = snap.manualTuning
        // Resume an in-progress weather diversion so the deviation card (and its
        // "clear of weather" button) survives the reconnect. A subsequent telemetry
        // tick may still clear a non-committed lifecycle if the weather is gone, but
        // a committed diversion stays put until the pilot reports clear of weather.
        if let deviation = snap.weatherDeviation { weatherDeviation = deviation }
        if !snap.transcript.isEmpty {
            transcript = snap.transcript
            let lastATC = snap.transcript.last { $0.sender == .atc }
            latestTransmission = lastATC
            lastATCTransmission = lastATC
        }
    }

    /// Forget any saved session so the next connect starts fresh (used when the
    /// pilot deliberately clears the flight or resets app data).
    private func clearSavedSession() {
        sessionStore.clear()
        lastPersistSignature = nil
        lastPersistedAt = nil
    }

    // MARK: - Context

    private func buildContext(for state: ATCState, arrivalOverride: Bool? = nil) -> ATCContext {
        let cs = engine.callsign(airline: flightPlan.airline,
                                 flightNumber: flightPlan.flightNumber,
                                 fallback: flightPlan.callsign)
        // Requesting an approach / vectors is inherently an arrival action, so callers
        // can force the arrival side even if the conversational state hasn't caught up.
        let arrival = arrivalOverride
            ?? [.descent, .approach, .final, .landing, .runwayExit, .groundArrival].contains(state)
        let metar = arrival ? destinationMETAR : departureMETAR
        let windDir = metar?.windDirection ?? 270
        let windSpeed = metar?.windSpeed ?? 8
        let cruise = flightPlan.cruiseAltitude > 0 ? flightPlan.cruiseAltitude : 37000

        // Parse any entered published procedures (deterministic, best-effort).
        let sidProc = flightPlan.sid.isEmpty ? nil
            : ProcedureParser.parseSID(flightPlan.sid, icao: flightPlan.departure)
        let starProc = flightPlan.star.isEmpty ? nil
            : ProcedureParser.parseSTAR(flightPlan.star, icao: flightPlan.destination)
        let approachProc = flightPlan.approach.isEmpty ? nil
            : ProcedureParser.parseApproach(flightPlan.approach)

        let runway = resolvedRunway(windDir: windDir, windSpeed: windSpeed, arrival: arrival,
                                    approach: approachProc)
        let taxiPlan = taxiPlanner.plan(airport: arrival ? flightPlan.destination : flightPlan.departure,
                                        runway: runway, arrival: arrival)
        let approachName = approachProc?.displayName
            ?? (flightPlan.approach.isEmpty ? "the ILS" : flightPlan.approach)

        // Initial departure heading: bearing from the field toward the first fix,
        // or the destination when no located fixes are available.
        let depCoord = airports.coordinate(for: flightPlan.departure)
        let firstWaypoint = flightPlan.waypoints.first
        let firstLocated = flightPlan.waypoints.first { $0.coordinate != nil }
        // The "resume own navigation, direct …" fix in the departure climb: once
        // airborne, the next fix *ahead* of the aircraft (not the runway-end fix the
        // aircraft has already passed); on the ground, simply the first filed fix.
        let directFix: Waypoint?
        if hasDeparted, let pos = aircraftState.coordinate {
            directFix = flightPlan.nextUnpassedWaypoint(from: pos, origin: depCoord)
        } else {
            directFix = firstWaypoint
        }
        // Initial departure heading intercepts the first fix off the runway: the
        // SID's first published fix when a SID is filed, otherwise the first filed
        // waypoint after the runway. The SID fix is matched to a located flight-plan
        // waypoint so it carries a coordinate; falls back to the first located
        // waypoint, then the destination bearing, when no fix can be located.
        let sidFirstFix: Waypoint? = sidProc?.fixes.lazy.compactMap { name in
            self.flightPlan.waypoints.first {
                $0.coordinate != nil && $0.name.caseInsensitiveCompare(name) == .orderedSame
            }
        }.first
        let intercept = (sidFirstFix ?? firstLocated)?.coordinate
            ?? airports.coordinate(for: flightPlan.destination)
        let depHeading: Int
        if let depCoord, let intercept {
            depHeading = Int(Geo.bearing(from: depCoord, to: intercept).rounded())
        } else {
            depHeading = 0
        }
        let initialClimb = settings.initialClimbAltitudeFt > 0 ? settings.initialClimbAltitudeFt : 5000

        // Ramp (simulated local/company procedure). Falls back to the generic
        // airline ramp profile when no airport-specific profile is known.
        let rampAirport = arrival ? flightPlan.destination : flightPlan.departure
        let rampProfile = RampProfile.profile(for: rampAirport)
        // Push direction is only spoken when the profile supplies one; otherwise
        // the ramp call falls back to "push approved, advise ready to taxi".
        let pushDirection = rampProfile.defaultPushDirections.first ?? ""
        let rampSpot = rampProfile.usesSpots ? (rampProfile.defaultSpotNames.first ?? "") : ""

        return ATCContext(
            callsign: cs,
            plan: flightPlan,
            assignedAltitude: assignedAltitude,
            cruiseAltitude: cruise,
            initialClimbAltitude: initialClimb,
            windDirection: windDir,
            windSpeed: windSpeed,
            squawk: deterministicSquawk(),
            runway: runway,
            taxiway: taxiPlan.taxiwaysText,
            crossingRunway: taxiPlan.crossingRunway,
            parkingTaxiway: taxiPlan.parkingTaxiway,
            approachName: approachName,
            departureFrequency: 124.300,
            centerFrequency: 132.450,
            approachFrequency: 119.700,
            towerFrequency: 118.300,
            groundFrequency: 121.800,
            rampFrequency: rampProfile.rampFrequency,
            rampProfile: rampProfile,
            pushDirection: pushDirection,
            rampSpot: rampSpot,
            gate: arrival ? flightPlan.arrivalGate : flightPlan.departureGate,
            departureHeading: ((depHeading % 360) + 360) % 360,
            firstFixName: directFix?.name ?? "",
            traconCeiling: currentTraconCeiling,
            approachInterceptAltitude: flightPlan.approachInterceptAltitude,
            sidProcedure: sidProc,
            starProcedure: starProc,
            approachProcedure: approachProc)
    }

    private func deterministicSquawk() -> String {
        let n = Int(flightPlan.flightNumber.filter { $0.isNumber }) ?? 4271
        return String(format: "%04o", (abs(n) * 7 + 1) % 4096)
    }

    private func resolvedRunway(windDir: Int, windSpeed: Int, arrival: Bool,
                                approach: Procedure? = nil) -> String {
        // On arrival a parsed approach's runway wins, then the flight plan's arrival
        // runway; on departure the flight plan's departure runway wins. Either way a
        // manual override is honored next, then the real active runway for the field
        // from the live wind (ATIS-style), and finally a wind-derived guess.
        if arrival {
            if let rwy = approach?.runway, !rwy.isEmpty { return rwy }
            if !flightPlan.arrivalRunway.isEmpty { return flightPlan.arrivalRunway }
        } else if !flightPlan.departureRunway.isEmpty {
            return flightPlan.departureRunway
        }
        if !flightPlan.runway.isEmpty { return flightPlan.runway }
        let icao = arrival ? flightPlan.destination : flightPlan.departure
        if let real = runways.activeRunway(for: icao, windDirection: windDir, windSpeed: windSpeed) {
            return real
        }
        let dir = windDir == 0 ? 270 : windDir
        var num = Int((Double(dir) / 10).rounded())
        if num <= 0 { num = 36 }
        if num > 36 { num -= 36 }
        return String(format: "%02d", num)
    }

    // MARK: - Flight plan

    func syncFlightPlanFromSettings() {
        var plan = FlightPlan()
        plan.callsign = settings.callsign
        plan.airline = settings.airline.isEmpty && settings.mockMode ? "United" : settings.airline
        plan.flightNumber = settings.flightNumber.isEmpty && settings.mockMode ? "598" : settings.flightNumber
        // Resolve an airline prefix typed into the Callsign field (e.g. "UAL598")
        // into a telephony name + flight number when not otherwise specified.
        if plan.airline.isEmpty, plan.flightNumber.isEmpty,
           let parsed = AirlineDatabase.parse(plan.callsign) {
            plan.airline = parsed.telephony
            plan.flightNumber = parsed.flightNumber
        }
        plan.departure = settings.departure.isEmpty && settings.mockMode ? mock.route.departure : settings.departure
        plan.destination = settings.destination.isEmpty && settings.mockMode ? mock.route.destination : settings.destination
        plan.alternate = settings.alternate
        plan.cruiseAltitude = settings.cruiseAltitude > 0 ? settings.cruiseAltitude
            : (settings.mockMode ? mock.route.cruiseAltitude : 0)
        plan.runway = settings.runway
        plan.sid = settings.sid
        plan.star = settings.star
        plan.approach = settings.approach
        plan.departureGate = settings.departureGate
        plan.arrivalGate = settings.arrivalGate
        plan.manualOverride = !settings.departure.isEmpty || !settings.destination.isEmpty
        if settings.mockMode { plan.waypoints = mock.route.waypoints }
        flightPlan = plan
    }

    func applyManualOverrides() {
        syncFlightPlanFromSettings()
        flightPlan.manualOverride = true
        diagnostics.log(.app, "Manual flight-plan overrides applied.")
    }

    /// Clear every manually-entered override field and revert to the live (or mock)
    /// flight plan. In live mode this forces an immediate re-read so the Infinite
    /// Flight plan repopulates the fields that were being overridden.
    func clearManualOverrides() {
        settings.callsign = ""
        settings.airline = ""
        settings.flightNumber = ""
        settings.departure = ""
        settings.destination = ""
        settings.alternate = ""
        settings.cruiseAltitude = 0
        settings.runway = ""
        settings.sid = ""
        settings.star = ""
        settings.approach = ""
        settings.departureGate = ""
        settings.arrivalGate = ""
        // Re-sync drops manualOverride (both endpoints are now empty) so Connect data
        // is no longer pinned back by the override flag.
        syncFlightPlanFromSettings()
        diagnostics.log(.app, "Manual flight-plan overrides cleared.")
        if !settings.mockMode { Task { await connect.refreshFlightPlan() } }
    }

    /// Re-read the flight plan from the active source. In live mode this forces an
    /// immediate re-read from Infinite Flight (use after editing the plan mid-flight);
    /// in mock mode it rebuilds from the current settings/mock route.
    func refreshFlightPlan() {
        if settings.mockMode {
            syncFlightPlanFromSettings()
            diagnostics.log(.app, "Flight plan refreshed from mock route.")
        } else {
            Task { await connect.refreshFlightPlan() }
            diagnostics.log(.app, "Requested flight-plan refresh from Infinite Flight.")
        }
    }

    // MARK: - Push-to-talk

    /// Last recognized spoken phrase and the intent it mapped to (for the UI).
    @Published var lastSpokenText: String = ""
    @Published var lastSpokenIntent: PilotIntent?

    /// Parse a recognized utterance into a pilot intent and perform it. Returns
    /// the recognized intent. Deterministic — no AI in the mapping.
    @discardableResult
    func handleSpokenInput(_ text: String) -> PilotIntent {
        let intent = intentParser.parse(text)
        lastSpokenText = text
        lastSpokenIntent = intent
        diagnostics.log(.atc, "PTT heard: \"\(text)\" -> \(intent.title)")
        // The user already spoke this; don't re-speak the pilot transmission.
        pilotInputViaVoice = true
        perform(intent)
        pilotInputViaVoice = false
        return intent
    }

    /// Dispatch a recognized intent to the matching pilot action.
    func perform(_ intent: PilotIntent) {
        switch intent {
        case .readback: readBack()
        case .sayAgain: sayAgain()
        case .unable: unable()
        case .wilco: wilco()
        case .requestClearance: requestClearance()
        case .requestPushback: requestPushback()
        case .requestEngineStart: requestEngineStart()
        case .requestTaxi: requestTaxi()
        case .readyForDeparture: reportReadyForDeparture()
        case .requestTakeoff: requestTakeoff()
        case .requestHigher: requestHigher()
        case .requestLower: requestLower()
        case .requestVectors: requestVectors()
        case .requestApproach: requestApproach()
        case .rideReport: requestRideReport()
        case .destinationWeather: requestDestinationWeather()
        case .checkIn: requestHandoff()
        case .unknown: break
        }
    }

    // MARK: - Pilot button actions

    func readBack() {
        // Prefer the read-back the controller composed for its *last* call (frequency
        // hand-offs, vectors, altitude changes) so the Read Back button always echoes
        // the message actually on the radio — not a read-back re-derived from the
        // conversational state, which lags behind double calls like "cleared the ILS,
        // contact Tower".
        if let rb = lastATCTransmission?.readback {
            postPilot(ATCTransmission(sender: .pilot, facility: rb.facility,
                                      displayText: rb.displayText, spokenText: rb.spokenText))
            // A frequency hand-off: only after reading it back do we move the radio to
            // the next controller (switching the active frequency button).
            if let tune = rb.tuneTo {
                tunedFacility = tune
                currentFacility = tune
                // While the pilot is tuning by hand, hold the new controller's
                // instruction until they check in on the freq they just acknowledged.
                if manualTuning { pendingCheckInFacility = tune }
                persistSession()
            }
            return
        }
        let c = buildContext(for: atcState)
        postPilot(pilotEngine.readback(for: atcState, context: c))
    }

    func wilco() {
        let c = buildContext(for: atcState)
        postPilot(pilotEngine.wilco(context: c, facility: currentFacility))
    }

    func sayAgain() {
        let c = buildContext(for: atcState)
        postPilot(pilotEngine.sayAgain(context: c, facility: currentFacility))
        if let last = lastATCTransmission {
            let repeated = ATCTransmission(sender: .atc, facility: last.facility,
                                           displayText: last.displayText, spokenText: last.spokenText,
                                           readback: last.readback)
            post(repeated, speak: true)
        }
    }

    func unable() {
        let c = buildContext(for: atcState)
        postPilot(pilotEngine.unable(context: c, facility: currentFacility))
        // Deterministic alternate controller response.
        let target = max(assignedAltitude, c.initialClimbAltitude)
        let alt = engine.formatAltDisplay(target)
        var tx = ATCTransmission(sender: .atc, facility: currentFacility,
                                 displayText: "\(c.callsign.display), roger, maintain \(alt), advise able to comply.",
                                 spokenText: "\(c.callsign.spoken), roger, maintain \(Phonetic.altitude(target)), advise able to comply.")
        tx.readback = ATCTransmission.Readback(
            displayText: "Maintain \(alt), \(c.callsign.display).",
            spokenText: "Maintain \(Phonetic.altitude(target)), \(c.callsign.spoken).",
            facility: currentFacility)
        post(tx, speak: true)
    }

    func requestHigher() {
        let c = buildContext(for: atcState)
        let target = nextAltitude(from: max(assignedAltitude, aircraftAltInt()), up: true)
        postPilot(pilotEngine.requestHigher(context: c, target: target))
        let blocked = rideReportItems.contains { $0.severity >= .moderate && ($0.altitudeBand?.contains(target) ?? false) }
        if blocked {
            post(deny(c, reason: "unable higher, traffic and reported turbulence at that level"), speak: true)
        } else {
            assignedAltitude = target
            var tx = engine.climbMaintain(cs: c.callsign, altitude: target)
            tx.readback = altitudeReadback("Climb", altitude: target, callsign: c.callsign, facility: currentFacility)
            post(tx, speak: true)
        }
    }

    func requestLower() {
        let c = buildContext(for: atcState)
        let target = nextAltitude(from: max(assignedAltitude, aircraftAltInt()), up: false)
        postPilot(pilotEngine.requestLower(context: c, target: target))
        var tx = engine.descendPilotsDiscretion(cs: c.callsign, altitude: target)
        tx.readback = altitudeReadback("Descend", altitude: target, callsign: c.callsign, facility: currentFacility)
        post(tx, speak: true)
        assignedAltitude = target
    }

    /// A pilot read-back of an assigned altitude, attached to a controller call so the
    /// Read Back button echoes "<verb> and maintain <alt>" instead of a read-back
    /// re-derived from the (now stale) conversational state.
    private func altitudeReadback(_ verb: String, altitude: Int,
                                  callsign cs: PhraseologyEngine.Callsign,
                                  facility: ATCFacility) -> ATCTransmission.Readback {
        ATCTransmission.Readback(
            displayText: "\(verb) and maintain \(engine.formatAltDisplay(altitude)), \(cs.display).",
            spokenText: "\(verb) and maintain \(Phonetic.altitude(altitude)), \(cs.spoken).",
            facility: facility)
    }

    func requestVectors() {
        let c = buildContext(for: atcState, arrivalOverride: true)
        postPilot(pilotEngine.requestVectors(context: c))
        // Name the approach as "<type> runway <rwy>" (e.g. "ILS runway 01R") rather
        // than a display name that already embeds the runway, to avoid "… ILS RWY
        // 01R runway 01R approach".
        let rwy = c.approachProcedure?.runway ?? c.runway
        // A real 30° intercept to the final approach course, turning toward the
        // extended centerline from whichever side the aircraft is on — rather than
        // just handing back the current heading.
        let hdg = approachInterceptHeading(runway: rwy)
        let typeD = c.approachProcedure?.approachType?.display ?? (c.approachName.isEmpty ? "ILS" : c.approachName)
        let typeS = c.approachProcedure?.approachType?.spoken ?? (c.approachName.isEmpty ? "I L S" : c.approachName)
        var tx = ATCTransmission(sender: .atc, facility: .approach,
                                 displayText: "\(c.callsign.display), fly heading \(String(format: "%03d", hdg)), vectors for the \(typeD) runway \(rwy) approach.",
                                 spokenText: "\(c.callsign.spoken), fly heading \(Phonetic.heading(hdg)), vectors for the \(typeS) runway \(Phonetic.runway(rwy)) approach.")
        // Read back the heading (the safety-critical element), not a state-derived call.
        tx.readback = ATCTransmission.Readback(
            displayText: "Heading \(String(format: "%03d", hdg)), \(c.callsign.display).",
            spokenText: "Heading \(Phonetic.heading(hdg)), \(c.callsign.spoken).",
            facility: .approach)
        post(tx, speak: true)
    }

    /// The heading to fly for approach vectors: a 30° intercept to the landing
    /// runway's final approach course, turning toward the extended centerline from
    /// whichever side the aircraft is on. Falls back to the current heading when
    /// the runway or position data is unavailable (e.g. manual practice with no
    /// telemetry, or an airport not in the coordinate database).
    private func approachInterceptHeading(runway: String) -> Int {
        let fallback = ApproachIntercept.normalizedHeading(aircraftState.heading ?? 270)
        guard let finalCourse = RunwayDatabase.heading(forRunway: runway),
              let aircraft = aircraftState.coordinate, aircraft.isValid,
              let airport = airports.coordinate(for: flightPlan.destination), airport.isValid else {
            return fallback
        }
        return ApproachIntercept.heading(finalCourse: finalCourse,
                                         aircraft: aircraft,
                                         runwayReference: airport)
    }

    func requestApproach() {
        let c = buildContext(for: atcState, arrivalOverride: true)
        postPilot(pilotEngine.requestApproach(context: c))
        // Prefer the procedure-aware clearance (names the runway once) when the
        // approach parsed; otherwise fall back to the plain string form. Either way,
        // carry the matching read-back so Read Back echoes the approach clearance.
        var tx: ATCTransmission
        if let approach = c.approachProcedure {
            tx = engine.clearedApproach(cs: c.callsign, procedure: approach, runway: c.runway)
        } else {
            tx = engine.clearedApproach(cs: c.callsign, approach: flightPlan.approach, runway: c.runway)
        }
        tx.readback = pilotEngine.readback(for: .final, context: c).asReadback(facility: .approach)
        post(tx, speak: true)
    }

    /// Check in on the currently tuned frequency. Posts the pilot's call-up and the
    /// controller's next instruction for the facility we're tuned to. This is the
    /// deliberate "check in" the pilot makes after switching frequency (tuning no
    /// longer checks in automatically).
    /// Live aircraft altitude rounded to the nearest 100 ft for a check-in report,
    /// or nil when no usable altitude telemetry is available (e.g. manual practice).
    private func checkInAltitude() -> Int? {
        guard let msl = aircraftState.altitudeMSL, msl > 0 else { return nil }
        return Int((msl / 100).rounded()) * 100
    }

    func requestHandoff() {
        guard !companionStandby else { return }
        // Checking in satisfies any pending hand-off the controller prompted: the new
        // controller now speaks for itself, so the semi-automatic flow resumes.
        pendingCheckInFacility = nil
        guard let target = nextState(workedBy: currentFacility, after: stateMachine.current),
              target != stateMachine.current else {
            // Nothing new ahead for this controller — a plain check-in / radar-contact
            // exchange (e.g. a same-sector Center re-check-in).
            let c = buildContext(for: atcState)
            postPilot(pilotEngine.requestHandoff(context: c, facility: currentFacility,
                                                 currentAltitude: checkInAltitude(),
                                                 targetAltitude: assignedAltitude,
                                                 onGround: aircraftState.onGround ?? false))
            post(engine.radarContact(cs: c.callsign, facility: currentFacility), speak: true)
            return
        }
        if !target.isManualGroundFlow { hasDeparted = true }
        let c = buildContext(for: target)
        // The pilot checks in on the tuned frequency. Because the pilot initiated the
        // switch, the controller does not precede its reply with a "contact …"
        // hand-off (announceHandoff: false). The pilot reports altitude relative to
        // the currently assigned altitude (still the previous controller's assignment
        // here — advanceAndPost updates it afterwards).
        postPilot(pilotEngine.requestHandoff(context: c, facility: currentFacility,
                                             currentAltitude: checkInAltitude(),
                                             targetAltitude: assignedAltitude,
                                             onGround: aircraftState.onGround ?? false))
        advanceAndPost(to: target, context: c, announceHandoff: false)
        if target == .parked, !arrivalAnnounced {
            announceArrival()
            arrivalAnnounced = true
        }
        if settings.mockMode { mock.setPhase(mockPhase(for: target)) }
    }

    // MARK: - Departure ground flow (pilot-driven)

    /// Post the pilot's ground request, advance the state machine to `state`, and
    /// post the controller's reply. Keeps the pre-departure sequence in order so no
    /// phase (pushback, engine start, taxi, …) is skipped.
    private func groundFlow(_ pilotRequest: ATCTransmission, to state: ATCState) {
        postPilot(pilotRequest)
        advanceAndPost(to: state, context: buildContext(for: state))
    }

    func requestClearance() {
        groundFlow(pilotEngine.requestClearance(context: buildContext(for: .clearance)), to: .clearance)
    }

    func requestPushback() {
        groundFlow(pilotEngine.requestPushback(context: buildContext(for: .pushback)), to: .pushback)
    }

    func requestEngineStart() {
        groundFlow(pilotEngine.requestEngineStart(context: buildContext(for: .engineStart)), to: .engineStart)
    }

    func requestTaxi() {
        // While still on the departure Ramp (pushed back / engines started), Ramp
        // does not issue the taxi clearance — it only hands the pilot to Ground.
        // The pilot then requests taxi again on Ground for the actual clearance.
        if onDepartureRampPreTaxi {
            handOffDepartureRampToGround()
            return
        }
        groundFlow(pilotEngine.requestTaxi(context: buildContext(for: .groundTaxi)), to: .groundTaxi)
    }

    /// True when the pilot is still on the departure Ramp frequency (after pushback /
    /// engine start) and Ramp has not yet handed them to Ground. While true, a taxi
    /// request makes Ramp hand off to Ground only; Ground issues the taxi clearance
    /// once the pilot re-requests taxi there. No-ramp airports (pilot already on
    /// Ground for the push) taxi in a single step.
    private var onDepartureRampPreTaxi: Bool {
        guard !hasDeparted, currentFacility == .ramp,
              [.pushback, .engineStart].contains(stateMachine.current) else { return false }
        return buildContext(for: .pushback).rampProfile.rampType != .none
    }

    /// Ramp hands the pilot to Ground for taxi. Posts the pilot's "ready to taxi" and
    /// Ramp's "contact Ground" — but does NOT advance the flow or issue a taxi
    /// clearance. Marks Ground as the tuned facility so the next taxi request (and the
    /// response buttons) are on Ground, where the actual taxi clearance is given.
    private func handOffDepartureRampToGround() {
        let c = buildContext(for: .engineStart)
        postPilot(rampEngine.pushComplete(cs: c.callsign))
        post(rampEngine.contactGround(cs: c.callsign, groundFrequency: c.groundFrequency,
                                      spot: c.rampSpot), speak: true)
        tunedFacility = .ground
        currentFacility = .ground
        persistSession()
    }

    /// Report ready for departure. Tower responds "line up and wait"; the Takeoff
    /// button (or tuning Tower and checking in) then yields the takeoff clearance,
    /// and the pilot drives every controller change from there with the frequency
    /// buttons.
    func reportReadyForDeparture() {
        groundFlow(pilotEngine.readyForDeparture(context: buildContext(for: .lineUpWait)), to: .lineUpWait)
    }

    func requestTakeoff() {
        groundFlow(pilotEngine.requestTakeoff(context: buildContext(for: .towerDeparture)), to: .towerDeparture)
        // Cleared for takeoff — the pilot has departed, so switch the response
        // buttons from the ground flow to the enroute set (which includes Check In
        // for calling up Departure and the rest of the airborne controllers).
        hasDeparted = true
    }

    // MARK: - Weather button actions

    func requestRideReport() {
        let c = buildContext(for: atcState)
        let facility = currentFacility
        postPilot(pilotEngine.requestRideReports(context: c))
        Task {
            await refreshWeather()
            recomputeRideItems()
            post(rideEngine.rideReport(assessment: rideAssessment, items: rideReportItems, callsign: c.callsign), speak: true)
            // Acknowledge the report — an informational reply, so a courtesy "Roger".
            postPilot(pilotEngine.roger(context: c, facility: facility))
        }
    }

    func requestDestinationWeather() {
        let c = buildContext(for: atcState)
        let facility = currentFacility
        let dest = flightPlan.destination
        postPilot(pilotEngine.requestWeather(context: c, airport: dest.isEmpty ? "destination" : dest))
        Task {
            await refreshWeather()
            post(rideEngine.destinationWeather(metar: destinationMETAR, callsign: c.callsign, icao: dest), speak: true)
            // Acknowledge the weather read-out with a courtesy "Roger".
            postPilot(pilotEngine.roger(context: c, facility: facility))
        }
    }

    func requestLowerDueRide() {
        let c = buildContext(for: atcState)
        recomputeRideItems()
        let target = nextAltitude(from: max(assignedAltitude, aircraftAltInt()), up: false)
        postPilot(pilotEngine.requestLower(context: c, target: target))
        var tx = ATCTransmission(sender: .atc, facility: .center,
                                 displayText: "\(c.callsign.display), descend and maintain \(engine.formatAltDisplay(target)) for a smoother ride, report conditions.",
                                 spokenText: "\(c.callsign.spoken), descend and maintain \(Phonetic.altitude(target)) for a smoother ride, report conditions.")
        tx.readback = ATCTransmission.Readback(
            displayText: "Descend and maintain \(engine.formatAltDisplay(target)), \(c.callsign.display).",
            spokenText: "Descend and maintain \(Phonetic.altitude(target)), \(c.callsign.spoken).",
            facility: .center)
        post(tx, speak: true)
        assignedAltitude = target
    }

    private func deny(_ c: ATCContext, reason: String) -> ATCTransmission {
        ATCTransmission(sender: .atc, facility: currentFacility,
                        displayText: "\(c.callsign.display), \(reason).",
                        spokenText: "\(c.callsign.spoken), \(reason).")
    }

    private func nextAltitude(from current: Int, up: Bool) -> Int {
        let step = 2000
        let base = current <= 0 ? (flightPlan.cruiseAltitude > 0 ? flightPlan.cruiseAltitude : 35000) : current
        let target = up ? base + step : base - step
        return max(4000, target)
    }

    private func aircraftAltInt() -> Int { Int(aircraftState.altitudeMSL ?? 0) }

    // MARK: - Weather refresh

    func refreshWeather() async {
        if settings.mockMode {
            let metars = mock.sampleMETARs()
            departureMETAR = metars.first { $0.icao == flightPlan.departure } ?? metars.first
            destinationMETAR = metars.first { $0.icao == flightPlan.destination } ?? metars.dropFirst().first
            destinationTAF = mock.sampleTAF()
            pireps = mock.samplePIREPs()
            // Mock precipitation cell (crosses the route ~40 NM ahead) drives the
            // offline weather-deviation demo. Live radar sampling is Mock-Mode-off.
            radarOverlay.mockCells = mock.sampleRadarCells()
            radarOverlay.sampledCells = []
            lastAviationWeatherUpdate = Date()
            recomputeRideItems()
            recomputeWeatherHazards()
            weatherStatus = "Mock weather loaded (\(metars.count) METARs, \(pireps.count) PIREPs)."
            diagnostics.weatherEndpointStatus = "Mock mode — sample data"
            return
        }

        // Live mode: no vector precipitation cells (radar is the NOAA image overlay).
        radarOverlay.mockCells = []

        do {
            var ids = [flightPlan.departure, flightPlan.destination, flightPlan.alternate]
                .filter { !$0.isEmpty }
            if let nearest = aircraftState.nearestAirport, !nearest.isEmpty { ids.append(nearest) }
            let metars = try await weatherService.metars(for: ids)
            departureMETAR = metars.first { $0.icao == flightPlan.departure }
            destinationMETAR = metars.first { $0.icao == flightPlan.destination }
            alternateMETAR = metars.first { $0.icao == flightPlan.alternate }
            destinationTAF = try? await weatherService.taf(for: flightPlan.destination)
            pireps = (try? await weatherService.pireps()) ?? []
            sigmets = (try? await weatherService.airSigmets()) ?? []
            await sampleLivePrecipitation()
            lastAviationWeatherUpdate = Date()
            recomputeRideItems()
            recomputeWeatherHazards()
            weatherStatus = "Loaded \(metars.count) METARs, \(pireps.count) PIREPs, \(sigmets.count) SIGMETs."
        } catch {
            let msg = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            weatherStatus = "Weather unavailable: \(msg)"
            diagnostics.log(.weather, "Weather fetch failed: \(msg)")
            recomputeWeatherHazards()
        }
    }

    func recomputeRideItems() {
        routeAnalyzer.config.corridorNM = settings.routeCorridorNM
        routeAnalyzer.config.altitudeBandFt = settings.altitudeBandFt
        guard let pos = aircraftState.coordinate ?? airports.coordinate(for: flightPlan.departure) else {
            rideReportItems = []
            routeSigmets = []
            return
        }
        let end = airports.coordinate(for: flightPlan.destination)
            ?? flightPlan.nextWaypoint(from: pos)?.coordinate
        let liveAlt = aircraftState.altitudeMSL ?? Double(flightPlan.cruiseAltitude)
        // Ride reports describe the cruise portion of the route ahead, so evaluate a
        // PIREP's altitude relevance against the planned cruise level (within the
        // ±tolerance band) rather than the live altitude — otherwise en-route
        // turbulence at cruise is filtered out while the aircraft is still climbing.
        // Fall back to the live altitude before a cruise level is set.
        let referenceAlt = flightPlan.cruiseAltitude > 0 ? Double(flightPlan.cruiseAltitude) : liveAlt
        let nearestFix = flightPlan.nextWaypoint(from: pos)?.name
        rideReportItems = routeAnalyzer.relevantReports(pireps: pireps, position: pos,
                                                        routeEnd: end, altitudeFt: referenceAlt,
                                                        nearestFix: nearestFix)
        let arrivalPhase = [.descent, .approach, .landing, .taxiIn, .parked].contains(phase)
        let nearMETAR = arrivalPhase ? (destinationMETAR ?? departureMETAR) : (departureMETAR ?? destinationMETAR)
        // Only SIGMETs whose area lies along the route may raise the ride index — a
        // nationwide turbulence advisory far from the route must not read as "severe".
        // Test the full route ahead (aircraft → remaining fixes → destination) so an
        // advisory on a later leg past a turn is caught, not just one on the straight
        // line to the destination.
        let routePolyline = [pos] + upcomingRouteCoordinates(from: pos)
        routeSigmets = routeAnalyzer.relevantSigmets(sigmets, routePolyline: routePolyline)
        // Wind shear is a low-level, surface-driven effect, so it keys off the live
        // altitude; the PIREP altitude band above keys off the cruise reference.
        rideAssessment = turbulenceModel.assess(items: rideReportItems, sigmets: routeSigmets,
                                                metar: nearMETAR, altitudeFt: liveAlt)
    }

    // MARK: - Radar precipitation + weather deviation
    //
    // Simulation/training/entertainment only — never real-world flight safety
    // guidance. Radar precipitation comes from the approved NOAA/NWS source where
    // NOAA provides coverage; outside coverage the overlay reports unavailable and
    // only existing aviation advisories (SIGMET etc.) drive the deviation flow.

    /// Use the mock precipitation provider in Mock Mode, or the live NOAA → OPERA →
    /// NASA selection otherwise.
    private func applyRadarProvider() {
        precipService.useMockProvider(settings.mockMode)
    }

    /// Throttle state for continuously resampling live precipitation as the aircraft
    /// moves (so the deviation line tracks the weather rather than only updating on a
    /// manual refresh). Guards against overlapping fetches.
    private var lastPrecipSampleAt: Date?
    private var lastPrecipSamplePos: CLLocationCoordinate2D?
    private var isSamplingPrecip = false
    /// Actual OPERA/CIRRUS composite bytes downloaded (latest / session total), read
    /// from the shared composite store for the Weather Diagnostics data-usage row.
    private var ordDataUsage: (last: Int, total: Int) = (0, 0)

    /// Reset the weather-deviation interaction (between flights / on mode change).
    private func resetWeatherDeviation() {
        weatherDeviation.reset()
        weatherDeviation.state = .none
        activeWeatherConflict = nil
        weatherHazards = []
        radarOverlay.sampledCells = []
        weatherHandled = false
        mockWeatherAdvisoryIssued = false
        lastConflictSeenAt = nil
        lastPrecipSampleAt = nil
        lastPrecipSamplePos = nil
    }

    /// The precipitation overlay image URL for the map's visible region, or nil when
    /// the overlay is off / uncovered / in Mock Mode (which draws vector cells).
    func radarImageURL(region: MKCoordinateRegion, size: CGSize) -> URL? {
        guard !settings.mockMode, radarOverlay.shouldDisplay else { return nil }
        return precipService.imageURL(for: region, size: size)
    }

    /// Rebuild the precipitation overlay descriptive state, the normalized hazard
    /// list, and the active route-weather conflict. Selects a provider in
    /// NOAA → OPERA → NASA order. Pure/cheap; safe to call every tick.
    func recomputeWeatherHazards() {
        let positions = [aircraftState.coordinate,
                         airports.coordinate(for: flightPlan.departure),
                         airports.coordinate(for: flightPlan.destination)].compactMap { $0 }
        let enabled = settings.noaaRadarOverlay == .autoWhereAvailable
        let provider = enabled ? precipService.selectedProvider(for: positions) : nil
        let coverage = enabled && provider != nil

        radarOverlay.isEnabled = enabled
        radarOverlay.coverageAvailable = coverage
        radarOverlay.opacity = settings.radarOpacity
        if let provider {
            radarOverlay.sourceDescription = provider.displayName
            radarOverlay.attributionText = provider.attributionText
            radarOverlay.coverageLabel = provider.coverageDescription
            radarOverlay.layerType = provider.layerType
            radarOverlay.layerLabel = provider.uiLayerLabel
        }
        if coverage { radarOverlay.lastUpdated = Date() }
        weatherDeviation.radarCoverageAvailable = coverage
        weatherDeviation.radarSourceDescription = provider?.displayName ?? radarOverlay.sourceDescription

        let hazards = buildWeatherHazards(provider: provider)
        weatherHazards = hazards

        guard let pos = aircraftState.coordinate ?? airports.coordinate(for: flightPlan.departure),
              pos.isValid else {
            activeWeatherConflict = nil
            weatherDeviationPreviews = []
            updateWeatherDiagnostics(conflict: nil)
            return
        }
        let course = currentCourse(from: pos)
        let detected = conflictDetector.detectConflict(position: pos, course: course,
                                                       groundspeedKnots: aircraftState.groundSpeed,
                                                       phase: phase, hazards: hazards,
                                                       waypoints: flightPlan.waypoints,
                                                       routeAhead: upcomingRouteCoordinates(from: pos),
                                                       rejoinCap: weatherRejoinCap())
        // Apply confirm-clear hysteresis so the mint line / banner don't blink out on
        // a noisy resample that momentarily loses a storm that's really still ahead.
        let conflict = resolveConflictWithHysteresis(detected: detected)
        activeWeatherConflict = conflict

        // The weather ahead has cleared: forget the "handled" flag and roll back a
        // not-yet-committed deviation lifecycle so a new conflict prompts afresh.
        // A turbulence / icing ride advisory (no precip conflict) keeps its lifecycle,
        // so it isn't torn down on every telemetry tick while it's still along route.
        if conflict == nil, activeRideSigmet == nil {
            weatherHandled = false
            mockWeatherAdvisoryIssued = false
            switch weatherDeviation.state {
            case .weatherAheadDetected, .advisoryIssued, .awaitingPilotIntentions, .deviationRequested:
                weatherDeviation.reset()
            default:
                break
            }
        }

        // Mock Mode auto-issues the advisory once so the offline demo plays out.
        maybeAutoIssueMockAdvisory(conflict: conflict)
        // Drive the deviation turns off the aircraft's progress, most-imminent first:
        //   1. a held beginning turn fires once the aircraft reaches the mint line's
        //      turn-out (a deviation approved while drawn ahead — "expect the turn …");
        //   2. else, while vectoring, the interior turns fire at each deviation vertex;
        //   3. else, reaching the rejoin end without a clear-of-weather auto-resumes.
        // At most one fires per tick, so they never race.
        if !maybeIssueDeviationStartTurn() {
            if !maybeIssueWeatherRejoinTurn() {
                maybeAutoResumeAtRouteIntercept()
            }
        }
        // Faint preview lines for the systems ahead beyond the one drawn solid.
        weatherDeviationPreviews = computeWeatherDeviationPreviews()
        updateWeatherDiagnostics(conflict: conflict)
    }

    /// Apply confirm-clear hysteresis to the raw per-tick detection so the mint line
    /// and "contact ATC" banner stay put through a noisy radar resample. Live radar
    /// sampling is noisy: a storm that is really still ahead can drop out of a single
    /// sample and return on the next, which — read straight through — blinks the mint
    /// line and banner on and off at the resample cadence. So once a conflict is
    /// shown we keep returning it until the route has tested continuously clear for
    /// `weatherClearConfirmWindow` (a *confirmed* clean route), rather than dropping
    /// it the instant one sample comes back empty. A committed deviation is never
    /// torn down here — the pilot is already flying the mint line; it settles only on
    /// clear-of-weather.
    private func resolveConflictWithHysteresis(detected: RouteWeatherConflict?) -> RouteWeatherConflict? {
        if let detected {
            lastConflictSeenAt = Date()
            return detected
        }
        // Nothing detected this tick. With no conflict currently shown, stay clear.
        guard let held = activeWeatherConflict else {
            lastConflictSeenAt = nil
            return nil
        }
        // A committed deviation keeps its conflict/mint line regardless of a momentary
        // clear — the pilot is following the approved reroute.
        if weatherDeviation.state.isCommittedDeviation { return held }
        // Otherwise hold the last shown conflict until the clear is confirmed.
        if let since = lastConflictSeenAt, Date().timeIntervalSince(since) < weatherClearConfirmWindow {
            return held
        }
        lastConflictSeenAt = nil
        return nil
    }

    /// Normalize the current weather into the hazards the conflict detector routes
    /// around. The **only** driver of the weather-deviation flow (the mint line) is
    /// moderate-or-greater precipitation: the hand-authored cells in Mock Mode, or
    /// the cells sampled from the live NOAA/OPERA radar image otherwise. The
    /// detector then threads the widest clear gap between those cells.
    ///
    /// SIGMET/AIRMET advisories are intentionally **not** fed here. A SIGMET polygon
    /// is a coarse, often huge advisory box, not a precipitation shape, so routing
    /// around it produces reroutes that ignore where the storms actually are.
    /// SIGMETs still shade the route map, populate the SIGMET card, and raise the
    /// composite ride index (`recomputeRideItems` / `routeSigmets`) — they just
    /// don't steer the deviation.
    private func buildWeatherHazards(provider: RadarPrecipitationProvider?) -> [WeatherHazard] {
        guard radarOverlay.isEnabled, radarOverlay.coverageAvailable else { return [] }
        let providerConfidence = provider?.confidence ?? .high
        let providerLabel = provider?.uiLayerLabel ?? "Radar precipitation"
        let precipCells = settings.mockMode ? radarOverlay.mockCells : radarOverlay.sampledCells
        return precipCells.compactMap { cell in
            guard cell.intensity >= .moderate else { return nil }
            return WeatherHazard(
                source: .noaaRadar, providerID: provider?.id,
                phenomenon: .precipitation, intensity: cell.intensity,
                geometry: .polygon(cell.polygon), confidence: providerConfidence,
                movementDirectionDegrees: cell.movementDirectionDegrees,
                movementSpeedKnots: cell.movementSpeedKnots,
                notes: providerLabel)
        }
    }

    /// Resample live precipitation so the deviation lines track evolving weather instead
    /// of only updating on a manual refresh. Single-flighted so fetches never overlap.
    /// Call it from the telemetry loop; it re-runs the conflict detection once fresh
    /// cells land. The sampled region is the whole route (not a window ahead of the
    /// aircraft), so it barely changes as the aircraft flies — resampling is driven
    /// mainly by staleness, with a large movement threshold as a backstop, to avoid
    /// re-fetching the bigger whole-route image on every mile flown.
    private func maybeResamplePrecipitation() {
        guard appActive, !settings.mockMode, settings.noaaRadarOverlay == .autoWhereAvailable,
              !isSamplingPrecip, let pos = aircraftState.coordinate, pos.isValid else { return }
        let now = Date()
        let movedFar = lastPrecipSamplePos.map { Geo.distanceNM(from: $0, to: pos) > 100 } ?? true
        let stale = lastPrecipSampleAt.map { now.timeIntervalSince($0) > 60 } ?? true
        guard movedFar || stale else { return }
        isSamplingPrecip = true
        Task { @MainActor in
            await sampleLivePrecipitation()
            isSamplingPrecip = false
            // Record real ORD composite data usage for the diagnostics row.
            ordDataUsage = await OPERACompositeStore.shared.dataUsage()
            // Re-run detection against the fresh cells so the mint line updates now.
            recomputeWeatherHazards()
        }
    }

    /// Sample the live radar image into moderate-or-greater precipitation cells over the
    /// **whole flight-plan corridor** (the aircraft and every fix ahead through the
    /// destination — the entire route from the gate, the remaining route in flight —
    /// widened laterally). These cells are the sole input to the weather-
    /// deviation flow and the strategic preview, which thread the widest clear gap
    /// between them (the "raster → cell" step in `docs/Weather.md`). Sampling the entire
    /// route — not just a window ahead — is what lets every system's reroute be seen at
    /// once, including from the gate before takeoff.
    ///
    /// Three properties keep it usable rather than coarse or flickery:
    /// 1. The sample **resolution scales with the corridor's size** (`RadarImageSampler
    ///    .sampleGrid`, ~2 NM/pixel, capped), so a long route still resolves individual
    ///    storms near the aircraft instead of the old fixed-grid whole-route sample that
    ///    under-resolved them and "cleared" weather that was still dead ahead.
    /// 2. On a fetch/decode failure it **keeps the last good cells** instead of wiping
    ///    them, so a transient network hiccup doesn't drop the reroute. Cells are
    ///    replaced only on a successful sample (which may legitimately be empty when the
    ///    route really is clear).
    /// 3. The region is route-relative (not aircraft-relative), so it barely changes as
    ///    the aircraft flies — resampling is driven mainly by staleness, not movement.
    ///
    /// Best-effort and **true-radar only** (NOAA/OPERA); a satellite estimate or no
    /// coverage yields no cells. Simulation/training only.
    private func sampleLivePrecipitation() async {
        guard settings.noaaRadarOverlay == .autoWhereAvailable else {
            radarOverlay.sampledCells = []
            return
        }
        guard let pos = aircraftState.coordinate ?? airports.coordinate(for: flightPlan.departure),
              pos.isValid else { return }   // no position — keep the last good cells

        // The whole flight plan ahead: the aircraft plus every located fix still in front
        // of it, through the destination. At the gate this is the entire route; in flight
        // it's the remaining route (so resolution isn't spent on the leg already flown).
        // `region(enclosing:)` boxes it; the box is widened below so the corridor — not
        // just the route centerline — is captured.
        var focus: [CLLocationCoordinate2D] = [pos]
        focus.append(contentsOf: upcomingRouteCoordinates(from: pos).filter { $0.isValid })

        lastPrecipSampleAt = Date()
        lastPrecipSamplePos = pos

        guard var region = PrecipitationOverlayService.region(enclosing: focus) else {
            radarOverlay.sampledCells = []
            return
        }
        // Widen the box by ~`corridorPadNM` on every side so weather whose body sits off
        // the centerline (but whose edge crosses the route) is still captured.
        let corridorPadNM = 60.0
        let padLat = corridorPadNM / 60.0
        let padLon = corridorPadNM / (60.0 * max(0.2, cos(region.center.latitude * .pi / 180)))
        region.span.latitudeDelta += 2 * padLat
        region.span.longitudeDelta += 2 * padLon

        guard let provider = precipService.selectedProvider(for: region),
              provider.supportsTrueRadar else {
            // No true-radar coverage for this route → genuinely no radar cells here.
            radarOverlay.sampledCells = []
            return
        }

        // "Reduce cellular data": on a cellular / expensive link, skip the background
        // download of the megabyte-scale EUMETNET OPERA composite that this sampling
        // triggers. The map overlay still loads when the user opens the Weather view
        // (user-initiated); NOAA/NASA (small server-cropped PNGs) keep sampling. Keep
        // the last good cells so the reroute doesn't blink out.
        // Dormant while OPERA is disabled: OPERA is never the selected provider, so this
        // guard never trips today — kept for when OPERA (and its toggle) are re-enabled.
        if settings.reduceCellularData, isExpensiveNetwork, provider.id == "eumetnet-opera-radar" {
            return
        }

        let bbox = RadarBoundingBox(region: region)
        // Scale the sample grid to the corridor so NM/pixel stays roughly constant on any
        // route length (fine for short routes, capped for transcon ones).
        let midLat = (bbox.minLatitude + bbox.maxLatitude) / 2
        let latSpanNM = (bbox.maxLatitude - bbox.minLatitude) * 60
        let lonSpanNM = (bbox.maxLongitude - bbox.minLongitude) * 60 * max(0.2, cos(midLat * .pi / 180))
        let grid = RadarImageSampler.sampleGrid(latSpanNM: latSpanNM, lonSpanNM: lonSpanNM)
        let frames = (try? await provider.availableFrames(for: region)) ?? []
        let frame = frames.first ?? RadarFrame(id: "sample", timestamp: Date(), label: "Current")
        // `exportImage` itself returns an optional Data, so flatten the `try?`
        // double-optional before decoding.
        let fetched = try? await provider.exportImage(
            for: bbox, size: CGSize(width: grid.columns, height: grid.rows), frame: frame)
        guard let data = fetched ?? nil,
              let cells = RadarImageSampler.cells(fromPNG: data, columns: grid.columns, rows: grid.rows, bbox: bbox) else {
            // Fetch or decode failed — keep the last good cells so the deviation line
            // doesn't blink out on a transient error.
            return
        }
        radarOverlay.sampledCells = cells
    }

    /// The upcoming route as ordered coordinates — the located fixes still ahead of
    /// the aircraft, then the destination — so the conflict detector can follow the
    /// route's bends into the weather rather than a straight bearing to the next fix.
    /// A fix counts as "ahead" when it lies farther down-route than the aircraft's
    /// progress from the origin (the same test `nextUnpassedWaypoint` uses).
    private func upcomingRouteCoordinates(from pos: CLLocationCoordinate2D) -> [CLLocationCoordinate2D] {
        let origin = airports.coordinate(for: flightPlan.departure) ?? flightPlan.firstWaypointCoordinate
        let progress = origin.map { Geo.distanceNM(from: $0, to: pos) }
        var coords: [CLLocationCoordinate2D] = []
        for wp in flightPlan.waypoints {
            guard let c = wp.coordinate, c.isValid else { continue }
            if let origin, let progress {
                if Geo.distanceNM(from: origin, to: c) > progress + 1 { coords.append(c) }
            } else {
                coords.append(c)
            }
        }
        if let dest = airports.coordinate(for: flightPlan.destination) ?? flightPlan.lastWaypointCoordinate,
           dest.isValid {
            coords.append(dest)
        }
        return coords
    }

    /// The course to fly for the corridor: bearing to the next un-passed fix, else
    /// to the destination, else the aircraft heading.
    private func currentCourse(from pos: CLLocationCoordinate2D) -> Double {
        let origin = airports.coordinate(for: flightPlan.departure)
        if let next = flightPlan.nextUnpassedWaypoint(from: pos, origin: origin)?.coordinate {
            return Geo.bearing(from: pos, to: next)
        }
        if let dest = airports.coordinate(for: flightPlan.destination) ?? flightPlan.lastWaypointCoordinate {
            return Geo.bearing(from: pos, to: dest)
        }
        return aircraftState.heading ?? 0
    }

    private func updateWeatherDiagnostics(conflict: RouteWeatherConflict?) {
        var d = WeatherProviderDiagnostics()
        // Show the active provider + layer type ("radar" vs "satellite estimate").
        d.radarSource = radarOverlay.coverageAvailable
            ? "\(radarOverlay.sourceDescription) (\(radarOverlay.layerLabel))"
            : "None"
        d.radarCoverageAvailable = radarOverlay.coverageAvailable
        d.lastRadarUpdate = radarOverlay.lastUpdated
        d.lastAviationUpdate = lastAviationWeatherUpdate
        d.hazardCount = weatherHazards.count
        if let c = conflict {
            // Distinguish an on-path conflict being monitored ahead (the reroute may be
            // drawn once within draw range, but the banner has not yet been raised) from
            // one close enough to be worked now.
            let stage = c.withinTacticalRange ? "" : " — monitoring"
            d.routeConflictStatus = "\(c.severity.displayLabel) \(c.hazard.source.label), \(Int(c.distanceAheadNM.rounded())) NM\(stage)"
        } else {
            d.routeConflictStatus = "No conflict"
        }
        d.selectedRejoinFix = conflict?.rejoinFix?.name ?? weatherDeviation.rejoinFix
        d.lastDeviationState = weatherDeviation.state
        d.providerError = precipService.lastError
        d.coverageMessage = radarOverlay.coverageAvailable ? nil : radarOverlay.unavailableMessage
        d.radarLastBytes = ordDataUsage.last
        d.radarSessionBytes = ordDataUsage.total
        weatherDiagnostics = d
    }

    // MARK: - Weather deviation — UI gating

    /// Whether the weather-deviation flow may run right now (airborne, enroute or
    /// arrival, not on the ground / takeoff / landing / standby).
    private var weatherFlowAllowed: Bool {
        guard hasDeparted, !companionStandby else { return false }
        switch atcState {
        case .notConnected, .connectedIdle, .clearance, .pushback, .engineStart,
             .pushbackTaxi, .groundTaxi, .runwayCrossing, .holdingShort, .lineUpWait,
             .towerDeparture, .landing, .runwayExit, .groundArrival, .parked:
            return false
        default:
            return true
        }
    }

    /// Whether the pilot is established on final (weather-near-final advisory only).
    private var establishedOnFinal: Bool {
        atcState == .final || atcState == .landing
    }

    /// The most significant turbulence / icing SIGMET along the route, when there is
    /// no precipitation conflict to thread. A turbulence or icing SIGMET has nothing
    /// to laterally route around, so — as ATC does — it drives an *altitude-change*
    /// advisory (smoother air / out of the icing) rather than a lateral reroute.
    /// Precipitation (the mint line) always takes precedence when both are present.
    var activeRideSigmet: SIGMET? {
        guard activeWeatherConflict == nil else { return nil }
        return routeSigmets
            .filter { $0.category == .turbulence || $0.category == .icingOrMountainWave }
            .max { $0.turbulenceSeverity < $1.turbulenceSeverity }
    }

    /// A short word for the active ride advisory's hazard, for the banner / status.
    var rideAdvisoryWord: String {
        guard let ride = activeRideSigmet else { return "Weather" }
        let text = (ride.hazard ?? ride.raw).uppercased()
        if text.contains("ICE") { return "Icing" }
        if text.contains("MTW") { return "Mountain wave" }
        return "Turbulence"
    }

    /// Whether the advisory currently in play is resolved with an altitude change
    /// (turbulence / icing) rather than a lateral deviation (precipitation). Derived
    /// live from the current weather so the response buttons always match reality.
    private var currentAdvisoryIsAltitude: Bool {
        activeWeatherConflict == nil && activeRideSigmet != nil
    }

    /// Whether the "Weather ahead — contact ATC" banner should be shown in ATCView.
    ///
    /// The banner stays up for as long as weather is detected ahead and no
    /// deviation interaction is currently in progress — even after the pilot has
    /// already contacted ATC and elected to continue on course. That way a pilot
    /// who continues now but decides to reroute later still has the banner to tap
    /// to re-open the weather-deviation flow. While a deviation is actively being
    /// worked, the deviation card (not the banner) carries the controls.
    var weatherBannerVisible: Bool {
        guard settings.weatherDeviationAlerts.alertsEnabled, weatherFlowAllowed else { return false }
        let hasPrecip = activeWeatherConflict?.shouldPrompt ?? false
        guard hasPrecip || activeRideSigmet != nil else { return false }
        return weatherDeviation.state == .none || weatherDeviation.state == .weatherAheadDetected
    }

    /// The banner text (advisory-only near final, else the contact-ATC prompt). Names
    /// the hazard for a turbulence / icing ride advisory.
    var weatherBannerText: String {
        if establishedOnFinal { return "Weather near final — advisory only" }
        if currentAdvisoryIsAltitude { return "\(rideAdvisoryWord) advisory — contact ATC" }
        return "Weather ahead — contact ATC"
    }

    /// Whether the weather-deviation response card should be shown in ATCView.
    var weatherDeviationCardVisible: Bool {
        switch weatherDeviation.state {
        case .none, .resumedOwnNavigation, .radarUnavailableForRegion:
            return false
        default:
            return weatherFlowAllowed
        }
    }

    /// The current simulated weather-deviation state.
    var weatherDeviationState: WeatherDeviationState { weatherDeviation.state }

    /// The mint deviation line to draw on the route map: the frozen path the pilot
    /// has committed to fly once a vector/deviation is approved, else the live
    /// recommended reroute from the current conflict. Nil when there's nothing to
    /// draw. Freezing after commit is what stops the line shifting/blinking while the
    /// pilot is following it — only a fresh reroute request or clear-of-weather
    /// resumes live proposals.
    var weatherDeviationLine: [CLLocationCoordinate2D]? {
        if let frozen = weatherDeviation.committedDeviationPath, frozen.count >= 2 {
            return frozen.map { $0.coordinate }
        }
        // Only draw the live recommendation once the weather is close enough to be a
        // real tactical deviation (`withinDrawRange`). Far on-path weather is still
        // detected and monitored, but its straight-corridor reroute — aimed across the
        // route's bends at distant weather — would render as a runaway "crazy" line, so
        // the line is held until the aircraft closes in.
        guard let conflict = activeWeatherConflict, conflict.withinDrawRange,
              conflict.deviationPath.count >= 2 else { return nil }
        return conflict.deviationPath
    }

    /// Build the faint strategic preview lines: a recommended reroute for each distinct
    /// weather system along the filed route **beyond** the one currently drawn solid.
    /// Purely for display / tuning — it never drives ATC or the active deviation. Walks
    /// the route, detecting each successive system from just past the previous system's
    /// rejoin, so a broken line clustered close still counts as one system while systems
    /// farther apart each get their own preview. Uses the cruise lookahead so the whole
    /// route is covered from a standstill (the gate), where the tactical lookahead is
    /// short. Bounded by `maxWeatherPreviewSystems`.
    private func computeWeatherDeviationPreviews() -> [[CLLocationCoordinate2D]] {
        guard radarOverlay.isEnabled, radarOverlay.coverageAvailable, !weatherHazards.isEmpty else { return [] }
        guard let origin = aircraftState.coordinate
                ?? airports.coordinate(for: flightPlan.departure)
                ?? flightPlan.firstWaypointCoordinate, origin.isValid else { return [] }
        let cap = weatherRejoinCap()
        // Start past the deviation drawn solid (committed / active), so the faint lines
        // are the UPCOMING systems rather than a duplicate of the one being worked.
        var startPoint = weatherDeviationLine?.last ?? origin
        var lines: [[CLLocationCoordinate2D]] = []
        // Detection only reaches one lookahead ahead, so walk the whole route in hops:
        // when a hop finds a system, append it and jump past its rejoin; when a hop is
        // clear, scan on down the route rather than stopping — otherwise a system beyond
        // the first clear gap (e.g. one seen from the gate before a long clear leg) would
        // never preview. Bounded so it can't run away on a long route.
        var steps = 0
        while lines.count < maxWeatherPreviewSystems, steps < maxWeatherPreviewSystems * 4 {
            steps += 1
            let ahead = upcomingRouteCoordinates(from: startPoint)
            guard !ahead.isEmpty else { break }
            let course = ahead.first { Geo.distanceNM(from: startPoint, to: $0) > 1 }
                .map { Geo.bearing(from: startPoint, to: $0) } ?? currentCourse(from: startPoint)
            if let conflict = conflictDetector.detectConflict(
                    position: startPoint, course: course, groundspeedKnots: aircraftState.groundSpeed,
                    phase: .cruise, hazards: weatherHazards, waypoints: flightPlan.waypoints,
                    routeAhead: ahead, rejoinCap: cap),
               conflict.deviationPath.count >= 2,
               let end = conflict.deviationPath.last, end.isValid,
               Geo.distanceNM(from: startPoint, to: end) > 1 {
                lines.append(conflict.deviationPath)
                startPoint = end   // jump past this system's rejoin; its cells are now behind
            } else if let next = pointAlongRoute(from: startPoint, through: ahead, byNM: previewScanStepNM),
                      Geo.distanceNM(from: startPoint, to: next) > 1 {
                startPoint = next  // clear window — scan further down the route
            } else {
                break              // reached the end of the route
            }
        }
        return lines
    }

    /// The point `target` NM along the route polyline (`start` then `ahead`) from
    /// `start`, or nil when the route ends before reaching it.
    private func pointAlongRoute(from start: CLLocationCoordinate2D,
                                 through ahead: [CLLocationCoordinate2D], byNM target: Double) -> CLLocationCoordinate2D? {
        var prev = start
        var accumulated = 0.0
        for c in ahead where c.isValid {
            let seg = Geo.distanceNM(from: prev, to: c)
            if accumulated + seg >= target {
                let remaining = target - accumulated
                return Geo.destination(from: prev, bearingDegrees: Geo.bearing(from: prev, to: c), distanceNM: remaining)
            }
            accumulated += seg
            prev = c
        }
        return nil
    }

    /// The rejoin fix marker for the mint line: its name and coordinate. Uses the
    /// live conflict's rejoin fix when available, else the end of the frozen
    /// committed path (labeled with the recorded rejoin fix name) so the marker stays
    /// put with the locked line even after the conflict itself settles.
    var weatherRejoinMarker: (name: String, coordinate: CLLocationCoordinate2D)? {
        // Mirror `weatherDeviationLine`: only show the live rejoin marker while the mint
        // line itself is drawn (within draw range), so a lone marker never appears for
        // far weather whose reroute is still being held.
        if let conflict = activeWeatherConflict, conflict.withinDrawRange,
           let fix = conflict.rejoinFix, let c = fix.coordinate, c.isValid {
            return (fix.name.isEmpty ? "Rejoin" : fix.name, c)
        }
        if let frozen = weatherDeviation.committedDeviationPath, let last = frozen.last {
            let c = last.coordinate
            if c.isValid { return (weatherDeviation.rejoinFix ?? "Rejoin", c) }
        }
        return nil
    }

    /// The weather response-buttons to surface, keyed off the deviation state. A
    /// turbulence / icing advisory offers only altitude changes (there is nothing to
    /// laterally route around); a precipitation advisory offers the full set.
    var weatherActions: [WeatherDeviationAction] {
        switch weatherDeviation.state {
        case .advisoryIssued, .awaitingPilotIntentions, .deviationRequested:
            if establishedOnFinal { return [.sayAgain] }
            if currentAdvisoryIsAltitude {
                return [.requestHigher, .requestLower, .continueOnCourse, .sayAgain]
            }
            return [.requestRightDeviation, .requestLeftDeviation, .requestVector,
                    .requestHigher, .requestLower, .continueOnCourse, .sayAgain]
        case .deviationApproved, .vectoringAroundWeather, .deviatingAroundWeather, .clearOfWeather:
            if establishedOnFinal { return [.clearOfWeather, .sayAgain] }
            // Keep Vectors available while flying a lateral deviation so the pilot can
            // re-plan around NEW weather that pops up ahead — the fresh vector is
            // computed from the current position, treating the committed mint line as
            // the route. An altitude-change ride advisory has nothing lateral to
            // re-vector around, so it only offers clear-of-weather.
            let flyingLateralDeviation = weatherDeviation.committedDeviationPath != nil || activeWeatherConflict != nil
            if currentAdvisoryIsAltitude || !flyingLateralDeviation { return [.clearOfWeather, .sayAgain] }
            return [.requestVector, .clearOfWeather, .sayAgain]
        default:
            return []
        }
    }

    // MARK: - Weather deviation — pilot actions

    /// The controller working the weather deviation. Uses whatever radar controller
    /// is currently tuned — Departure on climb, Approach on arrival, Center enroute —
    /// so the weather calls and read-backs address the active facility. Falls back to
    /// the phase of flight when the tuned facility is not an enroute/radar position.
    private var weatherFacility: ATCFacility {
        switch currentFacility {
        case .departure, .center, .approach:
            return currentFacility
        default:
            switch phase {
            case .approach, .descent: return .approach
            case .initialClimb, .climb: return .departure
            default: return .center
            }
        }
    }

    private var isOnSTAR: Bool {
        guard !flightPlan.star.isEmpty else { return false }
        return phase == .descent || phase == .approach
            || atcState == .descent || atcState == .approach || atcState == .final
    }

    private func starDisplaySpoken() -> (display: String, spoken: String) {
        guard !flightPlan.star.isEmpty else { return ("", "") }
        if let star = ProcedureParser.parseSTAR(flightPlan.star, icao: flightPlan.destination) {
            return (star.displayName, star.spokenName(icao: engine.icao))
        }
        return (flightPlan.star, Phonetic.spellToken(flightPlan.star, icao: engine.icao))
    }

    /// The altitude ATC assigns as "maintain" during a deviation.
    private func weatherMaintainAltitude() -> Int {
        if assignedAltitude > 0 { return assignedAltitude }
        return flightPlan.cruiseAltitude > 0 ? flightPlan.cruiseAltitude : 37000
    }

    /// The heading to fly for a vector around weather.
    ///
    /// Prefer the bearing from the current position to the first turn vertex of the
    /// recommended deviation path (the mint line drawn on the map — `[position, …,
    /// rejoin]`, whose second point is the initial turn-out). Deriving the vector from
    /// that path keeps the assigned heading consistent with the map and anchored to
    /// where the aircraft *is now* — so a second vector requested while already
    /// deviated turns toward the suggested reroute rather than stacking another offset
    /// on top of the current heading.
    ///
    /// Falls back to offsetting the current heading (or the filed course) by the
    /// recommended amount when no usable deviation path is available.
    private func weatherDeviationHeading(direction: DeviationDirection) -> Int {
        let pos = aircraftState.coordinate ?? airports.coordinate(for: flightPlan.departure)
        if let pos, pos.isValid,
           let apex = activeWeatherConflict?.deviationPath.dropFirst().first, apex.isValid {
            let bearing = Geo.bearing(from: pos, to: apex)
            return ((Int(bearing.rounded()) % 360) + 360) % 360
        }
        let base = aircraftState.heading ?? pos.map { currentCourse(from: $0) } ?? 0
        let degrees = activeWeatherConflict?.recommendedDeviationDegrees ?? 20
        let signed = Int(base.rounded()) + (direction == .right ? degrees : -degrees)
        return ((signed % 360) + 360) % 360
    }

    private func deviationInputs(direction: DeviationDirection, unableSide: Bool = false) -> WeatherDeviationEngine.Inputs {
        let star = starDisplaySpoken()
        return WeatherDeviationEngine.Inputs(
            maintainAltitude: weatherMaintainAltitude(),
            heading: weatherDeviationHeading(direction: direction),
            onSTAR: isOnSTAR,
            starDisplay: star.display,
            starSpoken: star.spoken,
            nearRoute: false,
            unableRequestedSide: unableSide)
    }

    private func applyDeviationResult(_ result: WeatherDeviationEngine.Result) {
        weatherHandled = true
        if let pilot = result.pilot { postPilot(pilot) }
        for tx in result.atc { post(tx, speak: true) }
        weatherDeviation = result.context
        updateWeatherDiagnostics(conflict: activeWeatherConflict)
    }

    /// The weather situation to advise on, or nil when nothing significant applies.
    private func currentWeatherSituation() -> WeatherDeviationEngine.Situation? {
        if let conflict = activeWeatherConflict {
            switch conflict.source {
            case .sigmet:
                return .sigmet(label: conflict.hazard.notes ?? "significant weather",
                               convective: conflict.isConvectiveSigmet)
            default:
                return .radarConflict(conflict)
            }
        }
        // No precipitation to route around: a turbulence / icing SIGMET along the
        // route is handled with an altitude change, not a lateral deviation.
        if let ride = activeRideSigmet {
            return rideSigmetSituation(ride)
        }
        if !radarOverlay.coverageAvailable, routeSigmets.isEmpty {
            return .noRadarNoAdvisory
        }
        return nil
    }

    /// Map a turbulence / icing SIGMET to its altitude-oriented advisory. Icing gets
    /// the "exit the icing" framing; mountain-wave and turbulence get "smoother air".
    private func rideSigmetSituation(_ s: SIGMET) -> WeatherDeviationEngine.Situation {
        let text = (s.hazard ?? s.raw).uppercased()
        if text.contains("ICE") { return .rideSigmet(label: "icing", icing: true) }
        if text.contains("MTW") { return .rideSigmet(label: "mountain wave turbulence", icing: false) }
        let label = s.turbulenceSeverity == .severe ? "severe turbulence" : "turbulence"
        return .rideSigmet(label: label, icing: false)
    }

    /// Auto-issue the advisory once for a fresh conflict in Mock Mode (the demo).
    private func maybeAutoIssueMockAdvisory(conflict: RouteWeatherConflict?) {
        guard settings.mockMode, let conflict, conflict.shouldPrompt,
              settings.weatherDeviationAlerts.alertsEnabled, weatherFlowAllowed,
              !companionStandby, !mockWeatherAdvisoryIssued,
              weatherDeviation.state == .none,
              let situation = currentWeatherSituation() else { return }
        mockWeatherAdvisoryIssued = true
        weatherHandled = true
        applyDeviationResult(deviationEngine.issueAdvisory(cs: callsignNow(),
                                                           situation: situation,
                                                           context: weatherDeviation,
                                                           facility: weatherFacility))
    }

    private func callsignNow() -> PhraseologyEngine.Callsign {
        engine.callsign(airline: flightPlan.airline, flightNumber: flightPlan.flightNumber,
                        fallback: flightPlan.callsign)
    }

    /// Pilot taps "Contact ATC": the controller volunteers the weather advisory.
    func askCenterAboutWeather() {
        guard !companionStandby else { return }
        weatherHandled = true
        let cs = callsignNow()
        let facility = weatherFacility
        if let situation = currentWeatherSituation() {
            // A brief pilot query, then the controller's advisory.
            postPilot(ATCTransmission(sender: .pilot, facility: facility,
                displayText: "\(facility.spokenName), \(cs.display), weather ahead, requesting advisory.",
                spokenText: "\(facility.spokenName), \(cs.spoken), weather ahead, requesting advisory."))
            applyDeviationResult(deviationEngine.issueAdvisory(cs: cs, situation: situation,
                                                              context: weatherDeviation,
                                                              facility: facility))
        } else {
            // Coverage but nothing significant along the route.
            post(ATCTransmission(sender: .atc, facility: facility,
                displayText: "\(cs.display), no significant precipitation along your route at this time.",
                spokenText: "\(cs.spoken), no significant precipitation along your route at this time."),
                speak: true)
        }
    }

    /// Pilot requests a left/right weather deviation; the controller approves.
    ///
    /// When the reroute is still drawn ahead — the aircraft has not yet reached the
    /// turn-out point at the start of the mint line — the controller approves the
    /// deviation but **holds the turn**: the pilot continues on course and is told to
    /// expect the turn in X miles. The beginning turn is issued automatically once the
    /// aircraft reaches the turn-out (`maybeIssueDeviationStartTurn`). Close aboard, the
    /// turn is worked immediately, as before.
    func requestWeatherDeviation(_ direction: DeviationDirection) {
        guard !companionStandby, weatherFlowAllowed, !establishedOnFinal else { return }
        let cs = callsignNow()
        if let ahead = deviationTurnOutAhead() {
            applyDeviationResult(deviationEngine.deferDeviation(
                cs: cs, conflict: activeWeatherConflict, direction: direction, distanceNM: ahead.distanceNM,
                inputs: deviationInputs(direction: direction), context: weatherDeviation,
                facility: weatherFacility))
            freezeCommittedDeviationPath()
            armDeviationStart()
            return
        }
        applyDeviationResult(deviationEngine.requestDeviation(
            cs: cs, conflict: activeWeatherConflict, direction: direction,
            inputs: deviationInputs(direction: direction), context: weatherDeviation,
            facility: weatherFacility))
        freezeCommittedDeviationPath()
    }

    /// Pilot requests a vector around the weather; the controller assigns a heading.
    ///
    /// When the pilot is **already** committed to a deviation and requests vectors
    /// again — new weather has popped up ahead of the reroute they're flying — the
    /// new vector is re-planned from where the aircraft is *now*, treating the
    /// committed mint line as the current route. So the fresh heading, mint line and
    /// rejoin turn are computed against the new weather and rejoin the line the
    /// aircraft was already following, rather than the original filed course.
    func requestVectorAroundWeather() {
        guard !companionStandby, weatherFlowAllowed, !establishedOnFinal else { return }
        // A deviation whose turn is still held (drawn ahead) is committed but not yet
        // being flown, so a fresh request re-holds rather than re-vectoring in place.
        let held = weatherDeviation.deviationStartLatitude != nil
        let alreadyCommitted = weatherDeviation.state.isCommittedDeviation && !held
        if alreadyCommitted,
           let pos = aircraftState.coordinate, pos.isValid,
           let fresh = detectConflictAlong(route: revectorRouteAhead(from: pos)) {
            activeWeatherConflict = fresh
            lastConflictSeenAt = Date()
        }
        let cs = callsignNow()
        let side = activeWeatherConflict?.recommendedDirection ?? .right
        // A fresh request with the reroute still drawn ahead holds the turn, exactly like
        // a lateral deviation request — continue on course, expect the turn in X miles —
        // then vectors onto the reroute at the turn-out. A re-vector while already
        // committed (new weather ahead of the line being flown) turns now.
        if !alreadyCommitted, let ahead = deviationTurnOutAhead() {
            applyDeviationResult(deviationEngine.deferDeviation(
                cs: cs, conflict: activeWeatherConflict, direction: side, distanceNM: ahead.distanceNM,
                inputs: deviationInputs(direction: side), context: weatherDeviation,
                facility: weatherFacility))
            freezeCommittedDeviationPath()
            armDeviationStart()
            return
        }
        applyDeviationResult(deviationEngine.requestVectors(
            cs: cs, inputs: deviationInputs(direction: side), context: weatherDeviation,
            facility: weatherFacility))
        freezeCommittedDeviationPath()
        captureWeatherRejoinTurn()
    }

    /// Detect a route-weather conflict along an explicit route polyline from the
    /// current position — used to re-plan a vector while already deviating, where the
    /// route to protect is the committed mint line (plus the filed route past it)
    /// rather than the straight filed course. Reuses the current normalized hazards.
    private func detectConflictAlong(route: [CLLocationCoordinate2D]) -> RouteWeatherConflict? {
        guard let pos = aircraftState.coordinate, pos.isValid else { return nil }
        // Aim the corridor at the first route point meaningfully ahead of the aircraft
        // (the route may start at the current position), else the filed course.
        let course = route.first(where: { $0.isValid && Geo.distanceNM(from: pos, to: $0) > 1 })
            .map { Geo.bearing(from: pos, to: $0) } ?? currentCourse(from: pos)
        return conflictDetector.detectConflict(position: pos, course: course,
                                               groundspeedKnots: aircraftState.groundSpeed,
                                               phase: phase, hazards: weatherHazards,
                                               waypoints: flightPlan.waypoints, routeAhead: route,
                                               rejoinCap: weatherRejoinCap())
    }

    /// The deepest point a weather deviation may rejoin the route: never past the
    /// destination, and at most the first fix of the ILS/approach when the plan names
    /// one. So the mint line always intercepts the filed route at or before this
    /// point — even when weather sits right on the destination.
    private func weatherRejoinCap() -> CLLocationCoordinate2D? {
        if let approachFix = flightPlan.approachStartCoordinate, approachFix.isValid {
            return approachFix
        }
        if let dest = airports.coordinate(for: flightPlan.destination), dest.isValid {
            return dest
        }
        return flightPlan.lastWaypointCoordinate
    }

    /// The route ahead to protect on a re-vector: the committed mint line from the
    /// vertex nearest the aircraft to its end (the part still being flown), then the
    /// filed route beyond the committed line's rejoin. Falls back to the plain filed
    /// route ahead when nothing is committed.
    private func revectorRouteAhead(from pos: CLLocationCoordinate2D) -> [CLLocationCoordinate2D] {
        guard let frozen = weatherDeviation.committedDeviationPath else {
            return upcomingRouteCoordinates(from: pos)
        }
        let committed = frozen.map { $0.coordinate }.filter { $0.isValid }
        guard committed.count >= 2 else { return upcomingRouteCoordinates(from: pos) }
        let nearestIdx = committed.enumerated().min {
            Geo.distanceNM(from: pos, to: $0.element) < Geo.distanceNM(from: pos, to: $1.element)
        }?.offset ?? 0
        let tail = Array(committed[nearestIdx...])
        let beyond = tail.last.map { upcomingRouteCoordinates(from: $0) } ?? []
        return tail + beyond
    }

    /// Freeze the currently recommended mint deviation line as the path the pilot has
    /// now committed to fly, so it stops being re-proposed (and stops shifting with
    /// each radar resample) while the deviation is flown. Once frozen, the map draws
    /// this fixed line until the pilot reports clear of weather or requests another
    /// reroute (which re-freezes it to the fresh recommendation).
    private func freezeCommittedDeviationPath() {
        guard let path = activeWeatherConflict?.deviationPath, path.count >= 2 else {
            weatherDeviation.committedDeviationPath = nil
            return
        }
        weatherDeviation.committedDeviationPath = path.map(WeatherDeviationContext.PathPoint.init)
    }

    /// The committed mint line the aircraft is flying, as coordinates. Prefers the
    /// frozen committed path (stable across radar resamples) and falls back to the
    /// live conflict's path, so the turn tracking keys off the exact line drawn.
    private func committedMintLineCoordinates() -> [CLLocationCoordinate2D] {
        if let frozen = weatherDeviation.committedDeviationPath {
            return frozen.map { $0.coordinate }
        }
        return activeWeatherConflict?.deviationPath ?? []
    }

    /// Clear any armed auto-turn (no turn pending).
    private func clearPendingRejoinTurn() {
        weatherDeviation.pendingTurnIndex = nil
        weatherDeviation.pendingRejoinHeading = nil
        weatherDeviation.vectorApexLatitude = nil
        weatherDeviation.vectorApexLongitude = nil
        weatherDeviation.vectorLegBearing = nil
    }

    // MARK: - Weather deviation — held beginning turn (reroute drawn ahead)

    /// The turn-out point at the start of the drawn mint line and its distance ahead
    /// (NM, rounded to 5), when it sits meaningfully ahead of the aircraft — i.e. the
    /// reroute is drawn ahead and the beginning turn should be held until the aircraft
    /// reaches it. Nil when the aircraft is already at/through the turn-out, so the
    /// deviation is worked immediately.
    private func deviationTurnOutAhead() -> (start: CLLocationCoordinate2D, distanceNM: Int)? {
        guard let pos = aircraftState.coordinate, pos.isValid,
              let v0 = activeWeatherConflict?.deviationPath.first, v0.isValid else { return nil }
        let d = Geo.distanceNM(from: pos, to: v0)
        guard d > deviationTurnHoldNM else { return nil }
        return (v0, max(5, Int((d / 5).rounded()) * 5))
    }

    /// Arm the held beginning turn at the mint line's turn-out point: store the turn-out
    /// (start of the committed line), the heading to fly out of it onto the reroute, and
    /// the bearing of the leg into it (to detect the aircraft passing abeam), so the
    /// telemetry loop can issue the turn once the aircraft reaches the turn-out.
    private func armDeviationStart() {
        let path = committedMintLineCoordinates()
        guard path.count >= 2, let v0 = path.first, v0.isValid,
              let v1 = path.dropFirst().first, v1.isValid,
              let pos = aircraftState.coordinate, pos.isValid else { clearDeviationStart(); return }
        weatherDeviation.deviationStartLatitude = v0.latitude
        weatherDeviation.deviationStartLongitude = v0.longitude
        weatherDeviation.deviationStartHeading = ApproachIntercept.normalizedHeading(Geo.bearing(from: v0, to: v1))
        weatherDeviation.deviationStartLegBearing = Geo.bearing(from: pos, to: v0)
    }

    /// Clear the held beginning turn (no deviation-start pending).
    private func clearDeviationStart() {
        weatherDeviation.deviationStartLatitude = nil
        weatherDeviation.deviationStartLongitude = nil
        weatherDeviation.deviationStartHeading = nil
        weatherDeviation.deviationStartLegBearing = nil
    }

    /// While a deviation is approved with its turn held (reroute drawn ahead), issue the
    /// beginning turn once the aircraft reaches the turn-out point — near it, or once it
    /// passes abeam/beyond along the leg into it. Vectors the aircraft onto the reroute
    /// and arms the interior turns. Returns whether it issued the turn this tick, so the
    /// caller can skip the other turn checks (they'd race on the same geometry).
    @discardableResult
    private func maybeIssueDeviationStartTurn() -> Bool {
        guard !companionStandby, weatherFlowAllowed,
              weatherDeviation.state == .deviationApproved,
              let sLat = weatherDeviation.deviationStartLatitude,
              let sLon = weatherDeviation.deviationStartLongitude,
              let heading = weatherDeviation.deviationStartHeading,
              let pos = aircraftState.coordinate, pos.isValid else { return false }
        let v0 = CLLocationCoordinate2D(latitude: sLat, longitude: sLon)
        let captureNM = max(2.0, (aircraftState.groundSpeed ?? 300) / 120)
        let dist = Geo.distanceNM(from: pos, to: v0)
        let reached: Bool
        if dist <= captureNM {
            reached = true
        } else if let leg = weatherDeviation.deviationStartLegBearing {
            let v0ToAircraft = Geo.bearing(from: v0, to: pos)
            reached = dist * cos((v0ToAircraft - leg) * .pi / 180) >= 0
        } else {
            reached = false
        }
        guard reached else { return false }
        applyDeviationResult(deviationEngine.beginDeviationTurn(
            cs: callsignNow(), heading: heading, maintainAltitude: weatherMaintainAltitude(),
            context: weatherDeviation, facility: weatherFacility))
        // Now vectoring onto the reroute — arm the interior turns of the committed line.
        captureWeatherRejoinTurn()
        return true
    }

    /// Arm the interior turn at `index` in the mint line: store the turn vertex, the
    /// bearing of the leg leading into it (to detect the aircraft passing abeam), and
    /// the heading to fly out of it toward the next vertex — so the telemetry loop can
    /// auto-issue the turn once the aircraft reaches it. Only interior vertices
    /// (1…count-2) are turns; the endpoints are the start and the rejoin.
    private func armRejoinTurn(at index: Int, path: [CLLocationCoordinate2D]) {
        guard index >= 1, index <= path.count - 2,
              path[index - 1].isValid, path[index].isValid, path[index + 1].isValid else {
            clearPendingRejoinTurn()
            return
        }
        let apex = path[index]
        weatherDeviation.pendingTurnIndex = index
        weatherDeviation.vectorApexLatitude = apex.latitude
        weatherDeviation.vectorApexLongitude = apex.longitude
        weatherDeviation.vectorLegBearing = Geo.bearing(from: path[index - 1], to: apex)
        weatherDeviation.pendingRejoinHeading =
            ApproachIntercept.normalizedHeading(Geo.bearing(from: apex, to: path[index + 1]))
    }

    /// Capture the turn points of the committed mint line and arm the **first**
    /// interior turn, so the telemetry loop can auto-issue a turn call at each vertex
    /// as the aircraft reaches it. A single dogleg (`[position, apex, rejoin]`) has one
    /// turn; a side-hug (`[position, turnOut, turnBack, rejoin]`) has two — out onto the
    /// parallel leg, then back down to the route — and each firing arms the next.
    private func captureWeatherRejoinTurn() {
        clearPendingRejoinTurn()
        guard weatherDeviation.state == .vectoringAroundWeather else { return }
        let path = committedMintLineCoordinates()
        // Need at least one interior turn vertex: [start, v1, …, rejoin].
        guard path.count >= 3, path.allSatisfy({ $0.isValid }) else { return }
        armRejoinTurn(at: 1, path: path)
    }

    /// While vectoring around weather, once the aircraft reaches the next turn in the
    /// mint line, the controller automatically issues the turn onto the following leg
    /// — an intermediate turn (onto the parallel leg) keeps vectoring, the final turn
    /// rejoins the filed route. After a non-final turn it arms the next interior turn,
    /// so a side-hug line gets both its turns called. Called each telemetry tick.
    /// Returns whether it issued a turn this tick, so the caller can skip the
    /// auto-resume check on the same tick (they both key off the mint line's geometry).
    @discardableResult
    private func maybeIssueWeatherRejoinTurn() -> Bool {
        guard !companionStandby, weatherFlowAllowed,
              weatherDeviation.state == .vectoringAroundWeather,
              let index = weatherDeviation.pendingTurnIndex,
              let heading = weatherDeviation.pendingRejoinHeading,
              let apexLat = weatherDeviation.vectorApexLatitude,
              let apexLon = weatherDeviation.vectorApexLongitude,
              let pos = aircraftState.coordinate, pos.isValid else { return false }
        let apex = CLLocationCoordinate2D(latitude: apexLat, longitude: apexLon)
        let distance = Geo.distanceNM(from: pos, to: apex)
        // Fire when near the turn vertex, or once the aircraft has passed abeam/beyond
        // it along the leg into it (so flying wide of it still triggers the turn). The
        // capture radius scales with groundspeed (~30 s of travel), min 2 NM, so a fast
        // aircraft does not skip past the turn between telemetry ticks.
        let captureNM = max(2.0, (aircraftState.groundSpeed ?? 300) / 120)
        let reached: Bool
        if distance <= captureNM {
            reached = true
        } else if let legBearing = weatherDeviation.vectorLegBearing {
            // Along-track distance from the vertex in the inbound leg direction; ≥ 0
            // means the aircraft is at or beyond the vertex's abeam line.
            let apexToAircraft = Geo.bearing(from: apex, to: pos)
            let alongNM = distance * cos((apexToAircraft - legBearing) * .pi / 180)
            reached = alongNM >= 0
        } else {
            reached = false
        }
        guard reached else { return false }
        // The turn onto the last leg (toward the rejoin, the final point) is the final
        // turn; earlier interior vertices are intermediate turns that keep vectoring.
        let path = committedMintLineCoordinates()
        let isFinalTurn = index >= path.count - 2
        applyDeviationResult(deviationEngine.rejoinTurn(
            cs: callsignNow(), heading: heading, rejoinFix: weatherDeviation.rejoinFix,
            finalTurn: isFinalTurn, context: weatherDeviation, facility: weatherFacility))
        // Arm the next interior turn if the mint line has one (a side-hug has two).
        // The engine cleared the fired turn, so this re-arms on the fresh context.
        if !isFinalTurn {
            armRejoinTurn(at: index + 1, path: path)
        }
        return true
    }

    /// Once the aircraft reaches the flight-plan intercept at the end of the mint line
    /// without the pilot reporting clear of weather, the controller automatically
    /// resumes own navigation and ends the deviation. Guarded to the final leg (at or
    /// beyond the last turn) and within `autoResumeInterceptNM` of the intercept, so it
    /// can't trip during the outbound or parallel legs.
    private func maybeAutoResumeAtRouteIntercept() {
        guard !companionStandby, weatherFlowAllowed else { return }
        switch weatherDeviation.state {
        case .deviationApproved, .vectoringAroundWeather: break
        default: return
        }
        guard let pos = aircraftState.coordinate, pos.isValid,
              let line = weatherDeviationLine, line.count >= 2,
              let end = line.last, end.isValid else { return }
        let lastTurn = line[line.count - 2]
        guard lastTurn.isValid else { return }
        // On the final leg to the intercept: at or beyond the last turn along that leg.
        let legBearing = Geo.bearing(from: lastTurn, to: end)
        let turnToAircraft = Geo.bearing(from: lastTurn, to: pos)
        let alongNM = Geo.distanceNM(from: lastTurn, to: pos) * cos((turnToAircraft - legBearing) * .pi / 180)
        guard alongNM >= 0, Geo.distanceNM(from: pos, to: end) <= autoResumeInterceptNM else { return }
        autoResumeOwnNavigation()
    }

    /// End a lateral weather deviation by resuming own navigation, with no pilot
    /// clear-of-weather call — the aircraft flew the mint line all the way to the
    /// flight-plan intercept on its own. Mirrors the clear-of-weather cleanup.
    private func autoResumeOwnNavigation() {
        applyDeviationResult(deviationEngine.autoResumeOwnNavigation(
            cs: callsignNow(), context: weatherDeviation, facility: weatherFacility))
        if settings.mockMode { radarOverlay.mockCells = [] }
        activeWeatherConflict = nil
        weatherDeviation.reset()
        weatherDeviation.state = .none
        weatherHandled = false
        mockWeatherAdvisoryIssued = false
        lastConflictSeenAt = nil
    }

    func requestHigherForWeather() {
        guard !companionStandby, weatherFlowAllowed else { return }
        let target = nextAltitude(from: max(assignedAltitude, aircraftAltInt()), up: true)
        applyDeviationResult(deviationEngine.requestAltitude(
            cs: callsignNow(), higher: true, targetAltitude: target,
            context: weatherDeviation, facility: weatherFacility))
        assignedAltitude = target
    }

    func requestLowerForWeather() {
        guard !companionStandby, weatherFlowAllowed else { return }
        let target = nextAltitude(from: max(assignedAltitude, aircraftAltInt()), up: false)
        applyDeviationResult(deviationEngine.requestAltitude(
            cs: callsignNow(), higher: false, targetAltitude: target,
            context: weatherDeviation, facility: weatherFacility))
        assignedAltitude = target
    }

    /// Pilot reports clear of weather; the controller clears back to the route.
    func reportClearOfWeather() {
        guard !companionStandby else { return }
        let cs = callsignNow()
        applyDeviationResult(deviationEngine.reportClearOfWeather(
            cs: cs, inputs: deviationInputs(direction: weatherDeviation.requestedDeviationDirection ?? .right),
            context: weatherDeviation, facility: weatherFacility))
        // Clear the conflict so the flow settles. In Mock Mode remove the demo cell
        // so the aircraft is genuinely "past" the weather.
        if settings.mockMode { radarOverlay.mockCells = [] }
        activeWeatherConflict = nil
        weatherDeviation.reset()
        weatherDeviation.state = .none
        weatherHandled = false
        mockWeatherAdvisoryIssued = false
        lastConflictSeenAt = nil
    }

    /// Pilot elects to continue on course through the advisory.
    func continueThroughWeather() {
        guard !companionStandby else { return }
        weatherHandled = true
        let cs = callsignNow()
        postPilot(ATCTransmission(sender: .pilot, facility: weatherFacility,
            displayText: "\(cs.display), continuing on course.",
            spokenText: "\(cs.spoken), continuing on course."))
        post(ATCTransmission(sender: .atc, facility: weatherFacility,
            displayText: "\(cs.display), roger, advise if you need to deviate.",
            spokenText: "\(cs.spoken), roger, advise if you need to deviate."), speak: true)
        weatherDeviation.reset()
        weatherDeviation.state = .none
        // Continuing resolves the prompt: drop the confirm-clear hold so a genuinely
        // clear route removes the banner promptly. Weather still ahead re-arms the
        // hold on the next detected tick (the banner stays for a possible reroute).
        lastConflictSeenAt = nil
    }

    /// Re-issue the last weather advisory/instruction ("say again").
    func sayAgainWeather() {
        guard !companionStandby else { return }
        let cs = callsignNow()
        postPilot(ATCTransmission(sender: .pilot, facility: weatherFacility,
            displayText: "Say again for \(cs.display).",
            spokenText: "Say again for \(cs.spoken)."))
        if let last = lastATCTransmission {
            post(ATCTransmission(sender: .atc, facility: last.facility,
                                 displayText: last.displayText, spokenText: last.spokenText), speak: true)
        }
    }

    // MARK: - Diagnostics helpers

    func advanceMockPhase() {
        mock.advancePhase()
    }

    func resetAppData() {
        transcript.removeAll()
        latestTransmission = nil
        clearSavedSession()
        resetWeatherDeviation()
        radarOverlay.mockCells = []
        radarOverlay.sampledCells = []
        settings.resetAll()
        syncFlightPlanFromSettings()
        diagnostics.log(.app, "App data reset.")
    }

    /// Clear the current flight and start a fresh one from the gate. Wipes the
    /// conversation and ATC/phase state but keeps the user's settings and flight
    /// plan, then restarts the active feed. Use this between flights so the new
    /// flight does not inherit the previous chat history.
    func clearFlight() {
        manualTuning = false
        tunedFacility = nil
        pendingCheckInFacility = nil
        clearReadbackGate()
        cancelTakeoffClearanceTimer()
        clearSavedSession()
        resetWeatherDeviation()
        speech.stop()
        transcript.removeAll()
        latestTransmission = nil
        lastATCTransmission = nil
        assignedAltitude = 0
        hasDeparted = false
        arrivalAnnounced = false
        awaitingGateArrival = false
        gateMonitored = false
        lastSpokenText = ""
        lastSpokenIntent = nil
        phase = .preflight
        phaseDetector = PhaseDetector()
        stateMachine.reset()
        atcState = .connectedIdle
        currentFacility = .clearance
        if settings.mockMode {
            // Restart the mock feed from the gate.
            mock.stop()
            mock.setPhase(.preflight)
            startMock()
        } else {
            // Keep the live IF connection; the conversation rebuilds from the next
            // telemetry update.
            stateMachine.setConnected()
        }
        diagnostics.log(.app, "Flight cleared — ready for a new flight.")
    }
}
