import Foundation
import CoreLocation

/// Which end of the flight an automatic stand assignment is being made for. The two ends
/// are assigned independently — a blank departure gate is filled from the origin field's
/// stands, a blank arrival gate from the destination's.
enum GateRole: String, Equatable, CaseIterable {
    case departure
    case arrival

    var title: String {
        switch self {
        case .departure: return "departure"
        case .arrival: return "arrival"
        }
    }
}

/// The marker recorded alongside an automatically-assigned gate: which airport it was
/// picked for, and which stand was picked. It is what lets the automatic assignment tell
/// **its own** value apart from one the pilot typed — the whole feature is conditional on
/// the field having been left blank, so it must never overwrite a gate a pilot entered,
/// and must equally never leave a stale gate from the last flight's airport in place.
///
/// Encoded as `ICAO:GATE` so it persists in the same string-shaped preference store as
/// every other flight override.
struct AutoGateStamp: Equatable {
    var icao: String
    var gate: String
    /// Whether the gate was read off the aircraft's own position — it was parked on that
    /// stand — rather than chosen from the field's stand list. A *chosen* gate is worth
    /// replacing the moment the aircraft's position says which stand it is actually on; a
    /// position-derived one is already the truth and is never re-picked.
    var fromAircraftPosition: Bool

    init(icao: String, gate: String, fromAircraftPosition: Bool = false) {
        self.icao = icao.uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
        self.gate = gate.trimmingCharacters(in: .whitespacesAndNewlines)
        self.fromAircraftPosition = fromAircraftPosition
    }

    /// `"KIAH:C24"`, or `"KIAH:C24:P"` for a gate read off the aircraft's position. Empty
    /// when either of the first two halves is missing (nothing to remember).
    var encoded: String {
        guard !icao.isEmpty, !gate.isEmpty else { return "" }
        return fromAircraftPosition ? "\(icao):\(gate):\(Self.positionFlag)" : "\(icao):\(gate)"
    }

    /// Decode a stored marker. Returns nil for an empty/garbled value, which reads as
    /// "the app has not assigned a gate" — the safe direction, because an unrecognised
    /// marker leaves whatever is in the field alone. A two-part marker (everything written
    /// before the position-derived gate existed) decodes as a chosen gate.
    init?(encoded: String) {
        let parts = encoded.split(separator: ":", maxSplits: 2, omittingEmptySubsequences: false)
        guard parts.count == 2 || parts.count == 3 else { return nil }
        let icao = String(parts[0]).uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
        let gate = String(parts[1]).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !icao.isEmpty, !gate.isEmpty else { return nil }
        self.icao = icao
        self.gate = gate
        self.fromAircraftPosition = parts.count == 3
            && String(parts[2]).trimmingCharacters(in: .whitespacesAndNewlines).uppercased() == Self.positionFlag
    }

    private static let positionFlag = "P"
}

/// What an OSM stand's tags say about the stand, normalized. Every field is best-effort:
/// stand tagging in OSM is sparse and inconsistent between airports, so an unknown never
/// disqualifies a stand — it only stops it from being *preferred* over one that does carry
/// the matching data.
///
/// Tags read (see https://wiki.openstreetmap.org/wiki/Key:aeroway):
///   • `aircraft:type` — what the stand is sized for: an airframe ("A320", "B738;B739"),
///     a size band ("heavy", "wide_body"), or a category ("helicopter", "light_aircraft").
///   • `operator`, `operator:en`, `operator:short` — the airline or handler working it.
///   • `access` — `no`/`private` marks a stand this flight has no business on.
///   • `ref`/`name` — the identifier a controller says ("B44"). Already folded into
///     `SurfaceParking.name` by the OSM normalizer, and required: a stand with no
///     identifier can't be named in a clearance, so it is never assigned.
struct StandProfile: Equatable {
    /// The largest size class the tags say the stand takes, or nil when untagged.
    var maxClass: AircraftSizeClass?
    /// The stand's `aircraft:type` names rotorcraft only.
    var helicopterOnly: Bool = false
    /// Operator / handler names found on the stand, lowercased for matching.
    var operatorNames: [String] = []
    /// `access=no` or `access=private`.
    var restricted: Bool = false
    /// A cargo / freight position, from the operator or the stand name.
    var cargo: Bool = false
    /// A working position rather than a parking stand — de-icing pad, maintenance or
    /// hangar stand, engine run-up or compass pad. Never assigned to a flight.
    var servicePosition: Bool = false

