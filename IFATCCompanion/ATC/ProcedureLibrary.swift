import Foundation

/// The kind of published procedure.
enum ProcedureKind: String, Codable, Equatable {
    case sid    // Standard Instrument Departure
    case star   // Standard Terminal Arrival
    case approach
}

/// Instrument approach types, with display + spoken forms.
enum ApproachType: String, Codable, Equatable {
    case ils
    case loc
    case rnav
    case rnavGPS
    case vor
    case ndb
    case gps
    case visual

    var display: String {
        switch self {
        case .ils: return "ILS"
        case .loc: return "LOC"
        case .rnav: return "RNAV"
        case .rnavGPS: return "RNAV (GPS)"
        case .vor: return "VOR"
        case .ndb: return "NDB"
        case .gps: return "GPS"
        case .visual: return "Visual"
        }
    }

    /// Spelled for the speech synthesizer (letters separated so they're read out).
    var spoken: String {
        switch self {
        case .ils: return "I L S"
        case .loc: return "localizer"
        case .rnav: return "R NAV"
        case .rnavGPS: return "R NAV G P S"
        case .vor: return "V O R"
        case .ndb: return "N D B"
        case .gps: return "G P S"
        case .visual: return "visual"
        }
    }

    static func parse(_ text: String) -> ApproachType? {
        let t = text.uppercased()
        if t.contains("RNAV") && t.contains("GPS") { return .rnavGPS }
        if t.contains("RNAV") { return .rnav }
        if t.contains("ILS") { return .ils }
        if t.contains("LOC") { return .loc }
        if t.contains("VOR") { return .vor }
        if t.contains("NDB") { return .ndb }
        if t.contains("GPS") { return .gps }
        if t.contains("VIS") { return .visual }
        return nil
    }
}

/// A parsed published procedure (SID, STAR, or approach). Deterministically
/// derived from the procedure name string the pilot enters, optionally enriched
/// with known fixes from the built-in `ProcedureLibrary`.
struct Procedure: Equatable {
    var kind: ProcedureKind
    var name: String            // designator root, e.g. "WAGON"
    var revision: Int?          // trailing revision number, e.g. 5
    var transition: String?     // text after a "." separator, e.g. "HOBTT"
    var runway: String?         // for approaches / runway-specific procedures
    var approachType: ApproachType?
    var fixes: [String] = []    // ordered fixes, when known

    /// Transcript form, e.g. "WAGON5", "WAGON5.HOBTT", "ILS RWY 30L".
    var displayName: String {
        switch kind {
        case .approach:
            let type = approachType?.display ?? "Approach"
            if let rwy = runway, !rwy.isEmpty { return "\(type) RWY \(rwy)" }
            return type
        case .sid, .star:
            var base = name
            if let rev = revision { base += "\(rev)" }
            if let t = transition, !t.isEmpty { base += ".\(t)" }
            return base
        }
    }

    /// Spoken form for the synthesizer.
    func spokenName(icao: Bool) -> String {
        switch kind {
        case .approach:
            let type = approachType?.spoken ?? "approach"
            if let rwy = runway, !rwy.isEmpty {
                return "\(type) runway \(Phonetic.runway(rwy, icao: icao))"
            }
            return type
        case .sid, .star:
            // Speak the name word as-is (the synthesizer pronounces it), plus the
            // revision number spelled out, plus an optional transition.
            var parts: [String] = [name.capitalized]
            if let rev = revision { parts.append(Phonetic.spellDigits(String(rev), icao: icao)) }
            var s = parts.joined(separator: " ")
            if let t = transition, !t.isEmpty { s += ", \(Phonetic.spellToken(t, icao: icao)) transition" }
            return s
        }
    }
}

/// Parses procedure name strings and supplies a small built-in library of known
/// procedures (fixes) for the demo/mock airports. Best-effort and deterministic.
enum ProcedureParser {

    /// Parse a SID name string, e.g. "WAGON5", "WAGmm", "WAGON5.HOBTT".
    static func parseSID(_ raw: String, icao: String? = nil) -> Procedure? {
        parseDesignator(raw, kind: .sid, icao: icao)
    }

    /// Parse a STAR name string, e.g. "KKILR3", "BDF.BDF7".
    static func parseSTAR(_ raw: String, icao: String? = nil) -> Procedure? {
        parseDesignator(raw, kind: .star, icao: icao)
    }

