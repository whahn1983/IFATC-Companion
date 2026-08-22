package com.h3consultingpartners.ifatccompanion.core.surface.routing

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import com.h3consultingpartners.ifatccompanion.core.surface.AirportSurfaceModel
import com.h3consultingpartners.ifatccompanion.core.surface.GeoCoordinate

/**
 * Fill a blank gate field from the airport's own stand data, and keep the app's own guess
 * out of the pilot's way.
 *
 * Ported from the auto-gate section of `IFATCCompanion/App/AppModel.swift` —
 * `autoAssignGatesIfNeeded` (:3094), `dropForeignAutoGates` (:3129), `mayAutoAssignGate`
 * (:3203), `autoAssignGate` (:3261), `updateAutoGatesFromTelemetry` (:3035) and
 * `retryFailedAutoGatesIfDue` (:3052).
 *
 * [GateAssigner] — the stand picker itself, with its operator matching, size classes and
 * tie-breaking — was ported with tests and called from nowhere, so toggling "Assign gates
 * automatically" in Settings wrote a boolean nobody read: a blank gate stayed blank, and
 * the taxi route had no stand to route to or from.
 *
 * Three rules run through all of it, and each exists because breaking it is worse than
 * leaving a field empty:
 *
 *  - **A gate the pilot typed is never touched.** Not overwritten, not upgraded, not
 *    cleared when the feature is switched off.
 *  - **A gate assigned at a different airport is dropped immediately**, before anything is
 *    read — even if the new field's extract turns out to be unreadable. A stale gate reads
 *    as though it belonged here, which is how a KLAX arrival comes to show a stand that
 *    exists at no terminal at LAX.
 *  - **The app's own chosen gate is replaced only by better information** — the stand the
 *    aircraft is demonstrably parked on. A second *chosen* gate would just re-roll the dice
 *    on the pilot.
 */
