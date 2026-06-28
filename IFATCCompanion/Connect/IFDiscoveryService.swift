import Foundation
import Network

/// Listens for Infinite Flight's UDP discovery broadcast (port 15000) to auto-find
/// the iPad's IP address on the local network. Best-effort — if it finds nothing,
/// the user enters the IP manually. Requires local-network permission.
final class IFDiscoveryService {

    struct Device: Equatable {
        let name: String
        let address: String
        let port: Int
    }

    private var listener: NWListener?
    private let queue = DispatchQueue(label: "com.h3consultingpartners.ifatccompanion.discovery")
    private var onFound: ((Device) -> Void)?

    /// Broadcast payload IF sends. We only need the address + port.
    private struct Broadcast: Decodable {
        let State: String?
        let Port: Int?
        let DeviceName: String?
        let Addresses: [String]?
    }

    func start(onFound: @escaping (Device) -> Void) {
        stop()
        self.onFound = onFound
        do {
            let params = NWParameters.udp
            params.allowLocalEndpointReuse = true
            guard let port = NWEndpoint.Port(rawValue: 15000) else { return }
            let listener = try NWListener(using: params, on: port)
            self.listener = listener
            listener.newConnectionHandler = { [weak self] connection in
                self?.handle(connection)
            }
            listener.start(queue: queue)
        } catch {
            // Discovery unavailable; caller falls back to manual entry.
            self.listener = nil
        }
    }

    func stop() {
        listener?.cancel()
        listener = nil
        onFound = nil
    }

    private func handle(_ connection: NWConnection) {
        connection.start(queue: queue)
        connection.receiveMessage { [weak self] data, _, _, _ in
            defer { connection.cancel() }
            guard let self, let data, !data.isEmpty else { return }
            guard let broadcast = try? JSONDecoder().decode(Broadcast.self, from: data) else { return }
            let address = broadcast.Addresses?.first(where: { !$0.contains(":") }) // prefer IPv4
                ?? broadcast.Addresses?.first
            guard let address else { return }
            let device = Device(name: broadcast.DeviceName ?? "Infinite Flight",
                                address: address,
                                port: broadcast.Port ?? 10112)
            DispatchQueue.main.async { self.onFound?(device) }
        }
    }
}
