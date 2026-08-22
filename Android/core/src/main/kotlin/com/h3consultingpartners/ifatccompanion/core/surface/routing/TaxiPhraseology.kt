package com.h3consultingpartners.ifatccompanion.core.surface.routing

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.phraseology.Phonetic
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyEngine

/**
 * Deterministic Ground taxi + runway-crossing phraseology derived from a calculated
 * [SurfaceTaxiRoute]. Wraps the existing [PhraseologyEngine] for callsign / phonetics
 * so the output flows through the same transcript/read-back machinery.
 *
 * Rules enforced here (also covered by tests):
 *  - never says "cleared to taxi";
 *  - always names the assigned runway and the ordered taxiway sequence;
 *  - always includes an explicit hold-short instruction;
 *  - never implies a runway crossing is included in the taxi clearance — crossings are
 *    issued as **separate** Ground clearances with their own read-back;
 *  - never says "cross all runways" or gives vague crossing authority;
 *  - never invents taxiway names (an empty sequence renders as "available taxiways");
 *  - a crossing read-back always contains the runway identifier;
 *  - low-confidence data downgrades to conservative language.
 *
 * Everything here is framed as simulated ATC.
 *
 * Ported from `IFATCCompanion/AirportSurface/TaxiPhraseology.swift`.
 */
data class TaxiPhraseology(val engine: PhraseologyEngine) {

    private val icao: Boolean get() = engine.icao

    // MARK: - Departure taxi clearance

    /**
     * Ground taxi clearance to the assigned departure runway from a calculated route.
     * Names the runway, the taxiway sequence, and an explicit hold-short. Crossings are
     * NOT authorized here.
     *
     * When the route crosses a runway, the clearance holds the pilot short of the **first**
     * runway crossing ([holdShortCrossing]) — the runway ahead they must await a separate
     * crossing clearance for — exactly as a real Ground controller phrases it ("taxi to
     * runway 36 via A, C, hold short runway 09"). With no crossing it holds short of the
     * assigned runway itself.
     */
    fun taxiClearance(
        cs: PhraseologyEngine.Callsign,
        route: SurfaceTaxiRoute,
        runway: String,
        holdShortCrossing: String? = null,
    ): ATCTransmission {
        val seq = sequenceText(route)
        val rwySpoken = Phonetic.runway(runway, icao)
        val holdRwy = holdShortCrossing?.takeIf { it.isNotEmpty() } ?: runway
        // Hold-short instructions name both directions of the physical runway
        // ("hold short runway 6R-24L" / "hold short runway six right two four left").
        val holdDisplay = Phonetic.runwayPairDisplay(holdRwy)
        val holdSpoken = Phonetic.runwayPairSpoken(holdRwy, icao)
        val display = "${cs.display}, taxi to runway $runway via ${seq.display}, hold short runway $holdDisplay."
        val spoken = "${cs.spoken}, taxi to runway $rwySpoken via ${seq.spoken}, hold short runway $holdSpoken."
        return ATCTransmission.create(
            sender = ATCTransmission.Sender.ATC,
            facility = ATCFacility.GROUND,
            displayText = display,
            spokenText = spoken,
            readback = ATCTransmission.Readback(
                displayText = "Taxi to runway $runway via ${seq.display}, hold short runway $holdDisplay, ${cs.display}.",
                spokenText = "Taxi to runway $rwySpoken via ${seq.spoken}, hold short runway $holdSpoken, ${cs.spoken}.",
                facility = ATCFacility.GROUND,
            ),
        )
    }

    /**
     * Conservative Ground instruction used when routing confidence is too low to issue
     * a detailed route. Names the runway but no specific taxiways, and holds short of
     * all runways.
     */
    fun lowConfidenceTaxi(cs: PhraseologyEngine.Callsign, runway: String): ATCTransmission {
        val rwySpoken = Phonetic.runway(runway, icao)
        val display = "${cs.display}, detailed taxi routing is unavailable. Taxi toward runway $runway, hold short of all runways, and continue using the simulator airport diagram."
        val spoken = "${cs.spoken}, detailed taxi routing is unavailable. Taxi toward runway $rwySpoken, hold short of all runways, and continue using the simulator airport diagram."
        return ATCTransmission.create(
            sender = ATCTransmission.Sender.ATC,
            facility = ATCFacility.GROUND,
            displayText = display,
            spokenText = spoken,
            readback = ATCTransmission.Readback(
                displayText = "Taxi toward runway $runway, hold short of all runways, ${cs.display}.",
                spokenText = "Taxi toward runway $rwySpoken, hold short of all runways, ${cs.spoken}.",
                facility = ATCFacility.GROUND,
            ),
        )
    }

