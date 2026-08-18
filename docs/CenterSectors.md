# Enroute Center Sectors

> **Simulation only — not for real-world aviation.** Sector boundaries © VATSIM VATSpy
> Data Project, licensed CC BY-SA 4.0. Most sector frequencies are **simulated** (see
> [Frequencies](#frequencies)).

This document describes where the enroute sector boundaries come from, why they do not
come from OpenStreetMap, how the bundled dataset is built, and how the app decides that
an aircraft has crossed from one Center's airspace into the next.

## What the feature does

Before this, one generic "Center" worked the whole enroute leg. Now the companion knows
which sector the aircraft is actually in and hands it along as it flies:

```
Departure  →  "contact Houston Center on 133.775"        (through the TRACON ceiling)
Center     →  "contact Fort Worth Center on 133.975"     (crossing ZHU → ZFW)
Center     →  "contact Memphis Center on 133.750"        (crossing ZFW → ZME)
Center     →  "contact Approach on 119.700"              (descending through the ceiling)
```

Everything the flight says about the controller follows the sector: the hand-off, the
pilot's read-back, the check-in call-up ("Fort Worth Center, United 598, with you at
flight level three seven zero"), the radar-contact reply, weather requests, and the
facility chip in the ATC header.

The scope is deliberately the **enroute leg only** — from the Departure hand-off at the
TRACON ceiling until Approach takes over on the descent. Sector crossings that happen
while Departure or Approach is working the flight are tracked but never spoken: whoever
hands the flight to Center names the sector it is in at that moment.

Turn it off with **Settings → ATC Automation → Center sector hand-offs** to return to a
single generic Center.

## Why not OpenStreetMap

OSM backs the airport-surface feature ([AirportSurfaceData.md](AirportSurfaceData.md)),
so it is the natural first question — but OSM **has no airspace data at all**. OSM maps
what is verifiable on the ground; its
[Aviation page](https://wiki.openstreetmap.org/wiki/Aviation) explicitly asks mappers to
refrain from mapping airspace, airways, FIRs, and other non-observable aviation data, and
sends them to dedicated aviation projects instead. There is no `aeroway` tag for an ARTCC
boundary because an ARTCC boundary is not a thing you can survey.

Sources that *do* publish sector geometry:

| Source | Coverage | Licence | Verdict |
|---|---|---|---|
| **VATSIM VATSpy Data Project** | Global FIR/UIR/ARTCC + radio names | CC BY-SA 4.0 | **Used.** The only openly licensed global set with the names controllers use. |
| FAA Airspace Boundary / ARTCC boundaries (ADIP, ArcGIS) | United States only | US Government, public domain | Excellent, but US-only — a second source would be needed for the rest of the world, plus reconciliation at the borders. |
| openAIP | Wide airspace coverage | CC BY-NC-SA | **Non-commercial** — unusable in a paid app. |
| National AIP / EUROCONTROL | Authoritative per state | Per-state, mostly not open | Not redistributable. |

The VATSpy set is also the closest match to what this app simulates: it is the airspace
model the online ATC community actually staffs, so its sectors line up with the way
Infinite Flight controllers and pilots think about "Center".

## Licence and attribution (CC BY-SA 4.0)

The bundled file is an **adapted database**, so ShareAlike applies to it: it is
redistributed under CC BY-SA 4.0, carries the attribution and licence in its own header,
and the app shows the attribution in **Settings → Data Sources** and **About & Legal**.
The adaptation is limited to selection and normalization (below) — no boundary is moved.

This does not extend the licence to the app: the dataset is a separately licensed file
included alongside the software, not merged into it. Nothing here implies VATSIM endorses
IFATC Companion.

## Building the dataset

`Tools/build_center_sectors.py` downloads the two source files and writes
`IFATCCompanion/Enroute/CenterSectors.json`:

```sh
python3 Tools/build_center_sectors.py                     # download and rebuild
python3 Tools/build_center_sectors.py --cache-dir /tmp/vatspy   # reuse local copies
```

| Source file | Used for |
|---|---|
| `Boundaries.geojson` | MultiPolygon geometry per boundary id |
| `VATSpy.dat` (`[FIRs]`) | `id \| name \| callsign prefix \| boundary` — the display name |

What the build does:

1. **Drops sub-sectors** whose parent is also present (`KZJX-A`, `EGPX-N` → keep `KZJX`,
   `EGPX`). Hand-offs are between whole ARTCCs / FIRs, and overlapping parent-plus-child
   polygons would make "which sector am I in" ambiguous.
2. **Drops non-enroute boundaries** — terminal areas (TMA/TCA/Approach), military
   overlays, and flight-information positions — all of which sit on top of ground an
   enroute sector already owns.
3. **Normalizes names.** `"Ahmedabad ACC - Mumbai"` → `Ahmedabad`; the parenthetical and
   the trailing parent reference are VATSpy bookkeeping, not what a controller says.
4. **Composes the radio name.** Americas / Australia get "Center"; the rest of the world
   gets the ICAO "Control"; names that already end in a radio word keep it ("Adria Radar",
   "Gander Oceanic"). A small override table fixes the handful the rule gets wrong.
5. **Extracts published frequencies.** A few regions — Australia most visibly — name each
   sector by the frequency it works on ("Melbourne 128.85"). That frequency is real, so it
   is kept and the name is cleaned up.
6. **Rounds coordinates** to 3 decimals (~110 m), drops the repeated closing vertex, and
   flattens each ring to `[lon, lat, lon, lat, …]`.
7. **Sorts smallest-area first.** Where boundaries still overlap, the lookup takes the
   first sector containing the aircraft, so this makes that the most specific one —
   deterministically, on every device.

The result is ~450 sectors, ~35,000 vertices, ~0.5 MB, bundled with the app. There is no
network call at runtime.

## Runtime

- **`CenterSector`** — one sector: id, name, radio name, bounding box, polygons, and the
  geometry primitives (bounding-box-first containment by even-odd ray cast, and distance
  to the nearest boundary).
- **`CenterSectorDatabase`** — loads the JSON once, lazily, off the main thread on the
  first airborne fix; answers "which sector is at this position". De-conflicts
  neighbouring sectors' frequencies at load (below).
- **`CenterSectorTracker`** — pure hysteresis. Decides when a position fix means the
  aircraft has actually *flown* into the next sector.
- **`AppModel.updateCenterSector`** — feeds every fix to the tracker, publishes the
  working sector, and issues the hand-off when Center has the aircraft and the radio is
  free.

### When a crossing counts

A boundary crossing only produces a call when all of these hold:

| Guard | Why |
|---|---|
| **4 NM inside** the new sector | Boundaries are often flown *along*, not across. Without a buffer, a track that parallels one bounces the radio between two controllers. |
| **≥ 90 s since the last hand-off** | Clipping the corner where three sectors meet would otherwise stack two calls back to back. |
| **≤ 40 NM since the previous fix** | A bigger jump is not flight — it is the app returning from the background, a link resync, or a repositioned sim. The new sector is adopted **silently**; announcing a boundary crossed while the app was asleep would be nonsense. |
| **Radio free** | No unread-back instruction, no outstanding hand-off, no go-around in progress. The crossing is *held*, not dropped, and issued as soon as the frequency is clear. |

A position no sector covers (the source has a few gaps, mostly over open ocean) keeps the
current sector rather than inventing a hand-off.

Checking in after a sector hand-off is a **call-up, not a request** — the conversation
does not advance. Without that distinction a check-in at cruise would be answered with the
top-of-descent call, since `.descent` is the next Center state in the gate-to-gate order.

The working sector id is part of the session snapshot, so a reconnect or a saved flight
resumes on the sector the pilot is already talking to instead of re-announcing it.

### Frequencies

Real ARTCC/FIR **sector** frequencies are not published as an openly licensed global
dataset, and Infinite Flight exposes none, so the companion synthesizes them:

- where the source publishes a real frequency (the Australian sectors), it is used as-is;
- otherwise, a slot in the 132.000–135.975 MHz enroute band chosen by an FNV-1a hash of
  the sector id — stable across devices and launches (Swift's own `Hasher` is seeded per
  process, so the same sector would otherwise change frequency on every launch);
- at load, any sector that lands on a frequency a **neighbouring** sector already holds is
  stepped up the band until it differs, so a hand-off never sends the pilot to the
  frequency they are already on. Published (real) frequencies are never moved.

## Limitations

- **ARTCC / FIR granularity, not internal sectors.** Real centres are split into dozens of
  high/low sectors with their own frequencies; that data is not openly published anywhere
  globally. Crossing from Houston Center to Fort Worth Center is modelled; the several
  internal frequency changes within Houston's own airspace are not.
- **Boundaries are community-sourced** and are neither authoritative nor guaranteed to
  match Infinite Flight's own airspace model.
- **Vertical structure is ignored.** Sectors are treated as 2-D; upper/lower splits in the
  source are collapsed to the parent boundary.
- **Frequencies are simulated** except where noted above.
- The dataset is a **snapshot**. Re-run the build script to pick up upstream changes.
