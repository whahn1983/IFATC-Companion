package com.h3consultingpartners.ifatccompanion

import android.app.Application
import com.h3consultingpartners.ifatccompanion.core.net.AppHttp
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticLevel
import com.h3consultingpartners.ifatccompanion.notification.FlightNotifications
import com.h3consultingpartners.ifatccompanion.service.ActiveFlightService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class IFATCCompanionApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()

        // The User-Agent the app presents to every public service it uses (NOAA, NASA,
        // the D-ATIS mirror, Overpass) carries the app version and a contact URL, because
        // those operators ask clients to identify themselves.
        AppHttp.appVersion = BuildConfig.VERSION_NAME

        graph = AppGraph.create(this)

        // Settings are read synchronously all through the engine, the way UserDefaults is
        // on iOS, so the one-time load from DataStore has to finish before anything reads
        // one. It is a single small file read at process start, off the critical path of
        // anything the user can see, and blocking here is what keeps every downstream
        // read simple.
        runBlocking { graph.warmUp() }

        FlightNotifications.createChannels(this)
        startTheForegroundServiceWithTheFlight()
        runBackgroundChatterWithTheFlight()
    }

    /**
     * Run background chatter while it is switched on and a flight is active.
     *
     * Nothing used to start it, so the service — and with it the whole radio engine — never
     * ran and the setting did nothing. It is tied to the flight rather than to the app
     * being open, because ambient radio during a flight is the entire point; and it stops
     * with the flight, because a static bed hissing after block-in is battery drain the
     * pilot did not ask for.
     */
    private fun runBackgroundChatterWithTheFlight() {
        val chatter = graph.chatter
        // The chatter needs to know which frequency it is simulating; the coordinator is
        // the only thing that knows.
        chatter.bindContext(facility = { graph.flightSessionCoordinator.state.value.currentFacility })

        graph.sessionScope.launch {
            combine(
                graph.settingsRepository.state.map { it.backgroundChatterEnabled },
                graph.activeFlightController.isSessionActive,
            ) { enabled, active -> enabled && active }
                .distinctUntilChanged()
                .collect { shouldRun -> if (shouldRun) chatter.start() else chatter.stop() }
        }
    }

    /**
     * Start the active-flight foreground service when a flight begins.
     *
     * It is bound to the *flight*, not to the app being open: the whole point is to keep
     * polling Infinite Flight and updating the Live Flight Update while the pilot is in the
     * sim on another device. The observer lives on the Application rather than in an
     * Activity because the session outlives every Activity — a flight must not end because
     * the user swiped the task away.
     *
     * Only the start is here. Stopping is the service's own job: it watches the same flag
     * and stops itself when the flight ends, which is both simpler and *correct* — the
     * explicit stop intent also tears the session down, which is right when the pilot taps
     * "Stop" on the notification and wrong when a flight has simply reached the gate.
     *
     * A foreground service cannot always be started from the background (Android 12+). In
     * practice a flight begins with the pilot's own tap, so the app is visible; but a
     * telemetry-triggered first call while backgrounded would throw, and losing the live
     * update is not a reason to take the process down with it.
     */
    private fun startTheForegroundServiceWithTheFlight() {
        graph.applicationScope.launch {
            graph.activeFlightController.isSessionActive.collect { active ->
                if (!active) return@collect
                runCatching { ActiveFlightService.start(this@IFATCCompanionApplication) }
                    .onFailure { error ->
                        graph.diagnostics.log(
                            DiagnosticCategory.SESSION,
                            level = DiagnosticLevel.WARNING,
                            message = "Could not start the flight service: ${error.message}",
                        )
                    }
            }
        }
    }
}
