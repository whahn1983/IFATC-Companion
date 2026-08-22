package com.h3consultingpartners.ifatccompanion.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.FlightLand
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Semantic icon key → Material Symbol.
 *
 * The engine names icons by meaning ("flight_takeoff") rather than by asset, because SF
 * Symbols do not exist on Android and :core must not know about Compose. This is the one
 * place the two vocabularies meet. Every substitution is recorded in
 * Docs/ANDROID_PARITY_MATRIX.md alongside the SF Symbol it replaces.
 */
object IFATCIcons {

    fun forKey(key: String): ImageVector = when (key) {
        "description" -> Icons.Filled.Description
        "local_parking" -> Icons.Filled.LocalParking
        "directions_car" -> Icons.Filled.DirectionsCar
        "apartment" -> Icons.Filled.Apartment
        "flight_takeoff" -> Icons.Filled.FlightTakeoff
        "flight_land" -> Icons.Filled.FlightLand
        "public" -> Icons.Filled.Public
        "first_page" -> Icons.Filled.FirstPage
        "power" -> Icons.Filled.Power
        "flag" -> Icons.Filled.Flag
        "arrow_upward" -> Icons.Filled.ArrowUpward
        "arrow_downward" -> Icons.Filled.ArrowDownward
        "arrow_circle_up" -> Icons.Filled.ArrowCircleUp
        "arrow_circle_down" -> Icons.Filled.ArrowCircleDown
        "turn_right" -> Icons.Filled.TurnRight
        "turn_left" -> Icons.Filled.TurnLeft
        "fork_right" -> Icons.Filled.ForkRight
        "arrow_forward" -> Icons.AutoMirrored.Filled.ArrowForward
        "replay" -> Icons.Filled.Replay
        "air" -> Icons.Filled.Air
        "partly_cloudy_day" -> Icons.Filled.WbSunny
        "record_voice_over" -> Icons.Filled.RecordVoiceOver
        "u_turn_left" -> Icons.Filled.UTurnLeft
        "check_circle" -> Icons.Filled.CheckCircle
        "undo" -> Icons.AutoMirrored.Filled.Undo
        "dangerous" -> Icons.Filled.Dangerous
        "cloud" -> Icons.Filled.Cloud
        // A key with no mapping is a bug, but it must not be one that blanks a button
        // mid-flight — the aircraft is a safe, meaningful stand-in.
        else -> Icons.Filled.AirplanemodeActive
    }
}
