package com.h3consultingpartners.ifatccompanion.core.phraseology

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import java.util.Locale

/**
 * Deterministic, template-based phraseology for the **Ramp / Apron / Company
 * Ramp** facility and the optional **Ground Crew / Interphone** channel.
 *
 * IMPORTANT — this is *simulated local/non-FAA procedure*, not FAA ATC:
 *  - Ramp may approve pushback, coordinate engine start, move aircraft in the
 *    non-movement area (alleys, spots), hold for traffic, and hand off to Ground.
 *  - Ramp must NEVER issue takeoff, landing, runway-crossing, IFR route,
 *    altitude, heading, SID, STAR, or approach instructions, and must never
 *    authorize runway entry/crossing. Those belong to FAA ATC only.
 *  - Ramp uses "push approved", "taxi via the alley", "proceed to spot",
 *    "hold position", "give way", "continue", and "monitor ramp" — never
 *    "cleared to taxi" or "cleared for pushback".
 *
 * Outputs are pure functions of their inputs; callsign/digit pronunciation is
 * borrowed from [PhraseologyEngine] so the FAA/ICAO pack and digit style apply.
 *
 * Ported from `IFATCCompanion/Phraseology/RampPhraseologyEngine.swift`. The Swift
 * `pushbackApproved` takes a whole `RampProfile` and reads exactly one thing off it
 * (`rampType.usesFaceDirection`); since `RampProfile` belongs to the ATC package this
 * port takes that Boolean directly. Its default `false` is what `RampProfile.generic`
 * (a `.companyRamp`) yields, so the default call is unchanged.
 */
data class RampPhraseologyEngine(val engine: PhraseologyEngine) {

    private val icao: Boolean get() = engine.icao

    private fun ramp(display: String, spoken: String): ATCTransmission = ATCTransmission.create(
        sender = ATCTransmission.Sender.ATC,
        facility = ATCFacility.RAMP,
        displayText = display,
        spokenText = spoken,
    )

    private fun pilot(display: String, spoken: String): ATCTransmission = ATCTransmission.create(
        sender = ATCTransmission.Sender.PILOT,
        facility = ATCFacility.RAMP,
        displayText = display,
        spokenText = spoken,
    )

    private fun system(display: String, spoken: String): ATCTransmission = ATCTransmission.create(
        sender = ATCTransmission.Sender.SYSTEM,
        facility = ATCFacility.RAMP,
        displayText = display,
        spokenText = spoken,
    )

    private fun spotPhrase(s: String): Pair<String, String> {
        val t = s.trim()
        if (t.isEmpty()) return "" to ""
        return "spot $t" to "spot ${Phonetic.spellToken(t, icao)}"
    }

    // MARK: - Departure ramp (controller side)

    /**
     * Pushback approval. With a known tail/face direction:
     * "push approved, tail west"; unknown direction falls back to
     * "push approved, advise ready to taxi". Apron style uses "face".
     */
    fun pushbackApproved(
        cs: PhraseologyEngine.Callsign,
        direction: String,
        usesFaceDirection: Boolean = false,
    ): ATCTransmission {
        val dir = direction.trim().lowercase()
        if (dir.isEmpty()) {
            return ramp(
                "${cs.display}, pushback approved, advise ready to taxi.",
                "${cs.spoken}, pushback approved, advise ready to taxi.",
            )
        }
        val word = if (usesFaceDirection) "face" else "tail"
        return ramp(
            "${cs.display}, pushback approved, $word $dir.",
            "${cs.spoken}, pushback approved, $word $dir.",
        )
    }

    /** Hold position for ramp/alley traffic (no runway/movement-area authority). */
    fun holdPosition(
        cs: PhraseologyEngine.Callsign,
        reason: String = "traffic entering the alley",
    ): ATCTransmission = ramp(
        "${cs.display}, hold position, $reason.",
        "${cs.spoken}, hold position, $reason.",
    )

    /** Engine-start coordination (company/ramp). Distinct from any FAA clearance. */
    fun startApproved(cs: PhraseologyEngine.Callsign): ATCTransmission = ramp(
        "${cs.display}, start approved.",
        "${cs.spoken}, start approved.",
    )

