package com.h3consultingpartners.ifatccompanion.core.atc

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.phraseology.Phonetic
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyEngine
import kotlin.math.abs
import kotlin.math.max

/**
 * Generates deterministic pilot readbacks/responses. Because the app knows exactly what
 * the controller said (it generated it), readbacks are composed from the same context
 * rather than parsed from text.
 *
 * Ported from `IFATCCompanion/ATC/PilotResponseEngine.swift`.
 */
data class PilotResponseEngine(val engine: PhraseologyEngine) {

    private val icao: Boolean get() = engine.icao

    private fun pilot(display: String, spoken: String, facility: ATCFacility): ATCTransmission =
        ATCTransmission.create(
            sender = ATCTransmission.Sender.PILOT,
            facility = facility,
            displayText = display,
            spokenText = spoken,
        )

    /** A correct readback for the controller instruction associated with [state]. */
    fun readback(state: ATCState, c: ATCContext): ATCTransmission {
        val cs = c.callsign
        return when (state) {
            ATCState.CLEARANCE -> pilot(
                "Cleared to ${c.plan.destinationName}, climb via SID except maintain " +
                    "${engine.formatAltDisplay(c.initialClimbAltitude)}, squawk ${c.squawk}, ${cs.display}.",
                "Cleared to ${engine.spokenAirport(c.plan.destination)}, climb via SID except maintain " +
                    "${Phonetic.altitude(c.initialClimbAltitude, icao = icao)}, " +
                    "${Phonetic.squawk(c.squawk, icao = icao)}, ${cs.spoken}.",
                ATCFacility.CLEARANCE,
            )

            ATCState.PUSHBACK -> {
                // Ramp readback. Echo the tail/face direction when one was given.
                val dir = c.pushDirection.trim().lowercase()
                val word = if (c.rampProfile.rampType.usesFaceDirection) "face" else "tail"
                val tail = if (dir.isEmpty()) "" else ", $word $dir"
                val phrase = if (icao) "Push approved" else "Pushback approved"
                pilot("$phrase$tail, ${cs.display}.", "$phrase$tail, ${cs.spoken}.", ATCFacility.RAMP)
            }

            ATCState.ENGINE_START -> pilot(
                "Start approved, ${cs.display}.",
                "Start approved, ${cs.spoken}.",
                ATCFacility.RAMP,
            )

            ATCState.LINE_UP_WAIT -> pilot(
                "Runway ${c.runway}, line up and wait, ${cs.display}.",
                "Runway ${Phonetic.runway(c.runway, icao)}, line up and wait, ${cs.spoken}.",
                ATCFacility.TOWER,
            )

            ATCState.GROUND_TAXI, ATCState.PUSHBACK_TAXI -> {
                var display = "Taxi to runway ${c.runway} via ${c.taxiway}"
                var spoken = "Taxi to runway ${Phonetic.runway(c.runway, icao)} via " +
                    Phonetic.spellToken(c.taxiway, icao)
                val crossing = c.crossingRunway
                if (!crossing.isNullOrEmpty()) {
                    display += ", cross runway ${Phonetic.runwayPairDisplay(crossing)}"
                    spoken += ", cross runway ${Phonetic.runwayPairSpoken(crossing, icao)}"
                }
                pilot("$display, ${cs.display}.", "$spoken, ${cs.spoken}.", ATCFacility.GROUND)
            }

            ATCState.TOWER_DEPARTURE -> pilot(
                "Runway ${c.runway}, cleared for takeoff, ${cs.display}.",
                "Runway ${Phonetic.runway(c.runway, icao)}, cleared for takeoff, ${cs.spoken}.",
                ATCFacility.TOWER,
            )

            ATCState.INITIAL_CLIMB, ATCState.DEPARTURE -> {
                val alt = max(c.assignedAltitude, c.initialClimbAltitude)
                pilot(
                    "Climb and maintain ${engine.formatAltDisplay(alt)}, ${cs.display}.",
                    "Climb and maintain ${Phonetic.altitude(alt, icao = icao)}, ${cs.spoken}.",
                    ATCFacility.DEPARTURE,
                )
            }

            ATCState.CLIMB -> pilot(
                "Climb and maintain ${engine.formatAltDisplay(c.cruiseAltitude)}, ${cs.display}.",
                "Climb and maintain ${Phonetic.altitude(c.cruiseAltitude, icao = icao)}, ${cs.spoken}.",
                ATCFacility.CENTER,
            )

            ATCState.CRUISE, ATCState.CENTER -> pilot(
                "${cs.display}, maintaining ${engine.formatAltDisplay(c.cruiseAltitude)}.",
                "${cs.spoken}, maintaining ${Phonetic.altitude(c.cruiseAltitude, icao = icao)}.",
                ATCFacility.CENTER,
            )

            ATCState.DESCENT -> {
                val star = c.starProcedure
                if (star != null) {
                    pilot(
                        "Descend via the ${star.displayName} arrival, ${cs.display}.",
                        "Descend via the ${star.spokenName(icao)} arrival, ${cs.spoken}.",
                        ATCFacility.CENTER,
                    )
                } else {
                    val alt = ATCStateMachine.descentTargetAltitude(c)
                    pilot(
                        "Descend and maintain ${engine.formatAltDisplay(alt)}, ${cs.display}.",
                        "Descend and maintain ${Phonetic.altitude(alt, icao = icao)}, ${cs.spoken}.",
                        ATCFacility.CENTER,
                    )
                }
            }

            ATCState.APPROACH -> {
                // Echo the altitude Approach actually assigned (plan intercept, else the
                // elevation-aware default) so the read-back matches the instruction.
                val alt = if (c.approachInterceptAltitude > 0) {
                    c.approachInterceptAltitude
                } else {
                    c.approachDefaultAltitude
                }
                val approach = c.approachProcedure
                if (approach != null) {
                    val rwy = approach.runway ?: c.runway
                    val typeD = approach.approachTypeDisplay ?: "approach"
                    val typeS = approach.approachTypeSpoken ?: "approach"
                    pilot(
                        "Down to ${engine.formatAltDisplay(alt)}, expecting the $typeD runway $rwy, ${cs.display}.",
                        "Down to ${Phonetic.altitude(alt, icao = icao)}, expecting the $typeS runway " +
                            "${Phonetic.runway(rwy, icao)}, ${cs.spoken}.",
                        ATCFacility.APPROACH,
                    )
                } else {
                    pilot(
                        "Down to ${engine.formatAltDisplay(alt)}, expecting " +
                            "${c.approachName.ifEmpty { "ILS" }} runway ${c.runway}, ${cs.display}.",
                        "Down to ${Phonetic.altitude(alt, icao = icao)}, expecting " +
                            "${c.approachName.ifEmpty { "I L S" }} runway " +
                            "${Phonetic.runway(c.runway, icao)}, ${cs.spoken}.",
                        ATCFacility.APPROACH,
                    )
                }
            }

            ATCState.FINAL -> {
                val approach = c.approachProcedure
                if (approach != null) {
                    val rwy = approach.runway ?: c.runway
                    val typeD = approach.approachTypeDisplay ?: "approach"
                    val typeS = approach.approachTypeSpoken ?: "approach"
                    pilot(
                        "Cleared the $typeD runway $rwy, ${cs.display}.",
                        "Cleared the $typeS runway ${Phonetic.runway(rwy, icao)}, ${cs.spoken}.",
                        ATCFacility.APPROACH,
                    )
                } else {
                    pilot(
                        "Cleared ${c.approachName.ifEmpty { "ILS" }} runway ${c.runway}, ${cs.display}.",
                        "Cleared ${c.approachName.ifEmpty { "I L S" }} runway " +
                            "${Phonetic.runway(c.runway, icao)}, ${cs.spoken}.",
                        ATCFacility.APPROACH,
                    )
                }
            }

            ATCState.LANDING -> pilot(
                "Runway ${c.runway}, cleared to land, ${cs.display}.",
                "Runway ${Phonetic.runway(c.runway, icao)}, cleared to land, ${cs.spoken}.",
                ATCFacility.TOWER,
            )

            ATCState.RUNWAY_EXIT -> pilot(
                "Exiting the runway, contact Ground, ${cs.display}.",
                "Exiting the runway, contact Ground, ${cs.spoken}.",
                ATCFacility.TOWER,
            )

            ATCState.GROUND_ARRIVAL -> {
                val gate = c.gate.trim()
                val destD = if (gate.isEmpty()) "parking" else "gate $gate"
                val destS = if (gate.isEmpty()) "parking" else "gate ${Phonetic.spellToken(gate, icao)}"
                pilot(
                    "Taxi to $destD via ${c.parkingTaxiway}, ${cs.display}.",
                    "Taxi to $destS via ${Phonetic.spellToken(c.parkingTaxiway, icao)}, ${cs.spoken}.",
                    ATCFacility.GROUND,
                )
            }

            else -> pilot("${cs.display}.", "${cs.spoken}.", state.facility)
        }
    }

