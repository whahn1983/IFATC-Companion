package com.h3consultingpartners.ifatccompanion.core.ui

/**
 * The SimBrief copy, verbatim from the iOS Flight view.
 *
 * Lives beside [LegalStrings] rather than in the :app module because it is ported prose
 * — the same reasoning that keeps the disclaimers here, and it lets the Flight screen
 * type-check without the Android SDK.
 */
object SimBriefStrings {
    const val BUTTON_TITLE = "Create Flight Plan (SimBrief)"

    const val HELPER_TEXT =
        "Create and load your SimBrief flight plan in Infinite Flight, then return here " +
            "to refresh."

    /**
     * IFATC Companion does not scrape SimBrief, does not alter what the site shows, and
     * claims no affiliation. Stated in the UI as well as in the docs.
     */
    const val NOT_AFFILIATED =
        "SimBrief is a Navigraph service. IFATC Companion is not affiliated with SimBrief " +
            "or Navigraph."
}
