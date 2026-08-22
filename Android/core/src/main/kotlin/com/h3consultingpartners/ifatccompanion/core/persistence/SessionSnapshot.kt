package com.h3consultingpartners.ifatccompanion.core.persistence

import com.h3consultingpartners.ifatccompanion.core.atis.AirportATIS
import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.WeatherDeviationContext
import kotlinx.serialization.Serializable

/**
 * A persisted snapshot of the in-progress ATC session. When the Infinite Flight link
 * drops (the pilot switched apps, the device slept, Wi-Fi blipped) and the app
 * reconnects, the conversation should resume exactly where it left off — parked at
 * the gate, climbing, at cruise, on approach — rather than being re-derived from raw
 * telemetry, which can jump the flight straight to cruise.
 *
 * Ported from `IFATCCompanion/App/SessionStateStore.swift`.
 *
 * **Every JSON key is the Swift `Codable` key**, so a session means the same thing on
 * both platforms. The one deliberate difference is that Swift's `Date` fields are
 * epoch **milliseconds** here (as everywhere else in `:core`, which has no
 * Foundation), and carry the `Millis` suffix in their key to say so — the same
 * convention `ATCTransmission.timestampMillis` and `AirportATIS.fetchedAtMillis`
 * already use.
 *
 * Every field added after the first release is nullable with a `null` default, and
 * decoding ignores unknown keys, so a session written by an older *or* newer build
 * still loads (missing key → null → treated as false/absent).
 */
