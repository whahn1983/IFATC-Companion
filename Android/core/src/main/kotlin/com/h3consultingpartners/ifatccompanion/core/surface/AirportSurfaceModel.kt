package com.h3consultingpartners.ifatccompanion.core.surface

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A serializable latitude/longitude pair. The normalized (and cached) surface model uses
 * this and converts to [Coordinate] at the edges — on iOS for the same reason
 * (`CLLocationCoordinate2D` is not `Codable`), here so `:core`'s plain [Coordinate] stays
 * free of a serialization dependency and the JSON keys stay `latitude`/`longitude`.
 *
 * Ported from `IFATCCompanion/AirportSurface/AirportSurfaceModel.swift`.
 */
@Serializable
data class GeoCoordinate(
    val latitude: Double,
    val longitude: Double,
) {
    constructor(c: Coordinate) : this(c.latitude, c.longitude)

    fun toCoordinate(): Coordinate = Coordinate(latitude, longitude)
}

fun List<GeoCoordinate>.toCoordinates(): List<Coordinate> = map { it.toCoordinate() }

/** Meters in a nautical mile — the surface layer works in meters, [Geo] in NM. */
private const val METERS_PER_NM = 1852.0

/** Great-circle distance in meters, matching `SurfaceGeometry.distanceMeters` on iOS. */
private fun metersBetween(a: GeoCoordinate, b: GeoCoordinate): Double =
    Geo.distanceNM(a.toCoordinate(), b.toCoordinate()) * METERS_PER_NM

/**
 * Confidence assigned to an airport dataset or a calculated route. Ordered so
 * `high > medium > low > unavailable`. Drives how precise the automatic behavior is
 * allowed to be (see the confidence model in the routing docs).
 *
 * PARITY NOTE: Swift makes this `Comparable` on [rank]. Kotlin gives every enum a
 * built-in ordinal ordering, and this enum's declaration order is the *reverse* of
 * `rank`, so `<`/`>` on the enum itself would be backwards. Compare [rank] (or use
 * [atLeast]) — never the enum values directly.
 */
@Serializable
enum class SurfaceConfidence(val rawValue: String) {
    @SerialName("high") HIGH("high"),
    @SerialName("medium") MEDIUM("medium"),
    @SerialName("low") LOW("low"),
    @SerialName("unavailable") UNAVAILABLE("unavailable"),
    ;

    /** Higher rank = more confident. */
    val rank: Int
        get() = when (this) {
            HIGH -> 3
            MEDIUM -> 2
            LOW -> 1
            UNAVAILABLE -> 0
        }

    val title: String
        get() = when (this) {
            HIGH -> "High"
            MEDIUM -> "Medium"
            LOW -> "Low"
            UNAVAILABLE -> "Unavailable"
        }

    /**
     * High/Medium allow the automatic runway-crossing workflow (Medium requires an
     * extra confirmation, handled by the coordinator). Low/Unavailable do not.
     */
    val allowsAutomaticCrossing: Boolean get() = this == HIGH || this == MEDIUM

    /** Whether detailed, turn-by-turn taxi routing should be issued at all. */
    val allowsDetailedRouting: Boolean get() = rank >= LOW.rank && this != UNAVAILABLE

    /** Swift's `>=` on the `Comparable` conformance, spelled so it can't be read backwards. */
    fun atLeast(other: SurfaceConfidence): Boolean = rank >= other.rank
}

/**
 * Provenance + license metadata carried with every normalized airport surface. Kept
 * with the cached data so attribution, license, source endpoint, fetch date, and the
 * original OSM extract size are always available — the app never presents OSM-derived
 * geometry without this.
 */
