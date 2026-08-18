import Foundation
import CoreLocation

/// Watches the aircraft's position against the enroute sector map and decides when it
/// has genuinely crossed into the next Center's airspace.
///
/// Pure and deterministic — no timers, no I/O — so the hand-off logic can be tested by
/// feeding it positions. The caller decides whether a crossing is *spoken*: the tracker
/// follows the aircraft for the whole flight (so the sector name is right the moment
/// Departure hands over), while only the enroute leg puts a call on the radio.
struct CenterSectorTracker {

    /// A confirmed crossing from one sector into the next. Entering the first sector of
    /// a flight is not a crossing — the tracker adopts it silently, since Departure's
    /// hand-off is what puts the pilot on that Center to begin with.
    struct Crossing: Equatable {
        let from: CenterSector
        let to: CenterSector
    }

    /// How far inside the next sector the aircraft must be before the crossing counts.
    /// Boundaries are frequently flown *along* rather than across — an airway that
    /// parallels one, a vector that skims a corner — and without a buffer the radio
    /// would ping-pong between two controllers. Four miles is roughly 30 seconds at jet
    /// cruise: long enough to be a real crossing, short enough that the new controller
    /// still has the aircraft for essentially the whole sector.
    static let boundaryBufferNM: Double = 4

    /// Minimum spacing between two sector hand-offs. Clipping the corner where three
    /// sectors meet would otherwise produce two calls back to back.
    static let minimumSecondsBetweenHandoffs: TimeInterval = 90

    /// Distance between consecutive fixes beyond which the aircraft did not *fly* the
    /// crossing: the app was backgrounded, the link dropped and resynced, or the sim was
    /// repositioned. Live telemetry moves a fraction of a mile between fixes, so 40 NM is
    /// far outside normal flight and squarely inside "this is a jump". A jump adopts the
    /// new sector silently — the pilot is already deep inside it, and announcing a
    /// hand-off for a boundary crossed while the app was asleep would be nonsense.
    static let maximumFixSpacingNM: Double = 40

    /// The sector currently working the aircraft, as far as the tracker is concerned.
    private(set) var current: CenterSector?

    private var lastCoordinate: CLLocationCoordinate2D?
    private var lastHandoffAt: Date?

    /// Forget everything — a new flight, or a session reset.
    mutating func reset() {
        current = nil
        lastCoordinate = nil
        lastHandoffAt = nil
    }

    /// Adopt a sector without producing a crossing. Used when restoring a saved session,
    /// so a reconnect mid-cruise doesn't re-announce the sector the pilot is already
    /// talking to.
    mutating func adopt(_ sector: CenterSector?) {
        current = sector
    }

    /// Feed a position fix. Returns a crossing only when the aircraft is confirmed to
    /// have flown into a different sector.
    ///
    /// Several cases produce no crossing and leave the tracker's sector where it is: the
    /// data isn't loaded yet, or the fix falls in a gap between boundaries (the source
    /// data has a few, mostly over open ocean), or the aircraft is still within the
    /// hysteresis buffer of the boundary, or another hand-off was issued moments ago.
    /// Leaving `current` untouched in those cases is what makes the decision retry on the
    /// next fix instead of silently swallowing the hand-off.
    mutating func update(coordinate: CLLocationCoordinate2D,
                         at now: Date,
                         database: CenterSectorDatabase) -> Crossing? {
        guard coordinate.isValid, let found = database.sector(at: coordinate) else { return nil }
        let previousCoordinate = lastCoordinate
        lastCoordinate = coordinate
        guard let working = current else {
            // First fix with data in hand: adopt the sector we're already in. There is
            // nothing to hand off from.
            current = found
            return nil
        }
        guard found.id != working.id else { return nil }
        // A discontinuity in the track, not a flown crossing.
        let jumped = previousCoordinate.map {
            Geo.distanceNM(from: $0, to: coordinate) > Self.maximumFixSpacingNM
        } ?? true
        guard !jumped else {
            current = found
            return nil
        }
        guard found.distanceToBoundaryNM(from: coordinate) >= Self.boundaryBufferNM else { return nil }
        if let last = lastHandoffAt,
           now.timeIntervalSince(last) < Self.minimumSecondsBetweenHandoffs { return nil }
        current = found
        lastHandoffAt = now
        return Crossing(from: working, to: found)
    }
}