class AutoGateController(
    private val clock: Clock,
    private val diagnostics: DiagnosticsSink = DiagnosticsSink.noop,
    private val settingsProvider: () -> AppSettings = { AppSettings() },
    /** The active plan, for the endpoints and the identity the picker matches stands against. */
    private val planProvider: () -> FlightPlan = { FlightPlan.empty },
    /** Where the aircraft is, so a departure gate can be *read* rather than chosen. */
    private val aircraftProvider: () -> AircraftState = { AircraftState.empty },
    /**
     * Whether a taxi is under way. A departure gate is where the aircraft actually is once
     * it starts moving, and reassigning it then would re-route the pilot mid-push.
     */
    private val taxiHasBegun: () -> Boolean = { false },
    /** The airport surface for a field, fetched on demand. Null when there is no extract. */
    private val surfaceProvider: suspend (icao: String, reference: Coordinate) -> AirportSurfaceModel? =
        { _, _ -> null },
    /** Where a field's reference point is, for the surface fetch. */
    private val referenceProvider: (icao: String) -> Coordinate? = { null },
    /** Persist a gate the app assigned, and the marker that says it is the app's. */
    private val writeGate: (role: GateRole, gate: String, stamp: String) -> Unit = { _, _, _ -> },
) {

    private val settings: AppSettings get() = settingsProvider()

    /** Requests in flight, so the same field is not fetched twice concurrently. */
    private val inFlight = mutableSetOf<String>()

    /** Consecutive unreadable extracts per field, so a dead endpoint is not hammered. */
    private val readFailures = mutableMapOf<String, Int>()

    private var lastRetryAtMillis: Long? = null
    private var aircraftWasParked: Boolean? = null

    /**
     * Fill any blank gate field from its airport's stand data.
     *
     * [forAirport] narrows it to the role(s) filed for one ICAO, which is what an arriving
     * extract uses so a landing surface only stirs the gate that was waiting on it.
     */
    suspend fun assignIfNeeded(forAirport: String? = null) {
        if (!settings.autoAssignGates) return
        val wanted = forAirport?.uppercase()?.trim()
        dropForeignGates()
        for (role in GateRole.entries) {
            if (wanted != null && airportFor(role) != wanted) continue
            assign(role)
        }
    }

    /**
     * A telemetry tick.
     *
     * The moment the aircraft comes to rest is the moment its stand can be read off its
     * position, so that transition re-runs the assignment. Otherwise this is where a failed
     * extract read gets its retry — without one, a cold Overpass read that timed out at
     * flight load left the gate unfilled for the whole flight, because the other triggers
     * (an endpoint change, a gate edit, becoming parked) had all already fired.
     */
    suspend fun onTelemetry(aircraft: AircraftState) {
        if (!settings.autoAssignGates) return
        val parked = aircraftIsParked(aircraft)
        val becameParked = parked && aircraftWasParked != true
        aircraftWasParked = parked
        if (becameParked) {
            assignIfNeeded()
            return
        }
        if (readFailures.isEmpty()) return
        val now = clock.nowMillis()
        val last = lastRetryAtMillis
        if (last != null && now - last < RETRY_INTERVAL_MILLIS) return
        lastRetryAtMillis = now
        assignIfNeeded()
    }

    /**
     * React to the Settings toggle: assign straight away when it is switched on, and give
     * the fields back when it is switched off.
     *
     * A gate the app filled in is the app's to withdraw; one the pilot typed always stays.
     */
    suspend fun applySettingChange() {
        if (settings.autoAssignGates) {
            assignIfNeeded()
        } else {
            clearAssignedGates()
        }
    }

    /** Clear the gates the app assigned itself, leaving anything the pilot typed alone. */
    fun clearAssignedGates() {
        val current = settings
        if (GateAssigner.isAppAssigned(current.departureGate, current.autoAssignedDepartureGate)) {
            writeGate(GateRole.DEPARTURE, "", "")
        } else {
            writeGate(GateRole.DEPARTURE, current.departureGate, "")
        }
        if (GateAssigner.isAppAssigned(current.arrivalGate, current.autoAssignedArrivalGate)) {
            writeGate(GateRole.ARRIVAL, "", "")
        } else {
            writeGate(GateRole.ARRIVAL, current.arrivalGate, "")
        }
        inFlight.clear()
        readFailures.clear()
    }

    /** Forget everything about the previous flight. */
    fun reset() {
        inFlight.clear()
        readFailures.clear()
        lastRetryAtMillis = null
        aircraftWasParked = null
    }

    // region The assignment

    private suspend fun assign(role: GateRole) {
        val icao = airportFor(role)
        if (icao.length < 3) return
        if (!shouldRun(role, icao)) return
        val reference = referenceProvider(icao)?.takeIf { it.isValid } ?: return

        val key = "${role.rawValue}:$icao"
        // Give up after a few unreadable reads for the same field rather than re-requesting
        // from a shared public endpoint for the rest of the flight. The field stays blank,
        // which is the honest answer.
        if ((readFailures[key] ?: 0) >= MAX_READ_FAILURES) return
        if (!inFlight.add(key)) return

        val surface = try {
            surfaceProvider(icao, reference)
        } finally {
            inFlight.remove(key)
        }
        if (surface == null) {
            noteReadFailure(key, role, icao)
            return
        }
        readFailures.remove(key)

        // Re-check: the pilot may have typed a gate, or the taxi may have started, while the
        // extract was downloading.
        if (!shouldRun(role, icao)) return
        val assignment = GateAssigner.assign(surface, flightContext(), role)
        if (assignment == null) {
            diagnostics.log(
                DiagnosticCategory.SURFACE,
                message = "No assignable stand at $icao for the ${role.title} gate " +
                    "(${surface.parkingPositions.size} parking features in the extract).",
            )
            return
        }
        if (!mayWrite(assignment, role, icao)) return
        applyAssignment(assignment, role, icao)
    }

    /**
     * Whether the assignment is worth running at all: either the field is the app's to fill,
     * or it holds a gate the app *chose* and the aircraft is now parked, so the stand it is
     * sitting on can replace the guess.
     */
    private fun shouldRun(role: GateRole, icao: String): Boolean =
        mayAssign(role, icao) || couldUpgrade(role, icao)

    /**
     * Three things have to hold: the editable field is the app's to write, the gate the
     * active plan is already flying is too — a saved flight carries its gates in the plan,
     * and those are not the app's to replace — and, for a departure, the taxi has not begun.
     */
    private fun mayAssign(role: GateRole, icao: String): Boolean {
        if (role == GateRole.DEPARTURE && taxiHasBegun()) return false
        val field = gateField(role)
        val stamp = gateStamp(role)
        return GateAssigner.mayAssign(field, stamp, icao) &&
            GateAssigner.mayAssign(planGateBlocking(role), stamp, icao)
    }

    /**
     * Whether a chosen departure gate could still be improved on by reading the aircraft's
     * position: the taxi has not begun, the aircraft is parked somewhere, and the field
     * still holds the app's own chosen — not yet position-derived — gate for this field.
     */
    private fun couldUpgrade(role: GateRole, icao: String): Boolean {
        if (role != GateRole.DEPARTURE || taxiHasBegun()) return false
        if (parkedPosition() == null) return false
        return GateAssigner.couldUpgrade(settings.departureGate, settings.autoAssignedDepartureGate, icao)
    }

    /**
     * Whether this particular assignment may be written: a field that is the app's to fill
     * takes anything, and a field already carrying an automatic gate takes only an upgrade.
     */
    private fun mayWrite(assignment: GateAssigner.Assignment, role: GateRole, icao: String): Boolean {
        if (mayAssign(role, icao)) return true
        if (role != GateRole.DEPARTURE) return false
        return GateAssigner.mayUpgrade(
            settings.departureGate,
            settings.autoAssignedDepartureGate,
            icao,
            assignment,
        )
    }

    private fun applyAssignment(assignment: GateAssigner.Assignment, role: GateRole, icao: String) {
        val stamp = AutoGateStamp(
            icao = icao,
            gate = assignment.gate,
            fromAircraftPosition = assignment.fromAircraftPosition,
        ).encoded
        writeGate(role, assignment.gate, stamp)
        diagnostics.log(
            DiagnosticCategory.SURFACE,
            message = "Auto-assigned ${role.title} gate ${assignment.gate} at $icao — " +
                "${assignment.reason}; ${assignment.totalCandidates} usable stands in the OSM extract.",
        )
    }

    /**
     * Drop any automatic gate belonging to a *different* airport than the one now filed.
     *
     * The rule that keeps a stale gate from being displayed as though it belonged here. The
     * app used to be willing to replace a previous flight's automatic gate, but only if a
     * replacement actually arrived — so when the new field's extract could not be read, the
     * old field's gate simply stayed. A stale gate is worse than no gate.
     */
    private fun dropForeignGates() {
        for (role in GateRole.entries) {
            val icao = airportFor(role)
            if (icao.length < 3) continue
            val stamp = AutoGateStamp.decode(gateStamp(role)) ?: continue
            if (!GateAssigner.isAppAssigned(gateField(role), gateStamp(role))) continue
            if (stamp.icao == icao) continue
            writeGate(role, "", "")
            diagnostics.log(
                DiagnosticCategory.SURFACE,
                message = "Dropped the automatic ${role.title} gate ${stamp.gate} — it was assigned " +
                    "at ${stamp.icao}, and this flight's ${role.title} field is $icao.",
            )
        }
    }

    private fun noteReadFailure(key: String, role: GateRole, icao: String) {
        val count = (readFailures[key] ?: 0) + 1
        readFailures[key] = count
        if (count != 1) return
        diagnostics.log(
            DiagnosticCategory.SURFACE,
            message = "No airport surface data for $icao yet — the ${role.title} gate stays " +
                "blank until its extract arrives.",
        )
    }

    // endregion

    // region Reading the flight

    private fun airportFor(role: GateRole): String {
        val plan = planProvider()
        return (if (role == GateRole.DEPARTURE) plan.departure else plan.destination).trim().uppercase()
    }

    private fun gateField(role: GateRole): String =
        if (role == GateRole.DEPARTURE) settings.departureGate else settings.arrivalGate

    private fun gateStamp(role: GateRole): String = if (role == GateRole.DEPARTURE) {
        settings.autoAssignedDepartureGate
    } else {
        settings.autoAssignedArrivalGate
    }

    /**
     * The gate the active plan is flying that the pilot did not type into the field — a
     * saved flight carries its gates in the plan, and those are not the app's to replace.
     */
    private fun planGateBlocking(role: GateRole): String {
        val plan = planProvider()
        return if (role == GateRole.DEPARTURE) plan.departureGate else plan.arrivalGate
    }

    /**
     * The details the stand picker matches against: the callsign (for the airline's own
     * stands), the aircraft Infinite Flight reports (for the stand's size), and — when the
     * aircraft is sitting still on the ground — where it is, which is what lets a departure
     * gate be *read* rather than chosen.
     */
    private fun flightContext(): GateAssigner.FlightContext {
        val plan = planProvider()
        return GateAssigner.FlightContext(
            callsign = plan.callsign,
            airline = plan.airline,
            aircraftName = aircraftProvider().aircraftName,
            parkedPosition = parkedPosition()?.let(::GeoCoordinate),
        )
    }

    /** The aircraft's position, but only when it is sitting still on the ground there. */
    private fun parkedPosition(): Coordinate? {
        val aircraft = aircraftProvider()
        if (!aircraftIsParked(aircraft)) return null
        return aircraft.coordinate
    }

    /**
     * Whether telemetry has the aircraft sitting still on the ground at a known position.
     *
     * Deliberately a weaker test than the one that ends the flight, which wants the parking
     * brake and the Ramp frequency behind it. All this position does is name the stand the
     * aircraft is already on, so being stopped on the ground is evidence enough; requiring
     * the brake would leave the gate unnamed for a pilot idling at their stand with it
     * released. A missing ground flag reads as *not* on the ground — the opposite default —
     * because with no ground reference there is nothing to say the aircraft is not airborne,
     * and an airborne position must never name a stand.
     */
    private fun aircraftIsParked(aircraft: AircraftState): Boolean {
        if (aircraft.coordinate == null) return false
        return aircraft.onGround == true && (aircraft.groundSpeed ?: 0.0) < PARKED_GROUND_SPEED
    }

    // endregion

    companion object {
        /** After this many unreadable extracts for one field, the app stops asking. */
        const val MAX_READ_FAILURES = 4

        /** How often a failed read is retried while the flight continues. */
        const val RETRY_INTERVAL_MILLIS = 90_000L

        /** Knots below which a stopped aircraft counts as sitting at a stand. */
        const val PARKED_GROUND_SPEED = 1.0
    }
}