    /// Whether a stand tagged with this profile can take the aircraft at all, size-wise.
    /// An untagged stand is assumed usable (unknown stays unknown).
    func accepts(_ aircraft: AircraftSizeClass) -> Bool {
        guard let maxClass else { return true }
        return maxClass.rank >= aircraft.rank
    }

    /// How much bigger the stand is than the aircraft needs, in size-class steps. 0 is a
    /// snug fit. Untagged stands report 0 — there is nothing to grade them on.
    func fitGap(for aircraft: AircraftSizeClass) -> Int {
        guard let maxClass else { return 0 }
        return max(0, maxClass.rank - aircraft.rank)
    }

    /// Read a stand's tags into a profile.
    static func from(tags: [String: String], standName: String) -> StandProfile {
        var profile = StandProfile(maxClass: nil)

        // Size, from `aircraft:type` (and the `aircraft` / `aircraft:size` variants some
        // mappers use). Tokens are separated by ";" in OSM's multi-value convention;
        // "," and "/" are accepted too because both show up in practice.
        let typeValue = [tags["aircraft:type"], tags["aircraft"], tags["aircraft:size"]]
            .compactMap { $0 }.joined(separator: ";")
        var sawHelicopter = false
        var sawFixedWing = false
        for token in tokenize(typeValue) {
            switch classify(typeToken: token) {
            case .helicopter:
                sawHelicopter = true
            case .size(let cls):
                sawFixedWing = true
                if let current = profile.maxClass {
                    if cls.rank > current.rank { profile.maxClass = cls }
                } else {
                    profile.maxClass = cls
                }
            case .unknown:
                continue
            }
        }
        profile.helicopterOnly = sawHelicopter && !sawFixedWing

        // Operator / handler.
        var operators: [String] = []
        for key in ["operator", "operator:en", "operator:short", "network", "owner"] {
            guard let raw = tags[key] else { continue }
            // A multi-operator stand ("Delta;KLM") counts as either airline's.
            for part in raw.split(whereSeparator: { $0 == ";" || $0 == "|" }) {
                let name = part.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                if !name.isEmpty { operators.append(name) }
            }
        }
        profile.operatorNames = operators

        if let access = tags["access"]?.lowercased().trimmingCharacters(in: .whitespaces) {
            profile.restricted = access == "no" || access == "private"
        }

        // Cargo and service positions have no dedicated OSM key, so they are read from the
        // descriptive tags mappers actually reach for. The two texts are kept apart on
        // purpose: "Lufthansa Cargo" in `operator` does mark a freight stand, but an airport
        // authority's name in `operator` must never be searched for words like "maintenance"
        // — that is what the stand's own purpose tags are for.
        let purposeText = ([standName, tags["name"] ?? "", tags["description"] ?? "",
                            tags["parking_position"] ?? "", tags["gate"] ?? "",
                            tags["usage"] ?? "", tags["aeroway:type"] ?? ""])
            .joined(separator: " ").lowercased()
        let operatorText = operators.joined(separator: " ")
        profile.cargo = cargoWords.contains { purposeText.contains($0) || operatorText.contains($0) }
        profile.servicePosition = serviceWords.contains { purposeText.contains($0) }
        return profile
    }

    // MARK: - Tag vocabulary

    private enum TypeToken {
        case size(AircraftSizeClass)
        case helicopter
        case unknown
    }

