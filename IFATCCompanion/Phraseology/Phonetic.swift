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
        "U": "Uniform", "V": "Victor", "W": "Whiskey", "X": "Xray",
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
