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
        case gForce
        case bankAngle
        case pitch
        case aircraftName
        case liveryName
        case nearestAirportICAO
        case callsign

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
            case .gForce: return ["gforce", "accelerationgforce"]
            case .bankAngle: return ["bankangledegrees", "bankangle", "bank"]
            case .pitch: return ["pitchdegrees", "pitch"]
            case .aircraftName: return ["aircraftname", "aircraftstate.name", "name"]
            case .liveryName: return ["liveryname", "livery"]
            case .nearestAirportICAO: return ["nearestairporticao", "nearestairport"]
            case .callsign: return ["callsign", "username", "displayname"]
            }
        }
    }

    private(set) var resolved: [Logical: IFManifestEntry] = [:]
    private(set) var allEntries: [IFManifestEntry] = []

    /// Resolve all logical keys against a freshly parsed manifest.
    /// Matching is exact-suffix first, then substring, honoring signature priority.
    func resolve(from entries: [IFManifestEntry]) {
        allEntries = entries
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

    /// Resolve a command id by keyword (used by UNICOM/command sending).
    func command(matchingAnyOf keywords: [String]) -> IFManifestEntry? {
        for kw in keywords {
            if let m = allEntries.first(where: { $0.matchKey.contains(kw) }) { return m }
        }
        return nil
    }
}
