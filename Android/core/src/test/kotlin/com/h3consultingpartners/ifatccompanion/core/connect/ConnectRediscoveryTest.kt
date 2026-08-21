package com.h3consultingpartners.ifatccompanion.core.connect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stored Host/IP is a starting point, not a fact: the iPad's address moves with
 * the Wi-Fi it joins, so an address auto-discovery wrote on one network points at
 * nothing on the next. These cover the rule that decides when that address is
 * re-searched and replaced — and, just as importantly, when it is left alone.
 *
 * Ported from `IFATCCompanionTests/ConnectRediscoveryTests.swift`. The iOS tests dial
 * real sockets (an empty host, then TEST-NET-1 192.0.2.1) and poll wall-clock time; the
 * Kotlin port injects the transport and the discovery seam instead, so the same rules
 * are exercised on virtual time with no network at all — a JVM unit test must not
 * depend on what the sandbox's routing table does with a reserved address.
 */
class ConnectRediscoveryTest {

    // region Which failures mean "wrong address"

    /**
     * Nothing answered at the address. These are the failures that justify searching
     * the network for Infinite Flight's current address.
     */
    @Test
    fun unreachableFailuresTriggerRediscovery() {
        assertTrue(IFConnectManager.isUnreachable(IFConnectError.Timeout))
        assertTrue(
            IFConnectManager.isUnreachable(IFConnectError.ConnectionFailed("Connection refused")),
        )
        assertTrue(IFConnectManager.isUnreachable(IFConnectError.InvalidHost))
        assertTrue(IFConnectManager.isUnreachable(IFConnectError.NotConnected))
    }

    /**
     * Infinite Flight *did* answer — it just answered badly. The address is right, so
     * searching for another one would only find the same device again; the existing
     * retry-the-handshake path is what fixes these.
     */
    @Test
    fun answeredButFaultyFailuresDoNotTriggerRediscovery() {
        assertFalse(IFConnectManager.isUnreachable(IFConnectError.ManifestUnavailable))
        assertFalse(IFConnectManager.isUnreachable(IFConnectError.DecodingFailed))
        assertFalse(IFConnectManager.isUnreachable(IFConnectError.UnknownState))
        assertFalse(IFConnectManager.isUnreachable(IFConnectError.Cancelled))
        // A foreign error is never this area's business to interpret.
        assertFalse(IFConnectManager.isUnreachable(IOException("not connected to the internet")))
    }

    // endregion

    // region The fallback itself

    /**
     * An address nothing answers at sends the link into a fresh search rather than
     * straight to a failure the pilot has to clear by hand.
     */
    @Test
    fun unreachableHostFallsBackToSearching() = runTest {
        val discovery = SilentDiscovery()
        val manager = manager(backgroundScope, discovery)
        manager.connectMaxAttempts = 1
        // Keep the search itself short — the test only cares that one starts.
        manager.discoveryTimeout = 1.0

        // An empty host fails as `InvalidHost` immediately — the same "nothing there"
        // class as a stale IP, without a six-second socket timeout in the test.
        manager.connect(host = "", port = 10112, rediscoverOnFailure = true)

        val searching = eventually {
            manager.state.value.connectionState == IFConnectConnectionState.Discovering
        }
        assertTrue(searching, "an unreachable address must start a new search, not just fail")
        manager.stopAutoDiscover()
    }

    /**
     * An address that neither connects nor fails — a route to a network this device
     * has left leaves the socket sitting there — must not hold the app at
     * "Connecting…". The deadline gives up on it and starts the search.
     */
    @Test
    fun addressThatNeverAnswersHitsTheDeadline() = runTest {
        val discovery = SilentDiscovery()
        // Well below the socket's own six-second timeout, so the deadline is provably
        // what fires here rather than the connect failing on its own.
        val manager = manager(backgroundScope, discovery)
        manager.rediscoverAfter = 1.0
        manager.discoveryTimeout = 1.0

        // A transport that never answers and never refuses, standing in for iOS's
        // TEST-NET-1 (RFC 5737) address.
        manager.connect(host = "192.0.2.1", port = 10112, rediscoverOnFailure = true)

        val searching = eventually {
            manager.state.value.connectionState == IFConnectConnectionState.Discovering
        }
        assertTrue(searching, "an address that never answers must be abandoned for a search")
        manager.stopAutoDiscover()
    }

    /**
     * A deliberate disconnect retires the attempt with it — the search its deadline
     * would have started must not surface moments after the pilot pulled the link down.
     */
    @Test
    fun disconnectCancelsThePendingDeadline() = runTest {
        val discovery = SilentDiscovery()
        val manager = manager(backgroundScope, discovery)
        manager.rediscoverAfter = 1.0
        manager.discoveryTimeout = 1.0

        manager.connect(host = "192.0.2.1", port = 10112, rediscoverOnFailure = true)
        manager.disconnect()

        val searched = eventually(timeoutMillis = 3_000) {
            manager.state.value.connectionState == IFConnectConnectionState.Discovering
        }
        assertFalse(searched, "a disconnected link must stay down, not start searching")
        manager.stopAutoDiscover()
    }

    /**
     * Without the fallback the same failure is surfaced as-is — a manually entered
     * address is the pilot's own and is never second-guessed or overwritten.
     */
    @Test
    fun unreachableHostWithoutFallbackFails() = runTest {
        val manager = manager(backgroundScope, SilentDiscovery())
        manager.connectMaxAttempts = 1

        manager.connect(host = "", port = 10112)

        val failed = eventually {
            manager.state.value.connectionState is IFConnectConnectionState.Failed
        }
        assertTrue(failed, "with rediscovery off the connect failure must stand")
    }

    // endregion

    private fun manager(scope: CoroutineScope, discovery: IFDeviceDiscovering) =
        IFConnectManager(
            scope = scope,
            client = IFConnectClient(transport = NeverAnsweringTransport()),
            discovery = discovery,
        )

    /**
     * Poll `condition` on virtual time until it holds or the timeout expires, mirroring
     * the iOS helper's 50 ms cadence. Returning at the first hit matters: advancing
     * further would run the discovery timeout the tests deliberately keep short.
     */
    private fun TestScope.eventually(
        timeoutMillis: Long = 5_000,
        condition: () -> Boolean,
    ): Boolean {
        testScheduler.runCurrent()
        if (condition()) return true
        var waited = 0L
        while (waited < timeoutMillis) {
            testScheduler.advanceTimeBy(POLL_INTERVAL_MILLIS)
            testScheduler.runCurrent()
            waited += POLL_INTERVAL_MILLIS
            if (condition()) return true
        }
        return condition()
    }

    /** A transport that neither connects nor refuses — the socket that never answers. */
    private class NeverAnsweringTransport : IFConnectTransport {
        override val isConnected: Boolean get() = false

        override suspend fun connect(host: String, port: Int, timeoutMillis: Long) {
            awaitCancellation()
        }

        override suspend fun send(bytes: ByteArray) {
            awaitCancellation()
        }

        override suspend fun receive(timeoutMillis: Long): ByteArray = awaitCancellation()

        override fun close() = Unit
    }

    /** Discovery that starts and finds nothing, so the search stays running. */
    private class SilentDiscovery : IFDeviceDiscovering {
        var starts = 0
            private set

        override fun start(onFound: (IFDiscoveryService.Device) -> Unit) {
            starts += 1
        }

        override fun stop() = Unit
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 50L
    }
}
