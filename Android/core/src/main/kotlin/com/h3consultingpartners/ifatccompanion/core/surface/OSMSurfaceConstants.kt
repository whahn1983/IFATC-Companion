package com.h3consultingpartners.ifatccompanion.core.surface

import com.h3consultingpartners.ifatccompanion.core.config.AppConfig
import com.h3consultingpartners.ifatccompanion.core.net.AppHttp

/**
 * Central configuration for the OpenStreetMap airport-surface feature.
 *
 * OpenStreetMap is the **only** live airport-surface data source for this release.
 * Data is retrieved as small, airport-sized extracts through a public **Overpass**
 * API endpoint, normalized into an internal surface model, cached on disk, and
 * rendered as custom map overlays. Everything here is a single source of truth so
 * the endpoint, attribution wording, license links, and cache policy are configured
 * in exactly one place.
 *
 * OpenStreetMap data is licensed under the **Open Database License (ODbL) 1.0** — not
 * CC BY 4.0. Commercial use is permitted subject to the ODbL and OSM attribution
 * requirements. Nothing here implies OpenStreetMap or any Overpass operator endorses
 * IFATC Companion, and OSM data is never presented as authoritative or guaranteed to
 * match Infinite Flight scenery.
 *
 * Ported from `IFATCCompanion/AirportSurface/OSMSurfaceConstants.swift`.
 */
object OSMSurface {

    // MARK: - Provider identity

    /** Human-readable name of the airport-surface data provider. */
    const val PROVIDER_NAME = "OpenStreetMap contributors"

    /** The license OSM data is distributed under (NOT CC BY 4.0). */
    const val LICENSE_NAME = "Open Database License (ODbL) 1.0"

    /** Short license identifier used in compact diagnostics/labels. */
    const val LICENSE_SHORT_NAME = "ODbL 1.0"

    // MARK: - Visible attribution

    /**
     * The exact wording shown directly on the taxi map, in Settings, and in
     * diagnostics. Kept identical everywhere so attribution reads consistently.
     */
    const val ATTRIBUTION_TEXT = "Surface data © OpenStreetMap contributors"

    /**
     * The bare copyright line some compact contexts use ("© OpenStreetMap
     * contributors"). Prefer [ATTRIBUTION_TEXT] where space allows.
     */
    const val ATTRIBUTION_SHORT = "© OpenStreetMap contributors"

    /** The OpenStreetMap copyright & license page the visible attribution links to. */
    const val COPYRIGHT_URL = AppConfig.Links.OPENSTREETMAP_COPYRIGHT

    /** Canonical ODbL 1.0 license text, linked from the detailed legal/data-source page. */
    const val ODBL_LICENSE_URL = AppConfig.Links.ODBL_LICENSE

    /**
     * Where the pilot can access the relevant ODbL notice and the transformation /
     * reproduction information for the OSM-derived airport data. Configurable in one
     * place — the IFATC Companion documentation on GitHub (renders the Markdown doc).
     * Swap this for the GitHub Pages HTML mirror if/when one is published.
     */
    const val PUBLIC_DOCUMENTATION_URL = AppConfig.Links.OPENSTREETMAP_LICENSING_DOC

    // MARK: - Overpass access

    /**
     * Public Overpass API endpoints, tried in order. These are **shared community
     * infrastructure** — the app requests only small airport areas, caches results,
     * backs off politely, and never runs parallel repeated queries for the same
     * airport. Free access to OSM data does not guarantee unlimited access to any
     * particular public server, so more than one is listed for graceful failover.
     */
    val OVERPASS_ENDPOINTS: List<String> get() = AppConfig.Endpoints.OVERPASS_ENDPOINTS

    /** The primary endpoint, surfaced in diagnostics and the legal page. */
    val PRIMARY_OVERPASS_ENDPOINT: String get() = OVERPASS_ENDPOINTS.firstOrNull() ?: ""

    /**
     * Server-side Overpass query budget (`[timeout:N]`) and the matching client-side request
     * timeout, in seconds.
     *
     * Sized for the biggest fields rather than the average one. A hub like KLAX answers with
     * ~540 stands plus every runway, taxiway and terminal building in the box; at the previous
     * 30 s budget those extracts could not finish, so the airport never cached — and anything
     * depending on the extract (the taxi route, the automatic gate assignment) silently got
     * nothing. Every fetch is a background coroutine whose result is applied whenever it lands,
     * so a long ceiling costs patience at a big field rather than blocking anything; small
     * fields are unaffected because they answer in a second or two either way.
     */
    const val OVERPASS_QUERY_TIMEOUT_SECONDS = 90
    const val OVERPASS_REQUEST_TIMEOUT_SECONDS: Long = 95

