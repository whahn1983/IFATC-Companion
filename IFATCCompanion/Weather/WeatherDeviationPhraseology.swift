import Foundation

/// Deterministic phraseology for the simulated weather-deviation flow. No AI —
/// every line is a pure function of its inputs, mirroring `PhraseologyEngine` /
/// `RideReportEngine`. Radar-derived weather is always spoken as "precipitation";
/// "turbulence" and "convective weather" are only used when the source supports
/// them (SIGMET/PIREP/CWA/G-AIRMET). This is simulated ATC for training and
/// entertainment only.
struct WeatherDeviationPhraseology {

    let engine: PhraseologyEngine

    private var icao: Bool { engine.icao }

    typealias Callsign = PhraseologyEngine.Callsign

    private func center(_ display: String, _ spoken: String, facility: ATCFacility = .center) -> ATCTransmission {
        ATCTransmission(sender: .atc, facility: facility, displayText: display, spokenText: spoken)
    }

    private func pilot(_ display: String, _ spoken: String, facility: ATCFacility = .center) -> ATCTransmission {
        ATCTransmission(sender: .pilot, facility: facility, displayText: display, spokenText: spoken)
    }

    // MARK: - ATC advisories

    /// Advisory for a radar-precipitation conflict. Voices intensity, clock
    /// position(s), distance, and movement — degrading gracefully to "movement
    /// unknown" / "intensity unknown" when those are not known. Radar-derived, so
    /// always "precipitation", never "turbulence".
    func radarAdvisory(cs: Callsign, conflict: RouteWeatherConflict, facility: ATCFacility = .center) -> ATCTransmission {
        let clockD = clockPhrase(conflict, spoken: false)
        let clockS = clockPhrase(conflict, spoken: true)
        let distD = distancePhrase(conflict.distanceAheadNM, spoken: false)
        let distS = distancePhrase(conflict.distanceAheadNM, spoken: true)

        let intensityKnown = conflict.severity != .unknown
        let lead = intensityKnown
            ? "area of \(conflict.severity.spokenPrecipitation)"
            : "precipitation area"

        if conflict.hazard.hasKnownMovement {
            let moveD = movementPhrase(conflict.hazard, spoken: false)
            let moveS = movementPhrase(conflict.hazard, spoken: true)
            return center("\(cs.display), \(lead) \(clockD), \(distD), \(moveD). Say intentions.",
                          "\(cs.spoken), \(lead) \(clockS), \(distS), \(moveS). Say intentions.",
                          facility: facility)
        }
        if !intensityKnown {
            return center("\(cs.display), precipitation area \(clockD), \(distD), intensity unknown. Say intentions.",
                          "\(cs.spoken), precipitation area \(clockS), \(distS), intensity unknown. Say intentions.",
                          facility: facility)
        }
        return center("\(cs.display), \(lead) \(clockD), \(distD), movement unknown. Say intentions.",
                      "\(cs.spoken), \(lead) \(clockS), \(distS), movement unknown. Say intentions.",
                      facility: facility)
    }

    /// Advisory for a convective SIGMET along the route when radar is unavailable.
    /// "Convective weather" is used only because the advisory supports it.
    func sigmetConvectiveAdvisory(cs: Callsign, facility: ATCFacility = .center) -> ATCTransmission {
        center("\(cs.display), SIGMET indicates convective weather along your route ahead. Say intentions.",
               "\(cs.spoken), SIGMET indicates convective weather along your route ahead. Say intentions.",
               facility: facility)
    }

    /// Advisory for a non-convective SIGMET/advisory along the route.
    func sigmetAdvisory(cs: Callsign, hazardLabel: String, facility: ATCFacility = .center) -> ATCTransmission {
        center("\(cs.display), SIGMET indicates \(hazardLabel) along your route ahead. Say intentions.",
               "\(cs.spoken), SIGMET indicates \(hazardLabel) along your route ahead. Say intentions.",
               facility: facility)
    }

    /// Outside NOAA radar coverage with no advisory data — do not invent weather.
    func noRadarNoAdvisory(cs: Callsign, facility: ATCFacility = .center) -> ATCTransmission {
        center("\(cs.display), radar precipitation is not available for this region. No significant aviation weather advisories are available along your route at this time.",
               "\(cs.spoken), radar precipitation is not available for this region. No significant aviation weather advisories are available along your route at this time.",
               facility: facility)
    }

