package com.h3consultingpartners.ifatccompanion.data

import android.content.Context
import android.net.wifi.WifiManager
import com.h3consultingpartners.ifatccompanion.core.connect.BroadcastReceiveHold
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticLevel
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink

/**
 * Holds a `WifiManager.MulticastLock` while Infinite Flight is being searched for.
 *
 * Several OEM Wi-Fi drivers filter inbound broadcast to 255.255.255.255 in the driver,
 * before it reaches a bound socket, unless this lock is held. The symptom is
 * indistinguishable from Infinite Flight not broadcasting at all — the UDP path simply
 * hears nothing — so the app fell back to the TCP subnet sweep every time and the
 * broadcast path, which is the fast one, was dead.
 *
 * `ACCESS_WIFI_STATE` and `CHANGE_WIFI_MULTICAST_STATE` have been in the manifest for
 * exactly this since the port began, with a comment saying so, and nothing acquired one.
 *
 * Held only for a discovery window, which is seconds: a multicast lock costs battery,
 * because it stops the Wi-Fi chip filtering packets the CPU would otherwise never wake
 * for. Reference counting is off and every call is idempotent, so a repeated `start()`
 * cannot leak a second lock.
 */
class WifiMulticastHold(
    context: Context,
    private val diagnostics: DiagnosticsSink = DiagnosticsSink.noop,
) : BroadcastReceiveHold {

    // The application context: this outlives any Activity, and getSystemService on an
    // Activity context would leak it for the lifetime of the lock.
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    private var lock: WifiManager.MulticastLock? = null

    @Synchronized
    override fun acquire() {
        if (lock != null) return
        val manager = wifi ?: return
        val result = runCatching {
            manager.createMulticastLock(TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        result.onSuccess { lock = it }
        result.onFailure { error ->
            // Not fatal: the subnet sweep still runs, and it is the documented workhorse
            // on both platforms. Worth a line, because it explains a slow discovery.
            diagnostics.log(
                DiagnosticCategory.DISCOVERY,
                level = DiagnosticLevel.WARNING,
                message = "Could not hold the Wi-Fi multicast lock: ${error.message}",
            )
        }
    }

    @Synchronized
    override fun release() {
        val held = lock ?: return
        lock = null
        runCatching { if (held.isHeld) held.release() }
    }

    private companion object {
        const val TAG = "IFATCCompanion:Discovery"
    }
}
