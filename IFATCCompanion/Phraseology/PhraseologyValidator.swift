import Foundation

/// Deterministic guard against banned / outdated / unsafe phraseology. Used by
/// tests (and available at runtime) to ensure generated calls never contain
/// prohibited wording. Pure string analysis — no AI.
///
/// Two tiers:
///  - `.block`  — must never appear in any generated transmission.
///  - `.warn`   — context-dependent; flagged for review but not auto-blocked.
struct PhraseologyValidator {

    enum Severity { case block, warn }

    struct Finding: Equatable {
        let phrase: String
        let severity: Severity
        let reason: String
    }

    /// Phrases prohibited anywhere in a generated controller/ramp/pilot call.
    /// Matched case-insensitively as substrings on a normalized string.
    static let banned: [(phrase: String, severity: Severity, reason: String)] = [
        ("cleared to taxi", .block, "Taxi is an instruction, not a clearance — use \"taxi\"."),
        ("cleared for taxi", .block, "Taxi is an instruction, not a clearance — use \"taxi\"."),
        ("cleared for pushback", .block, "Use \"pushback approved\" / \"push approved\"."),
        ("cleared for push", .block, "Use \"pushback approved\" / \"push approved\"."),
        ("position and hold", .block, "Outdated — use \"line up and wait\"."),
        ("taxi into position and hold", .block, "Outdated — use \"line up and wait\"."),
        ("takeoff at your discretion", .block, "Takeoff requires \"cleared for takeoff\"."),
        ("take off at your discretion", .block, "Takeoff requires \"cleared for takeoff\"."),
        ("cleared for departure", .warn, "Not a takeoff clearance — use \"cleared for takeoff\"."),
        ("line up and wait behind", .block, "Conditional line-up instructions are prohibited."),
        ("taxi as requested", .block, "Controlled movement areas require explicit taxi routing."),
        ("cross all runways", .block, "Each runway crossing requires an explicit, separate clearance."),
        ("cleared across all runways", .block, "Each runway crossing requires an explicit, separate clearance."),
        ("proceed as requested", .warn, "Use explicit taxi/runway instructions where required."),
        ("any traffic please advise", .block, "Prohibited (AIM) — do not solicit blanket traffic calls."),
        ("last call", .warn, "Avoid \"last call\" phrasing."),
        ("clear active", .block, "Name the specific runway — avoid \"active\"."),
        ("clear of the active", .block, "Name the specific runway — avoid \"active\"."),
        ("taking the active", .block, "Name the specific runway — avoid \"active\"."),
        ("active runway", .warn, "Use the specific runway number when known."),
        ("the active", .warn, "Use the specific runway number when known."),
        ("with you", .warn, "Do not generate \"with you\" in pilot check-ins."),
        ("on the ils", .warn, "Use a proper approach/status check-in, not \"on the ILS\".")
    ]

    /// Acknowledgments that are NOT acceptable on their own for a safety-critical
    /// readback (hold-short, runway crossing, landing, takeoff, heading, altitude).
    static let weakAcks = ["roger", "wilco"]

    /// Normalize for matching: lowercase, collapse whitespace, strip most punctuation.
    static func normalize(_ text: String) -> String {
        let lowered = text.lowercased()
        let cleaned = lowered.replacingOccurrences(of: "’", with: "'")
        let collapsed = cleaned.split(whereSeparator: { $0 == " " || $0 == "\n" || $0 == "\t" })
            .joined(separator: " ")
        return collapsed
    }

    /// All findings (block + warn) in a single string.
    func findings(in text: String) -> [Finding] {
        let n = Self.normalize(text)
        var out: [Finding] = []
        for entry in Self.banned where n.contains(entry.phrase) {
            out.append(Finding(phrase: entry.phrase, severity: entry.severity, reason: entry.reason))
        }
        return out
    }

    /// Only the blocking findings.
    func blockingFindings(in text: String) -> [Finding] {
        findings(in: text).filter { $0.severity == .block }
    }

    /// True when the text contains no `.block` phrases.
    func isClean(_ text: String) -> Bool { blockingFindings(in: text).isEmpty }

    /// Whether a readback acknowledging a safety-critical instruction is acceptable:
    /// it must NOT be a bare weak ack ("roger"/"wilco") and SHOULD echo the required
    /// elements (e.g. the runway, heading, altitude).
    func isAcceptableSafetyReadback(_ text: String, requiredElements: [String]) -> Bool {
        let n = Self.normalize(text)
        // A bare weak-ack readback (no substantive content) is unacceptable.
        let words = n.split(separator: " ").map(String.init)
        let onlyWeak = !words.isEmpty && words.allSatisfy { Self.weakAcks.contains($0) || $0.count <= 2 }
        if onlyWeak { return false }
        for el in requiredElements where !n.contains(PhraseologyValidator.normalize(el)) {
            return false
        }
        return true
    }

    /// Convenience: does the text contain a callsign-like trailing token? Used by
    /// tests to assert readbacks include the callsign.
    static func contains(_ text: String, element: String) -> Bool {
        normalize(text).contains(normalize(element))
    }
}
