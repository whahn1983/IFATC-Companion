# IFATC Companion

IFATC Companion is a deterministic, local-only air traffic control (ATC) companion for the flight simulator [Infinite Flight](https://infiniteflight.com). You fly Infinite Flight on an iPad; this iPhone app runs on the same Wi-Fi network, connects to Infinite Flight via the local **Infinite Flight Connect API v2**, reads your aircraft state and flight data, and produces realistic ATC-style conversation when no staffed (human) ATC is available. It can optionally send Infinite Flight UNICOM actions when the Connect API supports them. There is no backend, no AI/LLM, no accounts, and no internet dependency beyond free public aviation weather.

## What it does

IFATC Companion provides simulated, procedural ATC and pilot phraseology driven entirely by your live flight state, organized into five tabs:

- **ATC** — Live ATC-style conversation transcript driven by an internal state machine. Generates approach, tower, ground, departure, and en-route style calls plus pilot readbacks, all spoken aloud with text-to-speech. **Push-to-talk** lets you speak readbacks and requests (on-device recognition). When a human controller is detected on a multiplayer server, the companion **stands by** and defers to the live controller.
- **Flight** — Current aircraft state and flight plan: position, altitude, heading, speed, vertical speed, flight phase, and route.
- **Weather** — METAR, TAF, PIREP, and SIGMET briefings from NOAA, with a route weather analysis, a composite ride-quality model, and a **ForeFlight-style route/weather map overlay**.
- **Settings** — Connection (Host/IP + Port), voices, UNICOM automation mode, phraseology and unit preferences.
- **Diagnostics** — Connection status, Connect API manifest discovery, Mock Mode toggle, and troubleshooting information.

Key feature highlights:

- Deterministic, template-based phraseology engine ("niner", "flight level three seven zero", etc.) — no generative AI, fully reproducible.
- **Selectable FAA and ICAO phraseology packs** (digit words, "decimal" vs "point", QNH/hPa vs inHg) plus **user-created phraseology profiles** (custom call templates and airline call sets, shareable as JSON).
- **Push-to-talk** with on-device speech recognition and deterministic intent parsing.
- **Hybrid ATC flow (real-world)** — **you drive your own pilot calls** with the buttons (clearance → pushback → engine start → taxi → ready, plus read backs and check-ins), and the **controller's position-based calls play automatically**: **takeoff clearance once you line up on the runway** (with the initial departure heading + climb), automatic **facility hand-offs** (Departure, then Center at a configurable FL180 TRACON ceiling, Approach, Tower, Ground), descent, cleared approach, cleared to land, and taxi-in to the gate. The takeoff/departure vectors are built from your filed route. In Mock Mode the automatic callouts play on a short pause (standing in for live position telemetry), and a **Clear Flight** button resets the conversation for a new flight. The complete call sequence is documented in [`docs/ATC-Flow.md`](docs/ATC-Flow.md).
- **Flight plan read from Infinite Flight** — the companion reads your IF flight plan (`aircraft/0/flightplan`) and uses the departure, destination and route fixes for clearances and departure vectors (manual overrides still win).
- **Procedure-aware** instructions: SID/STAR/approach name parsing with a built-in fix library.
- **Modeled taxi routing** with taxiways, runway crossings, and ramp routes.
- **Multiplayer / human-ATC staffing detection** so the companion steps aside for live controllers.
- Automatic flight-phase detection (parked, taxi, takeoff, climb, cruise, descent, approach, landing, etc.).
- Offline text-to-speech via `AVSpeechSynthesizer`, with **per-facility controller voices and a separate pilot voice** — your readbacks and requests are spoken aloud when you use the buttons (push-to-talk input is never repeated).
- Optional UNICOM automation: Off, Preview-then-send, or Auto-send.
- Free aviation weather from NOAA (no API keys), a composite turbulence/ride-quality model, and a MapKit route overlay.
- Mock Mode for a complete demo in the iOS Simulator with no Infinite Flight present.

## Requirements

- **Xcode 26.x** with the iOS 26 SDK.
- **iOS 17.0 or later** (minimum deployment target).
- For **live mode**: an iPad running Infinite Flight on the **same Wi-Fi network** as the iPhone running this app.
- For **demo/offline use**: Mock Mode, which requires no Infinite Flight and runs fully in the iOS Simulator.

## How to run

1. Open `IFATCCompanion.xcodeproj` in Xcode 26.x.
2. Select an iPhone simulator or a connected iPhone device.
3. Build & Run (Cmd-R).
4. In the iOS Simulator there is no Infinite Flight available, so the app starts in (or you can enable) **Mock Mode** from the **Diagnostics** tab to see a full simulated flight.

The app uses synchronized file groups, so any new Swift files added to the source folder are automatically included in the project.

## Enabling the Infinite Flight Connect API

On the iPad running Infinite Flight:

1. Open **Settings → General**.
2. Enable **"Infinite Flight Connect"**.
3. Note the iPad's **IP address** shown there. You will enter this in IFATC Companion.

## Entering the iPad IP address

On the iPhone running IFATC Companion:

1. Open the **Settings** tab.
2. Enter the iPad's **Host/IP** and **Port** (default port **10112**).
3. Alternatively, use **auto-discover** to find Infinite Flight on the local network.

Once connected, the app reads live aircraft state and your flight plan automatically.

## Multiplayer etiquette

**IFATC Companion is NOT staffed (human) ATC.** It is a personal, simulated ATC companion for your own situational awareness and immersion.

- It does **not** impersonate live controllers and must never be presented as official or human ATC.
- When ATC is staffed by a real controller, follow that controller — not this app.
- UNICOM actions sent by this app announce **only the pilot's own intentions** (your own position/intentions calls). They do not issue instructions to other pilots and do not act as a controller.

Use it responsibly and in keeping with Infinite Flight community standards.

## Known limitations

- ATC behavior is procedural and template-driven; it does not model every real-world situation or every controller decision.
- Phase detection is heuristic and may occasionally misjudge transitions.
- Taxi routing is modeled from a built-in airport-surface dataset for the demo airports, with a deterministic fallback elsewhere — it is not full, surveyed surface routing.
- SID/STAR/approach names are parsed and referenced, enriched from a small built-in fix library; published charts are not ingested in full.
- Multiplayer / human-ATC staffing detection is best-effort and signature-based against the Connect manifest; exact field coverage varies by Infinite Flight version.
- Push-to-talk uses on-device speech recognition; accuracy depends on the device and microphone, and intent mapping is keyword-based.

### UNICOM automation limitations

The Infinite Flight Connect API may not expose every UNICOM command, and command availability varies by app version and context. The UNICOM automation layer performs availability detection and **degrades gracefully**: unsupported commands are simply not sent, and the app continues to operate in Preview or Off behavior without errors.

### Weather data limitations

Weather is sourced from free public NOAA Aviation Weather Center endpoints (aviationweather.gov), with no API keys. As a result:

- Availability depends on NOAA endpoint uptime and coverage for your region.
- Parsing of METAR/TAF/PIREP/SIGMET is best-effort and may not interpret every field.
- Results are cached, so data may be a few minutes old or unavailable when offline.

## No AI / No backend

IFATC Companion is **deterministic and local-only**. There is:

- **No** backend server.
- **No** generative AI/LLM of any kind.
- **No** paid APIs, accounts, login, analytics, ads, or in-app purchases.

All ATC and pilot phraseology is produced by deterministic, template-based engines on-device. Push-to-talk uses Apple's on-device Speech framework purely to transcribe your microphone input (it is not an LLM and makes no network calls); the resulting text is mapped to actions by deterministic keyword rules. The only network usage is the local Infinite Flight Connect connection and free public NOAA weather.

## License

This software is proprietary. See [`LICENSE`](LICENSE). Copyright © 2026 H3 Consulting Partners LLC. All rights reserved.
