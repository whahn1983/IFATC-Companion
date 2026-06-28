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
    var runway: String = ""
    var sid: String = ""
    var star: String = ""
    var approach: String = ""
    var waypoints: [Waypoint] = []

    /// Source of truth flag — when true, fields were entered manually and should
    /// not be overwritten by Connect parsing.
    var manualOverride: Bool = false

    static let empty = FlightPlan()

    var departureName: String { departure.isEmpty ? "departure" : departure }
    var destinationName: String { destination.isEmpty ? "destination" : destination }

    /// The next un-passed waypoint relative to a position, or destination.
    func nextWaypoint(from coordinate: CLLocationCoordinate2D?) -> Waypoint? {
        let located = waypoints.filter { $0.coordinate != nil }
        guard let coordinate, !located.isEmpty else { return waypoints.first }
        return located.min(by: {
            Geo.distanceNM(from: coordinate, to: $0.coordinate!) <
            Geo.distanceNM(from: coordinate, to: $1.coordinate!)
        })
    }
}
