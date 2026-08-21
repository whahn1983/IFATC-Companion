package com.h3consultingpartners.ifatccompanion.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.h3consultingpartners.ifatccompanion.core.billing.EntitlementState
import com.h3consultingpartners.ifatccompanion.core.billing.LiveAccessRules
import com.h3consultingpartners.ifatccompanion.core.billing.LiveProductOffer
import com.h3consultingpartners.ifatccompanion.core.billing.LivePurchase
import com.h3consultingpartners.ifatccompanion.core.billing.PurchasePhase
import com.h3consultingpartners.ifatccompanion.core.billing.SubscriptionProduct
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticLevel
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink
import com.h3consultingpartners.ifatccompanion.core.platform.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the app's entitlement state and drives the Google Play purchase / restore flow
 * for Live Connected Mode — the Android counterpart of
 * `IFATCCompanion/Subscription/EntitlementManager.swift` and `StoreKitService.swift`.
 *
 * `hasLiveAccess` is the single source of truth the rest of the app reads to decide
 * whether Live Connected Mode is available. It is derived from Play's own purchase
 * records and kept fresh by the purchases-updated listener, which is the closest
 * equivalent to StoreKit's `Transaction.updates`.
 *
 * **Entitlement rule, identical to iOS:** Live Access is granted when the customer has
 * an active Monthly subscription, OR an active Annual subscription, OR the Lifetime
 * one-time entitlement. Play keeps an auto-renewing subscription in `queryPurchasesAsync`
 * for as long as it is entitled — through the cancelled-but-not-yet-expired window and
 * through the grace period — so no expiry arithmetic is needed, exactly as
 * `Transaction.currentEntitlements` needs none.
 *
 * **Restore** is not a separate Play concept the way `AppStore.sync()` is: re-querying
 * purchases *is* the restore, and it also runs on every launch. The UI still offers the
 * button, because a customer who has just reinstalled looks for one.
 */
class PlayBillingRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val cache: KeyValueStore,
    private val diagnostics: DiagnosticsSink = DiagnosticsSink.noop,
) {

    private val _state = MutableStateFlow(EntitlementState(hasLiveAccess = cachedEntitlement()))
    val state: StateFlow<EntitlementState> = _state.asStateFlow()

    private val productDetails = mutableMapOf<String, ProductDetails>()
    private val connectionLock = Mutex()

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        scope.launch {
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    handlePurchases(purchases.orEmpty())
                    val pending = purchases.orEmpty().any {
                        it.purchaseState == Purchase.PurchaseState.PENDING
                    }
                    setPhase(if (pending) PurchasePhase.Pending else PurchasePhase.Purchased)
                }

                BillingClient.BillingResponseCode.USER_CANCELED ->
                    setPhase(PurchasePhase.Cancelled)

                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                    // Already entitled on this account — re-query rather than error out.
                    refreshPurchases()
                    setPhase(PurchasePhase.Purchased)
                }

                else -> {
                    log(DiagnosticLevel.WARNING, "Purchase failed: ${describe(result)}")
                    setPhase(PurchasePhase.Failed(EntitlementState.PURCHASE_FAILED_MESSAGE))
                }
            }
        }
    }

    private val billingClient: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            // The Lifetime purchase is a one-time product, and Play can hold one pending
            // while a delayed payment form clears. Without this the client refuses to
            // start.
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .build()

    /** Connect to Play, load products and evaluate entitlement. Safe to call repeatedly. */
    fun start() {
        scope.launch { refresh() }
    }

    suspend fun refresh() {
        setLoading(true)
        try {
            if (!connect()) {
                _state.value = _state.value.copy(
                    productLoadError = EntitlementState.BILLING_UNAVAILABLE_MESSAGE,
                )
                // Fall back to the cached entitlement so a customer with no connectivity
                // is not thrown out of Live Connected Mode mid-flight.
                applyEntitlement(cachedEntitlement(), fromCache = true)
                return
            }
            loadProducts()
            refreshPurchases()
        } finally {
            setLoading(false)
        }
    }

    private suspend fun connect(): Boolean = connectionLock.withLock {
        if (billingClient.isReady) return@withLock true
        val connected = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            var resumed = false
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (resumed) return
                    resumed = true
                    if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                        log(DiagnosticLevel.WARNING, "Billing setup failed: ${describe(result)}")
                    }
                    continuation.resumeWith(
                        Result.success(result.responseCode == BillingClient.BillingResponseCode.OK),
                    )
                }

                override fun onBillingServiceDisconnected() {
                    if (resumed) return
                    resumed = true
                    log(DiagnosticLevel.WARNING, "Billing service disconnected during setup")
                    continuation.resumeWith(Result.success(false))
                }
            })
        }
        connected
    }

    private suspend fun loadProducts() {
        val offers = mutableListOf<LiveProductOffer>()

        val subscriptionDetails = queryProducts(
            SubscriptionProduct.subscriptionProductIds,
            BillingClient.ProductType.SUBS,
        )
        val oneTimeDetails = queryProducts(
            SubscriptionProduct.oneTimeProductIds,
            BillingClient.ProductType.INAPP,
        )

        productDetails.clear()
        (subscriptionDetails + oneTimeDetails).forEach { productDetails[it.productId] = it }

        // Ordered monthly, annual, then lifetime — the order the subscription screen shows
        // them in on both platforms.
        for (product in SubscriptionProduct.entries) {
            val details = productDetails[product.productId]
            offers += if (details == null) {
                LiveProductOffer(
                    product = product,
                    displayName = product.fallbackDisplayName,
                    displayPrice = product.fallbackPrice,
                    description = "",
                )
            } else {
                toOffer(product, details)
            }
        }

        _state.value = _state.value.copy(
            products = offers,
            productLoadError = if (productDetails.isEmpty()) {
                EntitlementState.PRODUCTS_UNAVAILABLE_MESSAGE
            } else {
                null
            },
        )
    }

    private suspend fun queryProducts(
        productIds: List<String>,
        type: String,
    ): List<ProductDetails> {
        if (productIds.isEmpty()) return emptyList()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIds.map { id ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(id)
                        .setProductType(type)
                        .build()
                },
            )
            .build()
        val result = billingClient.queryProductDetails(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            log(DiagnosticLevel.WARNING, "Product query failed: ${describe(result.billingResult)}")
            return emptyList()
        }
        return result.productDetailsList.orEmpty()
    }

    private fun toOffer(product: SubscriptionProduct, details: ProductDetails): LiveProductOffer {
        val description = details.description.ifEmpty { details.name }
        return if (product.isSubscription) {
            // Pick the offer for this product's configured base plan, then its first
            // pricing phase — that is the recurring price the screen shows.
            val offer = details.subscriptionOfferDetails
                ?.firstOrNull { it.basePlanId == product.basePlanId }
                ?: details.subscriptionOfferDetails?.firstOrNull()
            val price = offer?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
            LiveProductOffer(
                product = product,
                displayName = details.name.ifEmpty { product.fallbackDisplayName },
                displayPrice = price ?: product.fallbackPrice,
                description = description,
                offerToken = offer?.offerToken,
            )
        } else {
            LiveProductOffer(
                product = product,
                displayName = details.name.ifEmpty { product.fallbackDisplayName },
                displayPrice = details.oneTimePurchaseOfferDetails?.formattedPrice
                    ?: product.fallbackPrice,
                description = description,
            )
        }
    }

    /** Re-query Play's purchase records and re-evaluate entitlement. */
    suspend fun refreshPurchases() {
        if (!billingClient.isReady) return
        val subscriptions = queryPurchases(BillingClient.ProductType.SUBS)
        val oneTime = queryPurchases(BillingClient.ProductType.INAPP)
        handlePurchases(subscriptions + oneTime)
    }

    private suspend fun queryPurchases(type: String): List<Purchase> {
        val result = billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(type).build(),
        )
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            log(DiagnosticLevel.WARNING, "Purchase query failed: ${describe(result.billingResult)}")
            return emptyList()
        }
        return result.purchasesList
    }

    private suspend fun handlePurchases(purchases: List<Purchase>) {
        val mapped = purchases.map { it.toLivePurchase() }

        // Play automatically refunds and revokes anything left unacknowledged for three
        // days, so acknowledge as soon as it is seen — before entitlement is even applied.
        for (purchase in purchases) {
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            if (purchase.isAcknowledged) continue
            val result = billingClient.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build(),
            )
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                log(DiagnosticLevel.INFO, "Acknowledged purchase ${purchase.products}")
            } else {
                log(DiagnosticLevel.ERROR, "Acknowledge failed: ${describe(result)}")
            }
        }

        val entitled = LiveAccessRules.hasLiveAccess(mapped)
        _state.value = _state.value.copy(
            hasPendingPurchase = LiveAccessRules.pending(mapped).isNotEmpty(),
        )
        applyEntitlement(entitled, fromCache = false)
    }

    /** Launch the Play purchase flow for one product. */
    fun purchase(activity: Activity, product: SubscriptionProduct) {
        val details = productDetails[product.productId]
        if (details == null) {
            setPhase(PurchasePhase.Failed(EntitlementState.PRODUCTS_UNAVAILABLE_MESSAGE))
            return
        }
        setPhase(PurchasePhase.Purchasing)

        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .apply {
                if (product.isSubscription) {
                    val token = _state.value.products
                        .firstOrNull { it.product == product }
                        ?.offerToken
                    if (token != null) setOfferToken(token)
                }
            }
            .build()

        val result = billingClient.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productParams))
                .build(),
        )
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            log(DiagnosticLevel.WARNING, "Could not launch billing flow: ${describe(result)}")
            setPhase(PurchasePhase.Failed(EntitlementState.PURCHASE_FAILED_MESSAGE))
        }
    }

    /**
     * Re-query Play for purchases made on another device or before a reinstall. Play has
     * no `AppStore.sync()` — the query is the restore.
     */
    fun restorePurchases() {
        scope.launch {
            setPhase(PurchasePhase.Purchasing)
            if (!connect()) {
                setPhase(PurchasePhase.Failed(EntitlementState.RESTORE_FAILED_MESSAGE))
                return@launch
            }
            refreshPurchases()
            setPhase(
                if (_state.value.hasLiveAccess) PurchasePhase.Purchased else PurchasePhase.Idle,
            )
        }
    }

    /** Reset any transient purchase status back to idle (e.g. when the sheet reopens). */
    fun resetPurchasePhase() = setPhase(PurchasePhase.Idle)

    fun close() {
        runCatching { billingClient.endConnection() }
    }

    // region State plumbing

    private fun applyEntitlement(entitled: Boolean, fromCache: Boolean) {
        _state.value = _state.value.copy(hasLiveAccess = entitled, isFromCache = fromCache)
        if (!fromCache) cache.putBoolean(CACHE_KEY_HAS_LIVE_ACCESS, entitled)
    }

    private fun setLoading(loading: Boolean) {
        _state.value = _state.value.copy(isLoading = loading)
    }

    private fun setPhase(phase: PurchasePhase) {
        _state.value = _state.value.copy(purchasePhase = phase)
    }

    /**
     * The last entitlement Play confirmed, so a launch with no connectivity does not drop
     * a paying customer into Mock Mode. It is only ever a bridge: the first successful
     * query replaces it, and a revoked or expired entitlement clears it as soon as Play
     * can be reached.
     */
    private fun cachedEntitlement(): Boolean =
        cache.getBoolean(CACHE_KEY_HAS_LIVE_ACCESS) ?: false

    private fun log(level: DiagnosticLevel, message: String) =
        diagnostics.log(DiagnosticCategory.BILLING, level, message)

    private fun describe(result: BillingResult): String =
        "code=${result.responseCode} ${result.debugMessage}"

    private fun Purchase.toLivePurchase() = LivePurchase(
        productIds = products,
        purchaseToken = purchaseToken,
        state = when (purchaseState) {
            Purchase.PurchaseState.PURCHASED -> LivePurchase.PurchaseState.PURCHASED
            Purchase.PurchaseState.PENDING -> LivePurchase.PurchaseState.PENDING
            else -> LivePurchase.PurchaseState.UNSPECIFIED
        },
        isAcknowledged = isAcknowledged,
        isAutoRenewing = isAutoRenewing,
        purchaseTimeMillis = purchaseTime,
    )

    // endregion

    companion object {
        private const val CACHE_KEY_HAS_LIVE_ACCESS = "billing.hasLiveAccess"
    }
}
