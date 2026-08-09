import Foundation

/// Extracts the **active departure and arrival runways** from a D-ATIS report, so the
/// simulated background chatter can reference the runways actually in use at a field
/// (e.g. departing 25R, landing 24R) rather than any runway on the map.
///
/// It is a pure, deterministic scan over the published D-ATIS text — no network, nothing
/// invented. The coded runway groups are read the way a controller would: keywords such as
/// `DEPG` / `TKOF` mark a departure runway, `LDG` / `ILS` / `APCH` mark an arrival runway, and
/// a combined phrase (`LDG AND DEPG RWY 13`) marks both. A field that publishes separate
/// arrival and departure ATIS resolves each part by its own `Kind`; a text the scanner doesn't
/// recognize simply yields nothing (the caller then falls back to the field's full runway set).
enum ATISRunwayParser {

    /// The active runways parsed from a report, split by operation.
    struct Runways: Equatable {
        var departures: [String] = []
        var arrivals: [String] = []

        var isEmpty: Bool { departures.isEmpty && arrivals.isEmpty }
    }

    /// Active runways across every part of a report — a single combined ATIS, or separate
    /// arrival and departure ATIS — de-duplicated in first-seen order.
    static func activeRunways(_ atis: AirportATIS) -> Runways {
        var departures: [String] = [], arrivals: [String] = []
        var seenDep = Set<String>(), seenArr = Set<String>()
        for part in atis.parts {
            let r = parse(part.text, kind: part.kind)
            for x in r.departures where seenDep.insert(x).inserted { departures.append(x) }
            for x in r.arrivals where seenArr.insert(x).inserted { arrivals.append(x) }
        }
        return Runways(departures: departures, arrivals: arrivals)
    }

    /// Canonical runway ident for comparison against other sources (e.g. the OSM map): uppercased
    /// with the leading zero dropped, so "04L" and "4L" and "4l" all compare equal; "09" -> "9".
    /// A token that isn't a runway is returned uppercased and trimmed.
    static func canonical(_ ident: String) -> String {
        runwayToken(ident) ?? ident.uppercased().trimmingCharacters(in: .whitespaces)
    }

    // MARK: - Parsing

    /// Words that put the scanner into a *departure* runway context.
    private static let departureKeywords: Set<String> = [
        "DEPG", "DEPTG", "DPTG", "DEPARTING", "DEPARTURE", "DEPARTURES", "DEP", "DEPS",
        "DEPART", "DEPARTS", "TKOF", "TKOFF", "TAKEOFF", "DEPU"
    ]
    /// Words that put the scanner into an *arrival* runway context (landing and every kind of
    /// approach clearance, which is always an arrival).
    private static let arrivalKeywords: Set<String> = [
        "LDG", "LNDG", "LANDING", "ARR", "ARRS", "ARRIVAL", "ARRIVALS", "ARRIVING",
        "APCH", "APCHS", "APPCH", "APPCHS", "APPROACH", "APPROACHES", "APP", "APPS",
        "ILS", "RNAV", "RNP", "LOC", "VOR", "LDA", "SDF", "GLS", "VISUAL", "VIS", "VA"
    ]
    /// Words that introduce a run of runway idents.
    private static let runwayKeywords: Set<String> = ["RWY", "RWYS", "RUNWAY", "RUNWAYS", "RY", "RYS"]
    /// Tokens that join runway idents inside one group ("24R AND 25L", "27L, 27R").
    private static let connectorTokens: Set<String> = ["AND", "&"]
    /// Words that mark a runway mention as a NAVAID-outage, closure, or surface-condition
    /// report rather than a runway in use: "…OTS" (out of service), "COND" (condition code),
    /// "CLSD"/"CLOSED", and the "unavailable" spellings. A runway named only in one of these
    /// contexts (e.g. "RWY 22R LOC OTS", "RWY 9L PAPI OTS", "RWY 22L COND CODE 5 5 5 …") is
    /// not the active runway, so it must not inherit the combined-ATIS "both operations" default.
    private static let statusTokens: Set<String> = [
        "OTS", "COND", "CLSD", "CLOSED", "UNAVBL", "UNAVAIL", "UNAVAILABLE"
    ]

