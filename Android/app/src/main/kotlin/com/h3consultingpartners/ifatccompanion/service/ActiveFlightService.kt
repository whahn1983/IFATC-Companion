package com.h3consultingpartners.ifatccompanion.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.h3consultingpartners.ifatccompanion.AppGraph
import com.h3consultingpartners.ifatccompanion.core.liveupdate.LiveFlightAction
import com.h3consultingpartners.ifatccompanion.notification.FlightNotifications
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps an explicitly started flight session running while the app is not on screen:
 * the Infinite Flight connection and its 1 Hz poll, phase detection, the ATC state
 * machine, the spoken radio, and the optional ambient chatter.
 *
 * **Why a foreground service, and why this type.** iOS keeps the flight alive with the
 * `audio` background mode, which is why the iOS build ties background operation to the
 * ambient-chatter toggle — it needs something actually producing audio. Android has no
 * such requirement, so on Android the session runs in the background whenever the pilot
 * has started a flight, whether or not chatter is on. That is a deliberate improvement
 * in the user experience, not a divergence in features; it is recorded in
 * Docs/ANDROID_PARITY_MATRIX.md.
 *
 * The declared type is `mediaPlayback` because the service genuinely plays audio for the
 * whole session — every controller call and pilot read-back is spoken, and the optional
 * chatter is a continuous radio bed. The service holds audio focus and a media session
 * for its lifetime to back that claim. Silence between transmissions is what a radio
 * sounds like; the app never plays silent audio to stay alive, which both Google Play
 * policy and Apple's guideline 2.5.4 rightly prohibit. Docs/ANDROID_BACKGROUND_EXECUTION.md
 * records the justification and the `specialUse` fallback if Play ever asks for one.
 *
 * The service is only ever started from a visible Activity as part of the pilot's
 * Connect / Start Flight action, so it never trips the background-start restrictions.
 */
class ActiveFlightService : LifecycleService() {

    private val controller: ActiveFlightController?
        get() = AppGraph.instanceOrNull()?.activeFlightController

    override fun onCreate() {
        super.onCreate()
        FlightNotifications.createChannels(this)
        startInForeground()
        observeSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                controller?.stopSession()
                stopSelfSafely()
                return START_NOT_STICKY
            }

            ACTION_PERFORM -> {
                intent.getStringExtra(EXTRA_ACTION_ID)
                    ?.let(LiveFlightAction::fromId)
                    ?.let { controller?.performLiveAction(it) }
            }
        }

        // The session is started deliberately by the pilot and must not be resurrected by
        // the system after a process death — a silently restarted flight with no
        // connection and no context would be worse than none.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startInForeground() {
        val notification = FlightNotifications.buildActiveFlight(this, controller?.liveUpdate?.value)
        ServiceCompat.startForeground(
            this,
            FlightNotifications.ACTIVE_FLIGHT_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private fun observeSession() {
        val controller = controller ?: return

        lifecycleScope.launch {
            controller.liveUpdate.collectLatest { update ->
                if (!canPostNotifications()) return@collectLatest
                // Re-post rather than rebuild the whole foreground state: the platform
                // treats an update to the same id as an update, and the channel is silent,
                // so the card refreshes without ever alerting.
                NotificationManagerCompat.from(this@ActiveFlightService).notify(
                    FlightNotifications.ACTIVE_FLIGHT_NOTIFICATION_ID,
                    FlightNotifications.buildActiveFlight(this@ActiveFlightService, update),
                )
            }
        }

        lifecycleScope.launch {
            controller.isSessionActive.collectLatest { active ->
                // The session ending is what stops the service: the pilot disconnected,
                // the flight reached the gate, or Live Connected Mode was stopped.
                if (!active) stopSelfSafely()
            }
        }
    }

    /**
     * From Android 13 POST_NOTIFICATIONS is a runtime permission, and a denial makes
     * `notify()` a silent no-op rather than an error. MainActivity asks for it, but the
     * pilot is free to say no — and then the Live Flight Update simply never appears
     * while the service itself keeps running perfectly well. Checking here keeps that
     * an explicit, readable decision instead of an invisible one.
     */
    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val ACTION_STOP = "com.h3consultingpartners.ifatccompanion.STOP_FLIGHT"
        private const val ACTION_PERFORM = "com.h3consultingpartners.ifatccompanion.PERFORM_LIVE_ACTION"
        private const val EXTRA_ACTION_ID = "action_id"

        /**
         * Start the session service. Must be called from a visible Activity, as part of
         * the pilot's own Connect / Start Flight action.
         */
        fun start(context: Context) {
            val intent = Intent(context, ActiveFlightService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ActiveFlightService::class.java).setAction(ACTION_STOP),
            )
        }

        fun performAction(context: Context, action: LiveFlightAction) {
            context.startService(
                Intent(context, ActiveFlightService::class.java)
                    .setAction(ACTION_PERFORM)
                    .putExtra(EXTRA_ACTION_ID, action.id),
            )
        }
    }
}
