import WidgetKit
import SwiftUI
import ActivityKit
import AppIntents

/// The live flight notification UI: the Lock Screen banner and every Dynamic Island
/// presentation. Rendered by the widget extension; the app pushes new `ContentState`s
/// via `LiveActivityController`.
///
/// This file (and `CompanionWidgetBundle.swift`) belong to the widget-extension target.
/// The shared model/intents (`CompanionActivityAttributes`, `CompanionIntents`,
/// `CompanionActionCenter`) must be added to *both* targets — see docs/LiveActivitySetup.md.
struct CompanionLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: CompanionActivityAttributes.self) { context in
            LockScreenLiveActivityView(state: context.state, isStale: context.isStale)
                .activityBackgroundTint(Color.black.opacity(0.55))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            let state = context.state
            let isStale = context.isStale
            return DynamicIsland {
                // The top leading/trailing regions sit hard against the expanded island's
                // rounded top corners, which clip their text ("Cruise", the facility name).
                // Nudge each inward from its edge and down from the top so nothing is cut.
                DynamicIslandExpandedRegion(.leading) {
                    Label(state.facility, systemImage: state.facilitySymbol)
                        .font(.caption).foregroundStyle(.white)
                        .padding(.leading, 6).padding(.top, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(state.phase)
                        .font(.caption).foregroundStyle(.cyan)
                        .padding(.trailing, 6).padding(.top, 4)
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(state.callsign)
                        .font(.headline).foregroundStyle(.white)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    // Inset the right edge so the last-updated line ("Updated 9:55 PM") clears
                    // the rounded bottom-right corner instead of being clipped.
                    VStack(spacing: 6) {
                        TelemetryRow(state: state, isStale: isStale)
                        if let alert = state.weatherAlert {
                            Label(alert, systemImage: "cloud.bolt.rain")
                                .font(.caption2).foregroundStyle(.orange)
                        }
                        BottomBar(state: state)
                    }
                    .padding(.trailing, 8)
                }
            } compactLeading: {
                // The app icon, not the controller glyph: the tuned controller changes as the
                // flight is handed off, so that icon would go stale between the throttled
                // background pushes. The app icon is fixed, so the pill always reads correctly.
                CompanionGlyph()
            } compactTrailing: {
                // Show the callsign rather than altitude. Altitude freezes between pushes (iOS
                // throttles a backgrounded app's Live Activity updates), so a frozen number
                // reads as live when it isn't; the callsign never changes, so it's always right.
                Text(state.callsign).font(.caption2).monospacedDigit()
                    .foregroundStyle(.white)
            } minimal: {
                CompanionGlyph()
            }
            .keylineTint(.cyan)
        }
    }
}

// MARK: - Lock Screen

private struct LockScreenLiveActivityView: View {
    let state: CompanionActivityAttributes.ContentState
    /// True once iOS marks the activity stale (no push within the controller's stale
    /// window) — i.e. the telemetry behind these numbers has stopped refreshing.
    let isStale: Bool

    var body: some View {
        // Keep the vertical footprint tight: the Lock Screen caps the banner height, and
        // with every row present (route, telemetry, phase, weather alert, action bar) a
        // roomier layout clips the BottomBar — Refresh and the "Updated" line — off the
        // bottom. Modest row spacing plus trimmed vertical padding keeps it all on screen.
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Label(state.callsign, systemImage: "airplane")
                    .font(.headline).foregroundStyle(.white)
                Spacer()
                if state.standby {
                    Label("Standby", systemImage: "person.fill.checkmark")
                        .font(.caption2).foregroundStyle(.yellow)
                } else {
                    Label(state.facility, systemImage: state.facilitySymbol)
                        .font(.caption).foregroundStyle(.cyan)
                }
            }

            if !state.route.isEmpty {
                Text(state.route).font(.caption).foregroundStyle(.secondary)
            }

            TelemetryRow(state: state, isStale: isStale)

            HStack {
                Text(state.phase).font(.caption).foregroundStyle(.cyan)
                if let next = state.nextFacility {
                    Spacer()
                    Label("Next: \(next)", systemImage: "arrow.right")
                        .font(.caption2).foregroundStyle(.secondary)
                }
            }

