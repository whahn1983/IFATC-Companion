import Foundation
import StoreKit

/// Owns the app's subscription entitlement state and drives the StoreKit
/// purchase / restore flow for Live Connected Mode.
///
/// `hasLiveAccess` is the single source of truth the rest of the app reads to
/// decide whether Live Connected Mode is available. It is derived only from
/// StoreKit's signed entitlements (`Transaction.currentEntitlements`) and kept
/// fresh by listening to `Transaction.updates`.
@MainActor
final class EntitlementManager: ObservableObject {

    /// Outcome of the most recent purchase attempt, for the subscription UI.
    enum PurchasePhase: Equatable {
        case idle
        case purchasing
        case purchased
        case cancelled
        case failed(String)
    }

    // MARK: - Published state

    /// Whether the user currently has access to Live Connected Mode.
    @Published private(set) var hasLiveAccess = false
    /// True while products are loading or entitlements are being refreshed.
    @Published private(set) var isLoading = false
    /// Short status line: "Live Connected Mode Active" or "Mock Mode Only".
    @Published private(set) var statusText = "Mock Mode Only"
    /// The loaded subscription products, monthly first.
    @Published private(set) var products: [Product] = []
    /// Set when products cannot be loaded, for a clean error message in the UI.
    @Published private(set) var productLoadError: String?
    /// Drives the subscription screen's purchasing / purchased / error states.
    @Published var purchasePhase: PurchasePhase = .idle

    /// User-facing message shown when products fail to load.
    static let productsUnavailableMessage = "Subscriptions are unavailable right now. Please try again later."

    private let store: StoreKitService
    private var updatesTask: Task<Void, Never>?

    #if DEBUG
    /// DEBUG-only switch to force entitlement for local development without a
    /// StoreKit configuration. Defaults to `false` so DEBUG behaves like
    /// production unless a developer deliberately flips it. Never compiled into
    /// Release builds, so there is no hidden production bypass.
    static var debugForceEntitlement = false
    #endif

    init(store: StoreKitService = StoreKitService()) {
        self.store = store
    }

    deinit { updatesTask?.cancel() }

    // MARK: - Convenience accessors

    /// The monthly product, if loaded.
    var monthlyProduct: Product? {
        products.first { $0.id == SubscriptionProduct.monthly.rawValue }
    }

    /// The annual product, if loaded.
    var annualProduct: Product? {
        products.first { $0.id == SubscriptionProduct.annual.rawValue }
    }

    // MARK: - Lifecycle

    /// Begin listening for StoreKit transaction updates (renewals, purchases on
    /// other devices, revocations) and refresh entitlement state for each one.
    func startListeningForTransactions() async {
        updatesTask?.cancel()
        updatesTask = Task { [weak self] in
            for await update in Transaction.updates {
                guard let self else { return }
                if case .verified(let transaction) = update {
                    await transaction.finish()
                }
                await self.refresh()
            }
        }
    }

    /// Load products and re-evaluate whether Live Connected access is entitled.
    func refresh() async {
        isLoading = true
        defer { isLoading = false }

        do {
            let loaded = try await store.loadProducts()
            products = loaded
            productLoadError = loaded.isEmpty ? Self.productsUnavailableMessage : nil
        } catch {
            // Keep any previously loaded products; surface a clean message.
            if products.isEmpty { productLoadError = Self.productsUnavailableMessage }
        }

        await refreshEntitlement()
    }

    /// Re-evaluate `hasLiveAccess` from StoreKit's current entitlements only.
    func refreshEntitlement() async {
        let active = await store.hasActiveLiveSubscription()
        #if DEBUG
        let entitled = active || Self.debugForceEntitlement
        #else
        let entitled = active
        #endif
        hasLiveAccess = entitled
        statusText = entitled ? "Live Connected Mode Active" : "Mock Mode Only"
    }

    // MARK: - Purchase / restore

    /// Purchase a subscription product. On success Live Connected Mode unlocks
    /// immediately; on cancel or failure the app stays in Mock Mode.
    func purchase(_ product: Product) async {
        purchasePhase = .purchasing
        do {
            let success = try await store.purchase(product)
            await refreshEntitlement()
            if success {
                purchasePhase = .purchased
            } else {
                // User cancelled or the purchase is pending — remain in Mock Mode.
                purchasePhase = .cancelled
            }
        } catch {
            purchasePhase = .failed("Purchase could not be completed. Please try again.")
        }
    }

    /// Restore purchases by syncing with the App Store, then re-checking
    /// entitlements. Use after reinstalling or on a new device.
    func restorePurchases() async {
        purchasePhase = .purchasing
        do {
            try await AppStore.sync()
            await refreshEntitlement()
            purchasePhase = hasLiveAccess ? .purchased : .idle
        } catch {
            purchasePhase = .failed("Restore could not be completed. Please try again.")
        }
    }

    /// Reset any transient purchase status back to idle (e.g. when the sheet is
    /// dismissed and reopened).
    func resetPurchasePhase() { purchasePhase = .idle }
}
