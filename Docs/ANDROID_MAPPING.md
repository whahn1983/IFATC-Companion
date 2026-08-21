# Android mapping architecture

The iOS app draws two maps on **MapKit**: a route map on the Weather tab (route,
aircraft, METAR/PIREP/SIGMET features, a radar precipitation overlay, weather hazards
and the deviation line) and a taxi map in the ATC view (OpenStreetMap airport-surface
geometry, the assigned taxi route, hold-short positions, runway crossings, gates and
the aircraft).

Android has no MapKit, and the brief was explicit: **do not add a recurring paid map
provider merely to mimic an Apple framework**, and stop before adopting anything that
requires billing, API keys, restrictive commercial licensing, or recurring cost.

## What was evaluated

| Option | Commercial use | Key / billing | Recurring cost | Verdict |
| --- | --- | --- | --- | --- |
| **Google Maps SDK for Android** | Yes | **Requires an API key and an enabled billing account**, even where the map itself is billed at $0 | Contractual exposure; pricing is Google's to change | **Rejected** — the brief says stop before adopting anything requiring billing or API keys. Adopting it would also bind a no-backend, no-account app to a Google Cloud project. |
| **Mapbox Maps SDK** | Yes | Requires an access token; billed per monthly active user above a free tier | Yes, scales with installs | **Rejected** — a recurring bill that grows with success. |
| **MapLibre Native + a hosted tile provider** (MapTiler, Stadia, Protomaps-hosted) | Yes | Token required by every commercial host | Yes | **Rejected** — MapLibre itself is free (BSD-2), but it renders nothing without tiles, and every commercial tile host wants a key and a bill. |
| **MapLibre Native + self-hosted tiles** | Yes | None | **Server hosting — new infrastructure** | **Rejected** — the app has no backend by design, and the brief forbids silently adding hosted infrastructure. |
| **OpenStreetMap public raster tiles** (`tile.openstreetmap.org`) | — | None | None | **Rejected outright.** The brief forbids it and so does the OSMF Tile Usage Policy: the public tile servers are not a production CDN. |
| **osmdroid** | Yes (Apache 2.0) | None itself | None itself | **Rejected** — it is a tile *client*; it still needs a tile source, which lands back on the rows above. |
| **Render the maps ourselves on a Compose canvas** | n/a | None | None | **Adopted.** |

## What was built

Neither of this app's maps is a *place* map. The taxi map is an airport surface
diagram; the weather map is a route with weather drawn on it. In both cases every
pixel that matters is the app's own data — OSM-derived surface geometry, the filed
route, the aircraft, hazard polygons, a precipitation raster the app already fetches —
and none of it needs streets, labels or buildings underneath. What a base map would
have supplied is a coordinate frame, and a coordinate frame is arithmetic.

So the Android build renders both maps itself:

- **`core/map/MapProjection.kt`** — Web Mercator (EPSG:3857), the viewport model, pan,
  pinch-zoom about a focal point, aspect correction and fit-to-content. Pure Kotlin in
  `:core`, so it is unit tested (`MapProjectionTest`) in a way MapKit's transform never
  was.
- **`app/ui/map/MapCanvas.kt`** — a Compose `Canvas` host that owns the viewport,
  handles gestures, and hands each layer a `MapFrame` with a `project(Coordinate)`
  function so every layer agrees on where a coordinate lands.
- **Layers** draw on top of that frame: route polyline, aircraft symbol, weather
  features, precipitation raster, and — on the taxi map — runways, taxiways, aprons,
  stands, the assigned route, hold-short bars and runway crossings.

Web Mercator is not an arbitrary choice: it is the projection NOAA's radar
`exportImage` service and NASA GIBS both publish in, so the precipitation imagery the
app already fetches composites onto the map with no reprojection.

### Geographic context on the weather map — NOT YET BUILT

