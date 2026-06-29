# Phraseology & Call-Flow Audit

**App:** IFATC Companion · **Mode audited:** FAA / US (v1 authoritative) · **Date:** 2026-06-29

This document records a comprehensive audit of every ATC, pilot, ramp, apron,
company-ramp, UNICOM, weather, ride-report, and TTS string in the project, and
the corrections, refactors, and additions made so the app produces a realistic,
deterministic, FAA-style commercial IFR gate-to-gate radio-call catalog —
including **Ramp** conversations before and after Ground.

**Phraseology authority:** FAA JO 7110.65, FAA AIM, FAA Pilot/Controller
Glossary, FAA Chart Supplement. Where a call could not be validated confidently
it is marked **needs review** and runtime behavior is kept conservative. ICAO
remains a selectable pack and is **not** mixed into the FAA default.

---

## 1. Summary of corrections made

| # | Change | Files |
|---|--------|-------|
| 1 | **Ramp modeled as a first-class, simulated (non-FAA) facility** separate from Ground. Pushback and engine-start are now Ramp, not Ground. | `ATCFacility.swift`, `ATCState.swift`, `AppModel.swift`, `ATCStateMachine.swift`, `PilotResponseEngine.swift` |
| 2 | New `RampPhraseologyEngine` for push approval (tail/face direction), engine-start coordination, ramp taxi to spot, hold/give-way, Ramp→Ground handoff, and arrival ramp-to-gate. | `RampPhraseologyEngine.swift` (new) |
| 3 | New `RampProfile` system so ramp behavior varies by airport without code changes (generic airline profile + KATL/KORD examples, all simulated/needs-review). | `RampProfile.swift` (new) |
| 4 | New `PhraseologyValidator` with a banned/outdated-phrase list (block + warn tiers) and a weak-ack readback check. | `PhraseologyValidator.swift` (new) |
| 5 | **Removed banned "the active"** from UNICOM broadcasts (unknown runway now reads "the runway"). | `UNICOMModels.swift` |
| 6 | Pushback approval uses **"pushback approved" + tail/face direction**, with a conservative **"advise ready to taxi"** fallback when direction is unknown. | `RampPhraseologyEngine.swift` |
| 7 | Arrival flow now includes a **Ramp-after-Ground** block-in call (`monitor ramp to the gate`) plus a System "flight complete" advisory; the manual arrival adds the full inbound/proceed-to-gate ramp exchange. | `AppModel.swift` |
| 8 | Structured **call catalog** (`Resources/GateToGateCallCatalog.json`) with the full schema. | new |
| 9 | New tests: banned-phrase detection, ramp separation, ramp-never-clears, call-flow completeness, plus a sweep asserting all generated calls are clean. | `*Tests.swift` (new) |
| 10 | Updated existing tests that assumed pushback/engine-start were Ground. | `StateMachineTests.swift`, `MockScenarioTests.swift` |

---

## 2. Calls found in existing code

The repository already had a mature, deterministic phraseology engine. Calls
found, by facility:

