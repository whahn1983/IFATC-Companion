import Foundation

/// Builds deterministic, template-based ATC transmissions. No AI — every output
/// is a pure function of its inputs. Variation comes only from approved template
/// alternates selected deterministically.
///
/// The engine honors two selectable phraseology packs (`PhraseologyMode`): FAA/US
/// and ICAO. The pack changes digit words ("tree/fower/fife"), the frequency
/// separator ("decimal" vs "point"), the altimeter/QNH convention, and a handful
/// of phrase forms (e.g. "taxi to holding point" vs "taxi to runway"). An optional
/// user `PhraseologyProfile` can override individual call templates and supply
/// custom airline call sets.
struct PhraseologyEngine {

    var digitStyle: CallsignDigitStyle = .grouped
    var mode: PhraseologyMode = .faa

    /// Optional user-defined overrides (templates + airline call sets).
    var profile: PhraseologyProfile?

    /// Convenience: whether the ICAO pack is selected.
    var icao: Bool { mode == .icao }

    // MARK: - Callsign

    /// Spoken callsign, e.g. ("United", "598") -> "United five niner eight".
    func spokenCallsign(airline: String, flightNumber: String, fallback: String = "") -> String {
        let airlineTrim = airline.trimmingCharacters(in: .whitespaces)
        let numTrim = flightNumber.trimmingCharacters(in: .whitespaces).filter { $0.isNumber }
        if !airlineTrim.isEmpty && !numTrim.isEmpty {
            return "\(spokenAirline(airlineTrim)) \(spokenFlightNumber(numTrim))"
        }
        let fb = fallback.trimmingCharacters(in: .whitespaces)
        if !fb.isEmpty {
            // Mixed alphanumeric tail/callsign -> spell it out.
            return Phonetic.spellToken(fb, icao: icao)
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

    /// Spoken telephony name for an airline. A user profile may map an ICAO/IATA
    /// designator or name to a custom radio name (e.g. "DLH" -> "Lufthansa").
    func spokenAirline(_ airline: String) -> String {
        if let custom = profile?.airlineCallName(for: airline) { return custom }
        return airline
    }

    func spokenFlightNumber(_ digits: String) -> String {
        switch digitStyle {
        case .individual:
            return Phonetic.spellDigits(digits, icao: icao)
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
            return "\(Phonetic.twoDigitGroup(a, icao: icao)) \(groupTail(b))"
        case 3:
            let first = Phonetic.digitMap(icao: icao)[chars[0]] ?? ""
            let b = Int(String(chars[1...2])) ?? 0
            return "\(first) \(groupTail(b))"
        case 2:
            return Phonetic.twoDigitGroup(Int(digits) ?? 0, icao: icao)
        default:
            return Phonetic.spellDigits(digits, icao: icao)
        }
    }

    /// Trailing two-digit group: "00" -> "hundred", else natural English.
    private func groupTail(_ n: Int) -> String {
        n == 0 ? "hundred" : Phonetic.twoDigitGroup(n, icao: icao)
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
                   initialAlt: Int, departureFreq: Double, squawk: String,
                   sidProcedure: Procedure? = nil) -> ATCTransmission {
        let destDisplay = destination.isEmpty ? "destination" : destination
        // Resolve the SID phrasing from a parsed procedure, raw text, or fall back.
        let sidDisplay: String
        let sidSpoken: String
        if let proc = sidProcedure {
            sidDisplay = "the \(proc.displayName) departure"
            sidSpoken = "the \(proc.spokenName(icao: icao)) departure"
        } else if sid.isEmpty {
            sidDisplay = "the filed route"; sidSpoken = "the filed route"
        } else {
            sidDisplay = "the \(sid) departure"
            sidSpoken = "the " + Phonetic.spellToken(sid, icao: icao) + " departure"
        }
        if let template = profile?.template(for: .clearance) {
            let ph = placeholders(cs: cs, extra: [
                "dest": destDisplay, "destSpoken": spokenAirport(destination),
                "sid": sidDisplay, "sidSpoken": sidSpoken,
                "initialAlt": formatAltDisplay(initialAlt), "initialAltSpoken": Phonetic.altitude(initialAlt, icao: icao),
                "cruise": formatAltDisplay(cruise), "cruiseSpoken": Phonetic.altitude(cruise, icao: icao),
                "depFreq": String(format: "%.3f", departureFreq), "depFreqSpoken": Phonetic.frequency(departureFreq, icao: icao),
                "squawk": squawk, "squawkSpoken": Phonetic.squawk(squawk, icao: icao)])
            return tx(.clearance, display: render(template.display, ph.display), spoken: render(template.spoken, ph.spoken))
        }
        let display = "\(cs.display), cleared to \(destDisplay) via \(sidDisplay), "
            + "climb via SID except maintain \(formatAltDisplay(initialAlt)), "
            + "expect \(formatAltDisplay(cruise)) one zero minutes after departure, "
            + "departure frequency \(String(format: "%.3f", departureFreq)), squawk \(squawk)."
        let spoken = "\(cs.spoken), cleared to \(spokenAirport(destination)) via \(sidSpoken), "
            + "climb via SID except maintain \(Phonetic.altitude(initialAlt, icao: icao)), "
            + "expect \(Phonetic.altitude(cruise, icao: icao)) one zero minutes after departure, "
            + "departure frequency \(Phonetic.frequency(departureFreq, icao: icao)), \(Phonetic.squawk(squawk, icao: icao))."
        return tx(.clearance, display: display, spoken: spoken)
    }

    // Center/Approach — descend via a published STAR (arrival).
    func descendViaArrival(cs: Callsign, star: Procedure, altitude: Int) -> ATCTransmission {
        let fixClause = star.fixes.count > 1 ? " crossing \(star.fixes[1])" : ""
        let fixClauseSpoken = star.fixes.count > 1 ? " crossing \(Phonetic.spellToken(star.fixes[1], icao: icao))" : ""
        return tx(.center,
           display: "\(cs.display), descend via the \(star.displayName) arrival, maintain \(formatAltDisplay(altitude))\(fixClause).",
           spoken: "\(cs.spoken), descend via the \(star.spokenName(icao: icao)) arrival, maintain \(Phonetic.altitude(altitude, icao: icao))\(fixClauseSpoken).")
    }

    // Approach — cleared a published approach procedure.
    func clearedApproach(cs: Callsign, procedure: Procedure, runway: String) -> ATCTransmission {
        let rwy = procedure.runway ?? runway
        return tx(.approach,
           display: "\(cs.display), cleared \(procedure.displayName) approach.",
           spoken: "\(cs.spoken), cleared \(procedure.approachType?.spoken ?? "approach") runway \(Phonetic.runway(rwy, icao: icao)) approach.")
    }

    // Ground — pushback approval.
    func pushbackApproved(cs: Callsign) -> ATCTransmission {
        // ICAO writes "push back"; FAA writes "pushback".
        let phrase = icao ? "push back approved" : "pushback approved"
        return tx(.ground,
           display: "\(cs.display), \(phrase).",
           spoken: "\(cs.spoken), \(phrase).")
    }

    // Ground — engine start-up approval (ICAO "start-up", FAA "start up").
    func startupApproved(cs: Callsign) -> ATCTransmission {
        let phrase = icao ? "start-up approved" : "start up approved"
        return tx(.ground,
           display: "\(cs.display), \(phrase).",
           spoken: "\(cs.spoken), \(phrase).")
    }

    // Ground — taxi.
    func taxiToRunway(cs: Callsign, runway: String, via: String, crossing: String?) -> ATCTransmission {
        let crossDisplay = (crossing.map { $0.isEmpty ? "" : ", cross runway \($0)" }) ?? ""
        let crossSpoken = (crossing.map { $0.isEmpty ? "" : ", cross runway \(Phonetic.runway($0, icao: icao))" }) ?? ""
        if let template = profile?.template(for: .taxiToRunway) {
            let ph = placeholders(cs: cs, extra: [
                "runway": runway, "runwaySpoken": Phonetic.runway(runway, icao: icao),
                "via": via, "viaSpoken": Phonetic.spellToken(via, icao: icao),
                "crossing": crossDisplay, "crossingSpoken": crossSpoken])
            return tx(.ground, display: render(template.display, ph.display), spoken: render(template.spoken, ph.spoken))
        }
        // ICAO: "taxi to holding point runway X"; FAA: "taxi to runway X". The taxi
        // instruction ends by telling the pilot to call Tower when ready to depart.
        let lead = icao ? "taxi to holding point runway" : "taxi to runway"
        let display = "\(cs.display), \(lead) \(runway) via \(via)\(crossDisplay). Contact Tower when ready."
        let spoken = "\(cs.spoken), \(lead) \(Phonetic.runway(runway, icao: icao)) via \(Phonetic.spellToken(via, icao: icao))\(crossSpoken). Contact Tower when ready."
        return tx(.ground, display: display, spoken: spoken)
    }

    // Tower — line up and wait.
    func lineUpAndWait(cs: Callsign, runway: String) -> ATCTransmission {
        tx(.tower,
           display: "\(cs.display), runway \(runway), line up and wait.",
           spoken: "\(cs.spoken), runway \(Phonetic.runway(runway, icao: icao)), line up and wait.")
    }

    // Tower — cleared for takeoff.
    func clearedForTakeoff(cs: Callsign, runway: String, windDir: Int, windSpeed: Int) -> ATCTransmission {
        if let template = profile?.template(for: .takeoff) {
            let ph = placeholders(cs: cs, extra: [
                "runway": runway, "runwaySpoken": Phonetic.runway(runway, icao: icao),
                "wind": "\(String(format: "%03d", windDir)) at \(windSpeed)",
                "windSpoken": Phonetic.wind(direction: windDir, speed: windSpeed, icao: icao)])
            return tx(.tower, display: render(template.display, ph.display), spoken: render(template.spoken, ph.spoken))
        }
        // ICAO uses the hyphenated "cleared for take-off".
        let phrase = icao ? "cleared for take-off" : "cleared for takeoff"
        return tx(.tower,
           display: "\(cs.display), wind \(String(format: "%03d", windDir)) at \(windSpeed), runway \(runway), \(phrase).",
           spoken: "\(cs.spoken), \(Phonetic.wind(direction: windDir, speed: windSpeed, icao: icao)), runway \(Phonetic.runway(runway, icao: icao)), \(phrase).")
    }

    // Tower — cleared for takeoff with departure instructions (initial heading +
    // climb). The heading is the bearing to the first fix / route intercept; when
    // it is within 10° of the runway heading we say "fly runway heading".
    func clearedForTakeoff(cs: Callsign, runway: String, windDir: Int, windSpeed: Int,
                           departureHeading: Int, initialAltitude: Int) -> ATCTransmission {
        let phrase = icao ? "cleared for take-off" : "cleared for takeoff"
        let rwyHeading = PhraseologyEngine.runwayHeading(runway)
        let aligned = rwyHeading.map { Self.angularDiff(Double(departureHeading), Double($0)) <= 10 } ?? false
        let hdgDisplay: String
        let hdgSpoken: String
        if departureHeading <= 0 || aligned {
            hdgDisplay = "fly runway heading"; hdgSpoken = "fly runway heading"
        } else {
            hdgDisplay = "fly heading \(String(format: "%03d", departureHeading))"
            hdgSpoken = "fly heading \(Phonetic.heading(departureHeading, icao: icao))"
        }
        let display = "\(cs.display), wind \(String(format: "%03d", windDir)) at \(windSpeed), runway \(runway), \(phrase), \(hdgDisplay), climb and maintain \(formatAltDisplay(initialAltitude))."
        let spoken = "\(cs.spoken), \(Phonetic.wind(direction: windDir, speed: windSpeed, icao: icao)), runway \(Phonetic.runway(runway, icao: icao)), \(phrase), \(hdgSpoken), climb and maintain \(Phonetic.altitude(initialAltitude, icao: icao))."
        return tx(.tower, display: display, spoken: spoken)
    }

    // Departure — radar contact + climb.
    func radarContactClimb(cs: Callsign, altitude: Int) -> ATCTransmission {
        tx(.departure,
           display: "\(cs.display), radar contact, climb and maintain \(formatAltDisplay(altitude)).",
           spoken: "\(cs.spoken), radar contact, climb and maintain \(Phonetic.altitude(altitude, icao: icao)).")
    }

    // Departure — radar contact, climb to the TRACON ceiling, join the route.
    func departureClimb(cs: Callsign, altitude: Int, firstFix: String) -> ATCTransmission {
        let join = firstFix.isEmpty ? "resume own navigation" : "resume own navigation, direct \(firstFix)"
        let joinSpoken = firstFix.isEmpty ? "resume own navigation"
            : "resume own navigation, direct \(Phonetic.spellToken(firstFix, icao: icao))"
        return tx(.departure,
           display: "\(cs.display), radar contact, climb and maintain \(formatAltDisplay(altitude)), \(join).",
           spoken: "\(cs.spoken), radar contact, climb and maintain \(Phonetic.altitude(altitude, icao: icao)), \(joinSpoken).")
    }

    // Center — climb.
    func climbMaintain(cs: Callsign, altitude: Int) -> ATCTransmission {
        tx(.center,
           display: "\(cs.display), climb and maintain \(formatAltDisplay(altitude)).",
           spoken: "\(cs.spoken), climb and maintain \(Phonetic.altitude(altitude, icao: icao)).")
    }

    // Center — first call when the aircraft checks in after the Departure hand-off:
    // radar contact, then the climb to the cruising altitude.
    func centerRadarContactClimb(cs: Callsign, altitude: Int) -> ATCTransmission {
        tx(.center,
           display: "\(cs.display), radar contact, climb and maintain \(formatAltDisplay(altitude)).",
           spoken: "\(cs.spoken), radar contact, climb and maintain \(Phonetic.altitude(altitude, icao: icao)).")
    }

    // Center — descend and maintain an assigned altitude (no STAR filed). A plain,
    // non-contradictory descent clearance.
    func descendMaintain(cs: Callsign, altitude: Int) -> ATCTransmission {
        tx(.center,
           display: "\(cs.display), descend and maintain \(formatAltDisplay(altitude)).",
           spoken: "\(cs.spoken), descend and maintain \(Phonetic.altitude(altitude, icao: icao)).")
    }

    // Center — pilot's discretion descent (used when the pilot requests lower; the
    // pilot chooses when to leave the current altitude, then levels at the target).
    func descendPilotsDiscretion(cs: Callsign, altitude: Int) -> ATCTransmission {
        tx(.center,
           display: "\(cs.display), descend at pilot's discretion, maintain \(formatAltDisplay(altitude)).",
           spoken: "\(cs.spoken), descend at pilot's discretion, maintain \(Phonetic.altitude(altitude, icao: icao)).")
    }

    // Approach — descend + expect a published approach procedure (clean ILS/GPS/
    // Visual phrasing, avoiding the doubled "RWY … runway …").
    func descendExpectApproach(cs: Callsign, altitude: Int, procedure: Procedure, runway: String) -> ATCTransmission {
        let rwy = procedure.runway ?? runway
        let typeDisplay = procedure.approachType?.display ?? "approach"
        let typeSpoken = procedure.approachType?.spoken ?? "approach"
        return tx(.approach,
           display: "\(cs.display), descend and maintain \(formatAltDisplay(altitude)), expect the \(typeDisplay) runway \(rwy) approach.",
           spoken: "\(cs.spoken), descend and maintain \(Phonetic.altitude(altitude, icao: icao)), expect the \(typeSpoken) runway \(Phonetic.runway(rwy, icao: icao)) approach.")
    }

    // Approach — descend + expect approach (free-text approach name fallback).
    func descendExpectApproach(cs: Callsign, altitude: Int, approach: String, runway: String) -> ATCTransmission {
        let appText = approach.isEmpty ? "the I-L-S" : approach
        return tx(.approach,
           display: "\(cs.display), descend and maintain \(formatAltDisplay(altitude)), expect \(appText) runway \(runway) approach.",
           spoken: "\(cs.spoken), descend and maintain \(Phonetic.altitude(altitude, icao: icao)), expect \(approach.isEmpty ? "the I L S" : approach) runway \(Phonetic.runway(runway, icao: icao)) approach.")
    }

    // Approach/Tower — cleared approach.
    func clearedApproach(cs: Callsign, approach: String, runway: String) -> ATCTransmission {
        let appText = approach.isEmpty ? "ILS" : approach
        return tx(.approach,
           display: "\(cs.display), cleared \(appText) runway \(runway) approach.",
           spoken: "\(cs.spoken), cleared \(approach.isEmpty ? "I L S" : approach) runway \(Phonetic.runway(runway, icao: icao)) approach.")
    }

    // Tower arrival — cleared to land.
    func clearedToLand(cs: Callsign, runway: String, windDir: Int, windSpeed: Int) -> ATCTransmission {
        if let template = profile?.template(for: .landing) {
            let ph = placeholders(cs: cs, extra: [
                "runway": runway, "runwaySpoken": Phonetic.runway(runway, icao: icao),
                "wind": "\(String(format: "%03d", windDir)) at \(windSpeed)",
                "windSpoken": Phonetic.wind(direction: windDir, speed: windSpeed, icao: icao)])
            return tx(.tower, display: render(template.display, ph.display), spoken: render(template.spoken, ph.spoken))
        }
        return tx(.tower,
           display: "\(cs.display), wind \(String(format: "%03d", windDir)) at \(windSpeed), runway \(runway), cleared to land.",
           spoken: "\(cs.spoken), \(Phonetic.wind(direction: windDir, speed: windSpeed, icao: icao)), runway \(Phonetic.runway(runway, icao: icao)), cleared to land.")
    }

    // Tower rollout — exit the runway and contact Ground once clear. Issued by
    // Tower after touchdown, before the Ground taxi-in instruction.
    func exitRunwayContactGround(cs: Callsign, frequency: Double) -> ATCTransmission {
        tx(.tower,
           display: "\(cs.display), exit the runway when able, contact Ground on \(String(format: "%.3f", frequency)) once on the taxiway.",
           spoken: "\(cs.spoken), exit the runway when able, contact Ground on \(Phonetic.frequency(frequency, icao: icao)) once on the taxiway.")
    }

    // Ground arrival — taxi to parking.
    func taxiToParking(cs: Callsign, via: String) -> ATCTransmission {
        let viaText = via.isEmpty ? "available taxiways" : via
        return tx(.ground,
           display: "\(cs.display), taxi to parking via \(viaText).",
           spoken: "\(cs.spoken), taxi to parking via \(via.isEmpty ? "available taxiways" : Phonetic.spellToken(via, icao: icao)).")
    }

    // Generic handoff.
    func handoff(cs: Callsign, to facility: ATCFacility, frequency: Double) -> ATCTransmission {
        tx(facility,
           display: "\(cs.display), contact \(facility.spokenName) on \(String(format: "%.3f", frequency)).",
           spoken: "\(cs.spoken), contact \(facility.spokenName) on \(Phonetic.frequency(frequency, icao: icao)).")
    }

    /// Handoff spoken by the facility you are leaving, instructing you to contact
    /// the next one (e.g. Tower: "contact Departure on 124.3"). Attributed to the
    /// `from` facility so the transcript shows who is releasing you.
    func handoff(cs: Callsign, from: ATCFacility, to: ATCFacility, frequency: Double) -> ATCTransmission {
        tx(from,
           display: "\(cs.display), contact \(to.spokenName) on \(String(format: "%.3f", frequency)).",
           spoken: "\(cs.spoken), contact \(to.spokenName) on \(Phonetic.frequency(frequency, icao: icao)).")
    }

    /// Append a pushback hand-off to an IFR clearance so Clearance Delivery tells
    /// the pilot whom to contact for the push — Ramp (when the airport has a
    /// ramp/apron layer) or Ground (when it does not). The callsign is omitted
    /// from the trailing sentence since the clearance already addresses the pilot.
    func appendingPushbackHandoff(to transmission: ATCTransmission,
                                  facility: ATCFacility, frequency: Double) -> ATCTransmission {
        var out = transmission
        out.displayText += " When ready for pushback, contact \(facility.spokenName) on \(String(format: "%.3f", frequency))."
        out.spokenText += " When ready for pushback, contact \(facility.spokenName) on \(Phonetic.frequency(frequency, icao: icao))."
        return out
    }

    /// Arrival courtesy on reaching the gate.
    func welcomeArrival(cs: Callsign, airport: String) -> ATCTransmission {
        let city = spokenAirport(airport)
        let display = airport.isEmpty ? "\(cs.display), welcome, monitor ground, good day."
            : "\(cs.display), welcome to \(PhraseologyEngine.cityNames[airport.uppercased()] ?? airport), good day."
        let spoken = "\(cs.spoken), welcome to \(city), good day."
        return tx(.ground, display: display, spoken: spoken)
    }

    func radarContact(cs: Callsign, facility: ATCFacility) -> ATCTransmission {
        tx(facility,
           display: "\(cs.display), \(facility.spokenName), radar contact.",
           spoken: "\(cs.spoken), \(facility.spokenName), radar contact.")
    }

    // MARK: - Helpers

    /// Magnetic heading (degrees) implied by a runway identifier, e.g. "17R" -> 170.
    static func runwayHeading(_ runway: String) -> Int? {
        let digits = runway.prefix { $0.isNumber }
        guard let n = Int(digits), n >= 1, n <= 36 else { return nil }
        return n * 10
    }

    /// Smallest absolute difference between two compass bearings (0–180°).
    static func angularDiff(_ a: Double, _ b: Double) -> Double {
        let d = abs((a - b).truncatingRemainder(dividingBy: 360))
        return min(d, 360 - d)
    }

    /// Display form of an altitude: "FL370" above transition, else "5,000".
    func formatAltDisplay(_ feet: Int) -> String {
        if feet >= 18000 { return "FL\(String(format: "%03d", feet / 100))" }
        return numberFormatter.string(from: NSNumber(value: feet)) ?? "\(feet)"
    }

    /// Spoken airport: known major ICAOs get city names, else spelled out.
    func spokenAirport(_ icao: String) -> String {
        let code = icao.uppercased()
        if let city = PhraseologyEngine.cityNames[code] { return city }
        return code.isEmpty ? "destination" : Phonetic.spellToken(code, icao: self.icao)
    }

    private var numberFormatter: NumberFormatter {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.locale = Locale(identifier: "en_US")
        f.groupingSeparator = ","
        return f
    }

    // MARK: - Template rendering

    private struct Placeholders { let display: [String: String]; let spoken: [String: String] }

    private func placeholders(cs: Callsign, extra: [String: String]) -> Placeholders {
        var display: [String: String] = ["callsign": cs.display]
        var spoken: [String: String] = ["callsign": cs.spoken]
        for (key, value) in extra {
            if key.hasSuffix("Spoken") {
                spoken[String(key.dropLast("Spoken".count))] = value
            } else {
                display[key] = value
                // Default spoken to display unless an explicit spoken value follows.
                if spoken[key] == nil { spoken[key] = value }
            }
        }
        return Placeholders(display: display, spoken: spoken)
    }

    /// Substitute `{placeholder}` tokens in a template string.
    private func render(_ template: String, _ values: [String: String]) -> String {
        var result = template
        for (key, value) in values {
            result = result.replacingOccurrences(of: "{\(key)}", with: value)
        }
        return result
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
