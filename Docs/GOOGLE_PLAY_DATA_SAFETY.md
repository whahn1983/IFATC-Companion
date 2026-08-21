# Google Play Data safety — IFATC Companion

Prepared from the **actual Android implementation**, not from the marketing copy. Every
claim below is checkable against the code paths named.

## Summary answer

> **This app does not collect or share any user data.**

That is the honest answer, and the sections below are the working that supports it.

---

## Section-by-section answers for the Play Console form

### Data collection and sharing

| Question | Answer |
| --- | --- |
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | Not applicable (no user data is collected). Note separately that all internet requests the app makes are HTTPS — `res/xml/network_security_config.xml` refuses cleartext. |
| Do you provide a way for users to request that their data be deleted? | Not applicable. Nothing is collected, and everything the app stores stays on the device; uninstalling removes it. |

### Every Play data category, and why each is "not collected"

| Category | Collected? | Why |
| --- | --- | --- |
| **Location** (approximate or precise) | **No** | The app declares **no location permission**. Discovery uses UDP broadcast on the local subnet, which needs none. The aircraft's position comes from Infinite Flight over the local network — it is a *simulated* aircraft's position in a video game, not the pilot's own location, and it never leaves the device except as the map bounding box in an Overpass query or an ICAO code in a weather query. |
| **Personal info** (name, email, user IDs, address, phone, race/ethnicity, political or religious beliefs, sexual orientation, other) | **No** | There are no accounts, no sign-in, no sign-up, and no profile. The app never asks for any of it. |
| **Financial info** | **No** | Purchases go through Google Play Billing. The app never sees a payment method, and never handles or stores one. |
| **Health and fitness** | **No** | — |
| **Messages** | **No** | — |
| **Photos and videos** | **No** | The app has no media permission and no picker. |
| **Audio files, music, voice or sound recordings** | **No** | Push-to-talk uses the platform speech recogniser, on-device where the device supports it. Audio is **not recorded to a file, not stored, and not transmitted by this app**. See the caveat below. |
| **Files and docs** | **No** | Only the app's own private storage. |
| **Calendar** | **No** | — |
| **Contacts** | **No** | — |
| **App activity** (interactions, in-app search, installed apps, other user-generated content, other actions) | **No** | No analytics SDK, no event logging, no crash reporter, no attribution SDK. The Diagnostics log stays on the device and is only ever shared if the pilot taps share and chooses where to send it. |
| **Web browsing** | **No** | SimBrief opens in the pilot's own browser via a Custom Tab. The app cannot see what happens there. |
| **App info and performance** (crash logs, diagnostics, other) | **No** | No crash-reporting SDK is integrated. |
| **Device or other IDs** | **No** | The app reads no advertising ID, no Android ID, no IMEI and no build serial. |

---

## Two things a reviewer will reasonably ask about

### Push-to-talk and the microphone

The app declares `RECORD_AUDIO` and requests it **at runtime, only when the pilot first
uses push-to-talk**, with a plain-language rationale. It is used solely to drive Android's
`SpeechRecognizer` so a spoken read-back can be turned into an action.

- Recognition prefers on-device where the platform provides it.
- The app **never writes an audio file** and **never transmits audio itself**.
- The recognised *text* is matched against fixed keyword rules
  (`core/atc/PilotIntentParser.kt`) and then discarded. No AI, no LLM, no network.

**The caveat, stated plainly**: on devices or configurations where Android's speech
recogniser falls back to a cloud service, that processing is the platform's and is
governed by the device's own speech settings and Google's privacy policy — not by this
app. This is why Play's guidance treats it as platform processing rather than collection
by the app, and it is why push-to-talk is off by default and entirely optional.

### The local network

The app declares `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`,
`CHANGE_WIFI_MULTICAST_STATE` and `LOCAL_NETWORK_ACCESS`. These exist to find and talk to
Infinite Flight on the pilot's own Wi-Fi. **Notably absent** is any location permission:
older Wi-Fi APIs used to require one, the modern ones do not, and the app deliberately
uses the ones that do not — so it never asks for location it does not need.

---

## What leaves the device, exactly

| Request | What it carries | To whom |
| --- | --- | --- |
| Aviation weather | ICAO codes and a route bounding box | aviationweather.gov (NOAA) |
| D-ATIS | An ICAO code | datis.clowd.io |
| Radar / precipitation imagery | A map bounding box | NOAA/NWS, NASA GIBS |
| Airport surface data | A small bounding box around one airport | Overpass (OpenStreetMap) |
| Billing | Nothing of the pilot's; Play handles the account | Google Play |

No request carries a name, an email address, an account, a device identifier, an
advertising ID, or the pilot's real-world location. See `Docs/ANDROID_DATA_SOURCES.md`.

---

## What is stored on the device

| Data | Where | Backed up? |
| --- | --- | --- |
| Settings and preferences | DataStore (`ifatc_settings`) | Yes — user preferences are worth restoring |
| Saved flights and the resumable session | App-private files | Yes |
| Custom phraseology profiles | App-private files | Yes |
| Cached airport surface extracts | App-private cache | **No** — re-fetchable |
| Cached weather / imagery | App-private cache | **No** — re-fetchable |
| Diagnostics log | Memory, capped at 500 entries | **No** |
| Cached Play entitlement | DataStore (`ifatc_entitlement`) | **No — deliberately excluded** from cloud backup *and* device transfer, so an entitlement can never travel between accounts |

Backup scope is defined in `res/xml/backup_rules.xml` and
`res/xml/data_extraction_rules.xml`.

---

## Ads, tracking and families policy

- **No ads.** No ad SDK of any kind is integrated.
- **No tracking.** No analytics, no attribution, no cross-app or cross-site tracking, and
  no advertising ID is read.
- The app is not directed at children and does not target a child audience.

---

## Content rating notes

- No violence, no sexual content, no profanity, no gambling, no user-generated content
  and no social features.
- The app simulates air-traffic-control radio for a flight-simulation game. It is
  labelled throughout as **simulation and entertainment only, never for real-world
  aviation**, and it explicitly instructs pilots to yield to real controllers in
  multiplayer.
- Expected rating: **Everyone / PEGI 3**, subject to Play's questionnaire.

---

## Verifying these answers before submission

1. `grep -rn "uses-permission" Android/app/src/main/AndroidManifest.xml` — confirm no
   location permission has crept in.
2. Check `Android/app/build.gradle.kts` dependencies — confirm no analytics, crash
   reporting, attribution or ad SDK has been added.
3. Build a release AAB and run Play Console's own **Data safety** and **App content**
   checks, which will flag any SDK that declares data collection on the app's behalf.
4. Re-read this file whenever a dependency is added. A single SDK can invalidate the
   summary answer above.
