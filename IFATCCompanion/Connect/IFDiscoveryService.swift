import Foundation
import Darwin
import Network

/// Listens for Infinite Flight's UDP discovery broadcast (port 15000) to auto-find
/// the iPad's IP address on the local network. Best-effort — if it finds nothing,
/// the user enters the IP manually.
///
/// Implementation note: Infinite Flight sends its discovery packet to the subnet
/// broadcast address. Apple's Network.framework (`NWListener`) supports UDP
/// *multicast* but **not** UDP *broadcast*, so an `NWListener` binds successfully
/// yet never receives IF's packet. We therefore use a BSD/POSIX socket, which does
/// receive broadcasts. We also emit one small broadcast on start: a receive-only
/// socket never triggers iOS's Local Network permission prompt (that only fires
/// when the app *sends* local traffic), and without that permission inbound local
/// traffic is silently dropped.
///
/// In parallel we run a Bonjour browser (`NWBrowser`) for the
/// `_infiniteflight._tcp` service. Bonjour/mDNS is handled by the system and only
/// needs the Local Network permission plus the `NSBonjourServices` Info.plist
/// declaration — it requires no special multicast entitlement, so it is the safe,
/// reliable discovery path on modern iOS. Whichever path finds the device first
/// wins.
final class IFDiscoveryService {

    struct Device: Equatable {
        let name: String
        let address: String
        let port: Int
    }

    private let discoveryPort: UInt16 = 15000
    private let queue = DispatchQueue(label: "com.h3consultingpartners.ifatccompanion.discovery")
    private var fd: Int32 = -1
    private var readSource: DispatchSourceRead?
    private var pingTimer: DispatchSourceTimer?
    private var browser: NWBrowser?
    private var resolverConnection: NWConnection?
    private var didReport = false
    private var onFound: ((Device) -> Void)?

    /// Broadcast payload IF sends. v2 uses `Addresses`/`Port`; v1 also sent a
    /// single `Address`. We only need an address + the TCP port.
    private struct Broadcast: Decodable {
        let State: String?
        let Port: Int?
        let DeviceName: String?
        let Addresses: [String]?
        let Address: String?
    }

    func start(onFound: @escaping (Device) -> Void) {
        stop()
        self.onFound = onFound
        didReport = false
        queue.async { [weak self] in
            self?.openSocket()
            self?.startBonjourBrowser()
        }
    }

    func stop() {
        onFound = nil
        pingTimer?.cancel()
        pingTimer = nil
        readSource?.cancel()   // cancel handler closes the fd
        readSource = nil
        browser?.cancel()
        browser = nil
        resolverConnection?.cancel()
        resolverConnection = nil
    }

    /// Deliver a discovered device exactly once and tear everything down.
    private func report(_ device: Device) {
        guard !didReport, let callback = onFound else { return }
        didReport = true
        DispatchQueue.main.async { callback(device) }
    }

    // MARK: - Socket setup

