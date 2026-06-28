import Foundation

/// Pure, deterministic aviation phonetics. No randomness, no AI.
/// These functions are unit-tested and used by `PhraseologyEngine`.
enum Phonetic {

    static let digitWords: [Character: String] = [
        "0": "zero", "1": "one", "2": "two", "3": "three", "4": "four",
        "5": "five", "6": "six", "7": "seven", "8": "eight", "9": "niner"
    ]

    static let letterWords: [Character: String] = [
        "A": "Alpha", "B": "Bravo", "C": "Charlie", "D": "Delta", "E": "Echo",
        "F": "Foxtrot", "G": "Golf", "H": "Hotel", "I": "India", "J": "Juliett",
        "K": "Kilo", "L": "Lima", "M": "Mike", "N": "November", "O": "Oscar",
        "P": "Papa", "Q": "Quebec", "R": "Romeo", "S": "Sierra", "T": "Tango",
        "U": "Uniform", "V": "Victor", "W": "Whiskey", "X": "Xray",
        "Y": "Yankee", "Z": "Zulu"
    ]

    /// Speak each digit individually: "4271" -> "four two seven one".
    static func spellDigits(_ s: String) -> String {
        s.compactMap { digitWords[$0] }.joined(separator: " ")
    }

    /// Spell a mixed token letter-by-letter / digit-by-digit (taxiway "A11" -> "Alpha one one").
    static func spellToken(_ s: String) -> String {
        s.uppercased().compactMap { ch -> String? in
            if let d = digitWords[ch] { return d }
            if let l = letterWords[ch] { return l }
            return nil
        }.joined(separator: " ")
    }

    /// Group integer below 100 into natural English ("34" -> "thirty four", "8" -> "eight").
    static func twoDigitGroup(_ n: Int) -> String {
        let ones = ["zero", "one", "two", "three", "four", "five",
                    "six", "seven", "eight", "niner"]
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
    static func altitude(_ feet: Int, transitionAltitude: Int = 18000) -> String {
        guard feet > 0 else { return "field elevation" }
        if feet >= transitionAltitude {
            let fl = feet / 100
            return "flight level " + spellDigits(String(fl))
        }
        let thousands = feet / 1000
        let hundreds = (feet % 1000) / 100
        var parts: [String] = []
        if thousands > 0 {
            // ATC spells the thousands digits: 11,000 -> "one one thousand"
            parts.append(spellDigits(String(thousands)) + " thousand")
        }
        if hundreds > 0 {
            parts.append(spellDigits(String(hundreds)) + " hundred")
        }
        if parts.isEmpty {
            // sub-100 ft, e.g. pattern altitude rounding
            return spellDigits(String(feet))
        }
        return parts.joined(separator: " ")
    }

    /// Heading: 270 -> "two seven zero" (always 3 digits).
    static func heading(_ deg: Int) -> String {
        let normalized = ((deg % 360) + 360) % 360
        let padded = String(format: "%03d", normalized)
        return spellDigits(padded)
    }

    /// Frequency: 118.300 -> "one one eight point three", 124.875 -> "one two four point eight seven five".
    static func frequency(_ mhz: Double) -> String {
        // Format to up to 3 decimal places, then trim trailing zeros (keep at least one).
        var s = String(format: "%.3f", mhz)
        while s.hasSuffix("0") && !s.hasSuffix(".0") { s.removeLast() }
        let parts = s.split(separator: ".")
        let whole = spellDigits(String(parts[0]))
        guard parts.count > 1 else { return whole }
        let frac = spellDigits(String(parts[1]))
        return "\(whole) point \(frac)"
    }

    /// Runway: "17R" -> "one seven right", "04L" -> "zero four left", "09" -> "zero niner".
    static func runway(_ raw: String) -> String {
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
        var result = spellDigits(digits)
        switch suffix {
        case "L": result += " left"
        case "R": result += " right"
        case "C": result += " center"
        default: break
        }
        return result
    }

    /// Wind: dir 330 / speed 12 -> "wind three three zero at one two".
    static func wind(direction: Int, speed: Int, gust: Int? = nil) -> String {
        if direction == 0 && speed == 0 { return "wind calm" }
        let dir = direction == 0 ? "variable" : spellDigits(String(format: "%03d", ((direction % 360) + 360) % 360))
        var s = "wind \(dir) at \(spellDigits(String(speed)))"
        if let gust, gust > speed {
            s += " gusting \(spellDigits(String(gust)))"
        }
        return s
    }

    /// Squawk: 4271 -> "squawk four two seven one".
    static func squawk(_ code: String) -> String {
        "squawk " + spellDigits(code)
    }

    /// Visibility in statute miles: 10 -> "one zero", 3 -> "three".
    static func visibility(_ sm: Int) -> String {
        spellDigits(String(sm))
    }

    /// Altimeter: 30.12 -> "three zero one two".
    static func altimeter(_ inHg: Double) -> String {
        let scaled = Int((inHg * 100).rounded())
        return spellDigits(String(scaled))
    }
}
