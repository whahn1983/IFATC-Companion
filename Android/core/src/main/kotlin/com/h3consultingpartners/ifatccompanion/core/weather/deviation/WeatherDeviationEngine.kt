package com.h3consultingpartners.ifatccompanion.core.weather.deviation

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.phraseology.Phonetic
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyEngine

/**
 * Deterministic coordinator for the simulated weather-deviation flow. It decides
 * which advisory applies, walks the [WeatherDeviationState] lifecycle, and emits
 * the matching controller/pilot transmissions via [WeatherDeviationPhraseology].
 * It never forces a deviation — it recommends and simulates ATC. No AI, no I/O.
 *
 * Ported from `IFATCCompanion/Weather/WeatherDeviationEngine.swift`. The Swift takes
 * a `WeatherDeviationContext` by value and returns a mutated copy; every step here
 * therefore starts from `context.copy()` so the caller's context is never mutated.
 */
class WeatherDeviationEngine(val phraseology: WeatherDeviationPhraseology) {

    val engine: PhraseologyEngine get() = phraseology.engine

    /** What weather situation the advisory should describe. */
    sealed interface Situation {
        /** A radar precipitation conflict along the route. */
        data class RadarConflict(val conflict: RouteWeatherConflict) : Situation

        /** A SIGMET along the route (outside radar coverage or radar off). */
        data class Sigmet(val label: String, val convective: Boolean) : Situation

        /**
         * A turbulence / icing SIGMET along the route. There is nothing to laterally
         * route around, so the advisory recommends an altitude change (smoother air,
         * or out of the icing) rather than a deviation.
         */
        data class RideSigmet(val label: String, val icing: Boolean) : Situation

        /** Radar unavailable and no advisory data — do not invent precipitation. */
        data object NoRadarNoAdvisory : Situation
    }

    /**
     * Parameters the concrete approval calls need, supplied by the coordinator from the
     * flight plan / assigned altitude / telemetry.
     */
    data class Inputs(
        var maintainAltitude: Int,
        var heading: Int,
        var onSTAR: Boolean = false,
        var starDisplay: String = "",
        var starSpoken: String = "",
        var nearRoute: Boolean = false,
        /** The requested side is unavailable (traffic) — approve the other side. */
        var unableRequestedSide: Boolean = false,
    )

    /**
     * The output of a step: an optional pilot line, the controller reply(ies), and
     * the updated deviation context.
     */
    data class Result(
        val pilot: ATCTransmission?,
        val atc: List<ATCTransmission>,
        val context: WeatherDeviationContext,
    )

    // MARK: - Advisory

    /**
     * Issue the appropriate advisory for a detected situation and move to
     * awaiting-pilot-intentions (or the terminal radar-unavailable state).
     */
    fun issueAdvisory(
        cs: PhraseologyEngine.Callsign,
        situation: Situation,
        context: WeatherDeviationContext,
        facility: ATCFacility,
    ): Result {
        val ctx = context.copy()
        val tx: ATCTransmission
        when (situation) {
            is Situation.RadarConflict -> {
                val conflict = situation.conflict
                tx = phraseology.radarAdvisory(cs = cs, conflict = conflict, facility = facility)
                ctx.state = WeatherDeviationState.AWAITING_PILOT_INTENTIONS
                ctx.activeHazardID = conflict.hazard.id
                ctx.rejoinFix = conflict.rejoinFix?.name
                ctx.originalRouteSegment = conflict.originalSegment
                ctx.requestedDeviationDirection = conflict.recommendedDirection
            }
            is Situation.Sigmet -> {
                tx = if (situation.convective) {
                    phraseology.sigmetConvectiveAdvisory(cs = cs, facility = facility)
                } else {
                    phraseology.sigmetAdvisory(cs = cs, hazardLabel = situation.label, facility = facility)
                }
                ctx.state = WeatherDeviationState.AWAITING_PILOT_INTENTIONS
            }
            is Situation.RideSigmet -> {
                tx = phraseology.sigmetRideAdvisory(
                    cs = cs,
                    hazardLabel = situation.label,
                    icing = situation.icing,
                    facility = facility,
                )
                ctx.state = WeatherDeviationState.AWAITING_PILOT_INTENTIONS
            }
            is Situation.NoRadarNoAdvisory -> {
                tx = phraseology.noRadarNoAdvisory(cs = cs, facility = facility)
                ctx.state = WeatherDeviationState.RADAR_UNAVAILABLE_FOR_REGION
            }
        }
        ctx.lastATCWeatherCall = tx.displayText
        return Result(pilot = null, atc = listOf(tx), context = ctx)
    }

