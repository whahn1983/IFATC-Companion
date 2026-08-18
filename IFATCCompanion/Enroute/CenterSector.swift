import Foundation
import CoreLocation

/// One enroute air-traffic-control sector: an ARTCC in the United States
/// ("Houston Center"), an FIR/UIR elsewhere ("London Control", "Gander Oceanic").
///
/// Geometry and radio names come from the bundled `CenterSectors.json` dataset —
/// see `CenterSectorData` for provenance/attribution and `docs/CenterSectors.md`
/// for how the file is built. **Simulation only**: the boundaries are
/// community-sourced and most frequencies are synthesized, so nothing here is
/// usable for real-world navigation or radio work.
struct CenterSector: Decodable, Identifiable {

    /// Boundary identifier from the source data ("KZHU", "EGTT", "CZQX").
    let id: String

    /// Short display name — what the sector is called without the radio suffix
    /// ("Houston", "London").
    let name: String

    /// What the controller is called on the radio ("Houston Center", "London
    /// Control"). Pre-composed in the dataset because the suffix is regional: the
    /// Americas and Australia say "Center"/"Centre", most of the rest of the world
    /// says "Control", and a few positions carry their own word ("Gander Oceanic").
    let radioName: String

    /// Whether this is an oceanic (procedural) sector rather than a radar one.
    let isOceanic: Bool

    /// The sector's real working frequency, for the regions whose source data
    /// publishes one (Australia names each enroute sector by its frequency). Nil for
    /// most sectors, which fall back to `Self.simulatedFrequency(for:)`.
    let publishedFrequency: Double?

    let minLat: Double
    let maxLat: Double
    let minLon: Double
    let maxLon: Double

    /// The polygons making up the sector. Each polygon is an outer ring followed by
    /// any holes; each ring is a flat `[lon, lat, lon, lat, …]` list with no repeated
    /// closing vertex (the containment test closes the ring itself). Flat arrays of
    /// doubles keep the bundled file roughly half the size of coordinate pairs and
    /// decode measurably faster.
    let polygons: [[[Double]]]

    enum CodingKeys: String, CodingKey {
        case id, name, polygons, minLat, maxLat, minLon, maxLon
        case radioName = "radio"
        case isOceanic = "oceanic"
        case publishedFrequency = "frequency"
    }

    /// Frequency handed to this sector at load time, once neighbouring sectors have been
    /// kept off each other's slots (see `CenterSectorDatabase`). Not part of the file
    /// format — it defaults to nil and is filled in after decoding, by
    /// `CenterSectorDatabase.deconflictFrequencies`, which is the only thing that sets it.
    var assignedFrequency: Double? = nil

    /// The frequency the companion works this sector on: the de-conflicted assignment
    /// where one has been made, otherwise the real frequency where the source data
    /// publishes one, otherwise a stable simulated slot.
    var frequency: Double { assignedFrequency ?? publishedFrequency ?? Self.simulatedFrequency(for: id) }

    /// Whether the two sectors are close enough to be worked back to back — bounding
    /// boxes overlapping, with half a degree of slack so sectors that merely share an
    /// edge still count. Used to keep neighbours off the same synthesized frequency.
    func isNeighbour(of other: CenterSector, marginDegrees: Double = 0.5) -> Bool {
        minLat - marginDegrees <= other.maxLat && maxLat + marginDegrees >= other.minLat
            && minLon - marginDegrees <= other.maxLon && maxLon + marginDegrees >= other.minLon
    }

    // MARK: - Geometry

    /// Whether the coordinate lies inside the sector. Bounding box first (which
    /// rejects all but a handful of the ~450 sectors), then an even-odd ray cast
    /// against each polygon, honoring holes.
    func contains(_ coordinate: CLLocationCoordinate2D) -> Bool {
        guard coordinate.latitude >= minLat, coordinate.latitude <= maxLat,
              coordinate.longitude >= minLon, coordinate.longitude <= maxLon else { return false }
        for polygon in polygons {
            guard let outer = polygon.first, Self.ring(outer, contains: coordinate) else { continue }
            let inHole = polygon.dropFirst().contains { Self.ring($0, contains: coordinate) }
            if !inHole { return true }
        }
        return false
    }

