package com.h3consultingpartners.ifatccompanion.core.atc

import kotlin.math.abs

/**
 * A planned taxi route: an ordered list of taxiway identifiers (single-letter codes
 * spoken as "Alpha", "Bravo", …), an optional runway to cross, and the taxiway used
 * to/from the ramp.
 *
 * Ported from `IFATCCompanion/ATC/TaxiRoutePlanner.swift`. This is the *fallback*
 * planner, used when no OpenStreetMap surface graph is available for the field; the
 * real route comes from `core.surface`.
 */
data class TaxiPlan(
    val taxiways: List<String>,
    val crossingRunway: String?,
    val parkingTaxiway: String,
) {
    /**
     * Comma-joined taxiway codes for display + (phonetically) for speech.
     * e.g. ["A", "C"] -> "A, C" which is spoken "Alpha Charlie".
     */
    val taxiwaysText: String
        get() = if (taxiways.isEmpty()) "available taxiways" else taxiways.joinToString(", ")
}

/**
 * A simplified model of an airport's movement surface: its taxiway codes, the ramp
 * taxiway, per-runway taxi routes, and any runway that must be crossed to reach a given
 * runway. Used to produce realistic taxi instructions.
 */
data class AirportLayout(
    val icao: String,
    val taxiways: List<String>,
    val rampTaxiway: String,
    /** Runway identifier -> ordered taxiway codes from the ramp to that runway. */
    val runwayRoutes: Map<String, List<String>>,
    /** Runway identifier -> runway that must be crossed en route. */
    val crossings: Map<String, String>,
) {
    /** A copy with a single generated route for the given runway. */
    internal fun replacingFallbackRoute(
        runway: String,
        ramp: String,
        feeder: String,
    ): AirportLayout = copy(
        rampTaxiway = ramp,
        runwayRoutes = runwayRoutes + (
            runway to if (ramp == feeder) listOf(ramp) else listOf(ramp, feeder)
            ),
    )
}

/**
 * Produces deterministic taxi routes from a small built-in surface model, with a stable
 * generated fallback for airports not in the library. No AI.
 */
class TaxiRoutePlanner {

    fun plan(airport: String, runway: String, arrival: Boolean): TaxiPlan {
        val icao = airport.uppercase()
        val layout = layouts[icao] ?: generatedLayout(icao, runway)

        if (arrival) {
            // Arrivals roll out and taxi to parking via the ramp taxiway, plus one feeder
            // taxiway if the runway has a known route.
            val feeder = layout.runwayRoutes[runway]?.lastOrNull()
            val taxiways = mutableListOf(layout.rampTaxiway)
            if (feeder != null && feeder != layout.rampTaxiway) taxiways.add(0, feeder)
            return TaxiPlan(
                taxiways = taxiways,
                crossingRunway = null,
                parkingTaxiway = layout.rampTaxiway,
            )
        }

        val route = layout.runwayRoutes[runway] ?: defaultRoute(layout, runway)
        return TaxiPlan(
            taxiways = route,
            crossingRunway = layout.crossings[runway],
            parkingTaxiway = layout.rampTaxiway,
        )
    }

    /**
     * A deterministic route when the specific runway isn't in the layout: pick the ramp
     * taxiway plus one taxiway chosen by the runway number so it's stable.
     */
    private fun defaultRoute(layout: AirportLayout, runway: String): List<String> {
        val others = layout.taxiways.filter { it != layout.rampTaxiway }
        if (others.isEmpty()) return listOf(layout.rampTaxiway)
        val seed = abs(runwayNumber(runway))
        return listOf(layout.rampTaxiway, others[seed % others.size])
    }

    private fun runwayNumber(runway: String): Int =
        runway.takeWhile { it.isDigit() }.toIntOrNull() ?: 0

    companion object {
        val layouts: Map<String, AirportLayout> = mapOf(
            "KIAH" to AirportLayout(
                icao = "KIAH",
                taxiways = listOf("A", "B", "C", "E", "WB", "NB"),
                rampTaxiway = "A",
                runwayRoutes = mapOf(
                    "15L" to listOf("A", "B"), "15R" to listOf("A", "C"),
                    "26L" to listOf("A", "E"), "26R" to listOf("A", "WB"),
                    "33L" to listOf("A", "C"), "33R" to listOf("A", "B"),
                ),
                crossings = mapOf("15R" to "15L", "33L" to "33R"),
            ),
            "KMSP" to AirportLayout(
                icao = "KMSP",
                taxiways = listOf("A", "B", "C", "G", "P", "Q"),
                rampTaxiway = "A",
                runwayRoutes = mapOf(
                    "12L" to listOf("A", "G"), "12R" to listOf("A", "B"),
                    "30L" to listOf("A", "B"), "30R" to listOf("A", "G"),
                    "04" to listOf("A", "P"), "22" to listOf("A", "Q"),
                ),
                crossings = mapOf("30R" to "30L", "12L" to "12R"),
            ),
            "KDEN" to AirportLayout(
                icao = "KDEN",
                taxiways = listOf("A", "B", "C", "M", "WC", "EC"),
                rampTaxiway = "A",
                runwayRoutes = mapOf(
                    "34L" to listOf("A", "M"), "34R" to listOf("A", "C"),
                    "16L" to listOf("A", "C"), "16R" to listOf("A", "M"),
                    "07" to listOf("A", "WC"), "25" to listOf("A", "EC"),
                ),
                crossings = mapOf("16L" to "16R"),
            ),
        )

        /**
         * A stable generated layout for unknown airports so taxi instructions still sound
         * plausible. Deterministic from the ICAO + runway.
         */
        fun generatedLayout(icao: String, runway: String): AirportLayout {
            val pool = listOf("A", "B", "C", "D", "E", "F", "G")
            // Deterministic seed from the ICAO characters (stable across launches).
            val seed = icao.sumOf { it.code }
            val ramp = pool[seed % pool.size]
            val feeder = pool[(seed / 7 + 2) % pool.size]
            return AirportLayout(
                icao = icao,
                taxiways = pool,
                rampTaxiway = ramp,
                runwayRoutes = emptyMap(),
                crossings = emptyMap(),
            ).replacingFallbackRoute(runway, ramp, feeder)
        }
    }
}
