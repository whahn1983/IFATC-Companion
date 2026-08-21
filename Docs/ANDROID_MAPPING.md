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

### Geographic context on the weather map — BUILT (A + B + C)

MapKit gave the weather map coastlines for free. The Compose canvas gets them from three
layers of its own, drawn under everything else by `ui/map/BaseMapLayers.kt`:

| Layer | Source | Needs network | Code |
| --- | --- | --- | --- |
| **A. Graticule + scale bar** | Arithmetic on `MapProjection` | No | `core/map/MapGraticule.kt` |
| **B. Coastlines** | Natural Earth 1:110m, public domain, bundled (~75 KB) | No | `core/map/CoastlineData.kt`, `core/src/main/resources/coastlines.json` |
| **C. Satellite underlay** | NASA GIBS `BlueMarble_ShadedRelief_Bathymetry`, keyless WMS | **Yes** | `core/map/BaseImageryService.kt`, `app/map/BaseMapImageryLoader.kt` |

The three are independent by design, and the order matters: **C degrades to A and B**.
A pilot at altitude with no signal gets a graticule, a scale bar and real coastlines, and
loses only the picture of the ground. `BaseImageryService.imagery` returns null for every
failure — no route, no signal, a WMS `ServiceException`, an empty body, bytes that do not
decode — and null simply leaves `BaseMapModel.imagery` unset. Nothing is reported to the
pilot, because nothing has gone wrong that they can act on.

None of this adds a provider relationship. GIBS is the same keyless service the
precipitation estimate already calls, Natural Earth is public domain and shipped in the
APK, and the graticule is a few dozen lines of arithmetic. No API key, no billing account,
no backend, no recurring cost.

**Imagery is fetched once per route, not once per viewport.** `core/map/BaseMapWindow.kt`
pads the route's bounding box (2×, floor of 1°) so ordinary panning and zooming stay
inside what was already fetched, and requests it at the aspect ratio of the window *in Web
Mercator* — sizing from degrees would squash the image by roughly `sec(latitude)`, which is
half at 60° N. The layer is static and carries no `TIME` dimension, so there is nothing to
keep current and one fetch is the right cadence rather than a compromise. With nothing
filed, the window follows the aircraft rounded to a whole degree, so free flight refetches
about every degree crossed instead of every telemetry tick.

The taxi map does not have this problem: an airport surface diagram is self-contained,
because the OSM geometry *is* the map.

### What this costs

| Aspect | MapKit (iOS) | Compose canvas (Android) |
| --- | --- | --- |
| Route, aircraft, hazards, deviation line, taxi geometry | Overlays on a base map | Drawn directly — **parity** |
| Precipitation overlay | Custom overlay renderer | Composited raster — **parity** |
| Pan / pinch-zoom / fit-to-content | MapKit | `MapProjection` — **parity**, and tested |
| Street-level base map | Yes | **No** — coastlines, a graticule and satellite imagery instead; see above |
| Place labels, points of interest | Yes | **No** |
| 3D / satellite / flyover | Yes | **No** |

The missing rows are all base-map furniture. Neither map's purpose depends on them,
and the alternative was a key, a billing account and a bill that grows with installs.
This trade-off is recorded as a **known parity difference** in
`Docs/ANDROID_PARITY_MATRIX.md` rather than presented as equivalence.

## Options that were considered

`MapCanvas` hands every layer a `MapFrame` with a `project(Coordinate)` function, so each
of these was an added layer rather than a rewrite. The projection and gestures do not
change.

| Option | Key / bill / backend | Offline | What it gives | Status |
| --- | --- | --- | --- | --- |
| **A. Graticule + scale bar** | None | Yes | A coordinate frame and a sense of distance. Pure arithmetic on the existing projection, no network at all. | **Built** |
| **B. Bundled Natural Earth coastlines** | None — public domain, shipped in the APK | Yes | Real coastlines, drawn as one more vector layer. Works with no connectivity, which is the case that matters for a pilot mid-flight. | **Built** (1:110m) |
| **C. NASA GIBS raster underlay** | None — keyless, public, already used for precipitation | No | Satellite/relief imagery under the route, via the same WMS the precipitation layer calls. | **Built**, degrades to A + B |
| **D. Protomaps / PMTiles** | No per-request API, but the file must be bundled or hosted | Yes if bundled | A genuine vector base map from a single file. | **Not built** — either a large APK or object-storage hosting, which is new infrastructure and therefore an owner decision |
| **E. Commercial SDK** (Google, Mapbox, MapTiler, Stadia) | **Key + billing account + recurring cost that scales with installs** | No | A full street map. | **Not built** — outside the brief this port was built to; adopting it would be a deliberate reversal, not a fix |

A, B and C together need no key, no bill, no backend and — for A and B — no network.
D and E both change the app's cost or dependency shape and remain owner decisions.

Note none of this affects the taxi map, which needs no base map at all.

Raising coastline resolution is a size/detail dial, not new work: swapping the bundled
1:110m set for 1:50m is a larger `coastlines.json` and nothing else. It was not done
because 1:110m is the right level for orientation at route scale and keeps the asset small.

## Licensing obligations that ride on the map

Rendering OSM-derived geometry does not escape the ODbL. Every taxi map view displays
**"Surface data © OpenStreetMap contributors"**, tappable, linking to
<https://www.openstreetmap.org/copyright>. The wording, the licence naming (ODbL 1.0,
**not** CC BY 4.0) and the link are asserted by `LegalStringsTest`. See
`Docs/ANDROID_DATA_SOURCES.md` and the iOS `docs/OpenStreetMapLicensing.md`.

The base map carries lighter obligations, and they are not the same as each other. Natural
Earth is **public domain**, so its credit is courtesy rather than a condition — it is shown
anyway, because saying where data came from is worth doing whether or not a licence
compels it. NASA asks that GIBS be credited wherever its imagery appears, so the route map
shows "Imagery: NASA GIBS" **only when imagery is actually on screen** — crediting a source
that is not being displayed would be its own kind of wrong. Both strings live in
`LegalStrings.BaseMap` so the map, Settings and this document cannot drift apart.
