import SwiftUI

/// Traffic-light status used across the dashboard.
enum StatusLevel {
    case green, amber, red, neutral

    var color: Color {
        switch self {
        case .green: return Color(red: 0.30, green: 0.82, blue: 0.45)
        case .amber: return Color(red: 0.98, green: 0.74, blue: 0.18)
        case .red: return Color(red: 0.95, green: 0.35, blue: 0.32)
        case .neutral: return Color.gray
        }
    }
}

/// A rounded dark card container.
struct Card<Content: View>: View {
    var title: String? = nil
    var systemImage: String? = nil
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let title {
                HStack(spacing: 6) {
                    if let systemImage { Image(systemName: systemImage) }
                    Text(title).font(.headline)
                }
                .foregroundStyle(.secondary)
            }
            content()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(Color(.secondarySystemBackground))
        )
    }
}

/// A status chip with a colored dot.
struct StatusPill: View {
    var text: String
    var level: StatusLevel
    var systemImage: String? = nil

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(level.color).frame(width: 10, height: 10)
            if let systemImage { Image(systemName: systemImage).font(.caption) }
            Text(text).font(.subheadline.weight(.semibold))
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .background(Capsule().fill(level.color.opacity(0.18)))
        .overlay(Capsule().stroke(level.color.opacity(0.5), lineWidth: 1))
    }
}

/// A label/value row for data panels.
struct DataRow: View {
    var label: String
    var value: String
    var systemImage: String? = nil

    var body: some View {
        HStack {
            HStack(spacing: 6) {
                if let systemImage { Image(systemName: systemImage).foregroundStyle(.secondary).frame(width: 22) }
                Text(label).foregroundStyle(.secondary)
            }
            Spacer()
            Text(value).font(.body.weight(.semibold)).multilineTextAlignment(.trailing)
        }
        .font(.body)
    }
}

/// Large action button suitable for one-handed use while flying.
struct ActionButton: View {
    var title: String
    var systemImage: String
    var tint: Color = .accentColor
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: systemImage).font(.title3)
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity, minHeight: 64)
            .padding(.vertical, 6)
        }
        .buttonStyle(.bordered)
        .tint(tint)
    }
}

extension View {
    /// Convenience to apply the standard screen background.
    func screenBackground() -> some View {
        self.background(Color(.systemBackground).ignoresSafeArea())
    }
}
