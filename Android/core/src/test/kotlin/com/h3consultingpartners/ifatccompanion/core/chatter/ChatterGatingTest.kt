package com.h3consultingpartners.ifatccompanion.core.chatter

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises when the ambient background chatter is allowed to run: held silent until the
 * pilot's first ATC communication, and stopped again once the flight ends.
 *
 * Ported from `IFATCCompanionTests/ChatterGatingTests.swift`. iOS reads the decision through
 * `AppModel.shouldRunAmbientChatter` and stands snapshots in for reaching a given point in
 * the flight; the decision itself is pure and lives in [ChatterRunGate], so these drive it
 * with the same three inputs the coordinator feeds it — the setting, the transcript, and the
 * state machine's current state.
 */
class ChatterGatingTest {

    private fun controllerLine() = ATCTransmission.create(
        sender = ATCTransmission.Sender.ATC,
        facility = ATCFacility.CLEARANCE,
        displayText = "Cleared to KMSP as filed.",
    )

    /** The gate as the coordinator applies it: setting + transcript + state machine. */
    private fun shouldRun(
        enabled: Boolean = true,
        transcript: List<ATCTransmission>,
        machine: ATCState,
    ): Boolean = ChatterRunGate.shouldRun(
        backgroundChatterEnabled = enabled,
        atcCommunicationStarted = ChatterRunGate.communicationStarted(transcript),
        flightHasEnded = ChatterRunGate.flightHasEnded(machine),
    )

    @Test
    fun chatterHeldUntilFirstATCCommunication() {
        // Fresh flight, nothing said yet.
        assertFalse(
            shouldRun(transcript = emptyList(), machine = ATCState.CONNECTED_IDLE),
            "chatter should stay silent before the first ATC communication",
        )

        // Once a controller/pilot exchange exists, the chatter may run.
        assertTrue(
            shouldRun(transcript = listOf(controllerLine()), machine = ATCState.CLEARANCE),
            "chatter should run once the pilot is working ATC",
        )
    }

    @Test
    fun chatterStopsWhenFlightEnds() {
        assertFalse(
            shouldRun(transcript = listOf(controllerLine()), machine = ATCState.PARKED),
            "chatter should stop once parked at the gate",
        )
    }

    @Test
    fun chatterRespectsTheSettingToggle() {
        assertTrue(shouldRun(transcript = listOf(controllerLine()), machine = ATCState.CRUISE))
        assertFalse(
            shouldRun(enabled = false, transcript = listOf(controllerLine()), machine = ATCState.CRUISE),
            "disabling the setting stops the chatter",
        )
    }

    /** An ATIS broadcast alone is not an ATC communication — it must not start the chatter. */
    @Test
    fun atisBroadcastDoesNotStartChatter() {
        val atis = ATCTransmission.create(
            sender = ATCTransmission.Sender.SYSTEM,
            facility = ATCFacility.CLEARANCE,
            displayText = "KIAH information Alpha.",
            isATIS = true,
        )
        assertFalse(
            shouldRun(transcript = listOf(atis), machine = ATCState.CONNECTED_IDLE),
            "an ATIS broadcast is not a two-way ATC communication",
        )
        assertFalse(
            ChatterRunGate.startsCommunication(atis),
            "an ATIS broadcast must not open the gate on its own",
        )
    }
}
