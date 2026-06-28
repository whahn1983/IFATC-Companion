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
- **IFConnectClient** — the Infinite Flight Connect API v2 client, implemented over `Network.framework` TCP. Handles the wire protocol and raw state/command exchange.
- **IFConnectManifestService** — state manifest discovery. Reads the Connect API's manifest of available state fields and commands so the app knows what data and which UNICOM commands are available in the current Infinite Flight session.

- **LiveATCDetector / LiveATCStatus** — derives multiplayer and human-ATC-staffing context from manifest-mapped states (best-effort, signature-based). When a human controller is detected, `AppModel` puts the companion into standby and stops generating controller calls.

**Isolation guarantee:** the Connect layer is the only code that talks to Infinite Flight. If Infinite Flight is not present, unreachable, or does not expose a given field/command, the layer reports unavailability and the rest of the app continues using manual overrides or the mock feed. It never crashes the app.

## ATC state machine and phase detection

- **PhaseDetector** — derives the current `FlightPhase` from `AircraftState` (and flight plan context) using heuristics over altitude, speed, vertical speed, and ground state.
- **ATCStateMachine** — the procedural core. It consumes the detected phase and pilot actions and advances through ATC interaction states, deciding which calls are due and tracking expected readbacks.

## Phraseology

- **PhraseologyEngine** — a deterministic, template-based engine that renders ATC calls into realistic phraseology ("niner", "flight level three seven zero", spoken headings/frequencies, etc.). Given the same inputs it always produces the same output. No AI is involved.
- **Phonetic** — pure phonetics with selectable FAA/ICAO packs (digit words "tree/fower/fife", "decimal" vs "point" frequency separator, QNH/hPa vs inHg altimeter).
- **PhraseologyProfile / PhraseologyProfileStore** — user-created profiles overriding individual call templates (with `{placeholder}` tokens) and mapping airline codes to spoken radio names. Persisted as JSON in `UserDefaults`; exportable/importable for sharing.

## Procedures and taxi routing

- **ProcedureParser / ProcedureLibrary** — parse SID/STAR/approach name strings into structured `Procedure` values, enriched with known fixes from a small built-in library. Clearance, descent-via-arrival, and approach clearances reference these by name.
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
- **WeatherRouteAnalyzer** — analyzes weather along the flight plan route.
- **RideReportEngine** — produces a ride-quality / turbulence summary ("ride report") from the available weather and PIREP/SIGMET data.
- **TurbulenceModel** — a composite, deterministic ride-quality model blending PIREPs (weighted by distance ahead and report age), SIGMET advisories, and a low-level wind-shear proxy from the surface METAR into a continuous ride index and severity.
- **RouteMapView** — a MapKit route/weather overlay (route line, departure/destination, live aircraft position, severity-colored PIREP turbulence markers).

## Speech

- **SpeechService** — text-to-speech via `AVSpeechSynthesizer`, fully offline. Supports per-facility voices so different ATC positions can sound distinct. Speaks transcript entries produced by the phraseology and pilot-response engines.

## Settings

- **AppSettings** — user preferences backed by `AppStorage` / `UserDefaults`: host/IP and port, voice selection, UNICOM automation mode, phraseology and unit preferences. No accounts or remote configuration.

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
