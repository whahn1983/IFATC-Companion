package com.h3consultingpartners.ifatccompanion.core.phraseology

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * Builds deterministic, template-based ATC transmissions. No AI — every output
 * is a pure function of its inputs. Variation comes only from approved template
 * alternates selected deterministically.
 *
 * The engine honors two selectable phraseology packs ([PhraseologyMode]): FAA/US
 * and ICAO. The pack changes digit words ("tree/fower/fife"), the frequency
 * separator ("decimal" vs "point"), the altimeter/QNH convention, and a handful
 * of phrase forms (e.g. "taxi to holding point" vs "taxi to runway"). An optional
 * user [PhraseologyProfile] can override individual call templates and supply
 * custom airline call sets.
 *
 * Ported from `IFATCCompanion/Phraseology/PhraseologyEngine.swift`. The Swift is a
 * `struct` whose properties callers mutate in place; here it is a `data class`, so
 * the equivalent of `engine.profile = p` is `engine.copy(profile = p)`.
 */
data class PhraseologyEngine(
    val digitStyle: CallsignDigitStyle = CallsignDigitStyle.GROUPED,
    val mode: PhraseologyMode = PhraseologyMode.FAA,
    /** Optional user-defined overrides (templates + airline call sets). */
    val profile: PhraseologyProfile? = null,
    /**
     * Radio name of the enroute sector currently working the flight — "Houston
     * Center", "Fort Worth Center", "London Control". Center is the one facility
     * whose spoken name is not fixed: the flight is handed from one sector to the
     * next across the enroute leg, and every call that names the controller has to
     * follow. Null (the default) falls back to the generic "Center", which is what a
     * flight with no position fix — or with sector hand-offs switched off — gets.
     */
    val centerSectorName: String? = null,
) {

    /**
     * What a facility is called on the radio right now. Identical to
     * [ATCFacility.spokenName] for every facility except Center, which takes the name of
     * the sector currently working the flight when one is known.
     */
    fun spokenName(facility: ATCFacility): String {
        if (facility != ATCFacility.CENTER) return facility.spokenName
        val sector = (centerSectorName ?: "").trim()
        return if (sector.isEmpty()) facility.spokenName else sector
    }

    /** Convenience: whether the ICAO pack is selected. */
    val icao: Boolean get() = mode == PhraseologyMode.ICAO

    // MARK: - Callsign

    /** Spoken callsign, e.g. ("United", "598") -> "United five niner eight". */
    fun spokenCallsign(airline: String, flightNumber: String, fallback: String = ""): String {
        val airlineTrim = airline.trim()
        val numTrim = flightNumber.trim().filter { it.isDigit() }
        if (airlineTrim.isNotEmpty() && numTrim.isNotEmpty()) {
            return "${spokenAirline(airlineTrim)} ${spokenFlightNumber(numTrim)}"
        }
        val fb = fallback.trim()
        if (fb.isNotEmpty()) {
            // Mixed alphanumeric tail/callsign -> spell it out.
            return Phonetic.spellToken(fb, icao)
        }
        return "aircraft"
    }

    /** Display callsign for the transcript, e.g. "United 598" or the raw fallback. */
    fun displayCallsign(airline: String, flightNumber: String, fallback: String = ""): String {
        val airlineTrim = airline.trim()
        val numTrim = flightNumber.trim()
        if (airlineTrim.isNotEmpty() && numTrim.isNotEmpty()) {
            return "${displayAirline(airlineTrim)} $numTrim"
        }
        val fb = fallback.trim()
        return if (fb.isEmpty()) "Aircraft" else fb
    }

    /**
     * Spoken telephony name for an airline. A user profile may map an ICAO/IATA
     * designator or name to a custom radio name (e.g. "DLH" -> "Lufthansa"); a
     * built-in airline database covers the common carriers out of the box.
     */
    fun spokenAirline(airline: String): String {
        profile?.airlineCallName(airline)?.let { return it }
        AirlineDatabase.callName(airline)?.let { return it }
        return airline
    }

    /**
     * Friendly name shown in the transcript: resolves a designator (e.g. "UAL")
     * to its telephony name ("United"), otherwise leaves the text as entered.
     */
    fun displayAirline(airline: String): String {
        profile?.airlineCallName(airline)?.let { return it }
        AirlineDatabase.callName(airline)?.let { return it }
        return airline
    }

    fun spokenFlightNumber(digits: String): String = when (digitStyle) {
        CallsignDigitStyle.INDIVIDUAL -> Phonetic.spellDigits(digits, icao)
        CallsignDigitStyle.GROUPED -> groupedNumber(digits)
    }

    private fun groupedNumber(digits: String): String = when (digits.length) {
        4 -> {
            val a = digits.substring(0, 2).toIntOrNull() ?: 0
            val b = digits.substring(2, 4).toIntOrNull() ?: 0
            "${Phonetic.twoDigitGroup(a, icao)} ${groupTail(b)}"
        }
        3 -> {
            val first = Phonetic.digitMap(icao)[digits[0]] ?: ""
            val b = digits.substring(1, 3).toIntOrNull() ?: 0
            "$first ${groupTail(b)}"
        }
        2 -> Phonetic.twoDigitGroup(digits.toIntOrNull() ?: 0, icao)
        else -> Phonetic.spellDigits(digits, icao)
    }

    /** Trailing two-digit group: "00" -> "hundred", else natural English. */
    private fun groupTail(n: Int): String =
        if (n == 0) "hundred" else Phonetic.twoDigitGroup(n, icao)

    // MARK: - Builders (each returns an ATCTransmission)

    private fun tx(facility: ATCFacility, display: String, spoken: String): ATCTransmission =
        ATCTransmission.create(
            sender = ATCTransmission.Sender.ATC,
            facility = facility,
            displayText = display,
            spokenText = spoken,
        )

    /** The two parallel forms of the flight's callsign: transcript and speech. */
    data class Callsign(val display: String, val spoken: String)

    fun callsign(airline: String, flightNumber: String, fallback: String): Callsign = Callsign(
        display = displayCallsign(airline, flightNumber, fallback),
        spoken = spokenCallsign(airline, flightNumber, fallback),
    )

    /** Clearance Delivery — IFR clearance. */
    fun clearance(
        cs: Callsign,
        destination: String,
        cruise: Int,
        sid: String,
        initialAlt: Int,
        departureFreq: Double,
        squawk: String,
        sidProcedure: PhraseologyProcedure? = null,
    ): ATCTransmission {
        val destDisplay = if (destination.isEmpty()) "destination" else destination
        // Resolve the SID phrasing from a parsed procedure, raw text, or fall back.
        val sidDisplay: String
        val sidSpoken: String
        if (sidProcedure != null) {
            sidDisplay = "the ${sidProcedure.displayName} departure"
            sidSpoken = "the ${sidProcedure.spokenName(icao)} departure"
        } else if (sid.isEmpty()) {
            sidDisplay = "the filed route"
            sidSpoken = "the filed route"
        } else {
            sidDisplay = "the $sid departure"
            sidSpoken = "the " + Phonetic.spellToken(sid, icao) + " departure"
        }
        val template = profile?.template(PhraseologyTemplateKey.CLEARANCE)
        if (template != null) {
            val ph = placeholders(
                cs,
                mapOf(
                    "dest" to destDisplay, "destSpoken" to spokenAirport(destination),
                    "sid" to sidDisplay, "sidSpoken" to sidSpoken,
                    "initialAlt" to formatAltDisplay(initialAlt),
                    "initialAltSpoken" to Phonetic.altitude(initialAlt, icao = icao),
                    "cruise" to formatAltDisplay(cruise),
                    "cruiseSpoken" to Phonetic.altitude(cruise, icao = icao),
                    "depFreq" to String.format(Locale.US, "%.3f", departureFreq),
                    "depFreqSpoken" to Phonetic.frequency(departureFreq, icao),
                    "squawk" to squawk, "squawkSpoken" to Phonetic.squawk(squawk, icao),
                ),
            )
            return tx(
                ATCFacility.CLEARANCE,
                display = render(template.display, ph.display),
                spoken = render(template.spoken, ph.spoken),
            )
        }
        val display = "${cs.display}, cleared to $destDisplay via $sidDisplay, " +
            "climb via SID except maintain ${formatAltDisplay(initialAlt)}, " +
            "expect ${formatAltDisplay(cruise)} one zero minutes after departure, " +
            "departure frequency ${String.format(Locale.US, "%.3f", departureFreq)}, squawk $squawk."
        val spoken = "${cs.spoken}, cleared to ${spokenAirport(destination)} via $sidSpoken, " +
            "climb via SID except maintain ${Phonetic.altitude(initialAlt, icao = icao)}, " +
            "expect ${Phonetic.altitude(cruise, icao = icao)} one zero minutes after departure, " +
            "departure frequency ${Phonetic.frequency(departureFreq, icao)}, ${Phonetic.squawk(squawk, icao)}."
        return tx(ATCFacility.CLEARANCE, display = display, spoken = spoken)
    }

    /** Center/Approach — descend via a published STAR (arrival). */
    fun descendViaArrival(cs: Callsign, star: PhraseologyProcedure, altitude: Int): ATCTransmission {
        // The crossing restriction names the *second* fix on the arrival, when there is one.
        val fixClause = if (star.fixes.size > 1) " crossing ${star.fixes[1]}" else ""
        val fixClauseSpoken =
            if (star.fixes.size > 1) " crossing ${Phonetic.spellToken(star.fixes[1], icao)}" else ""
        return tx(
            ATCFacility.CENTER,
            display = "${cs.display}, descend via the ${star.displayName} arrival, maintain ${formatAltDisplay(altitude)}$fixClause.",
            spoken = "${cs.spoken}, descend via the ${star.spokenName(icao)} arrival, maintain ${Phonetic.altitude(altitude, icao = icao)}$fixClauseSpoken.",
        )
    }

    /** Approach — cleared a published approach procedure. */
    fun clearedApproach(
        cs: Callsign,
        procedure: PhraseologyProcedure,
        runway: String,
    ): ATCTransmission {
        val rwy = procedure.runway ?: runway
        return tx(
            ATCFacility.APPROACH,
            display = "${cs.display}, cleared ${procedure.displayName} approach.",
            spoken = "${cs.spoken}, cleared ${procedure.approachTypeSpoken ?: "approach"} runway ${Phonetic.runway(rwy, icao)} approach.",
        )
    }

    /** Ground — pushback approval. */
    fun pushbackApproved(cs: Callsign): ATCTransmission {
        // ICAO writes "push back"; FAA writes "pushback".
        val phrase = if (icao) "push back approved" else "pushback approved"
        return tx(
            ATCFacility.GROUND,
            display = "${cs.display}, $phrase.",
            spoken = "${cs.spoken}, $phrase.",
        )
    }

    /** Ground — engine start-up approval (ICAO "start-up", FAA "start up"). */
    fun startupApproved(cs: Callsign): ATCTransmission {
        val phrase = if (icao) "start-up approved" else "start up approved"
        return tx(
            ATCFacility.GROUND,
            display = "${cs.display}, $phrase.",
            spoken = "${cs.spoken}, $phrase.",
        )
    }

    /** Ground — taxi. */
    fun taxiToRunway(cs: Callsign, runway: String, via: String, crossing: String?): ATCTransmission {
        // A runway crossing names both directions of the physical runway
        // ("cross runway 6R-24L" / "cross runway six right two four left").
        val crossDisplay = crossing?.let {
            if (it.isEmpty()) "" else ", cross runway ${Phonetic.runwayPairDisplay(it)}"
        } ?: ""
        val crossSpoken = crossing?.let {
            if (it.isEmpty()) "" else ", cross runway ${Phonetic.runwayPairSpoken(it, icao)}"
        } ?: ""
        val template = profile?.template(PhraseologyTemplateKey.TAXI_TO_RUNWAY)
        if (template != null) {
            val ph = placeholders(
                cs,
                mapOf(
                    "runway" to runway, "runwaySpoken" to Phonetic.runway(runway, icao),
                    "via" to via, "viaSpoken" to Phonetic.spellToken(via, icao),
                    "crossing" to crossDisplay, "crossingSpoken" to crossSpoken,
                ),
            )
            return tx(
                ATCFacility.GROUND,
                display = render(template.display, ph.display),
                spoken = render(template.spoken, ph.spoken),
            )
        }
        // ICAO: "taxi to holding point runway X"; FAA: "taxi to runway X". The taxi
        // instruction ends by telling the pilot to call Tower when ready to depart.
        val lead = if (icao) "taxi to holding point runway" else "taxi to runway"
        val display = "${cs.display}, $lead $runway via $via$crossDisplay. Contact Tower when ready."
        val spoken = "${cs.spoken}, $lead ${Phonetic.runway(runway, icao)} via ${Phonetic.spellToken(via, icao)}$crossSpoken. Contact Tower when ready."
        return tx(ATCFacility.GROUND, display = display, spoken = spoken)
    }

    /** Tower — line up and wait. */
    fun lineUpAndWait(cs: Callsign, runway: String): ATCTransmission = tx(
        ATCFacility.TOWER,
        display = "${cs.display}, runway $runway, line up and wait.",
        spoken = "${cs.spoken}, runway ${Phonetic.runway(runway, icao)}, line up and wait.",
    )

    /**
     * Ground → Tower *monitor* hand-off, issued as the aircraft approaches the departure
     * runway (real-world "monitor Tower on …", the red sign short of the runway). Unlike a
     * "contact" hand-off the pilot switches and just monitors — no check-in required — so
     * the read-back is "monitor Tower on <freq>" and it tunes the radio to Tower.
     */
    fun monitorTower(cs: Callsign, frequency: Double): ATCTransmission {
        val freqD = String.format(Locale.US, "%.3f", frequency)
        val freqS = Phonetic.frequency(frequency, icao)
        return tx(
            ATCFacility.GROUND,
            display = "${cs.display}, monitor Tower on $freqD.",
            spoken = "${cs.spoken}, monitor Tower on $freqS.",
        ).copy(
            readback = ATCTransmission.Readback(
                displayText = "Monitor Tower on $freqD, ${cs.display}.",
                spokenText = "Monitor Tower on $freqS, ${cs.spoken}.",
                facility = ATCFacility.TOWER,
                tuneTo = ATCFacility.TOWER,
            ),
        )
    }

    /**
     * Tower — acknowledges a pilot who checks in while monitoring before departure. No
     * check-in is required after "monitor Tower"; if the pilot does call up (typically
     * well before the runway), Tower **only reports the sequence** — it does *not* issue
     * a takeoff clearance. The clearance still comes automatically once the aircraft is
     * lined up on the runway.
     */
    fun numberOneForTakeoff(cs: Callsign, runway: String): ATCTransmission = tx(
        ATCFacility.TOWER,
        display = "${cs.display}, roger, you're number one for departure, runway $runway.",
        spoken = "${cs.spoken}, roger, you're number one for departure, runway ${Phonetic.runway(runway, icao)}.",
    )

    /** Tower — cleared for takeoff. */
    fun clearedForTakeoff(cs: Callsign, runway: String, windDir: Int, windSpeed: Int): ATCTransmission {
        val template = profile?.template(PhraseologyTemplateKey.TAKEOFF)
        if (template != null) {
            val ph = placeholders(
                cs,
                mapOf(
                    "runway" to runway, "runwaySpoken" to Phonetic.runway(runway, icao),
                    "wind" to "${String.format(Locale.US, "%03d", windDir)} at $windSpeed",
                    "windSpoken" to Phonetic.wind(direction = windDir, speed = windSpeed, icao = icao),
                ),
            )
            return tx(
                ATCFacility.TOWER,
                display = render(template.display, ph.display),
                spoken = render(template.spoken, ph.spoken),
            )
        }
        // ICAO uses the hyphenated "cleared for take-off".
        val phrase = if (icao) "cleared for take-off" else "cleared for takeoff"
        return tx(
            ATCFacility.TOWER,
            display = "${cs.display}, wind ${String.format(Locale.US, "%03d", windDir)} at $windSpeed, runway $runway, $phrase.",
            spoken = "${cs.spoken}, ${Phonetic.wind(direction = windDir, speed = windSpeed, icao = icao)}, runway ${Phonetic.runway(runway, icao)}, $phrase.",
        )
    }

    /**
     * Tower — cleared for takeoff with departure instructions (initial heading +
     * climb). The heading is the bearing to the first fix / route intercept; when
     * it is within 10° of the runway heading we say "fly runway heading".
     *
     * [runwayIsKnown] gates that substitution. The runway's heading here is its ident × 10,
     * which is only a heading at all when the ident names a real runway. When nothing in the
     * plan named one, the app falls back to rounding the *wind direction* to the nearest
     * ten and calling that the runway — and comparing the departure vector against that
     * number asks whether the departure lies near the wind, which is not a question anyone
     * wants answered by discarding the turn. In that case the heading is always spoken: a
     * heading is never wrong, only wordier than "runway heading" would have been.
     */
    fun clearedForTakeoff(
        cs: Callsign,
        runway: String,
        windDir: Int,
        windSpeed: Int,
        departureHeading: Int,
        initialAltitude: Int,
        runwayIsKnown: Boolean = true,
    ): ATCTransmission {
        val phrase = if (icao) "cleared for take-off" else "cleared for takeoff"
        val rwyHeading = if (runwayIsKnown) runwayHeading(runway) else null
        // Inclusive 10° tolerance, in degrees.
        val aligned = rwyHeading?.let {
            angularDiff(departureHeading.toDouble(), it.toDouble()) <= 10
        } ?: false
        val hdgDisplay: String
        val hdgSpoken: String
        if (departureHeading <= 0 || aligned) {
            hdgDisplay = "fly runway heading"
            hdgSpoken = "fly runway heading"
        } else {
            hdgDisplay = "fly heading ${String.format(Locale.US, "%03d", departureHeading)}"
            hdgSpoken = "fly heading ${Phonetic.heading(departureHeading, icao)}"
        }
        val display = "${cs.display}, wind ${String.format(Locale.US, "%03d", windDir)} at $windSpeed, runway $runway, $phrase, $hdgDisplay, climb and maintain ${formatAltDisplay(initialAltitude)}."
        val spoken = "${cs.spoken}, ${Phonetic.wind(direction = windDir, speed = windSpeed, icao = icao)}, runway ${Phonetic.runway(runway, icao)}, $phrase, $hdgSpoken, climb and maintain ${Phonetic.altitude(initialAltitude, icao = icao)}."
        // Read back both the assigned heading and the climb altitude — both are
        // safety-critical. Drop the leading "fly " (4 characters) so the pilot echo reads
        // naturally ("runway heading" / "heading 090" rather than "fly runway heading").
        val hdgReadDisplay = if (hdgDisplay.startsWith("fly ")) hdgDisplay.drop(4) else hdgDisplay
        val hdgReadSpoken = if (hdgSpoken.startsWith("fly ")) hdgSpoken.drop(4) else hdgSpoken
        return tx(ATCFacility.TOWER, display = display, spoken = spoken).copy(
            readback = ATCTransmission.Readback(
                displayText = "Runway $runway, cleared for takeoff, $hdgReadDisplay, climb and maintain ${formatAltDisplay(initialAltitude)}, ${cs.display}.",
                spokenText = "Runway ${Phonetic.runway(runway, icao)}, cleared for takeoff, $hdgReadSpoken, climb and maintain ${Phonetic.altitude(initialAltitude, icao = icao)}, ${cs.spoken}.",
                facility = ATCFacility.TOWER,
            ),
        )
    }

    /** Departure — radar contact + climb. */
    fun radarContactClimb(cs: Callsign, altitude: Int): ATCTransmission = tx(
        ATCFacility.DEPARTURE,
        display = "${cs.display}, radar contact, climb and maintain ${formatAltDisplay(altitude)}.",
        spoken = "${cs.spoken}, radar contact, climb and maintain ${Phonetic.altitude(altitude, icao = icao)}.",
    )

    /** Departure — radar contact, climb to the TRACON ceiling, join the route. */
    fun departureClimb(cs: Callsign, altitude: Int, firstFix: String): ATCTransmission {
        val join = if (firstFix.isEmpty()) "resume own navigation" else "resume own navigation, direct $firstFix"
        val joinSpoken = if (firstFix.isEmpty()) {
            "resume own navigation"
        } else {
            "resume own navigation, direct ${Phonetic.spellToken(firstFix, icao)}"
        }
        return tx(
            ATCFacility.DEPARTURE,
            display = "${cs.display}, radar contact, climb and maintain ${formatAltDisplay(altitude)}, $join.",
            spoken = "${cs.spoken}, radar contact, climb and maintain ${Phonetic.altitude(altitude, icao = icao)}, $joinSpoken.",
        ).copy(
            // Echo "resume own navigation" (and the direct fix, when named) in the
            // read-back — the pilot must acknowledge the navigation change, not just the
            // climb.
            readback = ATCTransmission.Readback(
                displayText = "Climb and maintain ${formatAltDisplay(altitude)}, $join, ${cs.display}.",
                spokenText = "Climb and maintain ${Phonetic.altitude(altitude, icao = icao)}, $joinSpoken, ${cs.spoken}.",
                facility = ATCFacility.DEPARTURE,
            ),
        )
    }

    /** Center — climb. */
    fun climbMaintain(cs: Callsign, altitude: Int): ATCTransmission = tx(
        ATCFacility.CENTER,
        display = "${cs.display}, climb and maintain ${formatAltDisplay(altitude)}.",
        spoken = "${cs.spoken}, climb and maintain ${Phonetic.altitude(altitude, icao = icao)}.",
    )

    /**
     * Center — first call when the aircraft checks in after the Departure hand-off:
     * radar contact, then the climb to the cruising altitude.
     */
    fun centerRadarContactClimb(cs: Callsign, altitude: Int): ATCTransmission = tx(
        ATCFacility.CENTER,
        display = "${cs.display}, radar contact, climb and maintain ${formatAltDisplay(altitude)}.",
        spoken = "${cs.spoken}, radar contact, climb and maintain ${Phonetic.altitude(altitude, icao = icao)}.",
    )

    /**
     * Center — descend and maintain an assigned altitude (no STAR filed). A plain,
     * non-contradictory descent clearance.
     */
    fun descendMaintain(cs: Callsign, altitude: Int): ATCTransmission = tx(
        ATCFacility.CENTER,
        display = "${cs.display}, descend and maintain ${formatAltDisplay(altitude)}.",
        spoken = "${cs.spoken}, descend and maintain ${Phonetic.altitude(altitude, icao = icao)}.",
    )

    /**
     * Center — pilot's discretion descent (used when the pilot requests lower; the
     * pilot chooses when to leave the current altitude, then levels at the target).
     */
    fun descendPilotsDiscretion(cs: Callsign, altitude: Int): ATCTransmission = tx(
        ATCFacility.CENTER,
        display = "${cs.display}, descend at pilot's discretion, maintain ${formatAltDisplay(altitude)}.",
        spoken = "${cs.spoken}, descend at pilot's discretion, maintain ${Phonetic.altitude(altitude, icao = icao)}.",
    )

    /**
     * Approach — descend + expect a published approach procedure (clean ILS/GPS/
     * Visual phrasing, avoiding the doubled "RWY … runway …").
     */
    fun descendExpectApproach(
        cs: Callsign,
        altitude: Int,
        procedure: PhraseologyProcedure,
        runway: String,
    ): ATCTransmission {
        val rwy = procedure.runway ?: runway
        val typeDisplay = procedure.approachTypeDisplay ?: "approach"
        val typeSpoken = procedure.approachTypeSpoken ?: "approach"
        return tx(
            ATCFacility.APPROACH,
            display = "${cs.display}, descend and maintain ${formatAltDisplay(altitude)}, expect the $typeDisplay runway $rwy approach.",
            spoken = "${cs.spoken}, descend and maintain ${Phonetic.altitude(altitude, icao = icao)}, expect the $typeSpoken runway ${Phonetic.runway(rwy, icao)} approach.",
        )
    }

    /** Approach — descend + expect approach (free-text approach name fallback). */
    fun descendExpectApproach(
        cs: Callsign,
        altitude: Int,
        approach: String,
        runway: String,
    ): ATCTransmission {
        val appText = if (approach.isEmpty()) "the I-L-S" else approach
        return tx(
            ATCFacility.APPROACH,
            display = "${cs.display}, descend and maintain ${formatAltDisplay(altitude)}, expect $appText runway $runway approach.",
            spoken = "${cs.spoken}, descend and maintain ${Phonetic.altitude(altitude, icao = icao)}, expect ${if (approach.isEmpty()) "the I L S" else approach} runway ${Phonetic.runway(runway, icao)} approach.",
        )
    }

    /**
     * Tower — go-around / missed approach. The pilot has broken off the approach;
     * Tower turns them onto a crosswind leg (a 90° vector off the runway heading),
     * climbs them to the pattern altitude, tells them which way to make traffic for
     * the *same* runway, and hands them back to Approach for another approach. The
     * read-back echoes every element and, once read back, tunes the radio to Approach.
     */
    fun goAround(
        cs: Callsign,
        runway: String,
        leftTraffic: Boolean,
        crosswindHeading: Int,
        patternAltitude: Int,
        approachFrequency: Double,
    ): ATCTransmission {
        val turn = if (leftTraffic) "left" else "right"
        val hdgD = String.format(Locale.US, "%03d", crosswindHeading)
        val hdgS = Phonetic.heading(crosswindHeading, icao)
        val altD = formatAltDisplay(patternAltitude)
        val altS = Phonetic.altitude(patternAltitude, icao = icao)
        val rwyS = Phonetic.runway(runway, icao)
        val freqD = String.format(Locale.US, "%.3f", approachFrequency)
        val freqS = Phonetic.frequency(approachFrequency, icao)
        val display = "${cs.display}, go around, turn $turn heading $hdgD, climb and maintain $altD, make $turn traffic runway $runway, contact Approach on $freqD."
        val spoken = "${cs.spoken}, go around, turn $turn heading $hdgS, climb and maintain $altS, make $turn traffic runway $rwyS, contact Approach on $freqS."
        return tx(ATCFacility.TOWER, display = display, spoken = spoken).copy(
            // The read-back carries every element — heading, climb altitude, traffic
            // direction, runway — plus the hand-off, and tunes to Approach once read back.
            readback = ATCTransmission.Readback(
                displayText = "Going around, turn $turn heading $hdgD, climb and maintain $altD, make $turn traffic runway $runway, contacting Approach on $freqD, ${cs.display}.",
                spokenText = "Going around, turn $turn heading $hdgS, climb and maintain $altS, make $turn traffic runway $rwyS, contacting Approach on $freqS, ${cs.spoken}.",
                facility = ATCFacility.APPROACH,
                tuneTo = ATCFacility.APPROACH,
            ),
        )
    }

    /**
     * Approach — re-establishing after a go-around: the pilot checks back in on the
     * missed-approach leg, and Approach holds the pattern altitude Tower assigned and
     * sends the aircraft back around for another approach. The cleared-approach →
     * Tower sequence then replays exactly as on the first approach.
     */
    fun continueInbound(
        cs: Callsign,
        altitude: Int,
        procedure: PhraseologyProcedure?,
        approach: String,
        runway: String,
    ): ATCTransmission {
        val expectD: String
        val expectS: String
        if (procedure != null) {
            val rwy = procedure.runway ?: runway
            expectD = "the ${procedure.approachTypeDisplay ?: "approach"} runway $rwy"
            expectS = "the ${procedure.approachTypeSpoken ?: "approach"} runway ${Phonetic.runway(rwy, icao)}"
        } else {
            val appD = if (approach.isEmpty()) "the ILS" else approach
            val appS = if (approach.isEmpty()) "the I L S" else approach
            expectD = "$appD runway $runway"
            expectS = "$appS runway ${Phonetic.runway(runway, icao)}"
        }
        return tx(
            ATCFacility.APPROACH,
            display = "${cs.display}, maintain ${formatAltDisplay(altitude)}, continue inbound, expect $expectD approach.",
            spoken = "${cs.spoken}, maintain ${Phonetic.altitude(altitude, icao = icao)}, continue inbound, expect $expectS approach.",
        ).copy(
            readback = ATCTransmission.Readback(
                displayText = "Maintain ${formatAltDisplay(altitude)}, continue inbound, ${cs.display}.",
                spokenText = "Maintain ${Phonetic.altitude(altitude, icao = icao)}, continue inbound, ${cs.spoken}.",
                facility = ATCFacility.APPROACH,
            ),
        )
    }

    /** Approach/Tower — cleared approach. */
    fun clearedApproach(cs: Callsign, approach: String, runway: String): ATCTransmission {
        val appText = if (approach.isEmpty()) "ILS" else approach
        return tx(
            ATCFacility.APPROACH,
            display = "${cs.display}, cleared $appText runway $runway approach.",
            spoken = "${cs.spoken}, cleared ${if (approach.isEmpty()) "I L S" else approach} runway ${Phonetic.runway(runway, icao)} approach.",
        )
    }

    /** Tower arrival — cleared to land. */
    fun clearedToLand(cs: Callsign, runway: String, windDir: Int, windSpeed: Int): ATCTransmission {
        val template = profile?.template(PhraseologyTemplateKey.LANDING)
        if (template != null) {
            val ph = placeholders(
                cs,
                mapOf(
                    "runway" to runway, "runwaySpoken" to Phonetic.runway(runway, icao),
                    "wind" to "${String.format(Locale.US, "%03d", windDir)} at $windSpeed",
                    "windSpoken" to Phonetic.wind(direction = windDir, speed = windSpeed, icao = icao),
                ),
            )
            return tx(
                ATCFacility.TOWER,
                display = render(template.display, ph.display),
                spoken = render(template.spoken, ph.spoken),
            )
        }
        return tx(
            ATCFacility.TOWER,
            display = "${cs.display}, wind ${String.format(Locale.US, "%03d", windDir)} at $windSpeed, runway $runway, cleared to land.",
            spoken = "${cs.spoken}, ${Phonetic.wind(direction = windDir, speed = windSpeed, icao = icao)}, runway ${Phonetic.runway(runway, icao)}, cleared to land.",
        )
    }

    /**
     * Tower rollout — exit the runway and contact Ground once clear. Issued by
     * Tower after touchdown, before the Ground taxi-in instruction.
     */
    fun exitRunwayContactGround(cs: Callsign, frequency: Double): ATCTransmission = tx(
        ATCFacility.TOWER,
        display = "${cs.display}, exit the runway when able, contact Ground on ${String.format(Locale.US, "%.3f", frequency)} once on the taxiway.",
        spoken = "${cs.spoken}, exit the runway when able, contact Ground on ${Phonetic.frequency(frequency, icao)} once on the taxiway.",
    )

    /** Ground arrival — taxi to the gate (named when known, else "parking"). */
    fun taxiToParking(cs: Callsign, gate: String, via: String): ATCTransmission {
        val gateTrim = gate.trim()
        val destDisplay = if (gateTrim.isEmpty()) "parking" else "gate $gateTrim"
        val destSpoken = if (gateTrim.isEmpty()) "parking" else "gate ${Phonetic.spellToken(gateTrim, icao)}"
        val viaText = if (via.isEmpty()) "available taxiways" else via
        val viaSpoken = if (via.isEmpty()) "available taxiways" else Phonetic.spellToken(via, icao)
        return tx(
            ATCFacility.GROUND,
            display = "${cs.display}, taxi to $destDisplay via $viaText.",
            spoken = "${cs.spoken}, taxi to $destSpoken via $viaSpoken.",
        ).copy(
            // Precompose the read-back so the Read Back button always echoes the taxi-in
            // instruction — even if telemetry has already advanced the conversation to the
            // gate/parked state. Without this, a read-back re-derived from a drifted state
            // collapses to a bare callsign ("United five niner eight") with no taxi routing.
            readback = ATCTransmission.Readback(
                displayText = "Taxi to $destDisplay via $viaText, ${cs.display}.",
                spokenText = "Taxi to $destSpoken via $viaSpoken, ${cs.spoken}.",
                facility = ATCFacility.GROUND,
            ),
        )
    }

    /** Generic handoff. */
    fun handoff(cs: Callsign, to: ATCFacility, frequency: Double): ATCTransmission = tx(
        to,
        display = "${cs.display}, contact ${spokenName(to)} on ${String.format(Locale.US, "%.3f", frequency)}.",
        spoken = "${cs.spoken}, contact ${spokenName(to)} on ${Phonetic.frequency(frequency, icao)}.",
    ).copy(readback = handoffReadback(cs, to, frequency))

    /**
     * Handoff spoken by the facility you are leaving, instructing you to contact
     * the next one (e.g. Tower: "contact Departure on 124.3"). Attributed to the
     * [from] facility so the transcript shows who is releasing you.
     */
    fun handoff(cs: Callsign, from: ATCFacility, to: ATCFacility, frequency: Double): ATCTransmission = tx(
        from,
        display = "${cs.display}, contact ${spokenName(to)} on ${String.format(Locale.US, "%.3f", frequency)}.",
        spoken = "${cs.spoken}, contact ${spokenName(to)} on ${Phonetic.frequency(frequency, icao)}.",
    ).copy(readback = handoffReadback(cs, to, frequency))

    /**
     * Pilot read-back for a frequency hand-off: "contacting <next> on <freq>",
     * carrying the facility to tune to once read back.
     */
    private fun handoffReadback(
        cs: Callsign,
        to: ATCFacility,
        frequency: Double,
    ): ATCTransmission.Readback = ATCTransmission.Readback(
        displayText = "Contacting ${spokenName(to)} on ${String.format(Locale.US, "%.3f", frequency)}, ${cs.display}.",
        spokenText = "Contacting ${spokenName(to)} on ${Phonetic.frequency(frequency, icao)}, ${cs.spoken}.",
        facility = to,
        tuneTo = to,
    )

    /**
     * Append a pushback hand-off to an IFR clearance so Clearance Delivery tells
     * the pilot whom to contact for the push — Ramp (when the airport has a
     * ramp/apron layer) or Ground (when it does not). The callsign is omitted
     * from the trailing sentence since the clearance already addresses the pilot.
     *
     * Note this names the facility with [ATCFacility.spokenName], not [spokenName] —
     * the Swift does the same, and neither Ramp nor Ground is sector-named anyway.
     */
    fun appendingPushbackHandoff(
        transmission: ATCTransmission,
        facility: ATCFacility,
        frequency: Double,
    ): ATCTransmission = transmission.copy(
        displayText = transmission.displayText +
            " When ready for pushback, contact ${facility.spokenName} on ${String.format(Locale.US, "%.3f", frequency)}.",
        spokenText = transmission.spokenText +
            " When ready for pushback, contact ${facility.spokenName} on ${Phonetic.frequency(frequency, icao)}.",
    )

    /** Arrival courtesy on reaching the gate. */
    fun welcomeArrival(cs: Callsign, airport: String): ATCTransmission {
        val city = spokenAirport(airport)
        val display = if (airport.isEmpty()) {
            "${cs.display}, welcome, monitor ground, good day."
        } else {
            "${cs.display}, welcome to ${cityNames[airport.uppercase()] ?: airport}, good day."
        }
        val spoken = "${cs.spoken}, welcome to $city, good day."
        return tx(ATCFacility.GROUND, display = display, spoken = spoken)
    }

    fun radarContact(cs: Callsign, facility: ATCFacility): ATCTransmission = tx(
        facility,
        display = "${cs.display}, ${spokenName(facility)}, radar contact.",
        spoken = "${cs.spoken}, ${spokenName(facility)}, radar contact.",
    )

    // MARK: - Helpers

    /** Display form of an altitude: "FL370" above transition, else "5,000". */
    fun formatAltDisplay(feet: Int): String {
        // 18,000 ft — hard-coded here, separate from `Phonetic.altitude`'s parameter.
        if (feet >= 18000) return "FL${String.format(Locale.US, "%03d", feet / 100)}"
        // iOS uses an en_US NumberFormatter with a "," grouping separator.
        return String.format(Locale.US, "%,d", feet)
    }

    /** Spoken airport: known major ICAOs get city names, else spelled out. */
    fun spokenAirport(icaoCode: String): String {
        val code = icaoCode.uppercase()
        cityNames[code]?.let { return it }
        return if (code.isEmpty()) "destination" else Phonetic.spellToken(code, this.icao)
    }

    // MARK: - Template rendering

    private class Placeholders(
        val display: Map<String, String>,
        val spoken: Map<String, String>,
    )

    private fun placeholders(cs: Callsign, extra: Map<String, String>): Placeholders {
        val display = mutableMapOf("callsign" to cs.display)
        val spoken = mutableMapOf("callsign" to cs.spoken)
        for ((key, value) in extra) {
            if (key.endsWith("Spoken")) {
                spoken[key.dropLast("Spoken".length)] = value
            } else {
                display[key] = value
                // Default spoken to display unless an explicit spoken value follows.
                if (spoken[key] == null) spoken[key] = value
            }
        }
        return Placeholders(display, spoken)
    }

    /** Substitute `{placeholder}` tokens in a template string. */
    private fun render(template: String, values: Map<String, String>): String {
        var result = template
        for ((key, value) in values) {
            result = result.replace("{$key}", value)
        }
        return result
    }

    companion object {
        /**
         * Magnetic heading (degrees) implied by a runway identifier, e.g. "17R" -> 170.
         * Runway numbers outside 1…36 are not runway numbers.
         */
        fun runwayHeading(runway: String): Int? {
            val digits = runway.takeWhile { it.isDigit() }
            val n = digits.toIntOrNull() ?: return null
            if (n < 1 || n > 36) return null
            return n * 10
        }

        /** Smallest absolute difference between two compass bearings (0–180°). */
        fun angularDiff(a: Double, b: Double): Double {
            val d = abs((a - b) % 360.0)
            return min(d, 360 - d)
        }

        /** Small built-in city lookup so common routes sound natural. Extendable. */
        val cityNames: Map<String, String> = mapOf(
            "KMSP" to "Minneapolis", "KIAH" to "Houston", "KDEN" to "Denver",
            "KORD" to "Chicago", "KATL" to "Atlanta", "KLAX" to "Los Angeles",
            "KJFK" to "New York", "KSFO" to "San Francisco", "KSEA" to "Seattle",
            "KDFW" to "Dallas", "KBOS" to "Boston", "KMIA" to "Miami",
            "KLAS" to "Las Vegas", "KPHX" to "Phoenix", "KDCA" to "Washington",
        )
    }
}
