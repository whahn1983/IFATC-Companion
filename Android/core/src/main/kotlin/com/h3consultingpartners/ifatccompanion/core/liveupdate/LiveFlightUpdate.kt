package com.h3consultingpartners.ifatccompanion.core.liveupdate

/**
 * The data model behind the **Live Flight Update** — Android's counterpart to the iOS
 * Live Activity.
 *
 * The iOS build shows this on the Lock Screen and in the Dynamic Island through
 * ActivityKit. Android has no Dynamic Island and no ActivityKit, so the same content
 * is carried by the active-flight foreground service's ongoing notification, promoted
 * to a Live Update on Android 16+ where the platform supports it. The *content* is
 * what matters for parity, so this is a faithful port of
 * `CompanionActivityAttributes.ContentState`; only the presentation differs.
 *
 * Android UI must never call this a "Live Activity" — that is Apple's term.
 */
data class LiveFlightUpdate(
    /** Static title shown on the notification, e.g. "IFATC Companion · UAL598". */
    val flightTitle: String,
    /** Flight phase title, e.g. "Cruise". */
    val phase: String,
    /** Tuned controller title, e.g. "Center". */
    val facility: String,
    /**
     * Semantic icon key for the facility. iOS names an SF Symbol here; the Android
     * notification maps the key to a Material Symbol. See `ATCFacility.iconKey`.
     */
    val facilityIconKey: String,
    /** Live telemetry, already unit-converted for display. */
    val altitude: Int,
    val heading: Int,
    val speed: Int,
    /** Spoken/display callsign, e.g. "UAL598". */
    val callsign: String,
    /** Route as "KIAH → KMSP" (either side may be blank). */
    val route: String,
    /** The next controller ahead, when a hand-off is pending. */
    val nextFacility: String? = null,
    /** A short weather advisory, when one is active on the route. */
    val weatherAlert: String? = null,
    /** Whether the Read Back / Check In actions should be offered right now. */
    val canReadBack: Boolean = false,
    val canCheckIn: Boolean = false,
    /** True while the companion is deferring to a human controller. */
    val standby: Boolean = false,
    /**
     * When this snapshot was produced, shown on the card as a static "Updated 9:55 PM"
     * line so the user can see how current the numbers are.
     *
     * iOS also offers a Refresh button, because ActivityKit throttles a backgrounded
     * app's routine pushes and the numbers freeze even on a perfectly connected app.
     * Android has no equivalent throttle: the foreground service keeps running and
     * re-posts the notification as the state moves, so the numbers stay live on their
     * own and no Refresh action is offered. That difference is recorded in
     * Docs/ANDROID_PARITY_MATRIX.md.
     */
    val asOfMillis: Long,
) {
    /** The latest ATC instruction line, when the caller has one to show. */
    val hasPendingResponse: Boolean get() = canReadBack || canCheckIn
}

/**
 * An action offered on the Live Flight Update. Mirrors the iOS Live Activity's App
 * Intents, minus [Refresh] which Android does not need (see [LiveFlightUpdate.asOfMillis]).
 */
enum class LiveFlightAction(val id: String) {
    READ_BACK("read_back"),
    CHECK_IN("check_in"),
    ;

    /** The label shown on the notification action. */
    val label: String
        get() = when (this) {
            READ_BACK -> "Read Back"
            CHECK_IN -> "Check In"
        }

    companion object {
        fun fromId(id: String): LiveFlightAction? = entries.firstOrNull { it.id == id }
    }
}