    /**
     * Ramp taxi to a spot via the alley (non-movement area). Uses "taxi via",
     * never "cleared to taxi". Unknown spot → conservative movement-area boundary.
     */
    fun taxiToSpot(
        cs: PhraseologyEngine.Callsign,
        spot: String,
        alley: String = "the alley",
    ): ATCTransmission {
        val s = spotPhrase(spot)
        if (s.first.isEmpty()) {
            return ramp(
                "${cs.display}, taxi via $alley, monitor ramp.",
                "${cs.spoken}, taxi via $alley, monitor ramp.",
            )
        }
        return ramp(
            "${cs.display}, taxi via $alley to ${s.first}.",
            "${cs.spoken}, taxi via $alley to ${s.second}.",
        )
    }

    /** Continue / proceed within the ramp (e.g. after a hold or give-way). */
    fun proceed(cs: PhraseologyEngine.Callsign, to: String): ATCTransmission = ramp(
        "${cs.display}, continue to $to.",
        "${cs.spoken}, continue to $to.",
    )

    /** Give way to crossing/entering ramp traffic. */
    fun giveWay(cs: PhraseologyEngine.Callsign, to: String): ATCTransmission = ramp(
        "${cs.display}, give way to $to.",
        "${cs.spoken}, give way to $to.",
    )

    /**
     * Hand off to Ground at the spot / movement-area boundary. This is the only
     * transition out of Ramp; Ground (FAA ATC) controls the movement area after.
     */
    fun contactGround(
        cs: PhraseologyEngine.Callsign,
        groundFrequency: Double,
        spot: String,
    ): ATCTransmission {
        val s = spotPhrase(spot)
        val freqD = String.format(Locale.US, "%.3f", groundFrequency)
        val freqS = Phonetic.frequency(groundFrequency, icao)
        // Compose the matching pilot read-back so the Read Back button echoes the
        // hand-off (the Ground frequency / movement-area boundary) rather than a
        // read-back re-derived from the stale conversational state (which lags at
        // engine-start and would read back "start approved").
        if (s.first.isEmpty()) {
            return ramp(
                "${cs.display}, proceed to the movement-area boundary, contact Ground $freqD.",
                "${cs.spoken}, proceed to the movement-area boundary, contact Ground $freqS.",
            ).copy(
                readback = ATCTransmission.Readback(
                    displayText = "Proceed to the movement-area boundary, contact Ground $freqD, ${cs.display}.",
                    spokenText = "Proceed to the movement-area boundary, contact Ground $freqS, ${cs.spoken}.",
                    facility = ATCFacility.RAMP,
                ),
            )
        }
        return ramp(
            "${cs.display}, contact Ground $freqD at ${s.first}.",
            "${cs.spoken}, contact Ground $freqS at ${s.second}.",
        ).copy(
            readback = ATCTransmission.Readback(
                displayText = "Contact Ground $freqD at ${s.first}, ${cs.display}.",
                spokenText = "Contact Ground $freqS at ${s.second}, ${cs.spoken}.",
                facility = ATCFacility.RAMP,
            ),
        )
    }

    // MARK: - Arrival ramp (controller side)

    /** Arrival ramp entry — proceed to the gate via the ramp/alley. Never "cleared". */
    fun proceedToGate(
        cs: PhraseologyEngine.Callsign,
        gate: String,
        via: String = "the inner alley",
    ): ATCTransmission {
        val g = gate.trim()
        val dest = if (g.isEmpty()) "the gate" else "gate $g"
        val destSpoken = if (g.isEmpty()) "the gate" else "gate ${Phonetic.spellToken(g, icao)}"
        // Compose the matching read-back so the Read Back button echoes the ramp
        // routing instead of a read-back re-derived from the stale `groundArrival`
        // state (which would read back the earlier Ground "taxi to gate via …").
        return ramp(
            "${cs.display}, proceed to $dest via $via.",
            "${cs.spoken}, proceed to $destSpoken via $via.",
        ).copy(
            readback = ATCTransmission.Readback(
                displayText = "Proceed to $dest via $via, ${cs.display}.",
                spokenText = "Proceed to $destSpoken via $via, ${cs.spoken}.",
                facility = ATCFacility.RAMP,
            ),
        )
    }

