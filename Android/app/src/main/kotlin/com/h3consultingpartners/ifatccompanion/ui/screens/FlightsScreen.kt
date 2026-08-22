package com.h3consultingpartners.ifatccompanion.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.h3consultingpartners.ifatccompanion.core.persistence.SavedFlight
import com.h3consultingpartners.ifatccompanion.core.persistence.SavedFlightPolicy
import com.h3consultingpartners.ifatccompanion.core.session.PilotActionPresentation
import com.h3consultingpartners.ifatccompanion.ui.components.Card

/** Everything the Flights list draws. */
data class FlightsScreenModel(
    val flights: List<SavedFlight> = emptyList(),
    /** The slot the live session is bound to — the one auto-save keeps current. */
    val activeFlightID: String? = null,
    val canSaveCurrentFlight: Boolean = false,
    val hasUnsavedFlight: Boolean = false,
    /** The finished flight that starting a new one would retire, if any. */
    val retiredByNewFlight: String? = null,
    /** Mock Mode drives its own scripted feed, so a saved flight has nowhere to load into. */
    val mockMode: Boolean = false,
    /** How long ago each flight was saved, already formatted. Keyed by flight id. */
    val savedAgo: Map<String, String> = emptyMap(),
)

data class FlightsScreenActions(
    val onSave: () -> Unit = {},
    val onStartNewFlight: () -> Unit = {},
    val onLoad: (SavedFlight) -> Unit = {},
    val onDelete: (SavedFlight) -> Unit = {},
    /** The route warning for a flight about to be loaded, or null when the routes agree. */
    val endpointMismatch: (SavedFlight) -> String? = { null },
)

/**
 * The pilot's saved flights: start a new one, save the one in progress, or pick a previous
 * flight to carry on exactly where it was left. A port of
 * `IFATCCompanion/Views/FlightsListView.swift`.
 *
 * Both destinations — New Flight and Load — replace the session in progress, so both ask
 * first, and saving is offered *first* in the dialog whenever there is something to lose.
 * That ordering is the whole safety design of this screen: the recoverable choice is the
 * one nearest the thumb.
 *
 * Deleting is an explicit button rather than a swipe. iOS uses `onDelete` on a `ForEach`,
 * which Android has no direct equivalent for on a `LazyColumn`; a visible control also
 * cannot be triggered by a stray gesture, and this list is the only copy of these flights.
 */
@Composable
fun FlightsScreen(
    model: FlightsScreenModel,
    actions: FlightsScreenActions,
    modifier: Modifier = Modifier,
) {
    // Which confirmation is open. Null for none; the flight is null for "start a new one".
    var pending by remember { mutableStateOf<PendingFlightAction?>(null) }
    var confirmDelete by remember { mutableStateOf<SavedFlight?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { pending = PendingFlightAction(flight = null) },
                        ) { Text("New Flight") }
                        TextButton(
                            onClick = actions.onSave,
                            enabled = model.canSaveCurrentFlight,
                        ) { Text("Save") }
                    }
                    if (model.mockMode) Caption(SavedFlightPolicy.MOCK_MODE_FOOTER)
                }
            }
        }

        if (model.flights.isEmpty()) {
            item { Card { Caption(SavedFlightPolicy.EMPTY_LIST) } }
        } else {
            items(model.flights, key = { it.id }) { flight ->
                SavedFlightRow(
                    flight = flight,
                    isActive = flight.id == model.activeFlightID,
                    savedAgo = model.savedAgo[flight.id].orEmpty(),
                    // Mock Mode has nowhere to load into; the rows stay visible and deletable.
                    canLoad = !model.mockMode,
                    onLoad = { pending = PendingFlightAction(flight = flight) },
                    onDelete = { confirmDelete = flight },
                )
            }
        }
    }

    pending?.let { action ->
        val isNew = action.flight == null
        ReplaceSessionDialog(
            title = if (isNew) SavedFlightPolicy.NEW_FLIGHT_TITLE else SavedFlightPolicy.LOAD_FLIGHT_TITLE,
            message = SavedFlightPolicy.confirmationMessage(
                endpointMismatch = action.flight?.let(actions.endpointMismatch),
                retiredName = model.retiredByNewFlight,
                hasUnsavedFlight = model.hasUnsavedFlight,
                isNewFlight = isNew,
            ),
            // Offered first, and only when there is genuinely something to save, so the
            // recoverable choice is the easy one. Never for a finished flight: it cannot be
            // saved, and clearing retires it either way.
            saveFirstLabel = if (model.hasUnsavedFlight && model.canSaveCurrentFlight) {
                if (isNew) SavedFlightPolicy.SAVE_AND_START_NEW else SavedFlightPolicy.SAVE_AND_LOAD
            } else {
                null
            },
            confirmLabel = if (isNew) SavedFlightPolicy.START_NEW_FLIGHT else SavedFlightPolicy.LOAD_FLIGHT,
            onSaveFirst = {
                actions.onSave()
                action.perform(actions)
                pending = null
            },
            onConfirm = {
                action.perform(actions)
                pending = null
            },
            onDismiss = { pending = null },
        )
    }

    confirmDelete?.let { flight ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${flight.name}”?") },
            text = {
                Text(
                    if (flight.id == model.activeFlightID) {
                        "This is the flight you're on. Deleting it leaves the flight running " +
                            "and stops it being saved anywhere."
                    } else {
                        "This saved flight will be removed. The flight you're on now is not affected."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.onDelete(flight)
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }
}

/** A pending replacement of the session: a flight to load, or null to start a new one. */
private data class PendingFlightAction(val flight: SavedFlight?) {
    fun perform(actions: FlightsScreenActions) {
        val flight = flight
        if (flight == null) actions.onStartNewFlight() else actions.onLoad(flight)
    }
}

@Composable
private fun SavedFlightRow(
    flight: SavedFlight,
    isActive: Boolean,
    savedAgo: String,
    canLoad: Boolean,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(if (canLoad) Modifier.clickable(onClick = onLoad) else Modifier),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = flight.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (isActive) FlyingBadge()
                }
                Text(
                    text = "${flight.stateTitle} · ${flight.facilityTitle}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (savedAgo.isNotEmpty()) {
                Text(
                    text = savedAgo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete ${flight.name}")
            }
        }
    }
}

