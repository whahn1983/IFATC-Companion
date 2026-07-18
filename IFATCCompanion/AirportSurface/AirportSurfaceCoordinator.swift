import Foundation
import CoreLocation
import Combine

/// Live display aircraft used by the taxi map (real telemetry in live mode, a
/// simulated track in mock mode).
struct TaxiAircraft: Equatable {
    var coordinate: GeoCoordinate
    var headingDegrees: Double
    var onGround: Bool
    var groundSpeedKnots: Double
}

/// Loading/availability status of the airport surface.
enum AirportSurfaceStatus: Equatable {
    case idle
    case loading
    case ready
    case unavailable(String)
    case error(String)

    var isReady: Bool { self == .ready }
}

/// Which taxi phase the coordinator is servicing.
enum TaxiKind: Equatable { case none, departure, arrival }

/// Response actions surfaced on the taxi map for the crossing / off-route flows.
enum TaxiMapAction: String, CaseIterable, Identifiable {
    case holdPosition, requestCrossing, requestAlternateRoute
    case recalculate, continueOriginalRoute, requestNewTaxi
    var id: String { rawValue }
    var title: String {
        switch self {
        case .holdPosition: return "Hold Position"
        case .requestCrossing: return "Request Crossing"
        case .requestAlternateRoute: return "Alt Route"
        case .recalculate: return "Recalculate"
        case .continueOriginalRoute: return "Continue"
        case .requestNewTaxi: return "New Taxi"
        }
    }
    var systemImage: String {
        switch self {
        case .holdPosition: return "hand.raised"
        case .requestCrossing: return "arrow.left.and.right"
        case .requestAlternateRoute: return "arrow.triangle.branch"
        case .recalculate: return "arrow.triangle.2.circlepath"
        case .continueOriginalRoute: return "arrow.forward"
        case .requestNewTaxi: return "arrow.uturn.left"
        }
    }
}

/// Coordinates the OpenStreetMap airport-surface feature: loads/normalizes/caches the
/// surface, builds the graph, calculates taxi routes, drives the temporary MapKit taxi
/// map, and runs the simulated Ground runway-crossing workflow. Owned by `AppModel`,
/// which calls into it at the taxi/hand-off/arrival lifecycle points and forwards
/// telemetry; the ATC/taxi views observe its published state.
///
/// Everything is framed as flight-simulation only and never presents OSM data as
/// authoritative.
@MainActor
final class AirportSurfaceCoordinator: ObservableObject {

    // MARK: - Published UI state

    @Published private(set) var status: AirportSurfaceStatus = .idle
    @Published private(set) var taxiMapVisible = false
    @Published var mapExpanded = false
    @Published private(set) var route: SurfaceTaxiRoute?
    @Published private(set) var surface: AirportSurfaceModel?
    @Published private(set) var datasetConfidence: SurfaceConfidence = .unavailable
    @Published private(set) var routeConfidence: SurfaceConfidence = .unavailable
    @Published private(set) var crossingState: RunwayCrossingState = .noCrossingPending
    @Published private(set) var activeCrossing: RouteCrossing?
    @Published private(set) var progress: RouteTracker.Progress?
    @Published private(set) var displayAircraft: TaxiAircraft?
    @Published private(set) var nextInstruction = ""
    @Published private(set) var offRoute = false
    @Published private(set) var reachedDestination = false
    @Published private(set) var lastError: String?
    @Published private(set) var awaitingCrossingReadback = false
    @Published private(set) var awaitingTaxiReadback = false

    /// Manual overrides (Settings / taxi map). Default per requirements.
    @Published var autoCrossingCalls = true
    @Published var autoRecalculate = false

    // MARK: - Dependencies

    private let provider: AirportSurfaceProvider
    private weak var diagnostics: DiagnosticsStore?
    private let tracker = RouteTracker()
    private var phraseology = TaxiPhraseology(engine: PhraseologyEngine())

    /// Posts a simulated ATC transmission into the transcript (and speaks it).
    var emitATC: ((ATCTransmission) -> Void)?
    /// Provides the current callsign for crossing/hold phraseology.
    var callsignProvider: (() -> PhraseologyEngine.Callsign)?

    // MARK: - Internal state

    @Published private(set) var kind: TaxiKind = .none
    private var graph: SurfaceGraph?
    private var icao = ""
    private var reference = CLLocationCoordinate2D()
    private var aircraftClass: AircraftSizeClass = .medium
    private var assignedRunway = ""
    private var gate = ""
    private var mockMode = false
    private var startGate = ""

    private var taxiReadBack = false
    /// A generic Ground taxi clearance was issued before the surface finished loading
    /// (uncached live airports load asynchronously). Supersede it with the detailed OSM
    /// route clearance once `recomputeRoute()` produces a credible route.
    private var pendingDetailedClearance = false
    private var lastAlong = 0.0
    private var offRouteTicks = 0
    private var unauthorizedTicks = 0
    private var holdSettleTicks = 0

