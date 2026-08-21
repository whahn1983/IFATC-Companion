package com.h3consultingpartners.ifatccompanion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Textsms
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.session.FlightSessionState
import com.h3consultingpartners.ifatccompanion.core.session.PilotAction
import com.h3consultingpartners.ifatccompanion.core.session.PilotActionPresentation
import com.h3consultingpartners.ifatccompanion.ui.components.ActionButton
import com.h3consultingpartners.ifatccompanion.ui.components.Card
import com.h3consultingpartners.ifatccompanion.ui.components.CurrentTransmission
import com.h3consultingpartners.ifatccompanion.ui.components.FrequencyButton
import com.h3consultingpartners.ifatccompanion.ui.components.IFATCIcons
import com.h3consultingpartners.ifatccompanion.ui.components.StatusLevel
import com.h3consultingpartners.ifatccompanion.ui.components.StatusPill
import com.h3consultingpartners.ifatccompanion.ui.components.SubscribeBanner
import com.h3consultingpartners.ifatccompanion.ui.components.StandbyBanner
import com.h3consultingpartners.ifatccompanion.ui.components.TranscriptList
import com.h3consultingpartners.ifatccompanion.ui.components.WeatherBanner
import com.h3consultingpartners.ifatccompanion.ui.theme.IFATCTheme

/**
 * Everything the ATC screen needs beyond [FlightSessionState] — the editable fields the
 * pilot types into, and the labels the coordinator formats.
 */
data class AtcScreenModel(
    val session: FlightSessionState,
    val callsign: String,
    val callsignPlaceholder: String,
    val departureGate: String,
    val arrivalGate: String,
    val nearestAirport: String,
    val assignedAltitudeText: String,
    val facilityLabel: String,
    val connectionText: String,
    val standbyText: String?,
    val weatherBannerText: String?,
    val frequencyText: (ATCFacility) -> String,
    val canTune: (ATCFacility) -> Boolean,
    val tunableFacilities: List<ATCFacility>,
    val atisButtonVisible: Boolean = false,
    val atisButtonSubtitle: String = "",
    val atisButtonActive: Boolean = false,
    val atisAirport: String = "",
    val atisIsArrival: Boolean = false,
    val atisReceiptSummary: String? = null,
    val holdToTalkEnabled: Boolean = false,
    val isListening: Boolean = false,
    val partialSpeech: String = "",
    val lastSpokenText: String = "",
    val lastSpokenIntentTitle: String? = null,
    val microphoneDenied: Boolean = false,
    val smootherAltitudeTitle: String? = null,
    val smootherAltitudeIsHigher: Boolean = true,
)

/** Everything the ATC screen can do. */
data class AtcScreenActions(
    val onCallsignChange: (String) -> Unit,
    val onCallsignCommit: () -> Unit,
    val onDepartureGateChange: (String) -> Unit,
    val onArrivalGateChange: (String) -> Unit,
    val onGatesCommit: () -> Unit,
    val onSwapGates: () -> Unit,
    val onTune: (ATCFacility) -> Unit,
    val onTuneAtis: () -> Unit,
    val onPilotAction: (PilotAction) -> Unit,
    val onAcknowledgement: (PilotActionPresentation.Acknowledgement) -> Unit,
    val onReplay: () -> Unit,
    val onSubscribe: () -> Unit,
    val onContactAtcAboutWeather: () -> Unit,
    val onPushToTalkStart: () -> Unit,
    val onPushToTalkEnd: () -> Unit,
)

/**
 * The ATC tab — a live, spoken ATC conversation for the flight.
 *
 * A faithful port of `IFATCCompanion/Views/ATCView.swift`: the same cards in the same
 * order (banners, status header, current transmission, taxi map, tune frequency, weather
 * deviation, responses, transcript), the same copy, and the same conditional visibility.
 *
 * A `LazyColumn` replaces SwiftUI's `ScrollView`+`VStack`: the transcript grows for the
 * length of a flight, and rendering every past line on every recomposition would make
 * the screen heavier the longer the pilot flies.
 */
