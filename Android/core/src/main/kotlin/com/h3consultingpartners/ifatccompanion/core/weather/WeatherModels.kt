package com.h3consultingpartners.ifatccompanion.core.weather

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import java.util.UUID

/**
 * Turbulence severity scale used by ride reports.
 *
 * Ported from `IFATCCompanion/Weather/WeatherModels.swift`. The Swift enum is
 * `Int`-raw-valued and `Comparable` on that raw value; a Kotlin `enum class`
 * compares on declaration order, which is the same ordering, and [rawValue] is
 * carried across so persisted/serialized values mean the same thing on both
 * platforms.
 */
enum class TurbulenceSeverity(val rawValue: Int) {
    SMOOTH(0),
    LIGHT_CHOP(1),
    LIGHT(2),
    MODERATE(3),
    SEVERE(4),
    ;

    val spoken: String
        get() = when (this) {
            SMOOTH -> "smooth"
            LIGHT_CHOP -> "light chop"
            LIGHT -> "light turbulence"
            MODERATE -> "moderate turbulence"
            SEVERE -> "severe turbulence"
        }

    /** Swift's `spoken.capitalized`, which title-cases *every* word ("Light Chop"). */
    val title: String
        get() = spoken.split(" ").joinToString(" ") { word ->
            if (word.isEmpty()) word else word[0].uppercaseChar() + word.substring(1).lowercase()
        }

    companion object {
        /**
         * Parse from a PIREP turbulence code or free text.
         *
         * The clause order is load bearing and ported verbatim: "LGT CHOP" contains
         * "LGT" and so is *light*, not light chop. Note the `LGT-MOD` clause is
         * unreachable on iOS too — anything containing "LGT-MOD" already contains
         * "MOD" and returned [MODERATE] one line above. It is kept so the two files
         * read the same; both platforms produce [MODERATE] for it either way.
         */
        fun parse(text: String): TurbulenceSeverity? {
            val t = text.uppercase()
            if (t.contains("SEV") || t.contains("EXTRM") || t.contains("EXTREME")) return SEVERE
            if (t.contains("MOD")) return MODERATE
            if (t.contains("LGT-MOD") || t.contains("LIGHT-MODERATE")) return MODERATE
            if (t.contains("LGT") || t.contains("LIGHT")) return LIGHT
            if (t.contains("CHOP") || t.contains("CAT")) return LIGHT_CHOP
            if (t.contains("SMOOTH") || t.contains("NEG") || t.contains("SKC")) return SMOOTH
            return null
        }
    }
}

/** One reported cloud layer. [cover] is FEW, SCT, BKN or OVC. */
data class CloudLayer(
    val cover: String,
    val baseFt: Int?,
)

/**
 * A decoded METAR.
 *
 * Times are epoch milliseconds rather than a `Date`, matching the rest of :core
 * (which has no Foundation and keeps every timestamp as `Long` millis).
 */
data class METAR(
    var icao: String,
    var raw: String,
    var observationTimeMillis: Long? = null,
    var windDirection: Int? = null,
    var windSpeed: Int? = null,
    var windGust: Int? = null,
    var visibilitySM: Double? = null,
    var altimeterInHg: Double? = null,
    var temperatureC: Double? = null,
    var dewpointC: Double? = null,
    var clouds: MutableList<CloudLayer> = mutableListOf(),
    /** VFR / MVFR / IFR / LIFR. */
    var flightCategory: String? = null,
) {
    /** Lowest broken/overcast ceiling in feet, if any. */
    val ceilingFt: Int?
        get() = clouds
            .filter { it.cover == "BKN" || it.cover == "OVC" }
            .mapNotNull { it.baseFt }
            .minOrNull()
}

data class TAFForecastPeriod(
    var raw: String,
    var windDirection: Int? = null,
    var windSpeed: Int? = null,
    var visibilitySM: Double? = null,
    var changeIndicator: String? = null,
)

data class TAF(
    var icao: String,
    var raw: String,
    var issueTimeMillis: Long? = null,
    var periods: List<TAFForecastPeriod> = emptyList(),
)

/**
 * A pilot report.
 *
 * iOS gives `PIREP` an identity-based `==` (`lhs.id == rhs.id`), so two reports with
 * identical contents are still distinct values and a mutated copy still equals its
 * original. [equals]/[hashCode] below reproduce that exactly; [id] is the last
 * constructor parameter so `copy()` carries it over the way Swift's `var` mutation
 * does.
 */
data class PIREP(
    var raw: String,
    var coordinate: Coordinate? = null,
    var altitudeFt: Int? = null,
    var turbulence: TurbulenceSeverity? = null,
    var icing: String? = null,
    var timeMillis: Long? = null,
    var aircraftType: String? = null,
    val id: String = UUID.randomUUID().toString(),
) {
    override fun equals(other: Any?): Boolean = other is PIREP && id == other.id
    override fun hashCode(): Int = id.hashCode()
}