    private var workedCrossingIndex: Int?
    private var authorizedCrossingIndex: Int?
    private var pendingCrossingIndex: Int?
    private var issuedHoldShortFor = Set<Int>()
    private var issuedClearanceFor = Set<Int>()
    private var completedCrossings = Set<Int>()
    private var userRequestedCrossingFor = Set<Int>()
    private var emittedResumeFor = Set<Int>()

    private var loadGeneration = 0
    private var mockTask: Task<Void, Never>?
    private var mockAlong = 0.0

    // Workflow tuning (meters unless noted).
    private let detectAheadMeters = 250.0
    private let approachMeters = 130.0
    private let holdIssueMeters = 90.0
    private let atHoldMeters = 20.0
    private let corridorEnterMeters = 30.0
    private let vacateMarginMeters = 42.0
    private let holdBeforeCrossingMeters = 25.0
    private let settleTicks = 2
    private let mockStepMeters = 4.0
    private let mockTickSeconds: UInt64 = 400_000_000

    init(provider: AirportSurfaceProvider = AirportSurfaceProvider()) {
        self.provider = provider
    }

    func configure(diagnostics: DiagnosticsStore?,
                   engine: PhraseologyEngine,
                   emit: @escaping (ATCTransmission) -> Void,
                   callsign: @escaping () -> PhraseologyEngine.Callsign) {
        self.diagnostics = diagnostics
        self.phraseology = TaxiPhraseology(engine: engine)
        self.emitATC = emit
        self.callsignProvider = callsign
        Task { await provider.configure(diagnostics: diagnostics) }
    }

    func updateEngine(_ engine: PhraseologyEngine) { phraseology = TaxiPhraseology(engine: engine) }

    // MARK: - Prefetch / load

    /// Begin loading an airport's surface ahead of time (no map reveal). Safe to call
    /// repeatedly; ignored when the surface is already loaded for that ICAO.
    func prefetch(icao: String, reference: CLLocationCoordinate2D, mock: Bool) {
        let key = icao.uppercased()
        if surface?.icao == key { return }
        Task { await loadSurface(icao: key, reference: reference, mock: mock, forceRefresh: false) }
    }

    /// Cache both the flight's departure and arrival airport surfaces at flight load,
    /// rather than lazily right before taxi. The departure surface is loaded into the
    /// coordinator so its taxi routes synchronously and issues the detailed clearance
    /// immediately; the arrival surface is fetched into the provider cache (disk + memory)
    /// so its later load is instant and works offline. Live surfaces only — mock airports
    /// build synthetic surfaces on demand, so there is nothing to pre-cache.
    func prefetchFlightSurfaces(departure: String, departureReference: CLLocationCoordinate2D?,
                                arrival: String, arrivalReference: CLLocationCoordinate2D?) {
        let dep = departure.uppercased()
        let arr = arrival.uppercased()
        // The departure surface goes into the coordinator so its taxi routes synchronously
        // — but only between flights, never clobbering an active taxi's loaded surface.
        if kind == .none, dep.count >= 3, let ref = departureReference, ref.isValid {
            prefetch(icao: dep, reference: ref, mock: false)
        }
        // The arrival only warms the provider cache (it never touches the coordinator's
        // active surface), so it is always safe to run — including in cruise before the
        // arrival taxi begins.
        if arr.count >= 3, arr != dep, let ref = arrivalReference, ref.isValid {
            warmCache(icao: arr, reference: ref)
        }
    }

    /// Warm the disk/memory surface cache for an airport without disturbing the active
    /// taxi surface. Used to pre-cache the arrival field while the departure surface stays
    /// loaded in the coordinator, so the arrival's later load is instant and offline.
    private func warmCache(icao: String, reference: CLLocationCoordinate2D) {
        let key = icao.uppercased()
        guard key.count >= 3, reference.isValid else { return }
        Task {
            do {
                _ = try await provider.surface(for: key, reference: reference, forceRefresh: false)
                diagnostics?.log(.app, "OSM surface pre-cached for \(key)")
            } catch {
                let message = (error as? LocalizedError)?.errorDescription ?? "\(error)"
                diagnostics?.log(.app, "OSM pre-cache failed for \(key): \(message)")
            }
        }
    }

    private func loadSurface(icao: String, reference: CLLocationCoordinate2D, mock: Bool, forceRefresh: Bool) async {
        let key = icao.uppercased()
        guard key.count >= 3, reference.isValid else { return }
        loadGeneration += 1
        let generation = loadGeneration
        status = .loading
        self.icao = key
        self.reference = reference
        self.mockMode = mock

        if mock {
            let model = MockAirportSurface.model(icao: key, reference: reference,
                                                 primaryRunwayIdent: mockPrimaryRunway(), gate: mockGate())
            applyLoaded(model, generation: generation)
            return
        }

        do {
            let model = try await provider.surface(for: key, reference: reference, forceRefresh: forceRefresh)
            guard generation == loadGeneration else { return }
            applyLoaded(model, generation: generation)
        } catch {
            guard generation == loadGeneration else { return }
            let message = (error as? LocalizedError)?.errorDescription ?? "\(error)"
            lastError = message
            surface = nil
            graph = nil
            datasetConfidence = .unavailable
            status = .unavailable(message)
            diagnostics?.log(.app, "OSM surface unavailable for \(key): \(message)")
            recomputeRoute()
        }
    }

