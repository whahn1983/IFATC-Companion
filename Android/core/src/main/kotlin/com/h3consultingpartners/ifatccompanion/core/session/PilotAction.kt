package com.h3consultingpartners.ifatccompanion.core.session

/**
 * A pilot response-button action.
 *
 * Ported from the `PilotAction` enum in `IFATCCompanion/App/AppModel.swift`.
 */
enum class PilotAction {
    CLEARANCE,
    PUSHBACK,
    ENGINE_START,
    TAXI,
    READY,
    TAKEOFF,
    REQUEST_HIGHER,
    REQUEST_LOWER,
    VECTORS,
    APPROACH,
    RIDE_REPORT,
    DEST_WX,
    CHECK_IN,
    TO_GATE,

    /**
     * Break off the approach and fly the missed-approach / go-around pattern (shown only
     * while airborne, inbound to land on the Tower frequency).
     */
    GO_AROUND,

    /**
     * Accept the smoother cruise altitude the last ride report suggested (shown only
     * while such a suggestion is active).
     */
    ACCEPT_SMOOTHER_ALTITUDE,
}

/**
 * A pilot response-button action for the simulated weather-deviation flow. Kept separate
 * from [PilotAction] so the gate-to-gate button logic is untouched; surfaced only while
 * a route-weather conflict / deviation is active.
 */
enum class WeatherDeviationAction {
    ASK_CENTER,
    REQUEST_RIGHT_DEVIATION,
    REQUEST_LEFT_DEVIATION,
    REQUEST_VECTOR,
    REQUEST_HIGHER,
    REQUEST_LOWER,
    CLEAR_OF_WEATHER,
    CONTINUE_ON_COURSE,
    SAY_AGAIN,
}
