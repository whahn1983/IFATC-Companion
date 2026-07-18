# OpenStreetMap Licensing & Attribution

**Reviewed:** 2026-07-18 · **Applies to:** IFATC Companion airport‑surface mapping, taxi
routing, and runway‑crossing automation.

> **Simulation‑only disclaimer.** Airport surface maps, taxi routes, and runway‑crossing
> instructions are for flight simulation only and must not be used for real‑world aviation.

> **Attribution.** Surface data © OpenStreetMap contributors.

---

## 1. Data provider

The **only** live airport‑surface data source in this release is **OpenStreetMap (OSM)**.
IFATC Companion uses OSM data to model airport movement surfaces (runways, taxiways,
taxilanes, holding positions, gates/parking, aprons), to build a connected surface graph,
to calculate best‑effort taxi routes, and to render a temporary MapKit taxi map.

No proprietary airport‑surface databases are used or consulted — not Jeppesen, Navigraph,
ForeFlight, Google, or Apple imagery — and Infinite Flight scenery is never extracted or
reverse engineered.

## 2. License — ODbL 1.0 (not CC BY 4.0)

OpenStreetMap data is licensed under the **Open Database License (ODbL) 1.0** by the
OpenStreetMap Foundation. It is **not** licensed under CC BY 4.0. (Map *tiles*/cartography
on openstreetmap.org are CC BY‑SA, but this app does not use OSM tiles — see §4.)

- License text: <https://opendatacommons.org/licenses/odbl/1-0/>
- OSM copyright & license page: <https://www.openstreetmap.org/copyright>

Commercial use is permitted under the ODbL, subject to its attribution and share‑alike
obligations for any *derived database* that is publicly used or distributed.

**License requirements last reviewed:** 2026‑07‑18.

## 3. How data is fetched

Airport‑sized extracts are retrieved through a public **Overpass API** endpoint using a
small bounding box around the active departure or destination airport (≈ ±0.04°, a few km).
Only `aeroway`‑tagged features are requested. The primary endpoint is
`https://overpass-api.de/api/interpreter` with `https://overpass.kumi.systems/api/interpreter`
as failover (see `OSMSurfaceConstants.swift`).

The client is a **well‑behaved public‑service client**:

- a descriptive **User‑Agent** identifying *IFATC Companion / H3 Consulting Partners* with a
  contact URL;
- requests only **small airport‑specific areas** — never a region or the planet, and never
  the global OSM database;
- **caches** each successful extract locally and refreshes infrequently (**~75 days**, within
  the 60–90 day guidance);
- **no network activity during taxi** once an airport is loaded;
- **request de‑duplication / coalescing** — never parallel repeated queries for the same
  airport;
- polite **exponential backoff** and endpoint **failover** on `429/5xx`, serving stale cache
  rather than hammering a shared server;
- a **manual refresh** control (Settings → Data Sources) and a graceful *"data service
  temporarily unavailable"* fallback.

Free access to OSM data does **not** guarantee unlimited access to any particular public
Overpass server; the behavior above treats those servers as shared community infrastructure.

## 4. Base map

The base map is **Apple MapKit**. OSM‑derived airport geometry is drawn as **custom
overlays** on top. The app does **not** use the standard OpenStreetMap raster tile servers
as a production map service, and does not use OpenStreetMap logos.

## 5. Normalization

Raw Overpass elements are normalized into an internal `AirportSurfaceModel`
(`OSMSurfaceNormalizer.swift`): runways (with runway ends and centerlines), taxiways and
taxilanes, holding positions, gates/parking, and aprons. During normalization the app
**retains the original OSM feature identifiers and tags** and stamps provenance metadata
(provider, license, attribution, Overpass endpoint, fetch date, bounding box, and raw
element count). Nothing is presented without this metadata, and OSM data is never claimed
to be authoritative or guaranteed to match Infinite Flight scenery.

## 6. How routes and rendered maps are produced

1. **Surface graph** (`SurfaceGraphBuilder.swift`): nodes at taxiway endpoints/intersections,
   holding positions, runway‑entry and runway‑crossing points, and gates/parking; edges from
   connected taxiway/taxilane geometry; runway crossings detected by intersecting taxiway
   geometry with runway centerlines.
2. **Taxi routing** (`TaxiRouteEngine.swift`): an A* search that is **not** shortest‑distance —
   it strongly penalizes unnecessary runway crossings, active‑runway occupancy/back‑taxi,
   inferred connectors, closed/unnamed segments, aircraft‑incompatible geometry, and sharp
   turns, and prefers named, connected, full‑length‑entry paths.
3. **Rendering** (`TaxiMapView.swift`): the OSM‑derived runways/taxiways, the assigned route,
   aircraft, gates, holding positions, and runway crossings are drawn as MapKit overlays with
   visible OSM attribution.

## 7. Produced works vs. derived database

Under the ODbL the distinction matters:

- **Produced Works** — the app's *visual outputs*: the rendered taxi map images, the textual
  taxi instructions, and the runway‑crossing phraseology. These are produced works of the OSM
  database. They carry the visible **"Surface data © OpenStreetMap contributors"** attribution
  wherever they appear.
- **Derived Database** — the normalized `AirportSurfaceModel` and the connected surface graph
  are computed from OSM and, cached on disk, **may constitute an OSM‑derived database** under
  the ODbL. We do **not** assume it falls outside ODbL obligations. Conservatively:
  - the cached derived data **retains OSM identifiers, tags, and license/attribution metadata**
    so it is traceable to its source;
  - the transformation process is documented here and in
    [`AirportSurfaceData.md`](AirportSurfaceData.md) and [`TaxiRouting.md`](TaxiRouting.md);
  - **reproduction information** — the exact Overpass query, bounding box, endpoint, and fetch
    date used to build any airport's extract — is available in **Airport Surface Diagnostics**
    (exportable as text) and can be re‑run to reproduce the source extract from OSM.

The proprietary Swift application, its UI, ATC engine, phraseology, routing logic, and
subscription functionality do **not** need to be open‑sourced merely because OSM data is used.

## 8. Attribution

Visible attribution is shown, unaltered, as **"Surface data © OpenStreetMap contributors"**:

- **directly on the taxi map** (compact and expanded), tappable, linking to
  <https://www.openstreetmap.org/copyright>;
- in **Settings → Data Sources** and **Settings → About & Legal** (with an ODbL 1.0 link);
- in **Airport Surface Diagnostics**;
- in this documentation.

Attribution is never hidden behind a menu or Settings‑only. OSM‑derived geometry is never
presented without it.

## 9. Public documentation location

This document is published at a central, configurable location
(`OSMSurface.publicDocumentationURL` in `OSMSurfaceConstants.swift`), currently the IFATC
Companion GitHub repository/Pages documentation. It provides the relevant ODbL notice and the
transformation/reproduction information for the OSM‑derived airport data.

## 10. Known legal & operational limitations

- OSM airport coverage and quality vary by field; data may be incomplete, unnamed,
  disconnected, or inconsistent with Infinite Flight scenery. The app grades **confidence**
  (High/Medium/Low/Unavailable) and degrades gracefully — see [`TaxiRouting.md`](TaxiRouting.md).
- Public Overpass servers may rate‑limit or be unavailable; the app backs off and serves cached
  data, and may show a temporary‑unavailable message.
- Neither OpenStreetMap, the OpenStreetMap Foundation, nor any Overpass operator endorses or is
  affiliated with IFATC Companion.
- **Simulation only** — see the disclaimer above.
