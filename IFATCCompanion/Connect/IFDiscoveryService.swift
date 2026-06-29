import Foundation
import Darwin

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
        queue.async { [weak self] in
            self?.openSocket()
        }
    }

    func stop() {
        onFound = nil
        readSource?.cancel()   // cancel handler closes the fd
        readSource = nil
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
        sendPermissionPing(on: s)
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
        let callback = onFound
        DispatchQueue.main.async { callback?(device) }
    }
}
