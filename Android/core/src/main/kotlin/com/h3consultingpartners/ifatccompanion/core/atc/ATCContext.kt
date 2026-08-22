package com.h3consultingpartners.ifatccompanion.core.atc

import com.h3consultingpartners.ifatccompanion.core.airports.RampProfile
import com.h3consultingpartners.ifatccompanion.core.airports.RampType
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyEngine
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyProcedure

/**
 * Context the state machine and phraseology engine need to compose realistic
 * instructions. Populated by the flight-session coordinator from the flight plan,
 * weather, and deterministic defaults.
 *
 * Ported from the `ATCContext` struct in `IFATCCompanion/ATC/ATCStateMachine.swift`.
 */
data class ATCContext(
    val callsign: PhraseologyEngine.Callsign,
    val plan: FlightPlan,
    val assignedAltitude: Int,
    val cruiseAltitude: Int,
    val initialClimbAltitude: Int,
    val windDirection: Int,
    val windSpeed: Int,
    val squawk: String,
    val runway: String,
    val taxiway: String,
    val crossingRunway: String?,
    val parkingTaxiway: String,
    val approachName: String,
    val departureFrequency: Double,
    val centerFrequency: Double,
    val approachFrequency: Double,
    val towerFrequency: Double,
    val groundFrequency: Double,
    /** Ramp/apron frequency used for the simulated (non-FAA) ramp conversation. */
    val rampFrequency: Double = 131.0,
    /**
     * Resolved ramp behaviour for this airport (push approval, spots, directions).
     * Defaults to the generic airline ramp profile when no airport profile exists.
     */
    val rampProfile: RampProfile = RampProfile.generic,
    /** Pushback tail/face direction ("west", "east", …) when known, else "". */
    val pushDirection: String = "",
    /** Ramp spot name used for the Ramp→Ground handoff ("5", "spot 5"), else "". */
    val rampSpot: String = "",
    /** Gate/stand identifier ("B44") when known, else "". */
    val gate: String = "",
    /**
     * Initial assigned heading after departure (bearing to the first fix / route
     * intercept). 0 when unknown — the takeoff clearance then says "runway heading".
     */
    val departureHeading: Int = 0,
    /**
     * Whether [runway] is a runway the field actually has (filed, typed, or from the
     * built-in runway inventory) rather than a number the app derived from the wind
     * because nothing named one. Only a real runway's ident carries a real heading, so
     * only then may the takeoff clearance replace the departure vector with "fly runway
     * heading" on the strength of the two being close.
     */
    val runwayIsKnown: Boolean = true,
    /** Name of the first enroute fix, used for "resume own navigation, direct …". */
    val firstFixName: String = "",
    /**
     * Altitude (ft MSL) up to which Departure works the climb before handing to Center.
     * Default 18,000 (FL180). Configurable in settings.
     */
    val traconCeiling: Int = DEFAULT_TRACON_CEILING,
    /**
     * Intercept/initial altitude (ft MSL) Approach assigns for the ILS/GPS/Visual — the
     * first altitude in the approach section of the flight plan when known, otherwise 0
     * (the state machine then falls back to [approachDefaultAltitude]).
     */
    val approachInterceptAltitude: Int = 0,
    /**
     * Fallback terminal altitude (ft MSL) Approach assigns when the flight plan supplies
     * no intercept altitude: 3,000 ft above the field, expressed in MSL and rounded up to
     * the next thousand, so it clears the ground at high-elevation airports (e.g. 9,000 ft
     * at Denver). Defaults to 3,000 ft (sea-level assumption); the coordinator recomputes
     * it from live telemetry.
     */
    val approachDefaultAltitude: Int = DEFAULT_APPROACH_ALTITUDE,
    // Parsed published procedures (optional; populated when the pilot enters them).
    val sidProcedure: PhraseologyProcedure? = null,
    val starProcedure: PhraseologyProcedure? = null,
    val approachProcedure: PhraseologyProcedure? = null,
) {

    /**
     * Whom the pilot contacts for pushback: Ramp when the airport has a ramp/apron layer
     * (the common commercial case), otherwise Ground directly. Clearance Delivery
     * announces this at the end of the IFR clearance so the pilot knows which frequency
     * to tune for the push.
     */
    val pushbackFacility: ATCFacility
        get() = if (rampProfile.rampType == RampType.NONE) ATCFacility.GROUND else ATCFacility.RAMP

    /** Frequency for the pushback facility resolved by [pushbackFacility]. */
    val pushbackFrequency: Double
        get() = if (pushbackFacility == ATCFacility.GROUND) groundFrequency else rampFrequency

    companion object {
        const val DEFAULT_TRACON_CEILING = 18_000
        const val DEFAULT_APPROACH_ALTITUDE = 3_000
        const val DEFAULT_RAMP_FREQUENCY = 131.0
    }
}
