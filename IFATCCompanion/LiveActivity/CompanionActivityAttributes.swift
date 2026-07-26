import Foundation
import ActivityKit

// NOTE: This file is SHARED between the app and the widget extension. When you add the
// widget-extension target (see docs/LiveActivitySetup.md), add this file — plus
// CompanionIntents.swift and CompanionActionCenter.swift — to that target's membership.

/// The data model behind the live flight notification (Lock Screen + Dynamic Island).
///
/// `ContentState` is the part that changes during the flight; the app pushes a new one
/// via `LiveActivityController.update(_:)` whenever the state meaningfully moves.
struct CompanionActivityAttributes: ActivityAttributes {

    struct ContentState: Codable, Hashable {
        /// Flight phase title, e.g. "Cruise".
        var phase: String
        /// Tuned controller title, e.g. "Center", and its SF Symbol.
        var facility: String
        var facilitySymbol: String
        /// Live telemetry (already unit-converted for display).
        var altitude: Int
        var heading: Int
        var speed: Int
        /// Spoken/display callsign, e.g. "UAL598".
        var callsign: String
        /// Route as "KIAH → KMSP" (either side may be blank).
        var route: String
        /// The next controller ahead, when a hand-off is pending.
        var nextFacility: String?
        /// A short weather advisory, when one is active on the route.
        var weatherAlert: String?
        /// Whether the Read Back / Check In buttons should be offered right now.
        var canReadBack: Bool
        var canCheckIn: Bool
        /// True while the companion is deferring to a human controller.
        var standby: Bool
        /// When this snapshot was pushed. The widget renders it as a self-updating
        /// "Updated Xm ago" line (a relative `Text` refreshes on the Lock Screen
        /// without a new push), and `LiveActivityController` derives the activity's
        /// `staleDate` from it — so once telemetry stops flowing (screen off, a
        /// stalled Infinite Flight socket) the card visibly reads "Reconnecting…"
        /// instead of showing old numbers as if they were live.
        var asOf: Date = Date()
    }

    /// Static title shown on the notification.
    var flightTitle: String
}