    /// Parse a single D-ATIS text into its active departure/arrival runways. `kind` supplies the
    /// default operation for a runway named with no explicit keyword: a departure-only or
    /// arrival-only ATIS defaults accordingly; a combined ATIS defaults to both.
    static func parse(_ text: String, kind: AirportATIS.Kind) -> Runways {
        var departures: [String] = [], arrivals: [String] = []
        // Clauses are delimited by '.', runway lists by commas — normalize both to whitespace-ish
        // boundaries, then scan each clause with its own keyword context.
        let normalized = text.uppercased().replacingOccurrences(of: ",", with: " ")
        for clause in normalized.split(separator: ".") {
            let tokens = clause.split { $0 == " " || $0 == "\n" || $0 == "\t" || $0 == "/" }
                .flatMap { splitFlushRunwayKeyword(String($0)) }
            parseClause(tokens, kind: kind, departures: &departures, arrivals: &arrivals)
        }
        return Runways(departures: dedup(departures), arrivals: dedup(arrivals))
    }

    private static func parseClause(_ tokens: [String], kind: AirportATIS.Kind,
                                    departures: inout [String], arrivals: inout [String]) {
        var pendingDep = false, pendingArr = false
        var i = 0
        while i < tokens.count {
            let token = tokens[i]
            if departureKeywords.contains(token) { pendingDep = true; i += 1; continue }
            if arrivalKeywords.contains(token) { pendingArr = true; i += 1; continue }
            guard runwayKeywords.contains(token) else { i += 1; continue }

            // Collect the runway idents that follow, skipping connectors and any repeated
            // "RWY"/"RWYS" ("RWY 24R AND RWY 25L"); stop at the first non-runway word.
            var j = i + 1
            var collected: [String] = []
            while j < tokens.count {
                let next = tokens[j]
                if connectorTokens.contains(next) || runwayKeywords.contains(next) { j += 1; continue }
                guard let rwy = runwayToken(next) else { break }
                collected.append(rwy)
                j += 1
            }
            if !collected.isEmpty {
                let hasContext = pendingDep || pendingArr
                // A runway named with no arrival/departure keyword is only "in use" when it
                // isn't a NAVAID-outage, closure, or surface-condition report. Without this,
                // the combined-ATIS default would flag every "RWY x LOC OTS" / "RWY x COND
                // CODE …" runway as an active arrival+departure runway. An explicitly keyworded
                // group (ILS/APCH/DEPG/…) is always trusted.
                let suppressed = !hasContext && groupIsStatusReport(tokens, from: j)
                if !suppressed {
                    let toDep = pendingDep || (!hasContext && kind != .arrival)
                    let toArr = pendingArr || (!hasContext && kind != .departure)
                    for rwy in collected {
                        if toDep { departures.append(rwy) }
                        if toArr { arrivals.append(rwy) }
                    }
                }
                // Consume the context so a later group in the same clause re-derives its own.
                pendingDep = false
                pendingArr = false
            }
            i = j
        }
    }

    /// Whether the tokens trailing a runway group — scanned up to the next runway/operation
    /// keyword or the clause end — describe a component outage, closure, or surface condition,
    /// meaning the runway was named for a status report rather than because it is in use.
    private static func groupIsStatusReport(_ tokens: [String], from start: Int) -> Bool {
        var k = start
        while k < tokens.count {
            let t = tokens[k]
            // A following runway or operation keyword starts a new group — the current group's
            // trailing context has ended without a status token.
            if runwayKeywords.contains(t) || departureKeywords.contains(t) || arrivalKeywords.contains(t) {
                return false
            }
            if statusTokens.contains(t) { return true }
            k += 1
        }
        return false
    }

    /// Split a runway keyword written flush against its designator ("RY8R", "RWY22L") into the
    /// two tokens the spaced form produces, so the scanner sees the runway either way. Only a
    /// token whose letters are exactly a runway keyword *and* whose tail is a valid ident is
    /// split — anything else (a fix name, a NOTAM word) is left as it was.
    private static func splitFlushRunwayKeyword(_ token: String) -> [String] {
        guard let firstDigit = token.firstIndex(where: \.isNumber), firstDigit != token.startIndex
        else { return [token] }
        let keyword = String(token[token.startIndex..<firstDigit])
        let ident = String(token[firstDigit...])
        guard runwayKeywords.contains(keyword), runwayToken(ident) != nil else { return [token] }
        return [keyword, ident]
    }

    /// A single runway token ("24R", "8", "04L") in canonical form (leading zero dropped), or
    /// nil when the token isn't a runway ident in the 1…36 range.
    private static func runwayToken(_ token: String) -> String? {
        var digits = "", suffix = ""
        for ch in token.uppercased().trimmingCharacters(in: .whitespaces) {
            if ch.isNumber { digits.append(ch) }
            else if ch == "L" || ch == "R" || ch == "C" { suffix = String(ch) }
            else { return nil }
        }
        guard let n = Int(digits), (1...36).contains(n) else { return nil }
        return "\(n)\(suffix)"
    }

    private static func dedup(_ xs: [String]) -> [String] {
        var seen = Set<String>()
        return xs.filter { seen.insert($0).inserted }
    }
}
