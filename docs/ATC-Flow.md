# Real-World ATC Flow (Gate to Gate)

This document enumerates the controller/pilot exchanges a commercial IFR flight
works through, in order, from **clearance delivery at the departure gate** to
**shutdown at the arrival gate** — and maps each to how IFATC Companion produces
it and to Infinite Flight (IF) Connect/ATC capabilities.

The goal is to mimic **real-world ATC** as closely as a deterministic, offline
companion can, using IF facilities where they align and going beyond IF's
built-in ATC where real procedures are richer. Nothing here uses an LLM; every
call is a template rendered from telemetry + the flight plan.

## How automation is driven

The companion uses a **hybrid** model: **you drive your own pilot calls**, and the
**controller's position-based calls play automatically**. This holds in both live
mode and Mock Mode.

**You drive (ATC-tab buttons / push-to-talk):**

- The pre-departure ground sequence — **clearance → pushback → engine start →
  taxi → ready** — one button per call.
- **Read backs** after every controller instruction, and **check-ins** when you
  switch to a new facility. These are always manual; the flow does not wait for
  them, just as real controllers keep working as you reach each position.

**The controller does automatically (position / telemetry driven):**

- The flight plan is read from IF (`aircraft/0/flightplan`) and parsed for the
  departure, destination and enroute fixes (`IFFlightPlanParser`).
- Approaching the departure runway on the taxi, **Ground automatically hands the
  pilot to Tower to *monitor*** ("monitor Tower on …") — no check-in required (see
  step 5).
- Nearing the runway while monitoring Tower — at the **same lead distance the
  automatic runway-crossing clearance uses** — **Tower automatically issues "line up
  and wait"** ("runway *XX*, line up and wait") so the aircraft can make a **rolling
  line-up** rather than stopping short first. No "ready" report or check-in required.
  Skipped only when the aircraft is already on the runway (taxied straight on), in
  which case the takeoff is cleared directly.
- The **takeoff clearance is issued automatically once the aircraft is lined up**
  on the assigned runway (`RunwayLineupDetector`: on the ground, low speed,
  heading aligned with the runway) — Tower does not wait for a prompt.
- **Facility hand-offs are issued automatically** whenever control passes between
  facilities ("contact Departure/Center/Approach/Tower/Ground on …").
- **Departure works the climb to the TRACON ceiling** (default FL180, configurable)
  then **hands off to Center passing that altitude**; Center then clears to cruise.
- Descent, approach, landing and taxi-in advance from telemetry as well.

**Manual frequency tuning.** The ATC tab has a **Tune Frequency** card with one
button per controller — **Clearance, Ground, Tower, Departure, Center, Approach,**
and **Ramp** (parking). Tap a controller to switch to its frequency, then tap
**Check In** to call it up and get its instruction. The same Ground/Tower button
serves both the departure and the arrival visit (it advances to whichever call lies
ahead).

How tuning interacts with the automatic flow depends on the mode:

- **Live mode (connected to Infinite Flight).** The controller's position-based
  calls and facility hand-offs **still fire automatically from telemetry** even
  while you tune by hand. A hand-off only prompts *"contact Departure on 124.3"* —
  it then **waits** for you to tune that frequency and check in before the new
  controller gives its instruction. Calls that stay on the same frequency (the
  takeoff clearance after line-up, *descend via the STAR* at top of descent, the
  cleared-approach, *exit the runway*) play on their own. Tuning the controller you
  were just handed to never produces a redundant "contact …" for a frequency you're
  already on. This is the recommended way to fly.
- **Mock Mode.** There is no live position telemetry, so tuning a frequency
  advances the conversation only on a button press — each call waits for you to tune
  the next frequency. (Re-tap **Center** to walk climb → cruise → descent; a button
  dims once that controller has no further call.)

In **Mock Mode** there is no live position telemetry, so use the Tune Frequency
buttons to drive the flight forward after you report *ready for departure*. Use
**Clear Flight** (top-left of the ATC tab) to wipe the conversation and start a new
flight from the gate; your settings and flight plan are kept.

---

## 1. Departure

