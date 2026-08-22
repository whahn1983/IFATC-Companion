package com.h3consultingpartners.ifatccompanion.core.chatter

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.phraseology.AirlineDatabase
import com.h3consultingpartners.ifatccompanion.core.phraseology.CallsignDigitStyle
import com.h3consultingpartners.ifatccompanion.core.phraseology.Phonetic
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyMode
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Produces random, phraseologically-plausible background ATC chatter, **bounded to the
 * frequency the pilot is tuned to** so it never sounds wrong for the position: Center works
 * ride reports, hand-offs, descend-via-STAR and en-route climbs/descents; Ground works taxi
 * and hand-offs; Tower works takeoff/landing/line-up-and-wait; and so on.
 *
 * Everything is deterministic given the random source and reuses the app's own [Phonetic]
 * renderer (so "niner/fife/tree", spoken headings, altitudes and frequencies match the real
 * calls) and [AirlineDatabase] (so callsigns are real radio names). No AI, no network —
 * pure templates filled with random values.
 *
 * When the tuned airport's OpenStreetMap surface has loaded, its real runway ends are
 * supplied via [runwayIdents] so runway references (Ground taxi/hold-short, Tower
 * takeoff/land/line-up, Approach clearances) name runways that actually exist at the
 * origin/destination field, rather than an invented "runway 18" the airport lacks. When the
 * field's ATIS is also available, [departureRunwayIdents] / [arrivalRunwayIdents] carry the
 * runways actually in use, so a takeoff or taxi call names a departure runway and a
 * landing/approach call an arrival runway — matching how the field is really being run.
 *
 * Swift makes the generator generic over `RandomNumberGenerator`; here every draw goes
 * through an injected [Random], for the same reason: tests drive it deterministically with
 * a seed.
 *
 * Ported from `IFATCCompanion/Chatter/ChatterScriptGenerator.swift`.
 */
