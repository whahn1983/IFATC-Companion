import Foundation
import CoreLocation

/// Pure, deterministic detection of route-weather conflicts. Builds a corridor
/// from the aircraft along its course, finds the precipitation cells that block
/// it, and — instead of hopping around a single cell — projects every nearby cell
/// onto the cross-track axis and **threads the widest clear gap** between them
/// (going around the near end of a solid line when no gap is usable), the way a
/// controller vectors a pilot between cells. No AI, no I/O — fully unit-testable.
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

        // MARK: Gap threading
        /// Lateral clearance kept on each side of a precipitation cell (NM). Cells
        /// are padded by this before gaps are measured, so a threaded gap keeps this
        /// much room from the actual precipitation on both sides.
        var lateralBufferNM: Double = 6
        /// Minimum *clear* lateral width (after the buffers) for a gap between two
        /// cells to count as threadable (NM).
        var minGapWidthNM: Double = 4
        /// How far off the course line to look for a threadable gap or an
        /// around-the-end bypass (NM).
        var searchHalfWidthNM: Double = 60
        /// Cells whose along-track position is within this margin of the blocking
        /// band are treated as part of the same line for gap analysis (NM).
        var clusterAlongMarginNM: Double = 30
    }

    var config = Config()

    /// One projected sample: along-track and (signed, +right) cross-track NM, and
    /// the relative bearing from the course line.
    private struct Sample {
        var along: Double
        var cross: Double
        var relBearing: Double
    }

    /// A cell projected into the course-relative (along/cross) frame — the reduced
    /// form the corridor and gap-threading logic reason about.
    private struct Projection {
        let hazard: WeatherHazard
        let polygon: [CLLocationCoordinate2D]?
        let center: CLLocationCoordinate2D?
        let radiusBuffer: Double
        /// Along-track extent over all samples.
        let alongMin: Double
        let alongMax: Double
        /// Near/far edge along-track for the portion in front (clamped ≥ 0).
        let nearAlong: Double
        let farAlong: Double
        /// Cross-track extent (signed, +right) of the portion ahead, buffered by any
        /// point-radius. Used to build the lateral gap intervals.
        let crossLo: Double
        let crossHi: Double
        let leftEdgeRel: Double
        let rightEdgeRel: Double
        let centerRel: Double
        let leftExtent: Double
        let rightExtent: Double
        /// Whether this cell actually intersects the route corridor (a blocker).
        let blocks: Bool
    }

    // MARK: - Public API

    /// Detect the most significant route-weather conflict ahead, if any, and the
    /// recommended gap-threading deviation around it.
    /// - Parameters:
    ///   - position: current aircraft position.
    ///   - course: course/heading to fly (deg true) — bearing to the next fix or
    ///     the aircraft heading.
    ///   - groundspeedKnots: for the time-based lookahead + ETA (nil → phase band).
    ///   - phase: current flight phase (selects the lookahead band).
    ///   - hazards: normalized weather hazards to test (moderate-or-greater
    ///     precipitation cells — SIGMET polygons are not fed here).
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

        // Project every hazard; keep those that sit ahead and near the route.
        let projections = hazards.compactMap {
            projectHazard($0, position: position, course: course,
                          lookahead: lookahead, corridorEnd: corridorEnd)
        }
        let blockers = projections.filter { $0.blocks }
        guard !blockers.isEmpty else { return nil }

        // The most significant blocker anchors severity, distance, clock and rejoin.
        let primary = blockers.dropFirst().reduce(blockers[0]) { best, cand in
            if cand.hazard.intensity > best.hazard.intensity { return cand }
            if cand.hazard.intensity == best.hazard.intensity, cand.nearAlong < best.nearAlong { return cand }
            return best
        }

        // The blocking "line" the aircraft is about to cross (all blockers, by
        // along-track extent), plus any nearby non-blocking cells that form part of
        // the same line — these are what the gap solver threads between.
        let bandNear = blockers.map { $0.nearAlong }.min() ?? primary.nearAlong
        let bandFar = blockers.map { $0.farAlong }.max() ?? primary.farAlong
        let lineCells = projections.filter {
            $0.alongMax >= bandNear - config.clusterAlongMarginNM
                && $0.alongMin <= bandFar + config.clusterAlongMarginNM
        }
        let gap = threadGap(cells: lineCells.isEmpty ? blockers : lineCells)

        // Build the deviation path that threads the chosen gap:
        //   position → through-point (abeam the middle of the line, offset into the
        //   gap) → rejoin (downstream fix or a point past the far edge).
        let midAlong = max(0, (bandNear + bandFar) / 2)
        let onCourse = Geo.destination(from: position, bearingDegrees: course, distanceNM: midAlong)
        let sideBearing = course + (gap.direction == .right ? 90 : -90)
        let throughPoint = Geo.destination(from: onCourse, bearingDegrees: sideBearing,
                                           distanceNM: abs(gap.target))

        let rejoin = rejoinFix(waypoints: waypoints, position: position, course: course,
                               farAlong: bandFar, lookahead: lookahead, polygon: primary.polygon)
        let rejoinCoord = rejoin?.coordinate
            ?? Geo.destination(from: position, bearingDegrees: course, distanceNM: bandFar + 20)
        let path = [position, throughPoint, rejoinCoord]

        // Speak the deviation the drawn line actually flies: the initial turn from
        // course to the through-point, rounded to 5°, with a severity-based floor.
        let degrees = deviationDegrees(position: position, course: course,
                                       throughPoint: throughPoint, severity: primary.hazard.intensity)

        let segment = originalSegment(waypoints: waypoints, position: position, course: course,
                                      nearAlong: primary.nearAlong, rejoin: rejoin)
        let distance = primary.nearAlong
        let eta = groundspeedKnots.flatMap { $0 > 30 ? distance / $0 * 60 : nil }
        let area = primary.polygon ?? boxAround(primary.center ?? corridorEnd)
        let prompt = shouldPrompt(severity: primary.hazard.intensity,
                                  convective: primary.hazard.isConvectiveSigmet,
                                  distance: distance, centerRel: primary.centerRel)

        return RouteWeatherConflict(
            hazard: primary.hazard,
            distanceAheadNM: distance,
            relativeBearingDegrees: primary.centerRel,
            leftClock: Self.clockPosition(relBearing: primary.leftEdgeRel),
            centerClock: Self.clockPosition(relBearing: primary.centerRel),
            rightClock: Self.clockPosition(relBearing: primary.rightEdgeRel),
            estimatedTimeMinutes: eta,
            severity: primary.hazard.intensity,
            leftBypassScore: primary.leftExtent,
            rightBypassScore: primary.rightExtent,
            recommendedDirection: gap.direction,
            recommendedDeviationDegrees: degrees,
            rejoinFix: rejoin,
            originalSegment: segment,
            shouldPrompt: prompt,
            intersectionArea: area,
            deviationPath: path)
    }

    // MARK: - Gap threading

    /// The lateral offset (signed cross-track NM, +right) to steer for, and which
    /// side of course that is. Projects the cells onto the cross-track axis, pads
    /// each by the lateral buffer, merges overlaps, and picks the reachable target
    /// requiring the least deviation among clear gaps — threading between cells when
    /// an adequately-wide gap exists, otherwise going around the nearer end of the
    /// line. Ties break toward the wider gap, then to the right (the conventional
    /// first offer).
    private func threadGap(cells: [Projection]) -> (target: Double, direction: DeviationDirection) {
        var intervals: [(lo: Double, hi: Double)] = cells.map {
            let buf = config.lateralBufferNM + $0.radiusBuffer
            return (lo: $0.crossLo - buf, hi: $0.crossHi + buf)
        }
        intervals.sort { $0.lo < $1.lo }

        var merged: [(lo: Double, hi: Double)] = []
        for iv in intervals {
            if let last = merged.last, iv.lo <= last.hi {
                merged[merged.count - 1].hi = max(last.hi, iv.hi)
            } else {
                merged.append(iv)
            }
        }
        guard let first = merged.first, let last = merged.last else { return (0, .right) }

        struct Candidate { var target: Double; var width: Double }
        var candidates: [Candidate] = []
        // Interior gaps between adjacent cells — only if wide enough to be flown.
        for i in 0..<(merged.count - 1) {
            let gapLo = merged[i].hi
            let gapHi = merged[i + 1].lo
            let width = gapHi - gapLo
            if width >= config.minGapWidthNM {
                candidates.append(Candidate(target: (gapLo + gapHi) / 2, width: width))
            }
        }
        // Around either end of the whole line (open air outboard of the edges).
        candidates.append(Candidate(target: first.lo, width: config.searchHalfWidthNM))
        candidates.append(Candidate(target: last.hi, width: config.searchHalfWidthNM))

        let reachable = candidates.filter { abs($0.target) <= config.searchHalfWidthNM }
        let pool = reachable.isEmpty ? candidates : reachable

        let best = pool.min { a, b in
            if abs(a.target) != abs(b.target) { return abs(a.target) < abs(b.target) }
            if a.width != b.width { return a.width > b.width }
            return a.target >= 0 && b.target < 0   // prefer the right side on an exact tie
        } ?? Candidate(target: last.hi, width: 0)

        return (best.target, best.target >= 0 ? .right : .left)
    }

    /// The spoken deviation amount: the initial turn from course to the through-
    /// point, rounded to 5° and clamped, with a severity-based floor so heavier
    /// precipitation is never given a token offset.
    private func deviationDegrees(position: CLLocationCoordinate2D, course: Double,
                                  throughPoint: CLLocationCoordinate2D,
                                  severity: WeatherIntensity) -> Int {
        let turn = abs(normalizedSigned(Geo.bearing(from: position, to: throughPoint) - course))
        var degrees = Int((turn / 5).rounded()) * 5
        switch severity {
        case .extreme: degrees = max(degrees, 30)
        case .heavy, .moderate: degrees = max(degrees, 15)
        case .light, .unknown: break
        }
        return min(45, max(10, degrees))
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

    // MARK: - Projection

    /// Project a hazard into the course-relative frame, or nil when it has no valid
    /// geometry or sits entirely behind / beyond the searched band.
    private func projectHazard(_ hazard: WeatherHazard,
                               position: CLLocationCoordinate2D,
                               course: Double,
                               lookahead: Double,
                               corridorEnd: CLLocationCoordinate2D) -> Projection? {
        let poly = hazard.geometry.polygonPoints
        let radiusBuffer = pointRadiusBuffer(hazard)
        let points = samplePoints(for: hazard)
        guard !points.isEmpty else { return nil }
        let samples = points.map { project($0, from: position, course: course) }

        let alongMin = samples.map { $0.along }.min() ?? 0
        let alongMax = samples.map { $0.along }.max() ?? 0
        // Drop cells fully behind, or beyond the searched band.
        guard alongMax > -5, alongMin < lookahead + config.clusterAlongMarginNM else { return nil }

        // Corridor blocking test: a sample inside the corridor, or (for a wide cell
        // whose vertices all straddle it) the route line passing through the polygon.
        let corridorHalf = config.corridorHalfWidthNM + radiusBuffer
        let inCorridor = samples.filter { $0.along >= -5 && $0.along <= lookahead && abs($0.cross) <= corridorHalf }
        let lineThrough = poly.map { routeLinePasses(through: $0, from: position, to: corridorEnd) } ?? false
        let blocks = !inCorridor.isEmpty || lineThrough

        // Near/far edge measured from the in-corridor portion (or the whole cell).
        let relevant = inCorridor.isEmpty ? samples : inCorridor
        let nearAlong = max(0, (relevant.map { $0.along }.min() ?? 0) - radiusBuffer)
        let farAlong = max(nearAlong, (relevant.map { $0.along }.max() ?? nearAlong) + radiusBuffer)

        // Clock span + cross extents come from the whole cell's forward portion.
        let ahead = samples.filter { $0.along > -5 }
        let spanSamples = ahead.isEmpty ? samples : ahead
        let crossLo = (spanSamples.map { $0.cross }.min() ?? 0)
        let crossHi = (spanSamples.map { $0.cross }.max() ?? 0)
        let leftEdgeRel = spanSamples.map { $0.relBearing }.min() ?? 0
        let rightEdgeRel = spanSamples.map { $0.relBearing }.max() ?? 0
        let centerCoord = hazard.geometry.representativeCenter
        let centerRel = centerCoord.map { project($0, from: position, course: course).relBearing }
            ?? ((leftEdgeRel + rightEdgeRel) / 2)
        let rightExtent = spanSamples.map { max(0, $0.cross) }.max() ?? 0
        let leftExtent = spanSamples.map { max(0, -$0.cross) }.max() ?? 0

        return Projection(hazard: hazard, polygon: poly, center: centerCoord, radiusBuffer: radiusBuffer,
                          alongMin: alongMin, alongMax: alongMax, nearAlong: nearAlong, farAlong: farAlong,
                          crossLo: crossLo, crossHi: crossHi, leftEdgeRel: leftEdgeRel, rightEdgeRel: rightEdgeRel,
                          centerRel: centerRel, leftExtent: leftExtent, rightExtent: rightExtent, blocks: blocks)
    }

    // MARK: - Severity → prompting

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
