# Background radio chatter, background audio & the live notification

Two opt-in Settings toggles under **Background Radio & Notification**:

1. **Background radio chatter** — plays quiet, static-wrapped, randomly-generated ATC
   traffic on the frequency you're tuned to, and (crucially) keeps the app running in the
   background so live Infinite Flight callbacks no longer stall when you leave the app. It
   comes up only **after your first ATC communication** (you don't hear other traffic on
   frequency before you've checked in) and goes quiet again when the flight **ends** (parked
   at the destination gate) or you **reset** the flight.
2. **Live flight notification** — a live-updating Lock Screen / Dynamic Island card with
   Read Back / Check In buttons. It **requires** background chatter, because the chatter is
   what supplies the continuous audio that keeps the app (and the notification's updates)
   alive in the background.

A third, independent toggle — **Radio voice effect** — runs the main ATC and pilot voices
through a VHF-radio filter (band-pass + soft-clip saturation, via `RadioVoiceProcessor`) so the
same iOS voices sound like real over-the-air transmissions. (Persisted as
`transmissionStaticEnabled`.) When off, the original clean-voice playback path is used
unchanged.

> The pilot's own transmissions are also bracketed with subtle mic-key sounds: a dull,
> low **contact thump** when the pilot presses PTT (`MicKeyEvent.keyUp` →
> `RadioAudioEngine.playKeyClick`, ~32 ms) and a short **receiver-return squelch tail**
> when they release it (`.keyDown` → `playSquelchTail`, ~140 ms: a band-limited noise
> burst that decays to silence with a couple of sparse crackles) — the asymmetric, quieter
> shape real (AM) aviation radios have. After the pilot's final syllable the pump waits
> ~45 ms before the tail, and holds the controller's reply until the tail finishes so it
> never starts over it. Wired via `SpeechService.micKey`.

## Why chatter is the background anchor

iOS suspends a normal app within seconds of backgrounding. The only practical way to keep
the Infinite Flight poll loop (`IFConnectManager`, 1 Hz), phase detection and the ATC
state machine running while backgrounded is the **`audio` background mode** (declared in
`Info.plist`) combined with an app that is *actually producing audio*. A continuous ambient
chatter bed satisfies both — and, unlike playing silent audio to stay alive (which App
Store review guideline 2.5.4 prohibits), it is genuine, purposeful, audible content.

While the chatter runs it holds a `.playback` audio session active; that is also what lets
TTS callouts play in the background and what keeps the Live Activity updating. Because the
chatter is gated on the first ATC communication (and stops at flight end), the background
anchor is active for the working portion of the flight — from the first call-up through the
arrival — which is exactly when the poll loop and notification need to keep running.

`AppModel` owns this lifecycle: `shouldRunAmbientChatter` is `backgroundChatterEnabled && `
`atcCommunicationStarted && !flightHasEnded`, and `updateChatterRunState()` starts/stops the
service whenever any of those change (first `post()` of a controller/pilot line, the `.parked`
transition, a flight reset, or a Settings toggle). On a reconnect the "communication started"
flag is re-derived from the restored transcript so a resumed flight keeps its chatter.

## Components

```
AmbientChatterService (orchestrator, @MainActor)
  ├─ ChatterScriptGenerator   — random, frequency-bounded phraseology (reuses Phonetic + AirlineDatabase)
  ├─ VoiceCatalog             — a curated set of natural English voices (Karen/Daniel/Moira/Rishi/Samantha)
  ├─ AVSpeechSynthesizer.write — renders a chatter line to PCM buffers
  └─ RadioAudioEngine (AVAudioEngine graph)
        ├─ bedSource (generated static)   ─► bedMixer ────────────┐
        ├─ speechPlayer (soft-clipped) ► EQ(bandpass) ► speechMixer ─► mainMixer ─► out
        └─ squelchPlayer (mic-key bursts) ─► squelchMixer ────────┘
```

- **`ChatterScriptGenerator`** is bounded to the tuned facility (`AppModel.currentFacility`):
  Center works ride reports / hand-offs / descend-via-STAR / en-route climbs-descents;
  Ground works taxi and hand-offs; Tower works takeoff / landing / line-up-and-wait;
  Approach works vectors, approaches, speed and the Tower hand-off; Departure works radar
  contact, climbs, headings and the Center hand-off; Clearance reads IFR clearances; Ramp
  works pushback/start/monitor. It is generic over `RandomNumberGenerator` for
  deterministic tests. **Runway references are grounded in the real field:** each cycle
  `AmbientChatterService` sets the generator's runway pools for the airport currently in play
  — the origin while pre-departure/climbing, the destination once descending/arriving (keyed
  off `AppModel.phase` / `currentFacility`). The base pool (`runwayIdents`) is the runway ends
  of the loaded OpenStreetMap surface (`AirportSurfaceCoordinator.cachedRunwayIdents`), so
  Ground never taxis a jet to a runway the field doesn't have, Tower never clears one for a
  nonexistent runway, and Approach's clearances match the arrival airport. When that field's
  **ATIS** is available, `ATISRunwayParser` extracts the runways actually in use and
  `AppModel` reconciles them against the map, filling `departureRunwayIdents` /
  `arrivalRunwayIdents`: Tower then clears takeoffs and Ground taxis out on a **departure**
  runway, while Tower landings and Approach clearances use an **arrival** runway — matching
  how the field is really being run. Until the surface/ATIS load (or with no flight plan) the
  pools are empty and the generator falls back to a plausible random runway.
- **`VoiceCatalog`** limits the chatter's **pilot** voices to a curated set of natural English
  voices — **Karen, Daniel, Moira, Rishi, Samantha** (a good AU/GB/IE/IN/US spread) — using
  whichever are installed, preferring the enhanced/premium variant of each. If none are
  installed it falls back to the general English-human filter (English, non-novelty,
  non-Personal-Voice, non-Eloquence). Each background **pilot** read-back is a fresh random
  pick from that pool (different aircraft sound different). The background **controllers**,
  however, use the **same per-facility voices the user configured for the real controllers**
  (`AppSettings.controllerVoiceID(for:)`, shared with `SpeechService`) — so the simulated
  Ground/Tower/Approach sounds like the controller actually on frequency. The chatter speaks at
  a fixed rate (0.55) independent of the user's main voice-rate setting.
- **`RadioAudioEngine`** generates the static bed (a filtered-noise `AVAudioSourceNode` — no
  bundled asset), gives the chatter voice a gentle soft-clip saturation
  (`applyRadioSaturation`) then band-passes it so it sounds like a real, barely-readable
  transmission — **not** the robotic ring-modulator artifact of `AVAudioUnitDistortion`'s
  speech presets — and fires the pilot mic-key sounds: a dull low **key-down thump** (~32 ms,
  no static) and a **release squelch tail** (~140 ms: band-limited noise that decays to
  silence with a few sparse crackles), both band-limited so they read as radio noise. The bed behaves like a real **squelch**: it is kept well
  below the voice and only opens up (`setTransmitting`) while a call is playing, falling to
  near-silent between calls.

## Interaction with the rest of the app

- **Ducking:** `AppModel` observes `SpeechService.isSpeaking` and ducks the chatter (voice
  to silence, bed to a faint hiss) whenever a real ATC/ATIS/pilot call plays, so the real
  calls stay clear.
- **Push-to-talk:** `AppModel` observes `SpeechRecognitionService.isListening` and pauses
  the chatter (and its engine) around PTT so it never bleeds into the microphone.
- **Frequency switching:** `AppModel` observes `$currentFacility` and calls
  `AmbientChatterService.facilityDidChange(to:)` on every distinct change, passing the **new**
  facility. If the pilot tunes a new frequency **mid-exchange**, the service ends the call that's
  on the air *and drops any read-back tied to it* (`abandonCurrentExchange` cuts the speech player
  via `RadioAudioEngine.stopSpeech()` and unblocks the awaiting `speak()`), then the loop starts a
  fresh exchange appropriate for the new facility — rather than finishing a Tower exchange after
  you've already switched to Ground. A switch in the gap between exchanges needs no interruption:
  the next loop cycle already reads the current facility. The new facility is passed in rather
  than re-read because `@Published` fires in `willSet`, so `currentFacility` still holds the old
  value inside the observer — the same reason the airport-surface observer uses its emitted value.
- **Silent switch:** background chatter overrides the silent switch (it forces `.playback`
  via `SpeechService.forcePlaybackForBackground`), because audible background audio is the
  whole point.
- **Mic-key sounds:** the effect pump (`runProcessedPump`) brackets each pilot call with
  `micKey?(.keyUp)` (a dull PTT key-down thump, before playback) and `micKey?(.keyDown)`
  (a release squelch tail, ~45 ms after the final syllable) — wired to
  `AmbientChatterService.micKey` → the radio engine. The pump then holds the controller's
  reply until the tail finishes. Kept subtle and asymmetric to match real AM aviation radios.

## Live notification

`LiveActivityController` starts/updates/ends an ActivityKit `Activity`. The content
(`CompanionActivityAttributes.ContentState`) carries phase, tuned controller, altitude,
heading, ground speed, callsign, route, the next controller, a weather advisory flag,
which of the Read Back / Check In buttons apply, and an `asOf` timestamp. Updates are
throttled (2 s) and pushed from the telemetry loop and on every new call. The buttons are
`LiveActivityIntent`s that run in the app process and call the same actions as the
on-screen buttons.

### Keeping the notification fresh (staleness handling)

There is no push server (the app is local-only), so the notification is only as fresh as
the last state the app pushed — which is only as fresh as the last telemetry it received.
Two mechanisms keep it honest and current while backgrounded:

- **Telemetry-stall watchdog (`AppModel`).** When the screen locks, Infinite Flight's
  Connect socket can go quiet while still looking "connected" — the poll loop keeps ticking
  but every state read comes back empty, so the notification would freeze on its last
  numbers. While a live flight's background-audio anchor is running, a 5 s watchdog watches
  the time since the last *usable* telemetry snapshot (`lastUsableTelemetryAt`, advanced in
  `handle(state:)`); if it exceeds ~12 s it forces a reconnect (`connect.disconnect()` +
  `startLive()`) — the same recovery `handleReturnToForeground()` does, applied proactively
  rather than waiting for the user to reopen the app. A 20 s cooldown between forced
  reconnects keeps a slow handshake from being read as a fresh stall, and the mock feed
  (which never stalls) is excluded. The pure decision is `telemetryStallDetected(now:)`.
- **`staleDate` + self-updating freshness line (widget).** Every push sets the activity's
  `staleDate` to `asOf + 60 s`. If no push arrives within that window (e.g. the app is fully
  suspended and the watchdog can't run), iOS flips `context.isStale`; the widget then dims
  the telemetry, greys the Dynamic Island pill, and shows "Reconnecting…". While fresh it
  shows a self-updating "Updated Xm ago" line — a relative `Text` that refreshes on the Lock
  Screen *without* a new push — so the age of the data is always visible instead of stale
  numbers reading as live.

The widget UI lives in a separate extension target — see
[LiveActivitySetup.md](LiveActivitySetup.md).

## Costs & caveats

- Background audio + 1 Hz polling uses meaningfully more battery — hence opt-in.
- `AVSpeechSynthesizer.write(toBufferCallback:)` doesn't work with every voice; the service
  falls back to a quiet direct utterance for any voice that can't render to buffers.
- Background Wi-Fi to Infinite Flight is less reliable with the screen **locked** than with
  another app foregrounded; active audio helps keep the radio up, but verify on-device.
- All chatter is generated locally — no AI, no network, no bundled audio.
