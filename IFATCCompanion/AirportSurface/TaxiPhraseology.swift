import Foundation

/// Deterministic Ground taxi + runway-crossing phraseology derived from a calculated
/// `SurfaceTaxiRoute`. Wraps the existing `PhraseologyEngine` for callsign / phonetics
/// so the output flows through the same transcript/read-back machinery.
///
/// Rules enforced here (also covered by tests):
///  - never says "cleared to taxi";
///  - always names the assigned runway and the ordered taxiway sequence;
///  - always includes an explicit hold-short instruction;
///  - never implies a runway crossing is included in the taxi clearance — crossings are
///    issued as **separate** Ground clearances with their own read-back;
///  - never says "cross all runways" or gives vague crossing authority;
///  - never invents taxiway names (an empty sequence renders as "available taxiways");
///  - a crossing read-back always contains the runway identifier;
///  - low-confidence data downgrades to conservative language.
///
/// Everything here is framed as simulated ATC.
struct TaxiPhraseology {

    let engine: PhraseologyEngine
    private var icao: Bool { engine.icao }

    // MARK: - Departure taxi clearance

    /// Ground taxi clearance to the assigned departure runway from a calculated route.
    /// Names the runway, the taxiway sequence, and an explicit hold-short. Crossings are
    /// NOT authorized here.
    ///
    /// When the route crosses a runway, the clearance holds the pilot short of the **first**
    /// runway crossing (`holdShortCrossing`) — the runway ahead they must await a separate
    /// crossing clearance for — exactly as a real Ground controller phrases it ("taxi to
    /// runway 36 via A, C, hold short runway 09"). With no crossing it holds short of the
    /// assigned runway itself.
    func taxiClearance(cs: PhraseologyEngine.Callsign, route: SurfaceTaxiRoute, runway: String,
                       holdShortCrossing: String? = nil) -> ATCTransmission {
        let seq = sequenceText(route)
        let rwySpoken = Phonetic.runway(runway, icao: icao)
        let holdRwy = holdShortCrossing.flatMap { $0.isEmpty ? nil : $0 } ?? runway
        // Hold-short instructions name both directions of the physical runway
        // ("hold short runway 6R-24L" / "hold short runway six right two four left").
        let holdDisplay = Phonetic.runwayPairDisplay(holdRwy)
        let holdSpoken = Phonetic.runwayPairSpoken(holdRwy, icao: icao)
        let display = "\(cs.display), taxi to runway \(runway) via \(seq.display), hold short runway \(holdDisplay)."
        let spoken = "\(cs.spoken), taxi to runway \(rwySpoken) via \(seq.spoken), hold short runway \(holdSpoken)."
        var tx = ATCTransmission(sender: .atc, facility: .ground, displayText: display, spokenText: spoken)
        tx.readback = ATCTransmission.Readback(
            displayText: "Taxi to runway \(runway) via \(seq.display), hold short runway \(holdDisplay), \(cs.display).",
            spokenText: "Taxi to runway \(rwySpoken) via \(seq.spoken), hold short runway \(holdSpoken), \(cs.spoken).",
            facility: .ground)
        return tx
    }

    /// Conservative Ground instruction used when routing confidence is too low to issue
    /// a detailed route. Names the runway but no specific taxiways, and holds short of
    /// all runways.
    func lowConfidenceTaxi(cs: PhraseologyEngine.Callsign, runway: String) -> ATCTransmission {
        let rwySpoken = Phonetic.runway(runway, icao: icao)
        let display = "\(cs.display), detailed taxi routing is unavailable. Taxi toward runway \(runway), hold short of all runways, and continue using the simulator airport diagram."
        let spoken = "\(cs.spoken), detailed taxi routing is unavailable. Taxi toward runway \(rwySpoken), hold short of all runways, and continue using the simulator airport diagram."
        var tx = ATCTransmission(sender: .atc, facility: .ground, displayText: display, spokenText: spoken)
        tx.readback = ATCTransmission.Readback(
            displayText: "Taxi toward runway \(runway), hold short of all runways, \(cs.display).",
            spokenText: "Taxi toward runway \(rwySpoken), hold short of all runways, \(cs.spoken).",
            facility: .ground)
        return tx
    }

