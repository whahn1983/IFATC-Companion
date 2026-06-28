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

    /// Pushed live aircraft states (AppModel subscribes).
    var onState: ((AircraftState) -> Void)?

    var pollInterval: TimeInterval = 1.0

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
                }
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
                try? await Task.sleep(nanoseconds: UInt64(self.pollInterval * 1_000_000_000))
            }
        }
    }

    // MARK: - Discovery

    func startAutoDiscover(onFound: @escaping (IFDiscoveryService.Device) -> Void) {
        connectionState = .discovering
        diagnostics?.log(.connect, "Searching for Infinite Flight on the local network…")
        discovery.start { [weak self] device in
            self?.diagnostics?.log(.connect, "Discovered \(device.name) at \(device.address):\(device.port)")
            self?.discovery.stop()
            onFound(device)
        }
    }

    func stopAutoDiscover() {
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
