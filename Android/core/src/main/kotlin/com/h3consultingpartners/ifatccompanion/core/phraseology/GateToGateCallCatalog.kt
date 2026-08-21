package com.h3consultingpartners.ifatccompanion.core.phraseology

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One gate-to-gate radio call in the declarative catalog.
 *
 * Field names are the JSON keys of `Resources/GateToGateCallCatalog.json` verbatim.
 * `atcTemplate`, `rampTemplate` and `pilotReadbackTemplate` are null for the calls
 * that have no such side (a pilot-only request has no `atcTemplate`, and only Ramp
 * calls carry a `rampTemplate`).
 */
@Serializable
data class GateToGateCall(
    val id: String,
    val phase: String,
    val facility: String,
    val callType: String,
    val trigger: String,
    val atcTemplate: String? = null,
    val rampTemplate: String? = null,
    val pilotReadbackTemplate: String? = null,
    val requiredReadbackElements: List<String> = emptyList(),
    val optionalElements: List<String> = emptyList(),
    val safetyCritical: Boolean = false,
    val ttsNotes: String = "",
    val validationRules: List<String> = emptyList(),
    val notes: String = "",
    /** "approved", "simulated", "needsReview", … — anything not approved needs validating. */
    val reviewStatus: String = "",
    val sourceType: String = "",
)

/**
 * The declarative catalog of gate-to-gate calls: templates, triggers, required
 * readback elements, validation rules and review status for every call the app
 * makes, from the ATIS acknowledgement to block-in.
 *
 * This is reference/documentation data — as on iOS, no engine reads it at runtime;
 * [PhraseologyEngine] and [RampPhraseologyEngine] hold the wording the app actually
 * speaks. It is loaded so tests and tooling can audit the two against each other.
 *
 * The JSON is a byte-for-byte copy of `Resources/GateToGateCallCatalog.json` and is
 * read from the classpath, which works unchanged on Android — Java resources are
 * packaged into the APK.
 */
@Serializable
data class GateToGateCallCatalog(
    val schemaVersion: String,
    val phraseologyAuthority: String,
    val mode: String,
    val notes: String,
    val facilities: List<String>,
    val calls: List<GateToGateCall>,
) {

    /** The call with this `id`, or null when the catalog does not describe one. */
    fun call(id: String): GateToGateCall? = calls.firstOrNull { it.id == id }

    /** Every call attributed to a catalog facility name (e.g. "ramp", "arrivalTower"). */
    fun calls(facility: String): List<GateToGateCall> = calls.filter { it.facility == facility }

    companion object {
        /** Classpath name of the packaged catalog. */
        const val RESOURCE_NAME = "GateToGateCallCatalog.json"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Load the packaged catalog from the classpath.
         *
         * @throws IllegalStateException when the resource is missing from the build.
         */
        fun load(): GateToGateCallCatalog =
            loadOrNull() ?: error("Missing or unreadable classpath resource /$RESOURCE_NAME")

        /** Load the packaged catalog, or null when it is missing or cannot be decoded. */
        fun loadOrNull(): GateToGateCallCatalog? {
            val text = GateToGateCallCatalog::class.java
                .getResourceAsStream("/$RESOURCE_NAME")
                ?.use { it.readBytes().decodeToString() }
                ?: return null
            return runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
        }
    }
}