@Serializable(with = SurfaceProvenanceSerializer::class)
data class SurfaceProvenance(
    val provider: String = OSMSurface.PROVIDER_NAME,
    val license: String = OSMSurface.LICENSE_NAME,
    val attribution: String = OSMSurface.ATTRIBUTION_TEXT,
    val endpoint: String,
    /** Serialized as the ISO-8601 string `fetchDate`, matching iOS's `.iso8601` strategy. */
    val fetchDateMillis: Long,
    val boundingBox: OSMBoundingBox,
    /** Number of raw OSM elements in the source extract (traceability / diagnostics). */
    val rawElementCount: Int,
    /**
     * Schema version of the model that produced this extract. New extracts stamp the
     * current [OSMSurface.SURFACE_SCHEMA_VERSION]; a cache written by an older version
     * decodes to `1` (no key) and is re-fetched on next load. Defaults to the current
     * version so freshly-built provenances are always current.
     */
    val schemaVersion: Int = OSMSurface.SURFACE_SCHEMA_VERSION,
) {
    /** Age of the cached extract at read time, in seconds. */
    fun cacheAgeSeconds(nowMillis: Long = System.currentTimeMillis()): Double =
        (nowMillis - fetchDateMillis) / 1000.0

    /** Whether the extract is older than the configured refresh interval. */
    fun isStale(nowMillis: Long = System.currentTimeMillis()): Boolean =
        cacheAgeSeconds(nowMillis) > OSMSurface.CACHE_REFRESH_INTERVAL_SECONDS

    /**
     * Whether the extract predates the current model schema (e.g. a cache with no
     * building footprints). Such a cache is re-fetched even when not time-stale.
     */
    val isOutdatedSchema: Boolean get() = schemaVersion < OSMSurface.SURFACE_SCHEMA_VERSION

    fun cacheAgeDays(nowMillis: Long = System.currentTimeMillis()): Int =
        (cacheAgeSeconds(nowMillis) / 86_400).toInt()
}

/**
 * The wire shape of [SurfaceProvenance]. Its only reason to exist is the schema
 * version's *two* defaults: a provenance built in code defaults to the current schema
 * version, while a cache file that has no `schemaVersion` key predates the field and
 * must decode as legacy version `1` so the provider re-fetches it. Swift expresses that
 * with a custom `init(from:)` alongside the synthesized memberwise init; kotlinx allows
 * one default per property, so the decode-side default lives here.
 */
@Serializable
private class SurfaceProvenanceSurrogate(
    val provider: String = OSMSurface.PROVIDER_NAME,
    val license: String = OSMSurface.LICENSE_NAME,
    val attribution: String = OSMSurface.ATTRIBUTION_TEXT,
    val endpoint: String,
    @Serializable(with = Iso8601MillisSerializer::class)
    val fetchDate: Long,
    val boundingBox: OSMBoundingBox,
    val rawElementCount: Int,
    val schemaVersion: Int = 1,
)

object SurfaceProvenanceSerializer : KSerializer<SurfaceProvenance> {
    override val descriptor: SerialDescriptor = SurfaceProvenanceSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: SurfaceProvenance) {
        encoder.encodeSerializableValue(
            SurfaceProvenanceSurrogate.serializer(),
            SurfaceProvenanceSurrogate(
                provider = value.provider,
                license = value.license,
                attribution = value.attribution,
                endpoint = value.endpoint,
                fetchDate = value.fetchDateMillis,
                boundingBox = value.boundingBox,
                rawElementCount = value.rawElementCount,
                schemaVersion = value.schemaVersion,
            ),
        )
    }

    override fun deserialize(decoder: Decoder): SurfaceProvenance {
        val s = decoder.decodeSerializableValue(SurfaceProvenanceSurrogate.serializer())
        return SurfaceProvenance(
            provider = s.provider,
            license = s.license,
            attribution = s.attribution,
            endpoint = s.endpoint,
            fetchDateMillis = s.fetchDate,
            boundingBox = s.boundingBox,
            rawElementCount = s.rawElementCount,
            schemaVersion = s.schemaVersion,
        )
    }
}

