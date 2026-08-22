package com.h3consultingpartners.ifatccompanion.core.phraseology

import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A single overridable controller call template. [display] is shown in the
 * transcript (normal digits); [spoken] is fed to the speech synthesizer
 * (phonetic). Both support `{placeholder}` tokens substituted at render time.
 *
 * Ported from `IFATCCompanion/Phraseology/PhraseologyProfile.swift`.
 */
@Serializable
data class PhraseologyTemplate(
    val display: String,
    val spoken: String,
)

/**
 * The controller calls a user profile may override. Each key documents the
 * placeholders available to its template so the editor can guide the user.
 *
 * Declaration order is the order the editor UI lists them in.
 */
enum class PhraseologyTemplateKey(val rawValue: String) {
    CLEARANCE("clearance"),
    TAXI_TO_RUNWAY("taxiToRunway"),
    TAKEOFF("takeoff"),
    LANDING("landing"),
    ;

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            CLEARANCE -> "IFR Clearance"
            TAXI_TO_RUNWAY -> "Taxi to Runway"
            TAKEOFF -> "Cleared for Takeoff"
            LANDING -> "Cleared to Land"
        }

    /** Placeholder tokens supported by this template (without braces). */
    val placeholders: List<String>
        get() = when (this) {
            CLEARANCE ->
                listOf("callsign", "dest", "sid", "initialAlt", "cruise", "depFreq", "squawk")
            TAXI_TO_RUNWAY -> listOf("callsign", "runway", "via", "crossing")
            TAKEOFF, LANDING -> listOf("callsign", "runway", "wind")
        }

    /**
     * A starting-point template the editor can pre-fill (mirrors built-in FAA wording).
     *
     * Note the deliberate asymmetries: the *spoken* clearance drops the literal word
     * "squawk" (because `{squawk}` already resolves to "squawk four two seven one"), and
     * the spoken takeoff/landing drop the literal word "wind" (because `{wind}` already
     * resolves to "wind three three zero at one two").
     */
    val defaultTemplate: PhraseologyTemplate
        get() = when (this) {
            CLEARANCE -> PhraseologyTemplate(
                display = "{callsign}, cleared to {dest} via {sid}, climb via SID except maintain {initialAlt}, expect {cruise} one zero minutes after departure, departure frequency {depFreq}, squawk {squawk}.",
                spoken = "{callsign}, cleared to {dest} via {sid}, climb via SID except maintain {initialAlt}, expect {cruise} one zero minutes after departure, departure frequency {depFreq}, {squawk}.",
            )
            TAXI_TO_RUNWAY -> PhraseologyTemplate(
                display = "{callsign}, taxi to runway {runway} via {via}{crossing}.",
                spoken = "{callsign}, taxi to runway {runway} via {via}{crossing}.",
            )
            TAKEOFF -> PhraseologyTemplate(
                display = "{callsign}, wind {wind}, runway {runway}, cleared for takeoff.",
                spoken = "{callsign}, {wind}, runway {runway}, cleared for takeoff.",
            )
            LANDING -> PhraseologyTemplate(
                display = "{callsign}, wind {wind}, runway {runway}, cleared to land.",
                spoken = "{callsign}, {wind}, runway {runway}, cleared to land.",
            )
        }

    companion object {
        fun fromRawValue(raw: String): PhraseologyTemplateKey? =
            entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * A user-created phraseology profile: a named set of call-template overrides plus
 * an airline call-set map (designator/name -> spoken radio telephony name). Fully
 * serializable so profiles can be shared as plain JSON. Deterministic — no AI.
 *
 * The JSON keys are the property names, matching the Swift `Codable` synthesis, so a
 * profile exported on iOS imports on Android and back. [id] is a UUID string; new ids
 * are generated upper-cased and hyphenated the way Swift's `UUID` encodes.
 */
@OptIn(ExperimentalUuidApi::class)
@Serializable
data class PhraseologyProfile(
    val id: String = newID(),
    val name: String,
    /** Keyed by [PhraseologyTemplateKey.rawValue]. */
    val templates: Map<String, PhraseologyTemplate> = emptyMap(),
    /** Keyed by uppercased airline designator/name -> spoken radio name. */
    val airlineCallSets: Map<String, String> = emptyMap(),
) {

    fun template(key: PhraseologyTemplateKey): PhraseologyTemplate? = templates[key.rawValue]

    fun airlineCallName(airline: String): String? {
        val key = airline.uppercase().trim()
        if (key.isEmpty()) return null
        return airlineCallSets[key]
    }

    companion object {
        /** A fresh profile id in Swift's `UUID` encoding (uppercase, hyphenated). */
        fun newID(): String = Uuid.random().toString().uppercase()

        /** An example profile users can duplicate as a starting point. */
        fun example(): PhraseologyProfile = PhraseologyProfile(
            name = "Custom Example",
            templates = mapOf(
                PhraseologyTemplateKey.TAKEOFF.rawValue to PhraseologyTemplateKey.TAKEOFF.defaultTemplate,
            ),
            airlineCallSets = mapOf("DLH" to "Lufthansa", "BAW" to "Speedbird"),
        )
    }
}
