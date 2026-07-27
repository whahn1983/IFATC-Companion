import Foundation

/// Produces random, phraseologically-plausible background ATC chatter, **bounded to the
/// frequency the pilot is tuned to** so it never sounds wrong for the position: Center
/// works ride reports, hand-offs, descend-via-STAR and en-route climbs/descents; Ground
/// works taxi and hand-offs; Tower works takeoff/landing/line-up-and-wait; and so on.
///
/// Everything is deterministic given the random source and reuses the app's own
/// `Phonetic` renderer (so "niner/fife/tree", spoken headings, altitudes and
/// frequencies match the real calls) and `AirlineDatabase` (so callsigns are real
/// radio names). No AI, no network — pure templates filled with random values.
///
/// When the tuned airport's OpenStreetMap surface has loaded, its real runway ends are
/// supplied via `runwayIdents` so runway references (Ground taxi/hold-short, Tower
/// takeoff/land/line-up, Approach clearances) name runways that actually exist at the
/// origin/destination field, rather than an invented "runway 18" the airport lacks. When the
/// field's ATIS is also available, `departureRunwayIdents` / `arrivalRunwayIdents` carry the
/// runways actually in use, so a takeoff or taxi call names a departure runway and a
/// landing/approach call an arrival runway — matching how the field is really being run.
///
/// The generator is intentionally generic over `RandomNumberGenerator` so tests can
/// drive it deterministically with a seeded generator.
struct ChatterScriptGenerator {

    var mode: PhraseologyMode = .faa
    var digitStyle: CallsignDigitStyle = .grouped

    /// Real runway-end idents for the airport this chatter is simulating right now — the
    /// origin field while pre-departure/climbing, the destination once descending/arriving —
    /// taken from the loaded OpenStreetMap surface (e.g. `["16L","34R","09","27"]`). When
    /// non-empty, every runway reference (Ground taxi/hold-short, Tower takeoff/land/line-up,
    /// Approach clearances) is drawn from these so the background traffic never taxis to or is
    /// cleared for a runway the field does not have. Empty (no surface loaded yet, or no flight
    /// plan) falls back to a plausible random runway, preserving the previous behavior.
    var runwayIdents: [String] = []

    /// The runways in use for **departures** per the field's ATIS (a subset of `runwayIdents`).
    /// Ground taxi/hold-short and Tower takeoff/line-up draw from here so departing traffic uses
    /// a departure runway. Empty when no ATIS is available — the departure calls then fall back
    /// to `runwayIdents` (any real runway), then to a random one.
    var departureRunwayIdents: [String] = []

    /// The runways in use for **arrivals** per the field's ATIS. Tower landing/final and Approach
    /// clearances draw from here so arriving traffic uses an arrival runway. Empty falls back to
    /// `runwayIdents`, then a random runway.
    var arrivalRunwayIdents: [String] = []

    private var icao: Bool { mode == .icao }

    /// Common ICAO designators that resolve to a spoken name via `AirlineDatabase`.
    private static let designators = [
        "UAL", "AAL", "DAL", "SWA", "JBU", "ASA", "NKS", "FFT", "ACA", "WJA",
        "FDX", "UPS", "BAW", "AFR", "DLH", "KLM", "SkW", "RPA", "EDV", "ENY",
    ].map { $0.uppercased() }

    /// A small pool of pronounceable waypoint-style names TTS reads as words.
    private static let fixes = [
        "Scatt", "Wynde", "Boove", "Hobit", "Ravnn", "Kylse", "Manta", "Dublv",
        "Cardz", "Bruno", "Tydev", "Ganns", "Pladd", "Sherlk", "Oconn", "Yeager",
    ]

