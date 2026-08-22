package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.mock.MockSimulatorFeed
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.phraseology.AirlineDatabase
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings

/**
 * Builds the active flight plan from the pilot's saved fields, filling the blanks from
 * the demo route while Mock Mode is on.
 *
 * Ported from `AppModel.syncFlightPlanFromSettings()` (IFATCCompanion/App/AppModel.swift:4247)
 * and the identity half of `applyFlightIdentity(to:)` (:2942). It is a pure function here
 * rather than a method that mutates a published property, because everything it decides is
 * decidable from its two inputs — which is what makes the demo's identity rules assertable
 * without a running session.
 *
 * The precedence is the same at every field: what the pilot typed wins, and the demo route
 * only supplies what was left blank. That ordering is not cosmetic. The controller composes
 * every call from the airline/flight-number pair and falls back to the raw callsign only
 * when that pair is empty, so a demo default that beat a typed callsign would keep the
 * flight flying as United 598 however the Callsign field read.
 */
object FlightPlanComposer {

    /** The identity the demo flies under when the pilot has said nothing about who they are. */
    const val MOCK_AIRLINE = "United"
    const val MOCK_FLIGHT_NUMBER = "598"

    fun plan(
        settings: AppSettings,
        mockRoute: MockSimulatorFeed.Route,
        mockMode: Boolean = settings.mockMode,
    ): FlightPlan {
        var plan = identity(settings)

        // Mock Mode flies the demo as United 598 — but only when the pilot has said nothing
        // at all about who they are. A callsign entered for the demo must not lose to this.
        if (mockMode && plan.callsign.isEmpty()) {
            if (plan.airline.isEmpty()) plan = plan.copy(airline = MOCK_AIRLINE)
            if (plan.flightNumber.isEmpty()) plan = plan.copy(flightNumber = MOCK_FLIGHT_NUMBER)
        }

        return plan.copy(
            departure = if (settings.departure.isEmpty() && mockMode) mockRoute.departure else settings.departure,
            destination = if (settings.destination.isEmpty() && mockMode) mockRoute.destination else settings.destination,
            alternate = settings.alternate,
            cruiseAltitude = when {
                settings.cruiseAltitude > 0 -> settings.cruiseAltitude
                mockMode -> mockRoute.cruiseAltitude
                else -> 0
            },
            runway = settings.runway,
            sid = settings.sid,
            star = settings.star,
            approach = settings.approach,
            // In Mock Mode default to the route's realistic United stand so the demo taxis
            // from and to a plausible gate; any gate the pilot enters wins.
            departureGate = if (settings.departureGate.isEmpty() && mockMode) {
                mockRoute.departureGate
            } else {
                settings.departureGate
            },
            arrivalGate = if (settings.arrivalGate.isEmpty() && mockMode) {
                mockRoute.arrivalGate
            } else {
                settings.arrivalGate
            },
            manualOverride = settings.departure.isNotEmpty() || settings.destination.isNotEmpty(),
            waypoints = if (mockMode) mockRoute.waypoints else emptyList(),
        )
    }

    /**
     * Who the flight is, resolved from the pilot's three identity fields.
     *
     * An explicit airline or flight number is taken as entered. With neither, a callsign
     * like "UAL598" is parsed into the pair — and a callsign that parses to nothing leaves
     * the pair empty rather than stale, so the controller falls back to reading the raw
     * callsign instead of announcing an airline the pilot never typed.
     */
    fun identity(settings: AppSettings): FlightPlan {
        val callsign = settings.callsign.trim()
        val airline = settings.airline.trim()
        val flightNumber = settings.flightNumber.trim()

        if (airline.isNotEmpty() || flightNumber.isNotEmpty()) {
            return FlightPlan(
                callsign = callsign,
                airline = airline,
                flightNumber = flightNumber,
            )
        }
        if (callsign.isEmpty()) return FlightPlan()

        val parsed = AirlineDatabase.parse(callsign)
        return FlightPlan(
            callsign = callsign,
            airline = parsed?.telephony.orEmpty(),
            flightNumber = parsed?.flightNumber.orEmpty(),
        )
    }

    /**
     * Whether the change from [previous] to [next] moves the route the weather sample was
     * taken over, so the cached radar sample has to be discarded rather than reused.
     */
    fun routeChanged(previous: FlightPlan, next: FlightPlan): Boolean =
        previous.departure != next.departure ||
            previous.destination != next.destination ||
            previous.waypoints.map { it.name } != next.waypoints.map { it.name }
}
