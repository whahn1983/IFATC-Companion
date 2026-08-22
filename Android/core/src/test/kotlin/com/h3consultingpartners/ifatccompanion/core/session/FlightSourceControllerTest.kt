package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.connect.IFConnectClient
import com.h3consultingpartners.ifatccompanion.core.connect.IFConnectConnectionState
import com.h3consultingpartners.ifatccompanion.core.connect.IFConnectManager
import com.h3consultingpartners.ifatccompanion.core.connect.IFConnectTransport
import com.h3consultingpartners.ifatccompanion.core.connect.IFDeviceDiscovering
import com.h3consultingpartners.ifatccompanion.core.connect.IFDiscoveryService
import com.h3consultingpartners.ifatccompanion.core.mock.MockSimulatorFeed
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which data source the flight runs on.
 *
 * Before this existed the app had none in either mode: the demo feed's telemetry went to
 * nobody and the Infinite Flight link was never opened, so a fresh launch was a static
 * shell. These pin the launch sequence, the entitlement lock, and the endpoint decision —
 * the three places where getting the order wrong is silent rather than loud.
 */
class FlightSourceControllerTest {

    // region Launch

    @Test
    fun launchWithoutASubscriptionPinsMockModeAndStartsTheDemo() = runTest {
        val rig = Rig(this, AppSettings(mockMode = false, voiceEnabled = false))

        rig.source.startAtLaunch(hasLiveAccess = false)

        assertTrue(rig.settings.mockMode, "an unentitled launch must never come up in Live mode")
        assertTrue(rig.mock.running.value, "the demo is the free tier — it has to be running")
        assertEquals(
            IFConnectConnectionState.Disconnected,
            rig.connect.state.value.connectionState,
            "Mock Mode must not open a link",
        )
    }

    /** The demo is only a demo if it has something to fly. */
    @Test
    fun theDemoLaunchesAtTheGateWithTheDemoPlan() = runTest {
        val rig = Rig(this, AppSettings(mockMode = true, voiceEnabled = false))

        rig.source.startAtLaunch(hasLiveAccess = false)

        val plan = rig.coordinator.state.value.flightPlan
        assertEquals(rig.mock.route.departure, plan.departure)
        assertEquals(rig.mock.route.destination, plan.destination)
        assertEquals(FlightPlanComposer.MOCK_AIRLINE, plan.airline)
        assertTrue(plan.waypoints.isNotEmpty(), "the demo route's fixes are what the weather map draws")
        assertEquals(ATCState.CONNECTED_IDLE, rig.coordinator.state.value.atcState)
    }

    @Test
    fun aSubscribedLaunchInLiveModeOpensTheLink() = runTest {
        val rig = Rig(
            this,
            AppSettings(mockMode = false, voiceEnabled = false, host = "192.0.2.1", autoDiscover = false),
        )

        rig.source.startAtLaunch(hasLiveAccess = true)
        testScheduler.runCurrent()

        assertFalse(rig.mock.running.value, "the demo feed must not run alongside a live link")
        assertEquals(IFConnectConnectionState.Connecting, rig.connect.state.value.connectionState)
    }

    // endregion

    // region Entitlement

    /**
     * A subscription that lapses, is refunded or is revoked has to take Live Connected Mode
     * with it — including mid-flight. Nothing observed the billing state at all, so a
     * cancelled subscription kept serving the paid feature until the next reinstall.
     */
    @Test
    fun losingAccessLocksBackToMockMode() = runTest {
        val rig = Rig(this, AppSettings(mockMode = false, voiceEnabled = false, host = "192.0.2.1"))
        rig.source.startAtLaunch(hasLiveAccess = true)

        rig.source.applyEntitlement(hasLiveAccess = false)

        assertTrue(rig.settings.mockMode)
        assertTrue(rig.mock.running.value)
    }

