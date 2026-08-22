package com.h3consultingpartners.ifatccompanion.core.connect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds the device running Infinite Flight on the local network. Best-effort — if it
 * finds nothing, the user enters the IP manually.
 *
 * Ported from `IFATCCompanion/Connect/IFDiscoveryService.swift`. The iOS file exists
 * mostly to work around Apple: `NWListener` supports UDP *multicast* but not UDP
 * *broadcast*, so it drops to a BSD socket, and receiving IF's broadcast on iOS 14+
 * additionally needs the `com.apple.developer.networking.multicast` entitlement that
 * the stock build does not have. Android has neither restriction on the socket itself,
 * so [java.net.DatagramSocket] with `broadcast = true` bound to 15000 does the job —
 * **but** the app layer must hold a `WifiManager.MulticastLock` for the lifetime of a
 * discovery window: without it several OEM Wi-Fi drivers filter inbound broadcast to
 * 255.255.255.255 before it reaches the socket, which looks exactly like Infinite
 * Flight not broadcasting at all. (`ACCESS_WIFI_STATE` + `CHANGE_WIFI_MULTICAST_STATE`.)
 *
 * Infinite Flight does **not** publish a Bonjour/mDNS service in practice, and `:core`
 * cannot touch `android.net.nsd`, so the mDNS path is a seam ([IFBonjourBrowsing]) the
 * app layer may fill; by default it is absent.
 *
 * That is why the workhorse path is the same **active subnet scan** iOS relies on: read
 * this device's own IPv4 address and netmask, then race short-lived TCP connects to the
 * Connect API port (10112) across every host on the local subnet. The host that accepts
 * the connection is the device running Infinite Flight. Whichever path finds it first
 * wins, and the report is delivered exactly once.
 */
