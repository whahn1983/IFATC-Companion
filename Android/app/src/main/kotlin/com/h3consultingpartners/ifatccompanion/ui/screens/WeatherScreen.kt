package com.h3consultingpartners.ifatccompanion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.h3consultingpartners.ifatccompanion.core.weather.METAR
import com.h3consultingpartners.ifatccompanion.core.weather.RideReportItem
import com.h3consultingpartners.ifatccompanion.core.weather.SIGMET
import com.h3consultingpartners.ifatccompanion.core.weather.TAF
import com.h3consultingpartners.ifatccompanion.core.weather.TurbulenceSeverity
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.RideAssessment
import com.h3consultingpartners.ifatccompanion.core.weather.radar.RadarOverlayModel
import com.h3consultingpartners.ifatccompanion.ui.components.Card
import com.h3consultingpartners.ifatccompanion.ui.components.DataRow
import com.h3consultingpartners.ifatccompanion.ui.components.StatusLevel
import com.h3consultingpartners.ifatccompanion.ui.components.StatusPill
import com.h3consultingpartners.ifatccompanion.ui.theme.IFATCTheme
import com.h3consultingpartners.ifatccompanion.ui.theme.TranscriptTextStyle
import kotlin.math.roundToInt

/** Everything the Weather screen shows. */
data class WeatherScreenModel(
    val radarOverlay: RadarOverlayModel,
    val radarOverlayEnabled: Boolean,
    val radarOpacity: Float,
    val showWeatherDataSourceLabels: Boolean,
    val showWeatherCoverageWarnings: Boolean,
    val lastRadarUpdatedText: String,
    val weatherStatus: String,
    val departureIcao: String,
    val destinationIcao: String,
    val alternateIcao: String,
    val departureMetar: METAR?,
    val destinationMetar: METAR?,
    val alternateMetar: METAR?,
    val destinationTaf: TAF?,
    val rideAssessment: RideAssessment,
    val rideReports: List<RideReportItem>,
    val routeSigmets: List<SIGMET>,
    val totalSigmetCount: Int,
)

data class WeatherScreenActions(
    val onToggleRadarOverlay: (Boolean) -> Unit,
    val onRadarOpacityChange: (Float) -> Unit,
    val onRefresh: () -> Unit,
)

/**
 * The Weather tab — the route overlay, the precipitation layer and its controls, the
 * METARs and TAF, the ride assessment, and the advisories along the route.
 *
 * Ported from `IFATCCompanion/Views/WeatherView.swift`: the same cards in the same order,
 * the same legends, and the same disclaimer copy verbatim — the "this is a satellite
 * estimate, not radar" wording in particular, which is a promise the app makes about its
 * data rather than decoration.
 *
 * The one structural difference is the refresh gesture. iOS refreshes only by
 * pull-to-refresh and says so in a note at the top. Compose's `PullToRefreshBox` is still
 * experimental in the BOM this project pins, and a screen whose only way to load weather
 * is an unstable API is the wrong trade, so the note becomes a refresh action in the top
 * bar — the same single entry point, with a control an Android user can see.
 */
