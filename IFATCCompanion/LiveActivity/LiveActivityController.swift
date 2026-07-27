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

    /// Stamp a `ContentState` with the push time and hand it to ActivityKit with **no
    /// `staleDate`**. A `staleDate` made iOS flip the card to "Reconnecting…" whenever the
    /// app hadn't pushed within the window — but on iOS that window is reached routinely
    /// even when the app is perfectly connected, because the system throttles a backgrounded
    /// app's Live Activity pushes (a hard OS limit no standalone app can override). So the
    /// indicator was firing on a healthy link and reading as a false failure. Without a
    /// `staleDate` the card simply shows the last values it received; the user pulls in fresh
    /// telemetry on demand with the notification's Refresh button (`RefreshIntent`), which —
    /// running in-process from a user tap — is delivered immediately, unlike a background push.
    private func makeContent(for state: CompanionActivityAttributes.ContentState)
        -> ActivityContent<CompanionActivityAttributes.ContentState> {
        var stamped = state
        stamped.asOf = Date()
        return ActivityContent(state: stamped, staleDate: nil)
    }

    func end() {
        guard let activity else { return }
        self.activity = nil
        Task { await activity.end(nil, dismissalPolicy: .immediate) }
    }
}
