package com.h3consultingpartners.ifatccompanion.core.enroute

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * One enroute air-traffic-control sector: an ARTCC in the United States
 * ("Houston Center"), an FIR/UIR elsewhere ("London Control", "Gander Oceanic").
 *
 * Geometry and radio names come from the bundled `CenterSectors.json` dataset —
 * see [CenterSectorData] for provenance/attribution and `docs/CenterSectors.md`
 * for how the file is built. **Simulation only**: the boundaries are
 * community-sourced and most frequencies are synthesized, so nothing here is
 * usable for real-world navigation or radio work.
 *
 * Ported from `IFATCCompanion/Enroute/CenterSector.swift`.
 */
@Serializable
data class CenterSector(

    /** Boundary identifier from the source data ("KZHU", "EGTT", "CZQX"). */
    val id: String,

    /**
     * Short display name — what the sector is called without the radio suffix
     * ("Houston", "London").
     */
    val name: String,

    /**
     * What the controller is called on the radio ("Houston Center", "London
     * Control"). Pre-composed in the dataset because the suffix is regional: the
     * Americas and Australia say "Center"/"Centre", most of the rest of the world
     * says "Control", and a few positions carry their own word ("Gander Oceanic").
     */
    @SerialName("radio") val radioName: String,

    /** Whether this is an oceanic (procedural) sector rather than a radar one. */
    @SerialName("oceanic") val isOceanic: Boolean,

    /**
     * The sector's real working frequency, for the regions whose source data
     * publishes one (Australia names each enroute sector by its frequency). Null for
     * most sectors, which fall back to [simulatedFrequency].
     */
    @SerialName("frequency") val publishedFrequency: Double? = null,

    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,

    /**
     * The polygons making up the sector. Each polygon is an outer ring followed by
     * any holes; each ring is a flat `[lon, lat, lon, lat, …]` list with no repeated
     * closing vertex (the containment test closes the ring itself). Flat arrays of
     * doubles keep the bundled file roughly half the size of coordinate pairs and
     * decode measurably faster.
     */
    val polygons: List<List<List<Double>>>,

    /**
     * Frequency handed to this sector at load time, once neighbouring sectors have been
     * kept off each other's slots (see [CenterSectorDatabase]). Not part of the file
     * format — it defaults to null and is filled in after decoding, by
     * [CenterSectorDatabase.deconflictFrequencies], which is the only thing that sets it.
     */
    @Transient val assignedFrequency: Double? = null,
) {

    /**
     * The frequency the companion works this sector on: the de-conflicted assignment
     * where one has been made, otherwise the real frequency where the source data
     * publishes one, otherwise a stable simulated slot.
     */
    val frequency: Double
        get() = assignedFrequency ?: publishedFrequency ?: simulatedFrequency(id)

    /**
     * Whether the two sectors are close enough to be worked back to back — bounding
     * boxes overlapping, with half a degree of slack so sectors that merely share an
     * edge still count. Used to keep neighbours off the same synthesized frequency.
     */
    fun isNeighbour(other: CenterSector, marginDegrees: Double = 0.5): Boolean =
        minLat - marginDegrees <= other.maxLat && maxLat + marginDegrees >= other.minLat &&
            minLon - marginDegrees <= other.maxLon && maxLon + marginDegrees >= other.minLon

    // MARK: - Geometry

    /**
     * Whether the coordinate lies inside the sector. Bounding box first (which
     * rejects all but a handful of the ~450 sectors), then an even-odd ray cast
     * against each polygon, honoring holes.
     */
    fun contains(coordinate: Coordinate): Boolean {
        if (coordinate.latitude < minLat || coordinate.latitude > maxLat ||
            coordinate.longitude < minLon || coordinate.longitude > maxLon
        ) {
            return false
        }
        for (polygon in polygons) {
            val outer = polygon.firstOrNull() ?: continue
            if (!ringContains(outer, coordinate)) continue
            val inHole = polygon.drop(1).any { ringContains(it, coordinate) }
            if (!inHole) return true
        }
        return false
    }

    /**
     * Distance (NM) from the coordinate to the nearest sector boundary, regardless of
     * which side of it the coordinate is on. Used as the hand-off hysteresis: the
     * aircraft must be a few miles *inside* the next sector before the crossing counts,
     * so a track that skims a shared boundary can't bounce the radio back and forth.
     *
     * Planar approximation — longitude scaled by the cosine of the query latitude, one
     * degree of latitude taken as 60 NM. Accurate to well under a mile at the scale that
     * matters here (a few miles from the boundary), which is all the hysteresis needs.
     */
    fun distanceToBoundaryNM(from: Coordinate): Double {
        val lonScale = cos(from.latitude * PI / 180)
        var best = Double.MAX_VALUE
        for (polygon in polygons) {
            for (ring in polygon) {
                val count = ring.size / 2
                if (count < 2) continue
                var previous = count - 1
                for (index in 0 until count) {
                    best = min(
                        best,
                        distanceToSegmentNM(
                            from = from,
                            ax = ring[2 * previous], ay = ring[2 * previous + 1],
                            bx = ring[2 * index], by = ring[2 * index + 1],
                            lonScale = lonScale,
                        ),
                    )
                    previous = index
                }
            }
        }
        return if (best == Double.MAX_VALUE) 0.0 else best
    }

    /**
     * Identity comparison. Two decodes of the same sector are the same sector, and
     * comparing thousands of boundary coordinates to establish that would be wasteful —
     * the tracker's "did the sector change?" check and every list diff lean on this.
     * Overriding here suppresses the `data class`'s generated all-properties equality,
     * which is the point: it matches the Swift's `Equatable` conformance exactly.
     */
    override fun equals(other: Any?): Boolean = this === other ||
        (other is CenterSector && other.id == id)

    override fun hashCode(): Int = id.hashCode()

    companion object {

        /** The simulated enroute band and its 25 kHz channel spacing. */
        const val LOWEST_FREQUENCY = 132.0
        const val HIGHEST_FREQUENCY = 135.975
        const val FREQUENCY_STEP = 0.025

        /** Nautical miles per degree of latitude, in the planar boundary-distance frame. */
        private const val NM_PER_DEGREE = 60.0

        /**
         * The next channel up the simulated band, wrapping at the top. Used to move a
         * sector off a frequency a neighbour already holds.
         */
        fun nextFrequency(after: Double): Double {
            val stepped = after + FREQUENCY_STEP
            val wrapped = if (stepped > HIGHEST_FREQUENCY + 0.0005) LOWEST_FREQUENCY else stepped
            return roundHalfAwayFromZero(wrapped * 1000) / 1000
        }

        /**
         * A stable, plausible enroute frequency for a sector whose real one is unknown.
         *
         * Sector-by-sector ARTCC/FIR frequencies are not published as an openly licensed
         * global dataset, and Infinite Flight exposes none, so the companion synthesizes
         * one: a channel in the enroute band picked by an FNV-1a hash of the sector id.
         * FNV rather than the platform's own hash because that is seeded per process on
         * both platforms — the same sector would otherwise get a different frequency on
         * every launch, and a hand-off read back before a relaunch would no longer match.
         */
        fun simulatedFrequency(id: String): Double {
            var hash = 0xcbf29ce484222325UL
            for (byte in id.encodeToByteArray()) {
                hash = hash xor byte.toUByte().toULong()
                hash *= 0x00000100000001b3UL
            }
            // 132.000 … 135.975 MHz at 25 kHz spacing.
            val slots =
                roundHalfAwayFromZero((HIGHEST_FREQUENCY - LOWEST_FREQUENCY) / FREQUENCY_STEP)
                    .toULong() + 1UL
            val megahertz = LOWEST_FREQUENCY + (hash % slots).toDouble() * FREQUENCY_STEP
            return roundHalfAwayFromZero(megahertz * 1000) / 1000
        }

        /**
         * Swift's `Double.rounded()` rounds halves *away from zero*; Kotlin's
         * `kotlin.math.round` is `Math.rint`, which rounds halves to even. The
         * frequencies would agree either way at the values involved, but the band
         * arithmetic is asserted to the kilohertz, so the Swift's rule is reproduced
         * rather than assumed harmless.
         */
        private fun roundHalfAwayFromZero(value: Double): Double =
            if (value < 0) -floor(-value + 0.5) else floor(value + 0.5)

        /**
         * Even-odd ray cast against one flat ring. The ring is treated as closed, so the
         * dataset does not repeat the first vertex at the end.
         */
        private fun ringContains(ring: List<Double>, coordinate: Coordinate): Boolean {
            val count = ring.size / 2
            if (count < 3) return false
            val x = coordinate.longitude
            val y = coordinate.latitude
            var inside = false
            var previous = count - 1
            for (index in 0 until count) {
                val xi = ring[2 * index]
                val yi = ring[2 * index + 1]
                val xj = ring[2 * previous]
                val yj = ring[2 * previous + 1]
                // The straddle test guarantees yj != yi, so the division is always safe.
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                    inside = !inside
                }
                previous = index
            }
            return inside
        }

        /** Distance (NM) from a coordinate to the segment a–b, in the local flat frame. */
        private fun distanceToSegmentNM(
            from: Coordinate,
            ax: Double,
            ay: Double,
            bx: Double,
            by: Double,
            lonScale: Double,
        ): Double {
            val px = from.longitude * lonScale * NM_PER_DEGREE
            val py = from.latitude * NM_PER_DEGREE
            val x1 = ax * lonScale * NM_PER_DEGREE
            val y1 = ay * NM_PER_DEGREE
            val x2 = bx * lonScale * NM_PER_DEGREE
            val y2 = by * NM_PER_DEGREE
            val dx = x2 - x1
            val dy = y2 - y1
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared <= 0) return hypot(px - x1, py - y1)
            val t = min(1.0, max(0.0, ((px - x1) * dx + (py - y1) * dy) / lengthSquared))
            return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
        }
    }
}
