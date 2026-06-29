import Foundation
import Combine
import CoreLocation
#if canImport(UIKit)
import UIKit
#endif

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

    /// Whether the companion should defer to a human controller right now.
    var companionStandby: Bool { liveATC.shouldStandBy }

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
        applyKeepScreenAwake()
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

        settings.$unicomModeRaw
            .sink { [weak self] _ in self?.unicom.mode = self?.currentUnicomMode ?? .preview }
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
        tunedFacility = nil
        clearReadbackGate()
        stateMachine.reset()
        hasDeparted = false
        arrivalAnnounced = false
        awaitingGateArrival = false
        atcState = .connectedIdle
        currentFacility = .clearance
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
        tunedFacility = nil
        clearReadbackGate()
        stateMachine.reset()
        hasDeparted = false
        arrivalAnnounced = false
        awaitingGateArrival = false
        currentFacility = .clearance
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

        // After contacting arrival Ramp, hold the "flight complete" block-in until the
        // aircraft is actually parked at the gate (stopped, parking brake set). This
        // runs even while manually tuned, since the pilot drove the Ramp call by hand.
        if awaitingGateArrival {
            if isParkedAtGate(state) { completeGateArrival() }
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
            } else if stateMachine.current == .lineUpWait, !settings.mockMode, !manualTuning,
                      isReadyForTakeoffClearance(state: state) {
                autoIssueTakeoffClearance()
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
        // When the pilot is changing controllers manually with the frequency
        // buttons, the conversation advances only on a button press. Keep the
        // position/phase display fresh, but do not auto-issue controller calls —
        // that is what makes the calls pile up "one after the next".
        if manualTuning {
            atcState = stateMachine.current
            currentFacility = tunedFacility ?? controller(for: stateMachine.current)
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
                                speak: Bool = true, announceHandoff: Bool = true,
                                automatic: Bool = false) {
        let previous = stateMachine.current
        // The conversation is advancing, so any pending manual tune has now been
        // acted on — the controller working the new state speaks for itself.
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
           lastATCTransmission != nil {
            post(engine.handoff(cs: c.callsign, from: fromFacility, to: toFacility,
                                frequency: frequency(for: toFacility, context: c)), speak: speak)
        }
        updateAssignedAltitude(for: target, context: c)
        post(tx, speak: speak)
        fireUnicomForTransition(into: target)
        // An automatic call that carries a read-back instruction closes the gate:
        // the flow holds here until the pilot reads back (or the idle prompt loop
        // runs its course). Pilot-driven advances never close the gate — the pilot
        // is already driving the conversation.
        if automatic, target.expectsReadback {
            engageReadbackGate(tx)
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
        advanceAndPost(to: .towerDeparture, context: buildContext(for: .towerDeparture),
                       automatic: true)
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
    /// pilot's read-back, and arm the idle re-prompt loop.
    private func engageReadbackGate(_ tx: ATCTransmission) {
        guard !settings.mockMode else { return }
        awaitingReadback = true
        pendingReadbackTx = tx
        readbackPrompts = 0
        armReadbackTimer()
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

    /// Manually tune the radio to a facility. Switching frequency does **not**
    /// check in or advance the conversation on its own: it only moves the radio to
    /// the new controller. From here the pilot either taps Check In to call up the
    /// controller (and get the next instruction) or makes a specific request — e.g.
    /// requesting pushback after Clearance hands you to Ground. This is how the
    /// pilot drives the flight forward without calls auto-playing back-to-back.
    func tuneTo(_ facility: ATCFacility) {
        guard !companionStandby else { return }
        manualTuning = true
        tunedFacility = facility
        currentFacility = facility
        // No transcript, no state change, and no mock-phase change: tuning only
        // moves the radio. The mock display advances with the conversation when the
        // pilot checks in or makes a request.
    }

    /// The "Ramp" button. Context-aware: before departure it contacts Ramp for the
    /// pushback (Ramp approves the push; Ground then handles the taxi). On arrival it
    /// contacts Ramp for the taxi-in to the gate. It is never the arrival routine
    /// during departure (which previously jumped straight to "parked, flight
    /// complete").
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
        // Now monitor for the actual gate stop; the block-in fires there, not here.
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

        // Departure keeps the climb until passing the TRACON ceiling, then Center.
        if mapped == .climb, alt < ceiling - 200,
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
        post(engine.handoff(cs: c.callsign, from: .approach, to: .tower,
                            frequency: c.towerFrequency), speak: true)
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
        let c = buildContext(for: atcState, arrivalOverride: true)
        postPilot(pilotEngine.requestVectors(context: c))
        let hdg = Int(aircraftState.heading ?? 270)
        // Name the approach as "<type> runway <rwy>" (e.g. "ILS runway 01R") rather
        // than a display name that already embeds the runway, to avoid "… ILS RWY
        // 01R runway 01R approach".
        let rwy = c.approachProcedure?.runway ?? c.runway
        let typeD = c.approachProcedure?.approachType?.display ?? (c.approachName.isEmpty ? "ILS" : c.approachName)
        let typeS = c.approachProcedure?.approachType?.spoken ?? (c.approachName.isEmpty ? "I L S" : c.approachName)
        let tx = ATCTransmission(sender: .atc, facility: .approach,
                                 displayText: "\(c.callsign.display), fly heading \(String(format: "%03d", hdg)), vectors for the \(typeD) runway \(rwy) approach.",
                                 spokenText: "\(c.callsign.spoken), fly heading \(Phonetic.heading(hdg)), vectors for the \(typeS) runway \(Phonetic.runway(rwy)) approach.")
        post(tx, speak: true)
    }

    func requestApproach() {
        let c = buildContext(for: atcState, arrivalOverride: true)
        postPilot(pilotEngine.requestApproach(context: c))
        // Prefer the procedure-aware clearance (names the runway once) when the
        // approach parsed; otherwise fall back to the plain string form.
        if let approach = c.approachProcedure {
            post(engine.clearedApproach(cs: c.callsign, procedure: approach, runway: c.runway), speak: true)
        } else {
            post(engine.clearedApproach(cs: c.callsign, approach: flightPlan.approach, runway: c.runway), speak: true)
        }
    }

    /// Check in on the currently tuned frequency. Posts the pilot's call-up and the
    /// controller's next instruction for the facility we're tuned to. This is the
    /// deliberate "check in" the pilot makes after switching frequency (tuning no
    /// longer checks in automatically).
    func requestHandoff() {
        guard !companionStandby else { return }
        guard let target = nextState(workedBy: currentFacility, after: stateMachine.current),
              target != stateMachine.current else {
            // Nothing new ahead for this controller — a plain check-in / radar-contact
            // exchange (e.g. a same-sector Center re-check-in).
            let c = buildContext(for: atcState)
            postPilot(pilotEngine.requestHandoff(context: c, facility: currentFacility))
            post(engine.radarContact(cs: c.callsign, facility: currentFacility), speak: true)
            return
        }
        if !target.isManualGroundFlow { hasDeparted = true }
        let c = buildContext(for: target)
        // The pilot checks in on the tuned frequency. Because the pilot initiated the
        // switch, the controller does not precede its reply with a "contact …"
        // hand-off (announceHandoff: false).
        postPilot(pilotEngine.requestHandoff(context: c, facility: currentFacility))
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
        groundFlow(pilotEngine.requestTaxi(context: buildContext(for: .groundTaxi)), to: .groundTaxi)
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
            routeSigmets = []
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
        // Only SIGMETs whose area lies along the route may raise the ride index — a
        // nationwide turbulence advisory far from the route must not read as "severe".
        routeSigmets = routeAnalyzer.relevantSigmets(sigmets, position: pos, routeEnd: end)
        rideAssessment = turbulenceModel.assess(items: rideReportItems, sigmets: routeSigmets,
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
        tunedFacility = nil
        clearReadbackGate()
        speech.stop()
        transcript.removeAll()
        latestTransmission = nil
        lastATCTransmission = nil
        assignedAltitude = 0
        hasDeparted = false
        arrivalAnnounced = false
        awaitingGateArrival = false
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