    /** A simple "Wilco" / acknowledgement when a full readback isn't required. */
    fun wilco(c: ATCContext, facility: ATCFacility): ATCTransmission = pilot(
        "Wilco, ${c.callsign.display}.",
        "Wilco, ${c.callsign.spoken}.",
        facility,
    )

    /**
     * A courtesy acknowledgement for an informational reply that carries no instruction
     * to comply with — a ride report or a destination weather read-out. "Roger"
     * (received), not "Wilco" (will comply).
     */
    fun roger(c: ATCContext, facility: ATCFacility): ATCTransmission = pilot(
        "Roger, thank you, ${c.callsign.display}.",
        "Roger, thank you, ${c.callsign.spoken}.",
        facility,
    )

    /** Pilot says "say again". */
    fun sayAgain(c: ATCContext, facility: ATCFacility): ATCTransmission = pilot(
        "Say again for ${c.callsign.display}.",
        "Say again for ${c.callsign.spoken}.",
        facility,
    )

    /** Pilot declines (Unable). */
    fun unable(c: ATCContext, facility: ATCFacility): ATCTransmission = pilot(
        "Unable, ${c.callsign.display}.",
        "Unable, ${c.callsign.spoken}.",
        facility,
    )

    // region Pilot requests (pilot-initiated transmissions)

