package com.h3consultingpartners.ifatccompanion.core.settings

/**
 * The persistence keys for every user preference.
 *
 * These are the **exact** strings the iOS build writes to `UserDefaults` (the Swift
 * `AppSettings.Key` enum takes its raw values from the case names). Keeping them
 * identical is what lets the two platforms mean the same thing by a stored setting —
 * and it is the fidelity rule the porting guide states outright: raw values match.
 *
 * [ALL] mirrors Swift's `Key.allCases`, in declaration order, because that is what
 * [SettingsRepository.resetAll] iterates. Note what is deliberately **not** in it:
 * [LEGACY_GATE], the pre-split single "gate" key, which the loader migrates but the
 * reset never clears — see the note on [SettingsRepository.resetAll].
 */
object SettingsKeys {

    // Connection
    const val HOST = "host"
    const val PORT = "port"
    const val AUTO_DISCOVER = "autoDiscover"
    const val KEEP_SCREEN_AWAKE = "keepScreenAwake"

    // Manual flight overrides
    const val CALLSIGN = "callsign"
    const val AIRLINE = "airline"
    const val FLIGHT_NUMBER = "flightNumber"
    const val DEPARTURE = "departure"
    const val DESTINATION = "destination"
    const val ALTERNATE = "alternate"
    const val CRUISE_ALTITUDE = "cruiseAltitude"
    const val RUNWAY = "runway"
    const val SID = "sid"
    const val STAR = "star"
    const val APPROACH = "approach"
    const val DEPARTURE_GATE = "departureGate"
    const val ARRIVAL_GATE = "arrivalGate"
    const val AUTO_ASSIGNED_DEPARTURE_GATE = "autoAssignedDepartureGate"
    const val AUTO_ASSIGNED_ARRIVAL_GATE = "autoAssignedArrivalGate"

    // Voice
    const val VOICE_ENABLED = "voiceEnabled"
    const val DEFAULT_VOICE_ID = "defaultVoiceID"
    const val SPEECH_RATE = "speechRate"
    const val SPEECH_PITCH = "speechPitch"
    const val VOICE_VOLUME = "voiceVolume"
    const val RESPECT_SILENT_SWITCH = "respectSilentSwitch"
    const val VOICE_GROUND = "voiceGround"
    const val VOICE_TOWER = "voiceTower"
    const val VOICE_DEPARTURE = "voiceDeparture"
    const val VOICE_CENTER = "voiceCenter"
    const val VOICE_APPROACH = "voiceApproach"
    const val VOICE_ATIS = "voiceATIS"
    const val VOICE_PILOT = "voicePilot"
    const val SPEAK_PILOT = "speakPilot"
    const val HOLD_TO_TALK_ENABLED = "holdToTalkEnabled"

    // Phraseology
    const val PHRASEOLOGY_MODE = "phraseologyMode"
    const val DIGIT_STYLE = "digitStyle"

    // Background radio chatter & Live Activity
    const val BACKGROUND_CHATTER_ENABLED = "backgroundChatterEnabled"
    const val LIVE_ACTIVITY_ENABLED = "liveActivityEnabled"
    const val CHATTER_VOLUME = "chatterVolume"
    const val CHATTER_DENSITY = "chatterDensity"
    const val TRANSMISSION_STATIC_ENABLED = "transmissionStaticEnabled"

    /**
     * Marker for the one-time "radio voice effect ships ON" migration. Not a
     * preference — it records that the migration has run.
     */
    const val RADIO_EFFECT_DEFAULT_MIGRATION = "radioEffectDefaultMigration"

    // ATC automation
    const val INITIAL_CLIMB_ALTITUDE_FT = "initialClimbAltitudeFt"
    const val TRACON_CEILING_FL = "traconCeilingFL"
    const val AUTO_TUNE_ON_HANDOFF = "autoTuneOnHandoff"
    const val CENTER_SECTOR_HANDOFFS = "centerSectorHandoffs"

