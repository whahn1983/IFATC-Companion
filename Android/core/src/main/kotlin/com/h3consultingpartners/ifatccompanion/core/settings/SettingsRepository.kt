package com.h3consultingpartners.ifatccompanion.core.settings

import com.h3consultingpartners.ifatccompanion.core.phraseology.CallsignDigitStyle
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyMode
import com.h3consultingpartners.ifatccompanion.core.platform.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Centralised, persisted user preferences, over the [KeyValueStore] port.
 *
 * Ported from `IFATCCompanion/Settings/AppSettings.swift`, whose `@Published`
 * properties each write themselves to `UserDefaults` in a `didSet`. Here the state is
 * one immutable [AppSettings] on a [StateFlow] and every setter writes the same key
 * the Swift writes, with the same value, so a device that has both apps' data means
 * the same thing by it.
 *
 * Reads happen once, in [load]; from then on the flow is the truth and the store is
 * write-through. That also removes the `isLoading` flag the Swift needs to stop its
 * `didSet`s firing during `init` — the load never goes through a setter.
 */
class SettingsRepository(private val store: KeyValueStore) {

    private val _state = MutableStateFlow(AppSettings())

    /** The current settings. One flow per feature, as the porting guide asks. */
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    /** Snapshot accessor for callers that are not collecting the flow. */
    val settings: AppSettings get() = _state.value

    init {
        _state.value = load()
    }

    // MARK: - Loading

