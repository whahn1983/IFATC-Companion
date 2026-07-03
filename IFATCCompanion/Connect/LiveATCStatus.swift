import Foundation

/// Snapshot of live multiplayer / ATC-staffing context read from Infinite Flight.
/// Used so the companion can step aside when a human controller is present.
///
/// The Infinite Flight **Connect API** does not publish a map of which airport each
/// controller is working, so a bare "a human is controlling somewhere in this session"
/// flag can't tell us whether that controller is relevant to *this* flight. What it
/// *does* publish is the name of the frequency the pilot is **currently tuned to**
/// (`aircraft/0/systems/comm_radios/com_1/name`). That is the location-aware signal:
/// if the pilot has dialled a staffed controller's frequency, that controller is on
/// the pilot's own radio, so the companion must stand by; if the pilot is on UNICOM,
/// ATIS, or an unstaffed field, the companion keeps working — regardless of who else
/// is controlling elsewhere in the session.
struct LiveATCStatus: Equatable {
    /// True when the session appears to be on a multiplayer server.
    var multiplayerOnline: Bool = false
    /// Server name, if exposed (e.g. "Expert", "Training").
    var serverName: String?
    /// True when a human controller is staffing *some* frequency somewhere in the
    /// session. Presence only — it does not say which airport/facility they work, so it
    /// can't decide standby on its own.
    var humanControllerActive: Bool = false
    /// A human controller's reported name/username, if the manifest exposes one (e.g.
    /// "j_vonl"). Informational: it identifies *a* controller in the session but not the
    /// facility or frequency they work.
    var controllerName: String?
    /// The name of the frequency the pilot is tuned to right now, read live from COM1
    /// (`aircraft/0/systems/comm_radios/com_1/name`) — e.g. "Ground", "KSFO Tower",
    /// "Unicom", "ATIS". This is how the companion knows which frequency the pilot is
    /// actually on. Nil/empty when unavailable or not tuned to a named frequency.
    var tunedFrequencyName: String?
    /// The COM1 frequency in MHz, if exposed (diagnostics/logging only).
    var tunedFrequencyMHz: Double?

    static let none = LiveATCStatus()

    /// The facility the pilot is tuned to right now, resolved from the live COM1
    /// frequency name (e.g. "KSFO Tower" → `.tower`). Nil for UNICOM/ATIS or a name that
    /// doesn't map to a gate-to-gate position.
    var tunedFacility: ATCFacility? { ATCFacility.matching(name: tunedFrequencyName) }

    /// Whether the frequency the pilot is tuned to is a **staffed human ATC** frequency.
    ///
    /// Infinite Flight only offers a field's ATC frequencies while a human is actually
    /// working them — otherwise pilots use UNICOM — so a tuned COM name that isn't blank,
    /// UNICOM, or ATIS is a live human controller on the pilot's own radio. This is the
    /// per-frequency, location-aware test: it's true only while the pilot has tuned a
    /// controller, and never for a controller working a different airport elsewhere in
    /// the session. UNICOM is an unstaffed advisory and ATIS is an automated broadcast,
    /// so both are excluded — as is the "Unknown"/"None" placeholder Infinite Flight
    /// reports for COM1 when the pilot isn't tuned to any frequency at all.
    var tunedToHumanController: Bool {
        LiveATCStatus.isHumanControllerFrequency(tunedFrequencyName)
    }

    /// True when `name` is a live, staffed human-controller frequency. Blank, missing,
    /// UNICOM, ATIS, and the "Unknown"/"None" not-tuned placeholders all return false —
    /// there is no controller to defer to in any of those cases.
    static func isHumanControllerFrequency(_ name: String?) -> Bool {
        guard let raw = name?.trimmingCharacters(in: .whitespaces), !raw.isEmpty else {
            return false
        }
        let upper = raw.uppercased()
        return !upper.contains("UNICOM") && !upper.contains("ATIS")
            && upper != "UNKNOWN" && upper != "NONE"
    }

    /// Whether the companion should defer to a human controller right now: true exactly
    /// when the pilot is tuned to a staffed human ATC frequency.
    var companionShouldStandBy: Bool { tunedToHumanController }

    /// Short human-readable summary for the UI.
    var summary: String {
        if tunedToHumanController {
            let facility = tunedFacility?.title ?? tunedFrequencyName ?? "a controller"
            // Append the controller's name only when it adds information beyond the
            // frequency label itself.
            let who = controllerName.flatMap { name in
                name.caseInsensitiveCompare(facility) == .orderedSame ? nil : " (\(name))"
            } ?? ""
            return "Tuned to human ATC — \(facility)\(who). Companion standing by; follow the live controller."
        }
        if humanControllerActive {
            let who = controllerName.map { " (\($0))" } ?? ""
            return "Human ATC online\(who) — tune their frequency to hand off. Companion is covering your current frequency."
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
    ///   - controllerName: a staffed-controller name/username string, if exposed.
    ///   - facilityCount: number of active ATC facilities, if exposed.
    ///   - online: an "is online / multiplayer" flag, if exposed.
    ///   - serverName: the server name string, if exposed.
    ///   - tunedFrequencyName: the name of the frequency the pilot is tuned to (COM1),
    ///     if exposed — the location-aware standby signal.
    ///   - tunedFrequencyMHz: the tuned COM1 frequency in MHz, if exposed.
    func status(atcActive: Bool?,
                controllerName: String?,
                facilityCount: Int?,
                online: Bool?,
                serverName: String?,
                tunedFrequencyName: String? = nil,
                tunedFrequencyMHz: Double? = nil) -> LiveATCStatus {
        var status = LiveATCStatus()
        status.serverName = serverName?.trimmingCharacters(in: .whitespaces).nonEmpty
        status.multiplayerOnline = (online ?? false) || (status.serverName != nil)

        let cleanedController = controllerName?.trimmingCharacters(in: .whitespaces).nonEmpty
        // UNICOM and ATIS are not human controllers — UNICOM is an unstaffed advisory
        // frequency and ATIS is an automated broadcast, so neither counts as staffing.
        let nameIsHuman = cleanedController.map {
            let name = $0.uppercased()
            return !name.contains("UNICOM") && !name.contains("ATIS")
        } ?? false

        let humanByFlag = atcActive ?? false
        let humanByCount = (facilityCount ?? 0) > 0
        status.humanControllerActive = humanByFlag || humanByCount || nameIsHuman
        status.controllerName = nameIsHuman ? cleanedController : nil

        // Infinite Flight reports "Unknown"/"None" for COM1 when the pilot isn't tuned to
        // any frequency; treat those placeholders as "not tuned" so they never surface in
        // the UI or trip the guard.
        let cleanedTuned = tunedFrequencyName?.trimmingCharacters(in: .whitespaces).nonEmpty
        let tunedIsPlaceholder = cleanedTuned.map {
            let u = $0.uppercased()
            return u == "UNKNOWN" || u == "NONE"
        } ?? false
        status.tunedFrequencyName = tunedIsPlaceholder ? nil : cleanedTuned
        status.tunedFrequencyMHz = tunedFrequencyMHz

        // Being tuned to a named controller frequency is itself proof a human is on the
        // air, even if the standalone staffing flags didn't resolve on this IF version.
        if status.tunedToHumanController { status.humanControllerActive = true }
        return status
    }
}

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
