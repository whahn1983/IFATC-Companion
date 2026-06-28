import Foundation
import Combine

/// Decides how UNICOM intentions are surfaced and (optionally) sent to Infinite
/// Flight. Honors the user's mode (off / preview / auto) and degrades gracefully
/// when the Connect API does not expose the required command.
///
/// IMPORTANT: UNICOM broadcasts announce the *pilot's own* intentions only. This
/// is never staffed ATC and never impersonates a live controller.
@MainActor
final class UNICOMAutomationService: ObservableObject {

    /// The pending suggestion awaiting send/skip (preview mode) or the last
    /// suggested/sent broadcast.
    @Published var pending: UNICOMSuggestion?
    @Published var statusText: String = "UNICOM idle."

    var mode: UNICOMMode = .preview
    var connected: Bool = false

    private weak var connect: IFConnectManager?
    private weak var diagnostics: DiagnosticsStore?

    func configure(connect: IFConnectManager?, diagnostics: DiagnosticsStore?) {
        self.connect = connect
        self.diagnostics = diagnostics
    }

    /// Recompute availability of every UNICOM command against the current manifest
    /// and publish it to Diagnostics.
    func refreshAvailability() {
        var list: [UNICOMCommandAvailability] = []
        for event in UNICOMEvent.allCases {
            if let entry = connect?.commandAvailable(keywords: event.commandKeywords) {
                list.append(.init(event: event, isAvailable: true, detail: "\(entry.name) [\(entry.id)]"))
            } else {
                list.append(.init(event: event, isAvailable: false, detail: nil))
            }
        }
        diagnostics?.unicomAvailability = list
    }

    /// Handle a fired UNICOM event for the given airport ident + runway.
    func handle(event: UNICOMEvent, ident: String, runway: String) {
        let message = event.broadcast(ident: ident, runway: runway)
        let available = connect?.commandAvailable(keywords: event.commandKeywords) != nil

        switch mode {
        case .off:
            pending = UNICOMSuggestion(event: event, message: message,
                                       isAvailable: available, willAutoSend: false)
            statusText = "Suggested: \(message)"
            diagnostics?.log(.unicom, "Suggested (mode off): \(message)")

        case .preview:
            pending = UNICOMSuggestion(event: event, message: message,
                                       isAvailable: available, willAutoSend: false)
            statusText = available ? "Preview — tap Send to broadcast." : "Preview (automation unavailable for this event)."
            diagnostics?.log(.unicom, "Preview: \(message) (available: \(available))")

        case .auto:
            let willAuto = available && connected && event.isTrusted
            pending = UNICOMSuggestion(event: event, message: message,
                                       isAvailable: available, willAutoSend: willAuto)
            if willAuto {
                statusText = "Auto-sending: \(message)"
                Task { await self.send(event: event, message: message) }
            } else if available && connected {
                statusText = "Preview (non-trusted event) — tap Send."
                diagnostics?.log(.unicom, "Auto held for confirmation: \(message)")
            } else {
                statusText = available ? "Auto unavailable (not connected)." : "UNICOM automation not available for this event."
                diagnostics?.log(.unicom, "Auto could not send: \(message)")
            }
        }
    }

    /// Send the pending suggestion (preview "Send" tap).
    func sendPending() {
        guard let p = pending else { return }
        Task { await self.send(event: p.event, message: p.message) }
    }

    /// Skip/dismiss the pending suggestion.
    func skipPending() {
        if let p = pending {
            diagnostics?.log(.unicom, "Skipped: \(p.message)")
        }
        statusText = "Skipped."
        pending = nil
    }

    private func send(event: UNICOMEvent, message: String) async {
        guard let connect else {
            statusText = "UNICOM automation not available for this event."
            return
        }
        let result = await connect.sendCommand(keywords: event.commandKeywords)
        if result.sent {
            statusText = "Sent: \(message)"
        } else if result.resolved == nil {
            statusText = "UNICOM automation not available for this event."
        } else {
            statusText = "Send failed — keeping companion conversation active."
        }
        // Clear pending after a send attempt.
        if pending?.event == event { pending = nil }
    }
}
