package com.h3consultingpartners.ifatccompanion.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.ui.theme.IFATCTheme
import com.h3consultingpartners.ifatccompanion.ui.theme.TranscriptSpeakerStyle
import com.h3consultingpartners.ifatccompanion.ui.theme.TranscriptTextStyle

/**
 * One line of the ATC transcript.
 *
 * The speaker prefix is monospaced and fixed-width so a pilot read-back lines up under
 * the controller call it answers — the same fixed-width feel the iOS transcript has, and
 * the reason a monospaced family is used here and nowhere else in the app.
 *
 * TalkBack reads the whole line as one utterance ("Ground: taxi to runway…"), because
 * announcing the prefix and the body as two separate nodes turns a readable transcript
 * into a stutter.
 */
@Composable
fun TranscriptRow(
    transmission: ATCTransmission,
    modifier: Modifier = Modifier,
) {
    val speaker = speakerLabel(transmission)
    val color = speakerColor(transmission)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$speaker: ${transmission.displayText}" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = speaker,
            style = TranscriptSpeakerStyle,
            color = color,
            modifier = Modifier.width(SPEAKER_COLUMN_WIDTH),
        )
        Text(
            text = transmission.displayText,
            style = TranscriptTextStyle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The transcript, newest first — the order the iOS view renders it in. */
@Composable
fun TranscriptList(
    transcript: List<ATCTransmission>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (transmission in transcript.asReversed()) {
            TranscriptRow(transmission)
        }
    }
}

/**
 * The current transmission, shown large above the transcript so the pilot can read the
 * last call at a glance without scrolling.
 */
@Composable
fun CurrentTransmission(
    transmission: ATCTransmission?,
    onReplay: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (transmission == null) {
            Text(
                text = com.h3consultingpartners.ifatccompanion.core.session
                    .PilotActionPresentation.AWAITING_FIRST_TRANSMISSION,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                text = speakerLabel(transmission),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = speakerColor(transmission),
            )
            Spacer(Modifier.weight(1f))
            if (onReplay != null) {
                androidx.compose.material3.TextButton(onClick = onReplay) {
                    Text("Replay")
                }
            }
        }
        Text(
            text = transmission.displayText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun speakerLabel(transmission: ATCTransmission): String = when {
    transmission.isATISLine -> "ATIS"
    transmission.sender == ATCTransmission.Sender.PILOT -> "You"
    transmission.sender == ATCTransmission.Sender.SYSTEM -> "System"
    else -> transmission.facility.title
}

@Composable
private fun speakerColor(transmission: ATCTransmission): Color {
    val semantic = IFATCTheme.semantic
    return when {
        transmission.sender == ATCTransmission.Sender.PILOT -> semantic.pilot
        transmission.sender == ATCTransmission.Sender.SYSTEM -> semantic.system
        else -> when (transmission.facility) {
            ATCFacility.CLEARANCE -> semantic.facilityClearance
            ATCFacility.RAMP -> semantic.facilityRamp
            ATCFacility.GROUND -> semantic.facilityGround
            ATCFacility.TOWER -> semantic.facilityTower
            ATCFacility.DEPARTURE -> semantic.facilityDeparture
            ATCFacility.CENTER -> semantic.facilityCenter
            ATCFacility.APPROACH -> semantic.facilityApproach
        }
    }
}

private val SPEAKER_COLUMN_WIDTH = 68.dp