            if let alert = state.weatherAlert {
                Label(alert, systemImage: "cloud.bolt.rain")
                    .font(.caption2).foregroundStyle(.orange)
            }

            BottomBar(state: state)
        }
        .padding(.horizontal)
        .padding(.vertical, 10)
    }
}

// MARK: - Shared pieces

private struct TelemetryRow: View {
    let state: CompanionActivityAttributes.ContentState
    /// Dim the numbers when the feed has stalled so they don't read as live.
    var isStale: Bool = false
    var body: some View {
        HStack(spacing: 16) {
            metric("ALT", "\(state.altitude) ft")
            metric("HDG", String(format: "%03d°", state.heading))
            metric("GS", "\(state.speed) kt")
        }
        .opacity(isStale ? 0.5 : 1)
    }

    private func metric(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(label).font(.system(size: 9, weight: .semibold)).foregroundStyle(.secondary)
            Text(value).font(.caption).monospacedDigit().foregroundStyle(.white)
        }
    }
}

/// The bottom row of the flight notification: ATC action buttons on the left; on the right, a
/// Refresh button stacked above the last-updated time. Refresh is how the user pulls current
/// telemetry to the card on demand — iOS throttles the app's routine background pushes, so the
/// numbers freeze while backgrounded even though the app stays connected, and a user tap is the
/// one push iOS delivers immediately.
private struct BottomBar: View {
    let state: CompanionActivityAttributes.ContentState

    var body: some View {
        HStack(alignment: .bottom) {
            ActionButtons(state: state)
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 3) {
                Button(intent: RefreshIntent()) {
                    Label("Refresh", systemImage: "arrow.clockwise")
                }
                .tint(.cyan)
                .font(.caption2)
                .buttonStyle(.bordered)
                FreshnessLine(asOf: state.asOf)
            }
            .fixedSize()
        }
    }
}

/// The last-update clock time, tucked under the Refresh button. Deliberately a *static*
/// pre-formatted time rather than an auto-updating `Text(asOf, style: .relative)`: a date-style
/// Text can't be reconstructed from the Lock Screen's archived snapshot when the screen locks,
/// and when that one element fails to render it takes the whole banner body down with it (every
/// row below the header renders blank).
private struct FreshnessLine: View {
    let asOf: Date

    var body: some View {
        Label("Updated \(asOf.formatted(date: .omitted, time: .shortened))",
              systemImage: "dot.radiowaves.left.and.right")
            .foregroundStyle(.secondary)
            .font(.system(size: 10))
    }
}

private struct ActionButtons: View {
    let state: CompanionActivityAttributes.ContentState

    var body: some View {
        if state.standby {
            EmptyView()
        } else if state.canReadBack || state.canCheckIn {
            HStack(spacing: 8) {
                if state.canReadBack {
                    Button(intent: ReadBackIntent()) {
                        Label("Read Back", systemImage: "checkmark.circle")
                    }
                    .tint(.green)
                }
                if state.canCheckIn {
                    Button(intent: CheckInIntent()) {
                        Label("Check In", systemImage: "person.wave.2")
                    }
                    .tint(.blue)
                }
            }
            .font(.caption)
            .buttonStyle(.borderedProminent)
        }
    }
}

/// The app icon, sized and rounded for the compact/minimal Dynamic Island. Uses the widget
/// target's bundled `CompanionIcon` asset (a copy of the app icon) — a fixed mark that stays
/// correct between the throttled background pushes, unlike the controller glyph it replaced.
///
/// The asset is a small (≤72 px) copy, not the 1024 px app icon: a Live Activity has a tight
/// image budget and silently renders an oversized image as a grey placeholder on device. The
/// explicit frame pins the region to exactly the icon's size so it hugs the sensor housing;
/// the remaining gap to the callsign is the housing itself, inherent to the compact layout.
private struct CompanionGlyph: View {
    var body: some View {
        Image("CompanionIcon")
            .resizable()
            .frame(width: 22, height: 22)
            .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
    }
}
