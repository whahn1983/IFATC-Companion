import Foundation

/// Deterministic rendering of raw D-ATIS text into forms the app can show and speak.
///
/// The raw D-ATIS text uses aviation notations and abbreviations (RWY, ILS, Zulu
/// time, altimeter groups, digit-by-digit numbers). For the transcript we keep the
/// text essentially verbatim; for text-to-speech we expand the common abbreviations
/// and speak digit groups one digit at a time — which is exactly how a real ATIS is
/// read on the air ("wind three three zero at one zero", "altimeter three zero zero
/// one", "two three five two zulu"). No AI, no invented content: every transform is
/// a fixed rule applied to the published text.
enum ATISPhraseology {

    /// The phonetic word for an information code letter, e.g. "A" -> "Alpha". Used to
    /// build the "…information Alpha" the pilot appends to ATC calls.
    static func phoneticLetter(_ letter: String) -> String {
        let t = letter.uppercased().trimmingCharacters(in: .whitespaces)
        guard let ch = t.first, let word = Phonetic.letterWords[ch] else { return t }
        return word
    }

    /// A cleaned, human-readable version of the raw D-ATIS text for the transcript
    /// (whitespace collapsed; abbreviations left intact — pilots read them fine).
    static func displayText(_ raw: String) -> String {
        collapseWhitespace(raw).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// A TTS-friendly rendering: Zulu time and altimeter groups handled, abbreviations
    /// expanded, and every remaining digit run spoken as individual digits per the
    /// selected phraseology pack ("niner", "tree/fower/fife" under ICAO).
    static func spokenText(_ raw: String, icao: Bool = false) -> String {
        var s = " " + collapseWhitespace(raw).uppercased() + " "

        // Zulu observation time: "2352Z" -> "two three five two zulu".
        s = replacingMatches(in: s, pattern: "\\b(\\d{3,4})Z\\b") { groups in
            Phonetic.spellDigits(groups[1], icao: icao) + " zulu"
        }
        // Compact altimeter form: "A2992" -> "altimeter two niner niner two".
        s = replacingMatches(in: s, pattern: "\\bA(\\d{4})\\b") { groups in
            "altimeter " + Phonetic.spellDigits(groups[1], icao: icao)
        }
        // Runway designators: "24R" -> "two four right", "25L" -> "two five left" (do
        // this before the generic digit rule, which would otherwise leave a bare "R").
        s = replacingMatches(in: s, pattern: "\\b(\\d{1,2})([LRC])\\b") { groups in
            let side = ["L": "left", "R": "right", "C": "center"][groups[2]] ?? groups[2]
            return Phonetic.spellDigits(groups[1], icao: icao) + " " + side
        }
        // Expand the common ATIS abbreviations (word-boundary, so "APPROACHES" and
        // "DEPARTURE" are never clipped by the shorter "APCH"/"DEP" entries).
        for (abbreviation, expansion) in abbreviations {
            let pattern = "\\b" + NSRegularExpression.escapedPattern(for: abbreviation) + "\\b"
            s = replacingMatches(in: s, pattern: pattern) { _ in expansion }
        }
        // Any remaining digit run -> individual spoken digits (authentic ATIS style).
        s = replacingMatches(in: s, pattern: "\\d+") { groups in
            Phonetic.spellDigits(groups[0], icao: icao)
        }
        return collapseWhitespace(s).trimmingCharacters(in: .whitespaces)
    }

    // MARK: - Abbreviation table

    /// Common D-ATIS abbreviations → spoken words. Multi-letter identifiers that
    /// should be spelled on the air (ILS, RNAV, GPS…) expand to space-separated
    /// letters so the synthesizer says "I L S" rather than "ils". Ordered longest-first
    /// where a prefix relationship exists, though `\b` boundaries already keep them
    /// from clipping longer words.
    private static let abbreviations: [(String, String)] = [
        ("RWYS", "runways"), ("RWY", "runway"),
        ("TWYS", "taxiways"), ("TWY", "taxiway"),
        ("APCHS", "approaches"), ("APCH", "approach"), ("APPCH", "approach"),
        ("ILS", "I L S"), ("RNAV", "R NAV"), ("GPS", "G P S"),
        ("VOR", "V O R"), ("DME", "D M E"), ("NDB", "N D B"), ("PRM", "P R M"),
        ("INTL", "international"), ("INTXN", "intersection"),
        ("CLSD", "closed"), ("CTC", "contact"), ("FREQ", "frequency"),
        ("INFO", "information"), ("ADVS", "advise"), ("ADVZ", "advise"),
        ("TEMP", "temperature"), ("WX", "weather"), ("TFC", "traffic"),
        ("DEPG", "departing"), ("ARR", "arrival"), ("DEP", "departure"),
        ("MAINT", "maintain"), ("HDG", "heading"), ("LDG", "landing"),
        ("BRKG", "braking"), ("SFC", "surface"), ("OTS", "out of service")
    ]

    // MARK: - Regex helpers

    private static func collapseWhitespace(_ s: String) -> String {
        s.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
    }

    /// Replace every match of `pattern` in `s` with the result of `transform`, which
    /// receives the match's capture groups (index 0 is the whole match).
    private static func replacingMatches(in s: String, pattern: String,
                                         _ transform: ([String]) -> String) -> String {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return s }
        let ns = s as NSString
        let matches = regex.matches(in: s, range: NSRange(location: 0, length: ns.length))
        guard !matches.isEmpty else { return s }
        var result = ""
        var cursor = 0
        for match in matches {
            result += ns.substring(with: NSRange(location: cursor, length: match.range.location - cursor))
            var groups: [String] = []
            for i in 0..<match.numberOfRanges {
                let r = match.range(at: i)
                groups.append(r.location == NSNotFound ? "" : ns.substring(with: r))
            }
            result += transform(groups)
            cursor = match.range.location + match.range.length
        }
        result += ns.substring(from: cursor)
        return result
    }
}
