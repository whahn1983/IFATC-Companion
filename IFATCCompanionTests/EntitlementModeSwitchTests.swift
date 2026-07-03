import XCTest
@testable import IFATCCompanion

/// Verifies that a confirmed subscription actually flips the app out of the
/// startup Mock-Mode lock and into Live Connected Mode — and that losing access
/// forces Mock Mode back on.
///
/// Regression: the entitlement observer routed the "access gained" case through
/// `toggleMockMode(false)`, whose guard re-reads `entitlements.hasLiveAccess`.
/// Because `@Published` emits from `willSet`, that property still returned the
/// old `false` while the observer ran, so the guard refused the switch and left
/// the mock toggle stuck on — the user saw the subscription confirmed but had to
/// turn Mock Mode off by hand. `applyEntitlement(hasLiveAccess:)` now acts on the
/// value the publisher provides, so the switch sticks.
@MainActor
final class EntitlementModeSwitchTests: XCTestCase {

    /// An AppModel that won't touch the network when it enters Live Mode: no host
    /// and auto-discover off means `startLive()` just idles.
    private func makeModel() -> AppModel {
        let model = AppModel()
        model.settings.voiceEnabled = false
        model.settings.autoDiscover = false
        model.settings.host = ""
        return model
    }

    func testConfirmedSubscriptionSwitchesOutOfMockLock() {
        let model = makeModel()
        // Startup pins a would-be subscriber to Mock Mode until the async
        // entitlement check completes.
        model.settings.mockMode = true

        // The entitlement check confirms the active subscription.
        model.applyEntitlement(hasLiveAccess: true)

        XCTAssertFalse(model.settings.mockMode,
                       "confirming an active subscription must turn Mock Mode off and activate Live mode")
    }

    func testLostAccessLocksBackToMockMode() {
        let model = makeModel()
        model.settings.mockMode = false

        model.applyEntitlement(hasLiveAccess: false)

        XCTAssertTrue(model.settings.mockMode,
                      "losing Live access must force Mock Mode back on")
    }

    func testAlreadyLiveWithAccessIsLeftAlone() {
        let model = makeModel()
        model.settings.mockMode = false

        model.applyEntitlement(hasLiveAccess: true)

        XCTAssertFalse(model.settings.mockMode, "a live session with access must stay live")
    }

    func testMockWithoutAccessIsLeftAlone() {
        let model = makeModel()
        model.settings.mockMode = true

        model.applyEntitlement(hasLiveAccess: false)

        XCTAssertTrue(model.settings.mockMode, "a mock session without access must stay mock")
    }
}
