package com.h3consultingpartners.ifatccompanion.core.settings

import com.h3consultingpartners.ifatccompanion.core.phraseology.CallsignDigitStyle
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyMode
import com.h3consultingpartners.ifatccompanion.core.platform.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Settings screen edits an immutable [AppSettings] and hands the whole object back,
 * so `replace` has to persist every field. A field it forgets is a setting that silently
 * reverts on the next launch — invisible in review, obvious and infuriating in use.
 *
 * These drive a real round trip through the store rather than inspecting the code, so a
 * newly added setting that is not wired into the bulk write fails here.
 */
class SettingsBulkWriteTest {

    /** An [AppSettings] with every field moved off its default. */
    private fun everythingChanged(defaults: AppSettings) = AppSettings(
        host = "192.168.1.99",
        port = 20112,
        autoDiscover = !defaults.autoDiscover,
        keepScreenAwake = !defaults.keepScreenAwake,
        callsign = "BAW212",
        airline = "BAW",
        flightNumber = "212",
        departure = "EGLL",
        destination = "KJFK",
        alternate = "KBOS",
        cruiseAltitude = 39_000,
        runway = "27R",
        sid = "MAXIT1F",
        star = "LENDY6",
        approach = "ILS 04R",
        departureGate = "A12",
        arrivalGate = "B7",
        autoAssignedDepartureGate = "A13",
        autoAssignedArrivalGate = "B8",
        voiceEnabled = !defaults.voiceEnabled,
        defaultVoiceID = "voice-default",
        speechRate = 0.77,
        speechPitch = 1.33,
        voiceVolume = 0.42,
        respectSilentSwitch = !defaults.respectSilentSwitch,
        voiceGround = "voice-ground",
        voiceTower = "voice-tower",
        voiceDeparture = "voice-departure",
        voiceCenter = "voice-center",
        voiceApproach = "voice-approach",
        voiceATIS = "voice-atis",
        voicePilot = "voice-pilot",
        speakPilot = !defaults.speakPilot,
        holdToTalkEnabled = !defaults.holdToTalkEnabled,
        phraseologyMode = PhraseologyMode.ICAO,
        digitStyle = CallsignDigitStyle.INDIVIDUAL,
        backgroundChatterEnabled = !defaults.backgroundChatterEnabled,
        liveActivityEnabled = !defaults.liveActivityEnabled,
        chatterVolume = 0.31,
        chatterDensity = ChatterDensity.BUSY,
        transmissionStaticEnabled = !defaults.transmissionStaticEnabled,
        initialClimbAltitudeFt = 7_000,
        traconCeilingFL = 210,
        autoTuneOnHandoff = !defaults.autoTuneOnHandoff,
        centerSectorHandoffs = !defaults.centerSectorHandoffs,
        taxiAutoCrossingCalls = !defaults.taxiAutoCrossingCalls,
        taxiAutoRecalculate = !defaults.taxiAutoRecalculate,
        autoAssignGates = !defaults.autoAssignGates,
        autoSaveFlights = !defaults.autoSaveFlights,
        routeCorridorNM = 175.0,
        altitudeBandFt = 7_000.0,
        weatherBaseURL = "https://example.invalid/api/data",
        noaaRadarOverlay = NOAARadarOverlayMode.OFF,
        radarOpacity = 0.81,
        weatherDeviationAlerts = WeatherDeviationAlertMode.OFF,
        satelliteDeviationsEnabled = !defaults.satelliteDeviationsEnabled,
        showWeatherDataSourceLabels = !defaults.showWeatherDataSourceLabels,
        showWeatherCoverageWarnings = !defaults.showWeatherCoverageWarnings,
        reduceCellularData = !defaults.reduceCellularData,
        debugLogging = !defaults.debugLogging,
        mockMode = !defaults.mockMode,
    )

    @Test
    fun everySettingSurvivesABulkWriteAndAReload() {
        val store = InMemoryKeyValueStore()
        val repository = SettingsRepository(store)
        val defaults = repository.settings
        val changed = everythingChanged(defaults)

        repository.replace(changed)

        // A fresh repository over the same store is exactly what the next launch does.
        val reloaded = SettingsRepository(store).settings

        assertEquals(
            changed,
            reloaded,
            "a setting that does not survive a reload was missed by the bulk write",
        )
    }

    @Test
    fun replacingWithAnIdenticalObjectWritesNothing() {
        val store = InMemoryKeyValueStore()
        val repository = SettingsRepository(store)

        // Construction runs the two migrations the iOS loader runs, and those legitimately
        // write, so the baseline is the store after loading rather than an empty store.
        val baseline = store.snapshot()

        repository.replace(repository.settings)

        assertEquals(
            baseline,
            store.snapshot(),
            "an unchanged replace must not touch the store",
        )
    }

    @Test
    fun theChatterAndLiveUpdateSettingsAreIndependentOnAndroid() {
        // iOS couples them, because its `audio` background mode needs the chatter to keep
        // the app alive. Android's foreground service does not, so forcing chatter on to
        // get a notification would make noise the pilot never asked for.
        val repository = SettingsRepository(InMemoryKeyValueStore())

        repository.setLiveActivityEnabled(true)
        assertFalse(
            repository.settings.backgroundChatterEnabled,
            "enabling the live update must not switch chatter on",
        )

        repository.setBackgroundChatterEnabled(true)
        repository.setBackgroundChatterEnabled(false)
        assertTrue(
            repository.settings.liveActivityEnabled,
            "turning chatter off must not switch the live update off",
        )
    }
}
