package com.h3consultingpartners.ifatccompanion.core.settings

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.phraseology.CallsignDigitStyle
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyMode
import com.h3consultingpartners.ifatccompanion.core.platform.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The settings store, against `IFATCCompanion/Settings/AppSettings.swift`.
 *
 * iOS has no dedicated test for this file — its behaviour is asserted indirectly all
 * over the suite — but the defaults, the key strings and the two migrations are exactly
 * the things a port gets subtly wrong, and a wrong default here is a different app
 * (voice off, mock mode off, a hand-off that never tunes). So they are pinned.
 */
class SettingsRepositoryTest {

    /** Every default is the Swift's default, to the digit. */
    @Test
    fun freshInstallMatchesTheSwiftDefaults() {
        val settings = SettingsRepository(InMemoryKeyValueStore()).settings

        assertEquals("", settings.host)
        assertEquals(10112, settings.port)
        assertTrue(settings.autoDiscover)
        assertTrue(settings.keepScreenAwake)

        assertEquals(0, settings.cruiseAltitude)
        assertEquals("", settings.arrivalGate)

        assertTrue(settings.voiceEnabled)
        assertEquals(0.5, settings.speechRate)
        assertEquals(1.0, settings.speechPitch)
        assertEquals(1.0, settings.voiceVolume)
        assertFalse(settings.respectSilentSwitch)
        assertTrue(settings.speakPilot)
        assertTrue(settings.holdToTalkEnabled)

        assertEquals(PhraseologyMode.FAA, settings.phraseologyMode)
        assertEquals(CallsignDigitStyle.GROUPED, settings.digitStyle)

        assertFalse(settings.backgroundChatterEnabled)
        assertFalse(settings.liveActivityEnabled)
        assertEquals(0.16, settings.chatterVolume)
        assertEquals(ChatterDensity.MODERATE, settings.chatterDensity)
        assertTrue(settings.transmissionStaticEnabled)

        assertEquals(5000, settings.initialClimbAltitudeFt)
        assertEquals(180, settings.traconCeilingFL)
        assertTrue(settings.autoTuneOnHandoff)
        assertTrue(settings.centerSectorHandoffs)

        assertTrue(settings.taxiAutoCrossingCalls)
        assertFalse(settings.taxiAutoRecalculate)
        assertFalse(settings.autoAssignGates)
        assertTrue(settings.autoSaveFlights)

        assertEquals(100.0, settings.routeCorridorNM)
        assertEquals(5000.0, settings.altitudeBandFt)
        assertEquals("https://aviationweather.gov/api/data", settings.weatherBaseURL)

        assertEquals(NOAARadarOverlayMode.AUTO_WHERE_AVAILABLE, settings.noaaRadarOverlay)
        assertEquals(0.55, settings.radarOpacity)
        assertEquals(
            WeatherDeviationAlertMode.ADVISORY_PLUS_DEVIATION,
            settings.weatherDeviationAlerts,
        )
        assertFalse(settings.satelliteDeviationsEnabled)
        assertTrue(settings.showWeatherDataSourceLabels)
        assertTrue(settings.showWeatherCoverageWarnings)
        assertTrue(settings.reduceCellularData)

        assertTrue(settings.debugLogging)
        assertTrue(settings.mockMode)
    }

    /**
     * Persisted data has to mean the same thing on both platforms, so a setting is
     * written under the exact `UserDefaults` key the iOS build uses.
     */
    @Test
    fun settingsAreWrittenUnderTheIOSKeys() {
        val store = InMemoryKeyValueStore()
        val repository = SettingsRepository(store)
        repository.setHost("192.168.1.20")
        repository.setPort(10111)
        repository.setPhraseologyMode(PhraseologyMode.ICAO)
        repository.setChatterDensity(ChatterDensity.BUSY)
        repository.setRouteCorridorNM(150.0)
        repository.setCenterSectorHandoffs(false)

        assertEquals("192.168.1.20", store.getString("host"))
        assertEquals(10111, store.getInt("port"))
        assertEquals("icao", store.getString("phraseologyMode"))
        assertEquals("busy", store.getString("chatterDensity"))
        assertEquals(150.0, store.getDouble("routeCorridorNM"))
        assertEquals(false, store.getBoolean("centerSectorHandoffs"))
    }

