import Foundation

/// Builds deterministic, template-based ATC transmissions. No AI — every output
/// is a pure function of its inputs. Variation comes only from approved template
/// alternates selected deterministically.
struct PhraseologyEngine {

    var digitStyle: CallsignDigitStyle = .grouped
    var mode: PhraseologyMode = .faa

    // MARK: - Callsign

    /// Spoken callsign, e.g. ("United", "598") -> "United five niner eight".
    func spokenCallsign(airline: String, flightNumber: String, fallback: String = "") -> String {
        let airlineTrim = airline.trimmingCharacters(in: .whitespaces)
        let numTrim = flightNumber.trimmingCharacters(in: .whitespaces).filter { $0.isNumber }
        if !airlineTrim.isEmpty && !numTrim.isEmpty {
            return "\(airlineTrim) \(spokenFlightNumber(numTrim))"
        }
        let fb = fallback.trimmingCharacters(in: .whitespaces)
        if !fb.isEmpty {
            // Mixed alphanumeric tail/callsign -> spell it out.
            return Phonetic.spellToken(fb)
        }
        return "aircraft"
    }

    /// Display callsign for the transcript, e.g. "United 598" or the raw fallback.
    func displayCallsign(airline: String, flightNumber: String, fallback: String = "") -> String {
        let airlineTrim = airline.trimmingCharacters(in: .whitespaces)
        let numTrim = flightNumber.trimmingCharacters(in: .whitespaces)
        if !airlineTrim.isEmpty && !numTrim.isEmpty { return "\(airlineTrim) \(numTrim)" }
        let fb = fallback.trimmingCharacters(in: .whitespaces)
        return fb.isEmpty ? "Aircraft" : fb
    }

    func spokenFlightNumber(_ digits: String) -> String {
        switch digitStyle {
        case .individual:
            return Phonetic.spellDigits(digits)
        case .grouped:
            return groupedNumber(digits)
        }
    }

    private func groupedNumber(_ digits: String) -> String {
        let chars = Array(digits)
        switch chars.count {
        case 4:
            let a = Int(String(chars[0...1])) ?? 0
            let b = Int(String(chars[2...3])) ?? 0
            return "\(Phonetic.twoDigitGroup(a)) \(groupTail(b))"
        case 3:
            let first = Phonetic.digitWords[chars[0]] ?? ""
            let b = Int(String(chars[1...2])) ?? 0
            return "\(first) \(groupTail(b))"
        case 2:
            return Phonetic.twoDigitGroup(Int(digits) ?? 0)
        default:
            return Phonetic.spellDigits(digits)
        }
    }

    /// Trailing two-digit group: "00" -> "hundred", else natural English.
    private func groupTail(_ n: Int) -> String {
        n == 0 ? "hundred" : Phonetic.twoDigitGroup(n)
    }

    // MARK: - Builders (each returns an ATCTransmission)

    private func tx(_ facility: ATCFacility, display: String, spoken: String) -> ATCTransmission {
        ATCTransmission(sender: .atc, facility: facility, displayText: display, spokenText: spoken)
    }

    struct Callsign {
        let display: String
        let spoken: String
    }

    func callsign(airline: String, flightNumber: String, fallback: String) -> Callsign {
        Callsign(display: displayCallsign(airline: airline, flightNumber: flightNumber, fallback: fallback),
                 spoken: spokenCallsign(airline: airline, flightNumber: flightNumber, fallback: fallback))
    }

    // Clearance Delivery — IFR clearance.
    func clearance(cs: Callsign, destination: String, cruise: Int, sid: String,
                   initialAlt: Int, departureFreq: Double, squawk: String) -> ATCTransmission {
        let destDisplay = destination.isEmpty ? "destination" : destination
        let sidText = sid.isEmpty ? "the filed route" : "the \(sid) departure"
        let display = "\(cs.display), cleared to \(destDisplay) via \(sidText), "
            + "climb via SID except maintain \(formatAltDisplay(initialAlt)), "
            + "expect \(formatAltDisplay(cruise)) one zero minutes after departure, "
            + "departure frequency \(String(format: "%.3f", departureFreq)), squawk \(squawk)."
        let spoken = "\(cs.spoken), cleared to \(spokenAirport(destination)) via \(sid.isEmpty ? "the filed route" : "the " + Phonetic.spellToken(sid) + " departure"), "
            + "climb via SID except maintain \(Phonetic.altitude(initialAlt)), "
            + "expect \(Phonetic.altitude(cruise)) one zero minutes after departure, "
            + "departure frequency \(Phonetic.frequency(departureFreq)), \(Phonetic.squawk(squawk))."
        return tx(.clearance, display: display, spoken: spoken)
    }

    // Ground — taxi.
    func taxiToRunway(cs: Callsign, runway: String, via: String, crossing: String?) -> ATCTransmission {
        var display = "\(cs.display), taxi to runway \(runway) via \(via)"
        var spoken = "\(cs.spoken), taxi to runway \(Phonetic.runway(runway)) via \(Phonetic.spellToken(via))"
        if let crossing, !crossing.isEmpty {
            display += ", cross runway \(crossing)"
            spoken += ", cross runway \(Phonetic.runway(crossing))"
        }
        return tx(.ground, display: display + ".", spoken: spoken + ".")
    }

    // Tower — line up and wait.
    func lineUpAndWait(cs: Callsign, runway: String) -> ATCTransmission {
        tx(.tower,
           display: "\(cs.display), runway \(runway), line up and wait.",
           spoken: "\(cs.spoken), runway \(Phonetic.runway(runway)), line up and wait.")
    }

