package com.h3consultingpartners.ifatccompanion.core.weather

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * A read-only snapshot of the weather/radar provider state for the Weather
 * Diagnostics panel. Assembled by the app-level coordinator; purely informational.
 *
 * Ported from `IFATCCompanion/Weather/WeatherProviderDiagnostics.swift`. The Swift
 * struct's `var`s are carried across as `var`s so the coordinator can fill it in
 * field by field the way `AppModel` does; timestamps are epoch millis (:core has no
 * Foundation `Date`).
 */
data class WeatherProviderDiagnostics(
    var radarSource: String = "NOAA/NWS",
    var radarCoverageAvailable: Boolean = false,
    var lastRadarUpdateMillis: Long? = null,
    var lastAviationUpdateMillis: Long? = null,
    var hazardCount: Int = 0,
    var routeConflictStatus: String = "No conflict",
    var selectedRejoinFix: String? = null,
    /**
     * CONTRACT for the weather-deviation/radar agent: iOS holds
     * `lastDeviationState: WeatherDeviationState` (defined in `RadarOverlayModel.swift`,
     * which belongs to that package) and the Diagnostics panel prints only its
     * `rawValue`. Until that enum lands in :core this snapshot carries the raw value
     * directly — wire it up as `d.lastDeviationStateRawValue = deviation.state.rawValue`.
     */
    var lastDeviationStateRawValue: String = "none",
    var providerError: String? = null,
    var coverageMessage: String? = null,

    /**
     * Actual radar composite bytes downloaded (latest download / running session
     * total). Only the EUMETNET OPERA / CIRRUS composite is megabyte-scale — NOAA and
     * NASA return small server-cropped PNGs — so this measures real ORD data usage.
     */
    var radarLastBytes: Int = 0,
    var radarSessionBytes: Int = 0,

    /**
     * The inputs that turn a mint-line leg (a **true** course) into the heading spoken to
     * the pilot: the wind solved from the aircraft's own wind triangle, the local magnetic
     * variation read from the sim's two headings, and the crab those produce for the leg
     * currently assigned. None of it was visible anywhere before, so a vector that came out
     * pointing the wrong way could only be argued about — these rows make the correction
     * checkable against what Infinite Flight itself is showing.
     *
     * The wind triangle's own estimate — always the triangle's, never whichever wind is in
     * use. The two rows only mean something as a cross-check if the solved one is solved
     * independently, so a tick spent steering by the sim's wind must not write that wind
     * into this row and turn the comparison below into `0° — sim reports “from”` forever.
     */
    var solvedWindFromDegrees: Double? = null,
    var solvedWindKnots: Double? = null,
    /**
     * The wind Infinite Flight itself reports (`environment/wind_direction_true` /
     * `environment/wind_velocity`), when the version exposes them — the preferred source for
     * the crab. Both winds stay on screen with the signed difference between them
     * ([reportedWindDeltaText]): the reported direction is used as the meteorological "from",
     * and that row is the standing check on it. It should sit near 0°; near 180° would mean a
     * build reporting the direction the wind blows *toward*, and — if the two agree on the
     * speed — the cross-check in the coordinator's `trustReportedWind` will already have
     * fallen back to the solved wind. A difference at some other angle, with the speeds far
     * apart, is the triangle mis-solving rather than the sim mis-reporting; compare the two
     * speeds to tell which row to disbelieve.
     */
    var reportedWindDirectionTrue: Double? = null,
    var reportedWindKnots: Double? = null,
    /** Which of the two the assigned headings are actually being crabbed for. */
    var windSourceIsSimReported: Boolean = false,
    var magneticVariationEast: Double? = null,
    /** The true course of the leg last assigned, and the magnetic heading actually spoken. */
    var lastAssignedTrueCourse: Double? = null,
    var lastAssignedHeading: Int? = null,
    /**
     * How the initial departure heading was arrived at — the runway the "fly runway
     * heading" test measures against (flagged when it was guessed from the wind), the
     * origin the bearing was taken from, the fix it targeted, and the true→magnetic step.
     * Composed by the coordinator; the panel prints it verbatim.
     */
    var departureHeadingSummary: String? = null,
) {

    /** Human-readable coverage yes/no for the panel. */
    val coverageText: String get() = if (radarCoverageAvailable) "Yes" else "No"

    /** "270°T · 276°M / 85 kt" — the solved wind, or null until the triangle has a usable sample. */
    val solvedWindText: String?
        get() {
            val from = solvedWindFromDegrees ?: return null
            val kt = solvedWindKnots ?: return null
            return windText(fromTrue = from, knots = kt, variationEast = magneticVariationEast)
        }

    /** "221°T · 227°M / 14 kt" — the wind the sim reports, or null when it doesn't expose it. */
    val reportedWindText: String?
        get() {
            val dir = reportedWindDirectionTrue ?: return null
            val kt = reportedWindKnots ?: return null
            return windText(fromTrue = dir, knots = kt, variationEast = magneticVariationEast)
        }

    /** Which wind the assigned headings are crabbed for — never left to inference. */
    val windSourceText: String
        get() = if (windSourceIsSimReported) "sim-reported" else "solved (wind triangle)"

    /**
     * How far the sim's reported direction sits from the wind the triangle solved, as a
     * signed 0–180° difference. Near **0°** the sim reports the direction the wind blows
     * *from* (the convention the app uses); near **180°** it reports the direction it blows
     * *toward*. Anything in between means one of the two is wrong. Null unless both are known.
     */
    val reportedWindDeltaText: String?
        get() {
            val reported = reportedWindDirectionTrue ?: return null
            val solved = solvedWindFromDegrees ?: return null
            var diff = (reported - solved) % 360
            if (diff > 180) diff -= 360
            if (diff < -180) diff += 360
            val reading = when {
                abs(diff) < 45 -> "sim reports “from”"
                abs(diff) >= 135 -> "sim reports “toward”"
                else -> "inconsistent"
            }
            return String.format(Locale.US, "%.0f° — %s", diff, reading)
        }

    /** "6.2°E" / "3.1°W" — the variation the magnetic conversion is using. */
    val magneticVariationText: String?
        get() {
            val v = magneticVariationEast ?: return null
            return String.format(Locale.US, "%.1f°%s", abs(v), if (v >= 0) "E" else "W")
        }

    /**
     * "true 042° → assigned 038°" for the last weather vector, so the crab plus variation
     * applied to it can be read off directly.
     */
    val assignedHeadingText: String?
        get() {
            val course = lastAssignedTrueCourse ?: return null
            val assigned = lastAssignedHeading ?: return null
            return String.format(Locale.US, "true %03.0f° → assigned %03d°", course, assigned)
        }

    /**
     * "1.8 MB (last 1.8 MB)"-style summary of composite data usage, or null when
     * nothing has been downloaded (NOAA/NASA/mock, or no composite fetched yet).
     */
    val radarDataUsageText: String?
        get() {
            if (radarSessionBytes <= 0) return null
            val total = formatBytes(radarSessionBytes)
            val last = formatBytes(radarLastBytes)
            return "$total this session (last $last)"
        }

    companion object {
        /** A fresh, all-default snapshot. A `get()` because the fields are mutable. */
        val empty: WeatherProviderDiagnostics get() = WeatherProviderDiagnostics()

        /**
         * A wind rendered in **both frames**, because the two rows exist to be held up against
         * Infinite Flight's own panel and the two panels don't speak the same one: every wind
         * here is true (`wind_direction_true`, and a triangle built from true heading and track),
         * while the sim's PFD shows the wind magnetic, like the heading bug beside it. Printing
         * the true number alone made a correct wind look wrong by exactly the local variation —
         * 346°T beside an instrument reading 352°M, with 6.2°W of variation between them, is the
         * same wind twice and nothing to chase. The magnetic step is the one the assigned heading
         * already uses (`magnetic = true − variationEast`); with no variation solved yet there is
         * nothing to step by, so only the true figure is shown, labelled as such.
         */
        fun windText(fromTrue: Double, knots: Double, variationEast: Double?): String {
            if (variationEast == null) {
                return String.format(Locale.US, "%03d°T / %.0f kt", degrees(fromTrue), knots)
            }
            return String.format(
                Locale.US,
                "%03d°T · %03d°M / %.0f kt",
                degrees(fromTrue),
                degrees(fromTrue - variationEast),
                knots,
            )
        }

        /**
         * Rounded before wrapping, so a wind just shy of north prints 000° rather than 360°.
         * Swift's `.rounded()` breaks ties away from zero, which `roundToInt` does not do for
         * a negative half, so the rounding is spelled out here.
         */
        private fun degrees(value: Double): Int {
            val rounded =
                if (value < 0) -floor(-value + 0.5).toInt() else floor(value + 0.5).toInt()
            return ((rounded % 360) + 360) % 360
        }

        fun formatBytes(bytes: Int): String {
            val mb = bytes.toDouble() / (1_024 * 1_024)
            if (mb >= 1) return String.format(Locale.US, "%.1f MB", mb)
            val kb = bytes.toDouble() / 1_024
            return String.format(Locale.US, "%.0f KB", kb)
        }
    }
}
