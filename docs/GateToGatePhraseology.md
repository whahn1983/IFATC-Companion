# Gate-to-Gate Phraseology (FAA-style, US commercial IFR)

This is the deterministic call reference for IFATC Companion's **FAA / US**
phraseology mode. It covers a complete commercial IFR flight gate-to-gate,
including the **Ramp** conversations before and after Ground.

> **IFATC Companion is simulated, private ATC for you only.** It is never staffed
> (human) ATC for other multiplayer users. **Ramp/apron/company-ramp** calls are
> *simulated local/non-FAA procedures* and vary by airport and operator — they are
> not FAA air traffic control.

Authority: FAA JO 7110.65, FAA AIM, FAA Pilot/Controller Glossary, FAA Chart
Supplement. Items the app cannot validate confidently are marked **needs review**
and behave conservatively. ICAO is a separate pack and is not mixed in.

Example route used below: **KIAH (Houston) → KMSP (Minneapolis)**, United 598.

---

## Facilities & non-movement vs movement area

| Facility | Authority | Controls |
|---|---|---|
| Clearance Delivery | FAA ATC | IFR clearance / PDC |
| **Ramp / Apron / Company Ramp** | **simulated local/company, NOT FAA ATC** | non-movement area: pushback, start coord, alley/spot moves, hold/give-way, handoff to Ground |
| Ground | FAA ATC | movement area (taxiways, runway crossings) |
| Tower | FAA ATC | runway: line up and wait, takeoff, landing |
| Departure / Center / Approach | FAA ATC | airborne: climb, route, descent, vectors, approach |
| Ground Crew / Interphone | crew comms (non-radio, private) | brakes/towbar/pushback |
| System / Advisory | app | ATIS ack, "flight complete", non-movement advisory |

**Boundary:** Ramp controls the aircraft until it reaches the **spot / movement-area
boundary**, where it hands off to **Ground**. Ground hands the arrival back to Ramp
at the ramp boundary (when a ramp profile applies).

---

## Prohibited phrases (blocked)

`cleared to taxi` · `cleared for taxi` · `cleared for pushback` · `position and
hold` · `taxi into position and hold` · `takeoff at your discretion` · `cleared
for departure` (as a takeoff clearance) · `line up and wait behind …` · `taxi as
requested` · `cross all runways` · `cleared across all runways` · `proceed as
requested` · `any traffic please advise` · `last call` · `clear active` · `active
runway`/`the active` · `taking the active` · `with you` (pilot check-ins) · `on
the ILS` (as a check-in) · `roger`/`wilco` as a readback for hold-short, crossing,
landing, takeoff, heading, or altitude.

These are enforced by `PhraseologyValidator` and covered by tests.

---

## Number & speech formatting

`9 → niner` · `0 → zero` · `10,000 → one zero thousand` · `11,000 → one one
thousand` · `2,500 → two thousand five hundred` · `FL230 → flight level two three
zero` · `heading 270 → heading two seven zero` · `118.300 → one one eight point
three` · `124.875 → one two four point eight seven five` · `17R → runway one seven
right` · `04L → runway zero four left` · `30C → runway three zero center` · `wind
330 at 12 → wind three three zero at one two` · `squawk 4271 → squawk four two
seven one`. Callsign style is configurable (grouped "twelve thirty four" vs
digit-by-digit "one two three four"; digit-by-digit recommended for TTS clarity).

---

## Departure ramp transcript (simulated, non-FAA)

Clearance Delivery ends the IFR clearance by telling the pilot whom to contact
for pushback, so they know which frequency to tune next — Ramp when the airport
has a ramp/apron layer (the common commercial case), or Ground when it does not:

```
Clearance → Pilot: United 598, cleared to KMSP … squawk 4271.
                   When ready for pushback, contact Ramp on 131.000.
```

