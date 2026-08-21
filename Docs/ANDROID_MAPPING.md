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

### Geographic context on the weather map

MapKit gave the weather map coastlines for free. Without a base map the route would
float in space, so the weather map draws a **graticule** (labelled parallels and
meridians at a spacing chosen from the zoom) and a **scale bar**, and can optionally
composite **NASA GIBS coastline imagery** — the same keyless, public, no-account
service the app already uses for the global precipitation estimate, and therefore no
new provider, no key and no bill.

### What this costs

| Aspect | MapKit (iOS) | Compose canvas (Android) |
| --- | --- | --- |
| Route, aircraft, hazards, deviation line, taxi geometry | Overlays on a base map | Drawn directly — **parity** |
| Precipitation overlay | Custom overlay renderer | Composited raster — **parity** |
| Pan / pinch-zoom / fit-to-content | MapKit | `MapProjection` — **parity**, and tested |
| Street-level base map | Yes | **No** — graticule + optional GIBS imagery instead |
| Place labels, points of interest | Yes | **No** |
| 3D / satellite / flyover | Yes | **No** |

The missing rows are all base-map furniture. Neither map's purpose depends on them,
and the alternative was a key, a billing account and a bill that grows with installs.
This trade-off is recorded as a **known parity difference** in
`Docs/ANDROID_PARITY_MATRIX.md` rather than presented as equivalence.

## If the owner later wants a base map

`MapCanvas` takes its background from a pluggable source, so adopting one is a
contained change and not a rewrite:

1. Add a `BaseMapTileSource` implementation that fetches and caches tiles.
2. Supply it to `MapCanvas`; the projection, gestures and every layer are unchanged.

That decision belongs to the owner, because it is the one that introduces a key,
a bill, or a server. Nothing in the current build presumes it.

## Licensing obligations that ride on the map

Rendering OSM-derived geometry does not escape the ODbL. Every taxi map view displays
**"Surface data © OpenStreetMap contributors"**, tappable, linking to
<https://www.openstreetmap.org/copyright>. The wording, the licence naming (ODbL 1.0,
**not** CC BY 4.0) and the link are asserted by `LegalStringsTest`. See
`Docs/ANDROID_DATA_SOURCES.md` and the iOS `docs/OpenStreetMapLicensing.md`.
