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
  `9L` matches an OSM‑tagged `09L`. A departure **never crosses its own runway, and never taxis
  the long way around it** — see [Holding short on the aircraft's side](#holding-short-on-the-aircrafts-side);
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

### Holding short on the aircraft's side

A departure hold belongs on the side of the runway the aircraft is already on. OSM often tags
holds for only one end, or only one side, of a runway, and the router used to take whichever
hold carried the assigned ident — which produced two bad routes:

- the hold sat **across** the runway and the only path to it ran over the departure runway; or
- the hold sat across the runway but was reachable **around** a runway end, so the route taxied
  the length of the field and back to reach a hold a few hundred metres away across the pavement.

Goal candidates are therefore split by **which side of the assigned runway's centerline** they
lie on (relative to the aircraft's own side). Candidates on the aircraft's side keep their
existing priority and are tried first. When something is stranded on the far side, the router
inserts **hold‑short‑at‑a‑crossing** goals ahead of it: for each taxiway that crosses the
assigned runway **toward the assigned end** (nearest that threshold first, never past midfield —
holding beyond it would leave too little runway to depart from), the taxiway's near‑side node
becomes the goal and the route then rolls on out along that taxiway and stops a short distance
before the runway centerline. **That crossing threshold is the departure hold.** The crossing
taxiway is named in the spoken sequence (the aircraft taxis onto it) but is never taxied across,
so it stays out of the route's node/edge path and the departure runway is never reported as a
mid‑route crossing. Far‑side holds remain in the list as a last resort, so a field where the
aircraft genuinely must taxi around still routes.

When the route does end up crossing its own runway anyway (nothing nearer was reachable), it is
still cut back to the near‑side hold‑short of that crossing, and the spoken taxiway sequence
drops the far‑side taxiways that are no longer taxied.

The route is left exactly as before when the assigned runway end is unknown, when the aircraft
sits essentially on the runway centerline (its side can't be told), or when no candidate is on
the far side — which is the normal, well‑mapped case.

### Not shortest‑distance

Routing is **never** chosen on distance alone. It strongly penalizes or prohibits:

- unnecessary runway crossings; active‑runway back‑taxi; unnecessary runway occupancy;
- disconnected jumps; inferred apron shortcuts; routes through parking stands;
- **gate lead‑ins that cut through a building / terminal** (see below);
- closed / non‑operational taxiways; taxiways incompatible with the aircraft (from OSM `width`);
- sharp turns unsuitable for the aircraft; low‑confidence / unnamed segments;
- entry at the wrong runway end.

It prefers named taxiways, connected geometry, full‑length runway entry, fewer runway crossings,
realistic turn geometry, high‑confidence features, and aircraft‑compatible paths. It also
**starts in the aircraft's direction of travel** and **keeps the number of turns down** — see
below.

### Aircraft heading (no 180° U‑turn in place)

When the aircraft is under way (taxiing, not parked or stopped), its heading is fed to the
router so the route **sets off in the direction the aircraft is already pointing** rather than
opening with a 180° pivot where it sits. If the destination lies behind the aircraft the route
still reaches it — by taxiing forward and **turning around farther along** (at a junction / via a
parallel taxiway), exactly as a real aircraft would, instead of reversing on the spot. Concretely,
the endpoint of the taxiway under the aircraft that lies *behind* it is dropped as a starting
point, so A\* leaves toward the endpoint ahead; a genuinely unavoidable reversal (e.g. off a
dead‑end exit) still routes, just at a high cost. The heading is **ignored while parked at a
stand** — the parked orientation isn't the taxi direction — and when it is unknown, routing is
unchanged.

### Fewer, larger turns (not a stepped staircase)

Distance alone doesn't distinguish a route that "steps down" through a series of small
alternating turns (left, right, left, right …) from one that reaches the same place with a single
left and a single right. Every ordinary turn at a junction therefore carries a small cost, so the
router **prefers the route with fewer turns** when the distances are close — trading a little
extra taxi distance for a materially simpler, easier‑to‑fly route. Sharp (>120°) and hard (>95°)
turns keep their larger existing penalties on top.

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

### Attaching to a taxiway edge, not just its nodes

A stand attaches to the nearest routable **node** within a fixed radius. That works where OSM maps a
lane up to each stand, but an apron taxilane is often drawn as one long, sparsely‑noded way whose
**line** runs right past a row of stands while its nearest *node* is hundreds of metres away — so a
stand can sit well within taxi distance of a taxiway yet have no node to snap to (e.g. KDEN's inner
Concourse‑B gates sit ~260 m from the nearest node on the "Green" apron taxilane, whose centreline
passes ~140 m away as a single 800 m+ segment). A node‑only snap left those stands **orphaned** —
no connector, unroutable — so an arrival to one silently ended at a different stand.

When no node is in range (or the only node's lead‑in would cross a terminal but an edge lead‑in
stays clear), the stand instead attaches to the nearest point **projected onto a taxiway edge**,
splitting that edge to insert the junction. The building‑avoidance and reversal scoring above apply
to the projected lead‑in exactly as they do to a node, so an edge whose lead‑in cuts through a
concourse is still passed over for a clear one. Stands that already have a clear node in range keep
their exact previous attachment, so well‑mapped fields are unchanged.

### Aircraft classification

Infinite Flight aircraft type is used when available (`AircraftSizeClass.classify`); otherwise
the aircraft is classified conservatively by size (default **Medium**). The class biases routing
away from narrow taxilanes / tight turns where OSM tags provide enough information.

## Confidence model

Each **dataset** and each calculated **route** is graded:

| Level | Meaning | Behavior |
|---|---|---|
| **High** | connected geometry, taxiway names, clear runways, valid path to the correct runway end, reliable holds, clear crossing geometry, clean aircraft snap | full detailed route + automatic runway‑crossing workflow |
| **Medium** | some inferred holds, minor missing names, limited apron detail, otherwise connected | show route + instructions; crossings still cleared automatically |
| **Low** | disconnected geometry, missing names, uncertain crossings, inferred connectors, questionable aircraft compatibility, visible mismatch | show limited geometry where useful; crossings still cleared automatically |
| **Unavailable** | no credible connected route | disable detailed routing; conservative fallback |

Runway crossings themselves are **always** cleared automatically regardless of confidence (the
companion never holds the pilot short or stops them at a crossing); confidence only affects the
detail of the taxi routing and phraseology. Turning off *Automatic runway‑crossing calls*
(Settings) switches every crossing to the manual Request‑Crossing path — see
[RunwayCrossingAutomation.md](RunwayCrossingAutomation.md).

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

Whenever a route is recalculated — the pilot tapping **Recalculate** / **Request New Taxi
Instructions** on the map, or an automatic off‑route recalculation — and it resolves to a
**materially different route**, Ground issues a **fresh taxi clearance with its own read‑back**
(never a silent swap): the new runway/gate, taxiway sequence, and hold‑short are read out and
re‑armed for the pilot's acknowledgement, exactly like the initial clearance. A recalculation
that reproduces the **same** instruction (same taxiway sequence, destination, and hold‑short)
stays silent, so recalculating never repeats an identical clearance. The comparison is on the
spoken instruction, so a route whose geometry shifts slightly but whose taxiways/hold‑short are
unchanged is not re‑issued. An automatic recalculation therefore keeps the ATC exchange honest:
it re‑plans from the current position and, when that changes the instruction, tells the pilot.

## Automatic gate assignment (optional, off by default)

A pilot who leaves **Dep Gate** / **Arr Gate** blank has nowhere for the taxi route to start from
or end at, so the route falls back to the nearest reachable stand. The **Auto‑assign gates**
toggle (Settings → Data Sources, `AppSettings.autoAssignGates`, off by default) instead fills a
blank field with a named stand taken from the airport's own OSM extract, so the clearance and the
map name a real gate.

### The gate you are parked at wins

When the aircraft is **already sitting on a mapped stand** at the departure field, nothing is
chosen: that stand *is* the gate, read straight off the aircraft's position. It beats every other
signal, including an airline match — an `operator` tag is a guess about where the flight belongs,
whereas the position is a fact about where it is.

The position is supplied only when telemetry has the aircraft **stopped on the ground**
(`AppModel.aircraftIsParked`), so a non-nil position always means "parked here". That test is
deliberately weaker than `isParkedAtGate`, which ends the flight and so wants the parking brake and
the Ramp frequency behind it: naming the stand you are on needs no more than being stopped on it,
and requiring the brake would leave the gate unnamed for a pilot idling at their stand with it
released. A *missing* ground flag reads as not-on-the-ground — the opposite default to
`isParkedAtGate` — because an airborne position must never name a stand.

The nearest assignable stand within `GateAssigner.parkedAtStandMeters` (80 m, the same radius the
arrival completion uses, and for the same reason: an OSM stand is one node, mapped anywhere from the
jet-bridge head to the nose-wheel stop line, against different scenery again) wins. Since the nearest
one wins, at a packed concourse the radius only decides whether the aircraft is on a stand at all.
The two hard exclusions still apply — parking on a de-icing pad or an unidentified stand names
neither, because neither can be said in a clearance.

Only the **departure** gate is read this way. The arrival gate is picked while the aircraft is still
at the origin or enroute, where its position says nothing about the stand it will end up on — and on
a there-and-back leg, where origin and destination are the same field, reading it would hand back the
stand the flight is leaving.

Because the gate is first filled at flight load — usually before any telemetry has arrived — a gate
that had to be *chosen* is **upgraded** to the parked one the moment the aircraft reports itself
stopped on the ground. `AppModel.updateAutoGateOnParkedChange` hooks that transition rather than the
telemetry tick, so it costs one attempt per stop instead of one per fix, and `GateAssigner.mayUpgrade`
allows the rewrite only when the incoming gate is position-derived and the outgoing one was the app's
own guess for the same airport. This is the *only* case in which the app rewrites its own gate at the
same airport: a position-derived gate is never re-picked (nudging the aircraft onto another stand
doesn't move the gate — clear the field to have it read again), and a second *chosen* gate would just
re-roll the dice on the pilot.

### Choosing one when the position can't say

`GateAssigner` (`AirportSurface/GateAssignment.swift`) makes the choice from the stand tags the
normalizer already keeps on every `SurfaceParking`:

| OSM tag | Used for |
| --- | --- |
| `aircraft:type` (also `aircraft`, `aircraft:size`) | The stand's size, from an airframe (`A320`, `B738;B77W`), a size band (`heavy`, `wide_body`), or an ICAO reference code (`code_c`). A stand that can't take the aircraft is a last resort; among those that can, the snuggest fit wins. `helicopter` marks a rotorcraft pad. |
| `operator`, `operator:en`, `operator:short`, `network`, `owner` | The airline working the stand. Matched against the callsign's designator via `StandOperators.brandNames` (OSM says "British Airways" where the radio says "Speedbird"), the resolved telephony name, and the plan's airline. |
| `access` | `no` / `private` — used only when the field offers nothing else. |
| `ref` / `name` | The identifier the controller says. **Required**: a stand with no identifier can't be named in a clearance, so it is never assigned. |
| `name`, `description`, `parking_position`, `usage` (purpose tags only) | Cargo positions — matched to freight flights and only to freight flights — and de‑icing / maintenance / hangar positions, which are never assigned. Operator text is searched for cargo words but never for purpose words, so an airport authority's name never disqualifies a real stand. |

Everything except "must be named" and "not a service position" is a *soft* preference expressed as
a penalty, so a sparsely tagged field still gets a stand rather than none. Where the tags say
nothing — the common case — the pick is random among the plausible stands, which is the point: a
real stand at the real airport that the router can reach. Nothing here is authoritative; a real
gate assignment comes from the airline, not from a map.

### Only when the pilot left it blank

The assignment writes the visible field, so it has to be able to tell its own value apart from a
pilot's. It stamps what it wrote as `ICAO:GATE` — or `ICAO:GATE:P` for a gate read off the aircraft's
position (`AppSettings.autoAssignedDepartureGate` / `…ArrivalGate`; a two-part marker written before
the flag existed decodes as a chosen gate). `GateAssigner.mayAssign` then allows a write only when the field is blank, or
still holds the app's own stamp **for a different airport** (the last flight's gate is stale). A
gate the pilot typed — or typed over an automatic one — is never touched. `AppModel` adds two more
conditions: the gate the active plan is already flying must be the app's too (a reloaded saved
flight keeps its gates; Mock Mode's own default gate counts as blank so the demo exercises the
feature), and a departure gate is off limits once the taxi has begun, since the gate is then where
the aircraft actually is. Switching the toggle off withdraws only the gates the app filled in.

The assignment runs where the surfaces are prefetched — whenever the flight's endpoints are
established — again on every gate-field edit (`applyManualGates`), so a field the pilot has just
*cleared* is blank again and gets filled, and again when the aircraft first reports itself parked (the
upgrade above). It reads its extract through
`AirportSurfaceCoordinator.surfaceModel(icao:reference:)`, which never disturbs an active taxi and
coalesces with the prefetch already in flight, so it costs no extra Overpass request. Every
assignment is logged with its rationale, so Diagnostics explains why a gate was chosen.

The stamps are deliberately **not** part of a saved flight's `FlightOverrides` (adding a key there
would break decoding of snapshots written by earlier builds). A reloaded saved flight therefore
comes back with its gates and a stamp that no longer matches them, which reads as "the pilot's" —
the conservative direction, and the behavior a saved flight wants anyway.

## Manual overrides

The pilot can override the departure/arrival runway, gates, runway entry, automatic crossing
calls, and data refresh (Settings and the taxi map). The two Settings toggles for the surface —
**Automatic runway‑crossing calls** (on by default) and **Auto‑recalculate when off route** (off
by default) — plus **Auto‑assign gates** (off by default, above) are stored in `AppSettings`
(`taxiAutoCrossingCalls` / `taxiAutoRecalculate` / `autoAssignGates`), so a
change sticks across app launches; `AppModel` observes them and applies each to
`AirportSurfaceCoordinator`, whose own properties are session state.

Infinite Flight Connect provides only runtime aircraft state (position, heading, groundspeed, on‑ground, type, airports, assigned
runway/facility) — never taxiway geometry, names, holds, gates, crossing geometry, or a
preferred route.

## Supported vs. unsupported airports

Well‑mapped fields with named, connected taxiways and mapped holds grade High and get the full
experience. Sparsely mapped fields grade Medium/Low and get reduced automation. Fields with no
usable OSM surface grade Unavailable and fall back to conservative guidance — the app never
fabricates a route it cannot support.
