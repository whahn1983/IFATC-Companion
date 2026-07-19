# Airport Surface Data (OpenStreetMap)

> **Simulation only — not for real‑world aviation.** Surface data © OpenStreetMap
> contributors (ODbL 1.0). See [OpenStreetMapLicensing.md](OpenStreetMapLicensing.md).

This document describes how IFATC Companion turns raw OpenStreetMap (OSM) data into the
normalized airport‑surface model and the connected surface graph used for taxi routing and
the runway‑crossing workflow.

## Why OpenStreetMap

OSM is the only globally available, openly licensed source of airport movement‑surface
geometry (runways, taxiways, holding positions, gates, aprons). Its **ODbL 1.0** license
permits commercial use with attribution, so it can back a paid companion app without the
per‑airport licensing of proprietary chart databases — provided the ODbL's attribution and
derived‑database obligations are met. Infinite Flight itself exposes no taxiway geometry,
names, holding positions, gates, or crossing geometry, so an external source is required.

## Retrieval

Small, airport‑sized extracts are fetched from a public **Overpass API** endpoint using a
bounding box (~±0.04°) around the active field, requesting `aeroway` features plus `building`
footprints. See [OpenStreetMapLicensing.md §3](OpenStreetMapLicensing.md) for the polite‑client
behavior (User‑Agent, caching, backoff, dedup, manual refresh).

Relevant OSM tags consumed:

| Feature | OSM tag | Used for |
|---|---|---|
| Runway | `aeroway=runway`, `ref` (e.g. `16L/34R`), `width` | runway ends, crossing geometry |
| Taxiway | `aeroway=taxiway`, `ref`/`name`, `oneway`, `access`, `width` | routable edges, names |
| Taxilane | `aeroway=taxilane` | apron lead‑in edges (lower preference) |
| Holding position | `aeroway=holding_position`, `ref` | hold‑short points, confidence |
| Gate | `aeroway=gate`, `ref`/`name` | taxi start/end |
| Parking position | `aeroway=parking_position`, `ref`/`name` | taxi start/end |
| Apron | `aeroway=apron` | context rendering |
| Building / terminal | `building=*`, `aeroway=terminal` | keep gate lead‑ins from crossing a concourse (not routable) |
| Aerodrome | `aeroway=aerodrome` | airport reference point |

## Normalized model

`AirportSurfaceModel` (see `AirportSurface/AirportSurfaceModel.swift`) contains:

- airport identifier and reference coordinate;
- runways, runway ends (threshold + heading), centerlines, widths (or inferred corridor);
- taxiways and taxilanes with names/references, directional (`oneway`) and operational
  (`access`) restrictions;
- holding positions (mapped, and — where none is mapped — inferred, flagged lower confidence);
- gates and parking positions;
- apron areas;
- building / terminal footprints (non‑routable; used only to steer gate lead‑ins);
- **source confidence** (High/Medium/Low/Unavailable);
- **original OSM identifiers and tags** (never discarded);
- **provenance**: Overpass endpoint, fetch date, cache age, bounding box, raw element count,
  and attribution/license metadata.

## Surface graph

`SurfaceGraphBuilder` builds a connected graph:

- **Nodes** at taxiway endpoints, taxiway/taxilane intersections, holding positions,
  runway‑entry points, runway‑crossing points, gates, and parking positions. Intersecting OSM
  taxiways share an identical vertex, so vertices are snapped to a ~1.1 m grid and merged into
  shared nodes.
- **Edges** from connected taxiway/taxilane geometry, each tracking: taxiway name/reference,
  geometry, distance, runway‑crossing status and occupancy, directional and operational
  restrictions, aircraft compatibility (from tagged width), whether the edge/connector is
  inferred, a confidence score, and the original OSM feature references.
- **Runway crossings** are detected by intersecting taxiway geometry with runway centerlines;
  an intersection near a runway‑end threshold is treated as a runway *entry* (line‑up), and
  elsewhere as a *crossing*.
- **Holding positions** are preferred; where none is mapped, an **inferred** hold may be created
  for simulation and is marked inferred / lower confidence.
- **Gates/parking** connect to the taxi network via an **inferred connector** (lower confidence),
  so routing can start/end at a stand without routing *through* stands. The connector attaches to
  the nearest taxi node whose straight lead‑in does **not** cross a building/terminal footprint and
  does not double back across the ramp — so a stand on a thin concourse connects to the taxiway on
  its own side rather than one drawn through the building. A connector that unavoidably clips a
  footprint is flagged and penalized (see [TaxiRouting.md](TaxiRouting.md)).

## Caching

Normalized surfaces are cached on disk (`AirportSurfaceCache`), one JSON file per ICAO under
the app's Caches directory:

- only airports actually used are cached;
- each file stores source identifier, fetch date, cache age, ODbL metadata, attribution, a
  **schema version**, and the retained OSM identifiers/tags;
- refresh interval ~75 days; manual refresh and per‑airport / full cache deletion are available
  in Settings → Data Sources;
- a cache written by an **older schema version** (e.g. one predating building footprints) is
  treated as not‑fresh and re‑fetched on next load, independent of the time‑based interval;
- stale data is flagged; no global OSM database is bundled in the App Store binary.

## Diagnostics

**Airport Surface Diagnostics** (Diagnostics tab) surfaces the airport id, OSM as the active
source, ODbL 1.0, attribution, the Overpass endpoint, fetch date/cache age, feature counts
(including building/terminal footprints), the cache schema version (flagged when outdated),
graph node/edge counts, disconnected components, inferred connectors, the aircraft's snapped
segment, the calculated route, route distance, runway crossings, confidence, next crossing,
current crossing state, readback/authorization state, and the last error. It exports as text
(this is also the ODbL reproduction information).

## Fallback & mismatch

OSM data may be incomplete, unnamed, disconnected, or inconsistent with Infinite Flight
scenery. The app **never claims OSM data is authoritative**, grades confidence, and degrades
gracefully (see [TaxiRouting.md](TaxiRouting.md) and
[RunwayCrossingAutomation.md](RunwayCrossingAutomation.md)).