    private func applyLoaded(_ model: AirportSurfaceModel, generation: Int) {
        guard generation == loadGeneration else { return }
        let builtGraph = SurfaceGraphBuilder.build(from: model)
        var m = model
        m.confidence = SurfaceConfidenceEvaluator.datasetConfidence(model: model, graph: builtGraph)
        surface = m
        graph = builtGraph
        datasetConfidence = m.confidence
        lastError = nil
        status = m.hasUsableGeometry ? .ready : .unavailable("No usable airport surface geometry.")
        diagnostics?.log(.app, "OSM surface ready \(m.icao): \(builtGraph.nodes.count) nodes, \(builtGraph.edges.count) edges, \(m.confidence.title)")
        recomputeRoute()
    }

    // MARK: - Departure / arrival entry

    /// Prepare a departure taxi to the assigned runway from the gate/current position.
    func beginDeparture(icao: String, reference: CLLocationCoordinate2D, aircraftName: String?,
                        runway: String, gate: String, startCoordinate: CLLocationCoordinate2D,
                        mock: Bool) {
        kind = .departure
        aircraftClass = AircraftSizeClass.classify(aircraftName: aircraftName)
        assignedRunway = runway
        self.gate = gate
        self.startGate = gate
        self.mockMode = mock
        self.pendingStart = startCoordinate
        resetTaxiProgress()
        loadForTaxi(icao: icao, reference: reference, mock: mock)
    }

    /// Prepare an arrival taxi to the gate from the runway exit / current position.
    func beginArrival(icao: String, reference: CLLocationCoordinate2D, aircraftName: String?,
                      gate: String, startCoordinate: CLLocationCoordinate2D, mock: Bool) {
        kind = .arrival
        aircraftClass = AircraftSizeClass.classify(aircraftName: aircraftName)
        assignedRunway = ""
        self.gate = gate
        self.startGate = ""
        self.mockMode = mock
        self.pendingStart = startCoordinate
        resetTaxiProgress()
        loadForTaxi(icao: icao, reference: reference, mock: mock)
    }

    /// Ensure the surface is available for a taxi. Mock builds synchronously (so the
    /// OSM taxi clearance is ready immediately); a cached live surface routes at once;
    /// an uncached live surface loads asynchronously and reveals the map when ready.
    private func loadForTaxi(icao: String, reference: CLLocationCoordinate2D, mock: Bool) {
        if mock {
            loadGeneration += 1
            let generation = loadGeneration
            self.icao = icao.uppercased()
            self.reference = reference
            self.mockMode = true
            status = .loading
            let model = MockAirportSurface.model(icao: icao, reference: reference,
                                                 primaryRunwayIdent: mockPrimaryRunway(), gate: mockGate())
            applyLoaded(model, generation: generation)
        } else if surface?.icao == icao.uppercased(), graph != nil {
            self.reference = reference
            recomputeRoute()
        } else {
            Task { await loadSurface(icao: icao, reference: reference, mock: false, forceRefresh: false) }
        }
    }

    private var pendingStart: CLLocationCoordinate2D?

    private func resetTaxiProgress() {
        taxiReadBack = false
        pendingDetailedClearance = false
        lastAlong = 0
        offRouteTicks = 0
        unauthorizedTicks = 0
        holdSettleTicks = 0
        workedCrossingIndex = nil
        authorizedCrossingIndex = nil
        pendingCrossingIndex = nil
        issuedHoldShortFor.removeAll()
        issuedClearanceFor.removeAll()
        completedCrossings.removeAll()
        userRequestedCrossingFor.removeAll()
        emittedResumeFor.removeAll()
        offRoute = false
        reachedDestination = false
        crossingState = .noCrossingPending
        activeCrossing = nil
        awaitingCrossingReadback = false
        mockAlong = 0
    }

