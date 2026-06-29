import Foundation

/// A pilot intent recognized from spoken (or typed) input. Maps to an existing
/// pilot action in `AppModel`. Deterministic keyword matching — no AI/LLM.
enum PilotIntent: String, CaseIterable, Identifiable {
    case readback
    case sayAgain
    case unable
    case wilco
    case requestClearance
    case requestPushback
    case requestEngineStart
    case requestTaxi
    case readyForDeparture
    case requestTakeoff
    case requestHigher
    case requestLower
    case requestVectors
    case requestApproach
    case rideReport
    case destinationWeather
    case checkIn
    case unknown

    var id: String { rawValue }

    var title: String {
        switch self {
        case .readback: return "Read Back"
        case .sayAgain: return "Say Again"
        case .unable: return "Unable"
        case .wilco: return "Wilco"
        case .requestClearance: return "Request Clearance"
        case .requestPushback: return "Request Pushback"
        case .requestEngineStart: return "Request Engine Start"
        case .requestTaxi: return "Request Taxi"
        case .readyForDeparture: return "Ready for Departure"
        case .requestTakeoff: return "Request Takeoff"
        case .requestHigher: return "Request Higher"
        case .requestLower: return "Request Lower"
        case .requestVectors: return "Request Vectors"
        case .requestApproach: return "Request Approach"
        case .rideReport: return "Ride Report"
        case .destinationWeather: return "Destination Weather"
        case .checkIn: return "Check In"
        case .unknown: return "Unrecognized"
        }
    }
}

/// Deterministically maps a recognized phrase to a `PilotIntent` using ordered
/// keyword rules. Order matters: more specific phrases are checked first.
struct PilotIntentParser {

    func parse(_ text: String) -> PilotIntent {
        let t = " " + text.lowercased()
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: " ") + " "

        func has(_ needles: String...) -> Bool {
            needles.contains { t.contains(" \($0) ") || t.contains(" \($0)") }
        }
        // Looser contains for multi-word phrases.
        func contains(_ phrases: String...) -> Bool { phrases.contains { t.contains($0) } }

        if contains("say again", "repeat that", "repeat last") { return .sayAgain }
        if contains("unable") { return .unable }

        // Departure ground flow (checked before the readback catch-all, which also
        // matches "taxi"/"runway").
        if contains("request pushback", "request push back", "ready for push", "pushback", "push back") { return .requestPushback }
        if contains("request start", "request engine start", "engine start", "start up", "startup", "ready to start") { return .requestEngineStart }
        if contains("request clearance", "ifr clearance", "request ifr") { return .requestClearance }
        if contains("request taxi", "ready to taxi", "ready for taxi") { return .requestTaxi }
        if contains("request takeoff", "request take off", "request departure") { return .requestTakeoff }
        if contains("ready for departure", "ready for takeoff", "ready for take off", "holding short", "line up and wait", "lining up") { return .readyForDeparture }
        if contains("ride report", "ride reports", "turbulence report", "any chop", "ride along") { return .rideReport }
        if contains("destination weather", "field conditions", "weather at", "atis") { return .destinationWeather }
        if contains("vectors", "vector us", "vector me") { return .requestVectors }
        if contains("request approach", "cleared approach", "the approach", "ils approach", "rnav approach", "visual approach") { return .requestApproach }
        if contains("request higher", "higher", "climb to", "request climb", "request flight level") { return .requestHigher }
        if contains("request lower", "lower", "descend to", "request descent", "down to") { return .requestLower }
        if contains("check in", "checking in", "with you", "good day") { return .checkIn }
        if contains("wilco") { return .wilco }
        // A read-back is the catch-all for acknowledgements / clearances repeated.
        if contains("read back", "readback", "roger", "copy", "cleared", "maintain",
                    "taxi", "runway", "squawk", "contact", "wind") || has("affirm", "affirmative") {
            return .readback
        }
        return .unknown
    }
}
