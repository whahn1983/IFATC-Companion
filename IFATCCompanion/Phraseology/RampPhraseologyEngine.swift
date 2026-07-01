import Foundation

/// Deterministic, template-based phraseology for the **Ramp / Apron / Company
/// Ramp** facility and the optional **Ground Crew / Interphone** channel.
///
/// IMPORTANT — this is *simulated local/non-FAA procedure*, not FAA ATC:
///  - Ramp may approve pushback, coordinate engine start, move aircraft in the
///    non-movement area (alleys, spots), hold for traffic, and hand off to Ground.
///  - Ramp must NEVER issue takeoff, landing, runway-crossing, IFR route,
///    altitude, heading, SID, STAR, or approach instructions, and must never
///    authorize runway entry/crossing. Those belong to FAA ATC only.
///  - Ramp uses "push approved", "taxi via the alley", "proceed to spot",
///    "hold position", "give way", "continue", and "monitor ramp" — never
///    "cleared to taxi" or "cleared for pushback".
///
/// Outputs are pure functions of their inputs; callsign/digit pronunciation is
/// borrowed from `PhraseologyEngine` so the FAA/ICAO pack and digit style apply.
struct RampPhraseologyEngine {

    let engine: PhraseologyEngine

    private var icao: Bool { engine.icao }

    private func ramp(_ display: String, _ spoken: String) -> ATCTransmission {
        ATCTransmission(sender: .atc, facility: .ramp, displayText: display, spokenText: spoken)
    }

    private func pilot(_ display: String, _ spoken: String) -> ATCTransmission {
        ATCTransmission(sender: .pilot, facility: .ramp, displayText: display, spokenText: spoken)
    }

    private func system(_ display: String, _ spoken: String) -> ATCTransmission {
        ATCTransmission(sender: .system, facility: .ramp, displayText: display, spokenText: spoken)
    }

