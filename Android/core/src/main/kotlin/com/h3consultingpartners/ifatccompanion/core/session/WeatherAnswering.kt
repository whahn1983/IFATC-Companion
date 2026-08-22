package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyEngine
import com.h3consultingpartners.ifatccompanion.core.weather.METAR
import com.h3consultingpartners.ifatccompanion.core.weather.SIGMET
import com.h3consultingpartners.ifatccompanion.core.weather.SmootherAltitude
import com.h3consultingpartners.ifatccompanion.core.weather.radar.RadarOverlayModel

/**
 * The weather engine's half of a weather-aware controller call.
 *
 * On iOS the ride reports, the PIREP pool and the ATC state machine all live on one
 * `AppModel`, so `requestRideReport()` can read them directly. Here they are two objects
 * that share only the flight plan, which is deliberate — but a Ride Report request still
 * has to be answered by the controller, and the answer is composed from weather the flight
 * session does not own.
 *
 * This is the seam. The session asks; whoever holds the weather answers. Defaulted to
 * [None] so the coordinator stays independent of the weather subsystem and its tests keep
 * constructing it unchanged.
 */
interface WeatherAnswering {

    /**
     * Refresh the weather, recompute the ride picture along the route, and compose Center's
     * read-out of it. Null when there is no weather engine to ask.
     *
     * Suspending because it fetches: iOS runs the same sequence inside a `Task` after
     * posting the pilot's half, so the pilot's call appears at once and the answer follows.
     */
    suspend fun rideReport(callsign: PhraseologyEngine.Callsign): ATCTransmission?

    /** The same for a destination-weather request. */
    suspend fun destinationWeather(callsign: PhraseologyEngine.Callsign, icao: String): ATCTransmission?

    /**
     * The one-shot smoother level the last ride report suggested, if any.
     *
     * It biases the next plain Request Higher/Lower toward that exact level, and it is what
     * the "Climb FL390" accept button offers. Cleared by any of those.
     */
    fun smootherAltitude(): SmootherAltitude?

    fun clearSmootherAltitude()

    /**
     * Whether a reported ride at or above moderate covers [altitudeFt]'s band, which is
     * what makes a controller refuse a climb into it.
     */
    fun altitudeIsBlockedByRideReports(altitudeFt: Int): Boolean

    /**
     * The current report for the end of the flight in play.
     *
     * The wind in it is what every takeoff and landing clearance reads out, and what the
     * runway in use is derived from when nothing has named one. With no report, callers
     * fall back to a plausible fixed wind rather than reading "wind zero zero zero at
     * zero", which is the one wind a controller never says.
     */
    fun metar(arriving: Boolean): METAR?

    /**
     * The precipitation overlay as it stands: which provider, whether there is coverage, and
     * the cells the deviation flow routes around.
     */
    fun radarOverlay(): RadarOverlayModel

    /** SIGMETs whose area lies along the route — the only ones that raise an advisory. */
    fun routeSigmets(): List<SIGMET>

    /**
     * The phonetic information word the pilot reports for this leg — "Alpha" — **once**,
     * and only once an ATIS has actually been tuned.
     *
     * The pilot says it on the departure taxi request and on the first Approach check-in of
     * the arrival. Returns null when there is nothing to report: no ATIS received, or the
     * code already reported on this leg. Reading it marks the leg reported, which is why it
     * lives with whoever holds the ATIS rather than with the caller.
     */
    fun atisInfoWord(arriving: Boolean): String?

    /** No weather engine attached: every request is unanswerable and nothing is blocked. */
    object None : WeatherAnswering {
        override suspend fun rideReport(callsign: PhraseologyEngine.Callsign): ATCTransmission? = null

        override suspend fun destinationWeather(
            callsign: PhraseologyEngine.Callsign,
            icao: String,
        ): ATCTransmission? = null

        override fun smootherAltitude(): SmootherAltitude? = null

        override fun clearSmootherAltitude() = Unit

        override fun altitudeIsBlockedByRideReports(altitudeFt: Int): Boolean = false

        override fun metar(arriving: Boolean): METAR? = null

        override fun radarOverlay(): RadarOverlayModel = RadarOverlayModel()

        override fun routeSigmets(): List<SIGMET> = emptyList()

        override fun atisInfoWord(arriving: Boolean): String? = null
    }
}
