package com.h3consultingpartners.ifatccompanion.core.phraseology

/**
 * The narrow view of a published procedure (SID / STAR / approach) that the
 * phraseology builders need.
 *
 * iOS passes `Procedure` (from `IFATCCompanion/ATC/ProcedureLibrary.swift`) straight
 * into [PhraseologyEngine]. That type belongs to the ATC package, so this interface
 * is the contract between the two: the ATC port's `Procedure` implements it and
 * nothing in this package depends on the procedure parser.
 *
 * Field-by-field this is exactly what the Swift engine reads off a `Procedure`:
 * `displayName`, `spokenName(icao:)`, `runway`, `fixes`, and the approach type's
 * `display` / `spoken` forms.
 */
interface PhraseologyProcedure {
    /** Transcript form, e.g. "WAGON5", "WAGON5.HOBTT", "ILS RWY 30L". */
    val displayName: String

    /** Runway the procedure serves, when it names one. */
    val runway: String?

    /** Ordered fixes, when known. `descendViaArrival` names `fixes[1]` when present. */
    val fixes: List<String>

    /** `ApproachType.display` — "ILS", "RNAV (GPS)", … — or null for a SID/STAR. */
    val approachTypeDisplay: String?

    /** `ApproachType.spoken` — "I L S", "R NAV G P S", … — or null for a SID/STAR. */
    val approachTypeSpoken: String?

    /** Spoken form for the synthesizer, in the selected pack. */
    fun spokenName(icao: Boolean): String
}
