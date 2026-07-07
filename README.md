# IFATC Companion

**IFATC Companion** is your personal, simulated air traffic control (ATC) companion for the flight simulator [Infinite Flight](https://infiniteflight.com). You fly Infinite Flight on your iPad; IFATC Companion runs on your iPhone, connects to Infinite Flight over your local Wi-Fi network, and talks you through your flight gate-to-gate with realistic, spoken ATC — approach, tower, ground, departure, ramp, and en-route — whenever no human (staffed) controller is online.

Everything runs on your device. There are no accounts to create, no logins, no ads, and no AI/chatbot in the loop — just deterministic, real-world-style radio phraseology built from your live flight, plus free public aviation weather.

> **Not affiliated with Infinite Flight.** IFATC Companion is an independent, third-party app. It is **not** created, endorsed, sponsored by, or affiliated with Infinite Flight LLC or its products in any way. "Infinite Flight" and related marks are the property of their respective owners. Infinite Flight is required to use Live Connected Mode and is sold separately.

---

## What you get

IFATC Companion is organized into five tabs:

- **ATC** — A live, spoken ATC conversation for your flight. It generates clearances, taxi instructions, takeoff and landing clearances, hand-offs, descents, and approach clearances — plus your pilot read-backs — all read aloud with distinct voices for each controller and for you. You drive your own pilot calls with on-screen buttons or **push-to-talk**, and the controller's position-based calls play automatically at the right moments.
- **Flight** — Your live aircraft state and flight plan at a glance: position, altitude, heading, speed, vertical speed, flight phase, and route.
- **Weather** — Aviation weather briefings (METAR, TAF, PIREP, SIGMET) with a route weather analysis, a ride-quality/turbulence outlook, a **route map with a radar precipitation overlay**, and a simulated **weather-deviation** flow with ATC.
- **Settings** — Your connection to Infinite Flight, controller and pilot voices, phraseology and unit preferences, custom phraseology profiles, weather options, and your subscription.
- **Diagnostics** — Connection status and troubleshooting, plus the free **Mock Mode** toggle for a full offline demo flight.

---

## Key features

### Realistic, gate-to-gate ATC
- **Hybrid ATC flow.** You drive your own pilot calls — clearance → pushback → engine start → taxi → ready, plus read-backs and check-ins — and the controller responds. Position-based controller calls play automatically: your **takeoff clearance fires once you line up on the runway** (with your initial heading and climb), then automatic hand-offs to Departure, Center, Approach, Tower, and Ground follow you through the flight, including descent, cleared approach, cleared to land, and taxi-in to the gate.
- **Smart, context-aware buttons.** Only the calls that make sense right now are shown — Clearance at the gate, pushback and start on the Ramp, taxi on Ground, takeoff on Tower, and the en-route and arrival requests on their controllers — instead of every button all the time.
- **Tune Frequency card.** Change controllers yourself with a button. It shows the controller you're working plus the next one ahead, and once you start tuning manually, the flow waits for you to make each frequency change.
- **Reads your Infinite Flight flight plan.** Your filed departure, destination, and route are used for clearances and departure vectors. Manual overrides always win when you want to set something yourself.
- **Call sign recognition.** Enter a call sign like `UA598` or `UAL598` and it's spoken as its real radio name ("United five niner eight") using a built-in airline database; tail numbers like `N123AB` are spelled out phonetically.
- **Procedure-aware.** SID, STAR, and approach names are recognized and referenced in your clearances.
- **Modeled taxi routing** with taxiways, runway crossings, and ramp routes.
- **Ramp handling** as a first-class facility — pushback, engine-start coordination, and the hand-off to Ground — modeled separately from ATC ground control.
- **Push-to-talk.** Hold to talk and speak your read-backs and requests. Recognition runs entirely on your device.
- **Distinct voices** for each controller position and a separate pilot voice, all spoken offline.

### Phraseology you can trust
- **FAA (US) phraseology by default**, faithful to real-world procedures, with a selectable **ICAO** pack (different digit words, "decimal" vs "point", QNH/hPa vs inHg).
- **Custom phraseology profiles.** Create your own call templates and airline call sets, and share them as files.
- **Safe, current wording, enforced.** Outdated or unsafe phrases are blocked — takeoff and landing clearances always include the runway, "line up and wait" is used instead of "position and hold", and so on.

### Weather and weather routing
- **Aviation weather briefings** — METAR, TAF, PIREP, and SIGMET, sourced from free public NOAA data (no keys, no subscriptions to weather services).
- **Route weather analysis** and a **composite ride-quality / turbulence outlook** that blends pilot reports, advisories, and surface winds into a continuous ride index.
- **Route map with a radar precipitation overlay.** See your route, your live aircraft position, and precipitation where coverage is available (NOAA/NWS radar in the U.S., EUMETNET OPERA radar in Europe, and a NASA satellite precipitation estimate elsewhere), with clear data-source labels and coverage notes.
- **Simulated weather-deviation flow.** When weather is detected along your route, ATC gives an advisory and you can request a deviation ("twenty degrees right for weather"), get a suggested heading and a rejoin point, then report clear of weather and be cleared back on course — all simulated, for immersion only.

### Convenience
- **Resume on reconnect.** If your Wi-Fi drops or you relaunch the app, your in-progress flight and conversation pick up where they left off.
- **Clear Flight** button to reset the conversation and start fresh.
- **Auto-discover** to find Infinite Flight on your local network.
- **Multiplayer awareness.** When a human controller is staffing your airport on a multiplayer server, IFATC Companion detects it and **stands by** so it never talks over the real controller.

---

## Getting started

### Free demo — Mock Mode
You can try the whole app for free, with no Infinite Flight and no subscription, using **Mock Mode**. Open the **Diagnostics** tab and turn on Mock Mode to fly a complete simulated flight — every call, the weather flow, and the map — right on your iPhone.

### Flying with Infinite Flight — Live Connected Mode
To connect IFATC Companion to your real Infinite Flight session, you'll need **Live Connected Mode** (a subscription — see below) and Infinite Flight running on another device on the same Wi-Fi network.

**1. Turn on Infinite Flight Connect (on the device running Infinite Flight):**
- Open **Settings → General** in Infinite Flight.
- Enable **"Infinite Flight Connect"**.
- Note the **IP address** shown there.

**2. Connect IFATC Companion (on your iPhone):**
- Open the **Settings** tab.
- Enter the **Host/IP** and **Port** from Infinite Flight (the default port is **10112**), or use **auto-discover** to find it automatically.

Once connected, IFATC Companion reads your live aircraft state and flight plan automatically, and the ATC conversation follows your flight.

---

## Subscription

- **Mock Mode is free** and never requires a subscription — you can explore the full experience offline.
- **Live Connected Mode** — connecting to your live Infinite Flight session — is unlocked with a subscription:
  - **Monthly** or **Annual** options, with pricing shown in your local currency in the app.
  - Manage or cancel anytime in your Apple Account settings.

Live Connected Mode requires Infinite Flight, which is sold separately.

---

## Multiplayer etiquette

**IFATC Companion is NOT staffed (human) ATC.** It's a personal companion for your own situational awareness and immersion.

- It does **not** impersonate live controllers and must never be presented as official or human ATC.
- When a real controller is on frequency, follow that controller — not this app. IFATC Companion detects staffed ATC and steps aside automatically.

Please use it responsibly and in keeping with Infinite Flight community standards.

---

## Good to know

IFATC Companion aims for realism, but a few things are worth setting expectations on:

- ATC is procedural and doesn't model every real-world situation or controller decision.
- Flight-phase detection is automatic and occasionally misjudges a transition.
- Taxi routing and procedure references use built-in datasets that cover the demo airports in detail and fall back to sensible defaults elsewhere.
- Push-to-talk accuracy depends on your device and microphone.

**About the weather:** it comes from free public NOAA sources, so availability depends on NOAA coverage and uptime, some fields are best-effort, and data may be a few minutes old or unavailable offline. Radar precipitation is only true radar where NOAA (U.S.) or EUMETNET OPERA (Europe) provide it; elsewhere it's a lower-confidence satellite estimate, and above roughly ±60° latitude it may be unavailable. **All weather, radar, and deviation features are for simulation, training, and entertainment only — never for real-world flight.**

---

## Your privacy

IFATC Companion is built to be private and self-contained:

- **No accounts, no login, no sign-up.**
- **No ads and no analytics or tracking.**
- **No AI or chatbot** — every call is produced by deterministic, real-world-style phraseology on your device.
- **No cloud servers of ours.** The only network connections are the local link to Infinite Flight on your own Wi-Fi and free public NOAA weather.
- **Push-to-talk stays on your device** — speech recognition uses Apple's on-device framework and makes no calls to us.

See the [Privacy Policy](https://whahn1983.github.io/IFATC-Companion/privacy-policy.html) for details.

---

## Legal

IFATC Companion is proprietary software. Copyright © 2026 H3 Consulting Partners LLC. All rights reserved.

IFATC Companion is an independent app and is **not** affiliated with, endorsed by, or sponsored by Infinite Flight LLC. "Infinite Flight" and all related names and marks are trademarks of their respective owners.
