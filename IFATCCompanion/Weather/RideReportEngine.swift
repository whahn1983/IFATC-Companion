import Foundation

/// Produces deterministic, programmatic Center responses for ride reports and
/// destination weather, based on filtered weather data. No AI.
struct RideReportEngine {

    let engine: PhraseologyEngine

    private var icao: Bool { engine.icao }

    private func center(_ display: String, _ spoken: String) -> ATCTransmission {
        ATCTransmission(sender: .atc, facility: .center, displayText: display, spokenText: spoken)
    }

    /// Build the ride-report transmission from relevant items.
    func rideReport(items: [RideReportItem], callsign: PhraseologyEngine.Callsign) -> ATCTransmission {
        guard let worst = items.max(by: { $0.severity < $1.severity }) else {
            return center("\(callsign.display), no significant ride reports along your route at this time.",
                          "\(callsign.spoken), no significant ride reports along your route at this time.")
        }
        // Prefer the nearest report of the worst severity for the lead phrase.
        let lead = items.filter { $0.severity == worst.severity }
            .min(by: { ($0.distanceAheadNM ?? .greatestFiniteMagnitude) < ($1.distanceAheadNM ?? .greatestFiniteMagnitude) }) ?? worst

        let bandDisplay = bandPhrase(lead.altitudeBand, spoken: false)
        let bandSpoken = bandPhrase(lead.altitudeBand, spoken: true)
        let distDisplay = aheadPhrase(lead, spoken: false)
        let distSpoken = aheadPhrase(lead, spoken: true)
        let fix = lead.nearFix.flatMap { $0.isEmpty ? nil : $0 }

        switch worst.severity {
        case .smooth:
            return center("\(callsign.display), smooth ride reported along your route.",
                          "\(callsign.spoken), smooth ride reported along your route.")
        case .lightChop, .light:
            let sevText = worst.severity.spoken
            let display = "\(callsign.display), \(sevText) reported ahead\(bandDisplay.isEmpty ? "" : " \(bandDisplay)")."
            let spoken = "\(callsign.spoken), \(sevText) reported ahead\(bandSpoken.isEmpty ? "" : " \(bandSpoken)")."
            return center(display, spoken)
        case .moderate:
            let near = fix.map { " near \($0)" } ?? ""
            let nearSpoken = fix.map { " near \(Phonetic.spellToken($0, icao: icao))" } ?? ""
            let display = "\(callsign.display), moderate turbulence reported\(distDisplay)\(near). Advise if you'd like higher or lower."
            let spoken = "\(callsign.spoken), moderate turbulence reported\(distSpoken)\(nearSpoken). Advise if you'd like higher or lower."
            return center(display, spoken)
        case .severe:
            let near = fix.map { " near \($0)" } ?? ""
            let nearSpoken = fix.map { " near \(Phonetic.spellToken($0, icao: icao))" } ?? ""
            let display = "\(callsign.display), severe turbulence reported\(distDisplay)\(near). Recommend deviation or altitude change when able; advise intentions."
            let spoken = "\(callsign.spoken), severe turbulence reported\(distSpoken)\(nearSpoken). Recommend deviation or altitude change when able; advise intentions."
            return center(display, spoken)
        }
    }

