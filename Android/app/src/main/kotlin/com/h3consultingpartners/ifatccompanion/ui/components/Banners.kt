package com.h3consultingpartners.ifatccompanion.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.h3consultingpartners.ifatccompanion.core.session.PilotActionPresentation
import com.h3consultingpartners.ifatccompanion.ui.theme.IFATCTheme

/**
 * The tinted, tappable banners the ATC screen stacks above its cards, ported from the
 * `subscribeBanner`, `standbyBanner` and `weatherBanner` in
 * `IFATCCompanion/Views/ATCView.swift`.
 */
@Composable
private fun Banner(
    text: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    trailingText: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = tint.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.5f)),
        onClick = onClick ?: {},
        enabled = onClick != null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
            Spacer(Modifier.weight(1f))
            if (trailingText != null) {
                Text(
                    text = trailingText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                )
            }
            if (onClick != null) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Compact upsell shown at the top of the ATC view only while the pilot has no active
 * subscription. Tapping it opens the subscription screen. Hidden entirely once Live
 * Connected Mode is unlocked.
 */
@Composable
fun SubscribeBanner(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Banner(
        text = PilotActionPresentation.SUBSCRIBE_BANNER,
        icon = Icons.Filled.Lock,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier,
        onClick = onClick,
    )
}

/**
 * Shown while a human controller is staffing the frequency and the companion has
 * stepped aside.
 */
@Composable
fun StandbyBanner(text: String, modifier: Modifier = Modifier) {
    Banner(
        text = text,
        icon = Icons.Filled.RecordVoiceOver,
        tint = IFATCTheme.semantic.connecting,
        modifier = modifier,
    )
}

/**
 * "Weather ahead — contact ATC". Tapping contacts the tuned controller for the simulated
 * weather advisory. Shown only when a route-weather conflict exists.
 */
@Composable
fun WeatherBanner(text: String, onContactAtc: () -> Unit, modifier: Modifier = Modifier) {
    Banner(
        text = text,
        icon = Icons.Filled.Thunderstorm,
        tint = IFATCTheme.semantic.severitySevere,
        modifier = modifier,
        trailingText = "Contact ATC",
        onClick = onContactAtc,
    )
}
