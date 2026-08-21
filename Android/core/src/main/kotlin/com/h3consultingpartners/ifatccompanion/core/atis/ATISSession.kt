package com.h3consultingpartners.ifatccompanion.core.atis

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission

/**
 * The per-flight ATIS state and the rules around it: when the ATIS button is offered,
 * which field's ATIS is the relevant one, which information code the pilot has actually
 * received by tuning, and when that code is reported to ATC.
 *
 * Ported from the `// MARK: - ATIS` extension of `IFATCCompanion/App/AppModel.swift`.
 * On iOS these are computed properties on the app model; here they are one small object
 * the session coordinator owns, taking the live facts it needs as a [Context] on each
 * query rather than reaching back into the coordinator. That keeps the rules — the
 * 100 NM arrival window, the per-phase dismissal, the report-the-code-once memory —
 * testable on their own, which is where every one of them was got wrong first.
 *
 * Nothing here fetches: [ATISService] does that. This object records what came back.
 */
class ATISSession {

    /**
     * The live facts an availability question depends on. Supplied by the session
     * coordinator at the moment of the question, so nothing here holds a stale copy of
     * the flight.
     */
    data class Context(
        /** At the gate / on the ground before the first takeoff. */
        val isPreDeparture: Boolean,
        val hasDeparted: Boolean,
        val atcState: ATCState,
        /** The aircraft's current position, or null when no valid fix has arrived. */
        val position: Coordinate? = null,
        /** The destination field's coordinate, resolved from the plan. */
        val destinationCoordinate: Coordinate? = null,
        val departureICAO: String = "",
        val destinationICAO: String = "",
        /**
         * Mock Mode. ATIS is a real-world, live-data feature keyed to the actual flight,
         * so the scripted demo never fetches — see [shouldRefreshDeparture].
         */
        val mockMode: Boolean = false,
    )

    // region Received reports

    /** Latest D-ATIS report for the departure field (null = none published / not fetched). */
    var departureATIS: AirportATIS? = null

    /** Latest D-ATIS report for the destination field (fetched within 100 NM). */
    var arrivalATIS: AirportATIS? = null

    /**
     * The information code letter the pilot has actually received by **tuning** ATIS,
     * per phase. This — not the report's own letter — is what is reported to ATC on the
     * taxi request and the approach check-in. Null until the pilot tunes ATIS for that
     * phase.
     */
    var reportedDepartureInfo: String? = null
    var reportedArrivalInfo: String? = null

    /** Whether the code has already been reported to ATC for each phase. */
    var departureInfoAppended: Boolean = false
    var arrivalInfoAppended: Boolean = false

    /**
     * Whether the pilot has tuned away from the ATIS frequency for each phase, so the
     * button drops out of the grid: you don't keep re-listening after you've copied the
     * information. Tracked per phase so the arrival ATIS button still reappears within
     * 100 NM of the destination even though the departure ATIS button was dismissed.
     */
    var departureATISDismissed: Boolean = false
    var arrivalATISDismissed: Boolean = false

    /** Throttle for the opportunistic arrival-ATIS fetch driven from the telemetry loop. */
    var lastArrivalATISAttemptMillis: Long? = null

    // endregion

    // region Availability

    /**
     * Whether the aircraft is within the arrival-ATIS range: departed, position and
     * destination both known, and within 100 NM of the destination field.
     */
    fun withinArrivalATISRange(ctx: Context): Boolean {
        if (!ctx.hasDeparted) return false
        val pos = ctx.position?.takeIf { it.isValid } ?: return false
        val dest = ctx.destinationCoordinate?.takeIf { it.isValid } ?: return false
        return Geo.distanceNM(pos, dest) <= ARRIVAL_RANGE_NM
    }

    /**
     * Whether the **departure** ATIS tune button should be offered: pre-departure (at
     * the gate / on the ground before the first takeoff) and ATIS data is available.
     */
    fun departureATISAvailable(ctx: Context): Boolean = ctx.isPreDeparture && departureATIS != null

    /**
     * Whether the **arrival** ATIS tune button should be offered: airborne/arriving,
     * within 100 NM of the destination, not yet parked, and ATIS data is available.
     */
    fun arrivalATISAvailable(ctx: Context): Boolean =
        ctx.hasDeparted && ctx.atcState != ATCState.PARKED &&
            withinArrivalATISRange(ctx) && arrivalATIS != null

    /**
     * The ATIS report relevant to the current phase (arrival preferred once in range,
     * else the departure ATIS), or null when no ATIS applies right now.
     */
    fun currentATIS(ctx: Context): AirportATIS? = when {
        arrivalATISAvailable(ctx) -> arrivalATIS
        departureATISAvailable(ctx) -> departureATIS
        else -> null
    }

    /** Whether the currently-relevant ATIS is the arrival (destination) ATIS. */
    fun currentATISIsArrival(ctx: Context): Boolean = arrivalATISAvailable(ctx)