class IFDiscoveryService(
    private val scope: CoroutineScope,
    private val bonjour: IFBonjourBrowsing = IFBonjourBrowsing.unavailable,
    /**
     * Held for the lifetime of a discovery window so inbound broadcast actually reaches
     * the socket. Absent by default, which is what `:core` and every test want; the app
     * layer supplies the real `WifiManager.MulticastLock`.
     */
    private val broadcastHold: BroadcastReceiveHold = BroadcastReceiveHold.none,
) : IFDeviceDiscovering {

    /** A device answering on the Connect API port. */
    data class Device(val name: String, val address: String, val port: Int)

    private var job: Job? = null

    @Volatile
    private var listenerSocket: DatagramSocket? = null

    @Volatile
    private var onFound: ((Device) -> Unit)? = null

    private val didReport = AtomicBoolean(false)

    override fun start(onFound: (Device) -> Unit) {
        stop() // idempotent teardown, exactly as the Swift's `start` begins with `stop()`
        this.onFound = onFound
        didReport.set(false)
        broadcastHold.acquire()
        bonjour.start { device -> report(device) }
        job = scope.launch {
            coroutineScope {
                launch { listenForBroadcast() }
                launch { sweepSubnet() }
            }
        }
    }

    override fun stop() {
        onFound = null
        broadcastHold.release()
        bonjour.stop()
        job?.cancel()
        job = null
        // The blocking `receive()` is not cancellable; closing the socket is what
        // unblocks it (the iOS read source's cancel handler closes its fd for the same
        // reason).
        runCatching { listenerSocket?.close() }
        listenerSocket = null
    }

    /**
     * Deliver a discovered device exactly once. The callback is dispatched onto the
     * owning [scope] rather than the socket thread, mirroring the iOS service's
     * unconditional hop to the main queue — it mutates the connect manager's state.
     */
    private fun report(device: Device) {
        val callback = onFound ?: return
        if (!didReport.compareAndSet(false, true)) return
        scope.launch { callback(device) }
    }

    // region UDP broadcast listener

    private suspend fun listenForBroadcast() = coroutineScope {
        val socket = withContext(Dispatchers.IO) { openListenerSocket() } ?: return@coroutineScope
        listenerSocket = socket
        val ping = launch(Dispatchers.IO) { permissionPingLoop(socket) }
        try {
            withContext(Dispatchers.IO) { receiveLoop(socket) }
        } finally {
            ping.cancel()
            runCatching { socket.close() }
        }
    }

    private fun openListenerSocket(): DatagramSocket? = try {
        // `DatagramSocket(null)` creates an unbound socket so the options can be set
        // before the bind, which is what `SO_REUSEADDR` has to be. There is no portable
        // `SO_REUSEPORT` in Java; `SO_REUSEADDR` alone is enough here because nothing
        // else on the device binds 15000.
        DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(DISCOVERY_PORT))
        }
    } catch (error: IOException) {
        null
    }

    private fun receiveLoop(socket: DatagramSocket) {
        val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
        try {
            while (true) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                if (packet.length <= 0) continue
                decodeBroadcast(buffer.copyOf(packet.length))?.let { report(it) }
            }
        } catch (closed: IOException) {
            // stop() closed the socket, or the interface went away. Either way there is
            // nothing left to listen to.
        }
    }

    /**
     * Send a harmless broadcast datagram, immediately and then every two seconds.
     *
     * On iOS this exists purely to trigger the Local Network permission prompt (a
     * receive-only socket never does) and to keep reception flowing once it is granted.
     * Android has no such permission, so this is kept only for wire parity with the
     * shipping app — Infinite Flight ignores the datagram either way.
     */
    private suspend fun permissionPingLoop(socket: DatagramSocket) {
        val payload = PING_PAYLOAD.toByteArray(Charsets.UTF_8)
        val destination = runCatching {
            InetSocketAddress(InetAddress.getByName(BROADCAST_ADDRESS), DISCOVERY_PORT)
        }.getOrNull() ?: return
        sendPing(socket, payload, destination)
        while (currentCoroutineContext().isActive) {
            delay(PING_INTERVAL_MILLIS)
            sendPing(socket, payload, destination)
        }
    }

    private fun sendPing(socket: DatagramSocket, payload: ByteArray, to: InetSocketAddress) {
        runCatching { socket.send(DatagramPacket(payload, payload.size, to)) }
    }

    /**
     * IF's broadcast payload. v2 uses `Addresses`/`Port`; v1 also sent a single
     * `Address`. Only an address and the TCP port are needed. The JSON keys are
     * PascalCase exactly as Infinite Flight writes them.
     */
    @Serializable
    private data class Broadcast(
        @SerialName("State") val state: String? = null,
        @SerialName("Port") val port: Int? = null,
        @SerialName("DeviceName") val deviceName: String? = null,
        @SerialName("Addresses") val addresses: List<String>? = null,
        @SerialName("Address") val address: String? = null,
    )

    private fun decodeBroadcast(bytes: ByteArray): Device? {
        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        val broadcast = runCatching {
            broadcastJson.decodeFromString(Broadcast.serializer(), text)
        }.getOrNull() ?: return null
        // Prefer IPv4: the Connect client dials a dotted quad, and IF lists its IPv6
        // addresses in the same array.
        val address = broadcast.addresses?.firstOrNull { !it.contains(":") }
            ?: broadcast.addresses?.firstOrNull()
            ?: broadcast.address
        if (address.isNullOrEmpty()) return null
        return Device(
            name = broadcast.deviceName ?: DEFAULT_DEVICE_NAME,
            address = address,
            port = broadcast.port ?: IFConnectClient.DEFAULT_PORT,
        )
    }

    // endregion

    // region Active subnet scan

    /**
     * Sweep the local subnet for a host accepting TCP on the Connect port, at most
     * [SCAN_CONCURRENCY] probes in flight. A full sweep with no hit and nothing still in
     * flight waits [RESCAN_DELAY_MILLIS] and sweeps again — on first run the most common
     * reason for a clean miss is that Infinite Flight had not finished loading into a
     * flight — until a hit or the owning timeout in [IFConnectManager] stops us.
     */
    private suspend fun sweepSubnet() {
        val targets = subnetScanTargets()
        if (targets.isEmpty()) return
        while (currentCoroutineContext().isActive && !didReport.get()) {
            val gate = Semaphore(SCAN_CONCURRENCY)
            coroutineScope {
                for (host in targets) {
                    if (didReport.get()) break
                    gate.acquire()
                    if (didReport.get()) {
                        gate.release()
                        break
                    }
                    launch(Dispatchers.IO) {
                        try {
                            if (probe(host)) {
                                report(Device(DEFAULT_DEVICE_NAME, host, SCAN_PORT))
                            }
                        } finally {
                            gate.release()
                        }
                    }
                }
            }
            if (didReport.get()) return
            delay(RESCAN_DELAY_MILLIS)
        }
    }

    /**
     * One probe. A real TCP connect, not `InetAddress.isReachable` — that uses ICMP or
     * port 7 and says nothing about whether the Connect API is listening.
     */
    private fun probe(host: String): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, SCAN_PORT), SCAN_PER_HOST_TIMEOUT_MILLIS)
            true
        }
    } catch (error: IOException) {
        false
    } catch (error: SecurityException) {
        false
    }

    /**
     * Every usable host on this device's primary IPv4 subnet, excluding our own address.
     * Capped to a /24's worth of hosts so a large netmask can't turn this into a
     * multi-thousand-host sweep.
     *
     * All arithmetic is in [Long] because an IPv4 address does not fit an unsigned
     * [Int] in Kotlin — 192.168.x.y is negative as a signed Int, which breaks the
     * comparisons.
     */
    internal fun subnetScanTargets(): List<String> {
        val (addr, mask) = primaryIPv4Interface() ?: return emptyList()
        val network = addr and mask
        val broadcast = network or (mask.inv() and IPV4_MASK)
        // A /31 or /32 has no usable host range to sweep.
        if (broadcast <= network + 1) return emptyList()

        var lo = network + 1
        var hi = broadcast - 1
        if (hi - lo > MAX_SWEEP_HOSTS) {
            // Restrict to the /24 containing this device.
            val net24 = addr and SLASH24_MASK
            lo = net24 + 1
            hi = net24 + 254
        }

        val targets = ArrayList<String>((hi - lo + 1).toInt())
        var h = lo
        while (h <= hi) {
            if (h != addr) targets += ipv4String(h)
            h += 1
        }
        return targets
    }

    /**
     * This device's primary non-loopback IPv4 address and netmask, in host byte order.
     * Prefers the Wi-Fi interface — [WIFI_INTERFACE_NAME] on Android, where iOS asks for
     * `en0` — because the iPad running Infinite Flight is on the same Wi-Fi, never on a
     * cellular or VPN interface.
     */
    private fun primaryIPv4Interface(): Pair<Long, Long>? {
        val interfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        }.getOrNull() ?: return null

        var fallback: Pair<Long, Long>? = null
        for (nic in interfaces) {
            // Java has no separate IFF_RUNNING; `isUp` covers both flags the Swift checks.
            val usable = runCatching { nic.isUp && !nic.isLoopback }.getOrDefault(false)
            if (!usable) continue
            for (interfaceAddress in nic.interfaceAddresses) {
                val address = interfaceAddress.address as? Inet4Address ?: continue
                val prefix = interfaceAddress.networkPrefixLength.toInt()
                if (prefix !in 1..32) continue
                val mask = (-1L shl (32 - prefix)) and IPV4_MASK
                if (mask == 0L) continue
                val value = ipv4Value(address)
                if (nic.name == WIFI_INTERFACE_NAME) return value to mask
                if (fallback == null) fallback = value to mask
            }
        }
        return fallback
    }

    private fun ipv4Value(address: Inet4Address): Long {
        val bytes = address.address
        var value = 0L
        for (byte in bytes) value = (value shl 8) or (byte.toLong() and 0xFF)
        return value
    }

    /** Format a host-order IPv4 integer as a dotted-quad string. */
    internal fun ipv4String(value: Long): String =
        "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}." +
            "${(value shr 8) and 0xFF}.${value and 0xFF}"

    // endregion

    companion object {
        /** UDP port Infinite Flight broadcasts its presence on. */
        const val DISCOVERY_PORT = 15000

        /** TCP port the subnet sweep probes — the Connect API v2 port. */
        const val SCAN_PORT = 10112

        /** How many subnet probes may be in flight at once. */
        const val SCAN_CONCURRENCY = 24

        /** Per-host probe timeout, in milliseconds (2.5 s). */
        const val SCAN_PER_HOST_TIMEOUT_MILLIS = 2_500

        /** Delay before re-sweeping the subnet after a full miss (1.5 s). */
        const val RESCAN_DELAY_MILLIS = 1_500L

        /** Broadcast ping cadence: first immediately, then every 2 s. */
        const val PING_INTERVAL_MILLIS = 2_000L

        /** `recvfrom` buffer size. */
        const val RECEIVE_BUFFER_BYTES = 4096

        /** Harmless datagram body; Infinite Flight ignores it. */
        const val PING_PAYLOAD = "IFATCCompanion"

        /** INADDR_BROADCAST. */
        const val BROADCAST_ADDRESS = "255.255.255.255"

        /** Used when the broadcast omits `DeviceName`, and for a subnet-scan hit. */
        const val DEFAULT_DEVICE_NAME = "Infinite Flight"

        /** Android's Wi-Fi interface, standing in for iOS's `en0`. */
        const val WIFI_INTERFACE_NAME = "wlan0"

        /** Above this many hosts the sweep is restricted to the /24 containing us. */
        const val MAX_SWEEP_HOSTS = 253L

        private const val IPV4_MASK = 0xFFFF_FFFFL
        private const val SLASH24_MASK = 0xFFFF_FF00L

        private val broadcastJson = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Whatever the platform needs held for inbound broadcast to reach a bound socket.
 *
 * On Android that is a `WifiManager.MulticastLock`: without one several OEM Wi-Fi drivers
 * filter inbound broadcast to 255.255.255.255 before the socket sees it, which looks
 * exactly like Infinite Flight not broadcasting at all. The manifest has declared
 * `ACCESS_WIFI_STATE` and `CHANGE_WIFI_MULTICAST_STATE` for this since the port began and
 * nothing acquired one, so of the three discovery paths only the TCP sweep was ever live.
 *
 * A seam because `:core` cannot touch `android.net.wifi`. Calls are paired with the
 * discovery window and must be safe to repeat.
 */
interface BroadcastReceiveHold {
    fun acquire()
    fun release()

    /** No hold needed, or none available. */
    object none : BroadcastReceiveHold {
        override fun acquire() = Unit
        override fun release() = Unit
    }
}

/**
 * The discovery seam the connect manager depends on. Concrete implementation is
 * [IFDiscoveryService]; tests substitute a stub so the manager's reconnect and
 * rediscovery rules can be exercised without touching the network.
 */
interface IFDeviceDiscovering {
    /** Begin searching. [onFound] is called at most once per [start]. */
    fun start(onFound: (IFDiscoveryService.Device) -> Unit)

    /** Idempotent teardown. */
    fun stop()
}

/**
 * The Bonjour/mDNS browse path, which `:core` cannot implement: it needs
 * `android.net.nsd.NsdManager` (`_infiniteflight._tcp`, `PROTOCOL_DNS_SD`) and so
 * belongs in `:app`.
 *
 * Infinite Flight does not actually publish a Bonjour service, so this is a
 * best-effort extra exactly as it is on iOS; [unavailable] is the default and changes
 * no observable behaviour.
 */
interface IFBonjourBrowsing {
    fun start(onFound: (IFDiscoveryService.Device) -> Unit)
    fun stop()

    companion object {
        val unavailable = object : IFBonjourBrowsing {
            override fun start(onFound: (IFDiscoveryService.Device) -> Unit) = Unit
            override fun stop() = Unit
        }
    }
}