    /// Generate an exchange (usually one controller line, sometimes with a matching
    /// read-back) for the tuned `facility`.
    func exchange<G: RandomNumberGenerator>(for facility: ATCFacility, using rng: inout G) -> [ChatterLine] {
        let cs = callsign(using: &rng)
        switch facility {
        case .center:    return centerExchange(cs: cs, using: &rng)
        case .approach:  return approachExchange(cs: cs, using: &rng)
        case .departure: return departureExchange(cs: cs, using: &rng)
        case .tower:     return towerExchange(cs: cs, using: &rng)
        case .ground:    return groundExchange(cs: cs, using: &rng)
        case .clearance: return clearanceExchange(cs: cs, using: &rng)
        case .ramp:      return rampExchange(cs: cs, using: &rng)
        }
    }

    /// Convenience using the system generator.
    func exchange(for facility: ATCFacility) -> [ChatterLine] {
        var rng = SystemRandomNumberGenerator()
        return exchange(for: facility, using: &rng)
    }

    // MARK: - Per-facility templates

    private func centerExchange<G: RandomNumberGenerator>(cs: Callsign, using rng: inout G) -> [ChatterLine] {
        switch Int.random(in: 0..<6, using: &rng) {
        case 0:
            let sev = ["light", "light to moderate", "occasional light", "moderate"].randomElement(using: &rng)!
            let fl = flightLevel(using: &rng)
            return [ctrl("\(cs.spoken), \(sev) chop reported at \(Phonetic.altitude(fl, icao: icao)), say ride conditions."),
                    pilot("\(cs.spoken), \(["smooth", "light chop", "negative, smooth ride"].randomElement(using: &rng)!).")]
        case 1:
            let fl = flightLevel(using: &rng)
            return [ctrl("\(cs.spoken), climb and maintain \(Phonetic.altitude(fl, icao: icao))."), readback(cs: cs, "climb and maintain \(Phonetic.altitude(fl, icao: icao))")]
        case 2:
            let fl = flightLevel(using: &rng)
            return [ctrl("\(cs.spoken), descend and maintain \(Phonetic.altitude(fl, icao: icao))."), readback(cs: cs, "descend and maintain \(Phonetic.altitude(fl, icao: icao))")]
        case 3:
            let facility = [ATCFacility.center, .approach, .departure].randomElement(using: &rng)!
            let freq = Phonetic.frequency(centerFreq(using: &rng), icao: icao)
            return [ctrl("\(cs.spoken), contact \(facility.spokenName) \(freq)."), readback(cs: cs, "over to \(facility.spokenName) \(freq)")]
        case 4:
            let star = Self.fixes.randomElement(using: &rng)!
            return [ctrl("\(cs.spoken), descend via the \(star) \(oneArrivalNumber(using: &rng)) arrival."),
                    readback(cs: cs, "descend via the \(star) arrival")]
        default:
            let fix = Self.fixes.randomElement(using: &rng)!
            return [ctrl("\(cs.spoken), cleared direct \(fix), rest of route unchanged."), readback(cs: cs, "direct \(fix)")]
        }
    }

    private func approachExchange<G: RandomNumberGenerator>(cs: Callsign, using rng: inout G) -> [ChatterLine] {
        switch Int.random(in: 0..<5, using: &rng) {
        case 0:
            let hdg = Phonetic.heading(heading(using: &rng), icao: icao)
            let alt = Phonetic.altitude(lowAltitude(using: &rng), icao: icao)
            let dir = ["left", "right"].randomElement(using: &rng)!
            return [ctrl("\(cs.spoken), turn \(dir) heading \(hdg), descend and maintain \(alt)."),
                    readback(cs: cs, "\(dir) heading \(hdg), down to \(alt)")]
        case 1:
            let type = ["ILS", "R NAV", "visual"].randomElement(using: &rng)!
            let rwy = Phonetic.runway(arrivalRunway(using: &rng), icao: icao)
            return [ctrl("\(cs.spoken), cleared \(type) runway \(rwy) approach."),
                    readback(cs: cs, "cleared \(type) runway \(rwy) approach")]
        case 2:
            let spd = spellNumber(String(Int.random(in: 18...29, using: &rng) * 10))
            return [ctrl("\(cs.spoken), reduce speed \(spd) knots."), readback(cs: cs, "\(spd) knots")]
        case 3:
            let freq = Phonetic.frequency(towerFreq(using: &rng), icao: icao)
            return [ctrl("\(cs.spoken), contact tower \(freq)."), readback(cs: cs, "tower \(freq), good day")]
        default:
            let miles = spellNumber(String(Int.random(in: 3...12, using: &rng)))
            return [ctrl("\(cs.spoken), traffic to follow is a heavy \(Self.fixes.randomElement(using: &rng)!.lowercased()) departure, \(miles) miles.")]
        }
    }