    // MARK: - Pilot requests

    func pilotRequestDeviation(cs: Callsign, direction: DeviationDirection, degrees: Int,
                               facility: ATCFacility = .center) -> ATCTransmission {
        let degD = String(degrees)
        let degS = Phonetic.spellDigits(degD, icao: icao)
        let f = facility.spokenName
        return pilot("\(f), \(cs.display) requests \(degD) degrees \(direction.word) for weather.",
                     "\(f), \(cs.spoken) requests \(degS) degrees \(direction.word) for weather.",
                     facility: facility)
    }

    func pilotRequestDirectDeviation(cs: Callsign, direction: DeviationDirection,
                                     facility: ATCFacility = .center) -> ATCTransmission {
        let f = facility.spokenName
        return pilot("\(f), \(cs.display) requests deviation \(direction.word) of course for weather.",
              "\(f), \(cs.spoken) requests deviation \(direction.word) of course for weather.",
              facility: facility)
    }

    func pilotRequestVectors(cs: Callsign, facility: ATCFacility = .center) -> ATCTransmission {
        let f = facility.spokenName
        return pilot("\(f), \(cs.display) requests vectors around weather.",
              "\(f), \(cs.spoken) requests vectors around weather.",
              facility: facility)
    }

    func pilotRequestAltitude(cs: Callsign, higher: Bool, facility: ATCFacility = .center) -> ATCTransmission {
        let word = higher ? "higher" : "lower"
        let f = facility.spokenName
        return pilot("\(f), \(cs.display) requests \(word) for weather.",
                     "\(f), \(cs.spoken) requests \(word) for weather.",
                     facility: facility)
    }

    func pilotClearOfWeather(cs: Callsign, facility: ATCFacility = .center) -> ATCTransmission {
        pilot("\(cs.display) is clear of weather.",
              "\(cs.spoken) is clear of weather.",
              facility: facility)
    }

    // MARK: - ATC approvals

    /// Deviation approved with a downstream rejoin fix (enroute).
    func approvalWithRejoin(cs: Callsign, direction: DeviationDirection, degrees: Int?,
                            maintainAltitude: Int, rejoinFix: String,
                            facility: ATCFacility = .center) -> ATCTransmission {
        let devD = deviationClause(direction: direction, degrees: degrees, spoken: false)
        let devS = deviationClause(direction: direction, degrees: degrees, spoken: true)
        let fixS = Phonetic.spellToken(rejoinFix, icao: icao)
        var tx = center("\(cs.display), \(devD) approved, maintain \(altDisplay(maintainAltitude)), when able proceed direct \(rejoinFix) and advise.",
                      "\(cs.spoken), \(devS) approved, maintain \(altSpoken(maintainAltitude)), when able proceed direct \(fixS) and advise.",
                      facility: facility)
        tx.readback = ATCTransmission.Readback(
            displayText: "Maintain \(altDisplay(maintainAltitude)), \(devD), direct \(rejoinFix) when able, \(cs.display).",
            spokenText: "Maintain \(altSpoken(maintainAltitude)), \(devS), direct \(fixS) when able, \(cs.spoken).",
            facility: facility)
        return tx
    }

    /// Deviation approved with no suitable rejoin fix — advise clear of weather.
    func approvalNoRejoin(cs: Callsign, direction: DeviationDirection, degrees: Int?,
                          maintainAltitude: Int, facility: ATCFacility = .center) -> ATCTransmission {
        let devD = deviationClause(direction: direction, degrees: degrees, spoken: false)
        let devS = deviationClause(direction: direction, degrees: degrees, spoken: true)
        var tx = center("\(cs.display), \(devD) approved, maintain \(altDisplay(maintainAltitude)), advise clear of weather.",
                      "\(cs.spoken), \(devS) approved, maintain \(altSpoken(maintainAltitude)), advise clear of weather.",
                      facility: facility)
        tx.readback = ATCTransmission.Readback(
            displayText: "Maintain \(altDisplay(maintainAltitude)), \(devD), \(cs.display).",
            spokenText: "Maintain \(altSpoken(maintainAltitude)), \(devS), \(cs.spoken).",
            facility: facility)
        return tx
    }

