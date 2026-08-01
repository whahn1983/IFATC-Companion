import Foundation

/// The StoreKit products that unlock Live Connected Mode.
///
/// Product IDs live here, in one place, so the StoreKit service, the
/// entitlement manager and the subscription screen all agree on the exact
/// identifiers configured in App Store Connect.
///
/// `monthly` and `annual` are auto-renewing subscriptions; `lifetime` is a
/// one-time, non-consumable purchase that unlocks the same Live features
/// forever. All three surface in `Transaction.currentEntitlements`, so a single
/// entitlement check covers every path to Live access.
enum SubscriptionProduct: String, CaseIterable, Identifiable {
    case monthly  = "com.h3consultingpartners.ifatccompanion.live.monthly"
    case annual   = "com.h3consultingpartners.ifatccompanion.live.annual"
    case lifetime = "com.h3consultingpartners.ifatccompanion.live.lifetime"

    var id: String { rawValue }

    /// Fallback display name used only when the StoreKit `Product` fails to load.
    var fallbackDisplayName: String {
        switch self {
        case .monthly:  return "Live Connected Monthly"
        case .annual:   return "Live Connected Annual"
        case .lifetime: return "Live Connected Lifetime"
        }
    }

    /// Fallback price string used only when StoreKit products are unavailable.
    /// StoreKit's localized `displayPrice` is always preferred when present.
    var fallbackPrice: String {
        switch self {
        case .monthly:  return "$2.99/month"
        case .annual:   return "$24.99/year"
        case .lifetime: return "$79.99"
        }
    }

    /// Human-readable purchase term shown beneath the name.
    var durationText: String {
        switch self {
        case .monthly:  return "Monthly subscription"
        case .annual:   return "Annual subscription"
        case .lifetime: return "One-time purchase"
        }
    }

    /// Whether this product is an auto-renewing subscription. `lifetime` is a
    /// one-time non-consumable purchase, so it returns `false`; the UI uses this
    /// to pick "Subscribe" vs "Buy" wording and to scope the renewal disclosure.
    var isSubscription: Bool {
        switch self {
        case .monthly, .annual: return true
        case .lifetime:         return false
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
