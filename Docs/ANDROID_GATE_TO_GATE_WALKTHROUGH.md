# Gate-to-gate walkthrough — iOS and Android side by side

The Android transcript below is **real output**, dumped from
`MockScenarioTest`'s scripted flight running against `FlightSessionCoordinator`: United
598, KIAH → KMSP at FL370, filed KKILR arrival and ILS 30L. It is the same demo flight
the iOS build flies, to the digit — the route, the five fixes, the cruise level and both
United stands are asserted equal in `theDefaultRouteIsTheIOSDemoFlight`.

The iOS column is what `IFATCCompanionTests/MockScenarioTests.swift` asserts of the same
flight. Where the two agree, the row simply says so; where they differ, the row says how
and why. Nothing here is paraphrased from the code — the Android side is the transcript
the engine produced.

**How to read it.** Every substantive controller instruction closes the read-back gate;
the pilot's read-back is what re-opens it and lets the next call fire. That is why the
pilot lines are not decoration: remove one and the flow stops there, on both platforms.

---

## The transcript

| # | Who | Frequency | Transmission |
| --- | --- | --- | --- |
| 0 | Pilot | Clearance | Clearance, United 598, request IFR clearance to KMSP. |
| 1 | ATC | Clearance | United 598, cleared to KMSP via the filed route, climb via SID except maintain 5,000, expect FL370 one zero minutes after departure, departure frequency 124.350, squawk 0133. When ready for pushback, contact Ramp on 131.000. |
| 2 | Pilot | Clearance | Cleared to KMSP, climb via SID except maintain 5,000, squawk 0133, United 598. |
| 3 | Pilot | Ramp | Ramp, United 598, ready to push. |
| 4 | ATC | Ramp | United 598, pushback approved, advise ready to taxi. |
| 5 | Pilot | Ramp | Pushback approved, United 598. |
| 6 | Pilot | Ramp | Ramp, United 598, request engine start. |
| 7 | ATC | Ramp | United 598, start approved. |
| 8 | Pilot | Ramp | Start approved, United 598. |
| 9 | Pilot | Ground | Ground, United 598, request taxi. |
| 10 | ATC | Ramp | United 598, contact Ground on 121.900. |
| 11 | ATC | Ground | United 598, taxi to runway 27 via A, E. Contact Tower when ready. |
| 12 | Pilot | Ground | Taxi to runway 27 via A, E, United 598. |
| 13 | Pilot | Ground | Ground, United 598, request taxi. |
| 14 | Pilot | Ground | Taxi to runway 27 via A, E, United 598. |
| 15 | Pilot | Tower | Tower, United 598, holding short runway 27, ready for departure. |
| 16 | ATC | Ground | United 598, contact Tower on 118.300. |
| 17 | ATC | Tower | United 598, runway 27, line up and wait. |
| 18 | Pilot | Tower | Runway 27, line up and wait, United 598. |
| 19 | ATC | Tower | United 598, wind 270 at 8, runway 27, cleared for takeoff, fly heading 007, climb and maintain 6,000. |
| 20 | Pilot | Tower | Runway 27, cleared for takeoff, heading 007, climb and maintain 6,000, United 598. |
| 21 | ATC | Tower | United 598, contact Departure on 124.350. |
| 22 | ATC | Departure | United 598, radar contact, climb and maintain FL180, resume own navigation, direct TBONE. |
| 23 | Pilot | Departure | Climb and maintain FL180, resume own navigation, direct TBONE, United 598. |
| 24 | ATC | Departure | United 598, contact Center on 133.400. |
| 25 | ATC | Center | United 598, radar contact, climb and maintain FL370. |
| 26 | Pilot | Center | Climb and maintain FL370, United 598. |
| 27 | ATC | Center | United 598, descend via the KKILR arrival, maintain 11,000 crossing KKILR. |
| 28 | Pilot | Center | Descend via the KKILR arrival, United 598. |
| 29 | ATC | Center | United 598, contact Approach on 119.700. |
| 30 | ATC | Approach | United 598, descend and maintain 4,000, expect the ILS runway 30L approach. |
| 31 | ATC | Approach | United 598, cleared ILS RWY 30L approach. |
| 32 | ATC | Approach | United 598, contact Tower on 118.300. |
| 33 | Pilot | Tower | Contacting Tower on 118.300, United 598. |
| 34 | ATC | Tower | United 598, wind 270 at 8, runway 30L, cleared to land. |
| 35 | ATC | Tower | United 598, exit the runway when able, contact Ground on 121.900 once on the taxiway. |
| 36 | Pilot | Tower | Exiting the runway, contact Ground, United 598. |
| 37 | ATC | Ground | United 598, taxi to parking via A. |
| 38 | Pilot | Ground | Taxi to parking via A, United 598. |

