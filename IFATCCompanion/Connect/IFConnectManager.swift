import Foundation
import Combine

/// Orchestrates the Infinite Flight Connect link: connection lifecycle, manifest
/// discovery, state polling, and command sending. Fully isolated — if Infinite
/// Flight is unavailable, every path degrades gracefully and never crashes.
@MainActor
final class IFConnectManager: ObservableObject {

    @Published private(set) var connectionState: IFConnectConnectionState = .disconnected
    @Published private(set) var manifestEntries: [IFManifestEntry] = []
    @Published private(set) var liveATC: LiveATCStatus = .none
    @Published private(set) var lastError: String?

    let mappingStore = IFStateMappingStore()

    private let client = IFConnectClient()
    private let manifestService = IFConnectManifestService()
    private lazy var reader = IFConnectStateReader(store: mappingStore)
    private let discovery = IFDiscoveryService()

    private weak var diagnostics: DiagnosticsStore?
    private var pollTask: Task<Void, Never>?
    private var discoveryTimeoutTask: Task<Void, Never>?
    /// Last angular conventions written to Diagnostics, so only the changes are logged.
    private struct AngleUnitsSnapshot: Equatable {
        let aircraftInDegrees: Bool
        let windInDegrees: Bool
    }
    private var lastLoggedAngleUnits: AngleUnitsSnapshot?

    /// How long to wait for an Infinite Flight discovery broadcast before giving up
    /// and pointing the user at manual IP entry.
    var discoveryTimeout: TimeInterval = 25

    /// Pushed live aircraft states (AppModel subscribes).
    var onState: ((AircraftState) -> Void)?
    /// Pushed parsed flight plan whenever the live plan changes (AppModel subscribes).
    var onFlightPlan: ((FlightPlan) -> Void)?

    /// Last raw flight-plan string read from Infinite Flight (for diagnostics).
    @Published private(set) var liveFlightPlanRaw: String?

    var pollInterval: TimeInterval = 1.0
    /// How often (in poll ticks) to re-read the flight-plan string. The plan rarely
    /// changes mid-flight, so this is throttled relative to state polling.
    var flightPlanReadEveryTicks = 15

