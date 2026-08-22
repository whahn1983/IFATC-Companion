package com.h3consultingpartners.ifatccompanion.core.weather.deviation

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.phraseology.Phonetic
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyEngine
import com.h3consultingpartners.ifatccompanion.core.weather.METAR
import com.h3consultingpartners.ifatccompanion.core.weather.RideReportItem
import com.h3consultingpartners.ifatccompanion.core.weather.SmootherAltitude
import com.h3consultingpartners.ifatccompanion.core.weather.TurbulenceSeverity
import java.util.Locale
import kotlin.math.floor

/** Swift's `.rounded()`: ties break away from zero, which `roundToInt` does not do. */
internal fun roundHalfAwayFromZero(value: Double): Double =
    if (value < 0) -floor(-value + 0.5) else floor(value + 0.5)

/**
 * Produces deterministic, programmatic Center responses for ride reports and
 * destination weather, based on filtered weather data. No AI.
 *
 * Ported from `IFATCCompanion/Weather/RideReportEngine.swift`.
 */
class RideReportEngine(val engine: PhraseologyEngine) {

    private val icao: Boolean get() = engine.icao

    private fun center(display: String, spoken: String): ATCTransmission =
        ATCTransmission.create(
            sender = ATCTransmission.Sender.ATC,
            facility = ATCFacility.CENTER,
            displayText = display,
            spokenText = spoken,
        )

    /** Build the ride-report transmission from relevant items. */
    fun rideReport(items: List<RideReportItem>, callsign: PhraseologyEngine.Callsign): ATCTransmission {
        val worst = items.maxWithOrNull(compareBy { it.severity })
            ?: return center(
                "${callsign.display}, no significant ride reports along your route at this time.",
                "${callsign.spoken}, no significant ride reports along your route at this time.",
            )
        // Prefer the nearest report of the worst severity for the lead phrase.
        val lead = items.filter { it.severity == worst.severity }
            .minWithOrNull(compareBy { it.distanceAheadNM ?: Double.MAX_VALUE })
            ?: worst

        val bandDisplay = bandPhrase(lead.altitudeBand, spoken = false)
        val bandSpoken = bandPhrase(lead.altitudeBand, spoken = true)
        val distDisplay = aheadPhrase(lead, spoken = false)
        val distSpoken = aheadPhrase(lead, spoken = true)
        val fix = lead.nearFix?.takeIf { it.isNotEmpty() }

        return when (worst.severity) {
            TurbulenceSeverity.SMOOTH -> center(
                "${callsign.display}, smooth ride reported along your route.",
                "${callsign.spoken}, smooth ride reported along your route.",
            )
            TurbulenceSeverity.LIGHT_CHOP, TurbulenceSeverity.LIGHT -> {
                val sevText = worst.severity.spoken
                val display = "${callsign.display}, $sevText reported ahead" +
                    (if (bandDisplay.isEmpty()) "" else " $bandDisplay") + "."
                val spoken = "${callsign.spoken}, $sevText reported ahead" +
                    (if (bandSpoken.isEmpty()) "" else " $bandSpoken") + "."
                center(display, spoken)
            }
            TurbulenceSeverity.MODERATE -> {
                val near = fix?.let { " near $it" } ?: ""
                val nearSpoken = fix?.let { " near ${Phonetic.spellToken(it, icao)}" } ?: ""
                val display = "${callsign.display}, moderate turbulence reported$distDisplay$near. " +
                    "Advise if you'd like higher or lower."
                val spoken = "${callsign.spoken}, moderate turbulence reported$distSpoken$nearSpoken. " +
                    "Advise if you'd like higher or lower."
                center(display, spoken)
            }
            TurbulenceSeverity.SEVERE -> {
                val near = fix?.let { " near $it" } ?: ""
                val nearSpoken = fix?.let { " near ${Phonetic.spellToken(it, icao)}" } ?: ""
                val display = "${callsign.display}, severe turbulence reported$distDisplay$near. " +
                    "Recommend deviation or altitude change when able; advise intentions."
                val spoken = "${callsign.spoken}, severe turbulence reported$distSpoken$nearSpoken. " +
                    "Recommend deviation or altitude change when able; advise intentions."
                center(display, spoken)
            }
        }
    }