    /**
     * The recommended reroute's entry point fell behind the aircraft — it was flown past or
     * missed — so the deviation was redrawn ahead of the aircraft. The controller advises the
     * revised deviation and how far ahead it now begins.
     *
     * The revised deviation is the pilot's to activate, so the call **opens the decision**:
     * the lifecycle moves to awaiting-intentions, which puts the response card and its
     * request buttons (deviate left/right, vectors, higher/lower) on screen with the call.
     * Two states are left exactly as they were: a pilot already deciding (the card is up, so
     * a pending decision — and the advisory still to come as the new turn closes — stands),
     * and a pilot already flying an approved deviation (there is nothing to activate).
     * [conflict] seeds the context from the redrawn line the way [issueAdvisory] does, so a
     * deviation requested off this call names the redrawn line's rejoin fix.
     */
    fun advisePathRedrawn(
        cs: PhraseologyEngine.Callsign,
        distanceNM: Int,
        conflict: RouteWeatherConflict? = null,
        context: WeatherDeviationContext,
        facility: ATCFacility,
    ): Result {
        val ctx = context.copy()
        val tx = phraseology.deviationRedrawnAhead(cs = cs, distanceNM = distanceNM, facility = facility)
        if (!ctx.state.isCommittedDeviation && !ctx.state.isAwaitingPilotDecision) {
            ctx.state = WeatherDeviationState.AWAITING_PILOT_INTENTIONS
            if (conflict != null) {
                ctx.activeHazardID = conflict.hazard.id
                ctx.rejoinFix = conflict.rejoinFix?.name
                ctx.originalRouteSegment = conflict.originalSegment
                ctx.requestedDeviationDirection = conflict.recommendedDirection
            }
        }
        ctx.lastATCWeatherCall = tx.displayText
        return Result(pilot = null, atc = listOf(tx), context = ctx)
    }

    // MARK: - Deviation request → approval

    /**
     * Pilot requests a left/right deviation; controller approves (with a rejoin
     * fix when one is available, else "advise clear of weather"). On a STAR the
     * altitude restriction is preserved with "maintain" and the rejoin is framed
     * as rejoining the arrival.
     */
    fun requestDeviation(
        cs: PhraseologyEngine.Callsign,
        conflict: RouteWeatherConflict?,
        requested: DeviationDirection,
        inputs: Inputs,
        context: WeatherDeviationContext,
        facility: ATCFacility,
    ): Result {
        val ctx = context.copy()
        val approved = if (inputs.unableRequestedSide) requested.opposite else requested
        val degrees = conflict?.recommendedDeviationDegrees
        val rejoin = conflict?.rejoinFix?.name ?: context.rejoinFix

        val pilotTx = phraseology.pilotRequestDeviation(
            cs = cs,
            direction = requested,
            degrees = degrees ?: 20,
            facility = facility,
        )

        val approval: ATCTransmission = if (inputs.unableRequestedSide) {
            phraseology.unableSideApproval(
                cs = cs,
                requested = requested,
                approved = approved,
                degrees = degrees,
                maintainAltitude = inputs.maintainAltitude,
                facility = facility,
            )
        } else if (inputs.onSTAR && rejoin != null) {
            phraseology.starDeviationApproval(
                cs = cs,
                direction = approved,
                degrees = degrees,
                maintainAltitude = inputs.maintainAltitude,
                starDisplay = inputs.starDisplay,
                starSpoken = inputs.starSpoken,
                rejoinFix = rejoin,
                facility = facility,
            )
        } else if (rejoin != null) {
            phraseology.approvalWithRejoin(
                cs = cs,
                direction = approved,
                degrees = degrees,
                maintainAltitude = inputs.maintainAltitude,
                rejoinFix = rejoin,
                facility = facility,
            )
        } else {
            phraseology.approvalNoRejoin(
                cs = cs,
                direction = approved,
                degrees = degrees,
                maintainAltitude = inputs.maintainAltitude,
                facility = facility,
            )
        }

        ctx.state = WeatherDeviationState.DEVIATION_APPROVED
        ctx.requestedDeviationDirection = requested
        ctx.approvedDeviationDegrees = degrees
        ctx.maintainAltitude = inputs.maintainAltitude
        ctx.rejoinFix = rejoin
        ctx.assignedHeading = null
        ctx.lastATCWeatherCall = approval.displayText
        return Result(pilot = pilotTx, atc = listOf(approval), context = ctx)
    }

