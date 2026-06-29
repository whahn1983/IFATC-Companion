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
          UNICOM, weather, speech, settings, mock)
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
- **IFConnectClient** — the Infinite Flight Connect API v2 client, implemented over `Network.framework` TCP. Handles the wire protocol and raw state/command exchange.
- **IFConnectManifestService** — state manifest discovery. Reads the Connect API's manifest of available state fields and commands so the app knows what data and which UNICOM commands are available in the current Infinite Flight session.

- **LiveATCDetector / LiveATCStatus** — derives multiplayer and human-ATC-staffing context from manifest-mapped states (best-effort, signature-based). When a human controller is detected, `AppModel` puts the companion into standby and stops generating controller calls.

- **IFFlightPlanParser** — best-effort parser for the flight plan Infinite Flight exposes at `aircraft/0/flightplan`. It handles both serializations IF has used: the richer **JSON document** (per-fix coordinates, nested SID/STAR/approach procedure groups, and planned altitudes — from which the cruise/TOC level is recovered) and the plain **route string** (whitespace/arrow-separated). It strips departure/arrival **runway tokens** so the runway is never mistaken for the first enroute waypoint — but first records them as the plan's **departure/arrival runways** (e.g. `DPT RW22R` → departure runway `22R`), and recovers a cruise altitude from any `FLxxx`/altitude token. The cruise level is taken from the **highest planned altitude including the TOC/TOD display markers** (those sit at the cruise level), so it reports the final cruise level rather than the climbing level reached just before the top of climb. `IFConnectManager` reads it after connecting and periodically while polling, and pushes the result to `AppModel`, which merges departure/destination/waypoints/SID/STAR/approach/cruise into the active `FlightPlan` (manual overrides win). The departure heading used in the takeoff clearance is the bearing from the field to the first fix.

- **Live callsign** — `IFConnectManager` reads the user's callsign from Infinite Flight (`onCallsign`) on connect and periodically while polling; `AppModel.applyLiveCallsign` adopts it as the flight-plan callsign automatically unless a manual callsign/airline/flight-number override is set (those always win).
- **Foreground reconnect** — when the app returns from the background (`scenePhase` → `.active` after `.background`), `AppModel.handleReturnToForeground` forces a fresh Connect link (`disconnect()` + `startLive()`), because Infinite Flight can leave the socket looking "connected" while no new telemetry flows. The in-progress session is restored on reconnect, so the conversation resumes where it left off.

**Isolation guarantee:** the Connect layer is the only code that talks to Infinite Flight. If Infinite Flight is not present, unreachable, or does not expose a given field/command, the layer reports unavailability and the rest of the app continues using manual overrides or the mock feed. It never crashes the app.

## ATC state machine and phase detection

- **PhaseDetector** — derives the current `FlightPhase` from `AircraftState` (and flight plan context) using heuristics over altitude, speed, vertical speed, and ground state.
- **ATCStateMachine** — the procedural core. It consumes the detected phase and pilot actions and advances through ATC interaction states, deciding which calls are due and tracking expected readbacks.

- **Hybrid ATC flow (`AppModel`)** — the pilot drives their own pre-departure calls via the ATC-tab buttons (clearance → pushback → engine start → taxi → ready), with read backs and check-ins always manual, while the controller's **position-based calls fire automatically**. The **takeoff clearance fires automatically once the aircraft is lined up** on the assigned runway (`RunwayLineupDetector`); once airborne (`hasDeparted`) the **facility hand-offs, climb, descent, approach, cleared-to-land and taxi-in advance automatically from telemetry**. Departure works the climb to a configurable **TRACON ceiling** (default FL180) before handing to Center, which clears to cruise. The arrival mirrors this: at **top of descent** Center issues the *descend-via-STAR* (or a plain descend); descending back through the TRACON ceiling Center **hands off to Approach**; once the aircraft is **established** (autopilot APPR hold, or lined up on final and wings-level) Approach clears the published **ILS/GPS/Visual** approach — using the first altitude in the approach section of the flight plan as the intercept altitude — and **hands off to Tower** for the landing clearance. In live mode a **read-back gate** holds the automatic flow after each controller instruction so calls never fire back-to-back: the next call is withheld until the pilot reads back (any pilot transmission releases the gate), and if the pilot stays idle the controller repeats the call and asks "how do you read?" every ten seconds. The automatic flow only ever moves **forward** through the gate-to-gate order, so a flickering phase detector near the ground cannot bounce the conversation back to an earlier call. The ATC tab also exposes a **Tune Frequency** card so the pilot can change controllers manually (`tuneTo(_:)` / `contactRamp()`). In **Mock Mode** the first manual tune sets `manualTuning`, which suppresses the automatic (button-less) advance so the conversation moves only on a button press. In **live mode** manual tuning instead drives a **semi-automatic** flow (`advanceSemiAutomatic`): the controller's position-based calls and facility hand-offs still fire from telemetry, but a hand-off only prompts "contact *next* on …" and then holds (`pendingCheckInFacility`) until the pilot tunes that frequency and checks in, at which point the new controller gives its instruction. Same-frequency calls (the takeoff clearance once lined up — after a short `takeoffClearanceDelay` when stopped — the descend-via-STAR, the cleared-approach, the runway-exit) play on their own; tuning the controller you were just handed to suppresses the redundant "contact …". The **Ramp** button is context-aware (`contactRamp()`): before departure it contacts Ramp for the **pushback** (Ramp approves the push; Ground then handles the taxi), and on arrival it contacts Ramp for the **taxi-in to the gate** (`arriveAtGate()`). On arrival the "block-in / flight complete" call is **not** issued when the Ramp call is made — instead the companion **monitors telemetry and only declares the flight complete once the aircraft is actually parked at the gate** (stopped on the ground with the **parking brake set**, when Infinite Flight exposes it; otherwise a sustained full stop). The same parking-brake gate keeps `PhaseDetector` from calling a stop on the taxiway "parked". `clearFlight()` wipes the conversation/ATC state (keeping settings and the flight plan) to start a new flight. The complete call sequence is documented in [`docs/ATC-Flow.md`](docs/ATC-Flow.md).

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