    /// Vectors around precipitation.
    func vectorApproval(cs: Callsign, heading: Int, maintainAltitude: Int,
                        facility: ATCFacility = .approach) -> ATCTransmission {
        var tx = center("\(cs.display), fly heading \(headingDisplay(heading)), vectors around precipitation, maintain \(altDisplay(maintainAltitude)), advise clear of weather.",
               "\(cs.spoken), fly heading \(Phonetic.heading(heading, icao: icao)), vectors around precipitation, maintain \(altSpoken(maintainAltitude)), advise clear of weather.",
               facility: facility)
        // Read back both the assigned heading and the maintain altitude.
        tx.readback = ATCTransmission.Readback(
            displayText: "Heading \(headingDisplay(heading)), maintain \(altDisplay(maintainAltitude)), \(cs.display).",
            spokenText: "Heading \(Phonetic.heading(heading, icao: icao)), maintain \(altSpoken(maintainAltitude)), \(cs.spoken).",
            facility: facility)
        return tx
    }

    /// Requested side unavailable — approve the other side.
    func unableSideApproval(cs: Callsign, requested: DeviationDirection, approved: DeviationDirection,
                            degrees: Int?, maintainAltitude: Int?, facility: ATCFacility = .center) -> ATCTransmission {
        let devD = deviationClause(direction: approved, degrees: degrees, spoken: false)
        let devS = deviationClause(direction: approved, degrees: degrees, spoken: true)
        let maintainD = maintainAltitude.map { " maintain \(altDisplay($0))," } ?? ""
        let maintainS = maintainAltitude.map { " maintain \(altSpoken($0))," } ?? ""
        var tx = center("\(cs.display), unable \(requested.word) deviation due traffic, \(devD) approved,\(maintainD) advise clear of weather.",
                      "\(cs.spoken), unable \(requested.word) deviation due traffic, \(devS) approved,\(maintainS) advise clear of weather.",
                      facility: facility)
        // Echo the approved side and the maintain altitude when one was assigned.
        let rbD = maintainAltitude.map { "Maintain \(altDisplay($0)), \(devD), \(cs.display)." } ?? "\(cap(devD)) approved, \(cs.display)."
        let rbS = maintainAltitude.map { "Maintain \(altSpoken($0)), \(devS), \(cs.spoken)." } ?? "\(cap(devS)) approved, \(cs.spoken)."
        tx.readback = ATCTransmission.Readback(displayText: rbD, spokenText: rbS, facility: facility)
        return tx
    }

    /// On a STAR / in descent: preserve the altitude restriction with "maintain",
    /// and set up the expected rejoin point on the arrival.
    func starDeviationApproval(cs: Callsign, direction: DeviationDirection, degrees: Int?,
                               maintainAltitude: Int, starDisplay: String, starSpoken: String,
                               rejoinFix: String, facility: ATCFacility = .center) -> ATCTransmission {
        let devD = deviationClause(direction: direction, degrees: degrees, spoken: false)
        let devS = deviationClause(direction: direction, degrees: degrees, spoken: true)
        let fixS = Phonetic.spellToken(rejoinFix, icao: icao)
        var tx = center("\(cs.display), \(devD) approved, maintain \(altDisplay(maintainAltitude)), expect to rejoin the \(starDisplay) arrival at \(rejoinFix).",
                      "\(cs.spoken), \(devS) approved, maintain \(altSpoken(maintainAltitude)), expect to rejoin the \(starSpoken) arrival at \(fixS).",
                      facility: facility)
        tx.readback = ATCTransmission.Readback(
            displayText: "Maintain \(altDisplay(maintainAltitude)), \(devD), rejoin the \(starDisplay) arrival at \(rejoinFix), \(cs.display).",
            spokenText: "Maintain \(altSpoken(maintainAltitude)), \(devS), rejoin the \(starSpoken) arrival at \(fixS), \(cs.spoken).",
            facility: facility)
        return tx
    }

    /// Rejoin the STAR once clear.
    func rejoinStar(cs: Callsign, rejoinFix: String, starDisplay: String, starSpoken: String,
                    facility: ATCFacility = .center) -> ATCTransmission {
        let fixS = Phonetic.spellToken(rejoinFix, icao: icao)
        var tx = center("\(cs.display), cleared direct \(rejoinFix), then descend via the \(starDisplay) arrival.",
                      "\(cs.spoken), cleared direct \(fixS), then descend via the \(starSpoken) arrival.",
                      facility: facility)
        tx.readback = ATCTransmission.Readback(
            displayText: "Direct \(rejoinFix), descend via the \(starDisplay) arrival, \(cs.display).",
            spokenText: "Direct \(fixS), descend via the \(starSpoken) arrival, \(cs.spoken).",
            facility: facility)
        return tx
    }

