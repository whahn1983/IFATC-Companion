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
2. **EUMETNET OPERA (ORD / CIRRUS) radar composite** — Europe, where OPERA data is
   available. **True radar.** Labeled *"Radar precipitation"*; source *EUMETNET
   OPERA radar precipitation*. Honors **CC BY 4.0** attribution ("Radar
   precipitation data: EUMETNET OPERA / CIRRUS composite (CC BY 4.0)"). Prefers OPERA
   composite products in order — **maximum reflectivity → instantaneous rain rate →
   1-hour accumulation** — and cloud-optimized GeoTIFF over ODIM HDF5 for easier iOS
   rendering. Coverage is best-effort: **not every European country necessarily has
   usable composite coverage**, and rendering **fails gracefully** where it does not.
   Check product metadata for any license/source exceptions before display.

   > **Status: disabled in shipping builds (`useORD: false`).** On-device, decoding
   > the raw scientific `DBZH` GeoTIFF with ImageIO produces a garbled field — false
   > clutter speckle over clear ocean **and** little/no signal where precipitation is
   > actually heavy — because ImageIO can't faithfully read/scale the single-band
   > sample values. No keyless, rendered, cleanly licensed pan-European radar source
   > exists to replace it: **LibreWXR** (`api.librewxr.net`) is the closest — keyless,
   > RainViewer-v2-compatible rendered tiles that include the OPERA composite — but its
   > European composite blends in **DPC Italy data under CC-BY-SA 4.0 (share-alike)**,
   > which the app's attribution-only licensing model avoids, and its public instance
   > offers no production reliability (self-hosting is the intended model). Until a
   > validated source exists, OPERA still *covers* Europe but *cannot render*, so
   > selection falls through to the **NASA satellite estimate** (§3). The entire
   > ORD/renderer/store stack below stays in place — flip `useORD: true` (or configure
   > a WMS endpoint) in `PrecipitationOverlayService` to re-enable.

   **How it renders (ORD / CIRRUS).** ODYSSEY was retired in 2024; the current
   pan-European composite is produced by **CIRRUS** and published through the
   **EUMETNET Open Radar Data (ORD)** programme. There is **no public keyless
   *rendered* WMS/WMTS** for the composite — only the raw ODIM-HDF5 and
   cloud-optimized GeoTIFF data files — so the app renders the overlay itself:
   - `EUMETNETORDClient` reads the ORD **24-hour cache** *anonymously* — a public S3
     bucket (`s3://openradar-24h/YYYY/MM/DD/OPERA/COMP/…@DBZH.tif` at
     `s3.waw3-1.cloudferro.com`) requiring **no account, API key, or credentials**
     (the AWS-CLI `--no-sign-request` equivalent — plain unsigned HTTPS GETs). It
     lists the latest composite GeoTIFF for the product and fetches it.
   - `OPERACompositeRenderer` decodes the composite (ImageIO), reprojects it from the
     OPERA **Lambert Azimuthal Equal Area** grid (origin 55° N/10° E; projected
     extent derived from the documented corners) into a Web-Mercator PNG for the map
     bounding box — the same form the NOAA/NASA overlays use, so the existing image
     sampler and overlay renderer consume it unchanged. Precipitation intensity is
     classified conservatively (the standard reflectivity color ramp for colorized
     pixels; ODIM `DBZH` scaling for near-gray data pixels) so the overlay never
     *invents* precipitation from ambiguous data.
   - **Clutter/speckle suppression.** The raw *maximum-reflectivity* composite carries
     substantial non-meteorological echo (ground/sea clutter, anomalous propagation,
     interference "spokes", bioscatter, coverage-edge artifacts) that the public
     *rendered* products quality-control away. `OPERACompositeRenderer.denoise` drops
     classified cells that are not part of an 8-connected cluster of a minimum size,
     applied once at full raster resolution so both the map overlay and the
     route-corridor sampler get clutter-suppressed data instead of speckling clear
     ocean. Resampling is nearest-neighbor throughout (the raster is *classified*, so
     linear blending would fabricate reflectivity across no-data boundaries).
   - **On-device verification note.** The exact composite GeoTIFF encoding, the
     `DBZH`→intensity scaling, and the LAEA georeferencing are **best-effort and meant
     to be verified/tuned against real ORD composites on device** (the ORD S3 host is
     not reachable from CI). Every fetch/decode step **fails to `nil`**, and after a
     few consecutive render failures the OPERA provider is put in a short cooldown so
     selection **falls through to the NASA satellite estimate** rather than leaving
     the map blank while claiming OPERA coverage.
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

## Responsible use of the public services (no backend)

The app has **no backend** — every device talks to these free public services
directly — so all clients are written to be **well-behaved shared-resource
citizens** (`AppHTTP` centralizes the common bits):

- **Descriptive User-Agent with contact.** Every request identifies the app and a
  contact URL: `IFATCCompanion/<version> (+https://github.com/whahn1983/IFATC-Companion)`.
- **Poll no faster than the data updates, and not off-screen.** Aviation weather is
  event-driven (on connect / route change / manual refresh / a ride-report or
  destination-weather request) **plus a slow periodic refresh on a 5-minute interval
  while a feed is active**, so the PIREP/ride-report pool stays current through a long
  flight instead of freezing at the connect-time snapshot. The interval matches the
  in-memory TTL, so each tick revalidates rather than re-serving cached bytes, and the
  network is never hit faster than the data updates. The radar overlay renders only
  while the weather map is on screen; radar *sampling* runs only while airborne **and in
  the foreground** (gated on app-active), never on a background tick.
- **Cache, and revalidate conditionally.** Responses are cached (in-memory TTL +
  an on-disk `URLCache`), and network revalidation uses **ETag / If-None-Match** and
  **Last-Modified / If-Modified-Since** (`.reloadRevalidatingCacheData`), so a `304`
  reuses cached bytes.
- **OPERA/ORD specifics.** The CIRRUS composite updates every ~5 min, so the client
  refreshes on a **5–8 minute jittered interval** (de-synchronizing devices), does the
  **cheap listing first and skips the multi-MB GeoTIFF download when the product
  timestamp is unchanged**, and shares one decoded composite across all overlay/sampling
  renders. The ORD docs note anonymous access has *low query limits* and *is not
  recommended for permanent usage*, which is exactly why these limits are enforced.
- **Data usage is measured, not assumed.** Unlike NOAA/NASA (small server-cropped
  PNGs, ~KB), the CIRRUS composite is a whole-Europe file the app downloads and renders
  itself, so it is the only megabyte-scale source (4400×3800 @ 1 km single band ≈
  16.7 MB / 33 MB uncompressed at 8/16-bit; a compressed COG is typically smaller, but
  the delivered product may also carry a quality band, overviews, and masks). The
  Weather Diagnostics panel reports the **actual bytes downloaded** (latest + session
  total) so real measurements can replace the estimate.
- **Back off on throttling/outages; prefer stale over failing.** On `429`/`503`/`5xx`
  or a network error the clients **back off exponentially** and honor **`Retry-After`**,
  and they **serve the last good cached data** rather than blanking. Non-retryable
  errors (e.g. `400`) don't trigger backoff. Missing products, partial responses, and
  temporary outages degrade gracefully (OPERA falls through to the NASA estimate).
- **Not `api.weather.gov`.** Aviation weather uses the **AWC Data API**
  (`aviationweather.gov/api/data`), so the app makes **no** `/points`, forecast-office,
  gridpoint, station-list, or alerts-metadata calls that would need separate long-lived
  caching.

## Provider selection order

`PrecipitationOverlayService` selects one provider for the current route/region:

1. Inside **NOAA** radar coverage → NOAA/NWS radar precipitation.
2. Else inside **EUMETNET OPERA** (Europe) coverage → OPERA radar precipitation
   *when it can render*. OPERA's ORD render is **currently disabled** (see §2 above),
   so in practice Europe falls through to case 3 today.
3. Else → **NASA** global satellite precipitation *estimate* (not radar).
4. If none covers the region → no overlay: *"Precipitation overlay unavailable for
   this region."*

UI labels: NOAA and OPERA both show *"Radar precipitation"*; NASA shows
*"Satellite precipitation estimate"*. The app **never** shows "global radar". While
OPERA is disabled, Europe shows the NASA *"Satellite precipitation estimate"* label.

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
  moderate-or-greater precipitation** from true radar (NOAA/OPERA) by default. Where
  true radar is unavailable there are normally no precipitation cells, so no reroute is
  offered — the app does **not** invent precipitation, and does **not** substitute a
  coarse SIGMET polygon for it. SIGMETs still shade the map, populate the SIGMET card,
  and raise the ride index.
  - **Opt-in exception — satellite-estimate deviations.** A Weather Data setting,
    *"Deviations from satellite estimate"* (`satelliteDeviationsEnabled`, **off by
    default**), lets the deviation flow also run from the **NASA global satellite
    estimate** where there is no radar. It is off by default because the estimate is
    coarse (~10 km), latent (hours), and cannot reliably grade severity, so its cells
    are decoded with the **IMERG rate palette** (§How the flow works) and tagged
    **low-confidence** and sourced as `.satelliteEstimate` — never presented as radar.
    When off, satellite coverage still shows the overlay image but draws no deviation.

## How the flow works

1. **Hazards.** The weather-deviation flow (the mint reroute line) is driven
   **only by moderate-or-greater precipitation cells** — the hand-authored cells in
   Mock Mode, or the cells sampled from the live radar image by `RadarImageSampler`
   (the "raster → cell" step): the app fetches a NOAA/OPERA base-reflectivity image
   for the **whole flight-plan corridor** (the aircraft and every fix ahead through the
   destination — the entire route from the gate, the remaining route in flight — widened
   ~60 NM on every side), classifies pixels by the reflectivity color
   ramp (or, for an opted-in NASA satellite estimate, the IMERG rate palette, which keeps
   the broad blue/green low-rate wash as light but promotes the yellow-green band to
   moderate to offset satellite under-estimation of cores), and clusters the
   moderate-and-warmer returns into cells. Sampling the entire
   route — not just a window ahead — is what lets every system's reroute be seen at once,
   including the faint strategic previews **from the gate before takeoff**. To avoid the
   old whole-route problem (a fixed grid over a long route under-resolved storms and
   "cleared" weather still dead ahead), the **sample resolution scales with the corridor
   size** to hold ~2 NM per pixel, floored for short routes and capped for transcon ones.
   The sampled image is sized to the corridor bbox's **exact Web-Mercator aspect ratio**
   (`RadarImageSampler.mercatorSampleSize`), so the EPSG:3857 render comes back registered
   to that bbox: a mismatched aspect makes the source adjust the returned extent (ArcGIS
   ImageServer) or stretch the render (WMS), which would drift every sampled cell — pulled
   toward the corridor's centre, tens of NM — off the displayed radar. Sampling is
   **continuous** — it resamples so the
   reroutes track the weather rather than going stale; because the region is the whole
   route (not aircraft-relative) it barely changes as the aircraft flies, so
   `maybeResamplePrecipitation` is driven mainly by staleness (~60 s) with a large
   movement backstop rather than re-fetching the bigger image every mile. On a
   fetch/decode failure it **keeps the last good cells** instead of wiping them, so a
   transient hiccup doesn't blink the mint line out. This is **true-radar only** by
   default and best-effort — outside NOAA/OPERA coverage there are no cells and no
   deviation is offered (rather than one invented from coarser data), unless the user
   opts in to satellite-estimate deviations (see Coverage limitations), in which case
   the NASA estimate is sampled with the IMERG palette and tagged low-confidence.
   The sampled cells drive geometry only and are not drawn on the map by default (the
   radar image overlay already shows the precipitation); an opt-in Diagnostics toggle
   ("Show sampled cells on map") can draw them as colored polygons to confirm they line up
   with the radar returns. Radar is always spoken as *"precipitation"*,
   never *"turbulence"*.
   - **Re-evaluated on a flight-plan change.** Detection reads the live flight plan
     (waypoints, upcoming route, rejoin cap) fresh on every telemetry tick, so a plan
     change in flight is reflected on the next tick. A change made while disconnected /
     paused wouldn't otherwise re-run, so the manual-edit path (`syncFlightPlanFromSettings`
     → `applyManualOverrides`) recomputes immediately, and a change to the **route**
     (endpoints or waypoints) invalidates the radar sample so the next sample covers the new
     corridor rather than the old one — the mint line and previews follow the new plan
     rather than the old one. A *committed* deviation stays locked until clear-of-weather or
     a fresh vectors request.
   - **SIGMETs do not steer the reroute.** A SIGMET/AIRMET polygon is a coarse,
     often huge advisory box, not a precipitation shape — routing around it produces
     reroutes that ignore where the storms actually are. SIGMETs still shade the
     route map, populate the SIGMET card, and raise the composite ride index
     (`routeSigmets`); they just don't feed `buildWeatherHazards`. Turbulence
     wording remains reserved for PIREP / AIREP / SIGMET / G-AIRMET / CWA / ride
     reports elsewhere in the app. Because they never drive a deviation, **every**
     route SIGMET is shown on the map — relevance is tested against the whole route
     polyline (all legs, not just the straight line to the destination), and the
     lower-severity advisories (IFR / icing / mountain wave, drawn gray) are no longer
     hidden behind a severity filter.
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
   in the terminal area, 80–180 NM enroute, with a 20–45 minute groundspeed-based
   fallback) and finds the precipitation cells that block it. Rather than hopping
   around a single cell, it projects every nearby cell onto the cross-track axis,
   pads each by a lateral buffer, merges the overlaps, and **threads the widest
   clear gap** between adjacent cells — offering the reachable gaps and going around
   either end of a solid line. This mirrors how a controller vectors a pilot between
   cells, whether they appear just after takeoff, enroute, or on approach.
   - **Only for weather *on* the flight path — with an intensity-scaled corridor.** A cell
     counts as a conflict only when it is on the route — within the corridor half-width of
     the course centerline, or crossed by it. The half-width **scales with intensity**, so it
     matches how much room the reroute keeps from each: **moderate ±6 NM** (kept tight — a
     yellow cell off to one side isn't worth a deviation), **heavy ±12 NM**, and **extreme
     ±18 NM** (a red core the route skirts is rounded by a ~20 NM berth anyway, so flagging it
     from that far off is what stops a live "clear red hazard on the route, but diagnostics say
     no conflict"). A cell that actually straddles the centerline is caught regardless of its
     intensity. Weather merely *near* the route stays off-path: a **moderate** cell off to one
     side still draws nothing — only the tight moderate corridor governs it.
   - **Mint line a little ahead, banner only close in — far weather monitored.** Three
     ranges, from close to far:
     - **Tactical (`deviationTriggerNM`, ~60 NM).** The near edge is close enough to work
       the deviation now: the mint line is drawn, the "contact ATC" banner is raised, and
       (in Mock Mode) the advisory auto-issues. This is the realistic range for a tactical
       convective deviation (pilots avoid severe echoes by ~20 NM laterally per FAA
       AC 00-24C and start deviating ~20–40 NM out, with ATC coordinating a little earlier).
     - **Draw range (`mintLineDrawNM`, ~75 NM).** A little beyond tactical: the mint line
       is drawn as advance notice so the pilot sees the suggested reroute a bit before the
       banner, but the banner / advisory hold off.
     - **Beyond the draw range, out to the lookahead.** The conflict is still *detected*
       and monitored, but the mint line is **not drawn**. The reroute is a straight-corridor
       offset aimed at the blockage; for weather far ahead — typically past one or more of
       the route's bends — drawing it produced a long line that shot across the map toward
       distant weather (the "crazy mint line" with no weather nearby). Holding the line
       until the aircraft is roughly committed toward the weather keeps the drawn geometry
       meaningful.

     The conflict carries `withinTacticalRange` and `withinDrawRange`; Diagnostics shows a
     conflict outside the tactical range as "… — monitoring" rather than "No conflict".
   - **The corridor follows the route.** The detection band is narrow (±6–18 NM by
     intensity), so a straight corridor aimed at the *bearing to the next fix* misses weather
     that sits
     on the route **after a turn** — the aircraft's wide sampling window still finds
     the cells (so Diagnostics shows hazards), but the narrow band slides past them
     and reports "no conflict". So the detector walks the **upcoming route polyline**
     (the fixes still ahead → destination, within the lookahead), finds the nearest
     point on it that comes within a corridor half-width of a cell, and aims the
     corridor from the aircraft at that blockage — turning the band down-route so a
     storm on a later leg is caught. With no route supplied, or nothing on it blocked,
     it keeps the straight bearing (unchanged behavior).
   - **"Ahead" is by projection onto the route, not distance from the departure.** The
     upcoming polyline (`upcomingRouteCoordinates`) is the filed route past the aircraft's
     **projection onto it**, so it always matches the drawn route. An earlier heuristic
     picked the fixes whose straight-line distance from the departure exceeded the
     aircraft's — which quietly dropped an upcoming fix wherever the route jogged (a later
     fix nearer the departure than the aircraft), reshaping the corridor away from the drawn
     line the instant telemetry arrived. That produced the "detected 51 hazards but **No
     conflict**, and the reroute drew perfectly while disconnected then vanished the moment
     the aircraft reconnected" failure: at the gate the departure-distance test keeps the
     whole route (progress 0), so it only bit once a live position was known.
   - **Side-hug for lines along course.** A single dogleg abeam the middle of the
     line always aims at the same downstream rejoin, so when a long line lies roughly
     *along* the course (each end near the aircraft and near the destination), the
     shorter-side dogleg cuts back across the line to reach that rejoin and is
     rejected — leaving only the long loop around the far end. To pass such a line on
     the genuinely shorter side, the detector also offers **side-hug** candidates:
     step out to a lateral offset just before the near end, hold that offset parallel
     to course past the far end, then close to the rejoin — the real-world weather
     deviation (turn out, parallel the line, rejoin when clear).
     - The hug offset is the **minimum offset that clears every cell** on that side —
       found by searching outward from the base margin until the whole path is clear,
       *not* the outboard edge of the entire clustered line. A cell well off to the
       side (within the along-track cluster window but far cross-track) would otherwise
       drag the parallel leg way out; taking the tightest clearing offset keeps the hug
       close to the flight plan. Because it stays close, the shortest-clear selector
       picks it **early** — so the line hugs the weather from the start instead of the
       aircraft diving wide and only tucking back in once the cluster thins downrange.
     - **Never closer than the minimum lateral separation (`minParallelOffsetNM`, 20 NM).**
       The tightest clearing offset above can be only a few NM off course — for a
       moderate/heavy cell that keeps just the base margin (~3 NM), or a cell that sits
       entirely to one side so the opposite-side hug clears at the base buffer. A real
       weather deviation instead turns well off course and parallels the weather with a
       wide berth, so the settled offset is **widened out to at least 20 NM from the
       flight path whenever the wider leg still clears every cell** (`atLeastMinOffset`).
       Where widening would re-enter weather it is *not* forced: threading a genuine gap
       *between* two cells (you cannot hold 20 NM off centerline and still fit inside a
       ~20 NM gap) or a boxed-in system keeps the tightest clearing offset. The
       single-apex gap-threading dogleg — which flies *between* cells rather than
       alongside one — is exempt; this governs the parallel legs drawn alongside a
       system. It applies to every parallel-hug generator (the fixed side-edge and
       tightest-clearing hugs, the variable-offset edge-following hugs, and the
       whole-system multi-leg hug), so no parallel leg is drawn closer than 20 NM.
     - The **initial turn-out is a realistic ~30°**, not a 90° sideways step: the hug
       reaches its offset over enough along-course distance (`initialDeviationTurnDegrees`)
       to make the first leg a genuine deviation. When the weather sits right at the
       aircraft (near edge ≈ 0) the forward-angled start would cut back through the cell,
       so a steeper turn-out is offered as a fallback and validation picks whichever
       stays clear.
     - The parallel leg **turns back at the rejoin, never past it**: the far corner is
       capped to the along-distance where the route exits the weather, so the line does
       not run out to the far edge of distant off-route cells and then double back across
       the intercept.
     - **Variable-offset, multi-leg hug for complex shapes.** The fixed-offset hug
       parallels the line at one width (the tightest that clears the whole side), which is
       wider than necessary when the line is *staggered* — near cells close to course, far
       cells bulging wide. So the detector also offers an **edge-following** hug: the convex
       upper hull (`upperHull`) of every clustered cell's projected corners on that side,
       offset outboard by the berth, traced as **as many legs as the shape needs** (turn
       out to the near offset just before the weather, follow the edge in/out, rejoin).
       Being convex it never zig-zags inboard, so it always stays outboard of every (convex)
       cell. It is added on top of the fixed hugs and validated by `pathIsClear` like every
       candidate, so the shortest-clear selector adopts it only where following the edge
       genuinely beats paralleling at the single widest offset — a tighter reroute around a
       complex line, without ever going wider than the fixed hug it replaces.
   - **Rejoin past the first system, not the farthest weather on the route.** Every
     candidate returns to the route at the point where the route exits the **first weather
     system** — the first contiguous run of cells, merged across clear gaps smaller than
     `systemSeparationNM` (the tuned packed-systems knob, ~30–50 NM) and ended by a larger
     gap. It does *not* stretch the drawn line to the farthest weather anywhere on the
     route: with precip scattered down a long route, taking "the farthest cell + 20" put
     the rejoin near a downstream system or the destination, so the single line was drawn
     *past* the first storm and ended near the airport — surrounding nothing. Rejoining at
     the first system's exit keeps the line compact around it; each later system is worked
     separately (the preview walker steps to each in turn). The same `systemSeparationNM`
     governs the along-track cluster window the hug parallels, so the parallel leg and the
     rejoin always agree on the system's extent — the closing leg can't be pulled past a
     system the parallel leg stopped short of. Three further points still hold. First,
     chasing a distant fix forces the closing leg of a short one-side deviation to swing
     back across the storm, so that candidate gets rejected and the reroute either loops
     the long way or, up close, drives straight through a core. Second, the rejoin follows
     the route's **bends**: if the route turns (say south) just past the storm, the
     intercept is on that turn, so the reroute's length is measured to the real rejoin and
     the shorter (southern) side wins. When no route is supplied it falls back to returning
     to course just past the far edge. The nearest downstream fix is still selected and
     named for the ATC rejoin call ("proceed direct …"); it simply lies on ahead of where
     the drawn line rejoins.
   - **Adjacent deviations fold into one parallel run.** The whole-route walk works each
     system separately, so a *complex* multi-cell system — cells packed just far enough
     apart that each is its own "system" — produced a string of short in-and-out hugs, each
     turning out, paralleling one cell, and dipping back to the route right where the next
     cell begins (so a rejoin often landed *inside* the next hazard). After the walk,
     `mergeAdjacentDeviations` (in `RouteWeatherConflictDetector`) folds a run of these
     together whenever the rejoin of one sits within `mergeAdjacentGapNM` (~30 NM) of the
     next one's turn-out **and both hug the same side**: it keeps the first turn-out, threads
     every offset vertex of the run into one line, drops the dips back to the route between
     them, and rejoins only at the last hug's rejoin — one long parallel deviation down the
     whole system, exactly what a pilot threading it would fly. Only the *connector* legs
     across the gaps are re-validated (each constituent hug already clears every core), so a
     hug whose own rejoin lands in the next cell still folds in. A run that would rejoin in a
     cell has its final rejoin **slid forward along the route to clear air** (closing leg
     kept clear of the cores), so the merged line no longer terminates in a hazard. Runs that
     hug opposite sides, or are separated by more than the gap, are left split. The interior
     turns are still walked generically at each vertex, so the folded line's ATC turn-by-turn
     is unchanged — it just has more legs.
   - **Red cores get a wide berth.** Clearance is per-cell by intensity: a
     red/extreme return demands a wide berth (`severeBerthNM`, ~20 NM per FAA AC
     00-24C guidance for severe echoes) while moderate/heavy cells keep the base margin. That berth is applied both to path
     validation and to the gap/side-hug spacing, so a reroute rounds a convective core
     well clear instead of shaving past it — or threading a coarse-sampled gap
     straight through one. When boxed in, the fallback picks the path that intrudes
     least on those berths, so the red cores keep the most room available.
   - **Tight to the storm; wide only as a last resort.** All candidates — the
     gap/around-the-end doglegs and the side-hugs — are **finalized first** (capped +
     turn-bounded, see below) and then validated end-to-end against **every** cell
     polygon, so what is ranked and flown is exactly the line drawn. Routine candidates
     are bounded to `searchHalfWidthNM` (~60 NM) off course, and the pick is made in a
     priority order that keeps the line close to the weather and **prefers the 3-leg /
     4-point parallel hug over the 2-leg / 3-point single-apex triangle** (each candidate
     carries a `parallel` flag, and the selector takes the shortest parallel hug when one
     clears before falling back to a triangle):
     1. the **shortest routine-width path clear of every cell** — a parallel hug when one
        clears, else a triangle (e.g. a gap-threading dogleg no straight parallel offset
        fits);
     2. else the shortest routine-width path that clears the **intense (heavy/extreme)
        cores** while skirting lighter (moderate) precip — again preferring the parallel
        hug — so a broad area of moderate returns is passed close rather than looped
        around wholesale;
     3. else, **only as an absolute last resort**, the shortest *wide* detour (out to
        `maxDetourOffsetNM`) that clears every cell — taken solely when nothing tight can
        even dodge the intense cores;
     4. else (genuinely boxed in) the routine path that keeps the **most room from the
        intense cores** — never the straight-through least-deviation dogleg.

     Preferring the parallel hug matches how real weather deviations are flown — turn out
     ~30°, parallel the weather, turn ~30° back — rather than cutting a single wide turn
     around it, even when that triangle to the shared rejoin would be a shade shorter.
     Ranking by true distance (not smallest initial turn) keeps the reroute on the
     genuinely shorter side, and dropping any candidate wider than the bound stops a
     broad line from emitting a runaway around-the-end loop far from the route. The
     intense cores are **always** avoided when any path — tight or wide — can; only a
     genuine box-in ever brings the line near one, and then it keeps the most room it can.
   - **Bounded turns — never reverse the aircraft.** Every leg of the drawn line is
     clamped to at most `maxDeviationTurnDegrees` (100°) off the course. ATC vectors
     around a storm; it never turns an aircraft the long way around, so any leg that
     would point further back is pulled onto the bound. The assigned vector and the
     auto rejoin turn are derived from the clamped line, so they can't command a
     near-180° reversal either.
   - **Always ends short of the airport — at least 20 NM out.** The reroute rejoins the
     route no deeper than a **cap** (`rejoinCap`), set by `AppModel.weatherRejoinCap()` to
     the point on the filed route **at least `weatherRejoinAirportMarginNM` (20 NM) before
     the airport**, measured along the route — and, when the plan names an approach fix
     farther out than that (`FlightPlan.approachStartCoordinate`), held at the fix instead
     (whichever is farther from the field). So a mint line always terminates on the flight
     path well short of the field rather than ending right on top of it — even with weather
     sitting on the destination — and never routes into the approach. Every vertex past the
     cap's along-course distance is pulled back to it (`clampPathToAlong`); the merged-hug
     rejoin slide is likewise bounded by truncating its route at the cap, so a folded
     multi-cell system can't step its rejoin past the 20 NM margin either.
   - **Never starts within 20 NM of the departure — the mirror of the airport cap.** The
     whole-route deviation walk (`recomputeLockedDeviations` → `computeDeviations`) begins the
     search `weatherRejoinAirportMarginNM` (20 NM) along the route *past* the departure end,
     so the first mint line never starts within that distance of the departure airport —
     weather on the immediate climb-out is worked by departure vectors, not a drawn enroute
     deviation. Because the turn-out is only ever shaped *forward* from the detection start
     (`startAtTurnOut` never moves it behind the start), flooring the walk floors every drawn
     line's turn-out. Applied only when the departure end of the route is known — not the
     aircraft-position fallback, where skipping ahead would drop weather right in front of the
     aircraft.
   - **Never left drawn behind the aircraft — redrawn 20 NM ahead, and ATC says so.** The
     lines are solved for the whole route and then held, so the aircraft can end up past the
     turn-out at the start of one: the pilot ignored the banner and flew by it, or a position
     jump carried the aircraft beyond it. The reroute is then drawn *behind* the aircraft,
     where it can no longer be flown. So whenever the active deviation's entry point falls
     behind the aircraft (measured along the filed route, so a missed entry counts the same as
     one flown past), the deviations are **re-solved starting `deviationRedrawAheadNM` (20 NM)
     in front of the aircraft** — far enough ahead to leave room to work the new turn — and the
     controller advises the revised deviation (*"weather deviation updated, revised deviation
     now begins 20 miles ahead"*). The call **assigns nothing** — it carries the courtesy
     *"Roger"* as its read-back, attached to the call itself so **Read Back** acknowledges this
     advisory instead of falling through to a stale read-back re-derived from the
     conversational state — but it does **open the decision**, because the revised deviation is
     the pilot's to activate. Unless the pilot is already deciding (the response card is up, so
     a pending decision and the near-turn advisory still to come stand exactly as they were) or
     is already flying an approved deviation (there is nothing to activate — and a committed
     line is never redrawn anyway), the lifecycle moves to **awaiting-intentions**: the
     response card and its request buttons (**Vectors**, left/right deviation, higher/lower,
     continue) come up with the call, seeded from the redrawn line, so the revised deviation
     can be activated on the spot rather than waiting for the near-turn advisory to raise it.
     The redraw point becomes a **walk floor** so a later
     recompute (the 5-min auto-refresh, a pull-to-refresh, a fresh radar sample) can't step
     back behind the aircraft and re-produce the same stale line; it is cleared on a route
     change. If nothing solves from 20 NM ahead (the weather is now abeam or behind, or the
     route ends first) the stale line is simply dropped — including its confirm-clear hold —
     with no call, rather than left drawn behind the aircraft.
     A **committed** deviation is never redrawn: the pilot is already flying that frozen line,
     whose start legitimately falls behind once the turn is made, and a held beginning turn
     still fires as the aircraft passes abeam its turn-out.
   - **Starts at the turn-out, not the aircraft — a ~30° dogleg out and back.** A reroute
     drawn far ahead must not drift shallowly from the aircraft across the whole distance
     to the weather. The chosen path is reshaped so it **begins at the turn-out point** —
     a lead-in just before the weather sized so the first leg is a ~30° turn onto the
     offset (`initialDeviationTurnDegrees`), rather than starting at the aircraft
     (`startAtTurnOut`) — and **rejoins with a matching ~30° turn-back** on a straight
     route (`endAtTurnBack`). The turn-back is **symmetric**: only the rejoin vertex moves
     — pulled back to steepen a too-shallow intercept, or pushed forward (a matching lead
     beyond the parallel-leg end, within the rejoin cap) to open up a too-steep one, so the
     closing leg is never the ~90° sideways jog back onto the route that a compressed rejoin
     used to produce. Where the cap (or a downstream system) leaves no room the step stays
     steep — the packed-systems case, handled by extending the parallel leg past the next
     system. Weather close aboard keeps the start at the aircraft with a necessarily steeper
     turn. Every turn on the drawn line is therefore at least ~30°,
     and the whole maneuver spans at least `minDeviationExtentNM` (15 NM) end-to-end
     (`enforceMinExtent`) so it never renders as a twitch. The reshaping only ever touches
     the lead-in / lead-out on the course line ahead of and behind the (already-clear)
     offset legs.
     - **Clip-aware, so a wide core doesn't force the transition square.** The ideal ~30°
       turn-out reaches the offset right at the weather's near edge; a red/extreme core needs
       a wider berth than that vertex sits at, so the diagonal onto the offset (or off it at
       the rejoin) can clip the core a few miles before/after the weather. Rather than
       collapse back to a square 90° step, `startAtTurnOut`/`endAtTurnBack` **pull the
       turn-out earlier / push the turn-back later into clear air** — holding the ~30° angle
       and extending the parallel leg to meet it — until the transition leg clears the cores.
       They stop only at the real limits: the turn-out can't begin behind the aircraft (the
       "pilot turned late" close-aboard case) and the turn-back can't push past the rejoin cap
       (weather near the destination) — the two cases where a steeper turn is genuinely
       unavoidable. As a final guard the whole reshaped line is re-validated against the
       intense cores, and only if no clear ~30° transition could be fitted is the validated
       original kept.
     - **A final gentle-intercept safety net (`gentleInterceptAngles`).** The per-candidate
       shaping above runs *before* the route-intercept truncation, on-route snapping, and
       rejoin-cap clamp in `detectConflict` — any of which can re-introduce a ~90° sideways
       jog or even a backwards intercept (a bent route whose rejoin lands off the course axis,
       or weather packed against the cap). So as the very last step every drawn hug (≥ 4
       points) has its opening and closing legs re-checked: a leg steeper than ~50° off course
       is reshaped to a gentle ~30° intercept by sliding the single adjacent parallel-leg
       vertex along course — the far vertex pulled back for the exit, the near vertex pushed
       forward for the entry — keeping the turn-out / rejoin points themselves on the route.
       Each reshape is kept only when the whole path still clears the intense cores, so a valid
       reroute is never bent into a core; where weather is genuinely packed against the rejoin
       cap and no gentle leg fits, the steep-but-clear leg is left. A triangle / gap-thread
       dogleg (< 4 points), whose single apex can't move without changing the detour, is left
       as-is.
   - **Must actually engage the weather (`pathEngagesWeather`).** Clearance validation
     (`pathIsClear`) only proves a candidate stays *clear* of every cell — a line drawn out
     in clear air, nowhere near the storm, passes it trivially (and can even rank as the
     "shortest"). So before a recommended line is drawn (the solid active line and the faint
     previews, in `AppModel`), it is re-checked: some interior point must come within
     `maxDistanceNM` (~45 NM) of a moderate-or-greater cell. A line that stays far from every
     cell does **not** engage the weather and is **suppressed** rather than shown as a mint
     line with no weather near it. A committed (frozen) line the pilot is already flying is
     never suppressed.
   - **The faint previews additionally require their apex to hug weather
     (`previewApexHugsWeather`).** `pathEngagesWeather` only asks that *some* point of the
     line be near a cell — which a "sharp angle out and back" spike satisfies when its *base*
     sits by a cell while its *apex bulges into clear air*. That shape is what a preview draws
     when a straight-corridor reroute is aimed across a route bend (e.g. the arrival turn) and
     truncated there. The solid line never shows it because it is held until the weather is
     within `mintLineDrawNM` (route to it essentially straight); the preview has no such gate,
     so it gets a stricter check — the vertex that bulges **farthest off the filed route** must
     itself be within a berth (~30 NM) of a moderate-or-greater cell. A preview whose apex is
     out in clear air, nowhere near precipitation, is hugging nothing and is dropped.
   - **Must actually leave the flight path (`pathLeavesRoute`).** The two guards above catch
     a line drawn *away* from the weather; this one catches its opposite — a line drawn right
     **on top of the filed route**, recommending the course already being flown. Neither of
     the others sees it: an on-route line passes `pathEngagesWeather` trivially (the route
     runs into the cell), and `previewApexHugsWeather` returns true for anything that barely
     leaves the route, having no apex to test. `minDeviationExtentNM` doesn't either — it
     bounds the maneuver's *length*, not its offset. So the drawn line's **lateral excursion**
     from the filed route (its farthest vertex, measured in `AppModel` once the path is finally
     shaped — after the adjacent-deviation merge and the gentle-rejoin softening, both of which
     move vertices) must reach `minRouteExcursionNM` (5 NM, the excursion below which
     `previewApexHugsWeather` stops looking for an apex, so the guards meet without a gap).
     Two constructions produce the degenerate shape: a threadable gap centered on the course
     (the single-apex dogleg is exempt from `minParallelOffsetNM`, so nothing widens it) and
     the zero-offset fallback taken when no candidate could be built at all. The first is fixed
     at the source — a thread landing within the floor is slid to the roomier side of its gap
     (`nudgedOffRoute`), kept only when the slid path still clears every cell, and taken *in
     place of* the centered one, since the shortest-path selector would otherwise always prefer
     the zero-offset original. Where the slot is too tight to hold the floor, the centered
     thread is kept and simply not drawn. Suppression is **display-only and total**: the solid
     line, the faint preview, and the rejoin marker are all withheld, and a withheld line is
     never frozen as a committed path either (which would put it back on the map ahead of every
     guard) — but the conflict stays in the locked set, so the weather is still detected, still
     raises the banner and the advisory, and the pilot can still request vectors. Diagnostics
     says so explicitly ("… — no lateral deviation available") rather than reporting a conflict
     the map shows no line for.
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
   - **The beginning turn is issued at the start of the mint line — held when drawn
     ahead.** Because the reroute is drawn beginning at its turn-out point (which sits
     ahead of the aircraft when the weather is still some distance off), requesting a
     deviation there does not turn the aircraft immediately. ATC approves but **holds the
     turn**: *"deviation right of course approved, maintain …, continue present heading,
     expect the turn in X miles"* (`deferDeviation` / `expectDeviation`). The aircraft
     flies the filed course to the turn-out; once it reaches it the controller issues the
     beginning turn (*"fly heading …, vectors around precipitation"*) and the interior
     auto-turns follow (`maybeIssueDeviationStartTurn` → `beginDeviationTurn` →
     `captureWeatherRejoinTurn`). Weather close aboard (the turn-out within
     `deviationTurnHoldNM`) is worked immediately, as before.
   - **"Ahead" is a direction, not a straight-line distance.** A turn-out the aircraft has
     already passed abeam is still tens of miles away as the crow flies. Read as a distance
     it looks like a turn comfortably ahead — so the request was held, the pilot told to
     "expect the turn in X miles", and the beginning turn pinned to a point *behind* the
     aircraft, where the reach test can never be satisfied: the turn was never called and
     the aircraft flew on through the weather waiting for it. So `deviationTurnOutAhead`
     (and the near-turn auto-advisory) use `turnOutAheadNM`, which goes negative once the
     point is behind. It takes the better of two measures — along the aircraft's **track**
     (right when it is off course, but a route bend can momentarily swing the instantaneous
     track off a turn-out that is genuinely ahead) and along the filed **route** (right on
     course, the same projection `deviationEntryIsBehind` uses) — since a turn-out is only
     really behind when it is behind by both. A request made against a line whose turn-out is
     already behind re-plans it from the aircraft's current position first
     (`reanchorDeviationIfTurnOutPassed`), so what the controller approves is what can be flown.
   - **A held turn can never be waited on forever.** If the aircraft passes the armed
     turn-out anyway — a late request, a re-locked line, a fix that jumped —
     `releaseStaleDeviationHoldIfPassed` re-plans from the current position
     (`replanHeldDeviation`): re-held at a fresh turn-out still ahead (with the revised
     distance announced, since the pilot was given the old one), vectored onto the reroute
     now when the aircraft is already at/past it, or — when nothing solves from here — the
     clearance is **cancelled** (*"weather deviation cancelled, resume own navigation, advise
     if you need to deviate"*, `cancelHeldDeviation`) and the lifecycle ended. Both halves of
     that last case matter. It has to be **said**: the pilot is holding a clearance to continue
     on course and expect a turn, so withdrawing it in silence leaves them flying toward a turn
     that will never be called. And it has to return the lifecycle to idle: `.deviationApproved`
     counts as `isCommittedDeviation`, so the per-tick rollback in `updateWeatherConflict`
     deliberately skips it, and a context left approved-but-unarmed is a dead end for the rest
     of the flight — no turn can fire, and no later conflict can prompt afresh. What it must
     *not* do is clear `weatherHandled`: the weather ahead has just been worked (the pilot asked
     and was approved), and re-arming the near-turn auto-advisory against that same conflict is
     what had the controller re-open with *"…say intentions"* seconds after cancelling the
     clearance for it. The flag clears on its own once the route genuinely reads clear; until
     then the banner is the way back in.
   - **An accepted deviation is never cancelled off an untrustworthy fix.** The same re-plan
     runs on the first fix after a background gap (`resyncWeatherDeviation`), and there the
     radar sample is stale, the aircraft has jumped, and nothing may re-solve from the new
     position — which is not evidence that the deviation should be torn up. It is the very
     reading `resolveConflictWithHysteresis` refuses to believe a clear route from. So the
     resync passes `trustedFix: false`: a re-plan that finds nothing leaves the approved
     deviation and its held turn exactly as they stand, and the next continuous tick decides.
     Otherwise the pilot came back from the background to an accepted "pressed vectors" route
     silently forgotten and re-advised from scratch, with the response card gone. For the same
     reason the discontinuity tick **restarts** the confirm-clear window rather than merely
     surviving it — the window is wall-clock, so one left running from before the gap has
     already expired, and the tick *after* the guard would otherwise drop the conflict on its
     first empty sample, tearing down an issued-but-unanswered advisory one tick late.
   - **Turn distances are rounded once, to fives.** Weather is described in tens (a cell's
     distance is never that precise), but a turn the pilot is about to fly is rounded to the
     nearest 5 NM by `deviationTurnOutAhead` and spoken as given. Rounding again to tens in
     the phraseology inflated it: a turn-out 13 NM ahead became 15, and 15 rounds *up* to
     "20 miles" — so the same turn the advisory had just called "10 miles" was announced as
     20 miles ahead.
5. **Clear of weather.** When the pilot reports clear of weather, ATC clears direct
   the rejoin fix (or *"resume own navigation"* when already near the route), or
   rejoins the STAR.
   - **The mint line ends at the first route intercept.** The deviation leaves the
     route, rounds the weather, and rejoins it **once** — it is truncated exactly where
     it first re-crosses the upcoming route polyline, so it can never cross the route and
     loop back to intercept a second time. (When it never re-crosses — it ends alongside
     the route — its final vertex is snapped to the nearest route point instead, so the
     line still ends cleanly on the flight plan.)
   - **Auto-turns at every vertex of the mint line.** On a vector, the controller
     automatically issues a turn as the aircraft reaches **each** turn in the drawn
     line — not just the last one. A single dogleg (`[position, apex, rejoin]`) has one
     turn; a **side-hug** (`[position, turnOut, turnBack, rejoin]`) has **two** — out
     onto the parallel leg, then back down to the route. The turn onto the parallel leg
     is an *intermediate* turn (*"fly heading …, vectors around precipitation"*); the
     turn onto the last leg is the *final* one (*"fly heading … to rejoin course direct
     …"*). Each firing arms the next interior turn (`pendingTurnIndex` walks the frozen
     `committedDeviationPath`), so the second turn back down to the flight path is called
     just like the first. The turn fires when the aircraft is near the vertex or has
     passed abeam it along the leg into it, so flying wide of it still triggers it.
   - **Every heading spoken is magnetic, and crabbed into the wind.** The mint line is
     built with great-circle geometry (`Geo.bearing`), so every leg of it is a **true**
     course — but the pilot flies a magnetic heading bug, through a wind that pushes the
     aircraft sideways for the whole length of a leg. Handing over the raw map bearing
     therefore misses on both counts, and both errors accumulate as cross-track: 1° over a
     100 NM leg is ~1.7 NM off the line, and 40 kt of crosswind at 460 kt TAS is ~5° of
     drift, or ~8.7 NM. So `HeadingSolver` converts a leg into the heading that makes the
     aircraft **track** it: crab into the wind (`asin(crosswind / TAS)`), then step from
     true into magnetic. Both corrections are measured from Infinite Flight's own
     telemetry — variation as the difference between the true and magnetic headings it
     reports for the same nose; wind by inverting the wind triangle
     (`wind = ground vector − air vector`, from track/groundspeed against true
     heading/TAS). Nothing is read from a declination table or a METAR: a METAR only ever
     describes the surface at a field, and the triangle needs no unit guessing and works
     on whatever states a given IF version exposes. Sampling is skipped past ~5° of bank
     (the two states are read in separate round-trips, so a roll smears the difference)
     and the wind is smoothed across ticks, with the last good estimate held meanwhile.
     Where the sim exposes no true heading there is nothing to measure and the raw true
     bearing is assigned, exactly as before. Only the **spoken** heading is converted —
     the armed turn geometry (`deviationStartHeading`, `pendingRejoinHeading`, the leg
     bearings) stays in true degrees so it keeps matching the drawn line.
   - **The aircraft's angles and the weather's settle their units separately.** They were settled
     together, on the reasoning that every angle comes out of the same API in the same convention.
     They don't: `environment/wind_direction_true` reports the weather in **degrees** on builds
     whose `aircraft/0/…` states are radians. Folding it into one vote meant a wind from 331
     proved "degrees" on every single snapshot, and the nose went with it — 084° magnetic arrives
     on the wire as 1.466, and read as degrees it is shown as 001°, so every heading in the app
     landed within 6° of north on the Flight tab, the taxi map and the weather map at once
     (reported from the field with the sim's own PFD beside it). It re-witnessed continuously, so
     the radians contradiction below never got a run to accumulate either. The heading's units are
     now settled by the **heading states themselves** — `heading_magnetic` and `heading_true`, the
     only angles resolved by an exact name, and the states the decision is actually for. The
     ground track, bank and pitch share their convention and *follow* that decision without
     contributing to it: the track is matched by a looser signature and has already been seen to
     land on the bool `is_on_flight_plan_track`, and a state that isn't the angle its name suggests
     has no business moving the nose. The weather is a separate family and votes only on itself
     (`IFStateMappingStore.AngleFamily`), which is also what restores the heading to what it read
     before the wind was ever consulted. Both decisions, and the raw readings behind them, are
     written to Diagnostics.
   - **Heading units are settled per *connection*, not per value or per snapshot.** Infinite Flight reports
     heading and track in radians on some versions and in degrees on others, and a single
     reading cannot tell the two apart: `4` is both a heading of 004° and one of 4 rad (229°).
     Guessing per value — "small magnitudes are radians" — therefore mangled *every* heading
     within ~6° of north on a build reporting degrees, and the damage did not stop at the
     compass rose: both inputs to the wind triangle are angles, so a nose read as 229° instead
     of 004° invents a wind that never existed and pushes it straight into the crab on every
     weather vector. So `IFConnectStateReader` reads magnetic heading, true heading and track
     together and decides for them at once: they come out of the same API in the same
     convention, so **any one of them too large to be radians makes them all degrees**. And the
     proof is *latched for the connection*, because deciding it per snapshot left the same hole
     open one size smaller: a snapshot whose angles are **all** near north — nose 004°, track
     004°, a northerly wind — witnesses nothing, since each reading is a valid radian value on
     its own. A build reporting degrees was then read as radians for as long as it stayed
     pointed north, which is precisely what a north-facing runway makes an aircraft do and hold:
     the nose reads 229°, and the one-degree gap between the true and magnetic headings becomes
     tens of degrees of "variation" that goes straight into the departure vector in the takeoff
     clearance. Units don't change mid-connection, so the proof carries across snapshots and a
     later witness-less snapshot leaves it standing. A fresh manifest means a fresh connection,
     and possibly a different IF build, so it starts over.
     **What that proof costs when it is wrong is the whole other half of it.** Taken on a single
     reading and never revisited, one bad number pins the session: a radians build read as
     degrees shows every heading — all of them in 0…6.28 — as 0–6°, so the aircraft symbol
     points north on the taxi and weather maps whichever way the nose is, until the app is
     relaunched. That was reported from the field. So the decision is **corroborated** and
     **falsifiable** (`IFStateMappingStore.noteAngleSnapshot`):
       - A reading past a full circle *in degrees* witnesses nothing. No heading, track or wind
         direction reads 450 in either convention, so such a number is a corrupt read — the
         answer to a different state — not evidence about units.
       - Two consecutive witnessing snapshots are required before the proof is taken. A genuine
         degrees build witnesses on every snapshot its nose is off north, so it still settles
         within a second; a lone stray reading settles nothing.
       - No single reading can prove *radians* — every radian value is a valid degree value —
         but a run of them can. A heading that visits three of the four quadrants of the 0…2π
         circle across a dozen samples, without one reading in them ever passing a full circle
         in radians, is an aircraft turning through the compass rather than one holding inside a
         6° arc of north. That contradiction clears the proof, and the headings come right
         without a relaunch. Any reading past the radian circle resets the run, so a build that
         genuinely reports degrees is never disproved by holding short on a north-facing runway.
     Which convention is in force, and every change to it, is written to Diagnostics.
     **Bank and pitch follow the same decision** — they are angles out of the same API, and
     read raw they were the quietest bug of the lot: no reading of bank is ever large enough to
     look wrong, so on a build reporting radians a 25° bank simply arrived as `0.44` and every
     degree-scaled test of it passed. The wings-level guard below (5°) therefore never tripped
     once, and the wind triangle was solved *through every turn* — see the next bullet. They
     wrap to −180…180 rather than onto the compass rose, so a 4° left bank stays −4° instead of
     becoming 356° and reading as knife-edge.
   - **The sim's own wind is read, and preferred.** Infinite Flight exposes
     `environment/wind_velocity` (m/s) and `environment/wind_direction_true` (radians on some
     builds, degrees on others), so the wind no longer *has* to be inferred. Both are mapped
     (`windVelocity` / `windDirectionTrue`) and normalised to knots and degrees true — the
     direction settling its **own** radians-vs-degrees decision, separately from the aircraft's,
     because the two are not always in the same convention (see above) — and the steady wind
     matched so it can never resolve onto `wind_gust_velocity` beside it.
     Read directly the wind is exact, so it needs neither the smoothing (which exists only to
     absorb the noise of differencing two ~450 kt vectors) nor the near-wings-level sampling
     guard — and that second point is the real gain: the triangle has to stand down through a
     turn, which is precisely when the *next* leg's crab is computed off it. Older versions
     don't expose the states; there the triangle carries on exactly as before.
   - **The reported direction is the "from" direction — pinned against the sim's own PFD.** A
     state called `wind_direction_true` can name either end of the vector, and the two are
     exactly 180° apart, so this was settled by observation rather than by the name: with the
     state reading **5.5069 rad = 315.5° true**, Infinite Flight's own panel showed **301°** —
     the same direction stepped into the magnetic frame by ~14.5° of local variation, not the
     ~135° a "blows toward" reading would have given. It is therefore used as read.
     Because that is one observation of one build, it is not left unguarded: whenever the
     triangle independently solves a wind of at least 10 kt, the two directions are compared,
     and a disagreement past 90° is treated as "this isn't the convention we think it is" — the
     inferred wind, whose convention is fixed by the arithmetic that produced it, is used
     instead and the mismatch logged. Weather Diagnostics shows which source is in use, both
     winds, and the signed difference between them.
   - **…but the speeds have to corroborate before that fallback fires.** The direction check
     alone hands the decision to whichever source disagrees *loudest*, which is exactly the
     wrong way round: the triangle differences two ~450 kt vectors read in separate
     round-trips, so a smeared sample can invent a wind of its own, while the sim's reading has
     nothing to smear. Caught in the field — the sim reporting 12 kt from 331°, the triangle
     solving 84 kt from 089°, 118° apart, and the app dutifully crabbing every weather vector
     for the 84 kt gale. So the fallback now also asks whether the two winds *could be the same
     wind*: naming the other end of a vector reverses it without changing its strength, so only
     matching speeds (within 5 kt or 1.5×) make a direction disagreement a convention problem.
     Speeds that disagree too mean the triangle is the broken one, and the sim's exact reading
     stands. Relatedly, the triangle's estimate is now kept apart from the wind actually in use
     rather than blended into it — a reported sample folded into the "solved" wind makes the
     Diagnostics comparison compare a number with itself, and reads `0° — sim reports “from”`
     however wrong the triangle is.
   - **The correction is visible.** Neither the solved wind nor the variation was surfaced
     anywhere, so a vector that came out pointing somewhere unexpected could only be argued
     about. Weather Diagnostics now shows the triangle's solved wind (`270° / 85 kt`) beside the
     sim's own reading and which of the two is in use, the variation being
     applied, and the last weather vector as `true 042° → assigned 038°` — the leg's own course
     next to the heading actually spoken, so the crab and the magnetic step can be read off and
     checked against what Infinite Flight itself is showing.
   - **Both wind rows print both frames** (`346°T · 352°M / 9 kt`). Every wind the app holds is
     true — `wind_direction_true` by name, and a triangle built from true heading and track —
     while the sim's own PFD shows the wind *magnetic*, like the heading bug beside it. The rows
     exist precisely to be held up against that panel, so printing the true figure alone made a
     perfectly correct wind look wrong by exactly the local variation and sent us chasing it:
     346°T beside an instrument reading 352°M, 6.2°W apart, is the same wind written twice. The
     magnetic step is the one the assigned heading already uses (`magnetic = true −
     variationEast`); before any variation is solved there is nothing to step by, so the true
     figure stands alone and is labelled `°T` either way.
   - **Never the same call twice — "did I say this, and was it acknowledged?"** A long
     parallel run past a multi-cell system carries several offset vertices on nearly the same
     bearing, so consecutive turns can round to the *same heading* and the radio ends up
     carrying *"fly heading 082, vectors around precipitation"* three times over while the
     pilot is already flying exactly that. So any call the **controller initiates on its own**
     — the auto-issued advisory, the held beginning turn, the interior turns, the off-path
     re-vector, the redraw update, the auto-resume — is **held** when it would only repeat the
     last controller call *and* the pilot has transmitted since (their acknowledgement). The
     deviation state still advances exactly as if it had been said, so the line keeps walking
     its turns; only the duplicate transmission is dropped (it is logged in Diagnostics). Two
     things are never held: a call the pilot hasn't answered — re-issuing it is how an unheard
     instruction gets through — and a **reply to a pilot request**, since a request left
     unanswered reads as a dropped call.
   - **Drift off the line being flown → re-planned from the aircraft.** Wind, a late roll-in
     or a wide turn can leave the aircraft well off the mint line it was cleared to fly — at
     which point the drawn line no longer describes the reroute being flown and the armed
     turns point at geometry the aircraft will never reach. So while vectoring, the
     perpendicular distance from the aircraft to the committed line is watched, and past
     `deviationOffPathToleranceNM` (**5 NM either side**) the deviation is re-planned **from the
     aircraft's current position**: fresh geometry when new precipitation now sits on the path
     from here (`recomputeConflictFrom`, which also carries the line down to the filed route),
     else the committed reroute itself — in both cases **re-anchored to the aircraft and forward
     only** (`pathAnchoredAtAircraft` keeps a vertex only when the aircraft has not already flown
     past it *along the line* **and** it is not behind the aircraft's current track, then starts
     the line at the aircraft, so its first leg is the intercept forward onto what remains of the
     reroute). When **nothing** of the reroute is left ahead, no vector is issued at all and the
     drawn line is left alone: turning the aircraft back around to pick up geometry behind it is
     never the answer. Keeping a trailing vertex so the line "still ends on the route" is exactly
     what produced a near-reciprocal vector (216° → 015°) once the aircraft had flown past and
     away from the whole reroute. As a backstop, **no automatically-issued weather vector may
     turn the aircraft more than `maxWeatherVectorTurnDegrees` (135°) off its current track** —
     the held beginning turn, the interior turns, the post-jump resync vector and the off-path
     re-plan all check it, since every drawn leg is already bounded to 100° off course, so a
     vector past that bound means the geometry is behind the aircraft, not a tight turn.
     `committedTailAhead` — the remainder of the reroute the re-plan and the Vectors button plan
     against — uses the same forward-only rule, rather than starting at whichever vertex is
     merely *nearest*. The re-anchored line is
     re-frozen, the controller re-vectors onto it (*"you appear to be off the assigned
     deviation, fly heading …, vectors around precipitation, maintain …, advise clear of
     weather"*), and the interior turns are re-armed against the new line so the upcoming turn
     calls match what is drawn. The rejoin on the filed route is preserved either way.
     Three things keep it from firing when nothing is wrong: the aircraft must actually be
     **on** the line (a reroute still drawn ahead of it, or one already flown out past the
     rejoin, is not drift); an **armed turn wins**, since flying wide of a vertex is what the
     abeam turn logic already handles; and a turn just issued gets a `turnComplyWindow` (60 s)
     to roll out before its distance from the line counts as drift. A re-plan also holds off
     within `autoResumeInterceptNM` of the rejoin — the maneuver is essentially over — and is
     rate-limited by `offPathReplanInterval`.
   - **A gradual return to course — rejoin at a fix farther down.** The closing leg intercepts
     the route wherever the geometry happens to put it, which can leave the aircraft turning
     hard onto course at the rejoin (and the controller calling that sharp turn). So when the
     final turn of a drawn line exceeds `maxRejoinTurnDegrees` (**60°**), the rejoin is moved to
     the **next fix down the flight plan** (`deviationWithGentleRejoin`): the closing leg gets
     longer, the intercept shallower, and the aircraft simply proceeds direct that fix — how the
     return to course is flown for real. Fixes are tried in order and the **first gentle-enough
     one whose closing leg still clears the weather** wins (else the shallowest clear one, else
     the line is left as it was — reaching farther down must never take the closing leg back
     through the system the deviation exists to avoid). The rejoin never moves past the rejoin
     cap or into the next deviation's turn-out, and the **named rejoin fix is retagged** to
     whatever the line now ends on, so *"rejoin course direct …"* names the fix actually being
     flown to. Applied to every drawn deviation (`computeDeviations`) and to a re-planned one
     (`recomputeConflictFrom`) alike.
   - **A re-planned line is checked against the filed route, because nothing else checks it.**
     "Every deviation ends on the flight path" is enforced *inside the detector*, and only
     against the polyline it was handed. On a first solve that polyline is the filed route, so
     the line lands on it. On a **re-plan** the polyline is the committed reroute plus the route
     beyond it (`revectorRouteAhead`) — so the fresh line is only guaranteed to end on the *old
     mint line*, which freezing the new one then erases. `deviationExtendedToFlightPath` exists
     to splice the rest of the old line back on so the new one still reaches the route, but it
     is conditional (the end has to land within ~5 NM of the committed tail, with at least a
     mile of that tail left to add), and nothing re-examined the finished line. That is how a
     recalculated deviation could be drawn ending in mid-air, well short of the magenta line.
     So `recomputeConflictFrom` now measures the finished line's rejoin against the filed route
     and, when it misses by more than `deviationRejoinOnRouteToleranceNM` (3 NM), **re-solves
     against the plain filed route ahead** — putting the detector's own truncation/snapping
     guarantee back on the flight path rather than bending the geometry by hand. The composite
     solve still stands when nothing solves against the filed course (weather that sits only on
     the reroute), and either outcome is logged to Diagnostics.
   - **Auto-resume at the intercept.** If the pilot never reports clear of weather, the
     controller automatically issues *"resume own navigation"* and ends the deviation
     once the aircraft reaches within 15 NM of that intercept (measured on the final leg,
     so it can't trip during the outbound or parallel legs). On a vector this fires the
     tick after the final automatic rejoin turn, so the two don't collide.

**Stable, non-flickering display.** Radar resampling is noisy: a storm that is
really still ahead can drop out of a single sample and return on the next. Read
straight through, that blinks the mint line and the "contact ATC" banner on and off
at the resample cadence. So once a conflict is shown, `resolveConflictWithHysteresis`
**holds** it until the route has tested *continuously clear* for a confirm window
(`weatherClearConfirmWindow`, ~90 s — longer than a resample cycle) — a *confirmed*
clean route — rather than removing it the instant one sample comes back empty. The
window resets when the pilot resolves the prompt (continue / clear of weather).

The **faint strategic previews** get the *same* hold (`resolvePreviewsWithHysteresis`).
They recompute straight off the sampled `weatherHazards` every tick, so without it a
noisy resample — or a marginal on-route cell dropping below the coarse whole-route
sampling threshold — would blink a preview line out even though the storm is still
along the route (a faint line that "appears for a bit then goes away while the hazard
is still there"). The last non-empty preview set is held until the route tests
continuously clear for the same window; each re-detection re-arms the hold, so an
intermittently sampled system shows a **steady** line rather than a flicker. The hold
is dropped on the lifecycle reset (`resetWeatherDeviation`) so stale previews never
carry across flights.

**The committed line is locked.** Once the pilot commits to a vector or deviation,
the mint line is **frozen** into `WeatherDeviationContext.committedDeviationPath` and
the map draws that fixed path (`weatherDeviationLine`) — it no longer shifts or
blinks as the radar resamples, and confirm-clear hysteresis never tears it down. The
lock releases only on clear-of-weather (which resets the flow) or on a fresh reroute
request, which re-freezes it.

**Refreshing the whole-route set.** The locked deviation set is re-solved in one
synchronous pass (`computeDeviations` → the full optimized search per system, then the
adjacent-hug fold — there is no longer a "quick hug first, refine in the background"
two-step; that only existed to bridge the slow radar-polygon sampling, since fixed). The
shared re-solve core is `refreshDeviationsFromCurrentRadar` (drop the lock → recompute
against the current radar sample). It re-locks on a route change; on a **pull-to-refresh**
(the Weather view has no refresh buttons — pulling down runs `refreshWeather` first, which
samples fresh radar, then `refreshDeviationsFromCurrentRadar` against it); and
**automatically every ~5 min** (`autoRefreshDeviationsUnlessDeviating`, driven off the
weather-refresh timer right after it samples radar) — a manual refresh, run on a cadence,
so the reroutes track weather that has moved without any interaction. The automatic refresh
**steps aside while a deviation is being flown**: if the state is a committed deviation
(`isCommittedDeviation`) it does nothing, so the path the pilot is following is never
re-proposed under them; the manual pull-to-refresh always refreshes (the committed line is
frozen regardless, so it still doesn't move).

**Re-vectoring for new weather.** While flying a lateral deviation, the **Vectors**
button stays on the card alongside *Clear of Weather*. If new weather pops up ahead
of the reroute, tapping it re-plans from the aircraft's **current position**, treating
the committed mint line as the current route (`revectorRouteAhead` + `detectConflictAlong`):
a fresh heading, mint line and rejoin turn are computed against the new weather and
rejoin the line the aircraft was already following, rather than the original filed
course.

Because freezing the fresh line **replaces** the committed one, the detector's
"end at the first route intercept" would otherwise leave the new (second) deviation
rejoining the *old* line partway — a rejoin that then sits mid-air on the deviation
just erased, so the aircraft would resume own navigation short of the route. To keep
the invariant that **every deviation ends on the flight path**,
`deviationExtendedToFlightPath` splices the remainder of the committed line (from where
the fresh line rejoined it, down to its rejoin on the filed route) onto the new path, so
the second deviation carries all the way to the flight-plan intercept.

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
- **Reduce cellular data** (default **on**) — on a cellular / hotspot / Low-Data-Mode
  connection, skips the background EUMETNET OPERA composite downloads that drive the
  automatic reroute (the only megabyte-scale weather source; NOAA/NASA are small
  server-cropped PNGs). The radar overlay still loads when you open the Weather map
  (user-initiated). Turn it off to run live OPERA radar on any connection.

No user-entered API keys, provider subscriptions, or commercial weather-provider
configuration are offered — by design.

### On-device storage / cache growth

Nothing accumulates without bound. The **one composite the app actually uses is held
in memory** (a single decoded raster, ~a few MB, replaced on each 5-min update and
freed when the app exits). The HTTP layer's on-disk cache is a **bounded LRU**, hard-
capped per client (**OPERA composite ≤ 64 MB**, aviation JSON ≤ 32 MB): new products
evict old ones, so it can never grow past the cap (and a composite larger than ~5 % of
the cap isn't disk-cached at all — it's just streamed, decoded, and discarded).

## Mock Mode demo

Mock Mode loads several deterministic precipitation systems along the filed route: a
primary heavy cell ~40 NM ahead of cruise (the one the demo works as an active
deviation), plus a moderate system early on and a heavy system near the arrival, spaced
well over a lookahead apart so each is a distinct deviation. The map shows the cells and
the faint strategic-preview reroutes for every system down the route (visible from the
gate), ATCView shows the advisory for the primary, and the pilot can request a right
deviation, get an approval with a downstream rejoin fix, then report clear of weather
and be cleared direct/own-navigation — all offline, with no live APIs and regardless of
subscription state. Because the extra systems are spaced beyond the cruise lookahead,
only the primary is in range at cruise, so the worked-deviation flow is unchanged.

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
