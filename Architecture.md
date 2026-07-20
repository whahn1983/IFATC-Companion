# Architecture

IFATC Companion is a native iOS SwiftUI app (bundle identifier `com.h3consultingpartners.ifatccompanion`, display name "IFATC Companion"). It is built with Xcode 26.x against the iOS 26 SDK with a minimum deployment target of iOS 17.0. The app is deterministic and local-only: no backend, no generative AI/LLM, no accounts, no analytics. Push-to-talk uses Apple's on-device Speech framework for transcription only (not an LLM, no network); recognized text is mapped to actions deterministically.

## Layered overview

The app follows a layered architecture:

```
Views (SwiftUI)
   │   observe / send intents
   ▼
AppModel (ObservableObject coordinator)
   │   owns and wires services
   ▼
Services (connection, ATC state machine, phraseology, pilot responses,
          weather, speech, settings, mock)
   │
   ▼
Infinite Flight Connect API v2 (TCP)   ·   NOAA weather   ·   AVSpeechSynthesizer
```

- **Views** are SwiftUI and contain no business logic. They observe published state and forward user intents (button taps) to the coordinator.
- **AppModel** is the central `ObservableObject` coordinator. It owns the service instances, wires their data flow, and publishes the state the views render.
- **Services** are single-responsibility units. Networking, ATC logic, phraseology, speech, weather, and settings are each isolated so they can be tested and so the absence of any one (notably Infinite Flight) degrades gracefully.

## App shell and navigation

The app shell is a SwiftUI `TabView` hosted by `ContentView`, presenting the five tabs: **ATC, Flight, Weather, Settings, Diagnostics**. Each tab is a view that reads from `AppModel`.

## Models

- **AircraftState** — the current aircraft state model: position, altitude, heading, speed, vertical speed, on-ground flag, and related telemetry read from the Connect API (or the mock feed).
- **FlightPlan** — the flight plan model: origin, destination, waypoints, and route used by the ATC logic and the Flight/Weather tabs.
- **FlightPhase** — the enumeration of flight phases (e.g. parked, taxi, takeoff, climb, cruise, descent, approach, landing) used by phase detection and the state machine.

## Connect / networking

This is the boundary between the app and Infinite Flight, deliberately isolated so the app never crashes when Infinite Flight is absent.

- **IFConnectManager** — high-level connection manager. Owns connection lifecycle, host/port configuration, auto-discovery, reconnection, and exposes connection status. Falls back to manual overrides / Mock Mode when no connection is available.
- **IFDiscoveryService** — auto-discovery of the Infinite Flight device. Runs two paths in parallel and reports whichever resolves first: (1) a Bonjour `NWBrowser` for `_infiniteflight._tcp` (the safe, entitlement-free path — needs only Local Network permission and the `NSBonjourServices` Info.plist entry), and (2) a BSD/POSIX UDP socket listening for IF's discovery broadcast on port 15000, re-emitting a permission ping every couple of seconds so reception flows once Local Network permission is granted. Receiving raw UDP broadcast on iOS 14+ also requires the `com.apple.developer.networking.multicast` entitlement (Apple-approved), so Bonjour is preferred; manual IP entry remains the always-available fallback.
- **IFConnectClient** — the Infinite Flight Connect API v2 client, implemented over `Network.framework` TCP (always port 10112). Handles the wire protocol and raw state/command exchange. All reads go through a persistent **IFConnectFrameBuffer**: because TCP delivers a framed response (`Int32 id + Int32 length + payload`) as an arbitrary stream of chunks, every chunk is appended and a frame is only surfaced once its full declared length has arrived — so a partial response is never mistaken for a missing one, and multiple frames can share one buffer.
- **IFConnectManifestService / IFManifestReader** — state manifest discovery. Reads the Connect API's manifest of available state fields and commands so the app knows what data is available in the current Infinite Flight session. `IFManifestReader` is the transport-agnostic engine that buffers, validates (response id, payload length, nested string length, UTF-8) and parses the manifest — the manifest payload is a **length-prefixed** UTF-8 string, and *not* stripping that nested `Int32` length prefix before decoding was the root cause of intermittent "Manifest Unavailable". It uses a 15 s inactivity timeout (reset as bytes arrive), retries once on the same connection, and emits granular progress to Diagnostics (`IFConnectManifestEvent`); `IFConnectManager` maps that to a "Receiving manifest…" status and reconnect-and-retry with backoff.