| # | Phase | Facility | Controller call (real ATC) | Companion source | IF alignment |
|---|-------|----------|----------------------------|------------------|--------------|
| 1 | At gate | **Clearance Delivery** | "Cleared to *dest* via *SID/route*, climb via SID except maintain *initial alt*, expect *cruise* 10 min after departure, departure frequency *freq*, squawk *code*." | `clearance(…)` | IF Clearance/Ground; squawk/altitude exist |
| 2 | At gate | **Ground** | "Push back approved." | `pushbackApproved` | IF pushback |
| 3 | At gate | **Ground** | "Start up approved." (often pilot's discretion) | `startupApproved` | n/a in IF (courtesy) |
| 4 | Taxi out | **Ground** | "Taxi to runway *XX* via *taxiways*, hold short *…*. Contact Tower when ready." | `taxiToRunway` | IF taxi/hold-short |
| 5 | Approaching rwy | **Ground → Tower** | "Monitor Tower on *freq*." | `monitorTower(cs:frequency:)` | IF hand-off |
| 6 | Nearing the runway | **Tower** | Approaching the runway (crossing-clearance lead distance), "runway *XX*, line up and wait" (`lineUpAndWait`) is issued automatically, so the aircraft can roll straight on. (Optional earlier check-in → "you're number one for departure" (`numberOneForTakeoff`, **no** takeoff clearance); the report-ready button reaches the same line-up-and-wait.) | `lineUpAndWait` / `numberOneForTakeoff` | IF LUAW |
| 7 | Lined up | **Tower** | "Wind *…*, runway *XX*, cleared for takeoff, fly heading *XXX* / runway heading, climb and maintain *initial alt*." | `clearedForTakeoff(departureHeading:…)` | IF takeoff clearance (+ real-world departure instructions) |

The takeoff clearance fires automatically once the aircraft is on the runway —
immediately if it is already rolling, otherwise a few seconds after it settles
**lined up and stopped** on the centerline. The "direct …" fix in the Departure
climb (step 9) is the next filed fix **ahead** of the aircraft, never a runway-end
fix it has already passed. Center's first call after the Departure hand-off (step
11) leads with **"radar contact"** before the climb to the cruise level.
| 8 | Airborne | **Tower → Departure** | "Contact Departure on *freq*." | `handoff(from:to:)` | IF hand-off |
| 9 | Initial climb | **Departure (TRACON)** | "Radar contact, climb and maintain *FL180*, resume own navigation direct *fix*." | `departureClimb` | IF Departure; vectors/own-nav are real-world |
| 10 | Passing FL180 | **Departure → Center** | "Contact Center on *freq*." | `handoff(from:to:)` | IF hand-off |
| 11 | Climb | **Center (ARTCC)** | "Climb and maintain *cruise*." | `climbMaintain` | IF Center |

The **initial climb altitude** (default 5,000 ft) and the **TRACON ceiling**
(default FL180) are configurable. The **departure heading** is the bearing from
the aircraft's position on the runway (the live on-ground position when telemetry
is available, otherwise the departure field reference) to the first fix of the
departure — the SID's first published fix when a SID is filed, otherwise the next
filed fix after the runway. It is airport-agnostic and never uses the bearing to
the destination. When no such fix can be located the heading is unknown and the
clearance says "fly runway heading"; the heading is likewise spoken as "fly runway
heading" whenever it lands within 10° of the runway heading.

**Every assigned heading is magnetic.** The pilot flies a magnetic heading bug, so
every number the app speaks as a heading has to be in that frame. Most already are
by construction: the approach intercept is the landing runway's course ±30°, and the
go-around crosswind leg is the runway heading ±90°, both derived from the runway
number, which *is* magnetic. The two that come from great-circle geometry are not —
the departure heading above (a bearing to the first fix) and the weather-deviation
vectors along the mint line — so both are converted through `HeadingSolver` before
they are spoken, using the variation the sim itself implies (the difference between
the true and magnetic headings it reports for the same nose). Leaving the departure
heading in the true frame also skewed the "within 10° of the runway heading" test
above, so it decided "fly runway heading" against a number in the wrong frame. Where
the sim exposes no true heading there is nothing to measure and the raw bearing is
assigned, as before. The mint-line vectors additionally carry a wind correction,
because they ask the aircraft to *track* a drawn path rather than simply point
somewhere — see [Weather.md](Weather.md).

One piece of geometry sits on the other side of the same seam: the approach
intercept works out which side of the extended centerline the aircraft is on, and
that centerline is laid out with `Geo.destination`, which steers in true degrees
from a final course that is magnetic. It is now converted before the geometry runs
and the answer converted back, so the centerline is drawn where it really is — at
the 20 NM gate a degree of variation is ~0.35 NM of error, enough to pick the wrong
side (and so a 30° turn the wrong way) for an aircraft close to the centerline
somewhere with real declination.