@Serializable
data class SessionSnapshot(
    /** Conversational/procedural position (what the UI shows and the flow drives off). */
    val atcState: ATCState,
    /** The state machine's internal current state (the gate-to-gate cursor). */
    val stateMachineCurrent: ATCState,
    val currentFacility: ATCFacility,
    val phase: FlightPhase,
    val assignedAltitude: Int,
    val hasDeparted: Boolean,
    val arrivalAnnounced: Boolean,
    val awaitingGateArrival: Boolean,
    val manualTuning: Boolean,
    val transcript: List<ATCTransmission>,
    /**
     * Flight-plan endpoints, recorded so a stale snapshot from a different flight can
     * be recognized — the endpoint-mismatch warning compares them before loading a
     * saved flight onto a different live flight.
     */
    val departure: String,
    val destination: String,
    /** Whether the snapshot was taken in mock mode (never restored into live mode). */
    val mockMode: Boolean,
    /**
     * When the snapshot was last written, epoch millis. Used to discard sessions too
     * old to be a reconnect of the same flight.
     */
    val savedAtMillis: Long,
    /**
     * Identifier of the enroute Center sector working the flight ("KZHU", "EGTT") when
     * the snapshot was taken, so a reconnect mid-cruise resumes with the sector the
     * pilot is already talking to instead of silently adopting whichever one is under
     * the aircraft — or, worse, re-announcing a hand-off they already read back.
     * Optional so snapshots written before this field decode cleanly.
     */
    val centerSectorID: String? = null,

    /**
     * Whether a Center-to-Center hand-off is waiting on the pilot to check in.
     *
     * Persisted because it changes what the *next* check-in means: without it a reconnect
     * mid-hand-off answers the pilot's call-up with the next clearance in the flow instead
     * of a radar-contact acknowledgement.
     */
    val awaitingCenterSectorCheckIn: Boolean? = null,
    /**
     * Whether Ground has already handed the departing aircraft to Tower to *monitor*
     * (the "monitor Tower on …" hand-off short of the runway), so a reconnect mid-taxi
     * doesn't re-issue it. Optional so snapshots written before this field decode
     * cleanly (missing key → null → treated as false).
     */
    val monitoringTower: Boolean? = null,
    /**
     * The in-progress weather-deviation interaction, so a reconnect mid-diversion
     * restores the deviation card (and its "clear of weather" button) rather than
     * dropping it. Optional so snapshots written before this field decode cleanly.
     */
    val weatherDeviation: WeatherDeviationContext? = null,
    /**
     * The ATIS information code letter the pilot has received (by tuning ATIS) for the
     * departure / arrival, so a reconnect keeps appending "information X" to the taxi
     * request / approach check-in. Optional so older snapshots decode cleanly.
     */
    val reportedDepartureInfo: String? = null,
    val reportedArrivalInfo: String? = null,
    /**
     * Whether the information code has already been reported to ATC for each phase, so
     * a reconnect doesn't repeat it on the next taxi request / Approach check-in.
     * Optional so older snapshots decode cleanly (missing key → null → treated as false).
     */
    val departureInfoAppended: Boolean? = null,
    val arrivalInfoAppended: Boolean? = null,
    /**
     * Whether the pilot has tuned away from the ATIS frequency for each phase, so a
     * reconnect keeps the ATIS tune button hidden instead of resurfacing it after the
     * pilot already copied the broadcast. Optional so older snapshots decode cleanly.
     */
    val departureATISDismissed: Boolean? = null,
    val arrivalATISDismissed: Boolean? = null,

    // region Whole-session fields
    //
    // Everything below exists for *saved flights*, which restore the entire app rather
    // than just the conversational cursor a reconnect needs. All of it is optional so
    // snapshots written by earlier versions still decode (missing key → null), and the
    // auto-resume path gets the richer restore for free.

    /**
     * The flight plan as it stood, so a saved flight loads with its route, runways and
     * procedures already in place instead of blank until Infinite Flight re-publishes
     * them. The next live tick still merges the sim's plan over it, so a route edited
     * in the sim after saving wins.
     */
    val flightPlan: FlightPlan? = null,
    /**
     * The pilot's manually-entered flight fields (callsign, endpoints, gates, …). They
     * live in `AppSettings` because the Flight tab edits them there, but they describe
     * the flight rather than the app, so they travel with a saved flight — while
     * genuine preferences (voices, volumes, radar, host/port) deliberately do not.
     */
    val overrides: FlightOverrides? = null,
    /**
     * The frequency the pilot is actually tuned to, and any facility they have tuned to
     * but not yet checked in with.
     */
    val tunedFacility: ATCFacility? = null,
    val pendingCheckInFacility: ATCFacility? = null,
    /**
     * The read-back gate: whether a controller is waiting on the pilot, which call it is
     * waiting on, and how many times it has already re-prompted.
     */
    val awaitingReadback: Boolean? = null,
    val pendingReadbackTx: ATCTransmission? = null,
    val readbackPrompts: Int? = null,
    /**
     * A go-around in progress holds the automatic flow until the pilot re-establishes
     * with Approach, so it must survive being put away mid-pattern.
     */
    val goAroundInProgress: Boolean? = null,
    /**
     * Arrival-to-gate staging: whether "monitor ramp to the gate" has already been
     * issued, and where the gate is, so a flight saved on the taxi-in still blocks in at
     * the right place rather than completing on the first full stop.
     */
    val gateMonitored: Boolean? = null,
    val arrivalGateLatitude: Double? = null,
    val arrivalGateLongitude: Double? = null,
    /**
     * Ground references captured before departure that later altitude decisions are
     * measured against (initial-climb altitudes, the 2,000 ft AGL Departure hand-off).
     */
    val departureFieldElevationMSL: Double? = null,
    val liftoffAltitudeMSL: Double? = null,
    /**
     * Whether the pilot has worked ATC at all, which gates the ambient chatter. Falls
     * back to being derived from the transcript when absent.
     */
    val atcCommunicationStarted: Boolean? = null,
    /**
     * ATIS reports already fetched, so the ATIS card is populated on load rather than
     * blank until the next refresh cycle.
     */
    val departureATIS: AirportATIS? = null,
    val arrivalATIS: AirportATIS? = null,
    val lastArrivalATISAttemptMillis: Long? = null,
    /**
     * Weather-interaction bookkeeping that a fresh radar sample cannot re-derive:
     * whether the pilot has already dealt with the active conflict. The observations
     * themselves — radar cells, METARs, TAF, PIREPs, SIGMETs — are deliberately *not*
     * saved. They are re-fetched on load, and restoring hours-old cells would draw a
     * deviation around weather that has since moved.
     */
    val weatherHandled: Boolean? = null,
    val mockWeatherAdvisoryIssued: Boolean? = null,
    /**
     * The Diagnostics tab's log, so a saved flight's history is inspectable after it is
     * loaded back.
     */
    val diagnostics: DiagnosticsSnapshot? = null,
    // endregion
) {

    /**
     * Whether this snapshot represents a flight already finished at the gate — there is
     * nothing to resume, so it should not be restored onto a fresh launch. (Saved
     * flights are exempt: the pilot picked that flight explicitly, and a completed one
     * is still worth reopening to read its transcript.)
     */
    val isCompleted: Boolean get() = atcState == ATCState.PARKED && arrivalAnnounced

    /** The gate coordinate the arrival blocks in at, when one was captured. */
    val arrivalGateCoordinate: Coordinate?
        get() {
            val lat = arrivalGateLatitude ?: return null
            val lon = arrivalGateLongitude ?: return null
            return Coordinate(lat, lon)
        }

    /**
     * A route label for the saved-flight list, e.g. "KIAH-KORD". Falls back to whichever
     * endpoint is known, and to a neutral name when the plan names neither.
     */
    val routeName: String get() = routeLabel(departure, destination)

    companion object {
        /**
         * The same label built from a pair of endpoints, so a live flight plan can be
         * described the same way a saved one is (used to compare the two before loading).
         */
        fun routeLabel(departure: String, destination: String): String {
            val dep = departure.trim().uppercase()
            val dest = destination.trim().uppercase()
            return when {
                dep.isNotEmpty() && dest.isNotEmpty() -> "$dep-$dest"
                dep.isNotEmpty() -> dep
                dest.isNotEmpty() -> dest
                else -> "Flight"
            }
        }
    }
}

