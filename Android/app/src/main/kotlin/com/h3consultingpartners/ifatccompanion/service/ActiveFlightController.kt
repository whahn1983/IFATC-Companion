package com.h3consultingpartners.ifatccompanion.service

import com.h3consultingpartners.ifatccompanion.core.liveupdate.LiveFlightAction
import com.h3consultingpartners.ifatccompanion.core.liveupdate.LiveFlightUpdate
import kotlinx.coroutines.flow.StateFlow

/**
 * What [ActiveFlightService] needs from the flight session, expressed as an interface
 * so the service does not depend on the whole engine graph (and so it can be driven by
 * a fake in tests).
 *
 * The real implementation is the app's single flight-session holder, which owns the
 * Infinite Flight connection, the ATC engine and the audio. The service does not own
 * the session — it keeps the process alive and mirrors the session onto the Live
 * Flight Update.
 */
interface ActiveFlightController {
    /** Whether an explicitly started flight session is running. */
    val isSessionActive: StateFlow<Boolean>

    /** The current Live Flight Update content, or null before the first snapshot. */
    val liveUpdate: StateFlow<LiveFlightUpdate?>

    /** Perform an action tapped on the notification. */
    fun performLiveAction(action: LiveFlightAction)

    /** End the session — the user disconnected, or Live Connected Mode was stopped. */
    fun stopSession()
}