    /**
     * Whether the ATIS tune button should be shown in the frequency grid right now: ATIS
     * data is available for the current phase **and** the pilot hasn't yet tuned away
     * from it. Tuning any controller/ramp frequency dismisses it for the phase (departure
     * ATIS at the gate, arrival ATIS within 100 NM), so it behaves like a real radio —
     * you copy the broadcast, then move on to your next frequency.
     */
    fun atisButtonVisible(ctx: Context): Boolean {
        if (currentATIS(ctx) == null) return false
        return if (currentATISIsArrival(ctx)) !arrivalATISDismissed else !departureATISDismissed
    }

    /** The ICAO whose ATIS is relevant right now (destination on arrival, else origin). */
    fun atisAirport(ctx: Context): String =
        if (currentATISIsArrival(ctx)) ctx.destinationICAO else ctx.departureICAO

    /**
     * The information code letter carried by the currently-relevant ATIS report ("A"), or
     * null when none is known.
     */
    fun currentATISCode(ctx: Context): String? =
        currentATIS(ctx)?.letter(currentATISIsArrival(ctx))

    /**
     * Secondary label for the ATIS tune button: the current info code when known
     * ("Info B"), else a prompt to listen.
     */
    fun atisButtonSubtitle(ctx: Context): String {
        val code = currentATISCode(ctx) ?: return "Listen"
        return "Info $code"
    }

    /**
     * A one-line receipt summary shown under the ATIS button once the pilot has tuned in
     * and captured the information code, e.g. "KLAX arrival information Bravo — added to
     * your check-in." Null until the pilot has tuned ATIS for this phase.
     */
    fun atisReceiptSummary(ctx: Context): String? {
        val arrival = currentATISIsArrival(ctx)
        val letter = (if (arrival) reportedArrivalInfo else reportedDepartureInfo) ?: return null
        val word = ATISPhraseology.phoneticLetter(letter)
        val field = if (arrival) ctx.destinationICAO else ctx.departureICAO
        val kind = if (arrival) "arrival" else "departure"
        val where = if (arrival) "check-in" else "taxi request"
        return "$field $kind information $word — added to your $where."
    }

    // endregion

    // region Refresh cadence

    /**
     * Whether the periodic refresh (connect, route change, pull-to-refresh, and the
     * weather timer) should pull the **departure** ATIS: while pre-departure, with a
     * named origin, in live mode.
     */
    fun shouldRefreshDeparture(ctx: Context): Boolean =
        !ctx.mockMode && ctx.isPreDeparture && ctx.departureICAO.isNotEmpty()

    /**
     * Whether the same refresh should pull the **arrival** ATIS: departed, not parked,
     * within 100 NM of a named destination, in live mode.
     */
    fun shouldRefreshArrival(ctx: Context): Boolean =
        !ctx.mockMode && ctx.hasDeparted && ctx.atcState != ATCState.PARKED &&
            withinArrivalATISRange(ctx) && ctx.destinationICAO.isNotEmpty()

    /**
     * The opportunistic destination-ATIS fetch driven from the telemetry loop, so the
     * arrival ATIS button appears the moment the aircraft comes within range rather than
     * waiting for the ~5-minute timer. Throttled to one attempt a minute, and only until
     * a report is actually held. Records the attempt when it returns true.
     */
    fun shouldAttemptArrivalFetch(ctx: Context, nowMillis: Long): Boolean {
        if (ctx.mockMode) return false // live-data feature; see shouldRefreshDeparture
        if (!ctx.hasDeparted || ctx.atcState == ATCState.PARKED) return false
        if (arrivalATIS != null) return false
        if (!withinArrivalATISRange(ctx) || ctx.destinationICAO.isEmpty()) return false
        val last = lastArrivalATISAttemptMillis
        if (last != null && nowMillis - last < ARRIVAL_FETCH_THROTTLE_MILLIS) return false
        lastArrivalATISAttemptMillis = nowMillis
        return true
    }

    // endregion

    // region Tuning

    /**
     * Apply the report a tune pulled. Returns the one-way transcript line to post and
     * speak, or null when the ATIS disappeared since the button was shown — in which case
     * the report is dropped so the button hides.
     *
     * Storing [reportedDepartureInfo] / [reportedArrivalInfo] here is the whole point of
     * tuning: it is the code the pilot now "has", and the only one ever reported to ATC.
     */
    fun applyTunedATIS(
        atis: AirportATIS?,
        arrival: Boolean,
        nowMillis: Long,
        icao: Boolean = false,
    ): ATCTransmission? {
        val part = atis?.part(arrival)
        if (atis == null || part == null) {
            if (arrival) arrivalATIS = null else departureATIS = null
            return null
        }
        if (arrival) arrivalATIS = atis else departureATIS = atis
        val letter = atis.letter(arrival)
        if (arrival) reportedArrivalInfo = letter else reportedDepartureInfo = letter
        return atisTransmission(part, nowMillis, icao)
    }

