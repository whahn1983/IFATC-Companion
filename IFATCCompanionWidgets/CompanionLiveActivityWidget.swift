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
            LockScreenLiveActivityView(state: context.state)
                .activityBackgroundTint(Color.black.opacity(0.55))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            let state = context.state
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
                        TelemetryRow(state: state)
                        if let alert = state.weatherAlert {
                            Label(alert, systemImage: "cloud.bolt.rain")
                                .font(.caption2).foregroundStyle(.orange)
                        }
                        ActionButtons(state: state)
                    }
                }
            } compactLeading: {
                Image(systemName: state.facilitySymbol).foregroundStyle(.cyan)
            } compactTrailing: {
                Text(altitudeShort(state.altitude)).font(.caption2).foregroundStyle(.white)
            } minimal: {
                Image(systemName: "airplane").foregroundStyle(.cyan)
            }
            .keylineTint(.cyan)
        }
    }
}

// MARK: - Lock Screen

private struct LockScreenLiveActivityView: View {
    let state: CompanionActivityAttributes.ContentState

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

            TelemetryRow(state: state)

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

            ActionButtons(state: state)
        }
        .padding()
    }
}

// MARK: - Shared pieces

private struct TelemetryRow: View {
    let state: CompanionActivityAttributes.ContentState
    var body: some View {
        HStack(spacing: 16) {
            metric("ALT", "\(state.altitude) ft")
            metric("HDG", String(format: "%03d°", state.heading))
            metric("GS", "\(state.speed) kt")
        }
    }

    private func metric(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(label).font(.system(size: 9, weight: .semibold)).foregroundStyle(.secondary)
            Text(value).font(.caption).monospacedDigit().foregroundStyle(.white)
        }
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
