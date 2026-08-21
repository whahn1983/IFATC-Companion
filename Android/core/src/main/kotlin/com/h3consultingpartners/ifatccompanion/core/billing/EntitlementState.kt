package com.h3consultingpartners.ifatccompanion.core.billing

/**
 * The entitlement state the whole app reads to decide whether Live Connected Mode is
 * available. Ported from `IFATCCompanion/Subscription/EntitlementManager.swift`.
 */
data class EntitlementState(
    /** Whether the user currently has access to Live Connected Mode. */
    val hasLiveAccess: Boolean = false,
    /** True while products are loading or entitlements are being refreshed. */
    val isLoading: Boolean = false,
    /** The loaded Live Connected products, ordered monthly, annual, then lifetime. */
    val products: List<LiveProductOffer> = emptyList(),
    /** Set when products cannot be loaded, for a clean error message in the UI. */
    val productLoadError: String? = null,
    /** Drives the subscription screen's purchasing / purchased / error states. */
    val purchasePhase: PurchasePhase = PurchasePhase.Idle,
    /** True when at least one purchase is awaiting payment. */
    val hasPendingPurchase: Boolean = false,
    /** True when the entitlement shown came from the offline cache, not a live query. */
    val isFromCache: Boolean = false,
) {
    /** Short status line: "Live Connected Mode Active" or "Mock Mode Only". */
    val statusText: String
        get() = if (hasLiveAccess) LIVE_ACTIVE_STATUS else MOCK_ONLY_STATUS

    companion object {
        const val LIVE_ACTIVE_STATUS = "Live Connected Mode Active"
        const val MOCK_ONLY_STATUS = "Mock Mode Only"

        /** User-facing message shown when products fail to load. */
        const val PRODUCTS_UNAVAILABLE_MESSAGE =
            "Subscriptions are unavailable right now. Please try again later."

        /** User-facing message shown when a purchase fails. */
        const val PURCHASE_FAILED_MESSAGE =
            "Purchase could not be completed. Please try again."

        /** User-facing message shown when restoring fails. */
        const val RESTORE_FAILED_MESSAGE =
            "Restore could not be completed. Please try again."

        /**
         * Shown while Play holds a purchase awaiting payment. Play, unlike StoreKit,
         * routinely produces this state for cash and delayed payment forms, so the
         * Android UI names it explicitly rather than silently staying in Mock Mode.
         */
        const val PURCHASE_PENDING_MESSAGE =
            "Your purchase is pending. Live Connected Mode unlocks as soon as Google Play " +
                "completes the payment."

        /** Shown when the device cannot reach Play Billing at all. */
        const val BILLING_UNAVAILABLE_MESSAGE =
            "Google Play Billing is unavailable on this device. Mock Mode is still free to use."
    }
}

/** Outcome of the most recent purchase attempt, for the subscription UI. */
sealed interface PurchasePhase {
    data object Idle : PurchasePhase
    data object Purchasing : PurchasePhase
    data object Purchased : PurchasePhase
    data object Cancelled : PurchasePhase
    data object Pending : PurchasePhase
    data class Failed(val message: String) : PurchasePhase
}
