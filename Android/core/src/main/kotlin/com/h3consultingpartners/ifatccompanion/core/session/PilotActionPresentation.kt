package com.h3consultingpartners.ifatccompanion.core.session

/**
 * How each pilot action is labelled and ordered on the Responses card.
 *
 * The labels are verbatim from the iOS ATC view; the icon is a semantic key the Compose
 * layer maps to a Material Symbol, because SF Symbols do not exist on Android. The
 * mapping is recorded in Docs/ANDROID_PARITY_MATRIX.md.
 */
object PilotActionPresentation {

    /** Emphasis the button is drawn with, matching the iOS tint. */
    enum class Emphasis { DEFAULT, POSITIVE, CAUTION, DESTRUCTIVE }

    data class Presentation(
        val title: String,
        val iconKey: String,
        val emphasis: Emphasis = Emphasis.DEFAULT,
    )

    /**
     * Canonical display order for the response buttons (gate-to-gate, then the
     * enroute/arrival requests). The grid renders whichever of these are currently
     * available for the tuned controller and phase.
     */
    val orderedActions: List<PilotAction> = listOf(
        PilotAction.CLEARANCE,
        PilotAction.PUSHBACK,
        PilotAction.ENGINE_START,
        PilotAction.TAXI,
        PilotAction.READY,
        PilotAction.TAKEOFF,
        PilotAction.TO_GATE,
        PilotAction.CHECK_IN,
        PilotAction.GO_AROUND,
        PilotAction.REQUEST_HIGHER,
        PilotAction.REQUEST_LOWER,
        PilotAction.ACCEPT_SMOOTHER_ALTITUDE,
        PilotAction.VECTORS,
        PilotAction.APPROACH,
        PilotAction.RIDE_REPORT,
        PilotAction.DEST_WX,
    )

    fun presentation(action: PilotAction): Presentation = when (action) {
        //                                                              iOS SF Symbol
        PilotAction.CLEARANCE -> Presentation("Clearance", "description") // doc.text
        PilotAction.PUSHBACK -> Presentation("Pushback", "first_page") // arrow.left.to.line
        PilotAction.ENGINE_START -> Presentation("Engine Start", "power") // powerplug
        PilotAction.TAXI -> Presentation("Taxi", "directions_car") // car
        PilotAction.READY -> Presentation("Ready", "flag") // flag.checkered
        PilotAction.TAKEOFF -> Presentation(
            "Takeoff", "flight_takeoff", Emphasis.POSITIVE,
        ) // airplane.departure, green
        PilotAction.REQUEST_HIGHER -> Presentation("Request Higher", "arrow_upward") // arrow.up
        PilotAction.REQUEST_LOWER -> Presentation("Request Lower", "arrow_downward") // arrow.down
        PilotAction.VECTORS -> Presentation(
            "Vectors", "turn_right",
        ) // arrow.triangle.turn.up.right.diamond
        PilotAction.APPROACH -> Presentation("Approach", "flight_land") // airplane.arrival
        PilotAction.ACCEPT_SMOOTHER_ALTITUDE -> Presentation(
            "Smoother Altitude", "arrow_circle_up", Emphasis.POSITIVE,
        )
        PilotAction.RIDE_REPORT -> Presentation("Ride Report", "air") // wind
        PilotAction.DEST_WX -> Presentation("Dest Wx", "partly_cloudy_day") // cloud.sun
        PilotAction.CHECK_IN -> Presentation("Check In", "record_voice_over") // person.wave.2
        PilotAction.TO_GATE -> Presentation("To Gate", "local_parking") // parkingsign
        PilotAction.GO_AROUND -> Presentation(
            "Go Around", "u_turn_left", Emphasis.CAUTION,
        ) // arrow.uturn.up, orange
    }

    /** The three acknowledgements, always available beneath the request grid. */
    enum class Acknowledgement(
        val title: String,
        val iconKey: String,
        val emphasis: Emphasis,
    ) {
        READ_BACK("Read Back", "check_circle", Emphasis.POSITIVE),
        SAY_AGAIN("Say Again", "undo", Emphasis.DEFAULT),
        UNABLE("Unable", "dangerous", Emphasis.DESTRUCTIVE),
    }

    // region Verbatim copy from the iOS ATC view

    const val NO_REQUESTS_HINT =
        "No requests right now — read back or wait for the next call."

    const val STANDBY_HINT = "Follow the live controller."

    const val UNICOM_REMINDER =
        "Remember to continue using all proper Unicom calls throughout your flight."

    const val TUNE_FREQUENCY_HINT =
        "Only the controllers you need now are shown. Tap one to change frequency, then " +
            "tap Check In to call them or make a request."

    const val AWAITING_FIRST_TRANSMISSION = "Awaiting first transmission…"

    const val NO_MESSAGES = "No messages yet."

    const val SUBSCRIBE_BANNER = "Live Mode locked — Subscribe"

    const val PUSH_TO_TALK_IDLE = "Hold to Talk"

    const val PUSH_TO_TALK_LISTENING = "Listening — release to send"

    const val PUSH_TO_TALK_HINT = "Press and hold to speak a readback or request."

    /**
     * Android's counterpart to the iOS "Enable Speech Recognition & Microphone in
     * Settings" message. Android has one permission for this, not two.
     */
    const val PUSH_TO_TALK_PERMISSION_DENIED =
        "Allow microphone access in Settings to use push-to-talk."

    const val CLEAR_FLIGHT_RESET =
        "Resets the conversation and starts a new flight from the gate. Your settings " +
            "and flight plan are kept."

    const val CLEAR_FLIGHT_UNSAVED = "This flight hasn't been saved and will be lost."

    fun clearFlightRetiredMessage(savedName: String): String =
        "This flight is complete, so “$savedName” will be removed from your saved " +
            "flights. $CLEAR_FLIGHT_RESET"

    // endregion
}
