import XCTest
@testable import IFATCCompanion

/// Verifies the telemetry-stall watchdog that keeps the live flight notification current:
/// its pure staleness decision, and that only real telemetry advances the clock it measures
/// against. The watchdog force-reconnects the Infinite Flight link when the feed silently
/// stalls (e.g. the screen locks), so the Lock Screen / Dynamic Island card keeps updating
/// instead of freezing on its last numbers.
@MainActor
final class LiveActivityStalenessTests: XCTestCase {

    /// A fixed base instant so the tests don't depend on the wall clock.
    private let base = Date(timeIntervalSince1970: 1_000_000)

    private func makeLiveModel() -> AppModel {
        let model = AppModel()
        model.settings.voiceEnabled = false
        model.settings.mockMode = false
        return model
    }

    // MARK: - The pure staleness decision

    /// Before the first fix arrives (`lastUsable == .distantPast`) the feed can't be judged
    /// stalled — there's nothing to reconnect to yet.
    func testNoStallBeforeFirstFix() {
        let model = makeLiveModel()
        model.setTelemetryClocksForTesting(lastUsable: .distantPast)
        XCTAssertFalse(model.telemetryStallDetected(now: base.addingTimeInterval(3600)))
    }

    /// Fresh telemetry within the stall window is healthy — no reconnect.
    func testNoStallWithinWindow() {
        let model = makeLiveModel()
        model.setTelemetryClocksForTesting(lastUsable: base)
        XCTAssertFalse(model.telemetryStallDetected(now: base.addingTimeInterval(5)),
                       "5 s since the last fix is well inside the stall window")
    }

    /// A long gap since the last usable snapshot, with no recent reconnect, is a stall.
    func testStallAfterThreshold() {
        let model = makeLiveModel()
        model.setTelemetryClocksForTesting(lastUsable: base, lastForcedReconnect: .distantPast)
        XCTAssertTrue(model.telemetryStallDetected(now: base.addingTimeInterval(15)),
                      "15 s with no fresh telemetry is a stall")
    }

    /// A reconnect that just fired is given time to re-establish the feed before the watchdog
    /// considers reconnecting again, so it never reconnect-storms.
    func testCooldownSuppressesRepeatReconnect() {
        let model = makeLiveModel()
        model.setTelemetryClocksForTesting(lastUsable: base,
                                           lastForcedReconnect: base.addingTimeInterval(14))
        XCTAssertFalse(model.telemetryStallDetected(now: base.addingTimeInterval(15)),
                       "a reconnect 1 s ago is still inside the cooldown")
    }

    /// The mock feed never stalls, so the watchdog stays out of Mock Mode entirely.
    func testNoStallInMockMode() {
        let model = makeLiveModel()
        model.settings.mockMode = true
        model.setTelemetryClocksForTesting(lastUsable: base)
        XCTAssertFalse(model.telemetryStallDetected(now: base.addingTimeInterval(3600)))
    }

    // MARK: - Retrying a link that already failed

    /// When a forced reconnect can't re-establish (common while the screen is locked) the link
    /// lands in `.failed`. The watchdog must keep retrying that on the cooldown rather than
    /// giving up until the user foregrounds the app — otherwise the notification sticks on
    /// "Reconnecting…". The retry is gated purely on the reconnect cooldown.
    func testFailedLinkRetriesOnlyAfterCooldown() {
        let model = makeLiveModel()
        model.setTelemetryClocksForTesting(lastUsable: base,
                                           lastForcedReconnect: base)
        XCTAssertFalse(model.reconnectCooldownElapsed(now: base.addingTimeInterval(5)),
                       "a reconnect 5 s ago is still inside the cooldown — don't retry yet")
        XCTAssertTrue(model.reconnectCooldownElapsed(now: base.addingTimeInterval(30)),
                      "once the cooldown has elapsed the failed link is retried")
    }

    // MARK: - Live Activity heartbeat

    /// While routine pushes are flowing (a push landed a few seconds ago) the heartbeat stays
    /// dormant, so it spends no extra background-push budget.
    func testHeartbeatDormantWhilePushesFlow() {
        let model = makeLiveModel()
        XCTAssertFalse(model.liveActivityHeartbeatDue(now: base.addingTimeInterval(5), lastPush: base),
                       "a push 5 s ago is recent — no heartbeat needed")
    }

    /// Once pushes have gone quiet past the silence window (the poll stalled, or iOS throttled
    /// the background pushes) the heartbeat is due, so a fresh update lands before the card
    /// reaches its stale window and sticks on "Reconnecting…".
    func testHeartbeatDueAfterSilence() {
        let model = makeLiveModel()
        XCTAssertTrue(model.liveActivityHeartbeatDue(now: base.addingTimeInterval(20), lastPush: base),
                      "20 s of push silence warrants a heartbeat")
    }

    /// The heartbeat must fire with comfortable margin before the notification's ~60 s stale
    /// window, so a starving card is always refreshed while the app is alive.
    func testHeartbeatSilenceComfortablyUnderStaleWindow() {
        let model = makeLiveModel()
        XCTAssertFalse(model.liveActivityHeartbeatDue(now: base.addingTimeInterval(50), lastPush: base.addingTimeInterval(45)),
                       "a push 5 s ago is fresh regardless of absolute time")
        XCTAssertTrue(model.liveActivityHeartbeatDue(now: base.addingTimeInterval(45), lastPush: base),
                      "45 s of silence is well past the heartbeat threshold and under the 60 s stale window")
    }

    // MARK: - Only real telemetry advances the clock

    /// A usable snapshot advances the last-usable-telemetry clock; an empty reconnect-handshake
    /// snapshot (all-nil) must not — otherwise a stalled link that keeps returning empties would
    /// look forever "fresh" and never recover.
    func testUsableTelemetryAdvancesClockButEmptyDoesNot() {
        let model = makeLiveModel()
        XCTAssertEqual(model.lastUsableTelemetryAtForTesting, .distantPast,
                       "no telemetry has arrived yet")

        model.ingestStateForTesting(.empty)
        XCTAssertEqual(model.lastUsableTelemetryAtForTesting, .distantPast,
                       "an empty handshake snapshot is not usable telemetry")

        let real = model.mock.state(for: .preflight)   // on ground, valid position
        model.ingestStateForTesting(real)
        XCTAssertGreaterThan(model.lastUsableTelemetryAtForTesting, .distantPast,
                             "a real snapshot advances the stall clock")
    }
}
