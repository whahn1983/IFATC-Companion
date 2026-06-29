import Foundation
import Combine
import CoreLocation

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
    let unicom: UNICOMAutomationService
    let phraseologyProfiles: PhraseologyProfileStore
    let speechRecognizer: SpeechRecognitionService

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
    private let airports = AirportDatabase.shared

    // Published UI state.
    @Published var aircraftState = AircraftState.empty
    @Published var flightPlan = FlightPlan.empty
    @Published var phase: FlightPhase = .preflight
    @Published var atcState: ATCState = .notConnected
    @Published var currentFacility: ATCFacility = .ground
    @Published var transcript: [ATCTransmission] = []
    @Published var latestTransmission: ATCTransmission?
    @Published var phaseDebug = PhaseDetector.Debug()
    @Published var assignedAltitude: Int = 0

    // Multiplayer / human-ATC staffing. When a human controller is detected the
    // companion stands by (stops generating controller calls).
    @Published var liveATC: LiveATCStatus = .none
    /// Mock-mode demo toggle to exercise the "step aside" behavior in the Simulator.
    @Published var simulateStaffedATC: Bool = false

    /// Whether the companion should defer to a human controller right now.
    var companionStandby: Bool { liveATC.shouldStandBy }

    /// Whether the pre-departure ground actions (clearance → pushback → engine
    /// start → taxi → ready) should be offered. True until the first departure.
    var isPreDeparture: Bool { !hasDeparted }

    // Weather state.
    @Published var departureMETAR: METAR?
    @Published var destinationMETAR: METAR?
    @Published var alternateMETAR: METAR?
    @Published var destinationTAF: TAF?
    @Published var pireps: [PIREP] = []
    @Published var sigmets: [SIGMET] = []
    @Published var rideReportItems: [RideReportItem] = []
    @Published var rideAssessment: RideAssessment = .smooth
    @Published var weatherStatus: String = "Not loaded"

    private var lastATCTransmission: ATCTransmission?
    private var cancellables = Set<AnyCancellable>()
    private var started = false

    /// Once airborne, automatic telemetry-driven controller calls run. Before the
    /// first departure the pre-departure ground flow (clearance → pushback →
    /// engine start → taxi → ready) is always pilot-driven via the response
    /// buttons; the only controller call issued automatically on the ground is the
    /// takeoff clearance, once the aircraft is lined up on the runway.
    private var hasDeparted = false

    /// Guards the one-time arrival courtesy call at the gate.
    private var arrivalAnnounced = false

    /// True once the pilot starts changing controllers with the frequency-tune
    /// buttons. While set, the airborne conversation advances only when the pilot
    /// tunes a frequency — automatic, telemetry-driven controller calls are
    /// suppressed so messages never play one after another without waiting.
    @Published private(set) var manualTuning = false

    init() {
        let settings = AppSettings()
        self.settings = settings
        let diagnostics = DiagnosticsStore()
        self.diagnostics = diagnostics
        self.speech = SpeechService()
        self.connect = IFConnectManager()
        self.mock = MockSimulatorFeed()
        self.unicom = UNICOMAutomationService()
        let profiles = PhraseologyProfileStore()
        self.phraseologyProfiles = profiles
        self.speechRecognizer = SpeechRecognitionService()
        self.weatherService = AviationWeatherService(baseURL: settings.weatherBaseURL)

        var engine = PhraseologyEngine(digitStyle: settings.digitStyle, mode: settings.phraseologyMode)
        engine.profile = profiles.activeProfile
        self.engine = engine
        self.stateMachine = ATCStateMachine(engine: engine)
        self.pilotEngine = PilotResponseEngine(engine: engine)
        self.rampEngine = RampPhraseologyEngine(engine: engine)
        self.rideEngine = RideReportEngine(engine: engine)
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
    }

    // MARK: - Lifecycle

    func onAppear() {
        guard !started else { return }
        started = true

        diagnostics.verbose = settings.debugLogging
        speech.configure(settings: settings)
        connect.configure(diagnostics: diagnostics)
        unicom.configure(connect: connect, diagnostics: diagnostics)
        unicom.mode = currentUnicomMode
        Task { await weatherService.configure(baseURL: settings.weatherBaseURL, diagnostics: diagnostics) }

        // Route state from whichever feed is active.
        mock.onState = { [weak self] state in self?.handle(state: state) }
        connect.onState = { [weak self] state in self?.handle(state: state) }

        // Read the flight plan from Infinite Flight when it changes.
        connect.onFlightPlan = { [weak self] plan in self?.mergeLiveFlightPlan(plan) }

        // Route recognized push-to-talk speech to the deterministic intent handler.
        speechRecognizer.onResult = { [weak self] text in self?.handleSpokenInput(text) }

        observeSettings()
        syncFlightPlanFromSettings()
        diagnostics.log(.app, "IFATC Companion ready. Mock mode: \(settings.mockMode).")

        if settings.mockMode {
            startMock()
        } else {
            startLive()
        }
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

        settings.$unicomModeRaw
            .sink { [weak self] _ in self?.unicom.mode = self?.currentUnicomMode ?? .preview }
            .store(in: &cancellables)

        settings.$debugLogging
            .sink { [weak self] on in self?.diagnostics.verbose = on }
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
                self.applyLiveATC(on
                    ? LiveATCStatus(multiplayerOnline: true, serverName: "Expert",
                                    humanControllerActive: true,
                                    activeFacility: self.currentFacility.title)
                    : .none)
            }
            .store(in: &cancellables)
    }

    /// Apply a new live-ATC status and log standby transitions.
    private func applyLiveATC(_ status: LiveATCStatus) {
        let wasStandby = liveATC.shouldStandBy
        liveATC = status
        if status.shouldStandBy != wasStandby {
            diagnostics.log(.atc, status.shouldStandBy
                ? "Human ATC detected — companion standing by."
                : "Human ATC cleared — companion resuming.")
        }
    }

    var currentUnicomMode: UNICOMMode {
        UNICOMMode(rawValue: settings.unicomModeRaw) ?? .preview
    }

    // MARK: - Source selection

    func startMock() {
        connect.disconnect()
        manualTuning = false
        stateMachine.reset()
        hasDeparted = false
        arrivalAnnounced = false
        atcState = .connectedIdle
        stateMachine.setConnected()
        applyLiveATC(simulateStaffedATC
            ? LiveATCStatus(multiplayerOnline: true, serverName: "Expert",
                            humanControllerActive: true, activeFacility: currentFacility.title)
            : .none)
        mock.start()
        diagnostics.log(.app, "Mock simulator feed started.")
        Task { await refreshWeather() }
    }

    func stopMock() {
        mock.stop()
    }

    func startLive() {
        mock.stop()
        manualTuning = false
        stateMachine.reset()
        hasDeparted = false
        arrivalAnnounced = false
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
        // Refresh UNICOM availability once the manifest is in, and load weather.
        Task {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            self.unicom.refreshAvailability()
            await self.refreshWeather()
        }
    }

    func toggleMockMode(_ on: Bool) {
        settings.mockMode = on
        if on { startMock() } else { startLive() }
    }

    func reconnect() {
        if settings.mockMode { startMock() } else { connect.disconnect(); startLive() }
    }

    // MARK: - State handling

    #if DEBUG
    /// Test hook: feed a single aircraft-state snapshot through the same pipeline the
    /// live/mock feeds use (phase detection → state machine → transcript). Lets tests
    /// drive a full mock scenario without starting timers, networking, or audio.
    func ingestStateForTesting(_ state: AircraftState) { handle(state: state) }
    #endif

    private func handle(state: AircraftState) {
        aircraftState = state
        stateMachine.setConnected()

        let result = phaseDetector.detect(state: state, plan: flightPlan,
                                          airports: airports, previous: phase)
        phase = result.phase
        phaseDebug = result.debug

        if !phase.isGround { hasDeparted = true }

        let mapped = stateMachine.mappedState(for: phase)
        unicom.connected = settings.mockMode || connect.connectionState.isConnected

        // Defer to a human controller: track state for display, but do not generate
        // or speak controller calls, and do not fire UNICOM.
        if companionStandby {
            atcState = mapped
            currentFacility = controller(for: mapped)
            return
        }

        // --- Pre-departure (on the ground, before the first takeoff) ---
        // The pilot drives their own calls (clearance → pushback → engine start →
        // taxi → ready) with the response buttons. The only controller call issued
        // automatically here is the takeoff clearance, once the aircraft is lined
        // up on the runway — Tower does not wait for a pilot prompt for that.
        if !hasDeparted {
            if stateMachine.current == .lineUpWait, !settings.mockMode, !manualTuning,
               isReadyForTakeoffClearance(state: state) {
                autoIssueTakeoffClearance()
            } else if !mapped.isManualGroundFlow {
                // Telemetry already shows the takeoff roll (the pilot rolled without
                // using the buttons) — advance so the flow is never stuck on ground.
                advanceAndPost(to: mapped, context: buildContext(for: mapped))
            }
            // Otherwise hold: the pilot-driven ground flow advances via the buttons.
            atcState = stateMachine.current
            currentFacility = controller(for: stateMachine.current)
            return
        }

        // --- Airborne / arrival ---
        // When the pilot is changing controllers manually with the frequency
        // buttons, the conversation advances only on a button press. Keep the
        // position/phase display fresh, but do not auto-issue controller calls —
        // that is what makes the calls pile up "one after the next".
        if manualTuning {
            atcState = stateMachine.current
            currentFacility = controller(for: stateMachine.current)
            return
        }

        // Otherwise controller callouts (hand-offs, climb/descent, approach,
        // landing, taxi-in) are issued automatically as the aircraft reaches each
        // position. Pilot read-backs and check-ins stay manual.
        let target = adjustedAirborneTarget(mapped: mapped, state: state)
        advanceAndPost(to: target, context: buildContext(for: target))

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
        case .clearance, .ground, .unicom: return c.groundFrequency
        case .tower: return c.towerFrequency
        case .departure: return c.departureFrequency
        case .center: return c.centerFrequency
        case .approach: return c.approachFrequency
        }
    }

    /// Advance the state machine and post the resulting controller call, preceding
    /// it with a "contact …" handoff whenever the controlling facility changes.
    private func advanceAndPost(to target: ATCState, context c: ATCContext,
                                speak: Bool = true, announceHandoff: Bool = true) {
        let previous = stateMachine.current
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
        // the duplicate hand-off when leaving that state. When the pilot tuned the
        // frequency themselves, the controller does not say "contact …" either.
        let firstContact = previous == .notConnected || previous == .connectedIdle
        if announceHandoff, !firstContact, previous != .runwayExit, fromFacility != toFacility,
           toFacility != .clearance, lastATCTransmission != nil {
            post(engine.handoff(cs: c.callsign, from: fromFacility, to: toFacility,
                                frequency: frequency(for: toFacility, context: c)), speak: speak)
        }
        updateAssignedAltitude(for: target, context: c)
        post(tx, speak: speak)
        fireUnicomForTransition(into: target)
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
        advanceAndPost(to: .towerDeparture, context: buildContext(for: .towerDeparture))
    }

    // MARK: - Manual frequency tuning

    /// Facilities the pilot can tune to with a button, in the order they're worked
    /// across a normal flight. The same Ground/Tower button serves both the
    /// departure and the arrival visit — `tuneTo` advances to whichever call lies
    /// ahead of the current state.
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

    /// Manually tune the radio to a facility and contact it. Posts the pilot's
    /// check-in on the new frequency, then the controller's next instruction for
    /// the current phase. This is how the pilot drives the flight forward: every
    /// frequency change is a deliberate button press, so the calls never auto-play
    /// back-to-back without waiting.
    func tuneTo(_ facility: ATCFacility) {
        guard !companionStandby else { return }
        manualTuning = true
        guard let target = nextState(workedBy: facility, after: stateMachine.current),
              target != stateMachine.current else {
            // Already talking to this facility — just confirm the frequency.
            currentFacility = facility
            return
        }
        if !target.isManualGroundFlow { hasDeparted = true }
        let c = buildContext(for: target)
        // The pilot checks in on the freshly tuned frequency. Because the pilot
        // initiated the switch, the controller does not precede its reply with a
        // "contact …" hand-off (announceHandoff: false).
        postPilot(pilotEngine.requestHandoff(context: c, facility: facility))
        advanceAndPost(to: target, context: c, announceHandoff: false)
        if target == .parked, !arrivalAnnounced {
            announceArrival()
            arrivalAnnounced = true
        }
        // Keep the mock telemetry display roughly in step with the tuned phase.
        if settings.mockMode { mock.setPhase(mockPhase(for: target)) }
    }

    /// Final step of the arrival: the aircraft is parked at the gate. Posts the
    /// arrival courtesy on Ground; no frequency of its own (the "Ramp" button).
    func arriveAtGate() {
        guard !companionStandby else { return }
        manualTuning = true
        hasDeparted = true
        let c = buildContext(for: .parked)
        // Simulated arrival Ramp (local/company, non-FAA) entry conversation:
        // Ground hands off to Ramp at the spot, the pilot checks in inbound, and
        // Ramp gives a non-movement-area routing to the gate.
        if !arrivalAnnounced, c.rampProfile.rampType != .none {
            postPilot(rampEngine.arrivalInbound(cs: c.callsign, gate: c.gate))
            post(rampEngine.proceedToGate(cs: c.callsign, gate: c.gate,
                                          via: c.rampProfile.arrivalRampEntryPhrase.contains("inner")
                                               ? "the inner alley" : "the ramp"), speak: true)
        }
        advanceAndPost(to: .parked, context: c, announceHandoff: false)
        if !arrivalAnnounced { announceArrival(); arrivalAnnounced = true }
        if settings.mockMode { mock.setPhase(.parked) }
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

        // Departure keeps the climb until passing the TRACON ceiling, then Center.
        if mapped == .climb, alt < ceiling - 200,
           [.towerDeparture, .initialClimb, .departure].contains(stateMachine.current) {
            return .initialClimb
        }

        // Approach clears the approach once the aircraft is established — autopilot
        // approach mode (APPR) engaged or lined up on final — before the Tower
        // hand-off. This must follow the "descend, expect approach" call (.approach).
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
        return lineupDetector.isOnFinalApproach(state: s, runway: runway)
    }

    /// Whether the aircraft is on short final (airborne, low, descending).
    private func isOnShortFinal(_ s: AircraftState) -> Bool {
        guard !(s.onGround ?? false) else { return false }
        let agl = s.altitudeAGL ?? (s.altitudeMSL ?? 0)
        let vs = s.verticalSpeed ?? 0
        return agl < 1500 && vs < -100
    }

    /// Arrival block-in once stopped at the gate. Emits the simulated Ramp
    /// (local/company, non-FAA) block-in call followed by a System "flight
    /// complete" advisory.
    private func announceArrival() {
        let c = buildContext(for: .parked)
        post(rampEngine.monitorRampToGate(cs: c.callsign), speak: true)
        let display = "\(c.callsign.display) parked\(c.gate.isEmpty ? "" : " at \(c.gate)"). Flight complete."
        let spoken = "\(c.callsign.spoken) parked. Flight complete."
        post(ATCTransmission(sender: .system, facility: .ramp, displayText: display, spokenText: spoken), speak: false)
    }

    // MARK: - Live flight plan

    /// Merge a flight plan read from Infinite Flight into the active plan. Manual
    /// overrides win; otherwise empty fields are filled from the live plan.
    private func mergeLiveFlightPlan(_ live: FlightPlan) {
        var plan = flightPlan
        let manual = plan.manualOverride
        let before = (plan.departure, plan.destination)

        if (!manual || plan.departure.isEmpty), !live.departure.isEmpty { plan.departure = live.departure }
        if (!manual || plan.destination.isEmpty), !live.destination.isEmpty { plan.destination = live.destination }
        if (!manual || plan.waypoints.isEmpty), !live.waypoints.isEmpty { plan.waypoints = live.waypoints }
        flightPlan = plan

        // Refresh weather only when the endpoints actually changed.
        if before != (plan.departure, plan.destination) {
            Task { await refreshWeather() }
        }
    }

    // MARK: - UNICOM triggering

    private func fireUnicomForTransition(into state: ATCState) {
        guard let event = unicomEvent(for: state) else { return }
        let c = buildContext(for: state)
        unicom.handle(event: event, ident: unicomIdent(for: state), runway: c.runway)
    }

    private func unicomEvent(for state: ATCState) -> UNICOMEvent? {
        switch state {
        case .groundTaxi, .pushbackTaxi: return .taxiingToRunway
        case .towerDeparture: return .takingRunway
        case .initialClimb, .departure: return .departingRunway
        case .approach: return .inbound
        case .final: return .onFinal
        case .landing: return .onFinal
        case .runwayExit, .groundArrival: return .clearOfRunway
        case .parked: return .taxiingToParking
        default: return nil
        }
    }

    private func unicomIdent(for state: ATCState) -> String {
        let arrival = [.descent, .approach, .final, .landing, .runwayExit, .groundArrival, .parked].contains(state)
        if let near = aircraftState.nearestAirport, !near.isEmpty { return near }
        return arrival ? flightPlan.destination : flightPlan.departure
    }

    private func updateAssignedAltitude(for state: ATCState, context c: ATCContext) {
        switch state {
        case .clearance: assignedAltitude = c.initialClimbAltitude
        case .towerDeparture: assignedAltitude = c.initialClimbAltitude
        case .initialClimb, .departure:
            assignedAltitude = c.traconCeiling > 0 ? c.traconCeiling : max(c.assignedAltitude, c.initialClimbAltitude)
        case .climb, .cruise: assignedAltitude = c.cruiseAltitude
        case .descent: assignedAltitude = ATCStateMachine.descentTargetAltitude(context: c)
        case .approach: assignedAltitude = 3000
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
    }

    /// Post a pilot transmission, speaking it aloud when appropriate. The pilot
    /// voice is heard for button/text-driven calls; push-to-talk input is never
    /// re-spoken because the user already said it.
    private func postPilot(_ tx: ATCTransmission) {
        post(tx, speak: shouldSpeakPilot)
    }

    /// Whether the pilot's next transmission should be spoken aloud.
    private var shouldSpeakPilot: Bool { settings.speakPilot && !pilotInputViaVoice }

    /// True while handling a push-to-talk utterance, so the resulting pilot
    /// transmission is not spoken back over the user.
    private var pilotInputViaVoice = false

    // MARK: - Context

    private func buildContext(for state: ATCState) -> ATCContext {
        let cs = engine.callsign(airline: flightPlan.airline,
                                 flightNumber: flightPlan.flightNumber,
                                 fallback: flightPlan.callsign)
        let arrival = [.descent, .approach, .final, .landing, .runwayExit, .groundArrival].contains(state)
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

        let runway = resolvedRunway(windDir: windDir, arrival: arrival,
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
        let intercept = firstLocated?.coordinate ?? airports.coordinate(for: flightPlan.destination)
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
            gate: "",
            departureHeading: ((depHeading % 360) + 360) % 360,
            firstFixName: firstWaypoint?.name ?? "",
            traconCeiling: currentTraconCeiling,
            sidProcedure: sidProc,
            starProcedure: starProc,
            approachProcedure: approachProc)
    }

    private func deterministicSquawk() -> String {
        let n = Int(flightPlan.flightNumber.filter { $0.isNumber }) ?? 4271
        return String(format: "%04o", (abs(n) * 7 + 1) % 4096)
    }

    private func resolvedRunway(windDir: Int, arrival: Bool, approach: Procedure? = nil) -> String {
        // A parsed approach's runway wins on arrival; otherwise an explicit
        // flight-plan runway; otherwise derive a sensible runway from the wind.
        if arrival, let rwy = approach?.runway, !rwy.isEmpty { return rwy }
        if !flightPlan.runway.isEmpty { return flightPlan.runway }
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
        plan.departure = settings.departure.isEmpty && settings.mockMode ? mock.route.departure : settings.departure
        plan.destination = settings.destination.isEmpty && settings.mockMode ? mock.route.destination : settings.destination
        plan.alternate = settings.alternate
        plan.cruiseAltitude = settings.cruiseAltitude > 0 ? settings.cruiseAltitude
            : (settings.mockMode ? mock.route.cruiseAltitude : 0)
        plan.runway = settings.runway
        plan.sid = settings.sid
        plan.star = settings.star
        plan.approach = settings.approach
        plan.manualOverride = !settings.departure.isEmpty || !settings.destination.isEmpty
        if settings.mockMode { plan.waypoints = mock.route.waypoints }
        flightPlan = plan
    }

    func applyManualOverrides() {
        syncFlightPlanFromSettings()
        flightPlan.manualOverride = true
        diagnostics.log(.app, "Manual flight-plan overrides applied.")
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
                                           displayText: last.displayText, spokenText: last.spokenText)
            post(repeated, speak: true)
        }
    }

    func unable() {
        let c = buildContext(for: atcState)
        postPilot(pilotEngine.unable(context: c, facility: currentFacility))
        // Deterministic alternate controller response.
        let alt = engine.formatAltDisplay(max(assignedAltitude, c.initialClimbAltitude))
        let tx = ATCTransmission(sender: .atc, facility: currentFacility,
                                 displayText: "\(c.callsign.display), roger, maintain \(alt), advise able to comply.",
                                 spokenText: "\(c.callsign.spoken), roger, maintain \(Phonetic.altitude(max(assignedAltitude, c.initialClimbAltitude))), advise able to comply.")
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
            post(engine.climbMaintain(cs: c.callsign, altitude: target), speak: true)
        }
    }

    func requestLower() {
        let c = buildContext(for: atcState)
        let target = nextAltitude(from: max(assignedAltitude, aircraftAltInt()), up: false)
        postPilot(pilotEngine.requestLower(context: c, target: target))
        post(engine.descendPilotsDiscretion(cs: c.callsign, altitude: target), speak: true)
        assignedAltitude = target
    }

    func requestVectors() {
        let c = buildContext(for: atcState)
        postPilot(pilotEngine.requestVectors(context: c))
        let hdg = Int(aircraftState.heading ?? 270)
        let tx = ATCTransmission(sender: .atc, facility: .approach,
                                 displayText: "\(c.callsign.display), fly heading \(String(format: "%03d", hdg)), vectors for the \(c.approachName) runway \(c.runway) approach.",
                                 spokenText: "\(c.callsign.spoken), fly heading \(Phonetic.heading(hdg)), vectors for the \(c.approachName) runway \(Phonetic.runway(c.runway)) approach.")
        post(tx, speak: true)
    }

    func requestApproach() {
        let c = buildContext(for: atcState)
        postPilot(pilotEngine.requestApproach(context: c))
        post(engine.clearedApproach(cs: c.callsign, approach: flightPlan.approach, runway: c.runway), speak: true)
    }

    func requestHandoff() {
        let c = buildContext(for: atcState)
        postPilot(pilotEngine.requestHandoff(context: c, facility: currentFacility))
        post(engine.radarContact(cs: c.callsign, facility: currentFacility), speak: true)
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
        groundFlow(pilotEngine.requestTaxi(context: buildContext(for: .groundTaxi)), to: .groundTaxi)
    }

    /// Report ready for departure. Tower responds "line up and wait"; tuning the
    /// Tower frequency (or the Takeoff button) then yields the takeoff clearance,
    /// and the pilot drives every controller change from there with the frequency
    /// buttons.
    func reportReadyForDeparture() {
        groundFlow(pilotEngine.readyForDeparture(context: buildContext(for: .lineUpWait)), to: .lineUpWait)
    }

    func requestTakeoff() {
        groundFlow(pilotEngine.requestTakeoff(context: buildContext(for: .towerDeparture)), to: .towerDeparture)
    }

    // MARK: - Weather button actions

    func requestRideReport() {
        let c = buildContext(for: atcState)
        postPilot(pilotEngine.requestRideReports(context: c))
        Task {
            await refreshWeather()
            recomputeRideItems()
            post(rideEngine.rideReport(assessment: rideAssessment, items: rideReportItems, callsign: c.callsign), speak: true)
        }
    }

    func requestDestinationWeather() {
        let c = buildContext(for: atcState)
        let dest = flightPlan.destination
        postPilot(pilotEngine.requestWeather(context: c, airport: dest.isEmpty ? "destination" : dest))
        Task {
            await refreshWeather()
            post(rideEngine.destinationWeather(metar: destinationMETAR, callsign: c.callsign, icao: dest), speak: true)
        }
    }

    func requestLowerDueRide() {
        let c = buildContext(for: atcState)
        recomputeRideItems()
        let target = nextAltitude(from: max(assignedAltitude, aircraftAltInt()), up: false)
        postPilot(pilotEngine.requestLower(context: c, target: target))
        let tx = ATCTransmission(sender: .atc, facility: .center,
                                 displayText: "\(c.callsign.display), descend and maintain \(engine.formatAltDisplay(target)) for a smoother ride, report conditions.",
                                 spokenText: "\(c.callsign.spoken), descend and maintain \(Phonetic.altitude(target)) for a smoother ride, report conditions.")
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
            recomputeRideItems()
            weatherStatus = "Mock weather loaded (\(metars.count) METARs, \(pireps.count) PIREPs)."
            diagnostics.weatherEndpointStatus = "Mock mode — sample data"
            return
        }

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
            recomputeRideItems()
            weatherStatus = "Loaded \(metars.count) METARs, \(pireps.count) PIREPs, \(sigmets.count) SIGMETs."
        } catch {
            let msg = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            weatherStatus = "Weather unavailable: \(msg)"
            diagnostics.log(.weather, "Weather fetch failed: \(msg)")
        }
    }

    func recomputeRideItems() {
        routeAnalyzer.config.corridorNM = settings.routeCorridorNM
        routeAnalyzer.config.altitudeBandFt = settings.altitudeBandFt
        guard let pos = aircraftState.coordinate ?? airports.coordinate(for: flightPlan.departure) else {
            rideReportItems = []
            return
        }
        let end = airports.coordinate(for: flightPlan.destination)
            ?? flightPlan.nextWaypoint(from: pos)?.coordinate
        let alt = aircraftState.altitudeMSL ?? Double(flightPlan.cruiseAltitude)
        let nearestFix = flightPlan.nextWaypoint(from: pos)?.name
        rideReportItems = routeAnalyzer.relevantReports(pireps: pireps, position: pos,
                                                        routeEnd: end, altitudeFt: alt,
                                                        nearestFix: nearestFix)
        let arrivalPhase = [.descent, .approach, .landing, .taxiIn, .parked].contains(phase)
        let nearMETAR = arrivalPhase ? (destinationMETAR ?? departureMETAR) : (departureMETAR ?? destinationMETAR)
        rideAssessment = turbulenceModel.assess(items: rideReportItems, sigmets: sigmets,
                                                metar: nearMETAR, altitudeFt: alt)
    }

    // MARK: - Diagnostics helpers

    func advanceMockPhase() {
        mock.advancePhase()
    }

    func resetAppData() {
        transcript.removeAll()
        latestTransmission = nil
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
        speech.stop()
        transcript.removeAll()
        latestTransmission = nil
        lastATCTransmission = nil
        assignedAltitude = 0
        hasDeparted = false
        arrivalAnnounced = false
        lastSpokenText = ""
        lastSpokenIntent = nil
        phase = .preflight
        phaseDetector = PhaseDetector()
        stateMachine.reset()
        atcState = .connectedIdle
        currentFacility = .ground
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
