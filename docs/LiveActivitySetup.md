# Live Activity widget-extension setup

The live flight notification (Lock Screen + Dynamic Island) is rendered by a **WidgetKit
extension**. Everything on the app side is already implemented and wired; all that remains
is adding the extension target, which must be done in Xcode (creating an app-extension
target by hand in `project.pbxproj` is error-prone, and Xcode's template also generates
the extension's `Info.plist` and the "Embed Foundation/App Extensions" build phase
correctly). This is a ~2-minute, one-time step.

## What already exists in the repo

- **App side (in the `IFATCCompanion` target):**
  - `LiveActivity/LiveActivityController.swift` — starts/updates/ends the activity.
  - `LiveActivity/CompanionActivityAttributes.swift` — the shared data model.
  - `LiveActivity/CompanionIntents.swift` — the Read Back / Check In `LiveActivityIntent`s.
  - `LiveActivity/CompanionActionCenter.swift` — bridges the intents back into `AppModel`.
  - `Info.plist` already declares `NSSupportsLiveActivities` and the `audio` background mode.
  - `AppModel` starts/updates the activity and installs the button handler.
- **Widget side (in `IFATCCompanionWidgets/`, NOT yet in any target):**
  - `CompanionWidgetBundle.swift` — the `@main` widget bundle.
  - `CompanionLiveActivityWidget.swift` — the Lock Screen + Dynamic Island UI.

## Steps

1. **Add the target.** In Xcode: **File → New → Target… → Widget Extension**.
   - Product Name: **`IFATCCompanionWidgets`** (match the existing folder name).
   - **Uncheck** "Include Configuration App Intent".
   - **Check** "Include Live Activity".
   - Finish, and **Activate** the scheme if prompted.

2. **Use the provided widget files, not the generated samples.** Xcode generates sample
   files (a `…Bundle.swift`, a `…LiveActivity.swift`, etc.). **Delete the generated
   Swift files** so there is only one `@main`, and make sure the target instead builds the
   two files already in `IFATCCompanionWidgets/`:
   - `CompanionWidgetBundle.swift`
   - `CompanionLiveActivityWidget.swift`

   (The project uses Xcode's file-system–synchronized groups, so if the new target points
   at the `IFATCCompanionWidgets/` folder these are picked up automatically; just confirm
   both show a checkmark for the widget target in the File Inspector.)

3. **Share the model/intents with the extension.** Select each of these three files and,
   in the **File Inspector → Target Membership**, tick **`IFATCCompanionWidgets`** in
   addition to `IFATCCompanion`:
   - `LiveActivity/CompanionActivityAttributes.swift`
   - `LiveActivity/CompanionIntents.swift`
   - `LiveActivity/CompanionActionCenter.swift`

4. **Match the deployment target.** Set the widget target's **iOS Deployment Target to
   17.0** (same as the app), so `LiveActivityIntent` and interactive buttons are available.

5. **Confirm the extension point.** The Widget Extension template sets the extension's
   `Info.plist` `NSExtensionPointIdentifier` to `com.apple.widgetkit-extension`
   automatically — no change needed.

6. **Build & run** the app target on a device or simulator, then turn on
   **Settings → Background Radio & Notification → Live flight notification** and start a
   flight (Mock Mode is fine for a first look).

## Notes

- Live Activities require the user to have them enabled (Settings → Face ID & Passcode /
  the app's notification settings). The controller checks
  `ActivityAuthorizationInfo().areActivitiesEnabled` and no-ops if they're off.
- Updates come **from the app while it is running**; there is no push server (consistent
  with the app's local-only design). The background chatter is what keeps the app running
  (and thus the notification updating) while backgrounded — which is why the notification
  requires background chatter.
- The two buttons run `ReadBackIntent` / `CheckInIntent` in the app process; they call the
  same `AppModel.readBack()` / `AppModel.requestHandoff()` as the on-screen buttons.