    private func departureExchange<G: RandomNumberGenerator>(cs: Callsign, using rng: inout G) -> [ChatterLine] {
        switch Int.random(in: 0..<5, using: &rng) {
        case 0:
            let alt = Phonetic.altitude(lowAltitude(using: &rng), icao: icao)
            return [ctrl("\(cs.spoken), radar contact, climb and maintain \(alt)."), readback(cs: cs, "climb and maintain \(alt)")]
        case 1:
            let hdg = Phonetic.heading(heading(using: &rng), icao: icao)
            let dir = ["left", "right"].randomElement(using: &rng)!
            return [ctrl("\(cs.spoken), turn \(dir) heading \(hdg)."), readback(cs: cs, "\(dir) heading \(hdg)")]
        case 2:
            let fix = Self.fixes.randomElement(using: &rng)!
            return [ctrl("\(cs.spoken), resume own navigation, direct \(fix)."), readback(cs: cs, "own nav, direct \(fix)")]
        case 3:
            let freq = Phonetic.frequency(centerFreq(using: &rng), icao: icao)
            return [ctrl("\(cs.spoken), contact center \(freq)."), readback(cs: cs, "center \(freq)")]
        default:
            let alt = Phonetic.altitude(flightLevel(using: &rng), icao: icao)
            return [ctrl("\(cs.spoken), climb and maintain \(alt), expedite through one zero thousand."), readback(cs: cs, "up to \(alt)")]
        }
    }

    private func towerExchange<G: RandomNumberGenerator>(cs: Callsign, using rng: inout G) -> [ChatterLine] {
        switch Int.random(in: 0..<5, using: &rng) {
        case 0:
            let rwy = Phonetic.runway(departureRunway(using: &rng), icao: icao)
            return [ctrl("\(cs.spoken), runway \(rwy), cleared for takeoff."), readback(cs: cs, "cleared for takeoff runway \(rwy)")]
        case 1:
            let rwy = Phonetic.runway(arrivalRunway(using: &rng), icao: icao)
            return [ctrl("\(cs.spoken), runway \(rwy), cleared to land."), readback(cs: cs, "cleared to land runway \(rwy)")]
        case 2:
            let rwy = Phonetic.runway(departureRunway(using: &rng), icao: icao)
            return [ctrl("\(cs.spoken), runway \(rwy), line up and wait."), readback(cs: cs, "line up and wait runway \(rwy)")]
        case 3:
            let freq = Phonetic.frequency(departureFreq(using: &rng), icao: icao)
            return [ctrl("\(cs.spoken), contact departure \(freq)."), readback(cs: cs, "departure \(freq)")]
        default:
            let rwy = Phonetic.runway(arrivalRunway(using: &rng), icao: icao)
            let miles = spellNumber(String(Int.random(in: 2...8, using: &rng)))
            return [ctrl("\(cs.spoken), traffic on a \(miles) mile final, runway \(rwy), continue."), readback(cs: cs, "continue, \(cs.spoken)")]
        }
    }