```
Pilot → Ramp:  Ramp, United 598 at Bravo 44, ready to push.
Ramp → Pilot:  United 598, pushback approved, tail west.
Pilot → Ramp:  Pushback approved, tail west, United 598.
   (congestion)
Ramp → Pilot:  United 598, hold position, traffic entering the alley.
Pilot → Ramp:  Holding position, United 598.
Ramp → Pilot:  United 598, push approved, tail west.
   (engine start, if ramp-coordinated)
Pilot → Ramp:  Ramp, United 598, request engine start.
Ramp → Pilot:  United 598, start approved.
Pilot → Ramp:  Start approved, United 598.
   (push complete)
Pilot → Ramp:  Ramp, United 598, push complete, ready to taxi.
Ramp → Pilot:  United 598, taxi via the alley to spot 5.
Pilot → Ramp:  Taxi via the alley to spot 5, United 598.
   (handoff at the spot / movement-area boundary)
Ramp → Pilot:  United 598, contact Ground 121.9 at spot 5.
Pilot → Ramp:  Ground 121.9 at spot 5, United 598.
```

If push direction is unknown: `push approved, advise ready to taxi`. If no spot:
`proceed to the movement-area boundary and contact Ground`. If ramp control is
disabled (System advisory): *"Ramp movement is non-movement-area / company
controlled at many airports. Continue when ready and contact Ground before
entering the movement area."*

### Optional tug / interphone (non-radio, private text-only)

```
Ground crew: Brakes set?
Pilot:       Brakes set.
Ground crew: Towbar disconnected, bypass pin removed, hand signal on the left. Have a good flight.
Pilot:       See you on the left, United 598.
```

---

## Ground → departure (FAA ATC)

```
Pilot → Ground: Houston Ground, United 598 at spot 5, ready to taxi, information Alpha.
Ground → Pilot: United 598, runway 17R, taxi via Alpha, Bravo, hold short runway 17L.
Pilot → Ground: Runway 17R via Alpha, Bravo, hold short runway 17L, United 598.
Ground → Pilot: United 598, cross runway 17L at Bravo, continue via Bravo.
Pilot → Ground: Cross runway 17L at Bravo, continue via Bravo, United 598.
Ground → Pilot: United 598, contact Tower 118.3.
Pilot → Tower:  Houston Tower, United 598 holding short runway 17R, ready for departure.
Tower → Pilot:  United 598, runway 17R, line up and wait.
Pilot → Tower:  Runway 17R, line up and wait, United 598.
Tower → Pilot:  United 598, wind 180 at 8, runway 17R, cleared for takeoff.
Pilot → Tower:  Runway 17R, cleared for takeoff, United 598.
```

Conservative fallback when no route is known: *"runway 17R, taxi via assigned
taxiways, hold short of all runways."* Progressive taxi: *"proceed straight ahead
on Alpha. I'll call your next turn."*

---

## Climb / enroute (FAA ATC)

```
Pilot → Dep:    Houston Departure, United 598 passing 1,800 for 5,000.
Dep → Pilot:    United 598, Houston Departure, radar contact, climb and maintain FL230.
Pilot → Dep:    Climb and maintain FL230, United 598.
Dep → Pilot:    United 598, contact Houston Center 132.65.
Pilot → Center: Houston Center, United 598 FL230 climbing FL370.
Center → Pilot: United 598, climb and maintain FL370.
```

Cruise calls available: maintain, climb, descend, direct-to, resume own
navigation, heading/vectors, speed, altimeter below transition altitude, traffic,
ride report, weather deviation, frequency change, handoff, unable, amended
clearance, say again, verify altitude, pilot's-discretion descent.

### Ride reports & weather (deterministic; never fabricated)

The ride report relays the driving PIREP the way ATC would — severity, the **reported
altitude**, distance/fix ahead, reporting type and recency — and, when a PIREP at another
level shows a smoother ride, names that **specific altitude** to climb/descend to (bounded
to the FL240–FL430 cruise band, data-driven only). With no supporting report it keeps the
generic higher/lower offer.

```
None:      United 598, overall ride is smooth along your route at this time.
Smooth:    United 598, no significant ride reports along your route at this time.
Moderate,  United 598, moderate turbulence reported at FL350 approximately 40 miles ahead
 smoother  near DSM, by a B738, 15 minutes ago. Based on pilot reports. Smooth ride reported
 level:    at FL390; advise if you'd like to climb.
Moderate,  United 598, moderate turbulence reported at FL350 approximately 25 miles ahead.
 no data:  Advise if you'd like higher or lower for a smoother ride.
Failure:   United 598, unable weather update at this time.
```

