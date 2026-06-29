import Foundation

/// How an airport's ramp/apron area is controlled. Ramp control is NOT FAA ATC —
/// it is a local airport, airline, or company procedure. These styles only change
/// how the *simulated* ramp conversation reads; none of them grant runway,
/// movement-area, route, altitude, heading, or approach authority.
enum RampType: String, Codable, CaseIterable, Identifiable {
    /// A dedicated ramp controller (e.g. ATL, ORD non-movement ramp towers).
    case rampControl
    /// European-style apron control.
    case apronControl
    /// Airline/company ramp coordinator (most US hubs).
    case companyRamp
    /// Unstaffed — advisory/CTAF-style ramp self-announce only.
    case advisoryOnly
    /// No ramp layer; the pilot contacts Ground directly.
    case none

    var id: String { rawValue }

    var title: String {
        switch self {
        case .rampControl: return "Ramp Control"
        case .apronControl: return "Apron Control"
        case .companyRamp: return "Company Ramp"
        case .advisoryOnly: return "Advisory Only"
        case .none: return "No Ramp"
        }
    }

    /// Whether this style speaks "face <dir>" instead of "tail <dir>" for pushes.
    var usesFaceDirection: Bool { self == .apronControl }
}

/// Per-airport ramp behavior so the simulated ramp conversation can vary without
/// code changes. When no airport profile exists, `RampProfile.generic` is used.
///
/// IMPORTANT: every string here is *local/simulated ramp phraseology*, documented
/// as non-FAA. Ramp must never issue runway, takeoff, landing, crossing, IFR
/// route, altitude, heading, SID, STAR, or approach instructions.
struct RampProfile: Codable, Equatable, Identifiable {
    var airportICAO: String          // "" for the generic profile
    var rampName: String             // spoken position name, e.g. "Ramp"
    var rampFrequency: Double        // simulated ramp frequency (MHz)
    var rampType: RampType
    var requiresPushApproval: Bool
    var requiresEngineStartCoordination: Bool
    var usesSpots: Bool
    var defaultSpotNames: [String]
    var defaultPushDirections: [String]   // "west", "east", "north", "south"
    var defaultGateNamingStyle: String    // free-text note, e.g. "letter+number (B44)"
    /// Template for the Ramp→Ground handoff. `{freq}`/`{spot}` placeholders allowed.
    var handoffToGroundPhrase: String
    /// Template for the arrival ramp entry. `{gate}`/`{alley}` placeholders allowed.
    var arrivalRampEntryPhrase: String
    var notes: String
    var reviewStatus: String

    var id: String { airportICAO.isEmpty ? "generic" : airportICAO }

    /// Generic US airline ramp profile used when no airport-specific profile is
    /// known. Conservative: requires push approval, uses tail directions, hands
    /// off to Ground at a generic spot/movement-area boundary.
    static let generic = RampProfile(
        airportICAO: "",
        rampName: "Ramp",
        rampFrequency: 131.0,
        rampType: .companyRamp,
        requiresPushApproval: true,
        requiresEngineStartCoordination: false,
        usesSpots: true,
        defaultSpotNames: [],
        defaultPushDirections: [],
        defaultGateNamingStyle: "as entered",
        handoffToGroundPhrase: "contact Ground {freq}",
        arrivalRampEntryPhrase: "proceed to the gate via the ramp",
        notes: "Generic simulated airline ramp. Not FAA ATC. No precise spots are "
            + "invented unless an airport-specific profile is supplied.",
        reviewStatus: "simulated")

    /// Built-in airport ramp profiles. Intentionally small — most airports use the
    /// generic profile. Entries here are documented as simulated/best-effort and
    /// flagged for airport-specific validation.
    static let known: [String: RampProfile] = [
        // KATL — dedicated ramp towers, spots, tail directions. Simulated.
        "KATL": RampProfile(
            airportICAO: "KATL", rampName: "Ramp", rampFrequency: 129.625,
            rampType: .rampControl, requiresPushApproval: true,
            requiresEngineStartCoordination: false, usesSpots: true,
            defaultSpotNames: ["1", "2", "3", "4", "5"],
            defaultPushDirections: ["north", "south"],
            defaultGateNamingStyle: "concourse+number (T1, A12)",
            handoffToGroundPhrase: "monitor Ground {freq} at spot {spot}",
            arrivalRampEntryPhrase: "proceed to the gate via the ramp",
            notes: "ATL uses ramp towers and spots. Spot numbers/frequencies are "
                + "illustrative only.",
            reviewStatus: "airportSpecific-needsReview"),
        // KORD — apron-style alleys and spots. Simulated.
        "KORD": RampProfile(
            airportICAO: "KORD", rampName: "Ramp", rampFrequency: 129.6,
            rampType: .rampControl, requiresPushApproval: true,
            requiresEngineStartCoordination: false, usesSpots: true,
            defaultSpotNames: ["5", "7", "9"],
            defaultPushDirections: ["east", "west"],
            defaultGateNamingStyle: "concourse+number (B12)",
            handoffToGroundPhrase: "contact Ground {freq} at spot {spot}",
            arrivalRampEntryPhrase: "proceed to the gate via the inner alley",
            notes: "ORD ramp/alley layout. Spots/frequency illustrative only.",
            reviewStatus: "airportSpecific-needsReview")
    ]

    /// Resolve the ramp profile for an airport, falling back to the generic one.
    static func profile(for icao: String) -> RampProfile {
        known[icao.uppercased()] ?? generic
    }
}