**Monitor Tower (step 5).** As the aircraft nears the departure runway on the taxi,
Ground **automatically** hands it to Tower to *monitor* — "monitor Tower on *freq*" —
mirroring the real-world red "MONITOR TOWER ON …" sign by the yellow checkered line
short of the runway. Reading it back (which auto-tunes to Tower when *Auto-tune on
hand-off* is on) is "monitor Tower on *freq*"; **no check-in is required**. As the
aircraft then **nears the runway** — at the **same lead distance the automatic
runway-crossing clearance uses** (`holdIssueMeters`, surfaced as the coordinator's
`approachingRunwayLineup`) — Tower **automatically issues "runway *XX*, line up and wait"**
(`autoAdvanceMonitoringTower` → `.lineUpWait`), so the aircraft can make a **rolling
line-up** rather than stopping short first — live mode only, and only if the aircraft
isn't already on the runway. The takeoff clearance still fires automatically once the
aircraft is lined up (step 7). If the pilot *does* check in on Tower — typically well
before the runway — Tower replies **only** with "you're number one for departure"
(`numberOneForTakeoff`) and **does not** issue a takeoff clearance; the clearance still
comes only once the aircraft is lined up. **The taxi map is kept visible through the
monitor-Tower hand-off and the final roll to the runway** — so the pilot can still see the
route to the hold-short *and*, crucially, so the surface keeps tracking the aircraft up to
the `approachingRunwayLineup` cue the automatic line-up-and-wait depends on. It is retired
when the pilot **reads back the line-up-and-wait (or the takeoff clearance)**, whichever
comes first (`retireDepartureTaxiMapAfterReadback`), with an airborne backstop; it is *not*
torn down the moment the radio tunes to Tower. The trigger point for the monitor-Tower hand-off
is **derived from the
taxi route**, not read from the map data: OpenStreetMap has no distinct feature for the
monitor-tower line/sign — the only runway-proximity marking it maps is the
`aeroway=holding_position` (hold-short) line *at* the runway — so the hand-off fires once
the aircraft comes within `OSMSurface.monitorTowerLeadMeters` of the route's runway
hold-short. Runs in both live mode and Mock Mode.

## 2. Enroute / Cruise

| # | Phase | Facility | Controller call | Companion source |
|---|-------|----------|-----------------|------------------|
| 12 | Cruise | **Center** | "*callsign*, Center, radar contact." (sector check-ins) | `radarContact` |
| 13 | Cruise | **Center** | Step climbs / "request higher/lower", ride reports, weather | `climbMaintain`, `descendPilotsDiscretion`, ride/weather replies |

Real ATC hands off between many Center sectors enroute; the companion models this
as periodic Center check-ins (additional sector frequencies are not simulated).

## 3. Arrival

| # | Phase | Facility | Controller call | Companion source | IF alignment |
|---|-------|----------|-----------------|------------------|--------------|
| 14 | Top of descent | **Center** | "Descend via the *STAR* arrival, maintain *alt*" (filed STAR) or "descend and maintain *alt*." | `descendViaArrival` / `descendMaintain` | IF descent |
| 15 | Descending | **Center → Approach** | "Contact Approach on *freq*." | `handoff(from:to:)` | IF hand-off |
| 16 | Approach | **Approach (TRACON)** | "Descend and maintain 3,000, expect *ILS/GPS/Visual* runway *XX*." | `descendExpectApproach` | IF Approach |
| 17 | Vectors | **Approach** | "Fly heading *XXX*, vectors for the *approach*." | `requestVectors` reply | IF vectors |
| 18 | Established | **Approach** | "Cleared *ILS/GPS/Visual* runway *XX* approach." | `clearedApproach` | IF approach clearance |
| 19 | Short final | **Approach → Tower** | "Contact Tower on *freq*." | `handoff(from:to:)` | IF hand-off |
| 20 | Final | **Tower** | "Wind *…*, runway *XX*, cleared to land." | `clearedToLand` | IF landing clearance |
| 21 | Rollout | **Tower** | "Exit the runway when able, contact Ground on *freq* once on the taxiway." | `exitRunwayContactGround` | IF rollout |
| 22 | Taxi in | **Ground** | "Taxi to gate *X* via *taxiways*." (or "taxi to parking" with no gate) | `arrivalTaxi` / `taxiToParking` | IF taxi |
| 23 | At gate | **Ground** | "Welcome to *city*, good day." (shutdown) | `welcomeArrival` | courtesy |

When an arrival gate is entered, the **taxi-in clearance routes to the gate** over the
OpenStreetMap airport surface, the same way the departure taxi routes to the runway.
**Both airports' surfaces are pre-cached at flight load** (as soon as the flight plan is
known), and the destination surface is loaded when the aircraft exits the runway, so on
landing all that remains is to calculate the best route to the gate from the current
position. On the automatic path **Ground waits for that route** (re-checking each
telemetry tick, up to a few seconds) rather than giving a generic "taxi to parking" — it
is fine for ATC to take a moment to respond. Only if the airport data genuinely can't be
fetched at all does it time out to a generic clearance (where waiting longer wouldn't
produce a route anyway). On a pilot-driven check-in the clearance is immediate — routed
if ready, otherwise generic and **superseded** by the detailed gate route the moment the
surface loads. With no gate entered there is nothing to route to, so a plain "taxi to
parking" stands alone (no map). The taxi map's geometry is **cleared each time the map is
removed**, so the arrival map never briefly shows the departure field while the
destination surface loads.

