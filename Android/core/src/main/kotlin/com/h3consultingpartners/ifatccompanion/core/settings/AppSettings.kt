package com.h3consultingpartners.ifatccompanion.core.settings

import com.h3consultingpartners.ifatccompanion.core.config.AppConfig
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.phraseology.CallsignDigitStyle
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyMode

/**
 * Every persisted user preference, as one immutable value.
 *
 * Ported from `IFATCCompanion/Settings/AppSettings.swift`, which is an
 * `ObservableObject` whose `@Published` properties write themselves back to
 * `UserDefaults` in `didSet`. The Kotlin port splits that in two: this data class is
 * the state, and [SettingsRepository] owns the store, the load, the migrations and
 * the one-`StateFlow`-per-feature publishing the porting guide asks for. Nothing
 * mutates a settings value in place, so a screen re-renders once per change instead
 * of once per property.
 *
 * **Every default here is the Swift's default**, to the digit. The user-facing label
 * each setting carries on the iOS Settings screen is quoted in its doc comment and
 * declared once in [SettingsLabels], so `:app` renders the same words iOS does.
 */
data class AppSettings(

    // region Connection

    /** Settings label: "Host / IP" (placeholder "192.168.1.20"). */
    val host: String = "",

    /** Settings label: "Port". Infinite Flight's Connect API v2 port. */
    val port: Int = 10112,

    /** Settings label: "Auto-discover on local network". */
    val autoDiscover: Boolean = true,

    /**
     * Keep the screen awake while the app is open. Infinite Flight drops the
     * Connect link when the companion device's screen locks, so this defaults on.
     *
     * Settings label: "Keep screen awake".
     */
    val keepScreenAwake: Boolean = true,

    // endregion

    // region Manual flight overrides

    val callsign: String = "",
    val airline: String = "",
    val flightNumber: String = "",
    val departure: String = "",
    val destination: String = "",
    val alternate: String = "",
    val cruiseAltitude: Int = 0,
    val runway: String = "",
    val sid: String = "",
    val star: String = "",
    val approach: String = "",

    /**
     * Departure gate / stand the pushback is requested from (manual-override only;
     * IF doesn't expose it).
     */
    val departureGate: String = "",

    /** Arrival gate / stand to taxi to (manual-override only; IF doesn't expose it). */
    val arrivalGate: String = "",

    /**
     * Marker (`ICAO:GATE`) recording the departure gate the app assigned itself, so the
     * automatic assignment can tell its own value apart from one the pilot typed. Not a
     * user-facing preference — see `GateAssigner.mayAssign`.
     */
    val autoAssignedDepartureGate: String = "",

    /** Marker for the arrival gate the app assigned itself (see above). */
    val autoAssignedArrivalGate: String = "",

    // endregion

    // region Voice

    /** Settings label: "Voice enabled". */
    val voiceEnabled: Boolean = true,

    /** Settings label: "Default voice". Empty means the platform's default voice. */
    val defaultVoiceID: String = "",

    /** Settings label: "Speech rate: <n>". */
    val speechRate: Double = 0.5,

    /** Settings label: "Pitch: <n>", slider range [SPEECH_PITCH_RANGE]. */
    val speechPitch: Double = 1.0,

    /**
     * Voice playback volume (0…1) applied to every spoken transmission. Kept
     * independent of the device volume so it stays consistent across PTT/system
     * audio interruptions.
     *
     * Settings label: "Volume: <n>%".
     */
    val voiceVolume: Double = 1.0,

    /** Settings label: "Respect silent switch". */
    val respectSilentSwitch: Boolean = false,

    /** Settings label: "Ground" (Controller Voice per Facility). */
    val voiceGround: String = "",

    /** Settings label: "Tower". */
    val voiceTower: String = "",

    /** Settings label: "Departure". */
    val voiceDeparture: String = "",

    /** Settings label: "Center". */
    val voiceCenter: String = "",

    /** Settings label: "Approach". */
    val voiceApproach: String = "",

    /** Voice used for the one-way ATIS broadcast (configurable like the frequencies). */
    val voiceATIS: String = "",

    /** Voice used for the pilot's own transmissions (readbacks/requests). */
    val voicePilot: String = "",

    /**
     * Speak the pilot's readbacks/requests aloud when they are triggered by a
     * button/text tap. Push-to-talk input is never re-spoken (the user already
     * said it).
     *
     * Settings label: "Speak pilot readbacks".
     */
    val speakPilot: Boolean = true,

    /**
     * Show the "Hold to Talk" push-to-talk button in the ATC responses card. On by
     * default; turn off to hide the button for those who don't use voice input and
     * keep hitting it by accident.
     *
     * Settings label: "Hold to Talk button".
     */
    val holdToTalkEnabled: Boolean = true,

    // endregion

    // region Phraseology

    /** Settings label: "Mode". Declared in `core.phraseology`, not here. */
    val phraseologyMode: PhraseologyMode = PhraseologyMode.FAA,

    /** Settings label: "Flight number style". Declared in `core.phraseology`, not here. */
    val digitStyle: CallsignDigitStyle = CallsignDigitStyle.GROUPED,

    // endregion

    // region Background radio chatter & Live Activity

    /**
     * Play ambient, randomly-generated background ATC radio chatter — quiet,
     * static-wrapped transmissions bounded to the frequency the pilot is tuned to.
     * This is also what keeps the app running (and audio flowing) in the background,
     * so live callbacks no longer stall when you switch apps or lock the screen.
     *
     * Settings label: "Background radio chatter". Interlocked with
     * [liveActivityEnabled] — see [SettingsRepository.setBackgroundChatterEnabled].
     */
    val backgroundChatterEnabled: Boolean = false,

    /**
     * Show a live-updating Lock Screen / Dynamic Island notification for the flight
     * (phase, altitude, heading, controller, weather) with Read Back / Check In
     * buttons. Requires background chatter, which supplies the continuous audio that
     * keeps the flight updating while the app is backgrounded.
     *
     * Settings label: "Live flight notification".
     */
    val liveActivityEnabled: Boolean = false,

    /**
     * Loudness of the background chatter bed (0…1). Deliberately low so it sits under
     * the real ATC calls.
     *
     * Settings label: "Chatter volume", slider range [CHATTER_VOLUME_RANGE].
     */
    val chatterVolume: Double = 0.16,

    /** How busy the simulated chatter frequency sounds. Settings label: "Traffic level". */
    val chatterDensity: ChatterDensity = ChatterDensity.MODERATE,

    /**
     * Bracket the pilot's own transmissions with a short mic-key / squelch static
     * burst so keying up sounds like a real radio.
     *
     * Settings label: "Radio voice effect".
     */
    val transmissionStaticEnabled: Boolean = true,

    // endregion

    // region ATC automation

    /**
     * Initial climb height (ft above field) assigned in the clearance/takeoff
     * before Departure. Added to the departure field elevation and rounded up to
     * the next thousand for the MSL callout, so it stays valid at high-elevation
     * airports.
     *
     * Settings label: "Initial climb: <n> ft above field", stepper
     * [INITIAL_CLIMB_RANGE_FT] step [INITIAL_CLIMB_STEP_FT].
     */
    val initialClimbAltitudeFt: Int = 5000,

    /**
     * Flight level at which Departure hands off to Center (TRACON ceiling), e.g. 180.
     *
     * Settings label: "Departure → Center at FL<n>", stepper [TRACON_CEILING_RANGE_FL]
     * step [TRACON_CEILING_STEP_FL].
     */
    val traconCeilingFL: Int = 180,

    /**
     * Auto-tune the radio to the next controller when the pilot reads back a frequency
     * hand-off. On by default: the active frequency follows the hand-off, but only once
     * the pilot has read it back — never the moment the controller issues it. When off,
     * nothing tunes on its own; the pilot changes every frequency by hand with the tune
     * buttons.
     *
     * Settings label: "Auto-tune on hand-off".
     */
    val autoTuneOnHandoff: Boolean = true,

    /**
     * Hand the flight from one enroute Center sector to the next as it crosses the
     * boundaries — "contact Fort Worth Center on 133.425" leaving Houston's airspace.
     * On by default. Only ever applies to the enroute leg (from the Departure hand-off
     * at the TRACON ceiling until Approach takes over); with it off, one generic
     * "Center" works the whole flight, as before.
     *
     * Settings label: "Center sector hand-offs".
     */
    val centerSectorHandoffs: Boolean = true,

    // endregion

    // region Airport surface (OpenStreetMap taxi routing)

    /**
     * Issue the runway-crossing clearances automatically as an OSM taxi route reaches each
     * hold-short, rather than waiting for the pilot to tap Request Crossing. On by default.
     * Applied to the surface coordinator's `autoCrossingCalls` by the flight coordinator.
     *
     * Settings label: "Automatic runway-crossing calls".
     */
    val taxiAutoCrossingCalls: Boolean = true,

    /**
     * Re-plan the taxi route automatically when the aircraft leaves it, instead of latching
     * the off-route banner for the pilot to decide. Off by default. Applied to the surface
     * coordinator's `autoRecalculate` by the flight coordinator.
     *
     * Settings label: "Auto-recalculate when off route".
     */
    val taxiAutoRecalculate: Boolean = false,

    /**
     * Fill a **blank** departure/arrival gate field with a realistic stand taken from the
     * airport's OpenStreetMap stand data — the airline's own stand and an aircraft-size
     * match where OSM carries those tags, otherwise a random plausible stand, so the taxi
     * route always has somewhere real to take the flight. Off by default, and it never
     * touches a gate the pilot typed (see `GateAssigner.mayAssign`).
     *
     * Settings label: "Auto-assign gates".
     */
    val autoAssignGates: Boolean = false,

    // endregion

    // region Saved flights

    /**
     * Keep a loaded saved flight up to date as it is flown, so switching away to
     * another flight (or being killed by the OS) never loses the leg you just flew.
     * On by default; turn it off to treat a saved flight as a fixed point-in-time
     * snapshot that only changes when you tap Save.
     *
     * Settings label: "Keep saved flights up to date".
     */
    val autoSaveFlights: Boolean = true,

    // endregion

    // region Weather

    /** Settings label: "Route corridor: <n> NM", slider [ROUTE_CORRIDOR_RANGE_NM]. */
    val routeCorridorNM: Double = 100.0,

    /** Settings label: "Altitude band: ±<n> ft", slider [ALTITUDE_BAND_RANGE_FT]. */
    val altitudeBandFt: Double = 5000.0,

    /** Settings label: "Endpoint" (placeholder "base URL"). */
    val weatherBaseURL: String = AppConfig.Endpoints.AVIATION_WEATHER_BASE,

    // endregion

    // region Weather data (NOAA radar precipitation + simulated deviation)

    /**
     * NOAA radar overlay preference (auto where available, or off).
     *
     * Settings label: "NOAA Radar Overlay".
     */
    val noaaRadarOverlay: NOAARadarOverlayMode = NOAARadarOverlayMode.AUTO_WHERE_AVAILABLE,

    /** Radar overlay opacity (0…1), default 0.55. Settings label: "Radar opacity: <n>%". */
    val radarOpacity: Double = 0.55,

    /** Simulated weather-deviation alert level. Settings label: "Weather deviation alerts". */
    val weatherDeviationAlerts: WeatherDeviationAlertMode =
        WeatherDeviationAlertMode.ADVISORY_PLUS_DEVIATION,

    /**
     * Opt in to driving the weather-deviation flow (mint reroute line + advisory) from
     * the **NASA global satellite precipitation estimate** where there is no NOAA/OPERA
     * radar coverage. Off by default: the estimate is coarse (~10 km), latent (hours),
     * and cannot reliably grade severity, so it is treated as low confidence and always
     * labeled "satellite estimate — not radar". When off, satellite coverage still shows
     * the overlay image but never draws a deviation (radar-only behavior).
     *
     * Settings label: "Deviations from satellite estimate".
     */
    val satelliteDeviationsEnabled: Boolean = false,

    /**
     * Show data-source labels (e.g. "Radar precipitation data: NOAA/NWS").
     *
     * Settings label: "Show data-source labels".
     */
    val showWeatherDataSourceLabels: Boolean = true,

    /** Show coverage/unavailable warnings. Settings label: "Show coverage warnings". */
    val showWeatherCoverageWarnings: Boolean = true,

    /**
     * On a cellular / expensive connection, skip the background EUMETNET OPERA radar
     * composite downloads (the megabyte-scale source that drives the auto reroute).
     * The overlay still loads when you open the Weather map. On by default.
     *
     * **Currently dormant and hidden from Settings:** OPERA's ORD render is disabled,
     * so there are no megabyte-scale downloads to throttle — the remaining NOAA/NASA
     * sources are small. The property and its network-path plumbing are kept so
     * re-enabling OPERA restores the throttle and its toggle together.
     */
    val reduceCellularData: Boolean = true,

    // endregion

    // region Diagnostics / dev

    /** Settings label: "Debug logging". */
    val debugLogging: Boolean = true,

    /** Settings label: "Mock Mode (no Infinite Flight needed)". */
    val mockMode: Boolean = true,

    // endregion
) {

    /**
     * The configured controller-voice identifier for a facility (empty = fall back to the
     * default controller voice). Ramp shares the Ground voice (both work the surface);
     * Clearance uses the default controller voice. Shared by the real-controller speech and
     * the background chatter so a simulated <facility> is spoken in the same voice as the
     * <facility> the pilot is actually working.
     */
    fun controllerVoiceID(facility: ATCFacility): String = when (facility) {
        ATCFacility.GROUND -> voiceGround
        ATCFacility.TOWER -> voiceTower
        ATCFacility.DEPARTURE -> voiceDeparture
        ATCFacility.CENTER -> voiceCenter
        ATCFacility.APPROACH -> voiceApproach
        ATCFacility.RAMP -> voiceGround
        ATCFacility.CLEARANCE -> defaultVoiceID
    }

    companion object {
        /**
         * Control ranges from the iOS Settings screen (`SettingsView.swift`). They are
         * part of the setting's contract — a stepper that steps by a different amount, or
         * a slider that reaches further, is a different preference — so they travel with
         * the value rather than being re-invented in the Compose layer.
         */
        val INITIAL_CLIMB_RANGE_FT: IntRange = 2000..10000
        const val INITIAL_CLIMB_STEP_FT = 1000

        val TRACON_CEILING_RANGE_FL: IntRange = 80..240
        const val TRACON_CEILING_STEP_FL = 10

        val VOICE_VOLUME_RANGE: ClosedFloatingPointRange<Double> = 0.0..1.0
        val SPEECH_PITCH_RANGE: ClosedFloatingPointRange<Double> = 0.5..2.0
        val CHATTER_VOLUME_RANGE: ClosedFloatingPointRange<Double> = 0.02..0.5
        val RADAR_OPACITY_RANGE: ClosedFloatingPointRange<Double> = 0.1..1.0

        val ROUTE_CORRIDOR_RANGE_NM: ClosedFloatingPointRange<Double> = 25.0..250.0
        const val ROUTE_CORRIDOR_STEP_NM = 25.0

        val ALTITUDE_BAND_RANGE_FT: ClosedFloatingPointRange<Double> = 1000.0..10000.0
        const val ALTITUDE_BAND_STEP_FT = 1000.0
    }
}