    // MARK: - Arrival taxi-to-gate

    /**
     * Ground taxi-to-gate clearance from a calculated arrival route. When the route
     * crosses a runway it holds the pilot short of the first crossing ([holdShortCrossing])
     * — the crossing is then authorized separately, just as on departure.
     */
    fun arrivalTaxi(
        cs: PhraseologyEngine.Callsign,
        route: SurfaceTaxiRoute,
        gate: String,
        holdShortCrossing: String? = null,
    ): ATCTransmission {
        val seq = sequenceText(route)
        val g = gate.trim { it == ' ' || it == '\t' }
        val destDisplay = if (g.isEmpty()) "parking" else "gate $g"
        val destSpoken = if (g.isEmpty()) "parking" else "gate ${Phonetic.spellToken(g, icao)}"
        val holdRwy = holdShortCrossing?.takeIf { it.isNotEmpty() }
        // Hold-short instructions name both directions of the physical runway.
        val holdDisplay = holdRwy?.let { ", hold short runway ${Phonetic.runwayPairDisplay(it)}" } ?: ""
        val holdSpoken = holdRwy?.let { ", hold short runway ${Phonetic.runwayPairSpoken(it, icao)}" } ?: ""
        val display = "${cs.display}, taxi to $destDisplay via ${seq.display}$holdDisplay."
        val spoken = "${cs.spoken}, taxi to $destSpoken via ${seq.spoken}$holdSpoken."
        return ATCTransmission.create(
            sender = ATCTransmission.Sender.ATC,
            facility = ATCFacility.GROUND,
            displayText = display,
            spokenText = spoken,
            readback = ATCTransmission.Readback(
                displayText = "Taxi to $destDisplay via ${seq.display}$holdDisplay, ${cs.display}.",
                spokenText = "Taxi to $destSpoken via ${seq.spoken}$holdSpoken, ${cs.spoken}.",
                facility = ATCFacility.GROUND,
            ),
        )
    }

    // MARK: - Runway crossing

    /**
     * A **separate** Ground runway-crossing clearance. Includes the runway id, the
     * taxiway/intersection name when known, and an optional continuation. The read-back
     * contains the runway identifier.
     */
    fun crossingClearance(
        cs: PhraseologyEngine.Callsign,
        runwayIdent: String,
        atTaxiway: String? = null,
        continueVia: String? = null,
    ): ATCTransmission {
        // A crossing spans the whole physical runway, so it is named by both directions
        // ("cross runway 6R-24L" / "cross runway six right two four left").
        val rwyDisplay = Phonetic.runwayPairDisplay(runwayIdent)
        val rwySpoken = Phonetic.runwayPairSpoken(runwayIdent, icao)
        val atDisplay = atTaxiway?.takeIf { it.isNotEmpty() }?.let { " at $it" } ?: ""
        val atSpoken = atTaxiway?.takeIf { it.isNotEmpty() }?.let { " at ${Phonetic.spellToken(it, icao)}" } ?: ""
        val contDisplay = continueVia?.takeIf { it.isNotEmpty() }?.let { ", then continue on $it" } ?: ""
        val contSpoken = continueVia?.takeIf { it.isNotEmpty() }
            ?.let { ", then continue on ${Phonetic.spellToken(it, icao)}" } ?: ""
        val display = "${cs.display}, cross runway $rwyDisplay$atDisplay$contDisplay."
        val spoken = "${cs.spoken}, cross runway $rwySpoken$atSpoken$contSpoken."
        return ATCTransmission.create(
            sender = ATCTransmission.Sender.ATC,
            facility = ATCFacility.GROUND,
            displayText = display,
            spokenText = spoken,
            readback = ATCTransmission.Readback(
                displayText = "Cross runway $rwyDisplay, ${cs.display}.",
                spokenText = "Cross runway $rwySpoken, ${cs.spoken}.",
                facility = ATCFacility.GROUND,
            ),
        )
    }

