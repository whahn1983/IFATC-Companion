import Foundation

/// Deterministic rendering of raw D-ATIS text into forms the app can show and speak.
///
/// The raw D-ATIS text (from the FAA feed) is a mostly plain-language message with an
/// **embedded coded observation** — the same groups you see in a METAR: wind
/// (`25012KT`), visibility (`10SM`), sky cover (`FEW015 OVC250`), weather (`-RA BR`),
/// temperature/dewpoint (`07/M02`) and altimeter (`A2992`) — followed by the runways,
/// approaches and NOTAMs in abbreviated English (`ILS RWY 24R APCH IN USE`, `DEPG RWY
/// 25R`, `TWY B CLSD`).
///
/// For the transcript we keep the text essentially verbatim; for text-to-speech we
/// decode every coded group into the way a real ATIS voice reads it on the air
/// ("wind two five zero at one two", "visibility one zero", "few clouds at one
/// thousand five hundred", "temperature seven, dewpoint minus two", "altimeter two
/// niner niner two"), expand the common abbreviations, and speak any remaining digit
/// run one digit at a time. No AI, no invented content: every transform is a fixed
/// rule applied to the published text.
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

    /// A TTS-friendly rendering: the embedded coded observation decoded to spoken
    /// phraseology, abbreviations expanded, and every remaining digit run spoken as
    /// individual digits per the selected phraseology pack ("niner", "tree/fower/fife"
    /// under ICAO).
    static func spokenText(_ raw: String, icao: Bool = false) -> String {
        var s = " " + collapseWhitespace(raw).uppercased() + " "

        // Strip the spelled-out readback the FAA appends after the altimeter, e.g.
        // "A2992 (TWO NINER NINER TWO)" — the coded group is decoded below, so the
        // parenthetical would otherwise be spoken twice. Only parentheticals made up
        // entirely of number words are removed; "(GPS)", "(CLSD)" etc. are preserved.
        s = replacingMatches(in: s, pattern: "\\(([A-Z0-9 ]*)\\)") { g in
            isNumberReadback(g[1]) ? " " : "(" + g[1] + ")"
        }

        // Drop the coded METAR remarks group ("RMK AO2 SLP224 T00331122 …") up to the
        // end of its sentence — it is dense station coding no ATIS voice reads aloud.
        s = replacingMatches(in: s, pattern: "\\bRMK\\b[^.]*") { _ in " " }

        // Information code letter → phonetic word: "INFO S" / "INFORMATION S" ->
        // "information Sierra" (header and the closing "…ADVS YOU HAVE INFO S").
        s = replacingMatches(in: s, pattern: "\\b(?:INFORMATION|INFO)\\s+([A-Z])\\b") { g in
            "information " + phoneticLetter(g[1])
        }

        // Zulu observation time. Two published forms: "2352Z" (HHMMZ) and the day-stamped
        // "042252" (DDHHMM, no Z). For the day-stamped form only the time is spoken.
        s = replacingMatches(in: s, pattern: "\\b(\\d{3,4})Z\\b") { g in
            Phonetic.spellDigits(g[1], icao: icao) + " zulu"
        }
        s = replacingMatches(in: s, pattern: "\\b(\\d{6})\\b") { g in
            guard let hhmm = dayStampedTime(g[1]) else { return g[1] }
            return Phonetic.spellDigits(hhmm, icao: icao) + " zulu"
        }

        // Altimeter (inHg): "A2992" -> "altimeter two niner niner two".
        s = replacingMatches(in: s, pattern: "\\bA(\\d{4})\\b") { g in
            "altimeter " + Phonetic.spellDigits(g[1], icao: icao)
        }
        // QNH (hectopascals): "Q1013" -> "Q N H one zero one three".
        s = replacingMatches(in: s, pattern: "\\bQ(\\d{4})\\b") { g in
            "Q N H " + Phonetic.spellDigits(g[1], icao: icao)
        }

        // Wind: "00000KT" (calm), "VRB05KT" (variable), "25012KT", "25012G30KT", and
        // the metric "…MPS" forms.
        s = replacingMatches(in: s, pattern: "\\b(00000|VRB\\d{2,3}|\\d{5,6})(G\\d{2,3})?(KT|MPS)\\b") { g in
            spokenWind(body: g[1], gust: g[2], unit: g[3], icao: icao)
        }
        // Variable wind-direction range: "210V280" -> "variable between two one zero and
        // two eight zero".
        s = replacingMatches(in: s, pattern: "\\b(\\d{3})V(\\d{3})\\b") { g in
            "variable between " + Phonetic.spellDigits(g[1], icao: icao)
                + " and " + Phonetic.spellDigits(g[2], icao: icao)
        }

        // Visibility (statute miles): greater-than, less-than, mixed fraction, fraction,
        // then whole. Order matters — the more specific fraction forms first.
        s = replacingMatches(in: s, pattern: "\\bP(\\d{1,2})SM\\b") { g in
            "visibility more than " + Phonetic.spellDigits(g[1], icao: icao)
        }
        s = replacingMatches(in: s, pattern: "\\bM(\\d{1,2})/(\\d{1,2})SM\\b") { g in
            "visibility less than " + spokenFraction(g[1], g[2])
        }
        s = replacingMatches(in: s, pattern: "\\b(\\d{1,2}) (\\d{1,2})/(\\d{1,2})SM\\b") { g in
            "visibility " + Phonetic.spellDigits(g[1], icao: icao) + " and " + spokenFraction(g[2], g[3])
        }
        s = replacingMatches(in: s, pattern: "\\b(\\d{1,2})/(\\d{1,2})SM\\b") { g in
            "visibility " + spokenFraction(g[1], g[2])
        }
        s = replacingMatches(in: s, pattern: "\\b(\\d{1,3})SM\\b") { g in
            "visibility " + Phonetic.spellDigits(g[1], icao: icao)
        }

        // RVR: "R28L/2400FT", "R06/2000V3000FT", "R28L/P6000FT", "R28L/M0600FT".
        s = replacingMatches(in: s,
                             pattern: "\\bR(\\d{2}[LRC]?)/([MP]?)(\\d{3,4})(?:V([MP]?)(\\d{3,4}))?FT\\b") { g in
            spokenRVR(runway: g[1], p1: g[2], v1: g[3], p2: g[4], v2: g[5], icao: icao)
        }

        // Temperature / dewpoint: "07/M02", "19/13", "M05/M10", "04/-09" — the negative
        // sign appears as either the METAR "M" prefix or a literal minus in real SWIM text.
        s = replacingMatches(in: s, pattern: "\\b([M-]?\\d{2})/([M-]?\\d{2})\\b") { g in
            "temperature " + spokenTemp(g[1], icao: icao) + ", dewpoint " + spokenTemp(g[2], icao: icao)
        }

        // Sky cover with cloud base: "FEW015" -> "few clouds at one thousand five
        // hundred", "OVC008" -> "eight hundred overcast", "VV002" -> "indefinite ceiling
        // two hundred". Optional CB / TCU cloud type is spoken too.
        s = replacingMatches(in: s, pattern: "\\b(FEW|SCT|BKN|OVC|VV)(\\d{3})(CB|TCU)?\\b") { g in
            spokenCloud(cover: g[1], hundreds: g[2], type: g[3], icao: icao)
        }

        // Weather phenomena groups: "-RA", "+TSRA", "VCSH", "FZFG", "BR". Bounded by
        // delimiters and matched only against the real METAR weather codes, so plain
        // words ("RWY", "INFO", "GROUND") are never mistaken for weather.
        s = replacingMatches(in: s,
                             pattern: "(?<=[\\s,(/])([+\\-]?(?:VC)?(?:" + weatherCodePattern + ")+)(?=[\\s,.)/])") { g in
            decodeWeather(g[1]) ?? g[1]
        }

        // Frequencies embedded in the text: "127.05" -> "one two seven point zero five".
        s = replacingMatches(in: s, pattern: "\\b(\\d{2,3})\\.(\\d{1,3})\\b") { g in
            let sep = icao ? "decimal" : "point"
            return Phonetic.spellDigits(g[1], icao: icao) + " " + sep + " " + Phonetic.spellDigits(g[2], icao: icao)
        }

        // Runway designators: "24R" -> "two four right", "25L" -> "two five left" (before
        // the generic digit rule, which would otherwise leave a bare "R").
        s = replacingMatches(in: s, pattern: "\\b(\\d{1,2})([LRC])\\b") { g in
            let side = ["L": "left", "R": "right", "C": "center"][g[2]] ?? g[2]
            return Phonetic.spellDigits(g[1], icao: icao) + " " + side
        }

        // Approach-variant single letters → phonetic words: "RNAV Z" -> "RNAV Zulu",
        // "ILS Z RWY 4L" -> "ILS Zulu runway…". Scoped to these keywords so a stray
        // compass "N"/"S" is never turned into a phonetic word. The keyword is kept for the
        // abbreviation pass below to expand.
        s = replacingMatches(in: s, pattern: "\\b(ILS|RNAV|RNP|LOC|VOR|LDA|SDF)\\s+([A-Z])\\b") { g in
            g[1] + " " + phoneticLetter(g[2])
        }
        // Taxiway identifiers → phonetic words. A taxiway ident is one or two letters with
        // an optional trailing number ("B", "SB", "B4"), so a multi-letter ident is spelled
        // phonetically ("TWY SB" -> "taxiway Sierra Bravo", "TWY B4" -> "taxiway Bravo four")
        // rather than left as bare letters the synthesizer reads as "S B". The one/two-letter
        // bound keeps a following abbreviation word (e.g. "CLSD") from being swallowed. A
        // two-letter, digit-less token that is a common word / abbreviation (e.g. "TWYS IN
        // USE", "TWY SW OF …") is left alone for the word/abbreviation passes below. The
        // keyword is kept for the abbreviation pass to expand.
        s = replacingMatches(in: s, pattern: "\\b(TWYS|TWY|TAXIWAY|TAXIWAYS|TY)\\s+([A-Z]{1,2}\\d{0,2})\\b") { g in
            let ident = g[2]
            if ident.count == 2, ident.allSatisfy(\.isLetter), nonTaxiwayTokens.contains(ident) {
                return g[1] + " " + ident
            }
            return g[1] + " " + Phonetic.spellToken(ident, icao: icao)
        }

        // Expand the common ATIS abbreviations (word-boundary, so "APPROACHES" and
        // "DEPARTURE" are never clipped by the shorter "APCH"/"DEP" entries).
        for (abbreviation, expansion) in abbreviations {
            let pattern = "\\b" + NSRegularExpression.escapedPattern(for: abbreviation) + "\\b"
            s = replacingMatches(in: s, pattern: pattern) { _ in expansion }
        }

        // Any remaining digit run -> individual spoken digits (authentic ATIS style).
        s = replacingMatches(in: s, pattern: "\\d+") { g in
            Phonetic.spellDigits(g[0], icao: icao)
        }

        // Drop any stray parentheses so TTS doesn't stumble over them.
        s = s.replacingOccurrences(of: "(", with: " ").replacingOccurrences(of: ")", with: " ")
        return collapseWhitespace(s).trimmingCharacters(in: .whitespaces)
    }

    // MARK: - Coded-group renderers

    /// Speak a coded wind group body ("00000", "VRB05", "25012", "090103") with an
    /// optional gust ("G30") and unit ("KT"/"MPS").
    static func spokenWind(body: String, gust: String, unit: String, icao: Bool) -> String {
        let unitSuffix = unit == "MPS" ? " meters per second" : ""
        let gustPhrase = gust.hasPrefix("G") ? " gusts " + spellCount(String(gust.dropFirst()), icao: icao) : ""
        if body == "00000" { return "wind calm" }
        if body.hasPrefix("VRB") {
            return "wind variable at " + spellCount(String(body.dropFirst(3)), icao: icao) + gustPhrase + unitSuffix
        }
        let dir = String(body.prefix(3))
        let speed = String(body.dropFirst(3))
        return "wind " + Phonetic.spellDigits(dir, icao: icao) + " at "
            + spellCount(speed, icao: icao) + gustPhrase + unitSuffix
    }

    /// Speak a temperature/dewpoint field ("07" -> "seven", "M02" -> "minus two",
    /// "19" -> "one niner"). Leading zeros are dropped, then digits are spoken
    /// individually as on the air.
    static func spokenTemp(_ s: String, icao: Bool) -> String {
        var d = s
        var negative = false
        if d.hasPrefix("M") || d.hasPrefix("-") { negative = true; d.removeFirst() }
        let magnitude = spellCount(d, icao: icao)
        return negative ? "minus " + magnitude : magnitude
    }

    /// Speak a sky-cover group the way an ATIS voice reads it (per the vATIS templates):
    /// "few clouds at {h}", "{h} scattered", "{h} broken", "{h} overcast", "indefinite
    /// ceiling {h}", with an optional cumulonimbus / towering-cumulus type appended.
    static func spokenCloud(cover: String, hundreds: String, type: String, icao: Bool) -> String {
        let feet = (Int(hundreds) ?? 0) * 100
        let height = spokenHeight(feet, icao: icao)
        let typeWord: String
        switch type {
        case "CB": typeWord = " cumulonimbus"
        case "TCU": typeWord = " towering cumulus"
        default: typeWord = ""
        }
        switch cover {
        case "FEW": return "few clouds at " + height + typeWord
        case "SCT": return height + " scattered" + typeWord
        case "BKN": return height + " broken" + typeWord
        case "OVC": return height + " overcast" + typeWord
        case "VV":  return "indefinite ceiling " + height
        default:    return cover + " " + height
        }
    }

    /// Render a height in feet the way ATC speaks cloud bases / vertical visibility:
    /// 800 -> "eight hundred", 1500 -> "one thousand five hundred", 25000 -> "two five
    /// thousand". (Unlike `Phonetic.altitude`, never a flight level — cloud bases are
    /// always read in plain feet.)
    static func spokenHeight(_ feet: Int, icao: Bool) -> String {
        let thousands = feet / 1000
        let hundreds = (feet % 1000) / 100
        var parts: [String] = []
        if thousands > 0 { parts.append(Phonetic.spellDigits(String(thousands), icao: icao) + " thousand") }
        if hundreds > 0 { parts.append(Phonetic.spellDigits(String(hundreds), icao: icao) + " hundred") }
        return parts.isEmpty ? Phonetic.spellDigits("0", icao: icao) : parts.joined(separator: " ")
    }

    /// Render a visibility fraction like "1/2" -> "one half", "3/4" -> "three quarters",
    /// "5/8" -> "five eighths". Unknown denominators fall back to "<n> over <d>".
    static func spokenFraction(_ numStr: String, _ denStr: String) -> String {
        guard let num = Int(numStr), let den = Int(denStr) else { return numStr + " over " + denStr }
        let unit: String
        switch den {
        case 2:  unit = "half"
        case 4:  unit = "quarter"
        case 8:  unit = "eighth"
        case 16: unit = "sixteenth"
        default: return Phonetic.twoDigitGroup(num) + " over " + Phonetic.twoDigitGroup(den)
        }
        let word = Phonetic.twoDigitGroup(num)
        return num == 1 ? word + " " + unit : word + " " + unit + "s"
    }

    /// Speak an RVR group: "R28L/2400FT" -> "runway two eight left R V R two thousand
    /// four hundred", with M/P (less/more than) and V (variable range) handled.
    static func spokenRVR(runway: String, p1: String, v1: String, p2: String, v2: String, icao: Bool) -> String {
        func prefixWord(_ p: String) -> String {
            p == "M" ? "less than " : (p == "P" ? "more than " : "")
        }
        let base = "runway " + Phonetic.runway(runway, icao: icao) + " R V R "
        if !v2.isEmpty {
            // Variable range: "RVR variable {low} to {high}".
            return base + "variable " + prefixWord(p1) + spokenHeight(Int(v1) ?? 0, icao: icao)
                + " to " + prefixWord(p2) + spokenHeight(Int(v2) ?? 0, icao: icao)
        }
        return base + prefixWord(p1) + spokenHeight(Int(v1) ?? 0, icao: icao)
    }

    // MARK: - Weather phenomena

    /// Descriptor codes (spoken before the phenomenon). `TS` reads standalone
    /// ("thunderstorm"); the rest only qualify a following phenomenon.
    private static let descriptorWords: [String: String] = [
        "TS": "thunderstorm", "FZ": "freezing", "MI": "shallow", "PR": "partial",
        "BC": "patches of", "DR": "low drifting", "BL": "blowing"
    ]

    /// Precipitation / obscuration / other phenomena codes.
    private static let phenomenaWords: [String: String] = [
        "DZ": "drizzle", "RA": "rain", "SN": "snow", "SG": "snow grains",
        "IC": "ice crystals", "PL": "ice pellets", "GR": "hail", "GS": "small hail",
        "BR": "mist", "FG": "fog", "FU": "smoke", "VA": "volcanic ash",
        "DU": "dust", "SA": "sand", "HZ": "haze", "PY": "spray",
        "PO": "dust whirls", "SQ": "squalls", "FC": "funnel cloud",
        "SS": "sandstorm", "DS": "duststorm"
    ]

    /// Alternation of every recognised two-letter weather code, longest matches first is
    /// irrelevant since all are two characters. Excludes `UP` (unknown precipitation) so
    /// the English word "UP" in NOTAM text is never mistaken for weather.
    private static let weatherCodePattern: String = {
        let codes = Array(descriptorWords.keys) + Array(phenomenaWords.keys) + ["SH"]
        return codes.joined(separator: "|")
    }()

    /// Decode a full weather group (already stripped of surrounding delimiters) such as
    /// "+TSRA", "VCSH", "-SHRA", "FZFG", "BR". Returns nil when the token isn't a valid
    /// weather group (e.g. a lone descriptor like "BC"), so the caller leaves it intact.
    static func decodeWeather(_ raw: String) -> String? {
        var body = raw
        var intensity: String? = nil
        if body.hasPrefix("+") { intensity = "heavy"; body.removeFirst() }
        else if body.hasPrefix("-") { intensity = "light"; body.removeFirst() }
        var vicinity = false
        if body.hasPrefix("VC") { vicinity = true; body.removeFirst(2) }
        guard !body.isEmpty, body.count % 2 == 0 else { return nil }

        var codes: [String] = []
        var i = body.startIndex
        while i < body.endIndex {
            let j = body.index(i, offsetBy: 2)
            codes.append(String(body[i..<j]))
            i = j
        }
        guard codes.allSatisfy({ $0 == "SH" || descriptorWords[$0] != nil || phenomenaWords[$0] != nil }) else {
            return nil
        }
        // A bare "GS" is far more often "glideslope" (e.g. "GS OTS") than small hail, which
        // in practice always carries intensity or another code ("-GS", "SHGS"). Leave a
        // lone, unqualified GS for the abbreviation pass.
        if codes == ["GS"], intensity == nil, !vicinity { return nil }

        // A thunderstorm carries its own name; any intensity belongs to the precipitation
        // that comes with it ("+TSRA" -> "thunderstorm with heavy rain"), so pull TS out
        // and build the precipitation phrase from the remaining codes.
        let hasThunderstorm = codes.contains("TS")
        var words: [String] = []
        var showers = false
        var hasPhenomenon = false
        for code in codes where code != "TS" {
            if code == "SH" { showers = true }
            else if let d = descriptorWords[code] { words.append(d) }
            else if let p = phenomenaWords[code] { words.append(p); hasPhenomenon = true }
        }
        // A lone qualifying descriptor ("BC", "FZ", "BL"…) with nothing to qualify isn't
        // a weather report here — leave it for the abbreviation pass.
        guard hasThunderstorm || hasPhenomenon || showers || intensity != nil || vicinity else {
            return nil
        }

        if showers { words.append("showers") }
        var precip = words.joined(separator: " ")
        if let intensity, !precip.isEmpty { precip = intensity + " " + precip }

        var phrase: String
        if hasThunderstorm {
            phrase = precip.isEmpty ? "thunderstorm" : "thunderstorm with " + precip
        } else {
            phrase = precip
        }
        if vicinity { phrase += " in the vicinity" }
        return phrase.trimmingCharacters(in: .whitespaces)
    }

    /// Interpret a 6-digit day-stamped observation stamp ("042252" = day 04, 2252Z),
    /// returning the "HHMM" time portion, or nil when the digits aren't a valid stamp
    /// (so an unrelated 6-digit run is left untouched).
    private static func dayStampedTime(_ s: String) -> String? {
        guard s.count == 6, s.allSatisfy(\.isNumber),
              let day = Int(s.prefix(2)), (1...31).contains(day),
              let hour = Int(s.dropFirst(2).prefix(2)), (0...23).contains(hour),
              let minute = Int(s.dropFirst(4).prefix(2)), (0...59).contains(minute) else { return nil }
        return String(s.suffix(4))
    }

    /// Two-letter uppercase tokens that can immediately follow "TWY"/"TWYS" in coded ATIS
    /// text but are **not** taxiway identifiers — common English words ("IN USE", "TO", "AT")
    /// and abbreviations/compass points that have their own expansion ("SW", "HS", "WS").
    /// These are left for the word/abbreviation passes instead of being spelled phonetically.
    private static let nonTaxiwayTokens: Set<String> = [
        "IN", "TO", "AT", "IS", "OR", "ON", "OF", "BY", "UP", "NO", "AS", "IT", "AN", "BE",
        "NE", "NW", "SE", "SW", "HS", "WS", "MU", "GS", "BA", "FT", "WX", "OM", "MM", "IM"
    ]

    // MARK: - Abbreviation table

    /// Common D-ATIS abbreviations → spoken words. Multi-letter identifiers that should
    /// be spelled on the air (ILS, RNAV, GPS…) expand to space-separated letters so the
    /// synthesizer says "I L S" rather than "ils". `\b` boundaries keep a short entry
    /// from clipping a longer word.
    private static let abbreviations: [(String, String)] = [
        ("RWYS", "runways"), ("RWY", "runway"), ("RY", "runway"), ("RWYCC", "runway condition code"),
        ("TWYS", "taxiways"), ("TWY", "taxiway"), ("TY", "taxiway"),
        ("APCHS", "approaches"), ("APCH", "approach"), ("APPCH", "approach"),
        ("APPR", "approach"), ("APPS", "approaches"), ("APP", "approach"), ("APPCHS", "approaches"),
        ("DEPS", "departures"), ("DEPG", "departing"), ("DEPTG", "departing"), ("DPTG", "departing"),
        ("DEPTURE", "departure"), ("DEP", "departure"),
        ("ARRS", "arrivals"), ("ARR", "arrival"),
        ("LDG", "landing"), ("LNDG", "landing"), ("TKOF", "takeoff"), ("TKOFF", "takeoff"),
        ("ILS", "I L S"), ("LOC", "localizer"), ("RNAV", "R NAV"), ("RNP", "R N P"), ("GPS", "G P S"),
        ("VOR", "V O R"), ("DME", "D M E"), ("NDB", "N D B"), ("PRM", "P R M"),
        ("LDA", "L D A"), ("SDF", "S D F"), ("BC", "back course"),
        // In the spoken D-ATIS body the observed visibility is always the coded group
        // (e.g. "10SM"), so a bare "VIS" is the approach kind — "VIS APP" = visual approach.
        ("VIS", "visual"), ("VCTR", "vector"), ("VCTRS", "vectors"), ("PROG", "progress"),
        ("INTL", "international"), ("INTXN", "intersection"), ("INTX", "intersection"), ("APRN", "apron"),
        ("CLSD", "closed"), ("CTC", "contact"), ("FREQ", "frequency"), ("FREQS", "frequencies"),
        ("INFO", "information"), ("ADVS", "advise"), ("ADVZ", "advise"), ("ADZ", "advise"),
        ("ADZYS", "advisories"), ("ADVZY", "advisory"),
        ("TEMP", "temperature"), ("DWPT", "dewpoint"), ("DEWPT", "dewpoint"),
        ("WX", "weather"), ("TFC", "traffic"), ("CIG", "ceiling"),
        // The observed altimeter is always coded (A####), so a bare "ALT" in the body is
        // an assigned altitude ("read back HS and ALT"); "ALSTG" is the altimeter setting.
        ("ALSTG", "altimeter"), ("ALT", "altitude"),
        ("MAINT", "maintenance"), ("HDG", "heading"), ("HDGS", "headings"),
        ("BRKG", "braking"), ("BA", "braking action"), ("SFC", "surface"),
        ("OTS", "out of service"), ("UNAVBL", "unavailable"), ("UNAVAIL", "unavailable"),
        ("AVBL", "available"), ("AVL", "available"), ("AVLB", "available"), ("AVAIL", "available"),
        ("SIMUL", "simultaneous"), ("SIMULT", "simultaneous"), ("SIMO", "simultaneous"),
        ("CONV", "converging"), ("PARL", "parallel"), ("DPNDNT", "dependent"), ("DPENDT", "dependent"),
        ("TWR", "tower"), ("GND", "ground"), ("CLNC", "clearance"), ("CLRNC", "clearance"),
        ("DEL", "delivery"), ("CTL", "control"), ("CTLR", "controller"), ("CTRL", "control"),
        ("ACFT", "aircraft"), ("EQUIP", "equipment"), ("EQPT", "equipment"),
        ("PERS", "personnel"), ("PERSONNEL", "personnel"), ("VEH", "vehicles"),
        ("CONST", "construction"), ("CONSTR", "construction"), ("OPS", "operations"),
        ("OPER", "operate"), ("OPR", "operate"),
        ("EXP", "expect"), ("EXPC", "expect"), ("EXPCT", "expect"), ("XPCT", "expect"),
        ("XPDR", "transponder"), ("XPNDR", "transponder"), ("TRNSPNDR", "transponder"),
        ("MODEC", "mode charlie"),
        ("BTN", "between"), ("BTWN", "between"), ("FT", "feet"), ("KTS", "knots"),
        ("HLDG", "holding"), ("DLA", "delay"), ("DLY", "delay"), ("DLAY", "delay"),
        ("NE", "northeast"), ("NW", "northwest"), ("SE", "southeast"), ("SW", "southwest"),
        ("CB", "cumulonimbus"), ("TCU", "towering cumulus"),
        ("WS", "wind shear"), ("LLWS", "low level wind shear"), ("WSHFT", "wind shift"),
        ("MU", "M U"), ("RCC", "runway condition code"), ("RVR", "R V R"),
        ("SKC", "sky clear"), ("CLR", "clear below one two thousand"), ("NSC", "no significant clouds"),
        ("NCD", "no clouds detected"),
        ("PIREP", "pilot report"), ("PIREPS", "pilot reports"),
        ("ARPT", "airport"), ("ARPTS", "airports"), ("INVOF", "in vicinity of"),
        ("VCNTY", "vicinity"), ("VCY", "vicinity"), ("CTN", "caution"), ("CAUT", "caution"),
        ("NUM", "numerous"), ("THSD", "thousand"), ("THND", "thousand"), ("HND", "hundred"),
        ("CONT", "continuous"), ("CONTINUOS", "continuous"),
        ("LAHSO", "land and hold short operations"), ("EFCT", "effect"),
        ("IM", "inner marker"), ("MM", "middle marker"), ("OM", "outer marker"), ("GS", "glideslope"),
        ("NOTAMS", "notams"), ("NOTAM", "notam"), ("RDBK", "read back"),
        // Hold short appears as both "HS" and the slashed "H/S"; the slash is a literal in
        // the escaped pattern, so "H/S" needs its own entry ("\bHS\b" can't reach across it).
        ("HS", "hold short"), ("H/S", "hold short"),
        ("HAZDS", "hazards"), ("HAZD", "hazard")
    ]

    // MARK: - Regex helpers

    /// Whether a parenthetical's contents are entirely spelled-out number words (an
    /// altimeter/pressure readback), e.g. "TWO NINER NINER TWO".
    private static func isNumberReadback(_ content: String) -> Bool {
        let words = content.split { $0 == " " }.map(String.init)
        guard !words.isEmpty else { return false }
        return words.allSatisfy { numberWords.contains($0) || $0.allSatisfy(\.isNumber) }
    }

    /// Uppercase set of every spoken number word across both phraseology packs, plus the
    /// magnitude words used in readbacks.
    private static let numberWords: Set<String> = {
        var set = Set<String>()
        for word in Phonetic.digitWords.values { set.insert(word.uppercased()) }
        for word in Phonetic.icaoDigitWords.values { set.insert(word.uppercased()) }
        set.formUnion(["NINE", "HUNDRED", "THOUSAND", "POINT", "DECIMAL"])
        return set
    }()

    /// Spell a numeric string one digit at a time after dropping leading zeros, so a
    /// coded "08" reads "eight" and "12" reads "one two" (but "00" still reads "zero").
    private static func spellCount(_ s: String, icao: Bool) -> String {
        let stripped = String(s.drop(while: { $0 == "0" }))
        return Phonetic.spellDigits(stripped.isEmpty ? "0" : stripped, icao: icao)
    }

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
