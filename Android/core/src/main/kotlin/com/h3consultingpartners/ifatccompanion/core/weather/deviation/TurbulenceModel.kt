package com.h3consultingpartners.ifatccompanion.core.weather.deviation

import com.h3consultingpartners.ifatccompanion.core.weather.METAR
import com.h3consultingpartners.ifatccompanion.core.weather.RideReportItem
import com.h3consultingpartners.ifatccompanion.core.weather.SIGMET
import com.h3consultingpartners.ifatccompanion.core.weather.TurbulenceSeverity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A composite ride-quality assessment: a continuous index plus the discrete
 * severity it maps to and the human-readable factors that drove it.
 */
data class RideAssessment(
    /** 0 (smooth) … 1 (severe). */
    var index: Double,
    var severity: TurbulenceSeverity,
    var contributors: List<String>,
) {
    companion object {
        val smooth = RideAssessment(index = 0.0, severity = TurbulenceSeverity.SMOOTH, contributors = emptyList())
    }
}

/**
 * A more sophisticated, still fully deterministic ride-quality model. It blends
 * multiple signals — route PIREPs (weighted by distance ahead and report age),
 * SIGMET turbulence/convective advisories, and a low-level wind-shear proxy from
 * the surface METAR — into a single ride index and severity. No AI.
 *
 * Ported from `IFATCCompanion/Weather/TurbulenceModel.swift`.
 */
class TurbulenceModel {

    data class Config(
        /** Distance (NM ahead) at which a PIREP's weight halves. */
        var distanceHalfLifeNM: Double = 120.0,
        /** Report age (minutes) at which a PIREP's weight halves. */
        var ageHalfLifeMin: Double = 90.0,
        /** Below this altitude (ft MSL) surface wind shear is considered relevant. */
        var lowLevelCeilingFt: Double = 10000.0,
    )

    var config: Config = Config()

    /** Produce an overall ride assessment for the current position/altitude. */
    fun assess(
        items: List<RideReportItem>,
        sigmets: List<SIGMET> = emptyList(),
        metar: METAR? = null,
        altitudeFt: Double,
    ): RideAssessment {
        var index = 0.0
        val contributors = mutableListOf<String>()

        // 1. PIREP contribution — take the strongest distance/age-weighted item.
        var bestPirep = 0.0
        for (item in items) {
            val score = weightedScore(item)
            if (score > bestPirep) bestPirep = score
        }
        if (bestPirep > 0) {
            index = max(index, bestPirep)
            contributors.add("pilot reports")
        }

        // 2. SIGMET contribution — turbulence/convective advisories raise the floor.
        sigmetContribution(sigmets)?.let { bump ->
            index = max(index, bump.value)
            contributors.add(bump.label)
        }

        // 3. Low-level wind shear proxy from the surface METAR.
        if (altitudeFt <= config.lowLevelCeilingFt) {
            windShearContribution(metar)?.let { shear ->
                // Additive but capped: shear compounds existing turbulence near the ground.
                index = min(1.0, index + shear.value)
                contributors.add(shear.label)
            }
        }

        index = min(1.0, max(0.0, index))
        return RideAssessment(index = index, severity = severity(index), contributors = contributors)
    }

    // MARK: - Components

    /** Distance- and age-weighted severity fraction (0…1) for a single PIREP item. */
    fun weightedScore(item: RideReportItem): Double {
        val severityFraction = item.severity.rawValue.toDouble() / TurbulenceSeverity.SEVERE.rawValue.toDouble()
        val distance = item.distanceAheadNM ?: 0.0
        val distanceWeight = 0.5.pow(max(0.0, distance) / config.distanceHalfLifeNM)
        val age = item.ageMinutes
        val ageWeight = if (age != null) 0.5.pow(max(0.0, age) / config.ageHalfLifeMin) else 1.0
        return severityFraction * distanceWeight * ageWeight
    }

    /** One weighted contribution: the ride-index value it forces, and the label it earns. */
    private data class Contribution(val value: Double, val label: String)

    private fun sigmetContribution(sigmets: List<SIGMET>): Contribution? {
        // Take the single most significant advisory. The ride-index floor is
        // derived from the same `turbulenceSeverity` used to color the advisory on
        // the map, so a "severe" ride index always corresponds to a red area on the
        // route (and vice versa).
        var value = 0.0
        var label: String? = null
        for (sigmet in sigmets) {
            val floor = rideIndexFloor(sigmet.turbulenceSeverity)
            if (floor > value) {
                value = floor
                label = sigmet.hazardLabel
            }
        }
        return label?.let { Contribution(value, it) }
    }

    /**
     * The composite ride-index floor a SIGMET of the given severity contributes.
     * Chosen so [severity] maps each floor back onto the same severity.
     */
    private fun rideIndexFloor(severity: TurbulenceSeverity): Double = when (severity) {
        TurbulenceSeverity.SMOOTH -> 0.0
        TurbulenceSeverity.LIGHT_CHOP -> 0.25
        TurbulenceSeverity.LIGHT -> 0.45
        TurbulenceSeverity.MODERATE -> 0.6
        TurbulenceSeverity.SEVERE -> 0.8
    }

    private fun windShearContribution(metar: METAR?): Contribution? {
        val m = metar ?: return null
        val speed = m.windSpeed ?: 0
        val gust = m.windGust ?: speed
        val spread = max(0, gust - speed)
        var value = 0.0
        if (spread >= 10) value += 0.3 else if (spread >= 6) value += 0.15
        if (speed >= 25) value += 0.2 else if (speed >= 18) value += 0.1
        if (value <= 0) return null
        return Contribution(min(0.4, value), "surface wind shear")
    }

    /** Map a continuous index onto the discrete severity scale. */
    fun severity(index: Double): TurbulenceSeverity = when {
        index < 0.15 -> TurbulenceSeverity.SMOOTH
        index < 0.35 -> TurbulenceSeverity.LIGHT_CHOP
        index < 0.55 -> TurbulenceSeverity.LIGHT
        index < 0.80 -> TurbulenceSeverity.MODERATE
        else -> TurbulenceSeverity.SEVERE
    }
}