    /// Build a ride report from a composite `RideAssessment` (turbulence model). When a
    /// PIREP drives it, relay that report the way ATC would — severity, the reported
    /// altitude, distance/fix ahead, reporting type and recency — and, when a PIREP at
    /// another level shows a smoother ride, name that specific altitude; otherwise fall
    /// back to the generic higher/lower offer. `referenceAltitudeFt` is the pilot's level
    /// (used when the lead report's own altitude is unknown).
    func rideReport(assessment: RideAssessment, items: [RideReportItem],
                    referenceAltitudeFt: Int = 0,
                    smoother: SmootherAltitude? = nil,
                    callsign: PhraseologyEngine.Callsign) -> ATCTransmission {
        guard assessment.severity > .smooth else {
            return center("\(callsign.display), overall ride is smooth along your route at this time.",
                          "\(callsign.spoken), overall ride is smooth along your route at this time.")
        }

        let lead = items.filter { $0.severity == assessment.severity }
            .min(by: { ($0.distanceAheadNM ?? .greatestFiniteMagnitude) < ($1.distanceAheadNM ?? .greatestFiniteMagnitude) })
            ?? items.max(by: { $0.severity < $1.severity })

        let sev = assessment.severity
        // Altitude: the report's own level when known, else the pilot's level.
        let altFt = lead?.reportedAltitudeFt ?? (referenceAltitudeFt > 0 ? referenceAltitudeFt : nil)
        let altDisplay = altFt.map { " at \(engine.formatAltDisplay($0))" } ?? ""
        let altSpoken = altFt.map { " at \(Phonetic.altitude($0))" } ?? ""
        let distDisplay = aheadPhrase(lead, spoken: false)
        let distSpoken = aheadPhrase(lead, spoken: true)
        let fix = lead?.nearFix.flatMap { $0.isEmpty ? nil : $0 }
        let nearDisplay = fix.map { " near \($0)" } ?? ""
        let nearSpoken = fix.map { " near \(Phonetic.spellToken($0, icao: icao))" } ?? ""
        let type = lead?.aircraftType.flatMap { $0.isEmpty ? nil : $0 }
        let typeDisplay = type.map { ", by a \($0)" } ?? ""
        let typeSpoken = type.map { ", by a \(Phonetic.spellToken($0, icao: icao))" } ?? ""
        let ageDisplay = agePhrase(lead?.ageMinutes, spoken: false)
        let ageSpoken = agePhrase(lead?.ageMinutes, spoken: true)
        let factors = assessment.contributors.isEmpty ? "" : " Based on \(assessment.contributors.joined(separator: ", "))."
        // A data-backed smoother level when one exists, else the generic offer (moderate+).
        let tailDisplay = smootherTail(smoother, spoken: false, offerGeneric: sev >= .moderate)
        let tailSpoken = smootherTail(smoother, spoken: true, offerGeneric: sev >= .moderate)

        let display = "\(callsign.display), \(sev.spoken) reported\(altDisplay)\(distDisplay)\(nearDisplay)\(typeDisplay)\(ageDisplay).\(factors)\(tailDisplay)"
        let spoken = "\(callsign.spoken), \(sev.spoken) reported\(altSpoken)\(distSpoken)\(nearSpoken)\(typeSpoken)\(ageSpoken).\(factors)\(tailSpoken)"
        return center(display, spoken)
    }

    /// A recency clause ("… , one five minutes ago"), or empty when the age is unknown.
    private func agePhrase(_ minutes: Double?, spoken: Bool) -> String {
        guard let minutes, minutes >= 1 else { return "" }
        let m = Int(minutes.rounded())
        return spoken ? ", \(Phonetic.spellDigits(String(m))) minutes ago" : ", \(m) minutes ago"
    }

    /// The smoother-altitude suggestion clause (names the specific level), or the generic
    /// higher/lower offer when there is no data-backed level and `offerGeneric` is set.
    private func smootherTail(_ s: SmootherAltitude?, spoken: Bool, offerGeneric: Bool) -> String {
        guard let s else {
            return offerGeneric ? " Advise if you'd like higher or lower for a smoother ride." : ""
        }
        let dir = s.higher ? "climb" : "descend"
        let alt = spoken ? Phonetic.altitude(s.altitudeFt) : engine.formatAltDisplay(s.altitudeFt)
        let ride = s.severity == .smooth ? "smooth ride" : "lighter ride, \(s.severity.spoken),"
        let leadCap = ride.prefix(1).uppercased() + String(ride.dropFirst())
        return " \(leadCap) reported at \(alt); advise if you'd like to \(dir)."
    }

