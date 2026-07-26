import Foundation

/// A single simulated background-radio transmission (audio only — never shown in the
/// transcript). `isPilot` lets the audio layer colour the voice: controller-side vs a
/// read-back from the aircraft, so an exchange sounds like two stations, not one.
struct ChatterLine: Equatable {
    var spokenText: String
    var isPilot: Bool
}
