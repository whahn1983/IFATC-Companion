import Foundation

/// The StoreKit subscription products that unlock Live Connected Mode.
///
/// Product IDs live here, in one place, so the StoreKit service, the
/// entitlement manager and the subscription screen all agree on the exact
/// identifiers configured in App Store Connect.
enum SubscriptionProduct: String, CaseIterable, Identifiable {
    case monthly = "com.h3consultingpartners.ifatccompanion.live.monthly"
    case annual  = "com.h3consultingpartners.ifatccompanion.live.annual"

    var id: String { rawValue }

    /// Fallback display name used only when the StoreKit `Product` fails to load.
    var fallbackDisplayName: String {
        switch self {
        case .monthly: return "Live Connected Monthly"
        case .annual:  return "Live Connected Annual"
        }
    }

    /// Fallback price string used only when StoreKit products are unavailable.
    /// StoreKit's localized `displayPrice` is always preferred when present.
    var fallbackPrice: String {
        switch self {
        case .monthly: return "$2.99/month"
        case .annual:  return "$24.99/year"
        }
    }

    /// Human-readable subscription duration shown beneath the name.
    var durationText: String {
        switch self {
        case .monthly: return "Monthly subscription"
        case .annual:  return "Annual subscription"
        }
    }

    /// All product IDs as plain strings, for `Product.products(for:)` and for
    /// scanning `Transaction.currentEntitlements`.
    static var allProductIDs: [String] { allCases.map(\.rawValue) }
}

/// Static, easy-to-change links surfaced in the subscription screen.
enum SubscriptionLinks {
    /// Apple's standard EULA, required as the Terms of Use for auto-renewable
    /// subscriptions when the app does not provide its own.
    static let termsOfUse = URL(string: "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/")!

    /// Privacy policy. Kept as a single constant so it is trivial to change.
    static let privacyPolicy = URL(string: "https://whahn1983.github.io/IFATC-Companion/privacy-policy.html")!
}
