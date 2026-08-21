package com.h3consultingpartners.ifatccompanion.core.weather.deviation

import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.phraseology.Phonetic
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyEngine
import java.util.Locale
import kotlin.math.max

/**
 * Deterministic phraseology for the simulated weather-deviation flow. No AI —
 * every line is a pure function of its inputs, mirroring `PhraseologyEngine` /
 * [RideReportEngine]. Radar-derived weather is always spoken as "precipitation";
 * "turbulence" and "convective weather" are only used when the source supports
 * them (SIGMET/PIREP/CWA/G-AIRMET). This is simulated ATC for training and
 * entertainment only.
 *
 * Ported from `IFATCCompanion/Weather/WeatherDeviationPhraseology.swift`; every
 * phrase is copied verbatim.
 */
class WeatherDeviationPhraseology(val engine: PhraseologyEngine) {

    private val icao: Boolean get() = engine.icao

    private fun center(
        display: String,
        spoken: String,
        facility: ATCFacility = ATCFacility.CENTER,
    ): ATCTransmission = ATCTransmission.create(
        sender = ATCTransmission.Sender.ATC,
        facility = facility,
        displayText = display,
        spokenText = spoken,
    )

    private fun pilot(
        display: String,
        spoken: String,
        facility: ATCFacility = ATCFacility.CENTER,
    ): ATCTransmission = ATCTransmission.create(
        sender = ATCTransmission.Sender.PILOT,
        facility = facility,
        displayText = display,
        spokenText = spoken,
    )

    // MARK: - ATC advisories

    /**
     * Advisory for a radar-precipitation conflict. Voices intensity, clock
     * position(s), distance, and movement — degrading gracefully to "movement
     * unknown" / "intensity unknown" when those are not known. Radar-derived, so
     * always "precipitation", never "turbulence".
     */
    fun radarAdvisory(
        cs: PhraseologyEngine.Callsign,
        conflict: RouteWeatherConflict,
        facility: ATCFacility = ATCFacility.CENTER,
    ): ATCTransmission {
        val clockD = clockPhrase(conflict, spoken = false)
        val clockS = clockPhrase(conflict, spoken = true)
        val distD = distancePhrase(conflict.distanceAheadNM, spoken = false)
        val distS = distancePhrase(conflict.distanceAheadNM, spoken = true)

        val intensityKnown = conflict.severity != WeatherIntensity.UNKNOWN
        val lead = if (intensityKnown) {
            "area of ${conflict.severity.spokenPrecipitation}"
        } else {
            "precipitation area"
        }

        if (conflict.hazard.hasKnownMovement) {
            val moveD = movementPhrase(conflict.hazard, spoken = false)
            val moveS = movementPhrase(conflict.hazard, spoken = true)
            return center(
                "${cs.display}, $lead $clockD, $distD, $moveD. Say intentions.",
                "${cs.spoken}, $lead $clockS, $distS, $moveS. Say intentions.",
                facility = facility,
            )
        }
        if (!intensityKnown) {
            return center(
                "${cs.display}, precipitation area $clockD, $distD, intensity unknown. Say intentions.",
                "${cs.spoken}, precipitation area $clockS, $distS, intensity unknown. Say intentions.",
                facility = facility,
            )
        }
        return center(
            "${cs.display}, $lead $clockD, $distD, movement unknown. Say intentions.",
            "${cs.spoken}, $lead $clockS, $distS, movement unknown. Say intentions.",
            facility = facility,
        )
    }

    /**
     * Advisory for a convective SIGMET along the route when radar is unavailable.
     * "Convective weather" is used only because the advisory supports it.
     */
    fun sigmetConvectiveAdvisory(
        cs: PhraseologyEngine.Callsign,
        facility: ATCFacility = ATCFacility.CENTER,
    ): ATCTransmission = center(
        "${cs.display}, SIGMET indicates convective weather along your route ahead. Say intentions.",
        "${cs.spoken}, SIGMET indicates convective weather along your route ahead. Say intentions.",
        facility = facility,
    )