/**
 * Epoch millis on the Kotlin side, an ISO-8601 instant string in the file — the format
 * iOS's `JSONEncoder.dateEncodingStrategy = .iso8601` writes, so a cache file means the
 * same thing on both platforms.
 */
object Iso8601MillisSerializer : KSerializer<Long> {
    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.h3consultingpartners.ifatccompanion.core.surface.Iso8601Date", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeString(formatter.format(Instant.ofEpochMilli(value)))
    }

    override fun deserialize(decoder: Decoder): Long {
        val raw = decoder.decodeString()
        return runCatching { Instant.parse(raw).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
            .getOrElse { Instant.from(formatter.parse(raw)).toEpochMilli() }
    }
}

// MARK: - Feature types

/**
 * A runway: its `ref` idents, centerline geometry, and width. Original OSM id + tags
 * are preserved.
 */
@Serializable
data class SurfaceRunway(
    val osmID: String,
    val tags: Map<String, String>,
    /** Runway-end designators parsed from `ref` (e.g. "16L/34R" → ["16L", "34R"]). */
    val idents: List<String>,
    val centerline: List<GeoCoordinate>,
    val widthMeters: Double,
    val widthInferred: Boolean,
) {
    val id: String get() = osmID
    val displayName: String get() = if (idents.isEmpty()) "Runway" else idents.joinToString("/")
}

/**
 * A single directional runway end (threshold + heading), derived from a runway's
 * centerline and one of its idents.
 */
@Serializable
data class SurfaceRunwayEnd(
    val ident: String,                    // "16L"
    val threshold: GeoCoordinate,         // where this end's numbers are painted
    val oppositeThreshold: GeoCoordinate,
    val headingDegrees: Double,           // 0–360, from ident×10 (fallback: geometry)
    val runwayOSMID: String,
    val widthMeters: Double,
) {
    val id: String get() = "$runwayOSMID:$ident"
}

/**
 * A taxiway or taxilane centerline. [isTaxilane] distinguishes `aeroway=taxilane`
 * (apron/stand lead-in lanes) from `aeroway=taxiway`.
 */
@Serializable
data class SurfaceTaxiway(
    val osmID: String,
    val tags: Map<String, String>,
    val isTaxilane: Boolean,
    /** `ref` (the letter/number controllers use) preferred, else `name`, else "". */
    val name: String,
    val geometry: List<GeoCoordinate>,
    /** `oneway=yes` (directional restriction). */
    val oneway: Boolean,
    /**
     * Truthy `access` values indicating a closed / non-operational segment
     * ("no", "private"). Null when unrestricted.
     */
    val access: String? = null,
    val widthMeters: Double? = null,
) {
    val id: String get() = osmID
    val hasName: Boolean get() = name.isNotEmpty()

    /** Whether the taxiway is closed / non-operational per its access tag. */
    val isClosed: Boolean
        get() {
            val a = access?.lowercase() ?: return false
            return a == "no" || a == "private"
        }
}

/**
 * A runway holding position (hold-short point). [inferred] marks a hold synthesized
 * for simulation where OSM had none mapped (always lower confidence).
 */
@Serializable
data class SurfaceHoldingPosition(
    val osmID: String,
    val tags: Map<String, String>,
    val coordinate: GeoCoordinate,
    /** The runway this hold protects, from `ref` (e.g. "16L"). May be empty. */
    val runwayRef: String,
    val inferred: Boolean,
) {
    val id: String get() = osmID
}

