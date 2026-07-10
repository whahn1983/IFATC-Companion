import Foundation

/// Deterministic coordinator for the simulated weather-deviation flow. It decides
/// which advisory applies, walks the `WeatherDeviationState` lifecycle, and emits
/// the matching controller/pilot transmissions via `WeatherDeviationPhraseology`.
/// It never forces a deviation — it recommends and simulates ATC. No AI, no I/O.
struct WeatherDeviationEngine {

    let phraseology: WeatherDeviationPhraseology
    var engine: PhraseologyEngine { phraseology.engine }

    typealias Callsign = PhraseologyEngine.Callsign

    /// What weather situation the advisory should describe.
    enum Situation {
        /// A radar precipitation conflict along the route.
        case radarConflict(RouteWeatherConflict)
        /// A SIGMET along the route (outside radar coverage or radar off).
        case sigmet(label: String, convective: Bool)
        /// A turbulence / icing SIGMET along the route. There is nothing to laterally
        /// route around, so the advisory recommends an altitude change (smoother air,
        /// or out of the icing) rather than a deviation.
        case rideSigmet(label: String, icing: Bool)
        /// Radar unavailable and no advisory data — do not invent precipitation.
        case noRadarNoAdvisory
    }

    /// Parameters the concrete approval calls need, supplied by `AppModel` from the
    /// flight plan / assigned altitude / telemetry.
    struct Inputs {
        var maintainAltitude: Int
        var heading: Int
        var onSTAR: Bool = false
        var starDisplay: String = ""
        var starSpoken: String = ""
        var nearRoute: Bool = false
        /// The requested side is unavailable (traffic) — approve the other side.
        var unableRequestedSide: Bool = false
    }

    /// The output of a step: an optional pilot line, the controller reply(ies), and
    /// the updated deviation context.
    struct Result {
        var pilot: ATCTransmission?
        var atc: [ATCTransmission]
        var context: WeatherDeviationContext
    }

    // MARK: - Advisory

    /// Issue the appropriate advisory for a detected situation and move to
    /// awaiting-pilot-intentions (or the terminal radar-unavailable state).
    func issueAdvisory(cs: Callsign, situation: Situation, context: WeatherDeviationContext,
                       facility: ATCFacility) -> Result {
        var ctx = context
        let tx: ATCTransmission
        switch situation {
        case .radarConflict(let conflict):
            tx = phraseology.radarAdvisory(cs: cs, conflict: conflict, facility: facility)
            ctx.state = .awaitingPilotIntentions
            ctx.activeHazardID = conflict.hazard.id
            ctx.rejoinFix = conflict.rejoinFix?.name
            ctx.originalRouteSegment = conflict.originalSegment
            ctx.requestedDeviationDirection = conflict.recommendedDirection
        case .sigmet(let label, let convective):
            tx = convective
                ? phraseology.sigmetConvectiveAdvisory(cs: cs, facility: facility)
                : phraseology.sigmetAdvisory(cs: cs, hazardLabel: label, facility: facility)
            ctx.state = .awaitingPilotIntentions
        case .rideSigmet(let label, let icing):
            tx = phraseology.sigmetRideAdvisory(cs: cs, hazardLabel: label, icing: icing, facility: facility)
            ctx.state = .awaitingPilotIntentions
        case .noRadarNoAdvisory:
            tx = phraseology.noRadarNoAdvisory(cs: cs, facility: facility)
            ctx.state = .radarUnavailableForRegion
        }
        ctx.lastATCWeatherCall = tx.displayText
        return Result(pilot: nil, atc: [tx], context: ctx)
    }

    // MARK: - Deviation request → approval

    /// Pilot requests a left/right deviation; controller approves (with a rejoin
    /// fix when one is available, else "advise clear of weather"). On a STAR the
    /// altitude restriction is preserved with "maintain" and the rejoin is framed
    /// as rejoining the arrival.
    func requestDeviation(cs: Callsign, conflict: RouteWeatherConflict?,
                          direction requested: DeviationDirection, inputs: Inputs,
                          context: WeatherDeviationContext, facility: ATCFacility) -> Result {
        var ctx = context
        let approved = inputs.unableRequestedSide ? requested.opposite : requested
        let degrees = conflict?.recommendedDeviationDegrees
        let rejoin = conflict?.rejoinFix?.name ?? context.rejoinFix

        let pilotTx = phraseology.pilotRequestDeviation(cs: cs, direction: requested,
                                                        degrees: degrees ?? 20, facility: facility)

        let approval: ATCTransmission
        if inputs.unableRequestedSide {
            approval = phraseology.unableSideApproval(cs: cs, requested: requested, approved: approved,
                                                      degrees: degrees, maintainAltitude: inputs.maintainAltitude,
                                                      facility: facility)
        } else if inputs.onSTAR, let rejoin {
            approval = phraseology.starDeviationApproval(cs: cs, direction: approved, degrees: degrees,
                                                         maintainAltitude: inputs.maintainAltitude,
                                                         starDisplay: inputs.starDisplay, starSpoken: inputs.starSpoken,
                                                         rejoinFix: rejoin, facility: facility)
        } else if let rejoin {
            approval = phraseology.approvalWithRejoin(cs: cs, direction: approved, degrees: degrees,
                                                      maintainAltitude: inputs.maintainAltitude,
                                                      rejoinFix: rejoin, facility: facility)
        } else {
            approval = phraseology.approvalNoRejoin(cs: cs, direction: approved, degrees: degrees,
                                                    maintainAltitude: inputs.maintainAltitude, facility: facility)
        }

        ctx.state = .deviationApproved
        ctx.requestedDeviationDirection = requested
        ctx.approvedDeviationDegrees = degrees
        ctx.maintainAltitude = inputs.maintainAltitude
        ctx.rejoinFix = rejoin
        ctx.assignedHeading = nil
        ctx.lastATCWeatherCall = approval.displayText
        return Result(pilot: pilotTx, atc: [approval], context: ctx)
    }