    /**
     * The pilot tuned a controller / ramp, so they've moved on from the ATIS for this
     * phase: if an ATIS was actually available, dismiss its button so it drops out of the
     * frequency grid. Guarding on availability means an early tune before the feed
     * arrives never permanently hides a later-arriving ATIS.
     */
    fun leaveATISFrequency(ctx: Context) {
        if (currentATIS(ctx) == null) return
        if (currentATISIsArrival(ctx)) arrivalATISDismissed = true else departureATISDismissed = true
    }

    // endregion

    // region Appending the information code

    /**
     * The phonetic information word ("Alpha") the pilot reports for the given phase —
     * **once**. Returns null when no ATIS was received or the code has already been
     * reported, so it is never repeated on a re-tap. Marks the phase reported.
     */
    fun consumeATISInfoWord(arrival: Boolean): String? {
        if (arrival) {
            if (arrivalInfoAppended) return null
            val letter = reportedArrivalInfo ?: return null
            arrivalInfoAppended = true
            return ATISPhraseology.phoneticLetter(letter)
        }
        if (departureInfoAppended) return null
        val letter = reportedDepartureInfo ?: return null
        departureInfoAppended = true
        return ATISPhraseology.phoneticLetter(letter)
    }

    // endregion

    // region Reset and diagnostics

    /**
     * Reset per-flight ATIS visibility, and — only for a genuinely fresh flight — clear
     * the fetched reports and the received information codes.
     *
     * [clearReported] distinguishes a **fresh flight** (new flight / mock start / Clear
     * Flight → true) from a **reconnect or resume of the same flight** (false, e.g.
     * returning from another app). On a reconnect the already-fetched reports are kept in
     * memory rather than blanked: nulling them made the Diagnostics ATIS line (and the
     * tune button) flap "received → not available → received" on every app switch,
     * because the connect-time refresh only re-populated them a beat later.
     */
    fun reset(clearReported: Boolean) {
        // Dismissal state is per-flight visibility: always reset it so a fresh (or
        // re-derived) flight shows the ATIS button again. A resume restores the dismissal
        // flags afterwards from the snapshot.
        departureATISDismissed = false
        arrivalATISDismissed = false
        if (clearReported) {
            departureATIS = null
            arrivalATIS = null
            lastArrivalATISAttemptMillis = null
            reportedDepartureInfo = null
            reportedArrivalInfo = null
            departureInfoAppended = false
            arrivalInfoAppended = false
        }
    }

    /** The read-only view the Diagnostics tab renders. */
    fun diagnostics(ctx: Context): ATISDiagnostics = ATISDiagnostics(
        departureAirport = ctx.departureICAO,
        departureReceived = departureATIS != null,
        departureLetter = departureATIS?.letter(false),
        arrivalAirport = ctx.destinationICAO,
        arrivalReceived = arrivalATIS != null,
        arrivalLetter = arrivalATIS?.letter(true),
        withinArrivalRange = withinArrivalATISRange(ctx),
        reportedDeparture = reportedDepartureInfo,
        reportedArrival = reportedArrivalInfo,
    )

    // endregion

    companion object {

        /**
         * Nautical miles from the destination at which the arrival ATIS becomes relevant.
         * Real-world practice: you copy the destination ATIS well before the descent.
         */
        const val ARRIVAL_RANGE_NM = 100.0

        /** One opportunistic arrival-ATIS attempt a minute, in milliseconds. */
        const val ARRIVAL_FETCH_THROTTLE_MILLIS = 60_000L

        /**
         * Build the one-way ATIS transcript line: the verbatim text for display, and the
         * abbreviation-expanded, digit-by-digit reading for speech. Sent as SYSTEM on the
         * Ground facility, whose label the transcript row overrides to "ATIS"; [isATIS]
         * keeps it out of the read-back and hand-off bookkeeping entirely.
         */
        fun atisTransmission(
            part: AirportATIS.Part,
            nowMillis: Long,
            icao: Boolean = false,
        ): ATCTransmission = ATCTransmission(
            sender = ATCTransmission.Sender.SYSTEM,
            facility = ATCFacility.GROUND,
            displayText = ATISPhraseology.displayText(part.text),
            spokenText = ATISPhraseology.spokenText(part.text, icao),
            timestampMillis = nowMillis,
            isATIS = true,
        )

        /**
         * Append ", information <word>" before the trailing period of a pilot
         * transmission (both display and spoken forms). A null/blank word returns the
         * transmission unchanged, so nothing is ever appended when no ATIS was received.
         */
        fun appendingATISInfo(tx: ATCTransmission, word: String?): ATCTransmission {
            val trimmed = word?.trim()
            if (trimmed.isNullOrEmpty()) return tx
            fun withInfo(s: String): String =
                if (s.endsWith(".")) s.dropLast(1) + ", information $trimmed."
                else "$s, information $trimmed"
            return tx.copy(
                displayText = withInfo(tx.displayText),
                spokenText = withInfo(tx.spokenText),
            )
        }
    }
}