/**
 * An AIRMET/SIGMET/G-AIRMET advisory. Like [PIREP], equality is identity on iOS.
 *
 * [hazard] is TURB, ICE, CONVECTIVE, IFR, MTW or ASH.
 */
data class SIGMET(
    var raw: String,
    var hazard: String? = null,
    var severity: String? = null,
    var area: List<Coordinate> = emptyList(),
    val id: String = UUID.randomUUID().toString(),
) {
    override fun equals(other: Any?): Boolean = other is SIGMET && id == other.id
    override fun hashCode(): Int = id.hashCode()

    /** Coarse hazard classification. */
    enum class Category { CONVECTIVE, TURBULENCE, ICING_OR_MOUNTAIN_WAVE, OTHER }

    /**
     * Coarse hazard classification derived from the advisory's hazard field
     * (falling back to the raw text when the structured field is absent).
     */
    val category: Category
        get() {
            val text = (hazard ?: raw).uppercase()
            if (text.contains("CONV") || text.contains("TS")) return Category.CONVECTIVE
            if (text.contains("TURB")) return Category.TURBULENCE
            if (text.contains("ICE") || text.contains("MTW")) return Category.ICING_OR_MOUNTAIN_WAVE
            return Category.OTHER
        }

    /**
     * The turbulence severity this advisory implies. This is the single source of
     * truth used both to raise the composite ride index and to color the advisory
     * area on the route map, so the two never disagree.
     */
    val turbulenceSeverity: TurbulenceSeverity
        get() = when (category) {
            Category.CONVECTIVE -> TurbulenceSeverity.SEVERE
            Category.TURBULENCE -> {
                val sev = (severity ?: "").uppercase()
                if (sev.contains("SEV") || sev.contains("EXTRM") || sev.contains("EXTREME")) {
                    TurbulenceSeverity.SEVERE
                } else {
                    TurbulenceSeverity.MODERATE
                }
            }
            Category.ICING_OR_MOUNTAIN_WAVE -> TurbulenceSeverity.LIGHT
            // IFR / volcanic-ash / other advisories don't imply a rough ride, so
            // they neither raise the ride index nor paint the turbulence overlay.
            Category.OTHER -> TurbulenceSeverity.SMOOTH
        }

    /** A short human label for the hazard, used in ride-report factors. */
    val hazardLabel: String
        get() = when (category) {
            Category.CONVECTIVE -> "convective SIGMET"
            Category.TURBULENCE -> "turbulence SIGMET"
            Category.ICING_OR_MOUNTAIN_WAVE, Category.OTHER -> "SIGMET advisory"
        }

    /**
     * The valid polygon vertices when this advisory has a drawable area (>= 3
     * points). Advisories without a real polygon can't be placed on the map and
     * must not silently drive the ride index either.
     */
    val drawableArea: List<Coordinate>?
        get() {
            val points = area.filter { it.isValid }
            return if (points.size >= 3) points else null
        }
}

/** A ride report relevant to the current route, produced by the ride-report engine. */
data class RideReportItem(
    var severity: TurbulenceSeverity,
    var altitudeBand: IntRange? = null,
    var distanceAheadNM: Double? = null,
    /**
     * Whether [distanceAheadNM] was measured from the live aircraft position (true) or
     * from a route-start fallback such as the departure airport (false). The ride-report
     * phrase and the Weather tab only present a "… miles ahead" distance when it is
     * aircraft-relative — otherwise the number would read as distance-from-origin, not
     * distance ahead of the aircraft. The turbulence model still uses [distanceAheadNM]
     * for route-progress weighting regardless.
     */
    var distanceIsFromAircraft: Boolean = true,
    var bearing: Double? = null,
    var nearFix: String? = null,
    var sourceRaw: String = "",
    /** Age of the source report in minutes, when the report time is known. */
    var ageMinutes: Double? = null,
    /**
     * The report's actual level (ft), when known — for the altitude-matched PIREP relay
     * and the smoother-altitude search. Distinct from [altitudeBand] (a ±2000 display band).
     */
    var reportedAltitudeFt: Int? = null,
    /** Reporting aircraft type code (e.g. "B738"), when the source is a PIREP. */
    var aircraftType: String? = null,
) {
    /** Stable list identity (iOS `Identifiable`); deliberately outside value equality. */
    val id: String = UUID.randomUUID().toString()
}

/**
 * A reachable altitude with a smoother reported ride than the pilot's current level,
 * derived from PIREPs at other levels along the route. [higher] is relative to the
 * pilot. Only ever produced when a real report supports it (never invented).
 */
data class SmootherAltitude(
    var altitudeFt: Int,
    var severity: TurbulenceSeverity,
    var aircraftType: String?,
    var higher: Boolean,
)