/** A gate or parking position (aircraft stand). */
@Serializable
data class SurfaceParking(
    val osmID: String,
    val tags: Map<String, String>,
    val kind: Kind,
    /**
     * The identifier a controller says, from `ref` (else `name`) — e.g. "B44". When the
     * tag carries several identifiers for the one stand ("A1;A2"), this is the first of
     * them and the rest are in [aliases] (see [StandIdentifier]).
     */
    val name: String,
    val coordinate: GeoCoordinate,
    /**
     * The other identifiers this same stand answers to, including the raw multi-value tag
     * as written. Empty for the ordinary single-`ref` stand. Used for *matching* only — a
     * stand is always displayed and spoken as its [name].
     *
     * A cache written before this field existed simply has no `aliases` key and decodes to
     * empty. Such a cache also predates the schema bump that introduced split identifiers,
     * so the provider re-fetches it anyway.
     */
    val aliases: List<String> = emptyList(),
) {
    @Serializable
    enum class Kind(val rawValue: String) {
        @SerialName("gate") GATE("gate"),
        @SerialName("parkingPosition") PARKING_POSITION("parkingPosition"),
    }

    val id: String get() = osmID

    /** Whether [candidate] is this stand's own name, case- and whitespace-insensitively. */
    fun isNamed(candidate: String): Boolean = key(candidate) == key(name)

    /** Whether this stand answers to [candidate] at all — its name or any of its aliases. */
    fun matches(candidate: String): Boolean {
        val k = key(candidate)
        if (k.isEmpty()) return false
        if (k == key(name)) return true
        return aliases.any { key(it) == k }
    }

    companion object {
        internal fun key(s: String): String = s.trim { it == ' ' || it == '\t' }.uppercase()
    }
}

/** An apron area polygon. */
@Serializable
data class SurfaceApron(
    val osmID: String,
    val tags: Map<String, String>,
    val polygon: List<GeoCoordinate>,
) {
    val id: String get() = osmID
}

/**
 * A building / terminal / concourse footprint polygon. Not a movement surface and never
 * routable — it is used only to stop synthesized gate lead-ins (and other inferred
 * connectors) from being drawn straight through a concourse to a stand on the far side.
 * Sourced from OSM `building=*` ways and `aeroway=terminal`.
 */
@Serializable
data class SurfaceBuilding(
    val osmID: String,
    val tags: Map<String, String>,
    val polygon: List<GeoCoordinate>,
) {
    val id: String get() = osmID
}

// MARK: - The normalized airport surface

/**
 * The normalized internal airport-surface model, built from an OSM extract. Retains
 * every original OSM feature identifier and its tags, plus provenance / attribution /
 * license metadata and a dataset confidence.
 *
 * This model — and the connected surface graph derived from it — may constitute an
 * OSM-derived database under the ODbL; the transformation is documented conservatively
 * (see `docs/OpenStreetMapLicensing.md`) and reproduction information is made available.
 */
