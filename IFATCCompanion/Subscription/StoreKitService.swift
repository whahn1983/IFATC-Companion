import Foundation
import StoreKit

/// Thin wrapper over StoreKit 2 for the Live Connected Mode subscription.
///
/// Loads the products, runs a purchase, and reports whether an active
/// subscription is currently entitled. All access decisions are made from
/// StoreKit's signed `Transaction` data — there is no backend and no local
/// override in production builds.
struct StoreKitService {

    /// The two products that unlock Live Connected Mode.
    let productIDs: [String]

    /// Monthly product identifier, for convenience at call sites.
    var monthly: String { SubscriptionProduct.monthly.rawValue }
    /// Annual product identifier, for convenience at call sites.
    var annual: String { SubscriptionProduct.annual.rawValue }

    init(productIDs: [String] = SubscriptionProduct.allProductIDs) {
        self.productIDs = productIDs
    }

    /// Errors surfaced to the entitlement layer.
    enum StoreError: Error {
        /// A purchase completed but its transaction could not be verified.
        case failedVerification
    }

    // MARK: - Products

    /// Fetch the subscription products from the App Store. Sorted so the monthly
    /// option appears before the annual option.
    func loadProducts() async throws -> [Product] {
        let products = try await Product.products(for: productIDs)
        return products.sorted { lhs, rhs in
            (productIDs.firstIndex(of: lhs.id) ?? .max) < (productIDs.firstIndex(of: rhs.id) ?? .max)
        }
    }

    // MARK: - Purchase

    /// Attempt to purchase a product. Returns `true` when the purchase succeeds
    /// and the transaction verifies (and is finished); `false` when the user
    /// cancels or the request is pending. Throws on verification failure.
    func purchase(_ product: Product) async throws -> Bool {
        let result = try await product.purchase()
        switch result {
        case .success(let verification):
            let transaction = try checkVerified(verification)
            await transaction.finish()
            return true
        case .userCancelled, .pending:
            return false
        @unknown default:
            return false
        }
    }

    // MARK: - Entitlement

    /// Whether StoreKit's current entitlements include an active Live Connected
    /// subscription (monthly or annual). Revoked or expired transactions are not
    /// reported by `currentEntitlements`, so their absence locks the app to Mock
    /// Mode automatically.
    func hasActiveLiveSubscription() async -> Bool {
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result else { continue }
            if productIDs.contains(transaction.productID),
               transaction.revocationDate == nil {
                return true
            }
        }
        return false
    }

    // MARK: - Verification

    /// Unwrap a StoreKit `VerificationResult`, throwing when the payload is not
    /// signed by the App Store.
    func checkVerified<T>(_ result: VerificationResult<T>) throws -> T {
        switch result {
        case .unverified:
            throw StoreError.failedVerification
        case .verified(let safe):
            return safe
        }
    }
}