    /** Advisory for a non-convective SIGMET/advisory along the route. */
    fun sigmetAdvisory(
        cs: PhraseologyEngine.Callsign,
        hazardLabel: String,
        facility: ATCFacility = ATCFacility.CENTER,
    ): ATCTransmission = center(
        "${cs.display}, SIGMET indicates $hazardLabel along your route ahead. Say intentions.",
        "${cs.spoken}, SIGMET indicates $hazardLabel along your route ahead. Say intentions.",
        facility = facility,
    )

    /**
     * Advisory for a turbulence / icing SIGMET along the route. There is nothing to
     * laterally route around, so the controller frames it as an altitude decision —
     * smoother air for turbulence, or exiting the layer for icing — matching how ATC
     * actually handles these (facilitating a climb/descent, not a vector).
     */
    fun sigmetRideAdvisory(
        cs: PhraseologyEngine.Callsign,
        hazardLabel: String,
        icing: Boolean,
        facility: ATCFacility = ATCFacility.CENTER,
    ): ATCTransmission {
        val tail = if (icing) {
            "a climb or descent may exit the icing"
        } else {
            "smoother air may be available at a different altitude"
        }
        return center(
            "${cs.display}, SIGMET indicates $hazardLabel along your route ahead, $tail. Say intentions.",
            "${cs.spoken}, SIGMET indicates $hazardLabel along your route ahead, $tail. Say intentions.",
            facility = facility,
        )
    }

    /**
     * The suggested reroute's entry point fell behind the aircraft (flown past or missed),
     * so the deviation has been redrawn ahead of it. The controller advises the revised
     * deviation and how far ahead it now begins. Nothing is assigned, so the read-back is
     * the courtesy "Roger" — carried on the call itself so the Read Back button
     * acknowledges *this* advisory instead of re-deriving a stale state read-back.
     */
    fun deviationRedrawnAhead(
        cs: PhraseologyEngine.Callsign,
        distanceNM: Int,
        facility: ATCFacility = ATCFacility.CENTER,
    ): ATCTransmission {
        // A turn distance, like `expectDeviation` — rounded to fives, not tens.
        val milesD = distancePhrase(distanceNM.toDouble(), spoken = false, nearest = 5)
        val milesS = distancePhrase(distanceNM.toDouble(), spoken = true, nearest = 5)
        val tx = center(
            "${cs.display}, weather deviation updated, revised deviation now begins $milesD ahead.",
            "${cs.spoken}, weather deviation updated, revised deviation now begins $milesS ahead.",
            facility = facility,
        )
        return tx.copy(
            readback = ATCTransmission.Readback(
                displayText = "Roger, ${cs.display}.",
                spokenText = "Roger, ${cs.spoken}.",
                facility = facility,
            ),
        )
    }

    /** Outside NOAA radar coverage with no advisory data — do not invent weather. */
    fun noRadarNoAdvisory(
        cs: PhraseologyEngine.Callsign,
        facility: ATCFacility = ATCFacility.CENTER,
    ): ATCTransmission = center(
        "${cs.display}, radar precipitation is not available for this region. " +
            "No significant aviation weather advisories are available along your route at this time.",
        "${cs.spoken}, radar precipitation is not available for this region. " +
            "No significant aviation weather advisories are available along your route at this time.",
        facility = facility,
    )

    // MARK: - Pilot requests

    fun pilotRequestDeviation(
        cs: PhraseologyEngine.Callsign,
        direction: DeviationDirection,
        degrees: Int,
        facility: ATCFacility = ATCFacility.CENTER,
    ): ATCTransmission {
        val degD = degrees.toString()
        val degS = Phonetic.spellDigits(degD, icao)
        val f = engine.spokenName(facility)
        return pilot(
            "$f, ${cs.display} requests $degD degrees ${direction.word} for weather.",
            "$f, ${cs.spoken} requests $degS degrees ${direction.word} for weather.",
            facility = facility,
        )
    }

    fun pilotRequestDirectDeviation(
        cs: PhraseologyEngine.Callsign,
        direction: DeviationDirection,
        facility: ATCFacility = ATCFacility.CENTER,
    ): ATCTransmission {
        val f = engine.spokenName(facility)
        return pilot(
            "$f, ${cs.display} requests deviation ${direction.word} of course for weather.",
            "$f, ${cs.spoken} requests deviation ${direction.word} of course for weather.",
            facility = facility,
        )
    }

