import Foundation
import AppIntents

// NOTE: SHARED between the app and the widget extension. Add to the widget-extension
// target's membership too, so its Live Activity buttons can reference these intents.

/// "Read Back" button on the live flight notification. Runs in the app process and
/// echoes the last controller instruction, exactly like the on-screen Read Back button.
struct ReadBackIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Read Back"
    static var description = IntentDescription("Read back the last ATC instruction.")

    func perform() async throws -> some IntentResult {
        await MainActor.run { CompanionActionCenter.shared.perform(.readBack) }
        return .result()
    }
}

/// "Check In" button on the live flight notification. Checks in with the controller the
/// pilot was handed to, like the on-screen Check In button.
struct CheckInIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Check In"
    static var description = IntentDescription("Check in with the next controller.")

    func perform() async throws -> some IntentResult {
        await MainActor.run { CompanionActionCenter.shared.perform(.checkIn) }
        return .result()
    }
}

/// "Refresh" button on the live flight notification. Forces the app to push its latest
/// telemetry to the card right now. iOS throttles a backgrounded app's routine Live Activity
/// pushes (so the numbers freeze while backgrounded even though the app stays connected), but
/// a Live Activity button runs in the app's process from a user tap and its update is
/// delivered immediately — so this is the on-demand way to pull current data to the card.
struct RefreshIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Refresh"
    static var description = IntentDescription("Update the flight data shown on the notification now.")

    func perform() async throws -> some IntentResult {
        await MainActor.run { CompanionActionCenter.shared.perform(.refresh) }
        return .result()
    }
}