    private func groundExchange<G: RandomNumberGenerator>(cs: Callsign, using rng: inout G) -> [ChatterLine] {
        // Ground traffic is taxiing out to depart, so it holds short of / taxis to a departure
        // runway.
        let rwy = Phonetic.runway(departureRunway(using: &rng), icao: icao)
        let taxi = taxiways(using: &rng)
        switch Int.random(in: 0..<4, using: &rng) {
        case 0:
            return [ctrl("\(cs.spoken), taxi to runway \(rwy) via \(taxi)."), readback(cs: cs, "runway \(rwy) via \(taxi)")]
        case 1:
            return [ctrl("\(cs.spoken), give way to company traffic, then continue taxi via \(taxi).")]
        case 2:
            let freq = Phonetic.frequency(towerFreq(using: &rng), icao: icao)
            return [ctrl("\(cs.spoken), monitor tower \(freq)."), readback(cs: cs, "tower \(freq)")]
        default:
            return [ctrl("\(cs.spoken), hold short of runway \(rwy)."), readback(cs: cs, "hold short runway \(rwy)")]
        }
    }

    private func clearanceExchange<G: RandomNumberGenerator>(cs: Callsign, using rng: inout G) -> [ChatterLine] {
        let freq = Phonetic.frequency(departureFreq(using: &rng), icao: icao)
        let squawk = Phonetic.squawk(squawkCode(using: &rng), icao: icao)
        let alt = Phonetic.altitude(lowAltitude(using: &rng), icao: icao)
        return [ctrl("\(cs.spoken), cleared to destination as filed, climb via SID, expect \(alt) one zero minutes after departure, departure \(freq), squawk \(squawk)."),
                readback(cs: cs, "as filed, \(alt), departure \(freq), squawk \(squawk)")]
    }

    private func rampExchange<G: RandomNumberGenerator>(cs: Callsign, using rng: inout G) -> [ChatterLine] {
        switch Int.random(in: 0..<3, using: &rng) {
        case 0:
            return [ctrl("\(cs.spoken), pushback approved, tail to the \(["north", "south", "east", "west"].randomElement(using: &rng)!)."), readback(cs: cs, "pushback approved")]
        case 1:
            return [ctrl("\(cs.spoken), engine start approved."), readback(cs: cs, "start approved")]
        default:
            let freq = Phonetic.frequency(groundFreq(using: &rng), icao: icao)
            return [ctrl("\(cs.spoken), monitor ground \(freq) for taxi."), readback(cs: cs, "ground \(freq)")]
        }
    }

    // MARK: - Line builders

    private func ctrl(_ text: String) -> ChatterLine { ChatterLine(spokenText: text, isPilot: false) }
    private func pilot(_ text: String) -> ChatterLine { ChatterLine(spokenText: text, isPilot: true) }
    private func readback(cs: Callsign, _ body: String) -> ChatterLine {
        ChatterLine(spokenText: "\(body), \(cs.spoken).", isPilot: true)
    }

    // MARK: - Random value helpers

    private struct Callsign { let spoken: String }

    private func callsign<G: RandomNumberGenerator>(using rng: inout G) -> Callsign {
        let designator = Self.designators.randomElement(using: &rng)!
        let name = AirlineDatabase.callName(for: designator) ?? Phonetic.spellToken(designator, icao: icao)
        let digits = Int.random(in: 1...4, using: &rng)
        var number = ""
        for i in 0..<digits {
            // Avoid a leading zero so the number reads naturally.
            let lo = (i == 0 && digits > 1) ? 1 : 0
            number += String(Int.random(in: lo...9, using: &rng))
        }
        return Callsign(spoken: "\(name) \(spellNumber(number))")
    }

    /// Speak a numeric string honouring the grouped/individual digit style.
    private func spellNumber(_ digits: String) -> String {
        guard digitStyle == .grouped else { return Phonetic.spellDigits(digits, icao: icao) }
        switch digits.count {
        case 2:
            return Phonetic.twoDigitGroup(Int(digits) ?? 0, icao: icao)
        case 4:
            let hi = Int(digits.prefix(2)) ?? 0
            let lo = Int(digits.suffix(2)) ?? 0
            return "\(Phonetic.twoDigitGroup(hi, icao: icao)) \(Phonetic.twoDigitGroup(lo, icao: icao))"
        default:
            return Phonetic.spellDigits(digits, icao: icao)
        }
    }

