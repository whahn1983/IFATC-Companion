import Foundation
import CoreLocation

/// Pure, deterministic detection of route-weather conflicts. Builds a corridor
/// from the aircraft along its course and finds the most significant weather
/// hazard (radar precipitation cell, SIGMET polygon, or other hazard polygon) the
/// corridor passes through, then computes the geometry the deviation flow needs:
/// distance, clock position, left/right bypass, recommended deviation, and a
/// rejoin fix. No AI, no I/O — fully unit-testable.
struct RouteWeatherConflictDetector {

    struct Config {
        /// Lookahead band for terminal/departure/arrival phases (NM).
        var terminalLookahead: ClosedRange<Double> = 25...75
        /// Lookahead band for the enroute phase (NM).
        var enrouteLookahead: ClosedRange<Double> = 100...250
        /// Half-width of the route corridor around the course line (NM).
        var corridorHalfWidthNM: Double = 15
        /// Minutes of travel used for the time-based lookahead fallback.
        var timeLookaheadMinutes: ClosedRange<Double> = 30...120
        /// Light precipitation only prompts when this close and near-dead-ahead.
        var lightImmediateNM: Double = 20
        /// Max relative bearing (deg) for "directly ahead" (light-precip prompting).
        var directlyAheadDegrees: Double = 25
        /// How far beyond the weather a rejoin fix may sit (NM).
        var rejoinReachBeyondNM: Double = 150
        /// Minimum clearance past the far edge before a fix counts as "downstream".
        var rejoinDownstreamMarginNM: Double = 10
    }

    var config = Config()

    /// One projected sample: along-track and (signed, +right) cross-track NM, and
    /// the relative bearing from the course line.
    private struct Sample {
        var along: Double
        var cross: Double
        var relBearing: Double
    }

    // MARK: - Public API

    /// Detect the most significant route-weather conflict ahead, if any.
    /// - Parameters:
    ///   - position: current aircraft position.
    ///   - course: course/heading to fly (deg true) — bearing to the next fix or
    ///     the aircraft heading.
    ///   - groundspeedKnots: for the time-based lookahead + ETA (nil → phase band).
    ///   - phase: current flight phase (selects the lookahead band).
    ///   - hazards: normalized weather hazards to test.
    ///   - waypoints: filed route fixes, for rejoin-fix selection.
    func detectConflict(position: CLLocationCoordinate2D,
                        course: Double,
                        groundspeedKnots: Double?,
                        phase: FlightPhase,
                        hazards: [WeatherHazard],
                        waypoints: [Waypoint]) -> RouteWeatherConflict? {
        guard position.isValid, !hazards.isEmpty else { return nil }
        let lookahead = lookaheadNM(phase: phase, groundspeed: groundspeedKnots)
        let corridorEnd = Geo.destination(from: position, bearingDegrees: course, distanceNM: lookahead)

        var best: RouteWeatherConflict?
        for hazard in hazards {
            guard let conflict = conflict(for: hazard, position: position, course: course,
                                          lookahead: lookahead, corridorEnd: corridorEnd,
                                          groundspeed: groundspeedKnots, waypoints: waypoints) else {
                continue
            }
            if let current = best {
                // Prefer higher severity, then the nearer conflict.
                if conflict.severity > current.severity
                    || (conflict.severity == current.severity && conflict.distanceAheadNM < current.distanceAheadNM) {
                    best = conflict
                }
            } else {
                best = conflict
            }
        }
        return best
    }

    // MARK: - Lookahead

    /// The lookahead distance (NM) for the corridor, from the phase band clamped by
    /// a groundspeed-based time window.
    func lookaheadNM(phase: FlightPhase, groundspeed: Double?) -> Double {
        let band = isTerminal(phase) ? config.terminalLookahead : config.enrouteLookahead
        guard let gs = groundspeed, gs > 30 else { return band.upperBound }
        // Distance covered in the middle of the time window (~60 min ≈ gs NM),
        // clamped to the phase band and the time-window bounds.
        let nominal = gs * 1.0
        let timeLower = gs * config.timeLookaheadMinutes.lowerBound / 60
        let timeUpper = gs * config.timeLookaheadMinutes.upperBound / 60
        let clampedToTime = min(max(nominal, timeLower), timeUpper)
        return min(band.upperBound, max(band.lowerBound, clampedToTime))
    }

