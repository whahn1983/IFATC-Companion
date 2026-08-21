package com.h3consultingpartners.ifatccompanion.core.connect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The byte pipe the Connect client speaks over.
 *
 * iOS builds the client directly on `NWConnection`. Here the socket sits behind an
 * interface so the whole request/response protocol — framing, manifest retry,
 * resynchronisation after a desync — can be exercised against a scripted byte queue
 * in a plain JVM test, with no simulator and no network.
 */
interface IFConnectTransport {
    val isConnected: Boolean

    /** Opens the connection, or throws an [IFConnectError]. */
    suspend fun connect(host: String, port: Int, timeoutMillis: Long)

    suspend fun send(bytes: ByteArray)

    /**
     * Receive the next available chunk of bytes (up to 64 KB). Returns an empty array
     * only when nothing arrived without the peer closing; a closed connection throws
     * [IFConnectError.ConnectionFailed] so the manifest path can report "closed before
     * full manifest", and an expired [timeoutMillis] throws [IFConnectError.Timeout].
     */
    suspend fun receive(timeoutMillis: Long): ByteArray

    fun close()
}

/**
 * The production transport: a plain TCP socket.
 *
 * `SO_TIMEOUT` gives the per-read inactivity timeout the iOS client gets from its
 * per-chunk `withTimeout`, and because it is applied per read the clock effectively
 * resets every time bytes arrive — a large manifest that trickles in over many chunks
 * is never cut off mid-transfer.
 *
 * `TCP_NODELAY` is set because the protocol is a chatty request/response exchange of
 * tiny frames polled at 1 Hz; Nagle's algorithm would add latency for nothing.
 */
class TcpConnectTransport : IFConnectTransport {

    private var socket: Socket? = null
    private val readBuffer = ByteArray(65536)

    override val isConnected: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed && !it.isInputShutdown } == true

    override suspend fun connect(host: String, port: Int, timeoutMillis: Long) {
        close()
        withContext(Dispatchers.IO) {
            val created = Socket()
            try {
                created.tcpNoDelay = true
                created.keepAlive = true
                created.connect(InetSocketAddress(host, port), timeoutMillis.toInt())
            } catch (timeout: SocketTimeoutException) {
                runCatching { created.close() }
                throw IFConnectError.Timeout
            } catch (io: IOException) {
                runCatching { created.close() }
                throw IFConnectError.ConnectionFailed(io.message ?: "Connection failed")
            } catch (illegal: IllegalArgumentException) {
                runCatching { created.close() }
                throw IFConnectError.InvalidHost
            }
            socket = created
        }
    }

    override suspend fun send(bytes: ByteArray) {
        val active = socket ?: throw IFConnectError.NotConnected
        withContext(Dispatchers.IO) {
            try {
                active.getOutputStream().apply {
                    write(bytes)
                    flush()
                }
            } catch (io: IOException) {
                throw IFConnectError.ConnectionFailed(io.message ?: "Send failed")
            }
        }
    }

    override suspend fun receive(timeoutMillis: Long): ByteArray {
        val active = socket ?: throw IFConnectError.NotConnected
        return withContext(Dispatchers.IO) {
            try {
                active.soTimeout = timeoutMillis.toInt()
                val read = active.getInputStream().read(readBuffer)
                when {
                    read > 0 -> readBuffer.copyOf(read)
                    // read == -1 is end of stream: the peer closed.
                    read < 0 -> throw IFConnectError.ConnectionFailed("Connection closed")
                    else -> ByteArray(0)
                }
            } catch (timeout: SocketTimeoutException) {
                throw IFConnectError.Timeout
            } catch (io: IOException) {
                throw IFConnectError.ConnectionFailed(io.message ?: "Receive failed")
            }
        }
    }

    override fun close() {
        socket?.let { runCatching { it.close() } }
        socket = null
    }
}
