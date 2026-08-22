package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.connect.IFConnectManager
import com.h3consultingpartners.ifatccompanion.core.connect.IFDiscoveryService
import com.h3consultingpartners.ifatccompanion.core.connect.LiveATCStatus
import com.h3consultingpartners.ifatccompanion.core.mock.MockSimulatorFeed
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Chooses and starts the flight's data source: the scripted demo feed, or the live
 * Infinite Flight link.
 *
 * Ported from the "Source selection" section of `IFATCCompanion/App/AppModel.swift`
 * (`startMock` :1431, `stopMock` :1471, `startLive` :1476, `connectToInfiniteFlight` :1544,
 * `adoptDiscoveredDevice` :1567, `refreshConnection` :1587, `afterConnect` :1605,
 * `toggleMockMode` :1615, `enterLiveMode` :1646, `reconnect` :1652) together with the
 * entitlement lock (`applyEntitlement` :1130) and the launch sequence at :1084-1105.
 *
 * It exists as its own class because on Android the pieces iOS keeps inside one `AppModel`
 * are genuinely separate objects — the session coordinator, the weather engine, the airport
 * surface, the chatter, the speech queue. Something has to know the order they are started
 * and torn down in, and that order is a decision, not an Android detail, so it belongs in
 * `:core` where it can be asserted without a device.
 *
 * Everything platform-shaped is a lambda: persisting a setting, resetting the engines this
 * class does not own, restoring a snapshot, asking for a rating. That keeps the sequencing
 * testable with fakes and keeps `:core` free of Android.
 */