    /**
     * Read every value, applying the defaults the Swift applies when a key is absent,
     * plus the two migrations it runs on the way in.
     */
    private fun load(): AppSettings {
        val defaults = AppSettings()
        var loaded = AppSettings(
            host = store.getString(SettingsKeys.HOST) ?: defaults.host,
            port = store.getInt(SettingsKeys.PORT) ?: defaults.port,
            autoDiscover = store.getBoolean(SettingsKeys.AUTO_DISCOVER) ?: defaults.autoDiscover,
            keepScreenAwake = store.getBoolean(SettingsKeys.KEEP_SCREEN_AWAKE) ?: defaults.keepScreenAwake,

            callsign = store.getString(SettingsKeys.CALLSIGN) ?: defaults.callsign,
            airline = store.getString(SettingsKeys.AIRLINE) ?: defaults.airline,
            flightNumber = store.getString(SettingsKeys.FLIGHT_NUMBER) ?: defaults.flightNumber,
            departure = store.getString(SettingsKeys.DEPARTURE) ?: defaults.departure,
            destination = store.getString(SettingsKeys.DESTINATION) ?: defaults.destination,
            alternate = store.getString(SettingsKeys.ALTERNATE) ?: defaults.alternate,
            cruiseAltitude = store.getInt(SettingsKeys.CRUISE_ALTITUDE) ?: defaults.cruiseAltitude,
            runway = store.getString(SettingsKeys.RUNWAY) ?: defaults.runway,
            sid = store.getString(SettingsKeys.SID) ?: defaults.sid,
            star = store.getString(SettingsKeys.STAR) ?: defaults.star,
            approach = store.getString(SettingsKeys.APPROACH) ?: defaults.approach,
            departureGate = store.getString(SettingsKeys.DEPARTURE_GATE) ?: defaults.departureGate,
            // Migrate the pre-split single "gate" key into the arrival gate. As on iOS the
            // migrated value is only *read*: it is written back the first time something
            // sets the arrival gate, and the legacy key is never removed.
            arrivalGate = store.getString(SettingsKeys.ARRIVAL_GATE)
                ?: store.getString(SettingsKeys.LEGACY_GATE)
                ?: defaults.arrivalGate,
            autoAssignedDepartureGate = store.getString(SettingsKeys.AUTO_ASSIGNED_DEPARTURE_GATE)
                ?: defaults.autoAssignedDepartureGate,
            autoAssignedArrivalGate = store.getString(SettingsKeys.AUTO_ASSIGNED_ARRIVAL_GATE)
                ?: defaults.autoAssignedArrivalGate,

            voiceEnabled = store.getBoolean(SettingsKeys.VOICE_ENABLED) ?: defaults.voiceEnabled,
            defaultVoiceID = store.getString(SettingsKeys.DEFAULT_VOICE_ID) ?: defaults.defaultVoiceID,
            speechRate = store.getDouble(SettingsKeys.SPEECH_RATE) ?: defaults.speechRate,
            speechPitch = store.getDouble(SettingsKeys.SPEECH_PITCH) ?: defaults.speechPitch,
            voiceVolume = store.getDouble(SettingsKeys.VOICE_VOLUME) ?: defaults.voiceVolume,
            respectSilentSwitch = store.getBoolean(SettingsKeys.RESPECT_SILENT_SWITCH)
                ?: defaults.respectSilentSwitch,
            voiceGround = store.getString(SettingsKeys.VOICE_GROUND) ?: defaults.voiceGround,
            voiceTower = store.getString(SettingsKeys.VOICE_TOWER) ?: defaults.voiceTower,
            voiceDeparture = store.getString(SettingsKeys.VOICE_DEPARTURE) ?: defaults.voiceDeparture,
            voiceCenter = store.getString(SettingsKeys.VOICE_CENTER) ?: defaults.voiceCenter,
            voiceApproach = store.getString(SettingsKeys.VOICE_APPROACH) ?: defaults.voiceApproach,
            voiceATIS = store.getString(SettingsKeys.VOICE_ATIS) ?: defaults.voiceATIS,
            voicePilot = store.getString(SettingsKeys.VOICE_PILOT) ?: defaults.voicePilot,
            speakPilot = store.getBoolean(SettingsKeys.SPEAK_PILOT) ?: defaults.speakPilot,
            holdToTalkEnabled = store.getBoolean(SettingsKeys.HOLD_TO_TALK_ENABLED)
                ?: defaults.holdToTalkEnabled,

            phraseologyMode = PhraseologyMode.fromRawValue(
                store.getString(SettingsKeys.PHRASEOLOGY_MODE) ?: "",
            ) ?: defaults.phraseologyMode,
            digitStyle = CallsignDigitStyle.fromRawValue(
                store.getString(SettingsKeys.DIGIT_STYLE) ?: "",
            ) ?: defaults.digitStyle,

            backgroundChatterEnabled = store.getBoolean(SettingsKeys.BACKGROUND_CHATTER_ENABLED)
                ?: defaults.backgroundChatterEnabled,
            liveActivityEnabled = store.getBoolean(SettingsKeys.LIVE_ACTIVITY_ENABLED)
                ?: defaults.liveActivityEnabled,
            chatterVolume = store.getDouble(SettingsKeys.CHATTER_VOLUME) ?: defaults.chatterVolume,
            chatterDensity = ChatterDensity.fromRawValue(
                store.getString(SettingsKeys.CHATTER_DENSITY) ?: "",
            ) ?: defaults.chatterDensity,
            transmissionStaticEnabled = store.getBoolean(SettingsKeys.TRANSMISSION_STATIC_ENABLED)
                ?: defaults.transmissionStaticEnabled,

            initialClimbAltitudeFt = store.getInt(SettingsKeys.INITIAL_CLIMB_ALTITUDE_FT)
                ?: defaults.initialClimbAltitudeFt,
            traconCeilingFL = store.getInt(SettingsKeys.TRACON_CEILING_FL) ?: defaults.traconCeilingFL,
            autoTuneOnHandoff = store.getBoolean(SettingsKeys.AUTO_TUNE_ON_HANDOFF)
                ?: defaults.autoTuneOnHandoff,
            centerSectorHandoffs = store.getBoolean(SettingsKeys.CENTER_SECTOR_HANDOFFS)
                ?: defaults.centerSectorHandoffs,

            taxiAutoCrossingCalls = store.getBoolean(SettingsKeys.TAXI_AUTO_CROSSING_CALLS)
                ?: defaults.taxiAutoCrossingCalls,
            taxiAutoRecalculate = store.getBoolean(SettingsKeys.TAXI_AUTO_RECALCULATE)
                ?: defaults.taxiAutoRecalculate,
            autoAssignGates = store.getBoolean(SettingsKeys.AUTO_ASSIGN_GATES) ?: defaults.autoAssignGates,

            autoSaveFlights = store.getBoolean(SettingsKeys.AUTO_SAVE_FLIGHTS) ?: defaults.autoSaveFlights,

            routeCorridorNM = store.getDouble(SettingsKeys.ROUTE_CORRIDOR_NM) ?: defaults.routeCorridorNM,
            altitudeBandFt = store.getDouble(SettingsKeys.ALTITUDE_BAND_FT) ?: defaults.altitudeBandFt,
            weatherBaseURL = store.getString(SettingsKeys.WEATHER_BASE_URL) ?: defaults.weatherBaseURL,

            noaaRadarOverlay = NOAARadarOverlayMode.fromRawValue(
                store.getString(SettingsKeys.NOAA_RADAR_OVERLAY) ?: "",
            ) ?: defaults.noaaRadarOverlay,
            radarOpacity = store.getDouble(SettingsKeys.RADAR_OPACITY) ?: defaults.radarOpacity,
            weatherDeviationAlerts = WeatherDeviationAlertMode.fromRawValue(
                store.getString(SettingsKeys.WEATHER_DEVIATION_ALERTS) ?: "",
            ) ?: defaults.weatherDeviationAlerts,
            satelliteDeviationsEnabled = store.getBoolean(SettingsKeys.SATELLITE_DEVIATIONS_ENABLED)
                ?: defaults.satelliteDeviationsEnabled,
            showWeatherDataSourceLabels = store.getBoolean(SettingsKeys.SHOW_WEATHER_DATA_SOURCE_LABELS)
                ?: defaults.showWeatherDataSourceLabels,
            showWeatherCoverageWarnings = store.getBoolean(SettingsKeys.SHOW_WEATHER_COVERAGE_WARNINGS)
                ?: defaults.showWeatherCoverageWarnings,
            reduceCellularData = store.getBoolean(SettingsKeys.REDUCE_CELLULAR_DATA)
                ?: defaults.reduceCellularData,

            debugLogging = store.getBoolean(SettingsKeys.DEBUG_LOGGING) ?: defaults.debugLogging,
            mockMode = store.getBoolean(SettingsKeys.MOCK_MODE) ?: defaults.mockMode,
        )

        // One-time migration: the radio voice effect ships ON by default. Fresh installs
        // already default `transmissionStaticEnabled` to true above; this additionally
        // flips it on once for installs that persisted it OFF during earlier testing, so
        // the release is on-by-default for everyone. After this runs, the user's own
        // on/off choice sticks.
        if (!store.contains(SettingsKeys.RADIO_EFFECT_DEFAULT_MIGRATION)) {
            loaded = loaded.copy(transmissionStaticEnabled = true)
            store.putBoolean(SettingsKeys.TRANSMISSION_STATIC_ENABLED, true)
            store.putBoolean(SettingsKeys.RADIO_EFFECT_DEFAULT_MIGRATION, true)
        }

        return loaded
    }