    /// Clear of weather — proceed direct the rejoin fix, resume own navigation.
    func clearOfWeatherResume(cs: Callsign, rejoinFix: String?, nearRoute: Bool,
                              facility: ATCFacility = .center) -> ATCTransmission {
        if nearRoute || rejoinFix == nil {
            var tx = center("\(cs.display), resume own navigation.",
                          "\(cs.spoken), resume own navigation.",
                          facility: facility)
            tx.readback = ATCTransmission.Readback(
                displayText: "Resume own navigation, \(cs.display).",
                spokenText: "Resume own navigation, \(cs.spoken).",
                facility: facility)
            return tx
        }
        let fix = rejoinFix!
        let fixS = Phonetic.spellToken(fix, icao: icao)
        var tx = center("\(cs.display), proceed direct \(fix), resume own navigation.",
                      "\(cs.spoken), proceed direct \(fixS), resume own navigation.",
                      facility: facility)
        // Echo the direct fix and "resume own navigation" — the navigation change,
        // not just an acknowledgement.
        tx.readback = ATCTransmission.Readback(
            displayText: "Direct \(fix), resume own navigation, \(cs.display).",
            spokenText: "Direct \(fixS), resume own navigation, \(cs.spoken).",
            facility: facility)
        return tx
    }

    // MARK: - Formatting helpers

    /// "deviation two zero degrees right" or, when degrees are uncertain,
    /// "deviation right of course".
    private func deviationClause(direction: DeviationDirection, degrees: Int?, spoken: Bool) -> String {
        guard let degrees, degrees > 0 else {
            return "deviation \(direction.word) of course"
        }
        if spoken {
            return "deviation \(Phonetic.spellDigits(String(degrees), icao: icao)) degrees \(direction.word)"
        }
        return "deviation \(degrees) degrees \(direction.word)"
    }

    /// Clock phrase: "twelve o'clock" or "between eleven o'clock and two o'clock".
    private func clockPhrase(_ conflict: RouteWeatherConflict, spoken: Bool) -> String {
        if conflict.leftClock == conflict.rightClock {
            return clock(conflict.centerClock, spoken: spoken)
        }
        return "between \(clock(conflict.leftClock, spoken: spoken)) and \(clock(conflict.rightClock, spoken: spoken))"
    }

    private func clock(_ n: Int, spoken: Bool) -> String {
        spoken ? "\(Self.clockWords[n] ?? String(n)) o'clock" : "\(n) o'clock"
    }

    private func distancePhrase(_ distance: Double, spoken: Bool) -> String {
        let rounded = max(0, Int((distance / 10).rounded()) * 10)
        if spoken { return "\(Phonetic.spellDigits(String(rounded), icao: icao)) miles" }
        return "\(rounded) miles"
    }

    private func movementPhrase(_ hazard: WeatherHazard, spoken: Bool) -> String {
        let dir = Geo.cardinal(hazard.movementDirectionDegrees ?? 0)
        let spd = Int((hazard.movementSpeedKnots ?? 0).rounded())
        if spoken { return "moving \(dir) at \(Phonetic.spellDigits(String(spd), icao: icao)) knots" }
        return "moving \(dir) at \(spd) knots"
    }

    private func altDisplay(_ feet: Int) -> String { engine.formatAltDisplay(feet) }
    private func altSpoken(_ feet: Int) -> String { Phonetic.altitude(feet, icao: icao) }
    private func headingDisplay(_ deg: Int) -> String { String(format: "%03d", ((deg % 360) + 360) % 360) }

    /// Capitalize the first character so a read-back that leads with a reused
    /// controller clause ("deviation …") reads as a sentence ("Deviation …").
    private func cap(_ s: String) -> String {
        s.isEmpty ? s : s.prefix(1).uppercased() + s.dropFirst()
    }

    static let clockWords: [Int: String] = [
        1: "one", 2: "two", 3: "three", 4: "four", 5: "five", 6: "six",
        7: "seven", 8: "eight", 9: "niner", 10: "one zero", 11: "one one", 12: "twelve"
    ]
}