- **LiveATCDetector / LiveATCStatus** — derives multiplayer and human-ATC-staffing context from manifest-mapped Connect states (best-effort, signature-based). The standby guard is **per-frequency and location-aware**, keyed off the frequency the pilot is actually tuned to. Infinite Flight's Connect API does not publish a map of which airport each controller works, so a bare "a human is controlling somewhere in this session" flag can't say whether that controller is relevant to *this* flight. What it *does* publish is the **name of the frequency the pilot is currently tuned to** on COM1 (`aircraft/0/systems/comm_radios/com_1/name`, e.g. "Ground", "KSFO Tower", "Unicom", "ATIS"). Since Infinite Flight only offers a field's ATC frequencies while a human is working them (otherwise pilots are on UNICOM), a tuned COM name that isn't blank, UNICOM, or ATIS is a live human controller on the pilot's own radio: `LiveATCStatus.tunedToHumanController` is true and `AppModel.companionStandby` defers. Tuning off that frequency — onto UNICOM, ATIS, or an unstaffed field — lifts the guard and the companion resumes covering that sector, and a human controlling a *different* airport elsewhere in the session never gates the app (the companion keeps working every sector until the pilot tunes that controller). While the guard is engaged, `AppModel.handle(state:)` returns before generating or speaking any controller call, so **all** automatic, telemetry-driven calls pause — the position-based callouts and facility hand-offs (takeoff clearance, departure hand-off at the initial climb, Center hand-off at the TRACON ceiling, descend-via-STAR, approach and landing, taxi-in) plus the semi-automatic manual-tuning flow — and the two independent timers (the auto takeoff-clearance countdown and the "how do you read?" read-back re-prompt) check the guard before firing; the read-back timer keeps re-arming so its reminder resumes if the pilot leaves the human frequency before reading back. The companion identifies a human controller in the session for the Diagnostics display from an exposed controller flag/count/username (`LiveATCStatus.humanControllerActive` / `controllerName`), but that is a presence signal only and never gates on its own. Because the exact ATC/COM state names Infinite Flight exposes only appear when connected to a session with a controller and vary by version, `IFConnectManager` logs every ATC/COM-related manifest state (and which staffing signatures they resolved to) to Diagnostics so the tuned-frequency matching can be verified and refined against a real session.

- **IFFlightPlanParser** — best-effort parser for the flight plan Infinite Flight exposes at `aircraft/0/flightplan`. It handles both serializations IF has used: the richer **JSON document** (per-fix coordinates, nested SID/STAR/approach procedure groups, and planned altitudes — from which the cruise/TOC level is recovered) and the plain **route string** (whitespace/arrow-separated). It strips departure/arrival **runway tokens** so the runway is never mistaken for the first enroute waypoint — but first records them as the plan's **departure/arrival runways** (e.g. `DPT RW22R` → departure runway `22R`), and recovers a cruise altitude from any `FLxxx`/altitude token. The cruise level is taken from the **highest planned altitude including the TOC/TOD display markers** (those sit at the cruise level), so it reports the final cruise level rather than the climbing level reached just before the top of climb. `IFConnectManager` reads it after connecting and periodically while polling, and pushes the result to `AppModel`, which merges departure/destination/waypoints/SID/STAR/approach/cruise into the active `FlightPlan` (manual overrides win). The departure heading used in the takeoff clearance is the bearing from the field to the first fix.

