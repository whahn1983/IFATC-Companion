import Foundation
import ActivityKit

/// Starts, updates and ends the live flight notification (Lock Screen + Dynamic Island).
///
/// Updates are throttled so a 1 Hz telemetry loop doesn't hammer ActivityKit; a "force"
/// update (phase/controller changes, hand-offs) always goes through immediately.
///
/// The visual rendering lives in the widget extension (`IFATCCompanionWidgets`). Without
/// that target the app still compiles and runs — starting an activity simply has nothing
/// to draw — so the extension can be added independently (see docs/LiveActivitySetup.md).
@MainActor
final class LiveActivityController: ObservableObject {

    private var activity: Activity<CompanionActivityAttributes>?
    private var lastUpdate = Date.distantPast
    /// Minimum spacing between routine (non-forced) pushes. Kept deliberately conservative:
    /// ActivityKit budgets how often a *backgrounded* app may push `activity.update()`, and
    /// a 1 Hz poll pushing every 2 s blew through it — iOS then dropped the updates and the
    /// card starved to its stale state ("Reconnecting…") even while the app was alive. Pushing
    /// routine telemetry every few seconds stays inside the budget so updates keep landing;
    /// meaningful changes (phase, controller, hand-off) still `force` through immediately.
    private let minInterval: TimeInterval = 5.0

    /// When the last update was pushed to ActivityKit (start or update). The app's telemetry
    /// watchdog reads this to notice when routine pushes have gone quiet — a poll stall, or
    /// iOS throttling background pushes — and force a heartbeat before the card reaches the
    /// stale window.
    var lastPushAt: Date { lastUpdate }

    /// How long after the last push before iOS marks the activity stale. Once passed,
    /// `context.isStale` flips true and the widget shows a "Reconnecting…" state rather
    /// than presenting old telemetry as current. Kept comfortably longer than the app's
    /// telemetry-stall watchdog (~12 s) so a normal reconnect refreshes the card before
    /// it ever reads stale, while a genuine background suspension still surfaces within a
    /// minute.
    private let staleWindow: TimeInterval = 60

    var isActive: Bool { activity != nil }

    /// Start the activity (or update it if one is already running).
    func start(_ state: CompanionActivityAttributes.ContentState) {
        guard activity == nil else { update(state, force: true); return }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let attributes = CompanionActivityAttributes(flightTitle: "IFATC Companion")
        do {
            activity = try Activity.request(
                attributes: attributes,
                content: makeContent(for: state),
                pushType: nil)
            lastUpdate = Date()
        } catch {
            activity = nil
        }
    }

    func update(_ state: CompanionActivityAttributes.ContentState, force: Bool = false) {
        guard let activity else { return }
        let now = Date()
        if !force, now.timeIntervalSince(lastUpdate) < minInterval { return }
        lastUpdate = now
        let content = makeContent(for: state)
        Task { await activity.update(content) }
    }

    /// Wrap a `ContentState` with a fresh `staleDate` so the notification tells the user
    /// when its data has stopped refreshing. `asOf` is the moment of this push, and the
    /// card is considered stale `staleWindow` seconds after it.
    private func makeContent(for state: CompanionActivityAttributes.ContentState)
        -> ActivityContent<CompanionActivityAttributes.ContentState> {
        var stamped = state
        stamped.asOf = Date()
        return ActivityContent(state: stamped, staleDate: stamped.asOf.addingTimeInterval(staleWindow))
    }

    func end() {
        guard let activity else { return }
        self.activity = nil
        Task { await activity.end(nil, dismissalPolicy: .immediate) }
    }
}
