# Taxi Routing & Confidence

> **Simulation only — not for real‑world aviation.** Surface data © OpenStreetMap
> contributors (ODbL 1.0). See [OpenStreetMapLicensing.md](OpenStreetMapLicensing.md).

IFATC Companion calculates **best‑effort** taxi routes over the OpenStreetMap‑derived surface
graph (see [AirportSurfaceData.md](AirportSurfaceData.md)).

## Routing engine

`TaxiRouteEngine` uses **A\*** with a great‑circle heuristic. It supports:

- **departure** routing from a gate / parking / ramp position / current aircraft position to the
  assigned runway hold‑short point. The engine resolves an **ordered list of goal candidates**
  for the assigned end — the full‑length runway‑entry node(s), then holding positions for that
  end, then plain taxi nodes near the runway‑end threshold — and routes to the first one the
  aircraft can actually reach, so a single goal node stranded in a disconnected patch of a large
  field's graph (e.g. a far‑end runway entry not wired to the terminal taxiways) no longer fails
  the whole route. Runway‑ident matching is tolerant of leading‑zero padding, so an assigned
  `9L` matches an OSM‑tagged `09L`;
- **arrival** routing from the current aircraft position to a selected gate or parking
  position — the destination surface is warmed early (at the runway exit) so the clearance can
  route to the gate, but the route is **re‑anchored at the aircraft's position at the moment
  taxi is requested**, so it starts under the aircraft rather than back at the runway exit it
  has since taxied clear of;
- recalculation after a deviation;
- alternate runway‑entry selection;
- **full‑length runway departure by default** (intersection departure only when selected or
  necessary).

### Snapping the start onto the graph

The route begins **under the aircraft**, not at the nearest graph node. `TaxiRouteEngine`
projects the start position onto the nearest connected **edge** and seeds A\* from *both* of that
edge's endpoints (each weighted by its along‑edge distance to the projection), then prepends a
short **lead‑in** from the projection up to whichever endpoint the route actually leaves from.
This matters most after landing where a runway has **diagonal high‑speed exits**: the nearest
*node* to an aircraft partway down an exit is often the exit's far end out on the parallel
taxiway, so a plain node snap drew the route starting *a taxiway away* — and, because a nearby
recalculation resolved to the same node, pressing recalculate produced the same displaced route.
Projecting onto the edge instead keeps the start on the exit/taxiway the aircraft is actually on
and lets it track continuously as the aircraft rolls. When the projection lands essentially on an
endpoint (within a few metres) the engine falls back to a plain node snap so a route that really
does begin at a junction stays clean; if an edge snap reaches no goal (a stub disconnected from
the network) it also falls back to the nearest connected node. When the still‑to‑be‑taxied
lead‑in portion crosses a runway, that crossing (and its hold‑short) is preserved — a crossing
already behind the aircraft is not re‑reported.

### Not shortest‑distance

Routing is **never** chosen on distance alone. It strongly penalizes or prohibits:

- unnecessary runway crossings; active‑runway back‑taxi; unnecessary runway occupancy;
- disconnected jumps; inferred apron shortcuts; routes through parking stands;
- **gate lead‑ins that cut through a building / terminal** (see below);
- closed / non‑operational taxiways; taxiways incompatible with the aircraft (from OSM `width`);
- sharp turns unsuitable for the aircraft; low‑confidence / unnamed segments;
- entry at the wrong runway end.

It prefers named taxiways, connected geometry, full‑length runway entry, fewer runway crossings,
realistic turn geometry, high‑confidence features, and aircraft‑compatible paths.

### Building geometry (gate lead‑ins)

A stand connects to the taxi network through a short synthesized **lead‑in connector** (OSM maps
the stand but not the lane that reaches it). Choosing the geometrically nearest taxi node can draw
that connector **straight through a concourse** — on a thin concourse with gates on both sides, the
nearest node is often across the building. To prevent this, the app fetches **building / terminal
footprints** (`building=*` and `aeroway=terminal`) alongside the movement surface and, when
attaching a stand, prefers the nearest taxi node whose lead‑in:

- does **not** cross a building footprint, and
- does not force a near‑reversal back across the ramp (a lead‑in that doubles back is disfavored).

If every reachable node is across a building (a stand fully ringed by a footprint), the connector is
still made — routing never fails for this reason — but it is flagged as crossing a building, which
penalizes it in the router and lowers route confidence (with the note *"gate lead‑in passes through
a building footprint"*). Footprints are **not routable**; they only shape stand attachment.

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
- includes an explicit hold‑short instruction — of the **first runway crossing** in the route
  when the route crosses a runway ("taxi to runway 36 via A, C, hold short runway 9-27"),
  otherwise of the assigned runway itself; hold‑short and crossing instructions name **both
  directions** of the physical runway ("hold short runway 9-27", "cross runway 6R-24L");
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

The cached model carries a **schema version**. When a new feature class is added that older
extracts cannot contain (e.g. building footprints), the version is bumped and any cache written by
an earlier version is treated as not‑fresh and **re‑fetched on next load**, even when it is still
within the time‑based refresh interval — so an already‑cached field that predates building geometry
is refreshed rather than kept routing stands through concourses. An outdated‑schema cache is still
served as a fallback while offline.

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

When a taxi begins **before** its real extract has finished pre‑caching — common for a large
destination like **KMSP**, whose extract takes longer to fetch than a short demo takes to reach
taxi‑in — the synthetic field is shown immediately (so the map and drive are never blocked or
blank), and the real surface is loaded asynchronously and **swapped in** the moment it arrives, as
long as the simulated drive hasn't started yet (swapping mid‑drive would teleport the aircraft, so
the real field is then simply used the next time the demo taxis there). The fetch is coalesced with
the in‑flight pre‑cache, so it never duplicates the request, and once loaded the field is cached for
the rest of the session.

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
