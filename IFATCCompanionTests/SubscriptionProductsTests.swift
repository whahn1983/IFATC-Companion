import XCTest
@testable import IFATCCompanion

/// Locks in the identifiers, fallback pricing and subscription/one-time
/// classification of the Live Connected products so the StoreKit service, the
/// entitlement manager and the subscription screen never drift apart — and so
/// the lifetime purchase keeps flowing through the shared entitlement check.
final class SubscriptionProductsTests: XCTestCase {

    func testProductIdentifiers() {
        XCTAssertEqual(SubscriptionProduct.monthly.rawValue,
                       "com.h3consultingpartners.ifatccompanion.live.monthly")
        XCTAssertEqual(SubscriptionProduct.annual.rawValue,
                       "com.h3consultingpartners.ifatccompanion.live.annual")
        XCTAssertEqual(SubscriptionProduct.lifetime.rawValue,
                       "com.h3consultingpartners.ifatccompanion.live.lifetime")
    }

    func testAllProductIDsIncludesLifetimeInOrder() {
        // Order matters: `loadProducts` sorts by this list, and the UI renders
        // monthly, annual, then lifetime.
        XCTAssertEqual(SubscriptionProduct.allProductIDs, [
            SubscriptionProduct.monthly.rawValue,
            SubscriptionProduct.annual.rawValue,
            SubscriptionProduct.lifetime.rawValue,
        ])
    }

    func testLifetimeIsAOneTimePurchaseNotASubscription() {
        XCTAssertTrue(SubscriptionProduct.monthly.isSubscription)
        XCTAssertTrue(SubscriptionProduct.annual.isSubscription)
        XCTAssertFalse(SubscriptionProduct.lifetime.isSubscription,
                       "Lifetime is a non-consumable one-time purchase")
    }

    func testLifetimeFallbackCopyAndPrice() {
        XCTAssertEqual(SubscriptionProduct.lifetime.fallbackDisplayName, "Live Connected Lifetime")
        XCTAssertEqual(SubscriptionProduct.lifetime.fallbackPrice, "$79.99")
        XCTAssertEqual(SubscriptionProduct.lifetime.durationText, "One-time purchase")
    }
}