    private func isTerminal(_ phase: FlightPhase) -> Bool {
        switch phase {
        case .preflight, .taxiOut, .takeoff, .initialClimb,
             .approach, .landing, .taxiIn, .parked:
            return true
        case .climb, .cruise, .descent, .unknown:
            return false
        }
    }

    // MARK: - Per-hazard conflict

    private func conflict(for hazard: WeatherHazard,
                          position: CLLocationCoordinate2D,
                          course: Double,
                          lookahead: Double,
                          corridorEnd: CLLocationCoordinate2D,
                          groundspeed: Double?,
                          waypoints: [Waypoint]) -> RouteWeatherConflict? {
        let poly = hazard.geometry.polygonPoints
        let radiusBuffer = pointRadiusBuffer(hazard)
        let points = samplePoints(for: hazard)
        guard !points.isEmpty else { return nil }

        let samples = points.map { project($0, from: position, course: course) }
        let corridorHalf = config.corridorHalfWidthNM + radiusBuffer
        let inCorridor = samples.filter { $0.along >= -5 && $0.along <= lookahead && abs($0.cross) <= corridorHalf }

        // A wide cell can straddle the corridor with every vertex outside it; catch
        // that with a route-line-through-polygon test (endpoint inside, or an edge
        // crossing), mirroring the SIGMET route test.
        let lineThrough = poly.map { routeLinePasses(through: $0, from: position, to: corridorEnd) } ?? false
        guard !inCorridor.isEmpty || lineThrough else { return nil }

        // Distance is measured to the near edge that actually lies in the corridor.
        let relevant = inCorridor.isEmpty ? samples : inCorridor
        let nearAlong = max(0, (relevant.map { $0.along }.min() ?? 0) - radiusBuffer)
        let farAlong = max(nearAlong, relevant.map { $0.along }.max() ?? nearAlong)

        // Clock span and bypass extents come from the *whole* cell (not just the
        // in-corridor sliver), so a cell mostly off to one side is bypassed on the
        // cleaner side rather than steered into.
        let ahead = samples.filter { $0.along > -5 }
        let spanSamples = ahead.isEmpty ? samples : ahead
        let leftEdgeRel = spanSamples.map { $0.relBearing }.min() ?? 0
        let rightEdgeRel = spanSamples.map { $0.relBearing }.max() ?? 0
        let centerCoord = hazard.geometry.representativeCenter
        let centerRel = centerCoord.map { project($0, from: position, course: course).relBearing }
            ?? ((leftEdgeRel + rightEdgeRel) / 2)

        // Bypass extents: how far the cell reaches to each side of course.
        let rightExtent = spanSamples.map { max(0, $0.cross) }.max() ?? 0
        let leftExtent = spanSamples.map { max(0, -$0.cross) }.max() ?? 0
        // Deviate toward the side the cell reaches *less* (shorter, cleaner bypass);
        // ties go right (the conventional first offer).
        let direction: DeviationDirection = leftExtent < rightExtent ? .left : .right

        let severity = hazard.intensity
        let convective = hazard.isConvectiveSigmet
        let distance = nearAlong
        let eta = groundspeed.flatMap { $0 > 30 ? distance / $0 * 60 : nil }

        let degrees = recommendedDegrees(severity: severity, convective: convective,
                                         cellWidthNM: max(rightExtent, leftExtent))
        let prompt = shouldPrompt(severity: severity, convective: convective,
                                  distance: distance, centerRel: centerRel)

        let rejoin = rejoinFix(waypoints: waypoints, position: position, course: course,
                               farAlong: farAlong, lookahead: lookahead, polygon: poly)
        let segment = originalSegment(waypoints: waypoints, position: position, course: course,
                                      nearAlong: nearAlong, rejoin: rejoin)

        let area = poly ?? boxAround(centerCoord ?? corridorEnd)
        let path = deviationPath(position: position, course: course,
                                 nearAlong: nearAlong, farAlong: farAlong,
                                 direction: direction,
                                 sideExtent: direction == .right ? rightExtent : leftExtent,
                                 rejoin: rejoin?.coordinate)

        return RouteWeatherConflict(
            hazard: hazard,
            distanceAheadNM: distance,
            relativeBearingDegrees: centerRel,
            leftClock: Self.clockPosition(relBearing: leftEdgeRel),
            centerClock: Self.clockPosition(relBearing: centerRel),
            rightClock: Self.clockPosition(relBearing: rightEdgeRel),
            estimatedTimeMinutes: eta,
            severity: severity,
            leftBypassScore: leftExtent,
            rightBypassScore: rightExtent,
            recommendedDirection: direction,
            recommendedDeviationDegrees: degrees,
            rejoinFix: rejoin,
            originalSegment: segment,
            shouldPrompt: prompt,
            intersectionArea: area,
            deviationPath: path)
    }