On arrival the simulated **Ramp** taxi-in is staged so the calls never all fire at
once: *"proceed to gate B44 via the ramp"* when you contact Ramp, then *"monitor
ramp to the gate"* as the aircraft slows to a stop, then the *"Flight complete"*
block-in once it is actually parked. **The arrival taxi map is kept visible after the
Ramp-frequency switch** — so the pilot can still see the route in to the gate — and is
retired only at the detected **end of flight** (the `.parked` transition once actually
stopped at the gate), not the moment Ramp is contacted. The flight ends **only when the
aircraft is stopped with the parking brake set AND tuned to the Ramp frequency** — the
Ramp-frequency requirement is the map-independent gate, so a parking brake set out on
a taxiway (before contacting Ramp) never ends the flight, with or without accurate
taxi-map data. When the taxi map also resolved the gate position, the aircraft must
additionally be within ~80 m of it. The **arrival gate** is taken from the
manual-override **Gate** field (Infinite Flight does not expose it); when no gate is
entered the calls say "the gate".

The **cleared-approach call (step 18)** is issued once the aircraft is *established*
— the autopilot approach mode (**APPR**) is engaged, or it is lined up on final with
the runway — read from Infinite Flight telemetry (`approachMode`, falling back to a
heading/altitude/descent-rate proxy). This guarantees the approach clearance is given
**before** the Tower hand-off. The **top-of-descent altitude** is an intermediate
level clearly below cruise (so the descent clearance is never contradictory), and
Approach then steps the aircraft down to ~3,000 ft on the intercept.

### Go around / missed approach

While inbound to land on **Tower** (cleared the approach / contacting Tower, or
cleared to land, and still airborne) a **Go Around** button appears. Tapping it flies
the missed approach:

1. **Pilot → Tower** — *"Tower, *callsign*, going around."* (`PilotResponseEngine.goAround`)
2. **Tower** — *"*callsign*, go around, turn left heading *XXX*, climb and maintain
   *pattern alt*, make left traffic runway *XX*, contact Approach on *freq*."*
   (`PhraseologyEngine.goAround`). The heading is a **90° crosswind leg** off the
   landing runway (left traffic = runway − 90°); the pattern is **left traffic for the
   same runway**. The read-back echoes every element (heading, climb, traffic
   direction, runway) and **tunes to Approach** once read back.
3. **Pilot → Approach** — on the next check-in, **Approach** replies *"maintain
   *pattern alt*, continue inbound, expect *ILS/GPS/Visual* runway *XX* approach."*
   (`PhraseologyEngine.continueInbound`), and the conversation is **rewound to the
   Approach state**, so the cleared-approach → Tower → cleared-to-land sequence
   (steps 18–20) **replays exactly as the first time**, driven by the same
   established-on-final / APPR detection. A pilot can go around again on the next
   approach.

The **pattern altitude** is **3,000 ft above the field**, using the same
elevation-aware math as the approach descent (`approachDefaultAltitude` — live
MSL − AGL near the field, rounded up to the next thousand), so it clears the ground at
a high field (9,000 ft at Denver) rather than a sub-surface 3,000 ft. While the
go-around is being flown back around the pattern the automatic flow holds
(`goAroundInProgress`); the pilot drives the re-establishing Approach check-in.

---

## Facility ↔ frequency mapping

Hand-offs are issued by the facility you are leaving and name the next facility +
frequency. Defaults (deterministic; overridable later):

| Facility | Frequency |
|----------|-----------|
| Clearance / Ground | 121.800 |
| Tower | 118.300 |
| Departure | 124.300 |
| Center | 132.450 |
| Approach | 119.700 |

## Notes on real ATC vs Infinite Flight

- IF's built-in ATC exposes Ground, Tower, Approach, Departure, Center, ATIS and
  UNICOM. The companion adds **Clearance Delivery** and the **real-world
  departure instructions** (initial heading + climb in the takeoff clearance,
  "resume own navigation direct *fix*") that IF does not phrase explicitly.
- TRACON/Center hand-off altitudes vary by facility in the real world; FL180 is a
  reasonable, configurable default rather than a fixed rule.
- When a **human controller** is detected on the frequency, the companion stands
  by and stops generating calls (it never impersonates live ATC).
