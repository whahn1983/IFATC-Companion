import Foundation

/// Splits the identifier tagged on an aircraft stand into the one a controller says and
/// every other identifier the same stand answers to.
///
/// OSM maps a physical stand that can be worked under more than one number as a *single*
/// node carrying all of them in its `ref`: `A1;A2` at Newark, `A54/A56` at Frankfurt,
/// `C16/C16A + C16B`. Around 8–10% of the stands at those two fields are tagged this way,
/// so it is not an edge case. Stored verbatim, such a value is displayed and spoken back
/// as written ("gate A1;A2"), and — worse — a pilot who *types* `A1` matches nothing at
/// all, because the stand is named `A1;A2` and the lookup is an exact one. That affects
/// manual entry, not just the automatic assignment.
///
/// So the tag is split: the first identifier becomes the stand's name — what the clearance
/// says and what the map labels — and the rest, plus the raw tag value itself, become
/// aliases the lookup also accepts. Nothing is lost: the original `ref` stays verbatim in
/// `SurfaceParking.tags`, as every OSM tag does.
enum StandIdentifier {

    /// Characters mappers use to join several identifiers into one `ref`. `;` is the OSM
    /// multi-value separator; the rest are what mappers actually write in the wild.
    /// Deliberately excludes `-`, which reads as a range ("A1-A5") rather than a list, and
    /// whitespace, which is part of identifiers like "Gate 12".
    private static let separators = CharacterSet(charactersIn: ";/+,&")

    /// Split a stand's `ref`/`name` into the name to use and the aliases it also answers to.
    /// A single-identifier tag — the common case — comes back unchanged with no aliases.
    static func parse(_ raw: String) -> (name: String, aliases: [String]) {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.rangeOfCharacter(from: separators) != nil else { return (trimmed, []) }

        var identifiers: [String] = []
        var seen = Set<String>()
        for piece in trimmed.components(separatedBy: separators) {
            let part = piece.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !part.isEmpty else { continue }
            let identifier = inheritingPrefix(part, after: identifiers.last)
            guard seen.insert(identifier.uppercased()).inserted else { continue }
            identifiers.append(identifier)
        }
        guard let name = identifiers.first else { return (trimmed, []) }
        // Only one identifier survived the split — a stray separator, or the same one twice.
        // There is nothing extra to match on, so the raw value isn't worth carrying.
        guard identifiers.count > 1 else { return (name, []) }
        // The tag exactly as written is an alias too, so a pilot who copies it in still matches.
        return (name, Array(identifiers.dropFirst()) + [trimmed])
    }

    /// `A54/56` means A54 *and* A56: a bare number following a lettered identifier inherits
    /// its letters. Applied only in that exact shape — an all-digit part after a part that
    /// starts with letters — so `1/2` and `A1/B2` are left exactly as tagged.
    private static func inheritingPrefix(_ part: String, after previous: String?) -> String {
        guard let previous, !part.isEmpty, part.allSatisfy({ $0.isNumber }) else { return part }
        let letters = previous.prefix { $0.isLetter }
        guard !letters.isEmpty else { return part }
        return String(letters) + part
    }
}
