package com.h3consultingpartners.ifatccompanion.core.phraseology

/**
 * Deterministic guard against banned / outdated / unsafe phraseology. Used by
 * tests (and available at runtime) to ensure generated calls never contain
 * prohibited wording. Pure string analysis — no AI.
 *
 * Two tiers:
 *  - [Severity.BLOCK] — must never appear in any generated transmission.
 *  - [Severity.WARN]  — context-dependent; flagged for review but not auto-blocked.
 *
 * Ported from `IFATCCompanion/Phraseology/PhraseologyValidator.swift`.
 */
class PhraseologyValidator {

    enum class Severity { BLOCK, WARN }

    data class Finding(
        val phrase: String,
        val severity: Severity,
        val reason: String,
    )

    /** One entry in the banned-phrase table. */
    data class BannedPhrase(
        val phrase: String,
        val severity: Severity,
        val reason: String,
    )

    /** All findings (block + warn) in a single string. */
    fun findings(text: String): List<Finding> {
        val n = normalize(text)
        val out = mutableListOf<Finding>()
        for (entry in banned) {
            if (n.contains(entry.phrase)) {
                out.add(Finding(entry.phrase, entry.severity, entry.reason))
            }
        }
        return out
    }

    /** Only the blocking findings. */
    fun blockingFindings(text: String): List<Finding> =
        findings(text).filter { it.severity == Severity.BLOCK }

    /** True when the text contains no [Severity.BLOCK] phrases. */
    fun isClean(text: String): Boolean = blockingFindings(text).isEmpty()

    /**
     * Whether a readback acknowledging a safety-critical instruction is acceptable:
     * it must NOT be a bare weak ack ("roger"/"wilco") and SHOULD echo the required
     * elements (e.g. the runway, heading, altitude).
     */
    fun isAcceptableSafetyReadback(text: String, requiredElements: List<String>): Boolean {
        val n = normalize(text)
        // A bare weak-ack readback (no substantive content) is unacceptable. A token of
        // two characters or fewer counts as non-substantive alongside the weak acks.
        val words = n.split(" ").filter { it.isNotEmpty() }
        val onlyWeak = words.isNotEmpty() && words.all { weakAcks.contains(it) || it.length <= 2 }
        if (onlyWeak) return false
        for (el in requiredElements) {
            if (!n.contains(normalize(el))) return false
        }
        return true
    }

    companion object {
        /**
         * Phrases prohibited anywhere in a generated controller/ramp/pilot call.
         * Matched case-insensitively as substrings on a normalized string.
         */
        val banned: List<BannedPhrase> = listOf(
            BannedPhrase("cleared to taxi", Severity.BLOCK, "Taxi is an instruction, not a clearance — use \"taxi\"."),
            BannedPhrase("cleared for taxi", Severity.BLOCK, "Taxi is an instruction, not a clearance — use \"taxi\"."),
            BannedPhrase("cleared for pushback", Severity.BLOCK, "Use \"pushback approved\" / \"push approved\"."),
            BannedPhrase("cleared for push", Severity.BLOCK, "Use \"pushback approved\" / \"push approved\"."),
            BannedPhrase("position and hold", Severity.BLOCK, "Outdated — use \"line up and wait\"."),
            BannedPhrase("taxi into position and hold", Severity.BLOCK, "Outdated — use \"line up and wait\"."),
            BannedPhrase("takeoff at your discretion", Severity.BLOCK, "Takeoff requires \"cleared for takeoff\"."),
            BannedPhrase("take off at your discretion", Severity.BLOCK, "Takeoff requires \"cleared for takeoff\"."),
            BannedPhrase("cleared for departure", Severity.WARN, "Not a takeoff clearance — use \"cleared for takeoff\"."),
            BannedPhrase("line up and wait behind", Severity.BLOCK, "Conditional line-up instructions are prohibited."),
            BannedPhrase("taxi as requested", Severity.BLOCK, "Controlled movement areas require explicit taxi routing."),
            BannedPhrase("cross all runways", Severity.BLOCK, "Each runway crossing requires an explicit, separate clearance."),
            BannedPhrase("cleared across all runways", Severity.BLOCK, "Each runway crossing requires an explicit, separate clearance."),
            BannedPhrase("proceed as requested", Severity.WARN, "Use explicit taxi/runway instructions where required."),
            BannedPhrase("any traffic please advise", Severity.BLOCK, "Prohibited (AIM) — do not solicit blanket traffic calls."),
            BannedPhrase("last call", Severity.WARN, "Avoid \"last call\" phrasing."),
            BannedPhrase("clear active", Severity.BLOCK, "Name the specific runway — avoid \"active\"."),
            BannedPhrase("clear of the active", Severity.BLOCK, "Name the specific runway — avoid \"active\"."),
            BannedPhrase("taking the active", Severity.BLOCK, "Name the specific runway — avoid \"active\"."),
            BannedPhrase("active runway", Severity.WARN, "Use the specific runway number when known."),
            BannedPhrase("the active", Severity.WARN, "Use the specific runway number when known."),
            BannedPhrase("with you", Severity.WARN, "Do not generate \"with you\" in pilot check-ins."),
            BannedPhrase("on the ils", Severity.WARN, "Use a proper approach/status check-in, not \"on the ILS\"."),
        )

        /**
         * Acknowledgments that are NOT acceptable on their own for a safety-critical
         * readback (hold-short, runway crossing, landing, takeoff, heading, altitude).
         */
        val weakAcks: List<String> = listOf("roger", "wilco")

        /** Normalize for matching: lowercase, collapse whitespace, strip most punctuation. */
        fun normalize(text: String): String {
            val lowered = text.lowercase()
            val cleaned = lowered.replace("’", "'")
            return cleaned.split(' ', '\n', '\t').filter { it.isNotEmpty() }.joinToString(" ")
        }

        /**
         * Convenience: does the text contain a callsign-like trailing token? Used by
         * tests to assert readbacks include the callsign.
         */
        fun contains(text: String, element: String): Boolean =
            normalize(text).contains(normalize(element))
    }
}
