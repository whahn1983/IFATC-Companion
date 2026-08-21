package com.h3consultingpartners.ifatccompanion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.ui.SimBriefStrings
import com.h3consultingpartners.ifatccompanion.ui.components.Card
import com.h3consultingpartners.ifatccompanion.ui.components.DataRow

/** The manual-override fields, which are pilot-entered rather than read from the sim. */
data class FlightOverrides(
    val callsign: String = "",
    val airline: String = "",
    val flightNumber: String = "",
    val departure: String = "",
    val destination: String = "",
    val alternate: String = "",
    val cruiseAltitude: String = "",
    val runway: String = "",
    val approach: String = "",
    val sid: String = "",
    val star: String = "",
    val departureGate: String = "",
    val arrivalGate: String = "",
)

data class FlightScreenModel(
    val aircraftState: AircraftState,
    val flightPlan: FlightPlan,
    val phase: FlightPhase,
    val activeRunway: String,
    val distanceToDestination: String,
    val nextWaypoint: String,
    val airportProximity: String,
    val cruiseAltitudeText: String,
    val overrides: FlightOverrides,
    val mockMode: Boolean,
)

data class FlightScreenActions(
    val onOverrideChange: (FlightOverrides) -> Unit,
    val onApplyOverrides: () -> Unit,
    val onClearOverrides: () -> Unit,
    val onRefreshFlightPlan: () -> Unit,
    val onOpenSimBrief: () -> Unit,
)

/**
 * The Flight tab — live aircraft state, the filed plan, and the manual overrides.
 *
 * Ported from `IFATCCompanion/Views/FlightView.swift`: the same three cards, the same
 * rows in the same order, the same labels and the same em-dash for an unknown value.
 */
@Composable
fun FlightScreen(
    model: FlightScreenModel,
    actions: FlightScreenActions,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { LiveStateCard(model) }
        item { FlightPlanCard(model, actions) }
        item { OverridesCard(model, actions) }
    }
}

@Composable
private fun LiveStateCard(model: FlightScreenModel) {
    val s = model.aircraftState
    Card(title = "Live Aircraft State", icon = Icons.Filled.FlightTakeoff) {
        DataRow("Latitude", decimal(s.latitude, 4))
        DataRow("Longitude", decimal(s.longitude, 4))
        DataRow("Altitude MSL", rounded(s.altitudeMSL, "ft"))
        DataRow("Groundspeed", rounded(s.groundSpeed, "kt"))
        DataRow("Airspeed (IAS)", rounded(s.indicatedAirspeed, "kt"))
        DataRow("Heading", heading(s.heading))
        DataRow("Track", heading(s.track))
        DataRow("Vertical Speed", rounded(s.verticalSpeed, "fpm"))
        DataRow("On Ground", s.onGround?.let { if (it) "Yes" else "No" } ?: DASH)
        DataRow("Distance to Dest", model.distanceToDestination)
        DataRow("Next Waypoint", model.nextWaypoint)
        DataRow("Flight Phase", model.phase.title)
        DataRow("Airport Proximity", model.airportProximity)
        DataRow("Runway", model.activeRunway.ifEmpty { "auto" })
    }
}

