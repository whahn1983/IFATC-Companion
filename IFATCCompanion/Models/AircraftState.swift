import Foundation
import CoreLocation

/// Snapshot of live aircraft state, read from Infinite Flight Connect or the mock feed.
/// All values are optional because Connect API coverage varies by aircraft/version.
struct AircraftState: Equatable {
    var latitude: Double?
    var longitude: Double?
    var altitudeMSL: Double?        // feet
    var altitudeAGL: Double?        // feet
    var groundSpeed: Double?        // knots
    var indicatedAirspeed: Double?  // knots
    var trueAirspeed: Double?       // knots
    var heading: Double?            // degrees true/magnetic
    var track: Double?              // degrees
    var verticalSpeed: Double?      // feet per minute
    var onGround: Bool?
    /// Autopilot approach mode (APPR) armed/engaged, read from Infinite Flight when
    /// exposed. Used to detect the aircraft is established on the approach so the
    /// "cleared … approach" call can be issued before the Tower hand-off.
    var approachModeEngaged: Bool?
    var gForce: Double?
    var bankAngle: Double?
    var pitch: Double?
    var nearestAirport: String?     // ICAO if known
    var nearestAirportDistanceNM: Double?
    var aircraftName: String?
    var liveryName: String?
    var lastUpdate: Date?

    static let empty = AircraftState()

    var coordinate: CLLocationCoordinate2D? {
        guard let lat = latitude, let lon = longitude else { return nil }
        let c = CLLocationCoordinate2D(latitude: lat, longitude: lon)
        return c.isValid ? c : nil
    }

    /// True when we have enough position/altitude to drive phase detection.
    var hasUsablePosition: Bool {
        coordinate != nil && altitudeMSL != nil
    }

    var isClimbing: Bool { (verticalSpeed ?? 0) > 300 }
    var isDescending: Bool { (verticalSpeed ?? 0) < -300 }
}