    fun pilotRequestVectors(
        cs: PhraseologyEngine.Callsign,
        facility: ATCFacility = ATCFacility.CENTER,
    ): ATCTransmission {
        val f = engine.spokenName(facility)
        return pilot(
            "$f, ${cs.display} requests vectors around weather.",
            "$f, ${cs.spoken} requests vectors around weather.",
            facility = facility,
        )
    }

    fun pilotRequestAltitude(
        cs: PhraseologyEngine.Callsign,
        higher: Boolean,
        facility: ATCFacility = ATCFacility.CENTER,
    ): ATCTransmission {
        val word = if (higher) "higher" else "lower"
        val f = engine.spokenName(facility)
        return pilot(
            "$f, ${cs.display} requests $word for weather.",
            "$f, ${cs.spoken} requests $word for weather.",
            facility = facility,
        )
    }

    fun pilotClearOfWeather(
        cs: PhraseologyEngine.Callsign,
        facility: ATCFacility = ATCFacility.CENTER,
    ): ATCTransmission = pilot(
        "${cs.display} is clear of weather.",
        "${cs.spoken} is clear of weather.",
        facility = facility,
    )

    // MARK: - ATC approvals

    /** Deviation approved with a downstream rejoin fix (enroute). */
    fun approvalWithRejoin(
        cs: PhraseologyEngine.Callsign,
        direction: DeviationDirection,
        degrees: Int?,
        maintainAltitude: Int,
        rejoinFix: String,
        facility: ATCFacility = ATCFacility.CENTER,
    ): ATCTransmission {
        val devD = deviationClause(direction, degrees, spoken = false)
        val devS = deviationClause(direction, degrees, spoken = true)
        val fixS = Phonetic.spellToken(rejoinFix, icao)
        val tx = center(
            "${cs.display}, $devD approved, maintain ${altDisplay(maintainAltitude)}, " +
                "when able proceed direct $rejoinFix and advise.",
            "${cs.spoken}, $devS approved, maintain ${altSpoken(maintainAltitude)}, " +
                "when able proceed direct $fixS and advise.",
            facility = facility,
        )
        return tx.copy(
            readback = ATCTransmission.Readback(
                displayText = "Maintain ${altDisplay(maintainAltitude)}, $devD, " +
                    "direct $rejoinFix when able, ${cs.display}.",
                spokenText = "Maintain ${altSpoken(maintainAltitude)}, $devS, " +
                    "direct $fixS when able, ${cs.spoken}.",
                facility = facility,
            ),
        )
    }

    /** Deviation approved with no suitable rejoin fix — advise clear of weather. */
    fun approvalNoRejoin(
        cs: PhraseologyEngine.Callsign,
        direction: DeviationDirection,
        degrees: Int?,
        maintainAltitude: Int,
        facility: ATCFacility = ATCFacility.CENTER,
    ): ATCTransmission {
        val devD = deviationClause(direction, degrees, spoken = false)
        val devS = deviationClause(direction, degrees, spoken = true)
        val tx = center(
            "${cs.display}, $devD approved, maintain ${altDisplay(maintainAltitude)}, advise clear of weather.",
            "${cs.spoken}, $devS approved, maintain ${altSpoken(maintainAltitude)}, advise clear of weather.",
            facility = facility,
        )
        return tx.copy(
            readback = ATCTransmission.Readback(
                displayText = "Maintain ${altDisplay(maintainAltitude)}, $devD, ${cs.display}.",
                spokenText = "Maintain ${altSpoken(maintainAltitude)}, $devS, ${cs.spoken}.",
                facility = facility,
            ),
        )
    }

