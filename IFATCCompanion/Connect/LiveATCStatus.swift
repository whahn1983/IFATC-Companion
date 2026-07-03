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
    /// facility the pilot is tuned to right now**. The guard is per-frequency: the
    /// companion only defers while the pilot is actually tuned to the frequency a
    /// human is confirmed to be working. It reads the currently tuned frequency and
    /// gates *only* when that frequency is human-controlled — anything else keeps the
    /// companion covering the sector. For example, with only Tower manned, the pilot
    /// still gets Clearance Delivery, Ground, Departure and Center from the companion,
    /// and only Tower defers.
    ///
    /// The guard never engages unless the staffed facility can be positively
    /// identified and matches the tuned one:
    /// - Ramp is never FAA ATC, so it can't be human-staffed — the companion always
    ///   handles the pushback / taxi-to-gate there.
    /// - ATIS is an automated broadcast, not a human controller, so it is excluded (a
    ///   frequency reported as ATIS never triggers the guard).
    /// - When a controller is active but the staffed facility can't be identified
    ///   (only a count/flag is exposed, or an unrecognised name), the companion does
    ///   **not** gate — we can't confirm the tuned frequency is the human's, so the
    ///   pilot keeps the companion rather than being locked out of an uncontrolled
    ///   frequency.
    func shouldStandBy(tunedTo facility: ATCFacility?) -> Bool {
        guard let staffed = staffedFacility else { return false }
        return facility == staffed
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
        // UNICOM and ATIS are not human controllers — UNICOM is an unstaffed advisory
        // frequency and ATIS is an automated broadcast, so neither should gate the app.
        let facilityIsHuman = cleanedFacility.map {
            let name = $0.uppercased()
            return !name.contains("UNICOM") && !name.contains("ATIS")
        } ?? false

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
