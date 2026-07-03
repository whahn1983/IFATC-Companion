# Weather: NOAA Radar Precipitation & Simulated Weather Deviation

> **Simulation, training, and entertainment only.** Radar, precipitation, and
> deviation logic in IFATC Companion must **not** be used for real-world aviation
> or treated as flight-safety guidance. This is *radar-aware ATC simulation*, not
> operational aviation guidance, storm avoidance, or certified weather radar.

This document describes the radar precipitation overlay and the deterministic
simulated weather-deviation flow added on top of the existing Weather View,
weather services, route map, models, and ride-report logic. Nothing here replaces
those features — it extends them.

## What was added

- **NOAA/NWS radar precipitation overlay** on the existing route map, shown only
  where NOAA provides coverage.
- **Route-weather conflict detection** along the active route corridor.
- A **simulated ATC weather-deviation flow**: advisory → pilot request (left/right
  deviation or vectors) → simulated ATC approval with a suggested heading/deviation
  and a downstream rejoin fix → monitor clear-of-weather → clear back to the filed
  route or a downstream fix.
- **Weather Data settings**, a **Weather Diagnostics** panel, and a **Mock Mode**
  demo that exercises the whole flow offline.

The existing METAR/TAF/PIREP/SIGMET display and the turbulence **ride reports** are
unchanged and continue to work exactly as before.

## Data sources (free / open / commercial-use compatible)

This feature uses **only** free, keyless NOAA/NWS sources. It requires **no paid
weather subscription, no API key, no billing account, and no additional user
subscription** — for the user or for the app publisher.

1. **NOAA/NWS radar base reflectivity / MRMS** (public ArcGIS ImageServer) — the
   radar precipitation overlay. This is the **only approved v1 radar source**.
   Labeled clearly as *NOAA/NWS radar precipitation*. No NOAA/NWS logos or branding
   implying endorsement are used; attribution is a plain text label
   ("Radar precipitation data: NOAA/NWS").
2. **NOAA Aviation Weather Center Data API** — the existing METAR/TAF/PIREP/SIGMET
   source, unchanged.

No other providers are included. In particular the app does **not** integrate
RainViewer, Meteoblue, Meteomatics, OpenWeather, Tomorrow.io, The Weather Company,
AccuWeather, ForeFlight, Garmin, Windy (paid API), or any other commercial /
paid / non-commercial-only / trial / evaluation-limited provider. The radar
provider architecture ships exactly two conformers:
`NOAARadarPrecipitationProvider` and, for Mock Mode and tests,
`MockRadarPrecipitationProvider`.

## Coverage limitations (read this)

- **Radar precipitation is available only where the free NOAA/NWS source provides
  coverage** — the contiguous U.S. and NOAA-covered regions (Alaska, Hawaii,
  Puerto Rico). Outside those the overlay is hidden/disabled and the app shows:
  *"Radar precipitation is not available for this region."* Forecast, model, or
  satellite precipitation is **never** substituted and displayed as radar.
- **The app does not claim global radar coverage.** There is no global true-radar
  provider in v1 (see the discovery task below).
- **Global non-radar aviation weather may still be available** through the existing
  METAR/TAF/SIGMET sources, which can work outside the U.S. depending on the
  upstream data.
- **PIREPs/AIREPs** are treated as limited primarily to **U.S. and North Atlantic**
  coverage. Where none are available the app shows *"No recent reports available"*
  rather than implying a smooth ride. Missing reports never mean "smooth weather".
- **G-AIRMET** is treated as **contiguous-U.S. only** and is never presented as
  global.
- For non-U.S. flights, SIGMETs and the existing aviation advisories drive the
  simulated weather-deviation calls. If no advisory data exists and radar is
  unavailable, the app does **not** invent precipitation.

## How the flow works

1. **Hazards.** Radar precipitation cells (where covered) and route-intersecting
   SIGMET polygons are normalized into `WeatherHazard` values, tagged by `source`
   so phraseology stays honest — radar is always spoken as *"precipitation"*, never
   *"turbulence"*. Turbulence wording is reserved for PIREP / AIREP / SIGMET /
   G-AIRMET / CWA / existing ride-report logic. Convective SIGMETs are phrased as
   *"convective weather / thunderstorms"* only when the advisory supports it.
2. **Conflict detection.** `RouteWeatherConflictDetector` builds a corridor from the
   aircraft through the upcoming route fixes (lookahead 25–75 NM in the terminal
   area, 100–250 NM enroute, with a 30–120 minute groundspeed-based fallback) and
   computes distance, clock position(s), estimated time, severity, a left/right
   bypass score, a recommended deviation amount, and a downstream rejoin fix.
3. **Advisory.** When a conflict warrants prompting, ATCView shows a
   *"Weather ahead — ask Center"* banner. Tapping **Ask Center** (or, in the Mock
   demo, automatically) issues a simulated advisory ("area of heavy precipitation
   …, say intentions"), degrading gracefully to *"movement unknown"* /
   *"intensity unknown"* and to the SIGMET / no-advisory variants.
4. **Deviation.** The pilot can request a right/left deviation, vectors around
   weather, or higher/lower for weather. Simulated ATC approves — with a suggested
   deviation (default 20°, 10° for small/light cells, 20–30° for moderate/heavy,
   30° for extreme/convective) and either a downstream rejoin fix or, when none is
   suitable, *"advise clear of weather"*. On a STAR the altitude restriction is
   preserved with *"maintain …"* and the rejoin is framed as rejoining the arrival.
5. **Clear of weather.** When the pilot reports clear of weather, ATC clears direct
   the rejoin fix (or *"resume own navigation"* when already near the route), or
   rejoins the STAR.

The deviation flow only runs during the enroute/arrival phases (Center/Approach).
It is conservative in departure/climb, preserves altitude restrictions on a STAR,
never interferes with takeoff/landing clearance logic, and shows an advisory-only
state when established on final.

## Settings (Weather Data)

- **NOAA Radar Overlay** — *Auto where available* or *Off* (no global/commercial
  provider selection).
- **Radar opacity** — default **0.55**.
- **Weather deviation alerts** — *Off*, *Advisory only*, or *Advisory + suggested
  deviation*.
- **Show data-source labels** and **Show coverage warnings**.

No user-entered API keys, provider subscriptions, or commercial weather-provider
configuration are offered — by design.

## Mock Mode demo

Mock Mode loads a deterministic precipitation cell that crosses the filed route
~40 NM ahead of cruise. The map shows the cell, ATCView shows the advisory, and the
pilot can request a right deviation, get an approval with a downstream rejoin fix,
then report clear of weather and be cleared direct/own-navigation — all offline,
with no live APIs and regardless of subscription state.

## Future-only discovery task (do not implement without verification)

**TODO:** Investigate whether a *truly free / open* global radar precipitation
source exists that allows commercial app inclusion without paid licensing. Do not
implement a global radar provider unless the source meets **all** of:

- free to use, with **no** paid tier required for this app's use;
- **no** user subscription and **no** API-key billing account required;
- commercial app use allowed, and redistribution/display in the app allowed;
- attribution requirements compatible with the app;
- **no** SLA/payment dependency.

If this cannot be verified, **leave global radar unsupported** (the current state).
Until then, radar precipitation remains NOAA-covered-regions only.
