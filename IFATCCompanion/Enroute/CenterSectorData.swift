import Foundation

/// Central configuration for the enroute Center-sector feature: where the boundary
/// data comes from, how it must be attributed, and where the pilot can read the
/// licence terms. Single source of truth so the wording is identical in Settings,
/// diagnostics, and the docs.
///
/// The sector boundaries are adapted from the **VATSIM VATSpy Data Project**, which
/// publishes FIR / UIR / ARTCC geometry and the radio names controllers use, globally,
/// under **CC BY-SA 4.0**. OpenStreetMap cannot supply this: OSM maps what is
/// observable on the ground and explicitly excludes airspace, so it has no ATC sector
/// geometry at all (see `docs/CenterSectors.md`).
///
/// Because the bundled file is an *adapted* database (sub-sectors and terminal areas
/// removed, names normalized, coordinates rounded), ShareAlike applies to it: the
/// generated `CenterSectors.json` carries the attribution and licence in its own
/// header and is redistributed under the same terms. Nothing here implies VATSIM
/// endorses IFATC Companion, and the data is never presented as authoritative.
enum CenterSectorData {

    // MARK: - Provider identity

    /// Human-readable name of the sector-boundary data provider.
    static let providerName = "VATSIM VATSpy Data Project"

    /// The licence the sector data is distributed under.
    static let licenseName = "Creative Commons Attribution-ShareAlike 4.0 International"

    /// Short licence identifier used in compact labels and diagnostics.
    static let licenseShortName = "CC BY-SA 4.0"

    // MARK: - Visible attribution

    /// The exact wording shown in Settings and diagnostics wherever sector data is
    /// surfaced. Kept identical everywhere so attribution reads consistently.
    static let attributionText = "Sector boundaries © VATSIM VATSpy Data Project"

    /// Compact form for space-constrained contexts.
    static let attributionShort = "© VATSpy Data Project"

    /// The source repository the visible attribution links to.
    static let sourceURL = URL(string: "https://github.com/vatsimnetwork/vatspy-data-project")!

    /// Canonical CC BY-SA 4.0 licence text.
    static let licenseURL = URL(string: "https://creativecommons.org/licenses/by-sa/4.0/")!

    /// Where the pilot can read how the bundled dataset is derived, and the
    /// attribution / ShareAlike notice that goes with it.
    static let publicDocumentationURL = URL(string: "https://github.com/whahn1983/IFATC-Companion/blob/main/docs/CenterSectors.md")!

    // MARK: - Bundled dataset

    /// Name of the bundled dataset (without extension), built by
    /// `Tools/build_center_sectors.py`.
    static let resourceName = "CenterSectors"
    static let resourceExtension = "json"

    /// Schema version this build understands. The loader refuses anything newer so a
    /// future format change can't be half-read.
    static let supportedSchemaVersion = 1

    /// One-line description of what the frequencies mean, shown next to the toggle so
    /// nobody mistakes a synthesized frequency for a real one.
    static let frequencyDisclaimer =
        "Sector frequencies are simulated — real ARTCC/FIR sector frequencies are not published as open data."
}