    /// Split a multi-value tag and normalize each token for matching (lowercased, spaces
    /// and hyphens folded to "_" so "light aircraft"/"light-aircraft"/"light_aircraft" all
    /// land on the same key).
    private static func tokenize(_ value: String) -> [String] {
        value.split(whereSeparator: { $0 == ";" || $0 == "," || $0 == "/" || $0 == "|" })
            .map { part in
                part.trimmingCharacters(in: .whitespacesAndNewlines)
                    .lowercased()
                    .replacingOccurrences(of: " ", with: "_")
                    .replacingOccurrences(of: "-", with: "_")
            }
            .filter { !$0.isEmpty }
    }

    private static func classify(typeToken token: String) -> TypeToken {
        if helicopterWords.contains(where: { token.contains($0) }) { return .helicopter }
        if let cls = sizeWords[token] { return .size(cls) }
        // Not a size band — try it as an airframe designator ("a320", "b77w"). Strict, so
        // a token that matches nothing stays unknown rather than defaulting to a 737 stand.
        if let cls = AircraftSizeClass.classifyStrict(aircraftName: token) { return .size(cls) }
        return .unknown
    }

    private static let helicopterWords = ["helicopter", "helipad", "rotor"]

    /// `aircraft:type` values that name a size band rather than an airframe. Matched
    /// exactly (not by substring) so short words never swallow an unrelated token.
    /// The ICAO aerodrome reference codes are included in their `code_x` spelling: C is the
    /// 737/A320 band, D the 767, E the 777/747, F the A380.
    private static let sizeWords: [String: AircraftSizeClass] = [
        "light": .light, "light_aircraft": .light, "lightaircraft": .light,
        "general_aviation": .light, "ga": .light, "glider": .light,
        "ultralight": .light, "microlight": .light, "piston": .light,
        "single_engine": .light, "code_a": .light, "code_b": .small,
        "small": .small, "commuter": .small, "regional": .small, "regional_jet": .small,
        "turboprop": .small, "prop": .small, "props": .small,
        "medium": .medium, "narrow_body": .medium, "narrowbody": .medium,
        "narrow": .medium, "code_c": .medium,
        "large": .large, "wide_body": .large, "widebody": .large, "wide": .large,
        "code_d": .large, "jet": .large, "airliner": .large,
        "heavy": .heavy, "jumbo": .heavy, "super": .heavy,
        "code_e": .heavy, "code_f": .heavy
    ]

    private static let cargoWords = ["cargo", "freight", "fracht", "fret", "carga"]
    /// Purpose words that mark a working position rather than a stand a flight parks on.
    /// Deliberately narrow — these are matched against the stand's *purpose* tags only, and
    /// a word that could plausibly appear in an airport or city name is left out so a real
    /// stand is never dropped for looking like a de-icing pad.
    private static let serviceWords = [
        "deic", "de-ic", "de_ic", "anti_ice", "anti-ice",
        "maintenance", "hangar", "hanger", "workshop", "engine_run", "run_up",
        "compass_swing", "aircraft_wash", "abandoned", "disused"
    ]
}

/// Airline brand names as they actually appear in OSM `operator` tags, keyed by ICAO
/// designator, plus the cargo carriers.
///
/// The *spoken* telephony name in `AirlineDatabase` is frequently not the brand a mapper
/// types — "Speedbird" is British Airways, "Airfrans" is Air France — so the carriers whose
/// two differ are listed here. For everyone else the telephony name itself is tried as a
/// fragment, which already covers the many airlines whose call name *is* their brand
/// ("Lufthansa", "KLM", "Delta", "Emirates"). Best-effort by design: a miss simply means the
/// stand is chosen on size and type rather than on the airline.
enum StandOperators {

    /// Brand fragments (lowercased) for an ICAO or IATA airline designator.
    static func brandNames(forDesignator designator: String) -> [String] {
        let key = designator.uppercased().trimmingCharacters(in: .whitespaces)
        guard !key.isEmpty else { return [] }
        return brands[key] ?? []
    }

