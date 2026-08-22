package com.h3consultingpartners.ifatccompanion.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A single line in the ATC transcript — from a simulated controller or the pilot.
 *
 * Ported from `IFATCCompanion/Models/ATCTransmission.swift`.
 */
@OptIn(ExperimentalUuidApi::class)
@Serializable
data class ATCTransmission(
    val id: String = Uuid.random().toString(),
    val sender: Sender,
    val facility: ATCFacility,
    /** Human-readable text shown in the transcript (normal digits). */
    val displayText: String,
    /** Phonetic text passed to the speech synthesizer ("niner", "flight level…"). */
    val spokenText: String,
    /** Epoch milliseconds. iOS persists a `Date`; the wire value here is millis. */
    val timestampMillis: Long,
    /** Optional precomposed pilot read-back for this controller call. */
    val readback: Readback? = null,
    /**
     * True for a one-way ATIS broadcast line. It is spoken on the dedicated ATIS
     * voice and is never treated as a controller instruction (no read-back, no
     * hand-off bookkeeping). Nullable — rather than a defaulted Boolean — so
     * transcripts persisted before this field decode cleanly. Read it via
     * [isATISLine], which maps null/false alike.
     */
    @SerialName("isATIS") val isATIS: Boolean? = null,
) {

    @Serializable
    enum class Sender(val rawValue: String) {
        @SerialName("atc") ATC("atc"),
        @SerialName("pilot") PILOT("pilot"),
        @SerialName("system") SYSTEM("system"),
    }

    /**
     * The pilot read-back that matches *this* controller call, composed when the call
     * is built (the companion knows exactly what it said). Lets the Read Back button
     * echo the actual last message — including frequency hand-offs and vectors —
     * instead of re-deriving a read-back from the conversational state.
     */
    @Serializable
    data class Readback(
        val displayText: String,
        val spokenText: String,
        /** Facility the read-back is addressed to / spoken on. */
        val facility: ATCFacility,
        /**
         * When the call is a frequency hand-off, the facility to auto-tune to once the
         * pilot has read it back ("contacting Tower on 118.3" → switch to Tower).
         */
        val tuneTo: ATCFacility? = null,
    )

    /** Whether this line is an ATIS broadcast (null and false read as "no"). */
    val isATISLine: Boolean get() = isATIS == true

    /**
     * Whether this is a two-way ATC communication — a controller call or the pilot's
     * own call — as opposed to a one-way ATIS broadcast or a SYSTEM advisory. Marks
     * that the pilot is actively working ATC (used to gate the ambient background
     * chatter).
     */
    val isControllerExchange: Boolean
        get() = !isATISLine && (sender == Sender.ATC || sender == Sender.PILOT)

    /**
     * View a composed pilot transmission's text as a [Readback] payload that can be
     * attached to the controller call it answers.
     */
    fun asReadback(facility: ATCFacility, tuneTo: ATCFacility? = null): Readback =
        Readback(displayText, spokenText, facility, tuneTo)

    companion object {
        /**
         * Convenience factory mirroring the Swift initialiser: [spokenText] defaults to
         * [displayText], and the timestamp defaults to now.
         */
        fun create(
            sender: Sender,
            facility: ATCFacility,
            displayText: String,
            spokenText: String? = null,
            timestampMillis: Long = System.currentTimeMillis(),
            readback: Readback? = null,
            isATIS: Boolean? = null,
        ): ATCTransmission = ATCTransmission(
            sender = sender,
            facility = facility,
            displayText = displayText,
            spokenText = spokenText ?: displayText,
            timestampMillis = timestampMillis,
            readback = readback,
            isATIS = isATIS,
        )

        /**
         * Whether this controller call would only repeat the last one in [transcript] —
         * same facility, same words — and the pilot has already acknowledged it.
         *
         * A controller-initiated call can come out verbatim-identical back to back: the
         * drawn weather-deviation line can carry the same heading across consecutive
         * vertices, and each vertex fires its own turn, so the radio ends up carrying
         * "fly heading 082, vectors around precipitation" three times over while the
         * pilot is already flying exactly that. Saying it again adds nothing, so the
         * caller holds it.
         *
         * A repeat only counts once the pilot has transmitted after the original — any
         * pilot transmission is an acknowledgement, matching the read-back gate. A call
         * that went unanswered is therefore never held: re-issuing it is how an unheard
         * instruction gets through ("…how do you read?").
         */
        fun isAcknowledgedRepeat(
            tx: ATCTransmission,
            transcript: List<ATCTransmission>,
        ): Boolean {
            val last = transcript.indexOfLast { it.sender == Sender.ATC }
            if (last < 0) return false
            if (transcript[last].facility != tx.facility) return false
            if (transcript[last].displayText != tx.displayText) return false
            return transcript.drop(last + 1).any { it.sender == Sender.PILOT }
        }
    }
}