    // Tower — cleared for takeoff.
    func clearedForTakeoff(cs: Callsign, runway: String, windDir: Int, windSpeed: Int) -> ATCTransmission {
        tx(.tower,
           display: "\(cs.display), wind \(String(format: "%03d", windDir)) at \(windSpeed), runway \(runway), cleared for takeoff.",
           spoken: "\(cs.spoken), \(Phonetic.wind(direction: windDir, speed: windSpeed)), runway \(Phonetic.runway(runway)), cleared for takeoff.")
    }

    // Departure — radar contact + climb.
    func radarContactClimb(cs: Callsign, altitude: Int) -> ATCTransmission {
        tx(.departure,
           display: "\(cs.display), radar contact, climb and maintain \(formatAltDisplay(altitude)).",
           spoken: "\(cs.spoken), radar contact, climb and maintain \(Phonetic.altitude(altitude)).")
    }

    // Center — climb.
    func climbMaintain(cs: Callsign, altitude: Int) -> ATCTransmission {
        tx(.center,
           display: "\(cs.display), climb and maintain \(formatAltDisplay(altitude)).",
           spoken: "\(cs.spoken), climb and maintain \(Phonetic.altitude(altitude)).")
    }

    // Center — pilot's discretion descent.
    func descendPilotsDiscretion(cs: Callsign, altitude: Int) -> ATCTransmission {
        tx(.center,
           display: "\(cs.display), descend at pilot's discretion, maintain \(formatAltDisplay(altitude)).",
           spoken: "\(cs.spoken), descend at pilot's discretion, maintain \(Phonetic.altitude(altitude)).")
    }

    // Approach — descend + expect approach.
    func descendExpectApproach(cs: Callsign, altitude: Int, approach: String, runway: String) -> ATCTransmission {
        let appText = approach.isEmpty ? "the I-L-S" : approach
        tx(.approach,
           display: "\(cs.display), descend and maintain \(formatAltDisplay(altitude)), expect \(appText) runway \(runway) approach.",
           spoken: "\(cs.spoken), descend and maintain \(Phonetic.altitude(altitude)), expect \(approach.isEmpty ? "the I L S" : approach) runway \(Phonetic.runway(runway)) approach.")
    }

    // Approach/Tower — cleared approach.
    func clearedApproach(cs: Callsign, approach: String, runway: String) -> ATCTransmission {
        let appText = approach.isEmpty ? "ILS" : approach
        tx(.approach,
           display: "\(cs.display), cleared \(appText) runway \(runway) approach.",
           spoken: "\(cs.spoken), cleared \(approach.isEmpty ? "I L S" : approach) runway \(Phonetic.runway(runway)) approach.")
    }

    // Tower arrival — cleared to land.
    func clearedToLand(cs: Callsign, runway: String, windDir: Int, windSpeed: Int) -> ATCTransmission {
        tx(.tower,
           display: "\(cs.display), wind \(String(format: "%03d", windDir)) at \(windSpeed), runway \(runway), cleared to land.",
           spoken: "\(cs.spoken), \(Phonetic.wind(direction: windDir, speed: windSpeed)), runway \(Phonetic.runway(runway)), cleared to land.")
    }

    // Ground arrival — taxi to parking.
    func taxiToParking(cs: Callsign, via: String) -> ATCTransmission {
        let viaText = via.isEmpty ? "available taxiways" : via
        tx(.ground,
           display: "\(cs.display), taxi to parking via \(viaText).",
           spoken: "\(cs.spoken), taxi to parking via \(via.isEmpty ? "available taxiways" : Phonetic.spellToken(via)).")
    }

    // Generic handoff.
    func handoff(cs: Callsign, to facility: ATCFacility, frequency: Double) -> ATCTransmission {
        tx(facility,
           display: "\(cs.display), contact \(facility.spokenName) on \(String(format: "%.3f", frequency)).",
           spoken: "\(cs.spoken), contact \(facility.spokenName) on \(Phonetic.frequency(frequency)).")
    }

    func radarContact(cs: Callsign, facility: ATCFacility) -> ATCTransmission {
        tx(facility,
           display: "\(cs.display), \(facility.spokenName), radar contact.",
           spoken: "\(cs.spoken), \(facility.spokenName), radar contact.")
    }

    // MARK: - Helpers

    /// Display form of an altitude: "FL370" above transition, else "5,000".
    func formatAltDisplay(_ feet: Int) -> String {
        if feet >= 18000 { return "FL\(String(format: "%03d", feet / 100))" }
        return numberFormatter.string(from: NSNumber(value: feet)) ?? "\(feet)"
    }

    /// Spoken airport: known major ICAOs get city names, else spelled out.
    func spokenAirport(_ icao: String) -> String {
        let code = icao.uppercased()
        if let city = PhraseologyEngine.cityNames[code] { return city }
        return code.isEmpty ? "destination" : Phonetic.spellToken(code)
    }

    private var numberFormatter: NumberFormatter {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.locale = Locale(identifier: "en_US")
        f.groupingSeparator = ","
        return f
    }

    /// Small built-in city lookup so common routes sound natural. Extendable.
    static let cityNames: [String: String] = [
        "KMSP": "Minneapolis", "KIAH": "Houston", "KDEN": "Denver",
        "KORD": "Chicago", "KATL": "Atlanta", "KLAX": "Los Angeles",
        "KJFK": "New York", "KSFO": "San Francisco", "KSEA": "Seattle",
        "KDFW": "Dallas", "KBOS": "Boston", "KMIA": "Miami",
        "KLAS": "Las Vegas", "KPHX": "Phoenix", "KDCA": "Washington"
    ]
}
