import Foundation

/// Central configuration for the OpenStreetMap airport-surface feature.
///
/// OpenStreetMap is the **only** live airport-surface data source for this release.
/// Data is retrieved as small, airport-sized extracts through a public **Overpass**
/// API endpoint, normalized into an internal surface model, cached on disk, and
/// rendered as custom MapKit overlays. Everything here is a single source of truth so
/// the endpoint, attribution wording, license links, and cache policy are configured
/// in exactly one place.
///
/// OpenStreetMap data is licensed under the **Open Database License (ODbL) 1.0** — not
/// CC BY 4.0. Commercial use is permitted subject to the ODbL and OSM attribution
/// requirements. Nothing here implies OpenStreetMap or any Overpass operator endorses
/// IFATC Companion, and OSM data is never presented as authoritative or guaranteed to
/// match Infinite Flight scenery.
enum OSMSurface {

    // MARK: - Provider identity

    /// Human-readable name of the airport-surface data provider.
    static let providerName = "OpenStreetMap contributors"

    /// The license OSM data is distributed under (NOT CC BY 4.0).
    static let licenseName = "Open Database License (ODbL) 1.0"

    /// Short license identifier used in compact diagnostics/labels.
    static let licenseShortName = "ODbL 1.0"

    // MARK: - Visible attribution

    /// The exact wording shown directly on the taxi map, in Settings, and in
    /// diagnostics. Kept identical everywhere so attribution reads consistently.
    static let attributionText = "Surface data © OpenStreetMap contributors"

    /// The bare copyright line some compact contexts use ("© OpenStreetMap
    /// contributors"). Prefer `attributionText` where space allows.
    static let attributionShort = "© OpenStreetMap contributors"

    /// The OpenStreetMap copyright & license page the visible attribution links to.
    static let copyrightURL = URL(string: "https://www.openstreetmap.org/copyright")!

    /// Canonical ODbL 1.0 license text, linked from the detailed legal/data-source page.
    static let odblLicenseURL = URL(string: "https://opendatacommons.org/licenses/odbl/1-0/")!

    /// Where the pilot can access the relevant ODbL notice and the transformation /
    /// reproduction information for the OSM-derived airport data. Configurable in one
    /// place — the IFATC Companion documentation on GitHub (renders the Markdown doc).
    /// Swap this for the GitHub Pages HTML mirror if/when one is published.
    static let publicDocumentationURL = URL(string: "https://github.com/whahn1983/IFATC-Companion/blob/main/docs/OpenStreetMapLicensing.md")!

    // MARK: - Overpass access

    /// Public Overpass API endpoints, tried in order. These are **shared community
    /// infrastructure** — the app requests only small airport areas, caches results,
    /// backs off politely, and never runs parallel repeated queries for the same
    /// airport. Free access to OSM data does not guarantee unlimited access to any
    /// particular public server, so more than one is listed for graceful failover.
    static let overpassEndpoints: [String] = [
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    ]

    /// The primary endpoint, surfaced in diagnostics and the legal page.
    static var primaryOverpassEndpoint: String { overpassEndpoints.first ?? "" }

    /// A descriptive User-Agent identifying the app and the publisher, so Overpass
    /// operators can attribute traffic and reach the project. Reuses the shared
    /// contact URL from `AppHTTP`.
    static let userAgent: String = {
        let version = (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "dev"
        return "IFATCCompanion/\(version) (H3 Consulting Partners; +\(AppHTTP.contactURL))"
    }()

    // MARK: - Extract sizing

    /// Half-width (degrees) of the bounding box requested around an airport reference
    /// point. ~0.04° ≈ 4.4 km, comfortably covering even the largest airports while
    /// keeping the Overpass extract small (airport-sized, never regional/global).
    static let bboxHalfSpanDegrees = 0.04

    // MARK: - Cache / refresh policy

    /// How long a cached airport extract is considered fresh before a refresh is
    /// suggested. OSM airport geometry changes slowly, so a long interval (75 days,
    /// within the 60–90 day guidance) avoids needless load on public Overpass servers.
    static let cacheRefreshInterval: TimeInterval = 75 * 24 * 60 * 60

    /// Directory name (under Caches) for the on-disk airport-surface cache.
    static let cacheDirectoryName = "osm-airport-surface"

    /// Schema version of the normalized surface model written to the cache. Bumped when
    /// a new feature class is added that older cached extracts cannot contain, so a cache
    /// written by an earlier version is treated as stale and re-fetched even before its
    /// time-based refresh interval elapses.
    ///
    /// History:
    ///  - 1: original schema (runways, taxiways, holds, gates, parking, aprons).
    ///  - 2: added building / terminal footprints (so gate lead-ins don't cut through a
    ///       concourse). A v1 cache has no buildings and is re-fetched on next load.
    static let surfaceSchemaVersion = 2

    // MARK: - Disclaimers

    /// The simulation-only disclaimer shown wherever surface maps / routes / crossing
    /// instructions appear.
    static let simulationDisclaimer =
        "Airport surface maps, taxi routes, and runway-crossing instructions are for flight simulation only and must not be used for real-world aviation."

    /// A short note that OSM data may not match Infinite Flight scenery.
    static let mismatchNote =
        "OpenStreetMap airport data is community-sourced, best-effort, and not guaranteed to match Infinite Flight scenery."
}
