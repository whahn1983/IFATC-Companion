import Foundation

/// Best-effort parser for the flight plan exposed by the Infinite Flight Connect
/// API v2 (`aircraft/0/flightplan`). Across IF versions this has been served both
/// as a plain whitespace/arrow-separated route string *and* as a richer JSON
/// document (with per-fix coordinates, nested SID/STAR/approach procedures, and
/// planned altitudes). This parser handles both:
///
///   1. If the payload looks like JSON, it is walked recursively to recover fix
///      names, coordinates, planned altitudes (the cruise/TOC level), and the
///      published procedures.
///   2. Otherwise it tokenises the route string, classifies tokens into airports
///      (4-letter ICAO codes) and named fixes, strips departure/arrival runway
///      tokens (so the runway is never mistaken for the first enroute waypoint),
///      and recovers a cruise altitude from any `FLxxx` / altitude token.
///
/// It degrades gracefully (returns `nil`) when nothing usable is found, so callers
/// can keep any existing plan untouched.
enum IFFlightPlanParser {

    /// Parse a raw IF flight-plan payload into a structured `FlightPlan`.
    /// Returns `nil` when the payload yields no recognisable departure/destination
    /// or fixes, so callers can keep any existing plan untouched.
    static func parse(_ raw: String) -> FlightPlan? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        // A JSON payload is parsed structurally; never fall back to tokenising the
        // braces as a route string (that would yield garbage "waypoints").
        if trimmed.hasPrefix("{") || trimmed.hasPrefix("[") {
            return parseJSON(trimmed)
        }
        return parseRouteString(trimmed)
    }

    /// Build a plan from the several flight-plan states Infinite Flight exposes.
    ///
    /// `full` (`aircraft/0/flightplan`) is the primary source; on some IF versions it
    /// is rich JSON (with per-fix coordinates and SID/STAR/approach groups), but on
    /// others it collapses a long route to a handful of summary legs. When that
    /// happens, the textual route (`flightplan/route`) carries every enroute fix, so
    /// its longer fix list is preferred. Coordinates (`flightplan/coordinates`) are
    /// attached when they line up 1-for-1 with the recovered fixes.
    static func parse(full: String?, route: String?, coordinates: String?) -> FlightPlan? {
        var plan = full.flatMap { parse($0) }

        // Prefer the route string's fix list when it recovers more enroute fixes than
        // the (possibly summarised) full payload did — but never trade away a richer
        // payload that already carries per-fix coordinates (the detailed-JSON case)
        // for a coordinate-less route string.
        let planHasCoordinates = plan?.waypoints.contains { $0.coordinate != nil } ?? false
        if let route, let routePlan = parseRouteString(route), !planHasCoordinates {
            if routePlan.waypoints.count > (plan?.waypoints.count ?? 0) {
                if var p = plan {
                    p.waypoints = routePlan.waypoints
                    if p.departure.isEmpty { p.departure = routePlan.departure }
                    if p.destination.isEmpty { p.destination = routePlan.destination }
                    if p.cruiseAltitude <= 0 { p.cruiseAltitude = routePlan.cruiseAltitude }
                    plan = p
                } else {
                    plan = routePlan
                }
            }
        }

        // Attach coordinates to fixes that still lack them, but only when the parsed
        // coordinate list lines up exactly with the recovered fixes — otherwise a
        // mismatched list would scatter the route across the map.
        if let coordinates, var p = plan, !p.waypoints.isEmpty {
            let coords = parseCoordinateList(coordinates)
            if coords.count == p.waypoints.count {
                for i in p.waypoints.indices where p.waypoints[i].coordinate == nil {
                    p.waypoints[i].latitude = coords[i].lat
                    p.waypoints[i].longitude = coords[i].lon
                }
                plan = p
            }
        }
        return plan
    }

    /// Parse a flat list of coordinate pairs from the `flightplan/coordinates` state.
    /// Tolerant of separators: pulls every signed decimal number and pairs them as
    /// (latitude, longitude). Returns only plausible on-globe pairs.
    static func parseCoordinateList(_ raw: String) -> [(lat: Double, lon: Double)] {
        // Pull every signed decimal number out of the payload, whatever separators
        // (commas, semicolons, whitespace, brackets) IF uses between them.
        var numbers: [Double] = []
        var token = ""
        func flush() { if let d = Double(token) { numbers.append(d) }; token = "" }
        for ch in raw {
            if ch == "-" || ch == "." || ch.isNumber {
                if ch == "-" && !token.isEmpty { flush() }   // a '-' starts a new number
                token.append(ch)
            } else { flush() }
        }
        flush()

        var pairs: [(lat: Double, lon: Double)] = []
        var i = 0
        while i + 1 < numbers.count {
            let lat = numbers[i], lon = numbers[i + 1]
            if abs(lat) <= 90, abs(lon) <= 180, lat != 0 || lon != 0 {
                pairs.append((lat, lon))
            }
            i += 2
        }
        return pairs
    }

    // MARK: - Route-string parsing

    private static func parseRouteString(_ trimmed: String) -> FlightPlan? {
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
        // (procedures, airways, runway and altitude/speed tokens are filtered out).
        var middle = tokens
        if !plan.departure.isEmpty, middle.first == plan.departure { middle.removeFirst() }
        if !plan.destination.isEmpty, middle.last == plan.destination { middle.removeLast() }

        // Recover a cruise altitude from any flight-level / altitude token.
        plan.cruiseAltitude = middle.compactMap(altitudeFromToken).max() ?? 0

        var seen = Set<String>()
        plan.waypoints = middle.compactMap { token -> Waypoint? in
            guard isFix(token), !isRunwayToken(token), !isPseudoWaypoint(token),
                  altitudeFromToken(token) == nil, !seen.contains(token) else { return nil }
            seen.insert(token)
            return Waypoint(name: token)
        }

        // Require at least one useful field to count as a parse.
        guard !plan.departure.isEmpty || !plan.destination.isEmpty || !plan.waypoints.isEmpty else {
            return nil
        }
        return plan
    }

    // MARK: - JSON parsing

    /// Parse the richer JSON flight-plan document. Tolerant of key/casing variation
    /// across IF versions: it recovers ordered located fixes, the endpoints, the
    /// cruise (highest planned altitude), and the SID/STAR/approach names.
    static func parseJSON(_ raw: String) -> FlightPlan? {
        guard let data = raw.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) else { return nil }

        // Locate the array of flight-plan items wherever it lives in the document.
        guard let items = flightPlanItems(in: root) else { return nil }

        var plan = FlightPlan()
        var fixes: [Waypoint] = []
        var maxAltitude = 0
        var seen = Set<String>()

        // Walk the (possibly nested) items in order. Procedure groups carry their
        // fixes as `children`; their name is the published procedure identifier.
        for item in items {
            guard let dict = item as? [String: Any] else { continue }
            let name = (string(dict, "identifier") ?? string(dict, "name") ?? "")
                .trimmingCharacters(in: .whitespaces).uppercased()
            let children = childArray(dict)

            if !children.isEmpty {
                // A SID/STAR/approach grouping. Classify by name and position — a
                // procedure before any enroute (non-airport) fix is the SID, one
                // after is the STAR. The departure airport doesn't count as enroute.
                let hadEnrouteFix = fixes.contains { !isICAO($0.name) }
                classifyProcedure(name: name, into: &plan, hasFixesBefore: hadEnrouteFix)
                let isApproach = isApproachName(name)
                for (i, child) in children.enumerated() {
                    appendFix(child, to: &fixes, maxAltitude: &maxAltitude, seen: &seen)
                    // The intercept altitude is the first altitude in the approach
                    // section of the flight plan.
                    if isApproach, i == 0, let dict = child as? [String: Any],
                       let alt = plannedAltitude(in: dict) {
                        plan.approachInterceptAltitude = alt
                    }
                }
            } else {
                appendFix(item, to: &fixes, maxAltitude: &maxAltitude, seen: &seen)
            }
        }

        // Endpoints: first/last ICAO-looking fixes become departure/destination and
        // are dropped from the enroute list.
        if let first = fixes.first, isICAO(first.name) {
            plan.departure = first.name
            fixes.removeFirst()
        }
        if let last = fixes.last, isICAO(last.name), last.name != plan.departure {
            plan.destination = last.name
            fixes.removeLast()
        }

        // Drop runway tokens / IF display markers so neither is shown as a fix.
        plan.waypoints = fixes.filter { !isRunwayToken($0.name) && !isPseudoWaypoint($0.name) }
        plan.cruiseAltitude = maxAltitude

        guard !plan.departure.isEmpty || !plan.destination.isEmpty || !plan.waypoints.isEmpty else {
            return nil
        }
        return plan
    }

    /// Find the richest flight-plan item array anywhere in the document.
    ///
    /// Infinite Flight serves both a *simplified* `Waypoints` string list (only the
    /// high-level legs — including non-navigational DPT/TOC/TOD display markers) and
    /// a *detailed* `FlightPlanItems` array (every fix, with coordinates and nested
    /// SID/STAR/approach groups). Keys are PascalCase and the two live side-by-side,
    /// so a case-insensitive search that *prefers the detailed array* is needed —
    /// otherwise the plan reads as a 5-fix summary with no procedures or coordinates.
    private static func flightPlanItems(in root: Any) -> [Any]? {
        var best: (score: Int, items: [Any])?

        func consider(_ array: [Any], key: String) {
            guard !array.isEmpty else { return }
            let k = key.lowercased()
            let dicts = array.reduce(0) { $0 + (($1 is [String: Any]) ? 1 : 0) }
            // Arrays of objects (detailed fixes) always rank above string lists.
            var score = dicts > 0 ? 1000 + dicts : array.count
            if k.contains("flightplanitem") { score += 5000 }
            else if k.contains("item") || k.contains("fix") { score += 2000 }
            else if k.contains("waypoint") { score += 100 }
            if best == nil || score > best!.score { best = (score, array) }
        }

        // Recurse through objects only — never descend into an array's elements, so a
        // procedure's nested `children` array is never mistaken for the whole plan.
        func walk(_ node: Any, key: String) {
            if let array = node as? [Any] { consider(array, key: key); return }
            if let dict = node as? [String: Any] {
                for (k, v) in dict { walk(v, key: k) }
            }
        }

        walk(root, key: "")
        return best?.items
    }

    /// A procedure group's child fixes, tolerant of key casing (IF uses `Children`).
    private static func childArray(_ dict: [String: Any]) -> [Any] {
        if let c = dict["children"] as? [Any] { return c }
        if let pair = dict.first(where: { $0.key.lowercased() == "children" }),
           let c = pair.value as? [Any] { return c }
        return []
    }

    /// Append a single fix (with coordinate + planned altitude when present).
    private static func appendFix(_ item: Any, to fixes: inout [Waypoint],
                                  maxAltitude: inout Int, seen: inout Set<String>) {
        // A bare string entry (a `waypoints` name list) has no coordinate.
        if let name = item as? String {
            let n = name.trimmingCharacters(in: .whitespaces).uppercased()
            guard !n.isEmpty, !isPseudoWaypoint(n), !seen.contains(n) else { return }
            seen.insert(n)
            fixes.append(Waypoint(name: n))
            return
        }
        guard let dict = item as? [String: Any] else { return }
        let name = (string(dict, "identifier") ?? string(dict, "name") ?? "")
            .trimmingCharacters(in: .whitespaces).uppercased()
        guard !name.isEmpty, !isPseudoWaypoint(name) else { return }

        let coord = coordinate(in: dict)
        let alt = plannedAltitude(in: dict)
        if let alt, alt > maxAltitude, alt < 60000 { maxAltitude = alt }

        // De-dupe by name, but prefer the entry that carries a coordinate.
        if seen.contains(name) {
            if let coord, let idx = fixes.firstIndex(where: { $0.name == name && $0.coordinate == nil }) {
                fixes[idx].latitude = coord.lat
                fixes[idx].longitude = coord.lon
            }
            return
        }
        seen.insert(name)
        fixes.append(Waypoint(name: name, latitude: coord?.lat, longitude: coord?.lon,
                              altitude: alt.map(Double.init)))
    }

    /// Whether a procedure group name denotes an instrument/visual approach.
    private static func isApproachName(_ name: String) -> Bool {
        let upper = name.uppercased()
        return ["ILS", "RNAV", "RNP", "VOR", "GPS", "LOC", "NDB", "VISUAL", "APP"]
            .contains { upper.contains($0) }
    }

    /// Record a SID/STAR/approach name onto the plan from a procedure grouping.
    private static func classifyProcedure(name: String, into plan: inout FlightPlan,
                                          hasFixesBefore: Bool) {
        guard !name.isEmpty else { return }
        if isApproachName(name) {
            if plan.approach.isEmpty { plan.approach = name }
        } else if !hasFixesBefore {
            // First procedure, near the departure → SID.
            if plan.sid.isEmpty { plan.sid = name }
        } else {
            // A later procedure near the arrival → STAR (overwrite so the last wins).
            plan.star = name
        }
    }

    // MARK: - JSON field helpers

    private static func string(_ dict: [String: Any], _ key: String) -> String? {
        if let s = dict[key] as? String { return s }
        // Case-insensitive fallback.
        if let pair = dict.first(where: { $0.key.lowercased() == key.lowercased() }),
           let s = pair.value as? String { return s }
        return nil
    }

    /// Recover a coordinate from an item, looking inside a nested `location`
    /// object as well as on the item itself. Tolerant of key casing.
    private static func coordinate(in dict: [String: Any]) -> (lat: Double, lon: Double)? {
        let container = (dict["location"] as? [String: Any])
            ?? (dict["Location"] as? [String: Any])
            ?? dict
        guard let lat = number(container, ["Latitude", "latitude", "lat"]),
              let lon = number(container, ["Longitude", "longitude", "lon", "lng"]),
              lat != 0 || lon != 0,
              abs(lat) <= 90, abs(lon) <= 180 else { return nil }
        return (lat, lon)
    }

    /// Recover a planned altitude (feet) from an item or its location.
    private static func plannedAltitude(in dict: [String: Any]) -> Int? {
        let keys = ["altitude", "Altitude", "AltitudeMSL", "altitudeMSL", "alt"]
        if let a = number(dict, keys), a > 0 { return Int(a) }
        if let loc = (dict["location"] as? [String: Any]) ?? (dict["Location"] as? [String: Any]),
           let a = number(loc, keys), a > 0 { return Int(a) }
        return nil
    }

    private static func number(_ dict: [String: Any], _ keys: [String]) -> Double? {
        for key in keys {
            if let n = dict[key] as? Double { return n }
            if let n = dict[key] as? Int { return Double(n) }
            if let n = dict[key] as? NSNumber { return n.doubleValue }
        }
        return nil
    }

    // MARK: - Token classification

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

    /// A non-navigational display marker Infinite Flight inserts into the simplified
    /// route (departure, top-of-climb, top-of-descent, destination). These are not
    /// real fixes and must never be shown as waypoints.
    static func isPseudoWaypoint(_ token: String) -> Bool {
        ["DPT", "DEP", "DEPARTURE", "TOC", "T/C", "TOD", "T/D",
         "DEST", "DESTINATION", "ARR", "ARRIVAL"].contains(token.uppercased())
    }

    /// A runway token such as `RW14`, `14`, `30L`, `09C` — these appear at the
    /// ends of a route (the departure/arrival runway) and must not be treated as
    /// enroute waypoints.
    static func isRunwayToken(_ token: String) -> Bool {
        var s = token.uppercased()
        if s.hasPrefix("RW") { s = String(s.dropFirst(2)) }
        let digits = s.prefix { $0.isNumber }
        guard !digits.isEmpty, let n = Int(digits), n >= 1, n <= 36 else { return false }
        let rest = s.dropFirst(digits.count)
        return rest.isEmpty || (rest.count == 1 && rest.allSatisfy { "LRC".contains($0) })
    }

    /// Recover an altitude in feet from a flight-level / altitude token:
    /// `FL370` → 37000, `F350` → 35000, `37000` → 37000. Returns nil otherwise.
    static func altitudeFromToken(_ token: String) -> Int? {
        let t = token.uppercased()
        if t.hasPrefix("FL") || (t.hasPrefix("F") && t.dropFirst().allSatisfy { $0.isNumber }) {
            let digits = t.drop { !$0.isNumber }
            if let fl = Int(digits), fl > 0, fl <= 600 { return fl * 100 }
        }
        if t.allSatisfy({ $0.isNumber }), let ft = Int(t), ft >= 1000, ft <= 60000 {
            return ft
        }
        return nil
    }
}
