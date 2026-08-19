import Foundation

/// Recognizes the error pages a public Overpass endpoint serves **with HTTP 200**.
///
/// Overpass does not report overload with a status code. When a server is loaded, rate
/// limited, or the query outruns its budget, it answers `200 OK` with an HTML page whose
/// body reads "The server is probably too busy…" or "runtime error: Query timed out…"
/// instead of the JSON extract. Both of the two big fields tried in one recent sample
/// (KATL, EHAM) came back exactly that way.
///
/// Left undetected, that page fails JSON decoding and the fetch falls through to the
/// "empty extract" path, which tells the pilot there are *no airport surface features for
/// this area* — sending them to hunt a data problem at their airport when the truth is
/// that a shared public server was busy for a minute. This type is what tells the two
/// apart.
struct OverpassErrorPage: Equatable {
    /// A short, recognized reason, when the page says something classifiable. Nil for an
    /// error page whose text matches none of the known markers — still an error page, just
    /// not one worth naming to the pilot.
    var reason: String?
    /// The page's text, tags stripped and whitespace collapsed, capped for a log line.
    /// Diagnostics only — never shown in the interface.
    var summary: String

    /// How much of the body to inspect. Overpass's error pages are tiny; a real extract
    /// can be megabytes, and only its first non-whitespace character is needed to rule it
    /// out.
    private static let inspectedBytes = 8_192

    /// The error page in a response body, or nil when the body is (or begins as) JSON.
    ///
    /// Deliberately shape-based rather than marker-based: any body that does not start as
    /// a JSON document is the server talking, not airport data. Call it only after JSON
    /// decoding has already failed — a valid extract never reaches here.
    static func detect(in data: Data) -> OverpassErrorPage? {
        let head = data.prefix(inspectedBytes)
        guard let text = String(data: head, encoding: .utf8)
                ?? String(data: head, encoding: .isoLatin1) else { return nil }
        guard let first = text.first(where: { !$0.isWhitespace }) else { return nil }
        guard first != "{" && first != "[" else { return nil }
        let plain = plainText(from: text)
        guard !plain.isEmpty else { return nil }
        return OverpassErrorPage(reason: classify(plain), summary: String(plain.prefix(240)))
    }

    /// The phrases Overpass puts in its error pages, mapped to how the app says it. Matched
    /// on the tag-stripped text, so markup between the words never hides one.
    private static let markers: [(phrase: String, reason: String)] = [
        ("too busy", "the server is too busy"),
        ("load too high", "the server load is too high"),
        ("slot available after", "the request rate limit is in force"),
        ("too many requests", "the request rate limit is in force"),
        ("rate_limited", "the request rate limit is in force"),
        ("timed out", "the query outran the server's time budget"),
        ("out of memory", "the query outran the server's memory budget")
    ]

    private static func classify(_ plain: String) -> String? {
        let haystack = plain.lowercased()
        return markers.first { haystack.contains($0.phrase) }?.reason
    }

    /// Tag-stripped, whitespace-collapsed text of an HTML (or plain-text) error page.
    private static func plainText(from html: String) -> String {
        var out = ""
        var insideTag = false
        for character in html {
            switch character {
            case "<": insideTag = true; out.append(" ")   // a tag also separates words
            case ">": insideTag = false
            default: if !insideTag { out.append(character) }
            }
        }
        return out.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }
}