    @Test
    fun gainingAccessPromotesOutOfTheDemo() = runTest {
        val rig = Rig(this, AppSettings(mockMode = true, voiceEnabled = false, host = "192.0.2.1"))
        rig.source.startAtLaunch(hasLiveAccess = false)

        rig.source.applyEntitlement(hasLiveAccess = true)
        testScheduler.runCurrent()

        assertFalse(rig.settings.mockMode)
        assertFalse(rig.mock.running.value)
        assertEquals(IFConnectConnectionState.Connecting, rig.connect.state.value.connectionState)
    }

    /**
     * The demo's identity is the one that has to be dropped on the way out: the controller
     * builds every call from the airline/flight-number pair, so a United 598 left behind
     * flies the live flight under it however the Callsign field reads.
     */
    @Test
    fun leavingTheDemoDropsItsIdentityAndRoute() = runTest {
        val rig = Rig(this, AppSettings(mockMode = true, voiceEnabled = false, host = "192.0.2.1"))
        rig.source.startAtLaunch(hasLiveAccess = false)
        assertEquals(FlightPlanComposer.MOCK_AIRLINE, rig.coordinator.state.value.flightPlan.airline)

        rig.source.applyEntitlement(hasLiveAccess = true)

        val plan = rig.coordinator.state.value.flightPlan
        assertEquals("", plan.airline)
        assertEquals("", plan.departure)
        assertTrue(plan.waypoints.isEmpty())
    }

    @Test
    fun theToggleCannotLeaveTheDemoWithoutASubscription() = runTest {
        val rig = Rig(this, AppSettings(mockMode = true, voiceEnabled = false, host = "192.0.2.1"))
        rig.source.startAtLaunch(hasLiveAccess = false)

        rig.source.toggleMockMode(on = false, hasLiveAccess = false)

        assertTrue(rig.settings.mockMode, "leaving Mock Mode is the paid step, and it must not happen")
        assertEquals(IFConnectConnectionState.Disconnected, rig.connect.state.value.connectionState)
    }

    // endregion

    // region Endpoint

    @Test
    fun noHostAndNoAutoDiscoverStaysIdle() = runTest {
        val rig = Rig(this, AppSettings(mockMode = false, voiceEnabled = false, host = "", autoDiscover = false))

        rig.source.startAtLaunch(hasLiveAccess = true)
        testScheduler.runCurrent()

        assertEquals(
            IFConnectConnectionState.Disconnected,
            rig.connect.state.value.connectionState,
            "with no address and no search there is nothing to connect to",
        )
        assertEquals(0, rig.discovery.starts, "auto-discover is off; it must not be started anyway")
    }

    @Test
    fun noHostWithAutoDiscoverSearches() = runTest {
        val rig = Rig(this, AppSettings(mockMode = false, voiceEnabled = false, host = "", autoDiscover = true))

        rig.source.startAtLaunch(hasLiveAccess = true)
        testScheduler.runCurrent()

        assertEquals(1, rig.discovery.starts)
        rig.connect.stopAutoDiscover()
    }

    /**
     * A stored address is a starting point, not a fact — the tablet's IP moves with the
     * Wi-Fi it joins. Whatever the search actually finds replaces it.
     */
    @Test
    fun aDiscoveredEndpointReplacesTheStoredOne() = runTest {
        val found = IFDiscoveryService.Device(name = "iPad", address = "192.168.1.44", port = 10112)
        val rig = Rig(
            this,
            AppSettings(mockMode = false, voiceEnabled = false, host = "", autoDiscover = true),
            discovery = ImmediateDiscovery(found),
        )

        rig.source.startAtLaunch(hasLiveAccess = true)
        testScheduler.runCurrent()

        assertEquals("192.168.1.44", rig.settings.host)
        assertEquals(10112, rig.settings.port)
        rig.connect.stopAutoDiscover()
    }

    // endregion

    // region Resume

