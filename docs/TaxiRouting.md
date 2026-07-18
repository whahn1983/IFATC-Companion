# Taxi Routing & Confidence

> **Simulation only — not for real‑world aviation.** Surface data © OpenStreetMap
> contributors (ODbL 1.0). See [OpenStreetMapLicensing.md](OpenStreetMapLicensing.md).

IFATC Companion calculates **best‑effort** taxi routes over the OpenStreetMap‑derived surface
graph (see [AirportSurfaceData.md](AirportSurfaceData.md)).

## Routing engine

`TaxiRouteEngine` uses **A\*** with a great‑circle heuristic. It supports:

- **departure** routing from a gate / parking / ramp position / current aircraft position to the
  assigned runway hold‑short point;
- **arrival** routing from the runway exit / current position to a selected gate or parking
  position;
- recalculation after a deviation;
- alternate runway‑entry selection;
- **full‑length runway departure by default** (intersection departure only when selected or
  necessary).

### Not shortest‑distance

Routing is **never** chosen on distance alone. It strongly penalizes or prohibits:

- unnecessary runway crossings; active‑runway back‑taxi; unnecessary runway occupancy;
- disconnected jumps; inferred apron shortcuts; routes through parking stands;
- closed / non‑operational taxiways; taxiways incompatible with the aircraft (from OSM `width`);
- sharp turns unsuitable for the aircraft; low‑confidence / unnamed segments;
- entry at the wrong runway end.

It prefers named taxiways, connected geometry, full‑length runway entry, fewer runway crossings,
realistic turn geometry, high‑confidence features, and aircraft‑compatible paths.

### Aircraft classification

Infinite Flight aircraft type is used when available (`AircraftSizeClass.classify`); otherwise
the aircraft is classified conservatively by size (default **Medium**). The class biases routing
away from narrow taxilanes / tight turns where OSM tags provide enough information.

## Confidence model

Each **dataset** and each calculated **route** is graded:

| Level | Meaning | Behavior |
|---|---|---|
| **High** | connected geometry, taxiway names, clear runways, valid path to the correct runway end, reliable holds, clear crossing geometry, clean aircraft snap | full detailed route + automatic runway‑crossing workflow |
| **Medium** | some inferred holds, minor missing names, limited apron detail, otherwise connected | show route + instructions, but **extra confirmation** required for crossings |
| **Low** | disconnected geometry, missing names, uncertain crossings, inferred connectors, questionable aircraft compatibility, visible mismatch | show limited geometry where useful; **no automatic detailed crossing clearances** |
| **Unavailable** | no credible connected route | disable detailed routing; conservative fallback |

Dataset confidence is graded by `SurfaceConfidenceEvaluator` (feature quality + graph
connectivity). Route confidence is graded by `TaxiRouteEngine` (named fraction, aircraft snap
distance, correct runway end, inferred‑connector use, crossing geometry) and never exceeds a
weak dataset's confidence.

When confidence is too low, the app **does not** issue overly precise instructions — it uses
conservative language instead (see below).

## Phraseology

Ground taxi phraseology (`TaxiPhraseology`) is generated from the calculated route and:

- includes the assigned runway and the ordered taxiway sequence;
- includes an explicit hold‑short instruction;
- never says "cleared to taxi", never says "cross all runways", never invents taxiway names,
  and never implies a runway crossing is included — **crossings are issued separately** (see
  [RunwayCrossingAutomation.md](RunwayCrossingAutomation.md));
- requires a pilot read‑back containing the callsign, assigned runway, taxiway route, and
  hold‑short runway (and the crossing runway on a crossing read‑back).

**Low/Unavailable confidence** downgrades to conservative language, e.g.: *"detailed taxi
routing is unavailable; taxi toward runway 27, hold short of all runways, and continue using
the simulator airport diagram."*

### Load‑time caching

Both the **departure** and **arrival** airport surfaces are cached at flight load — as soon as
the endpoints are known (from the entered plan or Infinite Flight), not lazily right before taxi.
The departure surface is loaded into the coordinator so its taxi routes **synchronously** and
Ground issues the detailed clearance immediately; the arrival surface is fetched into the
provider cache (disk + memory) so its later load is instant and works offline. Pre‑caching never
disturbs a taxi already in progress. On a cold start the departure surface is typically ready by
the time the pilot requests taxi.

### Mock Mode (simulated demo)

Mock Mode pre‑caches the **whole** origin and destination airports of the demo route (KIAH → KMSP)
just like live mode, and taxis the **real** fields so the demo shows realistic routing. The
aircraft is driven by the simulated ticker (there is no live telemetry), but the surface underneath
is the real, pre‑cached OSM extract. The demo defaults to a realistic **United gate** at each hub
(Houston Terminal C, Minneapolis Concourse C); any gate the pilot enters wins. The departure taxi
starts at the real gate stand and routes to the assigned runway; the arrival taxi starts at the
arrival runway's exit and routes to the gate — so the taxi map appears with simulated movement on
**both** departure and arrival. If a real extract can't be fetched (offline first run, no OSM
data) or can't be routed, the demo falls back to the built‑in **synthetic** field so the map and
drive still work. The entered gate is resolved to a real stand (exact → same concourse → any
stand), keeping the taxi on an actual United‑area gate even when the exact gate isn't mapped.

### Asynchronous surface loading

When a live airport is **not yet cached** at the moment the pilot requests taxi (pre‑cache still
in flight, an unknown‑coordinate field, or a cleared cache), the fetch is still resolving, so the
route does not yet exist and Ground issues the **generic** clearance up front. As soon as the
fetch resolves and a credible route is calculated, the coordinator **supersedes** it with the
detailed OSM route clearance (assigned runway + taxiway sequence + hold‑short) and re‑arms the
read‑back, so the pilot's acknowledgement reveals the taxi map. A **cached** airport routes
synchronously and issues the detailed clearance immediately. If the pilot has already been handed
to Tower by the time the fetch resolves, the superseding clearance is suppressed.

## Route tracking & off‑route

`RouteTracker` tracks progress along the route: current segment, completed segments, next turn,
next crossing, distance to the holding point, and arrival at the runway/gate. If the aircraft
leaves the route, an **"Off assigned taxi route"** warning is shown; the app does **not**
silently recalculate — it offers **Recalculate**, **Continue Original Route**, and **Request
New Taxi Instructions**. Automatic recalculation happens only when enabled and route confidence
remains acceptable.

## Manual overrides

The pilot can override the departure/arrival runway, gates, runway entry, automatic crossing
calls, and data refresh (Settings and the taxi map). Infinite Flight Connect provides only
runtime aircraft state (position, heading, groundspeed, on‑ground, type, airports, assigned
runway/facility) — never taxiway geometry, names, holds, gates, crossing geometry, or a
preferred route.

## Supported vs. unsupported airports

Well‑mapped fields with named, connected taxiways and mapped holds grade High and get the full
experience. Sparsely mapped fields grade Medium/Low and get reduced automation. Fields with no
usable OSM surface grade Unavailable and fall back to conservative guidance — the app never
fabricates a route it cannot support.