    private func heading<G: RandomNumberGenerator>(using rng: inout G) -> Int {
        let h = Int.random(in: 0..<36, using: &rng) * 10
        return h == 0 ? 360 : h
    }

    private func runway<G: RandomNumberGenerator>(using rng: inout G) -> String {
        // Prefer a real runway end at the field so the chatter never names a runway that
        // doesn't exist there; fall back to a plausible random one when none are known yet.
        if let ident = runwayIdents.randomElement(using: &rng) { return ident }
        let num = Int.random(in: 1...36, using: &rng)
        let suffix = ["", "", "L", "C", "R"].randomElement(using: &rng)!
        return String(format: "%02d", num) + suffix
    }

    /// A runway used for departures: an ATIS-active departure runway when known, else any real
    /// runway at the field, else a random one.
    private func departureRunway<G: RandomNumberGenerator>(using rng: inout G) -> String {
        if let ident = departureRunwayIdents.randomElement(using: &rng) { return ident }
        return runway(using: &rng)
    }

    /// A runway used for arrivals: an ATIS-active arrival runway when known, else any real
    /// runway at the field, else a random one.
    private func arrivalRunway<G: RandomNumberGenerator>(using rng: inout G) -> String {
        if let ident = arrivalRunwayIdents.randomElement(using: &rng) { return ident }
        return runway(using: &rng)
    }

    private func flightLevel<G: RandomNumberGenerator>(using rng: inout G) -> Int {
        Int.random(in: 24...41, using: &rng) * 1000
    }

    private func lowAltitude<G: RandomNumberGenerator>(using rng: inout G) -> Int {
        Int.random(in: 3...17, using: &rng) * 1000
    }

    private func taxiways<G: RandomNumberGenerator>(using rng: inout G) -> String {
        let letters = ["Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel", "Juliet", "Kilo", "Mike"]
        let count = Int.random(in: 1...2, using: &rng)
        return (0..<count).map { _ in letters.randomElement(using: &rng)! }.joined(separator: ", ")
    }

    private func squawkCode<G: RandomNumberGenerator>(using rng: inout G) -> String {
        (0..<4).map { _ in String(Int.random(in: 0...7, using: &rng)) }.joined()
    }

    private func oneArrivalNumber<G: RandomNumberGenerator>(using rng: inout G) -> String {
        Phonetic.spellDigits(String(Int.random(in: 1...9, using: &rng)), icao: icao)
    }

    // Frequency bands, chosen to sound right for each service.
    private func groundFreq<G: RandomNumberGenerator>(using rng: inout G) -> Double { band(121.6, 121.9, using: &rng) }
    private func towerFreq<G: RandomNumberGenerator>(using rng: inout G) -> Double { band(118.0, 120.9, using: &rng) }
    private func departureFreq<G: RandomNumberGenerator>(using rng: inout G) -> Double { band(124.0, 127.9, using: &rng) }
    private func centerFreq<G: RandomNumberGenerator>(using rng: inout G) -> Double { band(132.0, 135.9, using: &rng) }

    private func band<G: RandomNumberGenerator>(_ lo: Double, _ hi: Double, using rng: inout G) -> Double {
        // 25 kHz spacing.
        let steps = Int((hi - lo) / 0.025)
        let n = Int.random(in: 0...steps, using: &rng)
        return (lo + Double(n) * 0.025).rounded(toPlaces: 3)
    }
}

private extension Double {
    func rounded(toPlaces places: Int) -> Double {
        let m = pow(10.0, Double(places))
        return (self * m).rounded() / m
    }
}