    /**
     * Deviation approved but the turn is held: the mint line is drawn ahead, so the
     * controller has the pilot continue on course and expect the turn in [distanceNM]
     * miles. The beginning turn itself is issued (via [vectorApproval]) once the aircraft
     * reaches the turn-out point at the start of the mint line.
     */
    fun expectDeviation(
        cs: PhraseologyEngine.Callsign,
        direction: DeviationDirection,
        distanceNM: Int,
        maintainAltitude: Int,
        facility: ATCFacility = ATCFacility.CENTER,
    ): ATCTransmission {
        // Rounded to fives, and `distanceNM` arrives already rounded to fives — so the
        // number spoken here is the one the caller measured, not a second rounding of it.
        val milesD = distancePhrase(distanceNM.toDouble(), spoken = false, nearest = 5)
        val milesS = distancePhrase(distanceNM.toDouble(), spoken = true, nearest = 5)
        val tx = center(
            "${cs.display}, roger, deviation ${direction.word} of course approved, " +
                "maintain ${altDisplay(maintainAltitude)}, continue present heading, " +
                "expect the turn in $milesD for weather.",
            "${cs.spoken}, roger, deviation ${direction.word} of course approved, " +
                "maintain ${altSpoken(maintainAltitude)}, continue present heading, " +
                "expect the turn in $milesS for weather.",
            facility = facility,
        )
        return tx.copy(
            readback = ATCTransmission.Readback(
                displayText = "Deviation ${direction.word} approved, maintain ${altDisplay(maintainAltitude)}, " +
                    "continue present heading, ${cs.display}.",
                spokenText = "Deviation ${direction.word} approved, maintain ${altSpoken(maintainAltitude)}, " +
                    "continue present heading, ${cs.spoken}.",
                facility = facility,
            ),
        )
    }

    /** Vectors around precipitation. */
    fun vectorApproval(
        cs: PhraseologyEngine.Callsign,
        heading: Int,
        maintainAltitude: Int,
        facility: ATCFacility = ATCFacility.APPROACH,
    ): ATCTransmission {
        val tx = center(
            "${cs.display}, fly heading ${headingDisplay(heading)}, vectors around precipitation, " +
                "maintain ${altDisplay(maintainAltitude)}, advise clear of weather.",
            "${cs.spoken}, fly heading ${Phonetic.heading(heading, icao)}, vectors around precipitation, " +
                "maintain ${altSpoken(maintainAltitude)}, advise clear of weather.",
            facility = facility,
        )
        // Read back both the assigned heading and the maintain altitude.
        return tx.copy(
            readback = ATCTransmission.Readback(
                displayText = "Heading ${headingDisplay(heading)}, maintain ${altDisplay(maintainAltitude)}, " +
                    "${cs.display}.",
                spokenText = "Heading ${Phonetic.heading(heading, icao)}, maintain ${altSpoken(maintainAltitude)}, " +
                    "${cs.spoken}.",
                facility = facility,
            ),
        )
    }

    /**
     * The aircraft has drifted off the reroute it was cleared to fly, so the deviation was
     * re-planned from where it actually is and the controller re-vectors onto the re-anchored
     * line. Same assignment as [vectorApproval] — heading, maintain, advise clear of weather —
     * with the reason stated, the way a controller flags an aircraft off its assigned track.
     */
    fun offPathVector(
        cs: PhraseologyEngine.Callsign,
        heading: Int,
        maintainAltitude: Int,
        facility: ATCFacility = ATCFacility.CENTER,
    ): ATCTransmission {
        val tx = center(
            "${cs.display}, you appear to be off the assigned deviation, fly heading ${headingDisplay(heading)}, " +
                "vectors around precipitation, maintain ${altDisplay(maintainAltitude)}, advise clear of weather.",
            "${cs.spoken}, you appear to be off the assigned deviation, " +
                "fly heading ${Phonetic.heading(heading, icao)}, vectors around precipitation, " +
                "maintain ${altSpoken(maintainAltitude)}, advise clear of weather.",
            facility = facility,
        )
        return tx.copy(
            readback = ATCTransmission.Readback(
                displayText = "Heading ${headingDisplay(heading)}, maintain ${altDisplay(maintainAltitude)}, " +
                    "${cs.display}.",
                spokenText = "Heading ${Phonetic.heading(heading, icao)}, maintain ${altSpoken(maintainAltitude)}, " +
                    "${cs.spoken}.",
                facility = facility,
            ),
        )
    }

