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

    /// Whether the companion should stand by (defer to a human controller).
    var shouldStandBy: Bool { humanControllerActive }

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
