import Foundation

/// Best-effort parser for the flight plan string exposed by the Infinite Flight
/// Connect API v2 (`aircraft/0/flightplan`). The exact serialization is not
/// formally documented and has varied across IF versions, so this parser is
/// deliberately tolerant: it tokenises on common separators, classifies tokens
/// into airports (4-letter ICAO codes) and named fixes, and degrades gracefully
/// (returns `nil`) when nothing usable is found. No coordinates are assumed.
enum IFFlightPlanParser {

    /// Parse a raw IF flight-plan string into a structured `FlightPlan`.
    /// Returns `nil` when the string yields no recognisable departure/destination
    /// or fixes, so callers can keep any existing plan untouched.
    static func parse(_ raw: String) -> FlightPlan? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        let separators = CharacterSet(charactersIn: " \t\r\n,;|>/-")
        let tokens = trimmed
            .components(separatedBy: separators)
            .map { $0.trimmingCharacters(in: .whitespaces).uppercased() }
            .filter { !$0.isEmpty }
        guard !tokens.isEmpty else { return nil }

        var plan = FlightPlan()

        // First/last 4-letter alpha tokens are the departure/arrival airports.
        if let first = tokens.first, isICAO(first) { plan.departure = first }
        if let last = tokens.last, isICAO(last), last != plan.departure { plan.destination = last }

        // Everything between the airports that looks like a fix becomes a waypoint
        // (procedures, airways and altitude/speed tokens are filtered out).
        var middle = tokens
        if !plan.departure.isEmpty, middle.first == plan.departure { middle.removeFirst() }
        if !plan.destination.isEmpty, middle.last == plan.destination { middle.removeLast() }

        var seen = Set<String>()
        plan.waypoints = middle.compactMap { token -> Waypoint? in
            guard isFix(token), !seen.contains(token) else { return nil }
            seen.insert(token)
            return Waypoint(name: token)
        }

        // Require at least one useful field to count as a parse.
        guard !plan.departure.isEmpty || !plan.destination.isEmpty || !plan.waypoints.isEmpty else {
            return nil
        }
        return plan
    }

    /// A 4-letter, all-alphabetic token treated as an ICAO airport identifier.
    static func isICAO(_ token: String) -> Bool {
        token.count == 4 && token.allSatisfy { $0.isLetter }
    }

    /// A plausible named fix / VOR / waypoint: 2–6 alphanumerics containing at
    /// least one letter. Excludes pure numbers (altitudes/speeds) and ICAOs.
    static func isFix(_ token: String) -> Bool {
        guard token.count >= 2, token.count <= 6 else { return false }
        guard token.allSatisfy({ $0.isLetter || $0.isNumber }) else { return false }
        guard token.contains(where: { $0.isLetter }) else { return false }
        return !isICAO(token)
    }
}