MapKit gave the weather map coastlines for free. Without a base map the route floats in
space, and **today it does**: the route map draws its polyline, airports, waypoints,
PIREPs, hazard polygons and the aircraft onto an empty background, with no coastline, no
grid and no scale.

An earlier version of this document described a graticule, a scale bar and optional NASA
GIBS coastline imagery as though they existed. None of them do — `grep` finds no
graticule, no scale bar, and no `BaseMapTileSource`; GIBS is wired for the precipitation
raster only. Stated plainly here rather than left to be discovered, and tracked in
`ANDROID_REMAINING_WORK.md`.

The taxi map does not have this problem: an airport surface diagram is self-contained,
because the OSM geometry *is* the map.

### What this costs

| Aspect | MapKit (iOS) | Compose canvas (Android) |
| --- | --- | --- |
| Route, aircraft, hazards, deviation line, taxi geometry | Overlays on a base map | Drawn directly — **parity** |
| Precipitation overlay | Custom overlay renderer | Composited raster — **parity** |
| Pan / pinch-zoom / fit-to-content | MapKit | `MapProjection` — **parity**, and tested |
| Street-level base map | Yes | **No** — and no substitute yet either; see the section above |
| Place labels, points of interest | Yes | **No** |
| 3D / satellite / flyover | Yes | **No** |

The missing rows are all base-map furniture. Neither map's purpose depends on them,
and the alternative was a key, a billing account and a bill that grows with installs.
This trade-off is recorded as a **known parity difference** in
`Docs/ANDROID_PARITY_MATRIX.md` rather than presented as equivalence.

## Options for adding geographic context

`MapCanvas` hands every layer a `MapFrame` with a `project(Coordinate)` function, so any
of these is an added layer rather than a rewrite. The projection and gestures do not
change. Ordered by value per unit of cost and by whether they keep the no-key, no-bill,
no-backend constraints:

| Option | Key / bill / backend | Offline | Effort | What it gives |
| --- | --- | --- | --- | --- |
| **A. Graticule + scale bar** | None | Yes | Small | A coordinate frame and a sense of distance. Pure arithmetic on the existing projection, no network at all. The cheapest thing that stops the route floating in space. |
| **B. Bundled Natural Earth coastlines** | None — public domain, shipped in the APK | Yes | Small–Medium | Real coastlines and borders, drawn as one more vector layer. Works with no connectivity, which matters for the actual use case: a pilot mid-flight. A low-resolution set is small; resolution is a size/detail dial. |
| **C. NASA GIBS raster underlay** | None — keyless, public, already used for precipitation | No | Medium | Satellite or land imagery under the route, at the viewport bounding box, via the same WMS the precipitation layer already calls. No new provider, no key, no bill. Needs connectivity and adds a fetch per viewport change. |
| **D. Protomaps / PMTiles** | No per-request API, but the file must be bundled or hosted | Yes if bundled | Large | A genuine vector base map from a single file. Either a large APK or object-storage hosting — which is new infrastructure, so it is an owner decision, not a default. |
| **E. Commercial SDK** (Google, Mapbox, MapTiler, Stadia) | **Key + billing account + recurring cost that scales with installs** | No | Medium | A full street map. Outside the brief this port was built to; adopting it is a deliberate reversal, not a fix. |

**A and B together** are the recommendation: no key, no bill, no backend, no network, and
between them they turn the route map from a line in the void into something readable. C is
a reasonable enhancement on top. D and E both change the app's cost or dependency shape and
belong to the owner.

Note none of this affects the taxi map, which needs no base map at all.

## Licensing obligations that ride on the map

Rendering OSM-derived geometry does not escape the ODbL. Every taxi map view displays
**"Surface data © OpenStreetMap contributors"**, tappable, linking to
<https://www.openstreetmap.org/copyright>. The wording, the licence naming (ODbL 1.0,
**not** CC BY 4.0) and the link are asserted by `LegalStringsTest`. See
`Docs/ANDROID_DATA_SOURCES.md` and the iOS `docs/OpenStreetMapLicensing.md`.