    /**
     * Reset all stored preferences to defaults.
     *
     * Removes exactly the keys Swift's `Key.allCases` covers and re-runs [load], which
     * means — as on iOS — the radio-effect migration runs again (its marker was one of
     * the keys just removed) and a legacy `"gate"` value, which `allCases` does not
     * contain, is migrated into the arrival gate again. Both are ported as they are.
     */
    /**
     * Write a whole settings object at once, persisting only the values that actually
     * changed. The Settings screen edits an immutable [AppSettings] and hands the result
     * back, which keeps every row a plain copy() rather than a call to one of eighty
     * setters — and this is what turns that back into individual key writes.
     */
    fun replace(updated: AppSettings) {
        val current = _state.value
        if (current == updated) return
        _state.value = updated
        writeAll(current, updated)
    }

    fun resetAll() {
        SettingsKeys.ALL.forEach(store::remove)
        _state.value = load()
    }


    /**
     * Persist the fields that actually changed.
     *
     * The table below is the same key/type mapping the individual setters use — it exists
     * because the Settings screen edits an immutable [AppSettings] and hands the whole
     * object back, which keeps every row a plain `copy()` instead of a call to one of
     * eighty setters. Only changed keys are written, so a settings tap is one or two disk
     * writes rather than eighty.
     */
    private fun writeAll(current: AppSettings, updated: AppSettings) {
        if (current.host != updated.host) store.putString(SettingsKeys.HOST, updated.host)
        if (current.port != updated.port) store.putInt(SettingsKeys.PORT, updated.port)
        if (current.autoDiscover != updated.autoDiscover) store.putBoolean(SettingsKeys.AUTO_DISCOVER, updated.autoDiscover)
        if (current.keepScreenAwake != updated.keepScreenAwake) store.putBoolean(SettingsKeys.KEEP_SCREEN_AWAKE, updated.keepScreenAwake)
        if (current.callsign != updated.callsign) store.putString(SettingsKeys.CALLSIGN, updated.callsign)
        if (current.airline != updated.airline) store.putString(SettingsKeys.AIRLINE, updated.airline)
        if (current.flightNumber != updated.flightNumber) store.putString(SettingsKeys.FLIGHT_NUMBER, updated.flightNumber)
        if (current.departure != updated.departure) store.putString(SettingsKeys.DEPARTURE, updated.departure)
        if (current.destination != updated.destination) store.putString(SettingsKeys.DESTINATION, updated.destination)
        if (current.alternate != updated.alternate) store.putString(SettingsKeys.ALTERNATE, updated.alternate)
        if (current.cruiseAltitude != updated.cruiseAltitude) store.putInt(SettingsKeys.CRUISE_ALTITUDE, updated.cruiseAltitude)
        if (current.runway != updated.runway) store.putString(SettingsKeys.RUNWAY, updated.runway)
        if (current.sid != updated.sid) store.putString(SettingsKeys.SID, updated.sid)
        if (current.star != updated.star) store.putString(SettingsKeys.STAR, updated.star)
        if (current.approach != updated.approach) store.putString(SettingsKeys.APPROACH, updated.approach)
        if (current.departureGate != updated.departureGate) store.putString(SettingsKeys.DEPARTURE_GATE, updated.departureGate)
        if (current.arrivalGate != updated.arrivalGate) store.putString(SettingsKeys.ARRIVAL_GATE, updated.arrivalGate)
        if (current.autoAssignedDepartureGate != updated.autoAssignedDepartureGate) store.putString(SettingsKeys.AUTO_ASSIGNED_DEPARTURE_GATE, updated.autoAssignedDepartureGate)
        if (current.autoAssignedArrivalGate != updated.autoAssignedArrivalGate) store.putString(SettingsKeys.AUTO_ASSIGNED_ARRIVAL_GATE, updated.autoAssignedArrivalGate)
        if (current.voiceEnabled != updated.voiceEnabled) store.putBoolean(SettingsKeys.VOICE_ENABLED, updated.voiceEnabled)
        if (current.defaultVoiceID != updated.defaultVoiceID) store.putString(SettingsKeys.DEFAULT_VOICE_ID, updated.defaultVoiceID)
        if (current.speechRate != updated.speechRate) store.putDouble(SettingsKeys.SPEECH_RATE, updated.speechRate)
        if (current.speechPitch != updated.speechPitch) store.putDouble(SettingsKeys.SPEECH_PITCH, updated.speechPitch)
        if (current.voiceVolume != updated.voiceVolume) store.putDouble(SettingsKeys.VOICE_VOLUME, updated.voiceVolume)
        if (current.respectSilentSwitch != updated.respectSilentSwitch) store.putBoolean(SettingsKeys.RESPECT_SILENT_SWITCH, updated.respectSilentSwitch)
        if (current.voiceGround != updated.voiceGround) store.putString(SettingsKeys.VOICE_GROUND, updated.voiceGround)
        if (current.voiceTower != updated.voiceTower) store.putString(SettingsKeys.VOICE_TOWER, updated.voiceTower)
        if (current.voiceDeparture != updated.voiceDeparture) store.putString(SettingsKeys.VOICE_DEPARTURE, updated.voiceDeparture)
        if (current.voiceCenter != updated.voiceCenter) store.putString(SettingsKeys.VOICE_CENTER, updated.voiceCenter)
        if (current.voiceApproach != updated.voiceApproach) store.putString(SettingsKeys.VOICE_APPROACH, updated.voiceApproach)
        if (current.voiceATIS != updated.voiceATIS) store.putString(SettingsKeys.VOICE_ATIS, updated.voiceATIS)
        if (current.voicePilot != updated.voicePilot) store.putString(SettingsKeys.VOICE_PILOT, updated.voicePilot)
        if (current.speakPilot != updated.speakPilot) store.putBoolean(SettingsKeys.SPEAK_PILOT, updated.speakPilot)
        if (current.holdToTalkEnabled != updated.holdToTalkEnabled) store.putBoolean(SettingsKeys.HOLD_TO_TALK_ENABLED, updated.holdToTalkEnabled)
        if (current.phraseologyMode != updated.phraseologyMode) store.putString(SettingsKeys.PHRASEOLOGY_MODE, updated.phraseologyMode.rawValue)
        if (current.digitStyle != updated.digitStyle) store.putString(SettingsKeys.DIGIT_STYLE, updated.digitStyle.rawValue)
        if (current.backgroundChatterEnabled != updated.backgroundChatterEnabled)
            store.putBoolean(
                SettingsKeys.BACKGROUND_CHATTER_ENABLED,
                updated.backgroundChatterEnabled,
            )
        if (current.liveActivityEnabled != updated.liveActivityEnabled)
            store.putBoolean(SettingsKeys.LIVE_ACTIVITY_ENABLED, updated.liveActivityEnabled)
        if (current.chatterVolume != updated.chatterVolume) store.putDouble(SettingsKeys.CHATTER_VOLUME, updated.chatterVolume)
        if (current.chatterDensity != updated.chatterDensity) store.putString(SettingsKeys.CHATTER_DENSITY, updated.chatterDensity.rawValue)
        if (current.transmissionStaticEnabled != updated.transmissionStaticEnabled) store.putBoolean(SettingsKeys.TRANSMISSION_STATIC_ENABLED, updated.transmissionStaticEnabled)
        if (current.initialClimbAltitudeFt != updated.initialClimbAltitudeFt) store.putInt(SettingsKeys.INITIAL_CLIMB_ALTITUDE_FT, updated.initialClimbAltitudeFt)
        if (current.traconCeilingFL != updated.traconCeilingFL) store.putInt(SettingsKeys.TRACON_CEILING_FL, updated.traconCeilingFL)
        if (current.autoTuneOnHandoff != updated.autoTuneOnHandoff) store.putBoolean(SettingsKeys.AUTO_TUNE_ON_HANDOFF, updated.autoTuneOnHandoff)
        if (current.centerSectorHandoffs != updated.centerSectorHandoffs) store.putBoolean(SettingsKeys.CENTER_SECTOR_HANDOFFS, updated.centerSectorHandoffs)
        if (current.taxiAutoCrossingCalls != updated.taxiAutoCrossingCalls) store.putBoolean(SettingsKeys.TAXI_AUTO_CROSSING_CALLS, updated.taxiAutoCrossingCalls)
        if (current.taxiAutoRecalculate != updated.taxiAutoRecalculate) store.putBoolean(SettingsKeys.TAXI_AUTO_RECALCULATE, updated.taxiAutoRecalculate)
        if (current.autoAssignGates != updated.autoAssignGates) store.putBoolean(SettingsKeys.AUTO_ASSIGN_GATES, updated.autoAssignGates)
        if (current.autoSaveFlights != updated.autoSaveFlights) store.putBoolean(SettingsKeys.AUTO_SAVE_FLIGHTS, updated.autoSaveFlights)
        if (current.routeCorridorNM != updated.routeCorridorNM) store.putDouble(SettingsKeys.ROUTE_CORRIDOR_NM, updated.routeCorridorNM)
        if (current.altitudeBandFt != updated.altitudeBandFt) store.putDouble(SettingsKeys.ALTITUDE_BAND_FT, updated.altitudeBandFt)
        if (current.weatherBaseURL != updated.weatherBaseURL) store.putString(SettingsKeys.WEATHER_BASE_URL, updated.weatherBaseURL)
        if (current.noaaRadarOverlay != updated.noaaRadarOverlay) store.putString(SettingsKeys.NOAA_RADAR_OVERLAY, updated.noaaRadarOverlay.rawValue)
        if (current.radarOpacity != updated.radarOpacity) store.putDouble(SettingsKeys.RADAR_OPACITY, updated.radarOpacity)
        if (current.weatherDeviationAlerts != updated.weatherDeviationAlerts) store.putString(SettingsKeys.WEATHER_DEVIATION_ALERTS, updated.weatherDeviationAlerts.rawValue)
        if (current.satelliteDeviationsEnabled != updated.satelliteDeviationsEnabled) store.putBoolean(SettingsKeys.SATELLITE_DEVIATIONS_ENABLED, updated.satelliteDeviationsEnabled)
        if (current.showWeatherDataSourceLabels != updated.showWeatherDataSourceLabels) store.putBoolean(SettingsKeys.SHOW_WEATHER_DATA_SOURCE_LABELS, updated.showWeatherDataSourceLabels)
        if (current.showWeatherCoverageWarnings != updated.showWeatherCoverageWarnings) store.putBoolean(SettingsKeys.SHOW_WEATHER_COVERAGE_WARNINGS, updated.showWeatherCoverageWarnings)
        if (current.reduceCellularData != updated.reduceCellularData) store.putBoolean(SettingsKeys.REDUCE_CELLULAR_DATA, updated.reduceCellularData)
        if (current.debugLogging != updated.debugLogging) store.putBoolean(SettingsKeys.DEBUG_LOGGING, updated.debugLogging)
        if (current.mockMode != updated.mockMode) store.putBoolean(SettingsKeys.MOCK_MODE, updated.mockMode)
    }

