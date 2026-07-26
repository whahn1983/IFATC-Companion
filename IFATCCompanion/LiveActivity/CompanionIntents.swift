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