    /// Build the destination weather transmission from a METAR.
    func destinationWeather(metar: METAR?, callsign: PhraseologyEngine.Callsign, icao icaoCode: String) -> ATCTransmission {
        guard let m = metar else {
            return center("\(callsign.display), \(engine.spokenAirport(icaoCode)) weather is not available at this time.",
                          "\(callsign.spoken), \(engine.spokenAirport(icaoCode)) weather is not available at this time.")
        }
        let city = engine.spokenAirport(icaoCode)
        var displayParts: [String] = []
        var spokenParts: [String] = []

        if let dir = m.windDirection, let spd = m.windSpeed {
            displayParts.append("wind \(String(format: "%03d", dir)) at \(spd)")
            spokenParts.append(Phonetic.wind(direction: dir, speed: spd, gust: m.windGust, icao: icao))
        }
        if let vis = m.visibilitySM {
            let v = Int(vis.rounded())
            displayParts.append("visibility \(v)")
            spokenParts.append("visibility \(Phonetic.visibility(v, icao: icao))")
        }
        if let ceiling = m.ceilingFt {
            displayParts.append("ceiling \(ceiling) \(ceilingCover(m))")
            spokenParts.append("ceiling \(Phonetic.altitude(ceiling, icao: icao)) \(ceilingCoverSpoken(m))")
        }
        if let altim = m.altimeterInHg {
            if icao {
                let hpa = Int((altim * 33.8638866667).rounded())
                displayParts.append("QNH \(hpa)")
            } else {
                displayParts.append("altimeter \(String(format: "%.2f", altim))")
            }
            spokenParts.append(Phonetic.altimeterSetting(inHg: altim, icao: icao))
        }

        if displayParts.isEmpty {
            return center("\(callsign.display), \(city) weather is unavailable.",
                          "\(callsign.spoken), \(city) weather is unavailable.")
        }
        let display = "\(callsign.display), \(city) is reporting \(displayParts.joined(separator: ", "))."
        let spoken = "\(callsign.spoken), \(city) is reporting \(spokenParts.joined(separator: ", "))."
        return center(display, spoken)
    }

    // MARK: - Helpers

    private func bandPhrase(_ band: ClosedRange<Int>?, spoken: Bool) -> String {
        guard let band else { return "" }
        if spoken {
            return "between \(Phonetic.altitude(band.lowerBound)) and \(Phonetic.altitude(band.upperBound))"
        }
        return "between \(engine.formatAltDisplay(band.lowerBound)) and \(engine.formatAltDisplay(band.upperBound))"
    }

    private func distancePhrase(_ distance: Double?, spoken: Bool) -> String {
        guard let distance, distance > 1 else { return "" }
        let rounded = Int((distance / 10).rounded()) * 10
        if spoken {
            return " approximately \(Phonetic.spellDigits(String(rounded))) miles ahead"
        }
        return " approximately \(rounded) miles ahead"
    }

    /// The "how far" clause for the lead report. With a live aircraft fix this is an
    /// aircraft-relative distance ("… approximately four zero miles ahead"); without one
    /// (aircraft data lost / not connected) it falls back to a route-relative phrase
    /// ("… along your route") so the report never presents a distance-from-origin as if it
    /// were distance ahead of the aircraft.
    private func aheadPhrase(_ item: RideReportItem?, spoken: Bool) -> String {
        guard let item else { return "" }
        if item.distanceIsFromAircraft {
            return distancePhrase(item.distanceAheadNM, spoken: spoken)
        }
        return " along your route"
    }

    private func ceilingCover(_ m: METAR) -> String {
        let layer = m.clouds.first(where: { $0.cover == "BKN" || $0.cover == "OVC" })
        switch layer?.cover {
        case "OVC": return "overcast"
        case "BKN": return "broken"
        default: return "broken"
        }
    }

    private func ceilingCoverSpoken(_ m: METAR) -> String { ceilingCover(m) }
}
