import Foundation
import CoreLocation

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
    /// Whether Ground has already handed the departing aircraft to Tower to *monitor*
    /// (the "monitor Tower on …" hand-off short of the runway), so a reconnect mid-taxi
    /// doesn't re-issue it. Optional so snapshots written before this field decode cleanly
    /// (missing key → nil → treated as false).
    var monitoringTower: Bool? = nil
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
    /// can be recognized — `AppModel.endpointMismatch(with:)` warns on them before
    /// loading a saved flight onto a different live flight.
    var departure: String
    var destination: String
    /// Whether the snapshot was taken in mock mode (never restored into live mode).
    var mockMode: Bool
    /// When the snapshot was last written. Used to discard sessions too old to be a
    /// reconnect of the same flight.
    var savedAt: Date

    // MARK: Whole-session fields
    //
    // Everything below exists for *saved flights*, which restore the entire app rather
    // than just the conversational cursor a reconnect needs. All of it is optional so
    // snapshots written by earlier versions still decode (missing key → nil), and the
    // auto-resume path gets the richer restore for free.

    /// The flight plan as it stood, so a saved flight loads with its route, runways and
    /// procedures already in place instead of blank until Infinite Flight re-publishes
    /// them. The next live tick still merges the sim's plan over it, so a route edited
    /// in the sim after saving wins.
    var flightPlan: FlightPlan? = nil
    /// The pilot's manually-entered flight fields (callsign, endpoints, gates, …). They
    /// live in `AppSettings` because the Flight tab edits them there, but they describe
    /// the flight rather than the app, so they travel with a saved flight — while
    /// genuine preferences (voices, volumes, radar, host/port) deliberately do not.
    var overrides: FlightOverrides? = nil
    /// The frequency the pilot is actually tuned to, and any facility they have tuned
    /// to but not yet checked in with.
    var tunedFacility: ATCFacility? = nil
    var pendingCheckInFacility: ATCFacility? = nil
    /// The read-back gate: whether a controller is waiting on the pilot, which call it
    /// is waiting on, and how many times it has already re-prompted.
    var awaitingReadback: Bool? = nil
    var pendingReadbackTx: ATCTransmission? = nil
    var readbackPrompts: Int? = nil
    /// A go-around in progress holds the automatic flow until the pilot re-establishes
    /// with Approach, so it must survive being put away mid-pattern.
    var goAroundInProgress: Bool? = nil
    /// Arrival-to-gate staging: whether "monitor ramp to the gate" has already been
    /// issued, and where the gate is, so a flight saved on the taxi-in still blocks in
    /// at the right place rather than completing on the first full stop.
    var gateMonitored: Bool? = nil
    var arrivalGateLatitude: Double? = nil
    var arrivalGateLongitude: Double? = nil
    /// Ground references captured before departure that later altitude decisions are
    /// measured against (initial-climb altitudes, the 2,000 ft AGL Departure hand-off).
    var departureFieldElevationMSL: Double? = nil
    var liftoffAltitudeMSL: Double? = nil
    /// Whether the pilot has worked ATC at all, which gates the ambient chatter. Falls
    /// back to being derived from the transcript when absent.
    var atcCommunicationStarted: Bool? = nil
    /// ATIS reports already fetched, so the ATIS card is populated on load rather than
    /// blank until the next refresh cycle.
    var departureATIS: AirportATIS? = nil
    var arrivalATIS: AirportATIS? = nil
    var lastArrivalATISAttempt: Date? = nil
    /// Weather-interaction bookkeeping that a fresh radar sample cannot re-derive:
    /// whether the pilot has already dealt with the active conflict. The observations
    /// themselves — radar cells, METARs, TAF, PIREPs, SIGMETs — are deliberately *not*
    /// saved. They are re-fetched on load, and restoring hours-old cells would draw a
    /// deviation around weather that has since moved.
    var weatherHandled: Bool? = nil
    var mockWeatherAdvisoryIssued: Bool? = nil
    /// The Diagnostics tab's log, so a saved flight's history is inspectable after it
    /// is loaded back.
    var diagnostics: DiagnosticsSnapshot? = nil

    /// Whether this snapshot represents a flight already finished at the gate — there
    /// is nothing to resume, so it should not be restored onto a fresh launch. (Saved
    /// flights are exempt: the pilot picked that flight explicitly, and a completed one
    /// is still worth reopening to read its transcript.)
    var isCompleted: Bool { atcState == .parked && arrivalAnnounced }

    /// The gate coordinate the arrival blocks in at, when one was captured.
    var arrivalGateCoordinate: CLLocationCoordinate2D? {
        guard let lat = arrivalGateLatitude, let lon = arrivalGateLongitude else { return nil }
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    /// A route label for the saved-flight list, e.g. "KIAH-KORD". Falls back to whichever
    /// endpoint is known, and to a neutral name when the plan names neither.
    var routeName: String {
        SessionSnapshot.routeLabel(departure: departure, destination: destination)
    }

    /// The same label built from a pair of endpoints, so a live flight plan can be
    /// described the same way a saved one is (used to compare the two before loading).
    static func routeLabel(departure: String, destination: String) -> String {
        let dep = departure.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let dest = destination.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        switch (dep.isEmpty, dest.isEmpty) {
        case (false, false): return "\(dep)-\(dest)"
        case (false, true):  return dep
        case (true, false):  return dest
        case (true, true):   return "Flight"
        }
    }
}

/// The pilot's manually-entered flight fields, captured with a saved flight.
///
/// These are stored in `AppSettings` (the Flight tab edits them there) but they are
/// flight data, not preferences: reloading a saved flight must bring back the callsign
/// you were flying under and the gates you entered, without disturbing the device-level
/// setup — voices, volumes, chatter, radar and the Infinite Flight host/port stay as
/// they are.
struct FlightOverrides: Codable, Equatable {
    var callsign = ""
    var airline = ""
    var flightNumber = ""
    var departure = ""
    var destination = ""
    var alternate = ""
    var cruiseAltitude = 0
    var runway = ""
    var sid = ""
    var star = ""
    var approach = ""
    var departureGate = ""
    var arrivalGate = ""
}

extension FlightOverrides {
    /// Capture the flight fields currently entered in Settings / the Flight tab.
    init(settings: AppSettings) {
        self.init()
        callsign = settings.callsign
        airline = settings.airline
        flightNumber = settings.flightNumber
        departure = settings.departure
        destination = settings.destination
        alternate = settings.alternate
        cruiseAltitude = settings.cruiseAltitude
        runway = settings.runway
        sid = settings.sid
        star = settings.star
        approach = settings.approach
        departureGate = settings.departureGate
        arrivalGate = settings.arrivalGate
    }

    /// Write the saved flight's fields back into Settings. Each assignment persists
    /// through `AppSettings`' own `didSet`, so the Flight tab shows them immediately.
    func apply(to settings: AppSettings) {
        settings.callsign = callsign
        settings.airline = airline
        settings.flightNumber = flightNumber
        settings.departure = departure
        settings.destination = destination
        settings.alternate = alternate
        settings.cruiseAltitude = cruiseAltitude
        settings.runway = runway
        settings.sid = sid
        settings.star = star
        settings.approach = approach
        settings.departureGate = departureGate
        settings.arrivalGate = arrivalGate
    }
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