    /**
     * A turn in the deviation path. An **intermediate** turn keeps vectoring around
     * the precipitation (e.g. turning out onto the parallel leg of a side-hug); the
     * **final** turn intercepts and rejoins the filed route, naming the rejoin fix
     * when one is known. A side-hug line has two turns — an intermediate one out,
     * then the final one back down to the route. Read back the heading.
     */
    fun rejoinInterceptVector(
        cs: PhraseologyEngine.Callsign,
        heading: Int,
        rejoinFix: String?,
        finalTurn: Boolean = true,
        facility: ATCFacility = ATCFacility.APPROACH,
    ): ATCTransmission {
        val tailD: String
        val tailS: String
        if (finalTurn) {
            tailD = rejoinFix?.let { " to rejoin course direct $it" } ?: " to rejoin course"
            tailS = rejoinFix?.let { " to rejoin course direct ${Phonetic.spellToken(it, icao)}" }
                ?: " to rejoin course"
        } else {
            tailD = ", vectors around precipitation"
            tailS = ", vectors around precipitation"
        }
        val tx = center(
            "${cs.display}, fly heading ${headingDisplay(heading)}$tailD.",
            "${cs.spoken}, fly heading ${Phonetic.heading(heading, icao)}$tailS.",
            facility = facility,
        )
        return tx.copy(
            readback = ATCTransmission.Readback(
                displayText = "Heading ${headingDisplay(heading)}, ${cs.display}.",
                spokenText = "Heading ${Phonetic.heading(heading, icao)}, ${cs.spoken}.",
                facility = facility,
            ),
        )
    }

    /** Requested side unavailable — approve the other side. */
    fun unableSideApproval(
        cs: PhraseologyEngine.Callsign,
        requested: DeviationDirection,
        approved: DeviationDirection,
        degrees: Int?,
        maintainAltitude: Int?,
        facility: ATCFacility = ATCFacility.CENTER,
    ): ATCTransmission {
        val devD = deviationClause(approved, degrees, spoken = false)
        val devS = deviationClause(approved, degrees, spoken = true)
        val maintainD = maintainAltitude?.let { " maintain ${altDisplay(it)}," } ?: ""
        val maintainS = maintainAltitude?.let { " maintain ${altSpoken(it)}," } ?: ""
        val tx = center(
            "${cs.display}, unable ${requested.word} deviation due traffic, $devD approved," +
                "$maintainD advise clear of weather.",
            "${cs.spoken}, unable ${requested.word} deviation due traffic, $devS approved," +
                "$maintainS advise clear of weather.",
            facility = facility,
        )
        // Echo the approved side and the maintain altitude when one was assigned.
        val rbD = maintainAltitude?.let { "Maintain ${altDisplay(it)}, $devD, ${cs.display}." }
            ?: "${cap(devD)} approved, ${cs.display}."
        val rbS = maintainAltitude?.let { "Maintain ${altSpoken(it)}, $devS, ${cs.spoken}." }
            ?: "${cap(devS)} approved, ${cs.spoken}."
        return tx.copy(
            readback = ATCTransmission.Readback(displayText = rbD, spokenText = rbS, facility = facility),
        )
    }

    /**
     * On a STAR / in descent: preserve the altitude restriction with "maintain",
     * and set up the expected rejoin point on the arrival.
     */
    fun starDeviationApproval(
        cs: PhraseologyEngine.Callsign,
        direction: DeviationDirection,
        degrees: Int?,
        maintainAltitude: Int,
        starDisplay: String,
        starSpoken: String,
        rejoinFix: String,
        facility: ATCFacility = ATCFacility.CENTER,
    ): ATCTransmission {
        val devD = deviationClause(direction, degrees, spoken = false)
        val devS = deviationClause(direction, degrees, spoken = true)
        val fixS = Phonetic.spellToken(rejoinFix, icao)
        val tx = center(
            "${cs.display}, $devD approved, maintain ${altDisplay(maintainAltitude)}, " +
                "expect to rejoin the $starDisplay arrival at $rejoinFix.",
            "${cs.spoken}, $devS approved, maintain ${altSpoken(maintainAltitude)}, " +
                "expect to rejoin the $starSpoken arrival at $fixS.",
            facility = facility,
        )
        return tx.copy(
            readback = ATCTransmission.Readback(
                displayText = "Maintain ${altDisplay(maintainAltitude)}, $devD, " +
                    "rejoin the $starDisplay arrival at $rejoinFix, ${cs.display}.",
                spokenText = "Maintain ${altSpoken(maintainAltitude)}, $devS, " +
                    "rejoin the $starSpoken arrival at $fixS, ${cs.spoken}.",
                facility = facility,
            ),
        )
    }

