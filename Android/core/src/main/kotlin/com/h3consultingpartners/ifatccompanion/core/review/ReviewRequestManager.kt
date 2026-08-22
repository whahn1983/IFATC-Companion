package com.h3consultingpartners.ifatccompanion.core.review

import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import com.h3consultingpartners.ifatccompanion.core.platform.KeyValueStore

/**
 * When — and whether — to ask the pilot to rate the app.
 *
 * A port of `IFATCCompanion/Review/ReviewRequestManager.swift`. Both stores apply their own
 * throttle on top of anything the app does (Play's is undocumented; Apple's is three per
 * 365 days), so a request is exactly that: a request. What this decides is whether asking is
 * *appropriate*, which is a product question and not the store's.
 *
 * The product rule is that a prompt may only appear at one of two calm moments — connected
 * and idle at the gate before the first ATC call, or arrived and parked with the flight
 * done. Never mid-flight: a pilot asked to rate the app while being vectored is being
 * interrupted at exactly the moment the app is supposed to be useful.
 *
 * This decides only. Presenting the sheet is the platform's job, and on Android that means
 * an Activity — which is why this holds no reference to one and lives in `:core`, where the
 * whole gate can be tested against a fake clock rather than by waiting three days.
 */
class ReviewRequestManager(
    private val store: KeyValueStore,
    private val clock: Clock = Clock.system,
) {

    /** The two low-workload windows a prompt is permitted in. */
    enum class Trigger {
        /** Connected and idle at the gate, before the first ATC call of the session. */
        BEFORE_FIRST_CALL,

        /** Arrived and parked — the flight is complete. */
        AFTER_FLIGHT_COMPLETE,
    }

    /**
     * Stamp the install date, once, so the install-age gate has a baseline.
     *
     * Called at start-up rather than from an `init` block: the properties it writes are
     * declared below it, and Kotlin will not let a constructor touch them. Idempotent — a
     * second call on an install that already has a date does nothing.
     */
    fun noteAppStarted() {
        if (installDateMillis == null) installDateMillis = clock.nowMillis()
    }

    /**
     * Record a fully completed flight — arrived and parked.
     *
     * Drives the engagement gate, so it must be called exactly once per completed flight. A
     * double count is not harmful in itself, but it brings the first prompt forward past the
     * point the product rule chose.
     */
    fun recordFlightCompleted() {
        completedFlights += 1
    }

    /**
     * Whether every app-side gate currently permits a prompt.
     *
     * Public rather than private because the answer is worth recording: the store's own
     * throttle means a prompt that was requested and never shown is indistinguishable from
     * one that was never requested, and on a device that is the only way to tell whether the
     * wiring works at all.
     */
    fun isEligible(): Boolean {
        if (completedFlights < MINIMUM_COMPLETED_FLIGHTS) return false
        val now = clock.nowMillis()
        // No install date means noteAppStarted has never run, which means this is the first
        // launch — the one moment Apple and Play both say not to ask in.
        val installed = installDateMillis ?: return false
        if (now - installed < MINIMUM_INSTALL_AGE_MILLIS) return false
        lastPromptMillis?.let { if (now - it < MINIMUM_INTERVAL_MILLIS) return false }
        if (promptsInLastYear(now) >= MAXIMUM_PROMPTS_PER_YEAR) return false
        return true
    }

    /**
     * Ask, if and only if every gate passes. Returns whether the caller should launch the
     * store's review flow.
     *
     * Safe to call at either allowed moment: it self-limits and is a no-op otherwise. The
     * request is recorded when it is *made*, not when a prompt appears, because the app
     * cannot find out whether one appeared — and a gate that only advanced on a confirmed
     * prompt would ask again at every eligible moment forever.
     */
    fun requestReviewIfAppropriate(trigger: Trigger): Boolean {
        if (!isEligible()) return false
        val now = clock.nowMillis()
        lastPromptMillis = now
        recentPrompts = recentPrompts.filter { now - it < ONE_YEAR_MILLIS } + now
        return true
    }

    // region Persistence
    //
    // Timestamps go through the store's Double accessor: it has no Long, and epoch millis
    // (~1.7e12) is far inside the 2^53 a Double represents exactly, so nothing is lost.

    private var installDateMillis: Long?
        get() = store.getDouble(KEY_INSTALL_DATE)?.toLong()
        set(value) = store.putDouble(KEY_INSTALL_DATE, value?.toDouble())

    /** Completed flights so far. Readable for the Diagnostics row. */
    var completedFlights: Int
        get() = store.getInt(KEY_COMPLETED_FLIGHTS) ?: 0
        private set(value) = store.putInt(KEY_COMPLETED_FLIGHTS, value)

    private var lastPromptMillis: Long?
        get() = store.getDouble(KEY_LAST_PROMPT)?.toLong()
        set(value) = store.putDouble(KEY_LAST_PROMPT, value?.toDouble())

    /**
     * When each recent prompt was asked for.
     *
     * A list rather than a count, because the cap is a *rolling* year. A count would have to
     * reset on some fixed date, and a pilot who happened to fly either side of it would be
     * asked twice as often as the rule allows.
     */
    private var recentPrompts: List<Long>
        get() = store.getString(KEY_RECENT_PROMPTS)
            ?.split(",")
            ?.mapNotNull(String::toLongOrNull)
            .orEmpty()
        set(value) = store.putString(KEY_RECENT_PROMPTS, value.joinToString(","))

    private fun promptsInLastYear(now: Long): Int =
        recentPrompts.count { now - it < ONE_YEAR_MILLIS }

    // endregion

    companion object {
        /**
         * Completed flights before the app ever asks. Keeps brand-new installs and
         * first-session pilots out of the prompt entirely — someone who has not finished a
         * flight has nothing to rate.
         */
        const val MINIMUM_COMPLETED_FLIGHTS = 3

        /**
         * How old the install must be before the first ask. Defensive: a fresh install also
         * has zero completed flights, but a restore from backup can arrive with a count.
         */
        const val MINIMUM_INSTALL_AGE_MILLIS = 3L * 86_400_000

        /** About 120 days between prompts. Well inside any store's limit, and not nagging. */
        const val MINIMUM_INTERVAL_MILLIS = 120L * 86_400_000

        /**
         * An app-side mirror of the strictest store cap, so a run of tightly spaced eligible
         * moments can never overshoot it even if the spacing rule is later relaxed.
         */
        const val MAXIMUM_PROMPTS_PER_YEAR = 3

        const val ONE_YEAR_MILLIS = 365L * 86_400_000

        // The same keys iOS uses, so the two platforms' vocabularies stay in step.
        private const val KEY_INSTALL_DATE = "review.installDate"
        private const val KEY_COMPLETED_FLIGHTS = "review.completedFlights"
        private const val KEY_LAST_PROMPT = "review.lastPromptDate"
        private const val KEY_RECENT_PROMPTS = "review.recentPromptDates"
    }
}
