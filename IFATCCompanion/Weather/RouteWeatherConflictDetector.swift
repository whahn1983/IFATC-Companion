import Foundation
import CoreLocation

/// Pure, deterministic detection of route-weather conflicts. Builds a corridor
/// from the aircraft along its course, finds the precipitation cells that block
/// it, and — instead of hopping around a single cell — projects every nearby cell
/// onto the cross-track axis and **threads the widest clear gap** between them
/// (going around the near end of a solid line when no gap is usable), the way a
/// controller vectors a pilot between cells. When a line lies roughly along course,
/// it also offers **side-hug** reroutes down either edge.
///
/// The reroute is deliberately conservative, matching how a real tactical deviation
/// works:
/// - **Only when the weather is genuinely upcoming.** A deviation is surfaced only
///   once the blocking weather's near edge is within `deviationTriggerNM`; farther
///   out the route ahead is still clear, so no line is drawn yet.
/// - **Tight to the storm, wide only as a last resort.** Candidates are ranked so the
///   line hugs the weather: the shortest routine-width path clear of every cell, else
///   the shortest that at least clears the intense (heavy/extreme) cores while skirting
///   lighter precip, and only then — when nothing tight can dodge the cores — the
///   shortest wide detour. It never swings far out of the way when a closer path exists.
/// - **Validated as drawn.** Every candidate is finalized (capped to the rejoin limit,
///   bounded to the max off-course turn) *before* it is validated for clearance, so the
///   line actually drawn is the one checked against the cells.
/// - **Ends at the first route intercept.** The drawn line rejoins the filed route once
///   and stops; it never crosses the route and loops back to intercept a second time.
/// No AI, no I/O — fully unit-testable.
struct RouteWeatherConflictDetector {