/** Marks the flight the live session is flying — the one auto-save keeps up to date. */
@Composable
private fun FlyingBadge() {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
    ) {
        Box(Modifier.padding(horizontal = 7.dp, vertical = 2.dp)) {
            Text(
                text = SavedFlightPolicy.FLYING_BADGE,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The confirmation both destinations share.
 *
 * Three buttons rather than two when something is at stake, because "save it first" is a
 * genuinely different intention from "go ahead" and burying it behind a second dialog is
 * how a pilot loses a flight by tapping the obvious thing.
 */
@Composable
private fun ReplaceSessionDialog(
    title: String,
    message: String,
    saveFirstLabel: String?,
    confirmLabel: String,
    onSaveFirst: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                saveFirstLabel?.let { label ->
                    TextButton(onClick = onSaveFirst) {
                        Text(label, fontWeight = FontWeight.SemiBold)
                    }
                }
                TextButton(onClick = onConfirm) { Text(confirmLabel) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Secondary explanatory text, matching the other screens' footnote treatment. */
@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The ATC screen's own "Clear Flight" confirmation.
 *
 * The same shape as the list's, and deliberately a different wording: iOS keeps two sets of
 * strings here because the pilot arrives with a different question in mind. On the ATC
 * screen they are ending the flight they are looking at; in the list they are choosing
 * between flights. `PilotActionPresentation` holds this set, already ported.
 */
@Composable
fun ClearFlightConfirmation(
    hasUnsavedFlight: Boolean,
    canSaveCurrentFlight: Boolean,
    retiredName: String?,
    onSaveAndClear: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    ReplaceSessionDialog(
        title = "Clear this flight?",
        message = when {
            retiredName != null -> PilotActionPresentation.clearFlightRetiredMessage(retiredName)
            hasUnsavedFlight ->
                "${PilotActionPresentation.CLEAR_FLIGHT_UNSAVED} ${PilotActionPresentation.CLEAR_FLIGHT_RESET}"
            else -> PilotActionPresentation.CLEAR_FLIGHT_RESET
        },
        // Offered first whenever the flight in progress is not already in the list, so
        // clearing cannot quietly throw away a leg the pilot wanted to keep. Neither
        // condition holds for a finished flight, which cannot be saved.
        saveFirstLabel = "Save & Clear".takeIf { hasUnsavedFlight && canSaveCurrentFlight },
        confirmLabel = "Clear Flight",
        onSaveFirst = onSaveAndClear,
        onConfirm = onClear,
        onDismiss = onDismiss,
    )
}
