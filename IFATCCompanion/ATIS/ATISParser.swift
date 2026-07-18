import Foundation

/// Parses the FAA D-ATIS JSON payload (`datis.clowd.io/api/{ICAO}`) into an
/// `AirportATIS`. Deterministic and defensive — any shape it doesn't recognize
/// (an error object, an empty array, malformed JSON) yields `nil`, which the app
/// treats as "no ATIS for this field" and hides the feature. No data is invented.
///
/// The feed returns a JSON array; each element is:
/// ```json
/// { "airport": "KLAX", "type": "arr" | "dep" | "combined",
///   "code": "A", "datis": "…ADVISE YOU HAVE INFORMATION ALPHA." }
/// ```
enum ATISParser {

    private struct DATISElement: Decodable {
        let airport: String?
        let type: String?
        let code: String?
        let datis: String?
    }

    /// Parse a D-ATIS response body. Returns nil when the airport has no usable
    /// D-ATIS (error object, empty array, or no non-empty text).
    static func parse(_ data: Data, airport requested: String, now: Date = Date()) -> AirportATIS? {
        guard let elements = try? JSONDecoder().decode([DATISElement].self, from: data),
              !elements.isEmpty else {
            return nil
        }

        var parts: [AirportATIS.Part] = []
        for element in elements {
            let text = (element.datis ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { continue }
            let kind = AirportATIS.Kind(apiType: element.type ?? "")
            let letter = infoLetter(code: element.code, text: text) ?? ""
            parts.append(AirportATIS.Part(kind: kind, letter: letter, text: text))
        }
        guard !parts.isEmpty else { return nil }

        let icao = (elements.first?.airport ?? requested)
            .uppercased().trimmingCharacters(in: .whitespaces)
        return AirportATIS(airport: icao.isEmpty ? requested.uppercased() : icao,
                           parts: parts, fetchedAt: now)
    }

    // MARK: - Information code

    /// Reverse phonetic map, e.g. "ALPHA" -> "A", built from `Phonetic.letterWords`.
    private static let wordToLetter: [String: String] = {
        var map: [String: String] = [:]
        for (ch, word) in Phonetic.letterWords { map[word.uppercased()] = String(ch) }
        return map
    }()

    /// Resolve the ATIS information letter, preferring the feed's explicit `code`
    /// field and falling back to the "…INFORMATION <letter>" phrase in the text (the
    /// D-ATIS closes with "advise you have information X"). Returns an uppercase
    /// single letter, or nil.
    static func infoLetter(code: String?, text: String) -> String? {
        if let code = code?.trimmingCharacters(in: .whitespaces), !code.isEmpty,
           let letter = letter(fromToken: code) {
            return letter
        }
        let upper = text.uppercased()
        return letterAfterKeyword("INFORMATION", in: upper)
            ?? letterAfterKeyword("INFO", in: upper)
    }

    /// Interpret a code token that may be a single letter ("A"), a phonetic word
    /// ("ALPHA"), or a decorated form ("INFO A").
    private static func letter(fromToken token: String) -> String? {
        let t = token.uppercased().trimmingCharacters(in: .whitespaces)
        if t.count == 1, let ch = t.first, ch.isLetter { return String(ch) }
        if let mapped = wordToLetter[t] { return mapped }
        let letters = String(t.filter { $0.isLetter })
        if letters.count == 1 { return letters }
        if let mapped = wordToLetter[letters] { return mapped }
        return nil
    }

    /// The information letter following the last occurrence of `keyword` in the
    /// (uppercased) text — the closing "advise you have information X" wins over any
    /// earlier "…INFORMATION X" mention.
    private static func letterAfterKeyword(_ keyword: String, in upper: String) -> String? {
        let words = upper.split { !$0.isLetter }.map(String.init).filter { !$0.isEmpty }
        guard let idx = words.lastIndex(of: keyword), idx + 1 < words.count else { return nil }
        let next = words[idx + 1]
        if let mapped = wordToLetter[next] { return mapped }
        if next.count == 1, let ch = next.first, ch.isLetter { return String(ch) }
        return nil
    }
}
