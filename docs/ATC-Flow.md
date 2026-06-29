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

When connected to Infinite Flight (or in Mock Mode), **Automatic ATC** (Settings →
ATC Automation, on by default) advances the flow from live telemetry:

- The flight plan is read from IF (`aircraft/0/flightplan`) and parsed for the
  departure, destination and enroute fixes (`IFFlightPlanParser`).
- The pre-departure ground sequence is paced one step per state update.
- The **takeoff clearance is issued automatically once the aircraft is lined up**
  on the assigned runway (`RunwayLineupDetector`: on the ground, low speed,
  heading aligned with the runway).
- **Facility hand-offs are issued automatically** whenever control passes between
  facilities ("contact Departure/Center/Approach/Tower/Ground on …").
- **Departure works the climb to the TRACON ceiling** (default FL180, configurable)
  then **hands off to Center passing that altitude**; Center then clears to cruise.
- Descent, approach, landing and taxi-in advance from telemetry as well.
- **The pilot reads back each instruction before the flow progresses.** In
  Automatic ATC, every controller call that requires a readback (clearance, taxi,
  line-up, takeoff, climb/descent, approach and landing clearances, exit
  instruction) is followed by the deterministic pilot readback before the next
  section is issued. Courtesy/radar-contact check-ins are acknowledged, not read
  back.

Turn Automatic ATC off to drive the pre-departure flow manually with the ATC-tab
buttons (the original behavior); the pilot readback is then the pilot's own
button/voice action.

---

## 1. Departure

| # | Phase | Facility | Controller call (real ATC) | Companion source | IF alignment |
|---|-------|----------|----------------------------|------------------|--------------|
| 1 | At gate | **Clearance Delivery** | "Cleared to *dest* via *SID/route*, climb via SID except maintain *initial alt*, expect *cruise* 10 min after departure, departure frequency *freq*, squawk *code*." | `clearance(…)` | IF Clearance/Ground; squawk/altitude exist |
| 2 | At gate | **Ground** | "Push back approved." | `pushbackApproved` | IF pushback |
| 3 | At gate | **Ground** | "Start up approved." (often pilot's discretion) | `startupApproved` | n/a in IF (courtesy) |
| 4 | Taxi out | **Ground** | "Taxi to runway *XX* via *taxiways*, hold short *…*." | `taxiToRunway` | IF taxi/hold-short |
| 5 | Approaching rwy | **Ground → Tower** | "Contact Tower on *freq*." | `handoff(from:to:)` | IF hand-off |
| 6 | Holding short | **Tower** | "Runway *XX*, line up and wait." | `lineUpAndWait` | IF LUAW |
| 7 | Lined up | **Tower** | "Wind *…*, runway *XX*, cleared for takeoff, fly heading *XXX* / runway heading, climb and maintain *initial alt*." | `clearedForTakeoff(departureHeading:…)` | IF takeoff clearance (+ real-world departure instructions) |
| 8 | Airborne | **Tower → Departure** | "Contact Departure on *freq*." | `handoff(from:to:)` | IF hand-off |
| 9 | Initial climb | **Departure (TRACON)** | "Radar contact, climb and maintain *FL180*, resume own navigation direct *fix*." | `departureClimb` | IF Departure; vectors/own-nav are real-world |
| 10 | Passing FL180 | **Departure → Center** | "Contact Center on *freq*." | `handoff(from:to:)` | IF hand-off |
| 11 | Climb | **Center (ARTCC)** | "Climb and maintain *cruise*." | `climbMaintain` | IF Center |

The **initial climb altitude** (default 5,000 ft) and the **TRACON ceiling**
(default FL180) are configurable. The **departure heading** is the bearing from
the field to the first filed fix (or the destination), spoken as a heading unless
it is within 10° of the runway heading, in which case "fly runway heading".

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
| 22 | Taxi in | **Ground** | "Taxi to parking via *taxiways*." | `taxiToParking` | IF taxi |
| 23 | At gate | **Ground** | "Welcome to *city*, good day." (shutdown) | `welcomeArrival` | courtesy |

The **cleared-approach call (step 18)** is issued once the aircraft is *established*
— the autopilot approach mode (**APPR**) is engaged, or it is lined up on final with
the runway — read from Infinite Flight telemetry (`approachMode`, falling back to a
heading/altitude/descent-rate proxy). This guarantees the approach clearance is given
**before** the Tower hand-off. The **top-of-descent altitude** is an intermediate
level clearly below cruise (so the descent clearance is never contradictory), and
Approach then steps the aircraft down to ~3,000 ft on the intercept.

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
  by and stops generating calls (it never impersonates live ATC). UNICOM actions
  announce the pilot's own intentions only.
