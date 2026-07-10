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
    var waypoints: [Waypoint] = []

    /// Source of truth flag — when true, fields were entered manually and should
    /// not be overwritten by Connect parsing.
    var manualOverride: Bool = false

    static let empty = FlightPlan()

    var departureName: String { departure.isEmpty ? "departure" : departure }
    var destinationName: String { destination.isEmpty ? "destination" : destination }

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
}