class FlightSourceController(
    private val coordinator: FlightSessionCoordinator,
    private val connect: IFConnectManager,
    private val mock: MockSimulatorFeed,
    private val scope: CoroutineScope,
    private val diagnostics: DiagnosticsSink = DiagnosticsSink.noop,
    private val settingsProvider: () -> AppSettings = { AppSettings() },
    /** Persist the chosen mode, so the next launch comes up the same way. */
    private val persistMockMode: (Boolean) -> Unit = {},
    /** Persist an endpoint auto-discovery found, replacing whatever was stored. */
    private val persistEndpoint: (host: String, port: Int) -> Unit = { _, _ -> },
    /**
     * Everything a fresh session has to reset that the coordinator does not own: the
     * weather engine, ATIS, the airport surface and its routing, the chatter, the speech
     * queue. iOS resets all of it inside `AppModel`; here the split is real.
     */
    private val resetSubsystems: () -> Unit = {},
    /** Fetch weather, TAF, PIREPs, SIGMETs and D-ATIS for the route as it now stands. */
    private val refreshWeather: suspend () -> Unit = {},
    /**
     * Restore a recent in-progress session, returning true when one was restored.
     *
     * What keeps a parked aircraft from being re-derived straight to cruise after a
     * dropped link: the snapshot says where the conversation actually was.
     */
    private val restoreSession: () -> Boolean = { false },
    /**
     * Unbind the saved flight this session was writing to.
     *
     * Called only on the genuinely-fresh-flight path. Without it the new empty session
     * would auto-save straight over the flight that was still bound — the flight itself is
     * untouched and can be loaded again, but its saved slot would have been overwritten.
     */
    private val unbindSavedFlight: () -> Unit = {},
    /** A calm window in which a rating prompt is allowed. Self-limited by the caller. */
    private val reviewBeforeFirstCall: () -> Unit = {},
) {

    private val settings: AppSettings get() = settingsProvider()

    /**
     * False until the cold-launch feed has been started.
     *
     * The rating prompt is allowed at a fresh, connected, pre-first-call session — but not
     * at the one that happens automatically on launch, because asking for a rating in the
     * first second of the app opening is the thing every store guideline says not to do.
     */
    private var didAutostartInitialFeed = false

    private var weatherRefreshJob: Job? = null

    // region Launch

    /**
     * Bring the app up on whichever source the pilot is entitled to, exactly once.
     *
     * The mode is settled *before* a feed is started, so a lapsed subscriber never resumes
     * in Live Connected Mode for the moment it takes the entitlement check to answer. The
     * asynchronous refresh re-checks and [applyEntitlement] promotes or re-locks from there.
     */
    fun startAtLaunch(hasLiveAccess: Boolean) {
        if (!hasLiveAccess && !settings.mockMode) persistMockMode(true)

        // After the mode is settled, not before. iOS syncs the plan a few lines earlier
        // (AppModel.swift:1081, ahead of the pin at :1086), which on the launch where the
        // pin actually fires composes the plan for the mode the app is leaving — no demo
        // route, no demo gates, no waypoints. Composing it here is the same call in the
        // order its own rules assume.
        syncFlightPlan()

        diagnostics.log(
            DiagnosticCategory.SESSION,
            message = "IFATC Companion ready. Mock mode: ${settings.mockMode}.",
        )

        if (settings.mockMode) startMock() else startLive()

        // From here on a fresh session start is pilot-initiated — a reconnect, a mode
        // switch, the next flight — rather than the cold-launch auto-start.
        didAutostartInitialFeed = true
    }

    // endregion

    // region Mode

    /**
     * React to a change in Live-access entitlement, switching the active mode to match.
     *
     * Driven by the value handed in rather than by re-reading the repository, which is what
     * iOS's comment at AppModel.swift:1120-1129 is about: routing the "access gained" case
     * through [toggleMockMode] would let that function's own guard read a stale value and
     * bounce a just-confirmed subscriber straight back into Mock Mode.
     */
    fun applyEntitlement(hasLiveAccess: Boolean) {
        if (!hasLiveAccess && !settings.mockMode) {
            diagnostics.log(
                DiagnosticCategory.SESSION,
                message = "Live subscription not active — locking to Mock Mode.",
            )
            persistMockMode(true)
            startMock()
        } else if (hasLiveAccess && settings.mockMode) {
            diagnostics.log(
                DiagnosticCategory.SESSION,
                message = "Live subscription active — switching to Live Connected Mode.",
            )
            persistMockMode(false)
            // The same exit the Mock Mode toggle takes, so a subscription confirmed at
            // launch leaves the demo's plan behind exactly as switching by hand does.
            enterLiveMode()
        }
    }

    /**
     * The Mock Mode switch. Live Connected Mode requires an active subscription; without
     * one the app stays pinned to Mock Mode whatever was asked for.
     */
    fun toggleMockMode(on: Boolean, hasLiveAccess: Boolean) {
        if (!on && !hasLiveAccess) {
            persistMockMode(true)
            if (!mock.running.value) startMock()
            return
        }
        persistMockMode(on)
        if (on) {
            // Rebuild the plan from the mock route so its realistic default gates apply and
            // both airports are pre-cached for a realistic taxi demo.
            syncFlightPlan()
            startMock()
        } else {
            enterLiveMode()
        }
    }

    /**
     * Bring the app up in Live Connected Mode, coming out of the demo. The mode setting
     * must already be false.
     *
     * The demo's stand-ins have to be dropped on the way out, above all its flight plan.
     * The identity is the one that bites: the controller builds every call from the plan's
     * airline/flight-number pair and falls back to the raw callsign only when that pair is
     * empty, so a United 598 left in place keeps the live flight flying as United 598
     * however the Callsign field reads. Infinite Flight's own plan merges over the rebuilt
     * one on the first read after the link comes up.
     */
    fun enterLiveMode() {
        syncFlightPlan()
        startLive()
    }

    /** The Settings "Reconnect" control, and what a mode-agnostic restart means. */
    fun reconnect() {
        if (settings.mockMode) {
            startMock()
        } else {
            connect.disconnect()
            startLive()
        }
    }

    // endregion

    // region Mock

    fun startMock() {
        connect.disconnect()
        resetSubsystems()
        coordinator.resetForNewFlight()
        coordinator.applyLiveATC(
            if (coordinator.state.value.simulateStaffedATC) coordinator.mockStaffedStatus() else LiveATCStatus.none,
        )
        mock.start()
        diagnostics.log(DiagnosticCategory.SESSION, message = "Mock simulator feed started.")
        armWeatherRefresh(immediately = true)
        // Fresh session, connected and idle before the first ATC call — one of the two calm
        // windows in which a rating prompt is allowed. Skipped on the cold-launch
        // auto-start; self-limited by the review manager itself.
        if (didAutostartInitialFeed) reviewBeforeFirstCall()
    }

    fun stopMock() {
        mock.stop()
        weatherRefreshJob?.cancel()
        weatherRefreshJob = null
    }

    // endregion

    // region Live

    fun startLive() {
        stopMock()
        resetSubsystems()

        // Resume a recent in-progress session if one was saved (reconnect / relaunch);
        // otherwise start the conversation fresh.
        if (!restoreSession()) {
            // Nothing to resume, so this is a brand-new conversation — not the saved flight
            // that may still be bound. Unbind before anything is written.
            unbindSavedFlight()
            coordinator.resetForNewFlight()
            if (didAutostartInitialFeed) reviewBeforeFirstCall()
        }
        connectToInfiniteFlight()
    }

    /**
     * Bring the Infinite Flight link up: auto-discover when enabled and no host is set,
     * otherwise connect to the configured host.
     *
     * A stored address is a starting point, not a fact. With auto-discover on, an address
     * nothing answers at is re-searched and overwritten with whatever the network actually
     * has — the tablet's IP changes with the Wi-Fi it joins, and the address discovery
     * wrote last time then points at nothing. Auto-discover off means the address is the
     * pilot's own and is left exactly as entered.
     */
    fun connectToInfiniteFlight() {
        val current = settings
        if (current.host.isEmpty()) {
            if (!current.autoDiscover) {
                diagnostics.log(
                    DiagnosticCategory.CONNECTION,
                    message = "No host set and auto-discover off — staying idle. Enter an IP in Settings.",
                )
                return
            }
            connect.startAutoDiscover { device ->
                adoptDiscoveredDevice(device)
                connect.connect(host = device.address, port = device.port)
                afterConnect()
            }
        } else if (current.autoDiscover) {
            connect.connect(
                host = current.host,
                port = current.port,
                rediscoverOnFailure = true,
                onRediscovered = ::adoptDiscoveredDevice,
            )
            afterConnect()
        } else {
            connect.connect(host = current.host, port = current.port)
            afterConnect()
        }
    }

    /**
     * Tear the link down and bring it straight back up, leaving the conversation exactly
     * as it is.
     *
     * Every flight swap needs this. The Connect link is bound to the flight that was live
     * when it opened: after switching flights in the sim it keeps serving the old
     * aircraft's position and the old flight plan. Unlike [reconnect] this does *not* go
     * through [startLive] — the session has just been set up deliberately (cleared, or
     * restored from a saved flight) and must not be re-derived from the auto-resume
     * snapshot on top of that.
     */
    fun refreshConnection() {
        if (settings.mockMode) return
        connect.disconnect()
        diagnostics.log(
            DiagnosticCategory.CONNECTION,
            message = "Reconnecting to Infinite Flight for the new flight.",
        )
        connectToInfiniteFlight()
    }

    /** Persist the endpoint auto-discovery found, replacing whatever was stored. */
    private fun adoptDiscoveredDevice(device: IFDiscoveryService.Device) {
        val previous = settings.host
        persistEndpoint(device.address, device.port)
        if (previous.isNotEmpty() && previous != device.address) {
            diagnostics.log(
                DiagnosticCategory.CONNECTION,
                message = "Infinite Flight address updated from $previous to ${device.address} — the network changed.",
            )
        }
    }

    /**
     * Load weather once the connection is established, then keep it fresh.
     *
     * The initial load waits out the handshake rather than racing it: the plan Infinite
     * Flight publishes is what decides which fields to fetch, and it arrives a beat after
     * the socket does.
     */
    private fun afterConnect() {
        armWeatherRefresh(immediately = false)
    }

    // endregion

    /**
     * Keep the weather picture current for as long as a feed is running.
     *
     * The interval is the weather service's own cache TTL, so each tick revalidates rather
     * than re-downloading, and it is what keeps the PIREP/ride-report pool from freezing at
     * the connect-time snapshot on a long flight. The arrival D-ATIS depends on it too: the
     * fetch only runs once the aircraft is inside arrival range, and without a tick after
     * departure that check never runs again.
     */
    private fun armWeatherRefresh(immediately: Boolean) {
        weatherRefreshJob?.cancel()
        weatherRefreshJob = scope.launch {
            if (immediately) {
                refreshWeather()
            } else {
                delay(CONNECT_WEATHER_DELAY_MILLIS)
                refreshWeather()
            }
            while (isActive) {
                delay(WEATHER_REFRESH_INTERVAL_MILLIS)
                refreshWeather()
            }
        }
    }

    /** Rebuild the active plan from the pilot's saved fields and the demo route. */
    private fun syncFlightPlan() {
        coordinator.applyFlightPlan(
            FlightPlanComposer.plan(settings = settings, mockRoute = mock.route),
        )
    }

    companion object {
        /** Matches iOS's `weatherRefreshInterval` (AppModel.swift:5010) — the service's cache TTL. */
        const val WEATHER_REFRESH_INTERVAL_MILLIS = 300_000L

        /** iOS waits the same 1.5 s after `connect` before its first weather load. */
        const val CONNECT_WEATHER_DELAY_MILLIS = 1_500L
    }
}
