# Runway‑Crossing Automation

> **Simulation only — not for real‑world aviation.** All runway‑crossing instructions are
> simulated ATC for flight‑simulation training and entertainment. Surface data © OpenStreetMap
> contributors (ODbL 1.0). See [OpenStreetMapLicensing.md](OpenStreetMapLicensing.md).

When a calculated taxi route crosses a runway, IFATC Companion runs a simulated Ground
runway‑crossing workflow. Crossings are handled **independently** and issued as **separate**
Ground clearances that require a pilot read‑back before the crossing is authorized.

## Workflow states

`RunwayCrossingState` (driven by `AirportSurfaceCoordinator`) advances through:

`no crossing pending → crossing detected ahead → approaching holding position → hold‑short
instruction issued → holding short → crossing clearance ready → crossing clearance issued →
awaiting pilot read‑back → crossing authorized → crossing in progress → runway centerline
crossed → runway vacated → taxi resumed`

with two side states: `unauthorized crossing detected` and `low‑confidence crossing data`.

The initial Ground taxi clearance names the **first** runway crossing in the route as the
clearance limit ("taxi to runway 36 via A, C, hold short runway 9-27"). The crossing is then
cleared **automatically**, a generous distance before the runway, without repeating the
hold‑short — so the pilot is never actually stopped there (the runway may well be clear for
them). Hold‑short and crossing instructions name **both directions** of the physical runway
("hold short runway 9-27", spoken "hold short runway niner two seven").

## Per‑crossing sequence

For each planned crossing the coordinator:

1. identifies the mapped (or inferred) hold‑short location on the route;
2. monitors aircraft distance, heading, speed, and route progress each tick;
3. prepares the crossing early enough for the pilot to respond;
4. issues a **separate** Ground runway‑crossing clearance automatically, a **generous distance**
   before the runway threshold (far enough back that a ~25 kt taxi has time to read it back
   before the threshold) — **regardless of data confidence**; only when the *Automatic
   runway‑crossing calls* override is off does it instead give a **hold‑short** the pilot
   answers with **Request Crossing** first;
5. requires the pilot to tap **Read Back** — the crossing is **not** authorized until the
   read‑back is complete (the read‑back is added to the transcript);
6. tracks the aircraft entering and leaving the runway corridor;
7. marks the crossing complete only after the runway area is cleared;
8. resumes the remaining taxi route; and
9. handles multiple crossings independently.

The crossing clearance includes the runway identifier, the taxiway/intersection name when known,
and a continuation where appropriate; the read‑back must contain the runway identifier.

## Automatic clearance vs. manual

- **Automatic runway‑crossing calls on (default):** the crossing is **always** cleared
  automatically, **regardless of OpenStreetMap data confidence**. Ground issues the crossing
  clearance a generous distance before the threshold (no redundant hold‑short, never a stop),
  then read‑back → authorized. The companion never holds the aircraft short or stops it at a
  crossing — the runway may well be clear for the pilot.
- **Automatic runway‑crossing calls off** (Settings → Data Sources): the conservative manual
  path — Ground holds the pilot short and the pilot must **Request Crossing** before the
  clearance is issued (low‑confidence data uses cautious hold‑short language).
- **Request Crossing** issues the clearance immediately when tapped — it does not wait on the
  aircraft settling exactly at the mapped hold point (the OSM hold point rarely lines up with the
  simulator scenery), and **Hold Position** suppresses the automatic clearance until the pilot
  requests the crossing.

## Early / unauthorized crossing

**Manual mode only** (automatic runway‑crossing calls off). With automatic calls on the
companion always clears the crossing well in advance, so it never warns or stops the aircraft.
In manual mode, if the aircraft begins entering a runway corridor **before** a clearance was
issued or the read‑back completed, the app issues a simulated warning — **hold position** if the
aircraft has not substantially entered, or **stop immediately** if it is already entering. To avoid false
alarms from position noise it requires positive movement toward the runway, a heading consistent
with the planned crossing, meaningful corridor penetration, and **sustained** detection (a
debounce), not a single sample. It logs the crossing state, authorization state, position,
speed, heading, hold‑point distance, and geometry confidence to diagnostics.

## Response actions

The taxi map surfaces crossing responses: **Read Back**, **Say Again**, **Unable**, **Hold
Position**, **Request Crossing**, **Request Alternate Route**. The taxi map stays visible and
highlights the active crossing throughout the sequence.

## Map lifecycle

- The compact taxi map animates into the ATC view **after** the pilot reads back the taxi
  clearance, auto‑zooms to the route, shows the aircraft/heading, emphasizes the route,
  highlights the destination and upcoming crossings, and shows the next instruction.
- It stays visible during taxi, crossings, and holding short; it can be expanded full‑screen.
- It **disappears** when Ground hands the aircraft to Tower, and **reappears** after landing when
  Ground issues taxi‑to‑gate instructions; it disappears again once the aircraft enters the
  ramp/gate phase.

## Mock Mode

Mock Mode includes a complete offline taxi scenario (no OpenStreetMap network access): a gate
start, named taxiways, one runway crossing, a departure runway hold‑short, and mapped holds and
crossing points — plus an arrival scenario (runway exit → taxi‑to‑gate → ramp/gate). It
exercises the full sequence end‑to‑end.
