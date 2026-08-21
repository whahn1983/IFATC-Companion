package com.h3consultingpartners.ifatccompanion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import com.h3consultingpartners.ifatccompanion.core.settings.ChatterDensity
import com.h3consultingpartners.ifatccompanion.core.settings.NOAARadarOverlayMode
import com.h3consultingpartners.ifatccompanion.core.settings.SettingsLabels
import com.h3consultingpartners.ifatccompanion.core.settings.WeatherDeviationAlertMode
import com.h3consultingpartners.ifatccompanion.core.phraseology.CallsignDigitStyle
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyMode
import com.h3consultingpartners.ifatccompanion.core.ui.LegalStrings
import com.h3consultingpartners.ifatccompanion.ui.components.SettingsLink
import com.h3consultingpartners.ifatccompanion.ui.components.SettingsPicker
import com.h3consultingpartners.ifatccompanion.ui.components.SettingsSection
import com.h3consultingpartners.ifatccompanion.ui.components.SettingsSlider
import com.h3consultingpartners.ifatccompanion.ui.components.SettingsStepper
import com.h3consultingpartners.ifatccompanion.ui.components.SettingsToggle
import com.h3consultingpartners.ifatccompanion.ui.components.SettingsValue
import kotlin.math.roundToInt

/** A device text-to-speech voice, as the picker needs it. */
data class VoiceOption(val id: String, val title: String)

data class SettingsScreenModel(
    val settings: AppSettings,
    val hasLiveAccess: Boolean,
    val entitlementStatusText: String,
    val voices: List<VoiceOption>,
    val workingSectorText: String?,
    val overpassEndpoint: String,
    val surfaceCacheSummary: String?,
)

data class SettingsScreenActions(
    val onSettingsChange: (AppSettings) -> Unit,
    val onOpenSubscription: () -> Unit,
    val onOpenPhraseologyProfiles: () -> Unit,
    val onPreviewVoice: (String) -> Unit,
    val onRefreshAirportData: () -> Unit,
    val onClearAirportCache: () -> Unit,
    val onResetAppData: () -> Unit,
    val onOpenLink: (String) -> Unit,
)

/**
 * The Settings tab.
 *
 * Ported from `IFATCCompanion/Views/SettingsView.swift` — the same sections in the same
 * order, the same labels, the same footers, and the same legal and attribution text.
 * SwiftUI's `Form` becomes a `LazyColumn` of `SettingsSection`s built from Material 3
 * controls (`ui/components/SettingsControls.kt`).
 */