    /**
     * Pilot requests a deviation while the reroute is still drawn ahead (the aircraft
     * has not yet reached the turn-out point at the start of the mint line). The
     * controller approves the deviation but **holds the turn**: the pilot continues on
     * course and is told to expect the turn in [distanceNM] miles. The beginning turn is
     * issued later by [beginDeviationTurn] once the aircraft reaches the turn-out.
     */
    fun deferDeviation(
        cs: PhraseologyEngine.Callsign,
        conflict: RouteWeatherConflict?,
        direction: DeviationDirection,
        distanceNM: Int,
        inputs: Inputs,
        context: WeatherDeviationContext,
        facility: ATCFacility,
    ): Result {
        val ctx = context.copy()
        val pilotTx = phraseology.pilotRequestDirectDeviation(cs = cs, direction = direction, facility = facility)
        val atc = phraseology.expectDeviation(
            cs = cs,
            direction = direction,
            distanceNM = distanceNM,
            maintainAltitude = inputs.maintainAltitude,
            facility = facility,
        )
        ctx.state = WeatherDeviationState.DEVIATION_APPROVED
        ctx.requestedDeviationDirection = direction
        ctx.approvedDeviationDegrees = conflict?.recommendedDeviationDegrees
        ctx.maintainAltitude = inputs.maintainAltitude
        ctx.rejoinFix = conflict?.rejoinFix?.name ?: context.rejoinFix
        ctx.assignedHeading = null
        ctx.lastATCWeatherCall = atc.displayText
        return Result(pilot = pilotTx, atc = listOf(atc), context = ctx)
    }

    /**
     * Issue the held beginning turn once the aircraft reaches the turn-out point at the
     * start of the drawn mint line. Vectors the aircraft onto the reroute; the interior
     * turns then follow automatically ([rejoinTurn]). No pilot line — the controller
     * initiates it as the aircraft arrives at the turn.
     */
    fun beginDeviationTurn(
        cs: PhraseologyEngine.Callsign,
        heading: Int,
        maintainAltitude: Int,
        context: WeatherDeviationContext,
        facility: ATCFacility,
    ): Result {
        val ctx = context.copy()
        val atc = phraseology.vectorApproval(
            cs = cs,
            heading = heading,
            maintainAltitude = maintainAltitude,
            facility = facility,
        )
        ctx.state = WeatherDeviationState.VECTORING_AROUND_WEATHER
        ctx.assignedHeading = heading
        ctx.maintainAltitude = maintainAltitude
        ctx.deviationStartLatitude = null
        ctx.deviationStartLongitude = null
        ctx.deviationStartHeading = null
        ctx.deviationStartLegBearing = null
        ctx.lastATCWeatherCall = atc.displayText
        return Result(pilot = null, atc = listOf(atc), context = ctx)
    }