    /// Whether a designator belongs to a cargo carrier — a freight flight belongs on a
    /// cargo stand, and a passenger flight does not.
    static func isCargoDesignator(_ designator: String) -> Bool {
        cargoDesignators.contains(designator.uppercased().trimmingCharacters(in: .whitespaces))
    }

    private static let brands: [String: [String]] = [
        // North America
        "AAL": ["american airlines", "american"], "DAL": ["delta"], "UAL": ["united"],
        "SWA": ["southwest"], "ASA": ["alaska"], "JBU": ["jetblue"], "NKS": ["spirit"],
        "FFT": ["frontier"], "HAL": ["hawaiian"], "SCX": ["sun country"],
        "AAY": ["allegiant"], "SKW": ["skywest"], "ENY": ["envoy"],
        "RPA": ["republic"], "EDV": ["endeavor"], "QXE": ["horizon"],
        "ACA": ["air canada"], "WJA": ["westjet"], "TSC": ["air transat"],
        "POE": ["porter"], "AMX": ["aeromexico", "aeroméxico"], "VOI": ["volaris"],
        // Europe
        "BAW": ["british airways"], "VIR": ["virgin atlantic"], "EZY": ["easyjet"],
        "RYR": ["ryanair"], "EXS": ["jet2"], "DLH": ["lufthansa"],
        "EWG": ["eurowings"], "AFR": ["air france"], "KLM": ["klm"],
        "TRA": ["transavia"], "SAS": ["scandinavian", "sas"], "IBE": ["iberia"],
        "VLG": ["vueling"], "AEA": ["air europa"], "AZA": ["alitalia"],
        "ITY": ["ita airways"], "SWR": ["swiss"], "AUA": ["austrian"],
        "BEL": ["brussels airlines"], "TAP": ["tap", "air portugal"],
        "FIN": ["finnair"], "NAX": ["norwegian"], "NOZ": ["norwegian"],
        "WZZ": ["wizz air"], "LOT": ["lot"], "CSA": ["czech airlines"],
        "AEE": ["aegean"], "THY": ["turkish airlines"], "AFL": ["aeroflot"],
        "ICE": ["icelandair"], "EIN": ["aer lingus"], "TVS": ["smartwings"],
        // Middle East / Africa
        "UAE": ["emirates"], "QTR": ["qatar airways"], "ETD": ["etihad"],
        "SVA": ["saudia"], "MSR": ["egyptair"], "ETH": ["ethiopian"],
        "RJA": ["royal jordanian"], "ELY": ["el al"], "SAA": ["south african"],
        "RAM": ["royal air maroc"], "KQA": ["kenya airways"],
        // Asia / Pacific / South America
        "QFA": ["qantas"], "ANZ": ["air new zealand"], "VOZ": ["virgin australia"],
        "SIA": ["singapore airlines"], "CPA": ["cathay"], "JAL": ["japan airlines"],
        "ANA": ["all nippon", "ana"], "KAL": ["korean air"], "AAR": ["asiana"],
        "CCA": ["air china"], "CES": ["china eastern"], "CSN": ["china southern"],
        "THA": ["thai airways"], "MAS": ["malaysia airlines"], "GIA": ["garuda"],
        "PAL": ["philippine airlines"], "AIC": ["air india"], "IGO": ["indigo"],
        "LAN": ["latam"], "TAM": ["latam"], "GLO": ["gol"], "AZU": ["azul"],
        "AVA": ["avianca"], "CMP": ["copa"], "ARG": ["aerolineas argentinas"],
        // Cargo
        "FDX": ["fedex", "federal express"], "UPS": ["ups", "united parcel"],
        "GTI": ["atlas air"], "CLX": ["cargolux"], "GEC": ["lufthansa cargo"],
        "CJT": ["cargojet"], "NCA": ["nippon cargo"], "CKS": ["kalitta"],
        "ABX": ["abx air"], "ATN": ["air transport international"],
        "BOX": ["aerologic"], "DHK": ["dhl"], "BCS": ["dhl"], "TAY": ["dhl"],
        "MPH": ["martinair"], "SQC": ["singapore airlines cargo"]
    ]

