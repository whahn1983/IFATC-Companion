package com.h3consultingpartners.ifatccompanion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.h3consultingpartners.ifatccompanion.core.atc.PhaseDetector
import com.h3consultingpartners.ifatccompanion.core.diagnostics.DiagnosticsStore
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticRecord
import com.h3consultingpartners.ifatccompanion.core.ui.LegalStrings
import com.h3consultingpartners.ifatccompanion.ui.components.Card
import com.h3consultingpartners.ifatccompanion.ui.components.DataRow
import com.h3consultingpartners.ifatccompanion.ui.theme.IFATCTheme
import com.h3consultingpartners.ifatccompanion.ui.theme.TranscriptTextStyle

data class DiagnosticsScreenModel(
    val mockMode: Boolean,
    val mockRouteText: String,
    val mockPhaseText: String,
    val simulateStaffedATC: Boolean,
    val liveATCSummary: String,
    val phaseDebug: PhaseDetector.Debug,
    val atisSummary: String?,
    val weatherEndpointText: String,
    val weatherDiagnostics: List<Pair<String, String>>,
    val showSampledRadarCells: Boolean,
    val discoveredStateCount: Int,
    val resolvedMappingCount: Int,
    val discoveredStates: List<String>,
    val lastRawMessage: String?,
    val surfaceDiagnostics: List<Pair<String, String>>,
    val surfaceError: String?,
    val log: List<DiagnosticRecord>,
)

data class DiagnosticsScreenActions(
    val onToggleMockMode: (Boolean) -> Unit,
    val onAdvanceMockPhase: () -> Unit,
    val onToggleSimulateStaffedATC: (Boolean) -> Unit,
    val onToggleSampledRadarCells: (Boolean) -> Unit,
    val onClearLog: () -> Unit,
    val onExportSurfaceDiagnostics: () -> Unit,
)

/**
 * The Diagnostics tab — connection troubleshooting, and the free Mock Mode toggle.
 *
 * Ported from `IFATCCompanion/Views/DiagnosticsView.swift`: the same cards in the same
 * order, and the same copy. Mock Mode lives here on both platforms because that is where
 * a pilot who has not bought anything goes to try the whole app.
 */
@Composable
fun DiagnosticsScreen(
    model: DiagnosticsScreenModel,
    actions: DiagnosticsScreenActions,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Card(title = "Mock Mode", icon = Icons.Filled.BugReport) {
                ToggleRow(
                    label = "Mock simulator feed",
                    checked = model.mockMode,
                    onCheckedChange = actions.onToggleMockMode,
                )
                if (model.mockMode) {
                    TextButton(onClick = actions.onAdvanceMockPhase) {
                        Icon(Icons.Filled.FastForward, contentDescription = null)
                        Text("  Advance Phase")
                    }
                    Text(
                        text = model.mockRouteText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = model.mockPhaseText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(title = "Multiplayer / ATC Staffing", icon = Icons.Filled.Groups) {
                Text(model.liveATCSummary, style = MaterialTheme.typography.bodyMedium)
                if (model.mockMode) {
                    ToggleRow(
                        label = "Simulate staffed ATC (demo)",
                        checked = model.simulateStaffedATC,
                        onCheckedChange = actions.onToggleSimulateStaffedATC,
                    )
                }
                Text(
                    text = "Staffing detection runs automatically in live mode using the " +
                        "Connect manifest.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Card(title = "Phase Detection", icon = Icons.Filled.Functions) {
                val d = model.phaseDebug
                DataRow("On Ground", if (d.onGround) "Yes" else "No")
                DataRow("Groundspeed", "${Math.round(d.groundSpeed)} kt")
                DataRow("Altitude MSL", "${Math.round(d.altitudeMSL)} ft")
                DataRow("Vertical Speed", "${Math.round(d.verticalSpeed)} fpm")
                d.distanceToDepNM?.let { DataRow("To Departure", "${Math.round(it)} NM") }
                d.distanceToDestNM?.let { DataRow("To Destination", "${Math.round(it)} NM") }
                for (note in d.notes) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        model.atisSummary?.let { summary ->
            item {
                Card(title = "ATIS", icon = Icons.Filled.Sensors) {
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Real-world FAA D-ATIS (US airports, via datis.clowd.io). " +
                            "When a field has no D-ATIS, the ATIS button and information " +
                            "code simply don't appear — nothing is fabricated.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(title = "Weather Endpoint", icon = Icons.Filled.Cloud) {
                Text(model.weatherEndpointText, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (model.weatherDiagnostics.isNotEmpty()) {
            item {
                Card(title = "Weather Diagnostics", icon = Icons.Filled.Cloud) {
                    for ((label, value) in model.weatherDiagnostics) {
                        DataRow(label, value)
                    }
                    ToggleRow(
                        label = "Show sampled cells on map",
                        checked = model.showSampledRadarCells,
                        onCheckedChange = actions.onToggleSampledRadarCells,
                    )
                    Text(
                        text = "Draws the sampler's moderate-or-greater radar clusters as " +
                            "coloured polygons on the Weather map, so you can check they " +
                            "line up with the radar returns. Clearest with the radar " +
                            "overlay turned off, where they sit on the plain map.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(
                title = "Discovered States (${model.discoveredStateCount})",
                icon = Icons.Filled.Terminal,
            ) {
                Text(
                    text = "Resolved mappings: ${model.resolvedMappingCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (state in model.discoveredStates.take(DISCOVERED_STATE_LIMIT)) {
                    Text(
                        text = state,
                        style = TranscriptTextStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val remaining = model.discoveredStateCount - DISCOVERED_STATE_LIMIT
                if (remaining > 0) {
                    Text(
                        text = "…and $remaining more",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        model.lastRawMessage?.let { raw ->
            item {
                Card(title = "Last Raw Message (sanitized)", icon = Icons.Filled.Terminal) {
                    Text(text = raw, style = TranscriptTextStyle)
                }
            }
        }

        item {
            Card(title = "Airport Surface (OpenStreetMap)", icon = Icons.Filled.Map) {
                for ((label, value) in model.surfaceDiagnostics) {
                    DataRow(label, value)
                }
                model.surfaceError?.let { error ->
                    Text(
                        text = "Last error: $error",
                        style = MaterialTheme.typography.labelMedium,
                        color = IFATCTheme.semantic.connecting,
                    )
                }
                TextButton(onClick = actions.onExportSurfaceDiagnostics) {
                    Icon(Icons.Filled.IosShare, contentDescription = null)
                    Text("  Export surface diagnostics")
                }
                Text(
                    text = LegalStrings.DIAGNOSTICS_SURFACE_ATTRIBUTION,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Card(title = "Log (${model.log.size})", icon = Icons.Filled.Terminal) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = actions.onClearLog) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Text("  Clear")
                    }
                }
                // Newest first, so the most recent line is the one on screen.
                for (record in model.log.asReversed().take(LOG_LIMIT)) {
                    Text(
                        text = DiagnosticsStore.format(record),
                        style = TranscriptTextStyle,
                        color = logColor(record),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun logColor(record: DiagnosticRecord) = when (record.level) {
    com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticLevel.ERROR ->
        MaterialTheme.colorScheme.error

    com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticLevel.WARNING ->
        IFATCTheme.semantic.connecting

    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** The iOS view caps the state list at 40 and says how many more there are. */
private const val DISCOVERED_STATE_LIMIT = 40

/**
 * The log is capped at 500 records in the store; rendering all of them inside a card
 * would make the screen heavier the longer a flight runs, so the view shows the most
 * recent slice.
 */
private const val LOG_LIMIT = 200
