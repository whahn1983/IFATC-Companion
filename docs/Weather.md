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

This feature uses **only** free, keyless sources. It requires **no paid weather
subscription, no API key, no billing account, and no additional user
subscription** — for the user or for the app publisher.

**Precipitation overlay providers** (selected by region, see below):

1. **NOAA/NWS radar base reflectivity / MRMS** (public ArcGIS ImageServer) — U.S.
   and NOAA-covered radar regions. **True radar.** Labeled *"Radar precipitation"*;
   source *NOAA/NWS radar precipitation*. No NOAA/NWS logos or endorsement implied;
   attribution is a plain text label ("Radar precipitation data: NOAA/NWS").
2. **EUMETNET OPERA (ODC/ORD) radar composite** — Europe, where OPERA data is
   available. **True radar.** Labeled *"Radar precipitation"*; source *EUMETNET
   OPERA radar precipitation*. Honors **CC BY 4.0** attribution ("Radar
   precipitation data: EUMETNET OPERA (CC BY 4.0)"). Prefers OPERA composite
   products in order — **maximum reflectivity → instantaneous rain rate → 1-hour
   accumulation** — and cloud-optimized GeoTIFF over ODIM HDF5 for easier iOS
   rendering. Coverage is best-effort: **not every European country necessarily has
   usable ORD coverage**, and rendering **fails gracefully** where it does not.
   Check product metadata for any license/source exceptions before display.
3. **NASA GPM IMERG via NASA GIBS** — global fallback outside NOAA and OPERA
   coverage. This is a **satellite precipitation estimate — NOT radar** — always
   labeled *"Satellite precipitation estimate"* and treated as **lower confidence**
   than NOAA/OPERA radar. Includes the required acknowledgement: *"Imagery/data
   provided by NASA Global Imagery Browse Services (GIBS), part of NASA Earth
   Science Data and Information System, and NASA GPM IMERG where applicable."*

**Aviation advisory data:** the **NOAA Aviation Weather Center Data API** —
existing METAR/TAF/PIREP/SIGMET source, unchanged.

No paid/unclear providers are included. The app does **not** integrate RainViewer,
Meteoblue, Meteomatics, OpenWeather, Tomorrow.io, The Weather Company, AccuWeather,
ForeFlight, Garmin, Windy (paid API), or any other commercial / paid /
non-commercial-only / trial / evaluation-limited provider. The precipitation
provider architecture ships exactly these conformers:
`NOAARadarPrecipitationProvider`, `EUMETNETOPERARadarProvider`,
`NASAGIBSPrecipitationProvider`, and `MockRadarPrecipitationProvider` (Mock
Mode/tests).

## Provider selection order

`PrecipitationOverlayService` selects one provider for the current route/region:

1. Inside **NOAA** radar coverage → NOAA/NWS radar precipitation.
2. Else inside **EUMETNET OPERA** (Europe) coverage → OPERA radar precipitation.
3. Else → **NASA** global satellite precipitation *estimate* (not radar).
4. If none covers the region → no overlay: *"Precipitation overlay unavailable for
   this region."*

UI labels: NOAA and OPERA both show *"Radar precipitation"*; NASA shows
*"Satellite precipitation estimate"*. The app **never** shows "global radar".

## Coverage limitations (read this)

- **True radar precipitation** is available only where the free **NOAA/NWS** (U.S.)
  or **EUMETNET OPERA** (Europe) sources provide coverage. Outside those, the
  overlay is a **NASA satellite precipitation estimate** (lower confidence, not
  radar), and above ~±60° latitude even that is unavailable — the app then shows
  *"Precipitation overlay unavailable for this region."* Forecast/model
  precipitation is **never** displayed as radar, and a satellite estimate is never
  labeled radar.
- **The app does not claim global radar coverage.** There is no global true-radar
  provider (see the discovery task below); NASA IMERG is a satellite *estimate*.
- **Global non-radar aviation weather may still be available** through the existing
  METAR/TAF/SIGMET sources, which can work outside the U.S. depending on the
  upstream data.
- **PIREPs/AIREPs** are treated as limited primarily to **U.S. and North Atlantic**
  coverage. Where none are available the app shows *"No recent reports available"*
  rather than implying a smooth ride. Missing reports never mean "smooth weather".
- **G-AIRMET** is treated as **contiguous-U.S. only** and is never presented as
  global.
- The simulated weather-deviation reroute (the mint line) is driven **only by
  moderate-or-greater precipitation** from true radar (NOAA/OPERA). Where true radar
  is unavailable there are no precipitation cells, so no reroute is offered — the app
  does **not** invent precipitation, and does **not** substitute a coarse SIGMET
  polygon for it. SIGMETs still shade the map, populate the SIGMET card, and raise
  the ride index.

## How the flow works

1. **Hazards.** The weather-deviation flow (the mint reroute line) is driven
   **only by moderate-or-greater precipitation cells** — the hand-authored cells in
   Mock Mode, or the cells sampled from the live radar image by `RadarImageSampler`
   (the "raster → cell" step): the app fetches a NOAA/OPERA base-reflectivity image
   for a **window around the aircraft and the route ahead** (~280 NM ahead, ±90 NM
   wide — not the whole dep→dest box, which under-resolves storms on long routes),
   classifies pixels by the reflectivity color ramp, and clusters the
   moderate-and-warmer returns into cells. Sampling is **continuous** — it resamples
   as the aircraft flies (throttled by time/distance in `maybeResamplePrecipitation`),
   not only on a manual refresh, so the reroute tracks the weather rather than going
   stale. On a fetch/decode failure it **keeps the last good cells** instead of
   wiping them, so a transient hiccup doesn't blink the mint line out. This is
   **true-radar only** and best-effort — outside NOAA/OPERA coverage there are no
   cells and no deviation is offered (rather than one invented from coarser data).
   The sampled cells drive geometry only and are never drawn (the radar image overlay
   already shows the precipitation). Radar is always spoken as *"precipitation"*,
   never *"turbulence"*.
   - **SIGMETs do not steer the reroute.** A SIGMET/AIRMET polygon is a coarse,
     often huge advisory box, not a precipitation shape — routing around it produces
     reroutes that ignore where the storms actually are. SIGMETs still shade the
     route map, populate the SIGMET card, and raise the composite ride index
     (`routeSigmets`); they just don't feed `buildWeatherHazards`. Turbulence
     wording remains reserved for PIREP / AIREP / SIGMET / G-AIRMET / CWA / ride
     reports elsewhere in the app.
   - **Turbulence / icing → altitude, not a lateral reroute.** A turbulence or icing
     SIGMET along the route has nothing to laterally route around — real ATC handles
     it by facilitating a climb or descent (smoother air, or out of the icing), and
     relaying ride reports. So when there is no precipitation conflict but a
     turbulence / icing SIGMET lies along the route (`activeRideSigmet`), the app
     raises an **altitude-change advisory** whose only response buttons are
     higher / lower / continue — never deviate / vectors. Precipitation always takes
     precedence when both are present.
2. **Conflict detection + gap threading.** `RouteWeatherConflictDetector` builds a
   corridor from the aircraft through the upcoming route fixes (lookahead 25–75 NM
   in the terminal area, 100–250 NM enroute, with a 30–120 minute groundspeed-based
   fallback) and finds the precipitation cells that block it. Rather than hopping
   around a single cell, it projects every nearby cell onto the cross-track axis,
   pads each by a lateral buffer, merges the overlaps, and **threads the widest
   clear gap** between adjacent cells — offering the reachable gaps and going around
   either end of a solid line. This mirrors how a controller vectors a pilot between
   cells, whether they appear just after takeoff, enroute, or on approach.
   - **Side-hug for lines along course.** A single dogleg abeam the middle of the
     line always aims at the same downstream rejoin, so when a long line lies roughly
     *along* the course (each end near the aircraft and near the destination), the
     shorter-side dogleg cuts back across the line to reach that rejoin and is
     rejected — leaving only the long loop around the far end. To pass such a line on
     the genuinely shorter side, the detector also offers **side-hug** candidates:
     step out to the line's outboard edge just before the near end, hold that offset
     parallel to course past the far end, then close to the rejoin. Because the
     parallel leg sits outside the widest excursion of the line, it clears every cell
     on that side.
   - **Shortest clear path wins.** All candidates — the gap/around-the-end doglegs
     and the two side-hugs — are validated end-to-end: the whole path is sampled
     against **every** cell polygon (so a reroute never avoids one storm and turns
     into another), and the one with the **shortest total length** that stays clear
     is flown. Ranking by true distance rather than smallest initial turn is what
     keeps the reroute from looping the long way around a line when the other side is
     shorter. If the aircraft is genuinely boxed in with no clear candidate, it falls
     back to the least-deviation dogleg.
   It also computes distance, clock position(s), estimated time, severity, the spoken
   deviation amount (the actual initial turn onto the threading path), and a
   downstream rejoin fix.
3. **Advisory.** When a conflict warrants prompting, ATCView shows a
   *"Weather ahead — contact ATC"* banner. Tapping **Contact ATC** (or, in the Mock
   demo, automatically) issues a simulated advisory ("area of heavy precipitation
   …, say intentions"), degrading gracefully to *"movement unknown"* /
   *"intensity unknown"* and to the SIGMET / no-advisory variants. The calls and
   read-backs address whichever radar controller is currently tuned — Departure on
   climb, Center enroute, Approach on arrival.
4. **Deviation.** The pilot can request a right/left deviation, vectors around
   weather, or higher/lower for weather. Simulated ATC approves — with a suggested
   deviation (default 20°, 10° for small/light cells, 20–30° for moderate/heavy,
   30° for extreme/convective) and either a downstream rejoin fix or, when none is
   suitable, *"advise clear of weather"*. On a STAR the altitude restriction is
   preserved with *"maintain …"* and the rejoin is framed as rejoining the arrival.
5. **Clear of weather.** When the pilot reports clear of weather, ATC clears direct
   the rejoin fix (or *"resume own navigation"* when already near the route), or
   rejoins the STAR.

The deviation flow runs during the airborne enroute/climb/arrival phases and works
with whichever radar controller is tuned (Departure, Center, or Approach).
It preserves altitude restrictions on a STAR,
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
