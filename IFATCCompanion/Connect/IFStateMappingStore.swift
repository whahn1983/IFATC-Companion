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
            case .track: return ["gpstrack", "track", "courseovertheground"]
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
            }
        }
    }

    private(set) var resolved: [Logical: IFManifestEntry] = [:]

    /// Resolve all logical keys against a freshly parsed manifest.
    /// Matching is exact-suffix first, then substring, honoring signature priority.
    func resolve(from entries: [IFManifestEntry]) {
        resolved.removeAll()
        for logical in Logical.allCases {
            if let match = bestMatch(for: logical.signatures, in: entries) {
                resolved[logical] = match
            }
        }
    }

    func entry(for logical: Logical) -> IFManifestEntry? { resolved[logical] }

    var unresolvedKeys: [Logical] {
        Logical.allCases.filter { resolved[$0] == nil }
    }

    /// Find the best manifest entry for an ordered list of candidate signatures.
    private func bestMatch(for signatures: [String], in entries: [IFManifestEntry]) -> IFManifestEntry? {
        for sig in signatures {
            // Prefer an entry whose normalised key ends with the signature.
            if let suffix = entries.first(where: { $0.matchKey.hasSuffix(sig) }) {
                return suffix
            }
            // Then any entry containing the signature.
            if let contains = entries.first(where: { $0.matchKey.contains(sig) }) {
                return contains
            }
        }
        return nil
    }
}
