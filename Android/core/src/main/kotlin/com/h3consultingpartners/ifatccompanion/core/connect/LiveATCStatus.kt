package com.h3consultingpartners.ifatccompanion.core.connect

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility

/**
 * Snapshot of live multiplayer / ATC-staffing context read from Infinite Flight.
 * Used so the companion can step aside when a human controller is present.
 *
 * The Infinite Flight **Connect API** does not publish a map of which airport each
 * controller is working, so a bare "a human is controlling somewhere in this session"
 * flag can't tell us whether that controller is relevant to *this* flight. What it
 * *does* publish is the name of the frequency the pilot is **currently tuned to**
 * (`aircraft/0/systems/comm_radios/com_1/name`). That is the location-aware signal:
 * if the pilot has dialled a staffed controller's frequency, that controller is on
 * the pilot's own radio, so the companion must stand by; if the pilot is on UNICOM,
 * ATIS, or an unstaffed field, the companion keeps working — regardless of who else
 * is controlling elsewhere in the session.
 *
 * Ported from `IFATCCompanion/Connect/LiveATCStatus.swift`.
 */
data class LiveATCStatus(
    /** True when the session appears to be on a multiplayer server. */
    val multiplayerOnline: Boolean = false,
    /** Server name, if exposed (e.g. "Expert", "Training"). */
    val serverName: String? = null,
    /**
     * True when a human controller is staffing *some* frequency somewhere in the
     * session. Presence only — it does not say which airport/facility they work, so it
     * can't decide standby on its own.
     */
    val humanControllerActive: Boolean = false,
    /**
     * A human controller's reported name/username, if the manifest exposes one (e.g.
     * "j_vonl"). Informational: it identifies *a* controller in the session but not the
     * facility or frequency they work.
     */
    val controllerName: String? = null,
    /**
     * The name of the frequency the pilot is tuned to right now, read live from COM1
     * (`aircraft/0/systems/comm_radios/com_1/name`) — e.g. "Ground", "KSFO Tower",
     * "Unicom", "ATIS". This is how the companion knows which frequency the pilot is
     * actually on. Null/empty when unavailable or not tuned to a named frequency.
     */
    val tunedFrequencyName: String? = null,
    /** The COM1 frequency in MHz, if exposed (diagnostics/logging only). */
    val tunedFrequencyMHz: Double? = null,
) {

    /**
     * The facility the pilot is tuned to right now, resolved from the live COM1
     * frequency name (e.g. "KSFO Tower" → Tower). Null for UNICOM/ATIS or a name that
     * doesn't map to a gate-to-gate position.
     */
    val tunedFacility: ATCFacility? get() = ATCFacility.matching(tunedFrequencyName)

    /**
     * Whether the frequency the pilot is tuned to is a **staffed human ATC** frequency.
     *
     * Infinite Flight only offers a field's ATC frequencies while a human is actually
     * working them — otherwise pilots use UNICOM — so a tuned COM name that isn't blank,
     * UNICOM, or ATIS is a live human controller on the pilot's own radio. This is the
     * per-frequency, location-aware test: it's true only while the pilot has tuned a
     * controller, and never for a controller working a different airport elsewhere in
     * the session. UNICOM is an unstaffed advisory and ATIS is an automated broadcast,
     * so both are excluded — as is the "Unknown"/"None" placeholder Infinite Flight
     * reports for COM1 when the pilot isn't tuned to any frequency at all.
     */
    val tunedToHumanController: Boolean
        get() = isHumanControllerFrequency(tunedFrequencyName)

    /**
     * Whether the companion should defer to a human controller right now: true exactly
     * when the pilot is tuned to a staffed human ATC frequency.
     */
    val companionShouldStandBy: Boolean get() = tunedToHumanController

    /** Short human-readable summary for the UI. */
    val summary: String
        get() {
            if (tunedToHumanController) {
                val facility = tunedFacility?.title ?: tunedFrequencyName ?: "a controller"
                // Append the controller's name only when it adds information beyond the
                // frequency label itself.
                val who = controllerName
                    ?.let { name -> if (name.equals(facility, ignoreCase = true)) null else " ($name)" }
                    ?: ""
                return "Tuned to human ATC — $facility$who. " +
                    "Companion standing by; follow the live controller."
            }
            if (humanControllerActive) {
                val who = controllerName?.let { " ($it)" } ?: ""
                return "Human ATC online$who — tune their frequency to hand off. " +
                    "Companion is covering your current frequency."
            }
            if (multiplayerOnline) {
                val s = serverName?.let { " on $it" } ?: ""
                return "Multiplayer$s — no human ATC detected."
            }
            return "Solo / no human ATC detected."
        }

    companion object {
        val none = LiveATCStatus()

        /**
         * True when [name] is a live, staffed human-controller frequency. Blank, missing,
         * UNICOM, ATIS, and the "Unknown"/"None" not-tuned placeholders all return false —
         * there is no controller to defer to in any of those cases.
         */
        fun isHumanControllerFrequency(name: String?): Boolean {
            val raw = name?.trimmingWhitespaces()
            if (raw.isNullOrEmpty()) return false
            val upper = raw.uppercase()
            return !upper.contains("UNICOM") && !upper.contains("ATIS") &&
                upper != "UNKNOWN" && upper != "NONE"
        }
    }
}