    /**
     * Build a ride report from a composite [RideAssessment] (turbulence model). When a
     * PIREP drives it, relay that report the way ATC would — severity, the reported
     * altitude, distance/fix ahead, reporting type and recency — and, when a PIREP at
     * another level shows a smoother ride, name that specific altitude; otherwise fall
     * back to the generic higher/lower offer. [referenceAltitudeFt] is the pilot's level
     * (used when the lead report's own altitude is unknown).
     */
    fun rideReport(
        assessment: RideAssessment,
        items: List<RideReportItem>,
        referenceAltitudeFt: Int = 0,
        smoother: SmootherAltitude? = null,
        callsign: PhraseologyEngine.Callsign,
    ): ATCTransmission {
        if (assessment.severity <= TurbulenceSeverity.SMOOTH) {
            return center(
                "${callsign.display}, overall ride is smooth along your route at this time.",
                "${callsign.spoken}, overall ride is smooth along your route at this time.",
            )
        }

        val lead = items.filter { it.severity == assessment.severity }
            .minWithOrNull(compareBy { it.distanceAheadNM ?: Double.MAX_VALUE })
            ?: items.maxWithOrNull(compareBy { it.severity })

        // When a relevant PIREP leads the report, relay that pilot report's own severity —
        // ATC is quoting the pilot, not the advisory. The composite assessment severity
        // (which a SIGMET or the wind-shear proxy can raise above every PIREP) is spoken only
        // when there is no PIREP to reference and the advisory rests on SIGMET data alone.
        val sev = lead?.severity ?: assessment.severity
        // Altitude: the report's own level when known, else the pilot's level.
        val altFt = lead?.reportedAltitudeFt ?: (if (referenceAltitudeFt > 0) referenceAltitudeFt else null)
        val altDisplay = altFt?.let { " at ${engine.formatAltDisplay(it)}" } ?: ""
        val altSpoken = altFt?.let { " at ${Phonetic.altitude(it)}" } ?: ""
        val distDisplay = aheadPhrase(lead, spoken = false)
        val distSpoken = aheadPhrase(lead, spoken = true)
        val fix = lead?.nearFix?.takeIf { it.isNotEmpty() }
        val nearDisplay = fix?.let { " near $it" } ?: ""
        val nearSpoken = fix?.let { " near ${Phonetic.spellToken(it, icao)}" } ?: ""
        val type = lead?.aircraftType?.takeIf { it.isNotEmpty() }
        val typeDisplay = type?.let { ", by a $it" } ?: ""
        val typeSpoken = type?.let { ", by a ${Phonetic.spellToken(it, icao)}" } ?: ""
        val ageDisplay = agePhrase(lead?.ageMinutes, spoken = false)
        val ageSpoken = agePhrase(lead?.ageMinutes, spoken = true)
        val factors = if (assessment.contributors.isEmpty()) {
            ""
        } else {
            " Based on ${assessment.contributors.joinToString(", ")}."
        }
        // A data-backed smoother level when one exists, else the generic offer (moderate+).
        val offerGeneric = sev >= TurbulenceSeverity.MODERATE
        val tailDisplay = smootherTail(smoother, spoken = false, offerGeneric = offerGeneric)
        val tailSpoken = smootherTail(smoother, spoken = true, offerGeneric = offerGeneric)

        val display = "${callsign.display}, ${sev.spoken} reported$altDisplay$distDisplay$nearDisplay" +
            "$typeDisplay$ageDisplay.$factors$tailDisplay"
        val spoken = "${callsign.spoken}, ${sev.spoken} reported$altSpoken$distSpoken$nearSpoken" +
            "$typeSpoken$ageSpoken.$factors$tailSpoken"
        return center(display, spoken)
    }

    /** A recency clause ("… , one five minutes ago"), or empty when the age is unknown. */
    private fun agePhrase(minutes: Double?, spoken: Boolean): String {
        if (minutes == null || minutes < 1) return ""
        val m = roundHalfAwayFromZero(minutes).toInt()
        return if (spoken) ", ${Phonetic.spellDigits(m.toString())} minutes ago" else ", $m minutes ago"
    }

    /**
     * The smoother-altitude suggestion clause (names the specific level), or the generic
     * higher/lower offer when there is no data-backed level and [offerGeneric] is set.
     */
    private fun smootherTail(s: SmootherAltitude?, spoken: Boolean, offerGeneric: Boolean): String {
        if (s == null) {
            return if (offerGeneric) " Advise if you'd like higher or lower for a smoother ride." else ""
        }
        val dir = if (s.higher) "climb" else "descend"
        val alt = if (spoken) Phonetic.altitude(s.altitudeFt) else engine.formatAltDisplay(s.altitudeFt)
        val ride = if (s.severity == TurbulenceSeverity.SMOOTH) {
            "smooth ride"
        } else {
            "lighter ride, ${s.severity.spoken},"
        }
        val leadCap = ride.substring(0, 1).uppercase() + ride.substring(1)
        return " $leadCap reported at $alt; advise if you'd like to $dir."
    }

