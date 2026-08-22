package com.h3consultingpartners.ifatccompanion.core.persistence

import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink
import com.h3consultingpartners.ifatccompanion.core.session.FlightSessionState
import com.h3consultingpartners.ifatccompanion.core.session.SavedFlightBinding

/**
 * Save, clear and load a flight.
 *
 * The library ([SavedFlightStore]) knows how to persist flights and [SavedFlightPolicy]
 * knows the rules; this is the part that puts them together with a live session, and it is
 * the part where a mistake costs the pilot an hour of conversation. It lives in `:core`
 * rather than in a ViewModel for exactly that reason: every branch below is reachable from
 * a test.
 *
 * The session itself is reached through lambdas rather than held, so this stays testable
 * without standing up a coordinator, a simulator link and a settings repository.
 */
class SavedFlightsController(
    private val store: SavedFlightStore,
    private val diagnostics: DiagnosticsSink = DiagnosticsSink.noop,
    /** The session as it stands, for the save/clear decisions and for capture. */
    private val session: () -> FlightSessionState,
    /** Everything about the flight worth persisting. */
    private val captureSnapshot: () -> SessionSnapshot,
    /** Put the session down and start clean. Weather, ATIS and surface are reset alongside. */
    private val resetSession: () -> Unit,
    /** Apply a snapshot to the freshly reset session. */
    private val restoreSession: (SessionSnapshot) -> Unit,
    /** Drop the crash-resume snapshot, so a cleared flight does not come back on relaunch. */
    private val clearResumableSession: () -> Unit,
    private val settings: () -> Boolean,
) {

    /** What the session needs to know about this library. Fed to the coordinator. */
    fun binding(): SavedFlightBinding {
        val id = store.activeFlightID.value
        return SavedFlightBinding(
            activeFlightName = store.activeFlight?.name,
            activeFlightStillInLibrary = id != null && store.flight(id) != null,
        )
    }

    /**
     * Save the session in progress, or null when there is nothing worth saving.
     *
     * A session already bound to a slot updates that slot in place rather than duplicating
     * it — tapping Save twice on one flight must not leave "KIAH-KORD" and "KIAH-KORD-1"
     * side by side in the list.
     *
     * The rule lives here and not only in the button that is disabled by it, so a save that
     * arrives another way — a confirmation dialog's "Save & Clear", an auto-save tick —
     * refuses exactly what the button refuses.
     */
    fun saveCurrentFlight(): SavedFlight? {
        if (!session().canSaveCurrentFlight) return null
        val snapshot = captureSnapshot()
        val boundId = store.activeFlightID.value
        val existing = boundId?.let(store::flight)
        if (boundId != null && existing != null) {
            store.update(boundId, snapshot)
            diagnostics.log(DiagnosticCategory.SESSION, message = "Updated saved flight \"${existing.name}\"")
            return store.flight(boundId)
        }
        val saved = store.save(snapshot)
        diagnostics.log(
            DiagnosticCategory.SESSION,
            message = "Saved flight \"${saved.name}\" at ${snapshot.atcState.title}",
        )
        return saved
    }

    /**
     * Keep a bound slot current as the flight progresses, when the pilot asked for that.
     *
     * Mock Mode is refused outright. Its scripted demo always starts at the gate and would
     * otherwise overwrite a real saved flight with a rehearsal — iOS guards the same way, at
     * the top of `persistSession`.
     */
    fun autoSave() {
        if (!settings()) return
        if (session().mockMode) return
        val id = store.activeFlightID.value ?: return
        if (store.flight(id) == null) return
        store.update(id, captureSnapshot())
    }

    /**
     * Start again from the gate, keeping the settings and the flight plan.
     *
     * A *finished* flight is retired from the library along with the session: it is over,
     * and a flight that has blocked in at the destination gate is not something to pick up
     * again. A flight still in progress is the opposite — clearing is how the pilot switches
     * to another one — so it stays in the list and is only unbound. Read before the reset,
     * which wipes the state the decision is made on.
     */
    fun startNewFlight() {
        val finished = session().flightHasEnded
        if (finished) {
            store.activeFlight?.let { done ->
                store.delete(done.id)
                diagnostics.log(
                    DiagnosticCategory.SESSION,
                    message = "Removed completed flight \"${done.name}\" from the saved list",
                )
            }
        }
        resetSession()
        // Without this the flight the pilot just cleared is still the one a relaunch comes
        // back to, because the crash-resume snapshot is written separately from the library.
        clearResumableSession()
        // A brand-new flight is not the saved one, so auto-save must stop writing to it.
        store.setActive(null)
    }

    /**
     * Load a saved flight, replacing the session in progress.
     *
     * The session is reset first and the snapshot applied to the clean state, never layered
     * over the live one: a half-replaced session would keep the previous flight's clearances
     * and ground references under the new flight's transcript.
     *
     * Everything describing the *flight* comes back — plan, transcript, radio, read-back
     * gate. Everything describing the *world* is re-derived by the caller, because the
     * aircraft's real position and the weather on the route are whatever they are now, not
     * what they were when the flight was put away.
     */
    fun loadSavedFlight(flight: SavedFlight): Boolean {
        if (session().mockMode) return false
        resetSession()
        restoreSession(flight.snapshot)
        store.setActive(flight.id)
        diagnostics.log(
            DiagnosticCategory.SESSION,
            message = "Loaded saved flight \"${flight.name}\" at ${flight.snapshot.atcState.title}",
        )
        return true
    }

    /**
     * Delete a saved flight. Deleting the one being flown only unbinds it — the session in
     * progress carries on, it simply stops auto-saving anywhere.
     */
    fun deleteSavedFlight(flight: SavedFlight) {
        store.delete(flight.id)
        diagnostics.log(DiagnosticCategory.SESSION, message = "Deleted saved flight \"${flight.name}\"")
    }

    /**
     * A warning when the simulator is flying a different route from the one about to be
     * loaded. Leads the confirmation because it is the one thing the pilot may not have
     * noticed.
     */
    fun endpointMismatch(flight: SavedFlight): String? {
        val plan = session().flightPlan
        return SavedFlightPolicy.endpointMismatch(
            liveRoute = SessionSnapshot.routeLabel(plan.departure, plan.destination),
            savedRoute = flight.snapshot.routeName,
        )
    }
}
