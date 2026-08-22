package com.h3consultingpartners.ifatccompanion.core.persistence

/**
 * The rules about saving, clearing and loading a flight.
 *
 * These are the decisions behind four buttons — Save, New Flight, Load and Delete — and
 * every one of them either keeps or throws away a flight the pilot has been talking through
 * for an hour. iOS holds them as computed properties on `AppModel`; here they are pure
 * functions taking their inputs, so the reasoning can be tested without a session, a store,
 * a settings repository and a simulator link all standing up first.
 *
 * The wording matters as much as the logic: these strings are what the pilot reads in a
 * confirmation dialog a moment before something becomes irreversible, so they are the iOS
 * strings, and they are asserted.
 */
object SavedFlightPolicy {

    /**
     * Whether the flight in progress is finished — blocked in at the destination gate.
     *
     * Deliberately the same rule as [SessionSnapshot.isCompleted], so what the library
     * calls a finished flight and what the session calls one can never disagree.
     */
    fun flightIsComplete(atcStateIsParked: Boolean, arrivalAnnounced: Boolean): Boolean =
        atcStateIsParked && arrivalAnnounced

    /**
     * Whether there is a flight worth saving: Live Mode, not already finished, and either a
     * conversation under way or a plan with somewhere to go — so a flight can be set up and
     * put in the list before pushback.
     *
     * A completed flight is excluded deliberately. There is nothing to come back to once
     * the aircraft has blocked in, and clearing retires such a flight from the list anyway,
     * so offering to save one would set up the contradiction of saving a flight the next
     * tap deletes.
     */
    fun canSaveCurrentFlight(
        mockMode: Boolean,
        flightIsComplete: Boolean,
        transcriptIsEmpty: Boolean,
        hasDeparted: Boolean,
        departure: String,
        destination: String,
    ): Boolean {
        if (mockMode || flightIsComplete) return false
        return !transcriptIsEmpty || hasDeparted ||
            departure.isNotEmpty() || destination.isNotEmpty()
    }

    /**
     * Whether the session in progress would be **lost** by starting a new flight or loading
     * a different one, so the UI can offer to save it first.
     *
     * A session bound to a slot that auto-save keeps current has nothing to lose; one that
     * is not — or whose auto-save is switched off — does, as soon as it has any history at
     * all. A finished flight is not "unsaved": it is done and cannot be saved, so warning
     * that it will be lost would offer the pilot a rescue that is not there.
     */
    fun hasUnsavedFlight(
        mockMode: Boolean,
        flightIsComplete: Boolean,
        transcriptIsEmpty: Boolean,
        hasDeparted: Boolean,
        autoSaveFlights: Boolean,
        activeFlightStillInLibrary: Boolean,
    ): Boolean {
        if (mockMode || flightIsComplete) return false
        if (transcriptIsEmpty && !hasDeparted) return false
        if (autoSaveFlights && activeFlightStillInLibrary) return false
        return true
    }

    /**
     * The name of the saved flight that starting a new one would retire, or null.
     *
     * Clearing a *finished* flight removes it from the library along with the session: it is
     * over, and a flight that has blocked in at the destination gate is not something to
     * pick up again. A flight still in progress is the opposite — clearing is how the pilot
     * switches to another one — so it stays in the list and is only unbound.
     */
    fun retiredByClearing(flightIsComplete: Boolean, activeFlightName: String?): String? =
        if (flightIsComplete) activeFlightName else null

    /**
     * A warning when the simulator is flying a different route from the one about to be
     * loaded, or null when they agree or either is unknown.
     *
     * This leads the confirmation on iOS because it is the one thing the pilot may not have
     * noticed: the saved flight will load, and then the next telemetry reading will correct
     * the plan to whatever is actually in the sim.
     */
    fun endpointMismatch(liveRoute: String, savedRoute: String): String? {
        if (liveRoute == UNNAMED_ROUTE || savedRoute == UNNAMED_ROUTE) return null
        if (liveRoute == savedRoute) return null
        return "Infinite Flight is reporting $liveRoute, but this saved flight is $savedRoute."
    }

    /**
     * What [SessionSnapshot.routeLabel] produces when neither endpoint is known. Two flights
     * that are both merely "Flight" are not evidence of a mismatch.
     */
    const val UNNAMED_ROUTE = "Flight"

    // region The words the pilot reads before something becomes irreversible

    const val NEW_FLIGHT_TITLE = "Start a new flight?"
    const val LOAD_FLIGHT_TITLE = "Load this flight?"

    const val SAVE_AND_START_NEW = "Save & Start New"
    const val SAVE_AND_LOAD = "Save & Load"
    const val START_NEW_FLIGHT = "Start New Flight"
    const val LOAD_FLIGHT = "Load Flight"

    const val UNSAVED_WILL_BE_LOST = "The flight you're on now hasn't been saved and will be lost."

    const val NEW_FLIGHT_EXPLANATION =
        "Starting a new flight clears the conversation and begins again from the gate. " +
            "Your settings and flight plan are kept."

    const val LOAD_FLIGHT_EXPLANATION =
        "Loading brings back that flight's transcript, clearances, frequency and plan. " +
            "Your position and the weather update from Infinite Flight on the next reading."

    const val EMPTY_LIST =
        "No saved flights yet. Tap Save to put the flight you're on now into this list — " +
            "load it back later and the app returns exactly as you left it: transcript, " +
            "frequency, clearances and plan."

    const val MOCK_MODE_FOOTER =
        "Saved flights are a Live Connected Mode feature — Mock Mode always starts a fresh " +
            "demo flight from the gate."

    /** Marks the flight the live session is flying — the one auto-save keeps up to date. */
    const val FLYING_BADGE = "Flying"

    fun retiredByNewFlightMessage(savedName: String): String =
        "The flight you're on is complete, so “$savedName” will be removed from your saved flights."

    /**
     * The whole confirmation message, assembled the way iOS assembles it — mismatch first,
     * then what is at stake, then what the action does.
     */
    fun confirmationMessage(
        endpointMismatch: String?,
        retiredName: String?,
        hasUnsavedFlight: Boolean,
        isNewFlight: Boolean,
    ): String = buildList {
        endpointMismatch?.let(::add)
        if (isNewFlight && retiredName != null) {
            add(retiredByNewFlightMessage(retiredName))
        } else if (hasUnsavedFlight) {
            add(UNSAVED_WILL_BE_LOST)
        }
        add(if (isNewFlight) NEW_FLIGHT_EXPLANATION else LOAD_FLIGHT_EXPLANATION)
    }.joinToString(" ")

    // endregion
}