    /** Build the destination weather transmission from a METAR. */
    fun destinationWeather(
        metar: METAR?,
        callsign: PhraseologyEngine.Callsign,
        icaoCode: String,
    ): ATCTransmission {
        val m = metar ?: return center(
            "${callsign.display}, ${engine.spokenAirport(icaoCode)} weather is not available at this time.",
            "${callsign.spoken}, ${engine.spokenAirport(icaoCode)} weather is not available at this time.",
        )
        val city = engine.spokenAirport(icaoCode)
        val displayParts = mutableListOf<String>()
        val spokenParts = mutableListOf<String>()

        val dir = m.windDirection
        val spd = m.windSpeed
        if (dir != null && spd != null) {
            displayParts.add("wind ${String.format(Locale.US, "%03d", dir)} at $spd")
            spokenParts.add(Phonetic.wind(direction = dir, speed = spd, gust = m.windGust, icao = icao))
        }
        m.visibilitySM?.let { vis ->
            val v = roundHalfAwayFromZero(vis).toInt()
            displayParts.add("visibility $v")
            spokenParts.add("visibility ${Phonetic.visibility(v, icao)}")
        }
        m.ceilingFt?.let { ceiling ->
            displayParts.add("ceiling $ceiling ${ceilingCover(m)}")
            spokenParts.add("ceiling ${Phonetic.altitude(ceiling, icao = icao)} ${ceilingCoverSpoken(m)}")
        }
        m.altimeterInHg?.let { altim ->
            if (icao) {
                val hpa = roundHalfAwayFromZero(altim * 33.8638866667).toInt()
                displayParts.add("QNH $hpa")
            } else {
                displayParts.add("altimeter ${String.format(Locale.US, "%.2f", altim)}")
            }
            spokenParts.add(Phonetic.altimeterSetting(altim, icao))
        }

        if (displayParts.isEmpty()) {
            return center(
                "${callsign.display}, $city weather is unavailable.",
                "${callsign.spoken}, $city weather is unavailable.",
            )
        }
        val display = "${callsign.display}, $city is reporting ${displayParts.joinToString(", ")}."
        val spoken = "${callsign.spoken}, $city is reporting ${spokenParts.joinToString(", ")}."
        return center(display, spoken)
    }

    // MARK: - Helpers

    private fun bandPhrase(band: IntRange?, spoken: Boolean): String {
        if (band == null) return ""
        return if (spoken) {
            "between ${Phonetic.altitude(band.first)} and ${Phonetic.altitude(band.last)}"
        } else {
            "between ${engine.formatAltDisplay(band.first)} and ${engine.formatAltDisplay(band.last)}"
        }
    }

    private fun distancePhrase(distance: Double?, spoken: Boolean): String {
        if (distance == null || distance <= 1) return ""
        val rounded = roundHalfAwayFromZero(distance / 10).toInt() * 10
        return if (spoken) {
            " approximately ${Phonetic.spellDigits(rounded.toString())} miles ahead"
        } else {
            " approximately $rounded miles ahead"
        }
    }

    /**
     * The "how far" clause for the lead report. With a live aircraft fix this is an
     * aircraft-relative distance ("… approximately four zero miles ahead"); without one
     * (aircraft data lost / not connected) it falls back to a route-relative phrase
     * ("… along your route") so the report never presents a distance-from-origin as if it
     * were distance ahead of the aircraft.
     */
    private fun aheadPhrase(item: RideReportItem?, spoken: Boolean): String {
        if (item == null) return ""
        if (item.distanceIsFromAircraft) return distancePhrase(item.distanceAheadNM, spoken)
        return " along your route"
    }

    private fun ceilingCover(m: METAR): String {
        val layer = m.clouds.firstOrNull { it.cover == "BKN" || it.cover == "OVC" }
        return when (layer?.cover) {
            "OVC" -> "overcast"
            "BKN" -> "broken"
            else -> "broken"
        }
    }

    private fun ceilingCoverSpoken(m: METAR): String = ceilingCover(m)
}