---

## Descent / arrival (FAA ATC)

```
Center → Pilot: United 598, descend at pilot's discretion, maintain FL240.
Pilot → Center: Descend at pilot's discretion, maintain FL240, United 598.
Center → Pilot: United 598, descend via the MUSCL TWO arrival.
Pilot → Center: Descend via the MUSCL TWO arrival, United 598.
Pilot → App:    Minneapolis Approach, United 598 leaving FL240 descending via the MUSCL TWO arrival, information Delta.
App → Pilot:    United 598, fly heading 270, vectors ILS runway 30L approach.
Pilot → App:    Heading 270, vectors ILS runway 30L, United 598.
App → Pilot:    United 598, 5 miles from JERMS, turn left heading 300, maintain 4,000 until established on the localizer, cleared ILS runway 30L approach.
Pilot → App:    Left heading 300, maintain 4,000 until established, cleared ILS runway 30L approach, United 598.
App → Pilot:    United 598, contact Tower 126.7.
Pilot → Tower:  Minneapolis Tower, United 598, inbound on the ILS runway 30L.
Tower → Pilot:  United 598, wind 310 at 12, runway 30L, cleared to land.
Pilot → Tower:  Runway 30L, cleared to land, United 598.
```

"Descend via"/"climb via" preserve published restrictions unless "except maintain"
is read back. Visual: *"airport twelve o'clock, one five miles, report airport in
sight"* → *"cleared visual approach runway 30L."* Go-around: *"go around, fly runway
heading, climb and maintain 3,000."*

---

## Landing → arrival ramp transcript

```
Tower → Pilot:  United 598, exit the runway when able, contact Ground 121.7 once on the taxiway.
Pilot → Tower:  Exiting the runway, contact Ground, United 598.
Pilot → Ground: Minneapolis Ground, United 598 clear runway 30L at Kilo, request taxi to gate Bravo 44.
Ground → Pilot: United 598, taxi to gate Bravo 44 via Kilo, Bravo.
Pilot → Ground: Gate Bravo 44 via Kilo, Bravo, United 598.
   (if crossing) Ground: United 598, taxi to gate Bravo 44 via Kilo, cross runway 30R, then Bravo.
Ground → Pilot: United 598, contact Ramp 131.0 at spot 7.
Pilot → Ramp:   Ramp 131.0 at spot 7, United 598.
Pilot → Ramp:   Ramp, United 598 inbound Bravo 44.
Ramp → Pilot:   United 598, proceed to gate Bravo 44 via the inner alley.
Pilot → Ramp:   Proceed to Bravo 44 via the inner alley, United 598.
   (conflict) Ramp: United 598, hold position for outbound company traffic.
   (occupied) Ramp: United 598, gate Bravo 44 is occupied, hold short of the alley.
Ramp → Pilot:   United 598, monitor ramp to the gate.
Pilot → Ramp:   Monitor ramp, United 598.
System:         United 598 parked at Bravo 44. Flight complete.
```

Taxi-to-gate does **not** authorize a runway crossing unless explicitly stated.

---

## Manual data fallbacks

When Infinite Flight does not expose data, manual overrides apply: callsign,
airline, flight number, departure/destination/alternate, departure/arrival gate,
ramp profile, ramp frequency, ramp spot, runway, SID, STAR, approach, cruise
altitude, current & destination ATIS, departure frequency, squawk, taxi route.
When data is missing, conservative phraseology is preferred over guessing.

---

## Example full transcript

A complete KIAH→KMSP run is the concatenation of the **Departure ramp**, **Ground →
departure**, **Climb / enroute**, **Descent / arrival**, and **Landing → arrival
ramp** sections above, beginning with *"Information Alpha"* / the IFR (or PDC)
clearance and ending with *"United 598 parked at Bravo 44. Flight complete."*