    // MARK: - Write-through helpers

    private fun writeString(key: String, value: String, transform: (AppSettings) -> AppSettings) {
        _state.value = transform(_state.value)
        store.putString(key, value)
    }

    private fun writeBoolean(key: String, value: Boolean, transform: (AppSettings) -> AppSettings) {
        _state.value = transform(_state.value)
        store.putBoolean(key, value)
    }

    private fun writeInt(key: String, value: Int, transform: (AppSettings) -> AppSettings) {
        _state.value = transform(_state.value)
        store.putInt(key, value)
    }

    private fun writeDouble(key: String, value: Double, transform: (AppSettings) -> AppSettings) {
        _state.value = transform(_state.value)
        store.putDouble(key, value)
    }

    // MARK: - Connection

    fun setHost(value: String) = writeString(SettingsKeys.HOST, value) { it.copy(host = value) }

    fun setPort(value: Int) = writeInt(SettingsKeys.PORT, value) { it.copy(port = value) }

    fun setAutoDiscover(value: Boolean) =
        writeBoolean(SettingsKeys.AUTO_DISCOVER, value) { it.copy(autoDiscover = value) }

    fun setKeepScreenAwake(value: Boolean) =
        writeBoolean(SettingsKeys.KEEP_SCREEN_AWAKE, value) { it.copy(keepScreenAwake = value) }

