import Foundation

/// Produces deterministic, programmatic Center responses for ride reports and
/// destination weather, based on filtered weather data. No AI.
struct RideReportEngine {

    let engine: PhraseologyEngine

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
        let distDisplay = distancePhrase(lead.distanceAheadNM, spoken: false)
        let distSpoken = distancePhrase(lead.distanceAheadNM, spoken: true)
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
            let nearSpoken = fix.map { " near \(Phonetic.spellToken($0))" } ?? ""
            let display = "\(callsign.display), moderate turbulence reported\(distDisplay)\(near). Advise if you'd like higher or lower."
            let spoken = "\(callsign.spoken), moderate turbulence reported\(distSpoken)\(nearSpoken). Advise if you'd like higher or lower."
            return center(display, spoken)
        case .severe:
            let near = fix.map { " near \($0)" } ?? ""
            let nearSpoken = fix.map { " near \(Phonetic.spellToken($0))" } ?? ""
            let display = "\(callsign.display), severe turbulence reported\(distDisplay)\(near). Recommend deviation or altitude change when able; advise intentions."
            let spoken = "\(callsign.spoken), severe turbulence reported\(distSpoken)\(nearSpoken). Recommend deviation or altitude change when able; advise intentions."
            return center(display, spoken)
        }
    }

    /// Build the destination weather transmission from a METAR.
    func destinationWeather(metar: METAR?, callsign: PhraseologyEngine.Callsign, icao: String) -> ATCTransmission {
        guard let m = metar else {
            return center("\(callsign.display), \(engine.spokenAirport(icao)) weather is not available at this time.",
                          "\(callsign.spoken), \(engine.spokenAirport(icao)) weather is not available at this time.")
        }
        let city = engine.spokenAirport(icao)
        var displayParts: [String] = []
        var spokenParts: [String] = []

        if let dir = m.windDirection, let spd = m.windSpeed {
            displayParts.append("wind \(String(format: "%03d", dir)) at \(spd)")
            spokenParts.append(Phonetic.wind(direction: dir, speed: spd, gust: m.windGust))
        }
        if let vis = m.visibilitySM {
            let v = Int(vis.rounded())
            displayParts.append("visibility \(v)")
            spokenParts.append("visibility \(Phonetic.visibility(v))")
        }
        if let ceiling = m.ceilingFt {
            displayParts.append("ceiling \(ceiling) \(ceilingCover(m))")
            spokenParts.append("ceiling \(Phonetic.altitude(ceiling)) \(ceilingCoverSpoken(m))")
        }
        if let altim = m.altimeterInHg {
            displayParts.append("altimeter \(String(format: "%.2f", altim))")
            spokenParts.append("altimeter \(Phonetic.altimeter(altim))")
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