    /** Rejoin the STAR once clear. */
    fun rejoinStar(
        cs: PhraseologyEngine.Callsign,
        rejoinFix: String,
        starDisplay: String,
        starSpoken: String,
        facility: ATCFacility = ATCFacility.CENTER,
    ): ATCTransmission {
        val fixS = Phonetic.spellToken(rejoinFix, icao)
        val tx = center(
            "${cs.display}, cleared direct $rejoinFix, then descend via the $starDisplay arrival.",
            "${cs.spoken}, cleared direct $fixS, then descend via the $starSpoken arrival.",
            facility = facility,
        )
        return tx.copy(
            readback = ATCTransmission.Readback(
                displayText = "Direct $rejoinFix, descend via the $starDisplay arrival, ${cs.display}.",
                spokenText = "Direct $fixS, descend via the $starSpoken arrival, ${cs.spoken}.",
                facility = facility,
            ),
        )
    }

    /**
     * The pilot asks for vectors while already flying a deviation, but the reroute
     * they're on is still clear of precipitation — the controller has them continue on
     * the current deviation rather than issuing new vectors. Radar-derived, so
     * "precipitation".
     */
    fun continueCurrentDeviation(
        cs: PhraseologyEngine.Callsign,
        facility: ATCFacility = ATCFacility.CENTER,
    ): ATCTransmission {
        val tx = center(
            "${cs.display}, no new precipitation observed on your deviation, continue present deviation, " +
                "advise clear of weather.",
            "${cs.spoken}, no new precipitation observed on your deviation, continue present deviation, " +
                "advise clear of weather.",
            facility = facility,
        )
        return tx.copy(
            readback = ATCTransmission.Readback(
                displayText = "Continue present deviation, advise clear of weather, ${cs.display}.",
                spokenText = "Continue present deviation, advise clear of weather, ${cs.spoken}.",
                facility = facility,
            ),
        )
    }

    /**
     * An approved deviation whose beginning turn was still held ahead can no longer be
     * flown: the turn-out is behind the aircraft and no revised line solves from where it
     * actually is. The controller **cancels the deviation clearance** — the pilot was told
     * to continue on course and expect a turn, so the clearance has to be withdrawn out
     * loud rather than silently forgotten — and invites a fresh request, since the weather
     * itself may still be ahead.
     */
    fun deviationCancelled(
        cs: PhraseologyEngine.Callsign,
        facility: ATCFacility = ATCFacility.CENTER,
    ): ATCTransmission {
        val tx = center(
            "${cs.display}, weather deviation cancelled, resume own navigation, advise if you need to deviate.",
            "${cs.spoken}, weather deviation cancelled, resume own navigation, advise if you need to deviate.",
            facility = facility,
        )
        return tx.copy(
            readback = ATCTransmission.Readback(
                displayText = "Resume own navigation, ${cs.display}.",
                spokenText = "Resume own navigation, ${cs.spoken}.",
                facility = facility,
            ),
        )
    }

