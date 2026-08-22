package com.h3consultingpartners.ifatccompanion.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceConfidence
import com.h3consultingpartners.ifatccompanion.core.surface.routing.TaxiKind
import com.h3consultingpartners.ifatccompanion.core.surface.routing.TaxiMapAction
import com.h3consultingpartners.ifatccompanion.core.ui.LegalStrings
import com.h3consultingpartners.ifatccompanion.ui.components.Card
import com.h3consultingpartners.ifatccompanion.ui.components.StatusLevel
import com.h3consultingpartners.ifatccompanion.ui.components.StatusPill
import com.h3consultingpartners.ifatccompanion.ui.map.TaxiMap
import com.h3consultingpartners.ifatccompanion.ui.map.TaxiMapModel
import com.h3consultingpartners.ifatccompanion.ui.theme.IFATCTheme

/** Everything the taxi map's card shows around the canvas. */
data class TaxiMapCardModel(
    val kind: TaxiKind = TaxiKind.NONE,
    /** "To gate B44" / "To runway 27", or the fallback when no route has resolved. */
    val destinationLabel: String = "",
    val routeConfidence: SurfaceConfidence = SurfaceConfidence.UNAVAILABLE,
    /** "Via A, C, B", or "Route pending" before one is computed. */
    val taxiwayText: String = "",
    val crossingCount: Int = 0,
    val offRoute: Boolean = false,
    val nextInstruction: String = "",
    val expanded: Boolean = false,
)

/**
 * The taxi map's card: what it is, how good the route is, what is wrong with it, and the
 * licence the surface data comes under.
 *
 * The Android port drew the canvas alone — no title, no header chips, no off-route banner,
 * no controls, and **no attribution**. The last of those is not a polish item: the surface
 * geometry is OpenStreetMap data under the ODbL, and the attribution is a licence
 * condition. The file that drew the map said so in its own KDoc while showing none.
 *
 * Ported from `IFATCCompanion/Views/TaxiMapView.swift` — the card, `TaxiMapHeader`, the
 * off-route banner, the controls row, `TaxiMapFooter` and `ExpandedTaxiMap`.
 */
@Composable
fun TaxiMapCard(
    model: TaxiMapCardModel,
    map: TaxiMapModel,
    actions: List<TaxiMapAction>,
    awaitingCrossingReadback: Boolean,
    onAction: (TaxiMapAction) -> Unit,
    onCrossingReadback: () -> Unit,
    onExpand: () -> Unit,
    onReadBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier, title = TAXI_MAP_TITLE, icon = Icons.Filled.Map) {
        TaxiMapHeader(model)
        if (model.offRoute) OffRouteBanner()
        TaxiMap(
            model = map,
            actions = actions,
            awaitingCrossingReadback = awaitingCrossingReadback,
            nextInstruction = model.nextInstruction.ifBlank { FOLLOW_ROUTE_HINT },
            onAction = onAction,
            onCrossingReadback = onCrossingReadback,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExpand) {
                Icon(Icons.Filled.OpenInFull, contentDescription = null)
                Text(" Expand")
            }
            OutlinedButton(onClick = { onAction(TaxiMapAction.RECALCULATE) }) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text(" Recalculate")
            }
            // A read-back right on the map, so a taxi or crossing clearance can be
            // acknowledged without scrolling back up to the response grid.
            OutlinedButton(onClick = onReadBack) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null)
                Text(" Read Back")
            }
        }
        TaxiMapFooter(onOpenLink)
    }
}

/** Assigned destination, route confidence, the taxiway sequence, and any crossings. */
@Composable
private fun TaxiMapHeader(model: TaxiMapCardModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        StatusPill(
            text = model.destinationLabel.ifBlank {
                if (model.kind == TaxiKind.ARRIVAL) "To gate" else "To runway"
            },
            level = StatusLevel.NEUTRAL,
        )
        StatusPill(
            text = "${model.routeConfidence.title} confidence",
            level = confidenceLevel(model.routeConfidence),
            icon = Icons.Filled.Speed,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = model.taxiwayText.ifBlank { ROUTE_PENDING },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (model.crossingCount > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.SwapHoriz,
                    contentDescription = null,
                    tint = IFATCTheme.semantic.connecting,
                )
                Text(
                    text = if (model.crossingCount == 1) "1 crossing" else "${model.crossingCount} crossings",
                    style = MaterialTheme.typography.labelMedium,
                    color = IFATCTheme.semantic.connecting,
                )
            }
        }
    }
}

/** The aircraft has left the assigned route — the one thing the map must not be quiet about. */
@Composable
private fun OffRouteBanner() {
    val tint = IFATCTheme.semantic.connecting
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = tint.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = tint)
            Text(
                text = OFF_ROUTE,
                style = MaterialTheme.typography.labelLarge,
                color = tint,
            )
        }
    }
}

/**
 * Attribution and the simulation disclaimer, always visible.
 *
 * The attribution is tappable and links to OpenStreetMap's copyright page, which is what
 * the ODbL asks for — not a caption.
 */
@Composable
private fun TaxiMapFooter(onOpenLink: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(
            onClick = { onOpenLink(LegalStrings.OpenStreetMap.COPYRIGHT_URL) },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Text(
                text = LegalStrings.OpenStreetMap.ATTRIBUTION_TEXT,
                style = MaterialTheme.typography.labelSmall,
                textDecoration = TextDecoration.Underline,
            )
        }
        Text(
            text = TAXI_MAP_DISCLAIMER,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The full-screen taxi map.
 *
 * The same canvas at the height a pilot can actually read a complex field at, with the
 * attribution repeated — it is a licence condition wherever the data is shown, not once per
 * screen.
 */
@Composable
fun ExpandedTaxiMap(
    map: TaxiMapModel,
    nextInstruction: String,
    onDismiss: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(TAXI_MAP_TITLE, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                    Text(" Done")
                }
            }
            TaxiMap(model = map, expanded = true, nextInstruction = nextInstruction)
            TextButton(
                onClick = { onOpenLink(LegalStrings.OpenStreetMap.COPYRIGHT_URL) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text(
                    text = LegalStrings.OpenStreetMap.ATTRIBUTION_TEXT,
                    style = MaterialTheme.typography.labelSmall,
                    textDecoration = TextDecoration.Underline,
                )
            }
        }
    }
}

private fun confidenceLevel(confidence: SurfaceConfidence): StatusLevel = when (confidence) {
    SurfaceConfidence.HIGH -> StatusLevel.GREEN
    SurfaceConfidence.MEDIUM -> StatusLevel.AMBER
    SurfaceConfidence.LOW, SurfaceConfidence.UNAVAILABLE -> StatusLevel.RED
}

private const val TAXI_MAP_TITLE = "Taxi Map (Simulated)"
private const val ROUTE_PENDING = "Route pending"
private const val OFF_ROUTE = "Off assigned taxi route"
private const val FOLLOW_ROUTE_HINT = "Follow the assigned taxi route."
private const val TAXI_MAP_DISCLAIMER =
    "Simulation only — not for real-world aviation. OSM data may not match Infinite Flight scenery."