    @Test
    fun liveModeResumesASavedSessionRatherThanStartingOver() = runTest {
        val rig = Rig(this, AppSettings(mockMode = false, voiceEnabled = false, host = "192.0.2.1"))
        rig.resumable = true

        rig.source.startAtLaunch(hasLiveAccess = true)

        assertTrue(rig.restoreAttempts > 0, "a dropped link mid-flight must not re-derive the flight from scratch")
        assertEquals(0, rig.unbindCalls, "a resumed flight is still bound to its saved slot")
    }

    /**
     * A brand-new conversation must let go of the saved flight it is not continuing, or the
     * empty session auto-saves straight over it.
     */
    @Test
    fun aFreshLiveSessionUnbindsTheSavedFlight() = runTest {
        val rig = Rig(this, AppSettings(mockMode = false, voiceEnabled = false, host = "192.0.2.1"))
        rig.resumable = false

        rig.source.startAtLaunch(hasLiveAccess = true)

        assertEquals(1, rig.unbindCalls)
    }

    /** The demo's feed always restarts at the gate, so a restored cruise transcript would describe a flight it is not flying. */
    @Test
    fun theDemoNeverResumes() = runTest {
        val rig = Rig(this, AppSettings(mockMode = true, voiceEnabled = false))
        rig.resumable = true

        rig.source.startAtLaunch(hasLiveAccess = false)

        assertEquals(0, rig.restoreAttempts)
    }

    // endregion

    // region Rig

    private class Rig(
        test: TestScope,
        initial: AppSettings,
        val discovery: SilentDiscovery = SilentDiscovery(),
    ) {
        /**
         * Everything here launches something that never finishes on its own — the socket
         * that never answers, the demo's 1 Hz tick, the weather refresh loop — so all of it
         * belongs on the background scope. On the test's own scope `runTest` waits for
         * those children and the test hangs rather than failing.
         */
        private val scope = test.backgroundScope

        var settings: AppSettings = initial
            private set

        var resumable = false
        var restoreAttempts = 0
            private set
        var unbindCalls = 0
            private set

        val connect = IFConnectManager(
            scope = scope,
            client = IFConnectClient(transport = NeverAnsweringTransport()),
            discovery = discovery,
        )

        val mock = MockSimulatorFeed(scope = scope, clock = MutableClock(0))

        val coordinator = FlightSessionCoordinator(
            scope = scope,
            clock = MutableClock(0),
            connect = connect,
            settingsProvider = { settings },
        )

        val source = FlightSourceController(
            coordinator = coordinator,
            connect = connect,
            mock = mock,
            scope = scope,
            settingsProvider = { settings },
            persistMockMode = { on -> settings = settings.copy(mockMode = on) },
            persistEndpoint = { host, port -> settings = settings.copy(host = host, port = port) },
            restoreSession = {
                restoreAttempts += 1
                resumable
            },
            unbindSavedFlight = { unbindCalls += 1 },
        )
    }

    /** A transport that neither connects nor refuses — the socket that never answers. */
    private class NeverAnsweringTransport : IFConnectTransport {
        override val isConnected: Boolean get() = false

        override suspend fun connect(host: String, port: Int, timeoutMillis: Long) = awaitCancellation()

        override suspend fun send(bytes: ByteArray) = awaitCancellation()

        override suspend fun receive(timeoutMillis: Long): ByteArray = awaitCancellation()

        override fun close() = Unit
    }

    /** Discovery that starts and finds nothing, so the search stays running. */
    private open class SilentDiscovery : IFDeviceDiscovering {
        var starts = 0
            protected set

        override fun start(onFound: (IFDiscoveryService.Device) -> Unit) {
            starts += 1
        }

        override fun stop() = Unit
    }

    /** Discovery that answers with one device the moment it is asked. */
    private class ImmediateDiscovery(private val device: IFDiscoveryService.Device) : SilentDiscovery() {
        override fun start(onFound: (IFDiscoveryService.Device) -> Unit) {
            starts += 1
            onFound(device)
        }
    }

    // endregion
}