    private static let cargoDesignators: Set<String> = [
        "FDX", "UPS", "GTI", "CLX", "GEC", "CJT", "NCA", "CKS", "ABX", "ATN",
        "BOX", "DHK", "BCS", "TAY", "MPH", "SQC", "5X", "FX"
    ]
}

/// Picks a realistic stand for a flight from an airport's OpenStreetMap stand data.
///
/// The assignment is deliberately data-led rather than invented. When the aircraft is already
/// **parked on a mapped stand** at the departure field, that stand is the gate — nothing is
/// chosen at all, it is simply read off the aircraft's position. Otherwise the stand comes from
/// the airport's own OSM extract, and the tags that extract carries are used for as much of the
/// choice as they support —
///   • the airline's **own** stands when `operator` names the carrier flying;
///   • a stand **sized for the aircraft** when `aircraft:type` says what fits, preferring
///     the snuggest fit so a 737 doesn't take the widebody stand next to an empty 737 one;
///   • a terminal `aeroway=gate` for an airliner, a remote `parking_position` for light GA;
///   • cargo positions for freight flights and only for freight flights.
/// Where the tags say nothing — the common case at most fields — it falls back to a random
/// pick among the plausible stands, which is all the user asked for: a real stand at the
/// real airport that the taxi router can take them to.
///
/// Nothing here is authoritative. OSM stand data is community-sourced and incomplete, and a
/// real gate assignment comes from the airline, not from a map. This is a simulation aid.
enum GateAssigner {

    /// What the picker knows about the flight being assigned.
    struct FlightContext: Equatable {
        var callsign: String
        var airline: String
        var aircraftName: String?
        var aircraftClass: AircraftSizeClass
        /// Where the aircraft is **parked**, when it is: supplied only when telemetry says
        /// the aircraft is stopped on the ground, so a non-nil value always means "this is
        /// where the aircraft is sitting". Nil while airborne, taxiing, or before the first
        /// fix. When it lands on a mapped stand, the departure gate stops being a guess.
        var parkedPosition: GeoCoordinate?

        /// - Parameter aircraftClass: defaults to the class implied by `aircraftName`
        ///   (`medium` when Infinite Flight hasn't reported one).
        /// - Parameter parkedPosition: the aircraft's position, and only when it is stopped
        ///   on the ground there. The caller owns that test — see `AppModel`.
        init(callsign: String = "", airline: String = "", aircraftName: String? = nil,
             aircraftClass: AircraftSizeClass? = nil, parkedPosition: GeoCoordinate? = nil) {
            self.callsign = callsign
            self.airline = airline
            self.aircraftName = aircraftName
            self.aircraftClass = aircraftClass ?? AircraftSizeClass.classify(aircraftName: aircraftName)
            self.parkedPosition = parkedPosition
        }

        /// The airline designator flown, from the callsign ("UAL598" → "UAL"). Empty for a
        /// tail number or an unparseable callsign.
        var designator: String {
            AirlineDatabase.parse(callsign)?.designator ?? ""
        }

        /// Brand fragments to look for in a stand's `operator` tag: the designator's known
        /// brand names, the resolved telephony name, and the airline name on the plan (which
        /// for a plan read from Infinite Flight is already the telephony name).
        var operatorFragments: [String] {
            var out = StandOperators.brandNames(forDesignator: designator)
            if let telephony = AirlineDatabase.parse(callsign)?.telephony {
                out.append(telephony.lowercased())
            }
            let planAirline = airline.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            if planAirline.count >= 3 { out.append(planAirline) }
            // Two-letter fragments would match half the alphabet inside a longer name.
            return Array(Set(out.filter { $0.count >= 3 }))
        }

        /// Whether this is a freight flight (so cargo stands are the right ones).
        var isCargo: Bool {
            if StandOperators.isCargoDesignator(designator) { return true }
            let text = (airline + " " + callsign).lowercased()
            return text.contains("cargo") || text.contains("freight")
        }