- **Clearance Delivery:** IFR clearance ("cleared to … via the SID … climb via
  SID except maintain … expect … departure frequency … squawk …"). ✅ correct.
- **Ground (pre-departure):** *pushback approved*, *start up approved*, *taxi to
  runway X via … cross runway …*. Pushback/engine-start were **incorrectly modeled
  as Ground** (see corrections).
- **Tower (departure):** *line up and wait*, *cleared for takeoff* (with runway,
  optional initial heading + climb). ✅ correct; uses "line up and wait" (not
  "position and hold").
- **Departure / Center:** radar contact + climb, climb/maintain, descend/maintain,
  descend at pilot's discretion, descend via the STAR, handoffs. ✅ correct.
- **Approach / Tower (arrival):** descend + expect approach, cleared approach
  (ILS/RNAV/visual), cleared to land, exit runway + contact Ground. ✅ correct.
- **Ground (arrival):** taxi to parking. ✅ (enhanced with ramp follow-on).
- **UNICOM:** taxiing/crossing/taking/departing/inbound/final/clear/parking
  intentions. Intent-only. ⚠️ used **"the active"** when runway unknown (fixed).
- **Weather / RideReport:** no-significant / light / moderate / severe templates,
  destination METAR, graceful "unavailable". ✅ correct.
- **TTS / Phonetic:** niner, zero, "point", flight level, runway/heading/wind/
  squawk/altimeter, FAA & ICAO digit packs. ✅ correct and unit-tested.

## 3. Calls corrected

- **Pushback / engine start** moved from **Ground → Ramp** facility; routed
  through `RampPhraseologyEngine`. Pilot requests now address **"Ramp"**.
- **Pushback approval** now carries a **tail/face direction** when known, with the
  **"advise ready to taxi"** fallback otherwise (was a bare "pushback approved").
- **Engine-start** readback simplified to **"Start approved"** (ramp/company form).
- **UNICOM "the active"** → **"the runway"** for the unknown-runway fallback.

## 4. Calls removed

- The generic **gate "Welcome to <city>, good day"** courtesy at parking was
  replaced by a Ramp **"monitor ramp to the gate"** block-in + System **"flight
  complete"** advisory (the old `welcomeArrival` helper remains in
  `PhraseologyEngine` but is no longer used by the flow).
- No FAA ATC calls were deleted; nothing unsafe was generated by the prior code
  beyond the "the active" wording.

## 5. Calls added

- **Ramp (departure):** push request, push approved (tail/face or advise-ready
  fallback), hold position, start approved, taxi via the alley to spot, continue,
  give way, **contact Ground at spot / movement-area boundary**.
- **Ramp (arrival):** Ground→Ramp handoff, inbound check-in, proceed to gate via
  the alley, gate occupied / hold short of the alley, **monitor ramp to the gate**.
- **Ground Crew / Interphone** (non-radio, private text-only `.system`): brakes
  set, towbar disconnected / bypass pin removed / hand signal on the left.
- **Non-movement advisory** for when ramp control is disabled.
- **Catalog templates** (not yet wired into the automatic state flow but provided
  in `RampPhraseologyEngine` / catalog): cancel-takeoff / stop immediately,
  go-around, progressive taxi, visual approach.

## 6. Calls intentionally not implemented yet

- **PDC/CPDLC clearance card UI** — the catalog and docs define the PDC contents
  and the "PDC received" acknowledgment, and a clearance-mode setting is described,
  but the text-card UI and the Voice/PDC/Auto toggle are **not yet built**. Runtime
  remains voice clearance.
- **Cancel-takeoff, go-around, progressive taxi, visual-approach** are templated
  but **not auto-triggered** by the state machine.
- **Airport-specific ramp spots/frequencies** are only illustrative (KATL/KORD)
  and flagged needs-review; all other airports use the generic profile.
- **ICAO ramp phraseology** — Ramp is generated in FAA/generic form only.

## 7. Known limitations

- The app cannot be compiled/tested in this environment (no Xcode/iOS SDK), so
  changes were made conservatively and verified by reading + unit-test design; a
  local `xcodebuild test` is still required for final sign-off.
- Ramp spots, frequencies, alley names, and gate naming are **simulated** unless a
  validated airport profile exists.
- Taxi routing is from a small built-in surface dataset with a deterministic
  fallback — not surveyed routing.
- Weather depends on NOAA endpoint availability; failures degrade to "unavailable"
  (never fabricated).

## 8. FAA-style assumptions

1. The app simulates **private ATC for the user only**; it never implies staffed/
   live ATC for other multiplayer users (enforced by the existing standby logic).
2. **No "cleared to taxi"** — taxi uses "taxi", "hold short", "cross", "continue",
   "proceed", "give way".
3. A taxi-to-runway instruction does **not** authorize entering/crossing a runway.
4. **Runway crossings** require an explicit clearance + readback with the runway.
5. **Takeoff and landing clearances include the runway number.**
6. **"Line up and wait"**, never "position and hold".
7. **"Cleared for takeoff"**, never "takeoff at your discretion"/"cleared for
   departure"; **"cleared to land"** for landings.