    // MARK: - Manual flight overrides

    fun setCallsign(value: String) =
        writeString(SettingsKeys.CALLSIGN, value) { it.copy(callsign = value) }

    fun setAirline(value: String) =
        writeString(SettingsKeys.AIRLINE, value) { it.copy(airline = value) }

    fun setFlightNumber(value: String) =
        writeString(SettingsKeys.FLIGHT_NUMBER, value) { it.copy(flightNumber = value) }

    fun setDeparture(value: String) =
        writeString(SettingsKeys.DEPARTURE, value) { it.copy(departure = value) }

    fun setDestination(value: String) =
        writeString(SettingsKeys.DESTINATION, value) { it.copy(destination = value) }

    fun setAlternate(value: String) =
        writeString(SettingsKeys.ALTERNATE, value) { it.copy(alternate = value) }

    fun setCruiseAltitude(value: Int) =
        writeInt(SettingsKeys.CRUISE_ALTITUDE, value) { it.copy(cruiseAltitude = value) }

    fun setRunway(value: String) = writeString(SettingsKeys.RUNWAY, value) { it.copy(runway = value) }

    fun setSid(value: String) = writeString(SettingsKeys.SID, value) { it.copy(sid = value) }

    fun setStar(value: String) = writeString(SettingsKeys.STAR, value) { it.copy(star = value) }