    /// (Re)compute the taxi route for the current kind/params from the loaded graph.
    private func recomputeRoute() {
        guard let graph, let surface, kind != .none else { return }
        let engine = TaxiRouteEngine(graph: graph, model: surface)
        let start = pendingStart ?? reference
        let request: TaxiRouteEngine.Request
        if kind == .departure {
            request = .init(startCoordinate: mockMode ? MockAirportSurface.gateCoordinate(reference: reference) : start,
                            startGateName: mockMode ? mockGate() : (startGate.isEmpty ? nil : startGate),
                            isDeparture: true,
                            assignedRunwayIdent: mockMode ? mockPrimaryRunway() : assignedRunway,
                            arrivalGateName: nil,
                            aircraft: aircraftClass)
        } else {
            request = .init(startCoordinate: mockMode ? MockAirportSurface.runwayExitCoordinate(reference: reference) : start,
                            startGateName: nil,
                            isDeparture: false,
                            assignedRunwayIdent: nil,
                            arrivalGateName: mockMode ? mockGate() : (gate.isEmpty ? nil : gate),
                            aircraft: aircraftClass)
        }

        if let r = engine.route(request) {
            route = r
            routeConfidence = r.confidence
            if mockMode { assignedRunway = r.holdShortRunway ?? assignedRunway; gate = r.arrivalGate ?? gate }
            diagnostics?.log(.app, "Taxi route \(kind == .departure ? "DEP" : "ARR") \(r.destinationLabel): \(Int(r.distanceMeters)) m, \(r.crossings.count) crossing(s), \(r.confidence.title)")
        } else {
            route = nil
            routeConfidence = surface.hasUsableGeometry ? .low : .unavailable
            diagnostics?.log(.app, "Taxi route could not be calculated (\(kind == .departure ? "DEP" : "ARR"))")
        }
        updateInstruction()
        // A generic clearance issued while the surface was still loading is now
        // superseded by the detailed OSM route clearance.
        issueDeferredTaxiClearanceIfNeeded()
        // If the pilot already read back the taxi clearance, reveal the map now.
        if taxiReadBack { revealIfReady() }
    }

    /// Issue the detailed OSM departure taxi clearance that couldn't be sent when the
    /// pilot first requested taxi because the live surface was still loading. Emits the
    /// route clearance (superseding the generic one) and re-arms its read-back so the
    /// pilot's acknowledgement reveals the taxi map. Implements `requestTaxi()`'s
    /// documented "its Ground clearance replaces the generic one" for asynchronously
    /// loaded surfaces.
    private func issueDeferredTaxiClearanceIfNeeded() {
        guard pendingDetailedClearance, kind == .departure,
              let route, routeConfidence.allowsDetailedRouting else { return }
        pendingDetailedClearance = false
        let runway = route.holdShortRunway ?? assignedRunway
        emit(phraseology.taxiClearance(cs: cs(), route: route, runway: runway))
        awaitingTaxiReadback = true
        diagnostics?.log(.atc, "OSM taxi route ready — superseding generic clearance with detailed route to runway \(runway)")
    }

    // MARK: - Taxi clearance text (for AppModel to post)

    /// The Ground taxi clearance for the current route, or a conservative fallback when
    /// confidence is too low / no route. Returns nil when the caller should keep its own
    /// generic clearance (route not computed yet).
    func taxiClearance(callsign: PhraseologyEngine.Callsign) -> ATCTransmission? {
        guard kind != .none else { return nil }
        if let route, routeConfidence.allowsDetailedRouting {
            if kind == .departure {
                return phraseology.taxiClearance(cs: callsign, route: route, runway: route.holdShortRunway ?? assignedRunway)
            } else {
                return phraseology.arrivalTaxi(cs: callsign, route: route, gate: route.arrivalGate ?? gate)
            }
        }
        // Route unavailable/low: conservative departure fallback (arrival keeps generic).
        if kind == .departure, case .ready = status {
            return phraseology.lowConfidenceTaxi(cs: callsign, runway: assignedRunway)
        }
        if kind == .departure, case .unavailable = status {
            return phraseology.lowConfidenceTaxi(cs: callsign, runway: assignedRunway)
        }
        return nil
    }

    /// Mark that an OSM-based taxi clearance was issued (so a subsequent Read Back
    /// reveals the taxi map). Pass `supersedeWhenRouteReady: true` when the clearance
    /// that went out was the generic fallback because the live surface was still
    /// loading — the detailed route clearance is then issued automatically once the
    /// asynchronous load resolves.
    func taxiClearanceIssued(supersedeWhenRouteReady: Bool = false) {
        awaitingTaxiReadback = true
        pendingDetailedClearance = supersedeWhenRouteReady
    }

    /// Called by AppModel after the pilot reads back the taxi clearance.
    func taxiReadBackComplete() {
        awaitingTaxiReadback = false
        taxiReadBack = true
        revealIfReady()
    }

    private func revealIfReady() {
        guard taxiReadBack else { return }
        guard route != nil || status.isReady else { return }
        taxiMapVisible = true
        updateInstruction()
        if mockMode { startMockDrive() }
    }

    // MARK: - Hide / clear

    /// Hide the taxi map (Ground→Tower hand-off, or ramp/gate phase after arrival).
    func hideTaxiMap() {
        taxiMapVisible = false
        mapExpanded = false
        // The ground-taxi phase is over — don't let a late-resolving surface load
        // supersede the clearance with a stray Ground call after the hand-off.
        pendingDetailedClearance = false
        stopMockDrive()
    }

    /// Fully reset the taxi feature (clear flight / reset app data).
    func clear() {
        hideTaxiMap()
        kind = .none
        route = nil
        routeConfidence = .unavailable
        resetTaxiProgress()
        awaitingTaxiReadback = false
        nextInstruction = ""
        displayAircraft = nil
        progress = nil
    }

