import Foundation
import CoreLocation

/// A single flight-plan fix / waypoint.
struct Waypoint: Identifiable, Equatable, Codable {
    var id = UUID()
    var name: String
    var latitude: Double?
    var longitude: Double?
    var altitude: Double?   // feet, if specified

    var coordinate: CLLocationCoordinate2D? {
        guard let lat = latitude, let lon = longitude else { return nil }
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }
}

/// The active flight plan. Fields come from Connect when available, otherwise
/// from manual overrides entered by the pilot in the Flight tab.
struct FlightPlan: Equatable, Codable {
    var callsign: String = ""
    var airline: String = ""
    var flightNumber: String = ""
    var departure: String = ""      // ICAO
    var destination: String = ""    // ICAO
    var alternate: String = ""      // ICAO
    var cruiseAltitude: Int = 0     // feet
    /// Manual/override runway (applies to both ends when set in the Flight tab).
    var runway: String = ""
    /// Departure runway recovered from the flight plan (e.g. "22R" from a `DPT RW22R`
    /// token). Empty when the plan does not name one.
    var departureRunway: String = ""
    /// Arrival runway recovered from the flight plan (a runway token near the end of
    /// the route). The parsed approach's runway takes precedence over this on arrival.
    var arrivalRunway: String = ""
    var sid: String = ""
    var star: String = ""
    var approach: String = ""
    /// Departure gate / stand identifier (e.g. "C12"). Manual-override only —
    /// Infinite Flight does not expose it. Used by the pushback request at the gate.
    var departureGate: String = ""
    /// Arrival gate / stand identifier (e.g. "B44"). Manual-override only — Infinite
    /// Flight does not expose it. Used by the arrival Ramp taxi-to-gate instruction.
    var arrivalGate: String = ""
    /// Intercept/initial altitude (ft MSL) for the approach — the first altitude in
    /// the approach section of the flight plan when known, else 0 (callers default).
    var approachInterceptAltitude: Int = 0
    /// Name of the first fix of the approach procedure (the initial approach fix),
    /// when the plan carries a parsed approach. This is the deepest a weather
    /// deviation may rejoin the route — the mint line never routes past it toward the
    /// destination. Empty when no approach is known.
    var approachStartFixName: String = ""
    /// Ordered fix names of the filed departure procedure (SID), recovered from the
    /// SID group in the flight plan — Infinite Flight nests the SID's own fixes under
    /// the procedure. Empty when no SID is filed (or its fixes aren't known). The
    /// initial departure heading targets the first of these that is a located
    /// waypoint, so an intermediate "buffer" fix a pilot files between the runway and
    /// the SID (to keep the autopilot from turning at rotation) never displaces the
    /// SID's true first fix.
    var sidFixNames: [String] = []
    var waypoints: [Waypoint] = []

    /// Coordinate Infinite Flight reports for the departure field, captured from the
    /// flight plan itself. The built-in `AirportDatabase` only covers a handful of US
    /// hubs, so this is how the departure marker lands on the real field for airports
    /// outside that list (the whole world). Nil when the plan carries no located
    /// departure endpoint.
    var departureLatitude: Double?
    var departureLongitude: Double?
    /// Coordinate Infinite Flight reports for the destination field (see above).
    var destinationLatitude: Double?
    var destinationLongitude: Double?

    /// Source of truth flag — when true, fields were entered manually and should
    /// not be overwritten by Connect parsing.
    var manualOverride: Bool = false

    static let empty = FlightPlan()

    var departureName: String { departure.isEmpty ? "departure" : departure }
    var destinationName: String { destination.isEmpty ? "destination" : destination }

