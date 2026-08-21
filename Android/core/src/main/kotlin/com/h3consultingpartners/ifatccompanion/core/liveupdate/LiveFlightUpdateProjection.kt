package com.h3consultingpartners.ifatccompanion.core.liveupdate

import com.h3consultingpartners.ifatccompanion.core.config.AppConfig
import com.h3consultingpartners.ifatccompanion.core.session.FlightSessionState
import com.h3consultingpartners.ifatccompanion.core.session.PilotAction
import kotlin.math.roundToInt

/**
 * Projects the flight session onto the Live Flight Update the notification shows.
 *
 * It lives in `:core` rather than in the notification code for the usual reason: deciding
 * *what the pilot is told while the phone is in their pocket* is a product rule, not a
 * rendering detail, and it is the one place a wrong answer is least visible. Everything
 * here is tested; only the drawing of it is Android's.
 */
object LiveFlightUpdateProjection {

    /**
     * Build the update for a session snapshot.
     *
     * Two rules are worth stating because they are easy to get wrong:
     *
     *  - **The actions offered must be the ones the engine will actually accept.** A
     *    "Read Back" button that does nothing because the gate is already open is worse
     *    than no button, so they mirror the session's own availability rather than being
     *    inferred from the phase.
     *  - **Standby suppresses both.** While a human controller is working the frequency
     *    the companion is silent, and a notification action would be the one way it could
     *    still talk over them.
     */
    fun from(state: FlightSessionState, nowMillis: Long): LiveFlightUpdate {
        val plan = state.flightPlan
        val aircraft = state.aircraftState
        val callsign = plan.callsign.ifEmpty {
            (plan.airline + plan.flightNumber).ifEmpty { DEFAULT_CALLSIGN }
        }
        val standby = state.companionStandby

        return LiveFlightUpdate(
            flightTitle = "${AppConfig.App.NAME} · $callsign",
            phase = state.phase.title,
            facility = state.currentFacility.title,
            facilityIconKey = state.currentFacility.iconKey,
            altitude = aircraft.altitudeMSL?.roundToInt() ?: 0,
            heading = aircraft.heading?.roundToInt()?.mod(360) ?: 0,
            speed = aircraft.groundSpeed?.roundToInt() ?: 0,
            callsign = callsign,
            route = route(plan.departure, plan.destination),
            // Only a genuinely pending hand-off is named — the facility the pilot is
            // already on is not "next", and showing it as such would read as an
            // instruction they had missed.
            nextFacility = state.pendingCheckInFacility
                ?.takeIf { it != state.currentFacility }
                ?.title,
            weatherAlert = null,
            canReadBack = !standby && state.awaitingReadback,
            canCheckIn = !standby && PilotAction.CHECK_IN in state.availableActions,
            standby = standby,
            asOfMillis = nowMillis,
        )
    }

    /** "KIAH → KMSP", with either side allowed to be blank. */
    internal fun route(departure: String, destination: String): String = when {
        departure.isEmpty() && destination.isEmpty() -> ""
        departure.isEmpty() -> destination
        destination.isEmpty() -> departure
        else -> "$departure $ROUTE_ARROW $destination"
    }

    private const val ROUTE_ARROW = "→"

    /** Shown before a flight plan names the aircraft, so the card is never blank. */
    const val DEFAULT_CALLSIGN = "Flight"
}