    fun setApproach(value: String) =
        writeString(SettingsKeys.APPROACH, value) { it.copy(approach = value) }

    fun setDepartureGate(value: String) =
        writeString(SettingsKeys.DEPARTURE_GATE, value) { it.copy(departureGate = value) }

    fun setArrivalGate(value: String) =
        writeString(SettingsKeys.ARRIVAL_GATE, value) { it.copy(arrivalGate = value) }

    fun setAutoAssignedDepartureGate(value: String) =
        writeString(SettingsKeys.AUTO_ASSIGNED_DEPARTURE_GATE, value) {
            it.copy(autoAssignedDepartureGate = value)
        }

    fun setAutoAssignedArrivalGate(value: String) =
        writeString(SettingsKeys.AUTO_ASSIGNED_ARRIVAL_GATE, value) {
            it.copy(autoAssignedArrivalGate = value)
        }

    // MARK: - Voice

    fun setVoiceEnabled(value: Boolean) =
        writeBoolean(SettingsKeys.VOICE_ENABLED, value) { it.copy(voiceEnabled = value) }

    fun setDefaultVoiceID(value: String) =
        writeString(SettingsKeys.DEFAULT_VOICE_ID, value) { it.copy(defaultVoiceID = value) }

    fun setSpeechRate(value: Double) =
        writeDouble(SettingsKeys.SPEECH_RATE, value) { it.copy(speechRate = value) }

