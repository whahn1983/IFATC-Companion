# IFATC Companion

IFATC Companion is a deterministic, local-only air traffic control (ATC) companion for the flight simulator [Infinite Flight](https://infiniteflight.com). You fly Infinite Flight on an iPad; this iPhone app runs on the same Wi-Fi network, connects to Infinite Flight via the local **Infinite Flight Connect API v2**, reads your aircraft state and flight data, and produces realistic ATC-style conversation when no staffed (human) ATC is available. It can optionally send Infinite Flight UNICOM actions when the Connect API supports them. There is no backend, no AI/LLM, no accounts, and no internet dependency beyond free public aviation weather.

## What it does

IFATC Companion provides simulated, procedural ATC and pilot phraseology driven entirely by your live flight state, organized into five tabs:

- **ATC** — Live ATC-style conversation transcript driven by an internal state machine. Generates approach, tower, ground, departure, and en-route style calls plus pilot readbacks, all spoken aloud with text-to-speech.
- **Flight** — Current aircraft state and flight plan: position, altitude, heading, speed, vertical speed, flight phase, and route.
- **Weather** — METAR, TAF, PIREP, and SIGMET briefings from NOAA, with a route weather analysis and a "ride report" (turbulence/ride quality) summary.
- **Settings** — Connection (Host/IP + Port), voices, UNICOM automation mode, phraseology and unit preferences.
- **Diagnostics** — Connection status, Connect API manifest discovery, Mock Mode toggle, and troubleshooting information.

Key feature highlights:

- Deterministic, template-based phraseology engine ("niner", "flight level three seven zero", etc.) — no AI, fully reproducible.
- Automatic flight-phase detection (parked, taxi, takeoff, climb, cruise, descent, approach, landing, etc.).
- Offline text-to-speech via `AVSpeechSynthesizer`, with per-facility voices.
- Optional UNICOM automation: Off, Preview-then-send, or Auto-send.
- Free aviation weather from NOAA (no API keys).
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
- Taxi routing is simplified; there is no full airport surface routing.
- SID/STAR/approach procedures are not parsed; instructions are generalized.
- Live multiplayer metadata and real ATC-staffing detection are not yet integrated.

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
- **No** AI/LLM of any kind.
- **No** paid APIs, accounts, login, analytics, ads, or in-app purchases.

All ATC and pilot phraseology is produced by deterministic, template-based engines on-device. The only network usage is the local Infinite Flight Connect connection and free public NOAA weather.

## License

This software is proprietary. See [`LICENSE`](LICENSE). Copyright © 2026 H3 Consulting Partners LLC. All rights reserved.