@Composable
fun WeatherScreen(
    model: WeatherScreenModel,
    actions: WeatherScreenActions,
    modifier: Modifier = Modifier,
    routeMap: @Composable () -> Unit = {},
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { RouteOverlayCard(routeMap) }
        item { PrecipitationCard(model, actions) }
        item {
            Card(title = "Status", icon = Icons.Filled.Info) {
                Text(
                    text = model.weatherStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { MetarCard("Departure METAR", model.departureMetar, model.departureIcao) }
        item { MetarCard("Destination METAR", model.destinationMetar, model.destinationIcao) }
        if (model.alternateIcao.isNotEmpty()) {
            item { MetarCard("Alternate METAR", model.alternateMetar, model.alternateIcao) }
        }
        item { TafCard(model.destinationTaf) }
        item { OverallRideCard(model.rideAssessment) }
        item { RideReportsCard(model.rideReports) }
        item { SigmetCard(model.routeSigmets, model.totalSigmetCount) }
        item { DisclaimerCard() }
    }
}

// region Route overlay

@Composable
private fun RouteOverlayCard(routeMap: @Composable () -> Unit) {
    Card(title = "Route & Weather Overlay", icon = Icons.Filled.Map) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            routeMap()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendDot(IFATCTheme.semantic.severityLight, "Light/chop")
                LegendDot(IFATCTheme.semantic.severityModerate, "Light")
                LegendDot(IFATCTheme.semantic.severitySevere, "Moderate")
                LegendDot(IFATCTheme.semantic.severityExtreme, "Severe")
            }
            Caption(
                "Dots are pilot reports; shaded areas are SIGMET/AIRMET advisories and the " +
                    "precipitation overlay where available (NOAA radar in the U.S., or a NASA " +
                    "satellite estimate elsewhere). The mint paths are the simulated recommended " +
                    "reroutes around the precipitation on your route.",
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// endregion

// region Precipitation

/**
 * Precipitation overlay controls (NOAA radar → OPERA radar → NASA satellite estimate),
 * coverage/source labels, opacity, legend, and attribution. Simulation-only. A satellite
 * estimate is never presented as radar.
 */
@Composable
private fun PrecipitationCard(model: WeatherScreenModel, actions: WeatherScreenActions) {
    val overlay = model.radarOverlay
    Card(title = "Precipitation Overlay", icon = Icons.Filled.Cloud) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Precipitation Overlay", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                Switch(checked = model.radarOverlayEnabled, onCheckedChange = actions.onToggleRadarOverlay)
            }

            if (overlay.coverageAvailable) {
                DataRow(label = "Layer", value = overlay.layerLabel)
            }
            if (model.showWeatherDataSourceLabels && overlay.coverageAvailable) {
                DataRow(label = "Source", value = overlay.sourceDescription)
            }
            DataRow(label = "Last updated", value = model.lastRadarUpdatedText)

            if (overlay.coverageAvailable) {
                LabelledNote(
                    text = overlay.coverageLabel,
                    icon = Icons.Filled.CheckCircle,
                    color = IFATCTheme.semantic.connected,
                )
                if (overlay.isSatelliteEstimate) {
                    LabelledNote(
                        text = "Satellite precipitation estimate — lower confidence than radar. Not radar.",
                        icon = Icons.Filled.Info,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        small = true,
                    )
                }
            } else if (model.showWeatherCoverageWarnings) {
                LabelledNote(
                    text = overlay.unavailableMessage,
                    icon = Icons.Filled.Warning,
                    color = IFATCTheme.semantic.connecting,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Caption("Opacity")
                    Spacer(Modifier.weight(1f))
                    Caption("${(model.radarOpacity * 100).roundToInt()}%")
                }
                Slider(
                    value = model.radarOpacity,
                    onValueChange = actions.onRadarOpacityChange,
                    valueRange = 0.1f..1.0f,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Caption("Legend")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendDot(IFATCTheme.semantic.severityLight, "Light")
                    LegendDot(IFATCTheme.semantic.severityModerate, "Moderate")
                    LegendDot(IFATCTheme.semantic.severitySevere, "Heavy")
                    LegendDot(IFATCTheme.semantic.severityExtreme, "Extreme")
                }
                Caption("precipitation")
            }

            val attribution = overlay.attributionText
            if (model.showWeatherDataSourceLabels && attribution != null) {
                Caption(attribution)
            }
        }
    }
}

// endregion

// region METAR / TAF