    private func openSocket() {
        let s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard s >= 0 else { return }

        // Allow rebinding / coexisting with other discovery listeners on the device.
        var yes: Int32 = 1
        setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(s, SOL_SOCKET, SO_REUSEPORT, &yes, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(s, SOL_SOCKET, SO_BROADCAST, &yes, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_addr.s_addr = in_addr_t(0)               // INADDR_ANY (0.0.0.0)
        addr.sin_port = discoveryPort.bigEndian
        let bound = withUnsafePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                Darwin.bind(s, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0 else {
            close(s)
            return
        }

        fd = s

        let source = DispatchSource.makeReadSource(fileDescriptor: s, queue: queue)
        source.setEventHandler { [weak self] in self?.receive() }
        source.setCancelHandler { [weak self] in
            close(s)
            if self?.fd == s { self?.fd = -1 }
        }
        readSource = source
        source.resume()

        // Trigger the iOS Local Network permission prompt and unblock reception.
        // The prompt is asynchronous: the first ping shows it, but inbound traffic
        // stays blocked until the user grants permission, after which we need to be
        // actively sending again for reception to flow. So we re-ping every couple
        // of seconds for the lifetime of the discovery window rather than once.
        sendPermissionPing(on: s)
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 2, repeating: 2)
        timer.setEventHandler { [weak self] in
            guard let self, self.fd == s else { return }
            self.sendPermissionPing(on: s)
        }
        pingTimer = timer
        timer.resume()
    }

    /// Send a harmless broadcast datagram so iOS shows the Local Network prompt.
    /// Infinite Flight ignores it; we only care about the side effect of sending.
    private func sendPermissionPing(on s: Int32) {
        var dest = sockaddr_in()
        dest.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        dest.sin_family = sa_family_t(AF_INET)
        dest.sin_addr.s_addr = in_addr_t(0xFFFF_FFFF)     // INADDR_BROADCAST (255.255.255.255)
        dest.sin_port = discoveryPort.bigEndian
        let payload = Array("IFATCCompanion".utf8)
        _ = withUnsafePointer(to: &dest) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                payload.withUnsafeBytes { raw in
                    sendto(s, raw.baseAddress, raw.count, 0, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
    }

    // MARK: - Receive

    private func receive() {
        guard fd >= 0 else { return }
        var buffer = [UInt8](repeating: 0, count: 4096)
        let n = recvfrom(fd, &buffer, buffer.count, 0, nil, nil)
        guard n > 0 else { return }

        let data = Data(buffer[0..<n])
        guard let broadcast = try? JSONDecoder().decode(Broadcast.self, from: data) else { return }
        let address = broadcast.Addresses?.first(where: { !$0.contains(":") })   // prefer IPv4
            ?? broadcast.Addresses?.first
            ?? broadcast.Address
        guard let address, !address.isEmpty else { return }

        let device = Device(name: broadcast.DeviceName ?? "Infinite Flight",
                            address: address,
                            port: broadcast.Port ?? 10112)
        report(device)
    }

    // MARK: - Bonjour (mDNS) discovery

    /// Browse for `_infiniteflight._tcp` via Bonjour, then resolve the first
    /// result to a concrete host/port. Requires only Local Network permission and
    /// the `NSBonjourServices` Info.plist entry — no multicast entitlement.
    private func startBonjourBrowser() {
        let params = NWParameters()
        params.includePeerToPeer = true
        let descriptor = NWBrowser.Descriptor.bonjour(type: "_infiniteflight._tcp", domain: nil)
        let browser = NWBrowser(for: descriptor, using: params)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self, !self.didReport else { return }
            for result in results {
                if case let .service(name, _, _, _) = result.endpoint {
                    self.resolve(endpoint: result.endpoint, serviceName: name)
                    break
                }
            }
        }
        browser.stateUpdateHandler = { state in
            if case .failed = state { browser.cancel() }
        }
        self.browser = browser
        browser.start(queue: queue)
    }

    /// Open a short-lived connection to a Bonjour endpoint to learn its resolved
    /// IPv4 host and port, then report it and cancel the connection.
    private func resolve(endpoint: NWEndpoint, serviceName: String) {
        resolverConnection?.cancel()
        let connection = NWConnection(to: endpoint, using: .tcp)
        resolverConnection = connection
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            guard case .ready = state else {
                if case .failed = state { connection.cancel() }
                return
            }
            if case let .hostPort(host, port)? = connection.currentPath?.remoteEndpoint {
                let address = Self.ipv4String(from: host)
                if let address, !address.isEmpty {
                    self.report(Device(name: serviceName.isEmpty ? "Infinite Flight" : serviceName,
                                       address: address,
                                       port: Int(port.rawValue)))
                }
            }
            connection.cancel()
        }
        connection.start(queue: queue)
    }

    /// Extract a usable IPv4 dotted-quad string from an `NWEndpoint.Host`.
    private static func ipv4String(from host: NWEndpoint.Host) -> String? {
        switch host {
        case .ipv4(let addr):
            return addr.debugDescription.split(separator: "%").first.map(String.init)
        case .ipv6(let addr):
            // Strip any "%interface" zone suffix; Network.framework can still dial it.
            return addr.debugDescription.split(separator: "%").first.map(String.init)
        case .name(let name, _):
            return name
        @unknown default:
            return nil
        }
    }
}