    private func spot(_ s: String) -> (display: String, spoken: String) {
        let t = s.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return ("", "") }
        return ("spot \(t)", "spot \(Phonetic.spellToken(t, icao: icao))")
    }

    // MARK: - Departure ramp (controller side)

    /// Pushback approval. With a known tail/face direction:
    /// "push approved, tail west"; unknown direction falls back to
    /// "push approved, advise ready to taxi". Apron style uses "face".
    func pushbackApproved(cs: PhraseologyEngine.Callsign, direction: String,
                          profile: RampProfile = .generic) -> ATCTransmission {
        let dir = direction.trimmingCharacters(in: .whitespaces).lowercased()
        if dir.isEmpty {
            return ramp("\(cs.display), pushback approved, advise ready to taxi.",
                        "\(cs.spoken), pushback approved, advise ready to taxi.")
        }
        let word = profile.rampType.usesFaceDirection ? "face" : "tail"
        return ramp("\(cs.display), pushback approved, \(word) \(dir).",
                    "\(cs.spoken), pushback approved, \(word) \(dir).")
    }

    /// Hold position for ramp/alley traffic (no runway/movement-area authority).
    func holdPosition(cs: PhraseologyEngine.Callsign, reason: String = "traffic entering the alley") -> ATCTransmission {
        ramp("\(cs.display), hold position, \(reason).",
             "\(cs.spoken), hold position, \(reason).")
    }

    /// Engine-start coordination (company/ramp). Distinct from any FAA clearance.
    func startApproved(cs: PhraseologyEngine.Callsign) -> ATCTransmission {
        ramp("\(cs.display), start approved.",
             "\(cs.spoken), start approved.")
    }

    /// Ramp taxi to a spot via the alley (non-movement area). Uses "taxi via",
    /// never "cleared to taxi". Unknown spot → conservative movement-area boundary.
    func taxiToSpot(cs: PhraseologyEngine.Callsign, spot spotName: String,
                    alley: String = "the alley") -> ATCTransmission {
        let s = spot(spotName)
        if s.display.isEmpty {
            return ramp("\(cs.display), taxi via \(alley), monitor ramp.",
                        "\(cs.spoken), taxi via \(alley), monitor ramp.")
        }
        return ramp("\(cs.display), taxi via \(alley) to \(s.display).",
                    "\(cs.spoken), taxi via \(alley) to \(s.spoken).")
    }

    /// Continue / proceed within the ramp (e.g. after a hold or give-way).
    func proceed(cs: PhraseologyEngine.Callsign, to target: String) -> ATCTransmission {
        ramp("\(cs.display), continue to \(target).",
             "\(cs.spoken), continue to \(target).")
    }

    /// Give way to crossing/entering ramp traffic.
    func giveWay(cs: PhraseologyEngine.Callsign, to traffic: String) -> ATCTransmission {
        ramp("\(cs.display), give way to \(traffic).",
             "\(cs.spoken), give way to \(traffic).")
    }

    /// Hand off to Ground at the spot / movement-area boundary. This is the only
    /// transition out of Ramp; Ground (FAA ATC) controls the movement area after.
    func contactGround(cs: PhraseologyEngine.Callsign, groundFrequency: Double,
                       spot spotName: String) -> ATCTransmission {
        let s = spot(spotName)
        let freqD = String(format: "%.3f", groundFrequency)
        let freqS = Phonetic.frequency(groundFrequency, icao: icao)
        // Compose the matching pilot read-back so the Read Back button echoes the
        // hand-off (the Ground frequency / movement-area boundary) rather than a
        // read-back re-derived from the stale conversational state (which lags at
        // engine-start and would read back "start approved").
        if s.display.isEmpty {
            var tx = ramp("\(cs.display), proceed to the movement-area boundary, contact Ground \(freqD).",
                          "\(cs.spoken), proceed to the movement-area boundary, contact Ground \(freqS).")
            tx.readback = ATCTransmission.Readback(
                displayText: "Proceed to the movement-area boundary, contact Ground \(freqD), \(cs.display).",
                spokenText: "Proceed to the movement-area boundary, contact Ground \(freqS), \(cs.spoken).",
                facility: .ramp)
            return tx
        }
        var tx = ramp("\(cs.display), contact Ground \(freqD) at \(s.display).",
                      "\(cs.spoken), contact Ground \(freqS) at \(s.spoken).")
        tx.readback = ATCTransmission.Readback(
            displayText: "Contact Ground \(freqD) at \(s.display), \(cs.display).",
            spokenText: "Contact Ground \(freqS) at \(s.spoken), \(cs.spoken).",
            facility: .ramp)
        return tx
    }

    // MARK: - Arrival ramp (controller side)

    /// Arrival ramp entry — proceed to the gate via the ramp/alley. Never "cleared".
    func proceedToGate(cs: PhraseologyEngine.Callsign, gate: String,
                       via alley: String = "the inner alley") -> ATCTransmission {
        let g = gate.trimmingCharacters(in: .whitespaces)
        let dest = g.isEmpty ? "the gate" : "gate \(g)"
        let destSpoken = g.isEmpty ? "the gate" : "gate \(Phonetic.spellToken(g, icao: icao))"
        // Compose the matching read-back so the Read Back button echoes the ramp
        // routing instead of a read-back re-derived from the stale `.groundArrival`
        // state (which would read back the earlier Ground "taxi to gate via …").
        var tx = ramp("\(cs.display), proceed to \(dest) via \(alley).",
                      "\(cs.spoken), proceed to \(destSpoken) via \(alley).")
        tx.readback = ATCTransmission.Readback(
            displayText: "Proceed to \(dest) via \(alley), \(cs.display).",
            spokenText: "Proceed to \(destSpoken) via \(alley), \(cs.spoken).",
            facility: .ramp)
        return tx
    }

    /// Gate occupied — hold short of the alley until it opens.
    func gateOccupied(cs: PhraseologyEngine.Callsign, gate: String) -> ATCTransmission {
        let g = gate.trimmingCharacters(in: .whitespaces)
        let gd = g.isEmpty ? "the gate" : "gate \(g)"
        let gs = g.isEmpty ? "the gate" : "gate \(Phonetic.spellToken(g, icao: icao))"
        return ramp("\(cs.display), \(gd) is occupied, hold short of the alley.",
                    "\(cs.spoken), \(gs) is occupied, hold short of the alley.")
    }

    /// Final block-in — monitor ramp to the gate (marshaller/VDGS takes over).
    func monitorRampToGate(cs: PhraseologyEngine.Callsign) -> ATCTransmission {
        var tx = ramp("\(cs.display), monitor ramp to the gate.",
                      "\(cs.spoken), monitor ramp to the gate.")
        tx.readback = ATCTransmission.Readback(
            displayText: "Monitor ramp to the gate, \(cs.display).",
            spokenText: "Monitor ramp to the gate, \(cs.spoken).",
            facility: .ramp)
        return tx
    }

    // MARK: - Pilot side (ramp readbacks / requests)

    func requestPush(cs: PhraseologyEngine.Callsign, gate: String, andStart: Bool) -> ATCTransmission {
        let g = gate.trimmingCharacters(in: .whitespaces)
        let at = g.isEmpty ? "at the gate" : "at \(g)"
        let atSpoken = g.isEmpty ? "at the gate" : "at \(Phonetic.spellToken(g, icao: icao))"
        let req = andStart ? "request push and start" : "ready to push"
        return pilot("Ramp, \(cs.display) \(at), \(req).",
                     "Ramp, \(cs.spoken) \(atSpoken), \(req).")
    }

    func pushComplete(cs: PhraseologyEngine.Callsign) -> ATCTransmission {
        pilot("Ramp, \(cs.display), push complete, ready to taxi.",
              "Ramp, \(cs.spoken), push complete, ready to taxi.")
    }

    func arrivalInbound(cs: PhraseologyEngine.Callsign, gate: String) -> ATCTransmission {
        let g = gate.trimmingCharacters(in: .whitespaces)
        let inb = g.isEmpty ? "inbound to the gate" : "inbound \(g)"
        let inbSpoken = g.isEmpty ? "inbound to the gate" : "inbound \(Phonetic.spellToken(g, icao: icao))"
        return pilot("Ramp, \(cs.display), \(inb).",
                     "Ramp, \(cs.spoken), \(inbSpoken).")
    }

    /// Generic ramp readback echoing the controller instruction (e.g.
    /// "Pushback approved, tail west, United five niner eight").
    func readback(_ instruction: String, spokenInstruction: String,
                  cs: PhraseologyEngine.Callsign) -> ATCTransmission {
        pilot("\(instruction), \(cs.display).", "\(spokenInstruction), \(cs.spoken).")
    }

    // MARK: - Ground Crew / Interphone (non-radio, private text-only)

    /// The headset interphone exchange during pushback. These are crew comms, not
    /// radio, and never go out over the air. Returned as `.system` transmissions.
    func interphoneBrakesQuery() -> ATCTransmission {
        system("Ground crew: Brakes set?", "Ground crew. Brakes set?")
    }

    func interphoneDisconnect() -> ATCTransmission {
        system("Ground crew: Towbar disconnected, bypass pin removed, hand signal on the left. Have a good flight.",
               "Ground crew. Towbar disconnected, bypass pin removed, hand signal on the left. Have a good flight.")
    }

    /// Advisory shown when ramp control is disabled / non-movement-area is company
    /// controlled. Not FAA ATC; informs the pilot to continue and call Ground.
    func nonMovementAdvisory() -> ATCTransmission {
        system("Ramp movement is non-movement-area / company controlled at many airports. Continue when ready and contact Ground before entering the movement area.",
               "Ramp movement is non movement area or company controlled at many airports. Continue when ready and contact Ground before entering the movement area.")
    }
}