    /**
     * A descriptive User-Agent identifying the app and the publisher, so Overpass
     * operators can attribute traffic and reach the project. Reuses the shared
     * contact URL from [AppHttp].
     *
     * On iOS the version comes from `CFBundleShortVersionString`; here it is the
     * version `:app` injects into [AppHttp.appVersion] at start-up ("dev" until then),
     * so the two platforms report the same shape.
     */
    val userAgent: String
        get() = "IFATCCompanion/${AppHttp.appVersion} (H3 Consulting Partners; +${AppHttp.CONTACT_URL})"

    // MARK: - Extract sizing

    /**
     * Half-width (degrees) of the bounding box requested around an airport reference
     * point. ~0.04° ≈ 4.4 km, comfortably covering even the largest airports while
     * keeping the Overpass extract small (airport-sized, never regional/global).
     */
    const val BBOX_HALF_SPAN_DEGREES = 0.04

    /**
     * Half-width (degrees) of the **building** portion of the extract — a tighter box than
     * the movement-surface box. ~0.017° ≈ 1.9 km around the reference, enough to cover the
     * terminal/concourse core of even a large hub while excluding the surrounding city.
     *
     * Buildings are only used to keep synthesized gate lead-ins from cutting through a
     * concourse, so only those near the stands matter — but `building=*` is one of the
     * densest tags in OSM, and at a hub embedded in a dense metro (e.g. KMSP, ringed by
     * Minneapolis/Richfield/Bloomington) pulling every building in the full 4.4 km box makes
     * the Overpass extract so large it times out, so the airport never caches and the mock
     * demo is stuck on the synthetic field. Scoping buildings to the terminal core keeps the
     * extract small enough to fetch while still covering the concourses that matter. The
     * movement surfaces (runways/taxiways/gates) always use the full [BBOX_HALF_SPAN_DEGREES].
     */
    const val BUILDING_BBOX_HALF_SPAN_DEGREES = 0.017

    // MARK: - Ground → Tower "monitor" hand-off

    /**
     * How far (meters) before the departure runway hold-short the companion has Ground
     * hand the pilot to Tower to *monitor* (real-world "monitor Tower on …", the red
     * sign by the checkered line short of the runway).
     *
     * OpenStreetMap has **no distinct feature** for that monitor-tower line/sign — the
     * only runway-proximity feature it maps is the `aeroway=holding_position`
     * (hold-short) line at the runway itself. So the trigger point cannot be read from
     * OSM; it is derived from the calculated taxi route instead — the hand-off fires
     * once the aircraft is within this distance of the route's end (the runway
     * hold-short). A generous lead keeps it "well before" the hold-short on a
     * normal-length taxi while still being on the final leg to the runway — far enough
     * back that a pilot taxiing at ~25 kt has time to read the hand-off back before
     * reaching the runway.
     */
    const val MONITOR_TOWER_LEAD_METERS = 600.0

    // MARK: - Cache / refresh policy

    /**
     * How long a cached airport extract is considered fresh before a refresh is
     * suggested. OSM airport geometry changes slowly, so a long interval (75 days,
     * within the 60–90 day guidance) avoids needless load on public Overpass servers.
     */
    const val CACHE_REFRESH_INTERVAL_SECONDS: Double = 75.0 * 24 * 60 * 60

    /**
     * Namespace (under the app's private storage) for the on-disk airport-surface
     * cache. iOS names a directory under Caches (`osm-airport-surface`); the Android
     * port addresses the same store through the [FileStore][
     * com.h3consultingpartners.ifatccompanion.core.platform.FileStore] port, whose
     * namespace for this feature is `surface_cache`.
     */
    const val CACHE_NAMESPACE = "surface_cache"

    /** The iOS cache directory name, kept so the two platforms' docs agree. */
    const val CACHE_DIRECTORY_NAME = "osm-airport-surface"

    /**
     * Schema version of the normalized surface model written to the cache. Bumped when
     * a new feature class is added that older cached extracts cannot contain, so a cache
     * written by an earlier version is treated as stale and re-fetched even before its
     * time-based refresh interval elapses.
     *
     * History:
     *  - 1: original schema (runways, taxiways, holds, gates, parking, aprons).
     *  - 2: added building / terminal footprints (so gate lead-ins don't cut through a
     *       concourse). A v1 cache has no buildings and is re-fetched on next load.
     *  - 3: multi-identifier stands are split (`A1;A2` → name "A1", alias "A2"). A v2
     *       cache holds the raw tag as the stand's name, which is spoken as written and
     *       cannot be matched by a pilot typing either identifier, so it is re-fetched.
     */
    const val SURFACE_SCHEMA_VERSION = 3

    // MARK: - Disclaimers

    /**
     * The simulation-only disclaimer shown wherever surface maps / routes / crossing
     * instructions appear.
     */
    const val SIMULATION_DISCLAIMER =
        "Airport surface maps, taxi routes, and runway-crossing instructions are for flight simulation only and must not be used for real-world aviation."

    /** A short note that OSM data may not match Infinite Flight scenery. */
    const val MISMATCH_NOTE =
        "OpenStreetMap airport data is community-sourced, best-effort, and not guaranteed to match Infinite Flight scenery."
}
