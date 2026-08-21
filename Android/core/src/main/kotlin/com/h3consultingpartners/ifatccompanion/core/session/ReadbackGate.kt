package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Real controllers wait for the pilot to read an instruction back before issuing the
 * next one. The automatic (telemetry-driven) flow mirrors that: after a controller call
 * that expects a readback, the conversation holds — no further automatic call is issued
 * — until the pilot reads back. Any pilot transmission clears the gate. If the pilot is
 * idle, the controller repeats the call and asks "how do you read?" every
 * [repeatIntervalMillis].
 *
 * This is what stops calls from firing back-to-back near the runway.
 *
 * Ported from the readback-gate section of `IFATCCompanion/App/AppModel.swift`. The
 * Swift keeps this state in the model itself; pulling it into its own object makes the
 * timing rules testable without driving a whole flight.
 */
class ReadbackGate(
    private val scope: CoroutineScope,
    /** Seconds of pilot silence before the controller repeats the call. */
    private val repeatIntervalMillis: Long = DEFAULT_REPEAT_INTERVAL_MILLIS,
    /**
     * How many times to repeat before giving up. The gate stays closed afterwards so the
     * flow does not run away; the pilot can still act via the buttons or push-to-talk.
     */
    private val maxPrompts: Int = DEFAULT_MAX_PROMPTS,
    /** Whether the companion is currently deferring to a human controller. */
    private val isStandingBy: () -> Boolean = { false },
    /** Re-issues a pending call with a "how do you read?" tag. */
    private val repeatCall: (ATCTransmission) -> Unit,
    /** Notified whenever the gate opens or closes, so the session state can publish it. */
    private val onChanged: (Boolean) -> Unit = {},
) {

    /** True while waiting for the pilot to acknowledge the last automatic call. */
    var isClosed: Boolean = false
        private set

    /** The controller call to repeat if the pilot stays idle. */
    private var pendingTransmission: ATCTransmission? = null

    /** Drives the idle re-prompt while the gate is closed. */
    private var timer: Job? = null

    /** How many times the idle re-prompt has fired for the current call. */
    private var prompts = 0

    /**
     * Close the gate after an automatic instruction so the next call waits for the
     * pilot's read-back.
     *
     * When [promptIfIdle] is true the idle re-prompt loop is armed so an unanswered call
     * is repeated with "how do you read?". The takeoff clearance passes false so it holds
     * the gate silently — a controller does not radio-check a pilot it has just cleared
     * for takeoff, and the nag was firing before the pilot could even read the clearance
     * back.
     */
    fun engage(transmission: ATCTransmission, promptIfIdle: Boolean = true) {
        isClosed = true
        pendingTransmission = transmission
        prompts = 0
        onChanged(true)
        if (promptIfIdle) armTimer()
    }

    /**
     * Re-point a closed gate at a freshly posted hand-off so the pilot's read-back echoes
     * "contacting <next>" (the genuine last message) — and cancel the idle re-prompt,
     * since a courtesy hand-off must never trigger a "how do you read?" the way a missed
     * instruction does. Used when an instruction is immediately followed by a hand-off
     * ("cleared the ILS … contact Tower").
     */
    fun soften(transmission: ATCTransmission) {
        if (!isClosed) return
        pendingTransmission = transmission
        timer?.cancel()
        timer = null
    }

    /**
     * Open the gate once the pilot has responded (any pilot transmission counts as an
     * acknowledgement) so the automatic flow can resume.
     */
    fun clear() {
        if (!isClosed) return
        isClosed = false
        pendingTransmission = null
        prompts = 0
        timer?.cancel()
        timer = null
        onChanged(false)
    }

    /** Drop everything without notifying — used when the flight is reset. */
    fun reset() {
        isClosed = false
        pendingTransmission = null
        prompts = 0
        timer?.cancel()
        timer = null
    }

    /**
     * Schedule the next idle re-prompt. If the pilot stays silent the controller repeats
     * the call and asks "how do you read?", up to [maxPrompts] times, after which the
     * gate stays closed until the pilot acts.
     */
    private fun armTimer() {
        timer?.cancel()
        if (!isClosed) return
        timer = scope.launch {
            delay(repeatIntervalMillis)
            if (!isActive) return@launch
            if (!isClosed) return@launch
            val transmission = pendingTransmission ?: return@launch
            if (prompts >= maxPrompts) return@launch
            // Stand by while tuned to a human controller: don't nag "how do you read?"
            // over a live controller, but keep the timer alive (re-arm without counting a
            // prompt) so the reminder resumes if the pilot leaves the human frequency
            // before reading back.
            if (isStandingBy()) {
                armTimer()
                return@launch
            }
            prompts += 1
            repeatCall(transmission)
            armTimer()
        }
    }

    companion object {
        /** Seconds of pilot silence before the controller repeats the call. */
        const val DEFAULT_REPEAT_INTERVAL_MILLIS = 30_000L

        const val DEFAULT_MAX_PROMPTS = 3

        /**
         * Compose the "how do you read?" repeat of a pending call. The callsign prefix is
         * only added when the original did not already open with it, so the repeat does
         * not read "United five ninety-eight, … United five ninety-eight, how do you
         * read?".
         */
        fun repeatText(
            transmission: ATCTransmission,
            callsignDisplay: String,
        ): Pair<String, String> {
            val prefix = "$callsignDisplay, "
            val display = if (transmission.displayText.startsWith(prefix)) {
                "${transmission.displayText} How do you read?"
            } else {
                "${transmission.displayText} $callsignDisplay, how do you read?"
            }
            val spoken = "${transmission.spokenText} How do you read?"
            return display to spoken
        }
    }
}