@Composable
fun SettingsScreen(
    model: SettingsScreenModel,
    actions: SettingsScreenActions,
    modifier: Modifier = Modifier,
) {
    val s = model.settings
    fun update(transform: (AppSettings) -> AppSettings) = actions.onSettingsChange(transform(s))

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            SettingsSection(SettingsLabels.Sections.SUBSCRIPTION) {
                SettingsValue("Status", model.entitlementStatusText)
                SettingsLink(
                    label = if (model.hasLiveAccess) "Manage subscription" else "Unlock Live Connected Mode",
                    onClick = actions.onOpenSubscription,
                )
            }
        }

        item {
            SettingsSection(SettingsLabels.Sections.CONNECTION) {
                SettingsToggle(
                    label = SettingsLabels.MOCK_MODE,
                    checked = s.mockMode,
                    onCheckedChange = { on -> update { it.copy(mockMode = on) } },
                    // Live Connected Mode is the paid feature; Mock Mode is always free,
                    // so the toggle is never locked — only leaving it is.
                    description = if (model.hasLiveAccess) null else SettingsLabels.LIVE_LOCKED,
                )
                TextFieldRow(
                    label = SettingsLabels.HOST,
                    value = s.host,
                    placeholder = SettingsLabels.HOST_PLACEHOLDER,
                    onValueChange = { value -> update { it.copy(host = value) } },
                )
                TextFieldRow(
                    label = SettingsLabels.PORT,
                    value = if (s.port > 0) s.port.toString() else "",
                    placeholder = SettingsLabels.PORT_PLACEHOLDER,
                    numeric = true,
                    onValueChange = { value ->
                        update { it.copy(port = value.filter(Char::isDigit).toIntOrNull() ?: 0) }
                    },
                )
                SettingsToggle(
                    label = SettingsLabels.AUTO_DISCOVER,
                    checked = s.autoDiscover,
                    onCheckedChange = { on -> update { it.copy(autoDiscover = on) } },
                )
                SettingsToggle(
                    label = SettingsLabels.KEEP_SCREEN_AWAKE,
                    checked = s.keepScreenAwake,
                    onCheckedChange = { on -> update { it.copy(keepScreenAwake = on) } },
                )
            }
        }

        item {
            SettingsSection(SettingsLabels.Sections.VOICE) {
                SettingsToggle(
                    label = SettingsLabels.VOICE_ENABLED,
                    checked = s.voiceEnabled,
                    onCheckedChange = { on -> update { it.copy(voiceEnabled = on) } },
                )
                VoicePicker(
                    label = SettingsLabels.DEFAULT_VOICE,
                    selectedId = s.defaultVoiceID,
                    voices = model.voices,
                    onSelect = { id ->
                        update { it.copy(defaultVoiceID = id) }
                        actions.onPreviewVoice(id)
                    },
                )
                SettingsSlider(
                    label = "Volume",
                    valueText = "${(s.voiceVolume * 100).roundToInt()}%",
                    value = s.voiceVolume.toFloat(),
                    range = 0f..1f,
                    onValueChange = { v -> update { it.copy(voiceVolume = v.toDouble()) } },
                )
                SettingsSlider(
                    label = "Speech rate",
                    valueText = String.format(java.util.Locale.US, "%.2f", s.speechRate),
                    value = s.speechRate.toFloat(),
                    range = 0f..1f,
                    onValueChange = { v -> update { it.copy(speechRate = v.toDouble()) } },
                )
                SettingsSlider(
                    label = "Pitch",
                    valueText = String.format(java.util.Locale.US, "%.2f", s.speechPitch),
                    value = s.speechPitch.toFloat(),
                    range = 0.5f..2f,
                    onValueChange = { v -> update { it.copy(speechPitch = v.toDouble()) } },
                )
                // iOS offers "Respect silent switch". Android has no silent switch — the
                // ringer mode does not gate media playback — so the setting is omitted
                // rather than made to do nothing. Recorded in the parity matrix.
            }
        }

        item {
            SettingsSection(SettingsLabels.Sections.FACILITY_VOICES, footer = VOICE_PICKER_HINT) {
                VoicePicker(SettingsLabels.VOICE_GROUND, s.voiceGround, model.voices) { id ->
                    update { it.copy(voiceGround = id) }
                    actions.onPreviewVoice(id)
                }
                VoicePicker(SettingsLabels.VOICE_TOWER, s.voiceTower, model.voices) { id ->
                    update { it.copy(voiceTower = id) }
                    actions.onPreviewVoice(id)
                }
                VoicePicker(SettingsLabels.VOICE_DEPARTURE, s.voiceDeparture, model.voices) { id ->
                    update { it.copy(voiceDeparture = id) }
                    actions.onPreviewVoice(id)
                }
                VoicePicker(SettingsLabels.VOICE_CENTER, s.voiceCenter, model.voices) { id ->
                    update { it.copy(voiceCenter = id) }
                    actions.onPreviewVoice(id)
                }
                VoicePicker(SettingsLabels.VOICE_APPROACH, s.voiceApproach, model.voices) { id ->
                    update { it.copy(voiceApproach = id) }
                    actions.onPreviewVoice(id)
                }
                VoicePicker(SettingsLabels.VOICE_ATIS, s.voiceATIS, model.voices) { id ->
                    update { it.copy(voiceATIS = id) }
                    actions.onPreviewVoice(id)
                }
            }
        }

        item {
            SettingsSection(SettingsLabels.Sections.PILOT_VOICE) {
                SettingsToggle(
                    label = SettingsLabels.SPEAK_PILOT,
                    checked = s.speakPilot,
                    onCheckedChange = { on -> update { it.copy(speakPilot = on) } },
                )
                VoicePicker(SettingsLabels.VOICE_PILOT, s.voicePilot, model.voices) { id ->
                    update { it.copy(voicePilot = id) }
                    actions.onPreviewVoice(id)
                }
                SettingsToggle(
                    label = SettingsLabels.HOLD_TO_TALK,
                    checked = s.holdToTalkEnabled,
                    onCheckedChange = { on -> update { it.copy(holdToTalkEnabled = on) } },
                )
            }
        }

        item {
            SettingsSection(SettingsLabels.Sections.PHRASEOLOGY) {
                SettingsPicker(
                    label = SettingsLabels.PHRASEOLOGY_MODE,
                    selected = s.phraseologyMode,
                    options = PhraseologyMode.entries,
                    optionTitle = { it.title },
                    optionDetail = { it.detail },
                    onSelect = { mode -> update { it.copy(phraseologyMode = mode) } },
                )
                SettingsPicker(
                    label = SettingsLabels.DIGIT_STYLE,
                    selected = s.digitStyle,
                    options = CallsignDigitStyle.entries,
                    optionTitle = { it.title },
                    onSelect = { style -> update { it.copy(digitStyle = style) } },
                )
                SettingsLink(
                    label = SettingsLabels.CUSTOM_PROFILES,
                    onClick = actions.onOpenPhraseologyProfiles,
                )
            }
        }

        item {
            SettingsSection(SettingsLabels.Sections.ATC_AUTOMATION) {
                SettingsStepper(
                    label = SettingsLabels.INITIAL_CLIMB_PREFIX +
                        s.initialClimbAltitudeFt + SettingsLabels.INITIAL_CLIMB_SUFFIX,
                    value = s.initialClimbAltitudeFt,
                    range = 2000..10000,
                    step = 1000,
                    onValueChange = { v -> update { it.copy(initialClimbAltitudeFt = v) } },
                )
                SettingsStepper(
                    label = SettingsLabels.TRACON_CEILING_PREFIX + s.traconCeilingFL,
                    value = s.traconCeilingFL,
                    range = 80..240,
                    step = 10,
                    onValueChange = { v -> update { it.copy(traconCeilingFL = v) } },
                )
                SettingsToggle(
                    label = SettingsLabels.AUTO_TUNE_ON_HANDOFF,
                    checked = s.autoTuneOnHandoff,
                    onCheckedChange = { on -> update { it.copy(autoTuneOnHandoff = on) } },
                )
                SettingsToggle(
                    label = SettingsLabels.CENTER_SECTOR_HANDOFFS,
                    checked = s.centerSectorHandoffs,
                    onCheckedChange = { on -> update { it.copy(centerSectorHandoffs = on) } },
                )
                model.workingSectorText?.let { sector ->
                    SettingsValue(SettingsLabels.WORKING_SECTOR, sector)
                }
            }
        }

        item {
            SettingsSection(SettingsLabels.Sections.SAVED_FLIGHTS) {
                SettingsToggle(
                    label = SettingsLabels.AUTO_SAVE_FLIGHTS,
                    checked = s.autoSaveFlights,
                    onCheckedChange = { on -> update { it.copy(autoSaveFlights = on) } },
                )
            }
        }

        item {
            SettingsSection(
                SettingsLabels.Sections.BACKGROUND_RADIO,
                footer = BACKGROUND_RADIO_FOOTER,
            ) {
                SettingsToggle(
                    label = SettingsLabels.BACKGROUND_CHATTER,
                    checked = s.backgroundChatterEnabled,
                    onCheckedChange = { on -> update { it.copy(backgroundChatterEnabled = on) } },
                )
                if (s.backgroundChatterEnabled) {
                    SettingsSlider(
                        label = SettingsLabels.CHATTER_VOLUME,
                        valueText = "${(s.chatterVolume * 100).roundToInt()}%",
                        value = s.chatterVolume.toFloat(),
                        range = 0.02f..0.5f,
                        onValueChange = { v -> update { it.copy(chatterVolume = v.toDouble()) } },
                    )
                    SettingsPicker(
                        label = SettingsLabels.CHATTER_DENSITY,
                        selected = s.chatterDensity,
                        options = ChatterDensity.entries,
                        optionTitle = { it.title },
                        onSelect = { d -> update { it.copy(chatterDensity = d) } },
                    )
                }
                SettingsToggle(
                    label = SettingsLabels.LIVE_ACTIVITY,
                    checked = s.liveActivityEnabled,
                    onCheckedChange = { on -> update { it.copy(liveActivityEnabled = on) } },
                )
                SettingsToggle(
                    label = SettingsLabels.TRANSMISSION_STATIC,
                    checked = s.transmissionStaticEnabled,
                    onCheckedChange = { on -> update { it.copy(transmissionStaticEnabled = on) } },
                )
            }
        }

        item {
            SettingsSection(SettingsLabels.Sections.SIGMET_PIREP) {
                SettingsSlider(
                    label = "Route corridor",
                    valueText = "${s.routeCorridorNM.roundToInt()}${SettingsLabels.ROUTE_CORRIDOR_SUFFIX}",
                    value = s.routeCorridorNM.toFloat(),
                    range = 25f..250f,
                    steps = 8,
                    onValueChange = { v -> update { it.copy(routeCorridorNM = v.toDouble()) } },
                )
                SettingsSlider(
                    label = "Altitude band",
                    valueText = "±${s.altitudeBandFt.roundToInt()}${SettingsLabels.ALTITUDE_BAND_SUFFIX}",
                    value = s.altitudeBandFt.toFloat(),
                    range = 1000f..10000f,
                    steps = 8,
                    onValueChange = { v -> update { it.copy(altitudeBandFt = v.toDouble()) } },
                )
                TextFieldRow(
                    label = SettingsLabels.WEATHER_ENDPOINT,
                    value = s.weatherBaseURL,
                    placeholder = SettingsLabels.WEATHER_ENDPOINT_PLACEHOLDER,
                    onValueChange = { value -> update { it.copy(weatherBaseURL = value) } },
                )
            }
        }

        item {
            SettingsSection(
                SettingsLabels.Sections.WEATHER_DATA,
                footer = LegalStrings.PRECIPITATION_SOURCES,
            ) {
                SettingsPicker(
                    label = SettingsLabels.NOAA_RADAR_OVERLAY,
                    selected = s.noaaRadarOverlay,
                    options = NOAARadarOverlayMode.entries,
                    optionTitle = { it.title },
                    onSelect = { mode -> update { it.copy(noaaRadarOverlay = mode) } },
                )
                SettingsSlider(
                    label = "Radar opacity",
                    valueText = "${(s.radarOpacity * 100).roundToInt()}%",
                    value = s.radarOpacity.toFloat(),
                    range = 0.1f..1f,
                    onValueChange = { v -> update { it.copy(radarOpacity = v.toDouble()) } },
                )
                SettingsPicker(
                    label = SettingsLabels.WEATHER_DEVIATION_ALERTS,
                    selected = s.weatherDeviationAlerts,
                    options = WeatherDeviationAlertMode.entries,
                    optionTitle = { it.title },
                    onSelect = { mode -> update { it.copy(weatherDeviationAlerts = mode) } },
                )
                SettingsToggle(
                    label = SettingsLabels.SATELLITE_DEVIATIONS,
                    checked = s.satelliteDeviationsEnabled,
                    onCheckedChange = { on ->
                        update { it.copy(satelliteDeviationsEnabled = on) }
                    },
                )
                SettingsToggle(
                    label = SettingsLabels.SHOW_WEATHER_DATA_SOURCE_LABELS,
                    checked = s.showWeatherDataSourceLabels,
                    onCheckedChange = { on ->
                        update { it.copy(showWeatherDataSourceLabels = on) }
                    },
                )
                SettingsToggle(
                    label = SettingsLabels.SHOW_WEATHER_COVERAGE_WARNINGS,
                    checked = s.showWeatherCoverageWarnings,
                    onCheckedChange = { on ->
                        update { it.copy(showWeatherCoverageWarnings = on) }
                    },
                )
            }
        }

        item {
            SettingsSection(
                SettingsLabels.Sections.DATA_SOURCES,
                footer = LegalStrings.dataSourcesSummary(),
            ) {
                SettingsValue(SettingsLabels.AIRPORT_SURFACE, LegalStrings.OpenStreetMap.PROVIDER_NAME)
                SettingsValue(SettingsLabels.LICENSE, LegalStrings.OpenStreetMap.LICENSE_NAME)
                SettingsValue(SettingsLabels.OVERPASS_ENDPOINT, model.overpassEndpoint)
                SettingsValue(SettingsLabels.CENTER_SECTORS, LegalStrings.CenterSectors.PROVIDER_NAME)
                SettingsValue(SettingsLabels.LICENSE, LegalStrings.CenterSectors.LICENSE_SHORT_NAME)
                model.surfaceCacheSummary?.let { SettingsValue("Cached airports", it) }

                SettingsToggle(
                    label = SettingsLabels.TAXI_AUTO_CROSSING_CALLS,
                    checked = s.taxiAutoCrossingCalls,
                    onCheckedChange = { on -> update { it.copy(taxiAutoCrossingCalls = on) } },
                )
                SettingsToggle(
                    label = SettingsLabels.TAXI_AUTO_RECALCULATE,
                    checked = s.taxiAutoRecalculate,
                    onCheckedChange = { on -> update { it.copy(taxiAutoRecalculate = on) } },
                )
                SettingsToggle(
                    label = SettingsLabels.AUTO_ASSIGN_GATES,
                    checked = s.autoAssignGates,
                    onCheckedChange = { on -> update { it.copy(autoAssignGates = on) } },
                )
                SettingsLink(SettingsLabels.REFRESH_AIRPORT_DATA, actions.onRefreshAirportData)
                SettingsLink(SettingsLabels.CLEAR_AIRPORT_CACHE, actions.onClearAirportCache)
            }
        }

        item {
            SettingsSection(
                SettingsLabels.Sections.ABOUT_LEGAL,
                footer = LegalStrings.openStreetMapLegal(),
            ) {
                SettingsLink(label = "OpenStreetMap copyright", onClick = {
                    actions.onOpenLink(LegalStrings.OpenStreetMap.COPYRIGHT_URL)
                })
                SettingsLink(label = "ODbL 1.0 licence", onClick = {
                    actions.onOpenLink(LegalStrings.OpenStreetMap.LICENSE_URL)
                })
                SettingsLink(label = SettingsLabels.AIRPORT_DOCUMENTATION, onClick = {
                    actions.onOpenLink(LegalStrings.OpenStreetMap.DOCUMENTATION_URL)
                })
                SettingsLink(label = "VATSpy Data Project", onClick = {
                    actions.onOpenLink(LegalStrings.CenterSectors.SOURCE_URL)
                })
                SettingsLink(label = "CC BY-SA 4.0 licence", onClick = {
                    actions.onOpenLink(LegalStrings.CenterSectors.LICENSE_URL)
                })
                SettingsLink(label = SettingsLabels.CENTER_SECTOR_DOCUMENTATION, onClick = {
                    actions.onOpenLink(LegalStrings.CenterSectors.DOCUMENTATION_URL)
                })
            }
        }

        item {
            SettingsSection(SettingsLabels.Sections.ETIQUETTE) {
                Text(
                    text = LegalStrings.NOT_STAFFED_ATC.replace("**", ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item {
            SettingsSection(
                SettingsLabels.Sections.ADVANCED,
                footer = SettingsLabels.COPYRIGHT_FOOTER,
            ) {
                SettingsToggle(
                    label = SettingsLabels.DEBUG_LOGGING,
                    checked = s.debugLogging,
                    onCheckedChange = { on -> update { it.copy(debugLogging = on) } },
                )
                SettingsValue("Units", SettingsLabels.UNITS_NOTE.removePrefix("Units: "))
                TextButton(
                    onClick = actions.onResetAppData,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = SettingsLabels.RESET_APP_DATA,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item {
            Text(
                text = LegalStrings.INFINITE_FLIGHT_DISCLAIMER + "\n\n" +
                    LegalStrings.INFINITE_FLIGHT_REQUIRED + "\n\n" +
                    LegalStrings.SIMULATION_ONLY,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun VoicePicker(
    label: String,
    selectedId: String,
    voices: List<VoiceOption>,
    onSelect: (String) -> Unit,
) {
    // The system default is always offered, so a pilot can undo a per-facility choice.
    val options = listOf(VoiceOption("", SettingsLabels.SYSTEM_DEFAULT_VOICE)) + voices
    val selected = options.firstOrNull { it.id == selectedId } ?: options.first()
    SettingsPicker(
        label = label,
        selected = selected,
        options = options,
        optionTitle = { it.title },
        onSelect = { onSelect(it.id) },
    )
}

@Composable
private fun TextFieldRow(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    numeric: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Uri,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}

private const val VOICE_PICKER_HINT = "Tap a voice to select it and hear a sample line."

/**
 * The iOS footer explains that the live notification needs background chatter, because
 * on iOS it does — the chatter is what keeps the process alive. On Android the foreground
 * service does that, so the two settings are independent and the footer says so.
 */
private const val BACKGROUND_RADIO_FOOTER =
    "Background radio chatter plays quiet, static-wrapped traffic on the frequency you " +
        "are tuned to. It comes up after your first ATC communication and goes quiet when " +
        "the flight ends. The live flight notification is independent of it on Android — " +
        "your flight keeps running in the background either way. Both use more battery."