    fun requestClearance(c: ATCContext): ATCTransmission {
        val dest = if (c.plan.destination.isEmpty()) "destination" else c.plan.destinationName
        val destSpoken = engine.spokenAirport(c.plan.destination)
        return pilot(
            "Clearance, ${c.callsign.display}, request IFR clearance to $dest.",
            "Clearance, ${c.callsign.spoken}, request IFR clearance to $destSpoken.",
            ATCFacility.CLEARANCE,
        )
    }

    fun requestPushback(c: ATCContext): ATCTransmission {
        // Pushback is a Ramp (local/company) request, not FAA Ground ATC.
        val gate = c.gate.trim()
        val at = if (gate.isEmpty()) "" else " at $gate"
        val atSpoken = if (gate.isEmpty()) "" else " at ${Phonetic.spellToken(gate, icao)}"
        return pilot(
            "Ramp, ${c.callsign.display}$at, ready to push.",
            "Ramp, ${c.callsign.spoken}$atSpoken, ready to push.",
            ATCFacility.RAMP,
        )
    }

    fun requestEngineStart(c: ATCContext): ATCTransmission = pilot(
        "Ramp, ${c.callsign.display}, request engine start.",
        "Ramp, ${c.callsign.spoken}, request engine start.",
        ATCFacility.RAMP,
    )

    fun requestTaxi(c: ATCContext): ATCTransmission = pilot(
        "Ground, ${c.callsign.display}, request taxi.",
        "Ground, ${c.callsign.spoken}, request taxi.",
        ATCFacility.GROUND,
    )

    fun readyForDeparture(c: ATCContext): ATCTransmission = pilot(
        "Tower, ${c.callsign.display}, holding short runway ${c.runway}, ready for departure.",
        "Tower, ${c.callsign.spoken}, holding short runway ${Phonetic.runway(c.runway, icao)}, " +
            "ready for departure.",
        ATCFacility.TOWER,
    )