- **Callsign** — the Infinite Flight **Connect API exposes no callsign** for the user's own aircraft (the manifest has `infiniteflight/current_user` and `aircraft/0/name`, but neither is the flight callsign), so the callsign cannot be read live. It is entered by the pilot in an editable field surfaced on the main **ATC Companion** page (and mirrored in the Flight tab's overrides). `AppModel.applyManualCallsign` writes it into the active `FlightPlan` without rebuilding the live-read route, resolving an airline/IATA prefix (e.g. `UAL598` → `United 598`) when the airline/flight-number overrides are not separately set.
- **Foreground reconnect** — when the app returns from the background (`scenePhase` → `.active` after `.background`), `AppModel.handleReturnToForeground` forces a fresh Connect link (`disconnect()` + `startLive()`), because Infinite Flight can leave the socket looking "connected" while no new telemetry flows. The in-progress session is restored on reconnect, so the conversation resumes where it left off.

**Isolation guarantee:** the Connect layer is the only code that talks to Infinite Flight. If Infinite Flight is not present, unreachable, or does not expose a given field/command, the layer reports unavailability and the rest of the app continues using manual overrides or the mock feed. It never crashes the app.

## ATC state machine and phase detection

- **PhaseDetector** — derives the current `FlightPhase` from `AircraftState` (and flight plan context) using heuristics over altitude, speed, vertical speed, and ground state.
- **ATCStateMachine** — the procedural core. It consumes the detected phase and pilot actions and advances through ATC interaction states, deciding which calls are due and tracking expected readbacks.

- **Hybrid ATC flow (`AppModel`)** — the pilot drives their own pre-departure calls via the ATC-tab buttons (clearance → pushback → engine start → taxi → ready), with read backs and check-ins always manual, while the controller's **position-based calls fire automatically**. The **takeoff clearance fires automatically once the aircraft is lined up** on the assigned runway (`RunwayLineupDetector`); once airborne (`hasDeparted`) the **facility hand-offs, climb, descent, approach, cleared-to-land and taxi-in advance automatically from telemetry**. Departure works the climb to a configurable **TRACON ceiling** (default FL180) before handing to Center, which clears to cruise. The arrival mirrors this: at **top of descent** Center issues the *descend-via-STAR* (or a plain descend); descending back through the TRACON ceiling Center **hands off to Approach**; once the aircraft is **established** (autopilot APPR hold, or lined up on final and wings-level) Approach clears the published **ILS/GPS/Visual** approach — using the first altitude in the approach section of the flight plan as the intercept altitude — and **hands off to Tower** for the landing clearance. In live mode a **read-back gate** holds the automatic flow after each controller instruction so calls never fire back-to-back: the next call is withheld until the pilot reads back (any pilot transmission releases the gate), and if the pilot stays idle the controller repeats the call and asks "how do you read?" every ten seconds. The automatic flow only ever moves **forward** through the gate-to-gate order, so a flickering phase detector near the ground cannot bounce the conversation back to an earlier call. The ATC tab also exposes a **Tune Frequency** card so the pilot can change controllers manually (`tuneTo(_:)` / `contactRamp()`). In **Mock Mode** the first manual tune sets `manualTuning`, which suppresses the automatic (button-less) advance so the conversation moves only on a button press. In **live mode** manual tuning instead drives a **semi-automatic** flow (`advanceSemiAutomatic`): the controller's position-based calls and facility hand-offs still fire from telemetry, but a hand-off only prompts "contact *next* on …" and then holds (`pendingCheckInFacility`) until the pilot tunes that frequency and checks in, at which point the new controller gives its instruction. Same-frequency calls (the takeoff clearance once lined up — after a short `takeoffClearanceDelay` when stopped — the descend-via-STAR, the cleared-approach, the runway-exit) play on their own; tuning the controller you were just handed to suppresses the redundant "contact …". The **response buttons are surfaced by the tuned controller and phase** (`availableActions`): only the calls that apply now are shown — Clearance at the gate, push/start on Ramp, taxi on Ground, takeoff on Tower, the enroute/arrival requests on their controllers — and the **Tune Frequency** card likewise shows only the current and next-ahead facilities (`relevantFacilities`). Requesting **taxi while still on the departure Ramp** (`onDepartureRampPreTaxi`) only **hands the pilot to Ground** (`handOffDepartureRampToGround`) without issuing a taxi clearance; the pilot requests taxi again on Ground for the actual clearance, so the flow never auto-runs Ramp → Ground → taxi. The **Ramp** button is context-aware (`contactRamp()`): before departure it contacts Ramp for the **pushback** (using the **departure gate**), and on arrival it contacts Ramp for the **taxi-in to the gate** (`arriveAtGate()`, using the **arrival gate**). On arrival the "block-in / flight complete" call is **not** issued when the Ramp call is made — instead the companion **monitors telemetry and only declares the flight complete once the aircraft is actually parked at the gate**: stopped on the ground with the **parking brake set** (when Infinite Flight exposes it; otherwise a sustained full stop) **and tuned to the Ramp frequency** (`isParkedAtGate`). The **Ramp-frequency requirement is the map-independent gate** — a parking brake set out on an active taxiway, before the pilot has contacted Ramp for the gate, never ends the flight, whether or not the taxi map has usable data. This is enforced at a single choke point: `advanceAndPost` refuses to advance to `.parked` unless `isParkedAtGate` holds, so every telemetry- and check-in-driven path (not just the staged Ramp block-in) is covered. When the taxi map *also* resolved the gate position (`AirportSurfaceCoordinator.arrivalGateCoordinate`, the OSM stand matching the entered gate, captured before the map is hidden) the aircraft must additionally be within ~80 m of it; otherwise the Ramp-frequency + parking-brake check stands. Mock Mode is a scripted demo (telemetry and the synthetic surface are decoupled), so it isn't gated on the pilot tuning Ramp. The same parking-brake gate keeps `PhaseDetector` from calling a stop on the taxiway "parked". `clearFlight()` wipes the conversation/ATC state (keeping settings and the flight plan) to start a new flight. The complete call sequence is documented in [`docs/ATC-Flow.md`](docs/ATC-Flow.md).