@Serializable
data class AirportSurfaceModel(
    val icao: String,
    val reference: GeoCoordinate,
    val runways: List<SurfaceRunway>,
    val runwayEnds: List<SurfaceRunwayEnd>,
    /** Taxiways and taxilanes together; use [taxiwaysOnly] / [taxilanes] to separate. */
    val taxiways: List<SurfaceTaxiway>,
    val holdingPositions: List<SurfaceHoldingPosition>,
    val parkingPositions: List<SurfaceParking>,
    val aprons: List<SurfaceApron>,
    /**
     * Building / terminal footprints (added in schema v2). Defaulted so pre-v2 callers
     * and cache files that omit the key still construct/decode; a decoded model with an
     * empty `buildings` from an old cache is re-fetched via `source.isOutdatedSchema`.
     */
    val buildings: List<SurfaceBuilding> = emptyList(),
    val source: SurfaceProvenance,
    val confidence: SurfaceConfidence,
) {

    // MARK: Derived accessors

    val taxiwaysOnly: List<SurfaceTaxiway> get() = taxiways.filter { !it.isTaxilane }
    val taxilanes: List<SurfaceTaxiway> get() = taxiways.filter { it.isTaxilane }
    val gates: List<SurfaceParking> get() = parkingPositions.filter { it.kind == SurfaceParking.Kind.GATE }
    val standCount: Int get() = parkingPositions.size

    /**
     * One entry per physical stand: every `parking_position`, plus the `gate` nodes that
     * aren't already covered by one.
     *
     * A field may map a stand twice — the boarding gate as a node on the concourse and the
     * aircraft stand as a `parking_position` out on the apron, both carrying the one
     * identifier. KIAD does exactly that: `gate` C24 is a *vertex of the Concourse C/D
     * outline*, with `parking_position` C24 the stand 75 m south of it. With nothing to
     * choose between them, the taxi target became whichever the extract happened to list
     * first, and picking the gate ends the route inside the terminal — from a point *on* the
     * footprint no lead-in can avoid a building either, so the concourse-crossing penalty
     * applies to every candidate equally and the stand attaches to whatever is nearest,
     * which at a 33 m-wide concourse is as often the far side as its own.
     *
     * Both features stay in [parkingPositions] with their tags intact — this only decides
     * which one a route, a map marker, or a gate assignment should use.
     */
    val routableStands: List<SurfaceParking>
        get() {
            val stands = parkingPositions.filter { it.kind == SurfaceParking.Kind.PARKING_POSITION }
            if (stands.isEmpty()) return parkingPositions
            val byName = stands.groupBy { standKey(it.name) }
            return parkingPositions.filter { candidate ->
                if (candidate.kind != SurfaceParking.Kind.GATE) return@filter true
                val key = standKey(candidate.name)
                if (key.isEmpty()) return@filter true
                val sameName = byName[key] ?: return@filter true
                !sameName.any {
                    metersBetween(candidate.coordinate, it.coordinate) <= STAND_SUPERSEDE_METERS
                }
            }
        }

    /** Whether there is enough geometry to attempt any routing at all. */
    val hasUsableGeometry: Boolean get() = runways.isNotEmpty() && taxiways.isNotEmpty()

    /** All runway-end idents present at the field (e.g. ["16L","34R","09","27"]). */
    val allRunwayIdents: List<String> get() = runwayEnds.map { it.ident }

    /** The runway end matching an ident ("16L"), case-insensitively. */
    fun runwayEnd(ident: String): SurfaceRunwayEnd? {
        val key = ident.uppercase().trim { it == ' ' || it == '\t' }
        return runwayEnds.firstOrNull { it.ident.uppercase() == key }
    }

    /**
     * Locate the parking position (gate/stand) answering to a name, case-insensitively.
     * A stand's own name wins over another stand's alias, so a field mapping both `C16`
     * and `C16/C16A` resolves "C16" to the stand actually called that.
     */
    fun parking(named: String): SurfaceParking? {
        val key = named.trim { it == ' ' || it == '\t' }
        if (key.isEmpty()) return null
        preferredStand(parkingPositions.filter { it.isNamed(key) })?.let { return it }
        return preferredStand(parkingPositions.filter { it.matches(key) })
    }

    /**
     * The stand to use when one identifier matches more than one mapped feature. A field
     * that maps both the boarding gate and the aircraft stand under a single identifier —
     * KIAD tags `gate` C24 on the Concourse C/D outline and `parking_position` C24 on the
     * apron 75 m away — would otherwise resolve to whichever the extract happened to list
     * first. The aircraft parks on the `parking_position`, so that is the one every caller
     * means. Falls back to the first match, leaving a field that maps only gates unchanged.
     */
    private fun preferredStand(matches: List<SurfaceParking>): SurfaceParking? =
        matches.firstOrNull { it.kind == SurfaceParking.Kind.PARKING_POSITION } ?: matches.firstOrNull()

    companion object {
        /**
         * How close a `parking_position` must be to a same-named `gate` for the two to be one
         * physical stand mapped twice — comfortably more than a jet bridge, far less than the
         * spacing between stands that merely share a number in different parts of a field.
         */
        const val STAND_SUPERSEDE_METERS = 250.0

        private fun standKey(s: String): String = s.trim { it == ' ' || it == '\t' }.uppercase()

        /** Decode a cached model. Throws when the payload is not a surface model. */
        fun decode(text: String): AirportSurfaceModel =
            SurfaceJson.decodeFromString(serializer(), text)
    }
}