    fun setSpeechPitch(value: Double) =
        writeDouble(SettingsKeys.SPEECH_PITCH, value) { it.copy(speechPitch = value) }

    fun setVoiceVolume(value: Double) =
        writeDouble(SettingsKeys.VOICE_VOLUME, value) { it.copy(voiceVolume = value) }

    fun setRespectSilentSwitch(value: Boolean) =
        writeBoolean(SettingsKeys.RESPECT_SILENT_SWITCH, value) { it.copy(respectSilentSwitch = value) }

    fun setVoiceGround(value: String) =
        writeString(SettingsKeys.VOICE_GROUND, value) { it.copy(voiceGround = value) }

    fun setVoiceTower(value: String) =
        writeString(SettingsKeys.VOICE_TOWER, value) { it.copy(voiceTower = value) }

    fun setVoiceDeparture(value: String) =
        writeString(SettingsKeys.VOICE_DEPARTURE, value) { it.copy(voiceDeparture = value) }

    fun setVoiceCenter(value: String) =
        writeString(SettingsKeys.VOICE_CENTER, value) { it.copy(voiceCenter = value) }

    fun setVoiceApproach(value: String) =
        writeString(SettingsKeys.VOICE_APPROACH, value) { it.copy(voiceApproach = value) }

    fun setVoiceATIS(value: String) =
        writeString(SettingsKeys.VOICE_ATIS, value) { it.copy(voiceATIS = value) }

    fun setVoicePilot(value: String) =
        writeString(SettingsKeys.VOICE_PILOT, value) { it.copy(voicePilot = value) }

    fun setSpeakPilot(value: Boolean) =
        writeBoolean(SettingsKeys.SPEAK_PILOT, value) { it.copy(speakPilot = value) }

    fun setHoldToTalkEnabled(value: Boolean) =
        writeBoolean(SettingsKeys.HOLD_TO_TALK_ENABLED, value) { it.copy(holdToTalkEnabled = value) }

    // MARK: - Phraseology

    fun setPhraseologyMode(value: PhraseologyMode) =
        writeString(SettingsKeys.PHRASEOLOGY_MODE, value.rawValue) { it.copy(phraseologyMode = value) }

    fun setDigitStyle(value: CallsignDigitStyle) =
        writeString(SettingsKeys.DIGIT_STYLE, value.rawValue) { it.copy(digitStyle = value) }

    // MARK: - Background radio chatter & Live Activity

    /**
     * The Live Activity rides on the chatter audio; turning chatter off must turn the
     * notification off too.
     */
    /**
     * **The two chatter/notification settings are independent on Android**, and that is a
     * deliberate divergence from iOS rather than an oversight.
     *
     * The iOS repository couples them: turning chatter off turns the Live Activity off,
     * and turning the Live Activity on turns chatter on. It has to, because iOS keeps the
     * app alive in the background through the `audio` background mode, so the chatter is
     * literally what keeps the live updates coming.
     *
     * Android keeps the flight alive with a foreground service, which needs no audio at
     * all. Forcing chatter on to get a notification would make noise the pilot did not ask
     * for, to solve a problem this platform does not have. See
     * Docs/ANDROID_BACKGROUND_EXECUTION.md.
     */
    fun setBackgroundChatterEnabled(value: Boolean) =
        writeBoolean(SettingsKeys.BACKGROUND_CHATTER_ENABLED, value) {
            it.copy(backgroundChatterEnabled = value)
        }

    /** See [setBackgroundChatterEnabled] — independent of chatter on Android. */
    fun setLiveActivityEnabled(value: Boolean) =
        writeBoolean(SettingsKeys.LIVE_ACTIVITY_ENABLED, value) {
            it.copy(liveActivityEnabled = value)
        }

    fun setChatterVolume(value: Double) =
        writeDouble(SettingsKeys.CHATTER_VOLUME, value) { it.copy(chatterVolume = value) }

    fun setChatterDensity(value: ChatterDensity) =
        writeString(SettingsKeys.CHATTER_DENSITY, value.rawValue) { it.copy(chatterDensity = value) }

    fun setTransmissionStaticEnabled(value: Boolean) =
        writeBoolean(SettingsKeys.TRANSMISSION_STATIC_ENABLED, value) {
            it.copy(transmissionStaticEnabled = value)
        }