@Composable
private fun FlightPlanCard(model: FlightScreenModel, actions: FlightScreenActions) {
    val plan = model.flightPlan
    Card(title = "Flight Plan", icon = Icons.Filled.Map) {
        DataRow("Departure", orDash(plan.departure))
        DataRow("Destination", orDash(plan.destination))
        DataRow("Alternate", orDash(plan.alternate))
        DataRow("Cruise", model.cruiseAltitudeText)
        DataRow("SID", orDash(plan.sid))
        DataRow("STAR", orDash(plan.star))
        DataRow("Approach", orDash(plan.approach))

        if (plan.waypoints.isNotEmpty()) {
            HorizontalDivider()
            Text(
                text = "Waypoints (${plan.waypoints.size})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = plan.waypoints.joinToString("  →  ") { it.name },
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        HorizontalDivider()
        WideButton(
            title = if (model.mockMode) "Refresh Flight Plan" else "Refresh from Infinite Flight",
            icon = Icons.Filled.Refresh,
            onClick = actions.onRefreshFlightPlan,
        )
        WideButton(
            title = SimBriefStrings.BUTTON_TITLE,
            icon = Icons.Filled.NoteAdd,
            onClick = actions.onOpenSimBrief,
        )
        Text(
            text = SimBriefStrings.HELPER_TEXT,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = SimBriefStrings.NOT_AFFILIATED,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OverridesCard(model: FlightScreenModel, actions: FlightScreenActions) {
    val o = model.overrides
    Card(title = "Manual Overrides", icon = Icons.Filled.Edit) {
        Text(
            text = "Connect/Live coverage varies — enter any values manually.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OverrideField("Callsign", o.callsign, "e.g. UAL598 or N123AB") {
            actions.onOverrideChange(o.copy(callsign = it))
        }
        FieldPair(
            { OverrideField("Airline", o.airline, "United") { actions.onOverrideChange(o.copy(airline = it)) } },
            { OverrideField("Flight #", o.flightNumber, "598") { actions.onOverrideChange(o.copy(flightNumber = it)) } },
        )
        FieldPair(
            { OverrideField("Departure", o.departure, "KIAH") { actions.onOverrideChange(o.copy(departure = it)) } },
            { OverrideField("Destination", o.destination, "KMSP") { actions.onOverrideChange(o.copy(destination = it)) } },
        )
        FieldPair(
            { OverrideField("Alternate", o.alternate, "KDSM") { actions.onOverrideChange(o.copy(alternate = it)) } },
            {
                OverrideField(
                    "Cruise (ft)", o.cruiseAltitude, "37000", numeric = true,
                ) { actions.onOverrideChange(o.copy(cruiseAltitude = it.filter(Char::isDigit))) }
            },
        )
        FieldPair(
            { OverrideField("Runway", o.runway, "17R") { actions.onOverrideChange(o.copy(runway = it)) } },
            { OverrideField("Approach", o.approach, "ILS 30L") { actions.onOverrideChange(o.copy(approach = it)) } },
        )
        FieldPair(
            { OverrideField("SID", o.sid, "WAGmm") { actions.onOverrideChange(o.copy(sid = it)) } },
            { OverrideField("STAR", o.star, "KKILR") { actions.onOverrideChange(o.copy(star = it)) } },
        )
        FieldPair(
            { OverrideField("Departure Gate", o.departureGate, "C12") { actions.onOverrideChange(o.copy(departureGate = it)) } },
            { OverrideField("Arrival Gate", o.arrivalGate, "B44") { actions.onOverrideChange(o.copy(arrivalGate = it)) } },
        )

        Button(
            onClick = actions.onApplyOverrides,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null)
            Text("  Apply Overrides")
        }
        OutlinedButton(
            onClick = actions.onClearOverrides,
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(Icons.Filled.Cancel, contentDescription = null)
            Text("  Clear Overrides")
        }
    }
}

@Composable
private fun FieldPair(
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) { left() }
        Column(Modifier.weight(1f)) { right() }
    }
}

@Composable
private fun OverrideField(
    label: String,
    value: String,
    placeholder: String,
    numeric: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                capitalization = if (numeric) {
                    KeyboardCapitalization.None
                } else {
                    KeyboardCapitalization.Characters
                },
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Ascii,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WideButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
    ) {
        Icon(icon, contentDescription = null)
        Text("  $title")
    }
}

private const val DASH = "—"

private fun orDash(value: String) = value.ifEmpty { DASH }

private fun decimal(value: Double?, places: Int): String =
    value?.let { String.format(java.util.Locale.US, "%.${places}f", it) } ?: DASH

private fun rounded(value: Double?, unit: String): String =
    value?.let { "${Math.round(it)} $unit" } ?: DASH

private fun heading(value: Double?): String =
    value?.let { String.format(java.util.Locale.US, "%03d°", (Math.round(it) % 360).toInt()) }
        ?: DASH