    /**
     * The aircraft has drifted off the reroute it was cleared to fly (wind, a late roll-in, a
     * wide turn), so the deviation was re-planned from where it actually is. The controller
     * re-vectors it onto the re-anchored line. Controller-initiated — no pilot call — and the
     * armed turn is cleared, since it indexed the geometry just replaced (the caller re-arms
     * against the new line).
     */
    fun revectorOffPath(
        cs: PhraseologyEngine.Callsign,
        heading: Int,
        maintainAltitude: Int,
        context: WeatherDeviationContext,
        facility: ATCFacility,
    ): Result {
        val ctx = context.copy()
        val tx = phraseology.offPathVector(
            cs = cs,
            heading = heading,
            maintainAltitude = maintainAltitude,
            facility = facility,
        )
        ctx.state = WeatherDeviationState.VECTORING_AROUND_WEATHER
        ctx.assignedHeading = heading
        ctx.maintainAltitude = maintainAltitude
        ctx.pendingTurnIndex = null
        ctx.pendingRejoinHeading = null
        ctx.vectorApexLatitude = null
        ctx.vectorApexLongitude = null
        ctx.vectorLegBearing = null
        ctx.lastATCWeatherCall = tx.displayText
        return Result(pilot = null, atc = listOf(tx), context = ctx)
    }

    /** Pilot requests vectors; controller assigns a heading around precipitation. */
    fun requestVectors(
        cs: PhraseologyEngine.Callsign,
        inputs: Inputs,
        context: WeatherDeviationContext,
        facility: ATCFacility,
    ): Result {
        val ctx = context.copy()
        val pilotTx = phraseology.pilotRequestVectors(cs = cs, facility = facility)
        val approval = phraseology.vectorApproval(
            cs = cs,
            heading = inputs.heading,
            maintainAltitude = inputs.maintainAltitude,
            facility = facility,
        )
        ctx.state = WeatherDeviationState.VECTORING_AROUND_WEATHER
        ctx.assignedHeading = inputs.heading
        ctx.maintainAltitude = inputs.maintainAltitude
        ctx.lastATCWeatherCall = approval.displayText
        return Result(pilot = pilotTx, atc = listOf(approval), context = ctx)
    }

    /**
     * At a turn in the deviation path the controller automatically turns the
     * aircraft to the next leg of the mint line. An intermediate turn keeps
     * vectoring around the weather (the parallel leg of a side-hug); the [finalTurn]
     * intercepts and rejoins the filed route. Keeps the vectoring state (the pilot
     * still advises clear of weather) and clears the armed turn — the caller re-arms
     * the next interior turn when the line has one.
     */
    fun rejoinTurn(
        cs: PhraseologyEngine.Callsign,
        heading: Int,
        rejoinFix: String?,
        finalTurn: Boolean = true,
        context: WeatherDeviationContext,
        facility: ATCFacility,
    ): Result {
        val ctx = context.copy()
        val tx = phraseology.rejoinInterceptVector(
            cs = cs,
            heading = heading,
            rejoinFix = rejoinFix,
            finalTurn = finalTurn,
            facility = facility,
        )
        ctx.assignedHeading = heading
        ctx.pendingTurnIndex = null
        ctx.pendingRejoinHeading = null
        ctx.vectorApexLatitude = null
        ctx.vectorApexLongitude = null
        ctx.vectorLegBearing = null
        ctx.lastATCWeatherCall = tx.displayText
        return Result(pilot = null, atc = listOf(tx), context = ctx)
    }

    /**
     * Pilot requests vectors while already flying a deviation, but the committed reroute
     * ahead is still clear — the controller has them continue on the current deviation.
     * No deviation state changes: the committed line and its armed turns are preserved,
     * so the pilot keeps flying the line they're on (only the last-call text is updated).
     */
    fun continueCurrentDeviation(
        cs: PhraseologyEngine.Callsign,
        context: WeatherDeviationContext,
        facility: ATCFacility,
    ): Result {
        val ctx = context.copy()
        val pilotTx = phraseology.pilotRequestVectors(cs = cs, facility = facility)
        val atc = phraseology.continueCurrentDeviation(cs = cs, facility = facility)
        ctx.lastATCWeatherCall = atc.displayText
        return Result(pilot = pilotTx, atc = listOf(atc), context = ctx)
    }