    /// Distance (NM) from the coordinate to the nearest sector boundary, regardless of
    /// which side of it the coordinate is on. Used as the hand-off hysteresis: the
    /// aircraft must be a few miles *inside* the next sector before the crossing counts,
    /// so a track that skims a shared boundary can't bounce the radio back and forth.
    ///
    /// Planar approximation — longitude scaled by the cosine of the query latitude, one
    /// degree of latitude taken as 60 NM. Accurate to well under a mile at the scale that
    /// matters here (a few miles from the boundary), which is all the hysteresis needs.
    func distanceToBoundaryNM(from coordinate: CLLocationCoordinate2D) -> Double {
        let lonScale = cos(coordinate.latitude * .pi / 180)
        var best = Double.greatestFiniteMagnitude
        for polygon in polygons {
            for ring in polygon {
                let count = ring.count / 2
                guard count >= 2 else { continue }
                var previous = count - 1
                for index in 0..<count {
                    best = min(best, Self.distanceToSegmentNM(
                        from: coordinate,
                        ax: ring[2 * previous], ay: ring[2 * previous + 1],
                        bx: ring[2 * index], by: ring[2 * index + 1],
                        lonScale: lonScale))
                    previous = index
                }
            }
        }
        return best == .greatestFiniteMagnitude ? 0 : best
    }

    /// Even-odd ray cast against one flat ring. The ring is treated as closed, so the
    /// dataset does not repeat the first vertex at the end.
    private static func ring(_ ring: [Double], contains coordinate: CLLocationCoordinate2D) -> Bool {
        let count = ring.count / 2
        guard count >= 3 else { return false }
        let x = coordinate.longitude
        let y = coordinate.latitude
        var inside = false
        var previous = count - 1
        for index in 0..<count {
            let xi = ring[2 * index], yi = ring[2 * index + 1]
            let xj = ring[2 * previous], yj = ring[2 * previous + 1]
            // The straddle test guarantees yj != yi, so the division is always safe.
            if (yi > y) != (yj > y), x < (xj - xi) * (y - yi) / (yj - yi) + xi {
                inside.toggle()
            }
            previous = index
        }
        return inside
    }

    /// Distance (NM) from a coordinate to the segment a–b, in the local flat frame.
    private static func distanceToSegmentNM(from coordinate: CLLocationCoordinate2D,
                                            ax: Double, ay: Double,
                                            bx: Double, by: Double,
                                            lonScale: Double) -> Double {
        let nmPerDegree = 60.0
        let px = coordinate.longitude * lonScale * nmPerDegree
        let py = coordinate.latitude * nmPerDegree
        let x1 = ax * lonScale * nmPerDegree, y1 = ay * nmPerDegree
        let x2 = bx * lonScale * nmPerDegree, y2 = by * nmPerDegree
        let dx = x2 - x1, dy = y2 - y1
        let lengthSquared = dx * dx + dy * dy
        guard lengthSquared > 0 else { return hypot(px - x1, py - y1) }
        let t = min(1, max(0, ((px - x1) * dx + (py - y1) * dy) / lengthSquared))
        return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
    }

    // MARK: - Frequency

    /// The simulated enroute band and its 25 kHz channel spacing.
    static let lowestFrequency = 132.0
    static let highestFrequency = 135.975
    static let frequencyStep = 0.025

    /// The next channel up the simulated band, wrapping at the top. Used to move a
    /// sector off a frequency a neighbour already holds.
    static func nextFrequency(after frequency: Double) -> Double {
        let stepped = frequency + frequencyStep
        let wrapped = stepped > highestFrequency + 0.0005 ? lowestFrequency : stepped
        return (wrapped * 1000).rounded() / 1000
    }

    /// A stable, plausible enroute frequency for a sector whose real one is unknown.
    ///
    /// Sector-by-sector ARTCC/FIR frequencies are not published as an openly licensed
    /// global dataset, and Infinite Flight exposes none, so the companion synthesizes
    /// one: a channel in the enroute band picked by an FNV-1a hash of the sector id.
    /// FNV rather than Swift's `Hasher` because `Hasher` is seeded per process — the
    /// same sector would otherwise get a different frequency on every launch, and a
    /// hand-off read back before a relaunch would no longer match.
    static func simulatedFrequency(for id: String) -> Double {
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in id.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x0000_0100_0000_01b3
        }
        // 132.000 … 135.975 MHz at 25 kHz spacing.
        let slots = UInt64(((highestFrequency - lowestFrequency) / frequencyStep).rounded()) + 1
        let megahertz = lowestFrequency + Double(hash % slots) * frequencyStep
        return (megahertz * 1000).rounded() / 1000
    }
}

extension CenterSector: Equatable {
    /// Identity comparison. Two decodes of the same sector are the same sector, and
    /// comparing thousands of boundary coordinates to establish that would be wasteful
    /// — `@Published` republishes and SwiftUI diffing both lean on this.
    static func == (lhs: CenterSector, rhs: CenterSector) -> Bool { lhs.id == rhs.id }
}