@Composable
private fun MetarCard(title: String, metar: METAR?, icao: String) {
    Card(title = title, icon = Icons.Filled.Thermostat) {
        if (metar == null) {
            Text(
                text = if (icao.isEmpty()) "No airport set." else "No METAR for $icao.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Card
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(metar.icao, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                val category = metar.flightCategory
                if (category != null) {
                    StatusPill(text = category, level = categoryLevel(category), icon = Icons.Filled.Visibility)
                }
            }
            val direction = metar.windDirection
            val speed = metar.windSpeed
            if (direction != null && speed != null) {
                val gust = metar.windGust?.let { " G$it" } ?: ""
                DataRow(label = "Wind", value = "${"%03d".format(direction)}° @ $speed kt$gust")
            }
            metar.visibilitySM?.let { DataRow(label = "Visibility", value = "${formatVisibility(it)} SM") }
            metar.ceilingFt?.let { DataRow(label = "Ceiling", value = "$it ft") }
            metar.altimeterInHg?.let { DataRow(label = "Altimeter", value = "%.2f inHg".format(it)) }
            metar.temperatureC?.let { temperature ->
                val dewpoint = metar.dewpointC?.let { "${it.toInt()}°C" } ?: "—"
                DataRow(label = "Temp / Dew", value = "${temperature.toInt()}°C / $dewpoint")
            }
            if (metar.raw.isNotEmpty()) {
                Text(
                    text = metar.raw,
                    style = TranscriptTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TafCard(taf: TAF?) {
    Card(title = "Destination TAF", icon = Icons.Filled.CalendarMonth) {
        if (taf != null && taf.raw.isNotEmpty()) {
            Text(
                text = taf.raw,
                style = TranscriptTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "No TAF loaded.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// endregion

// region Ride

@Composable
private fun OverallRideCard(assessment: RideAssessment) {
    Card(title = "Overall Ride", icon = Icons.Filled.Speed) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    text = assessment.severity.title,
                    level = severityLevel(assessment.severity),
                    icon = Icons.Filled.Air,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Ride index ${(assessment.index * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            LinearProgressIndicator(
                progress = { assessment.index.toFloat() },
                modifier = Modifier.fillMaxWidth(),
                color = severityLevel(assessment.severity).color(),
            )
            Caption(
                if (assessment.contributors.isEmpty()) {
                    "Composite model: PIREPs, SIGMETs, and surface wind shear."
                } else {
                    "Factors: " + assessment.contributors.joinToString(", ")
                },
            )
        }
    }
}

@Composable
private fun RideReportsCard(reports: List<RideReportItem>) {
    Card(title = "Ride Reports", icon = Icons.Filled.Air) {
        if (reports.isEmpty()) {
            Text(
                text = "No significant ride reports along your route.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Card
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (item in reports) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    StatusPill(
                        text = item.severity.title,
                        level = severityLevel(item.severity),
                        icon = Icons.Filled.Air,
                    )
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val distance = item.distanceAheadNM
                        Text(
                            text = if (distance != null && item.distanceIsFromAircraft) {
                                "${distance.roundToInt()} NM ahead"
                            } else {
                                // No live aircraft fix — the distance would be origin-relative,
                                // so show a route-relative label instead.
                                "Along route"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        item.altitudeBand?.let {
                            Caption("${it.first}–${it.last} ft")
                        }
                        item.nearFix?.let { Caption("near $it") }
                    }
                }
            }
        }
    }
}

// endregion

// region Advisories & disclaimers

@Composable
private fun SigmetCard(routeSigmets: List<SIGMET>, totalCount: Int) {
    Card(title = "SIGMET / AIRMET", icon = Icons.Filled.Warning) {
        if (routeSigmets.isEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "No advisories along your route.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (totalCount > 0) {
                    Caption("$totalCount active elsewhere (off route).")
                }
            }
            return@Card
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Caption("Along your route:")
            for (sigmet in routeSigmets.take(MAX_ROUTE_SIGMETS)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = sigmet.hazard ?: "Advisory",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (sigmet.raw.isNotEmpty()) {
                        Text(
                            text = sigmet.raw,
                            style = TranscriptTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DisclaimerCard() {
    Card(title = "About This Data", icon = Icons.Filled.Info) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Radar, precipitation, and deviation logic are for simulation only and " +
                    "must not be used for real-world aviation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Radar precipitation is available only where the app's free NOAA/NWS " +
                    "(U.S.) data source provides coverage. Elsewhere — including Europe — the " +
                    "app shows a NASA global satellite precipitation estimate, which is not " +
                    "radar. No global radar coverage is implied.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Caption(
                "Training and entertainment use only. No paid weather subscription, API key, " +
                    "or account is required.",
            )
        }
    }
}

// endregion

// region Small pieces

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LabelledNote(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    small: Boolean = false,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = color,
        )
        Text(
            text = text,
            style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

private fun categoryLevel(category: String): StatusLevel = when (category.uppercase()) {
    "VFR" -> StatusLevel.GREEN
    "MVFR" -> StatusLevel.AMBER
    "IFR", "LIFR" -> StatusLevel.RED
    else -> StatusLevel.NEUTRAL
}

private fun severityLevel(severity: TurbulenceSeverity): StatusLevel = when (severity) {
    TurbulenceSeverity.SMOOTH, TurbulenceSeverity.LIGHT_CHOP -> StatusLevel.GREEN
    TurbulenceSeverity.LIGHT -> StatusLevel.AMBER
    TurbulenceSeverity.MODERATE, TurbulenceSeverity.SEVERE -> StatusLevel.RED
}

/** iOS prints a whole number when the visibility is whole, one decimal otherwise. */
private fun formatVisibility(value: Double): String =
    if (value == value.toLong().toDouble()) value.toInt().toString() else "%.1f".format(value)

private const val MAX_ROUTE_SIGMETS = 5

// endregion