    // MARK: - Live telemetry

    /// Forward live aircraft telemetry (live mode only). No-op in mock mode or when no
    /// taxi is active.
    func updateLive(coordinate: CLLocationCoordinate2D?, heading: Double?, onGround: Bool?, groundSpeed: Double?) {
        guard !mockMode, kind != .none, taxiMapVisible, route != nil else { return }
        guard let coordinate, coordinate.isValid else { return }
        displayAircraft = TaxiAircraft(coordinate: GeoCoordinate(coordinate),
                                       headingDegrees: heading ?? displayAircraft?.headingDegrees ?? 0,
                                       onGround: onGround ?? true,
                                       groundSpeedKnots: groundSpeed ?? 0)
        advanceTracking()
    }

    // MARK: - Mock drive

    private func startMockDrive() {
        stopMockDrive()
        mockAlong = 0
        lastAlong = 0
        if let r = route, let first = r.clGeometry.first {
            displayAircraft = TaxiAircraft(coordinate: GeoCoordinate(first), headingDegrees: mockHeading(at: 0),
                                           onGround: true, groundSpeedKnots: 0)
        }
        mockTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: self?.mockTickSeconds ?? 400_000_000)
                guard let self, !Task.isCancelled else { break }
                await MainActor.run { self.mockTick() }
            }
        }
    }

    private func stopMockDrive() {
        mockTask?.cancel()
        mockTask = nil
    }

    private func mockTick() {
        guard mockMode, taxiMapVisible, let r = route else { return }
        let line = r.clGeometry
        guard line.count >= 2 else { return }

        // How far the aircraft may advance right now: stop at the first not-yet-cleared
        // crossing's hold-short point.
        var allowed = r.distanceMeters
        for c in r.crossings {
            if completedCrossings.contains(c.index) { continue }
            if authorizedCrossingIndex == c.index { continue }
            let holdAlong = max(0, c.alongMeters - holdBeforeCrossingMeters)
            if holdAlong >= mockAlong - 1 { allowed = min(allowed, holdAlong); break }
        }
        let previous = mockAlong
        mockAlong = min(allowed, mockAlong + mockStepMeters)
        let moved = mockAlong - previous > 0.05

        if let point = SurfaceGeometry.pointAlong(line, meters: mockAlong) {
            displayAircraft = TaxiAircraft(coordinate: GeoCoordinate(point),
                                           headingDegrees: mockHeading(at: mockAlong),
                                           onGround: true,
                                           groundSpeedKnots: moved ? 16 : 0)
        }
        advanceTracking()
        if mockAlong >= r.distanceMeters - 1 { stopMockDrive() }
    }

    private func mockHeading(at along: Double) -> Double {
        guard let line = route?.clGeometry, line.count >= 2 else { return 0 }
        let a = SurfaceGeometry.pointAlong(line, meters: along) ?? line[0]
        let b = SurfaceGeometry.pointAlong(line, meters: along + 12) ?? line[line.count - 1]
        if SurfaceGeometry.distanceMeters(a, b) < 0.5 { return displayAircraft?.headingDegrees ?? 0 }
        return Geo.bearing(from: a, to: b)
    }

    // MARK: - Tracking + crossing workflow

    private func advanceTracking() {
        guard let route, let ac = displayAircraft else { return }
        let prog = tracker.progress(aircraft: ac.coordinate.clLocation, route: route, minAlong: lastAlong)
        lastAlong = max(lastAlong, prog.alongMeters)
        progress = prog

        // Off-route (live only; mock stays on the synthetic line).
        if !mockMode {
            if !prog.onRoute {
                offRouteTicks += 1
                if offRouteTicks >= 3 { offRoute = true }
            } else {
                offRouteTicks = 0
                offRoute = false
            }
        }

        // Destination.
        if prog.reachedDestination && !reachedDestination {
            reachedDestination = true
        }

        runCrossingWorkflow(route: route, aircraft: ac, progress: prog)
        updateInstruction()
    }

    private func runCrossingWorkflow(route: SurfaceTaxiRoute, aircraft ac: TaxiAircraft, progress prog: RouteTracker.Progress) {
        // Pick the crossing currently being worked.
        if workedCrossingIndex == nil, let idx = prog.nextCrossingIndex, !completedCrossings.contains(idx) {
            workedCrossingIndex = idx
        }
        guard let wc = workedCrossingIndex, route.crossings.indices.contains(wc) else {
            if crossingState != .noCrossingPending && !crossingState.isAuthorized { setCrossing(.noCrossingPending) }
            activeCrossing = nil
            return
        }
        let c = route.crossings[wc]
        activeCrossing = c
        let along = prog.alongMeters
        let dCross = c.alongMeters - along
        let holdAlong = max(0, c.alongMeters - holdBeforeCrossingMeters)
        let dHold = holdAlong - along
        let authorized = authorizedCrossingIndex == c.index

        if authorized {
            if along >= c.alongMeters + vacateMarginMeters {
                setCrossing(.runwayVacated)
                completedCrossings.insert(c.index)
                if !emittedResumeFor.contains(c.index) {
                    emittedResumeFor.insert(c.index)
                    emit(phraseology.resumeTaxi(cs: cs(), runway: assignedRunway, isDeparture: kind == .departure, gate: gate))
                }
                setCrossing(.taxiResumed)
                workedCrossingIndex = nil
                if authorizedCrossingIndex == c.index { authorizedCrossingIndex = nil }
            } else if along >= c.alongMeters {
                setCrossing(.runwayCenterlineCrossed)
            } else if dCross <= corridorEnterMeters {
                setCrossing(.crossingInProgress)
            } else {
                setCrossing(.crossingAuthorized)
            }
            return
        }

        // Not authorized — unauthorized-entry safety net (live; mock never trips it).
        if dCross <= corridorEnterMeters && ac.groundSpeedKnots > 1 && headingTowardCrossing(ac: ac, crossing: c) {
            unauthorizedTicks += 1
            if unauthorizedTicks >= 2 {
                logUnauthorized(c: c, along: along, ac: ac, dHold: dHold)
                if dCross <= 8 {
                    emitOnceUnauthorized(stop: true, c: c)
                } else {
                    emitOnceUnauthorized(stop: false, c: c)
                }
                setCrossing(.unauthorizedCrossingDetected)
            }
            return
        } else {
            unauthorizedTicks = 0
        }

        // Normal pre-authorization sequence.
        if dCross <= detectAheadMeters, crossingState == .noCrossingPending {
            setCrossing(.crossingDetectedAhead)
        }
        if dHold <= approachMeters, crossingState.rawValue == RunwayCrossingState.crossingDetectedAhead.rawValue {
            setCrossing(.approachingHoldingPosition)
        }
        if dHold <= holdIssueMeters, !issuedHoldShortFor.contains(c.index) {
            issuedHoldShortFor.insert(c.index)
            emit(phraseology.holdShort(cs: cs(), runwayIdent: c.runwayIdent))
            setCrossing(.holdShortInstructionIssued)
        }
        let lowConfidence = c.confidence == .low || routeConfidence == .low || routeConfidence == .unavailable
        if dHold <= atHoldMeters, ac.groundSpeedKnots < 4 {
            if crossingState != .holdingShort && crossingState != .crossingClearanceIssued && crossingState != .awaitingPilotReadback {
                setCrossing(lowConfidence ? .lowConfidenceCrossingData : .holdingShort)
            }
            holdSettleTicks += 1
        }

        let autoAllowed = autoCrossingCalls && routeConfidence == .high && c.confidence != .low
        let mayIssue = (crossingState == .holdingShort || crossingState == .lowConfidenceCrossingData)
            && holdSettleTicks >= settleTicks
            && !issuedClearanceFor.contains(c.index)
            && (autoAllowed || userRequestedCrossingFor.contains(c.index))
        if mayIssue {
            issueCrossingClearance(c)
        }
    }

    private func issueCrossingClearance(_ c: RouteCrossing) {
        issuedClearanceFor.insert(c.index)
        setCrossing(.crossingClearanceReady)
        let via = crossingTaxiwayName(for: c)
        emit(phraseology.crossingClearance(cs: cs(), runwayIdent: c.runwayIdent, atTaxiway: via))
        pendingCrossingIndex = c.index
        awaitingCrossingReadback = true
        setCrossing(.crossingClearanceIssued)
        setCrossing(.awaitingPilotReadback)
    }

    private func crossingTaxiwayName(for c: RouteCrossing) -> String? {
        graph?.edges.first { $0.id == c.edgeID }?.taxiwayName
    }

    // MARK: - Pilot / user actions

    /// Called by AppModel after the pilot reads back a crossing clearance.
    func crossingReadbackReceived() {
        guard let idx = pendingCrossingIndex else { return }
        authorizedCrossingIndex = idx
        awaitingCrossingReadback = false
        pendingCrossingIndex = nil
        setCrossing(.crossingAuthorized)
    }

    /// User taps Request Crossing (Medium/Low confidence, or when auto calls are off).
    func requestCrossing() {
        guard let wc = workedCrossingIndex, let route, route.crossings.indices.contains(wc) else { return }
        userRequestedCrossingFor.insert(route.crossings[wc].index)
        // If already holding short, this permits the clearance on the next tick; nudge now.
        if let ac = displayAircraft { runCrossingWorkflow(route: route, aircraft: ac, progress: progress ?? tracker.progress(aircraft: ac.coordinate.clLocation, route: route, minAlong: lastAlong)) }
    }

    func holdPosition() {
        guard let wc = workedCrossingIndex, let route, route.crossings.indices.contains(wc) else { return }
        emit(phraseology.holdShort(cs: cs(), runwayIdent: route.crossings[wc].runwayIdent))
        setCrossing(.holdingShort)
    }

    func requestAlternateRoute() { recomputeRoute() }

    // Off-route actions.
    func recalculateRoute() {
        offRoute = false
        offRouteTicks = 0
        lastAlong = 0
        // Recompute from the current aircraft position.
        if let ac = displayAircraft { pendingStart = ac.coordinate.clLocation }
        recomputeRoute()
    }

    func continueOriginalRoute() {
        offRoute = false
        offRouteTicks = 0
    }

    func requestNewTaxiInstructions() {
        recalculateRoute()
    }

    /// Manual refresh of the airport data (user-initiated).
    func refreshData() {
        guard !icao.isEmpty else { return }
        Task { await loadSurface(icao: icao, reference: reference, mock: mockMode, forceRefresh: true) }
    }

    /// Delete cached surfaces (Settings).
    func clearCache() { Task { await provider.clearCache() } }
    func cacheInfo() async -> (icaos: [String], bytes: Int) { await provider.cacheInfo() }

    // MARK: - Helpers

    private func cs() -> PhraseologyEngine.Callsign {
        callsignProvider?() ?? PhraseologyEngine.Callsign(display: "Aircraft", spoken: "aircraft")
    }

    /// Gate label used for the mock demo (the flight's gate, else a default).
    private func mockGate() -> String { gate.isEmpty ? MockAirportSurface.defaultGateName : gate }
    /// Primary runway used for the mock demo (the flight's assigned runway, else a default).
    private func mockPrimaryRunway() -> String { assignedRunway.isEmpty ? MockAirportSurface.defaultRunwayIdent : assignedRunway }

    private func emit(_ tx: ATCTransmission) { emitATC?(tx) }

    private func setCrossing(_ state: RunwayCrossingState) {
        if crossingState != state { crossingState = state }
    }

    private func headingTowardCrossing(ac: TaxiAircraft, crossing c: RouteCrossing) -> Bool {
        let bearing = Geo.bearing(from: ac.coordinate.clLocation, to: c.point.clLocation)
        return Geo.headingDifference(ac.headingDegrees, bearing) < 70
    }

    private func emitOnceUnauthorized(stop: Bool, c: RouteCrossing) {
        // Debounced by the caller; emit only on entering the unauthorized state.
        guard crossingState != .unauthorizedCrossingDetected else { return }
        emit(stop ? phraseology.stopWarning(cs: cs(), runwayIdent: c.runwayIdent)
                  : phraseology.holdPositionWarning(cs: cs(), runwayIdent: c.runwayIdent))
    }

    private func logUnauthorized(c: RouteCrossing, along: Double, ac: TaxiAircraft, dHold: Double) {
        diagnostics?.log(.atc, String(format: "Unauthorized runway entry watch RWY %@: state=%@ auth=%@ gs=%.0f hdg=%.0f dHold=%.0fm conf=%@",
                                      c.runwayIdent, crossingState.title,
                                      (authorizedCrossingIndex == c.index) ? "yes" : "no",
                                      ac.groundSpeedKnots, ac.headingDegrees, dHold, c.confidence.title))
    }

    private func updateInstruction() {
        guard route != nil else { nextInstruction = ""; return }
        if offRoute { nextInstruction = "Off assigned taxi route"; return }
        if awaitingCrossingReadback, let c = activeCrossing {
            nextInstruction = "Read back: cross runway \(c.runwayIdent)"; return
        }
        switch crossingState {
        case .holdShortInstructionIssued, .holdingShort, .approachingHoldingPosition, .lowConfidenceCrossingData:
            if let c = activeCrossing { nextInstruction = "Hold short of runway \(c.runwayIdent)"; return }
        case .crossingAuthorized, .crossingInProgress, .runwayCenterlineCrossed:
            if let c = activeCrossing { nextInstruction = "Crossing runway \(c.runwayIdent)"; return }
        case .unauthorizedCrossingDetected:
            if let c = activeCrossing { nextInstruction = "Hold short of runway \(c.runwayIdent)"; return }
        default:
            break
        }
        if reachedDestination {
            nextInstruction = kind == .departure
                ? "Hold short runway \(assignedRunway) — contact Tower when ready"
                : "Arriving at \(route?.destinationLabel ?? "gate")"
            return
        }
        if kind == .departure {
            nextInstruction = "Taxi to runway \(assignedRunway)"
        } else {
            nextInstruction = "Taxi to \(route?.destinationLabel ?? "gate")"
        }
    }

    // MARK: - Actions surfaced on the taxi map

    var crossingActions: [TaxiMapAction] {
        guard taxiMapVisible, activeCrossing != nil else { return [] }
        if awaitingCrossingReadback { return [.holdPosition, .requestAlternateRoute] }
        switch crossingState {
        case .holdingShort, .lowConfidenceCrossingData, .holdShortInstructionIssued, .approachingHoldingPosition:
            return [.requestCrossing, .holdPosition, .requestAlternateRoute]
        default:
            return []
        }
    }

    var offRouteActions: [TaxiMapAction] {
        offRoute ? [.recalculate, .continueOriginalRoute, .requestNewTaxi] : []
    }

    /// The pilot's entered gate for the active taxi — the departure gate on the way out,
    /// the arrival gate on the way in — used to label the taxi map's gate marker. Empty
    /// when none was set.
    var activeGate: String { gate }

    // MARK: - Test hooks
    //
    // Used by @testable unit tests to drive the mock taxi / runway-crossing workflow
    // deterministically (the production mock drive is an async ticker that does not
    // advance within a synchronous test).

    /// Begin a mock taxi and reveal the map without starting the async ticker.
    func beginMockTaxiForTesting(kind: TaxiKind, reference: CLLocationCoordinate2D, runway: String, gate: String) {
        if kind == .departure {
            beginDeparture(icao: "KTEST", reference: reference, aircraftName: "Boeing 737-800",
                           runway: runway, gate: gate,
                           startCoordinate: MockAirportSurface.gateCoordinate(reference: reference), mock: true)
        } else {
            beginArrival(icao: "KTEST", reference: reference, aircraftName: "Boeing 737-800",
                         gate: gate, startCoordinate: MockAirportSurface.runwayExitCoordinate(reference: reference), mock: true)
        }
        taxiReadBack = true
        taxiMapVisible = true
    }

    /// Install a specific prebuilt surface (e.g. a low-confidence one) and reveal the
    /// map, so the crossing workflow can be driven against controlled data.
    func installSurfaceForTesting(_ model: AirportSurfaceModel, kind: TaxiKind, runway: String, gate: String) {
        self.kind = kind
        self.assignedRunway = runway
        self.gate = gate
        self.startGate = gate
        self.mockMode = true
        resetTaxiProgress()
        loadGeneration += 1
        let gen = loadGeneration
        self.icao = model.icao
        self.reference = model.reference.clLocation
        applyLoaded(model, generation: gen)
        taxiReadBack = true
        taxiMapVisible = true
    }

    /// Reproduce the uncached live-departure race: a generic Ground taxi clearance is
    /// issued while the surface is still loading (`taxiClearanceIssued(supersedeWhenRouteReady:)`),
    /// then the asynchronous load resolves with `model`. Mirrors `beginDeparture` +
    /// `taxiClearanceIssued` + the async `applyLoaded` without performing a network fetch,
    /// so the deferred-clearance path can be driven deterministically.
    func simulateDeferredDepartureForTesting(model: AirportSurfaceModel, runway: String, gate: String) {
        kind = .departure
        aircraftClass = .medium
        assignedRunway = runway
        self.gate = gate
        self.startGate = gate
        self.mockMode = false
        resetTaxiProgress()
        pendingStart = MockAirportSurface.gateCoordinate(reference: model.reference.clLocation)
        loadGeneration += 1
        let gen = loadGeneration
        self.icao = model.icao
        self.reference = model.reference.clLocation
        status = .loading
        // Generic clearance issued because the route wasn't ready yet.
        taxiClearanceIssued(supersedeWhenRouteReady: true)
        // Surface finishes loading → route computed → deferred detailed clearance emitted.
        applyLoaded(model, generation: gen)
    }

    /// Advance the mock aircraft one step (mirrors one async tick).
    func mockTickForTesting() { mockTick() }

    /// Feed one synthetic aircraft sample through the tracker/workflow (any mode).
    func feedForTesting(coordinate: CLLocationCoordinate2D, heading: Double, groundSpeed: Double, onGround: Bool = true) {
        displayAircraft = TaxiAircraft(coordinate: GeoCoordinate(coordinate), headingDegrees: heading,
                                       onGround: onGround, groundSpeedKnots: groundSpeed)
        advanceTracking()
    }

    /// The current calculated route (test inspection).
    var routeForTesting: SurfaceTaxiRoute? { route }
    var graphForTesting: SurfaceGraph? { graph }

    // MARK: - Diagnostics snapshot

    func diagnosticsSnapshot() -> AirportSurfaceDiagnostics {
        AirportSurfaceDiagnostics(surface: surface, graph: graph, route: route,
                                  kind: kind, status: status, datasetConfidence: datasetConfidence,
                                  routeConfidence: routeConfidence, crossingState: crossingState,
                                  activeCrossing: activeCrossing, progress: progress,
                                  awaitingCrossingReadback: awaitingCrossingReadback,
                                  authorizedCrossingIndex: authorizedCrossingIndex,
                                  snappedSegment: snappedSegmentDescription, lastError: lastError)
    }

    /// Exposed for diagnostics: the graph's snapped segment under the aircraft.
    var snappedSegmentDescription: String {
        guard let graph, let ac = displayAircraft else { return "—" }
        guard let nearest = graph.nearestNode(to: ac.coordinate.clLocation) else { return "—" }
        let name = nearest.node.name ?? nearest.node.runwayRef ?? nearest.node.kind.rawValue
        return "\(name) (\(Int(nearest.distanceMeters)) m)"
    }
}
