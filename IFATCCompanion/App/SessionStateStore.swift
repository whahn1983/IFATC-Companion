import Foundation

/// A persisted snapshot of the in-progress ATC session. When the Infinite Flight
/// link drops (the pilot switched apps, the device slept, Wi-Fi blipped) and the
/// app reconnects, the conversation should resume exactly where it left off —
/// parked at the gate, climbing, at cruise, on approach — rather than being
/// re-derived from raw telemetry, which can jump the flight straight to cruise.
struct SessionSnapshot: Codable {
    /// Conversational/procedural position (what the UI shows and the flow drives off).
    var atcState: ATCState
    /// The state machine's internal current state (the gate-to-gate cursor).
    var stateMachineCurrent: ATCState
    var currentFacility: ATCFacility
    var phase: FlightPhase
    var assignedAltitude: Int
    var hasDeparted: Bool
    var arrivalAnnounced: Bool
    var awaitingGateArrival: Bool
    var manualTuning: Bool
    /// The in-progress weather-deviation interaction, so a reconnect mid-diversion
    /// restores the deviation card (and its "clear of weather" button) rather than
    /// dropping it. Optional so snapshots written before this field decode cleanly.
    var weatherDeviation: WeatherDeviationContext? = nil
    /// The ATIS information code letter the pilot has received (by tuning ATIS) for the
    /// departure / arrival, so a reconnect keeps appending "information X" to the taxi
    /// request / approach check-in. Optional so older snapshots decode cleanly.
    var reportedDepartureInfo: String? = nil
    var reportedArrivalInfo: String? = nil
    /// Whether the information code has already been reported to ATC for each phase, so
    /// a reconnect doesn't repeat it on the next taxi request / Approach check-in.
    /// Optional so older snapshots decode cleanly (missing key → nil → treated as false).
    var departureInfoAppended: Bool? = nil
    var arrivalInfoAppended: Bool? = nil
    /// Whether the pilot has tuned away from the ATIS frequency for each phase, so a
    /// reconnect keeps the ATIS tune button hidden instead of resurfacing it after the
    /// pilot already copied the broadcast. Optional so older snapshots decode cleanly.
    var departureATISDismissed: Bool? = nil
    var arrivalATISDismissed: Bool? = nil
    var transcript: [ATCTransmission]
    /// Flight-plan endpoints, recorded so a stale snapshot from a different flight
    /// can be recognized if needed.
    var departure: String
    var destination: String
    /// Whether the snapshot was taken in mock mode (never restored into live mode).
    var mockMode: Bool
    /// When the snapshot was last written. Used to discard sessions too old to be a
    /// reconnect of the same flight.
    var savedAt: Date

    /// Whether this snapshot represents a flight already finished at the gate — there
    /// is nothing to resume, so it should not be restored onto a fresh launch.
    var isCompleted: Bool { atcState == .parked && arrivalAnnounced }
}

/// Persists the latest `SessionSnapshot` so a disconnect/reconnect (or an app
/// relaunch) resumes the flight in progress. Backed by `UserDefaults` — small,
/// local, and survives the app being suspended or killed.
@MainActor
final class SessionStateStore {
    private let key = "session.snapshot.v1"
    private let defaults: UserDefaults

    /// Snapshots older than this are treated as a previous flight, not a reconnect,
    /// and are not restored. The active session re-stamps `savedAt` periodically
    /// while connected, so this only fires when the app was genuinely away a long
    /// time (e.g. reopened the next day).
    var maxAge: TimeInterval = 6 * 3600

    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    func save(_ snapshot: SessionSnapshot) {
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        defaults.set(data, forKey: key)
    }

    func load() -> SessionSnapshot? {
        guard let data = defaults.data(forKey: key),
              let snapshot = try? JSONDecoder().decode(SessionSnapshot.self, from: data) else { return nil }
        return snapshot
    }

    /// Load a snapshot only if it is recent enough and represents an in-progress
    /// flight worth resuming (not a completed gate-to-gate flight).
    func loadResumable(now: Date = Date()) -> SessionSnapshot? {
        guard let snapshot = load(),
              !snapshot.isCompleted,
              now.timeIntervalSince(snapshot.savedAt) <= maxAge else { return nil }
        return snapshot
    }

    func clear() { defaults.removeObject(forKey: key) }
}
