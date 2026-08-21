package com.h3consultingpartners.ifatccompanion.core.ui

import com.h3consultingpartners.ifatccompanion.core.billing.SubscriptionProduct

/**
 * The subscription screen's copy.
 *
 * Most of it is verbatim from the iOS subscription view. The **renewal disclosure is
 * not**, and could not be: iOS discloses that payment is charged to the customer's Apple
 * Account and managed in Apple Account settings, which is false on Android and would be a
 * policy violation to ship. It is rewritten for Google Play, saying the same things Play
 * requires — the price, the period, that it renews until cancelled, and where to cancel.
 * Likewise the Terms link goes to Google Play's terms, not Apple's standard EULA.
 *
 * Everything else — the header, the status lines, the badges, the button wording — is
 * carried across unchanged.
 */
object SubscriptionStrings {

    const val TITLE = "Subscription"

    const val HEADER = "Unlock Live Connected Mode"

    /**
     * iOS says "on iPhone while flying Infinite Flight on iPad". The shape of the idea is
     * the point — this app on one device, the simulator on another — so Android names
     * devices it actually has.
     */
    const val HEADER_SUBTITLE =
        "Use IFATC Companion on your phone while flying Infinite Flight on your tablet."

    const val HEADER_REQUIREMENT = LegalStrings.LIVE_CONNECTED_REQUIREMENT

    const val CURRENT_STATUS = "Current Status"

    const val RESTORE = "Restore Purchases"
    const val MANAGE = "Manage Subscription"
    const val DONE = "Done"

    const val PURCHASED_MESSAGE = "Live Connected Mode unlocked. Thank you!"
    const val CANCELLED_MESSAGE = "Purchase cancelled — Mock Mode is still active."

    /** Badges shown beside a plan's name. */
    const val BADGE_BEST_VALUE = "Best value"
    const val BADGE_PAY_ONCE = "Pay once"

    fun badge(product: SubscriptionProduct): String? = when (product) {
        SubscriptionProduct.ANNUAL -> BADGE_BEST_VALUE
        SubscriptionProduct.LIFETIME -> BADGE_PAY_ONCE
        SubscriptionProduct.MONTHLY -> null
    }

    /**
     * The call-to-action for a plan's button. "Subscribe" for the auto-renewing plans and
     * "Buy" for the one-time purchase, reflecting the owned state once Live access is
     * unlocked.
     */
    fun purchaseButtonTitle(
        product: SubscriptionProduct,
        price: String,
        hasLiveAccess: Boolean,
    ): String {
        if (hasLiveAccess) {
            return if (product.isSubscription) "Subscribed" else "Purchased"
        }
        return if (product.isSubscription) "Subscribe — $price" else "Buy — $price"
    }

    /**
     * The renewal disclosure, written for Google Play. This is the one piece of
     * subscription copy that is deliberately **not** a verbatim port: the iOS text names
     * the Apple Account and Apple's settings, which would be wrong here.
     */
    val disclosures: List<String> = listOf(
        "Monthly and Annual are auto-renewing subscriptions billed through Google Play. " +
            "Payment is charged to your Google Play account at confirmation of purchase. " +
            "A subscription renews automatically at the price and period shown until you " +
            "cancel it. You can manage or cancel a subscription any time in Google Play " +
            "under Payments & subscriptions; cancelling stops the next renewal and leaves " +
            "access in place until the current period ends.",
        "Lifetime is a one-time purchase that unlocks Live Connected Mode permanently — " +
            "it does not auto-renew.",
        "Mock Mode is free and does not require a purchase.",
    )

    const val TERMS_LINK_TITLE = "Google Play Terms"
    const val PRIVACY_LINK_TITLE = "Privacy Policy"
}