    struct Config {
        /// Lookahead band for terminal/departure/arrival phases (NM).
        var terminalLookahead: ClosedRange<Double> = 25...75
        /// Lookahead band for the enroute phase (NM).
        var enrouteLookahead: ClosedRange<Double> = 80...180
        /// How close the on-path weather's near edge must be (NM) before the conflict
        /// is worked as a *tactical* deviation — i.e. the "contact ATC" banner is raised
        /// and (in Mock Mode) the advisory is auto-issued (`withinTacticalRange` →
        /// `shouldPrompt`). The **mint line itself is drawn as soon as on-path weather is
        /// detected anywhere within the lookahead**, so a pilot sees the suggested
        /// reroute far ahead; only the banner/ATC call holds off until the aircraft
        /// closes to within this range. Real-world tactical convective deviations are
        /// flown from close in — pilots avoid severe/extreme echoes by ~20 NM laterally
        /// (FAA AC 00-24C) and typically start deviating ~20–40 NM out, with ATC
        /// coordinating a little earlier — so the banner appearing 100–250 NM in advance
        /// is neither realistic nor useful, while the advisory line ahead is.
        var deviationTriggerNM: Double = 60
        /// How close the on-path weather's near edge must be (NM) before the mint reroute
        /// line is actually **drawn** on the map. The conflict is still *detected* out to
        /// the full lookahead (so Diagnostics can report far weather as "monitoring"), but
        /// the drawn line is held until the weather is within this range. Drawing the line
        /// for weather far ahead — often past one or more route bends — produced a long
        /// straight line that cut across the near route legs toward distant weather (the
        /// "crazy mint line"): the deviation is built as a straight-corridor offset aimed
        /// at the blockage, which is only geometrically meaningful once the aircraft is
        /// roughly committed toward it. Set just beyond `deviationTriggerNM` so the
        /// reroute still appears a little before the "contact ATC" banner, but never at
        /// the far edge of the enroute lookahead where it reads as a runaway line.
        var mintLineDrawNM: Double = 75
        /// Half-width of the route corridor around the course line (NM). A precipitation
        /// cell only counts as a conflict (draws a mint line / can raise the advisory)
        /// when it is genuinely **on the flight path** — within this half-width of the
        /// course centerline, or crossed by it. Kept tight so weather merely *near* the
        /// route (off to one side) no longer triggers a deviation; a cell that actually
        /// straddles the course is still caught by the centerline-through-polygon test
        /// regardless of this value.
        var corridorHalfWidthNM: Double = 6
        /// Minutes of travel used for the time-based lookahead fallback.
        var timeLookaheadMinutes: ClosedRange<Double> = 20...45
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
        /// much room from the actual precipitation on both sides. Kept just above the
        /// validated floor (`pathClearanceNM`) so a genuinely flyable gap between two
        /// moderate/heavy cores isn't padded shut — the reroute then threads the gap
        /// with a slight jog rather than looping around the whole line. Red/extreme
        /// cores are unaffected: they use the wider `severeBerthNM` via `berthNM`.
        var lateralBufferNM: Double = 4
        /// Minimum *clear* lateral width (after the buffers) for a gap between two
        /// cells to count as threadable (NM). With the buffers above, a gap needs
        /// `2 * lateralBufferNM + minGapWidthNM` ≈ 11 NM of clear air to be flown —
        /// low enough to use the breaks in a broken line instead of rounding it.
        var minGapWidthNM: Double = 3
        /// How far off the course line a **routine** deviation may steer for a
        /// threadable gap or an around-the-end bypass (NM). The reroute hugs the storm
        /// within this bound; anything wider is a last resort (`maxDetourOffsetNM`),
        /// used only when nothing within this bound can clear the intense cores. Keeps
        /// the mint line tight to the weather instead of swinging far out of the way.
        var searchHalfWidthNM: Double = 60
        /// The absolute maximum lateral offset for a **last-resort** detour (NM), taken
        /// only when no path within `searchHalfWidthNM` can even avoid the heavy/extreme
        /// cores. Bounds how far out of the way the line may ever go, so a broad system
        /// never produces a runaway loop far from the route.
        var maxDetourOffsetNM: Double = 150
        /// Cells whose along-track position is within this margin of the blocking
        /// band are treated as part of the same line for gap analysis (NM).
        var clusterAlongMarginNM: Double = 30
        /// A candidate deviation path must stay at least this far from every
        /// precipitation cell to be accepted, so a reroute never threads a gap in one
        /// storm only to cut through another (NM). This is the base margin for
        /// moderate/heavy returns.
        var pathClearanceNM: Double = 3
        /// Lateral clearance kept from the most intense (red/extreme) cells (NM). A
        /// wide berth around convective cores — used for both path validation and
        /// gap/side-hug spacing — so the reroute rounds them well clear instead of
        /// shaving past or threading a coarse-sampled gap straight through one. Set to
        /// the ~20 NM real-world guidance for avoiding severe/extreme radar echoes
        /// (FAA AC 00-24C); moderate/heavy returns keep the tighter `pathClearanceNM`.
        var severeBerthNM: Double = 20
        /// The most a deviation leg may turn away from the course (degrees). ATC never
        /// turns an aircraft the long way around a storm, so the drawn mint line — and
        /// therefore the assigned vector / rejoin turn derived from it — is bounded to
        /// this off-course angle. Any leg that would point further back is pulled in to
        /// this bound, so the line never reverses the aircraft.
        var maxDeviationTurnDegrees: Double = 100
        /// The initial turn a deviation makes to establish its parallel offset (degrees).
        /// Real weather deviations turn out ~20–30° and then parallel the weather, so the
        /// hug reaches its offset over enough distance to make the first leg this angle
        /// rather than a 90° sideways step. When the weather sits right at the aircraft a
        /// steeper turn is used instead (the gentle start would cut back through the cell).
        /// The same angle shapes the turn-out at the start of the drawn line and the
        /// turn-back at the rejoin, so the mint line makes a nice ~30° dogleg out and back.
        var initialDeviationTurnDegrees: Double = 30
        /// The minimum end-to-end extent of the drawn maneuver (NM). A deviation shorter
        /// than this reads as a twitch on the map, so a compact cell's reroute is stretched
        /// (its rejoin pushed forward, within the cap) to at least this length.
        var minDeviationExtentNM: Double = 15
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
    ///   - rejoinCap: the deepest point the reroute may rejoin the route (never past
    ///     it). Used to keep the mint line from routing past the destination / into
    ///     the approach — the caller passes the first approach fix (else the
    ///     destination). Nil → uncapped.
    func detectConflict(position: CLLocationCoordinate2D,
                        course: Double,
                        groundspeedKnots: Double?,
                        phase: FlightPhase,
                        hazards: [WeatherHazard],
                        waypoints: [Waypoint],
                        routeAhead: [CLLocationCoordinate2D] = [],
                        rejoinCap: CLLocationCoordinate2D? = nil) -> RouteWeatherConflict? {
        guard position.isValid, !hazards.isEmpty else { return nil }
        let lookahead = lookaheadNM(phase: phase, groundspeed: groundspeedKnots)
        let flyCourse = routeAwareCourse(position: position, fallback: course, routeAhead: routeAhead,
                                         hazards: hazards, lookahead: lookahead)
        // Rejoin on the route where it exits the weather — so when the route turns
        // (e.g. south) past the storm, the intercept is measured to that turn and a
        // deviation onto the shorter side wins. Nil when no route is supplied.
        let routeRejoin = routeRejoinCoord(position: position, routeAhead: routeAhead,
                                           hazards: hazards, lookahead: lookahead)
        guard var conflict = detect(position: position, course: flyCourse, groundspeedKnots: groundspeedKnots,
                                    phase: phase, hazards: hazards, waypoints: waypoints,
                                    rejoinOverride: routeRejoin, rejoinCap: rejoinCap) else { return nil }
        // End the drawn line exactly where it first rejoins the filed route. The
        // deviation begins on the route, turns off it, and must come back to intercept it
        // **once** — it can't cross the route and loop back to intercept a second time.
        // So truncate it at the first point (past the departure) where it re-crosses the
        // upcoming route polyline. Truncation only ever shortens the path, so a candidate
        // validated clear stays clear. When it never re-crosses (it ends alongside the
        // route), fall back to snapping the final vertex onto the route so the line still
        // ends exactly on the flight plan.
        let routePoly = ([position] + routeAhead).filter { $0.isValid }
        if routePoly.count >= 2, conflict.deviationPath.count >= 2 {
            if let truncated = truncatedAtFirstRouteIntercept(conflict.deviationPath, route: routePoly),
               truncated.count >= 2 {
                conflict.deviationPath = truncated
            } else if let last = conflict.deviationPath.last,
                      let onRoute = nearestPointOnPolyline(last, routePoly) {
                conflict.deviationPath[conflict.deviationPath.count - 1] = onRoute
            }
        }
        return conflict
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
                        rejoinOverride: CLLocationCoordinate2D? = nil,
                        rejoinCap: CLLocationCoordinate2D? = nil) -> RouteWeatherConflict? {
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

        // On-path weather is detected across the whole lookahead — the conflict is
        // returned regardless of distance — but two range gates govern what the pilot
        // sees. `withinTacticalRange` gates working it *now*: the banner / ATC advisory
        // (via `shouldPrompt`) hold off until the near edge is within the tactical
        // deviation range.
        let withinTacticalRange = bandNear <= config.deviationTriggerNM
        // `withinDrawRange` gates *drawing* the mint line. Far on-path weather is still
        // detected (for the "monitoring" diagnostics) but its line is held until the
        // weather is close enough that a straight tactical deviation is meaningful —
        // otherwise the straight-corridor line, aimed across the route's bends at distant
        // weather, shoots off across the map ("crazy mint line").
        let withinDrawRange = bandNear <= config.mintLineDrawNM
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
        // The deepest along-course distance the reroute may rejoin the route — the
        // cap (first approach fix / destination) projected onto course. The mint line
        // must never route past it, even for weather sitting on the destination.
        let capAlong = rejoinCap.map { project($0, from: position, course: course).along }
            .flatMap { $0 > 0 ? $0 : nil }
        let rawRejoin = rejoinOverride
            ?? Geo.destination(from: position, bearingDegrees: course, distanceNM: lineFar + 20)
        let rejoinCoord = cappedToAlong(rawRejoin, capAlong: capAlong, position: position, course: course)

        // Per-cell required clearance (berth) for every precipitation cell — a wide
        // berth for red/extreme cores, the base margin for lighter returns. Used both
        // to validate a whole candidate path and to search for the tightest clear hug.
        let cellBerths: [(polygon: [CLLocationCoordinate2D], clearance: Double)] = hazards.compactMap {
            guard let poly = $0.geometry.polygonPoints, poly.count >= 3 else { return nil }
            return (poly, berthNM(for: $0.intensity))
        }
        // The intense cores (heavy + extreme — the orange/red returns) that a deviation
        // must *always* clear, each by its berth. Lighter (moderate) precipitation may be
        // skirted to keep the line tight to the storm when clearing everything would force
        // a wide detour; the intense cores never are.
        let intenseBerths: [(polygon: [CLLocationCoordinate2D], clearance: Double)] = hazards.compactMap {
            guard $0.intensity >= .heavy, let poly = $0.geometry.polygonPoints, poly.count >= 3 else { return nil }
            return (poly, berthNM(for: $0.intensity))
        }

        // A single dogleg abeam the middle of the line at a lateral offset.
        func apexPath(for target: Double) -> [CLLocationCoordinate2D] {
            let sideBearing = course + (target >= 0 ? 90 : -90)
            let apex = Geo.destination(from: onCourse, bearingDegrees: sideBearing, distanceNM: abs(target))
            return [position, apex, rejoinCoord]
        }

        // A path that hugs one side of the line: turn out to `offset`, hold it parallel,
        // then close to the rejoin. Two refinements keep it realistic:
        //   • `minLead` is how far along course the turn-out reaches the offset, so the
        //     first leg is a ~30° deviation rather than a 90° sideways step. When the
        //     weather sits right at the aircraft the forward-angled start clips a cell
        //     and validation drops it, leaving the steeper `minLead: 0` variant.
        //   • the far turn-back is capped at the rejoin's along-distance, so the parallel
        //     leg never runs past the intercept and doubles back when off-route cells
        //     sit beyond where the route exits the weather.
        func hugPath(offset: Double, minLead: Double) -> [CLLocationCoordinate2D] {
            let sideBearing = course + (offset >= 0 ? 90 : -90)
            let margin = config.lateralBufferNM
            let rejoinAlong = project(rejoinCoord, from: position, course: course).along
            let nearAlong = max(max(0, lineNear - margin), minLead)
            let farAlong = max(nearAlong, min(lineFar + margin, rejoinAlong))
            let nearOn = Geo.destination(from: position, bearingDegrees: course, distanceNM: nearAlong)
            let farOn = Geo.destination(from: position, bearingDegrees: course, distanceNM: farAlong)
            let pNear = Geo.destination(from: nearOn, bearingDegrees: sideBearing, distanceNM: abs(offset))
            let pFar = Geo.destination(from: farOn, bearingDegrees: sideBearing, distanceNM: abs(offset))
            return [position, pNear, pFar, rejoinCoord]
        }

        // Along-course distance to reach a lateral offset at the target initial-turn
        // angle — the lead that keeps the first leg a realistic deviation, not 90°.
        func gentleLead(_ offset: Double) -> Double {
            abs(offset) / tan(config.initialDeviationTurnDegrees * .pi / 180)
        }

        // The finally-drawn geometry of a candidate: capped to the rejoin limit (never
        // past the destination / approach) and bounded to the max off-course turn (never
        // reversing the aircraft). Validation and selection run on THIS — the line
        // actually drawn — so the clearance guard can't be defeated by a cap or bound
        // that bends an already-validated candidate back into a cell.
        func finalize(_ path: [CLLocationCoordinate2D]) -> [CLLocationCoordinate2D] {
            boundedToCourse(clampPathToAlong(path, capAlong: capAlong, position: position, course: course),
                            course: course)
        }

        // The tightest parallel-offset hug on one side (+1 right / −1 left) that stays
        // clear of `cells`: the smallest offset (out to `maxOffset`) whose whole drawn
        // path clears them. Mirrors the real weather-deviation maneuver — turn out just
        // enough to parallel the weather's edge, hold the offset, then rejoin when clear.
        // Being the *minimum* clearing offset it stays close to the flight plan, so the
        // shortest-path selector picks it early rather than diving wide. `gentle` bounds
        // the initial turn; a steep variant is offered for when the gentle start would
        // clip weather sitting right at the aircraft.
        func tightHug(side: Double, gentle: Bool,
                      cells: [(polygon: [CLLocationCoordinate2D], clearance: Double)],
                      maxOffset: Double) -> (path: [CLLocationCoordinate2D], target: Double)? {
            var offset = config.lateralBufferNM
            while offset <= maxOffset {
                let target = side * offset
                let path = finalize(hugPath(offset: target, minLead: gentle ? gentleLead(target) : 0))
                if pathIsClear(path, cells: cells, origin: position) {
                    return (path, target)
                }
                offset += 2
            }
            return nil
        }

        // Routine-width candidates (offset bounded to `searchHalfWidthNM`): the gap /
        // around-the-end doglegs, the capped side-edge hugs, and the tightest clearing
        // hug on each side. Each is finalized up front so it is validated and ranked as
        // the line actually drawn. Offsets beyond the bound are dropped here, so a broad
        // line can never emit a runaway around-the-end candidate that loops far out.
        var candidates: [(path: [CLLocationCoordinate2D], target: Double)] =
            solution.targets.filter { abs($0) <= config.searchHalfWidthNM }
                .map { (path: finalize(apexPath(for: $0)), target: $0) }
        for edge in [solution.leftEdge, solution.rightEdge] where abs(edge) <= config.searchHalfWidthNM {
            candidates.append((path: finalize(hugPath(offset: edge, minLead: 0)), target: edge))
        }
        for gentle in [true, false] {
            for side in [1.0, -1.0] {
                // The tightest hug that clears EVERY cell (the ideal, fully-clear option).
                if let tight = tightHug(side: side, gentle: gentle, cells: cellBerths,
                                        maxOffset: config.searchHalfWidthNM) {
                    candidates.append(tight)
                }
                // The tightest hug that clears the INTENSE cores but may skirt lighter
                // precip — the close-in option that keeps the line tight to a broad area
                // of moderate returns instead of forcing a wide detour around all of it.
                if let tight = tightHug(side: side, gentle: gentle, cells: intenseBerths,
                                        maxOffset: config.searchHalfWidthNM) {
                    candidates.append(tight)
                }
            }
        }

        // The shortest of a candidate set (by total drawn length), or nil when empty.
        func shortest(_ set: [(path: [CLLocationCoordinate2D], target: Double)])
            -> (path: [CLLocationCoordinate2D], target: Double)? {
            set.min { pathLengthNM($0.path) < pathLengthNM($1.path) }
        }
        // Last resort: the shortest *wide* hug (out to maxDetourOffsetNM) that clears
        // every cell — used only when nothing routine-width can even dodge the intense
        // cores, so a big detour is taken solely when there is no closer clear path.
        func wideDetourClearingAll() -> (path: [CLLocationCoordinate2D], target: Double)? {
            var best: (path: [CLLocationCoordinate2D], target: Double)?
            for gentle in [true, false] {
                for side in [1.0, -1.0] {
                    if let tight = tightHug(side: side, gentle: gentle, cells: cellBerths,
                                            maxOffset: config.maxDetourOffsetNM),
                       best == nil || pathLengthNM(tight.path) < pathLengthNM(best!.path) {
                        best = tight
                    }
                }
            }
            return best
        }

        // Choose the reroute the pilot actually flies, in priority order that keeps it
        // tight to the storm and only ever swings wide as an absolute last resort:
        //   1. the shortest routine-width path clear of EVERY cell;
        //   2. else the shortest routine-width path that clears the intense (heavy /
        //      extreme) cores — skirting lighter precip to stay close, not looping;
        //   3. else, last resort, the shortest *wide* detour that clears every cell —
        //      taken only when nothing tight can dodge the intense cores;
        //   4. else (genuinely boxed in) the routine path that keeps the most room from
        //      the intense cores — never the straight-through least-deviation one.
        let clearAll = candidates.filter { pathIsClear($0.path, cells: cellBerths, origin: position) }
        let clearIntense = candidates.filter { pathIsClear($0.path, cells: intenseBerths, origin: position) }
        let chosen = shortest(clearAll)
            ?? shortest(clearIntense)
            ?? wideDetourClearingAll()
            ?? candidates.max { pathBerthMarginNM($0.path, cells: intenseBerths, origin: position)
                                < pathBerthMarginNM($1.path, cells: intenseBerths, origin: position) }
            ?? (path: finalize(apexPath(for: 0)), target: 0)
        let target = chosen.target
        let direction: DeviationDirection = target >= 0 ? .right : .left
        // The chosen path is already finalized (capped + turn-bounded) and validated for
        // clearance. Draw the maneuver starting at the turn-out point — a ~30° lead
        // before the weather — and rejoining with a ~30° turn-back, rather than a long
        // shallow drift from the aircraft. Both only reshape the lead-in / lead-out on the
        // course line ahead of / behind the (already-clear) offset legs, and both leave a
        // reroute close aboard starting at the aircraft. Then guarantee the whole
        // maneuver spans at least the minimum extent.
        var deviationPath = startAtTurnOut(chosen.path, position: position, course: course)
        deviationPath = endAtTurnBack(deviationPath, position: position, course: course)
        deviationPath = enforceMinExtent(deviationPath, position: position, course: course, capAlong: capAlong)
        // Safety: the reshaped lead-in / lead-out must still clear the intense cores. If
        // the steeper turn-out geometry would clip one (e.g. a tight gap-thread), keep the
        // validated original path instead.
        if !pathIsClear(deviationPath, cells: intenseBerths, origin: position) {
            deviationPath = chosen.path
        }
        let throughPoint = deviationPath.count >= 2 ? deviationPath[1] : chosen.path[1]

        // Speak the deviation the drawn line actually flies: the initial turn from the
        // turn-out point to the through-point, rounded to 5°, with a severity-based floor.
        let degrees = deviationDegrees(from: deviationPath.first ?? position, course: course,
                                       throughPoint: throughPoint, severity: primary.hazard.intensity)

        let segment = originalSegment(waypoints: waypoints, position: position, course: course,
                                      nearAlong: primary.nearAlong, rejoin: rejoin)
        let distance = primary.nearAlong
        let eta = groundspeedKnots.flatMap { $0 > 30 ? distance / $0 * 60 : nil }
        let area = primary.polygon ?? boxAround(primary.center ?? corridorEnd)
        // The banner / advisory only fire for on-path weather that is also close enough
        // to work now; farther out, the mint line is drawn but no prompt is raised.
        let prompt = withinTacticalRange
            && shouldPrompt(severity: primary.hazard.intensity,
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
            withinTacticalRange: withinTacticalRange,
            withinDrawRange: withinDrawRange,
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
            // Pad each cell by the lateral buffer — or the cell's wider berth, for a
            // red/extreme core — so the gaps and side-hug edges keep that much room.
            let buf = max(config.lateralBufferNM, berthNM(for: $0.hazard.intensity)) + $0.radiusBuffer
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

    /// The clearance (NM) a reroute keeps from a cell of the given intensity: a wide
    /// berth for red/extreme cores, the base margin for everything lighter. Applied to
    /// both path validation and the gap/side-hug spacing so the whole reroute — not
    /// just its abeam point — gives convective cores real room.
    private func berthNM(for intensity: WeatherIntensity) -> Double {
        intensity == .extreme ? config.severeBerthNM : config.pathClearanceNM
    }

    /// Whether a deviation path stays clear of every cell along its whole length,
    /// each by that cell's own required clearance — the guard that stops a reroute
    /// from threading one storm and cutting into another, and that keeps a wide berth
    /// from red/extreme cores. Samples each leg and ignores the immediate vicinity of
    /// `origin` (the aircraft may already be in light precipitation; we only care that
    /// the path ahead stays clear).
    private func pathIsClear(_ path: [CLLocationCoordinate2D],
                             cells: [(polygon: [CLLocationCoordinate2D], clearance: Double)],
                             origin: CLLocationCoordinate2D) -> Bool {
        guard path.count >= 2, !cells.isEmpty else { return true }
        let startSkip = 8.0
        for i in 0..<(path.count - 1) {
            let a = path[i], b = path[i + 1]
            let steps = max(1, Int(Geo.distanceNM(from: a, to: b) / 4))
            for s in 0...steps {
                let f = Double(s) / Double(steps)
                let p = interpolate(a, b, f)
                if Geo.distanceNM(from: origin, to: p) < startSkip { continue }
                for cell in cells where distanceToPolygonNM(p, cell.polygon) < cell.clearance { return false }
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

    /// How well a path respects every cell's required berth: the smallest value of
    /// (distance to the cell − that cell's clearance) along the whole path, ignoring
    /// the immediate vicinity of the origin. Positive means it keeps the full berth
    /// everywhere; more negative means it cuts deeper past a berth. Used only as the
    /// boxed-in tie-breaker: with no fully-clear candidate, fly the one that intrudes
    /// least — which keeps the widest room from a red core — rather than the one that
    /// happens to need the smallest turn (which can drive straight through it).
    private func pathBerthMarginNM(_ path: [CLLocationCoordinate2D],
                                   cells: [(polygon: [CLLocationCoordinate2D], clearance: Double)],
                                   origin: CLLocationCoordinate2D) -> Double {
        guard path.count >= 2, !cells.isEmpty else { return .greatestFiniteMagnitude }
        let startSkip = 8.0
        var worst = Double.greatestFiniteMagnitude
        for i in 0..<(path.count - 1) {
            let a = path[i], b = path[i + 1]
            let steps = max(1, Int(Geo.distanceNM(from: a, to: b) / 4))
            for s in 0...steps {
                let f = Double(s) / Double(steps)
                let p = interpolate(a, b, f)
                if Geo.distanceNM(from: origin, to: p) < startSkip { continue }
                for cell in cells { worst = min(worst, distanceToPolygonNM(p, cell.polygon) - cell.clearance) }
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

    /// The point on a polyline nearest to `p` — used to snap the drawn deviation's final
    /// vertex exactly onto the filed route so the mint line always ends on the flight plan.
    private func nearestPointOnPolyline(_ p: CLLocationCoordinate2D,
                                        _ line: [CLLocationCoordinate2D]) -> CLLocationCoordinate2D? {
        guard line.count >= 2 else { return line.first }
        var best: CLLocationCoordinate2D?
        var bestD = Double.greatestFiniteMagnitude
        for i in 0..<(line.count - 1) {
            let c = closestPointOnSegment(p, line[i], line[i + 1])
            let d = Geo.distanceNM(from: p, to: c)
            if d < bestD { bestD = d; best = c }
        }
        return best
    }

    /// Truncate a deviation path so it ends exactly where it first re-intercepts the
    /// filed route — the mint line rejoins the route once and stops, never crossing it
    /// and looping back to intercept a second time. The path starts on the route (at the
    /// aircraft), so crossings within `departureSkipNM` of the start are ignored as the
    /// shared departure; the first crossing beyond that cuts the path, keeping the
    /// vertices up to that leg and ending precisely at the intercept. Returns nil when
    /// the path never re-crosses the route (the caller then snaps the last vertex on).
    private func truncatedAtFirstRouteIntercept(_ path: [CLLocationCoordinate2D],
                                                route: [CLLocationCoordinate2D]) -> [CLLocationCoordinate2D]? {
        guard path.count >= 2, route.count >= 2, let start = path.first else { return nil }
        let departureSkipNM = 3.0
        for i in 0..<(path.count - 1) {
            let a = path[i], b = path[i + 1]
            var bestPoint: CLLocationCoordinate2D?
            var bestAlong = Double.greatestFiniteMagnitude
            for r in 0..<(route.count - 1) {
                guard let x = segmentIntersectionPoint(a, b, route[r], route[r + 1]),
                      Geo.distanceNM(from: start, to: x) > departureSkipNM else { continue }
                // The crossing nearest this leg's start is the earliest along the path.
                let along = Geo.distanceNM(from: a, to: x)
                if along < bestAlong { bestAlong = along; bestPoint = x }
            }
            if let x = bestPoint { return Array(path[0...i]) + [x] }
        }
        return nil
    }

    /// The intersection point of two planar segments a–b and c–d (lat/lon treated as a
    /// flat plane, matching the detector's other planar geometry), or nil when they do
    /// not cross within both segments (including the parallel / collinear case).
    private func segmentIntersectionPoint(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D,
                                          _ c: CLLocationCoordinate2D, _ d: CLLocationCoordinate2D)
        -> CLLocationCoordinate2D? {
        let x1 = a.longitude, y1 = a.latitude
        let x2 = b.longitude, y2 = b.latitude
        let x3 = c.longitude, y3 = c.latitude
        let x4 = d.longitude, y4 = d.latitude
        let denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        guard abs(denom) > 1e-12 else { return nil }
        let t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / denom
        let u = ((x1 - x3) * (y1 - y2) - (y1 - y3) * (x1 - x2)) / denom
        guard t >= 0, t <= 1, u >= 0, u <= 1 else { return nil }
        return CLLocationCoordinate2D(latitude: y1 + t * (y2 - y1), longitude: x1 + t * (x2 - x1))
    }

    /// The closest point on segment a→b to `p`, in the same local NM plane as
    /// `pointToSegmentNM` (which returns only the distance).
    private func closestPointOnSegment(_ p: CLLocationCoordinate2D,
                                       _ a: CLLocationCoordinate2D,
                                       _ b: CLLocationCoordinate2D) -> CLLocationCoordinate2D {
        let latScale = 60.0
        let lonScale = 60.0 * cos(p.latitude * .pi / 180)
        let ax = a.longitude * lonScale, ay = a.latitude * latScale
        let bx = b.longitude * lonScale, by = b.latitude * latScale
        let px = p.longitude * lonScale, py = p.latitude * latScale
        let dx = bx - ax, dy = by - ay
        let lenSq = dx * dx + dy * dy
        let t = lenSq <= 0 ? 0 : max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / lenSq))
        let cx = ax + t * dx, cy = ay + t * dy
        return CLLocationCoordinate2D(latitude: cy / latScale, longitude: cx / lonScale)
    }

    // MARK: - Turn-out / turn-back shaping

    /// The along-course lead for a turn of `initialDeviationTurnDegrees` (capped by the
    /// max off-course bound) to reach a lateral `offset` — how far ahead the turn must
    /// begin so the leg onto the offset is that angle rather than a shallow drift.
    private func turnOutLead(forOffset offset: Double) -> Double {
        let angle = min(config.initialDeviationTurnDegrees, config.maxDeviationTurnDegrees)
        return abs(offset) / tan(max(1, angle) * .pi / 180)
    }

    /// Start the drawn maneuver at its **turn-out point** — a lead-in just before the
    /// weather that makes the first leg a ~`initialDeviationTurnDegrees`° turn onto the
    /// offset — instead of a long shallow drift all the way from the aircraft. For
    /// weather drawn far ahead this moves the mint line's start up to just before the
    /// weather; for weather close aboard the lead collapses and the line still starts at
    /// the aircraft (a necessarily steeper turn). Only reshapes the lead-in on the course
    /// line ahead of the (already-clear) first offset leg.
    private func startAtTurnOut(_ path: [CLLocationCoordinate2D], position: CLLocationCoordinate2D,
                                course: Double) -> [CLLocationCoordinate2D] {
        guard path.count >= 2, let first = path.first else { return path }
        let s1 = project(path[1], from: position, course: course)
        guard abs(s1.cross) > 0.5 else { return path }   // no offset to turn onto
        let startAlong = s1.along - turnOutLead(forOffset: s1.cross)
        let curStart = project(first, from: position, course: course).along
        // Only pull the start forward (ahead of the aircraft); keep it at the aircraft
        // when the weather is close enough that a 30° turn-out would begin behind it.
        guard startAlong > max(2, curStart + 2) else { return path }
        let v0 = Geo.destination(from: position, bearingDegrees: course, distanceNM: startAlong)
        return [v0] + Array(path.dropFirst())
    }

    /// Rejoin with a ~`initialDeviationTurnDegrees`° **turn-back** rather than a long
    /// shallow intercept, by ending the maneuver a matching lead past the last offset
    /// vertex. Applied only when the rejoin sits essentially on the course line (a
    /// straight route); a bent-route rejoin (off course) is left to the route-intercept
    /// truncation. Only ever shortens the maneuver (steepening a too-shallow rejoin).
    private func endAtTurnBack(_ path: [CLLocationCoordinate2D], position: CLLocationCoordinate2D,
                               course: Double) -> [CLLocationCoordinate2D] {
        guard path.count >= 3, let last = path.last else { return path }
        let sLast = project(last, from: position, course: course)
        guard abs(sLast.cross) < 3 else { return path }   // bent-route rejoin — leave to truncation
        let sFar = project(path[path.count - 2], from: position, course: course)
        guard abs(sFar.cross) > 0.5 else { return path }
        let endAlong = sFar.along + turnOutLead(forOffset: sFar.cross)
        guard endAlong < sLast.along - 2 else { return path }   // current rejoin already steep enough
        let v = Geo.destination(from: position, bearingDegrees: course, distanceNM: endAlong)
        return Array(path.dropLast()) + [v]
    }

    /// Guarantee the drawn maneuver spans at least `minDeviationExtentNM` from start to
    /// end. In practice construction already exceeds it (the rejoin sits well past the
    /// turn-out), but a compact cell can fall short — then the rejoin end is pushed
    /// forward along course (within the cap), which only lengthens the final leg.
    private func enforceMinExtent(_ path: [CLLocationCoordinate2D], position: CLLocationCoordinate2D,
                                  course: Double, capAlong: Double?) -> [CLLocationCoordinate2D] {
        guard path.count >= 2, let first = path.first, let last = path.last else { return path }
        let a0 = project(first, from: position, course: course).along
        let sLast = project(last, from: position, course: course)
        guard abs(sLast.cross) < 3, sLast.along - a0 < config.minDeviationExtentNM else { return path }
        var target = a0 + config.minDeviationExtentNM
        if let capAlong { target = min(target, capAlong) }
        guard target > sLast.along else { return path }
        let v = Geo.destination(from: position, bearingDegrees: course, distanceNM: target)
        return Array(path.dropLast()) + [v]
    }

    /// The spoken deviation amount: the initial turn from the turn-out point to the
    /// through-point, rounded to 5° and clamped, with a severity-based floor so heavier
    /// precipitation is never given a token offset.
    private func deviationDegrees(from position: CLLocationCoordinate2D, course: Double,
                                  throughPoint: CLLocationCoordinate2D,
                                  severity: WeatherIntensity) -> Int {
        let turn = abs(normalizedSigned(Geo.bearing(from: position, to: throughPoint) - course))
        var degrees = Int((turn / 5).rounded()) * 5
        // Severity-based floor: a real weather deviation turns out ~20–30° to establish
        // a parallel offset, so moderate-or-heavy precip is never given a token nudge
        // and a convective core gets the larger initial turn.
        switch severity {
        case .extreme: degrees = max(degrees, 30)
        case .heavy, .moderate: degrees = max(degrees, 20)
        case .light, .unknown: break
        }
        return min(45, max(10, degrees))
    }

    /// Pull a rejoin point back to the cap's along-course distance when it lies past
    /// it (keeping any cross-track offset), so the reroute intercepts the route no
    /// deeper than the cap. Uncapped (nil) or not-yet-past → returned unchanged.
    private func cappedToAlong(_ point: CLLocationCoordinate2D, capAlong: Double?,
                               position: CLLocationCoordinate2D, course: Double) -> CLLocationCoordinate2D {
        guard let capAlong else { return point }
        let s = project(point, from: position, course: course)
        guard s.along > capAlong else { return point }
        let onCourse = Geo.destination(from: position, bearingDegrees: course, distanceNM: capAlong)
        guard abs(s.cross) > 0.01 else { return onCourse }
        return Geo.destination(from: onCourse, bearingDegrees: course + (s.cross >= 0 ? 90 : -90),
                               distanceNM: abs(s.cross))
    }

    /// Cap every vertex of a deviation path (past the start) at the rejoin limit, so
    /// no part of the drawn line runs past the destination / into the approach.
    private func clampPathToAlong(_ path: [CLLocationCoordinate2D], capAlong: Double?,
                                  position: CLLocationCoordinate2D, course: Double) -> [CLLocationCoordinate2D] {
        guard capAlong != nil else { return path }
        return path.enumerated().map { idx, pt in
            idx == 0 ? pt : cappedToAlong(pt, capAlong: capAlong, position: position, course: course)
        }
    }

    /// Clamp a deviation path so no leg turns more than `maxDeviationTurnDegrees` off
    /// the course — ATC never turns an aircraft the long way around weather, so the
    /// drawn line (and the vector / rejoin turn derived from it) is prevented from
    /// reversing. Each successive vertex whose leg bearing exceeds the bound is pulled
    /// back onto the bound at the same leg length, keeping the path moving downrange.
    /// The starting position is never moved.
    private func boundedToCourse(_ path: [CLLocationCoordinate2D], course: Double) -> [CLLocationCoordinate2D] {
        guard path.count >= 2 else { return path }
        let maxTurn = config.maxDeviationTurnDegrees
        var result = [path[0]]
        var prev = path[0]
        for i in 1..<path.count {
            let pt = path[i]
            guard pt.isValid else { continue }
            let delta = normalizedSigned(Geo.bearing(from: prev, to: pt) - course)
            if abs(delta) > maxTurn {
                let clamped = course + (delta > 0 ? maxTurn : -maxTurn)
                let dist = Geo.distanceNM(from: prev, to: pt)
                prev = Geo.destination(from: prev, bearingDegrees: clamped, distanceNM: dist)
            } else {
                prev = pt
            }
            result.append(prev)
        }
        return result
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