    /// Parse an approach string, e.g. "ILS 30L", "RNAV (GPS) 27", "VOR 09".
    static func parseApproach(_ raw: String) -> Procedure? {
        let trimmed = raw.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        let type = ApproachType.parse(trimmed)
        let runway = extractRunway(trimmed)
        // If neither a type nor a runway is present it's not a usable approach.
        guard type != nil || runway != nil else { return nil }
        return Procedure(kind: .approach, name: type?.display ?? "Approach",
                         revision: nil, transition: nil, runway: runway,
                         approachType: type ?? .ils)
    }

    // MARK: - Internals

    private static func parseDesignator(_ raw: String, kind: ProcedureKind, icao: String?) -> Procedure? {
        let trimmed = raw.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }

        // Split off an optional ".TRANSITION".
        let dotParts = trimmed.split(separator: ".", maxSplits: 1).map(String.init)
        let designator = dotParts[0]
        let transition = dotParts.count > 1 ? dotParts[1] : nil

        // Separate a trailing revision number from the alphabetic root.
        var root = designator
        var revision: Int?
        if let last = designator.last, last.isNumber {
            var digits = ""
            while let l = root.last, l.isNumber { digits.insert(l, at: digits.startIndex); root.removeLast() }
            revision = Int(digits)
        }
        guard !root.isEmpty else { return nil }

        var procedure = Procedure(kind: kind, name: root.uppercased(), revision: revision,
                                  transition: transition?.uppercased(), runway: nil, approachType: nil)
        if let icao { procedure = ProcedureLibrary.enrich(procedure, icao: icao) }
        return procedure
    }

    /// Extract a runway identifier (e.g. "30L", "09", "16R") from free text.
    static func extractRunway(_ text: String) -> String? {
        let upper = text.uppercased()
        var current = ""
        var best: String?
        func flush() {
            if !current.isEmpty {
                // A runway is 1-2 digits optionally followed by L/R/C.
                let digits = current.prefix { $0.isNumber }
                if (1...2).contains(digits.count), let n = Int(digits), (1...36).contains(n) {
                    best = current
                }
            }
            current = ""
        }
        for ch in upper {
            if ch.isNumber { current.append(ch) }
            else if (ch == "L" || ch == "R" || ch == "C") && !current.isEmpty && current.allSatisfy({ $0.isNumber }) {
                current.append(ch); flush()
            } else { flush() }
        }
        flush()
        return best
    }
}

/// A tiny built-in library of published procedures (with fixes) for the demo
/// airports. Not exhaustive — used to enrich parsed procedures with realistic
/// fixes so procedure-aware instructions sound natural offline.
enum ProcedureLibrary {

    struct Entry { let designator: String; let runways: [String]; let fixes: [String] }

    static let sids: [String: [Entry]] = [
        "KIAH": [Entry(designator: "WAGON", runways: ["15L", "15R"], fixes: ["WAGON", "HOBTT", "DAS"])],
        "KMSP": [Entry(designator: "ZALES", runways: ["30L", "30R"], fixes: ["ZALES", "KKILR"])],
        "KDEN": [Entry(designator: "FLATI", runways: ["34L", "34R"], fixes: ["FLATI", "AKO"])]
    ]

    static let stars: [String: [Entry]] = [
        "KMSP": [Entry(designator: "KKILR", runways: ["30L", "30R"], fixes: ["FGT", "KKILR", "GOPHR"])],
        "KIAH": [Entry(designator: "DOOBI", runways: ["26L", "26R"], fixes: ["DOOBI", "GUMBYS"])],
        "KDEN": [Entry(designator: "QUAIL", runways: ["16L", "16R"], fixes: ["QUAIL", "BAACK"])]
    ]

    /// Attach known fixes / runways to a parsed SID/STAR if the designator matches.
    static func enrich(_ procedure: Procedure, icao: String) -> Procedure {
        let table = procedure.kind == .sid ? sids : (procedure.kind == .star ? stars : [:])
        guard let entries = table[icao.uppercased()],
              let match = entries.first(where: { $0.designator == procedure.name }) else {
            return procedure
        }
        var enriched = procedure
        enriched.fixes = match.fixes
        if enriched.runway == nil { enriched.runway = match.runways.first }
        return enriched
    }
}