    /**
     * Ground hold-short instruction issued as the aircraft approaches a crossing before
     * it has been cleared.
     */
    fun holdShort(cs: PhraseologyEngine.Callsign, runwayIdent: String): ATCTransmission {
        // Name both directions of the physical runway being held short of.
        val rwyDisplay = Phonetic.runwayPairDisplay(runwayIdent)
        val rwySpoken = Phonetic.runwayPairSpoken(runwayIdent, icao)
        return ATCTransmission.create(
            sender = ATCTransmission.Sender.ATC,
            facility = ATCFacility.GROUND,
            displayText = "${cs.display}, hold short of runway $rwyDisplay.",
            spokenText = "${cs.spoken}, hold short of runway $rwySpoken.",
            readback = ATCTransmission.Readback(
                displayText = "Hold short runway $rwyDisplay, ${cs.display}.",
                spokenText = "Hold short runway $rwySpoken, ${cs.spoken}.",
                facility = ATCFacility.GROUND,
            ),
        )
    }

    /**
     * Continuation after a crossing is vacated — resume the remaining taxi route.
     *
     * The controller call names only the destination (no taxiways — the route/map is
     * already established), so the read-back echoes it verbatim ("Continue taxi to
     * runway 36, callsign"). Carrying an explicit read-back keeps the Read Back button
     * from falling back to the generic "taxi to runway X via <taxiway>" form, which would
     * invent a taxiway letter unrelated to the remaining route.
     */
    fun resumeTaxi(
        cs: PhraseologyEngine.Callsign,
        runway: String,
        isDeparture: Boolean,
        gate: String,
    ): ATCTransmission {
        val destDisplay: String
        val destSpoken: String
        if (isDeparture) {
            destDisplay = "runway $runway"
            destSpoken = "runway ${Phonetic.runway(runway, icao)}"
        } else {
            val g = gate.trim { it == ' ' || it == '\t' }
            destDisplay = if (g.isEmpty()) "parking" else "gate $g"
            destSpoken = if (g.isEmpty()) "parking" else "gate ${Phonetic.spellToken(g, icao)}"
        }
        return ATCTransmission.create(
            sender = ATCTransmission.Sender.ATC,
            facility = ATCFacility.GROUND,
            displayText = "${cs.display}, continue taxi to $destDisplay.",
            spokenText = "${cs.spoken}, continue taxi to $destSpoken.",
            readback = ATCTransmission.Readback(
                displayText = "Continue taxi to $destDisplay, ${cs.display}.",
                spokenText = "Continue taxi to $destSpoken, ${cs.spoken}.",
                facility = ATCFacility.GROUND,
            ),
        )
    }

    // MARK: - Unauthorized-entry warnings

    /**
     * Simulated hold-position warning (aircraft moving toward a runway before a
     * crossing clearance / read-back).
     */
    fun holdPositionWarning(cs: PhraseologyEngine.Callsign, runwayIdent: String): ATCTransmission {
        val rwyDisplay = Phonetic.runwayPairDisplay(runwayIdent)
        val rwySpoken = Phonetic.runwayPairSpoken(runwayIdent, icao)
        return ATCTransmission.create(
            sender = ATCTransmission.Sender.ATC,
            facility = ATCFacility.GROUND,
            displayText = "${cs.display}, hold position, hold short of runway $rwyDisplay.",
            spokenText = "${cs.spoken}, hold position, hold short of runway $rwySpoken.",
        )
    }

    /**
     * Simulated stop-immediately warning (aircraft already entering the runway corridor
     * without authorization).
     */
    fun stopWarning(cs: PhraseologyEngine.Callsign, runwayIdent: String): ATCTransmission {
        val rwyDisplay = Phonetic.runwayPairDisplay(runwayIdent)
        val rwySpoken = Phonetic.runwayPairSpoken(runwayIdent, icao)
        return ATCTransmission.create(
            sender = ATCTransmission.Sender.ATC,
            facility = ATCFacility.GROUND,
            displayText = "${cs.display}, stop immediately, you are entering runway $rwyDisplay.",
            spokenText = "${cs.spoken}, stop immediately, you are entering runway $rwySpoken.",
        )
    }

    // MARK: - Helpers

    private data class SequenceText(val display: String, val spoken: String)

    private fun sequenceText(route: SurfaceTaxiRoute): SequenceText {
        val seq = route.taxiwaySequence.filter { it.isNotEmpty() }
        if (seq.isEmpty()) return SequenceText("available taxiways", "available taxiways")
        return SequenceText(
            seq.joinToString(", "),
            seq.joinToString(" ") { Phonetic.spellToken(it, icao) },
        )
    }
}