    // Airport surface (OpenStreetMap taxi routing)
    const val TAXI_AUTO_CROSSING_CALLS = "taxiAutoCrossingCalls"
    const val TAXI_AUTO_RECALCULATE = "taxiAutoRecalculate"
    const val AUTO_ASSIGN_GATES = "autoAssignGates"

    // Saved flights
    const val AUTO_SAVE_FLIGHTS = "autoSaveFlights"

    // Weather
    const val ROUTE_CORRIDOR_NM = "routeCorridorNM"
    const val ALTITUDE_BAND_FT = "altitudeBandFt"
    const val WEATHER_BASE_URL = "weatherBaseURL"

    // Weather data (NOAA radar precipitation + simulated deviation)
    const val NOAA_RADAR_OVERLAY = "noaaRadarOverlay"
    const val RADAR_OPACITY = "radarOpacity"
    const val WEATHER_DEVIATION_ALERTS = "weatherDeviationAlerts"
    const val SATELLITE_DEVIATIONS_ENABLED = "satelliteDeviationsEnabled"
    const val SHOW_WEATHER_DATA_SOURCE_LABELS = "showWeatherDataSourceLabels"
    const val SHOW_WEATHER_COVERAGE_WARNINGS = "showWeatherCoverageWarnings"
    const val REDUCE_CELLULAR_DATA = "reduceCellularData"

    // Diagnostics / dev
    const val DEBUG_LOGGING = "debugLogging"
    const val MOCK_MODE = "mockMode"

    /**
     * The pre-split single gate key, migrated into [ARRIVAL_GATE] on load. Absent from
     * [ALL] exactly as it is absent from Swift's `Key.allCases`.
     */
    const val LEGACY_GATE = "gate"

    /** Every key `resetAll()` clears, in the order Swift declares them. */
    val ALL: List<String> = listOf(
        HOST, PORT, AUTO_DISCOVER, KEEP_SCREEN_AWAKE,
        CALLSIGN, AIRLINE, FLIGHT_NUMBER, DEPARTURE, DESTINATION, ALTERNATE,
        CRUISE_ALTITUDE, RUNWAY, SID, STAR, APPROACH, DEPARTURE_GATE, ARRIVAL_GATE,
        AUTO_ASSIGNED_DEPARTURE_GATE, AUTO_ASSIGNED_ARRIVAL_GATE,
        VOICE_ENABLED, DEFAULT_VOICE_ID, SPEECH_RATE, SPEECH_PITCH, VOICE_VOLUME,
        RESPECT_SILENT_SWITCH,
        VOICE_GROUND, VOICE_TOWER, VOICE_DEPARTURE, VOICE_CENTER, VOICE_APPROACH, VOICE_ATIS,
        VOICE_PILOT, SPEAK_PILOT, HOLD_TO_TALK_ENABLED,
        PHRASEOLOGY_MODE, DIGIT_STYLE,
        BACKGROUND_CHATTER_ENABLED, LIVE_ACTIVITY_ENABLED, CHATTER_VOLUME, CHATTER_DENSITY,
        TRANSMISSION_STATIC_ENABLED,
        RADIO_EFFECT_DEFAULT_MIGRATION,
        INITIAL_CLIMB_ALTITUDE_FT, TRACON_CEILING_FL, AUTO_TUNE_ON_HANDOFF, CENTER_SECTOR_HANDOFFS,
        TAXI_AUTO_CROSSING_CALLS, TAXI_AUTO_RECALCULATE, AUTO_ASSIGN_GATES,
        AUTO_SAVE_FLIGHTS,
        ROUTE_CORRIDOR_NM, ALTITUDE_BAND_FT, WEATHER_BASE_URL,
        NOAA_RADAR_OVERLAY, RADAR_OPACITY, WEATHER_DEVIATION_ALERTS, SATELLITE_DEVIATIONS_ENABLED,
        SHOW_WEATHER_DATA_SOURCE_LABELS, SHOW_WEATHER_COVERAGE_WARNINGS, REDUCE_CELLULAR_DATA,
        DEBUG_LOGGING, MOCK_MODE,
    )
}