    /** Gate occupied — hold short of the alley until it opens. */
    fun gateOccupied(cs: PhraseologyEngine.Callsign, gate: String): ATCTransmission {
        val g = gate.trim()
        val gd = if (g.isEmpty()) "the gate" else "gate $g"
        val gs = if (g.isEmpty()) "the gate" else "gate ${Phonetic.spellToken(g, icao)}"
        return ramp(
            "${cs.display}, $gd is occupied, hold short of the alley.",
            "${cs.spoken}, $gs is occupied, hold short of the alley.",
        )
    }

    /** Final block-in — monitor ramp to the gate (marshaller/VDGS takes over). */
    fun monitorRampToGate(cs: PhraseologyEngine.Callsign): ATCTransmission = ramp(
        "${cs.display}, monitor ramp to the gate.",
        "${cs.spoken}, monitor ramp to the gate.",
    ).copy(
        readback = ATCTransmission.Readback(
            displayText = "Monitor ramp to the gate, ${cs.display}.",
            spokenText = "Monitor ramp to the gate, ${cs.spoken}.",
            facility = ATCFacility.RAMP,
        ),
    )

    // MARK: - Pilot side (ramp readbacks / requests)

    fun requestPush(
        cs: PhraseologyEngine.Callsign,
        gate: String,
        andStart: Boolean,
    ): ATCTransmission {
        val g = gate.trim()
        val at = if (g.isEmpty()) "at the gate" else "at $g"
        val atSpoken = if (g.isEmpty()) "at the gate" else "at ${Phonetic.spellToken(g, icao)}"
        val req = if (andStart) "request push and start" else "ready to push"
        return pilot(
            "Ramp, ${cs.display} $at, $req.",
            "Ramp, ${cs.spoken} $atSpoken, $req.",
        )
    }

    fun pushComplete(cs: PhraseologyEngine.Callsign): ATCTransmission = pilot(
        "Ramp, ${cs.display}, push complete, ready to taxi.",
        "Ramp, ${cs.spoken}, push complete, ready to taxi.",
    )

    fun arrivalInbound(cs: PhraseologyEngine.Callsign, gate: String): ATCTransmission {
        val g = gate.trim()
        val inb = if (g.isEmpty()) "inbound to the gate" else "inbound $g"
        val inbSpoken = if (g.isEmpty()) "inbound to the gate" else "inbound ${Phonetic.spellToken(g, icao)}"
        return pilot(
            "Ramp, ${cs.display}, $inb.",
            "Ramp, ${cs.spoken}, $inbSpoken.",
        )
    }

    /**
     * Generic ramp readback echoing the controller instruction (e.g.
     * "Pushback approved, tail west, United five niner eight").
     */
    fun readback(
        instruction: String,
        spokenInstruction: String,
        cs: PhraseologyEngine.Callsign,
    ): ATCTransmission = pilot("$instruction, ${cs.display}.", "$spokenInstruction, ${cs.spoken}.")

    // MARK: - Ground Crew / Interphone (non-radio, private text-only)

    /**
     * The headset interphone exchange during pushback. These are crew comms, not
     * radio, and never go out over the air. Returned as SYSTEM transmissions.
     */
    fun interphoneBrakesQuery(): ATCTransmission =
        system("Ground crew: Brakes set?", "Ground crew. Brakes set?")

    fun interphoneDisconnect(): ATCTransmission = system(
        "Ground crew: Towbar disconnected, bypass pin removed, hand signal on the left. Have a good flight.",
        "Ground crew. Towbar disconnected, bypass pin removed, hand signal on the left. Have a good flight.",
    )

    /**
     * Advisory shown when ramp control is disabled / non-movement-area is company
     * controlled. Not FAA ATC; informs the pilot to continue and call Ground.
     */
    fun nonMovementAdvisory(): ATCTransmission = system(
        "Ramp movement is non-movement-area / company controlled at many airports. Continue when ready and contact Ground before entering the movement area.",
        "Ramp movement is non movement area or company controlled at many airports. Continue when ready and contact Ground before entering the movement area.",
    )
}
