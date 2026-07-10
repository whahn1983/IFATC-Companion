import Foundation
import CoreLocation

/// Pure, deterministic detection of route-weather conflicts. Builds a corridor
/// from the aircraft along its course, finds the precipitation cells that block
/// it, and — instead of hopping around a single cell — projects every nearby cell
/// onto the cross-track axis and **threads the widest clear gap** between them
/// (going around the near end of a solid line when no gap is usable), the way a
/// controller vectors a pilot between cells. When a line lies roughly along course,
/// it also offers **side-hug** reroutes down either edge and flies the **shortest
/// path that stays clear of every cell**, so a long line is passed on the genuinely
/// shorter side rather than the one that merely needs the smallest initial turn.
/// No AI, no I/O — fully unit-testable.
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
        /// A candidate deviation path must stay at least this far from every
        /// precipitation cell to be accepted, so a reroute never threads a gap in one
        /// storm only to cut through another (NM).
        var pathClearanceNM: Double = 3
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
    /// recommended deviation around it — the shortest gap-threading or side-hug path
    /// that stays clear of every cell.
    /// - Parameters:
    ///   - position: current aircraft position.
    ///   - course: course/heading to fly (deg true) — bearing to the next fix or
    ///     the aircraft heading. Used as the corridor direction unless `routeAhead`
    ///     reveals weather on a later leg (see below).
    ///   - groundspeedKnots: for the time-based lookahead + ETA (nil → phase band).
    ///   - phase: current flight phase (selects the lookahead band).
    ///   - hazards: normalized weather hazards to test (moderate-or-greater
    ///     precipitation cells — SIGMET polygons are not fed here).
    ///   - waypoints: filed route fixes, for rejoin-fix selection.
    ///   - routeAhead: the upcoming route as ordered coordinates (fixes still ahead →
    ///     destination). When supplied, the corridor follows the route's bends into
    ///     the weather instead of a straight band along `course`, so a storm sitting
    ///     on a leg *after* a turn is still caught. Empty → straight-course detection.
    func detectConflict(position: CLLocationCoordinate2D,
                        course: Double,
                        groundspeedKnots: Double?,
                        phase: FlightPhase,
                        hazards: [WeatherHazard],
                        waypoints: [Waypoint],
                        routeAhead: [CLLocationCoordinate2D] = []) -> RouteWeatherConflict? {
        guard position.isValid, !hazards.isEmpty else { return nil }
        let lookahead = lookaheadNM(phase: phase, groundspeed: groundspeedKnots)
        let flyCourse = routeAwareCourse(position: position, fallback: course, routeAhead: routeAhead,
                                         hazards: hazards, lookahead: lookahead)
        // Rejoin on the route where it exits the weather — so when the route turns
        // (e.g. south) past the storm, the intercept is measured to that turn and a
        // deviation onto the shorter side wins. Nil when no route is supplied.
        let routeRejoin = routeRejoinCoord(position: position, routeAhead: routeAhead,
                                           hazards: hazards, lookahead: lookahead)
        return detect(position: position, course: flyCourse, groundspeedKnots: groundspeedKnots,
                      phase: phase, hazards: hazards, waypoints: waypoints, rejoinOverride: routeRejoin)
    }

    /// Aim the detection corridor along the route rather than a straight bearing to
    /// the next fix. The narrow corridor otherwise misses weather on the route
    /// *after* a turn — the sampler still finds the cells (its window is far wider),
    /// but the straight band slides past them, so hazards are seen with "no conflict".
    /// Walks the upcoming route polyline (within the lookahead), finds the nearest
    /// point on it that lies within a corridor half-width of a cell, and aims the
    /// course from the aircraft at that blockage. Returns `fallback` unchanged when no
    /// route is supplied or nothing on it is blocked — straight-ahead detection is
    /// then unaffected.
    private func routeAwareCourse(position: CLLocationCoordinate2D, fallback: Double,
                                  routeAhead: [CLLocationCoordinate2D],
                                  hazards: [WeatherHazard], lookahead: Double) -> Double {
        let ahead = routeAhead.filter { $0.isValid }
        guard !ahead.isEmpty else { return fallback }
        let route = [position] + ahead
        guard route.count >= 2 else { return fallback }

        // The nearest point on the upcoming route (within the lookahead) that sits
        // within a corridor half-width of a cell — the first place the route flies
        // into weather, wherever it bends.
        var bestAlong = Double.greatestFiniteMagnitude
        var bestPoint: CLLocationCoordinate2D?
        var cumulative = 0.0
        for i in 0..<(route.count - 1) {
            if cumulative > lookahead { break }
            let a = route[i], b = route[i + 1]
            let segLen = Geo.distanceNM(from: a, to: b)
            let steps = max(1, Int(segLen / 5))
            for s in 0...steps {
                let t = Double(s) / Double(steps)
                let along = cumulative + segLen * t
                if along > lookahead { break }
                let p = interpolate(a, b, t)
                for hazard in hazards {
                    let half = config.corridorHalfWidthNM + pointRadiusBuffer(hazard)
                    if distanceHazardToPointNM(hazard, p) <= half, along < bestAlong {
                        bestAlong = along
                        bestPoint = p
                    }
                }
            }
            cumulative += segLen
        }

        // Aim the course at the blockage. Too close for a stable bearing → keep the
        // filed course (the aircraft is essentially already at the weather).
        guard let point = bestPoint,
              Geo.distanceNM(from: position, to: point) > 5 else { return fallback }
        return Geo.bearing(from: position, to: point)
    }

    /// The point on the route where the deviation should rejoin: just past the far
    /// extent of the weather **along the route**. Because it follows the route's
    /// bends, a route that turns (say south) past the storm puts the rejoin on that
    /// turn — so the reroute's length is measured to the real intercept and the
    /// shorter-side deviation wins. Nil when no route is supplied or nothing on it is
    /// within the corridor (detection then rejoins straight ahead, as before).
    private func routeRejoinCoord(position: CLLocationCoordinate2D,
                                  routeAhead: [CLLocationCoordinate2D],
                                  hazards: [WeatherHazard], lookahead: Double) -> CLLocationCoordinate2D? {
        let ahead = routeAhead.filter { $0.isValid }
        guard !ahead.isEmpty else { return nil }
        let route = [position] + ahead
        guard route.count >= 2 else { return nil }

        // The farthest point along the route still within a corridor half-width of a
        // cell — where the route finally exits the weather.
        var weatherFarAlong: Double?
        var cumulative = 0.0
        for i in 0..<(route.count - 1) {
            if cumulative > lookahead { break }
            let a = route[i], b = route[i + 1]
            let segLen = Geo.distanceNM(from: a, to: b)
            let steps = max(1, Int(segLen / 5))
            for s in 0...steps {
                let t = Double(s) / Double(steps)
                let along = cumulative + segLen * t
                if along > lookahead { break }
                let p = interpolate(a, b, t)
                for hazard in hazards {
                    let half = config.corridorHalfWidthNM + pointRadiusBuffer(hazard)
                    if distanceHazardToPointNM(hazard, p) <= half {
                        weatherFarAlong = max(weatherFarAlong ?? 0, along)
                    }
                }
            }
            cumulative += segLen
        }
        guard let far = weatherFarAlong else { return nil }
        return pointOnRoute(route, atAlong: far + 20)
    }

    /// The coordinate a given distance along a route polyline (clamped to its end).
    private func pointOnRoute(_ route: [CLLocationCoordinate2D], atAlong target: Double) -> CLLocationCoordinate2D {
        var cumulative = 0.0
        for i in 0..<(route.count - 1) {
            let a = route[i], b = route[i + 1]
            let segLen = Geo.distanceNM(from: a, to: b)
            if cumulative + segLen >= target {
                let t = segLen <= 0 ? 0 : (target - cumulative) / segLen
                return interpolate(a, b, min(1, max(0, t)))
            }
            cumulative += segLen
        }
        return route.last ?? route[0]
    }

    /// Distance (NM) from a hazard's shape to a point: 0 inside a polygon, else the
    /// nearest edge / sample point.
    private func distanceHazardToPointNM(_ hazard: WeatherHazard, _ p: CLLocationCoordinate2D) -> Double {
        if let poly = hazard.geometry.polygonPoints, poly.count >= 3 {
            return distanceToPolygonNM(p, poly)
        }
        let pts = samplePoints(for: hazard)
        return pts.map { Geo.distanceNM(from: p, to: $0) }.min() ?? .greatestFiniteMagnitude
    }

    /// Linear interpolation between two coordinates (planar; adequate at corridor
    /// scale and consistent with the detector's other planar helpers).
    private func interpolate(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D,
                             _ t: Double) -> CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: a.latitude + (b.latitude - a.latitude) * t,
                               longitude: a.longitude + (b.longitude - a.longitude) * t)
    }

    /// The single-course detection body: builds the corridor from `position` along
    /// `course`, finds the blocking cells, threads/hugs the shortest clear reroute,
    /// and picks a downstream rejoin fix.
    private func detect(position: CLLocationCoordinate2D,
                        course: Double,
                        groundspeedKnots: Double?,
                        phase: FlightPhase,
                        hazards: [WeatherHazard],
                        waypoints: [Waypoint],
                        rejoinOverride: CLLocationCoordinate2D? = nil) -> RouteWeatherConflict? {
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
        // Candidate reroutes around the line come in two shapes:
        //   • single-apex doglegs (position → abeam-the-middle apex → rejoin) at each
        //     threadable gap and around either end, least-deviation first; and
        //   • side-hug paths (position → step out just before the near edge → hold
        //     that offset past the far edge → rejoin) down the left and right edges.
        // The hug is what lets a long line lying roughly along course be passed on the
        // genuinely shorter side — a single dogleg to the shared rejoin would cut back
        // across the line and be rejected. Every candidate is validated end-to-end and
        // the shortest one that stays clear is flown.
        let lineSet = lineCells.isEmpty ? blockers : lineCells
        let solution = threadSolution(cells: lineSet)
        let midAlong = max(0, (bandNear + bandFar) / 2)
        let onCourse = Geo.destination(from: position, bearingDegrees: course, distanceNM: midAlong)

        // The full along-track span of the whole line — its non-blocking cells can
        // reach past the blocking band — so a side-hug runs parallel far enough to
        // clear the entire line before closing back to the rejoin.
        let lineNear = lineSet.map { $0.nearAlong }.min() ?? bandNear
        let lineFar = max(lineNear, lineSet.map { $0.farAlong }.max() ?? bandFar)

        // The named downstream fix drives the ATC rejoin call ("proceed direct …").
        let rejoin = rejoinFix(waypoints: waypoints, position: position, course: course,
                               farAlong: bandFar, lookahead: lookahead, polygon: primary.polygon)
        // The drawn deviation, however, rejoins **just past the weather** — never at a
        // distant downstream fix. Chasing a far fix forces every candidate to swing
        // back across the storms to reach it, so a short one-side deviation gets
        // rejected and the reroute takes the long way round. Prefer the point where
        // the route itself exits the weather (so a route that turns past the storm
        // puts the intercept on that turn and the shorter side wins); otherwise return
        // to course right past the far edge. Either keeps each candidate compact.
        let rejoinCoord = rejoinOverride
            ?? Geo.destination(from: position, bearingDegrees: course, distanceNM: lineFar + 20)

        // A single dogleg abeam the middle of the line at a lateral offset.
        func apexPath(for target: Double) -> [CLLocationCoordinate2D] {
            let sideBearing = course + (target >= 0 ? 90 : -90)
            let apex = Geo.destination(from: onCourse, bearingDegrees: sideBearing, distanceNM: abs(target))
            return [position, apex, rejoinCoord]
        }

        // A path that hugs one side of the line: step out to `offset` just before the
        // near edge, hold it past the far edge, then close to the rejoin. `offset` is
        // the line's outboard edge on that side, so the parallel leg clears every cell.
        func hugPath(offset: Double) -> [CLLocationCoordinate2D] {
            let sideBearing = course + (offset >= 0 ? 90 : -90)
            let margin = config.lateralBufferNM
            let nearOn = Geo.destination(from: position, bearingDegrees: course,
                                         distanceNM: max(0, lineNear - margin))
            let farOn = Geo.destination(from: position, bearingDegrees: course,
                                        distanceNM: lineFar + margin)
            let pNear = Geo.destination(from: nearOn, bearingDegrees: sideBearing, distanceNM: abs(offset))
            let pFar = Geo.destination(from: farOn, bearingDegrees: sideBearing, distanceNM: abs(offset))
            return [position, pNear, pFar, rejoinCoord]
        }

        var candidates: [(path: [CLLocationCoordinate2D], target: Double)] =
            solution.targets.map { (path: apexPath(for: $0), target: $0) }
        candidates.append((path: hugPath(offset: solution.leftEdge), target: solution.leftEdge))
        candidates.append((path: hugPath(offset: solution.rightEdge), target: solution.rightEdge))

        // Validate the whole path against *every* precipitation cell, not just the
        // line being threaded — so we never avoid one storm and route into another.
        // Fly the shortest candidate whose path is clear. When none is clear (the
        // aircraft is boxed in), fall back to the candidate that stays **farthest**
        // from the cells — never the straight-through least-deviation one — so the
        // drawn line skirts the weather on the open side instead of cutting through a
        // core the way it did when it was jammed toward a distant rejoin.
        let allPolygons = hazards.compactMap { $0.geometry.polygonPoints }
        let clear = candidates.filter {
            pathIsClear($0.path, polygons: allPolygons, buffer: config.pathClearanceNM, origin: position)
        }
        let chosen = clear.min { pathLengthNM($0.path) < pathLengthNM($1.path) }
            ?? candidates.max { pathMinClearanceNM($0.path, polygons: allPolygons, origin: position)
                                < pathMinClearanceNM($1.path, polygons: allPolygons, origin: position) }
            ?? (path: apexPath(for: 0), target: 0)
        let target = chosen.target
        let direction: DeviationDirection = target >= 0 ? .right : .left
        let deviationPath = chosen.path
        let throughPoint = deviationPath[1]

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
            recommendedDirection: direction,
            recommendedDeviationDegrees: degrees,
            rejoinFix: rejoin,
            originalSegment: segment,
            shouldPrompt: prompt,
            intersectionArea: area,
            deviationPath: deviationPath)
    }

    // MARK: - Gap threading

    /// The candidate lateral offsets to steer for (signed cross-track NM, +right),
    /// ordered best-first, plus the line's outboard `leftEdge`/`rightEdge` used to
    /// build the side-hug paths. Projects the cells onto the cross-track axis, pads
    /// each by the lateral buffer, merges overlaps, and offers the interior gaps
    /// between adjacent cells (wide enough to fly) plus going around either end.
    /// `targets` is ordered by least deviation, then wider gap, then to the right (the
    /// conventional first offer); the caller validates each candidate's full path.
    private func threadSolution(cells: [Projection]) -> (targets: [Double], leftEdge: Double, rightEdge: Double) {
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
        guard let first = merged.first, let last = merged.last else {
            return (targets: [0], leftEdge: 0, rightEdge: 0)
        }

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

        let targets = pool.sorted { a, b in
            if abs(a.target) != abs(b.target) { return abs(a.target) < abs(b.target) }
            if a.width != b.width { return a.width > b.width }
            return a.target >= 0 && b.target < 0   // prefer the right side on an exact tie
        }.map { $0.target }

        return (targets: targets, leftEdge: first.lo, rightEdge: last.hi)
    }

    // MARK: - Path clearance

    /// Whether a deviation path stays at least `buffer` NM clear of every cell
    /// polygon along its whole length — the guard that stops a reroute from threading
    /// one storm and cutting into another. Samples each leg and ignores the immediate
    /// vicinity of `origin` (the aircraft may already be in light precipitation; we
    /// only care that the path ahead stays clear).
    private func pathIsClear(_ path: [CLLocationCoordinate2D],
                             polygons: [[CLLocationCoordinate2D]],
                             buffer: Double, origin: CLLocationCoordinate2D) -> Bool {
        guard path.count >= 2, !polygons.isEmpty else { return true }
        let startSkip = 8.0
        for i in 0..<(path.count - 1) {
            let a = path[i], b = path[i + 1]
            let steps = max(1, Int(Geo.distanceNM(from: a, to: b) / 4))
            for s in 0...steps {
                let f = Double(s) / Double(steps)
                let p = CLLocationCoordinate2D(latitude: a.latitude + (b.latitude - a.latitude) * f,
                                               longitude: a.longitude + (b.longitude - a.longitude) * f)
                if Geo.distanceNM(from: origin, to: p) < startSkip { continue }
                for poly in polygons where distanceToPolygonNM(p, poly) < buffer { return false }
            }
        }
        return true
    }

    /// Total great-circle length (NM) of a multi-leg path — the objective the
    /// selector minimizes over the candidate reroutes that stay clear.
    private func pathLengthNM(_ path: [CLLocationCoordinate2D]) -> Double {
        guard path.count >= 2 else { return 0 }
        var total = 0.0
        for i in 0..<(path.count - 1) {
            total += Geo.distanceNM(from: path[i], to: path[i + 1])
        }
        return total
    }

    /// The smallest clearance (NM) from a path to any cell polygon along its whole
    /// length (0 where it enters one), ignoring the immediate vicinity of the origin.
    /// Used only as the boxed-in tie-breaker: with no fully-clear candidate, fly the
    /// one that keeps the most room from the weather rather than the one that happens
    /// to need the smallest turn (which can drive straight through a core).
    private func pathMinClearanceNM(_ path: [CLLocationCoordinate2D],
                                    polygons: [[CLLocationCoordinate2D]],
                                    origin: CLLocationCoordinate2D) -> Double {
        guard path.count >= 2, !polygons.isEmpty else { return .greatestFiniteMagnitude }
        let startSkip = 8.0
        var worst = Double.greatestFiniteMagnitude
        for i in 0..<(path.count - 1) {
            let a = path[i], b = path[i + 1]
            let steps = max(1, Int(Geo.distanceNM(from: a, to: b) / 4))
            for s in 0...steps {
                let f = Double(s) / Double(steps)
                let p = interpolate(a, b, f)
                if Geo.distanceNM(from: origin, to: p) < startSkip { continue }
                for poly in polygons { worst = min(worst, distanceToPolygonNM(p, poly)) }
            }
        }
        return worst
    }

    /// Distance (NM) from a point to a polygon: 0 inside, else the nearest edge.
    private func distanceToPolygonNM(_ p: CLLocationCoordinate2D, _ poly: [CLLocationCoordinate2D]) -> Double {
        guard poly.count >= 3 else { return .greatestFiniteMagnitude }
        if WeatherRouteAnalyzer.pointInPolygon(p, poly) { return 0 }
        var best = Double.greatestFiniteMagnitude
        var j = poly.count - 1
        for i in poly.indices {
            best = min(best, pointToSegmentNM(p, poly[j], poly[i]))
            j = i
        }
        return best
    }

    /// Point-to-segment distance (NM) using a local equirectangular NM plane.
    private func pointToSegmentNM(_ p: CLLocationCoordinate2D,
                                  _ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D) -> Double {
        let latScale = 60.0
        let lonScale = 60.0 * cos(p.latitude * .pi / 180)
        let px = p.longitude * lonScale, py = p.latitude * latScale
        let ax = a.longitude * lonScale, ay = a.latitude * latScale
        let bx = b.longitude * lonScale, by = b.latitude * latScale
        let dx = bx - ax, dy = by - ay
        let lenSq = dx * dx + dy * dy
        let t = lenSq <= 0 ? 0 : max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / lenSq))
        let cx = ax + t * dx, cy = ay + t * dy
        return hypot(px - cx, py - cy)
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