    // MARK: - ATC automation

    fun setInitialClimbAltitudeFt(value: Int) =
        writeInt(SettingsKeys.INITIAL_CLIMB_ALTITUDE_FT, value) { it.copy(initialClimbAltitudeFt = value) }

    fun setTraconCeilingFL(value: Int) =
        writeInt(SettingsKeys.TRACON_CEILING_FL, value) { it.copy(traconCeilingFL = value) }

    fun setAutoTuneOnHandoff(value: Boolean) =
        writeBoolean(SettingsKeys.AUTO_TUNE_ON_HANDOFF, value) { it.copy(autoTuneOnHandoff = value) }

    fun setCenterSectorHandoffs(value: Boolean) =
        writeBoolean(SettingsKeys.CENTER_SECTOR_HANDOFFS, value) { it.copy(centerSectorHandoffs = value) }

    // MARK: - Airport surface

    fun setTaxiAutoCrossingCalls(value: Boolean) =
        writeBoolean(SettingsKeys.TAXI_AUTO_CROSSING_CALLS, value) { it.copy(taxiAutoCrossingCalls = value) }

    fun setTaxiAutoRecalculate(value: Boolean) =
        writeBoolean(SettingsKeys.TAXI_AUTO_RECALCULATE, value) { it.copy(taxiAutoRecalculate = value) }

    fun setAutoAssignGates(value: Boolean) =
        writeBoolean(SettingsKeys.AUTO_ASSIGN_GATES, value) { it.copy(autoAssignGates = value) }

    // MARK: - Saved flights

    fun setAutoSaveFlights(value: Boolean) =
        writeBoolean(SettingsKeys.AUTO_SAVE_FLIGHTS, value) { it.copy(autoSaveFlights = value) }

    // MARK: - Weather

    fun setRouteCorridorNM(value: Double) =
        writeDouble(SettingsKeys.ROUTE_CORRIDOR_NM, value) { it.copy(routeCorridorNM = value) }

    fun setAltitudeBandFt(value: Double) =
        writeDouble(SettingsKeys.ALTITUDE_BAND_FT, value) { it.copy(altitudeBandFt = value) }

    fun setWeatherBaseURL(value: String) =
        writeString(SettingsKeys.WEATHER_BASE_URL, value) { it.copy(weatherBaseURL = value) }

    // MARK: - Weather data

    fun setNoaaRadarOverlay(value: NOAARadarOverlayMode) =
        writeString(SettingsKeys.NOAA_RADAR_OVERLAY, value.rawValue) { it.copy(noaaRadarOverlay = value) }

    fun setRadarOpacity(value: Double) =
        writeDouble(SettingsKeys.RADAR_OPACITY, value) { it.copy(radarOpacity = value) }

    fun setWeatherDeviationAlerts(value: WeatherDeviationAlertMode) =
        writeString(SettingsKeys.WEATHER_DEVIATION_ALERTS, value.rawValue) {
            it.copy(weatherDeviationAlerts = value)
        }

    fun setSatelliteDeviationsEnabled(value: Boolean) =
        writeBoolean(SettingsKeys.SATELLITE_DEVIATIONS_ENABLED, value) {
            it.copy(satelliteDeviationsEnabled = value)
        }

    fun setShowWeatherDataSourceLabels(value: Boolean) =
        writeBoolean(SettingsKeys.SHOW_WEATHER_DATA_SOURCE_LABELS, value) {
            it.copy(showWeatherDataSourceLabels = value)
        }

    fun setShowWeatherCoverageWarnings(value: Boolean) =
        writeBoolean(SettingsKeys.SHOW_WEATHER_COVERAGE_WARNINGS, value) {
            it.copy(showWeatherCoverageWarnings = value)
        }

    fun setReduceCellularData(value: Boolean) =
        writeBoolean(SettingsKeys.REDUCE_CELLULAR_DATA, value) { it.copy(reduceCellularData = value) }

    // MARK: - Diagnostics / dev

    fun setDebugLogging(value: Boolean) =
        writeBoolean(SettingsKeys.DEBUG_LOGGING, value) { it.copy(debugLogging = value) }

    fun setMockMode(value: Boolean) =
        writeBoolean(SettingsKeys.MOCK_MODE, value) { it.copy(mockMode = value) }
}
