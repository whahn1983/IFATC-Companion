package com.h3consultingpartners.ifatccompanion.core.settings

/**
 * The words the Settings screen puts next to each preference, verbatim from
 * `IFATCCompanion/Views/SettingsView.swift`.
 *
 * They live in `:core` beside the settings themselves rather than in `strings.xml`
 * for the reason the porting guide gives for phraseology: a string the user reads is
 * part of the ported behaviour, and a test can only assert what it can reach. Rows
 * whose text is composed at render time (a stepper's "Initial climb: 5000 ft above
 * field") keep the fixed part here and the number is interpolated by the screen.
 *
 * Section names are the `Section` headers, in the order the iOS Form declares them.
 */
object SettingsLabels {

    object Sections {
        const val SUBSCRIPTION = "Subscription"
        const val CONNECTION = "Infinite Flight Connection"
        const val VOICE = "Voice"
        const val FACILITY_VOICES = "Controller Voice per Facility"
        const val PILOT_VOICE = "Pilot Voice"
        const val PHRASEOLOGY = "Phraseology"
        const val ATC_AUTOMATION = "ATC Automation"
        const val SAVED_FLIGHTS = "Saved Flights"
        const val BACKGROUND_RADIO = "Background Radio & Notification"
        const val SIGMET_PIREP = "SIGMET / PIREP"
        const val WEATHER_DATA = "Weather Data"
        const val DATA_SOURCES = "Data Sources"
        const val ABOUT_LEGAL = "About & Legal"
        const val ETIQUETTE = "Multiplayer Etiquette"
        const val ADVANCED = "Advanced"
    }

    // Connection
    const val MOCK_MODE = "Mock Mode (no Infinite Flight needed)"
    const val LIVE_LOCKED = "Live Connected Mode requires an active subscription."
    const val HOST = "Host / IP"
    const val HOST_PLACEHOLDER = "192.168.1.20"
    const val PORT = "Port"
    const val PORT_PLACEHOLDER = "10112"
    const val AUTO_DISCOVER = "Auto-discover on local network"
    const val KEEP_SCREEN_AWAKE = "Keep screen awake"

    // Voice
    const val VOICE_ENABLED = "Voice enabled"
    const val DEFAULT_VOICE = "Default voice"

    /** Rendered as "Volume: 100%". */
    const val VOICE_VOLUME_PREFIX = "Volume: "

    /** Rendered as "Speech rate: 0.50". */
    const val SPEECH_RATE_PREFIX = "Speech rate: "

    /** Rendered as "Pitch: 1.00". */
    const val SPEECH_PITCH_PREFIX = "Pitch: "
    const val RESPECT_SILENT_SWITCH = "Respect silent switch"

    // Controller voice per facility
    const val VOICE_GROUND = "Ground"
    const val VOICE_TOWER = "Tower"
    const val VOICE_DEPARTURE = "Departure"
    const val VOICE_CENTER = "Center"
    const val VOICE_APPROACH = "Approach"
    const val VOICE_ATIS = "ATIS"

    // Pilot voice
    const val SPEAK_PILOT = "Speak pilot readbacks"
    const val VOICE_PILOT = "Pilot voice"
    const val HOLD_TO_TALK = "Hold to Talk button"

    /** Shown on a voice row when nothing is chosen. */
    const val SYSTEM_DEFAULT_VOICE = "System default"

    // Phraseology
    const val PHRASEOLOGY_MODE = "Mode"
    const val DIGIT_STYLE = "Flight number style"
    const val CUSTOM_PROFILES = "Custom Profiles"

    // ATC automation — the numbers are interpolated by the stepper rows.
    const val INITIAL_CLIMB_PREFIX = "Initial climb: "
    const val INITIAL_CLIMB_SUFFIX = " ft above field"
    const val TRACON_CEILING_PREFIX = "Departure → Center at FL"
    const val AUTO_TUNE_ON_HANDOFF = "Auto-tune on hand-off"
    const val CENTER_SECTOR_HANDOFFS = "Center sector hand-offs"
    const val WORKING_SECTOR = "Working sector"

    // Saved flights
    const val AUTO_SAVE_FLIGHTS = "Keep saved flights up to date"

    // Background radio & notification
    const val BACKGROUND_CHATTER = "Background radio chatter"
    const val CHATTER_VOLUME = "Chatter volume"
    const val CHATTER_DENSITY = "Traffic level"
    const val LIVE_ACTIVITY = "Live flight notification"
    const val TRANSMISSION_STATIC = "Radio voice effect"

    // SIGMET / PIREP
    const val ROUTE_CORRIDOR_PREFIX = "Route corridor: "
    const val ROUTE_CORRIDOR_SUFFIX = " NM"
    const val ALTITUDE_BAND_PREFIX = "Altitude band: ±"
    const val ALTITUDE_BAND_SUFFIX = " ft"
    const val WEATHER_ENDPOINT = "Endpoint"
    const val WEATHER_ENDPOINT_PLACEHOLDER = "base URL"

    // Weather data
    const val NOAA_RADAR_OVERLAY = "NOAA Radar Overlay"
    const val RADAR_OPACITY_PREFIX = "Radar opacity: "
    const val WEATHER_DEVIATION_ALERTS = "Weather deviation alerts"
    const val SATELLITE_DEVIATIONS = "Deviations from satellite estimate"
    const val SHOW_WEATHER_DATA_SOURCE_LABELS = "Show data-source labels"
    const val SHOW_WEATHER_COVERAGE_WARNINGS = "Show coverage warnings"

    // Data sources
    const val AIRPORT_SURFACE = "Airport surface"
    const val LICENSE = "License"
    const val OVERPASS_ENDPOINT = "Overpass endpoint"
    const val CENTER_SECTORS = "Center sectors"
    const val MAP_COASTLINES = "Map coastlines"
    const val MAP_IMAGERY = "Map imagery"
    const val TAXI_AUTO_CROSSING_CALLS = "Automatic runway-crossing calls"
    const val TAXI_AUTO_RECALCULATE = "Auto-recalculate when off route"
    const val AUTO_ASSIGN_GATES = "Auto-assign gates"
    const val REFRESH_AIRPORT_DATA = "Refresh current airport data"
    const val CLEAR_AIRPORT_CACHE = "Clear cached airport data"

    // About & legal
    const val AIRPORT_DOCUMENTATION = "Airport data & licensing documentation"
    const val CENTER_SECTOR_DOCUMENTATION = "Center sector data & licensing documentation"

    // Advanced
    const val DEBUG_LOGGING = "Debug logging"
    const val UNITS_NOTE = "Units: feet / nautical miles / knots"
    const val RESET_APP_DATA = "Reset App Data"
    const val RESET_CONFIRM_TITLE = "Reset all settings and transcript?"
    const val COPYRIGHT_FOOTER = "© 2026 H3 Consulting Partners LLC."
}
