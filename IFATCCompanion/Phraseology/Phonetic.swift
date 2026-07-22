import Foundation

/// Pure, deterministic aviation phonetics. No randomness, no AI.
/// These functions are unit-tested and used by `PhraseologyEngine`.
///
/// Every digit-bearing helper accepts an `icao` flag selecting the phraseology
/// pack. FAA (the default) keeps the familiar "three / four / five" digit words;
/// ICAO radiotelephony substitutes "tree / fower / fife" and uses "decimal"
/// instead of "point" for frequencies. Defaulting `icao` to `false` keeps every
/// existing caller and unit test on the FAA pack unchanged.
enum Phonetic {

    static let digitWords: [Character: String] = [
        "0": "zero", "1": "one", "2": "two", "3": "three", "4": "four",
        "5": "five", "6": "six", "7": "seven", "8": "eight", "9": "niner"
    ]

    /// ICAO radiotelephony digit words (ICAO Annex 10): note "tree", "fower",
    /// "fife", "niner". Other digits are spoken as in the FAA set.
    static let icaoDigitWords: [Character: String] = [
        "0": "zero", "1": "one", "2": "two", "3": "tree", "4": "fower",
        "5": "fife", "6": "six", "7": "seven", "8": "eight", "9": "niner"
    ]

    static func digitMap(icao: Bool) -> [Character: String] {
        icao ? icaoDigitWords : digitWords
    }

    static let letterWords: [Character: String] = [
        "A": "Alpha", "B": "Bravo", "C": "Charlie", "D": "Delta", "E": "Echo",
        "F": "Foxtrot", "G": "Golf", "H": "Hotel", "I": "India", "J": "Juliett",
        "K": "Kilo", "L": "Lima", "M": "Mike", "N": "November", "O": "Oscar",
        "P": "Papa", "Q": "Quebec", "R": "Romeo", "S": "Sierra", "T": "Tango",
        "U": "Uniform", "V": "Victor", "W": "Whiskey", "X": "X-ray",
        "Y": "Yankee", "Z": "Zulu"
    ]

    /// Speak each digit individually: "4271" -> "four two seven one".
    static func spellDigits(_ s: String, icao: Bool = false) -> String {
        let map = digitMap(icao: icao)
        return s.compactMap { map[$0] }.joined(separator: " ")
    }

    /// Spell a mixed token letter-by-letter / digit-by-digit (taxiway "A11" -> "Alpha one one").
    static func spellToken(_ s: String, icao: Bool = false) -> String {
        let map = digitMap(icao: icao)
        return s.uppercased().compactMap { ch -> String? in
            if let d = map[ch] { return d }
            if let l = letterWords[ch] { return l }
            return nil
        }.joined(separator: " ")
    }

