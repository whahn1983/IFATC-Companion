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
        /// When this snapshot was pushed, shown on the card as a static "Updated 9:55 PM"
        /// line so the user can see how current the numbers are. Not tied to any `staleDate`
        /// (the notification no longer flags itself "stale" from push timing — iOS throttles
        /// background pushes on a perfectly connected app, which made that flag misfire); the
        /// user refreshes on demand with the Refresh button.
        var asOf: Date = Date()
    }

    /// Static title shown on the notification.
    var flightTitle: String
}