@Composable
fun AtcScreen(
    model: AtcScreenModel,
    actions: AtcScreenActions,
    modifier: Modifier = Modifier,
    taxiMap: @Composable () -> Unit = {},
    weatherDeviationCard: @Composable () -> Unit = {},
) {
    val session = model.session
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!session.hasLiveAccess) {
            item { SubscribeBanner(onClick = actions.onSubscribe) }
        }

        item { StatusHeader(model, actions) }

        model.standbyText?.let { text ->
            item { StandbyBanner(text) }
        }

        model.weatherBannerText?.let { text ->
            item { WeatherBanner(text, onContactAtc = actions.onContactAtcAboutWeather) }
        }

        item {
            Card {
                CurrentTransmission(
                    transmission = session.latestTransmission,
                    onReplay = if (session.latestTransmission != null) actions.onReplay else null,
                )
            }
        }

        item { taxiMap() }

        item { FrequencyCard(model, actions) }

        item { weatherDeviationCard() }

        item { ResponsesCard(model, actions) }

        item {
            Card(title = "Transcript", icon = Icons.Filled.Textsms) {
                if (session.transcript.isEmpty()) {
                    Text(
                        text = PilotActionPresentation.NO_MESSAGES,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TranscriptList(session.transcript)
                }
            }
        }
    }
}

