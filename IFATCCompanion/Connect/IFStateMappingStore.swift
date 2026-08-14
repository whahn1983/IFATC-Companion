import Foundation

/// Maps logical aircraft-state concepts onto concrete manifest entries discovered
/// at runtime. No aircraft-specific state ids are hardcoded — instead each logical
/// key has a list of candidate name signatures matched against the live manifest,
/// with fallbacks. Resolved ids are cached here.
final class IFStateMappingStore {

    /// Logical states the app reads.
    enum Logical: String, CaseIterable {
        case latitude
        case longitude
        case altitudeMSL
        case altitudeAGL
        case groundSpeed
        case indicatedAirspeed
        case trueAirspeed
        case heading
        /// True (geographic) heading, distinct from the magnetic `heading`. Used to
        /// orient the aircraft symbol on the true-north map.
        case trueHeading
        case track
        case verticalSpeed
        case onGround
        /// Autopilot approach mode (APPR) armed/engaged.
        case approachMode
        /// Parking brake set/released.
        case parkingBrake
        case gForce
        case bankAngle
        case pitch
        case aircraftName
        case liveryName
        case nearestAirportICAO
        /// Full flight plan as a string (`aircraft/0/flightplan`), parsed best-effort.
        case flightPlan
        /// The detailed flight-plan document (`aircraft/0/flightplan/full_info`). This
        /// is the rich JSON Infinite Flight serves with per-fix planned altitudes and
        /// nested SID/STAR/approach procedure groups — the plain `flightplan` state only
        /// returns a collapsed summary of the legs, so the cruise altitude and procedure
        /// names live here.
        case flightPlanFullInfo
        /// The textual route (`aircraft/0/flightplan/route`). Across IF versions the
        /// `flightplan` state often serves only a collapsed summary of the legs, while
        /// the route string carries every enroute fix — so it is read as a richer
        /// fallback when the summary is sparse.
        case flightPlanRoute
        /// Per-fix coordinates (`aircraft/0/flightplan/coordinates`), read so the
        /// route can be drawn even when the summary carries no coordinates.
        case flightPlanCoordinates
        // Multiplayer / ATC-staffing detection (all optional; coverage varies).
        case atcActive
        case atcFacilityName
        case atcFacilityCount
        case isOnline
        case serverName
        /// The name of the frequency the pilot is currently tuned to on COM1
        /// (`aircraft/0/systems/comm_radios/com_1/name`) — e.g. "Ground", "KSFO Tower",
        /// "Unicom". This is the location-aware standby signal: it names the frequency
        /// the pilot is actually on, so the companion can defer only when that frequency
        /// is a staffed human controller.
        case tunedComName
        /// The COM1 frequency in MHz (`aircraft/0/systems/comm_radios/com_1/frequency`),
        /// read for diagnostics/logging.
        case tunedComFrequency
        /// `environment/wind_velocity` — the wind speed at the aircraft, in metres per
        /// second, as the sim itself models it. The app has always *solved* the wind by
        /// inverting the wind triangle rather than reading it, because the states weren't
        /// known to exist; read directly it needs no differencing of two ~450 kt vectors and
        /// survives the regimes the triangle can't solve (no track, no TAS, low speed).
        case windVelocity
        /// `environment/wind_direction_true` — the wind direction, in radians, true. Whether
        /// that is the direction the wind blows **from** (the meteorological convention the
        /// app uses internally) or the direction it blows **toward** is not something the
        /// state name settles, so it is reported alongside the solved wind rather than
        /// trusted blind — see `WeatherProviderDiagnostics.reportedWindText`.
        case windDirectionTrue

