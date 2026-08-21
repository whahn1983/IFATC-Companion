package com.h3consultingpartners.ifatccompanion.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Typography mirroring the iOS app's hierarchy on Android's own type scale.
 *
 * SwiftUI's `.largeTitle/.title/.headline/.subheadline/.body/.caption` map onto
 * Material 3's headline/title/body/label roles rather than being reproduced at iOS
 * point sizes — the sizes here are Material's defaults, so system font scaling and
 * TalkBack behave the way Android users expect. What is preserved is the *relative*
 * hierarchy: which text is a screen title, a card header, a value, or a footnote.
 *
 * The transcript uses a monospaced family so a read-back lines up under the call it
 * answers, matching the fixed-width feel of the iOS transcript.
 */
val IFATCTypography = Typography()

/** Monospaced style for the ATC transcript and diagnostics log. */
val TranscriptTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 20.sp,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

/** Slightly heavier monospaced style for the speaker prefix in a transcript line. */
val TranscriptSpeakerStyle = TranscriptTextStyle.copy(fontWeight = FontWeight.SemiBold)

/** Tabular-feeling style for altitude / heading / speed read-outs. */
val TelemetryValueStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 20.sp,
    lineHeight = 24.sp,
)