    /** Clear of weather — proceed direct the rejoin fix, resume own navigation. */
    fun clearOfWeatherResume(
        cs: PhraseologyEngine.Callsign,
        rejoinFix: String?,
        nearRoute: Boolean,
        facility: ATCFacility = ATCFacility.CENTER,
    ): ATCTransmission {
        if (nearRoute || rejoinFix == null) {
            val tx = center(
                "${cs.display}, resume own navigation.",
                "${cs.spoken}, resume own navigation.",
                facility = facility,
            )
            return tx.copy(
                readback = ATCTransmission.Readback(
                    displayText = "Resume own navigation, ${cs.display}.",
                    spokenText = "Resume own navigation, ${cs.spoken}.",
                    facility = facility,
                ),
            )
        }
        val fixS = Phonetic.spellToken(rejoinFix, icao)
        val tx = center(
            "${cs.display}, proceed direct $rejoinFix, resume own navigation.",
            "${cs.spoken}, proceed direct $fixS, resume own navigation.",
            facility = facility,
        )
        // Echo the direct fix and "resume own navigation" — the navigation change,
        // not just an acknowledgement.
        return tx.copy(
            readback = ATCTransmission.Readback(
                displayText = "Direct $rejoinFix, resume own navigation, ${cs.display}.",
                spokenText = "Direct $fixS, resume own navigation, ${cs.spoken}.",
                facility = facility,
            ),
        )
    }

    // MARK: - Formatting helpers

    /**
     * "deviation two zero degrees right" or, when degrees are uncertain,
     * "deviation right of course".
     */
    private fun deviationClause(direction: DeviationDirection, degrees: Int?, spoken: Boolean): String {
        if (degrees == null || degrees <= 0) return "deviation ${direction.word} of course"
        return if (spoken) {
            "deviation ${Phonetic.spellDigits(degrees.toString(), icao)} degrees ${direction.word}"
        } else {
            "deviation $degrees degrees ${direction.word}"
        }
    }

    /** Clock phrase: "twelve o'clock" or "between eleven o'clock and two o'clock". */
    private fun clockPhrase(conflict: RouteWeatherConflict, spoken: Boolean): String {
        if (conflict.leftClock == conflict.rightClock) return clock(conflict.centerClock, spoken)
        return "between ${clock(conflict.leftClock, spoken)} and ${clock(conflict.rightClock, spoken)}"
    }

    private fun clock(n: Int, spoken: Boolean): String =
        if (spoken) "${clockWords[n] ?: n.toString()} o'clock" else "$n o'clock"

    /**
     * Spoken distance, rounded to [nearest] miles. Weather is described in tens (a cell's
     * distance is never that precise), but a **turn** the pilot is about to fly is rounded
     * to fives: a turn-out 13 NM ahead announced as "20 miles" reads as a different turn
     * from the one the advisory just described, and sends the pilot past it waiting.
     */
    private fun distancePhrase(distance: Double, spoken: Boolean, nearest: Int = 10): String {
        val step = max(1, nearest)
        val rounded = max(0, roundHalfAwayFromZero(distance / step).toInt() * step)
        if (spoken) return "${Phonetic.spellDigits(rounded.toString(), icao)} miles"
        return "$rounded miles"
    }

    private fun movementPhrase(hazard: WeatherHazard, spoken: Boolean): String {
        val dir = Geo.cardinal(hazard.movementDirectionDegrees ?: 0.0)
        val spd = roundHalfAwayFromZero(hazard.movementSpeedKnots ?: 0.0).toInt()
        if (spoken) return "moving $dir at ${Phonetic.spellDigits(spd.toString(), icao)} knots"
        return "moving $dir at $spd knots"
    }

    private fun altDisplay(feet: Int): String = engine.formatAltDisplay(feet)

    private fun altSpoken(feet: Int): String = Phonetic.altitude(feet, icao = icao)

    private fun headingDisplay(deg: Int): String =
        String.format(Locale.US, "%03d", ((deg % 360) + 360) % 360)

    /**
     * Capitalize the first character so a read-back that leads with a reused
     * controller clause ("deviation …") reads as a sentence ("Deviation …").
     */
    private fun cap(s: String): String =
        if (s.isEmpty()) s else s.substring(0, 1).uppercase() + s.substring(1)

    companion object {
        val clockWords: Map<Int, String> = mapOf(
            1 to "one", 2 to "two", 3 to "three", 4 to "four", 5 to "five", 6 to "six",
            7 to "seven", 8 to "eight", 9 to "niner", 10 to "one zero", 11 to "one one", 12 to "twelve",
        )
    }
}
