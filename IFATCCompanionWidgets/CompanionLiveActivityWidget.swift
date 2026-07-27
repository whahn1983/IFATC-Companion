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
                DynamicIslandExpandedRegion(.leading) {
                    Label(state.facility, systemImage: state.facilitySymbol)
                        .font(.caption).foregroundStyle(.white)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(state.phase)
                        .font(.caption).foregroundStyle(.cyan)
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(state.callsign)
                        .font(.headline).foregroundStyle(.white)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(spacing: 6) {
                        TelemetryRow(state: state, isStale: isStale)
                        if let alert = state.weatherAlert {
                            Label(alert, systemImage: "cloud.bolt.rain")
                                .font(.caption2).foregroundStyle(.orange)
                        }
                        BottomBar(state: state, isStale: isStale)
                    }
                }
            } compactLeading: {
                // The icon conveys the controller; pair it with a short status so the
                // pill always shows something relevant (never a bare "0k" at the gate).
                // A stalled feed greys the pill so it doesn't read as live.
                Image(systemName: state.facilitySymbol).foregroundStyle(isStale ? .gray : .cyan)
            } compactTrailing: {
                Text(compactStatus(state)).font(.caption2).monospacedDigit()
                    .foregroundStyle(isStale ? .gray : .white)
            } minimal: {
                Image(systemName: state.facilitySymbol).foregroundStyle(isStale ? .gray : .cyan)
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
        VStack(alignment: .leading, spacing: 8) {
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

            BottomBar(state: state, isStale: isStale)
        }
        .padding()
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

/// The bottom row of the flight notification: action buttons on the left, the freshness
/// indicator tucked into the bottom-right corner. Sharing one row keeps the freshness text
/// from claiming its own line and pushing the buttons past the notification's height.
private struct BottomBar: View {
    let state: CompanionActivityAttributes.ContentState
    let isStale: Bool

    var body: some View {
        HStack(alignment: .bottom) {
            ActionButtons(state: state)
            Spacer(minLength: 8)
            FreshnessLine(asOf: state.asOf, isStale: isStale)
                .fixedSize()
        }
    }
}

/// A one-line freshness indicator. When live it shows a self-updating "Updated Xm ago"
/// (a relative `Text` refreshes on the Lock Screen without a new push); once stale it
/// switches to "Reconnecting…" so the user knows the numbers above are no longer current.
private struct FreshnessLine: View {
    let asOf: Date
    let isStale: Bool

    var body: some View {
        Group {
            if isStale {
                Label("Reconnecting…", systemImage: "arrow.triangle.2.circlepath")
                    .foregroundStyle(.orange)
            } else {
                HStack(spacing: 3) {
                    Image(systemName: "dot.radiowaves.left.and.right")
                    Text("Updated")
                    Text(asOf, style: .relative)
                    Text("ago")
                }
                .foregroundStyle(.secondary)
            }
        }
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

private func altitudeShort(_ feet: Int) -> String {
    feet >= 18_000 ? "FL\(feet / 100)" : "\(feet / 1_000)k"
}

/// The single most relevant datum for the tiny compact Dynamic Island: the altitude once
/// airborne, otherwise a short flight-phase word (so it reads e.g. "Taxi"/"Gate" at the
/// gate instead of a meaningless "0k").
private func compactStatus(_ state: CompanionActivityAttributes.ContentState) -> String {
    if state.altitude >= 1_000 { return altitudeShort(state.altitude) }
    switch state.phase {
    case "Preflight", "Parked": return "Gate"
    case "Taxi Out", "Taxi In": return "Taxi"
    case "Takeoff": return "Dep"
    case "Initial Climb": return "Climb"
    default: return state.phase   // Climb, Cruise, Descent, Approach, Landing already fit
    }
}
