package com.h3consultingpartners.ifatccompanion.review

import android.app.Activity
import com.google.android.play.core.review.ReviewManagerFactory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticLevel
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink
import com.h3consultingpartners.ifatccompanion.core.review.ReviewRequestManager

/**
 * Presents Play's in-app review flow when [ReviewRequestManager] says the moment is right.
 *
 * The split is the one iOS makes: `:core` decides *whether* to ask, and this performs the
 * ask, because performing it needs an Activity and `:core` has no Android in it.
 *
 * **Nothing here can be verified by tapping through it.** Play throttles the prompt by rules
 * it does not publish, reports no difference between "shown" and "silently suppressed", and
 * shows nothing at all on a debug build or an unpublished track. A silent no-op is therefore
 * the expected outcome of a device test — and is indistinguishable from the call never being
 * made, which is exactly the failure shape this project has been bitten by before. So every
 * decision is written to Diagnostics: why it was skipped, that it was requested, and any
 * error. That log is the only evidence the wiring works at all.
 *
 * The host Activity comes from the graph's existing weak reference rather than a second one
 * of this class's own. A review flow is launched from a screen the pilot may leave a second
 * later, and two independent notions of "the current Activity" is one more than can be kept
 * in step.
 */
class PlayReviewLauncher(
    private val decide: ReviewRequestManager,
    private val diagnostics: DiagnosticsSink = DiagnosticsSink.noop,
    private val activity: () -> Activity?,
) {

    /**
     * Ask, if the moment qualifies.
     *
     * Returns nothing on purpose: the caller cannot act on the answer, because Play does not
     * report one.
     *
     * Deliberately the callback API rather than a coroutine wrapper. The flow outlives the
     * call — Play shows its own sheet over the Activity and dismisses it itself — so there is
     * nothing meaningful to await, and awaiting it would only tie the request to a
     * coroutine scope that may be cancelled while the pilot is looking at the sheet.
     */
    fun requestIfAppropriate(trigger: ReviewRequestManager.Trigger) {
        if (!decide.requestReviewIfAppropriate(trigger)) return
        val host = activity()
        if (host == null) {
            // The gate has already been spent — it is recorded when the request is made,
            // because there is no way to learn whether a prompt appeared. Worth a line so a
            // pilot who never sees one has somewhere to look.
            diagnostics.log(
                category = DiagnosticCategory.GENERAL,
                level = DiagnosticLevel.WARNING,
                message = "Review prompt was due at $trigger but no screen was in front to show it",
            )
            return
        }
        runCatching {
            val manager = ReviewManagerFactory.create(host)
            manager.requestReviewFlow().addOnCompleteListener { request ->
                if (!request.isSuccessful) {
                    diagnostics.log(
                        category = DiagnosticCategory.GENERAL,
                        level = DiagnosticLevel.WARNING,
                        message = "Play would not provide a review flow: " +
                            (request.exception?.message ?: "no reason given"),
                    )
                    return@addOnCompleteListener
                }
                manager.launchReviewFlow(host, request.result).addOnCompleteListener {
                    // "Requested", never "shown": Play returns the same success whether it
                    // presented the sheet or decided silently not to.
                    diagnostics.log(
                        category = DiagnosticCategory.GENERAL,
                        message = "Requested the Play review flow at $trigger " +
                            "(${decide.completedFlights} completed flights)",
                    )
                }
            }
        }.onFailure { error ->
            diagnostics.log(
                category = DiagnosticCategory.GENERAL,
                level = DiagnosticLevel.WARNING,
                message = "Play review flow failed: ${error.message ?: error::class.simpleName}",
            )
        }
    }
}