        /// Candidate name signatures (normalised, lowercased, separators removed),
        /// in priority order.
        var signatures: [String] {
            switch self {
            case .latitude: return ["aircraftlatitude", "latitude"]
            case .longitude: return ["aircraftlongitude", "longitude"]
            case .altitudeMSL: return ["altitudemsl", "msl", "altitude"]
            case .altitudeAGL: return ["altitudeagl", "agl"]
            case .groundSpeed: return ["groundspeed"]
            case .indicatedAirspeed: return ["indicatedairspeed", "ias"]
            case .trueAirspeed: return ["trueairspeed", "tas"]
            case .heading: return ["headingmagnetic", "heading", "magneticheading"]
            case .trueHeading: return ["headingtrue", "trueheading"]
            // `aircraft/0/course` is the course over the ground on the builds that expose it,
            // and is the only track-like *measurement* in the manifest — the entries actually
            // ending in "track" are flight-plan booleans (`is_on_flight_plan_track`), which the
            // numeric filter now keeps out of this key entirely.
            case .track: return ["gpstrack", "track", "courseovertheground", "course"]
            case .verticalSpeed: return ["verticalspeed", "vspeed", "verticalspeedfpm"]
            case .onGround: return ["isonground", "onground"]
            case .approachMode: return ["autopilotapproach", "approachmode", "apprmode", "isapproach", "appr", "approachhold"]
            case .parkingBrake: return ["parkingbrake", "parkbrake", "brakeparking"]
            case .gForce: return ["gforce", "accelerationgforce"]
            case .bankAngle: return ["bankangledegrees", "bankangle", "bank"]
            case .pitch: return ["pitchdegrees", "pitch"]
            case .aircraftName: return ["aircraftname", "aircraftstate.name", "name"]
            case .liveryName: return ["liveryname", "livery"]
            case .nearestAirportICAO: return ["nearestairporticao", "nearestairport"]
            case .flightPlan: return ["flightplan", "flightplanstring", "fpl"]
            case .flightPlanFullInfo: return ["flightplanfullinfo", "fullinfo", "flightplandetailed", "flightplaninfo"]
            case .flightPlanRoute: return ["flightplanroute", "planroute"]
            case .flightPlanCoordinates: return ["flightplancoordinates", "plancoordinates"]
            case .atcActive: return ["isatcactive", "atcactive", "atcisactive", "controlleractive"]
            case .atcFacilityName: return ["activeatcfacilityname", "atcfacilityname", "controllerfacility", "atcfacilit", "atcname", "atcusername", "controllername"]
            case .atcFacilityCount: return ["activeatcfacilitycount", "atcfacilitycount", "activeatccount", "atccount"]
            case .isOnline: return ["ismultiplayer", "isonline", "online", "multiplayer"]
            case .serverName: return ["servername", "sessionname", "server"]
            case .tunedComName: return ["com1name", "comm1name", "commradioscom1name", "activefrequencyname"]
            case .tunedComFrequency: return ["com1frequency", "comm1frequency", "commradioscom1frequency"]
            // `wind_gust_velocity` normalises to "windgustvelocity", which neither ends with
            // nor contains "windvelocity", so the steady wind is never read off the gust.
            case .windVelocity: return ["windvelocity", "windspeed"]
            case .windDirectionTrue: return ["winddirectiontrue", "winddirection"]
            }
        }

        /// What kind of value this key stands for. Name matching alone is not enough to
        /// identify a state: Infinite Flight's manifest carries ~1700 entries plus every
        /// command, and a signature matched across all of them lands on whatever shares a
        /// word. `track` matched `aircraft/0/is_on_flight_plan_track` — a *bool* — so the
        /// ground track read as 0° or 57°, and `parkingbrake` matched the `commands/…` entry
        /// beside the state. Filtering the candidates by type first makes the match mean what
        /// the name says.
        var valueKind: ValueKind {
            switch self {
            case .onGround, .approachMode, .parkingBrake, .atcActive, .isOnline:
                return .boolean
            case .aircraftName, .liveryName, .nearestAirportICAO, .flightPlan, .flightPlanFullInfo,
                 .flightPlanRoute, .flightPlanCoordinates, .atcFacilityName, .serverName, .tunedComName:
                return .text
            default:
                return .numeric
            }
        }
    }

    /// The family of manifest types a logical key may resolve onto. Commands (`.unknown`)
    /// are excluded from all three — they are actions, never readable values.
    enum ValueKind {
        case numeric
        case boolean
        case text

        func accepts(_ type: IFDataType) -> Bool {
            switch self {
            // A measurement is never a bool; an int one (a count, an enum-backed state) is fine.
            case .numeric: return type == .int32 || type == .float || type == .double || type == .long
            // Some builds expose an on/off state as an int rather than a bool.
            case .boolean: return type == .boolean || type == .int32
            case .text: return type == .string
            }
        }
    }

    private(set) var resolved: [Logical: IFManifestEntry] = [:]