## Session persistence (reconnect resume)

- **SessionStateStore / SessionSnapshot** — persist the in-progress ATC session so a dropped Infinite Flight link (the pilot switched apps, the device slept, Wi-Fi blipped) resumes where the flight left off instead of re-deriving the conversation from raw telemetry. The snapshot captures the conversational state (`atcState`, the state-machine cursor, `currentFacility`, detected `phase`, `assignedAltitude`), the gate-to-gate flags (`hasDeparted`, `arrivalAnnounced`, `awaitingGateArrival`, `manualTuning`), and the transcript, backed by `UserDefaults`. `AppModel` writes it whenever the state meaningfully changes (and on a low-frequency heartbeat so `savedAt` stays fresh through long level phases), and on `startLive()` (reconnect or relaunch) it **restores** a recent, in-progress snapshot rather than resetting to a fresh flight. Completed (parked + arrival announced) and stale (older than the store's `maxAge`) snapshots are not resumed; `clearFlight()` / `resetAppData()` clear the snapshot. Two guards make the resume safe: live mode never restores a mock snapshot, and **`handle(state:)` ignores telemetry with no usable position** — Infinite Flight returns an empty snapshot during the reconnect handshake, and feeding it to `PhaseDetector` (which reads a nil "on ground" as airborne and defaults to *climb*) is exactly what previously jumped a parked aircraft straight to cruise on reconnect.

## Phraseology

- **PhraseologyEngine** — a deterministic, template-based engine that renders ATC calls into realistic phraseology ("niner", "flight level three seven zero", spoken headings/frequencies, etc.). Given the same inputs it always produces the same output. No AI is involved.
- **Phonetic** — pure phonetics with selectable FAA/ICAO packs (digit words "tree/fower/fife", "decimal" vs "point" frequency separator, QNH/hPa vs inHg altimeter).
- **PhraseologyProfile / PhraseologyProfileStore** — user-created profiles overriding individual call templates (with `{placeholder}` tokens) and mapping airline codes to spoken radio names. Persisted as JSON in `UserDefaults`; exportable/importable for sharing.

## Procedures and taxi routing

- **ProcedureParser / ProcedureLibrary** — parse SID/STAR/approach name strings into structured `Procedure` values, enriched with known fixes from a small built-in library. Clearance, descent-via-arrival, and approach clearances reference these by name.
- **RunwayDatabase** — the real runway inventory per airport (major US fields incl. EWR/JFK/LGA). `AppModel` picks the active runway by choosing the field's real runway best aligned into the live METAR wind — the same way ATIS/ATC select the active runway — so the companion never assigns a runway that doesn't exist at the airport (e.g. it picks "22R" for a southerly wind at Newark instead of inventing "14"). When the airport isn't in the database it falls back to a wind-derived runway number. The flight plan's own runway always wins: on departure the parsed **departure runway**, and on arrival the **parsed approach runway** then the parsed **arrival runway** (a manual override beats both). Requesting an approach/vectors resolves the arrival runway even if the conversational state hasn't yet flipped to the arrival side, and the approach is named once ("the ILS runway 01R approach") rather than echoing a display name plus the runway again.
- **TaxiRoutePlanner / AirportLayout** — a simplified airport-surface model (taxiways, ramp, per-runway routes, runway crossings) producing deterministic taxi instructions, with a built-in dataset for the demo airports and a generated fallback elsewhere.

## Push-to-talk

- **SpeechRecognitionService** — on-device push-to-talk via Apple's `Speech` framework (`SFSpeechRecognizer` + `AVAudioEngine`), preferring on-device recognition. Transcribes microphone input only; no network.
- **PilotIntentParser** — deterministic keyword rules mapping recognized text to a `PilotIntent`, dispatched by `AppModel` to the matching pilot action.

## Pilot responses

- **PilotResponseEngine** — generates pilot-side phraseology, primarily readbacks of ATC instructions. Triggered by user button taps in the ATC tab; its output both populates the transcript and advances the `ATCStateMachine`.

## Weather

- **AviationWeatherService** — fetches free public NOAA Aviation Weather Center data (aviationweather.gov), no API keys: METAR, TAF, PIREP, SIGMET. Results are cached.
- **Parsers** — best-effort parsers for the raw NOAA text/products into structured values.
- **WeatherRouteAnalyzer** — analyzes weather along the flight plan route. It filters PIREPs to the route corridor/altitude band, and likewise filters **SIGMET/AIRMET advisories to those whose area actually intersects the route corridor** (point-in-polygon plus a corridor cross-track test) so a nationwide turbulence advisory far from the route no longer drives the ride to "severe". Advisories with no usable geometry are excluded from the route assessment.
- **RideReportEngine** — produces a ride-quality / turbulence summary ("ride report") from the available weather and PIREP/SIGMET data.
- **TurbulenceModel** — a composite, deterministic ride-quality model blending PIREPs (weighted by distance ahead and report age), **route-relevant** SIGMET advisories (filtered by `WeatherRouteAnalyzer`), and a low-level wind-shear proxy from the surface METAR into a continuous ride index and severity.
- **RouteMapView** — a MapKit route/weather overlay (route line, departure/destination, live aircraft position, severity-colored PIREP turbulence markers).

## ATIS (real-world D-ATIS)

The **ATIS** layer surfaces the real airport ATIS at the origin and destination, sourced from free public data — never fabricated. If a field has no ATIS, the feature simply does not exist for that field (no button, no information code appended anywhere).

- **AirportATIS / ATISParser** — the ATIS model and its parser. ATIS text comes from the FAA **Digital ATIS (D-ATIS)** feed at `datis.clowd.io` — a free, public, keyless endpoint (built by the vATIS project, sourced from the FAA SWIM system), used the same "direct-to-public-service" way as the NOAA weather. Coverage is the set of US airports that publish D-ATIS. The feed returns a JSON array of `{airport, type, code, datis}` objects; `ATISParser` turns that into an `AirportATIS` of one or more `Part`s (a **combined** ATIS, or separate **arrival**/**departure** ATIS each with its own information letter). Any shape it doesn't recognize (an error object, an empty array, malformed JSON) parses to `nil`, which the app treats as "no ATIS for this field." The information **letter** is taken from the feed's `code`, falling back to the "…INFORMATION X" phrase in the text.
- **ATISService** — an `actor` that fetches D-ATIS, mirroring `AviationWeatherService`: a short TTL cache, request coalescing, a descriptive User-Agent, exponential backoff with a stale-serve fallback on transient failures, and a cached **nil miss** on a 4xx (the field has no D-ATIS). A successful fetch may legitimately return `nil`; it only *throws* on a transient failure with no cached fallback. Tuning ATIS passes `forceRefresh` to always pull the latest broadcast.
- **ATISPhraseology** — deterministic rendering of raw D-ATIS text into what the app shows and speaks. The transcript keeps the text essentially verbatim; for TTS it decodes the **embedded coded observation** the way a real ATIS voice reads it on the air — wind (`25012G30KT` → "wind two five zero at one two gusts three zero"), visibility (`10SM` → "visibility one zero"; `1/2SM` → "visibility one half"; `P6SM` → "more than six"), sky cover with cloud bases (`FEW015` → "few clouds at one thousand five hundred"; `OVC008` → "eight hundred overcast"), present weather (`-TSRA` → "thunderstorm with light rain"; `FZFG` → "freezing fog"; `VCSH` → "showers in the vicinity"), temperature/dewpoint (`07/M02` → "temperature seven, dewpoint minus two"), altimeter (`A2992` → "altimeter two niner niner two", dropping the FAA's spelled readback so it isn't spoken twice), RVR, and Zulu time (both `1953Z` and the day-stamped `042252`). It then expands the common D-ATIS abbreviations (RWY → "runway", ILS → "I L S", DEPG → "departing", LAHSO → "land and hold short operations", …), speaks approach-variant and taxiway letters phonetically (`RNAV Z` → "R NAV Zulu", `TWY B` → "taxiway Bravo"), turns the information letter into its phonetic word ("…advise you have information Sierra"), drops the coded `RMK` station group, and reads any remaining digit run one digit at a time. No AI: every transform is a fixed rule.
- **AppModel wiring** — because ATIS is real live data keyed to your actual flight, it is fetched only in **live mode** (Mock Mode stays a fully offline demo). The departure ATIS is fetched while parked/pre-departure at the origin; the arrival ATIS is fetched once the aircraft comes **within 100 NM** of the destination (opportunistically from the telemetry loop, plus on the weather-refresh cadence). The ATC tab shows a round **ATIS** tune button **inline with the other frequency buttons** whenever ATIS is available for the current field; tapping it pulls the latest broadcast, plays it on repeat on the configurable **ATIS voice**, and stores the information code. Unlike a radio you tune away to, the ATIS button is a **momentary listen** — pressing it leaves the pilot on their **current frequency** (the tuned controller keeps the active highlight; `atisButtonActive` is always false). Once the pilot moves on to a controller/ramp frequency the ATIS button drops out of the grid for that phase (`departureATISDismissed` / `arrivalATISDismissed`, persisted in the session snapshot) and reappears only when the arrival ATIS comes into range. Because ATIS is a one-way broadcast, tuning never advances the state machine or the read-back gate. The stored information code is appended to real-world contacts — the **initial taxi request** on departure ("…request taxi, information Alpha") and the **first Approach check-in** on arrival ("…information Bravo") — once each, and only when the pilot has actually received that ATIS. The received information codes are persisted so a reconnect keeps reporting them correctly. The **Diagnostics** tab shows the departure/arrival airport and whether ATIS was received for each.

## Speech

- **SpeechService** — text-to-speech via `AVSpeechSynthesizer`, fully offline. Supports per-facility controller voices, a configurable **ATIS voice** for the one-way broadcast, plus a separate pilot voice (with a subtle pitch offset) so the controller and own-ship calls are distinguishable. Pilot transmissions are spoken when triggered by a button/text tap; push-to-talk input is not re-spoken because the user already said it.

## Settings

- **AppSettings** — user preferences backed by `AppStorage` / `UserDefaults`: host/IP and port, auto-discovery, keep-screen-awake, voice selection, phraseology and unit preferences. No accounts or remote configuration. **Keep screen awake** (default on) disables the iOS idle timer while the app is open so the device never sleeps and drops the Infinite Flight Connect link.

## Diagnostics

- The **Diagnostics** view surfaces connection status, Connect API manifest discovery results, the **Mock Mode** toggle, and general troubleshooting information.

## Mock

- **MockSimulatorFeed** — a sample/mock state feed that drives `AircraftState` and `FlightPlan` through a simulated flight with no Infinite Flight present. This makes the app fully demoable in the iOS Simulator and exercises every downstream layer (phase detection → state machine → phraseology → transcript → speech). The demo route (KIAH → KMSP, United 598) carries a realistic default **United gate** at each hub (Houston Terminal C, Minneapolis Concourse C), applied to the flight plan when the pilot enters none.
- **Mock taxi over the real surface** — at flight load Mock Mode pre-caches the **whole** origin and destination airport surfaces (`AirportSurfaceCoordinator.prepareSimulatedSurfaces`) so the simulated taxi routes over the **actual** OSM field, not a synthetic one — a realistic gate-to-runway departure and runway-exit-to-gate arrival, with the taxi map shown and the aircraft driven by the mock ticker on **both** ends. The synthetic `MockAirportSurface` remains the offline fallback when a real extract can't be fetched or routed. When a taxi begins before its real extract has finished pre-caching — common for a large destination like KMSP, whose extract takes longer to fetch than a short demo takes to reach taxi-in — the synthetic field is shown immediately and the real surface is loaded asynchronously and **swapped in** the moment it arrives, provided the simulated drive hasn't started yet (swapping mid-drive would teleport the aircraft, so the real field is then used the next time the demo taxis there). The coordinator keeps "simulated drive" (the mock ticker vs live telemetry) and "synthetic surface" (fallback vs real OSM) as independent flags, so the demo can drive a simulated aircraft across real airport geometry.

## Data flow

Primary (downstream) flow:

```
Live Connect state  ─┐
                     ├─►  PhaseDetector  ─►  ATCStateMachine  ─►  PhraseologyEngine
MockSimulatorFeed  ─┘                                                   │
                                                                       ▼
                                                          Transcript + SpeechService
```

User-initiated (upstream) flow:

```
User button tap  ─►  PilotResponseEngine  ─►  ATCStateMachine advance
```

Weather flows independently: `AviationWeatherService` → parsers → `WeatherRouteAnalyzer` / `RideReportEngine` → Weather tab.

## Folder layout

Under `IFATCCompanion/` (representative layout; synchronized file groups auto-include new Swift files):

```
IFATCCompanion/
├── App/              # App entry point, ContentView, TabView shell
├── Models/           # AircraftState, FlightPlan, FlightPhase
├── Connect/          # IFConnectManager, IFConnectClient, IFConnectManifestService, LiveATCDetector
├── ATC/              # PhaseDetector, ATCStateMachine, PilotResponseEngine, ProcedureLibrary, TaxiRoutePlanner, PilotIntentParser
├── ATIS/             # AirportATIS, ATISParser, ATISPhraseology, ATISService (real-world D-ATIS)
├── Phraseology/      # Phonetic, PhraseologyEngine, PhraseologyProfile (+Store)
├── Weather/          # AviationWeatherService, parsers, WeatherRouteAnalyzer, RideReportEngine, TurbulenceModel
├── Speech/           # SpeechService, SpeechRecognitionService
├── Settings/         # AppSettings
├── Mock/             # MockSimulatorFeed
└── Views/            # ATC, Flight, Weather, Settings, Diagnostics tabs, RouteMapView, PhraseologyProfilesView
```