    // MARK: - Arrival taxi-to-gate

    /// Ground taxi-to-gate clearance from a calculated arrival route. When the route
    /// crosses a runway it holds the pilot short of the first crossing (`holdShortCrossing`)
    /// — the crossing is then authorized separately, just as on departure.
    func arrivalTaxi(cs: PhraseologyEngine.Callsign, route: SurfaceTaxiRoute, gate: String,
                     holdShortCrossing: String? = nil) -> ATCTransmission {
        let seq = sequenceText(route)
        let g = gate.trimmingCharacters(in: .whitespaces)
        let destDisplay = g.isEmpty ? "parking" : "gate \(g)"
        let destSpoken = g.isEmpty ? "parking" : "gate \(Phonetic.spellToken(g, icao: icao))"
        let holdRwy = holdShortCrossing.flatMap { $0.isEmpty ? nil : $0 }
        // Hold-short instructions name both directions of the physical runway.
        let holdDisplay = holdRwy.map { ", hold short runway \(Phonetic.runwayPairDisplay($0))" } ?? ""
        let holdSpoken = holdRwy.map { ", hold short runway \(Phonetic.runwayPairSpoken($0, icao: icao))" } ?? ""
        let display = "\(cs.display), taxi to \(destDisplay) via \(seq.display)\(holdDisplay)."
        let spoken = "\(cs.spoken), taxi to \(destSpoken) via \(seq.spoken)\(holdSpoken)."
        var tx = ATCTransmission(sender: .atc, facility: .ground, displayText: display, spokenText: spoken)
        tx.readback = ATCTransmission.Readback(
            displayText: "Taxi to \(destDisplay) via \(seq.display)\(holdDisplay), \(cs.display).",
            spokenText: "Taxi to \(destSpoken) via \(seq.spoken)\(holdSpoken), \(cs.spoken).",
            facility: .ground)
        return tx
    }

    // MARK: - Runway crossing

    /// A **separate** Ground runway-crossing clearance. Includes the runway id, the
    /// taxiway/intersection name when known, and an optional continuation. The read-back
    /// contains the runway identifier.
    func crossingClearance(cs: PhraseologyEngine.Callsign, runwayIdent: String,
                           atTaxiway: String? = nil, continueVia: String? = nil) -> ATCTransmission {
        // A crossing spans the whole physical runway, so it is named by both directions
        // ("cross runway 6R-24L" / "cross runway six right two four left").
        let rwyDisplay = Phonetic.runwayPairDisplay(runwayIdent)
        let rwySpoken = Phonetic.runwayPairSpoken(runwayIdent, icao: icao)
        let atDisplay = atTaxiway.flatMap { $0.isEmpty ? nil : " at \($0)" } ?? ""
        let atSpoken = atTaxiway.flatMap { $0.isEmpty ? nil : " at \(Phonetic.spellToken($0, icao: icao))" } ?? ""
        let contDisplay = continueVia.flatMap { $0.isEmpty ? nil : ", then continue on \($0)" } ?? ""
        let contSpoken = continueVia.flatMap { $0.isEmpty ? nil : ", then continue on \(Phonetic.spellToken($0, icao: icao))" } ?? ""
        let display = "\(cs.display), cross runway \(rwyDisplay)\(atDisplay)\(contDisplay)."
        let spoken = "\(cs.spoken), cross runway \(rwySpoken)\(atSpoken)\(contSpoken)."
        var tx = ATCTransmission(sender: .atc, facility: .ground, displayText: display, spokenText: spoken)
        tx.readback = ATCTransmission.Readback(
            displayText: "Cross runway \(rwyDisplay), \(cs.display).",
            spokenText: "Cross runway \(rwySpoken), \(cs.spoken).",
            facility: .ground)
        return tx
    }

