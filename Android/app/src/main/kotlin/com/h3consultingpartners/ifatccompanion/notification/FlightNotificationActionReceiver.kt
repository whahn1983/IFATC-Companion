package com.h3consultingpartners.ifatccompanion.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.h3consultingpartners.ifatccompanion.core.liveupdate.LiveFlightAction
import com.h3consultingpartners.ifatccompanion.service.ActiveFlightService

/**
 * Handles the Read Back / Check In buttons on the Live Flight Update.
 *
 * The iOS build routes its Live Activity buttons through App Intents into a shared
 * action centre. Android has no equivalent, so the buttons are ordinary broadcasts
 * that are forwarded to the running [ActiveFlightService] — which owns the flight
 * session and can perform the action whether or not any Activity is alive.
 */
class FlightNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val actionId = intent.action?.removePrefix(ACTION_PREFIX) ?: return
        val action = LiveFlightAction.fromId(actionId) ?: return
        ActiveFlightService.performAction(context, action)
    }

    companion object {
        const val ACTION_PREFIX = "com.h3consultingpartners.ifatccompanion.LIVE_ACTION."
    }
}