    fun requestTakeoff(c: ATCContext): ATCTransmission = pilot(
        "${c.callsign.display}, ready for departure.",
        "${c.callsign.spoken}, ready for departure.",
        ATCFacility.TOWER,
    )

    fun requestHigher(c: ATCContext, target: Int): ATCTransmission = pilot(
        "${c.callsign.display}, request ${engine.formatAltDisplay(target)}.",
        "${c.callsign.spoken}, request ${Phonetic.altitude(target, icao = icao)}.",
        ATCFacility.CENTER,
    )

    fun requestLower(c: ATCContext, target: Int): ATCTransmission = pilot(
        "${c.callsign.display}, request descent to ${engine.formatAltDisplay(target)}.",
        "${c.callsign.spoken}, request descent to ${Phonetic.altitude(target, icao = icao)}.",
        ATCFacility.CENTER,
    )

    fun requestVectors(c: ATCContext): ATCTransmission = pilot(
        "${c.callsign.display}, request vectors for the approach.",
        "${c.callsign.spoken}, request vectors for the approach.",
        ATCFacility.APPROACH,
    )

    fun requestApproach(c: ATCContext): ATCTransmission {
        // Prefer the parsed procedure (approach *type* + runway) so the runway is named
        // exactly once — "request the ILS runway 01R approach" — rather than echoing a
        // display name that already contains "RWY 01R" and then repeating the runway
        // ("the ILS RWY 01R runway 01R approach").
        val approach = c.approachProcedure
        if (approach != null) {
            val rwy = approach.runway ?: c.runway
            val typeD = approach.approachTypeDisplay ?: "approach"
            val typeS = approach.approachTypeSpoken ?: "approach"
            return pilot(
                "${c.callsign.display}, request the $typeD runway $rwy approach.",
                "${c.callsign.spoken}, request the $typeS runway ${Phonetic.runway(rwy, icao)} approach.",
                ATCFacility.APPROACH,
            )
        }
        val app = c.approachName.ifEmpty { "ILS" }
        return pilot(
            "${c.callsign.display}, request the $app runway ${c.runway} approach.",
            "${c.callsign.spoken}, request the ${c.approachName.ifEmpty { "I L S" }} runway " +
                "${Phonetic.runway(c.runway, icao)} approach.",
            ATCFacility.APPROACH,
        )
    }

    /**
     * Pilot's go-around / missed-approach call to Tower, issued when breaking off the
     * approach. Tower answers with the pattern instructions (crosswind vector, climb,
     * left/right traffic for the same runway, then back to Approach).
     */
    fun goAround(c: ATCContext): ATCTransmission = pilot(
        "Tower, ${c.callsign.display}, going around.",
        "Tower, ${c.callsign.spoken}, going around.",
        ATCFacility.TOWER,
    )

    fun requestRideReports(c: ATCContext): ATCTransmission = pilot(
        "${c.callsign.display}, any ride reports along our route?",
        "${c.callsign.spoken}, any ride reports along our route?",
        ATCFacility.CENTER,
    )

    fun requestWeather(c: ATCContext, airport: String): ATCTransmission = pilot(
        "${c.callsign.display}, request latest $airport weather.",
        "${c.callsign.spoken}, request latest ${Phonetic.spellToken(airport, icao)} weather.",
        ATCFacility.CENTER,
    )

