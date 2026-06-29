# TODO / Roadmap

The original roadmap below has been **implemented**. Everything is deterministic
and local-only, in keeping with the app's design (no backend, no generative
AI/LLM, free public weather only). On-device speech recognition uses Apple's
Speech framework for push-to-talk input; the intent mapping that follows is
deterministic keyword matching.

## Delivered

### Pilot interaction
- ✅ **Speech recognition / push-to-talk** — hold-to-talk on the ATC tab using
  on-device speech recognition (`SpeechRecognitionService`), feeding a
  deterministic `PilotIntentParser` that maps phrases to existing pilot actions.
- ✅ **User-created phraseology profiles** — `PhraseologyProfile` /
  `PhraseologyProfileStore` with per-call template overrides and airline call
  sets, editable in Settings and shareable as JSON.

### Connect / multiplayer
- ✅ **Live API integration** — multiplayer/ATC-staffing detection via the
  Connect manifest (`LiveATCDetector`); the companion steps aside (stands by,
  stops generating controller calls) when a human controller is present. A mock
  toggle in Diagnostics exercises the behavior in the Simulator.

### ATC realism
- ✅ **More robust taxi routing** — `TaxiRoutePlanner` models a simplified airport
  surface (taxiways, ramp, per-runway routes, runway crossings) with a built-in
  set for the demo airports and a deterministic fallback elsewhere.
- ✅ **SID/STAR/approach parsing** — `ProcedureParser` / `ProcedureLibrary` parse
  procedure name strings into structured procedures, enriched with known fixes.
- ✅ **Procedure-aware instructions** — clearance, descent-via-arrival, and
  approach clearances reference the parsed procedures by name.
- ✅ **FAA / ICAO phraseology packs** — selectable packs change digit words
  ("tree/fower/fife"), the frequency separator ("decimal" vs "point"), and the
  altimeter/QNH convention (inHg vs hPa), plus phrase forms.

### Weather
- ✅ **ForeFlight-style route/weather overlay** — `RouteMapView` (MapKit) shows the
  route line, departure/destination, live aircraft position, and PIREP
  turbulence reports color-coded by severity.
- ✅ **Better turbulence model** — `TurbulenceModel` blends PIREPs (weighted by
  distance ahead and report age), SIGMET advisories, and a low-level wind-shear
  proxy from the surface METAR into a continuous ride index and severity.

### Hybrid ATC (pilot-driven calls + automatic position callouts)
- ✅ **Read the flight plan from Infinite Flight** — `IFConnectManager` reads
  `aircraft/0/flightplan` and `IFFlightPlanParser` extracts departure, destination
  and enroute fixes, merged into the active plan (manual overrides win).
- ✅ **Pilot-driven pre-departure flow** — the pilot drives clearance → pushback →
  engine start → taxi → ready with the ATC-tab buttons; read backs and check-ins
  are always manual. See [`docs/ATC-Flow.md`](docs/ATC-Flow.md).
- ✅ **Automatic, position-triggered controller calls** — the takeoff clearance
  fires once lined up (`RunwayLineupDetector`); once airborne, facility hand-offs,
  Departure-to-Center at a configurable TRACON ceiling (FL180),
  descent/approach/cleared-to-land and taxi-in advance from telemetry.
- ✅ **Manual frequency tuning** — a **Tune Frequency** card (Clearance, Ground,
  Tower, Departure, Center, Approach, Ramp) lets the pilot change controllers with
  a button (`tuneTo`/`arriveAtGate`). The first manual tune sets `manualTuning`,
  which suppresses the automatic telemetry-driven calls so they no longer play one
  after the next — the pilot drives every frequency change from then on.
- ✅ **Clear Flight** — a button on the ATC tab (`clearFlight()`) wipes the
  conversation and ATC/phase state to start a new flight, keeping settings and the
  flight plan.
- ✅ **Real-world departure instructions** — the takeoff clearance issues the
  initial heading (bearing to the first fix, or "runway heading" when aligned) and
  the initial climb; Departure adds "resume own navigation, direct *fix*".
- ✅ **Real-world arrival** — a filed STAR yields "descend via the *STAR* arrival"
  (else a plain, non-contradictory "descend and maintain *alt*" to an intermediate
  level); Approach issues the **cleared *ILS/GPS/Visual* approach** once the
  aircraft is established (autopilot APPR mode engaged or lined up on final, read
  from IF telemetry) before the Tower hand-off; after touchdown Tower adds "exit
  the runway when able, contact Ground … once on the taxiway".

## Known constraints (future refinement)

These features ship with intentionally small, deterministic, offline datasets.
Natural follow-ups, if desired later:

- Expand the built-in procedure and airport-surface libraries (currently a handful
  of demo airports; unknown fields fall back to generated/heuristic routes).
- Map exact Connect manifest field names for ATC staffing once validated against
  more Infinite Flight versions (detection is signature-based and best-effort).
- Broaden ICAO regional variations and add metric (QFE/meters) options.
- Render SIGMET/AIRMET area polygons on the route overlay.
