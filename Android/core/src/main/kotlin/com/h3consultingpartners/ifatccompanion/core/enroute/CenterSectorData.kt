package com.h3consultingpartners.ifatccompanion.core.enroute

import com.h3consultingpartners.ifatccompanion.core.ui.LegalStrings

/**
 * Central configuration for the enroute Center-sector feature: where the boundary
 * data comes from, how it must be attributed, and where the pilot can read the
 * licence terms. Single source of truth so the wording is identical in Settings,
 * diagnostics, and the docs.
 *
 * The sector boundaries are adapted from the **VATSIM VATSpy Data Project**, which
 * publishes FIR / UIR / ARTCC geometry and the radio names controllers use, globally,
 * under **CC BY-SA 4.0**. OpenStreetMap cannot supply this: OSM maps what is
 * observable on the ground and explicitly excludes airspace, so it has no ATC sector
 * geometry at all (see `docs/CenterSectors.md`).
 *
 * Because the bundled file is an *adapted* database (sub-sectors and terminal areas
 * removed, names normalized, coordinates rounded), ShareAlike applies to it: the
 * generated `CenterSectors.json` carries the attribution and licence in its own
 * header and is redistributed under the same terms. Nothing here implies VATSIM
 * endorses IFATC Companion, and the data is never presented as authoritative.
 *
 * Ported from `IFATCCompanion/Enroute/CenterSectorData.swift`. The attribution
 * strings themselves are **not** redeclared here — they already live in
 * [LegalStrings.CenterSectors], which the legal-copy tests pin character for
 * character, and attribution that exists twice is attribution that drifts.
 */
object CenterSectorData {

    // MARK: - Provider identity

    /** Human-readable name of the sector-boundary data provider. */
    const val PROVIDER_NAME = LegalStrings.CenterSectors.PROVIDER_NAME

    /** The licence the sector data is distributed under. */
    const val LICENSE_NAME = LegalStrings.CenterSectors.LICENSE_NAME

    /** Short licence identifier used in compact labels and diagnostics. */
    const val LICENSE_SHORT_NAME = LegalStrings.CenterSectors.LICENSE_SHORT_NAME

    // MARK: - Visible attribution

    /**
     * The exact wording shown in Settings and diagnostics wherever sector data is
     * surfaced. Kept identical everywhere so attribution reads consistently.
     */
    const val ATTRIBUTION_TEXT = LegalStrings.CenterSectors.ATTRIBUTION_TEXT

    /** Compact form for space-constrained contexts. */
    const val ATTRIBUTION_SHORT = "© VATSpy Data Project"

    /** The source repository the visible attribution links to. */
    const val SOURCE_URL = LegalStrings.CenterSectors.SOURCE_URL

    /** Canonical CC BY-SA 4.0 licence text. */
    const val LICENSE_URL = LegalStrings.CenterSectors.LICENSE_URL

    /**
     * Where the pilot can read how the bundled dataset is derived, and the
     * attribution / ShareAlike notice that goes with it.
     */
    const val PUBLIC_DOCUMENTATION_URL = LegalStrings.CenterSectors.DOCUMENTATION_URL

    // MARK: - Bundled dataset

    /**
     * Name of the bundled dataset (without extension), built by
     * `Tools/build_center_sectors.py`.
     */
    const val RESOURCE_NAME = "CenterSectors"
    const val RESOURCE_EXTENSION = "json"

    /**
     * Classpath name of the packaged dataset. iOS looks the resource up in the app
     * bundle; on Android a Java resource is packaged into the APK and read the same
     * way in `:core`'s unit tests and on device.
     */
    const val RESOURCE_FILENAME = "$RESOURCE_NAME.$RESOURCE_EXTENSION"

    /**
     * Schema version this build understands. The loader refuses anything newer so a
     * future format change can't be half-read.
     */
    const val SUPPORTED_SCHEMA_VERSION = 1

    /**
     * One-line description of what the frequencies mean, shown next to the toggle so
     * nobody mistakes a synthesized frequency for a real one.
     */
    const val FREQUENCY_DISCLAIMER = LegalStrings.CenterSectors.FREQUENCY_DISCLAIMER
}