class ChatterScriptGenerator(
    var mode: PhraseologyMode = PhraseologyMode.FAA,
    var digitStyle: CallsignDigitStyle = CallsignDigitStyle.GROUPED,

    /**
     * Real runway-end idents for the airport this chatter is simulating right now — the
     * origin field while pre-departure/climbing, the destination once descending/arriving —
     * taken from the loaded OpenStreetMap surface (e.g. `["16L","34R","09","27"]`). When
     * non-empty, every runway reference (Ground taxi/hold-short, Tower takeoff/land/line-up,
     * Approach clearances) is drawn from these so the background traffic never taxis to or
     * is cleared for a runway the field does not have. Empty (no surface loaded yet, or no
     * flight plan) falls back to a plausible random runway.
     */
    var runwayIdents: List<String> = emptyList(),

    /**
     * The runways in use for **departures** per the field's ATIS (a subset of
     * [runwayIdents]). Ground taxi/hold-short and Tower takeoff/line-up draw from here so
     * departing traffic uses a departure runway. Empty when no ATIS is available — the
     * departure calls then fall back to [runwayIdents] (any real runway), then to a random
     * one.
     */
    var departureRunwayIdents: List<String> = emptyList(),

    /**
     * The runways in use for **arrivals** per the field's ATIS. Tower landing/final and
     * Approach clearances draw from here so arriving traffic uses an arrival runway. Empty
     * falls back to [runwayIdents], then a random runway.
     */
    var arrivalRunwayIdents: List<String> = emptyList(),
) {

    private val icao: Boolean get() = mode == PhraseologyMode.ICAO

    /**
     * Generate an exchange (usually one controller line, sometimes with a matching
     * read-back) for the tuned [facility].
     */
    fun exchange(facility: ATCFacility, rng: Random): List<ChatterLine> {
        val cs = callsign(rng)
        return when (facility) {
            ATCFacility.CENTER -> centerExchange(cs, rng)
            ATCFacility.APPROACH -> approachExchange(cs, rng)
            ATCFacility.DEPARTURE -> departureExchange(cs, rng)
            ATCFacility.TOWER -> towerExchange(cs, rng)
            ATCFacility.GROUND -> groundExchange(cs, rng)
            ATCFacility.CLEARANCE -> clearanceExchange(cs, rng)
            ATCFacility.RAMP -> rampExchange(cs, rng)
        }
    }

    /** Convenience using the default (system) random source. */
    fun exchange(facility: ATCFacility): List<ChatterLine> = exchange(facility, Random.Default)

    // region Per-facility templates

    private fun centerExchange(cs: Callsign, rng: Random): List<ChatterLine> = when (rng.nextInt(6)) {
        0 -> {
            val sev = listOf("light", "light to moderate", "occasional light", "moderate").random(rng)
            val fl = flightLevel(rng)
            listOf(
                ctrl("${cs.spoken}, $sev chop reported at ${Phonetic.altitude(fl, icao = icao)}, say ride conditions."),
                pilot("${cs.spoken}, ${listOf("smooth", "light chop", "negative, smooth ride").random(rng)}."),
            )
        }
        1 -> {
            val fl = flightLevel(rng)
            listOf(
                ctrl("${cs.spoken}, climb and maintain ${Phonetic.altitude(fl, icao = icao)}."),
                readback(cs, "climb and maintain ${Phonetic.altitude(fl, icao = icao)}"),
            )
        }
        2 -> {
            val fl = flightLevel(rng)
            listOf(
                ctrl("${cs.spoken}, descend and maintain ${Phonetic.altitude(fl, icao = icao)}."),
                readback(cs, "descend and maintain ${Phonetic.altitude(fl, icao = icao)}"),
            )
        }
        3 -> {
            val facility =
                listOf(ATCFacility.CENTER, ATCFacility.APPROACH, ATCFacility.DEPARTURE).random(rng)
            val freq = Phonetic.frequency(centerFreq(rng), icao)
            listOf(
                ctrl("${cs.spoken}, contact ${facility.spokenName} $freq."),
                readback(cs, "over to ${facility.spokenName} $freq"),
            )
        }
        4 -> {
            val star = fixes.random(rng)
            listOf(
                ctrl("${cs.spoken}, descend via the $star ${oneArrivalNumber(rng)} arrival."),
                readback(cs, "descend via the $star arrival"),
            )
        }
        else -> {
            val fix = fixes.random(rng)
            listOf(
                ctrl("${cs.spoken}, cleared direct $fix, rest of route unchanged."),
                readback(cs, "direct $fix"),
            )
        }
    }

    private fun approachExchange(cs: Callsign, rng: Random): List<ChatterLine> = when (rng.nextInt(5)) {
        0 -> {
            val hdg = Phonetic.heading(heading(rng), icao)
            val alt = Phonetic.altitude(lowAltitude(rng), icao = icao)
            val dir = listOf("left", "right").random(rng)
            listOf(
                ctrl("${cs.spoken}, turn $dir heading $hdg, descend and maintain $alt."),
                readback(cs, "$dir heading $hdg, down to $alt"),
            )
        }
        1 -> {
            val type = listOf("ILS", "R NAV", "visual").random(rng)
            val rwy = Phonetic.runway(arrivalRunway(rng), icao)
            listOf(
                ctrl("${cs.spoken}, cleared $type runway $rwy approach."),
                readback(cs, "cleared $type runway $rwy approach"),
            )
        }
        2 -> {
            val spd = spellNumber((rng.nextInt(18, 30) * 10).toString())
            listOf(ctrl("${cs.spoken}, reduce speed $spd knots."), readback(cs, "$spd knots"))
        }
        3 -> {
            val freq = Phonetic.frequency(towerFreq(rng), icao)
            listOf(ctrl("${cs.spoken}, contact tower $freq."), readback(cs, "tower $freq, good day"))
        }
        else -> {
            val miles = spellNumber(rng.nextInt(3, 13).toString())
            listOf(
                ctrl(
                    "${cs.spoken}, traffic to follow is a heavy ${fixes.random(rng).lowercase()} " +
                        "departure, $miles miles.",
                ),
            )
        }
    }

    private fun departureExchange(cs: Callsign, rng: Random): List<ChatterLine> = when (rng.nextInt(5)) {
        0 -> {
            val alt = Phonetic.altitude(lowAltitude(rng), icao = icao)
            listOf(
                ctrl("${cs.spoken}, radar contact, climb and maintain $alt."),
                readback(cs, "climb and maintain $alt"),
            )
        }
        1 -> {
            val hdg = Phonetic.heading(heading(rng), icao)
            val dir = listOf("left", "right").random(rng)
            listOf(ctrl("${cs.spoken}, turn $dir heading $hdg."), readback(cs, "$dir heading $hdg"))
        }
        2 -> {
            val fix = fixes.random(rng)
            listOf(
                ctrl("${cs.spoken}, resume own navigation, direct $fix."),
                readback(cs, "own nav, direct $fix"),
            )
        }
        3 -> {
            val freq = Phonetic.frequency(centerFreq(rng), icao)
            listOf(ctrl("${cs.spoken}, contact center $freq."), readback(cs, "center $freq"))
        }
        else -> {
            val alt = Phonetic.altitude(flightLevel(rng), icao = icao)
            listOf(
                ctrl("${cs.spoken}, climb and maintain $alt, expedite through one zero thousand."),
                readback(cs, "up to $alt"),
            )
        }
    }

    private fun towerExchange(cs: Callsign, rng: Random): List<ChatterLine> = when (rng.nextInt(5)) {
        0 -> {
            val rwy = Phonetic.runway(departureRunway(rng), icao)
            listOf(
                ctrl("${cs.spoken}, runway $rwy, cleared for takeoff."),
                readback(cs, "cleared for takeoff runway $rwy"),
            )
        }
        1 -> {
            val rwy = Phonetic.runway(arrivalRunway(rng), icao)
            listOf(
                ctrl("${cs.spoken}, runway $rwy, cleared to land."),
                readback(cs, "cleared to land runway $rwy"),
            )
        }
        2 -> {
            val rwy = Phonetic.runway(departureRunway(rng), icao)
            listOf(
                ctrl("${cs.spoken}, runway $rwy, line up and wait."),
                readback(cs, "line up and wait runway $rwy"),
            )
        }
        3 -> {
            val freq = Phonetic.frequency(departureFreq(rng), icao)
            listOf(ctrl("${cs.spoken}, contact departure $freq."), readback(cs, "departure $freq"))
        }
        else -> {
            val rwy = Phonetic.runway(arrivalRunway(rng), icao)
            val miles = spellNumber(rng.nextInt(2, 9).toString())
            listOf(
                ctrl("${cs.spoken}, traffic on a $miles mile final, runway $rwy, continue."),
                readback(cs, "continue, ${cs.spoken}"),
            )
        }
    }

    private fun groundExchange(cs: Callsign, rng: Random): List<ChatterLine> {
        // Ground traffic is taxiing out to depart, so it holds short of / taxis to a
        // departure runway.
        val rwy = Phonetic.runway(departureRunway(rng), icao)
        val taxi = taxiways(rng)
        return when (rng.nextInt(4)) {
            0 -> listOf(
                ctrl("${cs.spoken}, taxi to runway $rwy via $taxi."),
                readback(cs, "runway $rwy via $taxi"),
            )
            1 -> listOf(ctrl("${cs.spoken}, give way to company traffic, then continue taxi via $taxi."))
            2 -> {
                val freq = Phonetic.frequency(towerFreq(rng), icao)
                listOf(ctrl("${cs.spoken}, monitor tower $freq."), readback(cs, "tower $freq"))
            }
            else -> listOf(
                ctrl("${cs.spoken}, hold short of runway $rwy."),
                readback(cs, "hold short runway $rwy"),
            )
        }
    }

    private fun clearanceExchange(cs: Callsign, rng: Random): List<ChatterLine> {
        val freq = Phonetic.frequency(departureFreq(rng), icao)
        val squawk = Phonetic.squawk(squawkCode(rng), icao)
        val alt = Phonetic.altitude(lowAltitude(rng), icao = icao)
        return listOf(
            ctrl(
                "${cs.spoken}, cleared to destination as filed, climb via SID, expect $alt " +
                    "one zero minutes after departure, departure $freq, squawk $squawk.",
            ),
            readback(cs, "as filed, $alt, departure $freq, squawk $squawk"),
        )
    }

    private fun rampExchange(cs: Callsign, rng: Random): List<ChatterLine> = when (rng.nextInt(3)) {
        0 -> listOf(
            ctrl(
                "${cs.spoken}, pushback approved, tail to the " +
                    "${listOf("north", "south", "east", "west").random(rng)}.",
            ),
            readback(cs, "pushback approved"),
        )
        1 -> listOf(ctrl("${cs.spoken}, engine start approved."), readback(cs, "start approved"))
        else -> {
            val freq = Phonetic.frequency(groundFreq(rng), icao)
            listOf(ctrl("${cs.spoken}, monitor ground $freq for taxi."), readback(cs, "ground $freq"))
        }
    }

    // endregion

    // region Line builders

    private fun ctrl(text: String) = ChatterLine(spokenText = text, isPilot = false)

    private fun pilot(text: String) = ChatterLine(spokenText = text, isPilot = true)

    private fun readback(cs: Callsign, body: String) =
        ChatterLine(spokenText = "$body, ${cs.spoken}.", isPilot = true)

    // endregion

    // region Random value helpers

    private class Callsign(val spoken: String)

    private fun callsign(rng: Random): Callsign {
        val designator = designators.random(rng)
        val name = AirlineDatabase.callName(designator) ?: Phonetic.spellToken(designator, icao)
        val digits = rng.nextInt(1, 5)
        var number = ""
        for (i in 0 until digits) {
            // Avoid a leading zero so the number reads naturally.
            val lo = if (i == 0 && digits > 1) 1 else 0
            number += rng.nextInt(lo, 10).toString()
        }
        return Callsign(spoken = "$name ${spellNumber(number)}")
    }

    /** Speak a numeric string honouring the grouped/individual digit style. */
    private fun spellNumber(digits: String): String {
        if (digitStyle != CallsignDigitStyle.GROUPED) return Phonetic.spellDigits(digits, icao)
        return when (digits.length) {
            2 -> Phonetic.twoDigitGroup(digits.toIntOrNull() ?: 0, icao)
            4 -> {
                val hi = digits.take(2).toIntOrNull() ?: 0
                val lo = digits.takeLast(2).toIntOrNull() ?: 0
                "${Phonetic.twoDigitGroup(hi, icao)} ${Phonetic.twoDigitGroup(lo, icao)}"
            }
            else -> Phonetic.spellDigits(digits, icao)
        }
    }

    private fun heading(rng: Random): Int {
        val h = rng.nextInt(36) * 10
        return if (h == 0) 360 else h
    }

    private fun runway(rng: Random): String {
        // Prefer a real runway end at the field so the chatter never names a runway that
        // doesn't exist there; fall back to a plausible random one when none are known yet.
        if (runwayIdents.isNotEmpty()) return runwayIdents.random(rng)
        val num = rng.nextInt(1, 37)
        val suffix = listOf("", "", "L", "C", "R").random(rng)
        return "%02d".format(num) + suffix
    }

    /**
     * A runway used for departures: an ATIS-active departure runway when known, else any
     * real runway at the field, else a random one.
     */
    private fun departureRunway(rng: Random): String {
        if (departureRunwayIdents.isNotEmpty()) return departureRunwayIdents.random(rng)
        return runway(rng)
    }

    /**
     * A runway used for arrivals: an ATIS-active arrival runway when known, else any real
     * runway at the field, else a random one.
     */
    private fun arrivalRunway(rng: Random): String {
        if (arrivalRunwayIdents.isNotEmpty()) return arrivalRunwayIdents.random(rng)
        return runway(rng)
    }

    private fun flightLevel(rng: Random): Int = rng.nextInt(24, 42) * 1000

    private fun lowAltitude(rng: Random): Int = rng.nextInt(3, 18) * 1000

    private fun taxiways(rng: Random): String {
        val letters = listOf(
            "Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel", "Juliet", "Kilo", "Mike",
        )
        val count = rng.nextInt(1, 3)
        return (0 until count).joinToString(", ") { letters.random(rng) }
    }

    private fun squawkCode(rng: Random): String =
        (0 until 4).joinToString("") { rng.nextInt(0, 8).toString() }

    private fun oneArrivalNumber(rng: Random): String =
        Phonetic.spellDigits(rng.nextInt(1, 10).toString(), icao)

    // Frequency bands, chosen to sound right for each service.
    private fun groundFreq(rng: Random): Double = band(121.6, 121.9, rng)
    private fun towerFreq(rng: Random): Double = band(118.0, 120.9, rng)
    private fun departureFreq(rng: Random): Double = band(124.0, 127.9, rng)
    private fun centerFreq(rng: Random): Double = band(132.0, 135.9, rng)

    private fun band(lo: Double, hi: Double, rng: Random): Double {
        // 25 kHz spacing.
        val steps = ((hi - lo) / 0.025).toInt()
        val n = rng.nextInt(0, steps + 1)
        return roundToPlaces(lo + n * 0.025, 3)
    }

    private fun roundToPlaces(value: Double, places: Int): Double {
        var m = 1.0
        repeat(places) { m *= 10 }
        return (value * m).roundToLong() / m
    }

    // endregion

    companion object {
        /** Common ICAO designators that resolve to a spoken name via [AirlineDatabase]. */
        private val designators: List<String> = listOf(
            "UAL", "AAL", "DAL", "SWA", "JBU", "ASA", "NKS", "FFT", "ACA", "WJA",
            "FDX", "UPS", "BAW", "AFR", "DLH", "KLM", "SkW", "RPA", "EDV", "ENY",
        ).map { it.uppercase() }

        /** A small pool of pronounceable waypoint-style names TTS reads as words. */
        private val fixes: List<String> = listOf(
            "Scatt", "Wynde", "Boove", "Hobit", "Ravnn", "Kylse", "Manta", "Dublv",
            "Cardz", "Bruno", "Tydev", "Ganns", "Pladd", "Sherlk", "Oconn", "Yeager",
        )
    }
}