    /// Ground hold-short instruction issued as the aircraft approaches a crossing before
    /// it has been cleared.
    func holdShort(cs: PhraseologyEngine.Callsign, runwayIdent: String) -> ATCTransmission {
        // Name both directions of the physical runway being held short of.
        let rwyDisplay = Phonetic.runwayPairDisplay(runwayIdent)
        let rwySpoken = Phonetic.runwayPairSpoken(runwayIdent, icao: icao)
        var tx = ATCTransmission(sender: .atc, facility: .ground,
                                 displayText: "\(cs.display), hold short of runway \(rwyDisplay).",
                                 spokenText: "\(cs.spoken), hold short of runway \(rwySpoken).")
        tx.readback = ATCTransmission.Readback(
            displayText: "Hold short runway \(rwyDisplay), \(cs.display).",
            spokenText: "Hold short runway \(rwySpoken), \(cs.spoken).",
            facility: .ground)
        return tx
    }

    /// Continuation after a crossing is vacated — resume the remaining taxi route.
    func resumeTaxi(cs: PhraseologyEngine.Callsign, runway: String, isDeparture: Bool, gate: String) -> ATCTransmission {
        if isDeparture {
            let rwySpoken = Phonetic.runway(runway, icao: icao)
            return ATCTransmission(sender: .atc, facility: .ground,
                                   displayText: "\(cs.display), continue taxi to runway \(runway).",
                                   spokenText: "\(cs.spoken), continue taxi to runway \(rwySpoken).")
        } else {
            let g = gate.trimmingCharacters(in: .whitespaces)
            let destDisplay = g.isEmpty ? "parking" : "gate \(g)"
            let destSpoken = g.isEmpty ? "parking" : "gate \(Phonetic.spellToken(g, icao: icao))"
            return ATCTransmission(sender: .atc, facility: .ground,
                                   displayText: "\(cs.display), continue taxi to \(destDisplay).",
                                   spokenText: "\(cs.spoken), continue taxi to \(destSpoken).")
        }
    }

    // MARK: - Unauthorized-entry warnings

    /// Simulated hold-position warning (aircraft moving toward a runway before a
    /// crossing clearance / read-back).
    func holdPositionWarning(cs: PhraseologyEngine.Callsign, runwayIdent: String) -> ATCTransmission {
        let rwyDisplay = Phonetic.runwayPairDisplay(runwayIdent)
        let rwySpoken = Phonetic.runwayPairSpoken(runwayIdent, icao: icao)
        return ATCTransmission(sender: .atc, facility: .ground,
                               displayText: "\(cs.display), hold position, hold short of runway \(rwyDisplay).",
                               spokenText: "\(cs.spoken), hold position, hold short of runway \(rwySpoken).")
    }

    /// Simulated stop-immediately warning (aircraft already entering the runway corridor
    /// without authorization).
    func stopWarning(cs: PhraseologyEngine.Callsign, runwayIdent: String) -> ATCTransmission {
        let rwyDisplay = Phonetic.runwayPairDisplay(runwayIdent)
        let rwySpoken = Phonetic.runwayPairSpoken(runwayIdent, icao: icao)
        return ATCTransmission(sender: .atc, facility: .ground,
                               displayText: "\(cs.display), stop immediately, you are entering runway \(rwyDisplay).",
                               spokenText: "\(cs.spoken), stop immediately, you are entering runway \(rwySpoken).")
    }

    // MARK: - Helpers

    private func sequenceText(_ route: SurfaceTaxiRoute) -> (display: String, spoken: String) {
        let seq = route.taxiwaySequence.filter { !$0.isEmpty }
        guard !seq.isEmpty else { return ("available taxiways", "available taxiways") }
        return (seq.joined(separator: ", "),
                seq.map { Phonetic.spellToken($0, icao: icao) }.joined(separator: " "))
    }
}
