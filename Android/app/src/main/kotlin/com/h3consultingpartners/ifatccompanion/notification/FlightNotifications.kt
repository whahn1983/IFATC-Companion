package com.h3consultingpartners.ifatccompanion.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.h3consultingpartners.ifatccompanion.R
import com.h3consultingpartners.ifatccompanion.core.liveupdate.LiveFlightAction
import com.h3consultingpartners.ifatccompanion.core.liveupdate.LiveFlightUpdate
import com.h3consultingpartners.ifatccompanion.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The **Live Flight Update** — Android's counterpart to the iOS Live Activity.
 *
 * On iOS this is an ActivityKit activity on the Lock Screen and in the Dynamic Island.
 * Android has neither, so the same content rides on the active-flight foreground
 * service's ongoing notification. On Android 16 (API 36) and above the notification
 * asks to be *promoted* to a Live Update, which the platform surfaces prominently on
 * the lock screen and in the status bar; below that it is a well-formed ongoing
 * notification with the same information and the same actions.
 *
 * User-facing wording says "Live Flight Update", never "Live Activity" — that is
 * Apple's term and means nothing on Android.
 */
object FlightNotifications {

    const val ACTIVE_FLIGHT_CHANNEL_ID = "active_flight"
    const val ACTIVE_FLIGHT_NOTIFICATION_ID = 1001

    private val updatedAtFormat = SimpleDateFormat("h:mm a", Locale.US)

    fun createChannels(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        val channel = NotificationChannel(
            ACTIVE_FLIGHT_CHANNEL_ID,
            context.getString(R.string.notification_channel_active_flight),
            // LOW keeps the card silent: the app's own spoken ATC is the alert, and a
            // chime on every telemetry update would be intolerable across a whole flight.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_active_flight_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Build the ongoing notification for the active flight.
     *
     * [update] is null before the first telemetry snapshot arrives — the service must
     * post a notification within a few seconds of starting, so it starts with a
     * "Connecting…" card and replaces it as soon as there is something to show.
     */
    fun buildActiveFlight(context: Context, update: LiveFlightUpdate?): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, ACTIVE_FLIGHT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (update == null) {
            return builder
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.notification_connecting))
                .build()
        }

        builder.setContentTitle(titleLine(update))
        builder.setContentText(statusLine(update))
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(expandedText(context, update)))
        builder.setSubText(update.route.ifEmpty { update.callsign })

        if (update.canReadBack) builder.addAction(action(context, LiveFlightAction.READ_BACK))
        if (update.canCheckIn) builder.addAction(action(context, LiveFlightAction.CHECK_IN))

        if (Build.VERSION.SDK_INT >= 36) {
            // Android 16 Live Updates: ask the platform to promote this ongoing
            // notification so the flight stays visible on the lock screen and in the
            // status bar chip, the way the iOS Live Activity does. The short critical
            // text is what the compact chip shows when there is no room for more.
            builder.setRequestPromotedOngoing(true)
            builder.setShortCriticalText(update.facility)
        }

        return builder.build()
    }

    /** "Cruise · Center" — the phase and who is working the flight. */
    private fun titleLine(update: LiveFlightUpdate): String = buildString {
        append(update.phase)
        if (update.facility.isNotEmpty()) {
            append(" · ")
            append(update.facility)
        }
        if (update.standby) append(" · Standby")
    }

    /** "FL350 · 084° · 452 kt" — the numbers, in the same order as the iOS card. */
    private fun statusLine(update: LiveFlightUpdate): String {
        val altitude = if (update.altitude >= 18_000) {
            "FL${(update.altitude / 100).toString().padStart(3, '0')}"
        } else {
            "${update.altitude} ft"
        }
        return "$altitude · ${update.heading.toString().padStart(3, '0')}° · ${update.speed} kt"
    }

    private fun expandedText(context: Context, update: LiveFlightUpdate): String = buildString {
        appendLine(statusLine(update))
        if (update.route.isNotEmpty()) appendLine(update.route)
        update.nextFacility?.let { appendLine(context.getString(R.string.notification_next_facility, it)) }
        update.weatherAlert?.let { appendLine(it) }
        if (update.standby) appendLine(context.getString(R.string.notification_standby))
        append(
            context.getString(
                R.string.notification_updated_at,
                updatedAtFormat.format(Date(update.asOfMillis)),
            ),
        )
    }

    private fun action(context: Context, action: LiveFlightAction): NotificationCompat.Action {
        val intent = Intent(context, FlightNotificationActionReceiver::class.java)
            .setAction(FlightNotificationActionReceiver.ACTION_PREFIX + action.id)
        val pending = PendingIntent.getBroadcast(
            context,
            action.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(0, action.label, pending).build()
    }
}
