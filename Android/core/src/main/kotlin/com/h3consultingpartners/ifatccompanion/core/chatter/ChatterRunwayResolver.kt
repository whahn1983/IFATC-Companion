package com.h3consultingpartners.ifatccompanion.core.chatter

import com.h3consultingpartners.ifatccompanion.core.atis.AirportATIS
import com.h3consultingpartners.ifatccompanion.core.atis.ATISRunwayParser
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan

/**
 * Which runways the background chatter is allowed to name.
 *
 * The generator falls back to a random runway when it has no pool, so with nothing bound it
 * will happily clear simulated traffic onto "runway one eight" at a field with no runway 18
 * — which contradicts the ATIS the app has just read the pilot, and is precisely the
 * behaviour the feature exists to prevent.
 *
 * Ported from `AppModel.chatterRunwayContext()` (IFATCCompanion/App/AppModel.swift:1178),
 * `atisReport(forICAO:)` (:1194), `reconcileRunways` (:1206), `orderedRunwayUnion` (:1214)
 * and `chatterAirportICAO` (:1220).
 *
 * The precedence is: the ATIS's active departure and arrival runways, reconciled against the
 * field's OpenStreetMap runway set so a parse slip can never name a runway that is not
 * there; then the field's full runway set; then nothing, which is the generator's cue to
 * pick something plausible at random.
 */
object ChatterRunwayResolver {

    /**
     * The field whose runways the chatter simulates: the destination once the flight is
     * descending or being worked by Approach, otherwise the origin.
     */
    fun airport(plan: FlightPlan, facility: ATCFacility, phase: FlightPhase): String {
        if (facility == ATCFacility.APPROACH) return plan.destination
        return when (phase) {
            FlightPhase.DESCENT, FlightPhase.APPROACH, FlightPhase.LANDING,
            FlightPhase.TAXI_IN, FlightPhase.PARKED,
            -> plan.destination

            else -> plan.departure
        }
    }

    /**
     * The runway pools for [icao].
     *
     * [fieldRunways] is the field's own runway-end idents from its loaded surface extract;
     * [atis] is whichever report covers that field, if the app is holding one.
     */
    fun context(
        icao: String,
        fieldRunways: List<String>,
        atis: AirportATIS?,
    ): ChatterRunwayContext {
        if (icao.isBlank()) return ChatterRunwayContext()
        if (atis == null) return ChatterRunwayContext(all = fieldRunways)

        val active = ATISRunwayParser.activeRunways(atis)
        val departures = reconcile(active.departures, fieldRunways)
        val arrivals = reconcile(active.arrivals, fieldRunways)
        val union = orderedUnion(departures, arrivals)
        return ChatterRunwayContext(
            all = union.ifEmpty { fieldRunways },
            departures = departures,
            arrivals = arrivals,
        )
    }

    /** The report covering a field, matched by ICAO. Null when the app holds none for it. */
    fun reportFor(icao: String, departure: AirportATIS?, arrival: AirportATIS?): AirportATIS? {
        val key = icao.uppercase().trim()
        if (key.isEmpty()) return null
        departure?.takeIf { it.airport.uppercase() == key }?.let { return it }
        arrival?.takeIf { it.airport.uppercase() == key }?.let { return it }
        return null
    }

    /**
     * Keep only the ATIS runways the field's map confirms.
     *
     * With no map loaded the ATIS runways are trusted as they are — they are real, they came
     * off a published report. An empty result means "fall back to the field set" rather than
     * "this field has no runways".
     */
    private fun reconcile(atisRunways: List<String>, field: List<String>): List<String> {
        if (atisRunways.isEmpty()) return emptyList()
        if (field.isEmpty()) return atisRunways
        val known = field.map(ATISRunwayParser::canonical).toSet()
        return atisRunways.filter { ATISRunwayParser.canonical(it) in known }
    }

    /** Departures then arrivals, de-duplicated in first-seen order. */
    private fun orderedUnion(a: List<String>, b: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return (a + b).filter { seen.add(ATISRunwayParser.canonical(it)) }
    }
}