    /**
     * Pilot check-in on a newly tuned frequency.
     *
     * Checking in with Tower while airborne means the pilot is inbound to land, so the
     * call reports the approach and runway ("inbound on the ILS runway 30L"), the way
     * IFATC expects — not an altitude.
     *
     * Otherwise, airborne, the pilot reports altitude the way a controller expects to hear
     * it: "with you at <altitude>" when level, or "with you at <current> for <target>"
     * while climbing or descending toward the assigned altitude. On the ground (Ramp,
     * Ground, Clearance, or Tower for departure) — or whenever we have no usable altitude
     * — a plain "checking in" is correct.
     *
     * @param currentAltitude live aircraft altitude (ft MSL), or null when unknown.
     * @param targetAltitude the altitude ATC has assigned (what the aircraft is
     *   climbing/descending toward); 0 when none.
     * @param onGround true when the aircraft is on the ground.
     */
    fun requestHandoff(
        c: ATCContext,
        facility: ATCFacility,
        currentAltitude: Int? = null,
        targetAltitude: Int = 0,
        onGround: Boolean = false,
    ): ATCTransmission {
        // Airborne check-in with Tower = inbound to land: report the approach and runway,
        // not an altitude. On the ground, Tower is a departure position, so fall through
        // to the plain "checking in" call-up below.
        if (facility == ATCFacility.TOWER && !onGround) {
            val rwy = c.approachProcedure?.runway ?: c.runway
            // Name the approach once. The parsed procedure's display/spoken forms omit the
            // article ("ILS"), so add "the"; the approach-name string already carries its
            // own ("the ILS").
            val approachD: String
            val approachS: String
            val typeDisplay = c.approachProcedure?.approachTypeDisplay
            val typeSpoken = c.approachProcedure?.approachTypeSpoken
            when {
                typeDisplay != null && typeSpoken != null -> {
                    approachD = "the $typeDisplay"
                    approachS = "the $typeSpoken"
                }

                c.approachName.isNotEmpty() -> {
                    approachD = c.approachName
                    approachS = c.approachName
                }

                else -> {
                    approachD = "the ILS"
                    approachS = "the I L S"
                }
            }
            if (rwy.isEmpty()) {
                return pilot(
                    "${engine.spokenName(facility)}, ${c.callsign.display}, inbound for landing.",
                    "${engine.spokenName(facility)}, ${c.callsign.spoken}, inbound for landing.",
                    facility,
                )
            }
            return pilot(
                "${engine.spokenName(facility)}, ${c.callsign.display}, inbound on $approachD runway $rwy.",
                "${engine.spokenName(facility)}, ${c.callsign.spoken}, inbound on $approachS runway " +
                    "${Phonetic.runway(rwy, icao)}.",
                facility,
            )
        }
        // Ground positions and any on-ground / altitude-unknown check-in: "checking in".
        val groundFacility = facility == ATCFacility.RAMP ||
            facility == ATCFacility.GROUND ||
            facility == ATCFacility.CLEARANCE
        if (onGround || groundFacility || currentAltitude == null) {
            return pilot(
                "${engine.spokenName(facility)}, ${c.callsign.display}, checking in.",
                "${engine.spokenName(facility)}, ${c.callsign.spoken}, checking in.",
                facility,
            )
        }
        // Climbing/descending toward a different assigned altitude: report both.
        if (targetAltitude > 0 && abs(targetAltitude - currentAltitude) >= CHECK_IN_ALTITUDE_TOLERANCE) {
            return pilot(
                "${engine.spokenName(facility)}, ${c.callsign.display}, with you at " +
                    "${engine.formatAltDisplay(currentAltitude)} for ${engine.formatAltDisplay(targetAltitude)}.",
                "${engine.spokenName(facility)}, ${c.callsign.spoken}, with you at " +
                    "${Phonetic.altitude(currentAltitude, icao = icao)} for " +
                    "${Phonetic.altitude(targetAltitude, icao = icao)}.",
                facility,
            )
        }
        // Level: report the current altitude.
        return pilot(
            "${engine.spokenName(facility)}, ${c.callsign.display}, with you at " +
                "${engine.formatAltDisplay(currentAltitude)}.",
            "${engine.spokenName(facility)}, ${c.callsign.spoken}, with you at " +
                "${Phonetic.altitude(currentAltitude, icao = icao)}.",
            facility,
        )
    }

    // endregion

    companion object {
        /**
         * Feet of difference from the assigned altitude below which the check-in reports a
         * single level rather than "at X for Y".
         */
        const val CHECK_IN_ALTITUDE_TOLERANCE = 200
    }
}