    /// A group of states that must be read in one angular convention, because they come out of
    /// one part of the sim together.
    ///
    /// **Each family decides its own units, from its own readings.** They were decided together,
    /// on the reasoning that every angle comes out of "the same API in the same convention" —
    /// and that is exactly where the field failure came from: `environment/wind_direction_true`
    /// reports the weather in degrees on builds whose *aircraft* states are radians. One wind
    /// from 331 then witnessed "degrees" on every single snapshot, which pinned the aircraft's
    /// heading — 084° magnetic arrives as 1.466 rad, and read as degrees it is shown as 001° —
    /// and kept re-witnessing, so the contradiction below never got a run to accumulate either.
    /// The nose sat on north on the Flight tab, the taxi map and the weather map at once.
    ///
    /// The aircraft's own attitude states (`aircraft/0/heading_magnetic`, `heading_true`,
    /// the ground track, and the bank/pitch that follow them) genuinely are one group. The
    /// weather is a different subsystem and gets no vote on the nose.
    enum AngleFamily: CaseIterable {
        /// `aircraft/0/…` — heading, true heading, ground track, bank, pitch.
        case aircraft
        /// `environment/…` — the reported wind direction.
        case environment
    }

    /// Whether the aircraft's angle states are currently read as **degrees** rather than radians.
    var anglesProvedDegrees: Bool { units[.aircraft]?.provedDegrees ?? false }
    /// The same decision for the sim's reported wind direction, made from its own readings.
    var windAnglesProvedDegrees: Bool { units[.environment]?.provedDegrees ?? false }

    private var units: [AngleFamily: AngleUnits] = [:]

    /// One angle exactly as the sim reported it, before any conversion.
    struct RawAngleReading {
        let name: String
        let value: Double
    }

    /// The raw angle readings behind the current units decisions. Logged whenever a decision
    /// changes: the whole radians-vs-degrees question turns on the *magnitude* of these
    /// numbers, and until now nothing anywhere recorded them — so a heading shown as 001°
    /// while the sim's own panel read 084° could only be argued about.
    private(set) var lastRawAngles: [RawAngleReading] = []

    func noteRawAngles(_ readings: [RawAngleReading]) { lastRawAngles = readings }

    /// Consecutive witnessing snapshots required before degrees is taken as proved.
    static let degreeWitnessesToProve = 2
    /// Radian-only snapshots required before a degrees proof is treated as contradicted.
    static let radianSamplesToDisprove = 12
    /// Distinct quadrants of the 0…2π circle the heading must visit in that run.
    static let radianQuadrantsToDisprove = 3

    /// Record what one telemetry snapshot's angles witnessed about one family's units.
    ///
    /// - Parameters:
    ///   - family: which group of states these readings came from. A family is never told
    ///     about another's readings — see `AngleFamily`.
    ///   - provesDegrees: an angle in the snapshot was too large to be radians *and* still a
    ///     plausible compass angle. A reading beyond a full circle in degrees is a corrupt
    ///     read, not evidence of the units.
    ///   - anyAboveRadianCircle: any angle exceeded a full circle in radians, plausible or
    ///     not. Only used to keep the radians disproof honest — a build genuinely reporting
    ///     degrees produces these constantly, so they reset the disproof run.
    ///   - rawHeading: the snapshot's raw heading, before any conversion. The disproof needs a
    ///     value that sweeps the compass over a flight, so only the aircraft family has one.
    func noteAngleSnapshot(family: AngleFamily,
                           provesDegrees: Bool,
                           anyAboveRadianCircle: Bool,
                           rawHeading: Double?) {
        var state = units[family] ?? AngleUnits()
        state.note(provesDegrees: provesDegrees,
                   anyAboveRadianCircle: anyAboveRadianCircle,
                   rawHeading: rawHeading)
        units[family] = state
    }

