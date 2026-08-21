package com.h3consultingpartners.ifatccompanion.core.billing

import com.h3consultingpartners.ifatccompanion.core.config.AppConfig

/**
 * The Google Play products that unlock Live Connected Mode.
 *
 * Product IDs live in [AppConfig.Billing], in one place, so the billing client, the
 * entitlement manager and the subscription screen all agree on the exact identifiers
 * configured in Play Console.
 *
 * [MONTHLY] and [ANNUAL] are auto-renewing subscriptions; [LIFETIME] is a one-time,
 * non-consumable in-app purchase that unlocks the same Live features forever. All
 * three surface in Play's purchase queries, so a single entitlement check covers
 * every path to Live access.
 *
 * Ported from `IFATCCompanion/Subscription/SubscriptionProducts.swift`.
 */
enum class SubscriptionProduct(val productId: String) {
    MONTHLY(AppConfig.Billing.MONTHLY_PRODUCT_ID),
    ANNUAL(AppConfig.Billing.ANNUAL_PRODUCT_ID),
    LIFETIME(AppConfig.Billing.LIFETIME_PRODUCT_ID),
    ;

    val id: String get() = productId

    /** Fallback display name used only when the Play product fails to load. */
    val fallbackDisplayName: String
        get() = when (this) {
            MONTHLY -> "Live Connected Monthly"
            ANNUAL -> "Live Connected Annual"
            LIFETIME -> "Live Connected Lifetime"
        }

    /**
     * Fallback price string used only when Play products are unavailable. Play's
     * localized formatted price is always preferred when present.
     */
    val fallbackPrice: String
        get() = when (this) {
            MONTHLY -> "$2.99/month"
            ANNUAL -> "$24.99/year"
            LIFETIME -> "$79.99"
        }

    /** Human-readable purchase term shown beneath the name. */
    val durationText: String
        get() = when (this) {
            MONTHLY -> "Monthly subscription"
            ANNUAL -> "Annual subscription"
            LIFETIME -> "One-time purchase"
        }

    /**
     * Whether this product is an auto-renewing subscription. [LIFETIME] is a one-time
     * purchase, so it returns false; the UI uses this to pick "Subscribe" vs "Buy"
     * wording and to scope the renewal disclosure.
     */
    val isSubscription: Boolean
        get() = when (this) {
            MONTHLY, ANNUAL -> true
            LIFETIME -> false
        }

    /**
     * The Play base-plan id to launch the billing flow with. Null for the one-time
     * purchase, which has no base plan.
     */
    val basePlanId: String?
        get() = when (this) {
            MONTHLY -> AppConfig.Billing.MONTHLY_BASE_PLAN_ID
            ANNUAL -> AppConfig.Billing.ANNUAL_BASE_PLAN_ID
            LIFETIME -> null
        }

    companion object {
        /** All product IDs as plain strings, for product queries and purchase scans. */
        val allProductIds: List<String> get() = entries.map { it.productId }

        val subscriptionProductIds: List<String>
            get() = entries.filter { it.isSubscription }.map { it.productId }

        val oneTimeProductIds: List<String>
            get() = entries.filterNot { it.isSubscription }.map { it.productId }

        fun fromProductId(id: String): SubscriptionProduct? =
            entries.firstOrNull { it.productId == id }
    }
}

/**
 * A Play product as the subscription screen needs it, decoupled from the Billing
 * library types so :core stays Android-free and the UI is testable.
 */
data class LiveProductOffer(
    val product: SubscriptionProduct,
    /** Play's localized name, or [SubscriptionProduct.fallbackDisplayName]. */
    val displayName: String,
    /** Play's localized formatted price, or [SubscriptionProduct.fallbackPrice]. */
    val displayPrice: String,
    val description: String,
    /** The Play offer token to launch the flow with; null for one-time products. */
    val offerToken: String? = null,
)

/**
 * A purchase as the entitlement rules need it, decoupled from the Billing library.
 *
 * Play's `Purchase` does not expose an expiry date to the client — an active
 * auto-renewing subscription simply keeps appearing in `queryPurchasesAsync` for as
 * long as it is entitled, including through the cancelled-but-not-yet-expired window
 * and through Play's grace period. That is exactly the entitlement semantics the iOS
 * `Transaction.currentEntitlements` scan has, so no expiry arithmetic is needed on
 * either platform.
 */
data class LivePurchase(
    val productIds: List<String>,
    val purchaseToken: String,
    val state: PurchaseState,
    val isAcknowledged: Boolean,
    val isAutoRenewing: Boolean,
    val purchaseTimeMillis: Long,
) {
    enum class PurchaseState { PURCHASED, PENDING, UNSPECIFIED }

    /** Only a PURCHASED purchase entitles. A PENDING one does not — yet. */
    val entitles: Boolean get() = state == PurchaseState.PURCHASED
}

/**
 * Whether Live Connected Mode is unlocked.
 *
 * Live Access is granted when the customer has an active Monthly subscription, OR an
 * active Annual subscription, OR the Lifetime one-time entitlement. Identical to the
 * iOS rule, which scans `Transaction.currentEntitlements` for any of the three.
 */
object LiveAccessRules {

    fun hasLiveAccess(purchases: List<LivePurchase>): Boolean =
        purchases.any { purchase ->
            purchase.entitles && purchase.productIds.any { it in SubscriptionProduct.allProductIds }
        }

    /**
     * Whether the customer holds the permanent Lifetime entitlement. Once true this
     * never becomes false through expiry — only through a Play refund/revocation,
     * which removes the purchase from the query result.
     */
    fun hasLifetime(purchases: List<LivePurchase>): Boolean =
        purchases.any { purchase ->
            purchase.entitles &&
                purchase.productIds.contains(AppConfig.Billing.LIFETIME_PRODUCT_ID)
        }

    /**
     * Purchases that Play has accepted but which the app has not acknowledged yet.
     * Play automatically refunds and revokes anything left unacknowledged for three
     * days, so these must be acknowledged as soon as they are seen.
     */
    fun needingAcknowledgement(purchases: List<LivePurchase>): List<LivePurchase> =
        purchases.filter { it.entitles && !it.isAcknowledged }

    /** Purchases still awaiting payment (e.g. cash / pending forms). */
    fun pending(purchases: List<LivePurchase>): List<LivePurchase> =
        purchases.filter { it.state == LivePurchase.PurchaseState.PENDING }
}