        /// Airliners belong at a terminal gate; light GA belongs on a parking stand.
        var prefersTerminalGate: Bool { aircraftClass != .light }
    }

    /// One automatic assignment, with enough detail for the diagnostics log to explain
    /// *why* this stand was chosen when a pilot asks.
    struct Assignment: Equatable {
        var gate: String
        var osmID: String
        var coordinate: GeoCoordinate
        var matchedOperator: Bool
        var matchedAircraftType: Bool
        /// Whether the stand was read off the aircraft's own position — it is parked there —
        /// rather than chosen from the field's stand list. A `true` here is not a guess.
        var fromAircraftPosition: Bool
        /// How many stands were in the winning band the pick was drawn from.
        var tiedCandidates: Int
        /// How many usable stands the field offered at all.
        var totalCandidates: Int
        /// Short human-readable rationale, e.g. "operator match, A320-class stand".
        var reason: String
    }

    /// Whether the app may write a gate into a field for `icao`.
    ///
    /// This is the "if and only if the pilot left it blank" rule, and it is the whole safety
    /// story of the feature:
    ///   • blank field → assign;
    ///   • field still holding the value the app itself stamped, for a **different** airport
    ///     → the last flight's automatic gate is stale, so reassign;
    ///   • field holding the app's own stamp for **this** airport → already assigned, leave
    ///     it (so the gate doesn't re-roll on every telemetry tick);
    ///   • anything else → the pilot typed it. Never touch it.
    static func mayAssign(current: String, stamp: String, icao: String) -> Bool {
        let entered = current.trimmingCharacters(in: .whitespacesAndNewlines)
        if entered.isEmpty { return true }
        guard let stamp = AutoGateStamp(encoded: stamp),
              stamp.gate.caseInsensitiveCompare(entered) == .orderedSame else { return false }
        let key = icao.uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
        return stamp.icao != key
    }

    /// Whether a gate value is one the app assigned itself (so clearing the feature may
    /// clear it), rather than one the pilot typed.
    static func isAppAssigned(current: String, stamp: String) -> Bool {
        stampOwning(current: current, stamp: stamp) != nil
    }

    /// The app's marker when it still owns what is in the field, else nil (the pilot's gate,
    /// a blank field, or a marker that no longer matches).
    private static func stampOwning(current: String, stamp: String) -> AutoGateStamp? {
        let entered = current.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !entered.isEmpty, let decoded = AutoGateStamp(encoded: stamp),
              decoded.gate.caseInsensitiveCompare(entered) == .orderedSame else { return nil }
        return decoded
    }