@Composable
private fun StatusHeader(model: AtcScreenModel, actions: AtcScreenActions) {
    val session = model.session
    Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPill(
                text = model.connectionText,
                level = connectionLevel(session),
                icon = Icons.Filled.Sensors,
            )
            Spacer(Modifier.weight(1f))
            // Center reads as the sector actually working the flight ("Fort Worth
            // Center") once an airborne fix has landed inside a known sector; every other
            // facility keeps its plain title. Before the first such fix — and whenever the
            // sector database has not loaded — it falls back to a plain "Center", which is
            // what the whole of a flight used to read, because nothing fed the tracker.
            StatusPill(
                text = model.facilityLabel,
                level = StatusLevel.NEUTRAL,
                icon = IFATCIcons.forKey(session.currentFacility.iconKey),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderField(
                title = "Callsign",
                value = model.callsign,
                placeholder = model.callsignPlaceholder,
                onValueChange = actions.onCallsignChange,
                onCommit = actions.onCallsignCommit,
                modifier = Modifier.weight(1f),
            )
            VerticalDivider(Modifier.height(34.dp), thickness = 1.dp)
            HeaderStat(
                title = "Airport",
                value = model.nearestAirport,
                icon = Icons.Filled.Place,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderField(
                title = "Dep Gate",
                value = model.departureGate,
                placeholder = "C12",
                onValueChange = actions.onDepartureGateChange,
                onCommit = actions.onGatesCommit,
                modifier = Modifier.weight(1f),
            )
            // Swaps the departure and arrival gates — a shortcut for the return leg,
            // where the two simply trade places. Sits between the fields in place of the
            // divider so it doubles as the visual separator.
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            ) {
                IconButton(onClick = actions.onSwapGates) {
                    Icon(
                        Icons.AutoMirrored.Filled.CompareArrows,
                        contentDescription = "Swap departure and arrival gates",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            HeaderField(
                title = "Arr Gate",
                value = model.arrivalGate,
                placeholder = "B44",
                onValueChange = actions.onArrivalGateChange,
                onCommit = actions.onGatesCommit,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderStat(
                title = "Phase",
                value = session.phase.title,
                icon = Icons.Filled.Flag,
                modifier = Modifier.weight(1f),
            )
            VerticalDivider(Modifier.height(34.dp), thickness = 1.dp)
            HeaderStat(
                title = "Assigned",
                value = model.assignedAltitudeText,
                icon = Icons.Filled.SwapVert,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HeaderStat(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * An editable header field. Infinite Flight's Connect API exposes neither the user's
 * callsign nor their gate, so both are entered here rather than buried in the Flight
 * tab's overrides; they feed the ATC phraseology, the pushback request and the arrival
 * taxi-to-gate instruction.
 */
@Composable
private fun HeaderField(
    title: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleSmall,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                // Callsigns and stands are upper-case ASCII identifiers. Asking for the
                // ASCII keyboard is what keeps an IME from "correcting" UAL598 into a
                // word, and it behaves the same across Compose versions.
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = { onCommit() },
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FrequencyCard(model: AtcScreenModel, actions: AtcScreenActions) {
    val session = model.session
    Card(title = "Tune Frequency", icon = Icons.Filled.Dialpad) {
        // ATIS sits with the rest of the frequencies — the pilot tunes it first, copies
        // the broadcast, then moves to a controller.
        val buttons = buildList<@Composable () -> Unit> {
            if (model.atisButtonVisible) {
                add {
                    FrequencyButton(
                        title = "ATIS",
                        icon = Icons.Filled.Sensors,
                        frequency = model.atisButtonSubtitle,
                        active = model.atisButtonActive,
                        enabled = true,
                        onClick = actions.onTuneAtis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            for (facility in model.tunableFacilities) {
                add {
                    FrequencyButton(
                        title = facility.title,
                        icon = IFATCIcons.forKey(facility.iconKey),
                        frequency = model.frequencyText(facility),
                        active = session.currentFacility == facility,
                        enabled = model.canTune(facility),
                        onClick = { actions.onTune(facility) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (session.canContactRamp) {
                add {
                    FrequencyButton(
                        title = "Ramp",
                        icon = IFATCIcons.forKey(ATCFacility.RAMP.iconKey),
                        frequency = model.frequencyText(ATCFacility.RAMP),
                        active = session.currentFacility == ATCFacility.RAMP,
                        enabled = true,
                        // Tuning only moves the radio — like every other frequency
                        // button. The actual call (pushback / taxi-to-gate) is made
                        // afterwards from the Responses card.
                        onClick = { actions.onTune(ATCFacility.RAMP) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        ButtonGrid(buttons)

        if (model.atisButtonVisible) {
            val target = if (model.atisIsArrival) "arrival check-in" else "taxi request"
            Text(
                text = "Play ${model.atisAirport}'s latest ATIS — the information code is " +
                    "added to your $target. You stay tuned to your current frequency.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            model.atisReceiptSummary?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = IFATCTheme.semantic.connected,
                )
            }
        }
        Text(
            text = PilotActionPresentation.TUNE_FREQUENCY_HINT,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResponsesCard(model: AtcScreenModel, actions: AtcScreenActions) {
    val session = model.session
    Card(title = "Responses", icon = Icons.Filled.TouchApp) {
        val available = PilotActionPresentation.orderedActions
            .filter { it in session.availableActions }

        if (available.isEmpty()) {
            Text(
                text = if (session.companionStandby) {
                    PilotActionPresentation.STANDBY_HINT
                } else {
                    PilotActionPresentation.NO_REQUESTS_HINT
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ButtonGrid(
                available.map { action ->
                    @Composable {
                        val presentation = PilotActionPresentation.presentation(action)
                        val title =
                            if (action == PilotAction.ACCEPT_SMOOTHER_ALTITUDE) {
                                model.smootherAltitudeTitle ?: presentation.title
                            } else {
                                presentation.title
                            }
                        val iconKey =
                            if (action == PilotAction.ACCEPT_SMOOTHER_ALTITUDE &&
                                !model.smootherAltitudeIsHigher
                            ) {
                                "arrow_circle_down"
                            } else {
                                presentation.iconKey
                            }
                        ActionButton(
                            title = title,
                            icon = IFATCIcons.forKey(iconKey),
                            onClick = { actions.onPilotAction(action) },
                            tint = emphasisColor(presentation.emphasis),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
        }

        // Always-available acknowledgements.
        ButtonGrid(
            PilotActionPresentation.Acknowledgement.entries.map { ack ->
                @Composable {
                    ActionButton(
                        title = ack.title,
                        icon = IFATCIcons.forKey(ack.iconKey),
                        onClick = { actions.onAcknowledgement(ack) },
                        tint = emphasisColor(ack.emphasis),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )

        if (model.holdToTalkEnabled) {
            PushToTalk(model, actions)
        }

        Text(
            text = PilotActionPresentation.UNICOM_REMINDER,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PushToTalk(model: AtcScreenModel, actions: AtcScreenActions) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PushToTalkButton(
            listening = model.isListening,
            onPress = actions.onPushToTalkStart,
            onRelease = actions.onPushToTalkEnd,
        )
        when {
            model.isListening -> Text(
                text = model.partialSpeech.ifEmpty { "Listening…" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            model.lastSpokenText.isNotEmpty() -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "\"${model.lastSpokenText}\"",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                model.lastSpokenIntentTitle?.let { intent ->
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = intent,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (intent == "Unrecognized") {
                            IFATCTheme.semantic.connecting
                        } else {
                            IFATCTheme.semantic.connected
                        },
                    )
                }
            }
        }
        if (model.microphoneDenied) {
            Text(
                text = PilotActionPresentation.PUSH_TO_TALK_PERMISSION_DENIED,
                style = MaterialTheme.typography.labelSmall,
                color = IFATCTheme.semantic.connecting,
            )
        }
    }
}

/**
 * Hold-to-talk. A press-and-hold gesture rather than a toggle, matching the iOS drag
 * gesture: the pilot holds while speaking and releases to send, which is how a real
 * push-to-talk switch behaves and which cannot leave the microphone open by accident.
 */
@Composable
private fun PushToTalkButton(
    listening: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val currentPress by rememberUpdatedState(onPress)
    val currentRelease by rememberUpdatedState(onRelease)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(PUSH_TO_TALK_HEIGHT)
            // Keyed on Unit, deliberately. Keying this on `listening` would restart the
            // gesture coroutine the instant `onPress` flipped it — cancelling
            // `waitForUpOrCancellation()` before it could return, so `onRelease` never
            // ran and the mic stayed open for the rest of the flight. The callbacks are
            // kept current through `rememberUpdatedState` instead.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown(requireUnconsumed = false)
                        currentPress()
                        waitForUpOrCancellation()
                        currentRelease()
                    }
                }
            }
            .semantics {
                contentDescription = if (listening) {
                    PilotActionPresentation.PUSH_TO_TALK_LISTENING
                } else {
                    PilotActionPresentation.PUSH_TO_TALK_IDLE
                }
                onClick(label = PilotActionPresentation.PUSH_TO_TALK_HINT) { false }
            },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        color = tint.copy(alpha = 0.18f),
        border = androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (listening) Icons.Filled.Mic else Icons.Filled.MicNone,
                contentDescription = null,
                tint = tint,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (listening) {
                    PilotActionPresentation.PUSH_TO_TALK_LISTENING
                } else {
                    PilotActionPresentation.PUSH_TO_TALK_IDLE
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
        }
    }
}

/**
 * A grid of equal-width buttons, three to a row — the iOS `LazyVGrid` with three
 * flexible columns. Written as a `Column` of `Row`s rather than a nested lazy grid
 * because it sits inside a `LazyColumn`, and nesting a scrollable in a scrollable is
 * both an error and unnecessary for a handful of buttons.
 */
@Composable
private fun ButtonGrid(buttons: List<@Composable () -> Unit>) {
    if (buttons.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        buttons.chunked(GRID_COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { button ->
                    Box(Modifier.weight(1f)) { button() }
                }
                // Keep the last row's buttons the same width as every other row's.
                repeat(GRID_COLUMNS - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun connectionLevel(session: FlightSessionState): StatusLevel = when {
    session.mockMode -> StatusLevel.AMBER
    session.connectionState.isConnected -> StatusLevel.GREEN
    session.connectionState.isActive -> StatusLevel.AMBER
    else -> StatusLevel.RED
}

@Composable
private fun emphasisColor(
    emphasis: PilotActionPresentation.Emphasis,
): androidx.compose.ui.graphics.Color = when (emphasis) {
    PilotActionPresentation.Emphasis.DEFAULT -> MaterialTheme.colorScheme.primary
    PilotActionPresentation.Emphasis.POSITIVE -> IFATCTheme.semantic.connected
    PilotActionPresentation.Emphasis.CAUTION -> IFATCTheme.semantic.connecting
    PilotActionPresentation.Emphasis.DESTRUCTIVE -> MaterialTheme.colorScheme.error
}

private const val GRID_COLUMNS = 3

private val PUSH_TO_TALK_HEIGHT = 48.dp