    // MARK: - Severity → prompting / degrees

    private func shouldPrompt(severity: WeatherIntensity, convective: Bool,
                              distance: Double, centerRel: Double) -> Bool {
        if convective { return true }
        switch severity {
        case .light, .unknown:
            // Light precipitation: only prompt when directly ahead and close.
            return distance <= config.lightImmediateNM && abs(centerRel) <= config.directlyAheadDegrees
        case .moderate, .heavy, .extreme:
            return true
        }
    }

    private func recommendedDegrees(severity: WeatherIntensity, convective: Bool,
                                    cellWidthNM: Double) -> Int {
        var degrees: Int
        switch severity {
        case .light, .unknown:
            degrees = 10
        case .moderate:
            degrees = 20
        case .heavy:
            degrees = 20
        case .extreme:
            degrees = 30
        }
        // Small cells only need a slight offset.
        if cellWidthNM < 8, severity <= .moderate { degrees = 10 }
        if convective { degrees = max(degrees, 30) }
        return degrees
    }

    // MARK: - Rejoin selection

    /// Pick a downstream filed fix to rejoin: past the far edge of the weather,
    /// not inside the hazard, within reasonable reach, and roughly on course.
    private func rejoinFix(waypoints: [Waypoint], position: CLLocationCoordinate2D,
                           course: Double, farAlong: Double, lookahead: Double,
                           polygon: [CLLocationCoordinate2D]?) -> Waypoint? {
        let located = waypoints.filter { $0.coordinate?.isValid ?? false }
        let candidates: [(wp: Waypoint, along: Double, cross: Double)] = located.compactMap { wp in
            guard let c = wp.coordinate else { return nil }
            let s = project(c, from: position, course: course)
            return (wp, s.along, s.cross)
        }
        let minAlong = farAlong + config.rejoinDownstreamMarginNM
        let maxAlong = lookahead + config.rejoinReachBeyondNM
        let onCourse = config.corridorHalfWidthNM * 3
        return candidates
            .filter { $0.along >= minAlong && $0.along <= maxAlong && abs($0.cross) <= onCourse }
            .filter { cand in
                guard let poly = polygon, let c = cand.wp.coordinate else { return true }
                return !WeatherRouteAnalyzer.pointInPolygon(c, poly)
            }
            .sorted { $0.along < $1.along }
            .first?.wp
    }

    /// The route segment the aircraft is leaving (fix before the weather → rejoin).
    private func originalSegment(waypoints: [Waypoint], position: CLLocationCoordinate2D,
                                 course: Double, nearAlong: Double,
                                 rejoin: Waypoint?) -> RouteSegmentRef? {
        guard let rejoin else { return nil }
        let located = waypoints.filter { $0.coordinate?.isValid ?? false }
        // The last fix still behind the near edge of the weather.
        let before = located
            .compactMap { wp -> (Waypoint, Double)? in
                guard let c = wp.coordinate else { return nil }
                return (wp, project(c, from: position, course: course).along)
            }
            .filter { $0.1 < nearAlong }
            .max { $0.1 < $1.1 }?.0
        guard let from = before else { return nil }
        return RouteSegmentRef(from: from.name, to: rejoin.name)
    }

    // MARK: - Geometry helpers

