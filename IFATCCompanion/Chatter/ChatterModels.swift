import Foundation

/// A single simulated background-radio transmission (audio only — never shown in the
/// transcript). `isPilot` lets the audio layer colour the voice: controller-side vs a
/// read-back from the aircraft, so an exchange sounds like two stations, not one.
struct ChatterLine: Equatable {
    var spokenText: String
    var isPilot: Bool
}

/// The runways the background chatter should reference for the airport currently in play,
/// resolved by `AppModel` from the ATIS in use and the loaded OSM surface. `departures` and
/// `arrivals` are the ATIS-active runways (so a takeoff clearance names a departure runway and
/// a landing clearance an arrival runway); `all` is what to use when the operation doesn't
/// matter, and the fallback when a side is unknown (no ATIS) — the field's full runway set.
/// Every field empty (no surface loaded, no flight plan) leaves the generator on random runways.
struct ChatterRunwayContext: Equatable {
    var all: [String] = []
    var departures: [String] = []
    var arrivals: [String] = []
}
