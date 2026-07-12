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
   size** to hold ~2 NM per pixel, floored for short routes and capped for transcon ones
   (`RadarImageSampler.sampleGrid`). Sampling is **continuous** — it resamples so the
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
   The sampled cells drive geometry only and are never drawn (the radar image overlay
   already shows the precipitation). Radar is always spoken as *"precipitation"*,
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
   - **Never past the destination / approach.** The reroute rejoins the route no
     deeper than a **cap** (`rejoinCap`) — the first fix of the ILS/approach when the
     plan names one (`FlightPlan.approachStartCoordinate`), else the destination.
     Even with weather sitting right on the field, the mint line intercepts the route
     at or before that cap instead of routing past it. Every vertex past the cap's
     along-course distance is pulled back to it (`clampPathToAlong`).
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

**Re-vectoring for new weather.** While flying a lateral deviation, the **Vectors**
button stays on the card alongside *Clear of Weather*. If new weather pops up ahead
of the reroute, tapping it re-plans from the aircraft's **current position**, treating
the committed mint line as the current route (`revectorRouteAhead` + `detectConflictAlong`):
a fresh heading, mint line and rejoin turn are computed against the new weather and
rejoin the line the aircraft was already following, rather than the original filed
course.

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