    /// Project a coordinate onto the course line from `position`.
    private func project(_ point: CLLocationCoordinate2D,
                         from position: CLLocationCoordinate2D,
                         course: Double) -> Sample {
        let d = Geo.distanceNM(from: position, to: point)
        let b = Geo.bearing(from: position, to: point)
        let deltaDeg = normalizedSigned(b - course)
        let delta = deltaDeg * .pi / 180
        let along = d * cos(delta)
        let cross = d * sin(delta)
        let relBearing = atan2(cross, along) * 180 / .pi
        return Sample(along: along, cross: cross, relBearing: relBearing)
    }

    /// Sample coordinates representing a hazard's shape.
    private func samplePoints(for hazard: WeatherHazard) -> [CLLocationCoordinate2D] {
        switch hazard.geometry {
        case .polygon(let pts):
            return pts.filter { $0.isValid }
        case .boundingBox(let box):
            return box.corners + [box.center]
        case .pointRadius(let center, _):
            return center.isValid ? [center] : []
        case .routeSegmentIntersection(let entry, let exit):
            return [entry, exit].filter { $0.isValid }
        }
    }

    private func pointRadiusBuffer(_ hazard: WeatherHazard) -> Double {
        if case .pointRadius(_, let r) = hazard.geometry { return max(0, r) }
        return 0
    }

    /// Whether the corridor line passes through the polygon (endpoint inside, or an
    /// edge crossing). Mirrors `WeatherRouteAnalyzer`.
    private func routeLinePasses(through polygon: [CLLocationCoordinate2D],
                                 from a: CLLocationCoordinate2D,
                                 to b: CLLocationCoordinate2D) -> Bool {
        if WeatherRouteAnalyzer.pointInPolygon(a, polygon) { return true }
        if WeatherRouteAnalyzer.pointInPolygon(b, polygon) { return true }
        var j = polygon.count - 1
        for i in polygon.indices {
            if Geo.segmentsIntersect(a, b, polygon[j], polygon[i]) { return true }
            j = i
        }
        return false
    }

    /// Build a small square polygon around a coordinate (fallback intersection area
    /// for point/segment hazards).
    private func boxAround(_ c: CLLocationCoordinate2D, half: Double = 0.25) -> [CLLocationCoordinate2D] {
        [CLLocationCoordinate2D(latitude: c.latitude - half, longitude: c.longitude - half),
         CLLocationCoordinate2D(latitude: c.latitude - half, longitude: c.longitude + half),
         CLLocationCoordinate2D(latitude: c.latitude + half, longitude: c.longitude + half),
         CLLocationCoordinate2D(latitude: c.latitude + half, longitude: c.longitude - half)]
    }

    /// A recommended deviation path: current position → lateral apex on the chosen
    /// side, abeam the middle of the weather → rejoin (fix or a downstream point).
    private func deviationPath(position: CLLocationCoordinate2D, course: Double,
                               nearAlong: Double, farAlong: Double,
                               direction: DeviationDirection, sideExtent: Double,
                               rejoin: CLLocationCoordinate2D?) -> [CLLocationCoordinate2D] {
        let midAlong = (nearAlong + farAlong) / 2
        let onCourse = Geo.destination(from: position, bearingDegrees: course, distanceNM: midAlong)
        let sideBearing = course + (direction == .right ? 90 : -90)
        let apex = Geo.destination(from: onCourse, bearingDegrees: sideBearing,
                                   distanceNM: sideExtent + 12)
        let rejoinCoord = rejoin
            ?? Geo.destination(from: position, bearingDegrees: course, distanceNM: farAlong + 20)
        return [position, apex, rejoinCoord]
    }

    // MARK: - Static formatting helpers

    /// Convert a relative bearing (0 = straight ahead, + = right) to a clock
    /// position 1…12. 0° → 12 o'clock, +90° → 3 o'clock, −90° → 9 o'clock.
    static func clockPosition(relBearing: Double) -> Int {
        let steps = Int((relBearing / 30).rounded())
        let mod = ((steps % 12) + 12) % 12
        return mod == 0 ? 12 : mod
    }

    /// Normalize an angle to (−180, 180].
    private func normalizedSigned(_ deg: Double) -> Double {
        var d = deg.truncatingRemainder(dividingBy: 360)
        if d > 180 { d -= 360 }
        if d <= -180 { d += 360 }
        return d
    }
}
