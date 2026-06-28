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

    private let weatherService: AviationWeatherService

    // Deterministic engines.
    private var engine: PhraseologyEngine
    private var stateMachine: ATCStateMachine
    private var pilotEngine: PilotResponseEngine
    private var phaseDetector = PhaseDetector()
    private var routeAnalyzer = WeatherRouteAnalyzer()
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

    // Weather state.
    @Published var departureMETAR: METAR?
    @Published var destinationMETAR: METAR?
    @Published var alternateMETAR: METAR?
    @Published var destinationTAF: TAF?
    @Published var pireps: [PIREP] = []
    @Published var sigmets: [SIGMET] = []
    @Published var rideReportItems: [RideReportItem] = []
    @Published var weatherStatus: String = "Not loaded"

    private var lastATCTransmission: ATCTransmission?
    private var cancellables = Set<AnyCancellable>()
    private var started = false

    init() {
        let settings = AppSettings()
        self.settings = settings
        let diagnostics = DiagnosticsStore()
        self.diagnostics = diagnostics
        self.speech = SpeechService()
        self.connect = IFConnectManager()
        self.mock = MockSimulatorFeed()
        self.unicom = UNICOMAutomationService()
        self.weatherService = AviationWeatherService(baseURL: settings.weatherBaseURL)

        self.engine = PhraseologyEngine(digitStyle: settings.digitStyle, mode: settings.phraseologyMode)
        self.stateMachine = ATCStateMachine(engine: engine)
        self.pilotEngine = PilotResponseEngine(engine: engine)
        self.rideEngine = RideReportEngine(engine: engine)
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
            .sink { [weak self] style, mode in
                guard let self else { return }
                self.engine.digitStyle = style
                self.engine.mode = mode
                self.stateMachine = ATCStateMachine(engine: self.engine)
                self.pilotEngine = PilotResponseEngine(engine: self.engine)
                self.rideEngine = RideReportEngine(engine: self.engine)
            }
            .store(in: &cancellables)

        settings.$unicomModeRaw
            .sink { [weak self] _ in self?.unicom.mode = self?.currentUnicomMode ?? .preview }
            .store(in: &cancellables)

        settings.$debugLogging
            .sink { [weak self] on in self?.diagnostics.verbose = on }
            .store(in: &cancellables)
    }

    var currentUnicomMode: UNICOMMode {
        UNICOMMode(rawValue: settings.unicomModeRaw) ?? .preview
    }

    // MARK: - Source selection

    func startMock() {
        connect.disconnect()
        stateMachine.reset()
        atcState = .connectedIdle
        stateMachine.setConnected()
        mock.start()
        diagnostics.log(.app, "Mock simulator feed started.")
        Task { await refreshWeather() }
    }

    func stopMock() {
        mock.stop()
    }

    func startLive() {
        mock.stop()
        stateMachine.reset()
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

    private func handle(state: AircraftState) {
        aircraftState = state
        stateMachine.setConnected()

        let result = phaseDetector.detect(state: state, plan: flightPlan,
                                          airports: airports, previous: phase)
        phase = result.phase
        phaseDebug = result.debug

        let target = stateMachine.mappedState(for: phase)
        let context = buildContext(for: target)
        unicom.connected = settings.mockMode || connect.connectionState.isConnected

        if let tx = stateMachine.advance(to: target, context: context) {
            updateAssignedAltitude(for: target, context: context)
            post(tx, speak: true)
            atcState = stateMachine.current
            currentFacility = target.facility
            fireUnicomForTransition(into: target)
        } else {
            atcState = stateMachine.current
            currentFacility = stateMachine.current.facility
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
        case .initialClimb, .departure: assignedAltitude = max(c.assignedAltitude, c.initialClimbAltitude)
        case .climb, .cruise: assignedAltitude = c.cruiseAltitude
        case .descent: assignedAltitude = max(10000, c.cruiseAltitude - 10000)
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
        if speak && tx.sender != .pilot { speech.speak(tx) }
    }

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

        return ATCContext(
            callsign: cs,
            plan: flightPlan,
            assignedAltitude: assignedAltitude,
            cruiseAltitude: cruise,
            initialClimbAltitude: 5000,
            windDirection: windDir,
            windSpeed: windSpeed,
            squawk: deterministicSquawk(),
            runway: resolvedRunway(windDir: windDir, arrival: arrival),
            taxiway: "Alpha",
            crossingRunway: nil,
            parkingTaxiway: "Bravo",
            approachName: flightPlan.approach.isEmpty ? "the ILS" : flightPlan.approach,
            departureFrequency: 124.300,
            centerFrequency: 132.450,
            approachFrequency: 119.700,
            towerFrequency: 118.300,
            groundFrequency: 121.800)
    }

    private func deterministicSquawk() -> String {
        let n = Int(flightPlan.flightNumber.filter { $0.isNumber }) ?? 4271
        return String(format: "%04o", (abs(n) * 7 + 1) % 4096)
    }

    private func resolvedRunway(windDir: Int, arrival: Bool) -> String {
        if arrival, !flightPlan.runway.isEmpty { return flightPlan.runway }
        if !arrival, !flightPlan.runway.isEmpty { return flightPlan.runway }
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

    // MARK: - Pilot button actions

    func readBack() {
        let c = buildContext(for: atcState)
        post(pilotEngine.readback(for: atcState, context: c), speak: false)
    }

    func sayAgain() {
        let c = buildContext(for: atcState)
        post(pilotEngine.sayAgain(context: c, facility: currentFacility), speak: false)
        if let last = lastATCTransmission {
            let repeated = ATCTransmission(sender: .atc, facility: last.facility,
                                           displayText: last.displayText, spokenText: last.spokenText)
            post(repeated, speak: true)
        }
    }

    func unable() {
        let c = buildContext(for: atcState)
        post(pilotEngine.unable(context: c, facility: currentFacility), speak: false)
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
        post(pilotEngine.requestHigher(context: c, target: target), speak: false)
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
        post(pilotEngine.requestLower(context: c, target: target), speak: false)
        post(engine.descendPilotsDiscretion(cs: c.callsign, altitude: target), speak: true)
        assignedAltitude = target
    }

    func requestVectors() {
        let c = buildContext(for: atcState)
        post(pilotEngine.requestVectors(context: c), speak: false)
        let hdg = Int(aircraftState.heading ?? 270)
        let tx = ATCTransmission(sender: .atc, facility: .approach,
                                 displayText: "\(c.callsign.display), fly heading \(String(format: "%03d", hdg)), vectors for the \(c.approachName) runway \(c.runway) approach.",
                                 spokenText: "\(c.callsign.spoken), fly heading \(Phonetic.heading(hdg)), vectors for the \(c.approachName) runway \(Phonetic.runway(c.runway)) approach.")
        post(tx, speak: true)
    }

    func requestApproach() {
        let c = buildContext(for: atcState)
        post(pilotEngine.requestApproach(context: c), speak: false)
        post(engine.clearedApproach(cs: c.callsign, approach: flightPlan.approach, runway: c.runway), speak: true)
    }

    func requestHandoff() {
        let c = buildContext(for: atcState)
        post(pilotEngine.requestHandoff(context: c, facility: currentFacility), speak: false)
        post(engine.radarContact(cs: c.callsign, facility: currentFacility), speak: true)
    }

    // MARK: - Weather button actions

    func requestRideReport() {
        let c = buildContext(for: atcState)
        post(pilotEngine.requestRideReports(context: c), speak: false)
        Task {
            await refreshWeather()
            recomputeRideItems()
            post(rideEngine.rideReport(items: rideReportItems, callsign: c.callsign), speak: true)
        }
    }

    func requestDestinationWeather() {
        let c = buildContext(for: atcState)
        let dest = flightPlan.destination
        post(pilotEngine.requestWeather(context: c, airport: dest.isEmpty ? "destination" : dest), speak: false)
        Task {
            await refreshWeather()
            post(rideEngine.destinationWeather(metar: destinationMETAR, callsign: c.callsign, icao: dest), speak: true)
        }
    }

    func requestLowerDueRide() {
        let c = buildContext(for: atcState)
        recomputeRideItems()
        let target = nextAltitude(from: max(assignedAltitude, aircraftAltInt()), up: false)
        post(pilotEngine.requestLower(context: c, target: target), speak: false)
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
}