    /// Whether a gate the app *chose* for this airport could still be improved on by reading
    /// the aircraft's position — i.e. the field holds the app's own chosen gate for this
    /// field, so a stand the aircraft turns out to be parked on should replace it.
    ///
    /// This is the "is it worth looking again" test, used before there is an assignment to
    /// judge; `mayUpgrade` is the test applied to the assignment that comes back. Both are
    /// false for a gate the pilot typed and for one already read off the aircraft's position.
    static func couldUpgrade(current: String, stamp: String, icao: String) -> Bool {
        guard let decoded = stampOwning(current: current, stamp: stamp) else { return false }
        return !decoded.fromAircraftPosition
            && decoded.icao == icao.uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Whether `assignment` may replace what the app itself last wrote for the same airport
    /// because it is better information: a stand the aircraft is demonstrably parked on beats
    /// one chosen from the field's stand list. Never touches a gate the pilot typed, and never
    /// swaps a position-derived gate back for a chosen one.
    static func mayUpgrade(current: String, stamp: String, icao: String,
                           to assignment: Assignment) -> Bool {
        assignment.fromAircraftPosition && couldUpgrade(current: current, stamp: stamp, icao: icao)
    }

    /// Pick a stand for the flight, or nil when the field's extract carries no stand that
    /// could be taxied to and named.
    static func assign(surface: AirportSurfaceModel, flight: FlightContext,
                       role: GateRole) -> Assignment? {
        var generator = SystemRandomNumberGenerator()
        return assign(surface: surface, flight: flight, role: role, using: &generator)
    }

    /// Seedable form, so the choice can be driven deterministically in tests.
    ///
    /// `role` decides one thing: only a **departure** gate may be read off the aircraft's
    /// parked position. The arrival gate is picked while the aircraft is still at the origin
    /// (or enroute), where its position says nothing about which stand it will end up on —
    /// and on a there-and-back leg, where origin and destination are the same field, reading
    /// it would hand back the stand the flight is leaving. Beyond that both ends are chosen
    /// from the same stand data in the same way.
    static func assign<G: RandomNumberGenerator>(surface: AirportSurfaceModel,
                                                flight: FlightContext,
                                                role: GateRole,
                                                using generator: inout G) -> Assignment? {
        let candidates = self.candidates(in: surface, flight: flight)
        guard !candidates.isEmpty else { return nil }

        // The aircraft is parked on one of these stands: that stand *is* the departure gate,
        // and no amount of tag matching beats knowing. Short-circuits the pick entirely.
        if role == .departure, let parked = standAircraftIsParkedOn(among: candidates, flight: flight) {
            return assignment(for: parked.candidate, fromAircraftPosition: true,
                              band: 1, total: candidates.count,
                              reason: "aircraft is parked on it "
                                + "(\(Int(parked.distanceMeters.rounded())) m from the mapped stand)")
        }

        // Lowest penalty wins, and the winner is drawn at random from everything tied on it
        // — so a field hands out a different one of its equally-suitable stands each flight
        // rather than always the first one in the extract.
        guard let bestPenalty = candidates.map({ $0.penalty }).min() else { return nil }
        let band = candidates.filter { $0.penalty == bestPenalty }
        guard let winner = band.randomElement(using: &generator) else { return nil }
        return assignment(for: winner, fromAircraftPosition: false,
                          band: band.count, total: candidates.count,
                          reason: reason(for: winner, flight: flight, band: band.count))
    }

    /// How close to a mapped stand a parked aircraft has to be for that stand to be read as
    /// the gate it is sitting on. Matches the radius the arrival completion already uses
    /// (`AppModel.gateArrivalRadiusMeters`), and for the same reason: an OSM stand is a single
    /// node, mapped anywhere from the jet-bridge head to the nose-wheel stop line, and the
    /// Infinite Flight scenery it is being compared against is a different survey again. The
    /// *nearest* stand wins, so at a tightly packed concourse the radius only decides whether
    /// the aircraft is on a stand at all, not which one.
    static let parkedAtStandMeters: Double = 80

    /// The stand a parked aircraft is sitting on, if any: the nearest assignable stand within
    /// `parkedAtStandMeters`. Nil when the aircraft isn't parked, has no position yet, or is
    /// nowhere near a mapped stand (out on a taxiway, or a field whose stands aren't mapped).
    ///
    /// Deliberately drawn from the same candidate list as the chosen gate, so the two hard
    /// exclusions still hold: a stand with no identifier can't be named in a clearance even if
    /// the aircraft is on it, and a de-icing pad or maintenance stand is not a gate to be
    /// pushed back off.
    private static func standAircraftIsParkedOn(among candidates: [Candidate],
                                                flight: FlightContext)
        -> (candidate: Candidate, distanceMeters: Double)? {
        guard let parked = flight.parkedPosition?.clLocation, parked.isValid else { return nil }
        var best: (candidate: Candidate, distanceMeters: Double)?
        for candidate in candidates {
            let distance = SurfaceGeometry.distanceMeters(parked, candidate.stand.coordinate.clLocation)
            guard distance <= parkedAtStandMeters else { continue }
            if let current = best, distance >= current.distanceMeters { continue }
            best = (candidate: candidate, distanceMeters: distance)
        }
        return best
    }

    private static func assignment(for candidate: Candidate, fromAircraftPosition: Bool,
                                   band: Int, total: Int, reason: String) -> Assignment {
        Assignment(gate: candidate.stand.name.trimmingCharacters(in: .whitespacesAndNewlines),
                   osmID: candidate.stand.osmID,
                   coordinate: candidate.stand.coordinate,
                   matchedOperator: candidate.matchedOperator,
                   matchedAircraftType: candidate.profile.maxClass != nil,
                   fromAircraftPosition: fromAircraftPosition,
                   tiedCandidates: band,
                   totalCandidates: total,
                   reason: reason)
    }

    // MARK: - Candidates

    private struct Candidate {
        var stand: SurfaceParking
        var profile: StandProfile
        var matchedOperator: Bool
        /// Lower is better. Built from the mismatches below, so every preference is a
        /// *soft* one: when a field offers nothing better, the least-bad stand is still
        /// assigned rather than leaving the pilot with no gate at all.
        var penalty: Int
        var fitGap: Int
    }

    /// Every stand at the field that could be assigned, each scored. Two exclusions are
    /// hard, because assigning them would be worse than assigning nothing: a stand with no
    /// identifier (it cannot be named in a clearance) and a service position (no flight
    /// parks on the de-icing pad).
    private static func candidates(in surface: AirportSurfaceModel,
                                   flight: FlightContext) -> [Candidate] {
        let fragments = flight.operatorFragments
        var out: [Candidate] = []
        // `routableStands` so a field that maps one stand as both a `gate` node and a
        // `parking_position` (KIAD) offers it once, as the stand an aircraft can park on.
        for stand in surface.routableStands {
            let name = stand.name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty else { continue }
            let profile = StandProfile.from(tags: stand.tags, standName: name)
            guard !profile.servicePosition else { continue }

            let matchedOperator = !fragments.isEmpty && profile.operatorNames.contains { op in
                fragments.contains { op.contains($0) }
            }

            var penalty = 0
            // The airline's own stand beats a stranger's.
            if !matchedOperator { penalty += 10 }
            // A stand whose tags say it fits beats one that simply isn't tagged, and among
            // tagged stands the snuggest fit wins: a 737 takes the 737 stand next to the
            // empty widebody one, not the widebody stand.
            let fitGap = profile.fitGap(for: flight.aircraftClass)
            if profile.maxClass == nil { penalty += 4 } else { penalty += min(fitGap, 3) }
            // Terminal gate vs. remote stand, by aircraft.
            let isTerminalGate = stand.kind == .gate
            if isTerminalGate != flight.prefersTerminalGate { penalty += 2 }
            // Freight and passenger stands are not interchangeable.
            if profile.cargo != flight.isCargo { penalty += 30 }
            // A stand the tags say is too small, a rotorcraft pad, or one the flight has no
            // access to: usable only when the field offers nothing else.
            if !profile.accepts(flight.aircraftClass) { penalty += 40 }
            if profile.helicopterOnly { penalty += 60 }
            if profile.restricted { penalty += 50 }

            out.append(Candidate(stand: stand, profile: profile, matchedOperator: matchedOperator,
                                 penalty: penalty, fitGap: fitGap))
        }
        return out
    }

    private static func reason(for candidate: Candidate, flight: FlightContext,
                               band: Int) -> String {
        var parts: [String] = []
        if candidate.matchedOperator {
            parts.append("operator match (\(candidate.profile.operatorNames.first ?? "airline"))")
        }
        if let maxClass = candidate.profile.maxClass {
            let fit = candidate.fitGap == 0 ? "exact fit" : "roomier than needed"
            parts.append("\(maxClass.title.lowercased())-class stand for a "
                + "\(flight.aircraftClass.title.lowercased()) aircraft (\(fit))")
        } else {
            parts.append("no aircraft-size tag")
        }
        parts.append(candidate.stand.kind == .gate ? "terminal gate" : "parking stand")
        if candidate.profile.cargo { parts.append("cargo position") }
        if candidate.profile.restricted { parts.append("access-restricted (nothing better available)") }
        parts.append(band > 1 ? "random pick of \(band) equal stands" : "only stand in its band")
        return parts.joined(separator: ", ")
    }
}