## UNICOM

- **UNICOMAutomationService** — optional automation layer for sending Infinite Flight UNICOM actions. It performs availability detection via the Connect manifest and supports three modes: **Off**, **Preview-then-send**, and **Auto-send**. UNICOM actions announce only the pilot's own intentions. When a command is unsupported by the current Connect API, the service degrades gracefully and skips it without error.

## Weather

- **AviationWeatherService** — fetches free public NOAA Aviation Weather Center data (aviationweather.gov), no API keys: METAR, TAF, PIREP, SIGMET. Results are cached.
- **Parsers** — best-effort parsers for the raw NOAA text/products into structured values.
- **WeatherRouteAnalyzer** — analyzes weather along the flight plan route. It filters PIREPs to the route corridor/altitude band, and likewise filters **SIGMET/AIRMET advisories to those whose area actually intersects the route corridor** (point-in-polygon plus a corridor cross-track test) so a nationwide turbulence advisory far from the route no longer drives the ride to "severe". Advisories with no usable geometry are excluded from the route assessment.
- **RideReportEngine** — produces a ride-quality / turbulence summary ("ride report") from the available weather and PIREP/SIGMET data.
- **TurbulenceModel** — a composite, deterministic ride-quality model blending PIREPs (weighted by distance ahead and report age), **route-relevant** SIGMET advisories (filtered by `WeatherRouteAnalyzer`), and a low-level wind-shear proxy from the surface METAR into a continuous ride index and severity.
- **RouteMapView** — a MapKit route/weather overlay (route line, departure/destination, live aircraft position, severity-colored PIREP turbulence markers).

## Speech

- **SpeechService** — text-to-speech via `AVSpeechSynthesizer`, fully offline. Supports per-facility controller voices plus a separate pilot voice (with a subtle pitch offset) so the controller and own-ship calls are distinguishable. Pilot transmissions are spoken when triggered by a button/text tap; push-to-talk input is not re-spoken because the user already said it.

## Settings

- **AppSettings** — user preferences backed by `AppStorage` / `UserDefaults`: host/IP and port, auto-discovery, keep-screen-awake, voice selection, UNICOM automation mode, phraseology and unit preferences. No accounts or remote configuration. **Keep screen awake** (default on) disables the iOS idle timer while the app is open so the device never sleeps and drops the Infinite Flight Connect link.

## Diagnostics

- The **Diagnostics** view surfaces connection status, Connect API manifest discovery results, the **Mock Mode** toggle, and general troubleshooting information.

## Mock

- **MockSimulatorFeed** — a sample/mock state feed that drives `AircraftState` and `FlightPlan` through a simulated flight with no Infinite Flight present. This makes the app fully demoable in the iOS Simulator and exercises every downstream layer (phase detection → state machine → phraseology → transcript → speech).

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
                                                     │
                                                     └─►  (optional) UNICOMAutomationService send
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
├── Phraseology/      # Phonetic, PhraseologyEngine, PhraseologyProfile (+Store)
├── UNICOM/           # UNICOMAutomationService
├── Weather/          # AviationWeatherService, parsers, WeatherRouteAnalyzer, RideReportEngine, TurbulenceModel
├── Speech/           # SpeechService, SpeechRecognitionService
├── Settings/         # AppSettings
├── Mock/             # MockSimulatorFeed
└── Views/            # ATC, Flight, Weather, Settings, Diagnostics tabs, RouteMapView, PhraseologyProfilesView
```
