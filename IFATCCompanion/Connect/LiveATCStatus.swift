import Foundation

/// Snapshot of live multiplayer / ATC-staffing context read from Infinite Flight.
/// Used so the companion can step aside when a human controller is present.
struct LiveATCStatus: Equatable {
    /// True when the session appears to be on a multiplayer server.
    var multiplayerOnline: Bool = false
    /// Server name, if exposed (e.g. "Expert", "Training").
    var serverName: String?
    /// True when a human controller is staffing a relevant frequency.
    var humanControllerActive: Bool = false
    /// The staffed facility name, if known (e.g. "Tower", "Approach").
    var activeFacility: String?

    static let none = LiveATCStatus()

    /// Whether a human controller is staffing *some* relevant frequency. This is a
    /// presence signal for the UI/diagnostics — it does not, on its own, mean the
    /// companion should stand by. The per-frequency decision is `shouldStandBy(tunedTo:)`.
    var shouldStandBy: Bool { humanControllerActive }

    /// The FAA facility a human controller is working, resolved from
    /// `activeFacility` when it maps to a gate-to-gate position. Nil when no
    /// controller is active or the reported name can't be matched (e.g. UNICOM/ATIS,
    /// or an IF version that exposes only a facility count).
    var staffedFacility: ATCFacility? {
        humanControllerActive ? ATCFacility.matching(name: activeFacility) : nil
    }

    /// Whether the companion should stand aside for a human controller **given the
    /// facility the pilot is tuned to right now**. The guard is per-frequency: it
    /// applies only while the pilot is on the staffed controller's frequency. Tuning
    /// off it — to another sector the human isn't working, or to no frequency — lifts
    /// the guard so the companion resumes covering that sector. For example, with only
    /// Ground and Tower manned, the pilot can still get Clearance Delivery before the
    /// push, and after departing and leaving Tower the companion picks up Departure,
    /// then Center.
    ///
    /// - Ramp is never FAA ATC, so it can't be human-staffed — the companion always
    ///   handles the pushback / taxi-to-gate there.
    /// - When a controller is active but the facility can't be identified (only a
    ///   count/flag is exposed, with no usable name), the guard falls back to standing
    ///   by, since we can't safely tell whether the tuned frequency is the staffed one.
    func shouldStandBy(tunedTo facility: ATCFacility?) -> Bool {
        guard humanControllerActive else { return false }
        if facility == .ramp { return false }
        if let staffed = staffedFacility { return facility == staffed }
        return true
    }

    /// Short human-readable summary for the UI.
    var summary: String {
        if humanControllerActive {
            let f = activeFacility.map { " (\($0))" } ?? ""
            return "Human ATC active\(f) — companion standing by."
        }
        if multiplayerOnline {
            let s = serverName.map { " on \($0)" } ?? ""
            return "Multiplayer\(s) — no human ATC detected."
        }
        return "Solo / no human ATC detected."
    }
}

/// Deterministically derives a `LiveATCStatus` from raw values read off the
/// Connect manifest. Tolerant of missing fields — Connect coverage varies by
/// version, so each signal is optional and the detector degrades gracefully.
struct LiveATCDetector {

    /// - Parameters:
    ///   - atcActive: an explicit "is ATC active" flag, if exposed.
    ///   - facilityName: a staffed-facility name string, if exposed.
    ///   - facilityCount: number of active ATC facilities, if exposed.
    ///   - online: an "is online / multiplayer" flag, if exposed.
    ///   - serverName: the server name string, if exposed.
    func status(atcActive: Bool?,
                facilityName: String?,
                facilityCount: Int?,
                online: Bool?,
                serverName: String?) -> LiveATCStatus {
        var status = LiveATCStatus()
        status.serverName = serverName?.trimmingCharacters(in: .whitespaces).nonEmpty
        status.multiplayerOnline = (online ?? false) || (status.serverName != nil)

        let cleanedFacility = facilityName?.trimmingCharacters(in: .whitespaces).nonEmpty
        // A UNICOM "facility" is not a human controller.
        let facilityIsHuman = cleanedFacility.map { !$0.uppercased().contains("UNICOM") } ?? false

        let humanByFlag = atcActive ?? false
        let humanByCount = (facilityCount ?? 0) > 0
        status.humanControllerActive = humanByFlag || humanByCount || facilityIsHuman
        status.activeFacility = facilityIsHuman ? cleanedFacility : nil
        return status
    }
}

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
