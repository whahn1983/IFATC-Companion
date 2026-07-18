import Foundation

/// A real-world ATIS (Automatic Terminal Information Service) broadcast for an
/// airport, as published by the FAA Digital ATIS (D-ATIS) feed.
///
/// **Source.** D-ATIS is the FAA's own digital text of the spoken ATIS. This app
/// reads it from the free, public, keyless community endpoint at
/// `datis.clowd.io` (built and maintained by the vATIS project, sourced from the
/// FAA SWIM system) — the same "direct-to-public-service" pattern the app already
/// uses for NOAA aviation weather. Coverage is the set of US airports that publish
/// D-ATIS (major fields); an airport with no D-ATIS simply returns nothing, and the
/// whole ATIS feature then quietly disappears for that field (no button, no code
/// appended anywhere). Nothing here is ever fabricated — a missing airport means a
/// missing feature, never an invented ATIS.
///
/// A field may publish a single **combined** ATIS or **separate** arrival and
/// departure ATIS, each with its own information letter, so a report holds one or
/// more `Part`s.
struct AirportATIS: Equatable, Codable {

    /// Which operation an ATIS part applies to.
    enum Kind: String, Codable {
        case combined
        case arrival
        case departure

        /// Map the D-ATIS `type` field ("arr" / "dep" / "combined") onto a `Kind`.
        init(apiType: String) {
            switch apiType.lowercased() {
            case "arr", "arrival": self = .arrival
            case "dep", "departure": self = .departure
            default: self = .combined
            }
        }
    }

    /// A single ATIS part (a combined ATIS, or one of arrival / departure).
    struct Part: Equatable, Codable {
        var kind: Kind
        /// The ATIS information code letter, uppercased single character ("A"…"Z"),
        /// or empty when the feed didn't supply a recognizable code.
        var letter: String
        /// The raw D-ATIS text exactly as published (kept verbatim for display).
        var text: String
    }

    /// ICAO the ATIS is for (e.g. "KLAX").
    var airport: String
    /// The published parts (one combined, or arrival + departure).
    var parts: [Part]
    /// When the app fetched this report.
    var fetchedAt: Date

    // MARK: - Access

    /// The ATIS part relevant to a phase of flight: the arrival part on arrival, the
    /// departure part on departure — each falling back to a combined ATIS, then any
    /// part, so a field that publishes only one still resolves.
    func part(arrival: Bool) -> Part? {
        let preferred: Kind = arrival ? .arrival : .departure
        return parts.first { $0.kind == preferred }
            ?? parts.first { $0.kind == .combined }
            ?? parts.first
    }

    /// The information code letter for a phase, uppercased ("A"), or nil when the
    /// relevant part carries no recognizable single-letter code.
    func letter(arrival: Bool) -> String? {
        guard let raw = part(arrival: arrival)?.letter.trimmingCharacters(in: .whitespaces),
              raw.count == 1, let ch = raw.first, ch.isLetter else { return nil }
        return raw.uppercased()
    }
}

/// A read-only snapshot of ATIS state for the Diagnostics tab: which fields ATIS was
/// requested for, whether it was received, and the information code carried / reported.
struct ATISDiagnostics: Equatable {
    var departureAirport = ""
    var departureReceived = false
    var departureLetter: String?
    var arrivalAirport = ""
    var arrivalReceived = false
    var arrivalLetter: String?
    /// Whether the aircraft is within the arrival-ATIS range (100 NM of destination).
    var withinArrivalRange = false
    /// The information code the pilot has actually received (by tuning) and will
    /// report to ATC, per phase. Nil until the pilot tunes ATIS for that phase.
    var reportedDeparture: String?
    var reportedArrival: String?
}