    /// The departure field's coordinate as reported by Infinite Flight, when the plan
    /// carries one. Preferred over the first-waypoint fallback so the departure marker
    /// sits on the actual field rather than the first enroute fix.
    var departureCoordinate: CLLocationCoordinate2D? {
        guard let lat = departureLatitude, let lon = departureLongitude else { return nil }
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    /// The destination field's coordinate as reported by Infinite Flight, when the
    /// plan carries one. Preferred over the last-waypoint fallback so the destination
    /// marker sits on the actual field rather than the last enroute fix.
    var destinationCoordinate: CLLocationCoordinate2D? {
        guard let lat = destinationLatitude, let lon = destinationLongitude else { return nil }
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    /// Coordinate of the first located enroute fix, used as a route-start fallback
    /// when the departure airport isn't in the built-in coordinate database.
    var firstWaypointCoordinate: CLLocationCoordinate2D? {
        waypoints.first { $0.coordinate != nil }?.coordinate
    }

    /// Coordinate of the last located enroute fix, used as a route-end fallback
    /// when the destination airport isn't in the built-in coordinate database.
    var lastWaypointCoordinate: CLLocationCoordinate2D? {
        waypoints.last { $0.coordinate != nil }?.coordinate
    }

    /// Coordinate of the first approach fix, when the plan names one and it carries a
    /// coordinate. The deepest point a weather deviation may rejoin the route.
    var approachStartCoordinate: CLLocationCoordinate2D? {
        guard !approachStartFixName.isEmpty else { return nil }
        return waypoints.first { $0.name == approachStartFixName }?.coordinate
    }

    /// The next un-passed waypoint relative to a position, or destination.
    func nextWaypoint(from coordinate: CLLocationCoordinate2D?) -> Waypoint? {
        let located = waypoints.filter { $0.coordinate != nil }
        guard let coordinate, !located.isEmpty else { return waypoints.first }
        return located.min(by: {
            Geo.distanceNM(from: coordinate, to: $0.coordinate!) <
            Geo.distanceNM(from: coordinate, to: $1.coordinate!)
        })
    }

    /// The next waypoint *ahead* of the aircraft along the filed route — the fix the
    /// pilot has not yet passed — used for the "resume own navigation, direct …"
    /// clearance so the companion never clears the pilot direct to a fix already
    /// behind them (e.g. the runway-end fix). When the route origin is known, a fix
    /// is "ahead" if it lies farther down-route than the aircraft's current progress;
    /// otherwise it falls back to the nearest located fix, then the first waypoint.
    func nextUnpassedWaypoint(from coordinate: CLLocationCoordinate2D?,
                              origin: CLLocationCoordinate2D?) -> Waypoint? {
        let located = waypoints.filter { $0.coordinate != nil }
        guard let coordinate, !located.isEmpty else { return waypoints.first }
        if let origin {
            let progress = Geo.distanceNM(from: origin, to: coordinate)
            if let ahead = located.first(where: {
                Geo.distanceNM(from: origin, to: $0.coordinate!) > progress + 1
            }) {
                return ahead
            }
        }
        return located.min(by: {
            Geo.distanceNM(from: coordinate, to: $0.coordinate!) <
            Geo.distanceNM(from: coordinate, to: $1.coordinate!)
        }) ?? waypoints.first
    }

    /// The fix the initial departure heading should intercept off the runway — the
    /// bearing to it (from the aircraft's position on the runway) is the heading the
    /// takeoff clearance issues. This is airport-agnostic: it never depends on the
    /// field being in a built-in table.
    ///
    ///   1. When a SID is filed, the SID's first published fix that is present as a
    ///      located flight-plan waypoint. The SID's own fix list is taken from the
    ///      filed procedure structure (`sidFixNames`, recovered from the SID group in
    ///      the plan) first, then from any caller-supplied list (`sidFixes` — the
    ///      built-in library for the demo airports). The first name that matches a
    ///      located filed waypoint wins. Because the match is by name — not by route
    ///      position — an intermediate "buffer" fix filed between the runway and the
    ///      SID never displaces the SID's true first fix.
    ///   2. Only when no SID structure is known: the next filed fix after the runway —
    ///      the first located fix clear of the field (≥ 1 NM from `origin`), so a fix
    ///      sitting on the field is never chosen. Falls back to the first located fix,
    ///      then the first filed fix (which may be unlocated).
    ///
    /// Returns nil only when the plan carries no fixes at all. When the chosen fix has
    /// no coordinate the caller cannot form a bearing and should issue "runway
    /// heading" — it must never fall back to a bearing toward the destination, which
    /// for a northern departure to a southern destination points ~180° the wrong way.
    func initialDepartureFix(sidFixes: [String], origin: CLLocationCoordinate2D?) -> Waypoint? {
        // The SID's own first published fix, matched by name to a located waypoint. The
        // filed SID structure (`sidFixNames`) is authoritative; `sidFixes` covers the
        // demo airports whose fixes come from the built-in library.
        for name in sidFixNames + sidFixes {
            if let sidFix = waypoints.first(where: {
                $0.coordinate != nil && $0.name.caseInsensitiveCompare(name) == .orderedSame
            }) {
                return sidFix
            }
        }
        // No SID structure: the next filed fix after the runway.
        let located = waypoints.filter { $0.coordinate != nil }
        if let origin,
           let ahead = located.first(where: { Geo.distanceNM(from: origin, to: $0.coordinate!) >= 1 }) {
            return ahead
        }
        return located.first ?? waypoints.first
    }
}