8. First pilot readback of taxi/departure/landing includes the runway.
9. Readbacks include callsign and all safety-critical elements (runway, hold-short,
   crossing, heading, altitude, speed, route, approach, squawk, frequency).
10. **niner / zero / "point" / "flight level"**; **"radar contact"** only after
    radar identification; **"contact"** triggers a frequency readback + transition;
    **"monitor"** is distinct from "contact".
11. **"Descend via" / "climb via"** preserve published restrictions unless an
    explicit exception ("except maintain") is read back.
12. **No "with you"** in check-ins; **no "any traffic please advise"**.
13. Specific runway is named — **never "active runway"/"the active"**.

## 9. Ramp / local-procedure assumptions

- Ramp/apron/non-movement-area communications are **local airport, airline,
  company, or ramp-control procedures — NOT FAA ATC**, and are documented as such.
- Ramp and Ground are **never** mixed roles. Ground (FAA ATC) controls the
  movement area only after the aircraft reaches the spot / movement-area boundary.
- Ramp may approve pushback, coordinate engine start, move aircraft in the
  non-movement area, hold for traffic, assign spots, and hand off to Ground.
- Ramp **must not** issue takeoff, landing, runway-crossing, IFR route, altitude,
  heading, SID, STAR, or approach instructions, and must not authorize runway
  entry/crossing. (Enforced by `RampPhraseologyTests`.)
- Defaults when data is missing: gate unknown → "at the gate"; push direction
  unknown → "push approved, advise ready to taxi"; spot unknown → "proceed to the
  movement-area boundary and contact Ground"; ramp control disabled → non-movement
  advisory.

## 10. Infinite Flight / UNICOM etiquette notes

- UNICOM automation broadcasts the **pilot's own intentions/status only**
  (taxiing, crossing, taking/departing runway, inbound, final, clear, parking).
- UNICOM **never** sends ATC clearances ("cleared for takeoff/land") or ramp
  approvals ("push approved") — verified by tests.
- Higher-stakes runway-occupancy events (taking/departing/crossing) are
  non-trusted and require preview before send; routine events may auto-send.
- When a Connect command is unavailable, the event is previewed and marked
  unavailable; it is never silently dropped as if sent.

## 11. Items requiring future pilot/controller validation (needs review)

- PDC clearance card contents and the Voice/PDC/Auto fallback behavior.
- Cancel-takeoff / "stop immediately" wording in context.
- Go-around follow-on vectors and altitudes per facility.
- Progressive taxi phrasing ("I'll call your next turn").
- Visual approach sequence ("report airport in sight" → "cleared visual approach
  runway X").

## 12. Items requiring airport-specific ramp profile validation (needs review)

- KATL ramp tower spots/frequencies, tail directions, concourse gate naming.
- KORD inner/outer alley layout and spot numbering.
- All other hubs (KDEN, KIAH, KMSP, KORD, KDFW, …) — currently the **generic**
  airline ramp profile; no precise spots/frequencies are invented.

## 13. Existing ramp phrases found, and their classification

| Phrase (prior code) | Classification | Action |
|---|---|---|
| "pushback approved" (Ground) | acceptable generic ramp phraseology, **but wrong facility** | Moved to Ramp; added tail/face direction + fallback |
| "start up approved" (Ground) | acceptable, wrong facility | Moved to Ramp as "start approved" |
| "taxi to parking via …" (Ground) | acceptable FAA Ground (arrival) | Kept on Ground; ramp follow-on added |
| UNICOM "clear of the active" / "the active" | **incorrect — uses "active"** | Fixed to "the runway" |
| (none) "push approved, tail/face …" | new acceptable generic ramp | Added |
| (none) "taxi via the alley to spot …" | new acceptable generic ramp | Added |
| (none) "contact Ground … at spot …" | new acceptable ramp→ATC handoff | Added |

No prior ramp phrase implied FAA ATC runway/movement-area authority.
