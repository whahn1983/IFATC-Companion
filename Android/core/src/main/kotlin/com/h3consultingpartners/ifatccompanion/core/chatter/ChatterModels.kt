package com.h3consultingpartners.ifatccompanion.core.chatter

import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission

/**
 * A single simulated background-radio transmission (audio only — never shown in the
 * transcript). [isPilot] lets the audio layer colour the voice: controller-side vs a
 * read-back from the aircraft, so an exchange sounds like two stations, not one.
 *
 * Ported from `IFATCCompanion/Chatter/ChatterModels.swift`.
 */
data class ChatterLine(
    val spokenText: String,
    val isPilot: Boolean,
)

/**
 * The runways the background chatter should reference for the airport currently in play,
 * resolved by the flight coordinator from the ATIS in use and the loaded OSM surface.
 * [departures] and [arrivals] are the ATIS-active runways (so a takeoff clearance names a
 * departure runway and a landing clearance an arrival runway); [all] is what to use when
 * the operation doesn't matter, and the fallback when a side is unknown (no ATIS) — the
 * field's full runway set. Every field empty (no surface loaded, no flight plan) leaves the
 * generator on random runways.
 *
 * Ported from `IFATCCompanion/Chatter/ChatterModels.swift`.
 */
data class ChatterRunwayContext(
    val all: List<String> = emptyList(),
    val departures: List<String> = emptyList(),
    val arrivals: List<String> = emptyList(),
)

/**
 * A push-to-talk transition on the pilot's radio: keying the mic (a dull contact thump) or
 * un-keying it (the receiver-return squelch tail).
 *
 * Ported from `IFATCCompanion/Chatter/AmbientChatterService.swift`.
 */
enum class MicKeyEvent {
    /** Pilot presses PTT — key-down thump. */
    KEY_UP,

    /** Pilot releases PTT — release squelch tail. */
    KEY_DOWN,
}

/**
 * When the ambient background chatter is allowed to run.
 *
 * The chatter is the app's background-audio anchor, so *when* it runs is a product
 * decision, not an audio one: it comes up only **after the pilot's first ATC
 * communication** (you don't hear other traffic on frequency before you've checked in) and
 * goes quiet again when the flight **ends** (parked at the destination gate) or the flight
 * is **reset**. See `docs/BackgroundChatter.md`.
 *
 * iOS keeps these three facts on `AppModel` (`shouldRunAmbientChatter`,
 * `atcCommunicationStarted`, `flightHasEnded`) and calls `updateChatterRunState()` whenever
 * any of them changes. The decision itself is pure, so it lives here where the chatter does
 * — the coordinator supplies the three inputs.
 */
object ChatterRunGate {

    /**
     * `settings.backgroundChatterEnabled && atcCommunicationStarted && !flightHasEnded`,
     * verbatim from `AppModel.shouldRunAmbientChatter`.
     */
    fun shouldRun(
        backgroundChatterEnabled: Boolean,
        atcCommunicationStarted: Boolean,
        flightHasEnded: Boolean,
    ): Boolean = backgroundChatterEnabled && atcCommunicationStarted && !flightHasEnded

    /**
     * Whether a transmission is the pilot's first ATC communication — the event that opens
     * the gate. An ATIS broadcast is **not** one: it is a one-way recording, and hearing it
     * doesn't mean the pilot is on frequency with a controller.
     */
    fun startsCommunication(transmission: ATCTransmission): Boolean = transmission.isControllerExchange

    /**
     * Re-derive "communication started" from a restored transcript, so a flight resumed
     * after a reconnect keeps its chatter instead of falling silent until the next call.
     */
    fun communicationStarted(transcript: List<ATCTransmission>): Boolean =
        transcript.any { it.isControllerExchange }

    /** The flight has ended once the state machine is parked at the destination gate. */
    fun flightHasEnded(stateMachineCurrent: ATCState): Boolean = stateMachineCurrent == ATCState.PARKED
}