    /**
     * **The two toggles are independent on Android, and that is deliberate.**
     *
     * The iOS repository interlocks them in both directions, because iOS keeps the app
     * alive in the background through the `audio` background mode — the chatter is
     * literally what keeps the Live Activity updating. Android keeps the flight alive
     * with a foreground service, which needs no audio at all, so forcing chatter on to
     * get a notification would make noise the pilot never asked for, to solve a problem
     * this platform does not have.
     *
     * Recorded in Docs/ANDROID_BACKGROUND_EXECUTION.md and in the parity matrix, and
     * stated in the Settings screen's own footer.
     */
    @Test
    fun theLiveActivityAndBackgroundChatterAreIndependent() {
        val store = InMemoryKeyValueStore()
        val repository = SettingsRepository(store)

        repository.setLiveActivityEnabled(true)
        assertTrue(repository.settings.liveActivityEnabled)
        assertFalse(
            repository.settings.backgroundChatterEnabled,
            "enabling the live update must not switch chatter on — the foreground " +
                "service, not the audio, is what keeps the flight running",
        )
        assertEquals(true, store.getBoolean("liveActivityEnabled"))

        repository.setBackgroundChatterEnabled(true)
        repository.setBackgroundChatterEnabled(false)
        assertFalse(repository.settings.backgroundChatterEnabled)
        assertTrue(
            repository.settings.liveActivityEnabled,
            "turning chatter off must leave the live update alone",
        )
        assertEquals(true, store.getBoolean("liveActivityEnabled"))
    }

    /** The pre-split single "gate" key is read as the arrival gate. */
    @Test
    fun theLegacyGateKeyMigratesIntoTheArrivalGate() {
        val store = InMemoryKeyValueStore(mapOf("gate" to "B44"))
        assertEquals("B44", SettingsRepository(store).settings.arrivalGate)

        // An arrival gate of its own always wins over the legacy value.
        store.putString("arrivalGate", "C12")
        assertEquals("C12", SettingsRepository(store).settings.arrivalGate)
    }

    /**
     * The radio voice effect ships ON: the one-time migration flips it on for installs
     * that persisted it OFF during earlier testing, and then never touches the user's
     * own choice again.
     */
    @Test
    fun theRadioEffectMigrationRunsOnceAndThenLeavesTheChoiceAlone() {
        val store = InMemoryKeyValueStore(mapOf("transmissionStaticEnabled" to false))
        val first = SettingsRepository(store)
        assertTrue(first.settings.transmissionStaticEnabled)
        assertEquals(true, store.getBoolean("radioEffectDefaultMigration"))

        first.setTransmissionStaticEnabled(false)
        assertFalse(
            SettingsRepository(store).settings.transmissionStaticEnabled,
            "after the migration has run, switching the effect off sticks",
        )
    }

    /**
     * Reset clears exactly the keys Swift's `Key.allCases` covers. The legacy "gate"
     * key is not one of them — ported as-is, so a value stored under it survives a
     * reset and is migrated into the arrival gate again.
     */
    @Test
    fun resetAllRestoresTheDefaults() {
        val store = InMemoryKeyValueStore(mapOf("gate" to "B44"))
        val repository = SettingsRepository(store)
        repository.setHost("192.168.1.20")
        repository.setMockMode(false)
        repository.setAutoAssignGates(true)

        repository.resetAll()

        assertEquals("", repository.settings.host)
        assertTrue(repository.settings.mockMode)
        assertFalse(repository.settings.autoAssignGates)
        assertFalse(store.contains("host"))
        assertEquals(
            "B44",
            repository.settings.arrivalGate,
            "the legacy gate key is outside Key.allCases, so reset does not clear it",
        )
    }

    /** Ramp shares Ground's voice; Clearance falls back to the default controller voice. */
    @Test
    fun controllerVoicesFollowTheFacility() {
        val repository = SettingsRepository(InMemoryKeyValueStore())
        repository.setDefaultVoiceID("default")
        repository.setVoiceGround("ground")
        repository.setVoiceTower("tower")
        repository.setVoiceDeparture("departure")
        repository.setVoiceCenter("center")
        repository.setVoiceApproach("approach")

        val settings = repository.settings
        assertEquals("ground", settings.controllerVoiceID(ATCFacility.GROUND))
        assertEquals("tower", settings.controllerVoiceID(ATCFacility.TOWER))
        assertEquals("departure", settings.controllerVoiceID(ATCFacility.DEPARTURE))
        assertEquals("center", settings.controllerVoiceID(ATCFacility.CENTER))
        assertEquals("approach", settings.controllerVoiceID(ATCFacility.APPROACH))
        assertEquals("ground", settings.controllerVoiceID(ATCFacility.RAMP))
        assertEquals("default", settings.controllerVoiceID(ATCFacility.CLEARANCE))
    }

    /** The chatter gap ranges are the sector's business hours: shorter gap, busier sector. */
    @Test
    fun chatterDensityCarriesItsGapRange() {
        assertEquals(9.0..22.0, ChatterDensity.LIGHT.gapRange)
        assertEquals(5.0..14.0, ChatterDensity.MODERATE.gapRange)
        assertEquals(2.0..7.0, ChatterDensity.BUSY.gapRange)
        assertFalse(WeatherDeviationAlertMode.OFF.alertsEnabled)
        assertTrue(WeatherDeviationAlertMode.ADVISORY_ONLY.alertsEnabled)
        assertFalse(WeatherDeviationAlertMode.ADVISORY_ONLY.suggestsDeviation)
        assertTrue(WeatherDeviationAlertMode.ADVISORY_PLUS_DEVIATION.suggestsDeviation)
    }
}
