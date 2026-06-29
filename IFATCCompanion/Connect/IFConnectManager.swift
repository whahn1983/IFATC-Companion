import Foundation
import Combine

/// Orchestrates the Infinite Flight Connect link: connection lifecycle, manifest
/// discovery, state polling, and command sending. Fully isolated — if Infinite
/// Flight is unavailable, every path degrades gracefully and never crashes.
@MainActor
final class IFConnectManager: ObservableObject {

    @Published private(set) var connectionState: IFConnectConnectionState = .disconnected
    @Published private(set) var manifestEntries: [IFManifestEntry] = []
    @Published private(set) var liveCallsign: String?
    @Published private(set) var liveATC: LiveATCStatus = .none
    @Published private(set) var lastError: String?

    let mappingStore = IFStateMappingStore()

    private let client = IFConnectClient()
    private let manifestService = IFConnectManifestService()
    private lazy var reader = IFConnectStateReader(store: mappingStore)
    private let commandSender = IFConnectCommandSender()
    private let discovery = IFDiscoveryService()

    private weak var diagnostics: DiagnosticsStore?
    private var pollTask: Task<Void, Never>?
    private var discoveryTimeoutTask: Task<Void, Never>?

    /// How long to wait for an Infinite Flight discovery broadcast before giving up
    /// and pointing the user at manual IP entry.
    var discoveryTimeout: TimeInterval = 25

    /// Pushed live aircraft states (AppModel subscribes).
    var onState: ((AircraftState) -> Void)?
    /// Pushed parsed flight plan whenever the live plan changes (AppModel subscribes).
    var onFlightPlan: ((FlightPlan) -> Void)?
    /// Pushed the live callsign read from Infinite Flight whenever it changes, so the
    /// companion can adopt it automatically instead of requiring a manual override.
    var onCallsign: ((String) -> Void)?

    /// Last raw flight-plan string read from Infinite Flight (for diagnostics).
    @Published private(set) var liveFlightPlanRaw: String?

    var pollInterval: TimeInterval = 1.0
    /// How often (in poll ticks) to re-read the flight-plan string. The plan rarely
    /// changes mid-flight, so this is throttled relative to state polling.
    var flightPlanReadEveryTicks = 15

    func configure(diagnostics: DiagnosticsStore) {
        self.diagnostics = diagnostics
    }

    // MARK: - Connection

    func connect(host: String, port: Int) {
        guard !connectionState.isActive else { return }
        connectionState = .connecting
        lastError = nil
        diagnostics?.log(.connect, "Connecting to \(host):\(port)…")

        Task {
            do {
                try await client.connect(host: host, port: port)
                diagnostics?.log(.connect, "TCP connected. Requesting manifest…")
                let entries = try await manifestService.discover(using: client, into: mappingStore)
                manifestEntries = entries
                diagnostics?.discoveredStates = entries
                diagnostics?.log(.manifest, "Manifest discovered: \(entries.count) entries. Resolved \(mappingStore.resolved.count) logical states.")
                if !mappingStore.unresolvedKeys.isEmpty {
                    let names = mappingStore.unresolvedKeys.map { $0.rawValue }.joined(separator: ", ")
                    diagnostics?.log(.manifest, "Unresolved (use manual override if needed): \(names)")
                }
                connectionState = .connected
                liveCallsign = await reader.readCallsign(using: client)
                if let cs = liveCallsign, !cs.isEmpty {
                    diagnostics?.log(.state, "Live callsign: \(cs)")
                    onCallsign?(cs)
                }
                await readFlightPlan()
                startPolling()
            } catch {
                let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                connectionState = .failed(message)
                lastError = message
                diagnostics?.log(.connect, "Connect failed: \(message)")
            }
        }
    }

    func disconnect() {
        pollTask?.cancel()
        pollTask = nil
        Task { await client.disconnect() }
        connectionState = .disconnected
        diagnostics?.log(.connect, "Disconnected.")
    }