/**
 * The pilot's manually-entered flight fields, captured with a saved flight.
 *
 * These are stored in [AppSettings] (the Flight tab edits them there) but they are
 * flight data, not preferences: reloading a saved flight must bring back the callsign
 * you were flying under and the gates you entered, without disturbing the device-level
 * setup — voices, volumes, chatter, radar and the Infinite Flight host/port stay as they
 * are.
 */
@Serializable
data class FlightOverrides(
    val callsign: String = "",
    val airline: String = "",
    val flightNumber: String = "",
    val departure: String = "",
    val destination: String = "",
    val alternate: String = "",
    val cruiseAltitude: Int = 0,
    val runway: String = "",
    val sid: String = "",
    val star: String = "",
    val approach: String = "",
    val departureGate: String = "",
    val arrivalGate: String = "",
) {

    /**
     * Write the saved flight's fields back over a settings value. iOS assigns each field
     * through `AppSettings`' `didSet` so the Flight tab shows them immediately; the
     * Kotlin settings value is immutable, so this returns the updated copy for the
     * repository to publish.
     */
    fun applyTo(settings: AppSettings): AppSettings = settings.copy(
        callsign = callsign,
        airline = airline,
        flightNumber = flightNumber,
        departure = departure,
        destination = destination,
        alternate = alternate,
        cruiseAltitude = cruiseAltitude,
        runway = runway,
        sid = sid,
        star = star,
        approach = approach,
        departureGate = departureGate,
        arrivalGate = arrivalGate,
    )

    companion object {
        /** Capture the flight fields currently entered in Settings / the Flight tab. */
        fun from(settings: AppSettings): FlightOverrides = FlightOverrides(
            callsign = settings.callsign,
            airline = settings.airline,
            flightNumber = settings.flightNumber,
            departure = settings.departure,
            destination = settings.destination,
            alternate = settings.alternate,
            cruiseAltitude = settings.cruiseAltitude,
            runway = settings.runway,
            sid = settings.sid,
            star = settings.star,
            approach = settings.approach,
            departureGate = settings.departureGate,
            arrivalGate = settings.arrivalGate,
        )
    }
}