    /** Pilot requests higher/lower for weather; controller assigns the altitude. */
    fun requestAltitude(
        cs: PhraseologyEngine.Callsign,
        higher: Boolean,
        targetAltitude: Int,
        context: WeatherDeviationContext,
        facility: ATCFacility,
    ): Result {
        val ctx = context.copy()
        val pilotTx = phraseology.pilotRequestAltitude(cs = cs, higher = higher, facility = facility)
        val verb = if (higher) "climb and maintain" else "descend and maintain"
        val verbCap = if (higher) "Climb and maintain" else "Descend and maintain"
        var atc = ATCTransmission.create(
            sender = ATCTransmission.Sender.ATC,
            facility = facility,
            displayText = "${cs.display}, $verb ${engine.formatAltDisplay(targetAltitude)} for weather, " +
                "advise clear of weather.",
            spokenText = "${cs.spoken}, $verb ${Phonetic.altitude(targetAltitude, icao = engine.icao)} " +
                "for weather, advise clear of weather.",
        )
        atc = atc.copy(
            readback = ATCTransmission.Readback(
                displayText = "$verbCap ${engine.formatAltDisplay(targetAltitude)}, ${cs.display}.",
                spokenText = "$verbCap ${Phonetic.altitude(targetAltitude, icao = engine.icao)}, ${cs.spoken}.",
                facility = facility,
            ),
        )
        ctx.state = WeatherDeviationState.DEVIATING_AROUND_WEATHER
        ctx.maintainAltitude = targetAltitude
        ctx.lastATCWeatherCall = atc.displayText
        return Result(pilot = pilotTx, atc = listOf(atc), context = ctx)
    }

    // MARK: - Clear of weather

    /**
     * Pilot reports clear of weather; controller clears back to the filed route or
     * the downstream rejoin fix (or, on a STAR, rejoins the arrival).
     */
    fun reportClearOfWeather(
        cs: PhraseologyEngine.Callsign,
        inputs: Inputs,
        context: WeatherDeviationContext,
        facility: ATCFacility,
    ): Result {
        val ctx = context.copy()
        val pilotTx = phraseology.pilotClearOfWeather(cs = cs, facility = facility)
        val rejoin = context.rejoinFix
        val atc: ATCTransmission = if (inputs.onSTAR && rejoin != null) {
            phraseology.rejoinStar(
                cs = cs,
                rejoinFix = rejoin,
                starDisplay = inputs.starDisplay,
                starSpoken = inputs.starSpoken,
                facility = facility,
            )
        } else {
            phraseology.clearOfWeatherResume(
                cs = cs,
                rejoinFix = rejoin,
                nearRoute = inputs.nearRoute,
                facility = facility,
            )
        }
        ctx.state = WeatherDeviationState.RESUMED_OWN_NAVIGATION
        ctx.lastATCWeatherCall = atc.displayText
        return Result(pilot = pilotTx, atc = listOf(atc), context = ctx)
    }

    /**
     * The pilot holds an approved deviation whose beginning turn was still ahead, and that
     * turn can no longer be flown — the turn-out fell behind the aircraft and no revised
     * line solves from here. Cancel the clearance out loud: the pilot was told to continue
     * on course and expect a turn, so dropping it silently leaves them flying toward a turn
     * that will never be called. Controller-initiated — no pilot line.
     */
    fun cancelHeldDeviation(
        cs: PhraseologyEngine.Callsign,
        context: WeatherDeviationContext,
        facility: ATCFacility,
    ): Result {
        val ctx = context.copy()
        val tx = phraseology.deviationCancelled(cs = cs, facility = facility)
        ctx.state = WeatherDeviationState.RESUMED_OWN_NAVIGATION
        ctx.lastATCWeatherCall = tx.displayText
        return Result(pilot = null, atc = listOf(tx), context = ctx)
    }

    /**
     * The aircraft reached the rejoin end of the deviation without the pilot reporting
     * clear of weather. The controller automatically resumes own navigation and ends
     * the deviation — no pilot call, since the pilot did not initiate it.
     */
    fun autoResumeOwnNavigation(
        cs: PhraseologyEngine.Callsign,
        context: WeatherDeviationContext,
        facility: ATCFacility,
    ): Result {
        val ctx = context.copy()
        val atc = phraseology.clearOfWeatherResume(
            cs = cs,
            rejoinFix = null,
            nearRoute = true,
            facility = facility,
        )
        ctx.state = WeatherDeviationState.RESUMED_OWN_NAVIGATION
        ctx.lastATCWeatherCall = atc.displayText
        return Result(pilot = null, atc = listOf(atc), context = ctx)
    }
}