/**
 * Deterministically derives a [LiveATCStatus] from raw values read off the Connect
 * manifest. Tolerant of missing fields — Connect coverage varies by version, so each
 * signal is optional and the detector degrades gracefully.
 */
class LiveATCDetector {

    /**
     * @param atcActive an explicit "is ATC active" flag, if exposed.
     * @param controllerName a staffed-controller name/username string, if exposed.
     * @param facilityCount number of active ATC facilities, if exposed.
     * @param online an "is online / multiplayer" flag, if exposed.
     * @param serverName the server name string, if exposed.
     * @param tunedFrequencyName the name of the frequency the pilot is tuned to (COM1),
     *   if exposed — the location-aware standby signal.
     * @param tunedFrequencyMHz the tuned COM1 frequency in MHz, if exposed.
     */
    fun status(
        atcActive: Boolean?,
        controllerName: String?,
        facilityCount: Int?,
        online: Boolean?,
        serverName: String?,
        tunedFrequencyName: String? = null,
        tunedFrequencyMHz: Double? = null,
    ): LiveATCStatus {
        val cleanedServer = serverName?.trimmingWhitespaces()?.nonEmptyOrNull()
        val multiplayerOnline = (online ?: false) || (cleanedServer != null)

        val cleanedController = controllerName?.trimmingWhitespaces()?.nonEmptyOrNull()
        // UNICOM and ATIS are not human controllers — UNICOM is an unstaffed advisory
        // frequency and ATIS is an automated broadcast, so neither counts as staffing.
        val nameIsHuman = cleanedController?.let {
            val name = it.uppercase()
            !name.contains("UNICOM") && !name.contains("ATIS")
        } ?: false

        val humanByFlag = atcActive ?: false
        val humanByCount = (facilityCount ?: 0) > 0

        // Infinite Flight reports "Unknown"/"None" for COM1 when the pilot isn't tuned to
        // any frequency; treat those placeholders as "not tuned" so they never surface in
        // the UI or trip the guard.
        val cleanedTuned = tunedFrequencyName?.trimmingWhitespaces()?.nonEmptyOrNull()
        val tunedIsPlaceholder = cleanedTuned?.let {
            val u = it.uppercase()
            u == "UNKNOWN" || u == "NONE"
        } ?: false

        val status = LiveATCStatus(
            multiplayerOnline = multiplayerOnline,
            serverName = cleanedServer,
            humanControllerActive = humanByFlag || humanByCount || nameIsHuman,
            controllerName = if (nameIsHuman) cleanedController else null,
            tunedFrequencyName = if (tunedIsPlaceholder) null else cleanedTuned,
            tunedFrequencyMHz = tunedFrequencyMHz,
        )

        // Being tuned to a named controller frequency is itself proof a human is on the
        // air, even if the standalone staffing flags didn't resolve on this IF version.
        return if (status.tunedToHumanController) {
            status.copy(humanControllerActive = true)
        } else {
            status
        }
    }
}
