import Foundation
import Combine

/// Central, in-memory diagnostics log surfaced on the Diagnostics tab and
/// exportable via the share sheet. Thread-safe for appends from networking code.
@MainActor
final class DiagnosticsStore: ObservableObject {

    enum Category: String {
        case connect = "CONNECT"
        case manifest = "MANIFEST"
        case state = "STATE"
        case command = "COMMAND"
        case weather = "WEATHER"
        case atc = "ATC"
        case app = "APP"
    }

    struct Entry: Identifiable {
        let id = UUID()
        let timestamp: Date
        let category: Category
        let message: String
    }

    @Published private(set) var entries: [Entry] = []
    @Published var discoveredStates: [IFManifestEntry] = []
    @Published var weatherEndpointStatus: String = "Not checked"
    @Published var lastRawMessage: String = ""

    private let maxEntries = 500
    var verbose = true

    private let formatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    func log(_ category: Category, _ message: String) {
        let entry = Entry(timestamp: Date(), category: category, message: message)
        entries.append(entry)
        if entries.count > maxEntries {
            entries.removeFirst(entries.count - maxEntries)
        }
    }

    /// Convenience for non-main contexts.
    nonisolated func logAsync(_ category: Category, _ message: String) {
        Task { @MainActor in self.log(category, message) }
    }

    func recordRaw(_ raw: String) {
        // Sanitize: keep it short and printable.
        let trimmed = String(raw.prefix(400))
        lastRawMessage = trimmed
    }

    func clear() {
        entries.removeAll()
    }

    /// Render the full diagnostics buffer as shareable plain text.
    func exportText() -> String {
        var lines: [String] = []
        lines.append("IFATC Companion — Diagnostics Export")
        lines.append("Generated: \(ISO8601DateFormatter().string(from: Date()))")
        lines.append("")
        lines.append("== Weather endpoint ==")
        lines.append(weatherEndpointStatus)
        lines.append("")
        lines.append("== Discovered manifest states (\(discoveredStates.count)) ==")
        for s in discoveredStates.prefix(200) {
            lines.append("  [\(s.id)] \(s.type.shortName) \(s.name)")
        }
        lines.append("")
        lines.append("== Last raw message (sanitized) ==")
        lines.append(lastRawMessage.isEmpty ? "(none)" : lastRawMessage)
        lines.append("")
        lines.append("== Log (\(entries.count)) ==")
        for e in entries {
            lines.append("\(formatter.string(from: e.timestamp)) [\(e.category.rawValue)] \(e.message)")
        }
        return lines.joined(separator: "\n")
    }
}