    /// How many times `connect()` attempts the TCP-connect + manifest-discovery
    /// handshake before surfacing a failure. Returning from the background often
    /// makes Infinite Flight answer the first manifest request with a partial or
    /// garbled frame (which decodes to "Failed to decode a response"), so a single
    /// attempt would spuriously fail even though a retry a moment later succeeds.
    var connectMaxAttempts = 4
    /// Base delay between connect attempts; backs off linearly per attempt.
    var connectRetryDelay: TimeInterval = 0.6
    /// How long to wait after the TCP socket opens before requesting the manifest.
    /// A short settle (300–500 ms) avoids the partial/garbled first frame Infinite
    /// Flight sometimes serves the instant the connection is ready.
    var manifestSettleDelay: TimeInterval = 0.4

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
            var lastFailure: Error?
            for attempt in 1...max(1, connectMaxAttempts) {
                do {
                    try await performConnect(host: host, port: port, attempt: attempt)
                    return
                } catch is CancellationError {
                    return
                } catch IFConnectError.cancelled {
                    // An intentional disconnect cancelled the socket mid-handshake —
                    // don't retry, or we'd reconnect against the user's wishes.
                    return
                } catch {
                    lastFailure = error
                    // `.invalidHost` won't fix itself on a retry — give up immediately.
                    if case IFConnectError.invalidHost = error { break }
                    let message = errorMessage(error)
                    if attempt < max(1, connectMaxAttempts) {
                        diagnostics?.log(.connect, "Connect attempt \(attempt) failed (\(message)). Retrying…")
                        // Drop the half-open socket so the next attempt starts clean,
                        // then back off briefly to let Infinite Flight settle.
                        await client.disconnect()
                        try? await Task.sleep(nanoseconds: UInt64(connectRetryDelay * Double(attempt) * 1_000_000_000))
                    }
                }
            }
            let message = lastFailure.map(errorMessage) ?? "Connection failed."
            let detail = lastFailure.flatMap(errorDetail)
            connectionState = .failed(message, detail: detail)
            lastError = detail ?? message
            diagnostics?.log(.connect, "Connect failed after \(max(1, connectMaxAttempts)) attempt(s): \(detail ?? message)")
        }
    }

    /// One attempt of the connect + manifest-discovery handshake. Throws on any
    /// failure so the caller can retry; only sets `.connected` and starts polling
    /// once the manifest has been read successfully.
    private func performConnect(host: String, port: Int, attempt: Int) async throws {
        try await client.connect(host: host, port: port)
        diagnostics?.log(.connect, attempt > 1 ? "TCP connected (attempt \(attempt))." : "TCP connected.")
        // Give Infinite Flight a beat after the socket opens before asking for the
        // manifest — requesting the instant it's ready often returns a partial or
        // garbled first frame (especially right after returning from the background).
        try? await Task.sleep(nanoseconds: UInt64(manifestSettleDelay * 1_000_000_000))
        connectionState = .receivingManifest
        diagnostics?.log(.manifest, "Requesting manifest…")
        let onEvent: @Sendable (IFConnectManifestEvent) -> Void = { [weak self] event in
            Task { @MainActor in self?.handleManifestEvent(event) }
        }
        let entries: [IFManifestEntry]
        do {
            entries = try await manifestService.discover(using: client, into: mappingStore, onEvent: onEvent)
        } catch is CancellationError {
            throw CancellationError()
        } catch IFConnectError.cancelled {
            throw IFConnectError.cancelled
        } catch {
            // The specific cause (timeout, decode/UTF-8 failure, closed early, …) is
            // already in Diagnostics via `onEvent`. Collapse it to the single
            // user-facing "Manifest Unavailable" — which the connect loop only lets
            // reach the UI once its reconnect-and-retry attempts are exhausted.
            throw IFConnectError.manifestUnavailable
        }
        manifestEntries = entries
        diagnostics?.discoveredStates = entries
        diagnostics?.log(.manifest, "Manifest discovered: \(entries.count) entries. Resolved \(mappingStore.resolved.count) logical states.")
        if !mappingStore.unresolvedKeys.isEmpty {
            let names = mappingStore.unresolvedKeys.map { $0.rawValue }.joined(separator: ", ")
            diagnostics?.log(.manifest, "Unresolved (use manual override if needed): \(names)")
        }
        logATCRelatedStates(entries)
        logAngleStates()
        // A fresh manifest re-decides the angular conventions, so log them afresh too.
        lastLoggedAngleUnits = nil
        connectionState = .connected
        await readFlightPlan()
        startPolling()
    }

    /// Map a granular manifest event to the Diagnostics log and, while the handshake
    /// is still in progress, to the "Receiving manifest…" status. Replaces the old
    /// opaque "Manifest Unavailable" line with a step-by-step trace so a stuck read
    /// can be diagnosed from the log alone.
    private func handleManifestEvent(_ event: IFConnectManifestEvent) {
        switch event {
        case .requestSent(let attempt):
            diagnostics?.log(.manifest, attempt > 1 ? "Manifest request sent (retry \(attempt))." : "Manifest request sent.")
        case .headerReceived(let id, let payloadLength):
            markReceivingManifest()
            diagnostics?.log(.manifest, "Header bytes received (id \(id)). Expected payload size: \(payloadLength) bytes.")
        case .progress(let received, let expected):
            markReceivingManifest()
            diagnostics?.log(.manifest, "Receiving manifest: \(received)/\(expected) bytes.")
        case .waitingForHeader(let received):
            diagnostics?.log(.manifest, "Partial manifest — waiting for more data (\(received) header byte(s) so far).")
        case .invalidResponseID(let id):
            diagnostics?.log(.manifest, "Invalid response ID: \(id) (expected \(IFConnectClient.manifestCommandID)).")
        case .invalidPayloadLength(let len):
            diagnostics?.log(.manifest, "Invalid payload length: \(len).")
        case .invalidStringLength(let len):
            diagnostics?.log(.manifest, "Invalid string length: \(len).")
        case .utf8DecodeFailed:
            diagnostics?.log(.manifest, "UTF-8 decode failure while reading the manifest.")
        case .connectionClosedEarly:
            diagnostics?.log(.manifest, "Connection closed before the full manifest arrived.")
        case .parsed(let stateCount):
            diagnostics?.log(.manifest, "Manifest parsed successfully: \(stateCount) states.")
        }
    }

    /// Move to `.receivingManifest` only while still handshaking, so a late progress
    /// event (e.g. from an abandoned read) can't downgrade an already-`.connected`
    /// or `.failed` link.
    private func markReceivingManifest() {
        switch connectionState {
        case .connecting, .receivingManifest:
            connectionState = .receivingManifest
        default:
            break
        }
    }

    private func errorMessage(_ error: Error) -> String {
        (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
    }

    /// The fuller error text — short summary plus any recovery instructions —
    /// for UI with room to show it. `nil` when the error has no extra detail
    /// beyond `errorMessage`.
    private func errorDetail(_ error: Error) -> String? {
        guard let suggestion = (error as? LocalizedError)?.recoverySuggestion else { return nil }
        return "\(errorMessage(error)). \(suggestion)"
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
                // Reading a snapshot is a long chain of individual socket round-trips, and
                // cancelling this task doesn't interrupt one that is already part-way
                // through — `disconnect()` tears the link down under it, so the reads that
                // hadn't run yet simply fail. What comes back is a half-read snapshot: the
                // fields read before the tear-down, nil for the rest. Publishing that hands
                // the app a position and an altitude with no on-ground flag, which is
                // exactly the shape that used to read as "airborne". A cancelled poll has
                // nothing true left to say, so drop it. (This fires on every forced
                // reconnect — the one the app performs on returning from the background.)
                if Task.isCancelled { break }
                self.onState?(state)
                await self.logTelemetryHealth(state)
                self.liveATC = await self.reader.readATCStatus(using: self.client)
                tick += 1
                if tick % self.flightPlanReadEveryTicks == 0 {
                    await self.readFlightPlan()
                }
                try? await Task.sleep(nanoseconds: UInt64(self.pollInterval * 1_000_000_000))
            }
        }
    }

    /// Log the two things that decide whether every heading in the app is right, and that
    /// were previously invisible: which angular convention the connection has been read as,
    /// and whether the link has desynchronised. A heading pinned to north on the maps is one
    /// of these two — a radians build read as degrees shows every heading as 0–6° — and
    /// neither left a trace in a diagnostics export before.
    private func logTelemetryHealth(_ state: AircraftState) async {
        let units = AngleUnitsSnapshot(aircraftInDegrees: mappingStore.anglesProvedDegrees,
                                       windInDegrees: mappingStore.windAnglesProvedDegrees)
        if units != lastLoggedAngleUnits {
            lastLoggedAngleUnits = units
            let heading = state.heading.map { String(format: "%.0f°", $0) } ?? "—"
            // The raw readings are the whole argument: 084° magnetic arrives as 1.466 on a
            // build reporting radians, and a wind on 331 sitting beside it is the weather
            // reporting degrees — two conventions on one connection, which is what these two
            // decisions exist to keep apart.
            let raw = mappingStore.lastRawAngles
                .map { "\($0.name) \(String(format: "%.3f", $0.value))" }
                .joined(separator: ", ")
            diagnostics?.log(.state, """
                Angle units — aircraft: \(units.aircraftInDegrees ? "DEGREES" : "RADIANS"), \
                wind: \(units.windInDegrees ? "DEGREES" : "RADIANS"). \
                Heading now \(heading). Raw: \(raw.isEmpty ? "—" : raw).
                """)
        }
        let mismatched = await client.takeMismatchedFrameCount()
        if mismatched > 0 {
            diagnostics?.log(.connect,
                             "Discarded \(mismatched) response frame(s) answering a state we didn't ask for — link resynchronised.")
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
        // The parsed fix list itself, not just its size: when a report says fixes are
        // missing from the route, this is what separates "the parser dropped them" from
        // "the parser has them and something downstream doesn't". A fix with no
        // coordinate is marked, since an unlocated fix draws nothing on the map.
        if !plan.waypoints.isEmpty {
            let names = plan.waypoints
                .map { $0.coordinate == nil ? "\($0.name)(no-pos)" : $0.name }
                .joined(separator: "→")
            diagnostics?.log(.state, "Flight plan fixes: \(names)")
        }
        onFlightPlan?(plan)
    }

    /// Emit the raw flight-plan payloads to diagnostics (truncated) so the actual IF
    /// wire format can be inspected when the parsed result looks wrong.
    private func logRawFlightPlan(_ payloads: IFConnectStateReader.FlightPlanPayloads) {
        // Keep the *tail* as well as the head: the detailed document runs to ~8 KB on a
        // full route, and the STAR/approach groups — the part in question whenever fixes
        // are reported missing — sit at the very end, exactly what a head-only cut drops.
        func trimmed(_ s: String) -> String {
            let max = 4000
            guard s.count > max else { return s }
            let half = max / 2
            return String(s.prefix(half)) + "…[\(s.count) chars, middle elided]…" + String(s.suffix(half))
        }
        if let fullInfo = payloads.fullInfo { diagnostics?.log(.state, "Raw flightplan/full_info: \(trimmed(fullInfo))") }
        if let full = payloads.full { diagnostics?.log(.state, "Raw flightplan: \(trimmed(full))") }
        if let route = payloads.route { diagnostics?.log(.state, "Raw flightplan/route: \(trimmed(route))") }
        if let coords = payloads.coordinates { diagnostics?.log(.state, "Raw flightplan/coordinates: \(trimmed(coords))") }
    }

    /// Log every manifest state whose path looks ATC/COM/multiplayer-related, plus which
    /// logical staffing keys they resolved to. The set of states Infinite Flight exposes
    /// for ATC only appears when connected to a session with a controller and varies by
    /// version, so surfacing the exact paths here is how the tuned-frequency and
    /// staffing signatures are verified and refined against a real session.
    private func logATCRelatedStates(_ entries: [IFManifestEntry]) {
        let needles = ["atc", "controller", "unicom", "comm", "com1", "com2",
                       "frequency", "facilit", "online", "server", "multiplayer"]
        let related = entries.filter { entry in
            let key = entry.matchKey
            return needles.contains { key.contains($0) }
        }
        guard !related.isEmpty else {
            diagnostics?.log(.manifest, "No ATC/COM-related states found in manifest.")
            return
        }
        let list = related.map { "\($0.name) [\($0.type.shortName)]" }.joined(separator: ", ")
        diagnostics?.log(.manifest, "ATC/COM-related states (\(related.count)): \(list)")

        let resolvedATC: [IFStateMappingStore.Logical] =
            [.atcActive, .atcFacilityName, .atcFacilityCount, .isOnline, .serverName,
             .tunedComName, .tunedComFrequency]
        for key in resolvedATC {
            if let entry = mappingStore.entry(for: key) {
                diagnostics?.log(.manifest, "  \(key.rawValue) → \(entry.name)")
            }
        }
    }

    /// Log which manifest state each angle was resolved onto, with its declared type. These
    /// are the readings the aircraft symbol, the departure vector and the wind are all built
    /// from, and a signature landing on the wrong entry — a bool named `…_track`, a command
    /// sharing a word with a state — is otherwise indistinguishable from the sim reporting
    /// something odd.
    private func logAngleStates() {
        let angles: [IFStateMappingStore.Logical] = [.heading, .trueHeading, .track, .windDirectionTrue,
                                                     .bankAngle, .pitch]
        let described = angles.map { key -> String in
            guard let entry = mappingStore.entry(for: key) else { return "\(key.rawValue) → —" }
            return "\(key.rawValue) → \(entry.name) [\(entry.type.shortName)]"
        }
        diagnostics?.log(.manifest, "Angle states: \(described.joined(separator: ", "))")
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
            let message = "No Infinite Flight found on the network."
            let detail = "No Infinite Flight found on the network. Check that Infinite Flight is running with the Connect API enabled and that both devices are on the same Wi-Fi, or enter the iPad's IP manually in Settings."
            self.connectionState = .failed(message, detail: detail)
            self.lastError = detail
            self.diagnostics?.log(.connect, "Auto-discovery timed out after \(Int(self.discoveryTimeout))s.")
        }
    }

    func stopAutoDiscover() {
        discoveryTimeoutTask?.cancel()
        discoveryTimeoutTask = nil
        discovery.stop()
        if case .discovering = connectionState { connectionState = .disconnected }
    }

}
