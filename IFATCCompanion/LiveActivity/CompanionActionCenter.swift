import Foundation

// NOTE: SHARED between the app and the widget extension (the Live Activity intents
// reference it). Add to the widget-extension target's membership too.

/// A tiny bridge that lets the Live Activity's Read Back / Check In buttons drive the
/// running app. The buttons launch App Intents (`CompanionIntents`) that run in the
/// app's process; those intents call `perform(_:)`, which forwards to the handler the
/// app installed (wired to `AppModel` in `configureLiveActivity`).
///
/// When this type is compiled into the widget extension, `handler` simply stays nil
/// there — the intents that matter run in the app, where the handler is set.
@MainActor
final class CompanionActionCenter {
    static let shared = CompanionActionCenter()

    enum Action {
        case readBack
        case checkIn
        /// Pull the latest telemetry to the notification now (the Refresh button). Carries no
        /// ATC side effect — the app just re-pushes its current state, which lands immediately
        /// because the button runs in-process from a user tap.
        case refresh
    }

    /// Installed by the app; invoked when a Live Activity button is tapped.
    var handler: ((Action) -> Void)?

    private init() {}

    func perform(_ action: Action) {
        handler?(action)
    }
}