    // MARK: - Polling

    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task { @MainActor [weak self] in
            guard let self else { return }
            var tick = 0
            while !Task.isCancelled {
                let connected = await self.client.isConnected
                if !connected {
                    if self.connectionState.isConnected {
                        self.connectionState = .failed("Connection lost")
                        self.diagnostics?.log(.connect, "Connection lost.")
                    }
                    break
                }
                let state = await self.reader.readState(using: self.client)
                self.onState?(state)
                self.liveATC = await self.reader.readATCStatus(using: self.client)
                tick += 1
                if tick % self.flightPlanReadEveryTicks == 0 {
                    await self.readFlightPlan()
                    if let cs = await self.reader.readCallsign(using: self.client),
                       !cs.isEmpty, cs != self.liveCallsign {
                        self.liveCallsign = cs
                        self.onCallsign?(cs)
                    }
                }
                try? await Task.sleep(nanoseconds: UInt64(self.pollInterval * 1_000_000_000))
            }
        }
    }

    /// Force an immediate re-read of the flight plan, bypassing the change guard, so
    /// the pilot can pull in an edit they made mid-flight without waiting for the
    /// next throttled poll. No-op when not connected.
    func refreshFlightPlan() async {
        guard connectionState.isConnected else { return }
        liveFlightPlanRaw = nil
        await readFlightPlan()
    }

    /// Read and parse the flight plan; emit `onFlightPlan` only when it changes.
    private func readFlightPlan() async {
        let payloads = await reader.readFlightPlanPayloads(using: client)
        guard !payloads.isEmpty else { return }

        // Change-detection key spans every payload so an edit to any of them re-reads.
        let key = [payloads.fullInfo, payloads.full, payloads.route, payloads.coordinates]
            .compactMap { $0 }.joined(separator: "\u{1F}")
        guard key != liveFlightPlanRaw else { return }
        liveFlightPlanRaw = key

        // Log the full raw payloads (verbose) so the exact IF format is visible — the
        // shape of these states varies across IF versions, and the parser is built
        // against whatever is observed here.
        logRawFlightPlan(payloads)

        guard let plan = IFFlightPlanParser.parse(fullInfo: payloads.fullInfo,
                                                  full: payloads.full,
                                                  route: payloads.route,
                                                  coordinates: payloads.coordinates) else {
            diagnostics?.log(.state, "Flight plan present but unparseable.")
            return
        }
        let located = plan.waypoints.filter { $0.coordinate != nil }.count
        let withAltitude = plan.waypoints.filter { ($0.altitude ?? 0) > 0 }.count
        diagnostics?.log(.state, "Flight plan from IF: \(plan.departure)→\(plan.destination), "
            + "\(plan.waypoints.count) fixes (\(located) located, \(withAltitude) with alt), "
            + "cruise \(plan.cruiseAltitude > 0 ? "\(plan.cruiseAltitude) ft" : "—"), "
            + "SID \(plan.sid.isEmpty ? "—" : plan.sid), STAR \(plan.star.isEmpty ? "—" : plan.star), "
            + "APP \(plan.approach.isEmpty ? "—" : plan.approach).")
        onFlightPlan?(plan)
    }

    /// Emit the raw flight-plan payloads to diagnostics (truncated) so the actual IF
    /// wire format can be inspected when the parsed result looks wrong.
    private func logRawFlightPlan(_ payloads: IFConnectStateReader.FlightPlanPayloads) {
        func trimmed(_ s: String) -> String {
            let max = 2000
            return s.count > max ? String(s.prefix(max)) + "…[\(s.count) chars]" : s
        }
        if let fullInfo = payloads.fullInfo { diagnostics?.log(.state, "Raw flightplan/full_info: \(trimmed(fullInfo))") }
        if let full = payloads.full { diagnostics?.log(.state, "Raw flightplan: \(trimmed(full))") }
        if let route = payloads.route { diagnostics?.log(.state, "Raw flightplan/route: \(trimmed(route))") }
        if let coords = payloads.coordinates { diagnostics?.log(.state, "Raw flightplan/coordinates: \(trimmed(coords))") }
    }

    // MARK: - Discovery

    func startAutoDiscover(onFound: @escaping (IFDiscoveryService.Device) -> Void) {
        connectionState = .discovering
        diagnostics?.log(.connect, "Searching for Infinite Flight on the local network…")
        discovery.start { [weak self] device in
            guard let self else { return }
            self.discoveryTimeoutTask?.cancel()
            self.discoveryTimeoutTask = nil
            self.diagnostics?.log(.connect, "Discovered \(device.name) at \(device.address):\(device.port)")
            self.discovery.stop()
            // Clear the active `.discovering` state so the connect() call made from
            // `onFound` isn't short-circuited by its `guard !connectionState.isActive`
            // guard — otherwise the search would appear to keep running and never
            // connect until the user manually reconnected.
            self.connectionState = .disconnected
            onFound(device)
        }
        discoveryTimeoutTask?.cancel()
        discoveryTimeoutTask = Task { @MainActor [weak self] in
            guard let self else { return }
            try? await Task.sleep(nanoseconds: UInt64(self.discoveryTimeout * 1_000_000_000))
            guard !Task.isCancelled, case .discovering = self.connectionState else { return }
            self.discovery.stop()
            let message = "No Infinite Flight found on the network. Check that Infinite Flight is running with the Connect API enabled and that both devices are on the same Wi-Fi, or enter the iPad's IP manually in Settings."
            self.connectionState = .failed(message)
            self.lastError = message
            self.diagnostics?.log(.connect, "Auto-discovery timed out after \(Int(self.discoveryTimeout))s.")
        }
    }

    func stopAutoDiscover() {
        discoveryTimeoutTask?.cancel()
        discoveryTimeoutTask = nil
        discovery.stop()
        if case .discovering = connectionState { connectionState = .disconnected }
    }

    // MARK: - Commands

    /// Resolve and send a UNICOM/command by keywords. Returns the resolved entry
    /// and whether the send succeeded.
    func sendCommand(keywords: [String]) async -> (resolved: IFManifestEntry?, sent: Bool) {
        guard let entry = mappingStore.command(matchingAnyOf: keywords) else {
            diagnostics?.log(.command, "No command found for keywords: \(keywords.joined(separator: ", "))")
            return (nil, false)
        }
        let ok = await commandSender.send(commandID: entry.id, using: client)
        diagnostics?.log(.command, "Command \(entry.name) [\(entry.id)] \(ok ? "sent" : "failed").")
        return (entry, ok)
    }

    /// Check whether a command exists for the given keywords (no send).
    func commandAvailable(keywords: [String]) -> IFManifestEntry? {
        mappingStore.command(matchingAnyOf: keywords)
    }
}
