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
    private let minInterval: TimeInterval = 2.0

    var isActive: Bool { activity != nil }

    /// Start the activity (or update it if one is already running).
    func start(_ state: CompanionActivityAttributes.ContentState) {
        guard activity == nil else { update(state, force: true); return }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let attributes = CompanionActivityAttributes(flightTitle: "IFATC Companion")
        do {
            activity = try Activity.request(
                attributes: attributes,
                content: ActivityContent(state: state, staleDate: nil),
                pushType: nil)
        } catch {
            activity = nil
        }
    }

    func update(_ state: CompanionActivityAttributes.ContentState, force: Bool = false) {
        guard let activity else { return }
        let now = Date()
        if !force, now.timeIntervalSince(lastUpdate) < minInterval { return }
        lastUpdate = now
        Task { await activity.update(ActivityContent(state: state, staleDate: nil)) }
    }

    func end() {
        guard let activity else { return }
        self.activity = nil
        Task { await activity.end(nil, dismissalPolicy: .immediate) }
    }
}