    /// Pilot requests vectors; controller assigns a heading around precipitation.
    func requestVectors(cs: Callsign, inputs: Inputs, context: WeatherDeviationContext,
                        facility: ATCFacility) -> Result {
        var ctx = context
        let pilotTx = phraseology.pilotRequestVectors(cs: cs, facility: facility)
        let approval = phraseology.vectorApproval(cs: cs, heading: inputs.heading,
                                                  maintainAltitude: inputs.maintainAltitude, facility: facility)
        ctx.state = .vectoringAroundWeather
        ctx.assignedHeading = inputs.heading
        ctx.maintainAltitude = inputs.maintainAltitude
        ctx.lastATCWeatherCall = approval.displayText
        return Result(pilot: pilotTx, atc: [approval], context: ctx)
    }

    /// At the turn in the deviation path the controller automatically turns the
    /// aircraft back to intercept and rejoin the filed route. Keeps the vectoring
    /// state (the pilot still advises clear of weather) and clears the armed turn.
    func rejoinTurn(cs: Callsign, heading: Int, rejoinFix: String?,
                    context: WeatherDeviationContext, facility: ATCFacility) -> Result {
        var ctx = context
        let tx = phraseology.rejoinInterceptVector(cs: cs, heading: heading,
                                                   rejoinFix: rejoinFix, facility: facility)
        ctx.assignedHeading = heading
        ctx.pendingRejoinHeading = nil
        ctx.vectorApexLatitude = nil
        ctx.vectorApexLongitude = nil
        ctx.vectorLegBearing = nil
        ctx.lastATCWeatherCall = tx.displayText
        return Result(pilot: nil, atc: [tx], context: ctx)
    }

    /// Pilot requests higher/lower for weather; controller assigns the altitude.
    func requestAltitude(cs: Callsign, higher: Bool, targetAltitude: Int,
                         context: WeatherDeviationContext, facility: ATCFacility) -> Result {
        var ctx = context
        let pilotTx = phraseology.pilotRequestAltitude(cs: cs, higher: higher, facility: facility)
        let verb = higher ? "climb and maintain" : "descend and maintain"
        let verbCap = higher ? "Climb and maintain" : "Descend and maintain"
        var atc = ATCTransmission(sender: .atc, facility: facility,
            displayText: "\(cs.display), \(verb) \(engine.formatAltDisplay(targetAltitude)) for weather, advise clear of weather.",
            spokenText: "\(cs.spoken), \(verb) \(Phonetic.altitude(targetAltitude, icao: engine.icao)) for weather, advise clear of weather.")
        atc.readback = ATCTransmission.Readback(
            displayText: "\(verbCap) \(engine.formatAltDisplay(targetAltitude)), \(cs.display).",
            spokenText: "\(verbCap) \(Phonetic.altitude(targetAltitude, icao: engine.icao)), \(cs.spoken).",
            facility: facility)
        ctx.state = .deviatingAroundWeather
        ctx.maintainAltitude = targetAltitude
        ctx.lastATCWeatherCall = atc.displayText
        return Result(pilot: pilotTx, atc: [atc], context: ctx)
    }

    // MARK: - Clear of weather

    /// Pilot reports clear of weather; controller clears back to the filed route or
    /// the downstream rejoin fix (or, on a STAR, rejoins the arrival).
    func reportClearOfWeather(cs: Callsign, inputs: Inputs, context: WeatherDeviationContext,
                              facility: ATCFacility) -> Result {
        var ctx = context
        let pilotTx = phraseology.pilotClearOfWeather(cs: cs, facility: facility)
        let rejoin = context.rejoinFix
        let atc: ATCTransmission
        if inputs.onSTAR, let rejoin {
            atc = phraseology.rejoinStar(cs: cs, rejoinFix: rejoin,
                                         starDisplay: inputs.starDisplay, starSpoken: inputs.starSpoken,
                                         facility: facility)
        } else {
            atc = phraseology.clearOfWeatherResume(cs: cs, rejoinFix: rejoin,
                                                   nearRoute: inputs.nearRoute, facility: facility)
        }
        ctx.state = .resumedOwnNavigation
        ctx.lastATCWeatherCall = atc.displayText
        return Result(pilot: pilotTx, atc: [atc], context: ctx)
    }
}
