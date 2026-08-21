package com.h3consultingpartners.ifatccompanion.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.h3consultingpartners.ifatccompanion.ui.theme.IFATCTheme

/**
 * The shared building blocks of the app's screens, ported from
 * `IFATCCompanion/Views/Components.swift`.
 *
 * The iOS versions are SwiftUI views over a dark card aesthetic. These keep the same
 * shapes, weights and roles while being built from Material 3 surfaces, so they look
 * native on Android without losing the app's visual identity.
 *
 * Everything here is a pure composable — state in, callbacks out, no Android
 * dependencies — so `settings-uicheck.gradle.kts` can type-check it without the SDK.
 */

/** Traffic-light status used across the dashboard. */
enum class StatusLevel {
    GREEN,
    AMBER,
    RED,
    NEUTRAL,
    ;

    @Composable
    fun color(): Color = when (this) {
        GREEN -> IFATCTheme.semantic.connected
        AMBER -> IFATCTheme.semantic.connecting
        RED -> IFATCTheme.semantic.disconnected
        NEUTRAL -> MaterialTheme.colorScheme.outline
    }
}

/** A rounded card container. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CARD_CORNER_RADIUS),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(CARD_PADDING),
            verticalArrangement = Arrangement.spacedBy(CARD_SPACING),
        ) {
            if (title != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}

/** A status chip with a coloured dot. */
@Composable
fun StatusPill(
    text: String,
    level: StatusLevel,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val tint = level.color()
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = tint.copy(alpha = PILL_FILL_ALPHA),
        border = BorderStroke(1.dp, tint.copy(alpha = PILL_STROKE_ALPHA)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(tint, CircleShape),
            )
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** A label/value row for data panels. */
@Composable
fun DataRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * Large action button suitable for one-handed use while flying.
 *
 * The 64 dp minimum height is carried over from iOS deliberately: it is comfortably
 * above Android's 48 dp touch-target minimum, which matters when the pilot is tapping it
 * on a phone propped next to a tablet mid-flight.
 */
@Composable
fun ActionButton(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = ACTION_BUTTON_MIN_HEIGHT),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.5f)),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (enabled) tint else LocalContentColor.current,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A frequency-tune button: facility name plus the frequency it's reached on,
 * highlighted while it's the controller currently being worked. Dimmed once the facility
 * has no further call in the flight.
 */
@Composable
fun FrequencyButton(
    title: String,
    icon: ImageVector,
    frequency: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // One button, styled two ways — filled while it is the controller being worked,
    // outlined otherwise. It stays a single Button rather than a Surface wrapping one so
    // TalkBack announces it as a button exactly once, and the whole label (facility and
    // frequency) is read together.
    val label = "$title, $frequency"
    val colors = if (active) {
        ButtonDefaults.buttonColors()
    } else {
        ButtonDefaults.outlinedButtonColors()
    }
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = FREQUENCY_BUTTON_MIN_HEIGHT)
            .semantics { contentDescription = label },
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = colors,
        border = if (active) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = frequency,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val CARD_CORNER_RADIUS = 16.dp
private val CARD_PADDING = 16.dp
private val CARD_SPACING = 10.dp
private val ACTION_BUTTON_MIN_HEIGHT = 64.dp
private val FREQUENCY_BUTTON_MIN_HEIGHT = 72.dp
private const val PILL_FILL_ALPHA = 0.18f
private const val PILL_STROKE_ALPHA = 0.5f