    /// One family's radians-vs-degrees decision, and the evidence behind it.
    ///
    /// The decision persists across snapshots, because one snapshot can fail to witness
    /// anything: with the nose and the track both within ~6° of north there is no angle too
    /// large to be radians, so a build reporting degrees was read as radians and every angle in
    /// it multiplied by 57.3 — a 4° nose becoming 229°, and the two headings' one-degree
    /// difference becoming tens of degrees of "variation" that went straight into the departure
    /// vector. A north-facing runway lines an aircraft up for exactly that and holds it there.
    ///
    /// But it is **not** taken on a single reading and **not** irreversible, because both of
    /// those turn one bad number into a session-long fault — a radians build read as degrees
    /// shows every heading in 0…6.28 as 0–6°, so the nose reads north whichever way it points:
    ///
    /// - **Proof needs corroboration** (`degreeWitnessesToProve` consecutive snapshots). A
    ///   genuine degrees build witnesses on every snapshot the nose is off north, so it still
    ///   settles within a second; a lone anomalous reading — a desynchronised response frame,
    ///   a state that isn't the angle its name suggests — no longer settles anything.
    /// - **The proof can be contradicted.** No single reading can prove radians (every radian
    ///   value is also a valid degree value), but a *run* of them can: a heading that visits
    ///   three of the four quadrants of the 0…2π circle without one reading ever exceeding a
    ///   full circle in radians is an aircraft turning through the compass, not one holding
    ///   within a 6° arc of north for a dozen samples. That clears the proof and the headings
    ///   come right without a relaunch.
    private struct AngleUnits {
        private(set) var provedDegrees = false
        private var consecutiveDegreeWitnesses = 0
        private var radianOnlySamples = 0
        private var radianHeadingQuadrants: Set<Int> = []

        mutating func note(provesDegrees: Bool, anyAboveRadianCircle: Bool, rawHeading: Double?) {
            guard provedDegrees else {
                if provesDegrees {
                    consecutiveDegreeWitnesses += 1
                    if consecutiveDegreeWitnesses >= IFStateMappingStore.degreeWitnessesToProve {
                        provedDegrees = true
                        resetDisproof()
                    }
                } else {
                    consecutiveDegreeWitnesses = 0
                }
                return
            }
            // Proved — watch for the contradiction that means it was taken in error.
            if anyAboveRadianCircle {
                resetDisproof()
                return
            }
            guard let rawHeading, rawHeading.isFinite else { return }
            radianOnlySamples += 1
            radianHeadingQuadrants.insert(IFStateMappingStore.radianQuadrant(of: rawHeading))
            if radianOnlySamples >= IFStateMappingStore.radianSamplesToDisprove,
               radianHeadingQuadrants.count >= IFStateMappingStore.radianQuadrantsToDisprove {
                provedDegrees = false
                consecutiveDegreeWitnesses = 0
                resetDisproof()
            }
        }

        private mutating func resetDisproof() {
            radianOnlySamples = 0
            radianHeadingQuadrants.removeAll()
        }
    }

    /// Which quarter of the 0…2π circle a raw heading falls in (0–3), wrapping negatives.
    static func radianQuadrant(of value: Double) -> Int {
        let circle = 2 * Double.pi
        var wrapped = value.truncatingRemainder(dividingBy: circle)
        if wrapped < 0 { wrapped += circle }
        return min(3, Int(wrapped / (circle / 4)))
    }

    /// Resolve all logical keys against a freshly parsed manifest.
    /// Matching is exact-suffix first, then substring, honoring signature priority.
    /// A fresh manifest means a fresh connection, so the units proof starts over with it —
    /// the next session may be a different Infinite Flight build.
    func resolve(from entries: [IFManifestEntry]) {
        resolved.removeAll()
        units.removeAll()
        for logical in Logical.allCases {
            if let match = bestMatch(for: logical.signatures, in: entries, kind: logical.valueKind) {
                resolved[logical] = match
            }
        }
    }

    func entry(for logical: Logical) -> IFManifestEntry? { resolved[logical] }

    var unresolvedKeys: [Logical] {
        Logical.allCases.filter { resolved[$0] == nil }
    }

    /// Find the best manifest entry for an ordered list of candidate signatures, considering
    /// only entries that could actually carry the value the logical key stands for.
    private func bestMatch(for signatures: [String], in entries: [IFManifestEntry],
                           kind: ValueKind) -> IFManifestEntry? {
        let candidates = entries.filter { kind.accepts($0.type) }
        for sig in signatures {
            // Prefer an entry whose normalised key ends with the signature.
            if let suffix = candidates.first(where: { $0.matchKey.hasSuffix(sig) }) {
                return suffix
            }
            // Then any entry containing the signature.
            if let contains = candidates.first(where: { $0.matchKey.contains(sig) }) {
                return contains
            }
        }
        return nil
    }
}