    /// Group integer below 100 into natural English ("34" -> "thirty four", "8" -> "eight").
    static func twoDigitGroup(_ n: Int, icao: Bool = false) -> String {
        let ones = icao
            ? ["zero", "one", "two", "tree", "fower", "fife", "six", "seven", "eight", "niner"]
            : ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "niner"]
        let teens = ["ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
                     "sixteen", "seventeen", "eighteen", "nineteen"]
        let tens = ["", "", "twenty", "thirty", "forty", "fifty",
                    "sixty", "seventy", "eighty", "ninety"]
        if n < 10 { return ones[n] }
        if n < 20 { return teens[n - 10] }
        let t = n / 10, o = n % 10
        return o == 0 ? tens[t] : "\(tens[t]) \(ones[o])"
    }

    /// Pronounce an altitude in feet per ATC convention.
    /// 10,000 -> "one zero thousand", 37,000 -> "flight level three seven zero",
    /// 2,500 -> "two thousand five hundred".
    static func altitude(_ feet: Int, transitionAltitude: Int = 18000, icao: Bool = false) -> String {
        guard feet > 0 else { return "field elevation" }
        if feet >= transitionAltitude {
            let fl = feet / 100
            return "flight level " + spellDigits(String(fl), icao: icao)
        }
        let thousands = feet / 1000
        let hundreds = (feet % 1000) / 100
        var parts: [String] = []
        if thousands > 0 {
            // ATC spells the thousands digits: 11,000 -> "one one thousand"
            parts.append(spellDigits(String(thousands), icao: icao) + " thousand")
        }
        if hundreds > 0 {
            parts.append(spellDigits(String(hundreds), icao: icao) + " hundred")
        }
        if parts.isEmpty {
            // sub-100 ft, e.g. pattern altitude rounding
            return spellDigits(String(feet), icao: icao)
        }
        return parts.joined(separator: " ")
    }

    /// Heading: 270 -> "two seven zero" (always 3 digits).
    static func heading(_ deg: Int, icao: Bool = false) -> String {
        let normalized = ((deg % 360) + 360) % 360
        let padded = String(format: "%03d", normalized)
        return spellDigits(padded, icao: icao)
    }

    /// Frequency: 118.300 -> "one one eight point three" (FAA) /
    /// "one one eight decimal three" (ICAO).
    static func frequency(_ mhz: Double, icao: Bool = false) -> String {
        // Format to up to 3 decimal places, then trim trailing zeros (keep at least one).
        var s = String(format: "%.3f", mhz)
        while s.hasSuffix("0") && !s.hasSuffix(".0") { s.removeLast() }
        let parts = s.split(separator: ".")
        let whole = spellDigits(String(parts[0]), icao: icao)
        guard parts.count > 1 else { return whole }
        let frac = spellDigits(String(parts[1]), icao: icao)
        let separator = icao ? "decimal" : "point"
        return "\(whole) \(separator) \(frac)"
    }

    /// Runway: "17R" -> "one seven right", "04L" -> "zero four left", "09" -> "zero niner".
    static func runway(_ raw: String, icao: Bool = false) -> String {
        let upper = raw.uppercased().trimmingCharacters(in: .whitespaces)
        var digits = ""
        var suffix = ""
        for ch in upper {
            if ch.isNumber { digits.append(ch) }
            else if ch == "L" || ch == "R" || ch == "C" { suffix = String(ch) }
        }
        guard !digits.isEmpty else { return raw }
        // Pad single-digit runways to two digits per convention (9 -> 09).
        if digits.count == 1 { digits = "0" + digits }
        var result = spellDigits(digits, icao: icao)
        switch suffix {
        case "L": result += " left"
        case "R": result += " right"
        case "C": result += " center"
        default: break
        }
        return result
    }

    // MARK: - Runway direction pairs

    /// Split a runway ident into its number (1...36) and side suffix. "24L" -> (24, "L"),
    /// "06R" -> (6, "R"), "36" -> (36, ""). The number is nil when the ident carries no
    /// usable runway number.
    private static func runwayComponents(_ raw: String) -> (number: Int?, suffix: String) {
        let upper = raw.uppercased().trimmingCharacters(in: .whitespaces)
        var digits = ""
        var suffix = ""
        for ch in upper {
            if ch.isNumber { digits.append(ch) }
            else if ch == "L" || ch == "R" || ch == "C" { suffix = String(ch) }
        }
        guard let n = Int(digits), n >= 1, n <= 36 else { return (nil, suffix) }
        return (n, suffix)
    }

    /// The reciprocal runway ident — the opposite end of the same physical runway:
    /// "24L" -> "6R", "06R" -> "24L", "36" -> "18", "09" -> "27", "13C" -> "31C".
    /// Returns nil when `raw` carries no usable runway number.
    static func reciprocalRunway(_ raw: String) -> String? {
        let (number, suffix) = runwayComponents(raw)
        guard let number else { return nil }
        let reciprocalNumber = number <= 18 ? number + 18 : number - 18
        let reciprocalSuffix: String
        switch suffix {
        case "L": reciprocalSuffix = "R"
        case "R": reciprocalSuffix = "L"
        default:  reciprocalSuffix = suffix   // "C" stays center; a bare number stays bare
        }
        return "\(reciprocalNumber)\(reciprocalSuffix)"
    }

    /// Both physical ends of a runway, ordered lower-number-first: "24L" -> ("6R", "24L"),
    /// "36" -> ("18", "36"). Nil when no reciprocal can be derived.
    private static func orderedRunwayEnds(_ raw: String) -> (low: String, high: String)? {
        let (number, suffix) = runwayComponents(raw)
        guard let number, let reciprocal = reciprocalRunway(raw) else { return nil }
        let end = "\(number)\(suffix)"
        let reciprocalNumber = number <= 18 ? number + 18 : number - 18
        return number <= reciprocalNumber ? (end, reciprocal) : (reciprocal, end)
    }

    /// Both physical directions of a runway as a written designation, lower number first:
    /// "24L" -> "6R-24L", "06R" -> "6R-24L", "36" -> "18-36", "09" -> "9-27". Falls back to
    /// the trimmed single ident when no reciprocal can be derived.
    static func runwayPairDisplay(_ raw: String) -> String {
        guard let ends = orderedRunwayEnds(raw) else {
            return raw.uppercased().trimmingCharacters(in: .whitespaces)
        }
        return "\(ends.low)-\(ends.high)"
    }

    /// Speak a single runway end without the two-digit padding `runway` applies, so both
    /// ends of a pair read naturally: "6R" -> "six right", "24L" -> "two four left".
    private static func spokenRunwayEnd(_ end: String, icao: Bool) -> String {
        let (number, suffix) = runwayComponents(end)
        guard let number else { return runway(end, icao: icao) }
        var result = spellDigits(String(number), icao: icao)
        switch suffix {
        case "L": result += " left"
        case "R": result += " right"
        case "C": result += " center"
        default: break
        }
        return result
    }

    /// Both physical directions of a runway spoken end-to-end, lower number first:
    /// "24L" -> "six right two four left", "36" -> "one eight three six". Falls back to the
    /// single-runway phonetics when no reciprocal can be derived.
    static func runwayPairSpoken(_ raw: String, icao: Bool = false) -> String {
        guard let ends = orderedRunwayEnds(raw) else {
            return runway(raw, icao: icao)
        }
        return "\(spokenRunwayEnd(ends.low, icao: icao)) \(spokenRunwayEnd(ends.high, icao: icao))"
    }

    /// Wind: dir 330 / speed 12 -> "wind three three zero at one two".
    static func wind(direction: Int, speed: Int, gust: Int? = nil, icao: Bool = false) -> String {
        if direction == 0 && speed == 0 { return "wind calm" }
        let dir = direction == 0 ? "variable" : spellDigits(String(format: "%03d", ((direction % 360) + 360) % 360), icao: icao)
        var s = "wind \(dir) at \(spellDigits(String(speed), icao: icao))"
        if let gust, gust > speed {
            s += " gusting \(spellDigits(String(gust), icao: icao))"
        }
        return s
    }

    /// Squawk: 4271 -> "squawk four two seven one".
    static func squawk(_ code: String, icao: Bool = false) -> String {
        "squawk " + spellDigits(code, icao: icao)
    }

    /// Visibility in statute miles: 10 -> "one zero", 3 -> "three".
    static func visibility(_ sm: Int, icao: Bool = false) -> String {
        spellDigits(String(sm), icao: icao)
    }

    /// Altimeter: FAA reports inHg ("altimeter three zero one two"); ICAO reports
    /// QNH in whole hectopascals ("QNH one zero one three"). Use `altimeterSetting`
    /// for the leading keyword + value combined.
    static func altimeter(_ inHg: Double, icao: Bool = false) -> String {
        let scaled = Int((inHg * 100).rounded())
        return spellDigits(String(scaled), icao: icao)
    }

    /// Full altimeter/QNH phrase including the keyword, selected by pack.
    static func altimeterSetting(inHg: Double, icao: Bool = false) -> String {
        if icao {
            let hpa = Int((inHg * 33.8638866667).rounded())
            return "QNH " + spellDigits(String(hpa), icao: icao)
        }
        return "altimeter " + altimeter(inHg, icao: icao)
    }
}
