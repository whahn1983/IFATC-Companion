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
    var heading: Double?            // degrees magnetic (what the pilot flies / ATC uses)
    /// True (geographic) heading in degrees, when Infinite Flight exposes it. Used to
    /// rotate the aircraft symbol on the true-north map so it points where the aircraft
    /// is actually pointing — `heading` (magnetic) would be off by the local magnetic
    /// declination, which is small near the US/UK but ~20°+ in parts of the southern
    /// hemisphere. ATC phraseology still uses the magnetic `heading`.
    var trueHeading: Double?        // degrees true
    var track: Double?              // degrees
    var verticalSpeed: Double?      // feet per minute
    var onGround: Bool?
    /// Autopilot approach mode (APPR) armed/engaged, read from Infinite Flight when
    /// exposed. Used to detect the aircraft is established on the approach so the
    /// "cleared … approach" call can be issued before the Tower hand-off.
    var approachModeEngaged: Bool?
    /// Parking brake state, read from Infinite Flight when exposed. Used to confirm
    /// the aircraft is actually parked at the gate (brake set) before the arrival is
    /// announced complete. `nil` when the sim/version doesn't expose it.
    var parkingBrakeSet: Bool?
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

    /// Whether this snapshot carries any usable telemetry at all. The Connect link
    /// returns an all-nil snapshot during the reconnect handshake (every field read
    /// fails); feeding that to phase detection makes it assume the aircraft is
    /// airborne (a nil "on ground" reads as false) and default to a climb, which
    /// would jump a parked aircraft to cruise on reconnect.
    var hasUsableTelemetry: Bool {
        onGround != nil || altitudeMSL != nil || coordinate != nil
    }

    var isClimbing: Bool { (verticalSpeed ?? 0) > 300 }
    var isDescending: Bool { (verticalSpeed ?? 0) < -300 }
}