---

## Stage by stage

| Stage | iOS | Android | Same? |
| --- | --- | --- | --- |
| IFR clearance | `requestClearance()` → clearance with SID, initial climb, expected cruise, departure frequency, squawk, then the pushback hand-off appended | identical, from the same `PhraseologyEngine.clearance` + `appendingPushbackHandoff` | ✅ |
| Pushback | Ramp (simulated local position, not FAA ATC) | identical | ✅ |
| Engine start | Ramp | identical | ✅ |
| Ramp → Ground hand-off | Ramp issues "contact Ground on …" on the first taxi request | identical | ✅ |
| Taxi clearance | Ground, with the route and the Tower instruction | identical | ✅ |
| Ready for departure → line up and wait | Tower, after the Ground → Tower hand-off | identical | ✅ |
| Takeoff clearance | Tower, automatic once lined up — no pilot prompt | identical; `maybeIssueTakeoffClearance` fires on the same three triggers | ✅ |
| Tower → Departure | Held until ~2,000 ft above the field, so the departure call does not stack on the takeoff clearance | identical (`adjustedAirborneTarget`, first rung) | ✅ |
| Departure climb | To the TRACON ceiling, joining the filed route | identical | ✅ |
| Departure → Center | 1,000 ft below the ceiling, so the pilot can check in before the climb reaches it | identical | ✅ |
| Center radar contact + cruise climb | "radar contact, climb and maintain FL370" | identical | ✅ |
| Top of descent | Center issues descend-via-STAR first, before any Approach hand-off | identical | ✅ |
| Center → Approach | On descent through the ceiling or on entering the terminal area | identical | ✅ |
| Descend + expect approach | Approach, to the intercept altitude | identical | ✅ |
| Cleared approach | Once established — APPR engaged, or lined up on final with wings level | identical | ✅ |
| Approach → Tower | **Instruction first, hand-off second** — the reverse of the usual order — and the read-back gate is re-aimed at the hand-off so the pilot answers "Contacting Tower" | identical (`announceApproachToTowerHandoff`) | ✅ |
| Cleared to land | Tower, on short final or at touchdown | identical | ✅ |
| Exit the runway | Tower, after touchdown | identical | ✅ |
| Tower → Ground, taxi to parking | Once clear of the runway and at taxi speed | identical | ✅ |
| Block-in | Ramp block-in + a System "flight complete" line, once stopped at the gate with the brake set, on the Ramp frequency, within 80 m of the assigned stand | Same three conditions. **Not reached in this dump**, because the scripted feed parks the aircraft without the pilot having contacted Ramp — the same script on iOS ends the same way | ✅ (rule), ⬜ (not exercised by the script) |

## Differences worth stating

1. **"Read Back" is a button on both, and a spoken phrase on both.** Android adds
   push-to-talk through the on-device recognizer, and a spoken read-back routes to
   `readBack()` — the identical method the button calls. There is no second path.

2. **Ramp is a simulated local position, not ATC**, on both platforms. It is voiced with
   the Ground voice on Android because Android has no equivalent of the iOS voice catalog;
   the words are unchanged.

3. **The cruise level appears as "FL370" in the transcript on both platforms.** The iOS
   descent test greps the whole transcript for `maintain FL370`; that also catches
   Center's climb call at line 25, where the phrasing is correct and identical on both.
   The Android test narrows the assertion to the descent onward, which is what the test
   means. This is the one place the ported test deliberately differs from the iOS one, and
   the reasoning is attached to it in `MockScenarioTest.kt`.

4. **Frequencies in this dump are the engine defaults**, because the scripted flight has
   no live ATIS or surface data behind it. In a live flight they come from the same places
   they do on iOS.

5. **Taxi routes here are the deterministic fallback planner's** ("via A, E"), because no
   OpenStreetMap surface is loaded in the scripted run. With a surface loaded the live
   route supersedes them and names the field's real taxiways, and a low-confidence route
   draws dashed on the map rather than being spoken as if it were certain.

6. **The wind is the no-report default, 270 at 8**, on both platforms — there is no METAR
   behind the scripted flight. It is also what the runway in use is picked from: KIAH's
   most into-wind runway for a 270 wind is 27, which is why the departure clearance names
   it. With a live METAR both follow the real wind.

---

*The transcript above is pinned by `GateToGateTranscriptTest` in
`Android/core/src/test/kotlin/.../core/mock/`, which asserts the whole conversation —
sender, frequency and wording — line for line. Change a controller call and that test
fails, which is the point: the whole product is phraseology, so a changed word is a
behaviour change. When it fails because a call legitimately changed, update both the test
and this document; a stale walkthrough is worse than none.*
